package huangdihd.xinbot.playermonitor;

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
    void capturesColoredStatResponse() {
        StatResponseCollector collector = new StatResponseCollector();
        collector.expect("WaltomAdaam");

        assertTrue(collector.accept("§b玩家名称: WaltomAdaam").isEmpty());
        assertTrue(collector.accept("§b加入游戏: 470 次\n§b死亡计数: 300 次\n§b击杀计数: 410 人").isEmpty());
        StatResponseCollector.CapturedStat captured = collector.accept(
                "§e游戏时长: 11天6时59分56秒\n§b优先队列: 已过期\n§b特殊权限: ✅ | ✅ | ✅").orElseThrow();

        assertEquals("WaltomAdaam", captured.playerName());
        assertEquals(300, captured.snapshot().deathCount);
        assertEquals("已过期", captured.snapshot().priorityQueue);
        assertFalse(captured.snapshot().permissions == null);
        assertEquals(List.of(), collector.expire());
    }
}
