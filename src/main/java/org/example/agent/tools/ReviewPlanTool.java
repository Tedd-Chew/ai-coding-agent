package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.BusMessage;
import org.example.agent.MessageBus;
import org.example.agent.TeamRegistry;
import org.example.agent.Teammate;
import org.example.agent.Tool;

import java.util.Map;

/**
 * Lead 批准或驳回某个队友的计划。只有 Lead 有。
 *
 * 审批结果【直接落在队友那个 PlanGate 对象上】——不用消息传数据。
 * 发回去那条消息只负责把队友从 waitForWork 里叫醒，顺便让它的模型看到 feedback。
 */
public class ReviewPlanTool implements Tool {

    private static final String APPROVED_DEFAULT = "计划已批准，可以动手了。";
    private static final String REJECTED_DEFAULT = "计划被驳回，改一版再提交。";

    @Override
    public String name() {
        return "review_plan";
    }

    @Override
    public String description() {
        return "批准或驳回某个队友提交的计划。"
             + "队友提交计划后你会在团队事件里看到，用这个工具处理。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("teammate")
            .put("type", "string")
            .put("description", "提交计划的队友名字");
        properties.putObject("approve")
            .put("type", "boolean")
            .put("description", "true 批准，false 驳回");
        properties.putObject("feedback")
            .put("type", "string")
            .put("description", "给队友看的一句话说明，可以省略");

        schema.putArray("required").add("teammate").add("approve");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            // ① 解析模型给的参数
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            String teammate = (String) args.get("teammate");
            // approve 是必填，但取出来可能是 null。用 equals 免掉拆箱 NPE
            boolean approve = Boolean.TRUE.equals(args.get("approve"));
            String feedback = (String) args.get("feedback");

            // ② 找到那个队友
            Teammate target = TeamRegistry.getInstance().get(teammate);
            if (target == null) {
                return "Error: 没有叫 " + teammate + " 的队友";
            }

            // ③ 把结果写进他的通行证。
            //    decide 返回 false = 没人在申请（模型幻觉调用），或者这条已经批过了。
            //    必须让 Lead 知道没批成，不然它会以为搞定了
            if (!target.gate().decide(approve)) {
                return "Error: " + teammate + " 没有正在等待审批的计划（可能已经批过了）";
            }

            // ④ 发条消息把队友从 waitForWork 里叫醒
            String message;
            if (feedback != null && !feedback.isBlank()) {
                message = feedback;                  // 模型给了说明，用它
            } else if (approve) {
                message = APPROVED_DEFAULT;
            } else {
                message = REJECTED_DEFAULT;
            }
            MessageBus.getInstance().send(MessageBus.LEAD, teammate, message,
                BusMessage.Type.MESSAGE);

            return "Plan " + (approve ? "approved" : "rejected") + ": " + teammate;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
