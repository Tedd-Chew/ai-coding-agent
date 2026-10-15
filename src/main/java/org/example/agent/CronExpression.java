package org.example.agent;

import java.time.LocalDateTime;

/**
 * 五段式 cron 表达式：分 时 日 月 周。
 *
 * <pre>
 *   *    *   *   *   *      每分钟
 *   0    9   *   *   *      每天 09:00
 *   *&#47;5   *   *   *   *      每 5 分钟
 *   0    9   *   *   1-5    工作日 09:00
 * </pre>
 *
 * 支持 {@code *}、{@code *&#47;N}、{@code N}、{@code N-M}、{@code N,M,...}（可嵌套，如 {@code 1-5,20}）。
 *
 * 【解析和校验是一件事】。parse() 不合法就抛，不额外提供"只校验不构造"的入口——
 * 两套走同一份字段规则，分开写迟早会漂移成"校验通过但解析炸"。
 *
 * 两个容易栽的坑都在 matches() 里标了：周的编号，和日/周的"或"规则。
 */
public final class CronExpression {

    private static final int FIELD_COUNT = 5;

    /** 字段下标，别用魔数 */
    private static final int MINUTE = 0;
    private static final int HOUR = 1;
    private static final int DAY_OF_MONTH = 2;
    private static final int MONTH = 3;
    private static final int DAY_OF_WEEK = 4;

    /** 各字段取值范围，顺序和表达式一致。周按 cron 惯例：0 = 周日 */
    private static final int[][] RANGES = {
        {0, 59},    // 分
        {0, 23},    // 时
        {1, 31},    // 日
        {1, 12},    // 月
        {0, 6},     // 周
    };

    /** 报错信息里用的字段名，让模型看得出是哪一段写错了 */
    private static final String[] FIELD_NAMES = {
        "minute", "hour", "day-of-month", "month", "day-of-week"
    };

    /**
     * 数字串的长度上限。挡住 "999999999999999" 这种把 Integer.parseInt 撑爆的输入——
     * NumberFormatException 不是想给模型看的报错。
     */
    private static final int MAX_DIGITS = 9;

    private final String expression;
    private final String[] fields;

    private CronExpression(String expression, String[] fields) {
        this.expression = expression;
        this.fields = fields;
    }

    /**
     * 解析 + 校验。
     *
     * @throws IllegalArgumentException 段数不对或某段不合法，消息里带上是哪一段错、为什么错
     */
    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cron 表达式不能为空");
        }
        String trimmed = expression.trim();
        String[] fields = trimmed.split("\\s+");
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException(
                "cron 要 5 段（分 时 日 月 周），实际 " + fields.length + " 段: " + trimmed);
        }
        for (int i = 0; i < FIELD_COUNT; i++) {
            String error = validateField(fields[i], RANGES[i][0], RANGES[i][1]);
            if (error != null) {
                throw new IllegalArgumentException(FIELD_NAMES[i] + ": " + error);
            }
        }
        // 用拆好的 fields 重新拼一遍，而不是原样留着 trimmed。
        // 这样 "*/1   *  * * *" 和 "*/1 * * * *" 会规范化成同一个字符串——
        // 否则同一个表达式在盘上、在 list_crons 里就有两种长相，读起来莫名其妙。
        return new CronExpression(String.join(" ", fields), fields);
    }

    /** 这个时刻是否命中。只看分钟精度——秒和纳秒一律忽略 */
    public boolean matches(LocalDateTime moment) {
        if (!fieldMatches(fields[MINUTE], moment.getMinute())
            || !fieldMatches(fields[HOUR], moment.getHour())
            || !fieldMatches(fields[MONTH], moment.getMonthValue())) {
            return false;
        }

        // Java 的 DayOfWeek 是 Mon=1..Sun=7，cron 是 Sun=0..Sat=6。
        // %7 正好把 7（周日）折回 0，其余原样：Mon(1)→1 … Sat(6)→6，Sun(7)→0。
        int weekday = moment.getDayOfWeek().getValue() % 7;

        String dayField = fields[DAY_OF_MONTH];
        String weekField = fields[DAY_OF_WEEK];
        boolean dayMatches = fieldMatches(dayField, moment.getDayOfMonth());
        boolean weekMatches = fieldMatches(weekField, weekday);

        // 老 cron 的怪癖：日和周【都】被限制时是「或」，不是「且」。
        // 所以 "0 9 1 * 1" 是「每月 1 号 或 每周一」，而不是「既是 1 号又是周一」。
        // 有一个是 * 时，这个 * 恒真，或运算就退化成"只看另一个"。
        if ("*".equals(dayField)) {
            return weekMatches;     // weekField 也是 * 时 weekMatches 恒真，顺带覆盖了"两个都 *"
        }
        if ("*".equals(weekField)) {
            return dayMatches;
        }
        return dayMatches || weekMatches;
    }

    public String expression() {
        return expression;
    }

    @Override
    public String toString() {
        return expression;
    }

    // ==================== 内部 ====================

    /**
     * 单个字段是否匹配某个值。
     * 递归下降——逗号列表里的每一项还可以是范围或步长，所以这里要能自己调自己。
     * 只在 parse() 校验通过之后被调用，边界检查不重复做。
     */
    private static boolean fieldMatches(String field, int value) {
        if ("*".equals(field)) {
            return true;
        }
        if (field.startsWith("*/")) {
            return value % Integer.parseInt(field.substring(2)) == 0;
        }
        if (field.contains(",")) {
            for (String part : field.split(",")) {
                if (fieldMatches(part.trim(), value)) {
                    return true;
                }
            }
            return false;
        }
        if (field.contains("-")) {
            int dash = field.indexOf('-');
            int start = Integer.parseInt(field.substring(0, dash));
            int end = Integer.parseInt(field.substring(dash + 1));
            return start <= value && value <= end;
        }
        return value == Integer.parseInt(field);
    }

    /**
     * 校验一个字段。合法返回 null，否则返回给模型看的错因。
     * 递归结构和 fieldMatches 一一对应——改一处记得改另一处。
     */
    private static String validateField(String field, int min, int max) {
        if ("*".equals(field)) {
            return null;
        }
        if (field.startsWith("*/")) {
            String step = field.substring(2);
            if (!isDigits(step) || Integer.parseInt(step) <= 0) {
                return "步长不合法: " + field;
            }
            return null;
        }
        if (field.contains(",")) {
            for (String part : field.split(",")) {
                String error = validateField(part.trim(), min, max);
                if (error != null) {
                    return error;
                }
            }
            return null;
        }
        if (field.contains("-")) {
            int dash = field.indexOf('-');
            String startText = field.substring(0, dash);
            String endText = field.substring(dash + 1);
            if (!isDigits(startText) || !isDigits(endText)) {
                return "范围写法不合法: " + field;
            }
            int start = Integer.parseInt(startText);
            int end = Integer.parseInt(endText);
            if (start > end) {
                return "范围起点大于终点: " + field;
            }
            if (start < min || end > max) {
                return "范围 " + field + " 超出 [" + min + "-" + max + "]";
            }
            return null;
        }
        if (!isDigits(field)) {
            return "字段不合法: " + field;
        }
        int value = Integer.parseInt(field);
        if (value < min || value > max) {
            return "值 " + value + " 超出 [" + min + "-" + max + "]";
        }
        return null;
    }

    /** 纯数字且长度可控。长度上限是为了让随后的 parseInt 不会抛 NumberFormatException */
    private static boolean isDigits(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_DIGITS) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
