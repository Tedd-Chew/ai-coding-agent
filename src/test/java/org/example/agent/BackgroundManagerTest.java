package org.example.agent;

import org.example.agent.tools.BashTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * s11 后台任务的机制测试。不碰 LLM，所以不用 API key、不花 token。
 *
 * 这些是"行为对不对"的验证；"Agent 循环有没有被阻塞"那种端到端的，
 * 得在 IDEA 里跑真的 Main 才看得到。
 */
class BackgroundManagerTest {

    private static final long AWAIT_MILLIS = 10_000;

    // ==================== BackgroundManager ====================

    @Test
    @Timeout(15)
    @DisplayName("start 立刻返回，不等任务跑完")
    void startReturnsImmediately() {
        long begin = System.currentTimeMillis();

        String taskId = BackgroundManager.getInstance().start("sleepy", () -> {
            sleep(800);
            return "done";
        });

        long elapsed = System.currentTimeMillis() - begin;
        assertTrue(taskId.startsWith("bg_"), "任务 ID 应该带 bg_ 前缀，实际是 " + taskId);
        assertTrue(elapsed < 300, "start 不该阻塞，实际耗时 " + elapsed + "ms");

        awaitNotification(taskId);   // 等它跑完，顺便清理
    }

    @Test
    @Timeout(15)
    @DisplayName("任务完成后能收到通知，状态是 completed")
    void collectReportsCompleted() {
        String taskId = BackgroundManager.getInstance().start("echo ok", () -> "ok");

        String notification = awaitNotification(taskId);

        assertTrue(notification.contains("<status>completed</status>"),
            "通知里应该是 completed：\n" + notification);
        assertTrue(notification.contains("<summary>ok</summary>"),
            "通知里应该带上返回值：\n" + notification);
    }

    @Test
    @Timeout(15)
    @DisplayName("任务抛异常时状态是 failed，消息取自 getCause()")
    void collectReportsFailed() {
        String taskId = BackgroundManager.getInstance().start("boom", () -> {
            throw new IllegalStateException("命令炸了");
        });

        String notification = awaitNotification(taskId);

        assertTrue(notification.contains("<status>failed</status>"),
            "通知里应该是 failed：\n" + notification);
        assertTrue(notification.contains("命令炸了"),
            "失败消息该是真正的原因，不是 ExecutionException：\n" + notification);
    }

    @Test
    @Timeout(15)
    @DisplayName("通知取走即移除，不会送第二次")
    void collectDeliversOnlyOnce() {
        String taskId = BackgroundManager.getInstance().start("once", () -> "only-once");
        awaitNotification(taskId);

        // 已经收过一次，再去收就不该有它了
        for (String later : BackgroundManager.getInstance().collect()) {
            assertFalse(later.contains(taskId),
                "同一个任务不该通知两次：\n" + later);
        }
    }

    // ==================== BashTool ====================

    @Test
    @Timeout(30)
    @DisplayName("bash 正常跑完，输出和 exit code 都在")
    void bashReturnsOutputAndExitCode() {
        String result = new BashTool().execute("{\"command\":\"echo hello\"}");

        assertTrue(result.contains("hello"), "应该带回输出：\n" + result);
        assertTrue(result.contains("[exit code: 0]"), "应该带 exit code：\n" + result);
    }

    @Test
    @Timeout(30)
    @DisplayName("bash 非零退出时 exit code 如实反映")
    void bashReportsNonZeroExit() {
        String result = new BashTool().execute("{\"command\":\"exit 3\"}");

        assertTrue(result.contains("[exit code: 3]"), "exit code 应该是 3：\n" + result);
    }

    @Test
    @Timeout(30)
    @DisplayName("bash 没有输出时给 (no output)，不是空串")
    void bashHandlesEmptyOutput() {
        String result = new BashTool().execute("{\"command\":\"ver > nul\"}");

        assertTrue(result.contains("(no output)"), "空输出要有占位：\n" + result);
    }

    // ==================== 工具 ====================

    /** 轮询 collect 直到拿到这个 taskId 的通知，最多等 AWAIT_MILLIS */
    private static String awaitNotification(String taskId) {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            List<String> notifications = BackgroundManager.getInstance().collect();
            for (String notification : notifications) {
                if (notification.contains(taskId)) {
                    return notification;
                }
            }
            sleep(50);
        }
        fail("等不到 " + taskId + " 的通知");
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
