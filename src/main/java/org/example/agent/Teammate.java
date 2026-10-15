package org.example.agent;

import org.example.entity.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个常驻队友：一条线程，一个独立的 Agent，一个绑在身上的计划闸门。
 *
 * 生命周期：
 *
 *   runTurn(spawn 的 prompt)  →  report()  →  waitForWork() ⇄ …
 *                                                  │ 收到 shutdown
 *                                                  ↓
 *                                     finally 交还任务 + 从活跃表摘掉
 *
 * 和 Agent 的关系是【持有】——Agent 是通用引擎，队友只是把它配好
 * （固定 prompt、窄工具表、关压缩）。所以这里一行 ReAct 循环都不用写。
 */
public class Teammate implements Runnable {

    public enum State {

        /** 正在跑一轮 */
        WORKING("working"),

        /** 手上没活，在等 */
        IDLE("idle"),

        /** 手上压着任务，但计划还没批下来 */
        WAITING_APPROVAL("waiting_approval");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 空闲时的轮询间隔。抄原书的 IDLE_SCAN_INTERVAL */
    private static final long IDLE_MS = 2000;

    private final String name;
    private final Agent agent;
    private final PlanGate gate;
    private final String initialPrompt;
    private final Task initialTask;

    private final TaskStore taskStore = TaskStore.getInstance();
    private final MessageBus bus = MessageBus.getInstance();
    private final TeamRegistry registry = TeamRegistry.getInstance();

    /** 给 list_teammates 看的。队友自己写，Lead 读 */
    private volatile State state = State.IDLE;

    /** 当前绑的任务。null = 手上没活，可以去任务板捡 */
    private volatile String boundTaskId;

    public Teammate(String name, Agent agent, PlanGate gate,
                    String initialPrompt, Task initialTask) {
        this.name = name;
        this.agent = agent;
        this.gate = gate;
        this.initialPrompt = initialPrompt;
        this.initialTask = initialTask;
    }

    public String name() {
        return name;
    }

    /** 谁调：review_plan —— 拿到队友之后要摸到他的通行证去 decide */
    public PlanGate gate() {
        return gate;
    }

    /** 谁调：list_teammates */
    public State state() {
        return state;
    }

    // ==================== 线程主体 ====================

    @Override
    public void run() {
        // 队友和 Lead 有一样的权限：能弹窗向用户要授权。cron 线程没有这个标记
        Permission.markInteractive();
        try {
            if (initialTask != null) {
                bindTask(initialTask);
            }

            String work = describeInitialWork();
            while (work != null) {
                state = State.WORKING;
                Message response = agent.runTurn(work);
                report(response);
                work = waitForWork();
            }
        } catch (Exception e) {
            sendError("队友线程异常退出: " + e);
        } finally {
            handBackWork();
            registry.unregister(name);
            // 转存过的大结果和完整历史留档，人死了就没人看了
            agent.cleanupTempFiles();
            System.out.println("  [teammate] " + name + " finished");
        }
    }

    /** spawn 那条消息：Lead 交代的 prompt，附上点名的任务 */
    private String describeInitialWork() {
        if (initialTask == null) {
            return initialPrompt;
        }
        return initialPrompt
             + "\n\n[Assigned task " + initialTask.id() + "] "
             + initialTask.subject() + "\n" + initialTask.description();
    }

    /**
     * 等下一个活。三件事按顺序看，都没有就睡 2 秒再来。
     *
     * @return 该干的一段话；null = 收到 shutdown，该退出了
     */
    private String waitForWork() {
        while (true) {
            // ① 收件箱
            List<BusMessage> inbox = bus.consume(name);
            if (!inbox.isEmpty()) {
                List<String> lines = new ArrayList<>();
                for (BusMessage message : inbox) {
                    if (message.type() == BusMessage.Type.SHUTDOWN) {
                        return null;
                    }
                    lines.add("[" + message.type().label() + " from " + message.from()
                              + "] " + message.content());
                }
                if (!lines.isEmpty()) {
                    return String.join("\n", lines);
                }
                // 空消息 / 都是些不叫醒人的通知 → 继续往下走
            }

            // ② 手上没活才去任务板捡。在等审批时 boundTaskId 不为 null，所以不会看任务板
            if (boundTaskId == null) {
                Task task = pickUpWork();
                if (task != null) {
                    bindTask(task);
                    return "[Auto-claimed task " + task.id() + "] "
                         + task.subject() + "\n" + task.description();
                }
            }

            // ③ 都没有 → 睡一会儿
            try {
                Thread.sleep(IDLE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * 干完一段活之后的收尾。两种情形分开：
     *   在等审批 → 什么都不做。活没干完，不该报 result；任务也不能释放
     *   其它     → 报一段结果给 Lead，顺便看看任务是不是完成了（完成就解绑）
     */
    private void report(Message response) {
        if (gate.awaiting()) {
            state = State.WAITING_APPROVAL;
            return;
        }
        state = State.IDLE;
        unbindIfDone();

        String summary = response.getContent();
        if (summary != null && !summary.isBlank()) {
            bus.send(name, MessageBus.LEAD, summary, BusMessage.Type.RESULT);
        }
    }

    // ==================== 绑定 / 解绑 ====================

    /** 任务到手：记下来 + 通行证归零。换任务要重新申请计划，就是靠这句 */
    private void bindTask(Task task) {
        boundTaskId = task.id();
        gate.bind();
    }

    /**
     * 任务干完了就解绑。查一次当前任务的状态（读一个文件），
     * 比扫整个任务目录便宜。解绑之后 waitForWork 才允许去捡新活。
     */
    private void unbindIfDone() {
        if (boundTaskId == null) {
            return;
        }
        Task mine = taskStore.load(boundTaskId);
        if (mine != null && mine.status() == Task.Status.COMPLETED) {
            boundTaskId = null;
        }
    }

    // ==================== 任务板 ====================

    /** 去任务板捡活：找第一个能开工的认领掉 */
    private Task pickUpWork() {
        for (Task task : taskStore.listAll()) {
            if (task.status() != Task.Status.PENDING
                || task.owner() != null
                || !taskStore.incompleteDependencies(task).isEmpty()) {
                continue;
            }
            try {
                return taskStore.claim(task.id(), name);
            } catch (IllegalStateException e) {
                // 筛的时候没人认领，claim 的瞬间被另一个队友抢走了。继续找下一个
            }
        }
        return null;
    }

    /** 线程退出时把手上的任务交还任务板。不退的话它永远卡在 in_progress，别人抢不走 */
    private void handBackWork() {
        if (boundTaskId == null) {
            return;
        }
        Task mine = taskStore.load(boundTaskId);
        if (mine == null || mine.status() != Task.Status.IN_PROGRESS) {
            return;                                   // 干完了 / 文件没了
        }
        if (!name.equals(mine.owner())) {
            return;                                   // 不是我的，别动
        }
        taskStore.save(mine.released());
    }

    // ==================== 报错 ====================

    private void sendError(String message) {
        try {
            bus.send(name, MessageBus.LEAD, message, BusMessage.Type.MESSAGE);
        } catch (Exception ignored) {
            // 连报错都发不出去（比如名字非法），只能作罢
        }
    }
}
