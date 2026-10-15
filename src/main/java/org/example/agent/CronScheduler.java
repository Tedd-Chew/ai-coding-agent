package org.example.agent;

import org.example.entity.Message;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 定时调度器（单例）：让 agent 在没人说话的时候也能自己动起来。
 *
 * 三条主线：
 *
 * 1. 【时间驱动】到 s11 为止所有触发都是事件（用户输入 / 工具结果 / 后台通知），
 *    这里是第一个"到点就发生"的源。
 *
 * 2. 【两条常驻线程，不能合并】poller 每秒匹配一次时间；队列线程负责在 agent 空闲时
 *    跑一轮。队列线程一旦开始跑 agent 就阻塞在那一轮里（可能几十分钟），
 *    合并的话这段时间没人轮询，落在窗口里的分钟级任务会全部错过。
 *
 * 3. 【at-least-once 交付】任务到点先入队，跑完一轮才算交付，中途失败退回队列。
 *    pendingDelivery 落盘，所以进程死在半路时，重启后这些任务会重新入队。
 *
 * 锁只有一把 cronLock，管全部共享状态（任务表、队列、落盘）。
 * 和 Agent 的回合锁是两把独立的锁，且【永远不会嵌套】：deliverDue 先把队列取空、
 * 释放 cronLock，再去抢回合锁——反过来的顺序会死锁。
 */
public class CronScheduler {

    /**
     * 轮询间隔。cron 只到分钟精度，每秒扫一次有 59 次余量。
     * 不能太长：队列线程在跑一轮 agent 时 poller 是唯一还在看时间的线程。
     */
    private static final long POLL_INTERVAL_MS = 1_000;

    /**
     * 队列线程的检查间隔。它同时也是"抢不到回合锁之后多久重试"的间隔——
     * 用户在忙时，这个线程每 200ms 来敲一次门，敲不开就回去。
     */
    private static final long QUEUE_INTERVAL_MS = 200;

    /** 停止时等线程收尾的上限 */
    private static final long STOP_JOIN_TIMEOUT_MS = 1_000;

    /** lastFired 的格式，精度到分钟——比这更细没有意义 */
    private static final DateTimeFormatter MINUTE_MARKER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final int ID_RANDOM_LENGTH = 8;
    private static final int ID_RETRY_LIMIT = 10;

    private static volatile CronScheduler instance;

    /** 保护 scheduledJobs / cronQueue / 所有落盘操作。落盘必须在锁里，否则写出的是个撕裂的快照 */
    private final ReentrantLock cronLock = new ReentrantLock();

    /** 插入顺序 = 触发顺序，list_crons 的输出才稳定 */
    private final Map<String, CronJob> scheduledJobs = new LinkedHashMap<>();

    /** 已到期、等着交给模型的任务。取走即清空（consumeDue），调用方负责 ack 或 restore */
    private final List<CronJob> cronQueue = new ArrayList<>();

    /** 常驻线程句柄，stop() 时 interrupt + join */
    private final List<Thread> runtimeThreads = new ArrayList<>();

    /** start() 时注入，之后只读。靠 Thread.start() 的 happens-before 保证可见性 */
    private Agent agent;

    private boolean started;

    private CronScheduler() {}

    public static CronScheduler getInstance() {
        if (instance == null) {
            synchronized (CronScheduler.class) {
                if (instance == null) {
                    instance = new CronScheduler();
                }
            }
        }
        return instance;
    }

    // ==================== 生命周期 ====================

    /**
     * 载入 durable 任务 + 起两条常驻线程。重复调用无副作用。
     *
     * @param agent 调度回合用的 agent。必须是主线程那一个——
     *              共用同一个 history，定时任务才看得见之前聊过什么
     */
    public synchronized void start(Agent agent) {
        if (started) {
            return;
        }
        this.agent = agent;

        loadDurable();

        runtimeThreads.add(ThreadPools.startDaemon("cron-poller", this::pollLoop));
        runtimeThreads.add(ThreadPools.startDaemon("cron-queue", this::queueLoop));
        started = true;
    }

