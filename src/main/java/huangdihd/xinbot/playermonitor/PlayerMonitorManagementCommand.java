package huangdihd.xinbot.playermonitor;

import huangdihd.xinbot.playermonitor.model.ChatEntry;
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
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

final class PlayerMonitorManagementCommand extends TabExecutor {
    private static final String DIM = "\u001B[90m";
    private static final String CYAN = "\u001B[36m";
    private static final String RESET = "\u001B[0m";
    private static final String PLAYER_PLACEHOLDER = "<玩家名>";
    private static final String BOOLEAN_PLACEHOLDER = "<true|false>";
    private static final String INTERVAL_PLACEHOLDER = "<ms>";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final PlayerMonitorService service;
    private final MonitorSettingsStore settings;
    private final PlayerMonitorListener listener;
    private final Logger logger;

    PlayerMonitorManagementCommand(PlayerMonitorService service, MonitorSettingsStore settings,
                                   PlayerMonitorListener listener, Logger logger) {
        this.service = service;
        this.settings = settings;
        this.listener = listener;
        this.logger = logger;
    }

    @Override
    public void onCommand(Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            help();
            return;
        }
        if ("setting".equalsIgnoreCase(args[0])) {
            setting(args);
            return;
        }
        player(args);
    }

    @Override
    public List<String> onTabComplete(Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            return List.of("setting", PLAYER_PLACEHOLDER);
        }
        if ("setting".equalsIgnoreCase(args[0])) {
            return completeSetting(args);
        }
        if (args.length == 1) {
            return completePlayerNames(args[0]);
        }
        if (args.length == 2) {
            return matching(args[1], List.of("stat", "latestlogin", "recentlogin", "chat"));
        }
        return List.of();
    }

    private void setting(String[] args) {
        if (args.length < 2 || !"stat".equalsIgnoreCase(args[1])) {
            help();
            return;
        }
        if (args.length == 2) {
            statSettings();
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if ("scan".equals(action)) {
            if (args.length != 3) {
                help();
                return;
            }
            scan();
            return;
        }
        if (args.length != 4) {
            help();
            return;
        }
        try {
            switch (action) {
                case "interval" -> {
                    int interval = parseInterval(args[3]);
                    settings.setStatIntervalMillis(interval);
                    print("Stat interval set to " + interval + "ms.");
                }
                case "auto" -> {
                    boolean enabled = parseBoolean(args[3]);
                    settings.setAutoScanOnGameEntry(enabled);
                    print("Auto scan on Game entry set to " + enabled + ".");
                }
                case "enabled" -> {
                    boolean enabled = parseBoolean(args[3]);
                    settings.setStatScanEnabled(enabled);
                    print("Automatic stat scan set to " + enabled + ".");
                }
                case "outputhide" -> {
                    boolean enabled = parseBoolean(args[3]);
                    settings.setStatOutputHidden(enabled);
                    print("Stat output hiding set to " + enabled + ".");
                }
                default -> help();
            }
        } catch (NumberFormatException error) {
            print("请输入准确的正整数毫秒值。");
        } catch (IllegalArgumentException error) {
            print(error.getMessage());
        } catch (IOException error) {
            print("Unable to save monitor settings: " + error.getMessage());
        }
    }

    private void player(String[] args) {
        String playerName = args[0];
        if (PLAYER_PLACEHOLDER.equals(playerName)) {
            print("请输入准确的玩家名。");
            return;
        }
        if (args.length != 2) {
            playerHelp();
            return;
        }
        try {
            Optional<PlayerRecord> record = service.findRecord(playerName);
            if (record.isEmpty()) {
                print("未找到玩家档案: " + playerName);
                return;
            }
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "stat" -> stat(record.get());
                case "latestlogin" -> latestLogin(record.get());
                case "recentlogin" -> recentLogins(record.get());
                case "chat" -> chats(record.get());
                default -> playerHelp();
            }
        } catch (IOException error) {
            print("Unable to read player record: " + error.getMessage());
        }
    }

    private List<String> completeSetting(String[] args) {
        if (args.length == 1) {
            return List.of("stat");
        }
        if (args.length == 2) {
            return matching(args[1], List.of("stat"));
        }
        if (!"stat".equalsIgnoreCase(args[1])) {
            return List.of();
        }
        if (args.length == 3) {
            return matching(args[2], List.of("scan", "interval", "auto", "enabled", "outputhide"));
        }
        if (args.length == 4) {
            return switch (args[2].toLowerCase(Locale.ROOT)) {
                case "interval" -> List.of(INTERVAL_PLACEHOLDER);
                case "auto", "enabled", "outputhide" -> List.of(BOOLEAN_PLACEHOLDER);
                default -> List.of();
            };
        }
        return List.of();
    }

    private List<String> completePlayerNames(String input) {
        if (input == null || input.isEmpty()) {
            return List.of("setting", PLAYER_PLACEHOLDER);
        }
        try {
            TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            names.addAll(service.listPlayerNames());
            names.addAll(listener.onlinePlayerNames());
            String prefix = input.toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            if ("setting".startsWith(prefix)) {
                matches.add("setting");
            }
            names.stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .forEach(matches::add);
            return matches;
        } catch (IOException error) {
            logger.warn("Unable to complete player name", error);
            return List.of();
        }
    }

    private void statSettings() {
        MonitorSettings current = settings.get();
        report("Stat settings", List.of(
                "扫描间隔: " + current.statIntervalMillis + " ms",
                "进入 Game 自动扫描: " + state(current.autoScanOnGameEntry),
                "自动 stat 扫描: " + state(current.statScanEnabled),
                "隐藏服务器 stat 输出: " + state(current.statOutputHidden)));
    }

    private void scan() {
        int queued = listener.scanAllOnlinePlayers();
        if (queued < 0) {
            print("Stat scan is only available in Game.");
            return;
        }
        print("Queued stat scan for " + queued + " online players.");
    }

    private void stat(PlayerRecord record) {
        if (record.statSnapshots.isEmpty()) {
            report("Player stat", List.of("玩家: " + record.playerName, "暂无 stat 记录"));
            return;
        }
        StatSnapshot snapshot = record.statSnapshots.get(record.statSnapshots.size() - 1);
        PlayerPermissions permissions = snapshot.permissions == null ? new PlayerPermissions() : snapshot.permissions;
        Integer deaths = snapshot.deathCount != null ? snapshot.deathCount : snapshot.onlineCount;
        String priority = snapshot.priorityQueue != null ? snapshot.priorityQueue : snapshot.team;
        report("Player stat", List.of(
                "玩家: " + record.playerName,
                "记录时间: " + format(snapshot.capturedAt),
                "加入游戏: " + value(snapshot.addedGameCount),
                "死亡计数: " + value(deaths),
                "击杀计数: " + value(snapshot.killCount),
                "游戏时长: " + duration(snapshot.playtimeSeconds),
                "优先队列: " + value(priority),
                "特殊权限: 绿字 " + marker(permissions.greenText)
                        + "  runmax " + marker(permissions.runMax)
                        + "  dupe " + marker(permissions.dupe)));
    }

    private void latestLogin(PlayerRecord record) {
        if (record.loginSessions.isEmpty()) {
            report("Latest login", List.of("玩家: " + record.playerName, "暂无登录记录"));
            return;
        }
        LoginSession session = record.loginSessions.get(record.loginSessions.size() - 1);
        report("Latest login", loginLines(record.playerName, session));
    }

    private void recentLogins(PlayerRecord record) {
        if (record.loginSessions.isEmpty()) {
            report("Recent logins", List.of("玩家: " + record.playerName, "暂无登录记录"));
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("玩家: " + record.playerName);
        lines.add("显示最近 " + Math.min(15, record.loginSessions.size()) + " / " + record.loginSessions.size() + " 次登录");
        int start = Math.max(0, record.loginSessions.size() - 15);
        for (int index = record.loginSessions.size() - 1; index >= start; index--) {
            LoginSession session = record.loginSessions.get(index);
            lines.add("#" + (record.loginSessions.size() - index) + "  登录: " + format(session.loginAt)
                    + "  时长: " + sessionDuration(session));
            if (session.logoutAt != null) {
                lines.add("   登出: " + format(session.logoutAt));
            }
        }
        report("Recent logins", lines);
    }

    private void chats(PlayerRecord record) {
        if (record.chatMessages.isEmpty()) {
            report("Recent chat", List.of("玩家: " + record.playerName, "暂无聊天记录"));
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("玩家: " + record.playerName);
        lines.add("显示最近 " + Math.min(30, record.chatMessages.size()) + " / " + record.chatMessages.size() + " 条消息");
        int start = Math.max(0, record.chatMessages.size() - 30);
        for (int index = record.chatMessages.size() - 1; index >= start; index--) {
            ChatEntry entry = record.chatMessages.get(index);
            lines.add(format(entry.timestamp) + "  " + entry.message);
        }
        report("Recent chat", lines);
    }

    private List<String> loginLines(String playerName, LoginSession session) {
        List<String> lines = new ArrayList<>();
        lines.add("玩家: " + playerName);
        lines.add("最近登录: " + format(session.loginAt));
        lines.add("游玩时长: " + sessionDuration(session));
        if (session.logoutAt != null) {
            lines.add("登出时间: " + format(session.logoutAt));
        }
        return lines;
    }

    private void report(String title, List<String> lines) {
        print(DIM + "================================" + RESET);
        print(CYAN + "  XinPlayerMonitor · " + title + RESET);
        print("");
        for (String line : lines) {
            int separator = Math.max(line.indexOf(':'), line.indexOf('：'));
            if (separator < 0) {
                print("  " + line);
            } else {
                print("  " + CYAN + line.substring(0, separator + 1) + RESET + line.substring(separator + 1));
            }
        }
        print(DIM + "================================" + RESET);
    }

    private void help() {
        print("Usage: playermonitor setting stat [scan|interval <ms>|auto <true|false>|enabled <true|false>|outputhide <true|false>]");
        playerHelp();
    }

    private void playerHelp() {
        print("Usage: playermonitor <玩家名> [stat|latestlogin|recentlogin|chat]");
    }

    private void print(String message) {
        logger.info(message);
    }

    private static List<String> matching(String input, List<String> values) {
        String prefix = input == null ? "" : input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private static boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("请输入 true 或 false。");
        };
    }

    private static int parseInterval(String value) {
        if (INTERVAL_PLACEHOLDER.equals(value)) {
            throw new NumberFormatException(value);
        }
        int interval = Integer.parseInt(value);
        if (interval <= 0) {
            throw new NumberFormatException(value);
        }
        return interval;
    }

    private static String state(boolean value) {
        return value ? "true" : "false";
    }

    private static String marker(boolean value) {
        return value ? "✓" : "—";
    }

    private static String format(long timestamp) {
        return TIME.format(Instant.ofEpochMilli(timestamp));
    }

    private static String value(Object value) {
        return value == null ? "未知" : value.toString();
    }

    private static String duration(Long seconds) {
        return seconds == null ? "未知" : duration(seconds.longValue());
    }

    private static String sessionDuration(LoginSession session) {
        long end = session.logoutAt == null ? System.currentTimeMillis() : session.logoutAt;
        return duration(Duration.ofMillis(Math.max(0L, end - session.loginAt)).getSeconds());
    }

    private static String duration(long seconds) {
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainingSeconds = seconds % 60L;
        return hours + "小时" + minutes + "分" + remainingSeconds + "秒";
    }
}
