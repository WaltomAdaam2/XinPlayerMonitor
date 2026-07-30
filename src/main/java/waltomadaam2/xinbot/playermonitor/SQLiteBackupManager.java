package waltomadaam2.xinbot.playermonitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Manages automatic and manual SQLite database backups.
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
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong scheduleGeneration = new AtomicLong();
    private final Object scheduleLock = new Object();
    private volatile ScheduledFuture<?> nextBackupTask;
    private volatile long scheduledBackupAtMillis;
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

    /** Starts this one-shot manager. A stopped manager cannot be restarted. */
    void start(int intervalHours) {
        if (stopped.get()) {
            throw new IllegalStateException("Backup manager has been stopped and cannot be restarted");
        }
        this.intervalHours = requirePositiveInterval(intervalHours);
        if (!running.compareAndSet(false, true)) {
            return;
        }
        long generation = scheduleGeneration.incrementAndGet();
        info("Automatic backup scheduler started (interval=" + intervalHours + " h)");
        scheduleNext(generation);
    }

    /** Stops the scheduler and permanently invalidates every old task generation. */
    void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        scheduleGeneration.incrementAndGet();
        scheduledBackupAtMillis = 0L;
        synchronized (scheduleLock) {
            ScheduledFuture<?> task = nextBackupTask;
            if (task != null) {
                task.cancel(false);
                nextBackupTask = null;
            }
        }
        // Let an already-running VACUUM INTO finish before the database service closes.
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                warn("Backup scheduler did not terminate within 60 seconds; interrupting it.");
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    warn("Backup scheduler is still running during shutdown.");
                }
            }
        } catch (InterruptedException error) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            warn("Interrupted while waiting for backup scheduler to stop");
        }
    }

    /** Recalculates the next backup time after the interval changes. */
    void reschedule(int newIntervalHours) {
        this.intervalHours = requirePositiveInterval(newIntervalHours);
        if (!running.get() || stopped.get()) {
            return;
        }
        long generation = scheduleGeneration.incrementAndGet();
        synchronized (scheduleLock) {
            ScheduledFuture<?> task = nextBackupTask;
            if (task != null) {
                task.cancel(false);
                nextBackupTask = null;
            }
        }
        info("Backup interval changed to " + newIntervalHours + " h; rescheduling.");
        scheduleNext(generation);
    }

    /** Synchronously executes one manual backup cycle. */
    boolean backupNow() {
        if (stopped.get()) {
            warn("Skipping backup: backup manager has already been stopped.");
            return false;
        }
        if (!backupLock.tryLock()) {
            warn("Skipping backup: another backup is already in progress.");
            return false;
        }
        try {
            return doBackup();
        } finally {
            backupLock.unlock();
        }
    }

    /** Returns the next schedule anchor used by the automatic scheduler. */
    long nextBackupTimeMillis() {
        long lastBackup = findLastSuccessfulBackupTime();
        return lastBackup + TimeUnit.HOURS.toMillis(requirePositiveInterval(intervalHours));
    }

    BackupStatus status() throws IOException {
        List<BackupFileInfo> backups = listBackups();
        BackupFileInfo latest = backups.isEmpty() ? null : backups.get(0);
        long next = scheduledBackupAtMillis;
        if (next <= 0L && running.get() && intervalHours > 0) {
            next = nextBackupTimeMillis();
        }
        return new BackupStatus(
                running.get() && !stopped.get(),
                stopped.get(),
                backupLock.isLocked(),
                intervalHours,
                latest == null ? 0L : latest.timestamp(),
                latest == null ? "" : latest.filename(),
                next,
                backups.size());
    }

    List<BackupFileInfo> listBackups() throws IOException {
        if (!Files.exists(dataDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dataDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> backupInfo(path))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparingLong(BackupFileInfo::timestamp).reversed()
                            .thenComparing(BackupFileInfo::filename))
                    .toList();
        }
    }

    BackupVerification verify(String filename) {
        if (filename == null || filename.isBlank()) {
            return new BackupVerification("", false, 0L, "Backup filename is required");
        }
        String trimmed = filename.trim();
        Path supplied;
        try {
            supplied = Path.of(trimmed);
        } catch (InvalidPathException error) {
            return new BackupVerification(trimmed, false, 0L, "Invalid backup filename");
        }
        if (!supplied.getFileName().toString().equals(trimmed) || !BACKUP_FILE_PATTERN.matcher(trimmed).matches()) {
            return new BackupVerification(trimmed, false, 0L, "Invalid backup filename");
        }
        Path root = dataDirectory.toAbsolutePath().normalize();
        Path backup = root.resolve(trimmed).normalize();
        if (!root.equals(backup.getParent())) {
            return new BackupVerification(trimmed, false, 0L, "Backup path escapes the data directory");
        }
        if (!Files.isRegularFile(backup)) {
            return new BackupVerification(trimmed, false, 0L, "Backup file does not exist");
        }
        if (Files.isSymbolicLink(backup)) {
            return new BackupVerification(trimmed, false, 0L, "Symbolic-link backups are not allowed");
        }
        try {
            Path realRoot = root.toRealPath();
            Path realBackup = backup.toRealPath();
            if (!realRoot.equals(realBackup.getParent())) {
                return new BackupVerification(trimmed, false, 0L, "Backup path escapes the data directory");
            }
        } catch (IOException error) {
            return new BackupVerification(trimmed, false, 0L,
                    "Unable to resolve backup path: " + error.getMessage());
        }
        long size;
        try {
            size = Files.size(backup);
        } catch (IOException error) {
            return new BackupVerification(trimmed, false, 0L, "Unable to read backup size: " + error.getMessage());
        }
        try (Connection connection = openBackupConnection(backup);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check")) {
            String result = resultSet.next() ? resultSet.getString(1) : "";
            if (!"ok".equalsIgnoreCase(result)) {
                return new BackupVerification(trimmed, false, size, "integrity_check: " + result);
            }
        } catch (SQLException error) {
            return new BackupVerification(trimmed, false, size, "Unable to open backup: " + error.getMessage());
        }
        try (Connection connection = openBackupConnection(backup);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (resultSet.next()) {
                return new BackupVerification(trimmed, false, size, "foreign_key_check reported violations");
            }
        } catch (SQLException error) {
            return new BackupVerification(trimmed, false, size, "Unable to run foreign_key_check: " + error.getMessage());
        }
        return new BackupVerification(trimmed, true, size, "ok");
    }

    private void scheduleNext(long generation) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        long now = System.currentTimeMillis();
        long nextTime = nextBackupTimeMillis();
        long delayMillis = nextTime - now;

        if (delayMillis <= TimeUnit.SECONDS.toMillis(30)) {
            delayMillis = TimeUnit.SECONDS.toMillis(60);
            info("Next automatic backup is overdue; scheduling in 1 minute.");
        }

        long scheduledAt = now + delayMillis;
        synchronized (scheduleLock) {
            if (!isCurrentGeneration(generation)) {
                return;
            }
            scheduledBackupAtMillis = scheduledAt;
            nextBackupTask = scheduler.schedule(() -> executeBackup(generation), delayMillis, TimeUnit.MILLISECONDS);
        }
        info("Next automatic backup at " + Instant.ofEpochMilli(scheduledAt)
                + " (in " + formatDuration(delayMillis) + ")");
    }

    private void executeBackup(long generation) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        if (!backupLock.tryLock()) {
            warn("Skipping automatic backup: previous backup still in progress.");
            if (isCurrentGeneration(generation)) {
                scheduleNext(generation);
            }
            return;
        }
        try {
            doBackup();
        } finally {
            backupLock.unlock();
        }
        if (isCurrentGeneration(generation)) {
            scheduleNext(generation);
        }
    }

    private boolean isCurrentGeneration(long generation) {
        return running.get() && !stopped.get() && scheduleGeneration.get() == generation;
    }

    private static int requirePositiveInterval(int hours) {
        if (hours <= 0) {
            throw new IllegalArgumentException("Backup interval must be greater than zero");
        }
        return hours;
    }

    private boolean doBackup() {
        try {
            flusher.run();
        } catch (RuntimeException error) {
            warn("Database backup aborted: flush failed: " + error.getMessage());
            return false;
        }

        Path target = nextAvailableTarget();
        Path temp = dataDirectory.resolve(target.getFileName() + ".tmp");
        boolean targetCreatedByThisRun = false;

        try {
            Files.deleteIfExists(temp);
            try (Connection connection = openMainConnection();
                 Statement statement = connection.createStatement()) {
                String escapedPath = temp.toAbsolutePath().toString().replace("'", "''");
                statement.execute("VACUUM INTO '" + escapedPath + "'");
            }

            try (Connection backupConn = openBackupConnection(temp);
                 Statement stmt = backupConn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
                String result = rs.next() ? rs.getString(1) : "";
                if (!"ok".equalsIgnoreCase(result)) {
                    throw new IOException("Backup integrity_check failed: " + result);
                }
            }

            Files.move(temp, target);
            targetCreatedByThisRun = true;
            cleanupWalFilesFor(target);
            info("Database backup completed: " + target.getFileName());
            return true;
        } catch (Exception error) {
            warn("Database backup failed: " + error.getMessage());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            if (targetCreatedByThisRun) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
            cleanupWalFilesFor(temp);
            return false;
        }
    }

    private Path nextAvailableTarget() {
        Instant candidate = Instant.now();
        for (int attempt = 0; attempt < 120; attempt++) {
            String timestamp = BACKUP_TIMESTAMP.format(candidate.plusSeconds(attempt));
            Path target = dataDirectory.resolve(BACKUP_PREFIX + timestamp + BACKUP_SUFFIX);
            if (!Files.exists(target) && !Files.exists(dataDirectory.resolve(target.getFileName() + ".tmp"))) {
                return target;
            }
        }
        throw new IllegalStateException("Unable to allocate a unique backup filename");
    }

    private long findLastSuccessfulBackupTime() {
        try {
            List<BackupFileInfo> backups = listBackups();
            return backups.isEmpty() ? System.currentTimeMillis() : backups.get(0).timestamp();
        } catch (IOException error) {
            warn("Unable to scan backup directory: " + error.getMessage());
            return System.currentTimeMillis();
        }
    }

    private BackupFileInfo backupInfo(Path path) {
        String filename = path.getFileName().toString();
        long timestamp = parseBackupTimestamp(filename);
        if (timestamp <= 0L) {
            return null;
        }
        try {
            return new BackupFileInfo(filename, timestamp, Files.size(path));
        } catch (IOException error) {
            warn("Unable to read backup file size for " + filename + ": " + error.getMessage());
            return new BackupFileInfo(filename, timestamp, -1L);
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
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
        }
        return connection;
    }

    private void cleanupWalFilesFor(Path backupPath) {
        String name = backupPath.getFileName().toString();
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

    record BackupFileInfo(String filename, long timestamp, long sizeBytes) {
    }

    record BackupStatus(boolean schedulerRunning, boolean stopped, boolean backupInProgress,
                        int intervalHours, long lastBackupAt, String lastBackupFile,
                        long nextBackupAt, int backupCount) {
    }

    record BackupVerification(String filename, boolean valid, long sizeBytes, String detail) {
    }
}
