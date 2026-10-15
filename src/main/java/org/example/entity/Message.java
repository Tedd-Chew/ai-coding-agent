package org.example.entity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息基类。统一通过静态工厂方法构造，避免到处 new + 强转 Role。
 *
 * 子类化说明：
 *   - 普通消息（user / system）：直接用本类的静态工厂
 *   - assistant 消息（可能带 tool_calls）：用 AssistantMessage.convertToMessage(...) 或 Message.assistant(content, toolCalls)
 *   - tool 消息（必须带 tool_call_id）：用 ToolMessage，或 Message.tool(content, toolCallId)
 */
public class Message {

    public enum Role {
        USER, ASSISTANT, SYSTEM, TOOL
    }

    private final Role role;
    private final String content;

    public Message(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    // ============ 静态工厂 ============

    public static Message user(String content) {
        return new Message(Role.USER, content);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    /**
     * 构造普通 assistant 消息（无 tool_calls）。
     */
    public static AssistantMessage assistant(String content) {
        return new AssistantMessage(content, null);
    }

    /**
     * 构造 assistant 消息（带 tool_calls）。
     */
    public static AssistantMessage assistant(String content, List<Map<String, Object>> toolCalls) {
        return new AssistantMessage(content, toolCalls);
    }

    /**
     * 构造 tool 消息（工具结果回传，必须带 tool_call_id）。
     */
    public static ToolMessage tool(String content, String toolCallId) {
        return new ToolMessage(Role.TOOL, content, toolCallId);
    }

    // ============ getter ============

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    /**
     * 子类如果有 tool_calls 就 override，默认返回 false。
     */
    public boolean hasToolCalls() {
        return false;
    }

    /**
     * 序列化为 DeepSeek 要求的 JSON map。
     * 子类（如 AssistantMessage / ToolMessage）按需 override 增加字段。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("role", role.name().toLowerCase());
        map.put("content", content);
        return map;
    }
}