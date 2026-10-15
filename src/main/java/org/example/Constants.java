package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class Constants {
    private Constants() {}

    // 只加载一次
    private static final Properties PROPS = load();

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get("config.properties"))) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("config.properties 没找到");
        }
        return props;
    }

    // 读取 int 配置，key 缺失时用默认值
    private static int getInt(String key, int defaultValue) {
        String value = PROPS.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    // 字段名 = key 名（一致）
    public static final String DEEPSEEK_BASE_URL = PROPS.getProperty("deepseek.base_url");
    public static final String DEEPSEEK_API_KEY = PROPS.getProperty("deepseek.api.key");
    public static final String DEEPSEEK_MODEL = PROPS.getProperty("deepseek.model");

    // 上下文压缩阈值（默认值对齐 s08 原项目，可在 config.properties 覆盖）
    // 整体上下文字符数超过此值时触发压缩（太小会导致"读了就删、删了再读"的死循环）
    public static final int CONTEXT_CHAR_LIMIT = getInt("context.char_limit", 200000);
    // 一批工具结果总字符超过此值时，大结果转存磁盘
    public static final int TOOL_RESULT_BATCH_CHAR_LIMIT = getInt("context.tool_result_batch_char_limit", 200000);
    // 单个工具结果超过此值才转存（太小的不转，转存也有开销）
    public static final int LARGE_RESULT_CHAR_LIMIT = getInt("context.large_result_char_limit", 30000);
    // 喂给总结模型的最大输入字符数（超长则截头留尾）
    public static final int SUMMARY_INPUT_CHAR_LIMIT = getInt("context.summary_input_char_limit", 80000);
    // micro_compact 保留的最近已读结果数
    public static final int KEEP_RECENT_RESULTS = getInt("context.keep_recent_results", 5);
    // reactive_compact 补救时保留的最近消息数
    public static final int KEEP_RECENT_MESSAGES = getInt("context.keep_recent_messages", 5);
    // snip_compact 归档后的目标消息数（低水位）。削到这个数就停
    public static final int MAX_MESSAGES = getInt("context.max_messages", 100);
    // snip_compact 触发归档的消息数（高水位）。必须大于 MAX_MESSAGES，
    // 两者的差值是"削完之后还能再攒多少条才重新触发"。
    // 若设成和 MAX_MESSAGES 相等，归档完正好落在阈值上，每加 1 条就会再写一个 transcript
    public static final int SNIP_TRIGGER_MESSAGES = getInt("context.snip_trigger_messages", 150);

    // 记忆条数不超过这个数就不合并（没多少可合的）
    public static final int CONSOLIDATE_MIN = getInt("memory.consolidate_min", 10);
    // 记忆条数超过 CONSOLIDATE_MIN 后，比上次合并时每多出这么多条就再合并一次
    public static final int CONSOLIDATE_EVERY = getInt("memory.consolidate_every", 5);
    // 合并输入（整个记忆库的正文）字符上限，超了就跳过本次合并，不做半截
    public static final int CONSOLIDATE_INPUT_CHAR_LIMIT = getInt("memory.consolidate_input_char_limit", 20000);
    // 记忆库硬上限：满了就不再写入新记忆（合并出的记录数上限也是它）
    public static final int MAX_RECORDS = getInt("memory.max_records", 30);

    // 后台任务线程池（s11）：固定大小（核心 = 最大），队列有界
    public static final int BG_POOL_THREADS = getInt("background.pool_threads", 4);
    public static final int BG_POOL_QUEUE = getInt("background.pool_queue", 16);
}
