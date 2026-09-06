package waltomadaam2.xinbot.playermonitor;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerProfile;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLitePlayerRecordStoreTest {
    private final List<PlayerMonitorService> services = new ArrayList<>();
    private final Gson gson = new Gson();

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void tearDown() {
        services.forEach(PlayerMonitorService::close);
    }

    @Test
    void createsSchemaWithWalAndForeignKeys() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        PlayerMonitorService service = service(directory);
        service.initialize();
        service.close();

        try (Connection connection = openRaw(directory.resolve("xinpm.db")); Statement statement = connection.createStatement()) {
            assertEquals("wal", pragma(statement, "PRAGMA journal_mode").toLowerCase(java.util.Locale.ROOT));
            statement.execute("PRAGMA foreign_keys = ON");
            assertEquals("1", pragma(statement, "PRAGMA foreign_keys"));
            try (ResultSet resultSet = statement.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
                assertTrue(resultSet.next());
                assertEquals(4, resultSet.getInt(1));
            }
        }
    }


    @Test
    void keepsDataWhenReopeningSchemaV3Database() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-v3-reopen");
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        store.initialize();
        store.recordChat("Steve", "keep me", 100L);
        store.close();

        SQLitePlayerRecordStore reopened = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        reopened.initialize();
        try {
            assertEquals(1, reopened.chatCount("Steve"));
            try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
                assertEquals(4, Integer.parseInt(scalar(connection, "SELECT MAX(version) FROM schema_migrations")));
                assertEquals(1, countRows(connection, "chat_messages"));
                assertEquals(0, countRows(connection, "replayed_failed_events"));
            }
        } finally {
            reopened.close();
        }
    }
    @Test
    void rejectsUnsupportedFutureSchemaVersion() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        PlayerMonitorService service = service(directory);
        service.initialize();
        service.close();
        try (Connection connection = openRaw(directory.resolve("xinpm.db")); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM schema_migrations");
            statement.executeUpdate("INSERT INTO schema_migrations(version, description, applied_at) VALUES(999, 'future', 1)");
        }

        PlayerMonitorService future = service(directory);
        assertThrows(IOException.class, future::initialize);
    }

    @Test
    void upgradesSchemaV1AndCompactsStatsToLatestSnapshot() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-v1-upgrade");
        PlayerMonitorService initial = service(directory);
        initial.initialize();
        initial.close();

        StatSnapshot older = new StatSnapshot();
        older.capturedAt = 100L;
        older.deathCount = 1;
        StatSnapshot newer = new StatSnapshot();
        newer.capturedAt = 200L;
        newer.deathCount = 9;
        try (Connection connection = openRaw(directory.resolve("xinpm.db")); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX idx_stats_one_per_player");
            statement.executeUpdate("DELETE FROM schema_migrations WHERE version >= 2");
            statement.executeUpdate("INSERT INTO players(normalized_name, display_name, first_seen_at, last_seen_at, "
                    + "online, current_session_id, last_stat_at, created_at, updated_at) "
                    + "VALUES('statuser', 'StatUser', 100, 200, 0, NULL, 200, 100, 200)");
            long playerId;
            try (ResultSet resultSet = statement.executeQuery("SELECT id FROM players WHERE normalized_name='statuser'")) {
                assertTrue(resultSet.next());
                playerId = resultSet.getLong(1);
            }
            try (var prepared = connection.prepareStatement(
                    "INSERT INTO stat_snapshots(player_id, timestamp, stat_json) VALUES(?, ?, ?)")) {
                prepared.setLong(1, playerId);
                prepared.setLong(2, older.capturedAt);
                prepared.setString(3, gson.toJson(older));
                prepared.executeUpdate();
                prepared.setLong(1, playerId);
                prepared.setLong(2, newer.capturedAt);
                prepared.setString(3, gson.toJson(newer));
                prepared.executeUpdate();
            }
        }

        PlayerMonitorService upgraded = service(directory);
        upgraded.initialize();
        var record = upgraded.findRecord("StatUser").orElseThrow();
        assertEquals(1, record.statSnapshots.size());
        assertEquals(9, record.statSnapshots.get(0).deathCount);
        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(4, Integer.parseInt(scalar(connection, "SELECT MAX(version) FROM schema_migrations")));
            assertEquals(1, countRows(connection, "stat_snapshots"));
            SQLiteSchema.verify(connection);
        }
    }

    @Test
    void legacyMigrationRefusesToOverwriteUntrackedLiveSqliteData() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-live-data-protection");
        SQLitePlayerRecordStore liveStore = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        liveStore.initialize();
        liveStore.recordChat("LivePlayer", "already persisted", 100L);
        liveStore.flush();
        liveStore.close();
        writeLegacyPlayer(directory, "LegacyPlayer", false);

        PlayerMonitorService service = service(directory);
        IOException error = assertThrows(IOException.class, service::initialize);
        assertTrue(error.getMessage().contains("already contains non-migration player data"));
        assertTrue(Files.exists(directory.resolve("players")));
        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(1, countRows(connection, "players"));
            assertEquals("LivePlayer", scalar(connection, "SELECT display_name FROM players"));
        }
    }

    @Test
    void migratesLegacyPlayerDirectoryAndMovesBackup() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        writeLegacyPlayer(directory, "Steve", false);

        PlayerMonitorService service = service(directory);
        service.initialize();

        var record = service.findRecord("steve").orElseThrow();
        assertEquals("Steve", record.playerName);
        assertEquals(1, record.loginSessions.size());
        assertEquals(1, record.chatMessages.size());
        assertEquals("hello", record.chatMessages.get(0).message);
        assertEquals(1, record.statSnapshots.size());
        assertFalse(Files.exists(directory.resolve("players")));
        assertTrue(Files.exists(directory.resolve("legacy-json-backup")));
        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            SQLiteSchema.verify(connection);
        }

        service.close();
        PlayerMonitorService reloaded = service(directory);
        reloaded.initialize();
        assertEquals(1, reloaded.findRecord("Steve").orElseThrow().chatMessages.size(),
                "completed migration must not import again after restart");
    }

    @Test
    void migrationFailureDoesNotMoveLegacyDirectory() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        writeLegacyPlayer(directory, "Broken", false);
        Files.writeString(directory.resolve("players/Broken/profile.json"), "{broken", StandardCharsets.UTF_8);

        PlayerMonitorService service = service(directory);
        assertThrows(IOException.class, service::initialize);

        assertTrue(Files.exists(directory.resolve("players")));
        assertFalse(Files.exists(directory.resolve("legacy-json-backup")));
        try (Stream<Path> reports = Files.list(directory.resolve("migration-reports"))) {
            assertTrue(reports.anyMatch(path -> path.getFileName().toString().contains("failed")));
        }
    }

    @Test
    void migrationCanResumeAfterFailedPlayerWithoutDuplicatingCompletedPlayers() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-resume");
        writeLegacyPlayer(directory, "Good", false);
        writeLegacyPlayer(directory, "Broken", false);
        Files.writeString(directory.resolve("players/Broken/profile.json"), "{broken", StandardCharsets.UTF_8);

        PlayerMonitorService firstAttempt = service(directory);
        assertThrows(IOException.class, firstAttempt::initialize);
        firstAttempt.close();

        writeLegacyPlayer(directory, "Broken", false);
        PlayerMonitorService secondAttempt = service(directory);
        secondAttempt.initialize();

        assertEquals(1, secondAttempt.findRecord("Good").orElseThrow().chatMessages.size());
        assertEquals(1, secondAttempt.findRecord("Broken").orElseThrow().chatMessages.size());
        assertFalse(Files.exists(directory.resolve("players")));
        assertTrue(Files.exists(directory.resolve("legacy-json-backup")));
        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(2, countRows(connection, "players"));
            assertEquals(2, countRows(connection, "sessions"));
            assertEquals(2, countRows(connection, "chat_messages"));
            assertEquals(2, countRows(connection, "stat_snapshots"));
            SQLiteSchema.verify(connection);
        }
    }
    @Test
    void corruptedJsonlLineFailsStrictMigrationAndKeepsLegacyDirectory() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-corrupt-jsonl");
        writeLegacyPlayer(directory, "Noisy", false);
        ChatEntry before = new ChatEntry(150L, "before");
        ChatEntry after = new ChatEntry(151L, "after");
        Files.writeString(directory.resolve("players/Noisy/chat.jsonl"),
                gson.toJson(before) + System.lineSeparator()
                        + "{broken" + System.lineSeparator()
                        + gson.toJson(after) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        PlayerMonitorService service = service(directory);
        assertThrows(IOException.class, service::initialize);

        assertTrue(Files.exists(directory.resolve("players")));
        assertFalse(Files.exists(directory.resolve("legacy-json-backup")));
        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(2, countRows(connection, "chat_messages"),
                    "valid rows may be staged for resumable migration");
            assertEquals("FAILED", scalar(connection,
                    "SELECT status FROM migration_state WHERE migration_key='legacy-json-v1'"));
        }
        try (Stream<Path> reports = Files.list(directory.resolve("migration-reports"))) {
            String reportText = reports.map(path -> {
                try {
                    return Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException error) {
                    throw new RuntimeException(error);
                }
            }).reduce("", String::concat);
            assertTrue(reportText.contains("corrupt JSONL line"));
            assertTrue(reportText.contains("Noisy chat.jsonl:2"));
        }
    }

    @Test
    void explicitPartialMigrationCanSkipCorruptJsonlLine() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-partial-jsonl");
        writeLegacyPlayer(directory, "Noisy", false);
        Files.writeString(directory.resolve("players/Noisy/chat.jsonl"),
                gson.toJson(new ChatEntry(150L, "before")) + System.lineSeparator()
                        + "{broken" + System.lineSeparator()
                        + gson.toJson(new ChatEntry(151L, "after")) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        MonitorSettings.Database settings = new MonitorSettings.Database();
        settings.allowPartialLegacyMigration = true;
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, settings);
        store.initialize();
        try {
            assertEquals(2, store.read("Noisy").chatMessages.size());
            assertFalse(Files.exists(directory.resolve("players")));
            assertTrue(Files.exists(directory.resolve("legacy-json-backup")));
        } finally {
            store.close();
        }
    }
    @Test
    void legacyDuplicateSessionsAndChatsAreDeduplicatedDuringMigration() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-duplicates");
        writeLegacyPlayer(directory, "Dupe", false);
        LoginSession session = new LoginSession(100L);
        session.logoutAt = 200L;
        Files.writeString(directory.resolve("players/Dupe/sessions.jsonl"),
                gson.toJson(session) + System.lineSeparator()
                        + gson.toJson(session) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        ChatEntry chat = new ChatEntry(150L, "same");
        Files.writeString(directory.resolve("players/Dupe/chat.jsonl"),
                gson.toJson(chat) + System.lineSeparator()
                        + gson.toJson(chat) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        PlayerMonitorService service = service(directory);
        service.initialize();

        var record = service.findRecord("Dupe").orElseThrow();
        assertEquals(1, record.loginSessions.size());
        assertEquals(1, record.chatMessages.size());
        assertEquals("same", record.chatMessages.get(0).message);
    }

    @Test
    void migrationKeepsOnlyLatestLegacyStatSnapshot() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-latest-stat");
        writeLegacyPlayer(directory, "Stats", false);
        StatSnapshot older = new StatSnapshot();
        older.capturedAt = 100L;
        older.deathCount = 1;
        StatSnapshot newer = new StatSnapshot();
        newer.capturedAt = 300L;
        newer.deathCount = 9;
        Files.writeString(directory.resolve("players/Stats/stats.jsonl"),
                gson.toJson(older) + System.lineSeparator() + gson.toJson(newer) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        PlayerMonitorService service = service(directory);
        service.initialize();
        var record = service.findRecord("Stats").orElseThrow();
        assertEquals(1, record.statSnapshots.size());
        assertEquals(300L, record.statSnapshots.get(0).capturedAt);
        assertEquals(9, record.statSnapshots.get(0).deathCount);
    }

    @Test
    void legacyOpenSessionPreservesOnlineStateAndCurrentSession() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-open-session");
        writeLegacyPlayer(directory, "Online", true);

        PlayerMonitorService service = service(directory);
        service.initialize();

        try (Connection connection = openRaw(directory.resolve("xinpm.db"));
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT p.online, p.current_session_id, s.logout_at
                     FROM players p
                     JOIN sessions s ON s.id = p.current_session_id
                     WHERE p.normalized_name = 'online'
                     """)) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
            assertTrue(resultSet.getLong(2) > 0);
            assertEquals(0, resultSet.getLong(3));
            assertTrue(resultSet.wasNull());
            SQLiteSchema.verify(connection);
        }
    }
    @Test
    void completeLegacyUpgradeDrillMigratesRestartsAndWritesOnlySqlite() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-upgrade-drill");
        writeLegacyPlayer(directory, "WaltomAdaam_", true);
        writeLegacyPlayer(directory, "濞戞搩鍘介弸鍐偝閳轰緡鍟€", false);

        PlayerMonitorService upgraded = service(directory);
        upgraded.initialize();
        upgraded.recordChat("WaltomAdaam_", "runtime sqlite only", 500L);
        upgraded.recordLogin("NewRuntime", 600L);
        upgraded.recordLogout("NewRuntime", 700L);
        upgraded.close();

        assertFalse(Files.exists(directory.resolve("players")), "runtime writes must not recreate players/");
        assertTrue(Files.exists(directory.resolve("legacy-json-backup")));
        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(3, countRows(connection, "players"));
            assertEquals(4, countRows(connection, "sessions"));
            assertEquals(3, countRows(connection, "chat_messages"));
            assertEquals(2, countRows(connection, "stat_snapshots"));
            assertEquals("COMPLETED", scalar(connection,
                    "SELECT status FROM migration_state WHERE migration_key = 'legacy-json-v1'"));
            SQLiteSchema.verify(connection);
        }

        PlayerMonitorService restarted = service(directory);
        restarted.initialize();
        assertEquals(2, restarted.findRecord("WaltomAdaam_").orElseThrow().chatMessages.size());
        assertEquals(1, restarted.findRecord("濞戞搩鍘介弸鍐偝閳轰緡鍟€").orElseThrow().chatMessages.size());
        assertEquals(1, restarted.findRecord("NewRuntime").orElseThrow().loginSessions.size());
        assertTrue(restarted.findRecord("ExternalHistoryMustBeIgnored").isEmpty());
        restarted.close();

        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(3, countRows(connection, "players"));
            assertEquals(4, countRows(connection, "sessions"));
            assertEquals(3, countRows(connection, "chat_messages"));
            assertEquals(2, countRows(connection, "stat_snapshots"));
            SQLiteSchema.verify(connection);
        }
    }
    @Test
    void backupUsesConsistentSQLiteSnapshot() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        store.initialize();
        store.recordChat("BackupPlayer", "message", 100L);
        Path backup = temporaryDirectory.resolve("backup.db");
        store.backupTo(backup);
        store.close();

        assertTrue(Files.exists(backup));
        try (Connection connection = openRaw(backup); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM chat_messages")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    @Test
    void completedSessionsWithSameTimesAreNotRejectedBySqliteConstraint() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-duplicate-sessions");
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        store.initialize();
        try {
            store.recordLogin("DuplicateSession", 100L);
            store.recordLogout("DuplicateSession", 200L);
            store.recordLogin("DuplicateSession", 100L);
            store.recordLogout("DuplicateSession", 200L);
            store.flush();
        } finally {
            store.close();
        }

        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(2, countRows(connection, "sessions"));
            SQLiteSchema.verify(connection);
        }
    }
    @Test
    void flushIntervalCommitsSmallBatchWithoutExplicitFlush() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-time-flush");
        MonitorSettings.Database settings = new MonitorSettings.Database();
        settings.batchSize = 100;
        settings.flushIntervalMs = 25;
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, settings);
        store.initialize();
        try {
            store.recordChat("Timer", "committed by interval", 100L);
            Thread.sleep(250L);
            try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
                assertEquals(1, countRows(connection, "chat_messages"));
            }
        } finally {
            store.close();
        }
    }

    @Test
    void queueFullFailsExplicitlyWithoutSilentDrop() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-queue-full");
        MonitorSettings.Database settings = new MonitorSettings.Database();
        settings.queueCapacity = 1;
        settings.batchSize = 10;
        settings.flushIntervalMs = 10;
        settings.shutdownFlushTimeoutMs = 5_000;
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, settings);
        store.setWriteDelayForTesting(2_000L);
        List<String> warnings = new ArrayList<>();
        store.setWarningSink(warnings::add);
        store.initialize();
        try {
            store.recordChat("Queue", "first", 100L);
            Thread.sleep(100L);
            store.recordChat("Queue", "second", 101L);
            IOException error = assertThrows(IOException.class,
                    () -> store.recordChat("Queue", "third", 102L));
            assertTrue(error.getMessage().contains("SQLite write queue is full"));
            assertTrue(warnings.stream().anyMatch(line -> line.contains("SQLite write queue is full")));
            Path failedEvents = directory.resolve("failed-events.jsonl");
            assertTrue(Files.exists(failedEvents), "queue rejection must be persisted for replay");
            assertTrue(Files.readString(failedEvents, StandardCharsets.UTF_8).contains("third"),
                    "the rejected event payload must be present in failed-events.jsonl");
            DatabaseHealth health = store.databaseHealth();
            assertTrue(health.failedEventLines() >= 1L);
            assertTrue(health.pendingFailedEvents() >= 1L);
            assertEquals(1L, health.chatQueueRejected());
            assertEquals(1L, health.chatDbFailed());
        } finally {
            store.setWriteDelayForTesting(0L);
            store.close();
        }
    }

    @Test
    void chatSubmittedAfterTerminalFailureIsDeadLettered() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-terminal-deadletter");
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        store.initialize();
        try {
            store.enterTerminalFailureForTesting(new IOException("simulated terminal failure"));

            IOException error = assertThrows(IOException.class,
                    () -> store.recordChat("FailedChat", "saved for replay", 100L));

            assertTrue(error.getMessage().contains("terminal failed state"));
            Path failedEvents = directory.resolve("failed-events.jsonl");
            assertTrue(Files.exists(failedEvents));
            assertTrue(Files.readString(failedEvents, StandardCharsets.UTF_8).contains("saved for replay"));
            DatabaseHealth health = store.databaseHealth();
            assertEquals(1L, health.chatDbFailed());
            assertEquals(1L, health.failedEventLines());
        } finally {
            store.close();
        }
    }

    @Test
    void failedEventsReplayExactlyOnceAndKeepOriginalDeadLetterFile() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-failed-replay");
        MonitorSettings.Database settings = new MonitorSettings.Database();
        SQLitePlayerRecordStore failing = new SQLitePlayerRecordStore(directory, settings);
        failing.setStatWriteFailureForTesting(name -> name.equals("replayme"));
        failing.initialize();
        StatSnapshot snapshot = new StatSnapshot();
        snapshot.capturedAt = 1234L;
        snapshot.deathCount = 9;
        try {
            failing.recordStat("ReplayMe", snapshot);
            assertThrows(IOException.class, failing::flush);
        } finally {
            failing.close();
        }

        Path failedEvents = directory.resolve("failed-events.jsonl");
        assertTrue(Files.exists(failedEvents));
        String originalDeadLetter = Files.readString(failedEvents, StandardCharsets.UTF_8);
        assertTrue(originalDeadLetter.contains("ReplayMe"));

        SQLitePlayerRecordStore replayed = new SQLitePlayerRecordStore(directory, settings);
        replayed.initialize();
        try {
            assertTrue(replayed.latestStat("ReplayMe").isPresent());
            DatabaseHealth health = replayed.databaseHealth();
            assertEquals(1L, health.replayedFailedEvents());
            assertEquals(0L, health.pendingFailedEvents());
        } finally {
            replayed.close();
        }
        assertEquals(originalDeadLetter, Files.readString(failedEvents, StandardCharsets.UTF_8),
                "successful replay must retain the original append-only failed-events file");

        SQLitePlayerRecordStore reopened = new SQLitePlayerRecordStore(directory, settings);
        reopened.initialize();
        try {
            assertTrue(reopened.latestStat("ReplayMe").isPresent(),
                    "restarting again must retain the replayed stat");
            try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
                assertEquals(1, countRows(connection, "replayed_failed_events"));
                assertEquals(1, countRows(connection, "stat_snapshots"));
            }
        } finally {
            reopened.close();
        }
    }

    @Test
    void recoversEveryStaleOpenSessionBeforeFreshRosterIsRecorded() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-open-session-recovery");
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        store.initialize();
        try {
            store.recordLogin("StillHere", 100L);
            store.recordLogin("AlreadyGone", 200L);
            store.flush();

            assertEquals(2, store.recoverOpenSessions(1_000L));
            DatabaseStats recovered = store.databaseStats();
            assertEquals(0, recovered.openSessions());

            store.recordLogin("StillHere", 1_000L);
            store.flush();
            DatabaseStats afterFreshRoster = store.databaseStats();
            assertEquals(1, afterFreshRoster.openSessions());
            assertEquals(3, afterFreshRoster.sessions());
        } finally {
            store.close();
        }

        try (Connection connection = openRaw(directory.resolve("xinpm.db"));
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT login_at, logout_at FROM sessions ORDER BY login_at")) {
            assertTrue(resultSet.next());
            assertEquals(100L, resultSet.getLong("login_at"));
            assertEquals(1_000L, resultSet.getLong("logout_at"));
            assertTrue(resultSet.next());
            assertEquals(200L, resultSet.getLong("login_at"));
            assertEquals(1_000L, resultSet.getLong("logout_at"));
            assertTrue(resultSet.next());
            assertEquals(1_000L, resultSet.getLong("login_at"));
            assertEquals(0L, resultSet.getLong("logout_at"));
            assertTrue(resultSet.wasNull());
        }
    }

    @Test
    void batchedStatCooldownLookupNormalizesNamesAndUsesOneResultSet() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-batched-stat-cooldown");
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        store.initialize();
        try {
            StatSnapshot recent = new StatSnapshot();
            recent.capturedAt = 2_000L;
            store.recordStat("Alice", recent);
            StatSnapshot old = new StatSnapshot();
            old.capturedAt = 500L;
            store.recordStat("Bob", old);

            Set<String> matches = store.playersWithStatCapturedAtOrAfter(
                    List.of("ALICE", "bob", "Missing", "alice"), 1_000L);

            assertEquals(Set.of("alice"), matches);
            assertTrue(store.playerExists("aLiCe"));
            assertFalse(store.playerExists("Missing"));
        } finally {
            store.close();
        }
    }

    @Test
    void stressWritesOneHundredThousandChatsAndInterleavedSessions() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-stress");
        MonitorSettings.Database settings = new MonitorSettings.Database();
        settings.queueCapacity = 150_000;
        settings.batchSize = 1_000;
        settings.flushIntervalMs = 25;
        settings.shutdownFlushTimeoutMs = 120_000;
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, settings);
        store.initialize();
        try {
            for (int player = 0; player < 2_000; player++) {
                store.recordLogin("Player" + player, player);
            }
            for (int message = 0; message < 100_000; message++) {
                store.recordChat("Player" + (message % 2_000), "msg-" + message, 10_000L + message);
            }
            for (int player = 0; player < 2_000; player++) {
                store.recordLogout("Player" + player, 200_000L + player);
            }
            store.flush();
        } finally {
            store.close();
        }

        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(2_000, countRows(connection, "players"));
            assertEquals(2_000, countRows(connection, "sessions"));
            assertEquals(100_000, countRows(connection, "chat_messages"));
            SQLiteSchema.verify(connection);
        }
    }

    @Test
    void simulatedSixMonthChatRunTracksWriterHealth() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-six-month-run");
        MonitorSettings.Database settings = new MonitorSettings.Database();
        settings.queueCapacity = 50_000;
        settings.batchSize = 500;
        settings.flushIntervalMs = 10;
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, settings);
        store.initialize();
        int messages = 0;
        try {
            long day = TimeUnit.DAYS.toMillis(1);
            for (int offset = 0; offset < 180; offset++) {
                long base = offset * day;
                String playerName = "MonthRunner" + (offset % 50);
                store.recordLogin(playerName, base);
                for (int chat = 0; chat < 50; chat++) {
                    store.recordChat(playerName, "day-" + offset + "-msg-" + chat, base + chat + 1);
                    messages++;
                }
                store.recordLogout(playerName, base + TimeUnit.HOURS.toMillis(1));
            }
            store.flush();
            DatabaseHealth health = store.databaseHealth();
            assertEquals(messages, health.chatCommitted());
            assertTrue(health.lastChatCommittedAt() > 0L);
            assertTrue(health.lastSessionCommittedAt() > 0L);
            assertTrue(health.queueHighWaterMark() > 0);
            assertFalse(health.writerStalled());
        } finally {
            store.close();
        }

        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(messages, countRows(connection, "chat_messages"));
            SQLiteSchema.verify(connection);
        }
    }

    @Test
    void interleavedLoginLogoutStressKeepsDatabaseConsistent() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-interleaved-stress");
        MonitorSettings.Database settings = new MonitorSettings.Database();
        settings.queueCapacity = 20_000;
        settings.batchSize = 1_000;
        settings.flushIntervalMs = 25;
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, settings);
        store.initialize();
        try {
            for (int player = 0; player < 3_000; player++) {
                String name = "Mixed" + player;
                long base = player * 10L;
                store.recordLogin(name, base);
                store.recordChat(name, "hello-" + player, base + 1L);
                store.recordLogout(name, base + 5L);
            }
            store.flush();
        } finally {
            store.close();
        }

        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(3_000, countRows(connection, "players"));
            assertEquals(3_000, countRows(connection, "sessions"));
            assertEquals(3_000, countRows(connection, "chat_messages"));
            SQLiteSchema.verify(connection);
        }
    }

    @Test
    void simultaneousReadsAndWritesKeepAllMessages() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-concurrent-read-write");
        MonitorSettings.Database settings = new MonitorSettings.Database();
        settings.queueCapacity = 10_000;
        settings.batchSize = 100;
        settings.flushIntervalMs = 10;
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, settings);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        store.initialize();
        Thread writer = new Thread(() -> {
            try {
                for (int index = 0; index < 1_000; index++) {
                    store.recordChat("Concurrent", "msg-" + index, index);
                }
            } catch (Throwable error) {
                writerFailure.set(error);
            }
        }, "sqlite-concurrent-writer-test");
        try {
            writer.start();
            while (writer.isAlive()) {
                store.recentChats("Concurrent", 10);
                Thread.sleep(1L);
            }
            writer.join();
            if (writerFailure.get() != null) {
                throw new AssertionError("writer failed", writerFailure.get());
            }
            store.flush();
        } finally {
            store.close();
        }

        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(1_000, countRows(connection, "chat_messages"));
            SQLiteSchema.verify(connection);
        }
    }

    @Test
    void parallelPressureKeepsAllAcceptedChats() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-parallel-pressure");
        MonitorSettings.Database settings = new MonitorSettings.Database();
        settings.queueCapacity = 50_000;
        settings.batchSize = 500;
        settings.flushIntervalMs = 10;
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, settings);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        store.initialize();
        int workers = 8;
        int perWorker = 2_500;
        List<Thread> threads = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        for (int index = 0; index < perWorker; index++) {
                            store.recordChat("Pressure" + (index % 100),
                                    workerId + "-" + index, workerId * 1_000_000L + index);
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }, "sqlite-pressure-" + worker);
                threads.add(thread);
                thread.start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join(30_000L);
                assertFalse(thread.isAlive(), "pressure submitter should finish");
            }
            if (failure.get() != null) {
                throw new AssertionError("pressure writer failed", failure.get());
            }
            store.flush();
            assertEquals((long) workers * perWorker, store.databaseHealth().chatCommitted());
        } finally {
            store.close();
        }

        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(workers * perWorker, countRows(connection, "chat_messages"));
            SQLiteSchema.verify(connection);
        }
    }
    @Test
    void acceptedWritesAreDrainedWhenCloseRacesWithSubmitters() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-close-race");
        MonitorSettings.Database settings = new MonitorSettings.Database();
        settings.queueCapacity = 5_000;
        settings.batchSize = 20;
        settings.flushIntervalMs = 10;
        settings.shutdownFlushTimeoutMs = 30_000;
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, settings);
        store.setWriteDelayForTesting(1L);
        store.initialize();

        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstAccepted = new CountDownLatch(1);
        List<Thread> submitters = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            int workerId = worker;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int index = 0; index < 500; index++) {
                        try {
                            store.recordChat("CloseRace", workerId + "-" + index, index);
                            accepted.incrementAndGet();
                            firstAccepted.countDown();
                        } catch (IOException closing) {
                            return;
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "sqlite-close-race-" + worker);
            submitters.add(thread);
            thread.start();
        }
        start.countDown();
        assertTrue(firstAccepted.await(5, TimeUnit.SECONDS));
        store.close();
        for (Thread submitter : submitters) {
            submitter.join(5_000L);
            assertFalse(submitter.isAlive(), "submitter should stop after close begins");
        }

        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(accepted.get(), countRows(connection, "chat_messages"),
                    "every event accepted before close must be committed");
        }
    }

    @Test
    void badStatEventIsReportedByFlushWithoutDiscardingOtherEvents() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-batch-failure");
        MonitorSettings.Database settings = new MonitorSettings.Database();
        settings.batchSize = 10;
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, settings);
        store.setStatWriteFailureForTesting(name -> name.equals("bad"));
        List<String> warnings = new ArrayList<>();
        store.setWarningSink(warnings::add);
        store.initialize();
        try {
            store.recordChat("Good", "before", 100L);
            StatSnapshot bad = new StatSnapshot();
            bad.capturedAt = 101L;
            store.recordStat("bad", bad);
            store.recordChat("Good", "after", 102L);
            IOException failure = assertThrows(IOException.class, store::flush);
            assertTrue(failure.getMessage().contains("events before this flush failed"));
            store.flush(); // the acknowledged non-terminal failure must not poison later flushes
            assertTrue(store.listPlayerNames().contains("Good"));
            assertFalse(store.listPlayerNames().contains("bad"),
                    "rolled-back player names must not leak into the in-memory index");
        } finally {
            store.close();
        }

        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(2, countRows(connection, "chat_messages"));
            assertEquals(0, countRows(connection, "stat_snapshots"));
            SQLiteSchema.verify(connection);
        }
        assertTrue(Files.exists(directory.resolve("failed-events.jsonl")));
        assertTrue(warnings.stream().anyMatch(line -> line.contains("operation=stat")));
    }

    @Test
    void playerOverviewIncludesLatestFiveChatsAndRawPermissionsDisplay() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-overview-recent-chat");
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        store.initialize();
        try {
            for (int index = 0; index < 6; index++) {
                store.recordChat("Steve", "msg-" + index, 1_000L + index);
            }
            StatSnapshot snapshot = new StatSnapshot();
            snapshot.capturedAt = 2_000L;
            snapshot.permissionsDisplay = "🎨√丨👟√丨🎒√";
            store.recordStat("Steve", snapshot);
            store.flush();

            var overview = store.playerOverview("Steve", 10_000L);

            assertTrue(overview.isPresent());
            assertEquals(6L, overview.get().chatCount());
            assertEquals(5, overview.get().recentChats().size());
            assertEquals("msg-5", overview.get().recentChats().get(0).message);
            assertEquals("msg-1", overview.get().recentChats().get(4).message);
            assertEquals("🎨√丨👟√丨🎒√", overview.get().latestStat().permissionsDisplay);
        } finally {
            store.close();
        }
    }

    @Test
    void recoveryDoesNotRepeatCompletedTasksAndFlushDoesNotOpenTransaction() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-partial-batch-recovery");
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        store.initialize();
        store.close();
        store.setStatWriteFailureForTesting(name -> name.equals("bad"));

        // Drive the batch directly so the fault lands after the first individual commit.
        Class<?> eventType = Class.forName(SQLitePlayerRecordStore.class.getName() + "$DatabaseEvent");
        Class<?> taskType = Class.forName(SQLitePlayerRecordStore.class.getName() + "$WriteTask");
        var taskConstructor = taskType.getDeclaredConstructor(long.class, eventType);
        taskConstructor.setAccessible(true);
        var chatConstructor = Class.forName(SQLitePlayerRecordStore.class.getName() + "$ChatEvent")
                .getDeclaredConstructor(String.class, String.class, long.class);
        chatConstructor.setAccessible(true);
        var statConstructor = Class.forName(SQLitePlayerRecordStore.class.getName() + "$StatEvent")
                .getDeclaredConstructor(String.class, StatSnapshot.class);
        statConstructor.setAccessible(true);
        var flushConstructor = Class.forName(SQLitePlayerRecordStore.class.getName() + "$FlushEvent")
                .getDeclaredConstructor();
        flushConstructor.setAccessible(true);
        var completed = taskType.getDeclaredField("completed");
        completed.setAccessible(true);
        StatSnapshot bad = new StatSnapshot();
        bad.capturedAt = 101L;
        List<Object> batch = new ArrayList<>(List.of(
                taskConstructor.newInstance(1L, chatConstructor.newInstance("Good", "before", 100L)),
                taskConstructor.newInstance(2L, statConstructor.newInstance("bad", bad)),
                taskConstructor.newInstance(3L, chatConstructor.newInstance("Good", "after", 102L))));
        Class<?> sqlType = Class.forName(SQLitePlayerRecordStore.class.getName() + "$WriterSql");
        var sqlConstructor = sqlType.getDeclaredConstructor(Connection.class);
        sqlConstructor.setAccessible(true);
        var processBatch = SQLitePlayerRecordStore.class.getDeclaredMethod(
                "processBatch", Connection.class, sqlType, List.class);
        processBatch.setAccessible(true);
        AtomicInteger commitAttempts = new AtomicInteger();
        AtomicInteger transactions = new AtomicInteger();
        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            Connection faultConnection = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("setAutoCommit") && Boolean.FALSE.equals(args[0])) {
                            transactions.incrementAndGet();
                        }
                        if (method.getName().equals("commit") && commitAttempts.incrementAndGet() == 2) {
                            throw new SQLException("simulated SQLITE_BUSY before commit", "", 5);
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
            try (AutoCloseable sql = (AutoCloseable) sqlConstructor.newInstance(faultConnection)) {
                var error = assertThrows(InvocationTargetException.class,
                        () -> processBatch.invoke(store, faultConnection, sql, batch));
                assertEquals(5, ((SQLException) error.getCause()).getErrorCode());
                assertEquals(1, countRows(connection, "chat_messages"));
                processBatch.invoke(store, faultConnection, sql, batch);
                assertEquals(2, countRows(connection, "chat_messages"));
                assertEquals(2L, store.databaseHealth().chatCommitted());
                assertEquals(0L, store.databaseHealth().chatDbFailed());
                assertEquals(1L, store.databaseHealth().failedEventLines());
                assertEquals(1L, Files.readAllLines(directory.resolve("failed-events.jsonl")).size());

                int beforeFlush = transactions.get();
                Object flush = taskConstructor.newInstance(4L, flushConstructor.newInstance());
                processBatch.invoke(store, faultConnection, sql, new ArrayList<>(List.of(flush)));
                assertTrue(((java.util.concurrent.CompletableFuture<?>) completed.get(flush)).isCompletedExceptionally(),
                        "flush must still report the earlier failed stat event");
                Object nextFlush = taskConstructor.newInstance(5L, flushConstructor.newInstance());
                processBatch.invoke(store, faultConnection, sql, new ArrayList<>(List.of(nextFlush)));
                ((java.util.concurrent.CompletableFuture<?>) completed.get(nextFlush)).get(1, TimeUnit.SECONDS);
                assertEquals(beforeFlush, transactions.get(), "flush-only batches should not open a transaction");
                SQLiteSchema.verify(connection);
            }
        }
    }

    private PlayerMonitorService service(Path directory) {
        PlayerMonitorService service = new PlayerMonitorService(directory);
        services.add(service);
        return service;
    }


    @Test
    void databaseStatsCountsRows() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-db-stat");
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
        store.initialize();
        try {
            StatSnapshot snapshot = new StatSnapshot();
            snapshot.capturedAt = 400L;
            store.recordLogin("Steve", 100L);
            store.recordLogout("Steve", 200L);
            store.recordChat("Steve", "hello", 300L);
            store.recordStat("Steve", snapshot);
            store.recordLogin("Alex", 500L);

            DatabaseStats stats = store.databaseStats();

            assertEquals(2, stats.players());
            assertEquals(1, stats.chats());
            assertEquals(2, stats.sessions());
            assertEquals(1, stats.stats());
            assertEquals(1, stats.openSessions());
        } finally {
            store.close();
        }
    }

    @Test
    void identityChecksAdvanceCheckTimeOnlyUntilValuesChangeAndSurviveRestart() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-identity-persistence");
        PlayerMonitorService service = service(directory);
        service.initialize();
        String serverUuid = "98465ebe-e619-3b1d-8b25-98352b6abbb9";
        String offlineUuid = "11111111-1111-3111-8111-111111111111";
        IdentityResolution initial = new IdentityResolution(serverUuid, offlineUuid,
                IdentityResolution.Lookup.found(serverUuid), IdentityResolution.Lookup.notFound(),
                IdentityType.PREMIUM, true);

        PlayerIdentity first = service.recordIdentityCheck("Steve", initial, 1_000L);
        assertEquals(1_000L, first.uuidLastCheckedAt());
        assertEquals(1_000L, first.uuidLastWrittenAt());

        PlayerIdentity unchanged = service.recordIdentityCheck("Steve", initial, 2_000L);
        assertEquals(2_000L, unchanged.uuidLastCheckedAt());
        assertEquals(1_000L, unchanged.uuidLastWrittenAt());

        String changedUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        IdentityResolution changed = new IdentityResolution(changedUuid, offlineUuid,
                IdentityResolution.Lookup.notFound(), IdentityResolution.Lookup.found(changedUuid),
                IdentityType.THIRD_PARTY, true);
        PlayerIdentity updated = service.recordIdentityCheck("Steve", changed, 3_000L);
        assertEquals(changedUuid, updated.serverUuid());
        assertEquals(IdentityType.THIRD_PARTY, updated.identityType());
        assertEquals(3_000L, updated.uuidLastCheckedAt());
        assertEquals(3_000L, updated.uuidLastWrittenAt());
        service.close();

        PlayerMonitorService reopened = service(directory);
        reopened.initialize();
        PlayerIdentity persisted = reopened.playerIdentity("Steve").orElseThrow();
        assertEquals(changedUuid, persisted.serverUuid());
        assertEquals(3_000L, persisted.uuidLastCheckedAt());
        assertEquals(3_000L, persisted.uuidLastWrittenAt());
    }

    @Test
    void failedRemoteLookupDoesNotErasePreviouslyValidExternalUuid() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-identity-errors");
        PlayerMonitorService service = service(directory);
        service.initialize();
        String serverUuid = "98465ebe-e619-3b1d-8b25-98352b6abbb9";
        IdentityResolution initial = new IdentityResolution(serverUuid, "offline",
                IdentityResolution.Lookup.found(serverUuid), IdentityResolution.Lookup.notFound(),
                IdentityType.PREMIUM, true);
        service.recordIdentityCheck("Steve", initial, 1_000L);

        IdentityResolution failed = new IdentityResolution(serverUuid, "offline",
                IdentityResolution.Lookup.error(), IdentityResolution.Lookup.error(),
                IdentityType.PREMIUM, false);
        PlayerIdentity preserved = service.recordIdentityCheck("Steve", failed, 2_000L);

        assertEquals(serverUuid, preserved.mojangUuid());
        assertEquals(1_000L, preserved.mojangCheckedAt());
        assertEquals(1_000L, preserved.uuidLastCheckedAt());
        assertEquals(1_000L, preserved.uuidLastWrittenAt());
    }

    @Test
    void migratesV3PlayersToV4WithoutLosingHistory() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-v3-to-v4");
        PlayerMonitorService initial = service(directory);
        initial.initialize();
        initial.recordChat("Historical", "keep", 100L);
        initial.flush();
        initial.close();

        try (Connection connection = openRaw(directory.resolve("xinpm.db")); Statement statement = connection.createStatement()) {
            for (String column : List.of("server_uuid", "offline_uuid", "mojang_uuid", "third_party_uuid",
                    "identity_type", "mojang_checked_at", "third_party_checked_at",
                    "uuid_last_checked_at", "uuid_last_written_at")) {
                statement.executeUpdate("ALTER TABLE players DROP COLUMN " + column);
            }
            statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 4");
        }

        PlayerMonitorService upgraded = service(directory);
        upgraded.initialize();
        assertEquals(1, upgraded.chatCount("Historical"));
        PlayerIdentity identity = upgraded.playerIdentity("Historical").orElseThrow();
        assertEquals(null, identity.uuidLastCheckedAt());
        assertEquals(null, identity.uuidLastWrittenAt());
        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals("4", scalar(connection, "SELECT MAX(version) FROM schema_migrations"));
            assertEquals("keep", scalar(connection, "SELECT message FROM chat_messages"));
        }
    }

    private void writeLegacyPlayer(Path directory, String name, boolean openSession) throws Exception {
        Path playerDirectory = directory.resolve("players").resolve(name);
        Files.createDirectories(playerDirectory);
        PlayerProfile profile = new PlayerProfile(name, 100L);
        profile.lastSeenAt = 300L;
        if (openSession) {
            profile.online = true;
            profile.currentSession = new LoginSession(200L);
        }
        Files.writeString(playerDirectory.resolve("profile.json"), gson.toJson(profile), StandardCharsets.UTF_8);
        LoginSession session = new LoginSession(100L);
        session.logoutAt = 200L;
        Files.writeString(playerDirectory.resolve("sessions.jsonl"), gson.toJson(session) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(playerDirectory.resolve("chat.jsonl"),
                gson.toJson(new ChatEntry(150L, "hello")) + System.lineSeparator(), StandardCharsets.UTF_8);
        StatSnapshot stat = new StatSnapshot();
        stat.capturedAt = 175L;
        stat.deathCount = 7;
        Files.writeString(playerDirectory.resolve("stats.jsonl"), gson.toJson(stat) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static Connection openRaw(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static int countRows(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
    private static String pragma(Statement statement, String pragma) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(pragma)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
