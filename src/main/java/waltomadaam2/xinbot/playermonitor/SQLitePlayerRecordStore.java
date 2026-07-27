package waltomadaam2.xinbot.playermonitor;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.io.IOException;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class SQLitePlayerRecordStore implements PlayerRepository {
    private final Path directory;
    private final Path databasePath;
    private final Path failedEventsPath;
    private final MonitorSettings.Database databaseSettings;
    private final Gson gson = new Gson();
    private final BlockingQueue<WriteTask> queue;
    private final TreeSet<String> playerNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Object playerNamesLock = new Object();
    private final AtomicLong nextSequence = new AtomicLong();
    private final ConcurrentLinkedQueue<FailureRecord> pendingFailures = new ConcurrentLinkedQueue<>();
    private volatile Consumer<String> warningSink = ignored -> {
    };
    private volatile Consumer<String> infoSink = ignored -> {
    };
    private volatile Predicate<String> statWriteFailure = ignored -> false;
    private volatile long writeDelayMillisForTesting;
    private volatile boolean accepting;
    private volatile boolean initialized;
    private volatile IOException terminalFailure;
    private Thread writerThread;

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

    @Override
    public synchronized void initialize() throws IOException {
        if (initialized) {
            return;
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
            loadPlayerNameIndex(connection);
            info("XinPM SQLite database: " + databasePath.toAbsolutePath());
            info("Schema version: " + SQLiteSchema.schemaVersion(connection));
            info("Journal mode: " + SQLiteSchema.value(connection, "PRAGMA journal_mode"));
            info("Legacy migration status: " + legacyMigrationStatus(connection));
            info("Database writer batch size: " + databaseSettings.batchSize);
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("initialize SQLite storage", error);
        }
        accepting = true;
        writerThread = new Thread(this::writerLoop, "XinPlayerMonitor-sqlite-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        initialized = true;
    }

    @Override
    public void close() {
        accepting = false;
        Thread thread = writerThread;
        if (thread != null) {
            try {
                thread.join(databaseSettings.shutdownFlushTimeoutMs);
                if (thread.isAlive()) {
                    warn("SQLite writer did not stop within " + databaseSettings.shutdownFlushTimeoutMs
                            + " ms; pending events=" + queue.size());
                    thread.interrupt();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                warn("Interrupted while waiting for SQLite writer shutdown; pending events=" + queue.size());
            }
        }
        writerThread = null;
        initialized = false;
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
        synchronized (playerNamesLock) {
            return List.copyOf(playerNames);
        }
    }

    @Override
    public DatabaseStats databaseStats() throws IOException {
        flush();
        try (Connection connection = openConnection()) {
            return new DatabaseStats(
                    countRows(connection, "players"),
                    countRows(connection, "chat_messages"),
                    countRows(connection, "sessions"),
                    countRows(connection, "stat_snapshots"),
                    countOpenSessions(connection));
        } catch (SQLException error) {
            throw SQLiteSchema.toIo("read database stats", error);
        }
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

    private static int countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static int countOpenSessions(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM sessions WHERE logout_at IS NULL")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
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
    public void flush() throws IOException {
        if (!initialized) {
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

    private void submit(DatabaseEvent event) throws IOException {
        IOException fatal = terminalFailure;
        if (fatal != null) {
            throw new IOException("SQLite writer is in a terminal failed state", fatal);
        }
        if (!accepting && !(event instanceof FlushEvent)) {
            throw new IOException("SQLite storage is not accepting new events");
        }
        if (!initialized && !(event instanceof FlushEvent)) {
            throw new IOException("SQLite storage has not been initialized");
        }
        WriteTask task = new WriteTask(nextSequence.incrementAndGet(), event);
        try {
            if (!queue.offer(task, Math.max(1000L, databaseSettings.flushIntervalMs), TimeUnit.MILLISECONDS)) {
                IOException error = new IOException("SQLite write queue is full; event=" + event.operation());
                warn("SEVERE: " + error.getMessage());
                throw error;
            }
            if (event instanceof FlushEvent) {
                await(task);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while queueing SQLite event " + event.operation(), error);
        }
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
        try (Connection connection = openConnection(); WriterSql sql = new WriterSql(connection)) {
            while (accepting || !queue.isEmpty()) {
                WriteTask first = queue.poll(databaseSettings.flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<WriteTask> batch = new ArrayList<>(databaseSettings.batchSize);
                batch.add(first);
                delayWriteForTesting();
                queue.drainTo(batch, databaseSettings.batchSize - 1);
                processBatch(connection, sql, batch);
            }
            SQLiteSchema.checkpoint(connection);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            IOException io = new IOException("SQLite writer interrupted", error);
            terminalFailure = io;
            accepting = false;
            failAllPending(io);
        } catch (SQLException error) {
            IOException io = SQLiteSchema.toIo("run SQLite writer", error);
            terminalFailure = io;
            accepting = false;
            warn("SEVERE: SQLite writer stopped: " + io.getMessage());
            failAllPending(io);
        } catch (RuntimeException error) {
            IOException io = new IOException("SQLite writer stopped unexpectedly", error);
            terminalFailure = io;
            accepting = false;
            warn("SEVERE: SQLite writer stopped: " + error.getMessage());
            failAllPending(io);
        }
    }
    private void delayWriteForTesting() throws InterruptedException {
        long delay = writeDelayMillisForTesting;
        if (delay > 0L) {
            Thread.sleep(delay);
        }
    }
    private void processBatch(Connection connection, WriterSql sql, List<WriteTask> batch) {
        try {
            connection.setAutoCommit(false);
            for (WriteTask task : batch) {
                applyEvent(sql, task.event);
            }
            connection.commit();
            batch.forEach(this::completeCommitted);
        } catch (Exception batchError) {
            SQLiteSchema.rollbackQuietly(connection);
            if (batch.size() == 1) {
                completeFailed(batch.get(0), batchError);
            } else {
                for (int index = 0; index < batch.size(); index++) {
                    if (!processOne(connection, sql, batch.get(index))) {
                        IOException fatal = terminalFailure == null
                                ? new IOException("SQLite writer entered a failed state") : terminalFailure;
                        for (int remaining = index + 1; remaining < batch.size(); remaining++) {
                            batch.get(remaining).completed.completeExceptionally(fatal);
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

    private boolean processOne(Connection connection, WriterSql sql, WriteTask task) {
        try {
            connection.setAutoCommit(false);
            applyEvent(sql, task.event);
            connection.commit();
            completeCommitted(task);
            return true;
        } catch (Exception error) {
            SQLiteSchema.rollbackQuietly(connection);
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
        }
    }

    private void completeCommitted(WriteTask task) {
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

    private void completeFailed(WriteTask task, Exception error) {
        IOException io;
        if (error instanceof IOException existing) {
            io = existing;
        } else if (error instanceof SQLException sqlError) {
            io = SQLiteSchema.toIo("write SQLite event " + task.event.operation(), sqlError);
        } else {
            io = new IOException("write SQLite event " + task.event.operation() + " failed: " + error.getMessage(), error);
        }
        pendingFailures.add(new FailureRecord(task.sequence, task.event.operation(), io));
        persistFailedEvent(task, io);
        warn("SQLite event failed; sequence=" + task.sequence
                + ", operation=" + task.event.operation()
                + ", player=" + task.event.playerNameForLog()
                + ", error=" + io.getMessage()
                + ", rolledBack=true, retriedIndividually=true");
        task.completed.completeExceptionally(io);

        if (error instanceof SQLException sqlError && isTerminalSqliteFailure(sqlError)) {
            terminalFailure = io;
            accepting = false;
            warn("SEVERE: SQLite writer entered terminal failed state; pending events will be rejected: "
                    + io.getMessage());
            failAllPending(io);
        }
    }

    private boolean isTerminalSqliteFailure(SQLException error) {
        int primaryCode = error.getErrorCode() & 0xFF;
        return primaryCode == 7   // SQLITE_NOMEM
                || primaryCode == 10  // SQLITE_IOERR
                || primaryCode == 11  // SQLITE_CORRUPT
                || primaryCode == 13  // SQLITE_FULL
                || primaryCode == 14  // SQLITE_CANTOPEN
                || primaryCode == 21  // SQLITE_MISUSE
                || primaryCode == 26; // SQLITE_NOTADB
    }

    private void persistFailedEvent(WriteTask task, IOException error) {
        FailedEventRecord record = new FailedEventRecord(
                task.sequence, System.currentTimeMillis(), task.event.operation(),
                task.event.playerNameForLog(), error.getMessage(), gson.toJson(task.event));
        try {
            Files.createDirectories(directory);
            Files.writeString(failedEventsPath, gson.toJson(record) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException persistError) {
            warn("SEVERE: Unable to persist failed SQLite event sequence=" + task.sequence
                    + " to " + failedEventsPath + ": " + persistError.getMessage());
        }
    }

    private void failAllPending(IOException error) {
        WriteTask task;
        while ((task = queue.poll()) != null) {
            task.completed.completeExceptionally(error);
        }
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
        rememberPlayerName(playerName);
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
        rememberPlayerName(playerName);
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
        rememberPlayerName(playerName);
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

    private void info(String message) {
        infoSink.accept(message);
    }
    private void warn(String message) {
        warningSink.accept(message);
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

    private record PlayerRow(long id, String displayName) {
    }

    private record OpenSession(long id, long loginAt) {
    }

    private static final class WriterSql implements AutoCloseable {
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
