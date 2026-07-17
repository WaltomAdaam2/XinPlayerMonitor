package huangdihd.xinbot.playermonitor;

import huangdihd.xinbot.playermonitor.model.StatSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatParserTest {
    @Test
    void parsesChineseStatSnapshot() {
        StatSnapshot snapshot = StatParser.parse("WaltomAdaam", List.of(
                "玩家名称: WaltomAdaam",
                "加入游戏: 470 次",
                "在线次数: 300 次",
                "击杀数: 410 人",
                "游戏时长: 11天6时59分56秒",
                "队伍: 已过期",
                "特殊权限: 图标✅图标✅图标✅"), 123L).orElseThrow();

        assertEquals(470, snapshot.addedGameCount);
        assertEquals(300, snapshot.onlineCount);
        assertEquals(410, snapshot.killCount);
        assertEquals(975_596L, snapshot.playtimeSeconds);
        assertEquals("已过期", snapshot.team);
        assertTrue(snapshot.permissions.greenText);
        assertTrue(snapshot.permissions.runMax);
        assertTrue(snapshot.permissions.dupe);
    }
}
