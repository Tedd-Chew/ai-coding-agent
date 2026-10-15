package org.example.entity;

import java.util.List;
import java.util.Map;

/**
 * 用来接收解析DeepSeek返回的assistant消息
 * 可以携带 tool_calls 数组 + reasoning_content（思考模式）
 */
public class AssistantMessage extends Message {
    private final List<Map<String,Object>> toolCalls;
    private final String reasoningContent;

    public AssistantMessage(String content, List<Map<String, Object>> toolCalls) {
        this(content, toolCalls, null);
    }

    public AssistantMessage(String content, List<Map<String, Object>> toolCalls, String reasoningContent) {
        super(Role.ASSISTANT, content);
        this.toolCalls = toolCalls;
        this.reasoningContent = reasoningContent;
    }

    public List<Map<String, Object>> getToolCalls() {
        return toolCalls;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public boolean hasToolCalls(){
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String,Object> map = super.toMap();
        if(toolCalls != null && !toolCalls.isEmpty()){
            map.put("tool_calls", toolCalls);
        }
        // 思考模式必须回传 reasoning_content，否则 DeepSeek 报 400
        if(reasoningContent != null && !reasoningContent.isEmpty()){
            map.put("reasoning_content", reasoningContent);
        }
        return map;
    }

    /**
     * 从http返回原始map构建AssistantMessage
     * 兼容 reasoning_content 字段（思考模式）
     */
    @SuppressWarnings("unchecked")
    public static AssistantMessage convertToMessage(Map<String,Object> rawMap){
        String content = (String) rawMap.get("content");
        List<Map<String,Object>> toolCalls = (List<Map<String, Object>>) rawMap.get("tool_calls");
        String reasoningContent = (String) rawMap.get("reasoning_content");
        return new AssistantMessage(content, toolCalls, reasoningContent);
    }
}