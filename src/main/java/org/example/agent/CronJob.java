package org.example.agent;

/**
 * 一个定时任务。
 *
 * 七个字段分两类：前五个是「配置」，后两个是「交付状态」——后者是 at-least-once 的全部秘密。
 *
 * - {@code pendingDelivery}：已到期、但模型还没收到。pollDue() 见到它就跳过（不会重复入队）；
 *   它同时会被写进 .scheduled_tasks.json，所以进程重启后能重新入队——"至少一次"就是这么来的。
 * - {@code lastFired}：上次触发的分钟标记（"yyyy-MM-dd HH:mm"）。防的是轮询线程在一分钟里
 *   转了好几圈、把同一个任务重复入队（秒级轮询 vs 分钟级表达式，必然撞上）。
 *
 * 不可变 record，状态变化靠 wither 返回新对象——和 {@link Task} 一个路子，
 * 免得"某人手里还攥着旧引用，状态却已经被别人改了"。
 */
public record CronJob(String id,
                      String cron,
                      String prompt,
                      boolean recurring,
                      boolean durable,
                      boolean pendingDelivery,//这个字段用于表明任务已经在待运行队列里了，不需要重复加入
                      String lastFired//这个字段用于记录上一次触发的时间，如果和本次时间一样，就不再重复触发（在同一分钟内）
) {

    /** 任务 ID 前缀，和 Task 的 "task_" 对应 */
    public static final String ID_PREFIX = "cron_";

    /**
     * 定时投递注入 history 时加的前缀。
     * 模型靠它认出这是"到点自动送进来的"，不是用户正在说话——两种消息同为 user 角色，
     * 不区分的话模型会以为用户就说了这么一句。
     */
    public static final String PROMPT_PREFIX = "[Scheduled] ";

    public CronJob withPendingDelivery(boolean value) {
        return new CronJob(id, cron, prompt, recurring, durable, value, lastFired);
    }

    public CronJob withLastFired(String value) {
        return new CronJob(id, cron, prompt, recurring, durable, pendingDelivery, value);
    }

    /** 交付给模型时实际注入的文本 */
    public String deliveryText() {
        return PROMPT_PREFIX + prompt;
    }

    /** 单行表示，给 list_crons 和日志用 */
    public String render() {
        return id + ": " + cron + " -> " + prompt
            + " [" + (recurring ? "recurring" : "one-shot")
            + ", " + (durable ? "durable" : "session") + "]";
    }
}
