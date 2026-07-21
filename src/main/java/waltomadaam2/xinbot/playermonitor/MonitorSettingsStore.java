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
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class MonitorSettingsStore {
    static final int MIN_STAT_COOLDOWN_HOURS = 0;
    static final int MAX_STAT_COOLDOWN_HOURS = 168;
    static final int MIN_STAT_TIMEOUT_MILLIS = 1_000;
    static final int MAX_STAT_TIMEOUT_MILLIS = 30_000;
    static final int MIN_STAT_ATTEMPTS = 1;
    static final int MAX_STAT_ATTEMPTS = 10;
    static final int MIN_QUERY_COUNT = 5;
    static final int MAX_QUERY_COUNT = 50;
    static final int MIN_CACHE_IDLE_MINUTES = 5;
    static final int MAX_CACHE_IDLE_MINUTES = 1_440;
    static final int MIN_MAX_CACHED_HISTORY = 50;
    static final int MAX_MAX_CACHED_HISTORY = 10_000;

    static final List<String> SUPPORTED_TIMEZONES = List.of(
            "UTC",
            "UTC-12:00", "UTC-11:00", "UTC-10:00", "UTC-09:30", "UTC-09:00",
            "UTC-08:00", "UTC-07:00", "UTC-06:00", "UTC-05:00", "UTC-04:00",
            "UTC-03:30", "UTC-03:00", "UTC-02:00", "UTC-01:00",
            "UTC+01:00", "UTC+02:00", "UTC+03:00", "UTC+03:30", "UTC+04:00",
            "UTC+04:30", "UTC+05:00", "UTC+05:30", "UTC+05:45", "UTC+06:00",
            "UTC+06:30", "UTC+07:00", "UTC+08:00", "UTC+08:45", "UTC+09:00",
            "UTC+09:30", "UTC+10:00", "UTC+10:30", "UTC+11:00", "UTC+11:30",
            "UTC+12:00", "UTC+12:45", "UTC+13:00", "UTC+14:00");

    private static final String TEMP_SETTINGS_PREFIX = "xpm-settings-";

    private final Path directory;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    /** Values persisted in settings.json and shown by the setting command. */
    private MonitorSettings settings = new MonitorSettings();
    /** Runtime Stat values. Updated immediately when idle, otherwise after active requests finish. */
    private MonitorSettings activeStatSettings = settings.copy();
    private int activeStatOperations;
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
        normalizeAndValidate(settings);
        activeStatSettings = settings.copy();
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
        return fileName.startsWith(TEMP_SETTINGS_PREFIX) && fileName.endsWith(".tmp");
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

    synchronized boolean hasDeferredStatSettings() {
        return activeStatOperations > 0 && !sameStatSettings(settings, activeStatSettings);
    }

    synchronized void beginStatOperation() {
        activeStatOperations++;
    }

    synchronized void endStatOperation() {
        if (activeStatOperations > 0) {
            activeStatOperations--;
        }
        if (activeStatOperations == 0) {
            activeStatSettings = settings.copy();
        }
    }

    synchronized int statSendIntervalMillis() {
        return activeStatSettings.statSendIntervalMillis;
    }

    synchronized boolean scanOnEntry() {
        return activeStatSettings.scanOnEntry;
    }

    synchronized boolean statEnabled() {
        return activeStatSettings.statEnabled;
    }

    synchronized boolean statOutputHide() {
        return activeStatSettings.statOutputHide;
    }

    synchronized int statCooldownHours() {
        return activeStatSettings.statCooldownHours;
    }

    synchronized int statTimeoutMillis() {
        return activeStatSettings.statTimeoutMillis;
    }

    synchronized int statAttempts() {
        return activeStatSettings.statAttempts;
    }

    synchronized boolean scanOnJoin() {
        return activeStatSettings.scanOnJoin;
    }

    synchronized boolean prioritizeJoinStat() {
        return activeStatSettings.prioritizeJoinStat;
    }

    synchronized int disconnectTimeoutMinutes() {
        return settings.disconnectTimeoutMinutes;
    }

    synchronized String displayTimezone() {
        return settings.displayTimezone;
    }

    synchronized ZoneId displayZoneId() {
        return parseTimezone(settings.displayTimezone);
    }

    synchronized int recentLoginCount() {
        return settings.recentLoginCount;
    }

    synchronized int chatCount() {
        return settings.chatCount;
    }

    synchronized int cacheIdleMinutes() {
        return settings.cacheIdleMinutes;
    }

    synchronized int maxCachedHistory() {
        return settings.maxCachedHistory;
    }

    synchronized void setStatSendIntervalMillis(int value) throws IOException {
        validatePositive(value, "Stat send interval must be greater than 0 ms");
        updateStatSetting(updated -> updated.statSendIntervalMillis = value);
    }

    synchronized void setScanOnEntry(boolean value) throws IOException {
        updateStatSetting(updated -> updated.scanOnEntry = value);
    }

    synchronized void setStatEnabled(boolean value) throws IOException {
        updateStatSetting(updated -> updated.statEnabled = value);
    }

    synchronized void setStatOutputHide(boolean value) throws IOException {
        updateStatSetting(updated -> updated.statOutputHide = value);
    }

    synchronized void setDisconnectTimeoutMinutes(int value) throws IOException {
        validatePositive(value, "Disconnect timeout must be greater than 0 minutes");
        updateSetting(updated -> updated.disconnectTimeoutMinutes = value);
    }

    synchronized void setStatCooldownHours(int value) throws IOException {
        validateRange(value, MIN_STAT_COOLDOWN_HOURS, MAX_STAT_COOLDOWN_HOURS,
                "Stat cooldown must be between 0 and 168 hours");
        updateStatSetting(updated -> updated.statCooldownHours = value);
    }

    synchronized void setStatTimeoutMillis(int value) throws IOException {
        validateRange(value, MIN_STAT_TIMEOUT_MILLIS, MAX_STAT_TIMEOUT_MILLIS,
                "Stat timeout must be between 1000 and 30000 ms");
        updateStatSetting(updated -> updated.statTimeoutMillis = value);
    }

    synchronized void setStatAttempts(int value) throws IOException {
        validateRange(value, MIN_STAT_ATTEMPTS, MAX_STAT_ATTEMPTS,
                "Stat attempts must be between 1 and 10");
        updateStatSetting(updated -> updated.statAttempts = value);
    }

    synchronized void setScanOnJoin(boolean value) throws IOException {
        updateStatSetting(updated -> updated.scanOnJoin = value);
    }

    synchronized void setPrioritizeJoinStat(boolean value) throws IOException {
        updateStatSetting(updated -> updated.prioritizeJoinStat = value);
    }

    synchronized void setDisplayTimezone(String value) throws IOException {
        String canonical = canonicalTimezone(value);
        updateSetting(updated -> updated.displayTimezone = canonical);
    }

    synchronized void setRecentLoginCount(int value) throws IOException {
        validateRange(value, MIN_QUERY_COUNT, MAX_QUERY_COUNT,
                "Recent login count must be between 5 and 50");
        updateSetting(updated -> updated.recentLoginCount = value);
    }

    synchronized void setChatCount(int value) throws IOException {
        validateRange(value, MIN_QUERY_COUNT, MAX_QUERY_COUNT,
                "Chat count must be between 5 and 50");
        updateSetting(updated -> updated.chatCount = value);
    }

    synchronized void setCacheIdleMinutes(int value) throws IOException {
        validateRange(value, MIN_CACHE_IDLE_MINUTES, MAX_CACHE_IDLE_MINUTES,
                "Cache idle time must be between 5 and 1440 minutes");
        updateSetting(updated -> updated.cacheIdleMinutes = value);
    }

    synchronized void setMaxCachedHistory(int value) throws IOException {
        validateRange(value, MIN_MAX_CACHED_HISTORY, MAX_MAX_CACHED_HISTORY,
                "Max cached history must be between 50 and 10000");
        updateSetting(updated -> updated.maxCachedHistory = value);
    }

    private void updateStatSetting(Consumer<MonitorSettings> update) throws IOException {
        MonitorSettings previousSettings = settings.copy();
        MonitorSettings previousActiveStatSettings = activeStatSettings.copy();
        update.accept(settings);
        if (activeStatOperations == 0) {
            activeStatSettings = settings.copy();
        }
        try {
            write();
        } catch (IOException error) {
            settings = previousSettings;
            activeStatSettings = previousActiveStatSettings;
            throw error;
        }
    }

    private void updateSetting(Consumer<MonitorSettings> update) throws IOException {
        MonitorSettings previousSettings = settings.copy();
        update.accept(settings);
        try {
            write();
        } catch (IOException error) {
            settings = previousSettings;
            throw error;
        }
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

    static String canonicalTimezone(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Timezone cannot be empty");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('−', '-');
        if ("UTC".equals(normalized) || "UTC+00:00".equals(normalized)
                || "UTC-00:00".equals(normalized) || "UTC±00:00".equals(normalized)) {
            return "UTC";
        }
        if (!normalized.startsWith("UTC")) {
            throw new IllegalArgumentException("Timezone must use UTC format, for example UTC+08:00");
        }
        String offsetText = normalized.substring(3);
        if (offsetText.matches("[+-]\\d{1,2}")) {
            offsetText += ":00";
        }
        if (offsetText.matches("[+-]\\d{1,2}:\\d{2}")) {
            char sign = offsetText.charAt(0);
            String[] parts = offsetText.substring(1).split(":", -1);
            offsetText = sign + String.format(Locale.ROOT, "%02d:%02d",
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
        try {
            ZoneOffset offset = ZoneOffset.of(offsetText);
            if (offset.getTotalSeconds() > ZoneOffset.of("+14:00").getTotalSeconds()
                    || offset.getTotalSeconds() < ZoneOffset.of("-12:00").getTotalSeconds()) {
                throw new IllegalArgumentException("Timezone must be between UTC-12:00 and UTC+14:00");
            }
            if (offset.getTotalSeconds() == 0) {
                return "UTC";
            }
            String canonical = "UTC" + offset.getId();
            if (!SUPPORTED_TIMEZONES.contains(canonical)) {
                throw new IllegalArgumentException("Unsupported UTC timezone: " + value);
            }
            return canonical;
        } catch (DateTimeException | NumberFormatException error) {
            throw new IllegalArgumentException("Invalid UTC timezone: " + value);
        }
    }

    static ZoneId parseTimezone(String value) {
        String canonical = canonicalTimezone(value);
        if ("UTC".equals(canonical)) {
            return ZoneOffset.UTC;
        }
        return ZoneOffset.of(canonical.substring(3));
    }

    private static void normalizeAndValidate(MonitorSettings value) {
        validatePositive(value.statSendIntervalMillis, "Stat send interval must be greater than 0 ms");
        validatePositive(value.disconnectTimeoutMinutes, "Disconnect timeout must be greater than 0 minutes");
        validateRange(value.statCooldownHours, MIN_STAT_COOLDOWN_HOURS, MAX_STAT_COOLDOWN_HOURS,
                "Stat cooldown must be between 0 and 168 hours");
        validateRange(value.statTimeoutMillis, MIN_STAT_TIMEOUT_MILLIS, MAX_STAT_TIMEOUT_MILLIS,
                "Stat timeout must be between 1000 and 30000 ms");
        validateRange(value.statAttempts, MIN_STAT_ATTEMPTS, MAX_STAT_ATTEMPTS,
                "Stat attempts must be between 1 and 10");
        validateRange(value.recentLoginCount, MIN_QUERY_COUNT, MAX_QUERY_COUNT,
                "Recent login count must be between 5 and 50");
        validateRange(value.chatCount, MIN_QUERY_COUNT, MAX_QUERY_COUNT,
                "Chat count must be between 5 and 50");
        validateRange(value.cacheIdleMinutes, MIN_CACHE_IDLE_MINUTES, MAX_CACHE_IDLE_MINUTES,
                "Cache idle time must be between 5 and 1440 minutes");
        validateRange(value.maxCachedHistory, MIN_MAX_CACHED_HISTORY, MAX_MAX_CACHED_HISTORY,
                "Max cached history must be between 50 and 10000");
        value.displayTimezone = canonicalTimezone(value.displayTimezone);
    }

    private static boolean sameStatSettings(MonitorSettings first, MonitorSettings second) {
        return first.statSendIntervalMillis == second.statSendIntervalMillis
                && first.scanOnEntry == second.scanOnEntry
                && first.statEnabled == second.statEnabled
                && first.statOutputHide == second.statOutputHide
                && first.statCooldownHours == second.statCooldownHours
                && first.statTimeoutMillis == second.statTimeoutMillis
                && first.statAttempts == second.statAttempts
                && first.scanOnJoin == second.scanOnJoin
                && first.prioritizeJoinStat == second.prioritizeJoinStat;
    }

    private static void validatePositive(int value, String message) {
        if (value <= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void validateRange(int value, int minimum, int maximum, String message) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(message);
        }
    }
}
