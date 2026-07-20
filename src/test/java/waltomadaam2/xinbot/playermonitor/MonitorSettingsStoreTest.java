package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        settings.setStatOutputHidden(false);
        settings.setDisconnectFinalizationMinutes(7);

        MonitorSettingsStore loaded = new MonitorSettingsStore(temporaryDirectory.resolve("playermonitor"));
        loaded.initialize();
        MonitorSettings current = loaded.get();

        assertEquals(125, current.statIntervalMillis);
        assertFalse(current.autoScanOnGameEntry);
        assertFalse(current.statScanEnabled);
        assertFalse(current.statOutputHidden);
        assertEquals(7, current.disconnectFinalizationMinutes);
        assertTrue(Files.exists(temporaryDirectory.resolve("playermonitor/settings.json")));
    }

    @Test
    void quarantinesCorruptedSettingsAndFallsBackToDefaults() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("settings.json"), "{not valid", StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();

        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.setWarningSink(warnings::add);
        settings.initialize();

        assertEquals(new MonitorSettings().statIntervalMillis, settings.statIntervalMillis());
        assertFalse(warnings.isEmpty());
        assertTrue(Files.exists(directory.resolve("settings.json")),
                "a fresh default settings.json must be written after quarantine succeeds");
        assertTrue(Files.exists(directory.resolve("settings.json.corrupted")),
                "quarantine naming is deterministic, not timestamp-based");
    }

    @Test
    void secondCorruptionUsesIncrementingQuarantineSuffix() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("settings.json.corrupted"), "previous corrupt", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("settings.json"), "{also not valid", StandardCharsets.UTF_8);

        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();

        assertTrue(Files.exists(directory.resolve("settings.json.corrupted")));
        assertTrue(Files.exists(directory.resolve("settings.json.corrupted-2")));
    }

    @Test
    void deletesLeftoverPluginTempFilesOnStartup() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(directory);
        Path stray = Files.createTempFile(directory, "xpm-settings-", ".tmp");

        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();

        assertFalse(Files.exists(stray));
    }

    @Test
    void nonPluginTempFilesAreNeverDeletedOnStartup() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(directory);
        Path userTemp = directory.resolve("backup.tmp");
        Files.writeString(userTemp, "keep me", StandardCharsets.UTF_8);

        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();

        assertTrue(Files.exists(userTemp), "non-plugin temp files must never be deleted on startup");
    }

    @Test
    void writeTempFilesUseScopedPrefix() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();

        List<String> observed = new ArrayList<>();
        Thread watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline && observed.isEmpty()) {
                try (Stream<Path> paths = Files.list(directory)) {
                    paths.map(path -> path.getFileName().toString())
                            .filter(name -> name.endsWith(".tmp"))
                            .forEach(observed::add);
                } catch (IOException ignored) {
                }
            }
        });
        watcher.start();
        for (int i = 0; i < 300; i++) {
            settings.setStatIntervalMillis(500 + i);
        }
        watcher.join(2000);

        for (String name : observed) {
            assertTrue(name.startsWith("xpm-settings-"), "unexpected temp file name: " + name);
        }
        try (Stream<Path> paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void failedQuarantineBlocksFurtherWritesWithoutOverwritingCorruptedData() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(directory);
        String corruptedContent = "{not valid json at all";
        Files.writeString(directory.resolve("settings.json"), corruptedContent, StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();

        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.setWarningSink(warnings::add);
        settings.setQuarantineFailureTrigger(path -> path.getFileName().toString().equals("settings.json"));

        assertThrows(IOException.class, settings::initialize);
        assertEquals(corruptedContent, Files.readString(directory.resolve("settings.json"), StandardCharsets.UTF_8),
                "corrupted settings must never be overwritten with defaults when quarantine failed");
        assertFalse(warnings.isEmpty());
        assertThrows(IOException.class, () -> settings.setStatIntervalMillis(1000),
                "further writes must be blocked after a failed quarantine attempt");
    }
}
