package waltomadaam2.xinbot.playermonitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class MonitorSettingsStore {
    private static final String TEMP_SETTINGS_PREFIX = "xpm-settings-";

    private final Path directory;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private MonitorSettings settings = new MonitorSettings();
    private volatile Consumer<String> warningSink = ignored -> {
    };
    private volatile java.util.function.Predicate<Path> quarantineFailureTrigger = ignored -> false;
    private boolean blocked;

    MonitorSettingsStore(Path directory) {
        this.directory = directory;
        this.file = directory.resolve("settings.json");
    }

    void setWarningSink(Consumer<String> warningSink) {
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    /** Test-only hook: force the quarantine move of a matching file to fail. */
    void setQuarantineFailureTrigger(java.util.function.Predicate<Path> quarantineFailureTrigger) {
        this.quarantineFailureTrigger = Objects.requireNonNull(quarantineFailureTrigger, "quarantineFailureTrigger");
    }

    synchronized void initialize() throws IOException {
        Files.createDirectories(directory);
        cleanupTempFiles();
        if (Files.exists(file)) {
            MonitorSettings loaded = null;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                loaded = gson.fromJson(reader, MonitorSettings.class);
            } catch (JsonParseException error) {
                quarantineCorruptedFile(error.getMessage());
            }
            if (loaded == null) {
                if (Files.exists(file)) {
                    quarantineCorruptedFile("missing or invalid settings");
                }
            } else {
                settings = loaded;
            }
        }
        validate(settings.statIntervalMillis);
        validateMinutes(settings.disconnectFinalizationMinutes);
        write();
    }

    private void cleanupTempFiles() throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> isPluginTempFile(path.getFileName().toString()))
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean isPluginTempFile(String fileName) {
        return fileName.startsWith("xpm-") && fileName.endsWith(".tmp");
    }

    private void quarantineCorruptedFile(String reason) throws IOException {
        Path target = uniqueQuarantinePath(file);
        try {
            if (quarantineFailureTrigger.test(file)) {
                throw new IOException("simulated quarantine failure for " + file);
            }
            Files.move(file, target);
            warningSink.accept("Quarantined corrupted monitor settings " + file.getFileName()
                    + " -> " + target.getFileName() + (reason == null ? "" : " (" + reason + ")"));
        } catch (IOException moveError) {
            blocked = true;
            warningSink.accept("Failed to quarantine corrupted monitor settings " + file.getFileName()
                    + ": " + moveError.getMessage() + "; blocking further writes to this file.");
            throw new IOException("Unable to quarantine corrupted monitor settings: " + file, moveError);
        }
    }

    private static Path uniqueQuarantinePath(Path path) {
        Path candidate = path.resolveSibling(path.getFileName() + ".corrupted");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = path.resolveSibling(path.getFileName() + ".corrupted-" + suffix);
            suffix++;
        }
        return candidate;
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
        if (blocked) {
            throw new IOException("Monitor settings are blocked from further writes after a failed quarantine attempt.");
        }
        Path temporary = Files.createTempFile(directory, TEMP_SETTINGS_PREFIX, ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                gson.toJson(settings, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
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
