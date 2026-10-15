package org.example;

import org.example.agent.*;
import org.example.agent.tools.*;
import org.example.entity.Message;
import org.example.service.ChatService;
import org.example.service.impl.ChatServiceImpl;
import org.example.ui.ChatWindow;
import org.example.ui.SwingPrompt;
import org.example.ui.UiStream;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Main {

    /** 主循环没输入时的空转间隔（毫秒）。抄原书 s13 主循环的 0.25 秒 */
    private static final long POLL_MS = 250;

    public static void main(String[] args) throws InterruptedException {

        // 0. ★ 告诉权限系统"这条线程能弹交互审批"。
        //    必须在起任何后台线程【之前】设好——调度线程会跑完整的 agent 回合，
        //    它没有这个标记，走到 Gate 3 就是失败关闭。
        //    s13 起队友线程时要同样调一次：队友该有和 Lead 一样的权限。
        Permission.markInteractive();

        // 1. 基础服务
        ChatService chatService = ChatServiceImpl.getInstance();
        HookRegistry hookRegistry = HookRegistry.getInstance();
        MessageBus bus = MessageBus.getInstance();

        // 1.5 ★ 窗口。用户点"发送"→ 内容进 inputQueue → 主循环从队列拿。
        //     窗口不知道 Agent 的存在，主循环也不知道窗口的存在，两边只靠这条队列耦合。
        //     ← 关键：没有任何线程需要"等用户敲键盘"了，所以"抢输入"这个问题根本不存在。
        BlockingQueue<String> inputQueue = new LinkedBlockingQueue<>();
        ChatWindow window = new ChatWindow(inputQueue);

        // 1.6 ★ 把 System.out 改道成【终端 + 窗口】两份。
        //     全项目那几十处 System.out.println 一行都不用动，窗口里就能看到。
        //     必须赶在下面任何初始化打印【之前】，否则前面几行会漏掉。
        PrintStream terminalOut = System.out;
        System.setOut(new PrintStream(new UiStream(terminalOut, window::appendLine),
            true, StandardCharsets.UTF_8));
        // stderr 也接过来。不接的话异常堆栈只打在【启动它的那个终端 / IDE 控制台】里，
        // 窗口一个字都看不到——"能打字、没人理"那次就是这么白排查了半天
        PrintStream terminalErr = System.err;
        System.setErr(new PrintStream(
            new UiStream(terminalErr, line -> window.appendLine("[err] " + line)),
            true, StandardCharsets.UTF_8));

        window.show();

        // 2. ★ Main 注册默认 hook（Agent 不知道这些）。
        //    权限"怎么问用户"也一并交给它——换实现类就换一种交互介质
        HookConfig.registerDefaults(hookRegistry, new SwingPrompt(window.component()));

        // 2.5 加载 skill 目录（扫描 skills/*/SKILL.md）
        SkillLoader.init(Paths.get("skills"));

        // 3. Memory（Main 拥有）：一条记忆一个文件，MEMORY.md 是索引
        MemoryStore memoryStore = MemoryStore.init(Paths.get(".memory"));
        MemoryExtractor memoryExtractor = new MemoryExtractor(chatService, memoryStore);

        // 4. ★ 数据目录。工具清单不在这里注册了——由 ToolManager 按角色组
        TaskStore.init(Paths.get(".tasks"));    // s10 任务系统（依赖图 + 持久化）
        CronStore.init(Paths.get("."));         // s12 定时任务（整张表一个文件，原子替换）

        // 4.5 ★ 收拾上次的残局。队友线程是 daemon，System.exit 不给它们跑 finally 的机会，
        //     所以"点 ×"以外的退出方式（任务管理器 / IDE 停止 / 崩溃 / 断电）里，
        //     Teammate 的 handBackWork 和 cleanupTempFiles 根本不会执行。
        //     启动时收拾是唯一 100% 覆盖的——进程刚起来，这些一定是上次留下的
        TaskStore.getInstance().recoverAbandoned();   // in_progress 全部退回任务板
        ContextCompactor.cleanupAll();                // 清空两个压缩落盘目录

        // 5. ★ 父 Agent。工具表来自 ToolManager.forLead —— "谁有什么工具"全在那三个方法里
        //    maxIter=500：纯安全网，避免参数不对时无限打转烧掉几分钟
        Agent agent = new Agent(chatService,
            ToolManager.forLead(chatService, hookRegistry), hookRegistry, 500, MessageBus.LEAD);

        // 7. ★ Main 只给基础角色描述；system prompt 由 SystemPromptBuilder 每轮拼
        //    （基础描述 + skill 目录 + 记忆目录 + 当轮选出的相关记忆）
        SystemPromptBuilder.init(chatService, memoryStore,
            "你是用户的简历分析助手。对复杂任务先用 create_task 列计划，再逐步执行。"
          + "开始一项多步骤工作前，先用 list_tasks 看看有没有未完成的任务"
          + "——它们可能是上次会话留下的。");

        // 7.5 ★ s12: 起调度线程——载入 durable 任务 + 拉两条常驻线程（poller / 队列）
        CronScheduler.getInstance().start(agent);

        // 8. ★ Main 自己的循环
        try {
            while (true) {
                // 兜在【循环内】而不是循环外——循环外的 catch 等于退出。
                // 只兜 RuntimeException：InterruptedException 要传出去，那是退出信号。
                // 这里没兜的那个洞就是 ISSUES 一-2：一个 RejectedExecutionException
                // 把主线程带走，可窗口还挂着（EDT 是非 daemon），于是"能打字、没人理"。
                try {
                    // ★ s13: 先看团队总线。队友的消息必须能把你叫醒——
                    //   不然它们躺在收件箱里，要等用户敲一下回车才被看见。
                    //   这正是原书主循环 select(stdin, 0.25) 之外那半圈在干的事。
                    List<BusMessage> teamEvents = bus.consume(MessageBus.LEAD);
                    if (!teamEvents.isEmpty()) {
                        System.out.println("\n[wake: " + teamEvents.size() + " 条团队事件]");
                        agent.runTurn(formatTeamEvents(teamEvents));
                        memoryExtractor.extractAsync(agent.getHistory());
                        continue;                    // 处理完立刻再看，可能又来了
                    }

                    // 没输入就转一圈。用户在窗口里打字、后台抽取在跑，都不影响这里空转
                    String input = inputQueue.poll(POLL_MS, TimeUnit.MILLISECONDS);
                    if (input == null) {
                        continue;
                    }

                    // ★ 等待点。位置是被两件事钉死的：
                    //   1. 必须在 runTurn 之前 —— build() 要读 .memory/，而 consolidate 是
                    //      "先写后删"，中间那一瞬目录里是新旧混在一起的 7 个文件
                    //   2. 不能放在轮末等 —— 那就退化成同步了，用户还是盯着空屏
                    //   放在这里，用户从"点发送"到 agent 开始跑之间先把等待吸收掉，
                    //   绝大多数情况立即返回。
                    //   放在 exit 判断【之前】，退出时也就顺带等了最后一段记忆。
                    memoryExtractor.awaitPending();

                    if (ChatWindow.EXIT_COMMAND.equals(input)) {
                        break;
                    }

                    // ★ Agent 跑一轮。临界区只有这一句——调度线程抢不到锁会自动让路，用户优先
                    Message response = agent.runTurn(input);
                    System.out.println("Assistant: " + response.getContent());

                    // ★ s09: 本轮结束后抽取记忆，顺带看要不要合并。
                    //   这一段【不在锁里】，三个东西各不冲突：
                    //     - getHistory() 给的是一份快照，随便读
                    //     - .memory/ 只有主线程碰（调度回合不抽记忆）
                    //     - 打印本就只碰 response 对象，不需要锁
                    //
                    //   consolidate 不能挂在 extract 的返回值上：库满时 extract 一条都写不进、
                    //   返回 0，合并就永远触发不了，库会卡死在满的状态出不来。
                    //   它自己的阈值判断很便宜（列一下目录比个大小），不触发就不花 LLM。
                    //
                    //   丢后台：这两步各是一次 LLM 调用，合起来 2~10 秒。同步做的话
                    //   用户从看到回答到能再输入之间要干等（ISSUES 五-10）。
                    memoryExtractor.extractAsync(agent.getHistory());

                } catch (RuntimeException e) {
                    System.out.println("[error] 这一轮挂了，主循环继续: " + e);
                }
            }
        } finally {
            // daemon 线程本来就会随进程消失，这里只是走得干净一点
            CronScheduler.getInstance().stop();
            memoryExtractor.shutdown();
            // 顺手把压缩落盘目录清掉，不用等到下次启动。
            // （清完之后队友线程可能又写回来一点——它们要等 System.exit(0) 才被收掉。
            //   无所谓，下次启动还会扫一遍）
            ContextCompactor.cleanupAll();
        }

        // Swing 的 EDT 是非守护线程，不显式退出的话 JVM 会挂在窗口线程上不结束
        System.exit(0);
    }

    /**
     * 把队友发来的一批消息拼成一段文字，塞进 Lead 的 history。
     *
     * PLAN_REQUEST 单独格式化——它是【要求 Lead 做一个动作】，光甩一段计划文本，
     * 模型不知道该干嘛。得明确写出"用 review_plan 处理"。
     */
    private static String formatTeamEvents(List<BusMessage> teamEvents) {
        StringBuilder sb = new StringBuilder("[Team events]\n");
        for (BusMessage event : teamEvents) {
            if (event.type() == BusMessage.Type.PLAN_REQUEST) {
                sb.append("[计划待审批] ").append(event.from())
                  .append(" 提交了计划，请用 review_plan(\"").append(event.from())
                  .append("\", approve, feedback) 批准或驳回。计划内容：\n")
                  .append(event.content()).append('\n');
            } else {
                sb.append('[').append(event.type().label()).append(" from ")
                  .append(event.from()).append("] ")
                  .append(event.content()).append('\n');
            }
        }
        return sb.toString();
    }
}
