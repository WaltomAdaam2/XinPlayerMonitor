package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorServiceStatTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesCapturedStatImmediately() throws Exception {
        PlayerMonitorService service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        StatSnapshot snapshot = StatParser.parse("WaltomAdaam", java.util.List.of(
                "玩家名称: WaltomAdaam",
                "加入游戏: 1 次",
                "死亡计数: 2 次",
                "击杀计数: 3 人",
                "游戏时长: 4秒",
                "优先队列: 已过期",
                "特殊权限: ✅"), 123L).orElseThrow();

        service.recordStat("WaltomAdaam", snapshot);

        assertTrue(Files.exists(temporaryDirectory.resolve("playermonitor/WaltomAdaam.json")));
        assertEquals(2, service.findRecord("WaltomAdaam").orElseThrow().statSnapshots.get(0).deathCount);
    }

    @Test
    void findsRecentStatsAfterReload() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        PlayerMonitorService service = new PlayerMonitorService(directory);
        service.initialize();
        StatSnapshot snapshot = new StatSnapshot();
        snapshot.capturedAt = 1_000L;
        service.recordStat("WaltomAdaam", snapshot);

        PlayerMonitorService reloaded = new PlayerMonitorService(directory);
        reloaded.initialize();

        assertTrue(reloaded.hasStatCapturedAtOrAfter("WaltomAdaam", 1_000L));
        assertFalse(reloaded.hasStatCapturedAtOrAfter("WaltomAdaam", 1_001L));
        assertFalse(reloaded.hasStatCapturedAtOrAfter("Unknown", 0L));
    }
}
