package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.BusMessage;
import org.example.agent.MessageBus;
import org.example.agent.PlanGate;
import org.example.agent.Tool;

import java.util.Map;

/**
 * 队友把工作计划交给 Lead 审批。只有队友有。
 *
 * 【立刻返回，不阻塞】。审批结果落在 PlanGate 上（Lead 那边 decide），
 * 队友这边要靠 Lead 发回来的那条消息才会醒。所以这个工具只负责"交"，
 * 不负责等——阻塞等的话，Lead 那轮没审它就吊死了。
 */
public class SubmitPlanTool implements Tool {

    private final String name;
    private final PlanGate gate;

    public SubmitPlanTool(String name, PlanGate gate) {
        this.name = name;
        this.gate = gate;
    }

    @Override
    public String name() {
        return "submit_plan";
    }

    @Override
    public String description() {
        return "把你的工作计划交给 lead 审批。批准之前，bash / write_file / edit_file "
             + "和 complete_task 都会被拦住。交完就等着，不要重复提交。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        schema.putObject("properties")
            .putObject("plan")
            .put("type", "string")
            .put("description", "计划内容：打算怎么做、改哪些文件");

        schema.putArray("required").add("plan");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            String plan = (String) args.get("plan");
            if (plan == null || plan.isBlank()) {
                return "Error: plan 不能为空";
            }

            // 已经有一条在等审批了就别再交 —— 否则 Lead 会收到两条，批了哪条都说不清
            if (!gate.submit()) {
                return "已经有一条计划在等审批了，别重复交。";
            }

            MessageBus.getInstance().send(name, MessageBus.LEAD, plan,
                BusMessage.Type.PLAN_REQUEST);
            return "Plan submitted. 等 lead 审批，通过之前不要动工作区。";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
