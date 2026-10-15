package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.Tool;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * glob 工具：按模式匹配文件
 * 对应 learn-claude-code s02 的 glob
 *
 * 用 Java 内置 PathMatcher（glob: 前缀）做匹配，避免自己造轮子
 */
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 200;

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "在工作目录下按 glob 模式查找文件。"
             + "支持 *（单层）、**（多层）、?（单字符）、[abc]（字符集）等通配符。"
             + "返回最多 " + MAX_RESULTS + " 个匹配路径。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ObjectNode pattern = properties.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "glob 模式，如 **/*.java、*.md、src/**/test?.java");

        schema.putArray("required").add("pattern");

        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> args = mapper.readValue(input, Map.class);
            String pattern = (String) args.get("pattern");

            Path workdir = Paths.get(System.getProperty("user.dir"));
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            // 1. 拿到所有文件（绝对路径）
            List<Path> allFiles;
            try (Stream<Path> stream = Files.walk(workdir)) {
                allFiles = stream
                    .filter(p -> {
                        try { return Files.isRegularFile(p); }
                        catch (Exception e) { return false; }//判断是不是常规文件，而不是二进制文件或者软连接或者文件夹
                    })
                    .filter(p -> matcher.matches(workdir.relativize(p)))  // 用相对路径匹配
                    .sorted()
                    .toList();
            }

            // 2. 格式化输出（前 MAX_RESULTS 条 + 截断提示）
            int total = allFiles.size();
            if (total == 0) {
                return "(no matches)";
            }

            int shown = Math.min(total, MAX_RESULTS);
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < shown; i++) {
                result.append(workdir.relativize(allFiles.get(i))).append("\n");
            }

            if (total > MAX_RESULTS) {
                result.append("...(more matches omitted; narrow the pattern)");
            }
            return result.toString().trim();

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
