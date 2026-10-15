package org.example.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 任务存储（单例）：一个任务一个 JSON 文件，放在 .tasks/ 下。
 *
 * 相比 s05 的 TodoManager（进程内、整表替换）：
 *   - 落盘，跨会话保留
 *   - 每个任务有独立 ID，工具对单条记录做增删改查
 *   - blockedBy 记录依赖，claim 前先检查前置是否全部完成
 *
 * 纯 IO + 状态机校验，不调 LLM。
 *
 * 非法操作一律抛异常（任务不存在、被阻塞、状态不对、依赖成环……），
 * 由工具层 catch 后转成文字回给模型——模型看得懂"为什么不行"才能自己改。
 */
public class TaskStore {

    /** 任务 ID = 这个前缀 + 若干位随机十六进制 */
    private static final String ID_PREFIX = "task_";

    /** 随机部分长度 */
    private static final int ID_RANDOM_LENGTH = 8;

    /** ID 撞车时的重新生成上限 */
    private static final int ID_RETRY_LIMIT = 10;

    /**
     * 主 agent（Lead）认领任务时写的 owner。
     *
     * s13 之前所有认领方分不出来（hook 那章讨论过的"调用者身份"问题），统一写 "agent"。
     * 有了队友之后 owner 才有意义：Lead 写 "lead"，队友写自己的名字——
     * 靠它才能判断"这个任务是谁在干"，也才能让 complete 校验调用者。
     */
    public static final String DEFAULT_OWNER = "lead";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static volatile TaskStore instance;

    private final Path taskDir;

    /**
     * 保护【读-改-写】这类操作的进程内锁。
     *
     * s13 起多个队友并发动同一张任务图，而 claim / complete / updateDependencies 都是
     * "读出来 → 校验 → 写回去"。中间没有原子性的话，两个队友会双双通过校验、双双落盘
     * ——同一个任务被两个人认领，或者一次完成解锁的下游被另一次覆盖。
     *
     * 【不加锁的三类】：
     *   - load / listAll / require 只读，最坏读到略旧的数据，不影响正确性
     *   - create 用 CREATE_NEW 排他创建，撞名会重试
     * 给它们加锁只是白白增加争用。
     *
     * 【只有进程内锁，没有文件锁】：`.tasks/` 只有一个进程在写。
     * 原书额外加了文件锁，因为它考虑"另一个 Harness 进程也在用同一个任务目录"——
     * 那不是我们的场景，多一层复杂度不值。
     */
    private final ReentrantLock lock = new ReentrantLock();

    private TaskStore(Path taskDir) {
        // 统一成绝对路径，越界校验才有意义
        this.taskDir = taskDir.toAbsolutePath().normalize();
    }

    public static TaskStore init(Path taskDir) {
        if (instance == null) {
            synchronized (TaskStore.class) {
                if (instance == null) {
                    instance = new TaskStore(taskDir);
                }
            }
        }
        return instance;
    }

    public static TaskStore getInstance() {
        if (instance == null) {
            throw new IllegalStateException("TaskStore 未初始化，请先调用 init()");
        }
        return instance;
    }

    // ==================== 建 ====================

