package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.CronJob;
import org.example.agent.CronScheduler;
import org.example.agent.Tool;

import java.util.Map;

/**
 * 撤销一个定时任务。
 *
 * 正在队列里等交付的也一并摘掉——不然它会在一轮跑完之后回来 ack 一个用户已经不想要的任务。
 */
public class CancelCronTool implements Tool {

    @Override
    public String name() {
        return "cancel_cron";
    }

    @Override
    public String description() {
        return "按 ID 撤销一个定时任务。已经在等待交付的也会一并取消。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        schema.putObject("properties")
            .putObject("job_id")
            .put("type", "string")
            .put("description", "要撤销的定时任务 ID，形如 cron_1a2b3c4d");

        schema.putArray("required").add("job_id");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            String jobId = (String) args.get("job_id");
            CronJob job = CronScheduler.getInstance().cancel(jobId);
            return "Cancelled " + job.id() + " (" + job.cron() + " -> " + job.prompt() + ")";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
