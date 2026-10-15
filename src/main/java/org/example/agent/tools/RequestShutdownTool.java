package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.BusMessage;
import org.example.agent.MessageBus;
import org.example.agent.TeamRegistry;
import org.example.agent.Tool;

import java.util.Map;

/**
 * 让一个队友退出。只有 Lead 有。
 *
 * 只做一件事：发一条 SHUTDOWN 消息。队友在 waitForWork 里收到就跳出循环，
 * 走 finally 收尾（交还任务 + 从活跃表摘掉）。
 *
 * 不做原书那套 request_id + status 校验 —— 同进程、消息的 from 是代码写死的、
 * 一个队友同时只可能有一条 shutdown 在飞。理由和 plan gate 那边一样。
 */
public class RequestShutdownTool implements Tool {

    private static final String SHUTDOWN_MESSAGE = "收工吧，手上的活交给任务板。";

    @Override
    public String name() {
        return "request_shutdown";
    }

    @Override
    public String description() {
        return "让一个队友退出。活干完了（list_teammates 里显示 idle）就该把他收掉。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
            .putObject("teammate")
            .put("type", "string")
            .put("description", "要收掉的队友名字");
        schema.putArray("required").add("teammate");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            String teammate = (String) args.get("teammate");

            if (TeamRegistry.getInstance().get(teammate) == null) {
                return "Error: 没有叫 " + teammate + " 的队友";
            }
            MessageBus.getInstance().send(MessageBus.LEAD, teammate,
                SHUTDOWN_MESSAGE, BusMessage.Type.SHUTDOWN);
            return teammate + " 已收到退出指令。";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
