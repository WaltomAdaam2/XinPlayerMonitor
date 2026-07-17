package huangdihd.xinbot.playermonitor;

import huangdihd.xinbot.playermonitor.model.LoginSession;
import huangdihd.xinbot.playermonitor.model.PlayerPermissions;
import huangdihd.xinbot.playermonitor.model.PlayerRecord;
import huangdihd.xinbot.playermonitor.model.StatSnapshot;
import org.slf4j.Logger;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.TabExecutor;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class PlayerMonitorCommand extends TabExecutor {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final PlayerMonitorService service;
    private final Logger logger;

    PlayerMonitorCommand(PlayerMonitorService service, Logger logger) {
        this.service = service;
        this.logger = logger;
    }

    @Override
    public void onCommand(Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            print("Usage: player <name> [stat|lastlogin|recentlogin]");
            return;
        }
        try {
            PlayerRecord record = service.getRecord(args[0]);
            if (args.length == 1) {
                print(summary(record));
                return;
            }
            switch (args[1].toLowerCase()) {
                case "stat" -> print(stat(record));
                case "lastlogin" -> print(lastLogin(record));
                case "recentlogin" -> recentLogins(record).forEach(this::print);
                default -> print("Usage: player <name> [stat|lastlogin|recentlogin]");
            }
        } catch (IOException error) {
            print("Unable to read player record: " + error.getMessage());
        }
    }

    @Override
    public List<String> onTabComplete(Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            try {
                String prefix = args[0].toLowerCase();
                return service.listPlayerNames().stream()
                        .filter(name -> name.toLowerCase().startsWith(prefix))
                        .toList();
            } catch (IOException error) {
                logger.warn("Unable to complete player name", error);
                return List.of();
            }
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return List.of("stat", "lastlogin", "recentlogin").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private String summary(PlayerRecord record) {
        return record.playerName + ": logins=" + record.loginSessions.size()
                + ", chats=" + record.chatMessages.size()
                + ", stat snapshots=" + record.statSnapshots.size();
    }

    private String stat(PlayerRecord record) {
        if (record.statSnapshots.isEmpty()) {
            return record.playerName + ": no stat record";
        }
        StatSnapshot snapshot = record.statSnapshots.get(record.statSnapshots.size() - 1);
        PlayerPermissions permissions = snapshot.permissions == null ? new PlayerPermissions() : snapshot.permissions;
        return record.playerName + ": stat at " + format(snapshot.capturedAt)
                + ", joined=" + value(snapshot.addedGameCount)
                + ", online=" + value(snapshot.onlineCount)
                + ", kills=" + value(snapshot.killCount)
                + ", playtime=" + duration(snapshot.playtimeSeconds)
                + ", team=" + value(snapshot.team)
                + ", greenText=" + permissions.greenText
                + ", runMax=" + permissions.runMax
                + ", dupe=" + permissions.dupe;
    }

    private String lastLogin(PlayerRecord record) {
        if (record.loginSessions.isEmpty()) {
            return record.playerName + ": no login record";
        }
        LoginSession session = record.loginSessions.get(record.loginSessions.size() - 1);
        long end = session.logoutAt == null ? System.currentTimeMillis() : session.logoutAt;
        return record.playerName + ": login=" + format(session.loginAt)
                + ", duration=" + duration(Duration.ofMillis(Math.max(0L, end - session.loginAt)).getSeconds())
                + ", logout=" + (session.logoutAt == null ? "online" : format(session.logoutAt));
    }

    private List<String> recentLogins(PlayerRecord record) {
        if (record.loginSessions.isEmpty()) {
            return List.of(record.playerName + ": no login record");
        }
        List<String> lines = new ArrayList<>();
        int start = Math.max(0, record.loginSessions.size() - 10);
        for (int index = record.loginSessions.size() - 1; index >= start; index--) {
            LoginSession session = record.loginSessions.get(index);
            long end = session.logoutAt == null ? System.currentTimeMillis() : session.logoutAt;
            lines.add(record.playerName + ": login=" + format(session.loginAt)
                    + ", duration=" + duration(Duration.ofMillis(Math.max(0L, end - session.loginAt)).getSeconds())
                    + ", logout=" + (session.logoutAt == null ? "online" : format(session.logoutAt)));
        }
        return lines;
    }

    private void print(String message) {
        logger.info(message);
    }

    private static String format(long timestamp) {
        return TIME.format(Instant.ofEpochMilli(timestamp));
    }

    private static String value(Object value) {
        return value == null ? "unknown" : value.toString();
    }

    private static String duration(Long seconds) {
        return seconds == null ? "unknown" : duration(seconds.longValue());
    }

    private static String duration(long seconds) {
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainingSeconds = seconds % 60L;
        return hours + "h" + minutes + "m" + remainingSeconds + "s";
    }
}
