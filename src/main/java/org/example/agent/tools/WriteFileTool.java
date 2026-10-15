package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * write_file 工具：覆盖式写文件
 * 对应 learn-claude-code s02 的 write_file
 */
public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "把内容写入文件（覆盖式）。"
             + "会自动创建父目录。如果文件已存在会覆盖，谨慎使用。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "文件路径");

        ObjectNode content = properties.putObject("content");
        content.put("type", "string");
        content.put("description", "要写入的完整内容");

        schema.putArray("required").add("path").add("content");

        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> args = mapper.readValue(input, Map.class);
            String pathStr = (String) args.get("path");
            String content = (String) args.get("content");

            Path path = FileToolUtils.resolvePath(pathStr);

            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, content, StandardCharsets.UTF_8);
            return "Wrote " + content.length() + " bytes to " + path;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
