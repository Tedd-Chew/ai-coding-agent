package org.example.agent;

import org.example.entity.Message;

/**
 * 默认 hook 注册器
 * 把 Agent 的"默认行为"集中到这里，Agent 本身不感知
 */
public class HookConfig {

    /** PostToolUse 大输出告警阈值（字符）。对齐原项目的 100000，太小会刷屏淹没真正的信号 */
    private static final int LARGE_OUTPUT_WARN_CHARS = 100_000;

    /**
     * 注册 5 个默认 hook：
     * - UserPromptSubmit: 打印用户输入 + 工作目录
     * - PreToolUse: Permission 检查（s03 逻辑）
     * - PreToolUse: 工具名日志
     * - PostToolUse: 大输出警告
     * - Stop: 总结
     */
    public static void registerDefaults(HookRegistry hookRegistry, UserPrompt prompt) {

        // 1. UserPromptSubmit: 打印用户输入 + 工作目录
        hookRegistry.register("UserPromptSubmit", args -> {
            String userSubmit = (String) args[0];
            System.out.println("[User: ]" + userSubmit);
            System.out.println("[Workdir: ]" + System.getProperty("user.dir"));
            return null;
        });

        // 2. PreToolUse: Permission 检查（s03 逻辑搬过来）
        hookRegistry.register("PreToolUse", args -> {
            String toolName = (String) args[0];
            String arguments = (String) args[1];
            if (!Permission.check(toolName, arguments, prompt)) {
                return "Permission denied";
            }
            return null;
        });

        // 3. PreToolUse: 工具名日志
        hookRegistry.register("PreToolUse", args -> {
            String toolName = (String) args[0];
            System.out.println("[" + toolName + ":]");
            return null;
        });

        // 4. PostToolUse: 大输出警告
        hookRegistry.register("PostToolUse", args -> {
            String toolName = (String) args[0];
            String result = (String) args[1];
            if (result != null && result.length() > LARGE_OUTPUT_WARN_CHARS) {
                System.out.println("[HOOK PostToolUse] Large output from "
                    + toolName + ": " + result.length() + " chars");
            }
            return null;
        });

        // 5. Stop: 总结
        hookRegistry.register("Stop", args -> {
            @SuppressWarnings("unchecked")
            java.util.List<Message> history = (java.util.List<Message>) args[0];
            long toolCount = history.stream().filter(Message::hasToolCalls).count();
            System.out.println("[HOOK Stop] Session used " + toolCount + " tool calls");
            return null;
        });
    }
}
