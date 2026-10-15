package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.BackgroundManager;
import org.example.agent.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Bash 工具：让 LLM 跑 shell 命令。对应 s02 的 bash 工具，s11 加了后台执行。
 *
 * 两条路：
 * - 同步（默认）：跑完再返回，120 秒超时，输出截断 5 万字符
 * - 后台（run_in_background=true）：立刻返回任务 ID，结果等后续轮次由 BackgroundManager 送达
 *
 * 危险命令拦截在 Permission 类（s03），不在这里。
 */
public class BashTool implements Tool {

    private static final int MAX_OUTPUT_LENGTH = 50_000;
    private static final int TIMEOUT_SECONDS = 120;

    /** 队友传 false。它不该起后台任务——会跟 Lead 抢 BackgroundManager（ISSUES 十-1） */
    private final boolean allowBackground;

    /** 默认允许后台。Lead 和 subagent 走这个 */
    public BashTool() {
        this(true);
    }

    public BashTool(boolean allowBackground) {
        this.allowBackground = allowBackground;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        String base = "运行一个 shell 命令并返回 stdout + stderr 的输出。"
                    + "适用于查询系统信息、运行脚本、查看文件等场景。"
                    + "同步执行有 120 秒超时；部分危险命令会被拦截。";
        if (!allowBackground) {
            return base;   // 不给队友提后台，免得它照着描述去试一个不存在的参数
        }
        return base + "**如果命令可能要跑很久（装依赖、跑完整测试、构建项目），"
                    + "把 run_in_background 设为 true**——它会立刻返回，你就能接着做别的事，"
                    + "结果会在后续轮次以通知形式送到。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("command")
            .put("type", "string")
            .put("description", "要执行的 shell 命令，比如 'date' 或 'dir'");
        // 队友那份不报这个参数：模型看不到就不会用
        if (allowBackground) {
            properties.putObject("run_in_background")
                .put("type", "boolean")
                .put("description",
                    "为 true 时把命令丢到后台，立刻返回任务 ID，不阻塞后续步骤。"
                    + "适合耗时命令。默认 false。");
        }

        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            Map<String, Object> args = new ObjectMapper().readValue(input, Map.class);
            String command = (String) args.get("command");
            if (command == null || command.isBlank()) {
                return "Error: command 不能为空";
            }

            // 双保险：schema 里不报，这里也不认
            if (allowBackground && Boolean.TRUE.equals(args.get("run_in_background"))) {
                return startBackground(command);
            }
            return runCommand(command);

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Error: " + e.getMessage();
        }
    }

    /** 丢后台，立刻返回。结果由 BackgroundManager 在后续轮次收集 */
    private String startBackground(String command) {
        try {
            String taskId = BackgroundManager.getInstance()
                .start(command, () -> runCommand(command));
            return "[Background task " + taskId + " started] " + command
                 + "\n结果会在后续轮次以 <task_notification> 送到，你可以先做别的。";
        } catch (RejectedExecutionException e) {
            // 池的队列满了。AbortPolicy 抛出来的就是它
            return "Error: 后台任务队列已满，稍后再试，或改成同步执行";
        }
    }

    /**
     * 跑一条命令并返回输出。同步路径和后台路径共用这一份。
     *
     * 输出重定向到【临时文件】而不是管道，原因是管道的死锁：
     * 管道是内核里一块固定大小的缓冲（Windows 只有几 KB），写满之后子进程会
     * 【阻塞在 write】；如果这时没人在读，它永远退不出去，而我们又在等它退出 ——
     * 双方互等，死锁。
     * 文件不会满，子进程能一路写完、正常退出，我们再读。
     */
    private static String runCommand(String command) {
        Path tmp = null;
        Process process = null;
        try {
            tmp = Files.createTempFile("bash-", ".out");

            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", command);
            pb.redirectErrorStream(true);      // stderr 并进 stdout（在重定向之前合并）
            pb.redirectOutput(tmp.toFile());
            pb.directory(Paths.get(".").toFile());//获取工作目录

            process = pb.start();
            //下面是阻塞当前线程，等待子进程程执行完，如果120秒都没有执行完，就会返回！false，执行强制进程销毁
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "Error: Timeout (" + TIMEOUT_SECONDS + "s)";
            }

            // 走到这说明进程已正常退出、文件句柄已释放，可以安全读。
            //
            // 必须用 new String(bytes, UTF_8) 而不是 Files.readString(tmp, UTF_8)：
            // readString 内部是 newStringNoRepl（no replacement），遇到非法字节直接抛
            // MalformedInputException，被下面的 catch(IOException) 吞成一句
            // "Error: Input length = 1"——输出和 exit code 全丢。
            // 中文 Windows 的 cmd 输出是 GBK，任何含中文的命令都会撞上。
            // new String(...) 用的是 REPLACE，坏字节变 U+FFFD，乱码但内容还在。
            String result = new String(Files.readAllBytes(tmp), StandardCharsets.UTF_8).trim();
            if (result.isEmpty()) {
                result = "(no output)";
            }
            if (result.length() > MAX_OUTPUT_LENGTH) {
                result = result.substring(0, MAX_OUTPUT_LENGTH) + "\n...(truncated)";
            }
            return result + "\n[exit code: " + process.exitValue() + "]";

        } catch (IOException e) {
            return "Error: " + e.getMessage();
        } catch (InterruptedException e) {
            // get/waitFor 声明的 checked 异常，编译器强制处理
            Thread.currentThread().interrupt();
            return "Error: interrupted";
        } finally {
            // 兜底：任何路径下都别留下活着的进程
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件删不掉不影响命令结果
                }
            }
        }
    }
}
