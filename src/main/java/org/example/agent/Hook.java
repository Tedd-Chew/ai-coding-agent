package org.example.agent;
/**
 * Hook 回调接口
 * 返回 null = 不干预
 * 返回非 null = 想表达什么（阻止 / 强制 /
 注入）
 */
@FunctionalInterface
public interface Hook {
    Object call(Object... args);
}
