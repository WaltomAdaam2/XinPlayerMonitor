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

        Path playerDir = temporaryDirectory.resolve("playermonitor/players/WaltomAdaam");
        assertTrue(Files.exists(playerDir.resolve("profile.json")));
        assertTrue(Files.exists(playerDir.resolve("stats.jsonl")));
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

    @Test
    void newerStatWriteReplacesPreviousStoredSnapshot() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        PlayerMonitorService service = new PlayerMonitorService(directory);
        service.initialize();

        StatSnapshot first = new StatSnapshot();
        first.capturedAt = 100L;
        first.deathCount = 1;
        StatSnapshot second = new StatSnapshot();
        second.capturedAt = 200L;
        second.deathCount = 9;

        service.recordStat("WaltomAdaam", first);
        service.recordStat("WaltomAdaam", second);

        Path statsFile = directory.resolve("players/WaltomAdaam/stats.jsonl");
        long lineCount;
        try (java.util.stream.Stream<String> lines = Files.lines(statsFile)) {
            lineCount = lines.filter(line -> !line.isBlank()).count();
        }
        assertEquals(1L, lineCount);
        var record = service.findRecord("WaltomAdaam").orElseThrow();
        assertEquals(1, record.statSnapshots.size());
        assertEquals(9, record.statSnapshots.get(0).deathCount);
    }

}
