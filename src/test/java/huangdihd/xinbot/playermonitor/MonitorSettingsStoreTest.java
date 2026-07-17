package huangdihd.xinbot.playermonitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorSettingsStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsStatScanSettings() throws Exception {
        MonitorSettingsStore settings = new MonitorSettingsStore(temporaryDirectory.resolve("playermonitor"));
        settings.initialize();
        settings.setStatIntervalMillis(125);
        settings.setAutoScanOnGameEntry(false);
        settings.setStatScanEnabled(false);

        MonitorSettingsStore loaded = new MonitorSettingsStore(temporaryDirectory.resolve("playermonitor"));
        loaded.initialize();
        MonitorSettings current = loaded.get();

        assertEquals(125, current.statIntervalMillis);
        assertFalse(current.autoScanOnGameEntry);
        assertFalse(current.statScanEnabled);
        assertTrue(java.nio.file.Files.exists(temporaryDirectory.resolve("playermonitor/settings.json")));
    }
}
