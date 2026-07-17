package huangdihd.xinbot.playermonitor;

import huangdihd.xinbot.playermonitor.model.PlayerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesChinesePlayerFileAndAllChatMessages() throws Exception {
        PlayerMonitorService service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        service.recordLogin("_xinbot宣传", 100L);
        service.recordChat("_xinbot宣传", "第一句", 101L);
        service.recordChat("_xinbot宣传", "第二句", 102L);
        service.recordLogout("_xinbot宣传", 200L);

        PlayerRecord record = service.getRecord("_xinbot宣传");
        Path playerFile = temporaryDirectory.resolve("playermonitor").resolve("_xinbot宣传.json");

        assertTrue(Files.exists(playerFile));
        assertTrue(Files.readString(playerFile, StandardCharsets.UTF_8).contains("第一句"));
        assertEquals(1, record.loginSessions.size());
        assertEquals(2, record.chatMessages.size());
        assertEquals(200L, record.loginSessions.get(0).logoutAt);
    }
}
