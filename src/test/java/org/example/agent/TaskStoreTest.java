package org.example.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskStore 的边界条件测试。
 *
 * 为什么必须写这些：这些路径【模型永远碰不到】——
 * 它不会自己造环、不会让任务依赖自己、不会传非法 ID、不会给已认领的任务改依赖。
 * 防线只在异常输入下生效，而模型不产生异常输入，所以只能靠单测覆盖。
 */
class TaskStoreTest {

    private static final Path TASK_DIR = Paths.get("target", "test-tasks");

    private TaskStore store;

    @BeforeAll
    static void initStore() {
        // TaskStore 是单例，init 只生效一次 → 整个测试类共用一个目录，
        // 靠 @BeforeEach 清空来保证用例之间互不干扰
        TaskStore.init(TASK_DIR);
    }

    @BeforeEach
    void freshStore() throws IOException {
        store = TaskStore.getInstance();
        if (Files.isDirectory(TASK_DIR)) {
            try (Stream<Path> files = Files.list(TASK_DIR)) {
                for (Path path : files.toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /** 建个任务，省掉 description */
    private Task task(String subject) {
        return store.create(subject, "");
    }

    // ==================== 依赖图的防线 ====================

    @Test
    @DisplayName("幂等：同一个依赖加两次，blockedBy 只有一项")
    void updateDependenciesIsIdempotent() {
        Task api = task("写 API");
        Task schema = task("建表");

        store.updateDependencies(api.id(), List.of(schema.id()));
        store.updateDependencies(api.id(), List.of(schema.id()));

        assertEquals(List.of(schema.id()), store.require(api.id()).blockedBy());
    }

    @Test
    @DisplayName("间接环会被拒绝，而且不落盘")
    void rejectsIndirectCycle() {
        Task a = task("A");
        Task b = task("B");
        Task c = task("C");
        store.updateDependencies(b.id(), List.of(a.id()));   // B 依赖 A
        store.updateDependencies(c.id(), List.of(b.id()));   // C 依赖 B

        // 再加 A 依赖 C，就闭合了 A → B → C → A
        assertThrows(IllegalArgumentException.class,
            () -> store.updateDependencies(a.id(), List.of(c.id())));

        // 关键：失败不能留下半条边。整批校验通过才保存
        assertEquals(List.of(), store.require(a.id()).blockedBy());
    }

    @Test
    @DisplayName("任务不能依赖自己")
    void rejectsSelfDependency() {
        Task a = task("A");

        assertThrows(IllegalArgumentException.class,
            () -> store.updateDependencies(a.id(), List.of(a.id())));
    }

    @Test
    @DisplayName("依赖一个不存在的任务会被拒绝")
    void rejectsMissingDependency() {
        Task a = task("A");

        assertThrows(IllegalArgumentException.class,
            () -> store.updateDependencies(a.id(), List.of("task_deadbeef")));
    }

    @Test
    @DisplayName("已被认领的任务不能再改依赖")
    void rejectsDependencyChangeAfterClaim() {
        Task a = task("A");
        Task b = task("B");
        store.claim(a.id(), TaskStore.DEFAULT_OWNER);

        assertThrows(IllegalStateException.class,
            () -> store.updateDependencies(a.id(), List.of(b.id())));
    }

    // ==================== 状态机 ====================

    @Test
    @DisplayName("前置没完成时不能认领")
    void claimRejectedWhenBlocked() {
        Task schema = task("建表");
        Task api = task("写 API");
        store.updateDependencies(api.id(), List.of(schema.id()));

        assertThrows(IllegalStateException.class,
            () -> store.claim(api.id(), TaskStore.DEFAULT_OWNER));
    }

    @Test
    @DisplayName("完成前置会解锁下游；解锁 ≠ 开始")
    void completeUnblocksDownstream() {
        Task schema = task("建表");
        Task api = task("写 API");
        store.updateDependencies(api.id(), List.of(schema.id()));

        store.claim(schema.id(), TaskStore.DEFAULT_OWNER);
        List<String> unblocked = store.complete(schema.id(), TaskStore.DEFAULT_OWNER);

        assertEquals(List.of("写 API"), unblocked);
        // 被解锁只是"能开工了"，不会自己变成 in_progress
        assertEquals(Task.Status.PENDING, store.require(api.id()).status());
    }

    // ==================== 输入安全 ====================

    @Test
    @DisplayName("任务 ID 不能用来做路径遍历")
    void rejectsPathTraversalId() {
        // ID 是模型直接给的参数，所以 taskPath() 必须挡住目录分隔符
        assertNull(store.load("../../etc/passwd"));
        assertNull(store.load("..\\..\\windows\\system32\\config"));
    }

    @Test
    @DisplayName("create 不去重：同名任务会生成两个不同 ID（已知行为，不是 bug）")
    void createDoesNotDeduplicate() {
        Task first = task("写测试");
        Task second = task("写测试");

        assertNotEquals(first.id(), second.id());
        assertEquals(2, store.listAll().size());
    }

    // ==================== 上次没干完的任务（s13） ====================

    @Test
    @DisplayName("recoverAbandoned：in_progress 退回 pending【并且清掉 owner】，已完成的不动")
    void recoverAbandonedResetsInProgress() {
        Task abandoned = task("认领了但没做完");
        Task done = task("做完了");
        store.claim(abandoned.id(), "alice");
        store.claim(done.id(), "bob");
        store.complete(done.id(), "bob");

        assertEquals(1, store.recoverAbandoned(), "只该收回那一个 in_progress 的");

        Task reset = store.require(abandoned.id());
        assertEquals(Task.Status.PENDING, reset.status());
        // owner 必须一起清 —— 只改状态的话，pickUpWork 那边 "owner != null" 一样会跳过它，
        // 任务看着能开工、实际是个僵尸
        assertNull(reset.owner());

        // 已经完成的不能被退回去
        assertEquals(Task.Status.COMPLETED, store.require(done.id()).status());
    }

    @Test
    @DisplayName("退回之后能被别人重新认领（owner 是清干净的）")
    void recoveredTaskCanBeClaimedAgain() {
        Task abandoned = task("被别人接手");
        store.claim(abandoned.id(), "alice");

        store.recoverAbandoned();
        Task again = store.claim(abandoned.id(), "bob");

        assertEquals("bob", again.owner());
        assertEquals(Task.Status.IN_PROGRESS, again.status());
    }

    // ==================== 并发（s13） ====================

    @Test
    @DisplayName("并发认领：多个队友抢同一个任务，只有一个能成功")
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        Task contest = task("只能被认领一次");

        int workers = 16;
        CountDownLatch fire = new CountDownLatch(1);      // 让所有线程尽量同时冲进去
        CountDownLatch finished = new CountDownLatch(workers);
        AtomicInteger winners = new AtomicInteger();

        for (int i = 0; i < workers; i++) {
            String owner = "teammate-" + i;
            new Thread(() -> {
                try {
                    fire.await();
                    store.claim(contest.id(), owner);
                    winners.incrementAndGet();
                } catch (Exception expected) {
                    // 抢不到是正常路径，不用管
                } finally {
                    finished.countDown();
                }
            }).start();
        }

        fire.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "有线程卡住了");

        // 没有 lock 的话：大家都读到 PENDING、都通过校验、都落盘，winners 会是好几个。
        // 注意这条用例是"钉住契约"而不是"稳定复现 bug"——去掉锁以后它大概率红，但不是每次必红，
        // 因为结果取决于线程读文件的先后。
        assertEquals(1, winners.get(), "同一个任务只能被认领一次");

        Task claimed = store.require(contest.id());
        assertEquals(Task.Status.IN_PROGRESS, claimed.status());
        assertTrue(claimed.owner().startsWith("teammate-"), claimed.owner());
    }

    @Test
    @DisplayName("并发完成：16 个线程重复完成同一个任务，只成功一次")
    void concurrentCompleteHasExactlyOneWinner() throws Exception {
        Task done = task("只能被完成一次");
        store.claim(done.id(), TaskStore.DEFAULT_OWNER);

        int workers = 16;
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(workers);
        AtomicInteger winners = new AtomicInteger();

        for (int i = 0; i < workers; i++) {
            new Thread(() -> {
                try {
                    fire.await();
                    store.complete(done.id(), TaskStore.DEFAULT_OWNER);
                    winners.incrementAndGet();
                } catch (Exception expected) {
                    // 第二次开始就该报"当前是 completed，不能完成"
                } finally {
                    finished.countDown();
                }
            }).start();
        }

        fire.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "有线程卡住了");

        assertEquals(1, winners.get());
    }
}
