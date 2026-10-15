package org.example.agent;

import org.example.entity.Message;
import org.example.service.ChatService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆召回：从记忆库里挑出跟当前请求相关的几条，读回正文。
 *
 * 流程：调一次轻量 LLM 选下标 → 失败降级关键词匹配 → 读正文并限制总长度。
 * 记忆库为空或当前请求为空时直接返回空串，不调 LLM。
 */
public class MemorySelector {

    /** 最多选几条 */
    private static final int MAX_ITEMS = 5;

    /** 喂给选择模型的目录长度上限 */
    private static final int CATALOG_CHAR_LIMIT = 12000;

    /** 召回正文的总长度上限 */
    private static final int RECALL_CHAR_LIMIT = 20000;

    /** 从模型回复里抠数字（它可能裹在 ```json 或说明文字里） */
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** 关键词降级用：英文 3 字符以上，或中文 2 字以上 */
    private static final Pattern WORD = Pattern.compile("[a-z0-9_]{3,}|[\\u4e00-\\u9fff]{2,}");

    private static final String SELECT_PROMPT = """
        从下面的记忆目录里挑出与当前请求相关的记录。
        只返回 JSON 下标数组，例如 [0, 2]。没有相关的返回 []。

        当前请求：
        %s

        记忆目录：
        %s""";

    private final ChatService chatService;
    private final MemoryStore memoryStore;

    public MemorySelector(ChatService chatService, MemoryStore memoryStore) {
        this.chatService = chatService;
        this.memoryStore = memoryStore;
    }

    /**
     * 选出相关记忆并读回正文，拼成一段文本。没有则返回空串。
     */
    public String recall(String userInput, List<MemoryRecord> records) {
        if (records.isEmpty() || userInput == null || userInput.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int remaining = RECALL_CHAR_LIMIT;
        for (MemoryRecord record : select(userInput, records)) {
            if (remaining <= 0) {
                break;
            }
            String filename = MemoryStore.filenameOf(record.name());
            String content = memoryStore.readByFilename(filename);
            if (content == null || content.isBlank()) {
                continue;
            }
            String recalled = content.length() <= remaining ? content : content.substring(0, remaining);
            sb.append("- ").append(filename).append('\n')
              .append(recalled.strip()).append("\n\n");
            remaining -= recalled.length();
        }
        return sb.toString().strip();
    }

    // ==================== 选择 ====================

    /**
     * 调模型选下标，任何异常（网络、解析）都降级成关键词匹配。
     */
    private List<MemoryRecord> select(String userInput, List<MemoryRecord> records) {
        String prompt = SELECT_PROMPT.formatted(userInput, buildCatalog(records));
        try {
            Message response = chatService.chat(List.of(Message.user(prompt)), List.of());
            return pickByIndex(records, extractIndices(response.getContent()));
        } catch (Exception e) {
            return keywordSelect(userInput, records);
        }
    }

    /** 目录：每行 "下标: name - description" */
    private String buildCatalog(List<MemoryRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < records.size(); i++) {
            MemoryRecord record = records.get(i);
            sb.append(i).append(": ").append(record.name())
              .append(" - ").append(record.description()).append('\n');
        }
        String catalog = sb.toString();
        return catalog.length() <= CATALOG_CHAR_LIMIT
            ? catalog
            : catalog.substring(0, CATALOG_CHAR_LIMIT);
    }

    /** 按下标取记录，去重、限数量、忽略越界 */
    private List<MemoryRecord> pickByIndex(List<MemoryRecord> records, List<Integer> indices) {
        List<MemoryRecord> selected = new ArrayList<>();
        for (int index : indices) {
            if (index < 0 || index >= records.size()) {
                continue;
            }
            MemoryRecord record = records.get(index);
            if (!selected.contains(record)) {
                selected.add(record);
            }
            if (selected.size() == MAX_ITEMS) {
                break;
            }
        }
        return selected;
    }

    /** 从模型回复里抠出下标数组 */
    private List<Integer> extractIndices(String text) {
        List<Integer> indices = new ArrayList<>();
        if (text == null) {
            return indices;
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start == -1 || end <= start) {
            return indices;
        }
        Matcher matcher = NUMBER.matcher(text.substring(start + 1, end));
        while (matcher.find()) {
            indices.add(Integer.parseInt(matcher.group()));
        }
        return indices;
    }

    /** 降级方案：按关键词在 name + description 里的命中数排序 */
    private List<MemoryRecord> keywordSelect(String userInput, List<MemoryRecord> records) {
        Set<String> words = extractWords(userInput);
        if (words.isEmpty()) {
            return List.of();
        }

        List<MemoryRecord> candidates = new ArrayList<>();
        for (MemoryRecord record : records) {
            if (matchScore(record, words) > 0) {
                candidates.add(record);
            }
        }
        candidates.sort((a, b) -> Integer.compare(matchScore(b, words), matchScore(a, words)));

        return candidates.size() <= MAX_ITEMS
            ? candidates
            : new ArrayList<>(candidates.subList(0, MAX_ITEMS));
    }

    private int matchScore(MemoryRecord record, Set<String> words) {
        String text = (record.name() + " " + record.description()).toLowerCase();
        int score = 0;
        for (String word : words) {
            if (text.contains(word)) {
                score++;
            }
        }
        return score;
    }

    private Set<String> extractWords(String text) {
        Set<String> words = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(text.toLowerCase());
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }
}
