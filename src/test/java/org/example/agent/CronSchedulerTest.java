package org.example.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CronScheduler 的队列与交付语义测试。
 *
 * 全章最容易错的就是这一块，而且错了不报错：
 *   - 同一分钟内重复入队 → 任务被投 60 次
 *   - 交付完成前又入队 → 同一个任务跑两遍
 *   - ack / restore 搞反 → 要么丢任务，要么永远重投
 * 所以每个状态位单独钉一条用例。
 *
 * 【不测什么】两条常驻线程（pollLoop / queueLoop）和真实的时间流逝。
 * 那些靠 start() + 真等一分钟，慢且脆。这里全部通过直接调 pollDue(时刻) 驱动——
 * 调度逻辑本来就只依赖"传进来的那个时刻"，测试就能把时间当成普通参数喂进去。
 *
 * 【单例怎么隔离】CronScheduler / CronStore 都是单例，init 只生效一次，
 * 内存里的任务表没有对外清空的口子。所以约定：
 *   - 本类建的任务一律记进 created，@AfterEach 逐个 cancel
 *   - 用例里建的任务统一用 durable=false，用例之间不通过磁盘串味
 *   - 需要测持久化的那几条自己用 durable=true，@BeforeEach 会清掉目录
 */
class CronSchedulerTest {

    private static final Path CRON_DIR = Paths.get("target", "test-cron");

    /** 2026-10-11 是周日。CronScheduler 本身不关心星期，锚点跟另一个测试类保持一致 */
    private static LocalDateTime oct(int hour, int minute) {
        return LocalDateTime.of(2026, 10, 11, hour, minute);
    }

    private CronScheduler scheduler;

    /** 本用例建过的任务 ID，@AfterEach 拿它清场 */
    private final List<String> created = new ArrayList<>();

    @BeforeAll
    static void initStore() {
        CronStore.init(CRON_DIR);
    }

