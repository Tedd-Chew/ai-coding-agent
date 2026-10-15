package org.example.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 记忆存储（单例）：一条记忆一个 Markdown 文件，MEMORY.md 是索引。
 *
 * 目录结构：
 *   .memory/
 *     MEMORY.md               ← 索引，每行：- [name](file.md) - description
 *     user-prefers-tabs.md    ← 一条记忆一个文件
 *     no-mock-database.md
 *
 * 纯 IO，不调 LLM：选记忆在 MemorySelector，抽取在 MemoryExtractor。
 */
public class MemoryStore {

    private static final String INDEX_NAME = "MEMORY.md";

    /** 索引文件名去掉扩展名。用来防止记忆 slug 撞上索引（见 slug()） */
    private static final String INDEX_STEM = "MEMORY";

    /** name 里全是符号时 slug 会被清空，用这个兜底名 */
    private static final String FALLBACK_SLUG = "untitled";

    private static volatile MemoryStore instance;

    private final Path memoryDir;

    private MemoryStore(Path memoryDir) {
        // 统一成绝对路径，后面的越界校验才有意义
        this.memoryDir = memoryDir.toAbsolutePath().normalize();
    }

    public static MemoryStore init(Path memoryDir) {
        if (instance == null) {
            synchronized (MemoryStore.class) {
                if (instance == null) {
                    instance = new MemoryStore(memoryDir);
                }
            }
        }
        return instance;
    }

    public static MemoryStore getInstance() {
        if (instance == null) {
            throw new IllegalStateException("MemoryStore 未初始化，请先调用 init()");
        }
        return instance;
    }

    // ==================== 读 ====================

