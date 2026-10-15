package org.example.agent;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池工厂。
 *
 * 按 CLAUDE.md 的规矩：禁止用 Executors 工具类创建，所以直接 new ThreadPoolExecutor，
 * 把参数一次配好。无状态纯工厂，所以不做单例——给无状态的工具类套 getInstance() 是白加一层。
 *
 * 每类后台工作各用一个池（bash 一个、将来记忆抽取一个），互不干扰：
 * bash 任务能占满 120 秒，和短任务共用一个池会互相饿死。
 */
public final class ThreadPools {

    private ThreadPools() {}

    /**
     * 造一个固定大小的线程池。
     *
     * @param name          线程名前缀，jstack 时能一眼看出是哪个池的线程
     * @param threads       核心 = 最大线程数
     * @param queueCapacity 有界队列容量
     */
    public static ThreadPoolExecutor create(String name, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
            // 核心 = 最大：固定大小，线程数不抖动。后台任务求稳，不求弹性
            threads, threads,
            0L, TimeUnit.MILLISECONDS,
            // 有界队列：不用无界。满了要能被发现，而不是无限堆积
            new LinkedBlockingQueue<>(queueCapacity),
            factory(name),
            // 队列满就抛，让工具把错误回给模型，模型自己知道该等一下。
            // 不能用 CallerRunsPolicy——那会让【调用者线程】去跑这个任务，
            // 在"后台任务"场景里等于阻塞主循环，正好是这一章要消除的东西
            new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 起一条常驻 daemon 线程——注意【不是池】。
     *
     * 池的模型是"一组线程轮流跑一堆短任务"，跑完归还给下一个。而常驻循环一提交就永不返回：
     * 那个 worker 会被永久认作忙（实际容量少一个），shutdown() 永远等不到它，
     * 队列容量和拒绝策略全用不上——是硬套，不是池不好。
     *
     * 但"线程要有名字、要 daemon"这两条要求跟池里是同一套，所以复用同一个 ThreadFactory。
     * 停止靠 interrupt()：调用方存下返回的 Thread，退出时 interrupt() + join()。
     *
     * @param name 线程名前缀。两次调用各拿一个独立的计数器，所以【同批线程必须用不同的 name】，
     *             否则会出现两条都叫 cron-1 的线程。反正 jstack 时也想一眼分清，正好。
     */
    public static Thread startDaemon(String name, Runnable loop) {
        Thread thread = factory(name).newThread(loop);
        thread.start();
        return thread;
    }

    /**
     * 统一命名 + daemon 的线程工厂。池和常驻线程对线程的要求相同，所以抽出来共用。
     */
    private static ThreadFactory factory(String name) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + seq.getAndIncrement());
            // daemon：主线程退出时不被 JVM 拦住。非 daemon 的话打完 exit 会卡住等它跑完
            thread.setDaemon(true);
            return thread;
        };
    }
}