    /**
     * 创建一个任务（初始无依赖、无 owner），返回带运行时 ID 的 Task。
     *
     * ID 由这里生成而不是让模型自己起名——保证唯一、格式统一。
     * 代价是模型不能在同一条回复里引用刚创建的任务 ID，必须分两阶段：
     * 先 create 完拿到 ID，再用 update 加依赖（见 updateDependencies）。
     *
     * 用排他创建（CREATE_NEW）而不是覆盖写，并发下不会踩掉别人的文件。
     */
    public Task create(String subject, String description) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject 不能为空");
        }
        try {
            Files.createDirectories(taskDir);
            for (int attempt = 0; attempt < ID_RETRY_LIMIT; attempt++) {
                Task task = new Task(newId(),
                    subject.trim(),
                    description == null ? "" : description.trim(),
                    Task.Status.PENDING,
                    null,
                    List.of());
                try {
                    Files.writeString(taskPath(task.id()), toJson(task),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    return task;
                } catch (FileAlreadyExistsException e) {
                    // ID 撞车，换一个再来
                }
            }
            throw new IllegalStateException("连续 " + ID_RETRY_LIMIT + " 次生成的 ID 都已存在");
        } catch (IOException e) {
            throw new RuntimeException("创建任务失败: " + e.getMessage(), e);
        }
    }

    // ==================== 读 ====================

    /** 按 ID 读，不存在返回 null */
    public Task load(String id) {
        Path path = taskPath(id);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return fromJson(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("读取任务失败 " + id + ": " + e.getMessage(), e);
        }
    }

    /** 按 ID 读，不存在抛异常（报错信息里要能看出是哪个 ID 不存在） */
    public Task require(String id) {
        Task task = load(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        return task;
    }

    /** 列出全部任务，按文件名排序（ID 随机，顺序无业务含义，只求稳定） */
    public List<Task> listAll() {
        List<Task> tasks = new ArrayList<>();
        if (!Files.isDirectory(taskDir)) {
            return tasks;
        }
        try (Stream<Path> stream = Files.list(taskDir)) {
            List<Path> files = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
            for (Path file : files) {
                Task task = fromJson(Files.readString(file, StandardCharsets.UTF_8));
                if (task != null) {
                    tasks.add(task);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("读取任务目录失败: " + e.getMessage(), e);
        }
        return tasks;
    }

    /** 未完成的前置任务 ID。前置文件不存在也算未完成——不能凭丢失的前置放行 */
    public List<String> incompleteDependencies(Task task) {
        List<String> incomplete = new ArrayList<>();
        for (String depId : task.blockedBy()) {
            Task dep = load(depId);
            if (dep == null || dep.status() != Task.Status.COMPLETED) {
                incomplete.add(depId);
            }
        }
        return incomplete;
    }

    // ==================== 写 ====================

    /** 覆盖写一条任务 */
    public void save(Task task) {
        try {
            Files.createDirectories(taskDir);
            Path path = taskPath(task.id());
            if (path == null) {
                throw new IllegalArgumentException("非法任务 ID: " + task.id());
            }
            Files.writeString(path, toJson(task), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("保存任务失败: " + e.getMessage(), e);
        }
    }

    /**
     * pending → in_progress。
     *
     * 拒绝的两种情况：已经被认领/完成了，或者前置还没做完。
     * 报错里带上未完成的前置 ID，模型才知道该先做谁。
     */
    public Task claim(String id, String owner) {
        lock.lock();
        try {
            Task task = require(id);
            if (task.status() != Task.Status.PENDING) {
                throw new IllegalStateException(
                    "任务 " + id + " 当前是 " + task.status().label() + "，不能认领");
            }
            List<String> incomplete = incompleteDependencies(task);
            if (!incomplete.isEmpty()) {
                throw new IllegalStateException(
                    "任务 " + id + " 被阻塞，未完成的前置: " + String.join(", ", incomplete));
            }
            Task claimed = task.claimedBy(owner);
            save(claimed);
            return claimed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * in_progress → completed，返回【本次完成解锁的下游任务】的 subject 列表。
     *
     * 解锁 = 完成前还不能开工、完成后能开工的 pending 任务。
     * 所以前后各取一次快照做差，不能直接列举"现在能开工的"——
     * 那些本来就没被阻塞的任务会被误报成刚解锁。
     */
    public List<String> complete(String id, String owner) {
        lock.lock();
        try {
            Task task = require(id);
            if (task.status() != Task.Status.IN_PROGRESS) {
                throw new IllegalStateException(
                    "任务 " + id + " 当前是 " + task.status().label() + "，不能完成");
            }
            if (!Objects.equals(task.owner(), owner)) {
                throw new IllegalStateException(
                    "任务 " + id + " 由 " + task.owner() + " 认领，不是 " + owner);
            }

            Set<String> readyBefore = readyPendingIds();
            save(task.completed());

            List<String> unblocked = new ArrayList<>();
            for (String readyId : readyPendingIds()) {
                if (!readyBefore.contains(readyId)) {
                    Task ready = load(readyId);
                    if (ready != null) {
                        unblocked.add(ready.subject());
                    }
                }
            }
            return unblocked;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 给任务加前置依赖，整批校验通过才落盘。
     *
     * 校验：目标存在、目标仍是 pending 且无人认领、依赖都存在、不能自依赖、不能成环。
     * 重复添加已有依赖是安全的（用 Set 合并，不产生重复边）。
     *
     * 必须整体先校验再保存——不能边加边存，否则加到一半失败会留下半成品。
     */
    public Task updateDependencies(String id, List<String> addBlockedBy) {
        if (addBlockedBy == null || addBlockedBy.isEmpty()) {
            throw new IllegalArgumentException("addBlockedBy 不能为空");
        }
        lock.lock();
        try {
            Task target = require(id);
            if (target.status() != Task.Status.PENDING || target.owner() != null) {
                throw new IllegalStateException(
                    "任务 " + id + " 已经是 " + target.status().label() + " 或已被认领，不能再改依赖");
            }

            LinkedHashSet<String> merged = new LinkedHashSet<>(target.blockedBy());
            for (String rawDepId : addBlockedBy) {
                if (rawDepId == null || rawDepId.isBlank()) {
                    throw new IllegalArgumentException("依赖的任务 ID 不能为空");
                }
                String depId = rawDepId.trim();
                if (depId.equals(id)) {
                    throw new IllegalArgumentException("任务不能依赖自己: " + id);
                }
                if (load(depId) == null) {
                    throw new IllegalArgumentException("依赖的任务不存在: " + depId);
                }
                merged.add(depId);
            }

            // 全部加完之后，对最终图做一次环检测
            if (hasCycleFrom(id, merged)) {
                throw new IllegalArgumentException(
                    "这些依赖会形成环: " + id + " -> " + String.join(", ", merged));
            }

            Task updated = target.withBlockedBy(List.copyOf(merged));
            save(updated);
            return updated;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 把所有 in_progress 的任务退回 pending。Main 启动时调一次。
     *
     * 【为什么需要】
     * 队友线程是 daemon，而 Main 最后是 System.exit(0) —— 它只在【调用它的那条线程】上
     * 展开还没跑的 finally，别的线程的栈根本不动。所以 Teammate 的 finally（handBackWork）
     * 在"点 × 关窗"这条路上都不会跑，更别说被任务管理器打死的时候。
     * 那些任务会永远卡在 in_progress：owner 是个已经不存在的人，谁也抢不走。
     *
     * 【为什么"全退"是安全的】
     * 进程刚起来，不可能有任何活跃队友，所以那些 in_progress 一定是上一次没干完的——
     * 哪怕上一次是被 kill -9 打死的。
     *
     * 【为什么不用"退出时清理"代替】
     * 退出这条路有太多绕过方式（任务管理器 / IDE 停止 / 崩溃 / 断电），
     * 凡事依赖"退出时执行"的方案都不可靠。启动时收拾是唯一 100% 的。
     *
     * 退回用 Task.released()——它【状态和 owner 一起改】。只改状态不清 owner 的话，
     * 任务看着是 pending，但 pickUpWork 那边 "owner != null" 一样会跳过它。
     *
     * @return 收回了几个任务
     */
    public int recoverAbandoned() {
        lock.lock();
        try {
            int recovered = 0;
            for (Task task : listAll()) {
                if (task.status() == Task.Status.IN_PROGRESS) {
                    save(task.released());
                    recovered++;
                }
            }
            if (recovered > 0) {
                System.out.println("  [task] 收回 " + recovered + " 个上次没做完的任务");
            }
            return recovered;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 内部 ====================

    /** 当前所有"前置已完成"的 pending 任务 ID。解锁判断靠前后两次快照做差 */
    private Set<String> readyPendingIds() {
        Set<String> ready = new LinkedHashSet<>();
        for (Task task : listAll()) {
            if (task.status() == Task.Status.PENDING
                && !task.blockedBy().isEmpty()
                && incompleteDependencies(task).isEmpty()) {
                ready.add(task.id());
            }
        }
        return ready;
    }

    /**
     * 把 targetId 的依赖临时换成 blockedBy，从它出发沿 blockedBy 走，
     * 看能不能绕回自己。其它任务按磁盘上的现状走。
     *
     * 注意：光靠"只有 pending 且无 owner 才能加依赖"是挡不住环的——
     * A 加 B、B 加 A，两步之间两个任务都还是 pending，环照样能成。
     */
    private boolean hasCycleFrom(String targetId, Set<String> blockedBy) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(targetId);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            List<String> deps = current.equals(targetId)
                ? List.copyOf(blockedBy)
                : depsOf(current);
            for (String dep : deps) {
                if (dep.equals(targetId)) {
                    return true;
                }
                stack.push(dep);
            }
        }
        return false;
    }

    /** 读一条任务的依赖，任务不存在就当没有依赖 */
    private List<String> depsOf(String taskId) {
        Task task = load(taskId);
        return task == null ? List.of() : task.blockedBy();
    }

    private String newId() {
        return ID_PREFIX + UUID.randomUUID().toString()
            .replace("-", "")
            .substring(0, ID_RANDOM_LENGTH);
    }

    /**
     * 任务 ID → 目录内的安全路径。
     * ID 是模型直接给的（get_task / claim_task 的参数），所以要防路径遍历：
     * 含目录分隔符、或 normalize 后跑到目录外，一律拒绝。
     */
    private Path taskPath(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String filename = id + ".json";
        if (!filename.equals(Path.of(filename).getFileName().toString())) {
            return null;
        }
        Path path = taskDir.resolve(filename).normalize();
        return path.startsWith(taskDir) ? path : null;
    }

    /**
     * 任务的规范【单行表示】：ID、状态、负责人、标题，有依赖才附上 blockedBy。
     *
     * 和 toJson 并列，都是"任务的对外表示"——list_tasks 的工具结果、控制台打印、
     * （以后）system prompt 里的常驻段，三处都该用同一个格式，不然会漂移。
     */
    public static String render(List<Task> tasks) {
        StringBuilder sb = new StringBuilder();
        for (Task task : tasks) {
            sb.append(task.id()).append("  ")
              .append(task.status().label()).append("  ")
              .append(task.owner() == null ? "-" : task.owner()).append("  ")
              .append(task.subject());
            if (!task.blockedBy().isEmpty()) {
                sb.append("  blockedBy=").append(task.blockedBy());
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    /** 任务的规范序列化形式。落盘和 get_task 返回给模型都用它 */
    public static String toJson(Task task) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", task.id());
        node.put("subject", task.subject());
        node.put("description", task.description());
        node.put("status", task.status().label());
        if (task.owner() == null) {
            node.putNull("owner");
        } else {
            node.put("owner", task.owner());
        }
        ArrayNode deps = node.putArray("blockedBy");
        for (String dep : task.blockedBy()) {
            deps.add(dep);
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化任务失败: " + e.getMessage(), e);
        }
    }

    /** 解析失败返回 null（读不出的一条坏文件跳过，不拖垮整个列表） */
    private static Task fromJson(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            String id = node.path("id").asText("");
            if (id.isBlank()) {
                return null;
            }
            List<String> deps = new ArrayList<>();
            for (JsonNode dep : node.path("blockedBy")) {
                deps.add(dep.asText());
            }
            Task.Status status = Task.Status.from(node.path("status").asText(null));
            if (status == null) {
                // 状态读不出来时保守当成 pending，别把任务弄成不可用
                status = Task.Status.PENDING;
            }
            JsonNode owner = node.path("owner");
            return new Task(id,
                node.path("subject").asText(""),
                node.path("description").asText(""),
                status,
                owner.isNull() ? null : owner.asText(null),
                List.copyOf(deps));
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
