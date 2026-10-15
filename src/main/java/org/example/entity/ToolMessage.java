package org.example.entity;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具调用结果消息，必须带 tool_call_id（对应前面 assistant 的 tool_calls[i].id）。
 * 推荐用 Message.tool(content, toolCallId) 构造。
 */
public class ToolMessage extends Message {

    private final String toolCallId;

    public ToolMessage(Role role, String content, String toolCallId) {
        super(role, content);
        this.toolCallId = toolCallId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("tool_call_id", toolCallId);
        return map;
    }
}