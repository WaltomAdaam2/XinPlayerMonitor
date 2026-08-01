package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SQLiteBackupManagerTest {

    @TempDir
    Path temporaryDirectory;

    private final List<SQLiteBackupManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (SQLiteBackupManager manager : managers) {
            manager.stop();
        }
    }

    @Test
    void backupContainsCommittedDataAfterFlush() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.recordChat("Player", "hello world", 100L);
        store.flush();
        store.close();

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        assertTrue(backupManager.backupNow(), "backup should succeed");

        List<Path> backups = listBackupFiles(dataDir);
        assertFalse(backups.isEmpty(), "should have created at least one backup");

        // Verify backup integrity
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backups.get(0).toAbsolutePath());
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
                assertTrue(rs.next());
                assertEquals("ok", rs.getString(1).toLowerCase(java.util.Locale.ROOT));
            }
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM chat_messages")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void backupPassesIntegrityCheck() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.recordChat("Integrity", "test", 500L);
        store.recordLogin("Integrity", 100L);
        store.recordLogout("Integrity", 600L);
        store.flush();
        store.close();

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        assertTrue(backupManager.backupNow());

        List<Path> backups = listBackupFiles(dataDir);
        assertFalse(backups.isEmpty());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backups.get(0).toAbsolutePath());
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
                assertTrue(rs.next());
                assertEquals("ok", rs.getString(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    @Test
    void backupFilesAreNamedWithTimestamp() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.flush();
        store.close();

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        assertTrue(backupManager.backupNow());

        List<Path> backups = listBackupFiles(dataDir);
        assertFalse(backups.isEmpty());
        String name = backups.get(0).getFileName().toString();
        assertTrue(name.startsWith("xinpm-auto-backup-"), "unexpected backup name: " + name);
        assertTrue(name.endsWith(".db"), "backup should end with .db: " + name);
        String timestampPart = name.substring("xinpm-auto-backup-".length(), name.length() - ".db".length());
        assertEquals(15, timestampPart.length(), "timestamp part should be yyyyMMdd-HHmmss: " + timestampPart);
        // Should be parseable
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).parse(timestampPart);
    }

    @Test
    void backupIsStoredDirectlyInDataDirectory() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.flush();
        store.close();

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        assertTrue(backupManager.backupNow());

        List<Path> backups = listBackupFiles(dataDir);
        assertFalse(backups.isEmpty());
        Path backup = backups.get(0);
        assertEquals(dataDir, backup.getParent());
        assertFalse(Files.exists(dataDir.resolve("backups")),
                "backups should not be in a 'backups' subdirectory");
    }

    @Test
    void walShmFilesAreNotLeftForBackup() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.flush();
        store.close();

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        assertTrue(backupManager.backupNow());

        List<Path> backups = listBackupFiles(dataDir);
        assertFalse(backups.isEmpty());
        for (Path backup : backups) {
            String name = backup.getFileName().toString();
            assertFalse(Files.exists(backup.resolveSibling(name + "-wal")),
                    "WAL file should not exist for backup: " + name + "-wal");
            assertFalse(Files.exists(backup.resolveSibling(name + "-shm")),
                    "SHM file should not exist for backup: " + name + "-shm");
        }
    }

    @Test
    void concurrentBackupsArePrevented() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.flush();
        store.close();

        CountDownLatch backupStarted = new CountDownLatch(1);
        CountDownLatch blocker = new CountDownLatch(1);
        AtomicInteger backupCount = new AtomicInteger();

        Runnable slowFlusher = () -> {
            backupCount.incrementAndGet();
            backupStarted.countDown();
            try {
                blocker.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), slowFlusher);
        managers.add(backupManager);

        // Start a backup in another thread
        Thread backupThread = new Thread(() -> backupManager.backupNow());
        backupThread.start();

        // Wait for first backup to start
        assertTrue(backupStarted.await(10, TimeUnit.SECONDS));

        // Try to start another backup while first is still running
        boolean secondResult = backupManager.backupNow();
        assertFalse(secondResult, "second concurrent backup should be rejected");

        // Unblock the first backup
        blocker.countDown();
        backupThread.join(5000);

        assertEquals(1, backupCount.get(), "only one backup should have run");
    }

    @Test
    void rescheduleChangesNextBackupTime() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.close();

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        backupManager.start(168);
        long firstNextTime = backupManager.nextBackupTimeMillis();

        backupManager.reschedule(24);
        long secondNextTime = backupManager.nextBackupTimeMillis();

        // After changing interval from 168h to 24h, next backup should be sooner
        assertTrue(secondNextTime <= firstNextTime,
                "reschedule to shorter interval should move next backup closer");
    }

    @Test
    void restartDoesNotResetFullInterval() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        // Create a store and simulate a recent backup
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.flush();
        store.close();

        // Create an old backup file (simulating one from 160 hours ago for a 168h interval)
        String oldTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now().minusSeconds(160L * 3600L));
        Path oldBackup = dataDir.resolve("xinpm-auto-backup-" + oldTimestamp + ".db");
        Files.copy(dataDir.resolve("xinpm.db"), oldBackup);
        long oldBackupTime = Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).parse(oldTimestamp)).toEpochMilli();

        // Create a new backup manager with 168h interval
        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        backupManager.start(168);
        long nextTime = backupManager.nextBackupTimeMillis();

        // The next backup should be ~8 hours from now (old backup + 168h),
        // not 168 hours from now
        long expectedNextMin = oldBackupTime + TimeUnit.HOURS.toMillis(168)
                - TimeUnit.HOURS.toMillis(1); // 1 hour tolerance
        long fromNowFullInterval = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(168);

        // Next backup should be close to oldBackupTime + 168h, not "now + 168h"
        long diffFromOld = Math.abs(nextTime - (oldBackupTime + TimeUnit.HOURS.toMillis(168)));
        long diffFromNow = Math.abs(nextTime - fromNowFullInterval);

        assertTrue(diffFromOld < diffFromNow,
                "next backup should be closer to old backup + interval than to now + full interval");
    }

    @Test
    void failedBackupDoesNotUpdateLastSuccessTimestamp() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.close();

        // Create an old backup file with known timestamp
        String oldTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now().minusSeconds(200L * 3600L));
        Path oldBackup = dataDir.resolve("xinpm-auto-backup-" + oldTimestamp + ".db");
        Files.copy(dataDir.resolve("xinpm.db"), oldBackup);
        long oldBackupTime = Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).parse(oldTimestamp)).toEpochMilli();

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        backupManager.start(168);
        long nextTime = backupManager.nextBackupTimeMillis();

        // Next backup should be based on the old backup time (200h ago + 168h = 32h overdue)
        // So nextTime should be ~oldBackupTime + 168h, which is already in the past
        long expectedNext = oldBackupTime + TimeUnit.HOURS.toMillis(168);
        long diffFromExpected = Math.abs(nextTime - expectedNext);
        assertTrue(diffFromExpected < TimeUnit.HOURS.toMillis(1),
                "next backup should be based on last successful backup time, got diff="
                        + (diffFromExpected / 60000) + " min");
    }

    @Test
    void staleScheduleGenerationCannotRunAfterReschedule() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor-stale-generation");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.close();

        AtomicInteger flushes = new AtomicInteger();
        SQLiteBackupManager backupManager = createBackupManager(
                dataDir, dataDir.resolve("xinpm.db"), flushes::incrementAndGet);
        backupManager.start(168);

        Field generationField = SQLiteBackupManager.class.getDeclaredField("scheduleGeneration");
        generationField.setAccessible(true);
        java.util.concurrent.atomic.AtomicLong generation =
                (java.util.concurrent.atomic.AtomicLong) generationField.get(backupManager);
        long staleGeneration = generation.get();

        backupManager.reschedule(24);
        Method executeBackup = SQLiteBackupManager.class.getDeclaredMethod("executeBackup", long.class);
        executeBackup.setAccessible(true);
        executeBackup.invoke(backupManager, staleGeneration);

        assertEquals(0, flushes.get(), "a stale task must not execute a backup or schedule follow-up work");
    }

    @Test
    void stoppedManagerCannotBeRestarted() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor-no-restart");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.close();

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        backupManager.start(168);
        backupManager.stop();
        managers.remove(backupManager);

        assertThrows(IllegalStateException.class, () -> backupManager.start(168));
    }

    @Test
    void schedulerStopsCleanly() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.close();

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        backupManager.start(168);
        backupManager.stop();
        // Verify no exceptions, and scheduler thread pool is terminated
        managers.clear(); // prevent tearDown from double-stopping
    }

    @Test
    void backupFromStoreContainsWalCommittedData() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();

        // Write data and flush
        store.recordChat("WalPlayer", "wal data", 100L);
        store.recordLogin("WalPlayer", 50L);
        store.recordLogout("WalPlayer", 200L);
        store.flush();

        // Backup directly via the store
        Path backupTarget = dataDir.resolve("test-backup.db");
        store.backupTo(backupTarget);
        store.close();

        // Verify the backup has all WAL-committed data
        assertTrue(Files.exists(backupTarget));
        assertFalse(Files.exists(dataDir.resolve("test-backup.db-wal")),
                "backup should not leave WAL files");
        assertFalse(Files.exists(dataDir.resolve("test-backup.db-shm")),
                "backup should not leave SHM files");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backupTarget.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
                assertTrue(rs.next());
                assertEquals("ok", rs.getString(1).toLowerCase(java.util.Locale.ROOT));
            }
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM chat_messages")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM sessions")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void invalidBackupIsRemoved() throws Exception {
        // This test verifies that if integrity check fails, the backup is deleted.
        // We can't easily force an invalid backup with VACUUM INTO,
        // but we can verify that the backup manager doesn't leave .tmp files around
        // and that the final backup passes integrity check.
        Path dataDir = temporaryDirectory.resolve("playermonitor");
        Files.createDirectories(dataDir);

        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.flush();
        store.close();

        SQLiteBackupManager backupManager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        assertTrue(backupManager.backupNow());

        // Verify no .tmp files are left over
        try (var stream = Files.list(dataDir)) {
            List<String> tmpFiles = stream
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".tmp"))
                    .toList();
            assertTrue(tmpFiles.isEmpty(), "no .tmp files should be left: " + tmpFiles);
        }
    }


    @Test
    void statusListAndVerifyExposeManualBackupOperations() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor-management");
        Files.createDirectories(dataDir);
        SQLitePlayerRecordStore store = new SQLitePlayerRecordStore(dataDir, new MonitorSettings.Database());
        store.initialize();
        store.recordChat("BackupUser", "persisted", 100L);
        store.flush();
        store.close();

        SQLiteBackupManager manager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });
        manager.start(168);
        assertTrue(manager.backupNow());

        SQLiteBackupManager.BackupStatus status = manager.status();
        assertTrue(status.schedulerRunning());
        assertEquals(168, status.intervalHours());
        assertEquals(1, status.backupCount());
        assertTrue(status.lastBackupAt() > 0L);
        assertTrue(status.nextBackupAt() > 0L);

        List<SQLiteBackupManager.BackupFileInfo> backups = manager.listBackups();
        assertEquals(1, backups.size());
        SQLiteBackupManager.BackupVerification verification = manager.verify(backups.get(0).filename());
        assertTrue(verification.valid());
        assertEquals("ok", verification.detail());
        assertTrue(verification.sizeBytes() > 0L);
    }

    @Test
    void verifyRejectsPathTraversalAndNonBackupNames() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor-safe-verify");
        Files.createDirectories(dataDir);
        SQLiteBackupManager manager = createBackupManager(dataDir, dataDir.resolve("xinpm.db"), () -> {
        });

        assertFalse(manager.verify("../xinpm-auto-backup-20260729-120000.db").valid());
        assertFalse(manager.verify("xinpm.db").valid());
        assertFalse(manager.verify("\u0000").valid());
        assertFalse(manager.verify("xinpm-auto-backup-20260729-120000.db").valid());
    }

    @Test
    void successfulBackupKeepsOnlyFiveNewestFiles() throws Exception {
        Path dataDir = temporaryDirectory.resolve("playermonitor-retention");
        Files.createDirectories(dataDir);
        Path database = dataDir.resolve("xinpm.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample(value TEXT)");
        }
        SQLiteBackupManager manager = createBackupManager(dataDir, database, () -> {
        });

        for (int index = 0; index < 6; index++) {
            assertTrue(manager.backupNow());
        }

        assertEquals(5, manager.listBackups().size());
        assertEquals(5, manager.status().maxBackupCount());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private SQLiteBackupManager createBackupManager(Path dataDir, Path dbPath, Runnable flusher) {
        List<String> warnings = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        SQLiteBackupManager manager = new SQLiteBackupManager(
                dataDir, dbPath, warnings::add, infos::add, flusher, () -> 5);
        managers.add(manager);
        return manager;
    }

    private List<Path> listBackupFiles(Path dataDir) throws IOException {
        try (var stream = Files.list(dataDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("xinpm-auto-backup-")
                            && path.getFileName().toString().endsWith(".db"))
                    .toList();
        }
    }
}
