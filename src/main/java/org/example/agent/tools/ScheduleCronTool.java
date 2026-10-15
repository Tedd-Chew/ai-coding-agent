package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.CronJob;
import org.example.agent.CronScheduler;
import org.example.agent.Tool;

import java.util.Map;

/**
 * 注册一个定时任务。
 *
 * 表达式在这层校验（CronExpression.parse 会抛），错误消息里带是哪一段写错了——
 * 模型看得懂"day-of-month: 值 32 超出 [1-31]"才能自己改，不然只能瞎猜。
 */
public class ScheduleCronTool implements Tool {

    @Override
    public String name() {
        return "schedule_cron";
    }

    @Override
    public String description() {
        return "注册一个定时任务：用五段式 cron（分 时 日 月 周）指定时间，到点后把 prompt 当成一轮任务交给 agent。"
             + "适合「每天早上 9 点跑测试」「每 30 分钟检查一次构建状态」这类要反复发生的事。"
             + "注意：进程退出后调度线程就停了，durable 只保证任务定义留到下次启动，不补跑停机期间错过的时间点。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("cron")
            .put("type", "string")
            .put("description", "五段式 cron：分 时 日 月 周。支持 * 、*/N 、N 、N-M 、N,M。"
                + "例：0 9 * * * = 每天 09:00；*/30 * * * * = 每 30 分钟；0 9 * * 1-5 = 工作日 09:00。");
        properties.putObject("prompt")
            .put("type", "string")
            .put("description", "到点后要 agent 做的事。要写完整——触发时它面对的是一份新上下文，"
                + "不一定还记得你现在的这段对话。");
        properties.putObject("recurring")
            .put("type", "boolean")
            .put("description", "true = 每次都触发（默认）；false = 只触发一次，交付后自动删除。");
        properties.putObject("durable")
            .put("type", "boolean")
            .put("description", "true = 落盘，进程重启后还在（默认）；false = 只活在本次会话里。");

        schema.putArray("required").add("cron").add("prompt");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            String cron = (String) args.get("cron");
            String prompt = (String) args.get("prompt");
            // 省略时按默认 true：Boolean.FALSE.equals(null) 是 false，取反即 true
            boolean recurring = !Boolean.FALSE.equals(args.get("recurring"));
            boolean durable = !Boolean.FALSE.equals(args.get("durable"));

            CronJob job = CronScheduler.getInstance().schedule(cron, prompt, recurring, durable);
            return "Scheduled " + job.render();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
