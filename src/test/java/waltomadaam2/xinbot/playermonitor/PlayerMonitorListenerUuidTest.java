package waltomadaam2.xinbot.playermonitor;

import net.kyori.adventure.text.Component;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.events.DisconnectEvent;
import xin.bbtt.mcbot.events.PlayerJoinEvent;
import xin.bbtt.mcbot.events.PlayerLeaveEvent;
import xin.bbtt.mcbot.events.ServerChangeEvent;

import java.io.IOException;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Bot.INSTANCE.players.clear();
    }

    @Test
    void disabledManualUuidScanKeepsRetryForced() throws Exception {
        TestContext context = context(false, 0, 1_000L);
        GameProfile profile = profile("ManualDisabled", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        Bot.INSTANCE.players.put(profile.getId(), profile);
        context.resolver.failNextLookup();

        assertEquals(1, context.listener.scanAllOnlineUuidPlayers());
        waitUntil(() -> context.resolver.calls.get() == 1
                && context.listener.scheduledUuidCheckCountForTesting() == 1);
        assertTrue(context.listener.scheduledUuidCheckForcedForTesting(profile.getName()));
        assertTrue(context.listener.manualUuidScanActiveForTesting());
        assertEquals(-2, context.listener.scanAllOnlineUuidPlayers());

        context.listener.runScheduledUuidCheckForTesting(profile.getName());
        waitUntil(() -> context.resolver.calls.get() == 2
                && !context.listener.manualUuidScanActiveForTesting());

        assertEquals(profile.getId().toString(),
                context.service.playerIdentity(profile.getName()).orElseThrow().serverUuid());
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
    }

    @Test
    void manualUuidFailuresStopAfterThreeAttemptsAndAllowLaterScan() throws Exception {
        TestContext context = context(false, 0, 1_000L);
        GameProfile profile = profile("ManualRetryLimit", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        Bot.INSTANCE.players.put(profile.getId(), profile);
        context.resolver.failNextLookups(3);

        assertEquals(1, context.listener.scanAllOnlineUuidPlayers());
        for (int attempt = 2; attempt <= 3; attempt++) {
            int expectedAttempt = attempt;
            int expectedCalls = attempt - 1;
            waitUntil(() -> context.resolver.calls.get() == expectedCalls
                    && context.listener.scheduledUuidCheckAttemptForTesting(profile.getName()) == expectedAttempt);
            assertTrue(context.listener.scheduledUuidCheckForcedForTesting(profile.getName()));
            assertTrue(context.listener.manualUuidScanActiveForTesting());
            context.listener.runScheduledUuidCheckForTesting(profile.getName());
        }
        waitUntil(() -> context.resolver.calls.get() == 3
                && !context.listener.manualUuidScanActiveForTesting());

        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
        assertEquals(1, context.listener.scanAllOnlineUuidPlayers());
        waitUntil(() -> context.resolver.calls.get() == 4
                && !context.listener.manualUuidScanActiveForTesting());
        assertEquals(profile.getId().toString(),
                context.service.playerIdentity(profile.getName()).orElseThrow().serverUuid());
    }

    @Test
    void automaticUuidFailuresStopAfterThreeAttemptsWithoutBecomingForced() throws Exception {
        TestContext context = context(true, 168, 1_000L);
        GameProfile profile = profile("AutomaticRetryLimit", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.resolver.failNextLookups(3);

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));
        for (int attempt = 2; attempt <= 3; attempt++) {
            int expectedAttempt = attempt;
            int expectedCalls = attempt - 1;
            waitUntil(() -> context.resolver.calls.get() == expectedCalls
                    && context.listener.scheduledUuidCheckAttemptForTesting(profile.getName()) == expectedAttempt);
            assertFalse(context.listener.scheduledUuidCheckForcedForTesting(profile.getName()));
            context.listener.runScheduledUuidCheckForTesting(profile.getName());
        }
        waitUntil(() -> context.resolver.calls.get() == 3
                && context.listener.scheduledUuidCheckCountForTesting() == 0);

        assertFalse(context.listener.manualUuidScanActiveForTesting());
    }

    @Test
    void manualUuidRetryIgnoresActiveNormalCooldown() throws Exception {
        TestContext context = context(true, 168, 1_000L);
        GameProfile profile = profile("ManualCooldown", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.service.recordIdentityCheck(profile.getName(),
                context.resolver.resolve(profile.getName(), profile.getId(), null), 1_000L);
        context.resolver.calls.set(0);
        context.resolver.failNextLookup();
        Bot.INSTANCE.players.put(profile.getId(), profile);

        assertEquals(1, context.listener.scanAllOnlineUuidPlayers());
        waitUntil(() -> context.resolver.calls.get() == 1
                && context.listener.scheduledUuidCheckCountForTesting() == 1);
        assertTrue(context.listener.scheduledUuidCheckForcedForTesting(profile.getName()));

        context.listener.runScheduledUuidCheckForTesting(profile.getName());
        waitUntil(() -> context.resolver.calls.get() == 2
                && !context.listener.manualUuidScanActiveForTesting());

        assertEquals(1, context.listener.scheduledUuidCheckCountForTesting());
        assertFalse(context.listener.scheduledUuidCheckForcedForTesting(profile.getName()),
                "the next periodic refresh must return to automatic semantics");
    }

    @Test
    void serverUuidChangeDuringManualScanKeepsReplacementForced() throws Exception {
        TestContext context = context(false, 168, 1_000L);
        GameProfile initial = profile("ManualChanged", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        GameProfile changed = profile("ManualChanged", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        Bot.INSTANCE.players.put(initial.getId(), initial);
        context.resolver.pause();

        assertEquals(1, context.listener.scanAllOnlineUuidPlayers());
        assertTrue(context.resolver.called.await(2, TimeUnit.SECONDS));
        Bot.INSTANCE.players.clear();
        Bot.INSTANCE.players.put(changed.getId(), changed);
        context.listener.markOnlineForTesting(changed);
        assertTrue(context.listener.scheduledUuidCheckForcedForTesting(changed.getName()));
        assertEquals(changed.getId(), context.listener.scheduledServerUuidForTesting(changed.getName()));
        assertEquals(changed.getId(), context.listener.onlineServerUuidForTesting(changed.getName()));
        assertTrue(context.listener.manualUuidScanActiveForTesting());

        context.listener.runScheduledUuidCheckForTesting(changed.getName());
        context.resolver.resume();
        waitUntil(() -> context.resolver.calls.get() >= 2);
        waitUntil(() -> !context.listener.manualUuidScanActiveForTesting());

        assertEquals(2, context.resolver.calls.get());
        assertEquals(changed.getId().toString(),
                context.service.playerIdentity(changed.getName()).orElseThrow().serverUuid());
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
    }

    @Test
    void manualForceDoesNotLeakIntoLaterAutomaticRefresh() throws Exception {
        TestContext context = context(false, 168, 1_000L);
        GameProfile manual = profile("NoForceLeak", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        Bot.INSTANCE.players.put(manual.getId(), manual);

        assertEquals(1, context.listener.scanAllOnlineUuidPlayers());
        waitUntil(() -> context.resolver.calls.get() == 1
                && !context.listener.manualUuidScanActiveForTesting());

        GameProfile changed = profile("NoForceLeak", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        context.listener.onPlayerJoin(new PlayerJoinEvent(changed));

        assertEquals(1, context.resolver.calls.get());
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
    }

    @Test
    void leaveDisconnectServerSwitchAndShutdownCancelManualUuidRetries() throws Exception {
        TestContext leave = manualRetryContext("ManualLeave");
        leave.listener.onPlayerLeave(new PlayerLeaveEvent(
                profile("ManualLeave", "98465ebe-e619-3b1d-8b25-98352b6abbb9")));
        assertManualRetryCancelled(leave, "ManualLeave");

        TestContext disconnect = manualRetryContext("ManualDisconnect");
        disconnect.listener.onDisconnect(new DisconnectEvent(Component.text("network")));
        assertManualRetryCancelled(disconnect, "ManualDisconnect");

        TestContext switchServer = manualRetryContext("ManualSwitch");
        switchServer.listener.onServerChange(new ServerChangeEvent(Server.Login, Server.Game));
        assertManualRetryCancelled(switchServer, "ManualSwitch");

        TestContext shutdown = manualRetryContext("ManualShutdown");
        shutdown.listener.close();
        listeners.remove(shutdown.listener);
        assertManualRetryCancelled(shutdown, "ManualShutdown");
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
    void automaticUuidRefreshStillWaitsForActiveCooldown() throws Exception {
        TestContext context = context(true, 168, 1_000L);
        GameProfile profile = profile("AutomaticCooldown", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        context.service.recordIdentityCheck(profile.getName(),
                context.resolver.resolve(profile.getName(), profile.getId(), null), 1_000L);
        context.resolver.calls.set(0);

        context.listener.onPlayerJoin(new PlayerJoinEvent(profile));
        awaitUuidTasks(context);

        assertEquals(0, context.resolver.calls.get());
        assertEquals(1, context.listener.scheduledUuidCheckCountForTesting());
        assertFalse(context.listener.scheduledUuidCheckForcedForTesting(profile.getName()));
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
    void changedIdentityEmitsOneColoredUuidRecordWithoutRepeatSpam() throws Exception {
        Path directory = temporaryDirectory.resolve("uuid-record-log");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        settings.setUuidRecordEnable(true);
        settings.setUuidRecordCooldown(0);
        PlayerMonitorService service = new PlayerMonitorService(directory, settings);
        service.initialize();
        services.add(service);
        LoggerContext context = new LoggerContext();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        ch.qos.logback.classic.Logger logger = context.getLogger("uuid-record-log");
        logger.addAppender(appender);
        PlayerMonitorListener listener = new PlayerMonitorListener(service,
                new PluginLog(directory.resolve("log")), logger, settings, new FakeResolver(), () -> 1_000L);
        listener.setGameActiveForTesting(true);
        listeners.add(listener);
        GameProfile profile = profile("Recorded", "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        try {
            listener.onPlayerJoin(new PlayerJoinEvent(profile));
            awaitUuidTasks(new TestContext(service, listener, settings, null));
            List<String> records = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Player uuid recorded for ")).toList();
            assertEquals(List.of("Player uuid recorded for \u001B[38;5;215mRecorded\u001B[0m"
                    + ": total=1, succeeded=1, failed=0, skipped=0"), records);

            listener.onPlayerJoin(new PlayerJoinEvent(profile));
            awaitUuidTasks(new TestContext(service, listener, settings, null));
            assertEquals(1, appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Player uuid recorded for ")).count());
        } finally {
            context.stop();
        }
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

    private TestContext manualRetryContext(String name) throws Exception {
        Bot.INSTANCE.players.clear();
        TestContext context = context(false, 168, 1_000L);
        GameProfile profile = profile(name, "98465ebe-e619-3b1d-8b25-98352b6abbb9");
        Bot.INSTANCE.players.put(profile.getId(), profile);
        context.resolver.failNextLookup();
        assertEquals(1, context.listener.scanAllOnlineUuidPlayers());
        waitUntil(() -> context.resolver.calls.get() == 1
                && context.listener.scheduledUuidCheckCountForTesting() == 1);
        assertTrue(context.listener.scheduledUuidCheckForcedForTesting(name));
        return context;
    }

    private static void assertManualRetryCancelled(TestContext context, String playerName) throws Exception {
        waitUntil(() -> !context.listener.manualUuidScanActiveForTesting());
        assertEquals(0, context.listener.scheduledUuidCheckCountForTesting());
        context.listener.runScheduledUuidCheckForTesting(playerName);
        assertEquals(1, context.resolver.calls.get());
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

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not met before timeout");
            }
            Thread.sleep(10L);
        }
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
        private final AtomicInteger failuresRemaining = new AtomicInteger();

        @Override
        public IdentityResolution resolve(String playerName, UUID serverUuid, StoredPlayerIdentity previous)
                throws IOException {
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
            if (failuresRemaining.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                finished.countDown();
                throw new IOException("test lookup failure");
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

        private void failNextLookup() {
            failNextLookups(1);
        }

        private void failNextLookups(int count) {
            failuresRemaining.addAndGet(count);
            resetLatch();
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
