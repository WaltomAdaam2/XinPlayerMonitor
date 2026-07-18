package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatParserTest {
    @Test
    void parsesColoredChineseStatSnapshot() {
        StatSnapshot snapshot = StatParser.parse("WaltomAdaam", List.of(
                "§b玩家名称: WaltomAdaam",
                "§b加入游戏: 470 次",
                "§b死亡计数: 300 次",
                "§b击杀计数: 410 人",
                "§e游戏时长: 11天6时59分56秒",
                "§b优先队列: 已过期",
                "§b特殊权限: ✅ | ✅ | ✅"), 123L).orElseThrow();

        assertEquals(470, snapshot.addedGameCount);
        assertEquals(300, snapshot.deathCount);
        assertEquals(410, snapshot.killCount);
        assertEquals(975_596L, snapshot.playtimeSeconds);
        assertEquals("已过期", snapshot.priorityQueue);
        assertEquals("✅ | ✅ | ✅", snapshot.permissionsDisplay);
        assertTrue(snapshot.permissions.greenText);
        assertTrue(snapshot.permissions.runMax);
        assertTrue(snapshot.permissions.dupe);
    }
}
