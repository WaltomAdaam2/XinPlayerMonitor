package huangdihd.xinbot.playermonitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class MonitorSettingsStore {
    private final Path directory;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private MonitorSettings settings = new MonitorSettings();

    MonitorSettingsStore(Path directory) {
        this.directory = directory;
        this.file = directory.resolve("settings.json");
    }

    synchronized void initialize() throws IOException {
        Files.createDirectories(directory);
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                MonitorSettings loaded = gson.fromJson(reader, MonitorSettings.class);
                if (loaded == null) {
                    throw new IOException("Invalid monitor settings");
                }
                settings = loaded;
            }
        }
        validate(settings.statIntervalMillis);
        validateMinutes(settings.disconnectFinalizationMinutes);
        write();
    }

    synchronized MonitorSettings get() {
        return settings.copy();
    }

    synchronized int statIntervalMillis() {
        return settings.statIntervalMillis;
    }

    synchronized boolean autoScanOnGameEntry() {
        return settings.autoScanOnGameEntry;
    }

    synchronized boolean statScanEnabled() {
        return settings.statScanEnabled;
    }

    synchronized boolean statOutputHidden() {
        return settings.statOutputHidden;
    }

    synchronized int disconnectFinalizationMinutes() {
        return settings.disconnectFinalizationMinutes;
    }

    synchronized void setStatIntervalMillis(int value) throws IOException {
        validate(value);
        settings.statIntervalMillis = value;
        write();
    }

    synchronized void setAutoScanOnGameEntry(boolean value) throws IOException {
        settings.autoScanOnGameEntry = value;
        write();
    }

    synchronized void setStatScanEnabled(boolean value) throws IOException {
        settings.statScanEnabled = value;
        write();
    }

    synchronized void setStatOutputHidden(boolean value) throws IOException {
        settings.statOutputHidden = value;
        write();
    }

    synchronized void setDisconnectFinalizationMinutes(int value) throws IOException {
        validateMinutes(value);
        settings.disconnectFinalizationMinutes = value;
        write();
    }

    private void write() throws IOException {
        Path temporary = Files.createTempFile(directory, "settings.", ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            gson.toJson(settings, writer);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validate(int intervalMillis) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("Stat interval must be greater than 0 ms");
        }
    }

    private static void validateMinutes(int minutes) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("Disconnect finalization time must be greater than 0 minutes");
        }
    }
}
