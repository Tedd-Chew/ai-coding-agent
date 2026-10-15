package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Task;
import org.example.agent.TaskStore;
import org.example.agent.Tool;

import java.util.List;
import java.util.Map;

/**
 * 用 create_task 返回的 ID 给任务添加前置依赖。
 *
 * 这是"两阶段构建"的第二阶段：第一阶段把节点都建出来拿到 ID，这里才连边。
 */
public class UpdateTaskTool implements Tool {

    @Override
    public String name() {
        return "update_task";
    }

    @Override
    public String description() {
        return "给任务添加前置依赖（blockedBy）。任务必须还没被认领。"
             + "addBlockedBy 里只能用【已经拿到结果】的任务 ID——"
             + "同一条回复里刚 create、结果还没回灌的 ID 用不了。"
             + "重复添加已有的依赖是安全的，不会产生重复边。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("task_id")
            .put("type", "string")
            .put("description", "要加依赖的任务 ID");
        ObjectNode deps = properties.putObject("addBlockedBy");
        deps.put("type", "array");
        deps.put("description", "前置任务的 ID 列表");
        deps.putObject("items").put("type", "string");

        schema.putArray("required").add("task_id").add("addBlockedBy");
        return schema;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            Task task = TaskStore.getInstance().updateDependencies(
                (String) args.get("task_id"),
                (List<String>) args.get("addBlockedBy"));
            return "Updated " + task.id() + "; blockedBy = " + task.blockedBy();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
