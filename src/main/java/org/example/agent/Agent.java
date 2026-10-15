package org.example.agent;

import org.example.agent.tools.CompactTool;
import org.example.entity.Message;
import org.example.entity.ToolMessage;
import org.example.service.ChatService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Agent：通用 ReAct 引擎
 *
 * 设计原则：
 * - 拥有自己的 history（实例状态）
 * - 接受外部 chatService / hookRegistry / toolList / scanner（共享服务）
 * - runAgent 处理一个 userInput，返回最后的 assistant 消息
 * - maxIterations 限制最大 ReAct 轮数（默认 1000，subagent 可传 30）
 *
 * 不管的事：
 * - memory 生命周期（Main 管）
 * - 默认 hook 注册（HookConfig 管）
 * - 多轮对话循环（Main 管）
 */
public class Agent {

    private final ChatService chatService;
    private final List<Tool> toolList;
    private final HookRegistry hookRegistry;
    private final int maxIterations;
    private final List<Message> history = new ArrayList<>();

    /**
     * 本 agent 的压缩器。它持有两个落盘目录（工具结果转存 / 完整历史留档），
     * 而那几个目录必须按 agent 分开，所以这里每个 Agent 建一个 —— 见 ContextCompactor 类注释。
     */
    private final ContextCompactor compactor;

    /**
     * 回合锁：s12 起主线程和调度线程会同时用这一个 Agent，history 成了共享可变状态。
     *
     * 它保护的【不是"对 history 的某一次访问"，是"一整轮 turn"】。
     * 只给单次 add 加锁没有意义——另一条线程恰好能在
     * "assistant 消息已入 history、它的 tool_calls 还没有对应 tool_result" 的那一刻插进来，
     * 那一刻的 history 是半成品，发出去就是 API 400。
     *
     * 所以锁的粒度是「一轮」，不是「一次操作」。
     *
     * 锁里【只有 runAgent】。轮后那些活儿（打印回答、记忆抽取）不碰活的 history：
     * 打印只碰 response 对象；抽取读的是 getHistory() 给的快照。
     * 锁的范围严格等于「共享可变状态（history）的范围」，多包一样都是白包。
     */
    private final ReentrantLock turnLock = new ReentrantLock();

    /** 非 null 表示本 Agent 用固定 prompt，不再每轮用 SystemPromptBuilder 重建（subagent 走这条） */
    private String fixedSystemPrompt;

    /** 是否启用上下文压缩。subagent 关掉——它是一次性任务，压缩只会让它读了就删、删了再读 */
    private boolean compactsHistory = true;

    /**
     * 收不收后台任务的完成通知。队友关掉。
     *
     * BackgroundManager 是全局单例、collect() 取走即移除。队友的 Agent 每轮开头都会
     * 把它还没收走的通知吃掉，Lead 就永远等不到了。队友本来也不碰后台能力
     * （工具表里是 BashTool(false)），所以更不该收这些通知。详见 ISSUES 十-1。
     */
    private boolean collectsBackgroundNotifications = true;

    /**
     * 轮次计数，纯日志用，每个 runAgent 从 1 重新开始。
     *
     * 没有它的话，日志里每个工具都自成一对 `[name:] → name`，
     * 「一批发两个」和「两轮各发一个」长得一模一样——
     * 同一批发出的 list_tasks 根本来不及影响同一批里的 create_task，
     * 但看上去像是「它先查了任务列表才规划」。
     */
    private int round;

    /**
     * 默认构造：maxIterations = 1000
     */
    public Agent(ChatService chatService, List<Tool> toolList,
                 HookRegistry hookRegistry, String owner) {
        this(chatService, toolList, hookRegistry, 1000, owner);
    }

    /**
     * 完整构造。
     *
     * @param owner 这个 agent 叫什么。用来给压缩落盘目录分家：
     *              Lead 传 "lead"、subagent 传 "subagent"、队友传自己的名字
     */
    public Agent(ChatService chatService, List<Tool> toolList,
                 HookRegistry hookRegistry, int maxIterations, String owner) {
        this.chatService = chatService;
        this.toolList = toolList;
        this.hookRegistry = hookRegistry;
        this.maxIterations = maxIterations;
        this.compactor = new ContextCompactor(chatService, owner);
    }

