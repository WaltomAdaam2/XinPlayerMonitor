package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorServiceTest {
    private final List<PlayerMonitorService> services = new ArrayList<>();

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void tearDown() {
        services.forEach(PlayerMonitorService::close);
    }

    @Test
    void preservesChinesePlayerNameAndAllChatMessagesInSQLite() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        PlayerMonitorService service = service(directory);
        service.initialize();
        service.recordLogin("_xinbot宣传", 100L);
        service.recordChat("_xinbot宣传", "第一句", 101L);
        service.recordChat("_xinbot宣传", "第二句", 102L);
        service.recordLogout("_xinbot宣传", 200L);

        PlayerRecord record = service.getRecord("_xinbot宣传");

        assertTrue(Files.exists(directory.resolve("xinpm.db")));
        assertFalse(Files.exists(directory.resolve("players")), "runtime storage must not create JSON player directories");
        assertEquals(1, record.loginSessions.size());
        assertEquals(2, record.chatMessages.size());
        assertEquals("第一句", record.chatMessages.get(0).message);
        assertEquals(200L, record.loginSessions.get(0).logoutAt);
    }

    @Test
    void reloadsRecordFromSQLite() throws Exception {
        Path storageDirectory = temporaryDirectory.resolve("playermonitor");
        PlayerMonitorService initialService = service(storageDirectory);
        initialService.initialize();
        initialService.recordLogin("WaltomAdaam", 100L);
        initialService.recordChat("WaltomAdaam", "cached message", 101L);
        initialService.close();

        PlayerMonitorService service = service(storageDirectory);
        service.initialize();

        assertEquals(1, service.getRecord("WaltomAdaam").loginSessions.size());
        assertEquals(1, service.getRecord("WaltomAdaam").chatMessages.size());
        assertEquals("cached message", service.getRecord("WaltomAdaam").chatMessages.get(0).message);
    }

    @Test
    void databaseOverviewCountsPlayersChatsSessionsStatsAndOpenSessions() throws Exception {
        PlayerMonitorService service = service(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        service.recordLogin("Alice", 100L);
        service.recordChat("Alice", "hello", 110L);
        service.recordLogout("Alice", 200L);
        service.recordLogin("Bob", 300L);
        service.recordChat("Bob", "one", 310L);
        service.recordChat("Bob", "two", 320L);
        StatSnapshot snapshot = new StatSnapshot();
        snapshot.capturedAt = 330L;
        service.recordStat("Bob", snapshot);

        DatabaseOverview overview = service.databaseOverview();

        assertEquals(2, overview.players());
        assertEquals(3, overview.chats());
        assertEquals(2, overview.sessions());
        assertEquals(1, overview.stats());
        assertEquals(1, overview.openSessions());
    }
    @Test
    void listsStoredPlayerNamesForCommandCompletion() throws Exception {
        PlayerMonitorService service = service(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        service.recordLogin("WaltomAdaam", 100L);
        service.recordLogin("_xinbot宣传", 100L);

        assertEquals(List.of("_xinbot宣传", "WaltomAdaam"), service.listPlayerNames());
    }

    @Test
    void doesNotWriteLogoutBeforeLoginTime() throws Exception {
        PlayerMonitorService service = service(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        service.recordLogin("WaltomAdaam", 500L);
        service.recordLogout("WaltomAdaam", 100L);

        assertEquals(500L, service.getRecord("WaltomAdaam").loginSessions.get(0).logoutAt);
    }

    @Test
    void keepsOneOpenSessionAndSortsRecentLoginsByLoginTime() throws Exception {
        PlayerMonitorService service = service(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        service.recordLogin("WaltomAdaam", 100L);
        service.recordLogin("WaltomAdaam", 200L);
        service.recordLogin("WaltomAdaam", 300L);

        PlayerRecord record = service.getRecord("WaltomAdaam");
        assertEquals(200L, record.loginSessions.get(0).logoutAt);
        assertEquals(300L, record.loginSessions.get(1).logoutAt);
        assertNull(record.loginSessions.get(2).logoutAt);

        Collections.swap(record.loginSessions, 0, 2);
        assertEquals(List.of(300L, 200L, 100L), service.recentLogins(record, 15).stream()
                .map(session -> session.loginAt)
                .toList());
    }

    @Test
    void caseInsensitiveNamesResolveToSamePlayerAcrossServiceCalls() throws Exception {
        PlayerMonitorService service = service(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        service.recordLogin("Steve", 100L);
        service.recordChat("steve", "hello", 200L);

        assertEquals(1, service.getRecord("STEVE").loginSessions.size());
        assertEquals(1, service.getRecord("STEVE").chatMessages.size());
        assertEquals(1, service.listPlayerNames().size());
    }

    private PlayerMonitorService service(Path directory) {
        PlayerMonitorService service = new PlayerMonitorService(directory);
        services.add(service);
        return service;
    }
}
