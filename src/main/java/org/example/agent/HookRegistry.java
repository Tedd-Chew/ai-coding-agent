package org.example.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 注册表（单例）：event 名 → 回调列表
 * 对应 learn-claude-code s04 的 hook registry
 *
 * 仿 ToolManager 的 DCL 单例模式
 */
public class HookRegistry {

    private final Map<String, List<Hook>> hooks = new HashMap<>();
    private static volatile HookRegistry instance;

    private HookRegistry() {}

    /**
     * 获取单例
     */
    public static HookRegistry getInstance() {
        if (instance == null) {
            synchronized (HookRegistry.class) {
                if (instance == null) {
                    instance = new HookRegistry();
                }
            }
        }
        return instance;
    }

    /**
     * 注册一个 hook,给main自定义hook用的。
     */
    public void register(String event, Hook hook) {
        hooks.computeIfAbsent(event, k -> new ArrayList<>()).add(hook);
    }

    /**
     * 触发指定事件的所有 hook
     * @return 第一个返回非 null 的 hook 的结果；都返回 null 则返回 null
     */
    public Object trigger(String event, Object... args) {
        List<Hook> list = hooks.get(event);
        if (list == null) return null;
        for (Hook hook : list) {
            Object result = hook.call(args);
            if (result != null) return result;
        }
        return null;
    }
}