    /**
     * 拿一份 history 的【快照】。
     *
     * 返回副本而不是本体，是为了让调用方（记忆抽取）能在锁【外】安全地读——
     * 它可能要丢到另一个线程上跑，读活的 ArrayList 会和正在写它的线程打架
     * （ConcurrentModificationException，或者读到"assistant 消息已入 history、
     * 它的 tool_calls 还没有对应 tool_result"的半成品）。
     *
     * 复制这个动作本身必须在锁里，否则复制出来的就是个半成品。
     *
     * 浅拷贝就够：消息本身是不可变对象（final 字段），复制引用等于完整快照。
     * 几百条消息也就几 KB。
     */
    public List<Message> getHistory() {
        turnLock.lock();
        try {
            return List.copyOf(history);
        } finally {
            turnLock.unlock();
        }
    }

    /**
     * 关掉上下文压缩（① 的防爆转存仍保留）。
     * subagent 用：它的 history 用完即弃，不存在"让会话继续下去"的需求；
     * 压了反而会让它陷入"读了就删、删了再读"的循环。
     */
    public void disableCompaction() {
        this.compactsHistory = false;
    }

    /** 关掉后台通知收集。队友用 */
    public void disableBackgroundNotifications() {
        this.collectsBackgroundNotifications = false;
    }

    /**
     * 清掉本 agent 的压缩落盘目录。subagent / 队友【用完之后】调。
     * Lead 不调——它一直活着，history 里那些路径随时可能被读回来。
     */
    public void cleanupTempFiles() {
        compactor.cleanup();
    }

    /**
     * 设定固定 system prompt。
     * subagent 用这个给自己一个独立角色，不去继承父 Agent 的 skill 目录和记忆。
     */
    public void loadSystemPrompt(String prompt) {
        this.fixedSystemPrompt = prompt;
        setSystemMessage(prompt);
    }

    /**
     * 重建 system prompt 并覆盖 history[0]。
     * 每轮用户输入开始时调一次——记忆要按当轮请求重新召回，
     * 不能用启动时选好的那一份一直用到底。
     */
    private void refreshSystemPrompt(String userInput) {
        if (fixedSystemPrompt != null) {
            return;
        }
        setSystemMessage(SystemPromptBuilder.getInstance().build(userInput));
    }

    private void setSystemMessage(String prompt) {
        if (history.isEmpty()) {
            history.add(Message.system(prompt));
        } else {
            history.set(0, Message.system(prompt));
        }
    }

    /**
     * 主线程用：阻塞拿锁跑一轮。用户回合必须跑完，它没有让路的理由。
     */
    public Message runTurn(String userInput) {
        turnLock.lock();
        try {
            return runAgent(userInput);
        } finally {
            turnLock.unlock();
        }
    }

    /**
     * 调度线程用：抢不到锁立刻返回 null，一秒都不等。
     *
     * 用户在忙就让路——任务还在 CronScheduler 的队列里躺着，丢不了；
     * 0.2 秒后队列线程会再来敲一次门，用户一用完就轮到了。
     *
     * 用 ReentrantLock 而不是 synchronized 就是为了这个语义：
     * synchronized 只有阻塞获取，没有"抢不到就走"。
     *
     * @return 跑完的 assistant 消息；没抢到锁返回 null（注意区分：不是"跑失败"）
     */
    public Message tryRunTurn(String userInput) {
        if (!turnLock.tryLock()) {
            return null;
        }
        try {
            return runAgent(userInput);
        } finally {
            turnLock.unlock();
        }
    }

