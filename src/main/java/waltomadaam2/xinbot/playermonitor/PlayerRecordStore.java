package waltomadaam2.xinbot.playermonitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerProfile;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Stores one directory per player under {@code playermonitor/players/<name>/} containing
 * {@code profile.json} (identity + open session + latest-state summary, rewritten atomically)
 * and JSON Lines data files: {@code sessions.jsonl} and {@code chat.jsonl} are append-only,
 * while {@code stats.jsonl} contains only the latest successfully captured Stat snapshot.
 */
final class PlayerRecordStore {
    private static final String PROFILE_FILE = "profile.json";
    private static final String SESSIONS_FILE = "sessions.jsonl";
    private static final String CHAT_FILE = "chat.jsonl";
    private static final String STATS_FILE = "stats.jsonl";

    private static final String TEMP_PROFILE_PREFIX = "xpm-profile-";
    private static final String TEMP_STAT_PREFIX = "xpm-stat-";
    private static final String TEMP_MIGRATION_PREFIX = "xpm-migration-";
    private static final String TEMP_REPAIR_PREFIX = "xpm-repair-";

    private static final long EVICTION_CHECK_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final int DEFAULT_MAX_CACHED_HISTORY_ENTRIES = 200;
    private static final int LOCK_STRIPE_COUNT = 256;

    private final Path directory;
    private final Path playersDirectory;
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    private final Gson compactGson = new Gson();
    private final IntSupplier maxCachedHistoryEntries;
    private final LongSupplier cacheIdleMillis;

    private final ReentrantLock[] lockStripes = new ReentrantLock[LOCK_STRIPE_COUNT];
    /** normalizedKey → canonical directory name (persistent disk index, never evicted). */
    private final ConcurrentHashMap<String, Path> playerDirectories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlayerRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastAccessAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> displayNames = new ConcurrentHashMap<>();
    private final java.util.Set<String> blockedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentSkipListSet<String> playerNames = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    private volatile Consumer<String> warningSink = ignored -> {
    };
    private volatile Predicate<String> evictionGuard = ignored -> false;
    private volatile Predicate<Path> quarantineFailureTrigger = ignored -> false;
    private volatile Predicate<String> migrationFailureTrigger = ignored -> false;
    private volatile Predicate<Path> writeFailureTrigger = ignored -> false;
    private volatile Predicate<String> statWriteFailure = ignored -> false;
    private volatile Predicate<Path> repairPromotionFailureTrigger = ignored -> false;
    private volatile Predicate<Path> repairRestoreFailureTrigger = ignored -> false;
    private ScheduledExecutorService evictionExecutor;

    PlayerRecordStore(Path directory) {
        this(directory, () -> DEFAULT_MAX_CACHED_HISTORY_ENTRIES,
                () -> TimeUnit.MINUTES.toMillis(MonitorSettings.DEFAULT_CACHE_IDLE_MINUTES));
    }

    PlayerRecordStore(Path directory, IntSupplier maxCachedHistoryEntries, LongSupplier cacheIdleMillis) {
        this.directory = directory;
        this.playersDirectory = directory.resolve("players");
        this.maxCachedHistoryEntries = Objects.requireNonNull(maxCachedHistoryEntries, "maxCachedHistoryEntries");
        this.cacheIdleMillis = Objects.requireNonNull(cacheIdleMillis, "cacheIdleMillis");
        for (int i = 0; i < LOCK_STRIPE_COUNT; i++) {
            lockStripes[i] = new ReentrantLock();
        }
    }

