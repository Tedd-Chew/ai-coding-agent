package org.example.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 消息总线（单例）：每个 agent 一个收件箱，进程内通信。
 *
 * 【为什么不能让所有 agent 共享一个 messages 数组】
 * 那样一个队友的工具结果会进入另一个队友的推理上下文——甲读了个文件，乙的上下文里
 * 凭空多出一段文件内容。所以每人一份 history，通信必须走总线。
 *
 * 【为什么是纯内存，不落盘】
 * 原书把文件当通信媒介（`.mailboxes/&lt;name&gt;.jsonl`），那是因为它在"可能有另一个
 * Harness 进程读同一个目录"的框架下设计。我们不是：队友都在同一个进程里，
 * 没有第二个进程。
 *
 * 更关键的是——消息【没有需要跨重启恢复的状态】。对比 s12 的 cron：
 * 任务必须落盘，因为进程死了重启后要靠 `pendingDelivery` 知道"这条还没交付"。
 * 而队友全死了，消息重投给谁？没有这个需求。
 *
 * 一旦落盘，就得回答"写失败了怎么办""文件损坏了怎么办""以内存为准还是以文件为准"——
 * 这些问题现在一个都不存在。
 *
 * 【为什么用内存队列 + consume/restore，而不是"读即删除"】
 * 取走之后如果发现处理不了（最典型的是抢不到回合锁，用户正在跑），消息得放回去。
 * 对队列就是 restore，很自然；对文件就是再 append 一遍，很别扭。
 * 这套 consume / restore 的形状和 s12 的 CronScheduler 一致。
 *
 * 【并发】：一把 lock 管全部（队列 + seq）。唤醒靠轮询 200ms，不用 Condition
 * ——和 CronScheduler 保持一致，少一个概念，代价是 IDLE 时最多多等 200ms（无所谓）。
 */
public class MessageBus {

    /** 协调者的名字。所有队友都往这个收件箱汇报，所有指令也从这儿发出去 */
    public static final String LEAD = "lead";

    private static volatile MessageBus instance;

    /** 保护 inboxes 和 seq */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 每人一个队列。
     * 用 Deque 而不是 List，是为了 restore 能把消息塞回【队首】——顺序不能乱。
     */
    private final Map<String, Deque<BusMessage>> inboxes = new HashMap<>();

    private final AtomicLong seq = new AtomicLong();

    /** 日志里正文的截断长度。只为了控制台好读，不影响消息本身 */
    private static final int LOG_PREVIEW_CHARS = 80;

    private MessageBus() {}

    public static MessageBus getInstance() {
        if (instance == null) {
            synchronized (MessageBus.class) {
                if (instance == null) {
                    instance = new MessageBus();
                }
            }
        }
        return instance;
    }

    /**
     * 名字只允许字母、数字、下划线、连字符，长度 1~32。
     *
     * 名字是【模型给的参数】（spawn_teammate 的 name、send_message 的 to）。
     * 这里不落盘，所以不是防路径遍历——是为了别让 "alice; rm -rf /" 这种名字
     * 流进任务表的 owner、日志和提示词里。
     */
    public static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,32}");
    }

    // ==================== 收发 ====================

    /** 发一条消息：进对方的收件箱，并打一行日志 */
    public void send(String from, String to, String content, BusMessage.Type type) {
        if (!isValidName(from) || !isValidName(to)) {
            throw new IllegalArgumentException("非法的收发件人名字: " + from + " -> " + to);
        }
        BusMessage message = new BusMessage(from, to, content, type, seq.incrementAndGet());
        lock.lock();
        try {
            inboxes.computeIfAbsent(to, name -> new ArrayDeque<>()).addLast(message);
        } finally {
            lock.unlock();
        }
        System.out.println("  [bus] " + from + " → " + to + " (" + type.label() + ") "
            + preview(content));
    }

    /**
     * 取走某人的全部消息，并清空它的收件箱。收件箱空时返回空列表。
     *
     * 取走即清空——调用方【必须】接着消费掉，或者处理不了时 restore 回去。
     * 拿到手不管的话，这批消息就没了。
     */
    public List<BusMessage> consume(String name) {
        lock.lock();
        try {
            Deque<BusMessage> queue = inboxes.get(name);
            if (queue == null || queue.isEmpty()) {
                return List.of();
            }
            List<BusMessage> batch = new ArrayList<>(queue);
            queue.clear();
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 把一批消息放回【队首】，保持原来的先后顺序。
     *
     * 和 s12 的 CronScheduler.restore 同一个用途：拿到了却发现处理不了
     * （最典型的是抢不到回合锁，用户正在跑），原样退回去，下一轮再来。
     *
     * 不做去重——调用方保证一批只放回一次。
     */
    public void restore(String name, List<BusMessage> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            Deque<BusMessage> queue = inboxes.computeIfAbsent(name, n -> new ArrayDeque<>());
            // 倒着 addFirst：最后一条先进去、第一条最后进去 → 队首顺序和原来一致
            for (int i = batch.size() - 1; i >= 0; i--) {
                queue.addFirst(batch.get(i));
            }
        } finally {
            lock.unlock();
        }
    }

    /** 收件箱里还有几条（只看不取）。给工具和调试用 */
    public int pending(String name) {
        lock.lock();
        try {
            Deque<BusMessage> queue = inboxes.get(name);
            return queue == null ? 0 : queue.size();
        } finally {
            lock.unlock();
        }
    }

    // ==================== 内部 ====================

    private static String preview(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replace('\n', ' ').strip();
        return flat.length() <= LOG_PREVIEW_CHARS
            ? flat
            : flat.substring(0, LOG_PREVIEW_CHARS) + "…";
    }
}
