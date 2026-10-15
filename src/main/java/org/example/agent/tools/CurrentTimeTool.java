package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class CurrentTimeTool implements Tool {
    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public String description() {
        return "A tool used for fetching current time";
    }

    @Override
    public String execute(String input) {
        String format = "yyyy-MM-dd HH:mm:ss";  // 默认格式

        // 解析 LLM 传入的参数（input 是 JSON 字符串）
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> args = mapper.readValue(input, Map.class);

            if (args.containsKey("format")) {
                format = (String) args.get("format");
            }
        } catch (Exception e) {
            // 解析失败用默认格式
        }

        return LocalDateTime.now().format(DateTimeFormatter.ofPattern(format));
    }
}
