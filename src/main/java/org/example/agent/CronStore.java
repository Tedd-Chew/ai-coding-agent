package org.example.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务的持久化（单例）：全部 durable 任务写在一张表 .scheduled_tasks.json 里。
 *
 * 和 {@link TaskStore} 的差别正是这章要学的东西：
 *
 * - TaskStore 是【一个任务一个文件】，用 CREATE_NEW 排他创建来防并发踩踏
 * - 这里是【一张表一个文件】，每次增删都要整体覆盖 → 覆盖写没有排他性，
 *   写到一半崩掉/断电，留下的是半个 JSON，下次启动全军覆没
 *
 * 所以这里必须【原子替换】：先写临时文件、落盘，再 rename 成正式文件名。
 * rename 在文件系统层面是原子的，换过去那一刻别人看到的要么是旧的全量、要么是新的全量，
 * 不存在中间态。TaskStore 不需要这一步，因为它从不覆盖已有文件。
 *
 * 纯 IO，不碰线程、不调 LLM。
 */
public class CronStore {

    /** 表文件名。放项目根目录下（现在是 .scheduled_tasks.json，由 init 的入参决定父目录） */
    private static final String FILE_NAME = ".scheduled_tasks.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static volatile CronStore instance;

    private final Path path;

    private CronStore(Path dir) {
        // 统一成绝对路径，临时文件和正式文件才会落在同一目录
        // （Files.move 只有同目录/同卷才是原子的，跨卷会退化成拷贝+删除）
        this.path = dir.toAbsolutePath().normalize().resolve(FILE_NAME);
    }

    public static CronStore init(Path dir) {
        if (instance == null) {
            synchronized (CronStore.class) {
                if (instance == null) {
                    instance = new CronStore(dir);
                }
            }
        }
        return instance;
    }

    public static CronStore getInstance() {
        if (instance == null) {
            throw new IllegalStateException("CronStore 未初始化，请先调用 init()");
        }
        return instance;
    }

    public Path path() {
        return path;
    }

    /**
     * 读回全部 durable 任务。
     *
     * 坏条目【跳过并打印原因】，不静默丢——静默跳过的话，用户只会看到"我的任务莫名其妙不见了"，
     * 没有任何线索可查。文件整体读不出来时同理：报告出来，但不拖垮启动。
     */
    public List<CronJob> load() {
        List<CronJob> jobs = new ArrayList<>();
        if (!Files.isRegularFile(path)) {
            return jobs;
        }

        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 文件在但读不出来，这跟"文件不存在"是两回事，不能当成空表——
            // 那会静默丢掉用户所有的定时任务，还可能被下一次 save 覆盖掉
            throw new RuntimeException("读取 " + FILE_NAME + " 失败: " + e.getMessage(), e);
        }

        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            System.out.println("  [cron] " + FILE_NAME + " 解析失败，本次不加载任何任务: " + e.getMessage());
            return jobs;
        }
        if (!root.isArray()) {
            System.out.println("  [cron] " + FILE_NAME + " 顶层不是数组，本次不加载任何任务");
            return jobs;
        }

        for (JsonNode node : root) {
            CronJob job = fromJson(node);
            if (job != null) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    /**
     * 整体写回。只写 durable 的任务——非 durable 的本来就该随进程消失，
     * 落盘反而会让"session 级"这个语义失真。
     *
     * 调用方负责在 cron_lock 里调它，这里不加锁（锁归 CronScheduler 统一管，免得两把锁互相等）。
     */
    public void save(List<CronJob> jobs) {
        ArrayNode array = JSON.createArrayNode();
        for (CronJob job : jobs) {
            if (job.durable()) {
                array.add(toJson(job));
            }
        }

        String payload;
        try {
            payload = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(array) + "\n";
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化定时任务失败: " + e.getMessage(), e);
        }

        // 临时文件名带上 pid + 线程 id：同一目录下多个进程/线程同时写时不会互相踩。
        // 放在正式文件同目录，是为了让下面的 move 是【同卷】的——跨卷就不是原子了。
        Path temp = path.resolveSibling(
            path.getFileName() + "." + ProcessHandle.current().pid()
                + "." + Thread.currentThread().getId() + ".tmp");

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temp, payload, StandardCharsets.UTF_8);
            move(temp, path);
        } catch (IOException e) {
            throw new RuntimeException("保存定时任务失败: " + e.getMessage(), e);
        } finally {
            // 换成功之后 temp 已经不在了，这行只清理失败路径上的残留
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 清理失败不影响主流程，留着就留着
            }
        }
    }

    /**
     * 原子替换。ATOMIC_MOVE 在 Windows 上是 MoveFileEx(MOVEFILE_REPLACE_EXISTING)，
     * 在 POSIX 上是 rename()——两者都是"要么旧的、要么新的"。
     *
     * 极少数文件系统（某些网络盘、FAT）不支持，退化成普通替换：可能被别人看到半个文件，
     * 但总比整个功能失败强。真遇到要记进 ISSUES。
     */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ==================== 序列化 ====================

    private static ObjectNode toJson(CronJob job) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", job.id());
        node.put("cron", job.cron());
        node.put("prompt", job.prompt());
        node.put("recurring", job.recurring());
        node.put("durable", job.durable());
        // 这两个是【交付状态】，不是配置——它们的值决定了重启后要不要重新投递，
        // 所以必须落盘，不能只活在内存里（at-least-once 就靠它跨重启）
        node.put("pendingDelivery", job.pendingDelivery());
        if (job.lastFired() == null) {
            node.putNull("lastFired");
        } else {
            node.put("lastFired", job.lastFired());
        }
        return node;
    }

    /** 坏条目返回 null 并打印原因 */
    private static CronJob fromJson(JsonNode node) {
        String id = node.path("id").asText("");
        String cron = node.path("cron").asText("");
        String prompt = node.path("prompt").asText("");

        if (!id.startsWith(CronJob.ID_PREFIX)) {
            System.out.println("  [cron] 跳过无效任务 ID: " + id);
            return null;
        }
        if (prompt.isBlank()) {
            System.out.println("  [cron] 跳过空 prompt 的任务: " + id);
            return null;
        }
        try {
            // 表达式不合法就别加载进来，否则每轮 poll 都要炸一次
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            System.out.println("  [cron] 跳过表达式非法的任务 " + id + ": " + e.getMessage());
            return null;
        }

        JsonNode lastFired = node.path("lastFired");
        return new CronJob(
            id,
            cron,
            prompt,
            node.path("recurring").asBoolean(true),
            node.path("durable").asBoolean(true),
            node.path("pendingDelivery").asBoolean(false),
            lastFired.isNull() ? null : lastFired.asText(null));
    }
}
