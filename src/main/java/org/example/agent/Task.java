package org.example.agent;

import java.util.List;

/**
 * 一个任务，对应 .tasks/{id}.json 一个文件。
 *
 * blockedBy 是前置任务的 ID 列表：全部 completed 之后本任务才能 claim。
 * owner 是认领它的 agent（当前统一写 "agent"，多 agent 那章再细化）。
 *
 * record 是不可变的，状态变更走下面几个 with 方法生成新实例，再由 TaskStore 落盘。
 */
public record Task(String id,
                   String subject,
                   String description,
                   Status status,
                   String owner,
                   List<String> blockedBy) {

    /**
     * 任务状态。三个状态，两个动作：
     *
     *   pending --claim--> in_progress --complete--> completed
     *
     * 内嵌在 Task 里而不单开一个文件——它只服务于 Task，
     * 和 Message.Role 同一个理由。label 是写进 JSON 的字符串。
     */
    public enum Status {

        PENDING("pending"),

        IN_PROGRESS("in_progress"),

        COMPLETED("completed");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 解析 JSON 里的字符串，无法识别返回 null（调用方决定怎么兜底） */
        public static Status from(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase();
            for (Status status : values()) {
                if (status.label.equals(normalized)) {
                    return status;
                }
            }
            return null;
        }
    }

    /** 认领：pending → in_progress，写下 owner */
    public Task claimedBy(String owner) {
        return new Task(id, subject, description, Status.IN_PROGRESS, owner, blockedBy);
    }

    /** 完成：in_progress → completed */
    public Task completed() {
        return new Task(id, subject, description, Status.COMPLETED, owner, blockedBy);
    }

    /** 退回任务板：in_progress → pending，清掉 owner。队友线程退出时用 */
    public Task released() {
        return new Task(id, subject, description, Status.PENDING, null, blockedBy);
    }

    /** 换一批前置依赖 */
    public Task withBlockedBy(List<String> deps) {
        return new Task(id, subject, description, status, owner, deps);
    }
}
