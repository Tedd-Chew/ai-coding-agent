package org.example.agent.tools;

import org.example.agent.CronJob;
import org.example.agent.CronScheduler;
import org.example.agent.Tool;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 列出全部定时任务。无参数，用 Tool 接口的默认空 schema。
 *
 * 一行一条：ID、表达式、prompt、是否周期、是否落盘。
 */
public class ListCronsTool implements Tool {

    @Override
    public String name() {
        return "list_crons";
    }

    @Override
    public String description() {
        return "列出全部定时任务，每行一条：ID、cron 表达式、prompt、是周期还是一次性、是否落盘。";
    }

    @Override
    public String execute(String input) {
        try {
            List<CronJob> jobs = CronScheduler.getInstance().listAll();
            String result = jobs.isEmpty()
                ? "(no cron jobs)"
                : jobs.stream().map(CronJob::render).collect(Collectors.joining("\n"));

            // 给【用户】看的（返回值才是给模型的），和 list_tasks 一个路子
            System.out.println("\n## Cron Jobs\n" + result);
            return result;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
