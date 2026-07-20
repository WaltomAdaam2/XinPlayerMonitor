package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesChinesePlayerDirectoryAndAllChatMessages() throws Exception {
        PlayerMonitorService service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        service.recordLogin("_xinbot宣传", 100L);
        service.recordChat("_xinbot宣传", "第一句", 101L);
        service.recordChat("_xinbot宣传", "第二句", 102L);
        service.recordLogout("_xinbot宣传", 200L);

        PlayerRecord record = service.getRecord("_xinbot宣传");
        Path playerDir = temporaryDirectory.resolve("playermonitor").resolve("players").resolve("_xinbot宣传");

        assertTrue(Files.exists(playerDir.resolve("profile.json")));
        assertTrue(Files.exists(playerDir.resolve("chat.jsonl")));
        assertTrue(Files.readString(playerDir.resolve("chat.jsonl"), StandardCharsets.UTF_8).contains("第一句"));
        assertEquals(1, record.loginSessions.size());
        assertEquals(2, record.chatMessages.size());
        assertEquals(200L, record.loginSessions.get(0).logoutAt);
    }

    @Test
    void updatesLoadedRecordWithoutReadingTheFileAgain() throws Exception {
        Path storageDirectory = temporaryDirectory.resolve("playermonitor");
        PlayerMonitorService initialService = new PlayerMonitorService(storageDirectory);
        initialService.initialize();
        initialService.recordLogin("WaltomAdaam", 100L);

        PlayerMonitorService service = new PlayerMonitorService(storageDirectory);
        service.initialize();
        service.getRecord("WaltomAdaam");
        Path chatFile = storageDirectory.resolve("players").resolve("WaltomAdaam").resolve("chat.jsonl");

        service.recordChat("WaltomAdaam", "cached message", 101L);

        assertEquals(1, service.getRecord("WaltomAdaam").chatMessages.size());
        assertTrue(Files.readString(chatFile, StandardCharsets.UTF_8).contains("cached message"));
    }

    @Test
    void listsStoredPlayerNamesForCommandCompletion() throws Exception {
        PlayerMonitorService service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        service.recordLogin("WaltomAdaam", 100L);
        service.recordLogin("_xinbot宣传", 100L);

        assertEquals(List.of("_xinbot宣传", "WaltomAdaam"), service.listPlayerNames());
    }

    @Test
    void doesNotWriteLogoutBeforeLoginTime() throws Exception {
        PlayerMonitorService service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        service.recordLogin("WaltomAdaam", 500L);
        service.recordLogout("WaltomAdaam", 100L);

        assertEquals(500L, service.getRecord("WaltomAdaam").loginSessions.get(0).logoutAt);
    }

    @Test
    void keepsOneOpenSessionAndSortsRecentLoginsByLoginTime() throws Exception {
        PlayerMonitorService service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
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
        PlayerMonitorService service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        service.recordLogin("Steve", 100L);
        service.recordChat("steve", "hello", 200L);

        assertEquals(1, service.getRecord("STEVE").loginSessions.size());
        assertEquals(1, service.getRecord("STEVE").chatMessages.size());
        assertEquals(1, service.listPlayerNames().size());
    }
}
