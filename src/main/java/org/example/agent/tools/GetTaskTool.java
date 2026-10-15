package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Task;
import org.example.agent.TaskStore;
import org.example.agent.Tool;

import java.util.Map;

/**
 * 按 ID 查看一个任务的完整内容。
 *
 * list_tasks 只给一行摘要；这里给完整 JSON，含 description 和依赖细节——
 * 跨会话恢复时需要读到完整描述才知道下一步干什么。
 */
public class GetTaskTool implements Tool {

    @Override
    public String name() {
        return "get_task";
    }

    @Override
    public String description() {
        return "按 ID 查看一个任务的完整内容，含 description 和依赖细节。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        schema.putObject("properties")
            .putObject("task_id")
            .put("type", "string")
            .put("description", "任务 ID");

        schema.putArray("required").add("task_id");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            Task task = TaskStore.getInstance().require((String) args.get("task_id"));
            return TaskStore.toJson(task);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
