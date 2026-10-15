package org.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * 给工具套一层「计划闸门」：写操作执行前先问一句 gate 批了没。
 *
 * 只拦会改动工作区的三个工具：bash / write_file / edit_file。
 * 读操作（read_file / glob / list_tasks）不拦——计划没批也该允许它先看看现场。
 *
 * 拦的方式是【返回一句话】而不是抛异常：模型收到 "Blocked: ..." 之后自己能改主意
 * （去提交计划，或者换一种做法）。没交的会去交、在等的会去 wait、被驳回的会去改，
 * 三种情况的动作都对，所以不需要把状态分得更细。
 *
 * 【为什么做成包装器，而不是在 Agent 里加个 if】
 * 要拦的时机只有一个——工具真正执行的那一行（Agent.runAgent 里的 executeTool）。
 * Agent 是通用引擎，不该知道「计划闸门」这种东西存在。
 *
 * 【为什么不用现成的 PreToolUse hook 拦】
 * HookRegistry 是全局单例，里面的 hook 对【所有 agent】都触发。队友 alice 注册的
 * 闸门检查，Lead 调 write_file 时也会跑——Lead 被自己队友的闸门拦住。
 * 要让它只对 alice 生效，hook 里得知道「现在是谁在调」，而那正是 ISSUES 一-6 记的坑
 * （hook 回调拿不到调用者身份）。走那条路要么加 ThreadLocal 传身份、要么把
 * HookRegistry 改成每个 agent 一份，比包一层麻烦得多。
 *
 * 包装器把 gate 闭包在【工具实例】上——而队友的工具列表本来就是每人组一份的
 * （claim_task 得带名字），所以顺手包一层，零额外机制。
 */
public class GatedTool implements Tool {

    /**
     * 被拦的工具。
     *
     * 前三个是原书 _run_teammate_tool 里拦的；complete_task 是原书在它的 handler 内部拦的
     * （gate 不是 approved 就不许完成）——我们挪到这儿，一个闸门只管一处。
     *
     * complete_task 为什么也要拦：它的语义是"宣布这件事做完了"。计划没批 = 还没资格动这件事，
     * 自然也还没资格宣布它完了。而且不拦的话队友能自己标记完成 → 解绑 → 去抢新活，绕开审批。
     */
    private static final Set<String> GATED_TOOLS =
        Set.of("bash", "write_file", "edit_file", "complete_task");

    private static final String BLOCKED = "Blocked: 计划还没被 Lead 批准。";

    private final Tool delegate;
    private final PlanGate gate;

    public GatedTool(Tool delegate, PlanGate gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        return delegate.parametersSchema(mapper);
    }

    @Override
    public String execute(String input) {
        if (GATED_TOOLS.contains(delegate.name()) && !gate.allowsWrites()) {
            return BLOCKED;
        }
        return delegate.execute(input);
    }
}
