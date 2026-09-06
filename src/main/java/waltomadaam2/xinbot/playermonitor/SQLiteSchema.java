package waltomadaam2.xinbot.playermonitor;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class SQLiteSchema {
    static final int VERSION = 4;

    private SQLiteSchema() {
    }

    static Connection open(Path databasePath, MonitorSettings.Database settings) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        configureConnection(connection, settings);
        return connection;
    }

    /**
     * Applies per-connection settings. journal_mode is deliberately not changed here: it is a persistent
     * database setting and is established once during initialize(). Re-running journal_mode on every read
     * connection can itself contend with the writer.
     */
    static void configureConnection(Connection connection, MonitorSettings.Database settings) throws SQLException {
        execute(connection, "PRAGMA busy_timeout = " + settings.busyTimeoutMs);
        execute(connection, "PRAGMA foreign_keys = ON");
        execute(connection, "PRAGMA synchronous = NORMAL");
        execute(connection, "PRAGMA temp_store = MEMORY");
        execute(connection, "PRAGMA wal_autocheckpoint = 1000");
        execute(connection, "PRAGMA cache_size = -" + settings.cacheSizeKiB);
    }

    static void initialize(Connection connection) throws SQLException, IOException {
        String journal = value(connection, "PRAGMA journal_mode = WAL");
        if (!"wal".equalsIgnoreCase(journal)) {
            throw new SQLException("SQLite journal_mode is " + journal + ", expected WAL");
        }

        boolean hasSchemaTable = tableExists(connection, "schema_migrations");
        if (!hasSchemaTable && hasUserTables(connection)) {
            throw new IOException("Existing SQLite database has no schema_migrations table; refusing to guess schema version");
        }

        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        description TEXT NOT NULL,
                        applied_at INTEGER NOT NULL
                    )
                    """);

            Integer currentVersion = schemaVersion(connection);
            if (currentVersion != null && currentVersion > VERSION) {
                throw new IOException("Unsupported future SQLite schema version " + currentVersion);
            }

            createTables(statement);
            if (currentVersion == null) {
                statement.executeUpdate("INSERT INTO schema_migrations(version, description, applied_at) VALUES(1, 'Initial SQLite player monitor schema', "
                        + System.currentTimeMillis() + ")");
                currentVersion = 1;
            }
            if (currentVersion < 2) {
                migrateToV2(statement);
                statement.executeUpdate("INSERT INTO schema_migrations(version, description, applied_at) VALUES(2, 'Keep only the latest stat snapshot per player', "
                        + System.currentTimeMillis() + ")");
                currentVersion = 2;
            }
            if (currentVersion < 3) {
                statement.executeUpdate("INSERT INTO schema_migrations(version, description, applied_at) VALUES(3, 'Track replayed failed events', "
                        + System.currentTimeMillis() + ")");
                currentVersion = 3;
            }
            if (currentVersion < 4) {
                migrateToV4(connection, statement);
                statement.executeUpdate("INSERT INTO schema_migrations(version, description, applied_at) VALUES(4, 'Track player UUID identity checks and writes', "
                        + System.currentTimeMillis() + ")");
            }
            connection.commit();
        } catch (SQLException | IOException error) {
            rollbackQuietly(connection);
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void migrateToV2(Statement statement) throws SQLException {
        // Keep the newest timestamp, then the highest row id for ties.
        statement.executeUpdate("""
                DELETE FROM stat_snapshots
                WHERE id NOT IN (
                    SELECT newest.id
                    FROM stat_snapshots newest
                    WHERE newest.id = (
                        SELECT candidate.id
                        FROM stat_snapshots candidate
                        WHERE candidate.player_id = newest.player_id
                        ORDER BY candidate.timestamp DESC, candidate.id DESC
                        LIMIT 1
                    )
                )
                """);
        statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_stats_one_per_player ON stat_snapshots(player_id)");
    }

    private static void migrateToV4(Connection connection, Statement statement) throws SQLException {
        addColumnIfMissing(connection, statement, "server_uuid", "TEXT");
        addColumnIfMissing(connection, statement, "offline_uuid", "TEXT");
        addColumnIfMissing(connection, statement, "mojang_uuid", "TEXT");
        addColumnIfMissing(connection, statement, "third_party_uuid", "TEXT");
        addColumnIfMissing(connection, statement, "identity_type", "TEXT");
        addColumnIfMissing(connection, statement, "mojang_checked_at", "INTEGER");
        addColumnIfMissing(connection, statement, "third_party_checked_at", "INTEGER");
        addColumnIfMissing(connection, statement, "uuid_last_checked_at", "INTEGER");
        addColumnIfMissing(connection, statement, "uuid_last_written_at", "INTEGER");
    }

    private static void addColumnIfMissing(Connection connection, Statement statement,
                                           String column, String type) throws SQLException {
        if (!columnExists(connection, "players", column)) {
            statement.executeUpdate("ALTER TABLE players ADD COLUMN " + column + " " + type);
        }
    }

    static Integer schemaVersion(Connection connection) throws SQLException {
        if (!tableExists(connection, "schema_migrations")) {
            return null;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
            if (!resultSet.next()) {
                return null;
            }
            int version = resultSet.getInt(1);
            return resultSet.wasNull() ? null : version;
        }
    }

    static void verify(Connection connection) throws SQLException, IOException {
        String integrity = value(connection, "PRAGMA integrity_check");
        if (!"ok".equalsIgnoreCase(integrity)) {
            throw new IOException("SQLite integrity_check failed: " + integrity);
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (resultSet.next()) {
                throw new IOException("SQLite foreign_key_check reported violations");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT player_id, COUNT(*)
                     FROM sessions
                     WHERE logout_at IS NULL
                     GROUP BY player_id
                     HAVING COUNT(*) > 1
                     """)) {
            if (resultSet.next()) {
                throw new IOException("SQLite verification failed: duplicate open sessions exist");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT player_id, COUNT(*)
                     FROM stat_snapshots
                     GROUP BY player_id
                     HAVING COUNT(*) > 1
                     """)) {
            if (resultSet.next()) {
                throw new IOException("SQLite verification failed: multiple stat snapshots exist for player_id="
                        + resultSet.getLong(1));
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT p.normalized_name
                     FROM players p
                     LEFT JOIN sessions s ON s.id = p.current_session_id
                     WHERE (p.online = 1 AND p.current_session_id IS NULL)
                        OR (p.current_session_id IS NOT NULL
                            AND (s.id IS NULL OR s.player_id <> p.id OR s.logout_at IS NOT NULL OR p.online <> 1))
                     LIMIT 1
                     """)) {
            if (resultSet.next()) {
                throw new IOException("SQLite verification failed: invalid current_session_id for " + resultSet.getString(1));
            }
        }
    }

    static String value(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : "";
        }
    }

    static void checkpoint(Connection connection) throws SQLException {
        execute(connection, "PRAGMA wal_checkpoint(TRUNCATE)");
    }

    static IOException toIo(String operation, SQLException error) {
        return new IOException(operation + " failed; sqlState=" + error.getSQLState()
                + ", errorCode=" + error.getErrorCode() + ", message=" + error.getMessage(), error);
    }

    static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best effort rollback
        }
    }

    private static void createTables(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    normalized_name TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    first_seen_at INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL,
                    online INTEGER NOT NULL DEFAULT 0,
                    current_session_id INTEGER,
                    last_stat_at INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    CHECK (online IN (0, 1))
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_id INTEGER NOT NULL,
                    login_at INTEGER NOT NULL,
                    logout_at INTEGER,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
                    CHECK (logout_at IS NULL OR logout_at >= login_at)
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_id INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    message TEXT NOT NULL,
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS stat_snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_id INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    stat_json TEXT NOT NULL,
                    FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS migration_state (
                    migration_key TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    started_at INTEGER,
                    completed_at INTEGER,
                    details TEXT
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS legacy_migration_players (
                    normalized_name TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    sessions_count INTEGER NOT NULL DEFAULT 0,
                    chats_count INTEGER NOT NULL DEFAULT 0,
                    stats_count INTEGER NOT NULL DEFAULT 0,
                    completed_at INTEGER,
                    error_message TEXT
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS replayed_failed_events (
                    event_id TEXT PRIMARY KEY,
                    replayed_at INTEGER NOT NULL
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_player_login ON sessions(player_id, login_at DESC)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_login ON sessions(login_at)");
        statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_open_player ON sessions(player_id) WHERE logout_at IS NULL");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chat_player_time ON chat_messages(player_id, timestamp DESC)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chat_time ON chat_messages(timestamp DESC)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_stats_player_time ON stat_snapshots(player_id, timestamp DESC)");
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean hasUserTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' LIMIT 1")) {
            return resultSet.next();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
