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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlayerMonitorListener implements Listener {
    private static final long STAT_WRITE_DELAY_MILLIS = 25L;
    private static final long CONNECTION_WATCHDOG_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long CONNECTION_WATCHDOG_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final Pattern MINECRAFT_FORMAT_CODE = Pattern.compile("(?i)§[0-9a-fk-or]");
    private static final Pattern PUBLIC_CHAT_TEXT = Pattern.compile(
            "^(?:§[0-9a-fk-or])*\\s*<((?:(?:§[0-9a-fk-or])|[^>])+)>(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final PlayerMonitorService service;
    private final PluginLog log;
    private final Logger logger;
    private final MonitorSettingsStore settings;
    private final Map<String, String> onlinePlayers = new ConcurrentHashMap<>();
    private final Set<String> pendingStatDispatches = ConcurrentHashMap.newKeySet();
    private final Set<String> activeStatCycles = ConcurrentHashMap.newKeySet();
    private final StatResponseCollector statResponses = new StatResponseCollector();
    private final Map<String, Integer> statAttempts = new ConcurrentHashMap<>();
    private final StatQueue statQueue;
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XinPlayerMonitor-stat-retry");
        thread.setDaemon(true);
        return thread;
    });
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
        this.service = service;
        this.log = log;
        this.logger = logger;
        this.settings = settings;
        statQueue = new StatQueue(
                () -> gameActive,
                name -> onlinePlayers.containsKey(normalize(name)),
                settings::statSendIntervalMillis,
                command -> {
                    String playerName = command.substring("stat ".length());
                    beginStatCycle(playerName);
                    pendingStatDispatches.add(normalize(playerName));
                    Bot.INSTANCE.sendCommand(command);
                },
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
        statQueue.close();
        clearStatTracking();
        retryExecutor.shutdown();
        waitForPendingStatWrites();
        retryExecutor.shutdownNow();
        try {
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
        boolean enteringGame = event.getServer() == Server.Game;
        if (!enteringGame) {
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
                if (closed || reconnectPending || rosterReconciling) {
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
                    recordFreshRoster(gameEntryAt, recoverStaleSessions);
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

        if (resumed && settings.statEnabled() && settings.scanOnJoin()) {
            for (String playerName : reconnectedNewPlayers) {
                enqueueAutomaticJoinStat(playerName, reconnectedBrandNewPlayers.contains(normalize(playerName)));
            }
        }
        reconnectedNewPlayers.clear();
        reconnectedBrandNewPlayers.clear();

        if (settings.scanOnEntry() && settings.statEnabled()) {
            StatScanResult result = queueStatScan(Bot.INSTANCE.players.values(), true, StatQueue.PRIORITY_ENTRY);
            log.info("queued automatic stat scan for " + result.queued() + " online players");
            logger.info("Queued automatic stat scan for {} online players; skipped {} in cooldown.",
                    result.queued(), result.cooldownSkipped());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!gameActive || reconnectPending || rosterReconciling) {
            return;
        }
        String playerName = nameOf(event.getPlayerProfile());
        boolean scanJoinStat = settings.statEnabled() && settings.scanOnJoin();
        boolean brandNew = scanJoinStat && isBrandNewPlayer(playerName);
        if (onlinePlayers.put(normalize(playerName), playerName) == null) {
            recordLogin(playerName, System.currentTimeMillis());
        }
        if (scanJoinStat) {
            enqueueAutomaticJoinStat(playerName, brandNew);
        }
    }

    @EventHandler
    public void onPlayerLeave(PlayerLeaveEvent event) {
        if (!gameActive || reconnectPending || rosterReconciling) {
            return;
        }
        String playerName = nameOf(event.getPlayerProfile());
        statResponses.cancel(playerName);
        statAttempts.remove(normalize(playerName));
        pendingStatDispatches.remove(normalize(playerName));
        finishStatCycle(playerName);
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
                    retryTimedOutStats();
                    statResponses.accept(event.getText()).ifPresent(captured -> {
                        statOutputSuppressionUntilNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                        String normalizedName = normalize(captured.playerName());
                        statAttempts.remove(normalizedName);
                        pendingStatDispatches.remove(normalizedName);
                        finishStatCycle(captured.playerName());
                        scheduleStatWrite(captured);
                    });
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

    private void scheduleStatWrite(StatResponseCollector.CapturedStat captured) {
        synchronized (statWriteLock) {
            pendingStatWrites++;
        }
        Runnable write = () -> persistCapturedStat(captured);
        try {
            retryExecutor.schedule(write, STAT_WRITE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException error) {
            write.run();
        }
    }

    private void persistCapturedStat(StatResponseCollector.CapturedStat captured) {
        try {
            service.recordStat(captured.playerName(), captured.snapshot());
            log.info("queued stat write for " + captured.playerName());
            logger.info("\u001B[94mQueued stat write for {}.\u001B[0m", captured.playerName());
        } catch (IOException error) {
            log.warn("failed to record stat for " + captured.playerName() + ": " + error.getMessage());
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
        if (!pendingStatDispatches.contains(normalizedName)) {
            return;
        }
        if (event.isDefaultActionCancelled()) {
            pendingStatDispatches.remove(normalizedName);
            log.warn("stat command cancelled for " + playerName);
            handleStatSendFailure(playerName);
            return;
        }
        if (pendingStatDispatches.remove(normalizedName)) {
            statAttempts.merge(normalizedName, 1, Integer::sum);
            statResponses.expect(playerName, settings.statTimeoutMillis());
            logger.info("Sent stat for {}.", playerName);
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
        Set<String> currentPlayers = Bot.INSTANCE.players.values().stream()
                .map(PlayerMonitorListener::nameOf)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (recoverStaleSessions) {
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
        for (String playerName : currentPlayers) {
            onlinePlayers.put(normalize(playerName), playerName);
            recordLogin(playerName, gameEntryAt);
        }
    }

    private void clearStatTracking() {
        statQueue.clear();
        statResponses.clear();
        statAttempts.clear();
        pendingStatDispatches.clear();
        for (String normalizedKey : Set.copyOf(activeStatCycles)) {
            if (activeStatCycles.remove(normalizedKey)) {
                settings.endStatOperation();
            }
        }
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
                    boolean brandNew = trackBrandNewPlayers && isBrandNewPlayer(playerName);
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
            } finally {
                rosterReconciling = false;
            }
            log.warn("connection watchdog reconciled roster drift: loggedIn=" + loggedIn
                    + ", loggedOut=" + loggedOut);
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
        List<String> playerNames = profiles.stream()
                .map(PlayerMonitorListener::nameOf)
                .filter(name -> name != null && !name.isBlank())
                .toList();
        long now = System.currentTimeMillis();
        for (String playerName : playerNames) {
            if (onlinePlayers.put(normalize(playerName), playerName) == null) {
                recordLogin(playerName, now);
            }
        }

        Set<String> cooldownPlayers = Set.of();
        if (applyCooldown && settings.statCooldownHours() > 0 && !playerNames.isEmpty()) {
            long cutoffAt = now - TimeUnit.HOURS.toMillis(settings.statCooldownHours());
            try {
                cooldownPlayers = service.playersWithStatCapturedAtOrAfter(playerNames, cutoffAt);
            } catch (IOException error) {
                log.warn("failed to check batched stat cooldown: " + error.getMessage());
            }
        }

        int queued = 0;
        int cooldownSkipped = 0;
        for (String playerName : playerNames) {
            if (cooldownPlayers.contains(normalize(playerName))) {
                cooldownSkipped++;
                continue;
            }
            if (enqueueStatIfAvailable(playerName, priority)) {
                queued++;
            }
        }
        return new StatScanResult(queued, cooldownSkipped);
    }

    private void enqueueAutomaticJoinStat(String playerName, boolean brandNew) {
        if (isAutomaticStatCooldownActive(playerName)) {
            return;
        }
        int priority;
        if (brandNew) {
            priority = StatQueue.PRIORITY_NEW_PLAYER;
        } else if (settings.prioritizeJoinStat()) {
            priority = StatQueue.PRIORITY_JOIN;
        } else {
            priority = StatQueue.PRIORITY_ENTRY;
        }
        enqueueStatIfAvailable(playerName, priority);
    }

    private boolean enqueueStatIfAvailable(String playerName, int priority) {
        String normalizedName = normalize(playerName);
        if (activeStatCycles.contains(normalizedName)
                || pendingStatDispatches.contains(normalizedName)
                || statResponses.isExpecting(normalizedName)) {
            return false;
        }
        return statQueue.enqueue(playerName, priority);
    }

    private boolean isAutomaticStatCooldownActive(String playerName) {
        int cooldownHours = settings.statCooldownHours();
        if (cooldownHours <= 0) {
            return false;
        }
        try {
            return service.hasStatCapturedAtOrAfter(
                    playerName,
                    System.currentTimeMillis() - TimeUnit.HOURS.toMillis(cooldownHours));
        } catch (IOException error) {
            log.warn("failed to check stat cooldown for " + playerName + ": " + error.getMessage());
            return false;
        }
    }

    private void retryTimedOutStats() {
        for (String playerName : statResponses.expire()) {
            String normalizedName = normalize(playerName);
            int attempts = statAttempts.getOrDefault(normalizedName, 0);
            evaluateStatAttempt(playerName, attempts, "timed out");
        }
    }

    private void handleStatSendFailure(String playerName) {
        String normalizedName = normalize(playerName);
        pendingStatDispatches.remove(normalizedName);
        statResponses.cancel(playerName);
        int attempts = statAttempts.merge(normalizedName, 1, Integer::sum);
        evaluateStatAttempt(playerName, attempts, "failed to send");
    }

    private void evaluateStatAttempt(String playerName, int attempts, String reason) {
        String normalizedName = normalize(playerName);
        int maximumAttempts = settings.statAttempts();
        if (gameActive && onlinePlayers.containsKey(normalizedName) && attempts < maximumAttempts) {
            log.warn("stat request " + reason + " for " + playerName
                    + "; retrying (" + attempts + "/" + maximumAttempts + ")");
            logger.warn("Stat request {} for {}; retrying ({}/{}).",
                    reason, playerName, attempts, maximumAttempts);
            statQueue.enqueue(playerName, StatQueue.PRIORITY_MANUAL);
        } else if (attempts > 0) {
            log.warn("stat scan failed for " + playerName + " after " + attempts + " attempt(s)");
            logger.warn("Stat scan failed for {} after {} attempt(s).", playerName, attempts);
            statAttempts.remove(normalizedName);
            pendingStatDispatches.remove(normalizedName);
            statResponses.cancel(playerName);
            finishStatCycle(playerName);
        }
    }

    private void beginStatCycle(String playerName) {
        if (activeStatCycles.add(normalize(playerName))) {
            settings.beginStatOperation();
        }
    }

    private void finishStatCycle(String playerName) {
        if (activeStatCycles.remove(normalize(playerName))) {
            settings.endStatOperation();
        }
    }

    private boolean isBrandNewPlayer(String playerName) {
        try {
            return !service.playerExists(playerName);
        } catch (IOException error) {
            log.warn("failed to check whether player is new " + playerName + ": " + error.getMessage());
            return false;
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
                || pendingStatDispatches.contains(normalized)
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

    int statAttemptsForTesting(String playerName) {
        return statAttempts.getOrDefault(normalize(playerName), 0);
    }

    boolean isPendingStatDispatchForTesting(String playerName) {
        return pendingStatDispatches.contains(normalize(playerName));
    }

    void markPendingStatDispatchForTesting(String playerName) {
        pendingStatDispatches.add(normalize(playerName));
    }

    void handleStatSendFailureForTesting(String playerName) {
        beginStatCycle(playerName);
        handleStatSendFailure(playerName);
    }

    void evaluateStatAttemptForTesting(String playerName, int attempts) {
        beginStatCycle(playerName);
        evaluateStatAttempt(playerName, attempts, "test");
    }

    boolean hasActiveStatCycleForTesting(String playerName) {
        return activeStatCycles.contains(normalize(playerName));
    }

    void runConnectionWatchdogForTesting() {
        stuckGameStateSince = System.currentTimeMillis() - CONNECTION_WATCHDOG_GRACE_MILLIS;
        rosterDriftSince = System.currentTimeMillis() - CONNECTION_WATCHDOG_GRACE_MILLIS;
        connectionWatchdog(true);
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

    private record StatScanResult(int queued, int cooldownSkipped) {
    }

    private record RosterDrift(int botRoster, int monitorRoster, int missingFromMonitor, int extraInMonitor) {
    }

    private static final class SystemChatContext {
        private boolean publicChatEventSeen;
    }
}
