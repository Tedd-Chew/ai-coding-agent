package org.example.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MessageBus 的收发语义测试。
 *
 * 重点盯两个容易错的：
 *   - consume 是"取走即清"，不是"看一眼"
 *   - restore 要放回【队首】并且保住顺序（不然 Lead 收到的团队事件会是乱的）
 *
 * MessageBus 是无配置单例，测试之间靠【每次用新的名字】隔离。
 */
class MessageBusTest {

    private static final AtomicInteger NAME_SEQ = new AtomicInteger();

    /** 每次调用给一个新名字，用例之间天然不串味 */
    private static String agent() {
        return "a" + NAME_SEQ.incrementAndGet();
    }

    private final MessageBus bus = MessageBus.getInstance();

    @Test
    @DisplayName("发出去的消息，对方能收到")
    void sendThenConsume() {
        String alice = agent();
        String lead = agent();

        bus.send(alice, lead, "认证已重构", BusMessage.Type.RESULT);

        List<BusMessage> got = bus.consume(lead);
        assertEquals(1, got.size());
        assertEquals(alice, got.get(0).from());
        assertEquals(lead, got.get(0).to());
        assertEquals("认证已重构", got.get(0).content());
        assertEquals(BusMessage.Type.RESULT, got.get(0).type());
    }

    @Test
    @DisplayName("consume 是取走即清，不是看一眼")
    void consumeDrainsInbox() {
        String alice = agent();
        String lead = agent();
        bus.send(alice, lead, "一", BusMessage.Type.MESSAGE);

        assertEquals(1, bus.consume(lead).size());
        assertTrue(bus.consume(lead).isEmpty(), "第二次就该空了");
        assertEquals(0, bus.pending(lead));
    }

    @Test
    @DisplayName("收件箱互相隔离：发给 alice 的，bob 拿不到")
    void inboxesAreIsolated() {
        String alice = agent();
        String bob = agent();
        String lead = agent();

        bus.send(lead, alice, "只给 alice", BusMessage.Type.MESSAGE);

        assertTrue(bus.consume(bob).isEmpty());
        assertEquals(1, bus.consume(alice).size());
    }

    @Test
    @DisplayName("restore 放回队首：顺序和原来一致")
    void restoreKeepsOrder() {
        String alice = agent();
        String lead = agent();
        bus.send(alice, lead, "一", BusMessage.Type.RESULT);
        bus.send(alice, lead, "二", BusMessage.Type.RESULT);
        bus.send(alice, lead, "三", BusMessage.Type.RESULT);

        List<BusMessage> batch = bus.consume(lead);
        assertEquals(List.of("一", "二", "三"), contents(batch));

        bus.restore(lead, batch);
        assertEquals(List.of("一", "二", "三"), contents(bus.consume(lead)),
            "退回之后顺序不能乱——Lead 是按顺序读团队事件的");
    }

    @Test
    @DisplayName("restore 之后新来的消息排在【后面】，不会插到前面去")
    void restoredBatchGoesBeforeNewMessages() {
        String alice = agent();
        String lead = agent();
        bus.send(alice, lead, "一", BusMessage.Type.RESULT);
        bus.send(alice, lead, "二", BusMessage.Type.RESULT);

        List<BusMessage> batch = bus.consume(lead);
        bus.send(alice, lead, "退回期间新来的", BusMessage.Type.RESULT);   // 模拟这中间又来了消息
        bus.restore(lead, batch);

        assertEquals(List.of("一", "二", "退回期间新来的"), contents(bus.consume(lead)));
    }

    @Test
    @DisplayName("consume 后不 restore 的消息就没了（调用方有责任）")
    void consumedButNotRestoredIsGone() {
        String alice = agent();
        String lead = agent();
        bus.send(alice, lead, "会丢的", BusMessage.Type.MESSAGE);

        bus.consume(lead);      // 拿走了，但没人管
        assertEquals(0, bus.pending(lead));
    }

    @Test
    @DisplayName("seq 单调递增，便于排查顺序")
    void seqIncreases() {
        String alice = agent();
        String lead = agent();
        bus.send(alice, lead, "一", BusMessage.Type.MESSAGE);
        bus.send(alice, lead, "二", BusMessage.Type.MESSAGE);

        List<BusMessage> got = bus.consume(lead);
        assertEquals(2, got.size());
        assertTrue(got.get(0).seq() < got.get(1).seq());
    }

    @Test
    @DisplayName("非法名字被拒绝（模型给的名字会流进 owner / 日志 / 提示词）")
    void rejectsIllegalNames() {
        String ok = agent();

        assertThrows(IllegalArgumentException.class,
            () -> bus.send("../../etc/passwd", ok, "x", BusMessage.Type.MESSAGE));
        assertThrows(IllegalArgumentException.class,
            () -> bus.send(ok, "alice; rm -rf /", "x", BusMessage.Type.MESSAGE));
        assertThrows(IllegalArgumentException.class,
            () -> bus.send("", ok, "x", BusMessage.Type.MESSAGE));

        // 这一组是合法的：字母、数字、下划线、连字符
        assertTrue(MessageBus.isValidName("alice"));
        assertTrue(MessageBus.isValidName("team_1-a"));
        assertTrue(!MessageBus.isValidName("a b"), "空格不合法");
        assertTrue(!MessageBus.isValidName(null));
    }

    private static List<String> contents(List<BusMessage> messages) {
        return messages.stream().map(BusMessage::content).toList();
    }
}
