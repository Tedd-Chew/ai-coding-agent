package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Task;
import org.example.agent.TaskStore;
import org.example.agent.Tool;

import java.util.List;
import java.util.Map;

/**
 * 完成自己认领的任务：in_progress → completed。
 *
 * 返回值里带上【本次解锁的下游任务】——模型不用再调一次 list_tasks 就知道接下来能做什么。
 */
public class CompleteTaskTool implements Tool {

    private final String owner;

    /** Lead 用：owner 写 "lead" */
    public CompleteTaskTool() {
        this(TaskStore.DEFAULT_OWNER);
    }

    /**
     * 队友用。owner 必须传自己的名字——TaskStore.complete 会校验 task.owner() == owner，
     * 拿 "lead" 去完成队友认领的任务会直接被拒。
     *
     * （"计划没批就不许完成"那道闸门不在这里，在 GatedTool 里——一个闸门只管一处。）
     */
    public CompleteTaskTool(String owner) {
        this.owner = owner;
    }

    @Override
    public String name() {
        return "complete_task";
    }

    @Override
    public String description() {
        return "完成你认领的任务，状态变成 completed，并返回本次被解锁的下游任务。"
             + "只能完成自己认领、且处于 in_progress 的任务。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        schema.putObject("properties")
            .putObject("task_id")
            .put("type", "string")
            .put("description", "要完成的任务 ID");

        schema.putArray("required").add("task_id");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            String taskId = (String) args.get("task_id");
            // 先 require 一次：ID 不存在时能报"任务不存在"，而不是走到 complete 才失败
            Task task = TaskStore.getInstance().require(taskId);

            List<String> unblocked = TaskStore.getInstance()
                .complete(taskId, owner);

            StringBuilder sb = new StringBuilder(
                "Completed " + task.id() + " (" + task.subject() + ")");
            if (!unblocked.isEmpty()) {
                sb.append("\nUnblocked: ").append(String.join(", ", unblocked));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
