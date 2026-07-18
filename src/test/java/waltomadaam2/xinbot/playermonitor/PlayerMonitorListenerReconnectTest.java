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
import xin.bbtt.mcbot.events.PlayerJoinEvent;
import xin.bbtt.mcbot.events.ServerChangeEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorListenerReconnectTest {
    @TempDir
    Path temporaryDirectory;

    private PlayerMonitorService service;
    private PlayerMonitorListener listener;

    @BeforeEach
    void setUp() throws Exception {
        service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        MonitorSettingsStore settings = new MonitorSettingsStore(temporaryDirectory.resolve("playermonitor"));
        settings.initialize();
        settings.setAutoScanOnGameEntry(false);
        settings.setStatScanEnabled(false);
        listener = new PlayerMonitorListener(
                service,
                new PluginLog(temporaryDirectory.resolve("playermonitor/log")),
                NOPLogger.NOP_LOGGER,
                settings);
        Bot.INSTANCE.players.clear();
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
        }
        Bot.INSTANCE.players.clear();
    }

    @Test
    void keepsExistingSessionsAndReconcilesMissingAndNewPlayers() throws Exception {
        GameProfile existing = profile("Existing");
        GameProfile leaving = profile("Leaving");
        GameProfile newcomer = profile("Newcomer");

        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        listener.onPlayerJoin(new PlayerJoinEvent(existing));
        listener.onPlayerJoin(new PlayerJoinEvent(leaving));
        long existingLoginAt = service.getRecord("Existing").loginSessions.get(0).loginAt;

        listener.onDisconnect(new DisconnectEvent(Component.text("network")));
        Bot.INSTANCE.players.put(existing.getId(), existing);
        Bot.INSTANCE.players.put(newcomer.getId(), newcomer);
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));

        assertEquals(1, service.getRecord("Existing").loginSessions.size());
        assertEquals(existingLoginAt, service.getRecord("Existing").loginSessions.get(0).loginAt);
        assertNull(service.getRecord("Existing").loginSessions.get(0).logoutAt);

        assertNotNull(service.getRecord("Leaving").loginSessions.get(0).logoutAt);
        assertEquals(1, service.getRecord("Newcomer").loginSessions.size());
        assertNull(service.getRecord("Newcomer").loginSessions.get(0).logoutAt);
    }

    @Test
    void timeoutClosesOldSessionsAndLateReconnectStartsNewSessions() throws Exception {
        GameProfile player = profile("Player");
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        listener.onPlayerJoin(new PlayerJoinEvent(player));
        listener.onDisconnect(new DisconnectEvent(Component.text("network")));

        Field disconnectAtField = PlayerMonitorListener.class.getDeclaredField("disconnectAt");
        disconnectAtField.setAccessible(true);
        long disconnectAt = disconnectAtField.getLong(listener);
        Method finalizeMethod = PlayerMonitorListener.class
                .getDeclaredMethod("finalizeDisconnectedSessions", long.class);
        finalizeMethod.setAccessible(true);
        finalizeMethod.invoke(listener, disconnectAt);

        Bot.INSTANCE.players.put(player.getId(), player);
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));

        assertEquals(2, service.getRecord("Player").loginSessions.size());
        assertNotNull(service.getRecord("Player").loginSessions.get(0).logoutAt);
        assertNull(service.getRecord("Player").loginSessions.get(1).logoutAt);
        assertTrue(service.getRecord("Player").loginSessions.get(1).loginAt
                >= service.getRecord("Player").loginSessions.get(0).logoutAt);
    }

    private static GameProfile profile(String name) {
        return new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name);
    }
}