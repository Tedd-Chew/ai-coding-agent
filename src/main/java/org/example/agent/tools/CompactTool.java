package org.example.agent.tools;

import org.example.agent.Tool;

/**
 * compact：模型主动请求压缩历史。
 *
 * 本工具只负责"表态"，真正压缩由 Agent 在执行完整个工具批次后调
 * ContextCompactor.compactHistory 完成——因为工具只拿得到参数字符串，
 * 拿不到 history。
 */
public class CompactTool implements Tool {

    /** 工具名。Agent 用它识别"模型请求压缩"，避免字符串散落各处 */
    public static final String NAME = "compact";

    /** 工具返回值。压缩不在这里发生，所以只回一句确认 */
    public static final String RESULT = "Compaction requested after this tool batch.";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把此前的对话总结成摘要，释放上下文空间。"
             + "当一个阶段结束、后续工作只需要保留摘要时调用。";
    }

    @Override
    public String execute(String input) {
        return RESULT;
    }
}
