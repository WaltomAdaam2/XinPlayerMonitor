package waltomadaam2.xinbot.playermonitor;

import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.events.DisconnectEvent;
import xin.bbtt.mcbot.events.PublicChatEvent;
import xin.bbtt.mcbot.events.PlayerJoinEvent;
import xin.bbtt.mcbot.events.ServerChangeEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorListenerChatLogTest {
    @TempDir
    Path temporaryDirectory;

    private PlayerMonitorService service;
    private PlayerMonitorListener listener;
    private Path logFile;

    @BeforeEach
    void setUp() throws Exception {
        service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        MonitorSettingsStore settings = new MonitorSettingsStore(temporaryDirectory.resolve("playermonitor"));
        settings.initialize();
        settings.setScanOnEntry(false);
        settings.setStatEnabled(false);
        Path logDirectory = temporaryDirectory.resolve("playermonitor/log");
        logFile = logDirectory.resolve("playermonitor-" + LocalDate.now() + ".log");
        listener = new PlayerMonitorListener(
                service,
                new PluginLog(logDirectory),
                NOPLogger.NOP_LOGGER,
                settings);
        Bot.INSTANCE.players.clear();
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
        }
        if (service != null) {
            service.close();
        }
        Bot.INSTANCE.players.clear();
    }

    @Test
    void successfulChatRecordingProducesNoRecordedChatSuccessLog() throws Exception {
        GameProfile profile = profile("ChatterBox");
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        listener.onPlayerJoin(new PlayerJoinEvent(profile));
        listener.onPublicChat(new PublicChatEvent(profile, "hello world"));

        assertFalse(Files.exists(logFile),
                "successful INFO-level player activity must not create a file log");
    }

    @Test
    void storesTheExactRawChatMessageWithoutEmojiOrWhitespaceNormalization() throws Exception {
        GameProfile profile = profile("RawChatter");
        String raw = "  hello  ✅ → 😀  world  ";
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        listener.onPlayerJoin(new PlayerJoinEvent(profile));
        listener.onPublicChat(new PublicChatEvent(profile, raw));

        var chats = service.recentChats("RawChatter", 10);
        assertEquals(1, chats.size());
        assertEquals(raw, chats.get(0).message);
    }

    @Test
    void loginLogoutAndDisconnectStillProduceLogEntries() throws Exception {
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        listener.onDisconnect(new DisconnectEvent(Component.text("network")));

        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(line -> line.contains("[WARN] connection lost")),
                "disconnect must still be logged at WARN level");
        assertFalse(lines.stream().anyMatch(line -> line.contains("recorded login")),
                "INFO-level activity must not be persisted");
    }

    private static GameProfile profile(String name) {
        return new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name);
    }
}
