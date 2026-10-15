package org.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tools.FileToolUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 三道闸门权限系统
 * 对应 learn-claude-code s03 的 Permission
 * Gate 1: 硬拒绝（永远禁止，命中立即拒）
 * Gate 2: 规则匹配（命中后问用户）
 * Gate 3: 用户审批（y/N）
 */

public class Permission {//纯工具函数，全用static

    /**
     * 标记"这条线程能弹交互审批"。
     *
     * Main 启动时给自己的线程打一次；s13 起队友线程时同样要打
     * （队友该有和 Lead 一样向用户要权限的机会）。cron 调度线程【永远不打】
     * ——它跑到 Gate 3 就是问不了，失败关闭，见 check()。
     *
     * 用 ThreadLocal 而不是"记住某一条 Thread 对象"：线程结束了标记跟着走，
     * 不会在某个集合里越攒越多。
     */
    private static final ThreadLocal<Boolean> INTERACTIVE = ThreadLocal.withInitial(() -> false);

    /** 给当前线程开交互审批的权限。必须在真正开始跑之前调用 */
    public static void markInteractive() {
        INTERACTIVE.set(true);
    }

    /** 当前线程能不能弹窗问用户。非交互线程走到这里必须【失败关闭】，见 check()。 */
    private static boolean canAskUser() {
        return INTERACTIVE.get();
    }

    /** 参数预览的截断长度。write_file 的 args 里带着整个文件内容，不截会淹掉弹窗 */
    private static final int ARG_PREVIEW_CHARS = 300;

    //Gate 1 : 执行BashTool时的硬拒绝清单（Unix + Windows 共用）
    private static final List<String> DENY_LIST = List.of(
            // Unix
            "rm -rf /", "rm -rf /*", "sudo", "shutdown", "reboot",
            "mkfs", "dd if=", "> /dev/sda",
            // Windows
            "format ", "rd /s", "rmdir /s", "del /f /s",
            "diskpart", "reg delete", "cipher /w"
    );
    //Gate 2 : 检测一些指令，看是否需要我们审批
    //内部record，永远为static
    private record Rule(List<String> tools, Predicate<Map<String,Object>> check,String message){

        boolean appliesTo(String toolName){
            return tools.contains(toolName);
        }
    }

    //Gate 2 的规则集合
    private static final List<Rule> RULES = List.of(
            // file 工具：路径超出工作目录
            new Rule(
                    List.of("read_file", "write_file", "edit_file"),
                    args -> {
                        String path = (String) args.get("path");
                        return path != null && isOutsideWorkSpace(path);
                    },
                    "Access outside workspace"
            ),
            // bash 工具：破坏性命令
            new Rule(
                    List.of("bash"),
                    args -> {
                        String command = (String) args.get("command");
                        return command != null && isDestructiveCommand(command);
                    },
                    "Potentially destructive command"
            ),
            // 写操作：无条件询问（新建和覆盖都问，避免 Agent 悄悄改文件）
            // 变体：只想在"覆盖已存在文件"时问，把 check 换成——
            //   args -> {
            //       String path = (String) args.get("path");
            //       if (path == null) return false;
            //       try { return Files.isRegularFile(FileToolUtils.resolvePath(path)); }
            //       catch (Exception e) { return true; }
            //   }
            new Rule(
                    List.of("write_file", "edit_file"),
                    args -> true,
                    "Write operation"
            )
    );

    /**
     * 路径是否在工作目录外
     */
    private static boolean isOutsideWorkSpace(String path)
    {
        try{
            Path resolvedPath = FileToolUtils.resolvePath(path);
            return !resolvedPath.startsWith(FileToolUtils.getWorkdir());
        }catch (Exception e)
        {
            return true;//发生异常，说明没找到这个目录，当在外面处理。
        }
    }
    /**
     * 参数的简短预览，给日志和弹窗用。
     * write_file 的 args 里带着整个文件内容，原样打出来会淹掉窗口，所以截断。
     */
    private static String preview(Map<String, Object> args) {
        String text = String.valueOf(args);
        return text.length() <= ARG_PREVIEW_CHARS
            ? text
            : text.substring(0, ARG_PREVIEW_CHARS) + "…";
    }

