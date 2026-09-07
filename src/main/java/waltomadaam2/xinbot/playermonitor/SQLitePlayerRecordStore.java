package waltomadaam2.xinbot.playermonitor;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class SQLitePlayerRecordStore implements PlayerRepository {
    private static final long MIN_WRITER_RECOVERY_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long MAX_WRITER_RECOVERY_BACKOFF_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private final Path directory;
    private final Path databasePath;
    private final Path failedEventsPath;
    private final MonitorSettings.Database databaseSettings;
    private final Gson gson = new Gson();
    private final BlockingQueue<WriteTask> queue;
    private final TreeSet<String> playerNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Object playerNamesLock = new Object();
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong lastCommittedAt = new AtomicLong();
    private final AtomicLong lastWriterProgressAt = new AtomicLong();
    private final AtomicLong lastChatCommittedAt = new AtomicLong();
    private final AtomicLong lastSessionCommittedAt = new AtomicLong();
    private final AtomicLong lastStatCommittedAt = new AtomicLong();
    private final AtomicLong lastFailureAt = new AtomicLong();
    private final AtomicLong writerRecoveryCount = new AtomicLong();
    private final AtomicLong chatCommitted = new AtomicLong();
    private final AtomicLong chatDbFailed = new AtomicLong();
    private final AtomicLong chatQueueRejected = new AtomicLong();
    private final AtomicLong failedEventPersistFailures = new AtomicLong();
    private final AtomicLong failedEventLines = new AtomicLong();
    private final AtomicLong replayedFailedEvents = new AtomicLong();
    private final AtomicLong malformedFailedEvents = new AtomicLong();
    private final AtomicInteger queueHighWaterMark = new AtomicInteger();
    private final AtomicInteger inFlightCount = new AtomicInteger();
    private final Object queueSamplesLock = new Object();
    private final Deque<QueueSample> queueSamples = new ArrayDeque<>();
    private volatile String lastFailureMessage = "";
    private volatile boolean writerRecovering;
    private volatile boolean writerStalled;
    private volatile long lastWriterStallWarningAt;
    private volatile long lastQueueGrowthWarningAt;
    private final ConcurrentLinkedQueue<FailureRecord> pendingFailures = new ConcurrentLinkedQueue<>();
    private volatile Consumer<String> warningSink = ignored -> {
    };
    private volatile Consumer<String> infoSink = ignored -> {
    };
    private volatile Predicate<String> statWriteFailure = ignored -> false;
    private volatile long writeDelayMillisForTesting;
    private final Object lifecycleLock = new Object();
    private final Object failedEventsFileLock = new Object();
    private volatile LifecycleState lifecycleState = LifecycleState.NEW;
    private volatile IOException terminalFailure;
    private Thread writerThread;
    private ScheduledExecutorService watchdogExecutor;

    SQLitePlayerRecordStore(Path directory, MonitorSettings.Database settings) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.databaseSettings = settings == null ? new MonitorSettings.Database() : settings.copy();
        Path configured = Path.of(this.databaseSettings.path == null || this.databaseSettings.path.isBlank()
                ? MonitorSettings.Database.DEFAULT_PATH : this.databaseSettings.path);
        this.databasePath = configured.isAbsolute() ? configured : directory.resolve(configured);
        this.failedEventsPath = directory.resolve("failed-events.jsonl");
        this.queue = new ArrayBlockingQueue<>(Math.max(1, this.databaseSettings.queueCapacity));
    }

    @Override
    public void setWarningSink(Consumer<String> warningSink) {
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }
    @Override
    public void setInfoSink(Consumer<String> infoSink) {
        this.infoSink = Objects.requireNonNull(infoSink, "infoSink");
    }

    @Override
    public void setStatWriteFailureForTesting(Predicate<String> statWriteFailure) {
        this.statWriteFailure = Objects.requireNonNull(statWriteFailure, "statWriteFailure");
    }

    void setWriteDelayForTesting(long writeDelayMillisForTesting) {
        this.writeDelayMillisForTesting = Math.max(0L, writeDelayMillisForTesting);
    }

    void enterTerminalFailureForTesting(IOException error) {
        enterTerminalFailure(error);
    }

    @Override
    public synchronized void initialize() throws IOException {
        synchronized (lifecycleLock) {
            if (lifecycleState == LifecycleState.RUNNING) {
                return;
            }
            if (lifecycleState == LifecycleState.CLOSING) {
                throw new IOException("SQLite storage is currently closing");
            }
            if (lifecycleState == LifecycleState.FAILED) {
                throw new IOException("SQLite storage is in a failed state", terminalFailure);
            }
            lifecycleState = LifecycleState.NEW;
        }
        Files.createDirectories(directory);
        if (databasePath.getParent() != null) {
            Files.createDirectories(databasePath.getParent());
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException error) {
            throw new IOException("SQLite JDBC driver is not available in the plugin classpath", error);
        }
        try (Connection connection = openConnection()) {
            SQLiteSchema.initialize(connection);
            new SQLiteLegacyMigrator(directory, gson, warningSink,
                    databaseSettings.allowPartialLegacyMigration).migrateIfNeeded(connection);
            SQLiteSchema.verify(connection);
            FailedReplaySummary replaySummary = replayFailedEvents(connection);
            if (replaySummary.replayed() > 0) {
                SQLiteSchema.verify(connection);
            }
            loadPlayerNameIndex(connection);
            failedEventLines.set(replaySummary.totalLines());
            replayedFailedEvents.set(replaySummary.alreadyReplayed() + replaySummary.replayed());
            malformedFailedEvents.set(replaySummary.malformed());
            info("XinPM SQLite database: " + databasePath.toAbsolutePath());
            info("Schema version: " + SQLiteSchema.schemaVersion(connection));
            info("Journal mode: " + SQLiteSchema.value(connection, "PRAGMA journal_mode"));
            info("Legacy migration status: " + legacyMigrationStatus(connection));
            info("Database writer batch size: " + databaseSettings.batchSize);
            if (replaySummary.totalLines() > 0) {
                info("Failed-event replay: replayed=" + replaySummary.replayed()
                        + ", alreadyReplayed=" + replaySummary.alreadyReplayed()
                        + ", malformed=" + replaySummary.malformed()
                        + ", stillPending=" + replaySummary.pending());
            }
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("initialize SQLite storage", error);
        }
        synchronized (lifecycleLock) {
            terminalFailure = null;
            lastWriterProgressAt.set(System.currentTimeMillis());
            writerRecovering = false;
            writerStalled = false;
            lifecycleState = LifecycleState.RUNNING;
            writerThread = new Thread(this::writerLoop, "XinPlayerMonitor-sqlite-writer");
            writerThread.setDaemon(true);
            writerThread.start();
            watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "XinPlayerMonitor-sqlite-watchdog");
                thread.setDaemon(true);
                return thread;
            });
            watchdogExecutor.scheduleWithFixedDelay(this::writerWatchdog, 30L, 30L, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        Thread thread;
        synchronized (lifecycleLock) {
            if (lifecycleState == LifecycleState.NEW || lifecycleState == LifecycleState.CLOSED) {
                lifecycleState = LifecycleState.CLOSED;
                return;
            }
            if (lifecycleState == LifecycleState.RUNNING) {
                lifecycleState = LifecycleState.CLOSING;
            }
            thread = writerThread;
        }
        ScheduledExecutorService watchdog = watchdogExecutor;
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdogExecutor = null;
        }

        if (thread != null && thread != Thread.currentThread()) {
            long timeout = Math.max(1000L, databaseSettings.shutdownFlushTimeoutMs);
            try {
                thread.join(timeout);
                if (thread.isAlive()) {
                    warn("SQLite writer did not stop within " + timeout
                            + " ms; pending events=" + queue.size() + "; interrupting writer");
                    thread.interrupt();
                    thread.join(Math.min(5000L, timeout));
                }
                if (thread.isAlive()) {
                    IOException failure = new IOException("SQLite writer remained alive after shutdown timeout");
                    terminalFailure = failure;
                    synchronized (lifecycleLock) {
                        lifecycleState = LifecycleState.FAILED;
                    }
                    warn("SEVERE: " + failure.getMessage() + "; pending events=" + queue.size());
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                warn("Interrupted while waiting for SQLite writer shutdown; pending events=" + queue.size());
            }
        }

        synchronized (lifecycleLock) {
            if (writerThread == thread && (thread == null || !thread.isAlive())) {
                writerThread = null;
                if (lifecycleState != LifecycleState.FAILED) {
                    lifecycleState = LifecycleState.CLOSED;
                }
            }
        }
        IOException fatal = terminalFailure;
        FailureRecord failure = pendingFailures.peek();
        if (fatal != null) {
            warn("SEVERE: SQLite writer closed in failed state: " + fatal.getMessage());
        } else if (failure != null) {
            warn("SEVERE: SQLite writer closed with an unacknowledged failed event; sequence="
                    + failure.sequence() + ", error=" + failure.error().getMessage());
        }
    }

    @Override
    public void recordLogin(String playerName, long now) throws IOException {
        validatePlayerName(playerName);
        submit(new LoginEvent(playerName, now));
    }

    @Override
    public void recordLogout(String playerName, long now) throws IOException {
        validatePlayerName(playerName);
        submit(new LogoutEvent(playerName, now));
    }

    @Override
    public void recordChat(String playerName, String message, long now) throws IOException {
        validatePlayerName(playerName);
        submit(new ChatEvent(playerName, Objects.requireNonNull(message, "message"), now));
    }

    @Override
    public void recordStat(String playerName, StatSnapshot snapshot) throws IOException {
        validatePlayerName(playerName);
        submit(new StatEvent(playerName, Objects.requireNonNull(snapshot, "snapshot").copy()));
    }

    @Override
    public StoredPlayerIdentity recordIdentityCheck(String playerName, IdentityResolution resolution, long checkedAt)
            throws IOException {
        validatePlayerName(playerName);
        submit(new IdentityEvent(playerName, Objects.requireNonNull(resolution, "resolution"), checkedAt));
        flush();
        return playerIdentity(playerName).orElseThrow(
                () -> new IOException("UUID identity write did not create player " + playerName));
    }

    @Override
    public Optional<StoredPlayerIdentity> playerIdentity(String playerName) throws IOException {
        validatePlayerName(playerName);
        flush();
        try (Connection connection = openConnection()) {
            Long playerId = findPlayerId(connection, normalize(playerName));
            return playerId == null ? Optional.empty() : Optional.of(readIdentity(connection, playerId));
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read player UUID identity " + playerName, error);
        }
    }

    @Override
    public OptionalLong playerLastSeenAt(String playerName) throws IOException {
        validatePlayerName(playerName);
        flush();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT last_seen_at FROM players WHERE normalized_name = ?")) {
            statement.setString(1, normalize(playerName));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? OptionalLong.of(resultSet.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read last seen for " + playerName, error);
        }
    }

    @Override
    public PlayerRecord read(String playerName) throws IOException {
        validatePlayerName(playerName);
        return find(playerName).orElseGet(() -> new PlayerRecord(playerName, System.currentTimeMillis()));
    }

    @Override
    public Optional<PlayerRecord> find(String playerName) throws IOException {
        validatePlayerName(playerName);
        flush();
        try (Connection connection = openConnection()) {
            Long playerId = findPlayerId(connection, normalize(playerName));
            return playerId == null ? Optional.empty() : Optional.of(readRecord(connection, playerId));
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read player " + playerName, error);
        }
    }

    @Override
    public List<String> listPlayerNames() throws IOException {
        flush();
        return playerNamesSnapshot();
    }

    @Override
    public List<String> listPlayerNamesSnapshot() {
        return playerNamesSnapshot();
    }

    private List<String> playerNamesSnapshot() {
        synchronized (playerNamesLock) {
            return List.copyOf(playerNames);
        }
    }

    @Override
    public DatabaseStats databaseStats() throws IOException {
        flush();
        return databaseStatsSnapshot();
    }

    @Override
    public DatabaseStats databaseStatsSnapshot() throws IOException {
        try (Connection connection = openConnection()) {
            return new DatabaseStats(
                    countRows(connection, "players"),
                    countRows(connection, "chat_messages"),
                    countRows(connection, "sessions"),
                    countRows(connection, "stat_snapshots"),
                    countUuidRecords(connection),
                    countOpenSessions(connection));
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read database stats", error);
        }
    }

    @Override
    public DatabaseHealth databaseHealth() throws IOException {
        Thread thread = writerThread;
        long failedLines = failedEventLines.get();
        long replayed = replayedFailedEvents.get();
        int currentQueueSize = queue.size();
        recordQueueSample(System.currentTimeMillis(), currentQueueSize);
        long lastUuidWrittenAt;
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COALESCE(MAX(uuid_last_written_at), 0) FROM players")) {
            lastUuidWrittenAt = resultSet.next() ? resultSet.getLong(1) : 0L;
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read last UUID write timestamp", error);
        }
        return new DatabaseHealth(
                lifecycleState.name(),
                thread != null && thread.isAlive(),
                writerRecovering,
                writerStalled,
                currentQueueSize,
                currentQueueSize + queue.remainingCapacity(),
                queueHighWaterMark.get(),
                queueDelta(TimeUnit.MINUTES.toMillis(1)),
                queueDelta(TimeUnit.MINUTES.toMillis(5)),
                lastCommittedAt.get(),
                lastChatCommittedAt.get(),
                lastSessionCommittedAt.get(),
                lastStatCommittedAt.get(),
                lastUuidWrittenAt,
                lastFailureAt.get(),
                lastFailureMessage,
                writerRecoveryCount.get(),
                chatCommitted.get(),
                chatDbFailed.get(),
                chatQueueRejected.get(),
                failedEventPersistFailures.get(),
                failedLines,
                replayed,
                Math.max(0L, failedLines - replayed),
                malformedFailedEvents.get(),
                fileSize(databasePath),
                fileSize(databasePath.resolveSibling(databasePath.getFileName() + "-wal")),
                fileSize(databasePath.resolveSibling(databasePath.getFileName() + "-shm")));
    }

    @Override
    public Optional<PlayerRecord> findSummary(String playerName) throws IOException {
        validatePlayerName(playerName);
        flush();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT display_name, first_seen_at FROM players WHERE normalized_name = ?")) {
            statement.setString(1, normalize(playerName));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerRecord(resultSet.getString("display_name"), resultSet.getLong("first_seen_at")));
            }
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read player summary " + playerName, error);
        }
    }

    @Override
    public boolean playerExists(String playerName) throws IOException {
        validatePlayerName(playerName);
        flush();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM players WHERE normalized_name = ? LIMIT 1")) {
            statement.setString(1, normalize(playerName));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("check player existence " + playerName, error);
        }
    }

    @Override
    public Optional<StatSnapshot> latestStat(String playerName) throws IOException {
        validatePlayerName(playerName);
        flush();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT stats.stat_json
                     FROM stat_snapshots stats
                     JOIN players p ON p.id = stats.player_id
                     WHERE p.normalized_name = ?
                     ORDER BY stats.timestamp DESC, stats.id DESC
                     LIMIT 1
                     """)) {
            statement.setString(1, normalize(playerName));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(gson.fromJson(resultSet.getString("stat_json"), StatSnapshot.class));
            }
        } catch (JsonParseException error) {
            throw new IOException("Unable to parse latest stat for " + playerName + ": " + error.getMessage(), error);
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read latest stat " + playerName, error);
        }
    }

    @Override
    public Optional<LoginSession> latestLogin(String playerName) throws IOException {
        List<LoginSession> sessions = recentLogins(playerName, 1);
        return sessions.isEmpty() ? Optional.empty() : Optional.of(sessions.get(0));
    }

    @Override
    public List<LoginSession> recentLogins(String playerName, int limit) throws IOException {
        validatePlayerName(playerName);
        flush();
        if (limit <= 0) {
            return List.of();
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT s.login_at, s.logout_at
                     FROM sessions s
                     JOIN players p ON p.id = s.player_id
                     WHERE p.normalized_name = ?
                     ORDER BY s.login_at DESC, s.id DESC
                     LIMIT ?
                     """)) {
            statement.setString(1, normalize(playerName));
            statement.setInt(2, limit);
            List<LoginSession> sessions = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    LoginSession session = new LoginSession(resultSet.getLong("login_at"));
                    long logoutAt = resultSet.getLong("logout_at");
                    session.logoutAt = resultSet.wasNull() ? null : logoutAt;
                    sessions.add(session);
                }
            }
            return sessions;
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read recent logins " + playerName, error);
        }
    }

    @Override
    public int loginCount(String playerName) throws IOException {
        return countRows(playerName, "sessions");
    }

    @Override
    public List<ChatEntry> recentChats(String playerName, int limit) throws IOException {
        validatePlayerName(playerName);
        flush();
        if (limit <= 0) {
            return List.of();
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT c.timestamp, c.message
                     FROM chat_messages c
                     JOIN players p ON p.id = c.player_id
                     WHERE p.normalized_name = ?
                     ORDER BY c.timestamp DESC, c.id DESC
                     LIMIT ?
                     """)) {
            statement.setString(1, normalize(playerName));
            statement.setInt(2, limit);
            List<ChatEntry> chats = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    chats.add(new ChatEntry(resultSet.getLong("timestamp"), resultSet.getString("message")));
                }
            }
            return chats;
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read recent chats " + playerName, error);
        }
    }

    @Override
    public int chatCount(String playerName) throws IOException {
        return countRows(playerName, "chat_messages");
    }

    @Override
    public Optional<PlayerOverview> playerOverview(String playerName, long now) throws IOException {
        validatePlayerName(playerName);
        flush();
        try (Connection connection = openConnection()) {
            Long playerId = findPlayerId(connection, normalize(playerName));
            if (playerId == null) {
                return Optional.empty();
            }
            String displayName;
            long firstSeenAt;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT display_name, first_seen_at FROM players WHERE id = ?")) {
                statement.setLong(1, playerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    displayName = resultSet.getString("display_name");
                    firstSeenAt = resultSet.getLong("first_seen_at");
                }
            }

            long chatTotal = countRows(connection, playerId, "chat_messages");
            Long latestLoginAt = null;
            Long latestLogoutAt = null;
            Long latestDurationMillis = null;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT login_at, logout_at
                    FROM sessions
                    WHERE player_id = ?
                    ORDER BY login_at DESC, id DESC
                    LIMIT 1
                    """)) {
                statement.setLong(1, playerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        latestLoginAt = resultSet.getLong("login_at");
                        long logout = resultSet.getLong("logout_at");
                        latestLogoutAt = resultSet.wasNull() ? null : logout;
                        latestDurationMillis = Math.max(0L, (latestLogoutAt == null ? now : latestLogoutAt) - latestLoginAt);
                    }
                }
            }

            long playtimeLast30DaysMillis = playtimeSince(connection, playerId,
                    now - TimeUnit.DAYS.toMillis(30), now);
            StatSnapshot latestStat = latestStatForPlayerId(connection, playerId);
            List<ChatEntry> recentChats = recentChatsForPlayerId(connection, playerId, 5);
            return Optional.of(new PlayerOverview(displayName, firstSeenAt, chatTotal, recentChats,
                    latestLoginAt, latestLogoutAt, latestDurationMillis, playtimeLast30DaysMillis, latestStat,
                    readIdentity(connection, playerId)));
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read player overview " + playerName, error);
        }
    }

    private int countRows(String playerName, String tableName) throws IOException {
        validatePlayerName(playerName);
        flush();
        String sql = "SELECT COUNT(*) FROM " + tableName + " r JOIN players p ON p.id = r.player_id WHERE p.normalized_name = ?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalize(playerName));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("count " + tableName + " for " + playerName, error);
        }
    }

    private static long countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private static long countOpenSessions(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM sessions WHERE logout_at IS NULL")) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private static long countUuidRecords(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM players WHERE server_uuid IS NOT NULL")) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }
    @Override
    public boolean hasStatCapturedAtOrAfter(String playerName, long cutoffAt) throws IOException {
        validatePlayerName(playerName);
        flush();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1
                     FROM stat_snapshots stats
                     JOIN players p ON p.id = stats.player_id
                     WHERE p.normalized_name = ? AND stats.timestamp >= ?
                     LIMIT 1
                     """)) {
            statement.setString(1, normalize(playerName));
            statement.setLong(2, cutoffAt);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("check stat cooldown for " + playerName, error);
        }
    }

    @Override
    public Set<String> playersWithStatCapturedAtOrAfter(Collection<String> names, long cutoffAt) throws IOException {
        Objects.requireNonNull(names, "names");
        Set<String> normalizedNames = new HashSet<>();
        for (String name : names) {
            validatePlayerName(name);
            normalizedNames.add(normalize(name));
        }
        if (normalizedNames.isEmpty()) {
            return Set.of();
        }
        flush();
        Set<String> matches = new HashSet<>();
        List<String> allNames = List.copyOf(normalizedNames);
        final int chunkSize = 400;
        try (Connection connection = openConnection()) {
            for (int start = 0; start < allNames.size(); start += chunkSize) {
                List<String> chunk = allNames.subList(start, Math.min(allNames.size(), start + chunkSize));
                String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
                String sql = """
                        SELECT p.normalized_name
                        FROM stat_snapshots stats
                        JOIN players p ON p.id = stats.player_id
                        WHERE stats.timestamp >= ? AND p.normalized_name IN (%s)
                        """.formatted(placeholders);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, cutoffAt);
                    for (int index = 0; index < chunk.size(); index++) {
                        statement.setString(index + 2, chunk.get(index));
                    }
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            matches.add(resultSet.getString(1));
                        }
                    }
                }
            }
            return Set.copyOf(matches);
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("check batched stat cooldown", error);
        }
    }

    @Override
    public int recoverOpenSessions(long recoveredAt) throws IOException {
        flush();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                int openSessions;
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery(
                             "SELECT COUNT(*) FROM sessions WHERE logout_at IS NULL")) {
                    openSessions = resultSet.next() ? resultSet.getInt(1) : 0;
                }
                if (openSessions == 0) {
                    connection.commit();
                    return 0;
                }
                try (PreparedStatement closeSessions = connection.prepareStatement("""
                        UPDATE sessions
                        SET logout_at = CASE WHEN login_at > ? THEN login_at ELSE ? END
                        WHERE logout_at IS NULL
                        """);
                     PreparedStatement markPlayersOffline = connection.prepareStatement("""
                        UPDATE players
                        SET online = 0,
                            current_session_id = NULL,
                            last_seen_at = MAX(last_seen_at, ?),
                            updated_at = ?
                        WHERE online = 1 OR current_session_id IS NOT NULL
                        """)) {
                    closeSessions.setLong(1, recoveredAt);
                    closeSessions.setLong(2, recoveredAt);
                    closeSessions.executeUpdate();
                    markPlayersOffline.setLong(1, recoveredAt);
                    markPlayersOffline.setLong(2, recoveredAt);
                    markPlayersOffline.executeUpdate();
                }
                connection.commit();
                long committedAt = System.currentTimeMillis();
                lastCommittedAt.set(committedAt);
                lastWriterProgressAt.set(committedAt);
                lastSessionCommittedAt.set(committedAt);
                return openSessions;
            } catch (SQLException error) {
                SQLiteSchema.rollbackQuietly(connection);
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("recover stale open sessions", error);
        }
    }

    @Override
    public void flush() throws IOException {
        LifecycleState state = lifecycleState;
        if (state == LifecycleState.NEW || state == LifecycleState.CLOSED) {
            return;
        }
        submit(new FlushEvent());
    }

    @Override
    public Path databasePath() {
        return databasePath;
    }

    public void backupTo(Path target) throws IOException {
        flush();
        if (target.toAbsolutePath().getParent() != null) {
            Files.createDirectories(target.toAbsolutePath().getParent());
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        if (Files.exists(target)) {
            throw new IOException("Backup target already exists: " + target);
        }
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + temporary.toAbsolutePath().toString().replace("'", "''") + "'");
        } catch (SQLException error) {
            Files.deleteIfExists(temporary);
            throw SQLiteSchema.toIo("backup SQLite database", error);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Connection openConnection() throws SQLException {
        return SQLiteSchema.open(databasePath, databaseSettings);
    }

    private FailedReplaySummary replayFailedEvents(Connection connection) throws IOException {
        if (!Files.exists(failedEventsPath)) {
            return FailedReplaySummary.EMPTY;
        }
        long total = 0L;
        long replayed = 0L;
        long alreadyReplayed = 0L;
        long malformed = 0L;
        long pending = 0L;
        try (BufferedReader reader = Files.newBufferedReader(failedEventsPath, StandardCharsets.UTF_8);
             WriterSql sql = new WriterSql(connection);
             PreparedStatement seen = connection.prepareStatement(
                     "SELECT 1 FROM replayed_failed_events WHERE event_id = ? LIMIT 1");
             PreparedStatement markReplayed = connection.prepareStatement(
                     "INSERT INTO replayed_failed_events(event_id, replayed_at) VALUES(?, ?)")) {
            String line;
            long lineNumber = 0L;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                total++;
                FailedEventRecord record;
                try {
                    record = gson.fromJson(line, FailedEventRecord.class);
                    if (record == null || record.operation() == null || record.operation().isBlank()
                            || record.eventJson() == null || record.eventJson().isBlank()) {
                        throw new JsonParseException("missing operation or eventJson");
                    }
                } catch (RuntimeException error) {
                    malformed++;
                    pending++;
                    warn("Malformed failed event at line " + lineNumber + ": " + error.getMessage());
                    continue;
                }

                String eventId = failedEventId(record);
                seen.setString(1, eventId);
                try (ResultSet resultSet = seen.executeQuery()) {
                    if (resultSet.next()) {
                        alreadyReplayed++;
                        continue;
                    }
                }

                DatabaseEvent event;
                try {
                    event = failedDatabaseEvent(record);
                } catch (IOException | RuntimeException error) {
                    malformed++;
                    pending++;
                    warn("Unable to decode failed event at line " + lineNumber + ": " + error.getMessage());
                    continue;
                }

                try {
                    connection.setAutoCommit(false);
                    applyEvent(sql, event);
                    markReplayed.setString(1, eventId);
                    markReplayed.setLong(2, System.currentTimeMillis());
                    markReplayed.executeUpdate();
                    connection.commit();
                    rememberCommittedPlayer(event);
                    long committedAt = System.currentTimeMillis();
                    lastCommittedAt.set(committedAt);
                    lastWriterProgressAt.set(committedAt);
                    if (event instanceof ChatEvent) {
                        lastChatCommittedAt.set(committedAt);
                    } else if (event instanceof LoginEvent || event instanceof LogoutEvent) {
                        lastSessionCommittedAt.set(committedAt);
                    } else if (event instanceof StatEvent) {
                        lastStatCommittedAt.set(committedAt);
                    }
                    replayed++;
                } catch (Exception error) {
                    SQLiteSchema.rollbackQuietly(connection);
                    pending++;
                    IOException io = error instanceof IOException existing
                            ? existing
                            : error instanceof SQLException sqlError
                            ? SQLiteSchema.toIo("replay failed SQLite event", sqlError)
                            : new IOException("replay failed SQLite event: " + error.getMessage(), error);
                    markFailure(io);
                    warn("Unable to replay failed event at line " + lineNumber
                            + ", operation=" + record.operation() + ": " + io.getMessage());
                } finally {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException error) {
                        throw SQLiteSchema.toIo("restore autocommit after failed-event replay", error);
                    }
                }
            }
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("replay failed SQLite events", error);
        }
        return new FailedReplaySummary(total, replayed, alreadyReplayed, malformed, pending);
    }

    private DatabaseEvent failedDatabaseEvent(FailedEventRecord record) throws IOException {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(record.eventJson()).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("invalid eventJson", error);
        }
        DatabaseEvent event = switch (record.operation()) {
            case "login" -> new LoginEvent(requiredString(payload, "playerName"), requiredLong(payload, "timestamp"));
            case "logout" -> new LogoutEvent(requiredString(payload, "playerName"), requiredLong(payload, "timestamp"));
            case "chat" -> new ChatEvent(requiredString(payload, "playerName"),
                    requiredString(payload, "message"), requiredLong(payload, "timestamp"));
            case "stat" -> {
                String playerName = requiredString(payload, "playerName");
                if (!payload.has("snapshot") || payload.get("snapshot").isJsonNull()) {
                    throw new IOException("stat event is missing snapshot");
                }
                StatSnapshot snapshot = gson.fromJson(payload.get("snapshot"), StatSnapshot.class);
                if (snapshot == null) {
                    throw new IOException("stat event snapshot is invalid");
                }
                yield new StatEvent(playerName, snapshot);
            }
            case "identity" -> {
                String playerName = requiredString(payload, "playerName");
                if (!payload.has("resolution") || payload.get("resolution").isJsonNull()) {
                    throw new IOException("identity event is missing resolution");
                }
                IdentityResolution resolution = gson.fromJson(payload.get("resolution"), IdentityResolution.class);
                if (resolution == null) {
                    throw new IOException("identity event resolution is invalid");
                }
                yield new IdentityEvent(playerName, resolution, requiredLong(payload, "checkedAt"));
            }
            default -> throw new IOException("unsupported failed event operation: " + record.operation());
        };
        if (!(event instanceof FlushEvent)) {
            validatePlayerName(event.playerNameForLog());
        }
        return event;
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            throw new IOException("missing field: " + name);
        }
        try {
            return object.get(name).getAsString();
        } catch (RuntimeException error) {
            throw new IOException("invalid string field: " + name, error);
        }
    }

    private static long requiredLong(JsonObject object, String name) throws IOException {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            throw new IOException("missing field: " + name);
        }
        try {
            return object.get(name).getAsLong();
        } catch (RuntimeException error) {
            throw new IOException("invalid long field: " + name, error);
        }
    }

    private static String failedEventId(FailedEventRecord record) {
        String material = record.sequence() + "\n" + record.failedAt() + "\n"
                + record.operation() + "\n" + Objects.toString(record.playerName(), "") + "\n"
                + Objects.toString(record.error(), "") + "\n" + record.eventJson();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    @Override
    public Map<String, Long> latestStatCapturedAt(Collection<String> names) throws IOException {
        Objects.requireNonNull(names, "names");
        Set<String> normalizedNames = new HashSet<>();
        for (String name : names) {
            validatePlayerName(name);
            normalizedNames.add(normalize(name));
        }
        if (normalizedNames.isEmpty()) {
            return Map.of();
        }
        flush();
        Map<String, Long> result = new HashMap<>();
        List<String> allNames = List.copyOf(normalizedNames);
        final int chunkSize = 400;
        try (Connection connection = openConnection()) {
            for (int start = 0; start < allNames.size(); start += chunkSize) {
                List<String> chunk = allNames.subList(start, Math.min(allNames.size(), start + chunkSize));
                String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
                String sql = """
                        SELECT p.normalized_name, MAX(stats.timestamp)
                        FROM stat_snapshots stats
                        JOIN players p ON p.id = stats.player_id
                        WHERE p.normalized_name IN (%s)
                        GROUP BY p.normalized_name
                        """.formatted(placeholders);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int index = 0; index < chunk.size(); index++) {
                        statement.setString(index + 1, chunk.get(index));
                    }
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            result.put(resultSet.getString(1), resultSet.getLong(2));
                        }
                    }
                }
            }
            return Map.copyOf(result);
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read latest Stat timestamps", error);
        }
    }

    private static long countRows(Connection connection, long playerId, String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE player_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private long playtimeSince(Connection connection, long playerId, long cutoffAt, long now) throws SQLException {
        long total = 0L;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT login_at, logout_at
                FROM sessions
                WHERE player_id = ? AND COALESCE(logout_at, ?) > ?
                """)) {
            statement.setLong(1, playerId);
            statement.setLong(2, now);
            statement.setLong(3, cutoffAt);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long loginAt = resultSet.getLong("login_at");
                    long logoutAt = resultSet.getLong("logout_at");
                    long end = resultSet.wasNull() ? now : Math.min(logoutAt, now);
                    long start = Math.max(loginAt, cutoffAt);
                    if (end > start) {
                        total += end - start;
                    }
                }
            }
        }
        return total;
    }

    private StatSnapshot latestStatForPlayerId(Connection connection, long playerId) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT stat_json
                FROM stat_snapshots
                WHERE player_id = ?
                ORDER BY timestamp DESC, id DESC
                LIMIT 1
                """)) {
            statement.setLong(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                try {
                    return gson.fromJson(resultSet.getString("stat_json"), StatSnapshot.class);
                } catch (JsonParseException error) {
                    throw new IOException("Unable to parse latest stat for player_id=" + playerId + ": "
                            + error.getMessage(), error);
                }
            }
        }
    }

    private static List<ChatEntry> recentChatsForPlayerId(Connection connection, long playerId, int limit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT timestamp, message
                FROM chat_messages
                WHERE player_id = ?
                ORDER BY timestamp DESC, id DESC
                LIMIT ?
                """)) {
            statement.setLong(1, playerId);
            statement.setInt(2, limit);
            List<ChatEntry> chats = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    chats.add(new ChatEntry(resultSet.getLong("timestamp"), resultSet.getString("message")));
                }
            }
            return chats;
        }
    }

    private void recordQueueSample(long now, int size) {
        queueHighWaterMark.accumulateAndGet(size, Math::max);
        synchronized (queueSamplesLock) {
            queueSamples.addLast(new QueueSample(now, size));
            long cutoff = now - TimeUnit.MINUTES.toMillis(6);
            while (!queueSamples.isEmpty() && queueSamples.peekFirst().timestamp() < cutoff) {
                queueSamples.removeFirst();
            }
        }
    }

    private int queueDelta(long windowMillis) {
        long cutoff = System.currentTimeMillis() - windowMillis;
        synchronized (queueSamplesLock) {
            QueueSample oldest = null;
            for (QueueSample sample : queueSamples) {
                if (sample.timestamp() >= cutoff) {
                    oldest = sample;
                    break;
                }
            }
            QueueSample newest = queueSamples.peekLast();
            return oldest == null || newest == null ? 0 : newest.size() - oldest.size();
        }
    }

    private void writerWatchdog() {
        if (lifecycleState != LifecycleState.RUNNING) {
            return;
        }
        long now = System.currentTimeMillis();
        int queued = queue.size();
        recordQueueSample(now, queued);
        int active = queued + inFlightCount.get();
        long lastProgress = lastWriterProgressAt.get();
        long stallThreshold = Math.max(TimeUnit.MINUTES.toMillis(1), databaseSettings.busyTimeoutMs * 3L);
        if (active > 0 && lastProgress > 0L && now - lastProgress > stallThreshold) {
            writerStalled = true;
            if (now - lastWriterStallWarningAt >= TimeUnit.MINUTES.toMillis(5)) {
                lastWriterStallWarningAt = now;
                warn("SQLite writer appears stalled; queue=" + queued
                        + ", inFlight=" + inFlightCount.get()
                        + ", lastCommit=" + lastProgress);
            }
        } else {
            writerStalled = false;
        }
        int delta5m = queueDelta(TimeUnit.MINUTES.toMillis(5));
        int capacity = queued + queue.remainingCapacity();
        if (capacity > 0 && (queued >= capacity * 4 / 5 || delta5m > capacity / 10)
                && now - lastQueueGrowthWarningAt >= TimeUnit.MINUTES.toMillis(5)) {
            lastQueueGrowthWarningAt = now;
            warn("SQLite queue backlog growing; queue=" + queued + "/" + capacity
                    + ", peak=" + queueHighWaterMark.get()
                    + ", delta5m=" + (delta5m >= 0 ? "+" : "") + delta5m);
        }
    }

    private void submit(DatabaseEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        WriteTask task;
        IOException queueFailure = null;
        synchronized (lifecycleLock) {
            task = new WriteTask(nextSequence.incrementAndGet(), event);
            IOException fatal = terminalFailure;
            if (fatal != null || lifecycleState == LifecycleState.FAILED) {
                queueFailure = new IOException("SQLite writer is in a terminal failed state", fatal);
            } else if (lifecycleState != LifecycleState.RUNNING) {
                queueFailure = new IOException("SQLite storage is not accepting events; state=" + lifecycleState);
            } else if (!queue.offer(task)) {
                queueFailure = new IOException("SQLite write queue is full; event=" + event.operation());
            }
        }
        if (queueFailure != null) {
            recordRejectedTask(task, queueFailure);
            throw queueFailure;
        }
        queueHighWaterMark.accumulateAndGet(queue.size(), Math::max);
        if (event instanceof FlushEvent) {
            await(task);
        }
    }

    private void recordRejectedTask(WriteTask task, IOException error) {
        markFailure(error);
        if (!(task.event instanceof FlushEvent)) {
            pendingFailures.add(new FailureRecord(task.sequence, task.event.operation(), error));
            if (task.event instanceof ChatEvent) {
                chatQueueRejected.incrementAndGet();
                chatDbFailed.incrementAndGet();
            }
            persistFailedEvent(task, error);
        }
        task.completed.completeExceptionally(error);
        warn("SEVERE: SQLite event was not queued; sequence=" + task.sequence
                + ", operation=" + task.event.operation()
                + ", player=" + task.event.playerNameForLog()
                + ", error=" + error.getMessage());
    }

    private void await(WriteTask task) throws IOException {
        try {
            task.completed.get(Math.max(1000L, databaseSettings.shutdownFlushTimeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for SQLite event " + task.event.operation(), error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("SQLite event failed: " + task.event.operation(), cause);
        } catch (TimeoutException error) {
            throw new IOException("Timed out waiting for SQLite event " + task.event.operation()
                    + "; pending events=" + queue.size(), error);
        }
    }

    private void writerLoop() {
        List<WriteTask> inFlight = new ArrayList<>(databaseSettings.batchSize);
        long recoveryBackoffMillis = 100L;
        long recoveryStartedAt = 0L;
        long lastRecoveryWarningAt = 0L;
        try {
            while (lifecycleState == LifecycleState.RUNNING || !queue.isEmpty() || !inFlight.isEmpty()) {
                try (Connection connection = openConnection(); WriterSql sql = new WriterSql(connection)) {
                    if (writerRecovering && inFlight.isEmpty()) {
                        finishWriterRecovery(recoveryStartedAt);
                        recoveryBackoffMillis = 100L;
                        recoveryStartedAt = 0L;
                        lastRecoveryWarningAt = 0L;
                    }
                    while (lifecycleState == LifecycleState.RUNNING || !queue.isEmpty() || !inFlight.isEmpty()) {
                        if (inFlight.isEmpty()) {
                            WriteTask first = queue.poll(databaseSettings.flushIntervalMs, TimeUnit.MILLISECONDS);
                            if (first == null) {
                                continue;
                            }
                            inFlight.add(first);
                            queue.drainTo(inFlight, databaseSettings.batchSize - 1);
                            inFlightCount.set(inFlight.size());
                        }
                        delayWriteForTesting();
                        processBatch(connection, sql, inFlight);
                        if (writerRecovering) {
                            finishWriterRecovery(recoveryStartedAt);
                        }
                        recoveryBackoffMillis = 100L;
                        recoveryStartedAt = 0L;
                        lastRecoveryWarningAt = 0L;
                        inFlight.clear();
                        inFlightCount.set(0);
                    }
                    SQLiteSchema.checkpoint(connection);
                } catch (SQLException error) {
                    IOException io = SQLiteSchema.toIo("run SQLite writer", error);
                    if (isTerminalSqliteFailure(error)) {
                        failInFlight(inFlight, io);
                        warn("SEVERE: SQLite writer entered terminal failed state: " + io.getMessage());
                        enterTerminalFailure(io);
                        break;
                    }
                    markFailure(io);
                    long now = System.currentTimeMillis();
                    if (!writerRecovering || recoveryStartedAt == 0L) {
                        writerRecovering = true;
                        recoveryStartedAt = now;
                    }
                    long recoveryElapsed = Math.max(0L, now - recoveryStartedAt);
                    if (recoveryElapsed >= writerRecoveryWindowMillis()) {
                        IOException exhausted = new IOException(
                                "SQLite writer recovery window exhausted after " + recoveryElapsed
                                        + " ms; last error=" + io.getMessage(), io);
                        failInFlight(inFlight, exhausted);
                        warn("SEVERE: SQLite writer recovery exhausted; pending batch=" + inFlight.size()
                                + ", queue=" + queue.size()
                                + ", error=" + io.getMessage());
                        enterTerminalFailure(exhausted);
                        break;
                    }
                    if (lastRecoveryWarningAt == 0L
                            || now - lastRecoveryWarningAt >= TimeUnit.SECONDS.toMillis(30)) {
                        lastRecoveryWarningAt = now;
                        warn("SQLite writer recovery attempt failed; pending batch=" + inFlight.size()
                                + ", queue=" + queue.size()
                                + ", retryInMs=" + recoveryBackoffMillis
                                + ", elapsedMs=" + recoveryElapsed
                                + ", error=" + io.getMessage());
                    }
                    sleepBeforeRecovery(recoveryBackoffMillis);
                    recoveryBackoffMillis = Math.min(MAX_WRITER_RECOVERY_BACKOFF_MILLIS,
                            recoveryBackoffMillis * 2L);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            IOException io = new IOException("SQLite writer interrupted", error);
            failInFlight(inFlight, io);
            enterTerminalFailure(io);
        } catch (RuntimeException error) {
            IOException io = new IOException("SQLite writer stopped unexpectedly", error);
            failInFlight(inFlight, io);
            warn("SEVERE: SQLite writer stopped: " + error.getMessage());
            enterTerminalFailure(io);
        } finally {
            inFlightCount.set(0);
            writerRecovering = false;
            synchronized (lifecycleLock) {
                if (Thread.currentThread() == writerThread && lifecycleState == LifecycleState.CLOSING) {
                    lifecycleState = LifecycleState.CLOSED;
                }
                lifecycleLock.notifyAll();
            }
        }
    }

    private void sleepBeforeRecovery(long backoffMillis) throws InterruptedException {
        Thread.sleep(Math.max(100L, backoffMillis));
    }

    private long writerRecoveryWindowMillis() {
        long busyBasedWindow = Math.max(1000L, databaseSettings.busyTimeoutMs) * 12L;
        return Math.max(MIN_WRITER_RECOVERY_WINDOW_MILLIS, busyBasedWindow);
    }

    private void finishWriterRecovery(long recoveryStartedAt) {
        if (!writerRecovering) {
            return;
        }
        long elapsed = recoveryStartedAt <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - recoveryStartedAt);
        writerRecovering = false;
        writerStalled = false;
        writerRecoveryCount.incrementAndGet();
        lastWriterProgressAt.set(System.currentTimeMillis());
        info("SQLite writer recovered; elapsedMs=" + elapsed
                + ", queue=" + queue.size());
    }

    private void failInFlight(List<WriteTask> inFlight, IOException error) {
        for (WriteTask task : inFlight) {
            if (!task.completed.isDone()) {
                failUnprocessedTask(task, error);
            }
        }
    }

    private void delayWriteForTesting() throws InterruptedException {
        long delay = writeDelayMillisForTesting;
        if (delay > 0L) {
            Thread.sleep(delay);
        }
    }
    private void processBatch(Connection connection, WriterSql sql, List<WriteTask> batch) throws SQLException {
        // Individual fallback may have committed part of this batch before a recoverable failure.
        batch.removeIf(task -> task.completed.isDone());
        if (batch.stream().allMatch(task -> task.event instanceof FlushEvent)) {
            batch.forEach(this::completeCommitted);
            return;
        }
        try {
            connection.setAutoCommit(false);
            for (WriteTask task : batch) {
                applyEvent(sql, task.event);
            }
            connection.commit();
            batch.forEach(this::completeCommitted);
        } catch (Exception batchError) {
            SQLiteSchema.rollbackQuietly(connection);
            if (batchError instanceof SQLException sqlError && isRecoverableSqliteFailure(sqlError)) {
                throw sqlError;
            }
            if (batch.size() == 1) {
                completeFailed(batch.get(0), batchError);
            } else {
                for (int index = 0; index < batch.size(); index++) {
                    if (!processOne(connection, sql, batch.get(index))) {
                        IOException fatal = terminalFailure == null
                                ? new IOException("SQLite writer entered a failed state") : terminalFailure;
                        for (int remaining = index + 1; remaining < batch.size(); remaining++) {
                            failUnprocessedTask(batch.get(remaining), fatal);
                        }
                        break;
                    }
                }
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException error) {
                warn("Unable to restore SQLite autocommit after batch: " + error.getMessage());
            }
        }
    }

    private boolean processOne(Connection connection, WriterSql sql, WriteTask task) throws SQLException {
        try {
            connection.setAutoCommit(false);
            applyEvent(sql, task.event);
            connection.commit();
            completeCommitted(task);
            return true;
        } catch (Exception error) {
            SQLiteSchema.rollbackQuietly(connection);
            if (error instanceof SQLException sqlError && isRecoverableSqliteFailure(sqlError)) {
                throw sqlError;
            }
            completeFailed(task, error);
            return terminalFailure == null;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException error) {
                warn("Unable to restore SQLite autocommit after single event: " + error.getMessage());
            }
        }
    }

    private void applyEvent(WriterSql sql, DatabaseEvent event) throws SQLException, IOException {
        if (event instanceof FlushEvent) {
            return;
        }
        if (event instanceof LoginEvent login) {
            writeLogin(sql, login.playerName(), login.timestamp());
        } else if (event instanceof LogoutEvent logout) {
            writeLogout(sql, logout.playerName(), logout.timestamp());
        } else if (event instanceof ChatEvent chat) {
            writeChat(sql, chat.playerName(), chat.message(), chat.timestamp());
        } else if (event instanceof StatEvent stat) {
            String normalized = normalize(stat.playerName());
            if (statWriteFailure.test(normalized)) {
                throw new IOException("simulated stat write failure for " + normalized);
            }
            writeStat(sql, stat.playerName(), stat.snapshot());
        } else if (event instanceof IdentityEvent identity) {
            writeIdentity(sql, identity.playerName(), identity.resolution(), identity.checkedAt());
        }
    }

    private void completeCommitted(WriteTask task) {
        rememberCommittedPlayer(task.event);
        if (!(task.event instanceof FlushEvent)) {
            long now = System.currentTimeMillis();
            lastCommittedAt.set(now);
            lastWriterProgressAt.set(now);
            if (task.event instanceof ChatEvent) {
                lastChatCommittedAt.set(now);
                chatCommitted.incrementAndGet();
            } else if (task.event instanceof LoginEvent || task.event instanceof LogoutEvent) {
                lastSessionCommittedAt.set(now);
            } else if (task.event instanceof StatEvent) {
                lastStatCommittedAt.set(now);
            }
        } else {
            lastWriterProgressAt.set(System.currentTimeMillis());
        }
        if (task.event instanceof FlushEvent) {
            FailureRecord first = null;
            int failureCount = 0;
            while (true) {
                FailureRecord candidate = pendingFailures.peek();
                if (candidate == null || candidate.sequence() >= task.sequence) {
                    break;
                }
                candidate = pendingFailures.poll();
                if (candidate != null) {
                    if (first == null) {
                        first = candidate;
                    }
                    failureCount++;
                }
            }
            if (first != null) {
                task.completed.completeExceptionally(new IOException(
                        failureCount + " SQLite events before this flush failed; first failed sequence="
                                + first.sequence() + ", operation=" + first.operation(), first.error()));
                return;
            }
        }
        task.completed.complete(null);
    }


    private void rememberCommittedPlayer(DatabaseEvent event) {
        if (event instanceof LoginEvent login) {
            rememberPlayerName(login.playerName());
        } else if (event instanceof ChatEvent chat) {
            rememberPlayerName(chat.playerName());
        } else if (event instanceof StatEvent stat) {
            rememberPlayerName(stat.playerName());
        } else if (event instanceof IdentityEvent identity) {
            rememberPlayerName(identity.playerName());
        }
    }

    private void completeFailed(WriteTask task, Exception error) {
        IOException io;
        if (error instanceof IOException existing) {
            io = existing;
        } else if (error instanceof SQLException sqlError) {
            io = SQLiteSchema.toIo("write SQLite event " + task.event.operation(), sqlError);
        } else {
            io = new IOException("write SQLite event " + task.event.operation() + " failed: " + error.getMessage(), error);
        }
        markFailure(io);
        pendingFailures.add(new FailureRecord(task.sequence, task.event.operation(), io));
        if (task.event instanceof ChatEvent) {
            chatDbFailed.incrementAndGet();
        }
        persistFailedEvent(task, io);
        warn("SQLite event failed; sequence=" + task.sequence
                + ", operation=" + task.event.operation()
                + ", player=" + task.event.playerNameForLog()
                + ", error=" + io.getMessage()
                + ", rolledBack=true, retriedIndividually=true");
        task.completed.completeExceptionally(io);

        if (error instanceof SQLException sqlError && isTerminalSqliteFailure(sqlError)) {
            warn("SEVERE: SQLite writer entered terminal failed state; pending events will be rejected: "
                    + io.getMessage());
            enterTerminalFailure(io);
        }
    }

    private boolean isTerminalSqliteFailure(SQLException error) {
        int primaryCode = error.getErrorCode() & 0xFF;
        return primaryCode == 11  // SQLITE_CORRUPT
                || primaryCode == 13  // SQLITE_FULL
                || primaryCode == 26; // SQLITE_NOTADB
    }

    private boolean isRecoverableSqliteFailure(SQLException error) {
        if (isTerminalSqliteFailure(error)) {
            return false;
        }
        int primaryCode = error.getErrorCode() & 0xFF;
        return primaryCode == 5   // SQLITE_BUSY
                || primaryCode == 6   // SQLITE_LOCKED
                || primaryCode == 10  // SQLITE_IOERR
                || primaryCode == 14  // SQLITE_CANTOPEN
                || primaryCode == 15  // SQLITE_PROTOCOL
                || primaryCode == 17; // SQLITE_SCHEMA
    }

    private void persistFailedEvent(WriteTask task, IOException error) {
        if (task.event instanceof FlushEvent) {
            return;
        }
        FailedEventRecord record = new FailedEventRecord(
                task.sequence, System.currentTimeMillis(), task.event.operation(),
                task.event.playerNameForLog(), error.getMessage(), gson.toJson(task.event));
        synchronized (failedEventsFileLock) {
            try {
                Files.createDirectories(directory);
                byte[] line = (gson.toJson(record) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(failedEventsPath,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    ByteBuffer buffer = ByteBuffer.wrap(line);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                failedEventLines.incrementAndGet();
            } catch (IOException persistError) {
                failedEventPersistFailures.incrementAndGet();
                warn("SEVERE: Unable to persist failed SQLite event sequence=" + task.sequence
                        + " to " + failedEventsPath + ": " + persistError.getMessage());
            }
        }
    }

    private void markFailure(IOException error) {
        lastFailureAt.set(System.currentTimeMillis());
        lastFailureMessage = error == null || error.getMessage() == null ? "unknown failure" : error.getMessage();
    }

    private void enterTerminalFailure(IOException error) {
        terminalFailure = error;
        synchronized (lifecycleLock) {
            lifecycleState = LifecycleState.FAILED;
        }
        failAllPending(error);
    }

    private void failAllPending(IOException error) {
        WriteTask task;
        while ((task = queue.poll()) != null) {
            failUnprocessedTask(task, error);
        }
    }

    private void failUnprocessedTask(WriteTask task, IOException error) {
        markFailure(error);
        if (!(task.event instanceof FlushEvent)) {
            pendingFailures.add(new FailureRecord(task.sequence, task.event.operation(), error));
            if (task.event instanceof ChatEvent) {
                chatDbFailed.incrementAndGet();
            }
            persistFailedEvent(task, error);
        }
        task.completed.completeExceptionally(error);
    }

    private void writeLogin(WriterSql sql, String playerName, long now) throws SQLException {
        long playerId = ensurePlayer(sql, playerName, now);
        OpenSession open = openSession(sql, playerId);
        if (open != null) {
            sql.closeSession.setLong(1, Math.max(open.loginAt(), now));
            sql.closeSession.setLong(2, open.id());
            sql.closeSession.executeUpdate();
        }
        long sessionId = insertSession(sql, playerId, now, null, now);
        sql.updatePlayerCurrent.setLong(1, sessionId);
        sql.updatePlayerCurrent.setLong(2, now);
        sql.updatePlayerCurrent.setLong(3, now);
        sql.updatePlayerCurrent.setLong(4, now);
        sql.updatePlayerCurrent.setLong(5, playerId);
        sql.updatePlayerCurrent.executeUpdate();
    }

    private void writeLogout(WriterSql sql, String playerName, long now) throws SQLException {
        PlayerRow player = findPlayer(sql, normalize(playerName));
        if (player == null) {
            return;
        }
        OpenSession open = openSession(sql, player.id());
        if (open == null) {
            return;
        }
        long logoutAt = Math.max(open.loginAt(), now);
        sql.closeSession.setLong(1, logoutAt);
        sql.closeSession.setLong(2, open.id());
        sql.closeSession.executeUpdate();
        sql.updatePlayerOffline.setLong(1, logoutAt);
        sql.updatePlayerOffline.setLong(2, now);
        sql.updatePlayerOffline.setLong(3, player.id());
        sql.updatePlayerOffline.executeUpdate();
    }

    private void writeChat(WriterSql sql, String playerName, String message, long now) throws SQLException {
        long playerId = ensurePlayer(sql, playerName, now);
        sql.insertChat.setLong(1, playerId);
        sql.insertChat.setLong(2, now);
        sql.insertChat.setString(3, message);
        sql.insertChat.executeUpdate();
        sql.updatePlayerLastSeen.setLong(1, now);
        sql.updatePlayerLastSeen.setLong(2, now);
        sql.updatePlayerLastSeen.setLong(3, playerId);
        sql.updatePlayerLastSeen.executeUpdate();
    }

    private void writeStat(WriterSql sql, String playerName, StatSnapshot snapshot) throws SQLException {
        long playerId = ensurePlayer(sql, playerName, snapshot.capturedAt);
        sql.insertStat.setLong(1, playerId);
        sql.insertStat.setLong(2, snapshot.capturedAt);
        sql.insertStat.setString(3, gson.toJson(snapshot));
        sql.insertStat.executeUpdate();
        sql.updatePlayerStat.setLong(1, snapshot.capturedAt);
        sql.updatePlayerStat.setLong(2, snapshot.capturedAt);
        sql.updatePlayerStat.setLong(3, snapshot.capturedAt);
        sql.updatePlayerStat.setLong(4, snapshot.capturedAt);
        sql.updatePlayerStat.setLong(5, playerId);
        sql.updatePlayerStat.executeUpdate();
    }

    private void writeIdentity(WriterSql sql, String playerName, IdentityResolution resolution, long checkedAt)
            throws SQLException {
        long playerId = ensurePlayer(sql, playerName, checkedAt);
        StoredPlayerIdentity previous = readIdentity(sql.connection, playerId);
        String mojangUuid = resolvedUuid(previous.mojangUuid(), resolution.mojang());
        String thirdPartyUuid = resolvedUuid(previous.thirdPartyUuid(), resolution.thirdParty());
        PlayerIdentityType identityType = resolution.identityType() == null
                ? previous.identityType() : resolution.identityType();

        List<String> assignments = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        boolean serverUuidChanged = addChanged(assignments, values, "server_uuid",
                previous.serverUuid(), resolution.serverUuid());
        boolean identityChanged = serverUuidChanged;
        identityChanged |= addChanged(assignments, values, "offline_uuid",
                previous.offlineUuid(), resolution.offlineUuid());
        identityChanged |= addChanged(assignments, values, "mojang_uuid", previous.mojangUuid(), mojangUuid);
        identityChanged |= addChanged(assignments, values, "third_party_uuid",
                previous.thirdPartyUuid(), thirdPartyUuid);
        identityChanged |= addChanged(assignments, values, "identity_type",
                previous.identityType() == null ? null : previous.identityType().name(),
                identityType == null ? null : identityType.name());
        if (resolution.mojang().completed() && !resolution.mojang().cached()) {
            assignments.add("mojang_checked_at = ?");
            values.add(checkedAt);
        }
        if (resolution.thirdParty().completed() && !resolution.thirdParty().cached()) {
            assignments.add("third_party_checked_at = ?");
            values.add(checkedAt);
        }
        if (resolution.successful()) {
            assignments.add("uuid_last_checked_at = ?");
            values.add(checkedAt);
        }
        if (serverUuidChanged) {
            assignments.add("uuid_first_recorded_at = ?");
            values.add(resolution.serverUuid() == null || resolution.serverUuid().isBlank() ? null : checkedAt);
        }
        if (identityChanged) {
            assignments.add("uuid_last_written_at = ?");
            values.add(checkedAt);
        }
        if (assignments.isEmpty()) {
            return;
        }
        assignments.add("updated_at = ?");
        values.add(checkedAt);
        try (PreparedStatement statement = sql.connection.prepareStatement(
                "UPDATE players SET " + String.join(", ", assignments) + " WHERE id = ?")) {
            int index = 1;
            for (Object value : values) {
                statement.setObject(index++, value);
            }
            statement.setLong(index, playerId);
            statement.executeUpdate();
        }
    }

    private static String resolvedUuid(String previous, IdentityResolution.Lookup lookup) {
        return switch (lookup.status()) {
            case FOUND -> lookup.uuid();
            case NOT_FOUND -> null;
            case ERROR, NOT_CHECKED -> previous;
        };
    }

    private static boolean addChanged(List<String> assignments, List<Object> values, String column,
                                      Object previous, Object current) {
        if (Objects.equals(previous, current)) {
            return false;
        }
        assignments.add(column + " = ?");
        values.add(current);
        return true;
    }

    private long ensurePlayer(WriterSql sql, String playerName, long timestamp) throws SQLException {
        PlayerRow existing = findPlayer(sql, normalize(playerName));
        if (existing != null) {
            return existing.id();
        }
        sql.insertPlayer.setString(1, normalize(playerName));
        sql.insertPlayer.setString(2, playerName);
        sql.insertPlayer.setLong(3, timestamp);
        sql.insertPlayer.setLong(4, timestamp);
        sql.insertPlayer.setLong(5, timestamp);
        sql.insertPlayer.setLong(6, timestamp);
        sql.insertPlayer.executeUpdate();
        try (ResultSet keys = sql.insertPlayer.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("No generated player id for " + playerName);
            }
            return keys.getLong(1);
        }
    }

    private PlayerRow findPlayer(WriterSql sql, String normalizedName) throws SQLException {
        sql.selectPlayer.setString(1, normalizedName);
        try (ResultSet resultSet = sql.selectPlayer.executeQuery()) {
            return resultSet.next()
                    ? new PlayerRow(resultSet.getLong("id"), resultSet.getString("display_name"))
                    : null;
        }
    }

    private OpenSession openSession(WriterSql sql, long playerId) throws SQLException {
        sql.selectOpenSession.setLong(1, playerId);
        try (ResultSet resultSet = sql.selectOpenSession.executeQuery()) {
            return resultSet.next() ? new OpenSession(resultSet.getLong("id"), resultSet.getLong("login_at")) : null;
        }
    }

    private long insertSession(WriterSql sql, long playerId, long loginAt, Long logoutAt, long createdAt)
            throws SQLException {
        sql.insertSession.setLong(1, playerId);
        sql.insertSession.setLong(2, loginAt);
        if (logoutAt == null) {
            sql.insertSession.setNull(3, Types.INTEGER);
        } else {
            sql.insertSession.setLong(3, logoutAt);
        }
        sql.insertSession.setLong(4, createdAt);
        sql.insertSession.executeUpdate();
        try (ResultSet keys = sql.insertSession.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("No generated session id for player_id=" + playerId);
            }
            return keys.getLong(1);
        }
    }

    private void loadPlayerNameIndex(Connection connection) throws SQLException {
        synchronized (playerNamesLock) {
            playerNames.clear();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT display_name FROM players ORDER BY display_name COLLATE NOCASE")) {
                while (resultSet.next()) {
                    playerNames.add(resultSet.getString(1));
                }
            }
        }
    }

    private Long findPlayerId(Connection connection, String normalizedName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM players WHERE normalized_name = ?")) {
            statement.setString(1, normalizedName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private PlayerRecord readRecord(Connection connection, long playerId) throws SQLException {
        PlayerRecord record;
        try (PreparedStatement statement = connection.prepareStatement("SELECT display_name, first_seen_at FROM players WHERE id = ?")) {
            statement.setLong(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Player id disappeared while reading: " + playerId);
                }
                record = new PlayerRecord(resultSet.getString("display_name"), resultSet.getLong("first_seen_at"));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT login_at, logout_at FROM sessions
                WHERE player_id = ? ORDER BY login_at ASC, id ASC
                """)) {
            statement.setLong(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    LoginSession session = new LoginSession(resultSet.getLong("login_at"));
                    long logoutAt = resultSet.getLong("logout_at");
                    session.logoutAt = resultSet.wasNull() ? null : logoutAt;
                    record.loginSessions.add(session);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT timestamp, message FROM chat_messages
                WHERE player_id = ? ORDER BY timestamp ASC, id ASC
                """)) {
            statement.setLong(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    record.chatMessages.add(new ChatEntry(resultSet.getLong("timestamp"), resultSet.getString("message")));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT stat_json FROM stat_snapshots
                WHERE player_id = ? ORDER BY timestamp DESC, id DESC LIMIT 1
                """)) {
            statement.setLong(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    try {
                        StatSnapshot snapshot = gson.fromJson(resultSet.getString("stat_json"), StatSnapshot.class);
                        if (snapshot != null) {
                            record.statSnapshots.add(snapshot);
                        }
                    } catch (JsonParseException error) {
                        warn("Unable to parse stat snapshot for player_id=" + playerId + ": " + error.getMessage());
                    }
                }
            }
        }
        return record;
    }

    private StoredPlayerIdentity readIdentity(Connection connection, long playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT display_name, server_uuid, offline_uuid, mojang_uuid, third_party_uuid,
                       identity_type, mojang_checked_at, third_party_checked_at,
                       uuid_first_recorded_at, uuid_last_checked_at, uuid_last_written_at
                FROM players WHERE id = ?
                """)) {
            statement.setLong(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Player id disappeared while reading identity: " + playerId);
                }
                String type = resultSet.getString("identity_type");
                PlayerIdentityType identityType;
                try {
                    identityType = type == null ? null : PlayerIdentityType.valueOf(type);
                } catch (IllegalArgumentException ignored) {
                    identityType = PlayerIdentityType.UNKNOWN;
                }
                return new StoredPlayerIdentity(
                        resultSet.getString("display_name"),
                        resultSet.getString("server_uuid"),
                        resultSet.getString("offline_uuid"),
                        resultSet.getString("mojang_uuid"),
                        resultSet.getString("third_party_uuid"),
                        identityType,
                        nullableLong(resultSet, "mojang_checked_at"),
                        nullableLong(resultSet, "third_party_checked_at"),
                        nullableLong(resultSet, "uuid_first_recorded_at"),
                        nullableLong(resultSet, "uuid_last_checked_at"),
                        nullableLong(resultSet, "uuid_last_written_at"));
            }
        }
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private String legacyMigrationStatus(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM migration_state WHERE migration_key = 'legacy-json-v1'")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : "NOT_STARTED";
            }
        }
    }

    private void rememberPlayerName(String playerName) {
        synchronized (playerNamesLock) {
            playerNames.add(playerName);
        }
    }

    private static String normalize(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }

    private static void validatePlayerName(String playerName) throws IOException {
        Objects.requireNonNull(playerName, "playerName");
        if (playerName.isBlank() || playerName.indexOf('/') >= 0 || playerName.indexOf('\\') >= 0
                || playerName.matches(".*[:*?\"<>|].*") || ".".equals(playerName) || "..".equals(playerName)) {
            throw new IOException("Unsafe player name for storage: " + playerName);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException ignored) {
            return -1L;
        }
    }

    private void info(String message) {
        infoSink.accept(message);
    }
    private void warn(String message) {
        warningSink.accept(message);
    }

    private enum LifecycleState {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED,
        FAILED
    }

    private interface DatabaseEvent {
        String operation();
        default String playerNameForLog() {
            return "-";
        }
    }

    private record LoginEvent(String playerName, long timestamp) implements DatabaseEvent {
        public String operation() { return "login"; }
        public String playerNameForLog() { return playerName; }
    }

    private record LogoutEvent(String playerName, long timestamp) implements DatabaseEvent {
        public String operation() { return "logout"; }
        public String playerNameForLog() { return playerName; }
    }

    private record ChatEvent(String playerName, String message, long timestamp) implements DatabaseEvent {
        public String operation() { return "chat"; }
        public String playerNameForLog() { return playerName; }
    }

    private record StatEvent(String playerName, StatSnapshot snapshot) implements DatabaseEvent {
        public String operation() { return "stat"; }
        public String playerNameForLog() { return playerName; }
    }

    private record IdentityEvent(String playerName, IdentityResolution resolution, long checkedAt)
            implements DatabaseEvent {
        public String operation() { return "identity"; }
        public String playerNameForLog() { return playerName; }
    }

    private record FlushEvent() implements DatabaseEvent {
        public String operation() { return "flush"; }
    }

    private static final class WriteTask {
        private final long sequence;
        private final DatabaseEvent event;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();

        private WriteTask(long sequence, DatabaseEvent event) {
            this.sequence = sequence;
            this.event = event;
        }
    }

    private record FailureRecord(long sequence, String operation, IOException error) {
    }

    private record FailedEventRecord(long sequence, long failedAt, String operation, String playerName,
                                     String error, String eventJson) {
    }

    private record FailedReplaySummary(long totalLines, long replayed, long alreadyReplayed,
                                       long malformed, long pending) {
        private static final FailedReplaySummary EMPTY = new FailedReplaySummary(0L, 0L, 0L, 0L, 0L);
    }

    private record QueueSample(long timestamp, int size) {
    }

    private record PlayerRow(long id, String displayName) {
    }

    private record OpenSession(long id, long loginAt) {
    }

    private static final class WriterSql implements AutoCloseable {
        private final Connection connection;
        private final PreparedStatement selectPlayer;
        private final PreparedStatement insertPlayer;
        private final PreparedStatement updatePlayerLastSeen;
        private final PreparedStatement selectOpenSession;
        private final PreparedStatement closeSession;
        private final PreparedStatement insertSession;
        private final PreparedStatement updatePlayerCurrent;
        private final PreparedStatement updatePlayerOffline;
        private final PreparedStatement insertChat;
        private final PreparedStatement insertStat;
        private final PreparedStatement updatePlayerStat;

        private WriterSql(Connection connection) throws SQLException {
            this.connection = connection;
            selectPlayer = connection.prepareStatement("SELECT id, display_name FROM players WHERE normalized_name = ?");
            insertPlayer = connection.prepareStatement("""
                    INSERT INTO players(normalized_name, display_name, first_seen_at, last_seen_at,
                                        online, current_session_id, last_stat_at, created_at, updated_at)
                    VALUES(?, ?, ?, ?, 0, NULL, NULL, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            updatePlayerLastSeen = connection.prepareStatement("""
                    UPDATE players SET last_seen_at = MAX(last_seen_at, ?), updated_at = ? WHERE id = ?
                    """);
            selectOpenSession = connection.prepareStatement("""
                    SELECT id, login_at FROM sessions WHERE player_id = ? AND logout_at IS NULL LIMIT 1
                    """);
            closeSession = connection.prepareStatement("UPDATE sessions SET logout_at = ? WHERE id = ?");
            insertSession = connection.prepareStatement("""
                    INSERT INTO sessions(player_id, login_at, logout_at, created_at) VALUES(?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            updatePlayerCurrent = connection.prepareStatement("""
                    UPDATE players
                    SET online = 1, current_session_id = ?, first_seen_at = MIN(first_seen_at, ?),
                        last_seen_at = MAX(last_seen_at, ?), updated_at = ?
                    WHERE id = ?
                    """);
            updatePlayerOffline = connection.prepareStatement("""
                    UPDATE players SET online = 0, current_session_id = NULL,
                        last_seen_at = MAX(last_seen_at, ?), updated_at = ? WHERE id = ?
                    """);
            insertChat = connection.prepareStatement("INSERT INTO chat_messages(player_id, timestamp, message) VALUES(?, ?, ?)");
            insertStat = connection.prepareStatement("""
                    INSERT INTO stat_snapshots(player_id, timestamp, stat_json) VALUES(?, ?, ?)
                    ON CONFLICT(player_id) DO UPDATE SET
                        timestamp = excluded.timestamp,
                        stat_json = excluded.stat_json
                    WHERE excluded.timestamp >= stat_snapshots.timestamp
                    """);
            updatePlayerStat = connection.prepareStatement("""
                    UPDATE players
                    SET last_seen_at = MAX(last_seen_at, ?),
                        last_stat_at = MAX(COALESCE(last_stat_at, ?), ?),
                        updated_at = ?
                    WHERE id = ?
                    """);
        }

        public void close() throws SQLException {
            selectPlayer.close();
            insertPlayer.close();
            updatePlayerLastSeen.close();
            selectOpenSession.close();
            closeSession.close();
            insertSession.close();
            updatePlayerCurrent.close();
            updatePlayerOffline.close();
            insertChat.close();
            insertStat.close();
            updatePlayerStat.close();
        }
    }
}
