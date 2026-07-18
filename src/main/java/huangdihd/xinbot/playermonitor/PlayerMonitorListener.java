package huangdihd.xinbot.playermonitor;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.slf4j.Logger;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.EventPriority;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.PlayerJoinEvent;
import xin.bbtt.mcbot.events.PlayerLeaveEvent;
import xin.bbtt.mcbot.events.PublicChatEvent;
import xin.bbtt.mcbot.events.ServerChangeEvent;
import xin.bbtt.mcbot.events.SendCommandEvent;
import xin.bbtt.mcbot.events.SystemChatMessageEvent;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class PlayerMonitorListener implements Listener {
    private static final int MAX_STAT_ATTEMPTS = 3;
    private static final long STAT_WRITE_DELAY_MILLIS = 25L;
    private static final long AUTOMATIC_STAT_COOLDOWN_MILLIS = TimeUnit.HOURS.toMillis(24L);

    private final PlayerMonitorService service;
    private final PluginLog log;
    private final Logger logger;
    private final MonitorSettingsStore settings;
    private final PublicPlayerQueryResponder publicQueries;
    private final Set<String> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingStatDispatches = ConcurrentHashMap.newKeySet();
    private final StatResponseCollector statResponses = new StatResponseCollector();
    private final Map<String, Integer> statAttempts = new ConcurrentHashMap<>();
    private final StatQueue statQueue;
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XinPlayerMonitor-stat-retry");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean gameActive;

    PlayerMonitorListener(PlayerMonitorService service, PluginLog log, Logger logger, MonitorSettingsStore settings) {
        this.service = service;
        this.log = log;
        this.logger = logger;
        this.settings = settings;
        publicQueries = new PublicPlayerQueryResponder(service, Bot.INSTANCE::sendChatMessage, log);
        statQueue = new StatQueue(
                () -> gameActive,
                onlinePlayers::contains,
                settings::statIntervalMillis,
                command -> {
                    String playerName = command.substring("stat ".length());
                    pendingStatDispatches.add(playerName);
                    Bot.INSTANCE.sendCommand(command);
                    log.info("queued stat for " + playerName);
                },
                ignored -> {
                });
        retryExecutor.scheduleWithFixedDelay(this::retryTimedOutStats, 100L, 100L, TimeUnit.MILLISECONDS);
    }

    void close() {
        statQueue.close();
        retryExecutor.shutdownNow();
        statResponses.clear();
        statAttempts.clear();
        pendingStatDispatches.clear();
    }

    @EventHandler
    public void onServerChange(ServerChangeEvent event) {
        gameActive = event.getServer() == Server.Game;
        onlinePlayers.clear();
        statQueue.clear();
        statResponses.clear();
        statAttempts.clear();
        pendingStatDispatches.clear();
        log.info(gameActive ? "entered Game; monitoring enabled" : "left Game; monitoring disabled");
        logger.info(gameActive
                ? "Entered Game; started scanning online players."
                : "Left Game; stopped monitoring player activity.");
        if (gameActive && settings.autoScanOnGameEntry() && settings.statScanEnabled()) {
            StatScanResult result = queueStatScan(Bot.INSTANCE.players.values(), true);
            log.info("queued automatic stat scan for " + result.queued() + " online players");
            logger.info("Queued automatic stat scan for {} online players; skipped {} in cooldown.",
                    result.queued(), result.cooldownSkipped());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!gameActive) {
            return;
        }
        String playerName = nameOf(event.getPlayerProfile());
        if (onlinePlayers.add(playerName)) {
            recordLogin(playerName, System.currentTimeMillis());
        }
        if (settings.statScanEnabled()) {
            enqueueAutomaticJoinStat(playerName);
        }
    }

    @EventHandler
    public void onPlayerLeave(PlayerLeaveEvent event) {
        if (!gameActive) {
            return;
        }
        String playerName = nameOf(event.getPlayerProfile());
        statResponses.cancel(playerName);
        statAttempts.remove(playerName);
        pendingStatDispatches.remove(playerName);
        if (!onlinePlayers.remove(playerName)) {
            return;
        }
        try {
            service.recordLogout(playerName, System.currentTimeMillis());
            log.info("recorded player " + playerName);
        } catch (IOException error) {
            log.info("failed to record player " + playerName + ": " + error.getMessage());
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
            log.info("recorded player " + playerName);
        } catch (IOException error) {
            log.info("failed to record player " + playerName + ": " + error.getMessage());
        }
        publicQueries.handle(message);
    }

    @EventHandler
    public void onSystemChat(SystemChatMessageEvent event) {
        if (!gameActive) {
            return;
        }
        retryTimedOutStats();
        statResponses.accept(event.getText()).ifPresent(captured -> retryExecutor.schedule(() -> {
            try {
                service.recordStat(captured.playerName(), captured.snapshot());
                statAttempts.remove(captured.playerName());
                log.info("recorded stat for " + captured.playerName());
                logger.info("\u001B[94mRecorded stat for {}.\u001B[0m", captured.playerName());
            } catch (IOException error) {
                log.info("failed to record stat for " + captured.playerName() + ": " + error.getMessage());
            }
        }, STAT_WRITE_DELAY_MILLIS, TimeUnit.MILLISECONDS));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSendCommand(SendCommandEvent event) {
        String command = event.getCommand();
        if (command == null || !command.startsWith("stat ")) {
            return;
        }
        String playerName = command.substring("stat ".length()).trim();
        if (!pendingStatDispatches.contains(playerName)) {
            return;
        }
        if (event.isDefaultActionCancelled()) {
            pendingStatDispatches.remove(playerName);
            log.info("stat command cancelled for " + playerName);
            return;
        }
        if (pendingStatDispatches.remove(playerName)) {
            statAttempts.merge(playerName, 1, Integer::sum);
            statResponses.expect(playerName);
            log.info("sent stat for " + playerName);
            logger.info("Sent stat for {}.", playerName);
        }
    }

    int scanAllOnlinePlayers() {
        if (!gameActive) {
            return -1;
        }
        StatScanResult result = queueStatScan(Bot.INSTANCE.players.values(), false);
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

    private StatScanResult queueStatScan(Collection<GameProfile> profiles, boolean applyCooldown) {
        int queued = 0;
        int cooldownSkipped = 0;
        for (GameProfile profile : profiles) {
            String playerName = nameOf(profile);
            if (onlinePlayers.add(playerName)) {
                recordLogin(playerName, System.currentTimeMillis());
            }
            if (applyCooldown && isAutomaticStatCooldownActive(playerName)) {
                cooldownSkipped++;
                continue;
            }
            if (statQueue.enqueue(playerName)) {
                queued++;
            }
        }
        return new StatScanResult(queued, cooldownSkipped);
    }

    private void enqueueAutomaticJoinStat(String playerName) {
        if (isAutomaticStatCooldownActive(playerName)) {
            log.info("skipped automatic stat for " + playerName + "; cooldown active");
            return;
        }
        statQueue.enqueueFirst(playerName);
    }

    private boolean isAutomaticStatCooldownActive(String playerName) {
        try {
            return service.hasStatCapturedAtOrAfter(
                    playerName,
                    System.currentTimeMillis() - AUTOMATIC_STAT_COOLDOWN_MILLIS);
        } catch (IOException error) {
            log.info("failed to check stat cooldown for " + playerName + ": " + error.getMessage());
            return false;
        }
    }

    private void retryTimedOutStats() {
        for (String playerName : statResponses.expire()) {
            int attempts = statAttempts.getOrDefault(playerName, 0);
            if (gameActive && onlinePlayers.contains(playerName) && attempts < MAX_STAT_ATTEMPTS) {
                logger.warn("Stat response timed out for {}; retrying.", playerName);
                statQueue.enqueue(playerName);
            } else if (attempts > 0) {
                logger.warn("Stat scan failed for {} after {} attempts.", playerName, attempts);
                statAttempts.remove(playerName);
            }
        }
    }

    private static String nameOf(GameProfile profile) {
        return profile.getName();
    }

    private void recordLogin(String playerName, long now) {
        try {
            service.recordLogin(playerName, now);
            log.info("recorded player " + playerName);
        } catch (IOException error) {
            log.info("failed to record player " + playerName + ": " + error.getMessage());
        }
    }

    private record StatScanResult(int queued, int cooldownSkipped) {
    }
}