    @BeforeEach
    void reset() throws IOException {
        scheduler = CronScheduler.getInstance();
        // 上一个用例要是抛在半路，@AfterEach 还是跑了的；这里再兜一次队列残留
        scheduler.consumeDue();
        if (Files.isDirectory(CRON_DIR)) {
            try (Stream<Path> files = Files.list(CRON_DIR)) {
                for (Path path : files.toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @AfterEach
    void cancelCreated() {
        for (String id : created) {
            try {
                scheduler.cancel(id);
            } catch (IllegalArgumentException ignored) {
                // 用例自己已经 cancel 过了
            }
        }
        created.clear();
    }

    // ==================== 入队 ====================

    @Test
    @DisplayName("到点就入队，没到点不入队")
    void enqueuesOnlyWhenCronMatches() {
        schedule("0 9 * * *", "每天九点");

        scheduler.pollDue(oct(9, 30));      // 分钟不对
        assertEquals(0, consumeMine().size(), "9:30 不匹配 0 9 * * *");

        scheduler.pollDue(oct(9, 0));
        assertEquals(1, consumeMine().size(), "9:00 才匹配");
    }

    @Test
    @DisplayName("同一分钟内轮询多次，只入队一次（lastFired 挡住）")
    void sameMinuteEnqueuesOnce() {
        schedule("*/1 * * * *", "每分钟");

        // 轮询线程每秒转一圈，这一分钟里它会转 60 圈
        scheduler.pollDue(oct(9, 30));
        scheduler.pollDue(oct(9, 30));
        scheduler.pollDue(oct(9, 30));

        assertEquals(1, consumeMine().size(), "不记 lastFired 的话这里会是 3");
    }

    @Test
    @DisplayName("已入队但还没交付时，跨到下一分钟也不会重复入队（pendingDelivery 挡住）")
    void pendingDeliveryBlocksReenqueueAcrossMinutes() {
        schedule("*/1 * * * *", "每分钟");

        scheduler.pollDue(oct(9, 30));      // 入队
        scheduler.pollDue(oct(9, 31));      // 下一分钟，cron 又匹配 —— 但上一批还没交付

        assertEquals(1, consumeMine().size(), "pendingDelivery 就是干这个的");
    }

    // ==================== 两个状态位的分工 ====================

    @Test
    @DisplayName("交付完成后：同一分钟不再触发，下一分钟可以再触发")
    void twoFlagsGuardDifferentWindows() {
        schedule("*/1 * * * *", "每分钟");

        scheduler.pollDue(oct(9, 30));
        List<CronJob> first = consumeMine();
        assertEquals(1, first.size());
        scheduler.acknowledge(first);

        // 交付完了，pendingDelivery 已经清掉 —— 这时候只剩 lastFired 在挡
        scheduler.pollDue(oct(9, 30));
        assertEquals(0, consumeMine().size(),
            "只靠 pendingDelivery 的话这里会再投一次：它已经清了，得靠 lastFired 挡");

        // 下一分钟：两个都不挡了
        scheduler.pollDue(oct(9, 31));
        assertEquals(1, consumeMine().size());
    }

    // ==================== ack ====================

    @Test
    @DisplayName("周期任务交付后留在表里")
    void recurringStaysAfterAcknowledge() {
        CronJob job = schedule("*/1 * * * *", "每分钟");

        scheduler.pollDue(oct(9, 30));
        scheduler.acknowledge(consumeMine());

        assertTrue(hasJob(job.id()), "周期任务只清 pendingDelivery，不该被删掉");
    }

    @Test
    @DisplayName("一次性任务交付后从表里消失")
    void oneShotIsRemovedAfterAcknowledge() {
        CronJob job = schedule("*/1 * * * *", "只跑一次", false);

        scheduler.pollDue(oct(9, 30));
        scheduler.acknowledge(consumeMine());

        assertFalse(hasJob(job.id()), "一次性任务交付后就该没了");
        created.remove(job.id());       // 已经不存在，别让 @AfterEach 去 cancel
    }

    @Test
    @DisplayName("等交付期间被 cancel 掉的，acknowledge 时安静忽略")
    void acknowledgeIgnoresCancelledJob() {
        CronJob job = schedule("*/1 * * * *", "每分钟");
        scheduler.pollDue(oct(9, 30));
        List<CronJob> due = consumeMine();

        scheduler.cancel(job.id());
        created.remove(job.id());

        // 不减这个判断的话，这里会对一个已经不在表里的任务改状态 / 删文件
        scheduler.acknowledge(due);
    }

    // ==================== restore ====================

    @Test
    @DisplayName("交付失败退回队列后，能重新取到")
    void restorePutsJobBackInQueue() {
        schedule("*/1 * * * *", "每分钟");

        scheduler.pollDue(oct(9, 30));
        List<CronJob> due = consumeMine();
        scheduler.restore(due);         // 模拟"抢不到回合锁"或"那一轮跑挂了"

        assertEquals(1, consumeMine().size(), "退回队列后下一轮还能取到");
    }

    @Test
    @DisplayName("restore 不会把同一个任务放进去两次")
    void restoreIsIdempotent() {
        schedule("*/1 * * * *", "每分钟");

        scheduler.pollDue(oct(9, 30));
        List<CronJob> due = consumeMine();
        scheduler.restore(due);
        scheduler.restore(due);

        assertEquals(1, consumeMine().size());
    }

    // ==================== cancel ====================

    @Test
    @DisplayName("cancel 后从表里消失")
    void cancelRemovesFromTable() {
        CronJob job = schedule("*/5 * * * *", "检查构建");

        scheduler.cancel(job.id());
        created.remove(job.id());

        assertFalse(hasJob(job.id()));
    }

    @Test
    @DisplayName("cancel 会把正在队列里等交付的也摘掉")
    void cancelAlsoDrainsQueue() {
        CronJob job = schedule("*/1 * * * *", "每分钟");
        scheduler.pollDue(oct(9, 30));      // 已入队

        scheduler.cancel(job.id());
        created.remove(job.id());

        // 不摘的话，队列线程跑完会回来 ack 一个用户已经不想要的任务
        boolean stillQueued = scheduler.consumeDue().stream()
            .anyMatch(queued -> queued.id().equals(job.id()));
        assertFalse(stillQueued);
    }

    @Test
    @DisplayName("cancel 不存在的任务会抛")
    void cancelUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> scheduler.cancel("cron_deadbeef"));
    }

    // ==================== 入参校验 ====================

    @Test
    @DisplayName("表达式非法 / prompt 为空时直接抛，表里不留东西")
    void rejectsBadInputWithoutSideEffect() {
        int before = scheduler.listAll().size();

        assertThrows(IllegalArgumentException.class,
            () -> scheduler.schedule("99 * * * *", "不可能", true, false));
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.schedule("* * * * *", "   ", true, false));

        assertEquals(before, scheduler.listAll().size(), "建失败不该在表里留下半成品");
    }

    // ==================== 持久化 ====================

    @Test
    @DisplayName("durable 任务落盘，字段能原样读回来")
    void durableJobRoundTrips() {
        CronJob job = scheduler.schedule("*/5 * * * *", "检查构建", true, true);
        created.add(job.id());

        CronJob reloaded = reload(job.id());

        assertNotNull(reloaded, "落盘后应该读得回来");
        assertEquals("*/5 * * * *", reloaded.cron());
        assertEquals("检查构建", reloaded.prompt());
        assertTrue(reloaded.recurring());
        assertTrue(reloaded.durable());
        assertFalse(reloaded.pendingDelivery(), "还没到点，不该是待交付");
        assertNull(reloaded.lastFired());
    }

    @Test
    @DisplayName("非 durable 任务不落盘")
    void sessionJobIsNotPersisted() {
        CronJob job = schedule("*/5 * * * *", "只在这次会话里");

        assertNull(reload(job.id()), "durable=false 不该写进 .scheduled_tasks.json");
    }

    @Test
    @DisplayName("已到期未交付：pendingDelivery 会落盘 —— 至少一次交付就靠它跨重启")
    void pendingDeliveryIsPersisted() {
        CronJob job = scheduler.schedule("*/5 * * * *", "检查构建", true, true);
        created.add(job.id());

        scheduler.pollDue(oct(9, 30));      // 到点 → 入队，pendingDelivery 置 true

        CronJob reloaded = reload(job.id());
        assertNotNull(reloaded);
        assertTrue(reloaded.pendingDelivery(),
            "模型还没收到，这个标记必须在盘上；不然进程死在中途，这个任务就丢了");
        assertEquals("2026-10-11 09:30", reloaded.lastFired());
    }

    // ==================== 工具 ====================

    private CronJob schedule(String cron, String prompt) {
        return schedule(cron, prompt, true);
    }

    private CronJob schedule(String cron, String prompt, boolean recurring) {
        CronJob job = scheduler.schedule(cron, prompt, recurring, false);
        created.add(job.id());
        return job;
    }

    /**
     * 取走队列里【属于本用例】的任务，别人的原样放回去。
     * 直接 consumeDue() 会把别的用例排队等交付的任务一起吞掉，它们就再也回不来了。
     */
    private List<CronJob> consumeMine() {
        List<CronJob> mine = new ArrayList<>();
        List<CronJob> others = new ArrayList<>();
        for (CronJob job : scheduler.consumeDue()) {
            (created.contains(job.id()) ? mine : others).add(job);
        }
        if (!others.isEmpty()) {
            scheduler.restore(others);
        }
        return mine;
    }

    private boolean hasJob(String id) {
        return scheduler.listAll().stream().anyMatch(job -> job.id().equals(id));
    }

    /** 从磁盘重读那一条 */
    private CronJob reload(String id) {
        return CronStore.getInstance().load().stream()
            .filter(job -> job.id().equals(id))
            .findFirst()
            .orElse(null);
    }
}
