package waltomadaam2.xinbot.playermonitor;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.slf4j.Logger;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.EventPriority;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.DisconnectEvent;
import xin.bbtt.mcbot.events.PlayerJoinEvent;
import xin.bbtt.mcbot.events.PlayerLeaveEvent;
import xin.bbtt.mcbot.events.PublicChatEvent;
import xin.bbtt.mcbot.events.SendCommandEvent;
import xin.bbtt.mcbot.events.ServerChangeEvent;
import xin.bbtt.mcbot.events.SystemChatMessageEvent;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlayerMonitorListener implements Listener {
    private static final long STAT_WRITE_DELAY_MILLIS = 25L;
    private static final long CONNECTION_WATCHDOG_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long CONNECTION_WATCHDOG_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long UUID_REFRESH_RETRY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final int UUID_MAX_ATTEMPTS = 3;
    private static final long UUID_DISPATCH_INTERVAL_MILLIS = 10L;
    private static final String YELLOW = "\u001B[93m";
    private static final String PLAYER_LOG_COLOR = "\u001B[38;5;215m";
    private static final String RESET = "\u001B[0m";
    private static final long ENTRY_ROSTER_POLL_MILLIS = 100L;
    private static final long ENTRY_ROSTER_STABLE_MILLIS = 500L;
    private static final long ENTRY_ROSTER_TIMEOUT_MILLIS = 5_000L;
    private static final Pattern MINECRAFT_FORMAT_CODE = Pattern.compile("(?i)§[0-9a-fk-or]");
    private static final Pattern PUBLIC_CHAT_TEXT = Pattern.compile(
            "^(?:§[0-9a-fk-or])*\\s*<((?:(?:§[0-9a-fk-or])|[^>])+)>(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final PlayerMonitorService service;
    private final PluginLog log;
    private final Logger logger;
    private final MonitorSettingsStore settings;
    private final PlayerIdentityResolver identityResolver;
    private final LongSupplier clock;
    private final Map<String, String> onlinePlayers = new ConcurrentHashMap<>();
    private final Map<String, UUID> onlineServerUuids = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingStatDispatches = new ConcurrentHashMap<>();
    private final Set<String> activeStatCycles = ConcurrentHashMap.newKeySet();
    private final StatResponseCollector statResponses = new StatResponseCollector();
    private final Map<String, Integer> statAttempts = new ConcurrentHashMap<>();
    private final StatQueue statQueue;
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XinPlayerMonitor-stat-retry");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService uuidExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XinPlayerMonitor-uuid-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService identityExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "XinPlayerMonitor-identity-lookup");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService uuidIdentityExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XinPlayerMonitor-uuid-lookup");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CompletableFuture<IdentityResolution>> inFlightIdentityLookups =
            new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PlayerIdentity>> inFlightPublicResolves =
            new ConcurrentHashMap<>();
    private final Object uuidScheduleLock = new Object();
    private final Map<String, ScheduledUuidCheck> scheduledUuidChecks = new ConcurrentHashMap<>();
    private final AtomicLong uuidTaskSequence = new AtomicLong();
    private final AtomicLong uuidGeneration = new AtomicLong();
    private long nextUuidDispatchNanos;
    private boolean manualUuidScanActive;
    private int activeUuidBatches;
    private final AtomicLong gameGeneration = new AtomicLong();
    private final AtomicLong statTargetSequence = new AtomicLong();
    private final Object statBatchLock = new Object();
    private final Map<String, ScheduledStatCheck> scheduledStatChecks = new ConcurrentHashMap<>();
    private final Consumer<String> statCommandSender;
    private volatile Runnable beforeStatSend = () -> {
    };
    private volatile Runnable afterStatDispatch = () -> {
    };
    private StatBatch activeStatBatch;
    private ScheduledFuture<?> entryRosterPoll;
    private StableRosterWait entryRosterWait;
    private long entryRosterGeneration;
    private volatile boolean gameActive;
    private volatile boolean closed;
    private volatile boolean reconnectPending;
    private volatile boolean rosterReconciling;
    private boolean coldStartReconciled;
    private volatile boolean forceFreshRoster;
    private volatile long disconnectAt;
    private long reconnectGeneration;
    private final Object connectionStateLock = new Object();
    private final Object statWriteLock = new Object();
    private int pendingStatWrites;
    private int activeStatCallbacks;
    private volatile long statOutputSuppressionUntilNanos;
    private final Set<String> disconnectedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> reconnectedNewPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> reconnectedBrandNewPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicLong systemChatReceived = new AtomicLong();
    private final AtomicLong publicChatParsed = new AtomicLong();
    private final AtomicLong chatAcceptedByPlayerMonitor = new AtomicLong();
    private final AtomicLong chatRejected = new AtomicLong();
    private final AtomicLong chatParseFailed = new AtomicLong();
    private final AtomicLong chatDbFailed = new AtomicLong();
    private final ThreadLocal<SystemChatContext> systemChatContext = new ThreadLocal<>();
    private ScheduledFuture<?> disconnectFinalizer;
    private ScheduledFuture<?> connectionWatchdog;
    private volatile long stuckGameStateSince;
    private volatile long rosterDriftSince;
    private volatile long lastRosterDriftWarningAt;

    PlayerMonitorListener(PlayerMonitorService service, PluginLog log, Logger logger, MonitorSettingsStore settings) {
        this(service, log, logger, settings,
                new HttpPlayerIdentityResolver(settings::thirdPartyYggdrasilBaseUrl), System::currentTimeMillis);
    }

    PlayerMonitorListener(PlayerMonitorService service, PluginLog log, Logger logger, MonitorSettingsStore settings,
                          PlayerIdentityResolver identityResolver, LongSupplier clock) {
        this(service, log, logger, settings, identityResolver, clock, Bot.INSTANCE::sendCommand);
    }

    PlayerMonitorListener(PlayerMonitorService service, PluginLog log, Logger logger, MonitorSettingsStore settings,
                          PlayerIdentityResolver identityResolver, LongSupplier clock,
                          Consumer<String> statCommandSender) {
        this.service = service;
        this.log = log;
        this.logger = logger;
        this.settings = settings;
        this.identityResolver = identityResolver;
        this.clock = clock;
        this.statCommandSender = statCommandSender;
        statQueue = new StatQueue(
                () -> gameActive,
                settings::statSendIntervalMillis,
                this::dispatchStat,
                ignored -> {
                },
                this::handleStatSendFailure);
        retryExecutor.scheduleWithFixedDelay(this::retryTimedOutStats, 100L, 100L, TimeUnit.MILLISECONDS);
        connectionWatchdog = retryExecutor.scheduleWithFixedDelay(this::connectionWatchdog,
                CONNECTION_WATCHDOG_PERIOD_MILLIS, CONNECTION_WATCHDOG_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
    }

    void close() {
        synchronized (statWriteLock) {
            closed = true;
            waitForActiveStatCallbacksLocked();
        }
        synchronized (connectionStateLock) {
            reconnectGeneration++;
            reconnectPending = false;
            rosterReconciling = false;
            forceFreshRoster = false;
            gameActive = false;
            disconnectedPlayers.clear();
            reconnectedNewPlayers.clear();
            reconnectedBrandNewPlayers.clear();
            if (disconnectFinalizer != null) {
                disconnectFinalizer.cancel(false);
                disconnectFinalizer = null;
            }
            if (connectionWatchdog != null) {
                connectionWatchdog.cancel(false);
                connectionWatchdog = null;
            }
        }
        gameGeneration.incrementAndGet();
        cancelEntryRosterWait();
        statQueue.close();
        clearStatTracking();
        invalidateUuidChecks();
        uuidExecutor.shutdownNow();
        identityExecutor.shutdownNow();
        uuidIdentityExecutor.shutdownNow();
        retryExecutor.shutdown();
        waitForPendingStatWrites();
        retryExecutor.shutdownNow();
        try {
            if (!uuidExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("UUID refresh executor did not terminate cleanly");
            }
            if (!identityExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("identity lookup executor did not terminate cleanly");
            }
            if (!uuidIdentityExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("UUID identity lookup executor did not terminate cleanly");
            }
            if (!retryExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("stat retry executor did not terminate cleanly");
            }
        } catch (InterruptedException error) {
            retryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            log.warn("interrupted while waiting for stat retry executor shutdown");
        }
    }

    private void waitForActiveStatCallbacksLocked() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (activeStatCallbacks > 0) {
            if (!waitForStatWorkLocked(deadline, "active stat callback(s)", activeStatCallbacks)) {
                return;
            }
        }
    }

    private void waitForPendingStatWrites() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (statWriteLock) {
            while (pendingStatWrites > 0) {
                if (!waitForStatWorkLocked(deadline, "pending stat write(s)", pendingStatWrites)) {
                    return;
                }
            }
        }
    }

    private boolean waitForStatWorkLocked(long deadline, String description, int remaining) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0L) {
            log.warn("timed out waiting for " + remaining + " " + description);
            return false;
        }
        try {
            TimeUnit.NANOSECONDS.timedWait(statWriteLock, remainingNanos);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while waiting for " + description);
            return false;
        }
    }

    private boolean beginStatCallback() {
        synchronized (statWriteLock) {
            if (closed) {
                return false;
            }
            activeStatCallbacks++;
            return true;
        }
    }

    private void endStatCallback() {
        synchronized (statWriteLock) {
            activeStatCallbacks--;
            statWriteLock.notifyAll();
        }
    }

    @EventHandler
    public void onDisconnect(DisconnectEvent event) {
        if (closed || !beginReconnectWindow(System.currentTimeMillis())) {
            return;
        }
        log.warn("connection lost; waiting for Game roster reconciliation");
        logger.info("Connection lost; waiting for Game roster reconciliation.");
    }

    @EventHandler
    public void onServerChange(ServerChangeEvent event) {
        if (closed) {
            return;
        }
        boolean enteringGame = event.getServer() == Server.Game;
        boolean waitForStableEntryRoster = enteringGame && settings.scanOnEntry() && settings.statEnabled();
        long generation;
        synchronized (statBatchLock) {
            gameActive = false;
            generation = gameGeneration.incrementAndGet();
            cancelEntryRosterWaitLocked();
            if (waitForStableEntryRoster) {
                entryRosterWait = new StableRosterWait(clock.getAsLong());
                entryRosterGeneration = generation;
            }
        }
        cancelStatWork();
        if (!enteringGame) {
            invalidateUuidChecks();
            if (event.getCurrentServer() == Server.Game) {
                beginReconnectWindow(System.currentTimeMillis());
            } else {
                gameActive = false;
                clearOnlinePlayers();
                clearStatTracking();
            }
            log.info("left Game; monitoring disabled");
            logger.info("Left Game; stopped monitoring player activity.");
            return;
        }

        long gameEntryAt = System.currentTimeMillis();
        boolean resumed = reconcileAfterReconnect(gameEntryAt);
        if (!resumed) {
            boolean recoverStaleSessions;
            synchronized (connectionStateLock) {
                if (closed || generation != gameGeneration.get() || reconnectPending || rosterReconciling) {
                    return;
                }
                gameActive = true;
                forceFreshRoster = false;
                rosterReconciling = true;
                recoverStaleSessions = !coldStartReconciled;
                coldStartReconciled = true;
            }
            clearOnlinePlayers();
            clearStatTracking();
            try {
                if (gameActive) {
                    if (waitForStableEntryRoster) {
                        recoverStaleSessions(gameEntryAt, recoverStaleSessions);
                    } else {
                        recordFreshRoster(gameEntryAt, recoverStaleSessions);
                    }
                }
            } finally {
                synchronized (connectionStateLock) {
                    rosterReconciling = false;
                }
            }
        }
        log.info(resumed
                ? "reconnected to Game; reconciled player roster"
                : "entered Game; monitoring enabled");
        logger.info(resumed
                ? "Reconnected to Game; reconciled player roster."
                : "Entered Game; player monitoring enabled.");

        if (resumed && !waitForStableEntryRoster && settings.statEnabled() && settings.scanOnJoin()) {
            for (String playerName : reconnectedNewPlayers) {
                enqueueAutomaticJoinStat(playerName, reconnectedBrandNewPlayers.contains(normalize(playerName)),
                        generation);
            }
        }
        reconnectedNewPlayers.clear();
        reconnectedBrandNewPlayers.clear();

        if (waitForStableEntryRoster) {
            scheduleStableEntryScan(generation);
        } else {
            observeUuidRoster(Bot.INSTANCE.players.values());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        long generation = gameGeneration.get();
        GameProfile profile = event.getPlayerProfile();
        String playerName = nameOf(profile);
        boolean statEnabled = settings.statEnabled();
        boolean scanJoinStat = statEnabled && settings.scanOnJoin();
        boolean batchActive;
        synchronized (statBatchLock) {
            if (!acceptsJoin(generation) || entryRosterWait != null) {
                // Xinbot updates Bot.players before publishing the event; the entry poll observes this join.
                return;
            }
            batchActive = activeStatBatch != null;
        }
        boolean brandNew = statEnabled && (scanJoinStat || batchActive) && hasNeverRecordedStat(playerName);
        synchronized (statBatchLock) {
            if (!acceptsJoin(generation) || entryRosterWait != null) {
                return;
            }
            if (onlinePlayers.put(normalize(playerName), playerName) == null) {
                recordLogin(playerName, System.currentTimeMillis());
            }
            observeUuid(profile);
            if (statEnabled) {
                if (activeStatBatch != null) {
                    if (brandNew) {
                        addTargetLocked(activeStatBatch, playerName, StatQueue.PRIORITY_NEW_PLAYER, false);
                    }
                } else if (scanJoinStat) {
                    enqueueAutomaticJoinStat(playerName, brandNew, generation);
                }
            }
        }
    }

    private boolean acceptsJoin(long generation) {
        return generation == gameGeneration.get() && !closed && gameActive
                && !reconnectPending && !rosterReconciling;
    }

    @EventHandler
    public void onPlayerLeave(PlayerLeaveEvent event) {
        if (!gameActive || reconnectPending || rosterReconciling) {
            return;
        }
        String playerName = nameOf(event.getPlayerProfile());
        cancelUuidCheck(playerName);
        onlineServerUuids.remove(normalize(playerName));
        cancelStatCheck(playerName);
        statResponses.cancel(playerName);
        statAttempts.remove(normalize(playerName));
        pendingStatDispatches.remove(normalize(playerName));
        terminalStatTarget(playerName, StatOutcome.OFFLINE);
        if (onlinePlayers.remove(normalize(playerName)) == null) {
            return;
        }
        try {
            service.recordLogout(playerName, System.currentTimeMillis());
            log.info("queued logout for " + playerName);
        } catch (IOException error) {
            log.warn("failed to record player " + playerName + ": " + error.getMessage());
        }
    }

    @EventHandler
    public void onPublicChat(PublicChatEvent event) {
        if (closed) {
            chatRejected.incrementAndGet();
            return;
        }
        publicChatParsed.incrementAndGet();
        SystemChatContext context = systemChatContext.get();
        if (context != null) {
            context.publicChatEventSeen = true;
        }
        String message = event.getMessage();
        if (message == null) {
            chatRejected.incrementAndGet();
            return;
        }
        String playerName = nameOf(event.getSender());
        chatAcceptedByPlayerMonitor.incrementAndGet();
        try {
            service.recordChat(playerName, message, System.currentTimeMillis());
        } catch (IOException error) {
            chatDbFailed.incrementAndGet();
            log.warn("failed to record player " + playerName + ": " + error.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeSystemChat(SystemChatMessageEvent event) {
        systemChatReceived.incrementAndGet();
        systemChatContext.set(new SystemChatContext());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSystemChat(SystemChatMessageEvent event) {
        try {
            if (gameActive && beginStatCallback()) {
                try {
                    SystemChatContext context = systemChatContext.get();
                    Optional<String> missingPlayer = context != null && context.publicChatEventSeen
                            ? Optional.empty() : statResponses.rejectMissingPlayer(event.getText());
                    if (missingPlayer.isPresent()) {
                        handleStatPlayerNotFound(missingPlayer.get());
                    } else {
                        statResponses.accept(event.getText()).ifPresent(captured -> {
                            statOutputSuppressionUntilNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                            String normalizedName = normalize(captured.playerName());
                            long token;
                            synchronized (statBatchLock) {
                                StatTarget target = currentStatTarget(normalizedName);
                                if (target == null || target.terminal || target.responseCaptured) {
                                    return;
                                }
                                token = target.token;
                                target.responseCaptured = true;
                                statQueue.cancel(captured.playerName());
                                statAttempts.remove(normalizedName);
                                pendingStatDispatches.remove(normalizedName);
                            }
                            scheduleStatWrite(captured, token);
                        });
                    }
                    retryTimedOutStats();
                } finally {
                    endStatCallback();
                }
            }
            recordFallbackPublicChat(event);
        } finally {
            systemChatContext.remove();
        }
    }

    private void recordFallbackPublicChat(SystemChatMessageEvent event) {
        SystemChatContext context = systemChatContext.get();
        if (context != null && context.publicChatEventSeen) {
            return;
        }
        if (closed || event.isOverlay()) {
            return;
        }
        String text = event.getText();
        if (text == null || text.indexOf('<') < 0 || text.indexOf('>') < 0) {
            return;
        }
        Matcher matcher = PUBLIC_CHAT_TEXT.matcher(text);
        if (!matcher.matches()) {
            chatParseFailed.incrementAndGet();
            return;
        }
        publicChatParsed.incrementAndGet();
        String playerName = MINECRAFT_FORMAT_CODE.matcher(matcher.group(1)).replaceAll("").trim();
        String message = matcher.group(2);
        if (message.startsWith(" ")) {
            message = message.substring(1);
        }
        if (message.startsWith("§a")) {
            message = message.substring(2);
        }
        if (playerName.isEmpty()) {
            chatRejected.incrementAndGet();
            return;
        }
        chatAcceptedByPlayerMonitor.incrementAndGet();
        try {
            service.recordChat(playerName, message, System.currentTimeMillis());
        } catch (IOException error) {
            chatDbFailed.incrementAndGet();
            log.warn("failed to record fallback chat for " + playerName + ": " + error.getMessage());
        }
    }

    private void scheduleStatWrite(StatResponseCollector.CapturedStat captured, long token) {
        synchronized (statWriteLock) {
            pendingStatWrites++;
        }
        Runnable write = () -> persistCapturedStat(captured, token);
        try {
            retryExecutor.schedule(write, STAT_WRITE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException error) {
            write.run();
        }
    }

    private void persistCapturedStat(StatResponseCollector.CapturedStat captured, long token) {
        try {
            service.recordStat(captured.playerName(), captured.snapshot());
            service.flush();
            terminalStatTarget(captured.playerName(), token, StatOutcome.SUCCESS);
            scheduleNextStatCheck(captured.playerName(), captured.snapshot().capturedAt);
        } catch (IOException error) {
            log.warn("failed to record stat for " + captured.playerName() + ": " + error.getMessage());
            terminalStatTarget(captured.playerName(), token, StatOutcome.RETRIES_EXHAUSTED);
        } finally {
            synchronized (statWriteLock) {
                pendingStatWrites--;
                statWriteLock.notifyAll();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSendCommand(SendCommandEvent event) {
        String command = event.getCommand();
        if (command == null || !command.startsWith("stat ")) {
            return;
        }
        String playerName = command.substring("stat ".length()).trim();
        String normalizedName = normalize(playerName);
        synchronized (statBatchLock) {
            Long token = pendingStatDispatches.get(normalizedName);
            if (token == null || !isCurrentStatTargetLocked(normalizedName, token)) {
                return;
            }
            if (event.isDefaultActionCancelled()) {
                log.warn("stat command cancelled for " + playerName);
                handleStatSendFailure(playerName);
                return;
            }
            if (pendingStatDispatches.remove(normalizedName, token)) {
                statAttempts.merge(normalizedName, 1, Integer::sum);
                statResponses.expect(playerName, settings.statTimeoutMillis());
                logger.info("Sent stat for {}.", playerName);
            }
        }
    }

    private void dispatchStat(StatQueue.Dispatch dispatch) {
        try {
            beforeStatSend.run();
            String normalized = normalize(dispatch.playerName());
            synchronized (statBatchLock) {
                if (closed || !gameActive || reconnectPending
                        || !isCurrentStatTargetLocked(normalized, dispatch.token())
                        || currentStatTarget(normalized).inFlight) {
                    return;
                }
                if (!onlinePlayers.containsKey(normalized)) {
                    terminalStatTargetLocked(activeStatBatch.targets.get(normalized), StatOutcome.OFFLINE);
                    completeBatchIfDoneLocked(activeStatBatch);
                    return;
                }
                currentStatTarget(normalized).inFlight = true;
                pendingStatDispatches.put(normalized, dispatch.token());
                statCommandSender.accept("stat " + dispatch.playerName());
            }
        } finally {
            afterStatDispatch.run();
        }
    }

    int scanAllOnlinePlayers() {
        if (!gameActive) {
            return -1;
        }
        StatScanResult result = queueStatScan(Bot.INSTANCE.players.values(), false, StatQueue.PRIORITY_MANUAL);
        log.info("queued manual stat scan for " + result.queued() + " online players");
        return result.queued();
    }

    StatScanStatus statScanStatus() {
        RosterDrift drift = rosterDrift();
        return new StatScanStatus(gameActive, reconnectPending, rosterReconciling, forceFreshRoster,
                reconnectGeneration, disconnectAt, onlinePlayers.size(), statQueue.size(),
                pendingStatDispatches.size(), activeStatCycles.size(), statResponses.hasPending(),
                drift.botRoster(), drift.monitorRoster(), drift.missingFromMonitor(), drift.extraInMonitor(),
                systemChatReceived.get(), publicChatParsed.get(), chatAcceptedByPlayerMonitor.get(),
                chatRejected.get(), chatParseFailed.get(), chatDbFailed.get());
    }

    UuidScanStatus uuidScanStatus() {
        synchronized (uuidScheduleLock) {
            int inFlight = (int) scheduledUuidChecks.values().stream().filter(ScheduledUuidCheck::inFlight).count();
            int queued = (int) scheduledUuidChecks.values().stream().filter(ScheduledUuidCheck::queued).count();
            return new UuidScanStatus(onlinePlayers.size(), queued,
                    activeUuidBatches, inFlight > 0);
        }
    }

    int scanAllOnlineUuidPlayers() {
        if (!gameActive || closed) {
            return -1;
        }
        List<GameProfile> roster = List.copyOf(Bot.INSTANCE.players.values());
        Set<String> queuedPlayers = ConcurrentHashMap.newKeySet();
        List<GameProfile> targets = new java.util.ArrayList<>();
        for (GameProfile profile : roster) {
            if (profile == null || profile.getId() == null || nameOf(profile) == null || nameOf(profile).isBlank()) {
                continue;
            }
            if (queuedPlayers.add(normalize(nameOf(profile)))) {
                targets.add(profile);
            }
        }
        synchronized (uuidScheduleLock) {
            if (manualUuidScanActive) {
                return -2;
            }
            manualUuidScanActive = true;
            UuidBatch batch = beginUuidBatchLocked(
                    targets.stream().map(PlayerMonitorListener::nameOf).toList(), true);
            for (GameProfile profile : targets) {
                String playerName = nameOf(profile);
                String normalized = normalize(playerName);
                onlinePlayers.put(normalized, playerName);
                onlineServerUuids.put(normalized, profile.getId());
                scheduleUuidCheck(playerName, profile.getId(), 0L, true, true, 1, batch);
            }
            if (targets.isEmpty()) {
                completeUuidBatchIfDoneLocked(batch);
            }
        }
        return targets.size();
    }

    List<String> onlinePlayerNames() {
        if (!gameActive) {
            return List.of();
        }
        return Bot.INSTANCE.players.values().stream()
                .map(PlayerMonitorListener::nameOf)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    /** Re-applies the configured timeout to an already active disconnect window. */
    void applyDisconnectTimeoutNow() {
        synchronized (connectionStateLock) {
            if (closed || !reconnectPending) {
                return;
            }
            if (disconnectFinalizer != null) {
                disconnectFinalizer.cancel(false);
            }
            long timeoutMillis = TimeUnit.MINUTES.toMillis(settings.disconnectTimeoutMinutes());
            long remainingMillis = Math.max(0L, disconnectAt + timeoutMillis - System.currentTimeMillis());
            long expectedDisconnectAt = disconnectAt;
            long expectedGeneration = reconnectGeneration;
            disconnectFinalizer = retryExecutor.schedule(
                    () -> finalizeDisconnectedSessions(expectedDisconnectAt, expectedGeneration),
                    remainingMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    private boolean beginReconnectWindow(long now) {
        synchronized (statBatchLock) {
            gameActive = false;
            gameGeneration.incrementAndGet();
            cancelEntryRosterWaitLocked();
        }
        cancelStatWork();
        synchronized (connectionStateLock) {
            if (closed || reconnectPending || rosterReconciling) {
                return false;
            }
            long generation = ++reconnectGeneration;
            disconnectAt = now;
            disconnectedPlayers.clear();
            disconnectedPlayers.addAll(onlinePlayers.values());
            reconnectPending = true;
            rosterReconciling = false;
            gameActive = false;
            invalidateUuidChecks();
            clearOnlinePlayers();
            clearStatTracking();
            if (disconnectFinalizer != null) {
                disconnectFinalizer.cancel(false);
            }
            disconnectFinalizer = retryExecutor.schedule(
                    () -> finalizeDisconnectedSessions(now, generation),
                    TimeUnit.MINUTES.toMillis(settings.disconnectTimeoutMinutes()),
                    TimeUnit.MILLISECONDS);
            return true;
        }
    }

    private void recordFreshRoster(long gameEntryAt, boolean recoverStaleSessions) {
        recoverStaleSessions(gameEntryAt, recoverStaleSessions);
        Set<String> currentPlayers = Bot.INSTANCE.players.values().stream()
                .map(PlayerMonitorListener::nameOf)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        for (String playerName : currentPlayers) {
            onlinePlayers.put(normalize(playerName), playerName);
            recordLogin(playerName, gameEntryAt);
        }
    }

    private void recoverStaleSessions(long gameEntryAt, boolean recover) {
        if (recover) {
            try {
                int recovered = service.recoverOpenSessions(gameEntryAt);
                if (recovered > 0) {
                    log.warn("recovered " + recovered + " stale open session(s) from an earlier unclean shutdown");
                    logger.warn("Recovered {} stale open session(s) before recording the current Game roster.", recovered);
                }
            } catch (IOException error) {
                log.warn("failed to recover stale open sessions: " + error.getMessage());
                logger.warn("Unable to recover stale open sessions before roster recording.", error);
            }
        }
    }

    private void clearStatTracking() {
        cancelStatWork();
    }

    private void cancelStatWork() {
        synchronized (statBatchLock) {
            if (activeStatBatch != null) {
                activeStatBatch.cancelled = true;
                activeStatBatch = null;
                settings.endStatOperation();
            }
            for (ScheduledStatCheck scheduled : scheduledStatChecks.values()) {
                scheduled.future().cancel(false);
            }
            scheduledStatChecks.clear();
        }
        statQueue.clear();
        statResponses.clear();
        statAttempts.clear();
        pendingStatDispatches.clear();
        activeStatCycles.clear();
    }

    private boolean reconcileAfterReconnect(long gameEntryAt) {
        int continued = 0;
        int loggedOut = 0;
        int loggedIn = 0;
        synchronized (connectionStateLock) {
            if (!reconnectPending || closed) {
                return false;
            }
            reconnectPending = false;
            rosterReconciling = true;
            gameActive = true;
            long generation = reconnectGeneration;
            long lostAt = disconnectAt;
            Set<String> previousPlayers = Set.copyOf(disconnectedPlayers);
            if (disconnectFinalizer != null) {
                disconnectFinalizer.cancel(false);
                disconnectFinalizer = null;
            }

            Set<String> currentPlayers = Bot.INSTANCE.players.values().stream()
                    .map(PlayerMonitorListener::nameOf)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(java.util.stream.Collectors.toSet());

            reconnectedNewPlayers.clear();
            reconnectedBrandNewPlayers.clear();
            for (String playerName : previousPlayers) {
                if (!containsIgnoreCase(currentPlayers, playerName)) {
                    recordLogoutAt(playerName, lostAt);
                    loggedOut++;
                } else {
                    continued++;
                }
            }
            onlinePlayers.clear();
            for (String playerName : currentPlayers) {
                onlinePlayers.put(normalize(playerName), playerName);
            }
            boolean trackBrandNewPlayers = settings.statEnabled() && settings.scanOnJoin();
            for (String playerName : currentPlayers) {
                if (!containsIgnoreCase(previousPlayers, playerName)) {
                    boolean brandNew = trackBrandNewPlayers && hasNeverRecordedStat(playerName);
                    recordLogin(playerName, gameEntryAt);
                    reconnectedNewPlayers.add(playerName);
                    if (brandNew) {
                        reconnectedBrandNewPlayers.add(normalize(playerName));
                    }
                    loggedIn++;
                }
            }

            if (reconnectGeneration == generation) {
                rosterReconciling = false;
                disconnectedPlayers.clear();
            }
        }
        log.info("reconciled Game roster: continued=" + continued
                + ", loggedOut=" + loggedOut + ", loggedIn=" + loggedIn);
        return true;
    }

    private void finalizeDisconnectedSessions(long expectedDisconnectAt) {
        long generation;
        synchronized (connectionStateLock) {
            generation = reconnectGeneration;
        }
        finalizeDisconnectedSessions(expectedDisconnectAt, generation);
    }

    private void finalizeDisconnectedSessions(long expectedDisconnectAt, long expectedGeneration) {
        Set<String> playersToClose;
        synchronized (connectionStateLock) {
            if (!reconnectPending || rosterReconciling
                    || disconnectAt != expectedDisconnectAt
                    || reconnectGeneration != expectedGeneration) {
                return;
            }
            reconnectPending = false;
            forceFreshRoster = true;
            gameActive = false;
            playersToClose = Set.copyOf(disconnectedPlayers);
            disconnectedPlayers.clear();
            disconnectFinalizer = null;
        }
        onlinePlayers.clear();
        for (String playerName : playersToClose) {
            recordLogoutAt(playerName, expectedDisconnectAt);
        }
        log.warn("finalized disconnected sessions after timeout");
        logger.info("Finalized disconnected sessions after timeout.");
    }

    private void connectionWatchdog() {
        connectionWatchdog(false);
    }

    private void connectionWatchdog(boolean ignoreBotRunningForTesting) {
        if (closed) {
            return;
        }
        if (Bot.INSTANCE.getServer() != Server.Game
                || (!ignoreBotRunningForTesting && !Bot.INSTANCE.isRunning())) {
            stuckGameStateSince = 0L;
            return;
        }
        RosterDrift drift = rosterDrift();
        long now = System.currentTimeMillis();
        boolean rosterDrifted = drift.missingFromMonitor() > 0 || drift.extraInMonitor() > 0;
        if (rosterDrifted) {
            if (rosterDriftSince == 0L) {
                rosterDriftSince = now;
            }
            if (now - lastRosterDriftWarningAt >= TimeUnit.MINUTES.toMillis(5)) {
                lastRosterDriftWarningAt = now;
                log.warn("Player roster drift detected: botRoster=" + drift.botRoster()
                        + ", monitorRoster=" + drift.monitorRoster()
                        + ", missing=" + drift.missingFromMonitor()
                        + ", extra=" + drift.extraInMonitor());
            }
            if (gameActive && !reconnectPending && !rosterReconciling
                    && now - rosterDriftSince >= CONNECTION_WATCHDOG_GRACE_MILLIS) {
                reconcileLiveRoster(now);
                rosterDriftSince = 0L;
            }
        } else {
            rosterDriftSince = 0L;
        }
        if (gameActive && !reconnectPending && !rosterReconciling) {
            stuckGameStateSince = 0L;
            return;
        }
        if (stuckGameStateSince == 0L) {
            stuckGameStateSince = now;
            return;
        }
        if (now - stuckGameStateSince < CONNECTION_WATCHDOG_GRACE_MILLIS) {
            return;
        }
        if (reconnectPending) {
            if (reconcileAfterReconnect(now)) {
                log.warn("connection watchdog recovered stuck reconnect state");
            }
        } else {
            recoverStuckGameState(now);
        }
        stuckGameStateSince = 0L;
    }

    private void recoverStuckGameState(long now) {
        synchronized (connectionStateLock) {
            if (closed || reconnectPending) {
                return;
            }
            reconnectGeneration++;
            gameActive = true;
            rosterReconciling = true;
            boolean recoverStaleSessions = !coldStartReconciled;
            boolean recordFreshSessions = recoverStaleSessions || forceFreshRoster;
            coldStartReconciled = true;
            forceFreshRoster = false;
            try {
                clearOnlinePlayers();
                if (recordFreshSessions) {
                    recordFreshRoster(now, recoverStaleSessions);
                } else {
                    syncMonitorRosterFromBot();
                }
                clearStatTracking();
            } finally {
                rosterReconciling = false;
            }
        }
        log.warn("connection watchdog restored Game state from Bot roster at " + now);
    }

    private void reconcileLiveRoster(long now) {
        synchronized (connectionStateLock) {
            if (closed || !gameActive || reconnectPending || rosterReconciling) {
                return;
            }
            rosterReconciling = true;
            int loggedIn = 0;
            int loggedOut = 0;
            try {
                Set<String> currentPlayers = Bot.INSTANCE.players.values().stream()
                        .map(PlayerMonitorListener::nameOf)
                        .filter(name -> name != null && !name.isBlank())
                        .collect(java.util.stream.Collectors.toSet());
                Set<String> previousPlayers = Set.copyOf(onlinePlayers.values());
                for (String playerName : previousPlayers) {
                    if (!containsIgnoreCase(currentPlayers, playerName)) {
                        recordLogoutAt(playerName, now);
                        loggedOut++;
                    }
                }
                for (String playerName : currentPlayers) {
                    if (!containsIgnoreCase(previousPlayers, playerName)) {
                        recordLogin(playerName, now);
                        loggedIn++;
                    }
                }
                onlinePlayers.clear();
                for (String playerName : currentPlayers) {
                    onlinePlayers.put(normalize(playerName), playerName);
                }
                observeUuidRoster(Bot.INSTANCE.players.values());
            } finally {
                rosterReconciling = false;
            }
            log.warn("connection watchdog reconciled roster drift: loggedIn=" + loggedIn
                    + ", loggedOut=" + loggedOut);
        }
    }

    private void scheduleStableEntryScan(long generation) {
        synchronized (statBatchLock) {
            if (closed || generation != gameGeneration.get() || entryRosterWait == null
                    || entryRosterGeneration != generation) {
                return;
            }
            scheduleEntryRosterPollLocked(generation);
        }
    }

    private void scheduleEntryRosterPollLocked(long generation) {
        try {
            entryRosterPoll = retryExecutor.schedule(() -> pollEntryRoster(generation),
                    ENTRY_ROSTER_POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            entryRosterWait = null;
            entryRosterPoll = null;
        }
    }

    private void pollEntryRoster(long generation) {
        StatScanResult result;
        synchronized (statBatchLock) {
            if (generation != gameGeneration.get() || generation != entryRosterGeneration) {
                return;
            }
            if (closed || !gameActive || reconnectPending
                    || !settings.get().statEnabled || !settings.get().scanOnEntry || entryRosterWait == null) {
                cancelEntryRosterWaitLocked();
                return;
            }
            List<GameProfile> snapshot = List.copyOf(Bot.INSTANCE.players.values());
            Set<String> roster = snapshot.stream()
                    .filter(profile -> profile != null && nameOf(profile) != null && !nameOf(profile).isBlank())
                    .map(profile -> normalize(nameOf(profile)) + "|" + profile.getId())
                    .collect(java.util.stream.Collectors.toSet());
            boolean ready = entryRosterWait.sample(roster, clock.getAsLong(),
                    ENTRY_ROSTER_STABLE_MILLIS, ENTRY_ROSTER_TIMEOUT_MILLIS);
            if (!ready) {
                scheduleEntryRosterPollLocked(generation);
                return;
            }
            entryRosterPoll = null;
            entryRosterWait = null;
            if (roster.isEmpty()) {
                log.info("automatic stat scan skipped because the Game roster stayed empty");
                return;
            }
            observeUuidRoster(snapshot);
            // Create the batch before join handlers can observe that the entry wait has ended.
            result = queueStatScan(snapshot, true, StatQueue.PRIORITY_ENTRY, generation);
        }
        log.info("queued automatic stat scan for " + result.queued() + " online players");
        logger.info("Queued automatic stat scan for {} online players; skipped {} in cooldown.",
                result.queued(), result.cooldownSkipped());
    }

    private void cancelEntryRosterWait() {
        synchronized (statBatchLock) {
            cancelEntryRosterWaitLocked();
        }
    }

    private void cancelEntryRosterWaitLocked() {
        if (entryRosterPoll != null) {
            entryRosterPoll.cancel(false);
        }
        entryRosterPoll = null;
        entryRosterWait = null;
    }

    void applyUuidSettingsNow() {
        invalidateUuidChecks();
        if (gameActive && settings.uuidRecordEnable()) {
            observeUuidRoster(Bot.INSTANCE.players.values());
            for (Map.Entry<String, UUID> entry : onlineServerUuids.entrySet()) {
                String playerName = onlinePlayers.get(entry.getKey());
                if (playerName != null) {
                    scheduleUuidCheck(playerName, entry.getValue(), 0L, false);
                }
            }
        }
    }

    void refreshUuidIfEligible(String playerName) {
        if (!closed && settings.uuidRecordEnable()) {
            resolve(playerName, true).exceptionally(failure -> {
                log.warn("failed to refresh UUID identity for " + playerName + ": " + rootMessage(failure));
                return null;
            });
        }
    }

    private void observeUuidRoster(Collection<GameProfile> profiles) {
        for (GameProfile profile : profiles) {
            observeUuid(profile);
        }
    }

    private void observeUuid(GameProfile profile) {
        if (profile == null || profile.getId() == null || nameOf(profile) == null || nameOf(profile).isBlank()) {
            return;
        }
        String playerName = nameOf(profile);
        String normalized = normalize(playerName);
        UUID previous = onlineServerUuids.put(normalized, profile.getId());
        scheduleUuidCheck(playerName, profile.getId(), 0L,
                previous != null && !previous.equals(profile.getId()));
    }

    private CompletableFuture<Void> scheduleUuidCheck(String playerName, UUID serverUuid, long delayMillis,
                                                       boolean replace) {
        return scheduleUuidCheck(playerName, serverUuid, delayMillis, replace, false, 1, null);
    }

    private CompletableFuture<Void> scheduleUuidCheck(String playerName, UUID serverUuid, long delayMillis,
                                                       boolean replace, boolean force) {
        return scheduleUuidCheck(playerName, serverUuid, delayMillis, replace, force, 1, null);
    }

    private CompletableFuture<Void> scheduleUuidCheck(String playerName, UUID serverUuid, long delayMillis,
                                                       boolean replace, boolean force, int attempt, UuidBatch batch) {
        if (closed || !gameActive) {
            terminalUuidTarget(batch, playerName, UuidOutcome.SKIPPED);
            return CompletableFuture.completedFuture(null);
        }
        String normalized = normalize(playerName);
        synchronized (uuidScheduleLock) {
            ScheduledUuidCheck existing = scheduledUuidChecks.get(normalized);
            boolean scheduledForce = force;
            int scheduledAttempt = attempt;
            UuidBatch scheduledBatch = batch;
            if (existing != null && !existing.completion().isDone()) {
                if (!replace && existing.serverUuid().equals(serverUuid)) {
                    return existing.completion();
                }
                scheduledForce |= existing.force();
                if (!force) {
                    scheduledAttempt = existing.attempt();
                    if (scheduledBatch == null) {
                        scheduledBatch = existing.batch();
                    }
                }
            }
            if ((!settings.uuidRecordEnable() && !scheduledForce)
                    || (!scheduledForce && delayMillis > 0 && settings.uuidRecordCooldown() <= 0)) {
                terminalUuidTargetLocked(scheduledBatch, playerName, UuidOutcome.SKIPPED);
                return CompletableFuture.completedFuture(null);
            }
            if (existing != null && !existing.completion().isDone()) {
                existing.future().cancel(false);
                if (existing.batch() != null && existing.batch() != scheduledBatch) {
                    terminalUuidTargetLocked(existing.batch(), playerName, UuidOutcome.SKIPPED);
                }
            }
            long token = uuidTaskSequence.incrementAndGet();
            long generation = uuidGeneration.get();
            try {
                long now = System.nanoTime();
                long requestedAt = now + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, delayMillis));
                // Only immediate work participates in the dispatch lane. A future cooldown
                // check must not hold the lane and delay a later UUID change by hours.
                long dispatchAt = delayMillis > 0L ? requestedAt : Math.max(requestedAt, nextUuidDispatchNanos);
                if (delayMillis <= 0L) {
                    nextUuidDispatchNanos = dispatchAt + TimeUnit.MILLISECONDS.toNanos(UUID_DISPATCH_INTERVAL_MILLIS);
                }
                long effectiveDelay = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(dispatchAt - now));
                CompletableFuture<Void> completion = new CompletableFuture<>();
                boolean taskForce = scheduledForce;
                int taskAttempt = scheduledAttempt;
                ScheduledFuture<?> future = uuidExecutor.schedule(
                        () -> runUuidCheck(playerName, serverUuid, generation, token, taskForce, taskAttempt),
                        effectiveDelay, TimeUnit.MILLISECONDS);
                boolean queued = scheduledBatch != null || delayMillis <= 0L;
                scheduledUuidChecks.put(normalized,
                        new ScheduledUuidCheck(playerName, serverUuid, token, future, completion, taskForce, taskAttempt,
                                scheduledBatch, queued, false));
                if (existing != null) {
                    if (existing.force()) {
                        completion.whenComplete((ignored, failure) -> existing.completion().complete(null));
                    } else {
                        existing.completion().complete(null);
                    }
                }
                return completion;
            } catch (RejectedExecutionException ignored) {
                // Plugin shutdown invalidates all UUID work.
                if (existing != null) {
                    existing.completion().complete(null);
                }
                terminalUuidTargetLocked(scheduledBatch, playerName, UuidOutcome.SKIPPED);
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    private void runUuidCheck(String playerName, UUID scheduledServerUuid, long generation, long token,
                              boolean force, int attempt) {
        String normalized = normalize(playerName);
        ScheduledUuidCheck current;
        synchronized (uuidScheduleLock) {
            current = scheduledUuidChecks.get(normalized);
            if (current == null || current.token() != token) {
                return;
            }
        }
        UUID currentServerUuid = onlineServerUuids.get(normalized);
        if (currentServerUuid == null || !uuidContextValid(normalized, currentServerUuid, generation, force)) {
            finishUuidCheck(normalized, current, () -> {
                terminalUuidTargetLocked(current.batch(), playerName, UuidOutcome.SKIPPED);
                return null;
            });
            return;
        }
        if (!currentServerUuid.equals(scheduledServerUuid)) {
            finishUuidCheck(normalized, current,
                    () -> scheduleUuidCheck(onlinePlayers.get(normalized), currentServerUuid,
                            0L, true, force, attempt, current.batch()));
            return;
        }

        long now = clock.getAsLong();
        try {
            StoredPlayerIdentity previous = service.playerIdentity(playerName).orElse(null);
            long cooldownMillis = TimeUnit.HOURS.toMillis(settings.uuidRecordCooldown());
            boolean serverUuidChanged = previous == null || previous.serverUuid() == null
                    || !currentServerUuid.toString().equalsIgnoreCase(previous.serverUuid());
            long lastCheckedAt = previous == null || previous.uuidLastCheckedAt() == null
                    ? 0L : previous.uuidLastCheckedAt();
            long dueAt = lastCheckedAt <= 0L ? now : saturatedAdd(lastCheckedAt, cooldownMillis);
            if (!force && !serverUuidChanged && cooldownMillis <= 0) {
                finishUuidCheck(normalized, current, () -> null);
                return;
            }
            if (!force && !serverUuidChanged && now < dueAt) {
                finishUuidCheck(normalized, current,
                        () -> {
                            scheduleUuidCheck(playerName, currentServerUuid, dueAt - now, false);
                            return null;
                        });
                return;
            }

            ScheduledUuidCheck dispatched;
            synchronized (uuidScheduleLock) {
                if (scheduledUuidChecks.get(normalized) != current) {
                    return;
                }
                UuidBatch batch = current.batch() == null
                        ? beginUuidBatchLocked(List.of(playerName), false) : current.batch();
                dispatched = new ScheduledUuidCheck(current.playerName(), current.serverUuid(), current.token(), current.future(),
                        current.completion(), current.force(), current.attempt(), batch, false, true);
                scheduledUuidChecks.put(normalized, dispatched);
            }
            logger.info("Requested uuid for " + PLAYER_LOG_COLOR + "{}" + RESET + ".", playerName);
            resolveIdentity(playerName, currentServerUuid, previous, uuidIdentityExecutor).whenComplete((resolution, failure) ->
                    finishUuidCheck(normalized, dispatched,
                            () -> completeUuidCheck(playerName, currentServerUuid, normalized, generation,
                                    clock.getAsLong(), resolution, failure, force, attempt, dispatched.batch())));
        } catch (IOException | RuntimeException error) {
            log.warn("failed to refresh UUID identity for " + playerName + ": " + error.getMessage());
            finishUuidCheck(normalized, current,
                    () -> retryUuidCheck(playerName, currentServerUuid, force, attempt, current.batch()));
        }
    }

    private void finishUuidCheck(String normalized, ScheduledUuidCheck check,
                                 Supplier<CompletableFuture<Void>> continuation) {
        CompletableFuture<Void> next;
        synchronized (uuidScheduleLock) {
            if (!scheduledUuidChecks.remove(normalized, check)) {
                return;
            }
            next = continuation.get();
        }
        if (check.force() && next != null) {
            next.whenComplete((ignored, failure) -> check.completion().complete(null));
        } else {
            check.completion().complete(null);
        }
    }

    private CompletableFuture<Void> completeUuidCheck(String playerName, UUID serverUuid, String normalized,
                                                      long generation, long checkedAt,
                                                      IdentityResolution resolution, Throwable failure,
                                                      boolean force, int attempt, UuidBatch batch) {
        if (failure != null) {
            log.warn("failed to refresh UUID identity for " + playerName + ": " + rootMessage(failure));
            if (uuidContextValid(normalized, serverUuid, generation, force)) {
                return retryUuidCheck(playerName, serverUuid, force, attempt, batch);
            }
            terminalUuidTarget(batch, playerName, UuidOutcome.SKIPPED);
            return null;
        }
        if (!uuidContextValid(normalized, serverUuid, generation, force)) {
            terminalUuidTarget(batch, playerName, UuidOutcome.SKIPPED);
            return null;
        }
        try {
            StoredPlayerIdentity updated = service.recordIdentityCheck(playerName, resolution, checkedAt);
            if (resolution.successful()) {
                terminalUuidTarget(batch, playerName, UuidOutcome.SUCCESS);
            }
            if (settings.uuidRecordCooldown() <= 0) {
                if (!resolution.successful()) {
                    return retryUuidCheck(playerName, serverUuid, force, attempt, batch);
                }
                return null;
            }
            if (resolution.successful() && updated.uuidLastCheckedAt() != null) {
                long nextAt = saturatedAdd(updated.uuidLastCheckedAt(),
                        TimeUnit.HOURS.toMillis(settings.uuidRecordCooldown()));
                scheduleUuidCheck(playerName, serverUuid, Math.max(0L, nextAt - clock.getAsLong()), false);
                return null;
            } else {
                return retryUuidCheck(playerName, serverUuid, force, attempt, batch);
            }
        } catch (IOException | RuntimeException error) {
            log.warn("failed to refresh UUID identity for " + playerName + ": " + error.getMessage());
            return retryUuidCheck(playerName, serverUuid, force, attempt, batch);
        }
    }

    private CompletableFuture<Void> retryUuidCheck(String playerName, UUID serverUuid,
                                                   boolean force, int attempt, UuidBatch batch) {
        if (attempt >= UUID_MAX_ATTEMPTS) {
            log.warn("UUID identity refresh failed for " + playerName + " after " + attempt + " attempts");
            terminalUuidTarget(batch, playerName, UuidOutcome.FAILED);
            return null;
        }
        return scheduleUuidCheck(playerName, serverUuid, UUID_REFRESH_RETRY_MILLIS,
                false, force, attempt + 1, batch);
    }

    private CompletableFuture<IdentityResolution> resolveIdentity(String playerName, UUID serverUuid,
                                                                   StoredPlayerIdentity previous) {
        return resolveIdentity(playerName, serverUuid, previous, identityExecutor);
    }

    private CompletableFuture<IdentityResolution> resolveIdentity(String playerName, UUID serverUuid,
                                                                   StoredPlayerIdentity previous,
                                                                   ExecutorService executor) {
        String key = normalize(playerName) + "|" + (serverUuid == null ? "" : serverUuid);
        CompletableFuture<IdentityResolution> lookup = inFlightIdentityLookups.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            CompletableFuture<IdentityResolution> created = CompletableFuture.supplyAsync(() -> {
                try {
                    return identityResolver.resolve(playerName, serverUuid, previous);
                } catch (IOException error) {
                    throw new CompletionException(error);
                }
            }, executor);
            return created;
        });
        lookup.whenComplete((result, failure) -> inFlightIdentityLookups.remove(key, lookup));
        return lookup;
    }

    CompletableFuture<PlayerIdentity> resolve(String playerName) {
        return resolve(playerName, false);
    }

    private CompletableFuture<PlayerIdentity> resolve(String playerName, boolean onlyIfStale) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("XinPlayerMonitor is disabled"));
        }
        if (playerName == null || playerName.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player name cannot be blank"));
        }
        String requestedName = playerName.trim();
        String key = normalize(requestedName);
        CompletableFuture<PlayerIdentity> lookup = inFlightPublicResolves.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            CompletableFuture<PlayerIdentity> created = CompletableFuture.supplyAsync(() -> {
                try {
                    StoredPlayerIdentity stored = service.playerIdentity(requestedName).orElse(null);
                    UUID current = onlineServerUuids.get(key);
                    UUID target = current != null ? current : uuid(stored == null ? null : stored.serverUuid());
                    return new ResolveContext(stored, target, current != null);
                } catch (IOException error) {
                    throw new CompletionException(error);
                }
            }, identityExecutor).thenCompose(context -> {
                StoredPlayerIdentity stored = context.stored();
                boolean sameServer = stored != null && context.serverUuid() != null
                        && context.serverUuid().toString().equalsIgnoreCase(stored.serverUuid());
                if (onlyIfStale && (!settings.uuidRecordEnable() || (sameServer
                        && ((stored.identityType() == PlayerIdentityType.OFFLINE && stored.uuidLastCheckedAt() != null)
                        || (HttpPlayerIdentityResolver.fresh(stored.mojangCheckedAt(), clock.getAsLong())
                        && HttpPlayerIdentityResolver.fresh(stored.thirdPartyCheckedAt(), clock.getAsLong())))))) {
                    return CompletableFuture.completedFuture(publicIdentity(requestedName, stored, context.online()));
                }
                return resolveIdentity(requestedName, context.serverUuid(), stored)
                        .thenApply(resolution -> persistPublicIdentity(requestedName, context, resolution));
            });
            return created;
        });
        lookup.whenComplete((result, failure) -> inFlightPublicResolves.remove(key, lookup));
        return lookup;
    }

    private PlayerIdentity persistPublicIdentity(String playerName, ResolveContext context,
                                                  IdentityResolution resolution) {
        try {
            StoredPlayerIdentity stored = service.recordIdentityCheck(playerName, resolution, clock.getAsLong());
            return publicIdentity(playerName, stored, context.online());
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private PlayerIdentity publicIdentity(String playerName, StoredPlayerIdentity stored, boolean online) {
        try {
            if (stored == null) {
                return new PlayerIdentity(playerName, null, null, null, null, PlayerIdentityType.UNKNOWN, online, null);
            }
            java.util.OptionalLong lastSeen = service.playerLastSeenAt(playerName);
            return new PlayerIdentity(stored.playerName(), uuid(stored.serverUuid()), uuid(stored.offlineUuid()),
                    uuid(stored.mojangUuid()), uuid(stored.thirdPartyUuid()),
                    stored.identityType() == null ? PlayerIdentityType.UNKNOWN : stored.identityType(),
                    online, lastSeen.isEmpty() ? null : Instant.ofEpochMilli(lastSeen.getAsLong()));
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private boolean uuidContextValid(String normalizedPlayerName, UUID serverUuid, long generation) {
        return uuidContextValid(normalizedPlayerName, serverUuid, generation, false);
    }

    private boolean uuidContextValid(String normalizedPlayerName, UUID serverUuid, long generation,
                                     boolean allowDisabled) {
        return !closed && generation == uuidGeneration.get() && gameActive && !reconnectPending
                && (allowDisabled || settings.uuidRecordEnable())
                && onlinePlayers.containsKey(normalizedPlayerName)
                && serverUuid.equals(onlineServerUuids.get(normalizedPlayerName));
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private void cancelUuidCheck(String playerName) {
        synchronized (uuidScheduleLock) {
            ScheduledUuidCheck scheduled = scheduledUuidChecks.remove(normalize(playerName));
            if (scheduled != null) {
                scheduled.future().cancel(false);
                terminalUuidTargetLocked(scheduled.batch(), playerName, UuidOutcome.SKIPPED);
                scheduled.completion().complete(null);
            }
        }
    }

    private void invalidateUuidChecks() {
        uuidGeneration.incrementAndGet();
        synchronized (uuidScheduleLock) {
            for (ScheduledUuidCheck scheduled : scheduledUuidChecks.values()) {
                scheduled.future().cancel(false);
                terminalUuidTargetLocked(scheduled.batch(), scheduled.playerName(), UuidOutcome.SKIPPED);
                scheduled.completion().complete(null);
            }
            scheduledUuidChecks.clear();
        }
    }

    private UuidBatch beginUuidBatchLocked(List<String> playerNames, boolean manual) {
        UuidBatch batch = new UuidBatch(manual, playerNames);
        activeUuidBatches++;
        return batch;
    }

    private void terminalUuidTarget(UuidBatch batch, String playerName, UuidOutcome outcome) {
        synchronized (uuidScheduleLock) {
            terminalUuidTargetLocked(batch, playerName, outcome);
        }
    }

    private void terminalUuidTargetLocked(UuidBatch batch, String playerName, UuidOutcome outcome) {
        if (batch == null || batch.completionEmitted) {
            return;
        }
        UuidTarget target = batch.targets.get(normalize(playerName));
        if (target == null || target.terminal) {
            return;
        }
        target.terminal = true;
        switch (outcome) {
            case SUCCESS -> batch.succeeded++;
            case FAILED -> batch.failed++;
            case SKIPPED -> batch.skipped++;
        }
        completeUuidBatchIfDoneLocked(batch);
    }

    private void completeUuidBatchIfDoneLocked(UuidBatch batch) {
        if (batch.completionEmitted || batch.targets.values().stream().anyMatch(target -> !target.terminal)) {
            return;
        }
        batch.completionEmitted = true;
        activeUuidBatches--;
        if (batch.manual) {
            manualUuidScanActive = false;
        }
        if (batch.targets.size() == 1) {
            String playerName = batch.targets.values().iterator().next().playerName;
            logger.info(YELLOW + "UUID record completed for " + PLAYER_LOG_COLOR + "{}" + RESET + YELLOW
                            + ": total=1, succeeded={}, failed={}, skipped={}" + RESET,
                    playerName, batch.succeeded, batch.failed, batch.skipped);
        } else {
            logger.info(YELLOW + "UUID record completed: total={}, succeeded={}, failed={}, skipped={}" + RESET,
                    batch.targets.size(), batch.succeeded, batch.failed, batch.skipped);
        }
    }

    private void syncMonitorRosterFromBot() {
        onlinePlayers.clear();
        for (GameProfile profile : Bot.INSTANCE.players.values()) {
            String playerName = nameOf(profile);
            if (playerName != null && !playerName.isBlank()) {
                onlinePlayers.put(normalize(playerName), playerName);
            }
        }
        observeUuidRoster(Bot.INSTANCE.players.values());
    }

    private RosterDrift rosterDrift() {
        Set<String> bot = Bot.INSTANCE.players.values().stream()
                .map(PlayerMonitorListener::nameOf)
                .filter(name -> name != null && !name.isBlank())
                .map(PlayerMonitorListener::normalize)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> monitor = Set.copyOf(onlinePlayers.keySet());
        int missing = 0;
        for (String name : bot) {
            if (!monitor.contains(name)) {
                missing++;
            }
        }
        int extra = 0;
        for (String name : monitor) {
            if (!bot.contains(name)) {
                extra++;
            }
        }
        return new RosterDrift(bot.size(), monitor.size(), missing, extra);
    }

    private void recordLogoutAt(String playerName, long timestamp) {
        try {
            service.recordLogout(playerName, timestamp);
            log.info("queued logout for " + playerName);
        } catch (IOException error) {
            log.warn("failed to record player " + playerName + ": " + error.getMessage());
        }
    }

    private StatScanResult queueStatScan(Collection<GameProfile> profiles, boolean applyCooldown, int priority) {
        return queueStatScan(profiles, applyCooldown, priority, -1L);
    }

    private StatScanResult queueStatScan(Collection<GameProfile> profiles, boolean applyCooldown, int priority,
                                         long expectedGeneration) {
        List<String> playerNames = profiles.stream()
                .map(PlayerMonitorListener::nameOf)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toMap(PlayerMonitorListener::normalize,
                        name -> name, (first, second) -> second, LinkedHashMap::new))
                .values().stream()
                .toList();
        long now = clock.getAsLong();
        Map<String, Long> latestStats;
        try {
            latestStats = service.latestStatCapturedAt(playerNames);
        } catch (IOException error) {
            log.warn("failed to read batched Stat timestamps: " + error.getMessage());
            latestStats = Map.of();
        }

        int queued = 0;
        int cooldownSkipped = 0;
        synchronized (statBatchLock) {
            if (expectedGeneration >= 0L && (expectedGeneration != gameGeneration.get()
                    || closed || !gameActive || reconnectPending)) {
                return new StatScanResult(0, 0);
            }
            for (String playerName : playerNames) {
                if (onlinePlayers.put(normalize(playerName), playerName) == null) {
                    recordLogin(playerName, now);
                }
            }
            StatBatch batch = activeStatBatch;
            if (batch == null) {
                batch = new StatBatch(gameGeneration.get());
                activeStatBatch = batch;
                settings.beginStatOperation();
            }
            for (String playerName : playerNames) {
                Long lastStatAt = latestStats.get(normalize(playerName));
                boolean cooldownActive = isStatCooldownActive(lastStatAt, now);
                int targetPriority = statPriority(lastStatAt, now)
                        + (priority == StatQueue.PRIORITY_MANUAL ? 1 : 0);
                if (applyCooldown && cooldownActive) {
                    if (addTargetLocked(batch, playerName, targetPriority, true)) {
                        cooldownSkipped++;
                        scheduleNextStatCheck(playerName, lastStatAt);
                    }
                } else if (addTargetLocked(batch, playerName, targetPriority, false)) {
                    queued++;
                } else {
                    StatTarget target = batch.targets.get(normalize(playerName));
                    if (target != null && !target.terminal) {
                        target.priority = Math.max(target.priority, targetPriority);
                        if (!target.responseCaptured && !target.inFlight) {
                            statQueue.enqueue(target.playerName, target.priority, target.token);
                        }
                    }
                }
            }
            completeBatchIfDoneLocked(batch);
        }
        return new StatScanResult(queued, cooldownSkipped);
    }

    private void enqueueAutomaticJoinStat(String playerName, boolean brandNew, long generation) {
        Long lastStatAt = latestStatAt(playerName);
        synchronized (statBatchLock) {
            if (!acceptsJoin(generation) || entryRosterWait != null) {
                return;
            }
            if (isStatCooldownActive(lastStatAt, clock.getAsLong())) {
                scheduleNextStatCheck(playerName, lastStatAt);
                return;
            }
            StatBatch batch = activeStatBatch;
            if (batch == null) {
                batch = new StatBatch(gameGeneration.get());
                activeStatBatch = batch;
                settings.beginStatOperation();
            }
            int priority = brandNew ? StatQueue.PRIORITY_NEW_PLAYER : StatQueue.PRIORITY_JOIN;
            addTargetLocked(batch, playerName, priority, false);
        }
    }

    private boolean addTargetLocked(StatBatch batch, String playerName, int priority, boolean cooldownSkipped) {
        String key = normalize(playerName);
        if (batch.targets.containsKey(key)) {
            return false;
        }
        StatTarget target = new StatTarget(playerName, statTargetSequence.incrementAndGet(), priority);
        batch.targets.put(key, target);
        if (cooldownSkipped) {
            target.terminal = true;
            batch.skipped++;
        } else {
            activeStatCycles.add(key);
            statQueue.enqueue(playerName, priority, target.token);
        }
        return true;
    }

    private Long latestStatAt(String playerName) {
        try {
            return service.latestStat(playerName).map(snapshot -> snapshot.capturedAt).orElse(null);
        } catch (IOException error) {
            log.warn("failed to read latest stat for " + playerName + ": " + error.getMessage());
            return null;
        }
    }

    private boolean isStatCooldownActive(Long lastStatAt, long now) {
        int cooldownHours = settings.statCooldownHours();
        return cooldownHours > 0 && lastStatAt != null
                && now < saturatedAdd(lastStatAt, TimeUnit.HOURS.toMillis(cooldownHours));
    }

    private int statPriority(Long lastStatAt, long now) {
        if (lastStatAt == null) {
            return StatQueue.PRIORITY_NEW_PLAYER;
        }
        return isStatCooldownActive(lastStatAt, now) ? StatQueue.PRIORITY_ENTRY : StatQueue.PRIORITY_JOIN;
    }

    private void retryTimedOutStats() {
        for (String playerName : statResponses.expire()) {
            String normalizedName = normalize(playerName);
            int attempts = statAttempts.getOrDefault(normalizedName, 0);
            evaluateStatAttempt(playerName, attempts, currentStatToken(playerName), "timed out");
        }
    }

    private void handleStatSendFailure(String playerName) {
        String normalizedName = normalize(playerName);
        Long token = pendingStatDispatches.remove(normalizedName);
        statResponses.cancel(playerName);
        int attempts = statAttempts.merge(normalizedName, 1, Integer::sum);
        evaluateStatAttempt(playerName, attempts, token == null ? currentStatToken(playerName) : token,
                "failed to send");
    }

    private void handleStatPlayerNotFound(String playerName) {
        String normalizedName = normalize(playerName);
        statQueue.cancel(playerName);
        statAttempts.remove(normalizedName);
        pendingStatDispatches.remove(normalizedName);
        statResponses.cancel(playerName);
        terminalStatTarget(playerName, StatOutcome.NOT_FOUND);
        log.warn("skipping stat for " + playerName + ": system reported player does not exist");
        logger.warn("Skipping stat for {}: system reported player does not exist.", playerName);
    }

    private void evaluateStatAttempt(String playerName, int attempts, long token, String reason) {
        String normalizedName = normalize(playerName);
        synchronized (statBatchLock) {
            if (!isCurrentStatTargetLocked(normalizedName, token)) {
                return;
            }
            int maximumAttempts = settings.statAttempts();
            StatTarget target = currentStatTarget(normalizedName);
            if (gameActive && onlinePlayers.containsKey(normalizedName) && attempts < maximumAttempts) {
                log.warn("stat request " + reason + " for " + playerName
                        + "; retrying (" + attempts + "/" + maximumAttempts + ")");
                logger.warn("Stat request {} for {}; retrying ({}/{}).",
                        reason, playerName, attempts, maximumAttempts);
                target.inFlight = false;
                statQueue.enqueue(playerName, target.priority, token);
            } else if (attempts > 0) {
                log.warn("stat scan failed for " + playerName + " after " + attempts + " attempt(s)");
                logger.warn("Stat scan failed for {} after {} attempt(s).", playerName, attempts);
                terminalStatTarget(playerName, token, onlinePlayers.containsKey(normalizedName)
                        ? StatOutcome.RETRIES_EXHAUSTED : StatOutcome.OFFLINE);
            }
        }
    }

    private void beginStatCycle(String playerName) {
        synchronized (statBatchLock) {
            if (activeStatBatch == null) {
                activeStatBatch = new StatBatch(gameGeneration.get());
                settings.beginStatOperation();
            }
            String normalized = normalize(playerName);
            if (!activeStatBatch.targets.containsKey(normalized)) {
                activeStatBatch.targets.put(normalized, new StatTarget(
                        playerName, statTargetSequence.incrementAndGet(), StatQueue.PRIORITY_JOIN));
                activeStatCycles.add(normalized);
            }
        }
    }

    private boolean hasNeverRecordedStat(String playerName) {
        try {
            return service.latestStat(playerName).isEmpty();
        } catch (IOException error) {
            log.warn("failed to check whether player has Stat " + playerName + ": " + error.getMessage());
            return false;
        }
    }

    private long currentStatToken(String playerName) {
        synchronized (statBatchLock) {
            StatTarget target = currentStatTarget(normalize(playerName));
            return target == null ? 0L : target.token;
        }
    }

    private StatTarget currentStatTarget(String normalizedName) {
        return activeStatBatch == null ? null : activeStatBatch.targets.get(normalizedName);
    }

    private boolean isCurrentStatTarget(String normalizedName, long token) {
        synchronized (statBatchLock) {
            return isCurrentStatTargetLocked(normalizedName, token);
        }
    }

    private boolean isCurrentStatTargetLocked(String normalizedName, long token) {
        StatTarget target = currentStatTarget(normalizedName);
        return activeStatBatch != null && !activeStatBatch.cancelled
                && activeStatBatch.gameGeneration == gameGeneration.get()
                && target != null && !target.terminal && !target.responseCaptured && target.token == token;
    }

    private void terminalStatTarget(String playerName, StatOutcome outcome) {
        terminalStatTarget(playerName, currentStatToken(playerName), outcome);
    }

    private void terminalStatTarget(String playerName, long token, StatOutcome outcome) {
        String normalized = normalize(playerName);
        synchronized (statBatchLock) {
            StatBatch batch = activeStatBatch;
            if (batch == null) {
                return;
            }
            StatTarget target = batch.targets.get(normalized);
            if (target == null || target.token != token || target.terminal) {
                return;
            }
            terminalStatTargetLocked(target, outcome);
            completeBatchIfDoneLocked(batch);
        }
    }

    private void terminalStatTargetLocked(StatTarget target, StatOutcome outcome) {
        if (target.terminal) {
            return;
        }
        target.terminal = true;
        String normalized = normalize(target.playerName);
        statQueue.cancel(target.playerName);
        statResponses.cancel(target.playerName);
        statAttempts.remove(normalized);
        pendingStatDispatches.remove(normalized);
        activeStatCycles.remove(normalized);
        switch (outcome) {
            case SUCCESS -> activeStatBatch.succeeded++;
            case NOT_FOUND, RETRIES_EXHAUSTED -> activeStatBatch.failed++;
            case COOLDOWN, OFFLINE -> activeStatBatch.skipped++;
        }
    }

    private void completeBatchIfDoneLocked(StatBatch batch) {
        if (batch == null || batch != activeStatBatch || batch.cancelled || batch.completionEmitted
                || batch.targets.values().stream().anyMatch(target -> !target.terminal)) {
            return;
        }
        batch.completionEmitted = true;
        activeStatBatch = null;
        settings.endStatOperation();
        if (batch.targets.size() == 1) {
            String playerName = batch.targets.values().iterator().next().playerName;
            logger.info(YELLOW + "Stat scan completed for " + PLAYER_LOG_COLOR + "{}" + RESET + YELLOW
                            + ": total=1, succeeded={}, failed={}, skipped={}" + RESET,
                    playerName, batch.succeeded, batch.failed, batch.skipped);
        } else {
            logger.info(YELLOW + "Stat scan completed: total={}, succeeded={}, failed={}, skipped={}" + RESET,
                    batch.targets.size(), batch.succeeded, batch.failed, batch.skipped);
        }
    }

    private void scheduleNextStatCheck(String playerName, long lastStatAt) {
        MonitorSettings configured = settings.get();
        int cooldownHours = configured.statCooldownHours;
        if (cooldownHours <= 0 || closed || !gameActive) {
            cancelStatCheck(playerName);
            return;
        }
        long dueAt = saturatedAdd(lastStatAt, TimeUnit.HOURS.toMillis(cooldownHours));
        scheduleStatCheck(playerName, dueAt, gameGeneration.get());
    }

    private void scheduleStatCheck(String playerName, long dueAt, long generation) {
        String normalized = normalize(playerName);
        synchronized (statBatchLock) {
            cancelStatCheckLocked(normalized);
            long token = statTargetSequence.incrementAndGet();
            try {
                ScheduledFuture<?> future = retryExecutor.schedule(
                        () -> runScheduledStatCheck(playerName, generation, token),
                        Math.max(0L, dueAt - clock.getAsLong()), TimeUnit.MILLISECONDS);
                scheduledStatChecks.put(normalized, new ScheduledStatCheck(token, future));
            } catch (RejectedExecutionException ignored) {
                // Shutdown invalidates scheduled Stat work.
            }
        }
    }

    private void runScheduledStatCheck(String playerName, long generation, long token) {
        String normalized = normalize(playerName);
        synchronized (statBatchLock) {
            ScheduledStatCheck scheduled = scheduledStatChecks.get(normalized);
            if (scheduled == null || scheduled.token() != token
                    || !scheduledStatChecks.remove(normalized, scheduled)) {
                return;
            }
        }
        if (closed || generation != gameGeneration.get() || !gameActive || reconnectPending
                || !settings.get().statEnabled || !onlinePlayers.containsKey(normalized)) {
            return;
        }
        int cooldownHours = settings.get().statCooldownHours;
        if (cooldownHours <= 0) {
            return;
        }
        Long latest = latestStatAt(playerName);
        if (latest == null) {
            enqueueScheduledStat(playerName);
            return;
        }
        long dueAt = saturatedAdd(latest, TimeUnit.HOURS.toMillis(cooldownHours));
        if (clock.getAsLong() < dueAt) {
            scheduleStatCheck(playerName, dueAt, generation);
            return;
        }
        enqueueScheduledStat(playerName);
    }

    private void enqueueScheduledStat(String playerName) {
        synchronized (statBatchLock) {
            if (activeStatBatch == null) {
                activeStatBatch = new StatBatch(gameGeneration.get());
                settings.beginStatOperation();
            }
            addTargetLocked(activeStatBatch, playerName, StatQueue.PRIORITY_JOIN, false);
        }
    }

    private void cancelStatCheck(String playerName) {
        synchronized (statBatchLock) {
            cancelStatCheckLocked(normalize(playerName));
        }
    }

    private void cancelStatCheckLocked(String normalized) {
        ScheduledStatCheck scheduled = scheduledStatChecks.remove(normalized);
        if (scheduled != null) {
            scheduled.future().cancel(false);
        }
    }

    void applyStatSettingsNow() {
        cancelEntryRosterWait();
        for (String playerName : Set.copyOf(scheduledStatChecks.keySet())) {
            cancelStatCheck(playerName);
        }
        MonitorSettings configured = settings.get();
        if (!configured.statEnabled) {
            cancelStatWork();
            return;
        }
        if (gameActive && configured.statCooldownHours > 0) {
            for (String playerName : onlinePlayers.values()) {
                Long latest = latestStatAt(playerName);
                if (latest != null) {
                    scheduleNextStatCheck(playerName, latest);
                }
            }
        }
    }

    boolean hasActiveStatCapture() {
        return !activeStatCycles.isEmpty()
                || !pendingStatDispatches.isEmpty()
                || statResponses.hasPending()
                || System.nanoTime() < statOutputSuppressionUntilNanos;
    }

    boolean isProtectedFromEviction(String normalizedPlayerName) {
        String normalized = normalize(normalizedPlayerName);
        if (onlinePlayers.containsKey(normalized)
                || pendingStatDispatches.containsKey(normalized)
                || statAttempts.containsKey(normalized)
                || activeStatCycles.contains(normalized)
                || statQueue.contains(normalized)) {
            return true;
        }
        return statResponses.isExpecting(normalized);
    }

    // ------------------------------------------------------------------
    // Test-only hooks
    // ------------------------------------------------------------------

    void setGameActiveForTesting(boolean active) {
        gameActive = active;
    }

    void markOnlineForTesting(String playerName) {
        onlinePlayers.put(normalize(playerName), playerName);
    }

    void markOnlineForTesting(GameProfile profile) {
        markOnlineForTesting(nameOf(profile));
        observeUuid(profile);
    }

    StatScanResult queueStatScanForTesting(Collection<GameProfile> profiles, boolean automatic) {
        return queueStatScan(profiles, automatic, automatic
                ? StatQueue.PRIORITY_ENTRY : StatQueue.PRIORITY_MANUAL);
    }

    int activeStatBatchTotalForTesting() {
        synchronized (statBatchLock) {
            return activeStatBatch == null ? 0 : activeStatBatch.targets.size();
        }
    }

    int statPriorityForTesting(String playerName) {
        synchronized (statBatchLock) {
            StatTarget target = currentStatTarget(normalize(playerName));
            return target == null ? Integer.MIN_VALUE : target.priority;
        }
    }

    int scheduledStatCheckCountForTesting() {
        return scheduledStatChecks.size();
    }

    void scheduleNextStatCheckForTesting(String playerName, long lastStatAt) {
        scheduleNextStatCheck(playerName, lastStatAt);
    }

    void setBeforeStatSendForTesting(Runnable action) {
        beforeStatSend = action;
    }

    void setAfterStatDispatchForTesting(Runnable action) {
        afterStatDispatch = action;
    }

    void pollTimedOutEntryRosterForTesting() {
        synchronized (statBatchLock) {
            entryRosterWait = new StableRosterWait(clock.getAsLong() - ENTRY_ROSTER_TIMEOUT_MILLIS);
            entryRosterGeneration = gameGeneration.get();
        }
        pollEntryRoster(gameGeneration.get());
    }

    long gameGenerationForTesting() {
        return gameGeneration.get();
    }

    void pollEntryRosterForTesting(long generation) {
        synchronized (statBatchLock) {
            if (generation == entryRosterGeneration && entryRosterPoll != null) {
                entryRosterPoll.cancel(false);
            }
        }
        pollEntryRoster(generation);
    }

    int statAttemptsForTesting(String playerName) {
        return statAttempts.getOrDefault(normalize(playerName), 0);
    }

    boolean isPendingStatDispatchForTesting(String playerName) {
        return pendingStatDispatches.containsKey(normalize(playerName));
    }

    void markPendingStatDispatchForTesting(String playerName) {
        beginStatCycle(playerName);
        pendingStatDispatches.put(normalize(playerName), currentStatToken(playerName));
    }

    void handleStatSendFailureForTesting(String playerName) {
        beginStatCycle(playerName);
        handleStatSendFailure(playerName);
    }

    void evaluateStatAttemptForTesting(String playerName, int attempts) {
        beginStatCycle(playerName);
        evaluateStatAttempt(playerName, attempts, currentStatToken(playerName), "test");
    }

    boolean hasActiveStatCycleForTesting(String playerName) {
        return activeStatCycles.contains(normalize(playerName));
    }

    void runConnectionWatchdogForTesting() {
        stuckGameStateSince = System.currentTimeMillis() - CONNECTION_WATCHDOG_GRACE_MILLIS;
        rosterDriftSince = System.currentTimeMillis() - CONNECTION_WATCHDOG_GRACE_MILLIS;
        connectionWatchdog(true);
    }

    int scheduledUuidCheckCountForTesting() {
        return scheduledUuidChecks.size();
    }

    boolean scheduledUuidCheckForcedForTesting(String playerName) {
        ScheduledUuidCheck scheduled = scheduledUuidChecks.get(normalize(playerName));
        return scheduled != null && scheduled.force();
    }

    int scheduledUuidCheckAttemptForTesting(String playerName) {
        ScheduledUuidCheck scheduled = scheduledUuidChecks.get(normalize(playerName));
        return scheduled == null ? 0 : scheduled.attempt();
    }

    UUID scheduledServerUuidForTesting(String playerName) {
        ScheduledUuidCheck scheduled = scheduledUuidChecks.get(normalize(playerName));
        return scheduled == null ? null : scheduled.serverUuid();
    }

    UUID onlineServerUuidForTesting(String playerName) {
        return onlineServerUuids.get(normalize(playerName));
    }

    boolean manualUuidScanActiveForTesting() {
        synchronized (uuidScheduleLock) {
            return manualUuidScanActive;
        }
    }

    void runScheduledUuidCheckForTesting(String playerName) {
        String normalized = normalize(playerName);
        ScheduledUuidCheck scheduled;
        synchronized (uuidScheduleLock) {
            scheduled = scheduledUuidChecks.get(normalized);
            if (scheduled == null) {
                return;
            }
            scheduled.future().cancel(false);
        }
        runUuidCheck(onlinePlayers.get(normalized), scheduled.serverUuid(), uuidGeneration.get(),
                scheduled.token(), scheduled.force(), scheduled.attempt());
    }

    CompletableFuture<Void> awaitUuidTasksForTesting() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (uuidScheduleLock) {
                return CompletableFuture.allOf(scheduledUuidChecks.values().stream()
                        .filter(check -> check.future().getDelay(TimeUnit.MILLISECONDS) <= 1_000L)
                        .map(ScheduledUuidCheck::completion).toArray(CompletableFuture[]::new));
            }
        }, uuidExecutor).thenCompose(completion -> completion);
    }

    void runUuidCheckForTesting(GameProfile profile) {
        String playerName = nameOf(profile);
        onlinePlayers.put(normalize(playerName), playerName);
        onlineServerUuids.put(normalize(playerName), profile.getId());
        try {
            StoredPlayerIdentity previous = service.playerIdentity(playerName).orElse(null);
            IdentityResolution resolution = resolveIdentity(playerName, profile.getId(), previous).join();
            service.recordIdentityCheck(playerName, resolution, clock.getAsLong());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String nameOf(GameProfile profile) {
        return profile.getName();
    }

    private static String normalize(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(Set<String> names, String target) {
        String normalized = normalize(target);
        return names.stream().anyMatch(name -> normalize(name).equals(normalized));
    }

    private void clearOnlinePlayers() {
        onlinePlayers.clear();
        onlineServerUuids.clear();
    }

    private void recordLogin(String playerName, long now) {
        try {
            service.recordLogin(playerName, now);
            log.info("queued login for " + playerName);
        } catch (IOException error) {
            log.warn("failed to record player " + playerName + ": " + error.getMessage());
        }
    }

    record StatScanStatus(boolean gameActive, boolean reconnectPending, boolean rosterReconciling,
                          boolean forceFreshRoster, long reconnectGeneration, long lastDisconnectAt,
                          int onlinePlayers, int queued, int pendingDispatches, int activeCycles,
                          boolean waitingResponse, int botRoster, int monitorRoster, int missingFromMonitor,
                          int extraInMonitor, long systemChatReceived, long publicChatParsed,
                          long chatAcceptedByPlayerMonitor, long chatRejected, long chatParseFailed,
                          long chatDbFailed) {
    }

    record UuidScanStatus(int onlinePlayers, int queued, int activeCycles, boolean waitingResponse) {
    }

    record StatScanResult(int queued, int cooldownSkipped) {
    }

    private record RosterDrift(int botRoster, int monitorRoster, int missingFromMonitor, int extraInMonitor) {
    }

    private record ScheduledUuidCheck(String playerName, UUID serverUuid, long token, ScheduledFuture<?> future,
                                      CompletableFuture<Void> completion, boolean force, int attempt,
                                      UuidBatch batch, boolean queued, boolean inFlight) {
    }

    private static final class UuidBatch {
        private final boolean manual;
        private final Map<String, UuidTarget> targets = new LinkedHashMap<>();
        private int succeeded;
        private int failed;
        private int skipped;
        private boolean completionEmitted;

        private UuidBatch(boolean manual, List<String> playerNames) {
            this.manual = manual;
            for (String playerName : playerNames) {
                targets.put(normalize(playerName), new UuidTarget(playerName));
            }
        }
    }

    private static final class UuidTarget {
        private final String playerName;
        private boolean terminal;

        private UuidTarget(String playerName) {
            this.playerName = playerName;
        }
    }

    private enum UuidOutcome {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    private record ResolveContext(StoredPlayerIdentity stored, UUID serverUuid, boolean online) {
    }

    private record ScheduledStatCheck(long token, ScheduledFuture<?> future) {
    }

    private enum StatOutcome {
        SUCCESS,
        NOT_FOUND,
        RETRIES_EXHAUSTED,
        COOLDOWN,
        OFFLINE
    }

    private static final class StatTarget {
        private final String playerName;
        private final long token;
        private int priority;
        private boolean terminal;
        private boolean inFlight;
        private boolean responseCaptured;

        private StatTarget(String playerName, long token, int priority) {
            this.playerName = playerName;
            this.token = token;
            this.priority = priority;
        }
    }

    private static final class StatBatch {
        private final long gameGeneration;
        private final Map<String, StatTarget> targets = new LinkedHashMap<>();
        private int succeeded;
        private int failed;
        private int skipped;
        private boolean cancelled;
        private boolean completionEmitted;

        private StatBatch(long gameGeneration) {
            this.gameGeneration = gameGeneration;
        }
    }

    static final class StableRosterWait {
        private final long startedAt;
        private Set<String> previous = Set.of();
        private long stableSince;

        StableRosterWait(long startedAt) {
            this.startedAt = startedAt;
        }

        boolean sample(Set<String> roster, long now, long stableMillis, long timeoutMillis) {
            if (now - startedAt >= timeoutMillis) {
                return true;
            }
            if (roster.isEmpty()) {
                previous = Set.of();
                stableSince = 0L;
                return false;
            }
            if (!roster.equals(previous)) {
                previous = Set.copyOf(roster);
                stableSince = now;
                return false;
            }
            return now - stableSince >= stableMillis;
        }
    }

    private static final class SystemChatContext {
        private boolean publicChatEventSeen;
    }
}
