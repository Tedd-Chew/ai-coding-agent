package org.example.agent;

import org.example.Constants;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 后台任务管理器（单例）：慢操作丢线程池，主循环继续跑，后续轮次再收结果。
 *
 * 和 s11 原版的差别：原版是 bash 专用的（start 收 tool_use block，非 bash 直接报错）。
 * 这里改成收【标签 + 一段工作】，不关心工作是什么——bash 只是第一个使用者。
 * "只有 bash 能进后台"这条限制留在 BashTool 自己身上（只有它有那个参数）。
 *
 * 收集方式是【拉】不是【推】：主循环在固定时刻（下一轮 LLM 调用前）主动问"谁完成了"。
 * 所以不需要完成回调，isDone() 轮询就够。
 *
 * 已知缺口（等 BashTool 接进来时处理）：池里的线程是 daemon，进程退出会被直接杀掉。
 * 但 worker 起的【子进程】是独立的 OS 进程，线程死了它还活着。所以退出时还需要
 * 主动杀掉正在跑的子进程——那件事得由持有 Process 对象的那一层做，不是这里。
 */
public class BackgroundManager {

    /** 通知里只放开头这么多字，防止大输出灌爆上下文 */
    private static final int SUMMARY_CHAR_LIMIT = 500;

    /** 一个进行中的任务：标签（通知里显示）+ 结果句柄 */
    private record Pending(String label, Future<String> future) {}

    private static volatile BackgroundManager instance;

    private final ThreadPoolExecutor pool =
        ThreadPools.create("bg", Constants.BG_POOL_THREADS, Constants.BG_POOL_QUEUE);

    /** task_id → 进行中的任务。被 collect 收走后从这里移除 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private final AtomicInteger counter = new AtomicInteger();

    private BackgroundManager() {}

    public static BackgroundManager getInstance() {
        if (instance == null) {
            synchronized (BackgroundManager.class) {
                if (instance == null) {
                    instance = new BackgroundManager();
                }
            }
        }
        return instance;
    }

    /**
     * 提交一个后台任务，立刻返回任务 ID（不阻塞）。
     *
     * @param label 通知里显示给模型看的说明，比如原始命令
     * @param work  实际要跑的东西
     * @throws java.util.concurrent.RejectedExecutionException 队列满了。调用方 catch 后回给模型
     */
    public String start(String label, Supplier<String> work) {
        String taskId = String.format("bg_%04d", counter.incrementAndGet());
        // 显式转 Callable：submit 有 Runnable / Callable 两个重载，
        // 不转的话 () -> work.get() 两边都匹配，编译期歧义
        Future<String> future = pool.submit((Callable<String>) work::get);
        pending.put(taskId, new Pending(label, future));
        System.out.println("  [background] started " + taskId + ": " + label);
        return taskId;
    }

    /**
     * 收走所有已完成的任务，拼成通知文本。都在跑就返回空列表。
     * 取走即移除——同一个结果不会通知两次。
     */
    public List<String> collect() {
        List<String> notifications = new ArrayList<>();

        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Pending> entry = it.next();
            Future<String> future = entry.getValue().future();
            if (!future.isDone()) {
                continue;
            }
            it.remove();

            String taskId = entry.getKey();
            String status;
            String summary;
            try {
                summary = future.get();               // 已完成，立即返回
                status = "completed";
            } catch (ExecutionException e) {
                // 工作里抛的异常会被包成 ExecutionException，真正的异常在 getCause()
                Throwable cause = e.getCause();
                summary = "Error: " + (cause == null ? e.getMessage() : cause.getMessage());
                status = "failed";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                summary = "Error: interrupted";
                status = "failed";
            }

            notifications.add(
                "<task_notification>\n"
                + "  <task_id>" + taskId + "</task_id>\n"
                + "  <status>" + status + "</status>\n"
                + "  <command>" + entry.getValue().label() + "</command>\n"
                + "  <summary>" + truncate(summary, SUMMARY_CHAR_LIMIT) + "</summary>\n"
                + "</task_notification>");
            System.out.println("  [background] collected " + taskId + ": " + status);
        }
        return notifications;
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
