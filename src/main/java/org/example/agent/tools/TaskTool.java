package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Agent;
import org.example.agent.HookRegistry;
import org.example.entity.Message;
import org.example.agent.Tool;
import org.example.service.ChatService;

import java.util.List;
import java.util.Map;

/**
 * Task 工具：调用 subagent 执行子任务
 *
 * 目的：减少父 agent 的上下文污染（不是并行执行）
 * - subagent 有独立 history，看不到父对话
 * - subagent 没有父 memory
 * - subagent 只能用基础工具（不含 Task，防递归）
 * - subagent 没有任务工具（TaskStore 是全局单例，子 agent 改了图会打乱主 agent 的计划）
 * - 只返回最终文本，中间过程不进入父 context
 */
public class TaskTool implements Tool {

    /**
     * 压缩落盘目录的 owner。所有 subagent 共用这一个——
     * TaskTool.execute 是同步的（runTurn 阻塞到跑完，没有后台线程），不会并发。
     */
    private static final String SUBAGENT_OWNER = "subagent";

    private final ChatService chatService;
    private final List<Tool> baseTools;
    private final HookRegistry hookRegistry;

    public TaskTool(ChatService chatService,
                    List<Tool> baseTools,
                    HookRegistry hookRegistry) {
        this.chatService = chatService;
        this.baseTools = baseTools;
        this.hookRegistry = hookRegistry;
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "Run a subagent with fresh conversation context and return its final text. "
             + "Use this to reduce parent context pollution when exploring or doing multi-step lookups. "
             + "The subagent has no memory of the parent conversation; provide all necessary context in the prompt.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ObjectNode prompt = properties.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("minLength", 1);
        prompt.put("description",
            "Self-contained task description. The subagent has no context, "
            + "so include all background, file paths, constraints, and expected output format.");

        schema.putArray("required").add("prompt");
        return schema;
    }

    @Override
    public String execute(String input) {
        Agent subAgent = null;
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> args = mapper.readValue(input, Map.class);
            String prompt = (String) args.get("prompt");

            System.out.println("\n[Subagent started]");

            // subagent: base tools (no Task)，50 轮上限
            subAgent = new Agent(chatService, baseTools, hookRegistry, 50, SUBAGENT_OWNER);
            subAgent.loadSystemPrompt(
                "You are a focused subagent with no memory of the parent conversation. "
                + "Complete the given task and return a concise final answer."
            );
            // 关掉压缩：它的 history 用完即弃，压缩只会让它"读了就删、删了再读"。
            // 关的是②③④；第①步"大结果转存"照跑——那个防的是单批结果大到 API 都发不出去
            subAgent.disableCompaction();

            // 走 runTurn 而不是 runAgent：runAgent 是 private 了，所有调用方都必须经过回合锁
            // （subagent 是独立实例、独立锁，和父 agent 不冲突）
            Message lastResponse = subAgent.runTurn(prompt);
            System.out.println("[Subagent done]");
            return lastResponse.getContent() != null ? lastResponse.getContent() : "(no answer)";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        } finally {
            // history 用完即弃，转存过的工具结果也就没人看了。不清的话它越攒越多
            if (subAgent != null) {
                subAgent.cleanupTempFiles();
            }
        }
    }
}
