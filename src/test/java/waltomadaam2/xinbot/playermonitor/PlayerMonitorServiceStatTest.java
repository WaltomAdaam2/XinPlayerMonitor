package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorServiceStatTest {
    private final List<PlayerMonitorService> services = new ArrayList<>();

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void tearDown() {
        services.forEach(PlayerMonitorService::close);
    }

    @Test
    void writesCapturedStatImmediately() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        PlayerMonitorService service = service(directory);
        service.initialize();
        StatSnapshot snapshot = new StatSnapshot();
        snapshot.capturedAt = 123L;
        snapshot.deathCount = 2;
        snapshot.killCount = 3;

        service.recordStat("WaltomAdaam", snapshot);

        assertTrue(Files.exists(directory.resolve("xinpm.db")));
        assertFalse(Files.exists(directory.resolve("players")), "runtime stat writes must use SQLite only");
        assertEquals(2, service.findRecord("WaltomAdaam").orElseThrow().statSnapshots.get(0).deathCount);
    }

    @Test
    void findsRecentStatsAfterReload() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        PlayerMonitorService service = service(directory);
        service.initialize();
        StatSnapshot snapshot = new StatSnapshot();
        snapshot.capturedAt = 1_000L;
        service.recordStat("WaltomAdaam", snapshot);
        service.close();

        PlayerMonitorService reloaded = service(directory);
        reloaded.initialize();

        assertTrue(reloaded.hasStatCapturedAtOrAfter("WaltomAdaam", 1_000L));
        assertFalse(reloaded.hasStatCapturedAtOrAfter("WaltomAdaam", 1_001L));
        assertFalse(reloaded.hasStatCapturedAtOrAfter("Unknown", 0L));
    }

    @Test
    void statSnapshotsAreRetainedAndReturnedInTimeOrder() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        PlayerMonitorService service = service(directory);
        service.initialize();

        StatSnapshot first = new StatSnapshot();
        first.capturedAt = 100L;
        first.deathCount = 1;
        StatSnapshot second = new StatSnapshot();
        second.capturedAt = 200L;
        second.deathCount = 9;

        service.recordStat("WaltomAdaam", first);
        service.recordStat("WaltomAdaam", second);

        var record = service.findRecord("WaltomAdaam").orElseThrow();
        assertEquals(2, record.statSnapshots.size());
        assertEquals(1, record.statSnapshots.get(0).deathCount);
        assertEquals(9, record.statSnapshots.get(1).deathCount);
    }

    private PlayerMonitorService service(Path directory) {
        PlayerMonitorService service = new PlayerMonitorService(directory);
        services.add(service);
        return service;
    }
}