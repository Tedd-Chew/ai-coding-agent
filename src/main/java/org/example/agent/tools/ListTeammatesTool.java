package org.example.agent.tools;

import org.example.agent.TeamRegistry;
import org.example.agent.Tool;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 列出当前活着的队友。只有 Lead 有。
 *
 * 状态是 Lead 做"该不该收人"这个判断的输入——idle 就表示这个人可以让他退出了。
 * 光靠消息流不行：Lead 的 history 会被压缩，消息会被压没。
 */
public class ListTeammatesTool implements Tool {

    @Override
    public String name() {
        return "list_teammates";
    }

    @Override
    public String description() {
        return "列出当前活着的队友，每行一条：名字、状态。"
             + "状态 idle = 手上没活（可以让他退出），waiting_approval = 交了计划在等你批。";
    }

    @Override
    public String execute(String input) {
        try {
            TeamRegistry registry = TeamRegistry.getInstance();
            List<String> names = registry.names();
            if (names.isEmpty()) {
                return "No active teammates.";
            }
            String result = names.stream()
                .map(name -> name + "  " + registry.get(name).state().label())
                .collect(Collectors.joining("\n"));

            // 给【用户】看的（返回值才是给模型的），和 list_tasks / list_crons 一个路子
            System.out.println("\n## Teammates\n" + result);
            return result;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
