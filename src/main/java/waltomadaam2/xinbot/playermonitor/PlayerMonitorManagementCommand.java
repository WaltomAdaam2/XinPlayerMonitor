package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerPermissions;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;
import org.jline.utils.AttributedStyle;
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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlayerMonitorManagementCommand extends TabExecutor {
    private static final String DIM = "[90m";
    private static final String CYAN = "[36m";
    private static final String YELLOW = "[33m";
    private static final String RED = "[31m";
    private static final String RESET = "[0m";

    // Context-aware command colors (RGB 24-bit)
    private static final String ROOT_SUBCOMMAND = "[38;2;224;176;255m"; // #E0B0FF setting, stat as root
    private static final String PLAYER_COLOR = "[38;2;46;111;64m";       // #2E6F40
    private static final String NESTED_STAT = "[38;2;255;192;103m";       // #FFC067 stat after setting
    private static final String SETTING_NAME = "[38;2;0;123;167m";        // #007BA7 autoscan, enabled, ...
    private static final String SETTING_VALUE = "[38;2;222;161;147m";     // #DEA193 true, false, <ms>, <min>
    private static final String SCAN_ACTION = "[38;2;255;179;67m";        // #FFB343 scan after stat
    private static final String PLAYER_ACTION = "[38;2;224;176;255m";     // #E0B0FF stat|latestlogin|... after player
    private static final String COMMAND_NAME = "[38;2;166;173;180m";      // #A6ADB4 playermonitor

    // Interactive AttributedStyle counterparts
    private static final AttributedStyle ROOT_SUBCOMMAND_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xE0B0FF);
    private static final AttributedStyle PLAYER_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0x2E6F40);
    private static final AttributedStyle NESTED_STAT_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xFFC067);
    private static final AttributedStyle SETTING_NAME_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0x007BA7);
    private static final AttributedStyle SETTING_VALUE_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xDEA193);
    private static final AttributedStyle SCAN_ACTION_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xFFB343);
    private static final AttributedStyle PLAYER_ACTION_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xE0B0FF);

    static final String PLAYER_PLACEHOLDER = "<玩家名>"; // <玩家名>
    private static final String INTERVAL_PLACEHOLDER = "<ms>";
    private static final String MINUTES_PLACEHOLDER = "<min>";
    private static final List<String> PLAYER_ACTIONS = List.of("stat", "latestlogin", "recentlogin", "chat");
    private static final List<String> SETTING_ITEMS = List.of("interval", "autoscan", "enabled", "outputhide", "disconnecttimeout");
    private static final List<String> BOOLEAN_VALUES = List.of("true", "false");
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
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
        if ("stat".equalsIgnoreCase(args[0])) {
            statCommand(args);
            return;
        }
        player(args);
    }

    @Override
    public List<String> onTabComplete(Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            return List.of("setting", "stat", PLAYER_PLACEHOLDER);
        }
        if ("setting".equalsIgnoreCase(args[0])) {
            return completeSetting(args);
        }
        if ("stat".equalsIgnoreCase(args[0])) {
            return completeStatCommand(args);
        }
        if (args.length == 1) {
            return completePlayerNames(args[0]);
        }
        if (args.length == 2) {
            return matching(args[1], PLAYER_ACTIONS);
        }
        return List.of();
    }

    @Override
    public AttributedStyle[] onHighlight(Command command, String label, String[] args) {
        String[] effectiveArgs = args == null ? new String[0] : args;
        AttributedStyle[] styles = new AttributedStyle[effectiveArgs.length];
        for (int index = 0; index < effectiveArgs.length; index++) {
            styles[index] = styleForArgument(effectiveArgs, index);
        }
        return styles;
    }

    // ----------------------------------------------------------------
    // Context-aware argument styling
    // ----------------------------------------------------------------

    static AttributedStyle styleForArgument(String[] args, int index) {
        if (args == null || index < 0 || index >= args.length) {
            return AttributedStyle.DEFAULT;
        }
        String value = args[index] == null ? "" : args[index].toLowerCase(Locale.ROOT);
        if (index == 0) {
            if ("setting".equals(value) || "stat".equals(value)) {
                return ROOT_SUBCOMMAND_STYLE;
            }
            return PLAYER_STYLE;
        }
        String root = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
        if ("setting".equals(root)) {
            if (index == 1 && "stat".equals(value)) {
                return NESTED_STAT_STYLE;
            }
            if (index == 2 && SETTING_ITEMS.contains(value)) {
                return SETTING_NAME_STYLE;
            }
            if (index == 3) {
                return valueStyle(args[2], value);
            }
            return AttributedStyle.DEFAULT;
        }
        if ("stat".equals(root)) {
            if (index == 1 && "scan".equals(value)) {
                return SCAN_ACTION_STYLE;
            }
            return AttributedStyle.DEFAULT;
        }
        if (index == 1 && PLAYER_ACTIONS.contains(value)) {
            return PLAYER_ACTION_STYLE;
        }
        return AttributedStyle.DEFAULT;
    }

    private static AttributedStyle valueStyle(String settingName, String value) {
        if (settingName == null) return AttributedStyle.DEFAULT;
        settingName = settingName.toLowerCase(Locale.ROOT);
        if ("interval".equals(settingName) || "disconnecttimeout".equals(settingName)) {
            return SETTING_VALUE_STYLE;
        }
        if (BOOLEAN_VALUES.contains(value)) {
            return SETTING_VALUE_STYLE;
        }
        if (INTERVAL_PLACEHOLDER.equals(value) || MINUTES_PLACEHOLDER.equals(value)) {
            return SETTING_VALUE_STYLE;
        }
        return AttributedStyle.DEFAULT;
    }

    // ----------------------------------------------------------------
    // Context-aware usage coloring
    // ----------------------------------------------------------------

    static String colorUsage(String usage) {
        if (usage.startsWith("Usage: playermonitor setting stat")) {
            return colorSettingUsage(usage);
        }
        if (usage.startsWith("Usage: playermonitor stat scan")) {
            return colorScanUsage(usage);
        }
        if (usage.startsWith("Usage: playermonitor " + PLAYER_PLACEHOLDER)) {
            return colorPlayerUsage(usage);
        }
        return usage;
    }

    private static String colorSettingUsage(String usage) {
        // "Usage: playermonitor setting stat [interval <ms>|autoscan <true|false>|...]"
        StringBuilder sb = new StringBuilder();
        sb.append("Usage: ");
        sb.append(COMMAND_NAME).append("playermonitor").append(RESET).append(" ");
        sb.append(ROOT_SUBCOMMAND).append("setting").append(RESET).append(" ");
        sb.append(NESTED_STAT).append("stat").append(RESET).append(" [");
        sb.append(SETTING_NAME).append("interval").append(RESET).append(" ");
        sb.append(SETTING_VALUE).append("<ms>").append(RESET).append("|");
        sb.append(SETTING_NAME).append("autoscan").append(RESET).append(" ");
        sb.append(SETTING_VALUE).append("<true|false>").append(RESET).append("|");
        sb.append(SETTING_NAME).append("enabled").append(RESET).append(" ");
        sb.append(SETTING_VALUE).append("<true|false>").append(RESET).append("|");
        sb.append(SETTING_NAME).append("outputhide").append(RESET).append(" ");
        sb.append(SETTING_VALUE).append("<true|false>").append(RESET).append("|");
        sb.append(SETTING_NAME).append("disconnecttimeout").append(RESET).append(" ");
        sb.append(SETTING_VALUE).append("<min>").append(RESET).append("]");
        return sb.toString();
    }

    private static String colorScanUsage(String usage) {
        // "Usage: playermonitor stat scan"
        return "Usage: " + COMMAND_NAME + "playermonitor" + RESET + " "
                + ROOT_SUBCOMMAND + "stat" + RESET + " "
                + SCAN_ACTION + "scan" + RESET;
    }

    private static String colorPlayerUsage(String usage) {
        // "Usage: playermonitor <玩家名> [stat|latestlogin|recentlogin|chat]"
        return "Usage: " + COMMAND_NAME + "playermonitor" + RESET + " "
                + PLAYER_COLOR + PLAYER_PLACEHOLDER + RESET + " ["
                + PLAYER_ACTION + "stat" + RESET + "|"
                + PLAYER_ACTION + "latestlogin" + RESET + "|"
                + PLAYER_ACTION + "recentlogin" + RESET + "|"
                + PLAYER_ACTION + "chat" + RESET + "]";
    }

    // ----------------------------------------------------------------
    // Command handlers
    // ----------------------------------------------------------------

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
                case "autoscan" -> {
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
                case "disconnecttimeout" -> {
                    int minutes = parseMinutes(args[3]);
                    settings.setDisconnectFinalizationMinutes(minutes);
                    print("Disconnect finalization timeout set to " + minutes + "min.");
                }
                default -> help();
            }
        } catch (NumberFormatException error) {
            print("disconnecttimeout".equals(action) ? "请输入准确的正整数分钟值。" : "请输入准确的正整数毫秒值。");
        } catch (IllegalArgumentException error) {
            print(error.getMessage());
        } catch (IOException error) {
            print("Unable to save monitor settings: " + error.getMessage());
        }
    }

    private void statCommand(String[] args) {
        if (args.length == 2 && "scan".equalsIgnoreCase(args[1])) {
            scan();
            return;
        }
        help();
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
            return matching(args[2], SETTING_ITEMS);
        }
        if (args.length == 4) {
            return switch (args[2].toLowerCase(Locale.ROOT)) {
                case "interval" -> List.of(INTERVAL_PLACEHOLDER);
                case "autoscan", "enabled", "outputhide" -> matching(args[3], List.of("true", "false"));
                case "disconnecttimeout" -> List.of(MINUTES_PLACEHOLDER);
                default -> List.of();
            };
        }
        return List.of();
    }

    private List<String> completeStatCommand(String[] args) {
        if (args.length == 1) {
            return List.of("scan");
        }
        if (args.length == 2) {
            return matching(args[1], List.of("scan"));
        }
        return List.of();
    }

    private List<String> completePlayerNames(String input) {
        if (input == null || input.isEmpty()) {
            return List.of("setting", "stat", PLAYER_PLACEHOLDER);
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
            if ("stat".startsWith(prefix)) {
                matches.add("stat");
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
                "隐藏服务器 stat 输出: " + state(current.statOutputHidden),
                "断线会话收尾: " + current.disconnectFinalizationMinutes + " min"));
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
            print("暂无 stat 记录: " + record.playerName);
            return;
        }
        StatSnapshot snapshot = record.statSnapshots.get(record.statSnapshots.size() - 1);
        PlayerPermissions permissions = snapshot.permissions == null ? new PlayerPermissions() : snapshot.permissions;
        Integer deaths = snapshot.deathCount != null ? snapshot.deathCount : snapshot.onlineCount;
        String priority = snapshot.priorityQueue != null ? snapshot.priorityQueue : snapshot.team;
        print(DIM + "===== " + CYAN + "Player stat" + DIM + " =====" + RESET);
        statField("玩家名称", record.playerName);
        statField("加入游戏", value(snapshot.addedGameCount) + " 次");
        statField("死亡计数", value(deaths) + " 次");
        statField("击杀计数", value(snapshot.killCount) + " 人");
        print(CYAN + "游戏时长: " + YELLOW + duration(snapshot.playtimeSeconds) + RESET);
        String priorityColor = "已过期".equals(priority) ? RED : RESET;
        print(CYAN + "优先队列: " + priorityColor + value(priority) + RESET);
        statField("特殊权限", permissionsDisplay(snapshot, permissions));
        print(DIM + "================================" + RESET);
    }

    private void statField(String label, String value) {
        print(CYAN + label + ": " + RESET + value);
    }

    private void latestLogin(PlayerRecord record) {
        if (record.loginSessions.isEmpty()) {
            report("Latest login", List.of("玩家: " + record.playerName, "暂无登录记录"));
            return;
        }
        LoginSession session = service.latestLogin(record).orElseThrow();
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
        List<LoginSession> sessions = service.recentLogins(record, 15);
        for (int index = 0; index < sessions.size(); index++) {
            LoginSession session = sessions.get(index);
            String line = "#" + (index + 1) + "  登录: " + format(session.loginAt)
                    + "  时长: " + sessionDuration(session);
            if (session.logoutAt != null) {
                line += "  登出: " + format(session.logoutAt);
            }
            lines.add(line);
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
        String line = "最近登录: " + format(session.loginAt) + "  游玩时长: " + sessionDuration(session);
        if (session.logoutAt != null) {
            line += "  登出时间: " + format(session.logoutAt);
        }
        lines.add(line);
        return lines;
    }

    private void report(String title, List<String> lines) {
        print(DIM + "===== " + CYAN + title + DIM + " =====" + RESET);
        print("");
        for (String line : lines) {
            String rendered;
            if (TIMESTAMP.matcher(line).lookingAt()) {
                rendered = line;
            } else {
                int separator = Math.max(line.indexOf(':'), line.indexOf('：'));
                if (separator < 0) {
                    rendered = line;
                } else {
                    rendered = CYAN + line.substring(0, separator + 1) + RESET + line.substring(separator + 1);
                }
            }
            print("  " + highlightTimestamps(rendered));
        }
        print(DIM + "================================" + RESET);
    }

    private void help() {
        print(colorUsage("Usage: playermonitor setting stat [interval <ms>|autoscan <true|false>|enabled <true|false>|outputhide <true|false>|disconnecttimeout <min>]"));
        print(colorUsage("Usage: playermonitor stat scan"));
        playerHelp();
    }

    private void playerHelp() {
        print(colorUsage("Usage: playermonitor " + PLAYER_PLACEHOLDER + " [stat|latestlogin|recentlogin|chat]"));
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

    private static int parseMinutes(String value) {
        if (MINUTES_PLACEHOLDER.equals(value)) {
            throw new NumberFormatException(value);
        }
        int minutes = Integer.parseInt(value);
        if (minutes <= 0) {
            throw new NumberFormatException(value);
        }
        return minutes;
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

    private static String permissionsDisplay(StatSnapshot snapshot, PlayerPermissions permissions) {
        if (snapshot.permissionsDisplay != null && !snapshot.permissionsDisplay.isBlank()) {
            return snapshot.permissionsDisplay;
        }
        return marker(permissions.greenText) + " | " + marker(permissions.runMax) + " | " + marker(permissions.dupe);
    }

    private static String marker(boolean value) {
        return value ? "✓" : "—";
    }

    private static String format(long timestamp) {
        return TIME.format(Instant.ofEpochMilli(timestamp));
    }

    private static String highlightTimestamps(String line) {
        Matcher matcher = TIMESTAMP.matcher(line);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(YELLOW + matcher.group() + RESET));
        }
        matcher.appendTail(output);
        return output.toString();
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
