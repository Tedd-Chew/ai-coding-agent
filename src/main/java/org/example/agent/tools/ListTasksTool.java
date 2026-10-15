package org.example.agent.tools;

import org.example.agent.Task;
import org.example.agent.TaskStore;
import org.example.agent.Tool;

import java.util.List;

/**
 * 列出全部任务。无参数，用 Tool 接口的默认空 schema。
 *
 * 一行一条：ID、状态、负责人、标题，有依赖才附上 blockedBy。
 * 跨会话恢复进度就是靠它——读一遍就知道上次做到哪了。
 */
public class ListTasksTool implements Tool {

    @Override
    public String name() {
        return "list_tasks";
    }

    @Override
    public String description() {
        return "列出全部任务，每行一条：ID、状态、负责人、标题，有依赖的会附上 blockedBy。";
    }

    @Override
    public String execute(String input) {
        try {
            List<Task> tasks = TaskStore.getInstance().listAll();
            String result = tasks.isEmpty() ? "(no tasks)" : TaskStore.render(tasks);

            // 这一行是给【用户】看的（返回值是给模型看的）。
            // 原来 TodoWriteTool 就打印，删掉它之后用户会看不到计划，所以挪到这里。
            System.out.println("\n## Current Tasks\n" + result);
            return result;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
