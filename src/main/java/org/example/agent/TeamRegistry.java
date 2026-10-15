package org.example.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃队友表（单例）。只回答一件事：现在有谁活着、按名字能不能找到他。
 *
 * 一个进程就一支团队，所以是单例——和 MessageBus / TaskStore 一个路子。
 */
public class TeamRegistry {

    private static volatile TeamRegistry instance;

    private final Map<String, Teammate> active = new ConcurrentHashMap<>();

    private TeamRegistry() {}

    public static TeamRegistry getInstance() {
        if (instance == null) {
            synchronized (TeamRegistry.class) {
                if (instance == null) {
                    instance = new TeamRegistry();
                }
            }
        }
        return instance;
    }

    /** 谁调：起队友时登记 */
    public void register(Teammate teammate) {
        active.put(teammate.name(), teammate);
    }

    /** 谁调：Teammate.run() 的 finally —— 线程退出，摘掉 */
    public void unregister(String name) {
        active.remove(name);
    }

    /** 谁调：review_plan / request_shutdown —— 先按名字找到人，再摸到他的 gate */
    public Teammate get(String name) {
        return active.get(name);
    }

    /** 谁调：list_teammates */
    public List<String> names() {
        return List.copyOf(active.keySet());
    }
}
