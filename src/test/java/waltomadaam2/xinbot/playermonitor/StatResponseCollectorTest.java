package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatResponseCollectorTest {
    @Test
    void expiresUnansweredStatRequestForRetry() throws Exception {
        StatResponseCollector collector = new StatResponseCollector(1L);
        collector.expect("WaltomAdaam");

        Thread.sleep(10L);

        assertEquals(List.of("WaltomAdaam"), collector.expire());
        assertEquals(List.of(), collector.expire());
    }

    @Test
    void waitsForTheClosingSeparatorBeforeCapturingColoredStatResponse() {
        StatResponseCollector collector = new StatResponseCollector();
        collector.expect("WaltomAdaam");

        assertTrue(collector.accept("§b玩家名称: WaltomAdaam").isEmpty());
        assertTrue(collector.accept("§b加入游戏: 470 次\n§b死亡计数: 300 次\n§b击杀计数: 410 人").isEmpty());
        assertTrue(collector.accept("§e游戏时长: 11天6时59分56秒\n§b优先队列: 已过期\n§b特殊权限: ✅ | ✅ | ✅").isEmpty());

        StatResponseCollector.CapturedStat captured = collector.accept("----------------------").orElseThrow();

        assertEquals("WaltomAdaam", captured.playerName());
        assertEquals(300, captured.snapshot().deathCount);
        assertEquals("已过期", captured.snapshot().priorityQueue);
        assertEquals("✅ | ✅ | ✅", captured.snapshot().permissionsDisplay);
        assertFalse(captured.snapshot().permissions == null);
        assertEquals(List.of(), collector.expire());
    }

    @Test
    void timeoutClearsActivePartialResponseState() throws Exception {
        StatResponseCollector collector = new StatResponseCollector(1L);
        collector.expect("Alice");

        // Feed partial response to set active state
        collector.accept("§b玩家名称: Alice");
        collector.accept("§b加入游戏: 1 次\n§b死亡计数: 2 次");
        // No closing separator — active state is set but incomplete

        Thread.sleep(10L);
        List<String> expired = collector.expire();
        assertEquals(List.of("Alice"), expired);

        // After expiration, the player must no longer be expecting
        assertFalse(collector.isExpecting("Alice"),
                "isExpecting must return false after expiration + active-state clear");
    }

    @Test
    void expiredQueryLinesDoNotLeakIntoNextQuery() throws Exception {
        StatResponseCollector collector = new StatResponseCollector(1L);
        collector.expect("Alice");

        // Start collecting Alice's response but don't finish
        collector.accept("§b玩家名称: Alice");
        collector.accept("§b死亡计数: 5 次");

        // Let Alice expire
        Thread.sleep(10L);
        collector.expire();

        // Now start a fresh query for Bob
        collector.expect("Bob");
        collector.accept("§b玩家名称: Bob");
        collector.accept("§b死亡计数: 10 次");
        collector.accept("§b特殊权限: ✅");
        StatResponseCollector.CapturedStat captured = collector.accept("----------------------").orElseThrow();

        assertEquals("Bob", captured.playerName(),
                "Bob's query must not receive Alice's expired partial lines");
        assertEquals(10, captured.snapshot().deathCount,
                "Bob's stat must reflect his own data, not Alice's");
    }
}
