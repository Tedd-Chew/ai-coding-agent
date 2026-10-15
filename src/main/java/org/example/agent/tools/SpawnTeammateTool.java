package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Agent;
import org.example.agent.HookRegistry;
import org.example.agent.MessageBus;
import org.example.agent.PlanGate;
import org.example.agent.Task;
import org.example.agent.TaskStore;
import org.example.agent.Teammate;
import org.example.agent.TeamRegistry;
import org.example.agent.ThreadPools;
import org.example.agent.Tool;
import org.example.agent.ToolManager;
import org.example.service.ChatService;
import org.example.service.impl.ChatServiceImpl;

import java.util.Map;

/**
 * 起一个常驻队友。只有 Lead 有。
 *
 * 两个要点：
 *   - 【校验全在起线程之前做完】。名字不合法 / 重名 / 初始任务抢不到，都直接返回错误，
 *     不会留下一个半死的队友
 *   - 给了 task_id 就【立刻 claim】。spawn_teammate 是 Lead 点名指派，
 *     现在不占住的话可能半路被别的队友抢走，Lead 的指派就落空了
 */
public class SpawnTeammateTool implements Tool {

    /** "lead" 是保留名——队友叫 lead 的话消息就串了 */
    private static final String RESERVED_NAME = MessageBus.LEAD;

    /** 队友一轮最多跑多少步。纯安全网，和 Lead 那个 500 一个意思 */
    private static final int TEAMMATE_MAX_ITERATIONS = 200;

    private static final String SYSTEM_PROMPT_TEMPLATE =
        "你是 %s，一个 %s。\n\n"
        + "- 用工具完成分配给你的任务，做完调 complete_task，然后把结果简短说一遍。\n"
        + "- 第一步是交计划：调 submit_plan 等 lead 批准。批准之前 bash / write_file / "
        + "edit_file / complete_task 都会被拦住。\n"
        + "- 第一条消息里如果带 [Assigned task]，那个任务已经认领了，别再 claim_task。\n"
        + "- 你每次写文件都会弹给用户确认，所以写之前想清楚要写什么、写多少。\n"
        + "- 你的最终回复会由运行时转交给 lead；send_message 只用于中途协调。\n"
        + "- 汇报里凡是写成\"lead 要求的\"\"契约规定的\"，必须能指到原话；"
        + "指不到就说明那是你的判断。\n"
        + "- 中间产物（临时脚本、测试文件、日志）放工作目录里，不要写到系统盘或 %TEMP%。\n"
        + "- 协调者的名字是 lead。";

    @Override
    public String name() {
        return "spawn_teammate";
    }

    @Override
    public String description() {
        return "起一个常驻队友，它会自己接活干、用消息向你汇报。"
             + "用 task_id 点名一个任务、用 require_plan 决定要不要先审他的计划（默认要）。"
             + "起完就结束这一轮——它的事件会以团队消息的形式送回来。"
             + "开的条件：各块之间怎么对接是现成的——接口已经在代码里、是已有 API，"
             + "或者几块本来就不互相调用。"
             + "如果还得先写一份契约才能开工，别开：写契约本身就是主要工作量，自己串行做更快。"
             + "活儿太小（一会儿能写完）同理，协调开销比省下的多。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("name")
            .put("type", "string")
            .put("description", "队友名字，只用字母、数字、下划线、连字符");
        properties.putObject("role")
            .put("type", "string")
            .put("description", "角色描述，比如 \"后端工程师\"，会写进它的身份");
        properties.putObject("prompt")
            .put("type", "string")
            .put("description", "交给它的第一句话：要它干什么");
        properties.putObject("task_id")
            .put("type", "string")
            .put("description", "可选。点名一个任务，起的时候就会替它认领下来");
        properties.putObject("require_plan")
            .put("type", "boolean")
            .put("description", "要不要先审计划。默认 true");

        schema.putArray("required").add("name").add("role").add("prompt");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            String name = text(args, "name");
            String role = text(args, "role");
            String prompt = text(args, "prompt");
            String taskId = text(args, "task_id");
            // 没给就当 true。默认严格——新队友要先交计划
            boolean requirePlan = !Boolean.FALSE.equals(args.get("require_plan"));

            // ① 校验名字。全在起线程之前做完，失败就不留半死的队友
            if (!MessageBus.isValidName(name)) {
                return "Error: 队友名只能用字母、数字、下划线、连字符（1~32 位）";
            }
            if (RESERVED_NAME.equals(name)) {
                return "Error: \"" + RESERVED_NAME + "\" 是保留名，换一个";
            }
            if (role == null || role.isBlank() || prompt == null || prompt.isBlank()) {
                return "Error: role 和 prompt 都不能为空";
            }
            TeamRegistry registry = TeamRegistry.getInstance();
            if (registry.get(name) != null) {
                return "Error: 已经有叫 " + name + " 的队友了";
            }

            // ② 点名了任务就先占住。抢不到就根本不起队友
            Task task = null;
            if (taskId != null && !taskId.isBlank()) {
                try {
                    task = TaskStore.getInstance().claim(taskId, name);
                } catch (Exception e) {
                    return "Error: 起不了队友 " + name + "：" + e.getMessage();
                }
            }

            // ③ 把队友拼出来
            PlanGate gate = new PlanGate(requirePlan);
            ChatService chatService = ChatServiceImpl.getInstance();
            HookRegistry hooks = HookRegistry.getInstance();
            Agent agent = new Agent(chatService, ToolManager.forTeammate(name, gate),
                hooks, TEAMMATE_MAX_ITERATIONS, name);
            agent.loadSystemPrompt(String.format(SYSTEM_PROMPT_TEMPLATE, name, role));
            // 注意【不关压缩】：队友是常驻的，history 会一直涨，它恰恰是最需要压缩的那种。
            // （subagent 才关——它一个任务用完即弃）
            agent.disableBackgroundNotifications();

            Teammate teammate = new Teammate(name, agent, gate, prompt, task);

            // ④ 登记 + 起线程
            registry.register(teammate);
            ThreadPools.startDaemon(name, teammate);
            System.out.println("  [teammate] " + name + " spawned as " + role);

            String assigned = (task == null ? " without an initial task" : " for " + task.id());
            return "Teammate '" + name + "' spawned as " + role + assigned
                 + ". End this turn; the runtime will deliver its events.";

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /** 取字符串参数，null 安全 */
    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : value.toString();
    }
}