    /** 停线程。daemon 线程本来就会随进程消失，这里只是走得干净一点 */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        for (Thread thread : runtimeThreads) {
            thread.interrupt();
        }
        for (Thread thread : runtimeThreads) {
            try {
                thread.join(STOP_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        runtimeThreads.clear();
        started = false;
    }

    // ==================== 增删查 ====================

    /**
     * 建一个定时任务。
     *
     * @throws IllegalArgumentException 表达式不合法（消息里带是哪一段错）或 prompt 为空
     */
    public CronJob schedule(String cron, String prompt, boolean recurring, boolean durable) {
        // 借 CronExpression 的校验，顺带拿到规范化后的表达式（去掉多余空白）
        CronExpression parsed = CronExpression.parse(cron);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }

        cronLock.lock();
        try {
            CronJob job = new CronJob(newId(), parsed.expression(), prompt.trim(),
                recurring, durable, false, null);
            scheduledJobs.put(job.id(), job);
            try {
                persist();
            } catch (RuntimeException e) {
                scheduledJobs.remove(job.id());     // 盘上没写成功，内存里也别留
                throw e;
            }
            System.out.println("  [cron] scheduled " + job.render());
            return job;
        } finally {
            cronLock.unlock();
        }
    }

    public List<CronJob> listAll() {
        cronLock.lock();
        try {
            return new ArrayList<>(scheduledJobs.values());
        } finally {
            cronLock.unlock();
        }
    }

    /**
     * 撤销。已经在队列里等交付的也一并摘掉——不然它会在一轮跑完之后
     * 回来 ack 一个已经不存在的任务（acknowledge 里那种情况会被忽略，但队列白跑一趟）。
     *
     * @throws IllegalArgumentException 任务不存在
     */
    public CronJob cancel(String id) {
        cronLock.lock();
        try {
            CronJob job = scheduledJobs.get(id);
            if (job == null) {
                throw new IllegalArgumentException("定时任务不存在: " + id);
            }
            Map<String, CronJob> jobsBefore = new LinkedHashMap<>(scheduledJobs);
            List<CronJob> queueBefore = new ArrayList<>(cronQueue);

            scheduledJobs.remove(id);
            cronQueue.removeIf(queued -> queued.id().equals(id));
            try {
                persist();
            } catch (RuntimeException e) {
                restoreSnapshot(jobsBefore, queueBefore);
                throw e;
            }
            System.out.println("  [cron] cancelled " + id);
            return job;
        } finally {
            cronLock.unlock();
        }
    }

    // ==================== 到期检查 ====================

    /**
     * 扫一遍任务表，把到点的入队。poller 每秒调一次；测试也可以直接调它，
     * 不用等真实时间流过去。
     */
    public void pollDue(LocalDateTime now) {
        String marker = now.format(MINUTE_MARKER);
        cronLock.lock();
        try {
            for (CronJob job : new ArrayList<>(scheduledJobs.values())) {
                try {
                    // 已经在等交付（pendingDelivery）、或者这一分钟已经触发过了，都跳过
                    if (job.pendingDelivery() || marker.equals(job.lastFired())) {
                        continue;
                    }
                    if (CronExpression.parse(job.cron()).matches(now)) {
                        enqueueDue(job, marker);
                    }
                } catch (RuntimeException e) {
                    // 一个任务出问题不能连累整张表。表达式在 schedule/load 时都校验过，
                    // 走到这里基本只剩落盘失败
                    System.out.println("  [cron] 无法入队 " + job.id() + ": " + e.getMessage());
                }
            }
        } finally {
            cronLock.unlock();
        }
    }

    /**
     * 到期 → 打标记 → 落盘 → 入队。
     *
     * 顺序不能反：先落盘再入队。否则落盘失败时，内存里会留下一个"轮询线程认为已交付、
     * 磁盘上却还是旧值"的任务，重启后它会再被投一次。
     */
    private void enqueueDue(CronJob job, String marker) {
        CronJob updated = job.withPendingDelivery(true).withLastFired(marker);
        scheduledJobs.put(job.id(), updated);
        try {
            persist();
        } catch (RuntimeException e) {
            scheduledJobs.put(job.id(), job);       // 回滚
            throw e;
        }
        cronQueue.add(updated);
        System.out.println("  [cron] due " + updated.id() + ": " + updated.prompt());
    }

    // ==================== 交付 ====================

    /**
     * 取走全部到期任务并清空队列。取走即清空，所以调用方【必须】接着调 acknowledge 或 restore，
     * 否则这批任务就悬在内存里了（盘上的 pendingDelivery 还是 true，重启会重新投——兜底在）。
     */
    public List<CronJob> consumeDue() {
        cronLock.lock();
        try {
            List<CronJob> due = new ArrayList<>(cronQueue);
            cronQueue.clear();
            return due;
        } finally {
            cronLock.unlock();
        }
    }

    /**
     * 交付成功：模型收到了。
     *
     * - 周期任务：只清 pendingDelivery，等下一次匹配
     * - 一次性任务：整个删掉
     *
     * 等在 ack 期间被 cancel 掉的任务直接忽略（它已经不在表里了，说明用户不想要了）。
     */
    public void acknowledge(List<CronJob> delivered) {
        cronLock.lock();
        try {
            Map<String, CronJob> jobsBefore = new LinkedHashMap<>(scheduledJobs);
            List<CronJob> queueBefore = new ArrayList<>(cronQueue);

            for (CronJob job : delivered) {
                CronJob current = scheduledJobs.get(job.id());
                if (current == null) {
                    continue;
                }
                if (current.recurring()) {
                    scheduledJobs.put(current.id(), current.withPendingDelivery(false));
                } else {
                    scheduledJobs.remove(current.id());
                }
            }
            try {
                persist();
            } catch (RuntimeException e) {
                restoreSnapshot(jobsBefore, queueBefore);
                throw e;
            }
        } finally {
            cronLock.unlock();
        }
    }

    /**
     * 交付失败：放回队列，等下一轮重投。
     *
     * 【这里不用落盘】，因为 consumeDue 只清了内存队列，任务的 pendingDelivery 一直是 true
     * ——磁盘上本来就是"未交付"的状态，放回队列不改变任何持久化状态。
     *
     * 注意一个和原项目的差异：原书在"首次 LLM 调用成功"就 ack，我们在一轮跑完之后才 ack。
     * 差别在失败场景：如果模型其实已经看到了、是后续某一轮工具调用挂了，我们会重投一次。
     * 那是 at-least-once 的定义，不是 bug——宁可重复，不可丢失。
     */
    public void restore(List<CronJob> delivered) {
        cronLock.lock();
        try {
            Set<String> queuedIds = cronQueue.stream()
                .map(CronJob::id)
                .collect(Collectors.toSet());
            for (CronJob job : delivered) {
                CronJob current = scheduledJobs.get(job.id());
                if (current == null) {
                    continue;       // 等交付期间被 cancel 了
                }
                if (queuedIds.add(current.id())) {
                    cronQueue.add(current);
                }
            }
        } finally {
            cronLock.unlock();
        }
    }

    // ==================== 两条常驻线程 ====================

    /**
     * 调度线程：每秒看一眼有没有到点的。
     *
     * 【不能和队列线程合并】——队列线程一旦跑起 agent 就阻塞在那一轮里，
     * 合并的话这段时间没人轮询，落在窗口里的分钟级任务全部错过。
     */
    private void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;     // 被打断就是让停，直接退
            }
            try {
                pollDue(LocalDateTime.now());
            } catch (RuntimeException e) {
                System.out.println("  [cron] poll error: " + e.getMessage());
            }
        }
    }

    /**
     * 队列线程：有到期任务、且 agent 空闲时，跑一轮。
     *
     * 抢不到回合锁就 continue——不是"走了不回来"，是回 while 顶部，200ms 后再敲一次门。
     * 任务一直在 cronQueue 里躺着，丢不了。
     */
    private void queueLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(QUEUE_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                deliverDue();
            } catch (RuntimeException e) {
                System.out.println("  [cron] 交付出错: " + e.getMessage());
            }
        }
    }

    /** 取一批到期任务，交给 agent 跑一轮 */
    private void deliverDue() {
        List<CronJob> due = consumeDue();
        if (due.isEmpty()) {
            return;
        }

        String prompt = due.stream()
            .map(CronJob::deliveryText)
            .collect(Collectors.joining("\n\n"));

        Message response;
        try {
            // 注意这里 cronLock 已经放掉了 —— 两把锁永不嵌套，否则就是死锁的温床
            response = agent.tryRunTurn(prompt);
        } catch (RuntimeException e) {
            System.out.println("  [cron] 定时回合失败，退回队列等重投: " + e.getMessage());
            restore(due);
            return;
        }
        if (response == null) {
            // null 是"没抢到回合锁"，不是失败 —— 用户在忙，让路，原样退回队列
            restore(due);
            return;
        }

        acknowledge(due);
        System.out.println("  [cron] delivered "
            + due.stream().map(CronJob::id).collect(Collectors.joining(", ")));
        // 调度回合【不抽记忆】。它的轮后只有"打印"这一件事，而 CronScheduler 自己就能干。
        //
        // 为什么不抽：
        //   1. 内容重复。*/1 的任务每分钟产出"⏱ 12:50:35"这种程序生成的文本，
        //      抽进记忆库就是永久噪音
        //   2. 烧钱。每轮抽取 + 可能的合并 = 2 次 LLM 调用。定时任务无人值守地重跑，
        //      */1 就是通宵每分钟一次
        //   3. 不需要特殊处理——这批消息躺在 history 末尾（extract 取末尾 12 条），
        //      用户下次说话时会被【顺带抽走】，而且那时候模型还多看到"用户怎么反应"，
        //      判断得更准
        System.out.println("Assistant: " + response.getContent());
    }

    // ==================== 持久化 ====================

    /** 落盘。调用方必须已经持有 cronLock */
    private void persist() {
        CronStore.getInstance().save(new ArrayList<>(scheduledJobs.values()));
    }

    /**
     * 载入 durable 任务。
     *
     * pendingDelivery 为 true 的那批要【重新入队】——它们上次是"已到期但没交付"，
     * 进程死在半路。这就是 at-least-once 跨重启的那一半。
     */
    private void loadDurable() {
        List<CronJob> loaded = CronStore.getInstance().load();
        cronLock.lock();
        try {
            int requeued = 0;
            for (CronJob job : loaded) {
                scheduledJobs.put(job.id(), job);
                if (job.pendingDelivery()) {
                    cronQueue.add(job);
                    requeued++;
                }
            }
            if (!loaded.isEmpty()) {
                System.out.println("  [cron] loaded " + loaded.size() + " durable job(s)"
                    + (requeued == 0 ? "" : ", 其中 " + requeued + " 个待重投"));
            }
        } finally {
            cronLock.unlock();
        }
    }

    /** 落盘失败后的回滚。整体换回快照——任务表就几条，比逐条反向操作好推理得多 */
    private void restoreSnapshot(Map<String, CronJob> jobs, List<CronJob> queue) {
        scheduledJobs.clear();
        scheduledJobs.putAll(jobs);
        cronQueue.clear();
        cronQueue.addAll(queue);
    }

    private String newId() {
        for (int attempt = 0; attempt < ID_RETRY_LIMIT; attempt++) {
            String id = CronJob.ID_PREFIX + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, ID_RANDOM_LENGTH);
            if (!scheduledJobs.containsKey(id)) {
                return id;
            }
        }
        throw new IllegalStateException("连续 " + ID_RETRY_LIMIT + " 次生成的 ID 都已存在");
    }
}
