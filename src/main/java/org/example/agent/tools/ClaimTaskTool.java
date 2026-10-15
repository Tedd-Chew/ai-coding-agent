package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Task;
import org.example.agent.TaskStore;
import org.example.agent.Tool;

import java.util.Map;

/**
 * 认领任务：pending → in_progress，写下 owner。
 *
 * 前置没全部完成时会拒绝，报错里带上还缺哪些前置 ID——
 * 模型要看得懂"为什么不行"才能自己调整下一步该做谁。
 */
public class ClaimTaskTool implements Tool {

    private final String owner;

    /** Lead 用：owner 写 "lead" */
    public ClaimTaskTool() {
        this(TaskStore.DEFAULT_OWNER);
    }

    /** 队友用：owner 是自己的名字——任务表里才分得清是谁认领的 */
    public ClaimTaskTool(String owner) {
        this.owner = owner;
    }

    @Override
    public String name() {
        return "claim_task";
    }

    @Override
    public String description() {
        return "认领一个任务，状态从 pending 变成 in_progress。"
             + "前置没全部完成时会拒绝，并告诉你还缺哪些任务。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        schema.putObject("properties")
            .putObject("task_id")
            .put("type", "string")
            .put("description", "要认领的任务 ID");

        schema.putArray("required").add("task_id");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            Task task = TaskStore.getInstance().claim(
                (String) args.get("task_id"), owner);
            return "Claimed " + task.id() + " (" + task.subject() + ")";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
