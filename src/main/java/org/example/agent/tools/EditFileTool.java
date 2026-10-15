package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Tool;

import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * edit_file 工具：单次替换文件中的文本
 * 对应 learn-claude-code s02 的 edit_file
 */
public class EditFileTool implements Tool {

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "在文件中查找 old_text 并替换为 new_text（只替换第一次出现）。"
             + "如果 old_text 不存在会报错，避免静默失败。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "文件路径");

        ObjectNode oldText = properties.putObject("old_text");
        oldText.put("type", "string");
        oldText.put("description", "要被替换的原文（必须精确匹配）");

        ObjectNode newText = properties.putObject("new_text");
        newText.put("type", "string");
        newText.put("description", "替换后的新内容");

        schema.putArray("required").add("path").add("old_text").add("new_text");

        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> args = mapper.readValue(input, Map.class);
            String pathStr = (String) args.get("path");
            String oldText = (String) args.get("old_text");
            String newText = (String) args.get("new_text");

            Path path = FileToolUtils.resolvePath(pathStr);

            String content = Files.readString(path, StandardCharsets.UTF_8);

            int idx = content.indexOf(oldText);
            if (idx == -1) {
                return "Error: old_text not found in file. 原文必须精确匹配（包括空格、换行）。";
            }

            String newContent = content.substring(0, idx)
                              + newText
                              + content.substring(idx + oldText.length());//拼接，好搞笑，原来是这样实现的

            Files.writeString(path, newContent, StandardCharsets.UTF_8);
            return "Edited " + path + " (replaced 1 occurrence)";

        } catch (MalformedInputException e) {
            // 这里【故意】保持失败，不学 ReadFileTool / BashTool 改成 REPLACE：
            // 本工具会写回文件。一旦拿乱码去匹配 old_text、再把结果按 UTF-8 写回去，
            // 原文件里的中文就被永久替换成 U+FFFD 了——而且模型不会察觉，以为是正常编辑。
            // 宁可改不了，也不能悄悄写坏。
            return "Error: 文件不是 UTF-8 编码（中文 Windows 上常见 GBK），已放弃编辑以免写坏原文件。";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