    void setWarningSink(Consumer<String> warningSink) {
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    void setEvictionGuard(Predicate<String> evictionGuard) {
        this.evictionGuard = Objects.requireNonNull(evictionGuard, "evictionGuard");
    }

    /** Test-only hook: force the next quarantine attempt against a matching path to fail. */
    void setQuarantineFailureTrigger(Predicate<Path> quarantineFailureTrigger) {
        this.quarantineFailureTrigger = Objects.requireNonNull(quarantineFailureTrigger, "quarantineFailureTrigger");
    }

    /** Test-only hook: force staging of a matching legacy player name to throw mid-migration. */
    void setMigrationFailureTrigger(Predicate<String> migrationFailureTrigger) {
        this.migrationFailureTrigger = Objects.requireNonNull(migrationFailureTrigger, "migrationFailureTrigger");
    }

    /** Test-only hook: force a runtime exception while writing a matching profile path, after the temp file exists. */
    void setWriteFailureTrigger(Predicate<Path> writeFailureTrigger) {
        this.writeFailureTrigger = Objects.requireNonNull(writeFailureTrigger, "writeFailureTrigger");
    }

    /** Test-only hook: force recordStat to throw IOException for a matching normalized player name. */
    void setStatWriteFailureForTesting(Predicate<String> statWriteFailure) {
        this.statWriteFailure = Objects.requireNonNull(statWriteFailure, "statWriteFailure");
    }

    /** Test-only hook: force promotion of a repaired JSONL file to fail. */
    void setRepairPromotionFailureTrigger(Predicate<Path> trigger) {
        this.repairPromotionFailureTrigger = Objects.requireNonNull(trigger, "trigger");
    }

    /** Test-only hook: force restoration of a quarantined JSONL file to fail. */
    void setRepairRestoreFailureTrigger(Predicate<Path> trigger) {
        this.repairRestoreFailureTrigger = Objects.requireNonNull(trigger, "trigger");
    }

    void initialize() throws IOException {
        Files.createDirectories(directory);
        Files.createDirectories(playersDirectory);
        cleanupTempFiles(directory);
        cleanupTempFiles(playersDirectory);
        migrateLegacyPlayerFiles();
        indexPlayerDirectories();
        startEvictionScheduler();
    }

    void close() {
        if (evictionExecutor != null) {
            evictionExecutor.shutdownNow();
            evictionExecutor = null;
        }
    }

    void recordLogin(String playerName, long now) throws IOException {
        validatePlayerName(playerName);
        String normalizedKey = normalize(playerName);
        ReentrantLock lock = lockFor(normalizedKey);
        lock.lock();
        try {
            ensureNotBlocked(normalizedKey);
            String displayName = resolveDisplayName(normalizedKey, playerName);
            Path playerDir = playersDirectory.resolve(displayName);
            Files.createDirectories(playerDir);
            playerDirectories.putIfAbsent(normalizedKey, playerDir);
            ensureLoaded(normalizedKey, displayName, playerDir, now);
            touchAccess(normalizedKey);

            PlayerRecord record = records.get(normalizedKey);
            PlayerProfile profile = profiles.get(normalizedKey);
            if (profile.currentSession != null) {
                profile.currentSession.logoutAt = Math.max(profile.currentSession.loginAt, now);
                appendJsonLine(playerDir.resolve(SESSIONS_FILE), profile.currentSession);
            }
            LoginSession opened = new LoginSession(now);
            record.loginSessions.add(opened);
            profile.currentSession = opened;
            profile.online = true;
            profile.lastSeenAt = now;
            writeProfile(playerDir, profile);
        } finally {
            lock.unlock();
        }
    }

    void recordLogout(String playerName, long now) throws IOException {
        validatePlayerName(playerName);
        String normalizedKey = normalize(playerName);
        ReentrantLock lock = lockFor(normalizedKey);
        lock.lock();
        try {
            ensureNotBlocked(normalizedKey);
            String displayName = resolveDisplayName(normalizedKey, playerName);
            Path playerDir = playersDirectory.resolve(displayName);
            Files.createDirectories(playerDir);
            playerDirectories.putIfAbsent(normalizedKey, playerDir);
            ensureLoaded(normalizedKey, displayName, playerDir, now);
            touchAccess(normalizedKey);

            PlayerProfile profile = profiles.get(normalizedKey);
            if (profile.currentSession == null) {
                return;
            }
            profile.currentSession.logoutAt = Math.max(profile.currentSession.loginAt, now);
            appendJsonLine(playerDir.resolve(SESSIONS_FILE), profile.currentSession);
            profile.currentSession = null;
            profile.online = false;
            profile.lastSeenAt = now;
            writeProfile(playerDir, profile);
        } finally {
            lock.unlock();
        }
    }

    void recordChat(String playerName, String message, long now) throws IOException {
        validatePlayerName(playerName);
        String normalizedKey = normalize(playerName);
        ReentrantLock lock = lockFor(normalizedKey);
        lock.lock();
        try {
            ensureNotBlocked(normalizedKey);
            String displayName = resolveDisplayName(normalizedKey, playerName);
            Path playerDir = playersDirectory.resolve(displayName);
            Files.createDirectories(playerDir);
            playerDirectories.putIfAbsent(normalizedKey, playerDir);
            ensureLoaded(normalizedKey, displayName, playerDir, now);
            touchAccess(normalizedKey);

            PlayerRecord record = records.get(normalizedKey);
            ChatEntry entry = new ChatEntry(now, message);
            appendJsonLine(playerDir.resolve(CHAT_FILE), entry);
            addBounded(record.chatMessages, entry, maxCachedHistory());
        } finally {
            lock.unlock();
        }
    }

    void recordStat(String playerName, StatSnapshot snapshot) throws IOException {
        validatePlayerName(playerName);
        String normalizedKey = normalize(playerName);
        ReentrantLock lock = lockFor(normalizedKey);
        lock.lock();
        try {
            ensureNotBlocked(normalizedKey);
            if (statWriteFailure.test(normalizedKey)) {
                throw new IOException("simulated stat write failure for " + normalizedKey);
            }
            String displayName = resolveDisplayName(normalizedKey, playerName);
            Path playerDir = playersDirectory.resolve(displayName);
            Files.createDirectories(playerDir);
            playerDirectories.putIfAbsent(normalizedKey, playerDir);
            ensureLoaded(normalizedKey, displayName, playerDir, snapshot.capturedAt);
            touchAccess(normalizedKey);

            PlayerRecord record = records.get(normalizedKey);
            PlayerProfile profile = profiles.get(normalizedKey);
            writeLatestStat(playerDir.resolve(STATS_FILE), snapshot);
            record.statSnapshots.clear();
            record.statSnapshots.add(snapshot);
            profile.lastSeenAt = Math.max(profile.lastSeenAt, snapshot.capturedAt);
            profile.lastStatCapturedAt = snapshot.capturedAt;
            writeProfile(playerDir, profile);
        } finally {
            lock.unlock();
        }
    }

    PlayerRecord read(String playerName) throws IOException {
        validatePlayerName(playerName);
        String normalizedKey = normalize(playerName);
        ReentrantLock lock = lockFor(normalizedKey);
        lock.lock();
        try {
            ensureNotBlocked(normalizedKey);
            String displayName = resolveDisplayName(normalizedKey, playerName);
            Path playerDir = playersDirectory.resolve(displayName);
            ensureLoaded(normalizedKey, displayName, playerDir, System.currentTimeMillis());
            touchAccess(normalizedKey);
            return records.get(normalizedKey).copy();
        } finally {
            lock.unlock();
        }
    }

    Optional<PlayerRecord> find(String playerName) throws IOException {
        validatePlayerName(playerName);
        String normalizedKey = normalize(playerName);
        ReentrantLock lock = lockFor(normalizedKey);
        lock.lock();
        try {
            ensureNotBlocked(normalizedKey);
            String displayName = displayNames.get(normalizedKey);
            if (displayName == null) {
                // Index missing — rescan the filesystem before giving up
                Path existingDir = playerDirectories.get(normalizedKey);
                if (existingDir == null) {
                    existingDir = scanForExistingPlayerDirectory(normalizedKey);
                }
                if (existingDir != null) {
                    displayName = existingDir.getFileName().toString();
                    playerDirectories.put(normalizedKey, existingDir);
                    displayNames.put(normalizedKey, displayName);
                    playerNames.add(displayName);
                } else {
                    return Optional.empty();
                }
            }
            Path playerDir = playersDirectory.resolve(displayName);
            ensureLoaded(normalizedKey, displayName, playerDir, System.currentTimeMillis());
            touchAccess(normalizedKey);
            return Optional.of(records.get(normalizedKey).copy());
        } finally {
            lock.unlock();
        }
    }

    List<String> listPlayerNames() {
        return List.copyOf(playerNames);
    }

    // ------------------------------------------------------------------
    // Loading / caching
    // ------------------------------------------------------------------

    /**
     * Resolves the canonical display name for a normalized player key by checking
     * the in-memory display-name map, then the persistent disk-directory index,
     * then the filesystem (case-insensitive scan). Only falls back to the
     * requested name when no existing directory is found anywhere.
     */
    private String resolveDisplayName(String normalizedKey, String requestedName) throws IOException {
        String displayName = displayNames.get(normalizedKey);
        if (displayName != null) {
            return displayName;
        }
        Path existingDir = playerDirectories.get(normalizedKey);
        if (existingDir != null) {
            displayName = existingDir.getFileName().toString();
            displayNames.put(normalizedKey, displayName);
            playerNames.add(displayName);
            return displayName;
        }
        existingDir = scanForExistingPlayerDirectory(normalizedKey);
        if (existingDir != null) {
            displayName = existingDir.getFileName().toString();
            playerDirectories.put(normalizedKey, existingDir);
            displayNames.put(normalizedKey, displayName);
            playerNames.add(displayName);
            return displayName;
        }
        displayNames.put(normalizedKey, requestedName);
        playerNames.add(requestedName);
        return requestedName;
    }

    /**
     * Scans the filesystem for a player directory matching the given normalized
     * key case-insensitively. Returns null only when no matching directory exists.
     * Propagates IOException so callers never create a directory when the scan
     * fails. Detects multiple case-only matches and blocks the player.
     */
    private Path scanForExistingPlayerDirectory(String normalizedKey) throws IOException {
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> paths = Files.list(playersDirectory)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                if (isInternalOrQuarantinedDirectory(path)) {
                    continue;
                }
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).equals(normalizedKey)) {
                    matches.add(path);
                }
            }
        }
        if (matches.size() > 1) {
            List<String> names = matches.stream()
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            warn("Found case-only duplicate player directories for '" + normalizedKey + "': "
                    + String.join(", ", names) + "; blocking automatic writes for this player until "
                    + "the conflict is resolved manually.");
            blockedPlayers.add(normalizedKey);
            throw new IOException("Multiple case-only directories exist for player '" + normalizedKey
                    + "': " + String.join(", ", names));
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private void ensureLoaded(String normalizedKey, String displayName, Path playerDir, long now) throws IOException {
        if (records.containsKey(normalizedKey)) {
            return;
        }
        Path profilePath = playerDir.resolve(PROFILE_FILE);
        if (Files.exists(profilePath)) {
            String mismatchReason = detectIdentityMismatch(profilePath, displayName);
            if (mismatchReason != null) {
                warn("Player identity mismatch for '" + displayName + "': " + mismatchReason
                        + "; quarantining the directory contents rather than guessing or merging.");
                quarantineDirectory(playerDir, mismatchReason, normalizedKey);
            }
        }
        PlayerProfile profile = loadOrCreateProfile(playerDir, displayName, normalizedKey, now);
        List<LoginSession> sessions = loadJsonl(playerDir.resolve(SESSIONS_FILE), LoginSession.class, normalizedKey);
        if (profile.currentSession != null) {
            sessions.add(profile.currentSession);
        }
        List<ChatEntry> chat = loadJsonl(playerDir.resolve(CHAT_FILE), ChatEntry.class, normalizedKey);
        List<StatSnapshot> stats = loadLatestStat(playerDir.resolve(STATS_FILE), normalizedKey);

        PlayerRecord record = new PlayerRecord(profile.playerName, profile.firstSeenAt);
        record.loginSessions = sessions;
        record.chatMessages = chat;
        record.statSnapshots = stats;

        records.put(normalizedKey, record);
        profiles.put(normalizedKey, profile);
        playerNames.add(profile.playerName);
    }

    private PlayerProfile loadOrCreateProfile(Path playerDir, String displayName, String normalizedKey, long now)
            throws IOException {
        Path profilePath = playerDir.resolve(PROFILE_FILE);
        if (!Files.exists(profilePath)) {
            return new PlayerProfile(displayName, now);
        }
        PlayerProfile profile;
        try (Reader reader = Files.newBufferedReader(profilePath, StandardCharsets.UTF_8)) {
            profile = prettyGson.fromJson(reader, PlayerProfile.class);
        } catch (JsonParseException error) {
            quarantineWholeFile(profilePath, "corrupted profile.json: " + error.getMessage(), normalizedKey);
            return new PlayerProfile(displayName, now);
        }
        if (profile == null || profile.playerName == null || profile.playerName.isBlank()) {
            quarantineWholeFile(profilePath, "profile.json missing playerName", normalizedKey);
            return new PlayerProfile(displayName, now);
        }
        if (profile.currentSession != null && profile.currentSession.logoutAt != null) {
            profile.currentSession = null;
        }
        return profile;
    }

    private static <T> void trimToLimit(List<T> list, int maximum) {
        while (list.size() > maximum) {
            list.remove(0);
        }
    }

    private static <T> void addBounded(List<T> list, T value, int maximum) {
        list.add(value);
        trimToLimit(list, maximum);
    }

    private String detectIdentityMismatch(Path profilePath, String displayName) {
        try (Reader reader = Files.newBufferedReader(profilePath, StandardCharsets.UTF_8)) {
            PlayerProfile profile = prettyGson.fromJson(reader, PlayerProfile.class);
            if (profile == null || profile.playerName == null || profile.playerName.isBlank()) {
                return null;
            }
            if (!profile.playerName.equalsIgnoreCase(displayName)) {
                return "directory '" + displayName + "' contains profile.json for player '" + profile.playerName + "'";
            }
            return null;
        } catch (IOException | JsonParseException error) {
            return null;
        }
    }

    private <T> List<T> loadJsonl(Path file, Class<T> type, String normalizedKey) throws IOException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        int maximum = maxCachedHistory();
        Deque<T> values = new ArrayDeque<>(maximum);
        List<String> corruptedDescriptions = new ArrayList<>();
        int lineNumber = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    T value = compactGson.fromJson(line, type);
                    if (value == null) {
                        throw new JsonParseException("line parsed to null");
                    }
                    if (values.size() == maximum) {
                        values.removeFirst();
                    }
                    values.addLast(value);
                } catch (JsonParseException error) {
                    corruptedDescriptions.add(file.getFileName() + ":" + lineNumber + " - " + error.getMessage());
                }
            }
        }
        if (!corruptedDescriptions.isEmpty()) {
            repairJsonlFile(file, type, corruptedDescriptions, normalizedKey);
        }
        return new ArrayList<>(values);
    }

    /**
     * Two-pass repair: the first pass builds only the bounded cache and detects
     * corruption; this second pass streams every valid original line directly
     * into a temporary file before safely replacing the damaged file.
     */
    private <T> void repairJsonlFile(Path file, Class<T> type, List<String> corruptedDescriptions,
            String normalizedKey) throws IOException {
        for (String description : corruptedDescriptions) {
            warn("Corrupted JSONL entry detected at " + description);
        }

        Path temp = Files.createTempFile(file.getParent(), TEMP_REPAIR_PREFIX, ".tmp");
        Path quarantine = null;
        try {
            long validEntries = 0L;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        T value = compactGson.fromJson(line, type);
                        if (value == null) {
                            throw new JsonParseException("line parsed to null");
                        }
                        writer.write(line);
                        writer.newLine();
                        validEntries++;
                    } catch (JsonParseException ignored) {
                        // Skip only the damaged line; every other line is preserved in order.
                    }
                }
            }

            if (countJsonlEntries(temp, type) != validEntries) {
                throw new IOException("Validation failed for repaired JSONL file " + file);
            }

            quarantine = quarantineWholeFile(file,
                    corruptedDescriptions.size() + " corrupted line(s)", normalizedKey);
            try {
                if (repairPromotionFailureTrigger.test(file)) {
                    throw new IOException("simulated repaired JSONL promotion failure for " + file);
                }
                atomicMove(temp, file);
                temp = null;
            } catch (IOException promotionError) {
                IOException restoreError = null;
                try {
                    if (repairRestoreFailureTrigger.test(file)) {
                        throw new IOException("simulated quarantined JSONL restoration failure for " + file);
                    }
                    restoreQuarantinedFile(quarantine, file);
                    quarantine = null;
                } catch (IOException error) {
                    restoreError = error;
                }

                if (restoreError == null) {
                    throw new IOException("Unable to promote repaired JSONL file " + file
                            + "; the original corrupted file was restored.", promotionError);
                }

                blockedPlayers.add(normalizedKey);
                warn("CRITICAL: JSONL repair failed for player '" + normalizedKey
                        + "'. Original=" + file
                        + ", quarantine=" + quarantine
                        + ", repairTemp=" + temp
                        + ", promotionError=" + promotionError.getMessage()
                        + ", restorationError=" + restoreError.getMessage()
                        + "; all further automatic writes for this player are blocked.");
                IOException combined = new IOException("Unable to promote repaired JSONL file or restore the "
                        + "quarantined original for player " + normalizedKey, promotionError);
                combined.addSuppressed(restoreError);
                throw combined;
            }
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static void restoreQuarantinedFile(Path quarantine, Path original) throws IOException {
        if (quarantine == null || !Files.exists(quarantine)) {
            throw new IOException("Quarantined original is unavailable: " + quarantine);
        }
        if (Files.exists(original)) {
            throw new IOException("Original path already exists and cannot be safely restored: " + original);
        }
        try {
            Files.move(quarantine, original, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(quarantine, original);
        }
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    private void writeProfile(Path playerDir, PlayerProfile profile) throws IOException {
        Files.createDirectories(playerDir);
        Path target = playerDir.resolve(PROFILE_FILE);
        Path temp = Files.createTempFile(playerDir, TEMP_PROFILE_PREFIX, ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                prettyGson.toJson(profile, writer);
            }
            if (writeFailureTrigger.test(target)) {
                throw new RuntimeException("simulated runtime failure writing " + target);
            }
            atomicMove(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void appendJsonLine(Path file, Object value) throws IOException {
        String line = compactGson.toJson(value);
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private void writeLatestStat(Path file, StatSnapshot snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), TEMP_STAT_PREFIX, ".tmp");
        try {
            Files.writeString(temp, compactGson.toJson(snapshot) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            atomicMove(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private List<StatSnapshot> loadLatestStat(Path file, String normalizedKey) throws IOException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }

        // Reuse the normal loader first so corrupted lines are safely repaired.
        loadJsonl(file, StatSnapshot.class, normalizedKey);

        StatSnapshot latest = null;
        long validCount = 0L;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                StatSnapshot candidate = compactGson.fromJson(line, StatSnapshot.class);
                if (candidate == null) {
                    continue;
                }
                validCount++;
                if (latest == null || candidate.capturedAt >= latest.capturedAt) {
                    latest = candidate;
                }
            }
        } catch (JsonParseException error) {
            throw new IOException("Unable to read repaired Stat data from " + file, error);
        }

        if (latest == null) {
            return new ArrayList<>();
        }
        if (validCount > 1L) {
            writeLatestStat(file, latest);
        }
        return new ArrayList<>(List.of(latest));
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------
    // Quarantine
    // ------------------------------------------------------------------

    private void ensureNotBlocked(String normalizedKey) throws IOException {
        if (blockedPlayers.contains(normalizedKey)) {
            throw new IOException("Player data for '" + normalizedKey
                    + "' is blocked from further reads/writes after an unrecoverable data-safety failure.");
        }
    }

    private Path quarantineWholeFile(Path path, String reason, String normalizedKey) throws IOException {
        Path target = uniqueQuarantinePath(path);
        if (quarantineFailureTrigger.test(path)) {
            blockedPlayers.add(normalizedKey);
            warn("Failed to quarantine corrupted file " + path.getFileName()
                    + ": simulated failure; blocking further reads/writes to this file.");
            throw new IOException("Unable to quarantine corrupted file: " + path);
        }
        try {
            Files.move(path, target);
            warn("Quarantined corrupted file " + path.getFileName() + " -> " + target.getFileName()
                    + (reason == null ? "" : " (" + reason + ")"));
            return target;
        } catch (IOException moveError) {
            blockedPlayers.add(normalizedKey);
            warn("Failed to quarantine corrupted file " + path.getFileName() + ": " + moveError.getMessage()
                    + "; blocking further reads/writes to this file.");
            throw new IOException("Unable to quarantine corrupted file: " + path, moveError);
        }
    }

    private void quarantineDirectory(Path playerDir, String reason, String normalizedKey) throws IOException {
        Path target = uniqueQuarantinePath(playerDir);
        if (quarantineFailureTrigger.test(playerDir)) {
            blockedPlayers.add(normalizedKey);
            warn("Failed to quarantine mismatched player directory " + playerDir.getFileName()
                    + ": simulated failure; blocking further reads/writes to this player.");
            throw new IOException("Unable to quarantine mismatched player directory: " + playerDir);
        }
        try {
            Files.move(playerDir, target);
            warn("Quarantined player directory " + playerDir.getFileName() + " -> " + target.getFileName()
                    + (reason == null ? "" : " (" + reason + ")"));
        } catch (IOException moveError) {
            blockedPlayers.add(normalizedKey);
            warn("Failed to quarantine player directory " + playerDir.getFileName() + ": " + moveError.getMessage()
                    + "; blocking further reads/writes to this player.");
            throw new IOException("Unable to quarantine mismatched player directory: " + playerDir, moveError);
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

    // ------------------------------------------------------------------
    // Startup: temp-file cleanup, legacy migration, indexing
    // ------------------------------------------------------------------

    private void cleanupTempFiles(Path dir) throws IOException {
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : paths.toList()) {
                if (Files.isDirectory(path)) {
                    String name = path.getFileName().toString();
                    if (dir.equals(playersDirectory) && name.startsWith(TEMP_MIGRATION_PREFIX)) {
                        deleteDirectoryQuietly(path);
                    } else if (!isInternalOrQuarantinedDirectory(path)) {
                        cleanupTempFiles(path);
                    }
                } else if (isPluginTempFile(path.getFileName().toString())) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static boolean isPluginTempFile(String fileName) {
        return (fileName.startsWith(TEMP_PROFILE_PREFIX) || fileName.startsWith(TEMP_STAT_PREFIX)
                || fileName.startsWith("xpm-settings-") || fileName.startsWith(TEMP_REPAIR_PREFIX))
                && fileName.endsWith(".tmp");
    }

    private static boolean isInternalOrQuarantinedDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            return false;
        }
        String name = path.getFileName().toString();
        return name.startsWith(TEMP_MIGRATION_PREFIX)
                || name.endsWith(".corrupted")
                || name.contains(".corrupted-");
    }

    private void indexPlayerDirectories() throws IOException {
        Map<String, List<String>> byNormalized = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(playersDirectory)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                if (isInternalOrQuarantinedDirectory(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                byNormalized.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(name);
            }
        }
        for (Map.Entry<String, List<String>> entry : byNormalized.entrySet()) {
            List<String> variants = new ArrayList<>(entry.getValue());
            variants.sort(String::compareTo);
            if (variants.size() > 1) {
                warn("Found case-only duplicate player directories for '" + entry.getKey() + "': "
                        + String.join(", ", variants) + "; blocking automatic writes for this player until "
                        + "the conflict is resolved manually.");
                blockedPlayers.add(entry.getKey());
                continue;
            }
            String canonical = variants.get(0);
            Path playerDir = playersDirectory.resolve(canonical);
            Path profilePath = playerDir.resolve(PROFILE_FILE);
            String playerName = canonical;
            if (Files.exists(profilePath)) {
                String mismatchReason = detectIdentityMismatch(profilePath, canonical);
                if (mismatchReason != null) {
                    warn("Player identity mismatch detected at startup: " + mismatchReason
                            + "; leaving this directory unindexed until it is next accessed.");
                    continue;
                }
                try (Reader reader = Files.newBufferedReader(profilePath, StandardCharsets.UTF_8)) {
                    PlayerProfile profile = prettyGson.fromJson(reader, PlayerProfile.class);
                    if (profile != null && profile.playerName != null && !profile.playerName.isBlank()) {
                        playerName = profile.playerName;
                    }
                } catch (JsonParseException ignored) {
                    // corrupted profile.json is quarantined lazily when this player is next accessed
                }
            }
            displayNames.put(entry.getKey(), playerName);
            playerNames.add(playerName);
            playerDirectories.put(entry.getKey(), playerDir);
        }
    }

    private static final class MigrationCounts {
        int succeeded;
        int skipped;
        int failed;
    }

    private void migrateLegacyPlayerFiles() throws IOException {
        MigrationCounts counts = new MigrationCounts();
        List<Path> flatLegacyInRoot;
        try (Stream<Path> paths = Files.list(directory)) {
            flatLegacyInRoot = paths.filter(Files::isRegularFile)
                    .filter(path -> isLegacyPlayerFile(path.getFileName().toString()))
                    .toList();
        }
        for (Path legacy : flatLegacyInRoot) {
            migrateOneLegacyFile(legacy, counts);
        }
        List<Path> flatLegacyInPlayers;
        try (Stream<Path> paths = Files.list(playersDirectory)) {
            flatLegacyInPlayers = paths.filter(Files::isRegularFile)
                    .filter(path -> isLegacyPlayerFile(path.getFileName().toString()))
                    .toList();
        }
        for (Path legacy : flatLegacyInPlayers) {
            migrateOneLegacyFile(legacy, counts);
        }
        if (counts.succeeded > 0 || counts.skipped > 0 || counts.failed > 0) {
            warn("Legacy player data migration complete: " + counts.succeeded + " succeeded, "
                    + counts.skipped + " skipped, " + counts.failed + " failed.");
        }
    }

    private static boolean isLegacyPlayerFile(String fileName) {
        return fileName.endsWith(".json") && !"settings.json".equalsIgnoreCase(fileName);
    }

    private void migrateOneLegacyFile(Path legacy, MigrationCounts counts) {
        PlayerRecord legacyRecord;
        try (Reader reader = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) {
            legacyRecord = prettyGson.fromJson(reader, PlayerRecord.class);
        } catch (IOException | JsonParseException error) {
            warn("Failed to migrate " + legacy.getFileName() + ": unable to parse legacy record ("
                    + error.getMessage() + "); left in place.");
            counts.failed++;
            return;
        }
        if (legacyRecord == null || legacyRecord.playerName == null || legacyRecord.playerName.isBlank()) {
            String fileName = legacy.getFileName().toString();
            String fallbackName = fileName.substring(0, fileName.length() - ".json".length());
            if (legacyRecord == null) {
                legacyRecord = new PlayerRecord(fallbackName, System.currentTimeMillis());
            } else {
                legacyRecord.playerName = fallbackName;
            }
            warn("Legacy record " + legacy.getFileName() + " was missing a playerName; using filename '"
                    + fallbackName + "' instead.");
        }
        if (legacyRecord.loginSessions == null) legacyRecord.loginSessions = new ArrayList<>();
        if (legacyRecord.chatMessages == null) legacyRecord.chatMessages = new ArrayList<>();
        if (legacyRecord.statSnapshots == null) legacyRecord.statSnapshots = new ArrayList<>();

        String normalizedLegacyName = normalize(legacyRecord.playerName);
        Path existingDir = playerDirectories.get(normalizedLegacyName);
        if (existingDir == null) {
            try {
                existingDir = scanForExistingPlayerDirectory(normalizedLegacyName);
            } catch (IOException error) {
                warn("Failed to migrate " + legacy.getFileName() + ": cannot scan for existing directory ("
                        + error.getMessage() + "); left in place.");
                counts.failed++;
                return;
            }
        }
        if (existingDir != null) {
            warn("Skipped migrating " + legacy.getFileName() + ": destination player directory '"
                    + existingDir.getFileName() + "' already exists (case-insensitive match for '"
                    + legacyRecord.playerName + "'); both the legacy file and the existing "
                    + "directory were left in place.");
            counts.skipped++;
            return;
        }
        Path targetDir = playersDirectory.resolve(legacyRecord.playerName);

        Path stagingDir = null;
        try {
            if (migrationFailureTrigger.test(legacyRecord.playerName)) {
                throw new IOException("simulated migration failure for " + legacyRecord.playerName);
            }
            stagingDir = Files.createTempDirectory(playersDirectory, TEMP_MIGRATION_PREFIX);
            writeMigratedProfile(stagingDir, legacyRecord);
            writeMigratedJsonl(stagingDir.resolve(SESSIONS_FILE), completedSessionsOf(legacyRecord));
            writeMigratedJsonl(stagingDir.resolve(CHAT_FILE), legacyRecord.chatMessages);
            StatSnapshot latestLegacyStat = latestStatOf(legacyRecord.statSnapshots);
            writeMigratedJsonl(stagingDir.resolve(STATS_FILE),
                    latestLegacyStat == null ? List.of() : List.of(latestLegacyStat));

            if (!validateMigratedDirectory(stagingDir, legacyRecord)) {
                warn("Failed to migrate " + legacy.getFileName() + ": validation of converted data failed; left in place.");
                counts.failed++;
                return;
            }
            if (Files.exists(targetDir)) {
                warn("Skipped migrating " + legacy.getFileName() + ": destination player directory '"
                        + legacyRecord.playerName + "' was created concurrently; both were left in place.");
                counts.skipped++;
                return;
            }
            try {
                Files.move(stagingDir, targetDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(stagingDir, targetDir);
            }
            stagingDir = null;
            Files.deleteIfExists(legacy);
            playerDirectories.put(normalizedLegacyName, targetDir);
            counts.succeeded++;
        } catch (IOException error) {
            warn("Failed to migrate " + legacy.getFileName() + ": " + error.getMessage() + "; left in place.");
            counts.failed++;
        } finally {
            if (stagingDir != null) {
                deleteDirectoryQuietly(stagingDir);
            }
        }
    }

    private void writeMigratedProfile(Path stagingDir, PlayerRecord legacyRecord) throws IOException {
        PlayerProfile profile = new PlayerProfile(legacyRecord.playerName, legacyRecord.firstSeenAt);
        LoginSession open = legacyRecord.loginSessions.stream()
                .filter(session -> session.logoutAt == null)
                .max(Comparator.comparingLong(session -> session.loginAt))
                .orElse(null);
        profile.currentSession = open;
        profile.online = open != null;

        long lastSeen = legacyRecord.firstSeenAt;
        for (LoginSession session : legacyRecord.loginSessions) {
            lastSeen = Math.max(lastSeen, session.loginAt);
            if (session.logoutAt != null) {
                lastSeen = Math.max(lastSeen, session.logoutAt);
            }
        }
        for (ChatEntry entry : legacyRecord.chatMessages) {
            lastSeen = Math.max(lastSeen, entry.timestamp);
        }
        long lastStat = -1;
        for (StatSnapshot snapshot : legacyRecord.statSnapshots) {
            lastSeen = Math.max(lastSeen, snapshot.capturedAt);
            lastStat = Math.max(lastStat, snapshot.capturedAt);
        }
        profile.lastSeenAt = lastSeen;
        profile.lastStatCapturedAt = lastStat < 0 ? null : lastStat;

        Path temp = Files.createTempFile(stagingDir, TEMP_PROFILE_PREFIX, ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                prettyGson.toJson(profile, writer);
            }
            atomicMove(temp, stagingDir.resolve(PROFILE_FILE));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static List<LoginSession> completedSessionsOf(PlayerRecord legacyRecord) {
        return legacyRecord.loginSessions.stream()
                .filter(session -> session.logoutAt != null)
                .toList();
    }

    private static StatSnapshot latestStatOf(List<StatSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        return snapshots.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingLong(snapshot -> snapshot.capturedAt))
                .orElse(null);
    }

    private <T> void writeMigratedJsonl(Path target, List<T> values) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (T value : values) {
            builder.append(compactGson.toJson(value)).append(System.lineSeparator());
        }
        Files.writeString(target, builder.toString(), StandardCharsets.UTF_8);
    }

    private boolean validateMigratedDirectory(Path stagingDir, PlayerRecord legacyRecord) {
        try {
            Path profilePath = stagingDir.resolve(PROFILE_FILE);
            if (!Files.exists(profilePath)) {
                return false;
            }
            PlayerProfile profile;
            try (Reader reader = Files.newBufferedReader(profilePath, StandardCharsets.UTF_8)) {
                profile = prettyGson.fromJson(reader, PlayerProfile.class);
            }
            if (profile == null || profile.playerName == null
                    || !profile.playerName.equalsIgnoreCase(legacyRecord.playerName)) {
                return false;
            }
            long expectedCompletedSessions = legacyRecord.loginSessions.stream()
                    .filter(session -> session.logoutAt != null)
                    .count();
            if (countJsonlEntries(stagingDir.resolve(SESSIONS_FILE), LoginSession.class)
                    != expectedCompletedSessions) {
                return false;
            }
            if (countJsonlEntries(stagingDir.resolve(CHAT_FILE), ChatEntry.class)
                    != legacyRecord.chatMessages.size()) {
                return false;
            }
            long expectedStatEntries = latestStatOf(legacyRecord.statSnapshots) == null ? 0L : 1L;
            return countJsonlEntries(stagingDir.resolve(STATS_FILE), StatSnapshot.class)
                    == expectedStatEntries;
        } catch (IOException | JsonParseException error) {
            return false;
        }
    }

    private static long countJsonlEntries(Path file, Class<?> type) throws IOException {
        if (!Files.exists(file)) {
            return 0L;
        }
        long count = 0L;
        Gson gson = new Gson();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Object value = gson.fromJson(line, type);
                if (value == null) {
                    throw new JsonParseException("line parsed to null during migration validation");
                }
                count++;
            }
        }
        return count;
    }

    private static void deleteDirectoryQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of a migration staging directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of a migration staging directory
        }
    }

    // ------------------------------------------------------------------
    // Cache eviction
    // ------------------------------------------------------------------

    private void startEvictionScheduler() {
        if (evictionExecutor != null) {
            return;
        }
        evictionExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "XinPlayerMonitor-cache-eviction");
            thread.setDaemon(true);
            return thread;
        });
        evictionExecutor.scheduleWithFixedDelay(this::evictIdleRecords,
                EVICTION_CHECK_INTERVAL_MILLIS, EVICTION_CHECK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    void evictIdleRecords() {
        evictRecordsIdleSince(System.currentTimeMillis() - Math.max(1L, cacheIdleMillis.getAsLong()));
    }

    /** Package-private so tests can force eviction without waiting on real time. */
    void evictRecordsIdleSince(long cutoff) {
        for (String normalizedKey : List.copyOf(records.keySet())) {
            Long last = lastAccessAt.get(normalizedKey);
            if (last == null || last > cutoff) {
                continue;
            }
            if (evictionGuard.test(normalizedKey)) {
                continue;
            }
            ReentrantLock lock = lockFor(normalizedKey);
            if (!lock.tryLock()) {
                continue;
            }
            try {
                Long recheck = lastAccessAt.get(normalizedKey);
                if (recheck == null || recheck > cutoff || evictionGuard.test(normalizedKey)) {
                    continue;
                }
                records.remove(normalizedKey);
                profiles.remove(normalizedKey);
                lastAccessAt.remove(normalizedKey);
                // displayNames and playerDirectories persist — they are the disk index.
                // Striped locks are never removed.
            } finally {
                lock.unlock();
            }
        }
    }


    /** Applies a reduced runtime history limit immediately without touching disk history. */
    void trimCachedHistoryToConfiguredLimit() {
        int maximum = maxCachedHistory();
        for (String normalizedKey : List.copyOf(records.keySet())) {
            ReentrantLock lock = lockFor(normalizedKey);
            lock.lock();
            try {
                PlayerRecord record = records.get(normalizedKey);
                if (record != null) {
                    trimToLimit(record.loginSessions, maximum);
                    trimToLimit(record.chatMessages, maximum);
                    trimToLimit(record.statSnapshots, maximum);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private int maxCachedHistory() {
        return Math.max(1, maxCachedHistoryEntries.getAsInt());
    }

    /** Test-only hook. */
    boolean isCachedForTesting(String playerName) {
        return records.containsKey(normalize(playerName));
    }

    /** Test-only hook. */
    void forceLastAccessForTesting(String playerName, long timestamp) {
        lastAccessAt.put(normalize(playerName), timestamp);
    }

    /** Test-only hook: returns the striped lock identity for a player name. */
    ReentrantLock lockForTesting(String playerName) {
        return lockFor(normalize(playerName));
    }

    /** Test-only hook: checks whether the disk index contains a player directory. */
    boolean isDirectoryIndexedForTesting(String playerName) {
        return playerDirectories.containsKey(normalize(playerName));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void touchAccess(String normalizedKey) {
        lastAccessAt.put(normalizedKey, System.currentTimeMillis());
    }

    private ReentrantLock lockFor(String normalizedKey) {
        return lockStripes[Math.floorMod(normalizedKey.hashCode(), LOCK_STRIPE_COUNT)];
    }

    private static String normalize(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }

    private static void validatePlayerName(String playerName) throws IOException {
        Objects.requireNonNull(playerName, "playerName");
        if (playerName.isBlank() || playerName.indexOf('/') >= 0 || playerName.indexOf('\\') >= 0
                || playerName.matches(".*[:*?\"<>|].*") || ".".equals(playerName) || "..".equals(playerName)) {
            throw new IOException("Unsafe player name for file storage: " + playerName);
        }
    }

    private void warn(String message) {
        warningSink.accept(message);
    }
}
