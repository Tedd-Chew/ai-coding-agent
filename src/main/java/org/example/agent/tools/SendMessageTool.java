package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.BusMessage;
import org.example.agent.MessageBus;
import org.example.agent.Tool;

import java.util.Map;

/**
 * 给 lead 或另一个队友发一条消息。
 *
 * from 由构造时注入——工具实例自己知道"这条是谁发的"，不靠模型传，
 * 所以模型伪造不了发送者。Lead 那份传 "lead"，队友那份传自己的名字。
 */
public class SendMessageTool implements Tool {

    private final String from;

    public SendMessageTool(String from) {
        this.from = from;
    }

    @Override
    public String name() {
        return "send_message";
    }

    @Override
    public String description() {
        return "给 lead 或另一个队友发一条消息。"
             + "只用于中途的协调和汇报；最终结果用 complete_task 收尾。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("to")
            .put("type", "string")
            .put("description", "收件人名字。协调者叫 \"lead\"");
        properties.putObject("content")
            .put("type", "string")
            .put("description", "消息内容");

        schema.putArray("required").add("to").add("content");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            String to = (String) args.get("to");
            if (to == null || to.isBlank()) {
                return "Error: to 不能为空";
            }
            String content = (String) args.get("content");
            MessageBus.getInstance().send(from, to,
                content == null ? "" : content, BusMessage.Type.MESSAGE);
            return "Sent to " + to;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
