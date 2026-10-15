package org.example.agent;

import org.example.agent.tools.BashTool;
import org.example.agent.tools.CancelCronTool;
import org.example.agent.tools.ClaimTaskTool;
import org.example.agent.tools.CompactTool;
import org.example.agent.tools.CompleteTaskTool;
import org.example.agent.tools.CreateTaskTool;
import org.example.agent.tools.CurrentTimeTool;
import org.example.agent.tools.EditFileTool;
import org.example.agent.tools.GetTaskTool;
import org.example.agent.tools.GlobTool;
import org.example.agent.tools.ListCronsTool;
import org.example.agent.tools.ListTasksTool;
import org.example.agent.tools.ListTeammatesTool;
import org.example.agent.tools.LoadSkillTool;
import org.example.agent.tools.ReadFileTool;
import org.example.agent.tools.RequestShutdownTool;
import org.example.agent.tools.ReviewPlanTool;
import org.example.agent.tools.ScheduleCronTool;
import org.example.agent.tools.SendMessageTool;
import org.example.agent.tools.SpawnTeammateTool;
import org.example.agent.tools.SubmitPlanTool;
import org.example.agent.tools.TaskTool;
import org.example.agent.tools.UpdateTaskTool;
import org.example.agent.tools.WriteFileTool;
import org.example.service.ChatService;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具清单工厂：给谁组谁的列表。
 *
 * 三个方法摆在一起，"谁有什么工具"一眼看完；加一个新工具只有一处要改。
 *
 * 【为什么不是"注册表 + 按角色筛"】
 * 有些工具是【每个队友一份】的——claim_task / complete_task 要知道自己叫什么，
 * submit_plan / send_message 要知道是谁在说。它们根本进不了全局注册表。
 * 所以这里直接 new。无状态的那些每次 new 也无所谓。
 *
 * 【为什么不用黑名单】
 * list_tasks 要给 Lead 和队友、但不给 subagent。黑名单只能表达"除了这些全给"，
 * 说不出"给 A 和 C，不给 B"。所以换成白名单——直接列出来。
 */
public final class ToolManager {

    private ToolManager() {}

    /**
     * Lead 用的全部工具。
     *
     * 注意列表里【没有】 SubmitPlanTool —— 交计划是队友的事，Lead 不给自己交计划。
     */
    public static List<Tool> forLead(ChatService chatService, HookRegistry hooks) {
        List<Tool> tools = new ArrayList<>(forSubagent());

        // 只有 Lead 能再起 subagent。subagent 自己拿不到 TaskTool，所以不会无限递归
        tools.add(new TaskTool(chatService, forSubagent(), hooks));

        // 任务系统。Lead 那份 owner 写 "lead"
        tools.add(new CreateTaskTool());
        tools.add(new UpdateTaskTool());
        tools.add(new ListTasksTool());
        tools.add(new GetTaskTool());
        tools.add(new ClaimTaskTool());
        tools.add(new CompleteTaskTool());

        // 定时任务
        tools.add(new ScheduleCronTool());
        tools.add(new ListCronsTool());
        tools.add(new CancelCronTool());

        // 团队
        tools.add(new SpawnTeammateTool());
        tools.add(new ListTeammatesTool());
        tools.add(new ReviewPlanTool());
        tools.add(new RequestShutdownTool());
        tools.add(new SendMessageTool(MessageBus.LEAD));

        return tools;
    }

    /** subagent 用的：不含 task（防递归），也不含任务 / 定时 / 团队那些 */
    public static List<Tool> forSubagent() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new CurrentTimeTool());
        tools.add(new BashTool(true));      // 带后台
        tools.add(new ReadFileTool());
        tools.add(new WriteFileTool());
        tools.add(new EditFileTool());
        tools.add(new GlobTool());
        tools.add(new LoadSkillTool());
        tools.add(new CompactTool());
        return tools;
    }

    /**
     * 队友用的。每人一份——带名字的和带闸门的都在这里面。
     *
     * 只有 GatedTool.GATED_TOOLS 里那四个需要包闸门，其余裸着。
     * "需要包哪几个"由 GatedTool 自己定义，这里只是照着包。
     */
    public static List<Tool> forTeammate(String name, PlanGate gate) {
        List<Tool> tools = new ArrayList<>();

        // 要过计划闸门的四个
        tools.add(new GatedTool(new BashTool(false), gate));   // 不给后台
        tools.add(new GatedTool(new WriteFileTool(), gate));
        tools.add(new GatedTool(new EditFileTool(), gate));
        tools.add(new GatedTool(new CompleteTaskTool(name), gate));

        // 下面这些不碰工作区，裸着
        tools.add(new ReadFileTool());
        tools.add(new GlobTool());
        tools.add(new ListTasksTool());
        tools.add(new ClaimTaskTool(name));
        tools.add(new SubmitPlanTool(name, gate));
        tools.add(new SendMessageTool(name));

        return tools;
    }
}
