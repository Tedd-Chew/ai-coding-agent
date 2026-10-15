package org.example.service;

import org.example.agent.Tool;
import org.example.entity.Message;

import java.util.List;
import java.util.Map;

public interface ChatService {
    /**
     * 发送对话，返回大模型回答文本
     * @param history 对话历史，包含此次的用户输入
     * @return 模型返回字符串
     */
    Message chat(List<Message> history,List<Tool> toolList);


}
