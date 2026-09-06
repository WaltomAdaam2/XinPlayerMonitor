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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        awaitUuidTasks(context);
        assertEquals(0, context.resolver.calls.get());
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
    }

    @Test
    void enabledNewPlayerRefreshesAndSchedulesDefaultCooldownOnce() throws Exception {
        TestContext context = context(true, 168, 1_000L);
        GameProfile profile = profile("Eligible", "98465ebe-e619-3b1d-8b25-98352b6abbb9");

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));

        assertTrue(context.resolver.called.await(2, TimeUnit.SECONDS));
        awaitUuidTasks(context);
        assertEquals(1, context.listener.scheduledUuidCheckCountForTesting());
        StoredPlayerIdentity identity = context.service.playerIdentity("Eligible").orElseThrow();
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

        StoredPlayerIdentity identity = context.service.playerIdentity("Unchanged").orElseThrow();
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
        awaitUuidTasks(context);
        context.resolver.resetLatch();
        GameProfile changed = profile("Changed", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        context.listener.onPlayerJoin(new PlayerJoinEvent(changed));

        assertTrue(context.resolver.called.await(2, TimeUnit.SECONDS));
        awaitUuidTasks(context);
        StoredPlayerIdentity identity = context.service.playerIdentity("Changed").orElseThrow();
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

        awaitUuidTasks(context);
        assertEquals(1, context.listener.scheduledUuidCheckCountForTesting());
        assertEquals(1, context.resolver.calls.get(), "the seeded identity must not be queried again");
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
        awaitUuidTasks(context);

        StoredPlayerIdentity identity = context.service.playerIdentity("InFlight").orElseThrow();
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
        awaitUuidTasks(context);
        assertEquals(1, context.resolver.calls.get());
    }

    @Test
    void zeroCooldownCreatesNoRepeatingUuidTask() throws Exception {
        TestContext context = context(true, 0, 1_000L);
        GameProfile profile = profile("Zero", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.service.recordIdentityCheck("Zero", context.resolver.resolve("Zero", profile.getId(), null), 1_000L);
        context.resolver.calls.set(0);

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));
        awaitUuidTasks(context);

        assertEquals(0, context.resolver.calls.get());
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
    }

    @Test
    void zeroCooldownStillRefreshesChangedServerUuidExactlyOnce() throws Exception {
        TestContext context = context(true, 0, 2_000L);
        GameProfile first = profile("ZeroChanged", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.service.recordIdentityCheck("ZeroChanged",
                context.resolver.resolve("ZeroChanged", first.getId(), null), 1_000L);
        context.resolver.calls.set(0);
        context.listener.onPlayerJoin(new PlayerJoinEvent(first));
        awaitUuidTasks(context);
        assertEquals(0, context.resolver.calls.get());

        GameProfile changed = profile("ZeroChanged", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        context.listener.onPlayerJoin(new PlayerJoinEvent(changed));
        awaitUuidTasks(context);

        StoredPlayerIdentity identity = context.service.playerIdentity("ZeroChanged").orElseThrow();
        assertEquals(changed.getId().toString(), identity.serverUuid());
        assertEquals(2_000L, identity.uuidLastCheckedAt());
        assertEquals(2_000L, identity.uuidLastWrittenAt());
        assertEquals(1, context.resolver.calls.get());
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());

        context.listener.onPlayerJoin(new PlayerJoinEvent(changed));
        context.listener.onPlayerJoin(new PlayerJoinEvent(changed));
        awaitUuidTasks(context);
        assertEquals(1, context.resolver.calls.get(), "unchanged joins must not restart a zero-cooldown loop");
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
    }

    @Test
    void zeroCooldownRecordsMissingIdentityOnceWithoutPeriodicRefresh() throws Exception {
        TestContext context = context(true, 0, 1_000L);
        GameProfile profile = profile("ZeroMissing", "98465ebe-e619-3b1d-8b25-98352b6abbb9");

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));
        awaitUuidTasks(context);

        assertEquals(profile.getId().toString(),
                context.service.playerIdentity("ZeroMissing").orElseThrow().serverUuid());
        assertEquals(1, context.resolver.calls.get());
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));
        awaitUuidTasks(context);
        assertEquals(1, context.resolver.calls.get());
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
    }

    @Test
    void publicResolveReturnsRequiredRecordAndCoalescesSameName() throws Exception {
        TestContext context = context(false, 168, 1_000L);
        GameProfile profile = profile("PublicApi", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.listener.markOnlineForTesting(profile);
        context.resolver.pause();

        CompletableFuture<PlayerIdentity> first = context.listener.resolve("PublicApi");
        CompletableFuture<PlayerIdentity> second = context.listener.resolve("publicapi");

        assertSame(first, second);
        assertTrue(context.resolver.called.await(2, TimeUnit.SECONDS));
        context.resolver.resume();
        PlayerIdentity identity = first.get(2, TimeUnit.SECONDS);
        assertEquals("PublicApi", identity.name());
        assertEquals(profile.getId(), identity.serverUuid());
        assertEquals(PlayerIdentityType.PREMIUM, identity.type());
        assertTrue(identity.online());
        assertEquals(1, context.resolver.calls.get());
    }

    @Test
    void publicResolveRejectsBlankNameAndDisabledPlugin() {
        XinPlayerMonitor plugin = new XinPlayerMonitor();

        assertThrows(CompletionException.class, () -> plugin.resolve("Alice").join());
        TestContext context;
        try {
            context = context(false, 168, 1_000L);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        assertThrows(CompletionException.class, () -> context.listener.resolve(" ").join());
    }

    @Test
    void completedPublicResolvesDoNotRemainInTheInFlightMap() throws Exception {
        TestContext context = context(false, 168, 1_000L);
        GameProfile profile = profile("Completed", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.listener.markOnlineForTesting(profile);

        CompletableFuture<PlayerIdentity> first = context.listener.resolve("Completed");
        PlayerIdentity identity = first.get(2, TimeUnit.SECONDS);
        CompletableFuture<PlayerIdentity> next = context.listener.resolve("Completed");

        assertEquals(identity, next.get(2, TimeUnit.SECONDS));
        assertNotSame(first, next, "completed requests must not stay cached as in-flight work");
    }

    @Test
    void externalIdentityConcurrencyIsBoundedToFour() throws Exception {
        Path directory = temporaryDirectory.resolve("bounded-concurrency");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        settings.setUuidRecordEnable(false);
        PlayerMonitorService service = new PlayerMonitorService(directory, settings);
        service.initialize();
        services.add(service);
        ConcurrencyResolver resolver = new ConcurrencyResolver();
        PlayerMonitorListener listener = new PlayerMonitorListener(service,
                new PluginLog(directory.resolve("log")), NOPLogger.NOP_LOGGER, settings, resolver,
                System::currentTimeMillis);
        listener.setGameActiveForTesting(true);
        listeners.add(listener);
        List<CompletableFuture<PlayerIdentity>> futures = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            String name = "Concurrent" + index;
            GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name);
            listener.markOnlineForTesting(profile);
            futures.add(listener.resolve(name));
        }

        assertTrue(resolver.fourStarted.await(2, TimeUnit.SECONDS));
        assertEquals(4, resolver.maximum.get());
        resolver.release.countDown();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertTrue(resolver.maximum.get() <= 4);
    }

    private TestContext scheduledContext(String name) throws Exception {
        TestContext context = context(true, 168, 1_000L);
        GameProfile profile = profile(name, "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.service.recordIdentityCheck(name, context.resolver.resolve(name, profile.getId(), null), 1_000L);
        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));
        awaitUuidTasks(context);
        assertEquals(1, context.listener.scheduledUuidCheckCountForTesting());
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

    private static void awaitUuidTasks(TestContext context) throws Exception {
        context.listener.awaitUuidTasksForTesting().get(2, TimeUnit.SECONDS);
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
        public IdentityResolution resolve(String playerName, UUID serverUuid, StoredPlayerIdentity previous) {
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
                    PlayerIdentityType.PREMIUM, true);
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

    private static final class ConcurrencyResolver implements PlayerIdentityResolver {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();
        private final CountDownLatch fourStarted = new CountDownLatch(4);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public IdentityResolution resolve(String playerName, UUID serverUuid, StoredPlayerIdentity previous) {
            int concurrent = active.incrementAndGet();
            maximum.accumulateAndGet(concurrent, Math::max);
            fourStarted.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            String value = serverUuid.toString();
            return new IdentityResolution(value, value,
                    IdentityResolution.Lookup.notChecked(), IdentityResolution.Lookup.notChecked(),
                    PlayerIdentityType.OFFLINE, true);
        }
    }
}
