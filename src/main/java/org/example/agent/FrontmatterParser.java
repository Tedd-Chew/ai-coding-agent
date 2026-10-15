package org.example.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析 Markdown 的 YAML frontmatter（手写，只识别扁平 key: value，不引 snakeyaml）。
 *
 * 输入：
 *   ---
 *   name: xxx
 *   description: yyy
 *   ---
 *   正文……
 *
 * 没有 frontmatter 或格式不完整时，meta 为空、body 是原文。
 *
 * 注：SkillLoader 里有一份等价实现，等第三处也用到时再合并过来。
 */
public class FrontmatterParser {

    /** 解析结果：元数据映射 + 正文 */
    public record Parsed(Map<String, String> meta, String body) {}

    public static Parsed parse(String text) {
        if (text == null || !text.startsWith("---")) {
            return new Parsed(Map.of(), text == null ? "" : text);
        }

        String[] lines = text.split("\\r?\\n", -1);
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                end = i;
                break;
            }
        }
        if (end == -1) {
            return new Parsed(Map.of(), text);
        }

        Map<String, String> meta = new LinkedHashMap<>();
        for (int i = 1; i < end; i++) {
            String line = lines[i].trim();
            int colon = line.indexOf(':');
            if (line.isEmpty() || line.startsWith("#") || colon == -1) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = unquote(line.substring(colon + 1).trim());
            meta.put(key, value);
        }

        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.length; i++) {
            body.append(lines[i]).append('\n');
        }
        return new Parsed(meta, body.toString().strip());
    }

    /** 去掉值两侧成对的引号 */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
