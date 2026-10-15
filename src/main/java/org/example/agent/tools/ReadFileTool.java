package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * read_file 工具：读取文件内容
 * 支持三种读取方式（互斥）：
 *   - limit: 读前 N 行
 *   - offset + limit: 读第 offset 行起的 N 行
 *   - tail: 读最后 N 行
 */
public class ReadFileTool implements Tool {

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取文件内容。"
             + "三种模式（互斥）："
             + "1) limit: 读前 N 行；"
             + "2) offset+limit: 跳过前 offset 行再读 limit 行；"
             + "3) tail: 读最后 N 行（适合日志）。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "文件路径");

        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "读前 N 行（与 tail 互斥）");

        ObjectNode offset = properties.putObject("offset");
        offset.put("type", "integer");
        offset.put("description", "跳过前 N 行（配合 limit 用）");

        ObjectNode tail = properties.putObject("tail");
        tail.put("type", "integer");
        tail.put("description", "读最后 N 行（与 limit/offset 互斥）");

        schema.putArray("required").add("path");

        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> args = mapper.readValue(input, Map.class);
            String pathStr = (String) args.get("path");
            Integer limit = (Integer) args.get("limit");
            Integer offset = (Integer) args.get("offset");
            Integer tail = (Integer) args.get("tail");

            Path path = FileToolUtils.resolvePath(pathStr);

            // 用 new String(bytes, UTF_8) 而不是 Files.readString(path, UTF_8)：
            // readString 内部是 newStringNoRepl（no replacement），遇到非法字节直接抛
            // MalformedInputException，被 catch 吞成 "Error: Input length = 1"——内容全丢。
            // 中文 Windows 上 GBK 编码的文件（老源码、记事本存的 txt）都会撞上。
            // new String(...) 用 REPLACE：坏字节变 U+FFFD，中文会乱码，但 ASCII 部分
            // （代码、路径、关键字）照常可读。只读不写，所以乱码不会污染文件。
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            int total = lines.length;

            // 模式 1: tail
            if (tail != null && tail > 0) {
                int start = Math.max(0, total - tail);
                return formatLines(lines, start, total, start);
            }

            // 模式 2: offset + limit
            int actualOffset = (offset != null && offset > 0) ? offset : 0;
            if (actualOffset >= total) {
                return "(offset 超出文件总行数 " + total + ")";
            }

            int end;
            if (limit != null && limit > 0) {
                end = Math.min(total, actualOffset + limit);
            } else {
                end = total;
            }

            return formatLines(lines, actualOffset, end, total);

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 格式化输出 [from, to) 范围的行
     * @param total 总行数（用于显示剩余信息）
     */
    private String formatLines(String[] lines, int from, int to, int total) {
        if (from == 0 && to == total) {
            return String.join("\n", lines);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(lines[i]).append("\n");
        }

        int remaining = total - to;
        if (remaining > 0) {
            sb.append("...(剩余 ").append(remaining).append(" 行未读)");
        }
        return sb.toString();
    }
}
