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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PlayerMonitorListener implements Listener {
    private final PlayerMonitorService service;
    private final PluginLog log;
    private final Logger logger;
    private final Set<String> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final StatResponseCollector statResponses = new StatResponseCollector();
    private final StatQueue statQueue;
    private volatile boolean gameActive;

    PlayerMonitorListener(PlayerMonitorService service, PluginLog log, Logger logger) {
        this.service = service;
        this.log = log;
        this.logger = logger;
        statQueue = new StatQueue(
                () -> gameActive,
                onlinePlayers::contains,
                command -> {
                    Bot.INSTANCE.sendCommand(command);
                    String playerName = command.substring("stat ".length());
                    log.info("sent stat for " + playerName);
                    logger.info("Sent stat for {}.", playerName);
                },
                statResponses::expect);
    }

    void close() {
        statQueue.close();
    }

    @EventHandler
    public void onServerChange(ServerChangeEvent event) {
        gameActive = event.getServer() == Server.Game;
        onlinePlayers.clear();
        statQueue.clear();
        log.info(gameActive ? "entered Game; monitoring enabled" : "left Game; monitoring disabled");
        logger.info(gameActive
                ? "Entered Game; started scanning online players."
                : "Left Game; stopped monitoring player activity.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!gameActive) {
            return;
        }
        String playerName = nameOf(event.getPlayerProfile());
        onlinePlayers.add(playerName);
        try {
            service.recordLogin(playerName, System.currentTimeMillis());
            log.info("recorded player " + playerName);
            statQueue.enqueue(playerName);
        } catch (IOException error) {
            log.info("failed to record player " + playerName + ": " + error.getMessage());
        }
    }

    @EventHandler
    public void onPlayerLeave(PlayerLeaveEvent event) {
        if (!gameActive) {
            return;
        }
        String playerName = nameOf(event.getPlayerProfile());
        onlinePlayers.remove(playerName);
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
    }

    @EventHandler
    public void onSystemChat(SystemChatMessageEvent event) {
        if (!gameActive) {
            return;
        }
        statResponses.accept(event.getText()).ifPresent(captured -> {
            try {
                service.recordStat(captured.playerName(), captured.snapshot());
                log.info("recorded stat for " + captured.playerName());
                logger.info("Recorded stat for {}.", captured.playerName());
            } catch (IOException error) {
                log.info("failed to record stat for " + captured.playerName() + ": " + error.getMessage());
            }
        });
    }

    private static String nameOf(GameProfile profile) {
        return profile.getName();
    }
}
