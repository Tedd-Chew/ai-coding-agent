package org.example.agent;

/**
 * 向用户索要一次授权的唯一入口。
 *
 * 为什么要有这个接口：Permission 的 Gate 3 原先直接读 System.in。那有三个毛病——
 *   1. 只有"正好持有 Scanner 的那条线程"问得了，队友线程问不了
 *   2. 两条线程同时读 stdin 会抢输入，回车被随机某一条吃掉
 *   3. 想换交互介质（窗口 / 网页）就得改 Permission 自己
 *
 * 抽成接口之后，Permission 只管"要不要问"，"怎么问"交给实现类。
 *
 * 返回值：true = 允许，false = 拒绝，null = 这个场景问不了（非交互线程）。
 * 调用方【必须】把 null 当成拒绝——失败关闭，不然定时任务会变成权限后门。
 */
public interface UserPrompt {

    /**
     * 问用户一次。实现方内部可以阻塞（窗口弹窗就是阻塞的），
     * 但必须保证多条线程同时问的时候【依次】弹，不能一起冒出来。
     */
    Boolean ask(String message);
}
