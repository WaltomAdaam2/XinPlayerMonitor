package waltomadaam2.xinbot.playermonitor;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.events.DisconnectEvent;
import xin.bbtt.mcbot.events.PlayerJoinEvent;
import xin.bbtt.mcbot.events.SendCommandEvent;
import xin.bbtt.mcbot.events.ServerChangeEvent;
import xin.bbtt.mcbot.events.SystemChatMessageEvent;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorListenerBatchTest {
    @TempDir
    Path temporaryDirectory;

    private final List<PlayerMonitorListener> listeners = new ArrayList<>();
    private final List<PlayerMonitorService> services = new ArrayList<>();
    private final List<LoggerContext> loggerContexts = new ArrayList<>();

    @AfterEach
    void tearDown() {
        listeners.forEach(PlayerMonitorListener::close);
        services.forEach(PlayerMonitorService::close);
        loggerContexts.forEach(LoggerContext::stop);
        Bot.INSTANCE.players.clear();
    }

    @Test
    void stableRosterRequiresFiveHundredMillisecondsAndTimeoutHandlesEmptyOrNonEmpty() {
        PlayerMonitorListener.StableRosterWait wait = new PlayerMonitorListener.StableRosterWait(0L);
        assertFalse(wait.sample(Set.of("login"), 100L, 500L, 5_000L));
        assertFalse(wait.sample(Set.of("game"), 200L, 500L, 5_000L));
        assertFalse(wait.sample(Set.of("game"), 699L, 500L, 5_000L));
        assertTrue(wait.sample(Set.of("game"), 700L, 500L, 5_000L));

        PlayerMonitorListener.StableRosterWait nonEmptyTimeout =
                new PlayerMonitorListener.StableRosterWait(0L);
        assertTrue(nonEmptyTimeout.sample(Set.of("slow"), 5_000L, 500L, 5_000L));
        PlayerMonitorListener.StableRosterWait emptyTimeout =
                new PlayerMonitorListener.StableRosterWait(0L);
        assertTrue(emptyTimeout.sample(Set.of(), 5_000L, 500L, 5_000L));
    }

    @Test
    void timedOutEmptyRosterDoesNotCreateEntryBatch() throws Exception {
        TestContext context = context(System::currentTimeMillis, ignored -> {
        }, NOPLogger.NOP_LOGGER);
        context.settings.setScanOnEntry(true);
        context.listener.setGameActiveForTesting(true);
        Bot.INSTANCE.players.clear();

        context.listener.pollTimedOutEntryRosterForTesting();

        assertEquals(0, context.listener.activeStatBatchTotalForTesting());
    }

    @Test
    void staleLoginRosterDoesNotLeakAndServerSwitchInvalidatesDelayedEntryScan() throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        CountDownLatch sent = new CountDownLatch(1);
        TestContext context = context(now::get, command -> {
            contextCommands.add(command);
            sent.countDown();
        },
                NOPLogger.NOP_LOGGER);
        context.settings.setStatEnabled(true);
        context.settings.setScanOnEntry(true);
        GameProfile stale = profile("LoginStale");
        GameProfile game = profile("GamePlayer");
        Bot.INSTANCE.players.put(stale.getId(), stale);
        context.listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        long generation = context.listener.gameGenerationForTesting();
        context.listener.pollEntryRosterForTesting(generation);
        now.addAndGet(100L);
        Bot.INSTANCE.players.clear();
        Bot.INSTANCE.players.put(game.getId(), game);
        context.listener.onPlayerJoin(new PlayerJoinEvent(game));
        context.listener.pollEntryRosterForTesting(generation);
        now.addAndGet(499L);
        context.listener.pollEntryRosterForTesting(generation);
        assertEquals(0, context.listener.activeStatBatchTotalForTesting());
        assertTrue(contextCommands.isEmpty());

        now.incrementAndGet();
        context.listener.pollEntryRosterForTesting(generation);
        assertTrue(sent.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("stat GamePlayer"), contextCommands);

        context.listener.onServerChange(new ServerChangeEvent(Server.Login, Server.Game));
        int before = contextCommands.size();
        context.listener.onPlayerJoin(new PlayerJoinEvent(stale));
        now.addAndGet(700L);
        context.listener.pollEntryRosterForTesting(generation);
        assertEquals(before, contextCommands.size());
        assertEquals(0, context.listener.activeStatBatchTotalForTesting());
    }

    private final List<String> contextCommands = new CopyOnWriteArrayList<>();

    @Test
    void joinsDuringEntryWaitResetStabilityAndJoinTheAcceptedSnapshot() throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        CountDownLatch sent = new CountDownLatch(2);
        List<String> commands = new CopyOnWriteArrayList<>();
        TestContext context = context(now::get, command -> {
            commands.add(command);
            sent.countDown();
        }, NOPLogger.NOP_LOGGER);
        context.settings.setScanOnEntry(true);
        context.settings.setStatSendIntervalMillis(1);
        context.listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        long generation = context.listener.gameGenerationForTesting();

        GameProfile first = profile("InitialRoster");
        Bot.INSTANCE.players.put(first.getId(), first);
        context.listener.onPlayerJoin(new PlayerJoinEvent(first));
        context.listener.pollEntryRosterForTesting(generation);
        assertEquals(0, context.listener.activeStatBatchTotalForTesting());

        now.addAndGet(499L);
        GameProfile joining = profile("StabilizingJoin");
        Bot.INSTANCE.players.put(joining.getId(), joining);
        context.listener.onPlayerJoin(new PlayerJoinEvent(joining));
        context.listener.pollEntryRosterForTesting(generation);
        now.addAndGet(499L);
        context.listener.pollEntryRosterForTesting(generation);
        assertEquals(0, context.listener.activeStatBatchTotalForTesting());
        assertTrue(commands.isEmpty());

        now.incrementAndGet();
        context.listener.pollEntryRosterForTesting(generation);
        assertTrue(sent.await(2, TimeUnit.SECONDS));
        assertEquals(Set.of("stat InitialRoster", "stat StabilizingJoin"), Set.copyOf(commands));
        assertEquals(2, context.listener.activeStatBatchTotalForTesting());
    }

    @Test
    void delayedJoinFromPreviousGenerationCannotEnqueueInTheNextGame() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        TestContext context = context(System::currentTimeMillis, ignored -> sends.incrementAndGet(),
                NOPLogger.NOP_LOGGER, playerName -> {
                    lookupStarted.countDown();
                    try {
                        assertTrue(releaseLookup.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                });
        context.listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        CompletableFuture<Void> join = CompletableFuture.runAsync(() ->
                context.listener.onPlayerJoin(new PlayerJoinEvent(profile("StaleJoin"))));
        try {
            assertTrue(lookupStarted.await(2, TimeUnit.SECONDS));
            context.listener.onDisconnect(new DisconnectEvent(Component.text("network")));
            context.listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        } finally {
            releaseLookup.countDown();
        }
        join.get(2, TimeUnit.SECONDS);

        assertEquals(0, context.listener.activeStatBatchTotalForTesting());
        assertEquals(0, context.listener.statScanStatus().onlinePlayers());
        assertEquals(0, sends.get());
    }

    @Test
    void staleEntryPollCannotCancelTheNextGenerationsStabilization() throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        CountDownLatch sent = new CountDownLatch(1);
        TestContext context = context(now::get, ignored -> sent.countDown(), NOPLogger.NOP_LOGGER);
        context.settings.setScanOnEntry(true);
        context.listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        long oldGeneration = context.listener.gameGenerationForTesting();
        context.listener.onDisconnect(new DisconnectEvent(Component.text("network")));
        context.listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        long generation = context.listener.gameGenerationForTesting();
        GameProfile player = profile("NewGeneration");
        Bot.INSTANCE.players.put(player.getId(), player);

        context.listener.pollEntryRosterForTesting(oldGeneration);
        context.listener.onPlayerJoin(new PlayerJoinEvent(player));
        assertEquals(0, context.listener.activeStatBatchTotalForTesting());
        context.listener.pollEntryRosterForTesting(generation);
        now.addAndGet(500L);
        context.listener.pollEntryRosterForTesting(generation);

        assertTrue(sent.await(2, TimeUnit.SECONDS));
        assertEquals(1, context.listener.activeStatBatchTotalForTesting());
    }

    @Test
    void batchAddsOnlyNeverRecordedLaterJoinersAndManualMergeDeduplicates() throws Exception {
        TestContext context = context(System::currentTimeMillis, ignored -> {
        }, NOPLogger.NOP_LOGGER);
        context.listener.setGameActiveForTesting(true);
        GameProfile initial = profile("Initial");
        context.listener.markOnlineForTesting(initial);
        context.listener.queueStatScanForTesting(List.of(initial), false);

        GameProfile newPlayer = profile("NeverRecorded");
        context.listener.onPlayerJoin(new PlayerJoinEvent(newPlayer));
        assertEquals(2, context.listener.activeStatBatchTotalForTesting());

        GameProfile known = profile("AlreadyRecorded");
        recordStat(context.service, known.getName(), System.currentTimeMillis());
        context.listener.onPlayerJoin(new PlayerJoinEvent(known));
        assertEquals(2, context.listener.activeStatBatchTotalForTesting());

        Bot.INSTANCE.players.put(initial.getId(), initial);
        Bot.INSTANCE.players.put(newPlayer.getId(), newPlayer);
        Bot.INSTANCE.players.put(known.getId(), known);
        context.listener.scanAllOnlinePlayers();
        context.listener.scanAllOnlinePlayers();
        assertEquals(3, context.listener.activeStatBatchTotalForTesting());
    }

    @Test
    void prioritiesRemainNewThenExpiredThenCooldownAndManualRaisesUnfinishedTargets() throws Exception {
        AtomicLong now = new AtomicLong(TimeUnit.DAYS.toMillis(10));
        TestContext context = context(now::get, ignored -> {
        }, NOPLogger.NOP_LOGGER);
        context.settings.setStatCooldownHours(24);
        context.listener.setGameActiveForTesting(true);
        GameProfile fresh = profile("Fresh");
        GameProfile expired = profile("Expired");
        GameProfile cooldown = profile("Cooldown");
        recordStat(context.service, expired.getName(), now.get() - TimeUnit.HOURS.toMillis(25));
        recordStat(context.service, cooldown.getName(), now.get() - TimeUnit.HOURS.toMillis(1));
        for (GameProfile profile : List.of(fresh, expired, cooldown)) {
            context.listener.markOnlineForTesting(profile);
        }

        context.listener.queueStatScanForTesting(List.of(fresh, expired), true);
        int freshAutomatic = context.listener.statPriorityForTesting("Fresh");
        int expiredAutomatic = context.listener.statPriorityForTesting("Expired");
        context.listener.queueStatScanForTesting(List.of(fresh, expired, cooldown), false);

        int freshManual = context.listener.statPriorityForTesting("Fresh");
        int expiredManual = context.listener.statPriorityForTesting("Expired");
        int cooldownManual = context.listener.statPriorityForTesting("Cooldown");
        assertTrue(freshManual > expiredManual && expiredManual > cooldownManual);
        assertEquals(freshAutomatic + 1, freshManual);
        assertEquals(expiredAutomatic + 1, expiredManual);
    }

    @Test
    void automaticCooldownSkipIsCountedWithoutSending() throws Exception {
        AtomicLong now = new AtomicLong(TimeUnit.DAYS.toMillis(3));
        AtomicInteger sends = new AtomicInteger();
        TestContext context = context(now::get, ignored -> sends.incrementAndGet(), NOPLogger.NOP_LOGGER);
        context.settings.setStatCooldownHours(24);
        context.listener.setGameActiveForTesting(true);
        GameProfile profile = profile("CooldownSkip");
        recordStat(context.service, profile.getName(), now.get());
        context.listener.markOnlineForTesting(profile);

        PlayerMonitorListener.StatScanResult result =
                context.listener.queueStatScanForTesting(List.of(profile), true);

        assertEquals(0, result.queued());
        assertEquals(1, result.cooldownSkipped());
        assertEquals(0, sends.get());
    }

    @Test
    void successfulBatchEmitsExactlyOneYellowCompletion() throws Exception {
        LoggerCapture capture = loggerCapture("stat-completion");
        CountDownLatch sent = new CountDownLatch(1);
        TestContext context = context(System::currentTimeMillis, ignored -> sent.countDown(), capture.logger);
        context.listener.setGameActiveForTesting(true);
        GameProfile profile = profile("Complete");
        context.listener.markOnlineForTesting(profile);
        context.listener.queueStatScanForTesting(List.of(profile), false);
        assertTrue(sent.await(2, TimeUnit.SECONDS));
        context.listener.onSendCommand(new SendCommandEvent("stat Complete"));

        SystemChatMessageEvent response = new SystemChatMessageEvent(Component.text(statText("Complete")), false);
        context.listener.onSystemChat(response);
        waitUntil(() -> completionMessages(capture).size() == 1);
        context.listener.onSystemChat(response);

        assertEquals(List.of("\u001B[93mStat scan completed: total=1, succeeded=1, failed=0, skipped=0\u001B[0m"),
                completionMessages(capture));
    }

    @Test
    void cooldownExpiryRescansOnlinePlayerButZeroCooldownDoesNotLoop() throws Exception {
        AtomicLong now = new AtomicLong(3_601_000L);
        CountDownLatch sent = new CountDownLatch(1);
        TestContext context = context(now::get, ignored -> sent.countDown(), NOPLogger.NOP_LOGGER);
        context.settings.setStatCooldownHours(1);
        context.listener.setGameActiveForTesting(true);
        GameProfile profile = profile("DueAgain");
        context.listener.markOnlineForTesting(profile);
        recordStat(context.service, profile.getName(), 1_000L);

        context.listener.scheduleNextStatCheckForTesting(profile.getName(), 1_000L);
        assertTrue(sent.await(2, TimeUnit.SECONDS));

        context.settings.setStatCooldownHours(0);
        context.listener.applyStatSettingsNow();
        context.listener.scheduleNextStatCheckForTesting(profile.getName(), now.get());
        assertEquals(0, context.listener.scheduledStatCheckCountForTesting());
    }

    @Test
    void retryDequeuedBeforeTerminalCannotSendAndDisconnectSuppressesCompletion() throws Exception {
        LoggerCapture capture = loggerCapture("stat-race");
        CountDownLatch dequeued = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch validationReached = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        TestContext context = context(System::currentTimeMillis, ignored -> sends.incrementAndGet(), capture.logger);
        context.settings.setStatAttempts(1);
        context.listener.setGameActiveForTesting(true);
        GameProfile profile = profile("Race");
        context.listener.markOnlineForTesting(profile);
        context.listener.setBeforeStatSendForTesting(() -> {
            dequeued.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            validationReached.countDown();
        });
        context.listener.queueStatScanForTesting(List.of(profile), false);
        assertTrue(dequeued.await(2, TimeUnit.SECONDS));

        context.listener.evaluateStatAttemptForTesting("Race", 1);
        release.countDown();
        assertTrue(validationReached.await(2, TimeUnit.SECONDS));
        assertEquals(0, sends.get());
        capture.appender.list.clear();

        context.listener.setBeforeStatSendForTesting(() -> {
        });
        GameProfile cancel = profile("CancelBatch");
        context.listener.markOnlineForTesting(cancel);
        context.listener.queueStatScanForTesting(List.of(cancel), false);
        context.listener.onDisconnect(new DisconnectEvent(Component.text("network")));
        assertTrue(completionMessages(capture).isEmpty());
    }

    private TestContext context(java.util.function.LongSupplier clock, Consumer<String> sender, Logger logger)
            throws Exception {
        return context(clock, sender, logger, null);
    }

    private TestContext context(java.util.function.LongSupplier clock, Consumer<String> sender, Logger logger,
                                Consumer<String> beforeLatestStat) throws Exception {
        Path directory = temporaryDirectory.resolve("context-" + services.size());
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        settings.setScanOnEntry(false);
        settings.setScanOnJoin(true);
        settings.setStatEnabled(true);
        settings.setUuidRecordEnable(false);
        PlayerRepository repository = new SQLitePlayerRecordStore(directory, settings.database());
        PlayerRepository source = repository;
        if (beforeLatestStat != null) {
            repository = (PlayerRepository) Proxy.newProxyInstance(PlayerRepository.class.getClassLoader(),
                    new Class<?>[]{PlayerRepository.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("latestStat")) {
                            beforeLatestStat.accept((String) arguments[0]);
                        }
                        try {
                            return method.invoke(source, arguments);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
        }
        PlayerMonitorService service = new PlayerMonitorService(repository);
        service.initialize();
        services.add(service);
        PlayerIdentityResolver resolver = (name, serverUuid, previous) -> new IdentityResolution(
                serverUuid == null ? null : serverUuid.toString(),
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString(),
                IdentityResolution.Lookup.notChecked(), IdentityResolution.Lookup.notChecked(),
                PlayerIdentityType.UNKNOWN, true);
        PlayerMonitorListener listener = new PlayerMonitorListener(service,
                new PluginLog(directory.resolve("log")), logger, settings, resolver, clock, sender);
        listeners.add(listener);
        return new TestContext(service, listener, settings);
    }

    private LoggerCapture loggerCapture(String name) {
        LoggerContext context = new LoggerContext();
        loggerContexts.add(context);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        ch.qos.logback.classic.Logger logger = context.getLogger(name);
        logger.addAppender(appender);
        return new LoggerCapture(logger, appender);
    }

    private static List<String> completionMessages(LoggerCapture capture) {
        return capture.appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("Stat scan completed:"))
                .toList();
    }

    private static void recordStat(PlayerMonitorService service, String playerName, long capturedAt) throws Exception {
        StatSnapshot snapshot = new StatSnapshot();
        snapshot.capturedAt = capturedAt;
        service.recordStat(playerName, snapshot);
        service.flush();
    }

    private static String statText(String playerName) {
        return String.join("\n", "玩家名称: " + playerName, "加入游戏: 1 次", "死亡计数: 2 次",
                "击杀计数: 3 人", "游戏时长: 4秒", "优先队列: 已过期", "特殊权限: ✅",
                "----------------------");
    }

    private static GameProfile profile(String name) {
        return new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
    }

    private static void waitUntil(Check condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.get() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.get());
    }

    private interface Check {
        boolean get() throws Exception;
    }

    private record TestContext(PlayerMonitorService service, PlayerMonitorListener listener,
                               MonitorSettingsStore settings) {
    }

    private record LoggerCapture(ch.qos.logback.classic.Logger logger,
                                 ListAppender<ILoggingEvent> appender) {
    }
}
