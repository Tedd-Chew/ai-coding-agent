package org.example.agent;

/**
 * 一条总线消息。
 *
 * {@code type} 是【结构化的】，不是自由文本——这是关键。
 *
 * 运行时需要【按类型做不同的事】：{@code result} 说"这项任务产出了什么"，
 * {@code idle} 说"这个人空出来了"。这两件事必须分开表达，一个含糊的"完成了"
 * 说不清"活干完了"和"人能再接活"；而运行时想区分它们，靠读文本猜意图是不行的。
 *
 * {@code seq} 是单调递增的序号，只用于排序和排查（落盘的 jsonl 里带着它，
 * 出问题时能对上顺序），不参与任何业务判断。
 */
public record BusMessage(String from, String to, String content, Type type, long seq) {

    public enum Type {

        /** 普通消息：指令、提问、说明 */
        MESSAGE("message"),

        /** 结果：这项任务产出了什么 */
        RESULT("result"),

        /** 空闲通知：我空出来了，能再接活 */
        IDLE("idle_notification"),

        /**
         * 计划待审批：队友把计划交给 Lead。
         *
         * 单独一个类型是因为【它要求 Lead 做一个动作】——收到之后得调 review_plan。
         * 混在普通 MESSAGE 里，Lead 只看到一段计划文本，不知道该干嘛。
         * formatTeamEvents 靠这个类型把它渲染成一句明确的指令。
         */
        PLAN_REQUEST("plan_approval_request"),

        /** 关机指令：Lead 发给队友，队友收到就退。队友不对外发这个 */
        SHUTDOWN("shutdown");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
