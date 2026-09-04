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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorListenerReconnectTest {
    @TempDir
    Path temporaryDirectory;

    private PlayerMonitorService service;
    private PlayerMonitorListener listener;
    private MonitorSettingsStore settings;

    @BeforeEach
    void setUp() throws Exception {
        service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        settings = new MonitorSettingsStore(temporaryDirectory.resolve("playermonitor"));
        settings.initialize();
        settings.setScanOnEntry(false);
        settings.setStatEnabled(false);
        listener = new PlayerMonitorListener(
                service,
                new PluginLog(temporaryDirectory.resolve("playermonitor/log")),
                NOPLogger.NOP_LOGGER,
                settings);
        Bot.INSTANCE.players.clear();
        Bot.INSTANCE.setServer(Server.Game);
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
        Bot.INSTANCE.setServer(Server.Login);
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
    void staleSessionRecoveryRunsOnlyOnFirstColdGameEntry() throws Exception {
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        service.recordLogin("CurrentSession", 100L);
        service.flush();

        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));

        assertEquals(1, service.getRecord("CurrentSession").loginSessions.size());
        assertNull(service.getRecord("CurrentSession").loginSessions.get(0).logoutAt);
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


    @Test
    void staleTimeoutCannotCloseSessionAfterReconnectClaimsGeneration() throws Exception {
        GameProfile player = profile("GenerationPlayer");
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        listener.onPlayerJoin(new PlayerJoinEvent(player));
        listener.onDisconnect(new DisconnectEvent(Component.text("network")));

        Field disconnectAtField = PlayerMonitorListener.class.getDeclaredField("disconnectAt");
        disconnectAtField.setAccessible(true);
        Field generationField = PlayerMonitorListener.class.getDeclaredField("reconnectGeneration");
        generationField.setAccessible(true);
        long disconnectAt = disconnectAtField.getLong(listener);
        long generation = generationField.getLong(listener);

        Bot.INSTANCE.players.put(player.getId(), player);
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));

        Method staleFinalizer = PlayerMonitorListener.class
                .getDeclaredMethod("finalizeDisconnectedSessions", long.class, long.class);
        staleFinalizer.setAccessible(true);
        staleFinalizer.invoke(listener, disconnectAt, generation);

        assertEquals(1, service.getRecord("GenerationPlayer").loginSessions.size());
        assertNull(service.getRecord("GenerationPlayer").loginSessions.get(0).logoutAt);
    }

    @Test
    void changingDisconnectTimeoutReschedulesAnActiveDisconnectWindow() throws Exception {
        settings.setDisconnectTimeoutMinutes(60);
        GameProfile player = profile("TimeoutPlayer");
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        listener.onPlayerJoin(new PlayerJoinEvent(player));
        listener.onDisconnect(new DisconnectEvent(Component.text("network")));

        Field finalizerField = PlayerMonitorListener.class.getDeclaredField("disconnectFinalizer");
        finalizerField.setAccessible(true);
        ScheduledFuture<?> original = (ScheduledFuture<?>) finalizerField.get(listener);
        assertNotNull(original);

        settings.setDisconnectTimeoutMinutes(1);
        listener.applyDisconnectTimeoutNow();

        ScheduledFuture<?> replacement = (ScheduledFuture<?>) finalizerField.get(listener);
        assertNotNull(replacement);
        assertTrue(original.isCancelled());
        long remaining = replacement.getDelay(TimeUnit.MILLISECONDS);
        assertTrue(remaining >= 0L && remaining <= TimeUnit.MINUTES.toMillis(1));
    }

    @Test
    void watchdogRecoversStuckReconnectWithoutSplittingExistingSession() throws Exception {
        GameProfile player = profile("WatchdogPlayer");
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        listener.onPlayerJoin(new PlayerJoinEvent(player));
        long loginAt = service.getRecord("WatchdogPlayer").loginSessions.get(0).loginAt;

        listener.onDisconnect(new DisconnectEvent(Component.text("network")));
        Bot.INSTANCE.players.put(player.getId(), player);
        listener.runConnectionWatchdogForTesting();

        PlayerMonitorListener.StatScanStatus status = listener.statScanStatus();
        assertTrue(status.gameActive());
        assertEquals(0, status.missingFromMonitor());
        assertEquals(1, service.getRecord("WatchdogPlayer").loginSessions.size());
        assertEquals(loginAt, service.getRecord("WatchdogPlayer").loginSessions.get(0).loginAt);
        assertNull(service.getRecord("WatchdogPlayer").loginSessions.get(0).logoutAt);
    }

    @Test
    void watchdogRecordsCurrentRosterWhenPluginStartsInsideGame() throws Exception {
        GameProfile player = profile("AlreadyOnline");
        Bot.INSTANCE.players.put(player.getId(), player);

        listener.runConnectionWatchdogForTesting();
        service.flush();

        PlayerMonitorListener.StatScanStatus status = listener.statScanStatus();
        assertTrue(status.gameActive());
        assertEquals(1, status.onlinePlayers());
        assertEquals(1, service.databaseStatsSnapshot().openSessions());
        assertNull(service.getRecord("AlreadyOnline").loginSessions.get(0).logoutAt);
    }

    @Test
    void watchdogRepairsPersistentRosterDriftAndSessions() throws Exception {
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        GameProfile player = profile("MissedJoin");
        Bot.INSTANCE.players.put(player.getId(), player);

        listener.runConnectionWatchdogForTesting();
        service.flush();

        PlayerMonitorListener.StatScanStatus joined = listener.statScanStatus();
        assertEquals(1, joined.onlinePlayers());
        assertEquals(0, joined.missingFromMonitor());
        assertEquals(1, service.databaseStatsSnapshot().openSessions());

        Bot.INSTANCE.players.remove(player.getId());
        listener.runConnectionWatchdogForTesting();
        service.flush();

        PlayerMonitorListener.StatScanStatus left = listener.statScanStatus();
        assertEquals(0, left.onlinePlayers());
        assertEquals(0, left.extraInMonitor());
        assertEquals(0, service.databaseStatsSnapshot().openSessions());
    }

    private static GameProfile profile(String name) {
        return new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name);
    }
}
