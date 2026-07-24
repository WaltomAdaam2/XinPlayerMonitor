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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
                assertEquals(1, resultSet.getInt(1));
            }
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
    void corruptedJsonlLineIsReportedAndValidLinesContinue() throws Exception {
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
        service.initialize();

        var record = service.findRecord("Noisy").orElseThrow();
        assertEquals(2, record.chatMessages.size());
        assertEquals("before", record.chatMessages.get(0).message);
        assertEquals("after", record.chatMessages.get(1).message);
        try (Stream<Path> reports = Files.list(directory.resolve("migration-reports"))) {
            String reportText = reports.map(path -> {
                try {
                    return Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException error) {
                    throw new RuntimeException(error);
                }
            }).reduce("", String::concat);
            assertTrue(reportText.contains("skipped corrupt JSONL line"));
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
        writeLegacyPlayer(directory, "娑擃厽鏋冮悳鈺侇啀", false);

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
        assertEquals(1, restarted.findRecord("娑擃厽鏋冮悳鈺侇啀").orElseThrow().chatMessages.size());
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
        } finally {
            store.setWriteDelayForTesting(0L);
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
    void badStatEventDoesNotDiscardOtherEventsInTheBatch() throws Exception {
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
            store.flush();
        } finally {
            store.close();
        }

        try (Connection connection = openRaw(directory.resolve("xinpm.db"))) {
            assertEquals(2, countRows(connection, "chat_messages"));
            assertEquals(0, countRows(connection, "stat_snapshots"));
            SQLiteSchema.verify(connection);
        }
        assertTrue(warnings.stream().anyMatch(line -> line.contains("operation=stat")));
    }
    private PlayerMonitorService service(Path directory) {
        PlayerMonitorService service = new PlayerMonitorService(directory);
        services.add(service);
        return service;
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
