package waltomadaam2.xinbot.playermonitor;

import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.events.DisconnectEvent;
import xin.bbtt.mcbot.events.PlayerJoinEvent;
import xin.bbtt.mcbot.events.PlayerLeaveEvent;
import xin.bbtt.mcbot.events.ServerChangeEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorListenerUuidTest {
    @TempDir
    Path temporaryDirectory;

    private final List<PlayerMonitorListener> listeners = new ArrayList<>();
    private final List<PlayerMonitorService> services = new ArrayList<>();

    @AfterEach
    void tearDown() {
        listeners.forEach(PlayerMonitorListener::close);
        services.forEach(PlayerMonitorService::close);
    }

    @Test
    void periodicRefreshDoesNotRunWhileDisabled() throws Exception {
        TestContext context = context(false, 168, 1_000L);
        GameProfile profile = profile("Disabled", "98465ebe-e619-3b1d-8b25-98352b6abbb9");

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));

        assertFalse(context.resolver.called.await(200, TimeUnit.MILLISECONDS));
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
    }

    @Test
    void enabledNewPlayerRefreshesAndSchedulesDefaultCooldownOnce() throws Exception {
        TestContext context = context(true, 168, 1_000L);
        GameProfile profile = profile("Eligible", "98465ebe-e619-3b1d-8b25-98352b6abbb9");

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));

        assertTrue(context.resolver.called.await(2, TimeUnit.SECONDS));
        waitUntil(() -> context.listener.scheduledUuidCheckCountForTesting() == 1);
        PlayerIdentity identity = context.service.playerIdentity("Eligible").orElseThrow();
        assertEquals(1_000L, identity.uuidLastCheckedAt());
        assertEquals(1_000L, identity.uuidLastWrittenAt());
        assertEquals(1, context.resolver.calls.get());
    }

    @Test
    void unchangedIdentityAdvancesCheckButNotWriteTime() throws Exception {
        AtomicLong now = new AtomicLong(1_000L);
        TestContext context = context(true, 168, now);
        GameProfile profile = profile("Unchanged", "98465ebe-e619-3b1d-8b25-98352b6abbb9");

        context.listener.runUuidCheckForTesting(profile);
        now.set(2_000L);
        context.listener.runUuidCheckForTesting(profile);

        PlayerIdentity identity = context.service.playerIdentity("Unchanged").orElseThrow();
        assertEquals(2_000L, identity.uuidLastCheckedAt());
        assertEquals(1_000L, identity.uuidLastWrittenAt());
    }

    @Test
    void changedServerUuidForcesRefreshInsideCooldown() throws Exception {
        AtomicLong now = new AtomicLong(2_000L);
        TestContext context = context(true, 168, now);
        GameProfile first = profile("Changed", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.service.recordIdentityCheck("Changed", context.resolver.resolve("Changed", first.getId(), null), 1_000L);

        context.listener.onPlayerJoin(new PlayerJoinEvent(first));
        waitUntil(() -> context.listener.scheduledUuidCheckCountForTesting() == 1);
        context.resolver.resetLatch();
        GameProfile changed = profile("Changed", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        context.listener.onPlayerJoin(new PlayerJoinEvent(changed));

        assertTrue(context.resolver.called.await(2, TimeUnit.SECONDS));
        PlayerIdentity identity = context.service.playerIdentity("Changed").orElseThrow();
        assertEquals(changed.getId().toString(), identity.serverUuid());
        assertEquals(2_000L, identity.uuidLastWrittenAt());
    }

    @Test
    void repeatedJoinDoesNotCreateDuplicateUuidTasks() throws Exception {
        TestContext context = context(true, 168, 1_000L);
        GameProfile profile = profile("Duplicate", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.service.recordIdentityCheck("Duplicate", context.resolver.resolve("Duplicate", profile.getId(), null), 1_000L);

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));
        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));

        waitUntil(() -> context.listener.scheduledUuidCheckCountForTesting() == 1);
        assertEquals(1, context.listener.scheduledUuidCheckCountForTesting());
    }

    @Test
    void leaveDisconnectServerSwitchAndShutdownInvalidateUuidTasks() throws Exception {
        TestContext leave = scheduledContext("Leaving");
        GameProfile leaving = profile("Leaving", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        leave.listener.onPlayerLeave(new PlayerLeaveEvent(leaving));
        assertEquals(0, leave.listener.scheduledUuidCheckCountForTesting());

        TestContext disconnect = scheduledContext("Disconnecting");
        disconnect.listener.onDisconnect(new DisconnectEvent(Component.text("network")));
        assertEquals(0, disconnect.listener.scheduledUuidCheckCountForTesting());

        TestContext switchServer = scheduledContext("Switching");
        switchServer.listener.onServerChange(new ServerChangeEvent(Server.Login, Server.Game));
        assertEquals(0, switchServer.listener.scheduledUuidCheckCountForTesting());

        TestContext shutdown = scheduledContext("Shutdown");
        shutdown.settings.setUuidRecordEnable(false);
        shutdown.listener.applyUuidSettingsNow();
        assertEquals(0, shutdown.listener.scheduledUuidCheckCountForTesting());
        shutdown.listener.close();
        listeners.remove(shutdown.listener);
        assertEquals(0, shutdown.listener.scheduledUuidCheckCountForTesting());
    }

    @Test
    void playerLeavingDuringLookupPreventsStaleUuidWrite() throws Exception {
        TestContext context = context(true, 168, 1_000L);
        GameProfile profile = profile("InFlight", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.resolver.pause();

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));
        assertTrue(context.resolver.called.await(2, TimeUnit.SECONDS));
        context.listener.onPlayerLeave(new PlayerLeaveEvent(profile));
        context.resolver.resume();
        assertTrue(context.resolver.finished.await(2, TimeUnit.SECONDS));

        PlayerIdentity identity = context.service.playerIdentity("InFlight").orElseThrow();
        assertEquals(null, identity.serverUuid());
        assertEquals(null, identity.uuidLastWrittenAt());
    }

    @Test
    void rejoinAfterExpiredCooldownRefreshesImmediately() throws Exception {
        long expiredAt = TimeUnit.HOURS.toMillis(169);
        TestContext context = context(true, 168, expiredAt);
        GameProfile profile = profile("Rejoin", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.service.recordIdentityCheck("Rejoin", context.resolver.resolve("Rejoin", profile.getId(), null), 1L);
        context.resolver.calls.set(0);
        context.resolver.resetLatch();

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));

        assertTrue(context.resolver.called.await(2, TimeUnit.SECONDS));
        assertEquals(1, context.resolver.calls.get());
    }

    @Test
    void zeroCooldownCreatesNoRepeatingUuidTask() throws Exception {
        TestContext context = context(true, 0, 1_000L);
        context.listener.onPlayerJoin(new PlayerJoinEvent(
                profile("Zero", "98465ebe-e619-3b1d-8b25-98352b6abbb9")));

        assertFalse(context.resolver.called.await(200, TimeUnit.MILLISECONDS));
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
    }

    private TestContext scheduledContext(String name) throws Exception {
        TestContext context = context(true, 168, 1_000L);
        GameProfile profile = profile(name, "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.service.recordIdentityCheck(name, context.resolver.resolve(name, profile.getId(), null), 1_000L);
        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));
        waitUntil(() -> context.listener.scheduledUuidCheckCountForTesting() == 1);
        return context;
    }

    private TestContext context(boolean enabled, int cooldown, long now) throws Exception {
        return context(enabled, cooldown, new AtomicLong(now));
    }

    private TestContext context(boolean enabled, int cooldown, AtomicLong now) throws Exception {
        Path directory = temporaryDirectory.resolve("context-" + services.size());
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        settings.setScanOnJoin(false);
        settings.setStatEnabled(false);
        settings.setUuidRecordEnable(enabled);
        settings.setUuidRecordCooldown(cooldown);
        PlayerMonitorService service = new PlayerMonitorService(directory, settings);
        service.initialize();
        services.add(service);
        FakeResolver resolver = new FakeResolver();
        PlayerMonitorListener listener = new PlayerMonitorListener(service,
                new PluginLog(directory.resolve("log")), NOPLogger.NOP_LOGGER, settings, resolver, now::get);
        listener.setGameActiveForTesting(true);
        listeners.add(listener);
        return new TestContext(service, listener, settings, resolver);
    }

    private static GameProfile profile(String name, String uuid) {
        return new GameProfile(UUID.fromString(uuid), name);
    }

    private static void waitUntil(Check condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.get() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.get());
    }

    private interface Check {
        boolean get() throws Exception;
    }

    private record TestContext(PlayerMonitorService service, PlayerMonitorListener listener,
                               MonitorSettingsStore settings,
                               FakeResolver resolver) {
    }

    private static final class FakeResolver implements PlayerIdentityResolver {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch called = new CountDownLatch(1);
        private volatile CountDownLatch finished = new CountDownLatch(1);
        private volatile CountDownLatch release;

        @Override
        public IdentityResolution resolve(String playerName, UUID serverUuid, PlayerIdentity previous) {
            calls.incrementAndGet();
            called.countDown();
            CountDownLatch blocker = release;
            if (blocker != null) {
                try {
                    blocker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            String server = serverUuid.toString();
            IdentityResolution resolution = new IdentityResolution(server,
                    UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes()).toString(),
                    IdentityResolution.Lookup.found(server), IdentityResolution.Lookup.notFound(),
                    IdentityType.PREMIUM, true);
            finished.countDown();
            return resolution;
        }

        private void resetLatch() {
            called = new CountDownLatch(1);
            finished = new CountDownLatch(1);
        }

        private void pause() {
            release = new CountDownLatch(1);
        }

        private void resume() {
            release.countDown();
            release = null;
        }
    }
}
