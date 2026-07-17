package huangdihd.xinbot.playermonitor;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.slf4j.Logger;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.PlayerJoinEvent;
import xin.bbtt.mcbot.events.PlayerLeaveEvent;
import xin.bbtt.mcbot.events.PublicChatEvent;
import xin.bbtt.mcbot.events.ServerChangeEvent;
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

    private final PlayerMonitorService service;
    private final PluginLog log;
    private final Logger logger;
    private final MonitorSettingsStore settings;
    private final PublicPlayerQueryResponder publicQueries;
    private final Set<String> onlinePlayers = ConcurrentHashMap.newKeySet();
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
                    Bot.INSTANCE.sendCommand(command);
                    String playerName = command.substring("stat ".length());
                    statAttempts.merge(playerName, 1, Integer::sum);
                    log.info("sent stat for " + playerName);
                    logger.info("Sent stat for {}.", playerName);
                },
                statResponses::expect);
        retryExecutor.scheduleWithFixedDelay(this::retryTimedOutStats, 100L, 100L, TimeUnit.MILLISECONDS);
    }

    void close() {
        statQueue.close();
        retryExecutor.shutdownNow();
        statResponses.clear();
        statAttempts.clear();
    }

    @EventHandler
    public void onServerChange(ServerChangeEvent event) {
        gameActive = event.getServer() == Server.Game;
        onlinePlayers.clear();
        statQueue.clear();
        statResponses.clear();
        statAttempts.clear();
        log.info(gameActive ? "entered Game; monitoring enabled" : "left Game; monitoring disabled");
        logger.info(gameActive
                ? "Entered Game; started scanning online players."
                : "Left Game; stopped monitoring player activity.");
        if (gameActive && settings.autoScanOnGameEntry() && settings.statScanEnabled()) {
            int queued = queueStatScan(Bot.INSTANCE.players.values());
            log.info("queued automatic stat scan for " + queued + " online players");
            logger.info("Queued automatic stat scan for {} online players.", queued);
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
            statQueue.enqueue(playerName);
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
        statResponses.accept(event.getText()).ifPresent(captured -> {
            try {
                service.recordStat(captured.playerName(), captured.snapshot());
                statAttempts.remove(captured.playerName());
                log.info("recorded stat for " + captured.playerName());
                logger.info("Recorded stat for {}.", captured.playerName());
            } catch (IOException error) {
                log.info("failed to record stat for " + captured.playerName() + ": " + error.getMessage());
            }
        });
    }

    int scanAllOnlinePlayers() {
        if (!gameActive) {
            return -1;
        }
        int queued = queueStatScan(Bot.INSTANCE.players.values());
        log.info("queued manual stat scan for " + queued + " online players");
        return queued;
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

    private int queueStatScan(Collection<GameProfile> profiles) {
        int queued = 0;
        for (GameProfile profile : profiles) {
            String playerName = nameOf(profile);
            if (onlinePlayers.add(playerName)) {
                recordLogin(playerName, System.currentTimeMillis());
            }
            if (statQueue.enqueue(playerName)) {
                queued++;
            }
        }
        return queued;
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
}
