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
import java.util.concurrent.TimeUnit;

final class PlayerMonitorListener implements Listener {
    private static final long STAT_WRITE_DELAY_MILLIS = 25L;

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
    private volatile boolean forceFreshRoster;
    private volatile long disconnectAt;
    private final Object connectionStateLock = new Object();
    private final Set<String> disconnectedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> reconnectedNewPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> reconnectedBrandNewPlayers = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> disconnectFinalizer;

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
    }

    void close() {
        synchronized (connectionStateLock) {
            closed = true;
            reconnectPending = false;
            rosterReconciling = false;
            forceFreshRoster = false;
            disconnectedPlayers.clear();
            reconnectedNewPlayers.clear();
            reconnectedBrandNewPlayers.clear();
            if (disconnectFinalizer != null) {
                disconnectFinalizer.cancel(false);
                disconnectFinalizer = null;
            }
        }
        statQueue.close();
        clearStatTracking();
        retryExecutor.shutdownNow();
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
        gameActive = true;
        boolean resumed = reconcileAfterReconnect(gameEntryAt);
        if (!resumed) {
            clearOnlinePlayers();
            clearStatTracking();
            recordFreshRoster(gameEntryAt);
            forceFreshRoster = false;
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
        boolean brandNew = isBrandNewPlayer(playerName);
        if (onlinePlayers.put(normalize(playerName), playerName) == null) {
            recordLogin(playerName, System.currentTimeMillis());
        }
        if (settings.statEnabled() && settings.scanOnJoin()) {
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
            log.info("recorded logout for " + playerName);
        } catch (IOException error) {
            log.warn("failed to record player " + playerName + ": " + error.getMessage());
        }
    }

    @EventHandler
    public void onPublicChat(PublicChatEvent event) {
        if (!gameActive) {
            return;
        }
        String message = EmojiFilter.removeEmojiExceptCheckMarks(event.getMessage());
        if (message.isEmpty()) {
            return;
        }
        String playerName = nameOf(event.getSender());
        try {
            service.recordChat(playerName, message, System.currentTimeMillis());
        } catch (IOException error) {
            log.warn("failed to record player " + playerName + ": " + error.getMessage());
        }
    }

    @EventHandler
    public void onSystemChat(SystemChatMessageEvent event) {
        if (!gameActive) {
            return;
        }
        retryTimedOutStats();
        statResponses.accept(event.getText()).ifPresent(captured -> {
            String normalizedName = normalize(captured.playerName());
            statAttempts.remove(normalizedName);
            pendingStatDispatches.remove(normalizedName);
            finishStatCycle(captured.playerName());
            retryExecutor.schedule(() -> {
                try {
                    service.recordStat(captured.playerName(), captured.snapshot());
                    log.info("recorded stat for " + captured.playerName());
                    logger.info("\u001B[94mRecorded stat for {}.\u001B[0m", captured.playerName());
                } catch (IOException error) {
                    log.warn("failed to record stat for " + captured.playerName() + ": " + error.getMessage());
                }
            }, STAT_WRITE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        });
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
            disconnectFinalizer = retryExecutor.schedule(
                    () -> finalizeDisconnectedSessions(expectedDisconnectAt),
                    remainingMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    private boolean beginReconnectWindow(long now) {
        synchronized (connectionStateLock) {
            if (reconnectPending) {
                return false;
            }
            disconnectAt = now;
            disconnectedPlayers.clear();
            disconnectedPlayers.addAll(onlinePlayers.values());
            reconnectPending = true;
            rosterReconciling = false;
            if (disconnectFinalizer != null) {
                disconnectFinalizer.cancel(false);
            }
            disconnectFinalizer = retryExecutor.schedule(
                    () -> finalizeDisconnectedSessions(now),
                    TimeUnit.MINUTES.toMillis(settings.disconnectTimeoutMinutes()),
                    TimeUnit.MILLISECONDS);
        }
        gameActive = false;
        clearOnlinePlayers();
        clearStatTracking();
        return true;
    }

    private void recordFreshRoster(long gameEntryAt) {
        Set<String> currentPlayers = Bot.INSTANCE.players.values().stream()
                .map(PlayerMonitorListener::nameOf)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toSet());
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
        Set<String> previousPlayers;
        long lostAt;
        synchronized (connectionStateLock) {
            if (!reconnectPending) {
                return false;
            }
            rosterReconciling = true;
            previousPlayers = Set.copyOf(disconnectedPlayers);
            lostAt = disconnectAt;
        }

        Set<String> currentPlayers = Bot.INSTANCE.players.values().stream()
                .map(PlayerMonitorListener::nameOf)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        int continued = 0;
        int loggedOut = 0;
        int loggedIn = 0;
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
        for (String playerName : currentPlayers) {
            if (!containsIgnoreCase(previousPlayers, playerName)) {
                boolean brandNew = isBrandNewPlayer(playerName);
                recordLogin(playerName, gameEntryAt);
                reconnectedNewPlayers.add(playerName);
                if (brandNew) {
                    reconnectedBrandNewPlayers.add(normalize(playerName));
                }
                loggedIn++;
            }
        }

        synchronized (connectionStateLock) {
            reconnectPending = false;
            rosterReconciling = false;
            disconnectedPlayers.clear();
            if (disconnectFinalizer != null) {
                disconnectFinalizer.cancel(false);
                disconnectFinalizer = null;
            }
        }
        log.info("reconciled Game roster: continued=" + continued
                + ", loggedOut=" + loggedOut + ", loggedIn=" + loggedIn);
        return true;
    }

    private void finalizeDisconnectedSessions(long expectedDisconnectAt) {
        Set<String> playersToClose;
        synchronized (connectionStateLock) {
            if (!reconnectPending || disconnectAt != expectedDisconnectAt) {
                return;
            }
            reconnectPending = false;
            rosterReconciling = false;
            forceFreshRoster = true;
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

    private void recordLogoutAt(String playerName, long timestamp) {
        try {
            service.recordLogout(playerName, timestamp);
            log.info("recorded logout for " + playerName);
        } catch (IOException error) {
            log.warn("failed to record player " + playerName + ": " + error.getMessage());
        }
    }

    private StatScanResult queueStatScan(Collection<GameProfile> profiles, boolean applyCooldown, int priority) {
        int queued = 0;
        int cooldownSkipped = 0;
        for (GameProfile profile : profiles) {
            String playerName = nameOf(profile);
            if (onlinePlayers.put(normalize(playerName), playerName) == null) {
                recordLogin(playerName, System.currentTimeMillis());
            }
            if (applyCooldown && isAutomaticStatCooldownActive(playerName)) {
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
            return service.findRecord(playerName).isEmpty();
        } catch (IOException error) {
            log.warn("failed to check whether player is new " + playerName + ": " + error.getMessage());
            return false;
        }
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
            log.info("recorded login for " + playerName);
        } catch (IOException error) {
            log.warn("failed to record player " + playerName + ": " + error.getMessage());
        }
    }

    private record StatScanResult(int queued, int cooldownSkipped) {
    }
}
