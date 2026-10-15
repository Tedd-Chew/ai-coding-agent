package org.example.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只管 cleanup() 和"目录按 owner 分家"这两件事，不动压缩算法本身。
 *
 * 为什么值得测：删目录这段是手写的、平台相关的代码（Windows 的目录句柄很挑），
 * 光靠读代码看不出到底删没删干净。
 */
class ContextCompactorTest {

    private static final String OTHER = "alice";

    /** 和 ContextCompactor 里那两个目录的拼法一致 */
    private static Path toolResultsDir(String owner) {
        return Paths.get(".task_outputs", "tool-results", owner);
    }

    private static Path transcriptDir(String owner) {
        return Paths.get(".transcripts", owner);
    }

    /** 造一棵小目录树，模拟压缩产物（含一层嵌套，验证递归删） */
    private static void seed(String owner) throws IOException {
        Files.createDirectories(toolResultsDir(owner).resolve("nested"));
        Files.writeString(toolResultsDir(owner).resolve("call_1.txt"), "x");
        Files.writeString(toolResultsDir(owner).resolve("nested/call_2.txt"), "y");
        Files.createDirectories(transcriptDir(owner));
        Files.writeString(transcriptDir(owner).resolve("t.jsonl"), "{}");
    }

    @AfterEach
    void tidyUp() {
        new ContextCompactor(null, MessageBus.LEAD).cleanup();
        new ContextCompactor(null, OTHER).cleanup();
    }

    @Test
    @DisplayName("cleanup 把自己那两棵目录树删干净（含嵌套子目录）")
    void cleanupRemovesOwnDirs() throws IOException {
        seed(OTHER);

        new ContextCompactor(null, OTHER).cleanup();

        assertFalse(Files.exists(toolResultsDir(OTHER)), "工具结果目录该没了");
        assertFalse(Files.exists(transcriptDir(OTHER)), "留档目录该没了");
    }

    @Test
    @DisplayName("cleanup 不动别人的目录 —— Lead 那棵一直活着")
    void cleanupLeavesOtherOwnersAlone() throws IOException {
        seed(MessageBus.LEAD);
        seed(OTHER);

        new ContextCompactor(null, OTHER).cleanup();

        assertTrue(Files.exists(toolResultsDir(MessageBus.LEAD)), "lead 的不该被删");
        assertTrue(Files.exists(transcriptDir(MessageBus.LEAD)), "lead 的不该被删");
        assertTrue(Files.exists(toolResultsDir(MessageBus.LEAD).resolve("nested/call_2.txt")),
            "lead 的嵌套文件也该还在");
    }

    @Test
    @DisplayName("目录本来就不存在时，cleanup 不炸")
    void cleanupOnMissingDirsIsSafe() {
        new ContextCompactor(null, "never-ever-existed").cleanup();
    }
}
