package waltomadaam2.xinbot.playermonitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Manages automatic SQLite database backups.
 * <p>
 * Uses {@code VACUUM INTO} for WAL-safe backups, validates each backup with
 * {@code PRAGMA integrity_check}, and persists the schedule by scanning existing
 * backup filenames. Backups are stored directly in the data directory with names
 * like {@code xinpm-auto-backup-20260725-061500.db}.
 */
final class SQLiteBackupManager {
    private static final String BACKUP_PREFIX = "xinpm-auto-backup-";
    private static final String BACKUP_SUFFIX = ".db";
    private static final Pattern BACKUP_FILE_PATTERN =
            Pattern.compile("xinpm-auto-backup-(\\d{8})-(\\d{6})\\.db");
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path dataDirectory;
    private final Path databasePath;
    private final Consumer<String> warningSink;
    private final Consumer<String> infoSink;
    private final Runnable flusher;
    private final ScheduledExecutorService scheduler;
    private final ReentrantLock backupLock = new ReentrantLock();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> nextBackupTask;
    private volatile int intervalHours;

    SQLiteBackupManager(Path dataDirectory, Path databasePath,
                        Consumer<String> warningSink, Consumer<String> infoSink,
                        Runnable flusher) {
        this.dataDirectory = dataDirectory;
        this.databasePath = databasePath;
        this.warningSink = warningSink;
        this.infoSink = infoSink;
        this.flusher = flusher;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "XinPlayerMonitor-backup");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the backup scheduler with the given interval in hours.
     * Safe to call multiple times; subsequent calls are ignored unless
     * {@link #stop()} was called in between.
     */
    void start(int intervalHours) {
        this.intervalHours = intervalHours;
        if (!running.compareAndSet(false, true)) {
            return;
        }
        info("Automatic backup scheduler started (interval=" + intervalHours + " h)");
        scheduleNext();
    }

    /** Stops the scheduler and cancels any pending backup. */
    void stop() {
        running.set(false);
        ScheduledFuture<?> task = nextBackupTask;
        if (task != null) {
            task.cancel(false);
            nextBackupTask = null;
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                warn("Backup scheduler did not terminate within 5 seconds");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            warn("Interrupted while waiting for backup scheduler to stop");
        }
    }

    /**
     * Recalculates the next backup time based on a new interval, cancelling the
     * currently scheduled task if one exists. Has no effect if the scheduler has
     * not been started.
     */
    void reschedule(int newIntervalHours) {
        this.intervalHours = newIntervalHours;
        if (!running.get()) {
            return;
        }
        ScheduledFuture<?> task = nextBackupTask;
        if (task != null) {
            task.cancel(false);
            nextBackupTask = null;
        }
        info("Backup interval changed to " + newIntervalHours + " h; rescheduling.");
        scheduleNext();
    }

    /**
     * Synchronously executes one backup cycle. Intended for testing and
     * for the very first backup on a fresh install. Returns true if the
     * backup succeeded.
     */
    boolean backupNow() {
        if (!backupLock.tryLock()) {
            warn("Skipping backup: another backup is already in progress.");
            return false;
        }
        try {
            doBackup();
            return true;
        } catch (Exception error) {
            warn("Backup failed: " + error.getMessage());
            return false;
        } finally {
            backupLock.unlock();
        }
    }

    /**
     * Returns the epoch-millis of the next scheduled backup, calculated from the
     * last successful backup time plus the current interval.
     */
    long nextBackupTimeMillis() {
        long lastBackup = findLastSuccessfulBackupTime();
        return lastBackup + TimeUnit.HOURS.toMillis(Math.max(1, intervalHours));
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private void scheduleNext() {
        long now = System.currentTimeMillis();
        long nextTime = nextBackupTimeMillis();
        long delayMillis = nextTime - now;

        if (delayMillis <= TimeUnit.SECONDS.toMillis(30)) {
            delayMillis = TimeUnit.SECONDS.toMillis(60);
            info("Next automatic backup is overdue; scheduling in 1 minute.");
        }

        nextBackupTask = scheduler.schedule(this::executeBackup, delayMillis, TimeUnit.MILLISECONDS);
        info("Next automatic backup at " + Instant.ofEpochMilli(now + delayMillis)
                + " (in " + formatDuration(delayMillis) + ")");
    }

    private void executeBackup() {
        if (!running.get()) {
            return;
        }
        if (!backupLock.tryLock()) {
            warn("Skipping automatic backup: previous backup still in progress.");
            scheduleNext();
            return;
        }
        try {
            doBackup();
        } finally {
            backupLock.unlock();
        }
        // Schedule the next backup regardless of outcome.
        // A failed backup does not advance the last-success timestamp,
        // so the next attempt will be recalculated from the last successful one.
        if (running.get()) {
            scheduleNext();
        }
    }

    private void doBackup() {
        // 1. Flush all pending writes so the main database is up to date.
        try {
            flusher.run();
        } catch (RuntimeException error) {
            warn("Automatic backup aborted: flush failed: " + error.getMessage());
            return;
        }

        String timestamp = BACKUP_TIMESTAMP.format(Instant.now());
        Path target = dataDirectory.resolve(BACKUP_PREFIX + timestamp + BACKUP_SUFFIX);
        Path temp = dataDirectory.resolve(target.getFileName() + ".tmp");

        try {
            Files.deleteIfExists(temp);

            // 2. Create backup via VACUUM INTO (safe for WAL mode).
            try (Connection connection = openMainConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                String escapedPath = temp.toAbsolutePath().toString().replace("'", "''");
                statement.execute("VACUUM INTO '" + escapedPath + "'");
            }

            // 3. Validate the backup.
            try (Connection backupConn = openBackupConnection(temp);
                 Statement stmt = backupConn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
                String result = rs.next() ? rs.getString(1) : "";
                if (!"ok".equalsIgnoreCase(result)) {
                    throw new IOException("Backup integrity_check failed: " + result);
                }
            }

            // 4. Move to final location.
            Files.move(temp, target);
            info("Automatic backup completed: " + target.getFileName());

            // 5. Clean up any stale WAL/SHM files that may have been created
            //    alongside the backup (VACUUM INTO should not create them,
            //    but ensure we don't leave anything behind).
            cleanupWalFilesFor(target);

        } catch (Exception error) {
            warn("Automatic backup failed: " + error.getMessage());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            // Also remove the target if it was somehow partially created.
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            cleanupWalFilesFor(target);
        }
    }

    /**
     * Scans the data directory for existing backup files and returns the
     * most recent backup's epoch-millis. If no backups exist, returns the
     * current time so the interval counts from now.
     */
    private long findLastSuccessfulBackupTime() {
        try (Stream<Path> files = Files.list(dataDirectory)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(BACKUP_PREFIX) && name.endsWith(BACKUP_SUFFIX))
                    .map(this::parseBackupTimestamp)
                    .filter(ts -> ts > 0L)
                    .max(Long::compare)
                    .orElse(System.currentTimeMillis());
        } catch (IOException error) {
            warn("Unable to scan backup directory: " + error.getMessage());
            return System.currentTimeMillis();
        }
    }

    private long parseBackupTimestamp(String filename) {
        Matcher matcher = BACKUP_FILE_PATTERN.matcher(filename);
        if (!matcher.matches()) {
            return 0L;
        }
        try {
            return Instant.from(BACKUP_TIMESTAMP.parse(matcher.group(1) + "-" + matcher.group(2)))
                    .toEpochMilli();
        } catch (DateTimeParseException error) {
            return 0L;
        }
    }

    private Connection openMainConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 30000");
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private Connection openBackupConnection(Path path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    private void cleanupWalFilesFor(Path backupPath) {
        String name = backupPath.getFileName().toString();
        // VACUUM INTO creates a standalone DB; WAL/SHM should not exist,
        // but clean them up if they do.
        try {
            Files.deleteIfExists(backupPath.resolveSibling(name + "-wal"));
            Files.deleteIfExists(backupPath.resolveSibling(name + "-shm"));
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String formatDuration(long millis) {
        long totalMinutes = millis / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0) {
            return hours + " h " + minutes + " min";
        }
        return minutes + " min";
    }

    private void info(String message) {
        infoSink.accept(message);
    }

    private void warn(String message) {
        warningSink.accept(message);
    }
}
