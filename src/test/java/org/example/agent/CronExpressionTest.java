package org.example.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CronExpression 的解析 / 校验 / 匹配测试。
 *
 * 为什么这些必须靠单测盯住：出错的都是【静默】的。
 * 表达式写对了但匹配错一分钟、周的编号偏了一天、日和周该"或"却按了"且"——
 * 不会有任何报错，只会表现为"任务在该跑的时候没跑"，而且极难在运行时排查。
 * 所以边界全部在这里钉死。
 *
 * 【日期锚点】2026-10-11 是周日，2026-10-12 是周一。
 * cron 的周编号是 周日=0 … 周六=6，跟 Java 的 DayOfWeek（周一=1 … 周日=7）差一位，
 * 下面所有涉及星期的用例都以这两天为准。
 */
class CronExpressionTest {

    /** 2026 年 10 月的某个时刻 */
    private static LocalDateTime oct(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute);
    }

    // ==================== 五种字段写法 ====================

    @Test
    @DisplayName("* 匹配任意值")
    void wildcardMatchesEverything() {
        CronExpression every = CronExpression.parse("* * * * *");

        assertTrue(every.matches(oct(11, 0, 0)));
        assertTrue(every.matches(oct(12, 23, 59)));
    }

    @Test
    @DisplayName("精确值只在该值上命中")
    void exactValue() {
        CronExpression parsed = CronExpression.parse("30 9 * * *");

        assertTrue(parsed.matches(oct(11, 9, 30)));
        assertFalse(parsed.matches(oct(11, 9, 31)), "分钟不对不该命中");
        assertFalse(parsed.matches(oct(11, 10, 30)), "小时不对不该命中");
    }

    @Test
    @DisplayName("只看分钟精度：同一分钟内任意秒都命中")
    void minutePrecision() {
        CronExpression parsed = CronExpression.parse("30 9 * * *");

        assertTrue(parsed.matches(LocalDateTime.of(2026, 10, 11, 9, 30, 0)));
        assertTrue(parsed.matches(LocalDateTime.of(2026, 10, 11, 9, 30, 59)));
        assertFalse(parsed.matches(LocalDateTime.of(2026, 10, 11, 9, 29, 59)));
    }

    @Test
    @DisplayName("*/N 按步长命中")
    void step() {
        CronExpression parsed = CronExpression.parse("*/15 * * * *");

        assertTrue(parsed.matches(oct(11, 9, 0)));
        assertTrue(parsed.matches(oct(11, 9, 15)));
        assertTrue(parsed.matches(oct(11, 9, 45)));
        assertFalse(parsed.matches(oct(11, 9, 7)), "7 不是 15 的倍数");
    }

    @Test
    @DisplayName("N-M 按范围命中，两端都算在内")
    void range() {
        CronExpression parsed = CronExpression.parse("0 9-17 * * *");

        assertTrue(parsed.matches(oct(11, 9, 0)), "下界要算在内");
        assertTrue(parsed.matches(oct(11, 17, 0)), "上界要算在内");
        assertFalse(parsed.matches(oct(11, 8, 0)));
        assertFalse(parsed.matches(oct(11, 18, 0)));
    }

    @Test
    @DisplayName("N,M 列表：命中任意一项即可")
    void list() {
        CronExpression parsed = CronExpression.parse("0 9,18 * * *");

        assertTrue(parsed.matches(oct(11, 9, 0)));
        assertTrue(parsed.matches(oct(11, 18, 0)));
        assertFalse(parsed.matches(oct(11, 12, 0)));
    }

    @Test
    @DisplayName("列表里可以嵌范围：1-5,20")
    void listOfRanges() {
        CronExpression parsed = CronExpression.parse("0 1-5,20 * * *");

        assertTrue(parsed.matches(oct(11, 3, 0)));
        assertTrue(parsed.matches(oct(11, 20, 0)));
        assertFalse(parsed.matches(oct(11, 6, 0)));
        assertFalse(parsed.matches(oct(11, 19, 0)));
    }

    // ==================== 周的编号换算 ====================

    @Test
    @DisplayName("周字段 0 = 周日，不是周一")
    void weekdayZeroIsSunday() {
        CronExpression sunday = CronExpression.parse("0 9 * * 0");

        assertTrue(sunday.matches(oct(11, 9, 0)), "10-11 是周日，应该命中");
        assertFalse(sunday.matches(oct(12, 9, 0)), "10-12 是周一，不该命中");
    }

    @Test
    @DisplayName("周字段 1 = 周一（Java 里周一也是 1，但周日在 Java 里是 7，这一步必须换算）")
    void weekdayOneIsMonday() {
        CronExpression monday = CronExpression.parse("0 9 * * 1");

        assertTrue(monday.matches(oct(12, 9, 0)), "10-12 是周一，应该命中");
        assertFalse(monday.matches(oct(11, 9, 0)), "10-11 是周日，不该命中");
    }

    @Test
    @DisplayName("工作日 1-5 不含周日和周六")
    void workdays() {
        CronExpression workdays = CronExpression.parse("0 9 * * 1-5");

        assertTrue(workdays.matches(oct(12, 9, 0)), "周一");
        assertFalse(workdays.matches(oct(11, 9, 0)), "周日不算工作日");
    }

    // ==================== 日 / 周的"或"规则（老 cron 的怪癖） ====================

    @Test
    @DisplayName("日和周【都】被限制时是「或」：命中其一即可")
    void dayAndWeekdayAreOr() {
        // 每月 11 号【或】每周一
        CronExpression parsed = CronExpression.parse("0 9 11 * 1");

        assertTrue(parsed.matches(oct(11, 9, 0)),
            "10-11 是 11 号（周日）—— 命中的是「11 号」这一条");
        assertTrue(parsed.matches(oct(12, 9, 0)),
            "10-12 是周一（12 号）—— 命中的是「周一」这一条");
        assertFalse(parsed.matches(oct(13, 9, 0)),
            "10-13 是周二、13 号 —— 两条都不命中");
    }

    @Test
    @DisplayName("只要有一个是 * ，或运算就退化成「只看另一个」")
    void wildcardOnOneSideDisablesOr() {
        CronExpression onlyDay = CronExpression.parse("0 9 11 * *");
        assertTrue(onlyDay.matches(oct(11, 9, 0)));
        assertFalse(onlyDay.matches(oct(12, 9, 0)), "周字段是 *，不该把周一也拉进来");

        CronExpression onlyWeekday = CronExpression.parse("0 9 * * 1");
        assertTrue(onlyWeekday.matches(oct(12, 9, 0)));
        assertFalse(onlyWeekday.matches(oct(13, 9, 0)));
    }

    @Test
    @DisplayName("其它字段之间还是「且」——只有日 vs 周是或")
    void otherFieldsAreAnd() {
        CronExpression october11 = CronExpression.parse("0 9 11 10 *");
        assertTrue(october11.matches(oct(11, 9, 0)), "10 月 11 号");

        CronExpression november11 = CronExpression.parse("0 9 11 11 *");
        assertFalse(november11.matches(oct(11, 9, 0)), "月份是「且」：11 月 11 号不该在 10 月命中");
    }

    // ==================== 校验 ====================

    @Test
    @DisplayName("段数不是 5 就拒绝")
    void rejectsWrongFieldCount() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * * * * *"));
    }

    @Test
    @DisplayName("各字段超出取值范围就拒绝，且报错里带字段名")
    void rejectsOutOfRange() {
        assertEquals("minute: 值 60 超出 [0-59]",
            assertThrows(IllegalArgumentException.class,
                () -> CronExpression.parse("60 * * * *")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* 24 * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * 0 * *"),
            "日是 1-31，0 不合法");
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * * 13 *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * * * 7"),
            "周是 0-6，7 不合法（周日在 cron 里是 0）");
    }

    @Test
    @DisplayName("范围起点大于终点就拒绝")
    void rejectsReversedRange() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> CronExpression.parse("0 17-9 * * *"));

        assertTrue(error.getMessage().contains("范围起点大于终点"), error.getMessage());
    }

    @Test
    @DisplayName("非法字符、空段、坏步长都拒绝")
    void rejectsMalformedFields() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("abc * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("- * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("*/0 * * * *"),
            "步长必须大于 0");
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("*/x * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("1,,2 * * * *"),
            "空的列表项");
    }

    @Test
    @DisplayName("超长数字只报「字段不合法」，不能让它把 parseInt 撑爆")
    void rejectsOverlongNumberWithoutNumberFormatException() {
        // 如果直接 parseInt，这里抛的是 NumberFormatException（不是 IllegalArgumentException），
        // 消息也会变成"Illegal radix"之类，模型看不懂
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> CronExpression.parse("999999999999999 * * * *"));

        assertTrue(error.getMessage().contains("不合法"), error.getMessage());
    }

    @Test
    @DisplayName("空表达式 / null 就拒绝")
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("   "));
    }

    @Test
    @DisplayName("多余空白会被规范化成单个空格")
    void normalizesWhitespace() {
        CronExpression parsed = CronExpression.parse("  0   9  *  *  *  ");

        // 规范化的意义：同一个表达式的不同写法应该产生同一个字符串。
        // 否则 list_crons 的显示会带多余空格，盘上也会存成两个样子。
        assertEquals("0 9 * * *", parsed.expression());
        assertTrue(parsed.matches(oct(11, 9, 0)), "规范化不影响匹配");
    }
}
