package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Task;
import org.example.agent.TaskStore;
import org.example.agent.Tool;

import java.util.Map;

/**
 * 创建一个任务，返回运行时生成的 ID。
 *
 * 描述里必须写清"两阶段"的约束：工具参数是在模型发起那一刻一次性定死的，
 * 拿不到同批里另一个调用还没生成的值。模型不知道这个协议限制，会自然地想一次写完。
 */
public class CreateTaskTool implements Tool {

    @Override
    public String name() {
        return "create_task";
    }

    @Override
    public String description() {
        return "创建一个新任务，返回它的运行时 ID。新任务没有依赖、没有负责人。"
             + "**建之前先 list_tasks 确认没有重复**——任务会持久化、跨会话还在，"
             + "不检查就重复创建，会留下内容一样但 ID 不同的任务。"
             + "**拿到返回的 ID 之前不要引用它**：工具参数在发起那一刻就定死了，"
             + "取不到还没生成的值。要加依赖，等结果回来后再单独调用 update_task。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("subject")
            .put("type", "string")
            .put("description", "任务标题，一句话说清要做什么");
        properties.putObject("description")
            .put("type", "string")
            .put("description", "补充说明，可省略");

        schema.putArray("required").add("subject");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            Task task = TaskStore.getInstance().create(
                (String) args.get("subject"),
                (String) args.get("description"));
            return "Created " + task.id() + " (" + task.subject() + ")";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