    /**
     * 读索引内容。索引不存在返回空串（没记忆就是没记忆，不算错）。
     */
    public String readIndex() {
        Path path = memoryDir.resolve(INDEX_NAME);
        try {
            return Files.isRegularFile(path)
                ? Files.readString(path, StandardCharsets.UTF_8).strip()
                : "";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 列出所有记忆。跳过索引文件，读不出来的单条跳过（不让一条坏文件拖垮整体）。
     */
    public List<MemoryRecord> listAll() {
        List<MemoryRecord> records = new ArrayList<>();
        if (!Files.isDirectory(memoryDir)) {
            return records;
        }

        try (Stream<Path> stream = Files.list(memoryDir)) {
            List<Path> files = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                // 忽略大小写：Windows 上 memory.md 和 MEMORY.md 是同一个文件
                .filter(p -> !INDEX_NAME.equalsIgnoreCase(p.getFileName().toString()))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();

            for (Path file : files) {
                MemoryRecord record = readFile(file);
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("读取记忆目录失败: " + e.getMessage(), e);
        }
        return records;
    }

    /**
     * 按文件名读一条记忆的完整内容（给 MemorySelector 加载正文用）。
     * 文件名非法或越界返回 null。
     */
    public String readByFilename(String filename) {
        Path path = resolveSafe(filename);
        if (path == null) {
            return null;
        }
        try {
            return Files.isRegularFile(path)
                ? Files.readString(path, StandardCharsets.UTF_8)
                : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ==================== 写 ====================

    /**
     * 写一条记忆，然后重建索引。
     * 文件名 = slug(name)，同名视为同一条 → 覆盖。
     */
    public void write(MemoryRecord record) {
        try {
            Files.createDirectories(memoryDir);
            Path path = memoryDir.resolve(filenameOf(record.name()));
            Files.writeString(path, toDocument(record), StandardCharsets.UTF_8);
            rebuildIndex();
        } catch (IOException e) {
            throw new RuntimeException("保存记忆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 用新记录集整体替换记忆库（合并操作用）。
     *
     * 【先写后删】顺序不能反：先写，全部成功了才删多余的。
     * 原项目是先删后写，所以它必须准备一份快照来回滚；这样写则任何时刻磁盘上都
     * 至少有一份完整数据，中途失败也只是新旧并存（下次合并会收拾），不需要回滚。
     *
     * 调用方必须保证 records 非空——空了就等于清库。
     */
    public void replaceAll(List<MemoryRecord> records) {
        if (records.isEmpty()) {
            throw new IllegalArgumentException("replaceAll 不接受空集合：那等于清空记忆库");
        }
        try {
            Files.createDirectories(memoryDir);

            // ① 先写：同名覆盖、新名新建。这一步走完，新数据已全部就位
            Set<String> kept = new HashSet<>();
            for (MemoryRecord record : records) {
                String filename = filenameOf(record.name());
                Files.writeString(memoryDir.resolve(filename), toDocument(record), StandardCharsets.UTF_8);
                // Windows 文件系统大小写不敏感，比较时统一小写
                kept.add(filename.toLowerCase());
            }

            // ② 再删：不在新集合里的旧文件
            List<Path> stale;
            try (Stream<Path> stream = Files.list(memoryDir)) {
                stale = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !INDEX_NAME.equalsIgnoreCase(p.getFileName().toString()))
                    .filter(p -> !kept.contains(p.getFileName().toString().toLowerCase()))
                    .toList();
            }
            for (Path path : stale) {
                Files.deleteIfExists(path);
            }

            rebuildIndex();
        } catch (IOException e) {
            throw new RuntimeException("整体替换记忆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 扫目录重新生成 MEMORY.md。
     * 索引是派生数据，任何时候都能从文件重建，所以不需要单独维护。
     */
    public void rebuildIndex() {
        try {
            Files.createDirectories(memoryDir);
            StringBuilder sb = new StringBuilder();
            for (MemoryRecord record : listAll()) {
                sb.append("- [").append(record.name()).append("](")
                  .append(filenameOf(record.name())).append(") - ")
                  .append(record.description()).append('\n');
            }
            Files.writeString(memoryDir.resolve(INDEX_NAME), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("重建记忆索引失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部 ====================

    /**
     * name → 文件名。跟 Python 的 re.sub(r"[^\w]+", "-", name.lower()).strip("-_") 等价，
     * 但用 \p{L}\p{N} 保证中文不被替换掉（Java 的 \w 不含 CJK）。
     */
    public static String filenameOf(String name) {
        return slug(name) + ".md";
    }

    private static String slug(String name) {
        String slug = name.toLowerCase()
            .replaceAll("[^\\p{L}\\p{N}_]+", "-")
            .replaceAll("^[-_]+|[-_]+$", "");

        if (slug.isEmpty()) {
            slug = FALLBACK_SLUG;
        }
        // 撞上索引名会覆盖 MEMORY.md（Windows 大小写不敏感），退让一步
        return slug.equalsIgnoreCase(INDEX_STEM) ? slug + "-record" : slug;
    }

    /** 读一个文件成 MemoryRecord，失败返回 null */
    private MemoryRecord readFile(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            FrontmatterParser.Parsed parsed = FrontmatterParser.parse(text);
            Map<String, String> meta = parsed.meta();

            String name = meta.getOrDefault("name", "").trim();
            if (name.isEmpty()) {
                name = file.getFileName().toString().replaceFirst("\\.md$", "");
            }

            String description = meta.getOrDefault("description", "").trim();
            if (description.isEmpty()) {
                description = firstLine(parsed.body());
            }

            return new MemoryRecord(
                name,
                MemoryType.from(meta.get("type")),
                description,
                parsed.body()
            );
        } catch (IOException e) {
            return null;
        }
    }

    /** 拼成带 frontmatter 的 Markdown 文档 */
    private String toDocument(MemoryRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(oneLine(record.name())).append('\n');
        sb.append("description: ").append(oneLine(record.description())).append('\n');
        if (record.type() != null) {
            sb.append("type: ").append(record.type().label()).append('\n');
        }
        sb.append("---\n\n");
        sb.append(record.body() == null ? "" : record.body().strip()).append('\n');
        return sb.toString();
    }

    /** frontmatter 是扁平 key: value，值必须压成单行 */
    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String firstLine(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        int newline = body.indexOf('\n');
        String first = newline == -1 ? body : body.substring(0, newline);
        return first.replaceFirst("^#+\\s*", "").trim();
    }

    /**
     * 把文件名解析成目录内的安全路径。
     * 含目录分隔符、或 normalize 后跑到目录外，一律拒绝（防路径遍历）。
     */
    private Path resolveSafe(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        if (!filename.equals(Path.of(filename).getFileName().toString())) {
            return null;
        }
        Path path = memoryDir.resolve(filename).normalize();
        return path.startsWith(memoryDir) ? path : null;
    }
}
