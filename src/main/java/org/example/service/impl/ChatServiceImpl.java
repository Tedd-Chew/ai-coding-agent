package org.example.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Constants;
import org.example.agent.Tool;
import org.example.entity.AssistantMessage;
import org.example.entity.Message;
import org.example.service.ChatService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 无状态 LLM 调用实现：每次 chat() 只负责"序列化 history + 发请求 + 解析响应"。
 * 不持有任何会话状态，历史由调用方（Agent）维护。
 */
public class ChatServiceImpl implements ChatService {

    private static final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;
    private static volatile ChatServiceImpl instance;

    private ChatServiceImpl() {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public static ChatServiceImpl getInstance() {
        if (instance == null) {
            synchronized (ChatServiceImpl.class) {
                if (instance == null) {
                    instance = new ChatServiceImpl();
                }
            }
        }
        return instance;
    }

    /**
     * Tool 列表 → JSON Schema（OpenAI / DeepSeek function calling 格式）
     */
    private List<ObjectNode> convertToolsToJsonSchema(List<Tool> tools) {
        List<ObjectNode> res = new ArrayList<>();
        for (Tool tool : tools) {
            ObjectNode functionNode = json.createObjectNode();
            functionNode.put("name", tool.name());
            functionNode.put("description", tool.description());
            functionNode.set("parameters", tool.parametersSchema(json));

            ObjectNode toolNode = json.createObjectNode();
            toolNode.put("type", "function");
            toolNode.set("function", functionNode);
            res.add(toolNode);
        }
        return res;
    }

    @Override
    public Message chat(List<Message> history, List<Tool> tools) {
        try {
            // 1. 拼 body
            Map<String, Object> body = new HashMap<>();
            body.put("model", Constants.DEEPSEEK_MODEL);

            List<Map<String, Object>> messagesJson = new ArrayList<>();
            for (Message m : history) {
                messagesJson.add(m.toMap());
            }
            body.put("messages", messagesJson);
            // tools 为空时不传该字段（纯文本调用，如总结/抽取）
            if (tools != null && !tools.isEmpty()) {
                body.put("tools", convertToolsToJsonSchema(tools));
            }

            String requestBody = json.writeValueAsString(body);

            // 2. 发请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Constants.DEEPSEEK_BASE_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + Constants.DEEPSEEK_API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String rawBody = response.body();

            if (response.statusCode() != 200) {
                throw new RuntimeException("invoke failure! " + response.statusCode() + " response body " + rawBody);
            }

            // 3. 解析响应
            Map<String, Object> result = json.readValue(rawBody, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
            Map<String, Object> rawMessage = (Map<String, Object>) choices.get(0).get("message");

            // 4. 包装成 AssistantMessage（可能含 tool_calls）
            return AssistantMessage.convertToMessage(rawMessage);

        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}