    /**
     * 跑一轮 ReAct：处理一个 userInput，返回最后的 assistant 消息。
     *
     * 【private】：直接调它会绕过 turnLock。所有调用方都必须走 runTurn / tryRunTurn。
     */
    private Message runAgent(String userInput) {

        // 0. s09: 按当前请求重建 system prompt（skill 目录 + 相关记忆）
        refreshSystemPrompt(userInput);

        // 轮次计数从头开始，一次用户输入 = 一段独立的 [round 1..N]
        round = 0;

        // 1. UserPromptSubmit
        hookRegistry.trigger("UserPromptSubmit", userInput);

        // 2. 加 user 到 history
        history.add(Message.user(userInput));

        // 3. 第一次 LLM 调用（调之前先收一轮后台通知）
        Message message = chatWithNotifications();//zhe
        history.add(message);

        // 4. ReAct 循环
        int step = 0;
        while (message.hasToolCalls() && step < maxIterations) {

            boolean compactRequested = false;

            List<Map<String, Object>> toolCalls = extractToolCalls(message);
            for (Map<String, Object> toolCall : toolCalls) {
                String toolCallId = (String) toolCall.get("id");
                Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
                String toolName = (String) function.get("name");
                String arguments = (String) function.get("arguments");

                String toolResult;
                try {
                    // PreToolUse
                    Object blocked = hookRegistry.trigger("PreToolUse", toolName, arguments);
                    if (blocked != null) {
                        toolResult = blocked.toString();        // 被拦也是一条 result
                    } else {
                        toolResult = executeTool(toolName, arguments);
                        System.out.println("→ " + toolName);
                        // PostToolUse
                        hookRegistry.trigger("PostToolUse", toolName, toolResult);
                    }
                } catch (RuntimeException e) {
                    // 兜底：不管出什么事，这个 tool_call 都要拿到一条 result。
                    //
                    // 这不是"容错"而是【必须】——一个 tool_call 只能对应一个 tool_result。
                    // 少补这条，history 里就永久留下"assistant 带 tool_calls、但没有对应
                    // result"的半成品，下一次请求直接 400，而且【再也恢复不了】，后面每轮都 400。
                    //
                    // 错误本身也是一条正常的 result：模型看得到、知道这个工具挂了，能换个做法。
                    // 项目里每个工具一直都在这么干（catch 了返回 "Error: ..."），
                    // 这里补的是漏掉的那一层——工具【外面】出事。
                    toolResult = "Error: " + e;
                }

                history.add(new ToolMessage(Message.Role.TOOL, toolResult, toolCallId));

                // s08: 标记模型主动请求压缩
                if (CompactTool.NAME.equals(toolName)) {
                    compactRequested = true;
                }
            }

            // s08: 模型主动请求压缩。
            // 必须等整批工具都执行完、tool_result 全补上之后再压——
            // 否则会留下孤立的 tool_call，也会丢掉本批次已经发生的副作用。
            if (compactRequested && compactsHistory) {
                compactor.compactHistory(history, userInput);
            }

            // s08: 每次调 LLM 前先压缩上下文（subagent 关掉了压缩，只保留 ① 的防爆转存）
            compactor.prepare(history, userInput, compactsHistory);

            // 再调一次 LLM（同样先收后台通知）
            message = chatWithNotifications();
            history.add(message);
            step++;
        }

        // 5. Stop hook
        Object force = hookRegistry.trigger("Stop", history);
        if (force != null) {
            history.add(Message.user(force.toString()));
            message = chatWithNotifications();
            history.add(message);
        }

        return message;
    }

    /**
     * 调模型前，先把已完成的后台任务收进 history。
     *
     * 三个 LLM 调用点（首轮 / 循环里 / Stop 重试）都必须走这里——
     * 后台任务随时可能完成，只在轮末收会漏掉循环中途完成的那批。
     *
     * 通知必须是【user 消息】，不能是 tool 消息：原来那个 tool_call 已经用
     * "[Background task xxx started]" 这个占位结果结清了，一个 tool_call 只能对应
     * 一个 tool_result，再挂一条上去会被 API 拒。
     * 凡是"凭空冒出来的新信息"都只能走 user 通道——和 todo reminder 是同一条规矩。
     */
    private Message chatWithNotifications() {
        System.out.println("\n[round " + (++round) + "]");
        if (collectsBackgroundNotifications) {
            List<String> notifications = BackgroundManager.getInstance().collect();
            if (!notifications.isEmpty()) {
                history.add(Message.user(String.join("\n\n", notifications)));
            }
        }
        return chatService.chat(history, toolList);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractToolCalls(Message message) {
        return (List<Map<String, Object>>) message.toMap().get("tool_calls");
    }

    private String executeTool(String toolName, String arguments) {
        for (Tool t : toolList) {
            if (t.name().equals(toolName)) {
                return t.execute(arguments);
            }
        }
        return "No such tool: " + toolName;
    }
}
