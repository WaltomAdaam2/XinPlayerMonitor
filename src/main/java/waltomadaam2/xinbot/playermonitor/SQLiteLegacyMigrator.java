package waltomadaam2.xinbot.playermonitor;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerProfile;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class SQLiteLegacyMigrator {
    private static final String MIGRATION_KEY = "legacy-json-v1";
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final Path directory;
    private final Path playersDirectory;
    private final Path reportDirectory;
    private final Gson gson;
    private final Consumer<String> warningSink;
    private final boolean allowPartialMigration;

    SQLiteLegacyMigrator(Path directory, Gson gson, Consumer<String> warningSink,
            boolean allowPartialMigration) {
        this.directory = directory;
        this.playersDirectory = directory.resolve("players");
        this.reportDirectory = directory.resolve("migration-reports");
        this.gson = gson;
        this.warningSink = warningSink;
        this.allowPartialMigration = allowPartialMigration;
    }

    void migrateIfNeeded(Connection connection) throws IOException, SQLException {
        String status = migrationStatus(connection);
        if ("COMPLETED".equals(status)) {
            if (Files.isDirectory(playersDirectory)) {
                Report report = new Report();
                report.warning("Migration data was already committed; retrying legacy directory archival");
                moveLegacyDirectory(report);
            }
            return;
        }
        if (!Files.isDirectory(playersDirectory)) {
            return;
        }
        Files.createDirectories(reportDirectory);
        Report report = new Report();
        ensureNoUntrackedSqlitePlayers(connection, report);
        if (allowPartialMigration) {
            report.warning("allowPartialLegacyMigration=true: corrupted non-empty JSONL lines may be skipped");
            warningSink.accept("WARNING: XinPM legacy migration is running in partial mode; damaged rows may be skipped");
        }
        long startedAt = System.currentTimeMillis();
        upsertMigrationState(connection, "IN_PROGRESS", startedAt, null, "Legacy JSON migration started");
        List<Path> playerDirs = playerDirectories();
        validateNoCaseDuplicates(playerDirs, report);
        if (report.fatals > 0) {
            failMigration(connection, report, "case-only duplicate player directories");
            throw new IOException("Legacy JSON migration failed: case-only duplicate player directories");
        }
        int processed = 0;
        for (Path playerDir : playerDirs) {
            String normalized = normalize(playerDir.getFileName().toString());
            if (legacyPlayerCompleted(connection, normalized)) {
                report.skippedPlayers++;
                continue;
            }
            try {
                int corruptBefore = report.corruptLines;
                LegacyPlayer player = readPlayer(playerDir, report);
                boolean playerHasCorruptRows = report.corruptLines > corruptBefore;
                String playerStatus = playerHasCorruptRows && !allowPartialMigration ? "FAILED" : "COMPLETED";
                String playerError = playerHasCorruptRows && !allowPartialMigration
                        ? "one or more non-empty JSONL lines were corrupted" : null;
                writePlayer(connection, player, playerStatus, playerError);
                if ("FAILED".equals(playerStatus)) {
                    report.failedPlayers++;
                    report.error("Failed player " + player.displayName
                            + ": corrupted JSONL rows were skipped in strict migration mode");
                } else {
                    report.migratedPlayers++;
                    report.migratedSessions += player.sessions.size();
                    report.migratedChats += player.chats.size();
                    report.migratedStats += player.stats.size();
                }
            } catch (IOException | SQLException error) {
                report.failedPlayers++;
                report.error("Failed player " + playerDir.getFileName() + ": " + error.getMessage());
                markLegacyPlayer(connection, normalized, "FAILED", 0, 0, 0, error.getMessage());
            }
            processed++;
            if (processed % 500 == 0 || processed == playerDirs.size()) {
                warningSink.accept("Migrated " + processed + " / " + playerDirs.size() + " players");
            }
        }
        SQLiteSchema.verify(connection);
        if (report.failedPlayers > 0 || report.fatals > 0
                || (!allowPartialMigration && report.corruptLines > 0)) {
            failMigration(connection, report, "one or more players or JSONL rows failed");
            throw new IOException("Legacy JSON migration failed for " + report.failedPlayers
                    + " player(s), corrupt JSONL lines=" + report.corruptLines);
        }
        upsertMigrationState(connection, "COMPLETED", startedAt, System.currentTimeMillis(), report.summary());
        writeReport(report, "completed");
        moveLegacyDirectory(report);
    }

    private void ensureNoUntrackedSqlitePlayers(Connection connection, Report report)
            throws SQLException, IOException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT p.normalized_name
                     FROM players p
                     LEFT JOIN legacy_migration_players legacy
                       ON legacy.normalized_name = p.normalized_name
                     WHERE legacy.normalized_name IS NULL
                     LIMIT 1
                     """)) {
            if (resultSet.next()) {
                String normalized = resultSet.getString(1);
                report.fatal("SQLite already contains untracked player data for " + normalized
                        + "; refusing to overwrite a database that may have received live writes");
                writeReport(report, "unsafe-existing-data");
                throw new IOException("Legacy JSON migration refused: xinpm.db already contains non-migration player data");
            }
        }
    }

    private List<Path> playerDirectories() throws IOException {
        try (Stream<Path> paths = Files.list(playersDirectory)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> !isInternalOrQuarantinedDirectory(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    private void validateNoCaseDuplicates(List<Path> playerDirs, Report report) {
        Map<String, List<String>> byNormalized = new LinkedHashMap<>();
        for (Path playerDir : playerDirs) {
            String name = playerDir.getFileName().toString();
            byNormalized.computeIfAbsent(normalize(name), ignored -> new ArrayList<>()).add(name);
        }
        for (Map.Entry<String, List<String>> entry : byNormalized.entrySet()) {
            if (entry.getValue().size() > 1) {
                entry.getValue().sort(String::compareTo);
                report.fatal("Case-only duplicate player directories for " + entry.getKey() + ": " + entry.getValue());
            }
        }
    }

    private LegacyPlayer readPlayer(Path playerDir, Report report) throws IOException {
        String directoryName = playerDir.getFileName().toString();
        String normalized = normalize(directoryName);
        PlayerProfile profile = readProfile(playerDir.resolve("profile.json"), directoryName, report);
        String displayName = profile.playerName == null || profile.playerName.isBlank() ? directoryName : profile.playerName;
        if (!normalize(displayName).equals(normalized)) {
            throw new IOException("profile playerName does not match directory name case-insensitively: " + displayName);
        }
        List<LoginSession> sessions = readSessions(playerDir.resolve("sessions.jsonl"), displayName, report);
        if (profile.currentSession != null && profile.currentSession.logoutAt == null) {
            sessions.add(profile.currentSession.copy());
        }
        sessions = dedupeSessions(sessions, displayName, report);
        List<ChatEntry> chats = dedupeChats(readChats(playerDir.resolve("chat.jsonl"), displayName, report), displayName, report);
        List<StatSnapshot> stats = dedupeStats(readStats(playerDir.resolve("stats.jsonl"), displayName, report), displayName, report);
        long firstSeen = profile.firstSeenAt > 0 ? profile.firstSeenAt : earliest(sessions, chats, stats, System.currentTimeMillis());
        long lastSeen = Math.max(profile.lastSeenAt, latest(sessions, chats, stats, firstSeen));
        Long lastStat = stats.stream().map(snapshot -> snapshot.capturedAt).max(Long::compareTo)
                .orElse(profile.lastStatCapturedAt);
        return new LegacyPlayer(normalized, displayName, firstSeen, lastSeen, lastStat, sessions, chats, stats);
    }

    private PlayerProfile readProfile(Path file, String fallbackName, Report report) throws IOException {
        if (!Files.exists(file)) {
            report.warning("Missing profile.json for " + fallbackName + "; using directory name");
            return new PlayerProfile(fallbackName, System.currentTimeMillis());
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            PlayerProfile profile = gson.fromJson(reader, PlayerProfile.class);
            if (profile == null || profile.playerName == null || profile.playerName.isBlank()) {
                throw new IOException("profile.json missing playerName");
            }
            if (profile.currentSession != null && profile.currentSession.logoutAt != null) {
                profile.currentSession = null;
            }
            return profile;
        } catch (JsonParseException error) {
            throw new IOException("corrupted profile.json for " + fallbackName + ": " + error.getMessage(), error);
        }
    }
    private List<LoginSession> readSessions(Path file, String playerName, Report report) throws IOException {
        return readJsonl(file, LoginSession.class, playerName + " sessions.jsonl", report, (object, session, line) -> {
            if (!object.has("loginAt")) {
                throw new JsonParseException("missing loginAt");
            }
            if (session.logoutAt != null && session.logoutAt < session.loginAt) {
                report.warning(playerName + " sessions.jsonl:" + line + " logoutAt before loginAt; clamped");
                session.logoutAt = session.loginAt;
            }
        });
    }

    private List<ChatEntry> readChats(Path file, String playerName, Report report) throws IOException {
        return readJsonl(file, ChatEntry.class, playerName + " chat.jsonl", report, (object, chat, line) -> {
            if (!object.has("timestamp")) {
                throw new JsonParseException("missing timestamp");
            }
            if (!object.has("message") || chat.message == null) {
                throw new JsonParseException("missing message");
            }
        });
    }

    private List<StatSnapshot> readStats(Path file, String playerName, Report report) throws IOException {
        return readJsonl(file, StatSnapshot.class, playerName + " stats.jsonl", report, (object, stat, line) -> {
            if (!object.has("capturedAt")) {
                throw new JsonParseException("missing capturedAt");
            }
        });
    }

    private <T> List<T> readJsonl(Path file, Class<T> type, String label, Report report, Validator<T> validator) throws IOException {
        List<T> values = new ArrayList<>();
        if (!Files.exists(file)) {
            return values;
        }
        int lineNumber = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonElement element = JsonParser.parseString(line);
                    if (!element.isJsonObject()) {
                        throw new JsonParseException("line is not a JSON object");
                    }
                    JsonObject object = element.getAsJsonObject();
                    T value = gson.fromJson(object, type);
                    if (value == null) {
                        throw new JsonParseException("line parsed to null");
                    }
                    validator.validate(object, value, lineNumber);
                    values.add(value);
                } catch (JsonParseException error) {
                    report.corruptLine(label + ":" + lineNumber + " corrupt JSONL line: " + error.getMessage(),
                            allowPartialMigration);
                }
            }
        }
        return values;
    }

    private List<LoginSession> dedupeSessions(List<LoginSession> sessions, String playerName, Report report) {
        sessions.sort(Comparator.comparingLong(session -> session.loginAt));
        Set<String> seenCompleted = new HashSet<>();
        LoginSession newestOpen = null;
        List<LoginSession> result = new ArrayList<>();
        for (LoginSession session : sessions) {
            if (session.logoutAt == null) {
                if (newestOpen == null || session.loginAt >= newestOpen.loginAt) {
                    if (newestOpen != null) {
                        report.warning(playerName + " multiple open sessions; kept newest open session");
                    }
                    newestOpen = session.copy();
                }
                continue;
            }
            long logoutAt = Math.max(session.loginAt, session.logoutAt);
            String key = session.loginAt + "\0" + logoutAt;
            if (seenCompleted.add(key)) {
                LoginSession copy = new LoginSession(session.loginAt);
                copy.logoutAt = logoutAt;
                result.add(copy);
            } else {
                report.warning(playerName + " duplicate legacy session skipped at " + session.loginAt);
            }
        }
        if (newestOpen != null) {
            result.add(newestOpen);
        }
        result.sort(Comparator.comparingLong(session -> session.loginAt));
        return result;
    }

    private List<ChatEntry> dedupeChats(List<ChatEntry> chats, String playerName, Report report) {
        Set<String> seen = new HashSet<>();
        List<ChatEntry> result = new ArrayList<>();
        for (ChatEntry chat : chats) {
            String key = chat.timestamp + "\0" + chat.message;
            if (seen.add(key)) {
                result.add(chat);
            } else {
                report.warning(playerName + " duplicate legacy chat skipped at " + chat.timestamp);
            }
        }
        return result;
    }

    private List<StatSnapshot> dedupeStats(List<StatSnapshot> stats, String playerName, Report report) {
        if (stats.isEmpty()) {
            return List.of();
        }
        StatSnapshot latest = stats.stream()
                .max(Comparator.comparingLong(snapshot -> snapshot.capturedAt))
                .orElseThrow();
        if (stats.size() > 1) {
            report.warning(playerName + " legacy stats contained " + stats.size()
                    + " snapshots; kept only latest capturedAt=" + latest.capturedAt);
        }
        return List.of(latest);
    }

    private void writePlayer(Connection connection, LegacyPlayer player, String migrationStatus,
            String migrationError) throws SQLException, IOException {
        connection.setAutoCommit(false);
        try {
            deleteExistingPlayer(connection, player.normalizedName);
            long playerId = insertPlayer(connection, player);
            Long currentSessionId = null;
            try (PreparedStatement session = connection.prepareStatement("""
                    INSERT INTO sessions(player_id, login_at, logout_at, created_at)
                    VALUES(?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                for (LoginSession value : player.sessions) {
                    session.setLong(1, playerId);
                    session.setLong(2, value.loginAt);
                    if (value.logoutAt == null) {
                        session.setNull(3, Types.INTEGER);
                    } else {
                        session.setLong(3, value.logoutAt);
                    }
                    session.setLong(4, value.loginAt);
                    session.executeUpdate();
                    if (value.logoutAt == null) {
                        try (ResultSet keys = session.getGeneratedKeys()) {
                            if (keys.next()) {
                                currentSessionId = keys.getLong(1);
                            }
                        }
                    }
                }
            }
            insertChats(connection, playerId, player.chats);
            insertStats(connection, playerId, player.stats);
            if (currentSessionId != null) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE players SET online = 1, current_session_id = ?, updated_at = ? WHERE id = ?")) {
                    statement.setLong(1, currentSessionId);
                    statement.setLong(2, System.currentTimeMillis());
                    statement.setLong(3, playerId);
                    statement.executeUpdate();
                }
            }
            verifyImportedPlayerCounts(connection, playerId, player);
            markLegacyPlayer(connection, player.normalizedName, migrationStatus,
                    player.sessions.size(), player.chats.size(), player.stats.size(), migrationError);
            connection.commit();
        } catch (SQLException | IOException error) {
            SQLiteSchema.rollbackQuietly(connection);
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void verifyImportedPlayerCounts(Connection connection, long playerId, LegacyPlayer player)
            throws SQLException, IOException {
        int sessions = countRows(connection, "sessions", playerId);
        int chats = countRows(connection, "chat_messages", playerId);
        int stats = countRows(connection, "stat_snapshots", playerId);
        if (sessions != player.sessions.size() || chats != player.chats.size() || stats != player.stats.size()) {
            throw new IOException("legacy migration count mismatch for " + player.displayName
                    + ": sessions " + sessions + "/" + player.sessions.size()
                    + ", chats " + chats + "/" + player.chats.size()
                    + ", stats " + stats + "/" + player.stats.size());
        }
    }

    private int countRows(Connection connection, String table, long playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE player_id = ?")) {
            statement.setLong(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }
    private long insertPlayer(Connection connection, LegacyPlayer player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO players(normalized_name, display_name, first_seen_at, last_seen_at,
                                    online, current_session_id, last_stat_at, created_at, updated_at)
                VALUES(?, ?, ?, ?, 0, NULL, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, player.normalizedName);
            statement.setString(2, player.displayName);
            statement.setLong(3, player.firstSeenAt);
            statement.setLong(4, player.lastSeenAt);
            if (player.lastStatAt == null) {
                statement.setNull(5, Types.INTEGER);
            } else {
                statement.setLong(5, player.lastStatAt);
            }
            long now = System.currentTimeMillis();
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated id for " + player.displayName);
                }
                return keys.getLong(1);
            }
        }
    }

    private void insertChats(Connection connection, long playerId, List<ChatEntry> chats) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat_messages(player_id, timestamp, message) VALUES(?, ?, ?)")) {
            for (ChatEntry chat : chats) {
                statement.setLong(1, playerId);
                statement.setLong(2, chat.timestamp);
                statement.setString(3, chat.message);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertStats(Connection connection, long playerId, List<StatSnapshot> stats) throws SQLException {
        if (stats.isEmpty()) {
            return;
        }
        StatSnapshot stat = stats.get(0);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO stat_snapshots(player_id, timestamp, stat_json) VALUES(?, ?, ?)")) {
            statement.setLong(1, playerId);
            statement.setLong(2, stat.capturedAt);
            statement.setString(3, gson.toJson(stat));
            statement.executeUpdate();
        }
    }

    private void deleteExistingPlayer(Connection connection, String normalizedName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM players WHERE normalized_name = ?")) {
            statement.setString(1, normalizedName);
            statement.executeUpdate();
        }
    }

    private boolean legacyPlayerCompleted(Connection connection, String normalizedName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM legacy_migration_players WHERE normalized_name = ?")) {
            statement.setString(1, normalizedName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && "COMPLETED".equals(resultSet.getString(1));
            }
        }
    }

    private void markLegacyPlayer(Connection connection, String normalizedName, String status,
            int sessions, int chats, int stats, String error) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO legacy_migration_players(normalized_name, status, sessions_count, chats_count,
                                                     stats_count, completed_at, error_message)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(normalized_name) DO UPDATE SET
                    status = excluded.status,
                    sessions_count = excluded.sessions_count,
                    chats_count = excluded.chats_count,
                    stats_count = excluded.stats_count,
                    completed_at = excluded.completed_at,
                    error_message = excluded.error_message
                """)) {
            statement.setString(1, normalizedName);
            statement.setString(2, status);
            statement.setInt(3, sessions);
            statement.setInt(4, chats);
            statement.setInt(5, stats);
            if ("COMPLETED".equals(status)) {
                statement.setLong(6, System.currentTimeMillis());
            } else {
                statement.setNull(6, Types.INTEGER);
            }
            statement.setString(7, error);
            statement.executeUpdate();
        }
    }
    private void upsertMigrationState(Connection connection, String status, Long startedAt, Long completedAt,
            String details) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO migration_state(migration_key, status, started_at, completed_at, details)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(migration_key) DO UPDATE SET
                    status = excluded.status,
                    started_at = COALESCE(migration_state.started_at, excluded.started_at),
                    completed_at = excluded.completed_at,
                    details = excluded.details
                """)) {
            statement.setString(1, MIGRATION_KEY);
            statement.setString(2, status);
            if (startedAt == null) {
                statement.setNull(3, Types.INTEGER);
            } else {
                statement.setLong(3, startedAt);
            }
            if (completedAt == null) {
                statement.setNull(4, Types.INTEGER);
            } else {
                statement.setLong(4, completedAt);
            }
            statement.setString(5, details);
            statement.executeUpdate();
        }
    }

    private String migrationStatus(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM migration_state WHERE migration_key = ?")) {
            statement.setString(1, MIGRATION_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : "NOT_STARTED";
            }
        }
    }

    private void failMigration(Connection connection, Report report, String details) throws SQLException, IOException {
        upsertMigrationState(connection, "FAILED", System.currentTimeMillis(), null, details);
        writeReport(report, "failed");
    }

    private void writeReport(Report report, String suffix) throws IOException {
        Files.createDirectories(reportDirectory);
        Path reportPath = reportDirectory.resolve("legacy-json-v1-" + REPORT_TIME.format(Instant.now())
                + "-" + suffix + ".log");
        Files.writeString(reportPath, report.render(), StandardCharsets.UTF_8);
    }

    private void moveLegacyDirectory(Report report) throws IOException {
        Path backup = directory.resolve("legacy-json-backup");
        if (Files.exists(backup)) {
            backup = directory.resolve("legacy-json-backup-" + REPORT_TIME.format(Instant.now()));
        }
        IOException atomicFailure = null;
        try {
            Files.move(playersDirectory, backup, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException error) {
            atomicFailure = error;
        } catch (IOException error) {
            // Some providers report a cross-device atomic move as a generic IOException.
            atomicFailure = error;
        }
        try {
            Files.move(playersDirectory, backup);
        } catch (IOException error) {
            if (atomicFailure != null) {
                error.addSuppressed(atomicFailure);
            }
            reportMoveFailure(report, error);
        }
    }

    private void reportMoveFailure(Report report, IOException error) throws IOException {
        report.warning("Unable to move legacy players directory after completed migration: " + error.getMessage());
        warningSink.accept("Legacy JSON migration completed, but moving players directory failed: "
                + error.getMessage() + "; XinPM will retry the archival on next startup.");
        writeReport(report, "backup-warning");
    }

    private long earliest(List<LoginSession> sessions, List<ChatEntry> chats, List<StatSnapshot> stats, long fallback) {
        long earliest = Long.MAX_VALUE;
        for (LoginSession session : sessions) {
            earliest = Math.min(earliest, session.loginAt);
        }
        for (ChatEntry chat : chats) {
            earliest = Math.min(earliest, chat.timestamp);
        }
        for (StatSnapshot stat : stats) {
            earliest = Math.min(earliest, stat.capturedAt);
        }
        return earliest == Long.MAX_VALUE ? fallback : earliest;
    }

    private long latest(List<LoginSession> sessions, List<ChatEntry> chats, List<StatSnapshot> stats, long fallback) {
        long latest = fallback;
        for (LoginSession session : sessions) {
            latest = Math.max(latest, session.loginAt);
            if (session.logoutAt != null) {
                latest = Math.max(latest, session.logoutAt);
            }
        }
        for (ChatEntry chat : chats) {
            latest = Math.max(latest, chat.timestamp);
        }
        for (StatSnapshot stat : stats) {
            latest = Math.max(latest, stat.capturedAt);
        }
        return latest;
    }

    private static boolean isInternalOrQuarantinedDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            return false;
        }
        String name = path.getFileName().toString();
        return name.startsWith("xpm-migration-")
                || name.endsWith(".corrupted")
                || name.contains(".corrupted-");
    }

    private static String normalize(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }

    private interface Validator<T> {
        void validate(JsonObject object, T value, int lineNumber);
    }

    private record LegacyPlayer(String normalizedName, String displayName, long firstSeenAt, long lastSeenAt,
                                Long lastStatAt, List<LoginSession> sessions, List<ChatEntry> chats,
                                List<StatSnapshot> stats) {
    }

    private static final class Report {
        private final List<String> lines = new ArrayList<>();
        private int migratedPlayers;
        private int skippedPlayers;
        private int failedPlayers;
        private int migratedSessions;
        private int migratedChats;
        private int migratedStats;
        private int warnings;
        private int corruptLines;
        private int errors;
        private int fatals;

        void warning(String message) {
            warnings++;
            lines.add("WARNING: " + message);
        }

        void corruptLine(String message, boolean partialAllowed) {
            corruptLines++;
            if (partialAllowed) {
                warning("PARTIAL: " + message + " (skipped by explicit configuration)");
            } else {
                error(message);
            }
        }

        void error(String message) {
            errors++;
            lines.add("ERROR: " + message);
        }

        void fatal(String message) {
            fatals++;
            lines.add("FATAL: " + message);
        }

        String summary() {
            return "players=" + migratedPlayers
                    + ", skipped=" + skippedPlayers
                    + ", failed=" + failedPlayers
                    + ", sessions=" + migratedSessions
                    + ", chats=" + migratedChats
                    + ", stats=" + migratedStats
                    + ", warnings=" + warnings
                    + ", corruptLines=" + corruptLines
                    + ", errors=" + errors
                    + ", fatals=" + fatals;
        }

        String render() {
            StringBuilder builder = new StringBuilder();
            builder.append("XinPlayerMonitor legacy JSON migration report").append(System.lineSeparator());
            builder.append(summary()).append(System.lineSeparator()).append(System.lineSeparator());
            for (String line : lines) {
                builder.append(line).append(System.lineSeparator());
            }
            return builder.toString();
        }
    }
}