    private static boolean isDestructiveCommand(String command)
    {
        String lowerCaseCommand = command.toLowerCase();
        String[] tokens = lowerCaseCommand.split("[;&|]");//按 ; & | 切分命令链
        for(String token : tokens)
        {
            String trimmedToken = token.trim();
            if (trimmedToken.matches("rm(\\s.*)?") ||
                    trimmedToken.matches("del(\\s.*)?")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gate 1: 硬拒绝检查
     */
    private static boolean isHardDenied(String toolName, Map<String, Object> args) {
        if (!"bash".equals(toolName)) return false;
        String command = (String) args.get("command");
        if (command == null) return false;
        String lower = command.toLowerCase();
        for (String pattern : DENY_LIST) {
            if (lower.contains(pattern.toLowerCase())) {
                System.out.println("\n[blocked] '" + pattern + "' is on the deny list");
                // 带上命令原文：匹配用的是 contains，命中不等于命令真危险（"grep shutdown log" 也会中），
                // 不留原文的话事后没法判断是哪一条、误杀了什么。
                System.out.println("   命令: " + command);
                return true;
            }
        }
        return false;
    }

    /**
     * 主入口：检查权限，返回是否放行
     * @param toolName 工具名
     * @param argsJson LLM 传入的 JSON 字符串
     * @param prompt   用于 Gate 3 问用户。现在是 Swing 弹窗，将来换网页也只换实现类
     */
    public static boolean check(String toolName, String argsJson, UserPrompt prompt) {
        // 解析参数
        Map<String, Object> args;
        try {
            args = new ObjectMapper().readValue(argsJson, Map.class);
        } catch (Exception e) {
            System.out.println("[error] Failed to parse arguments: " + e.getMessage());
            return false;
        }

        // Gate 1: 硬拒绝
        if (isHardDenied(toolName, args)) {
            return false;
        }

        // Gate 2 + 3: 规则匹配 → 用户审批
        for (Rule rule : RULES) {
            if (rule.appliesTo(toolName) && rule.check.test(args)) {
                // Gate 3 只有交互线程做得了。非交互线程（s12 的定时回合）【失败关闭】——
                // 问不了就当没批准，直接拒。
                //
                // 方向绝不能反：如果改成"问不了就放行"，那把危险操作塞进 schedule_cron 的
                // prompt 里就能免审批执行，Gate 1/2 全部作废——定时任务会变成权限后门。
                //
                // 注意 Gate 1（硬拒绝）和 Gate 2（规则匹配）在调度线程里【照跑】，
                // 失效的只有"问用户"这一步。
                if (!canAskUser()) {
                    System.out.println("\n[permission] " + rule.message);
                    System.out.println("   Tool: " + toolName + "(" + preview(args) + ")");
                    System.out.println("[permission] denied (non-interactive): " + toolName
                        + " —— 非交互线程（定时回合）无法请求交互审批");
                    return false;
                }

                System.out.println("\n[permission] " + rule.message
                    + "  " + toolName + "(" + preview(args) + ")");

                // 问用户。【窗口实现内部是模态弹窗】：阻塞当前线程直到点掉为止，
                // 而且多条线程同时问会依次弹（见 SwingPrompt 的类注释）。
                // 队友线程也是走到这里来问的——它和 Lead 有同样的权限。
                Boolean allowed = prompt.ask(
                    rule.message + "\n\n工具：" + toolName + "\n参数：" + preview(args)
                        + "\n\n允许这一次操作吗？");
                if (allowed == null || !allowed) {
                    // 必须留痕：静默 return false 时，模型只收到一句 "Permission denied"，
                    // 用户也看不出刚才那次拒绝是怎么来的（见 ISSUES 一-8）
                    System.out.println("[permission] denied: " + toolName);
                    return false;
                }
                break;// 一个规则命中就够
            }
        }

        return true;// 三道闸门都没命中 → 放行
    }
}
