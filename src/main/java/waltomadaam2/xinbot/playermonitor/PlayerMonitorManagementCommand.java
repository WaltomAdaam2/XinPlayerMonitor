package waltomadaam2.xinbot.playermonitor;

import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerPermissions;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;
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

    private static final String ROOT_COMMAND = "\u001B[38;5;183m"; // xterm-256 approximation of #E0B0FF
    private static final String PLAYER_COLOR = "\u001B[38;5;29m";  // compatible green approximation of #2E6F40
    private static final String SETTING_NAME = "\u001B[38;5;151m"; // xterm-256 approximation of #ADEBB3
    private static final String SETTING_VALUE = "\u001B[38;5;215m"; // xterm-256 approximation of #FFC067
    private static final String PLAYER_ACTION = "\u001B[38;5;183m"; // xterm-256 approximation of #E0B0FF
    private static final String COMMAND_NAME = "\u001B[38;5;145m"; // xterm-256 approximation of #A6ADB4
    private static final String COUNT_COLOR = "\u001B[38;5;215m"; // xterm-256 approximation of #ffb343

    private static final AttributedStyle ROOT_COMMAND_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xE0B0FF);
    private static final AttributedStyle PLAYER_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0x2E6F40);
    private static final AttributedStyle SETTING_NAME_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xADEBB3);
    private static final AttributedStyle SETTING_VALUE_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xFFC067);
    private static final AttributedStyle PLAYER_ACTION_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xE0B0FF);
    private static final AttributedStyle COUNT_ARG_STYLE = AttributedStyle.DEFAULT.foregroundRgb(0xFFB343);

    static final String PLAYER_PLACEHOLDER = "<玩家名>";
    private static final String TRUE_FALSE_PLACEHOLDER = "<true|false>";
    private static final String MS_PLACEHOLDER = "<ms>";
    private static final String MINUTE_PLACEHOLDER = "<minute>";
    private static final String HOUR_PLACEHOLDER = "<hour>";
    private static final String COUNT_PLACEHOLDER = "<count>";
    private static final String TIMEZONE_PLACEHOLDER = "<timezone>";

    private static final List<String> PLAYER_ACTIONS = List.of("stat", "latestlogin", "recentlogin", "chat");
    private static final List<String> BOOLEAN_VALUES = List.of("true", "false");
    private static final List<String> SETTING_ITEMS = List.of(
            "scan-on-entry",
            "disconnect-timeout",
            "stat-enabled",
            "stat-send-interval",
            "stat-output-hide",
            "stat-cooldown",
            "stat-timeout",
            "stat-attempts",
            "scan-on-join",
            "prioritize-join-stat",
            "display-timezone",
            "recentlogin-count",
            "chat-count",
            "cache-idle",
            "max-cached-history",
            "backup-interval");

    private static final List<String> TIMEZONE_VALUES = MonitorSettingsStore.SUPPORTED_TIMEZONES;

    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PlayerMonitorService service;
    private final MonitorSettingsStore settings;
    private final PlayerMonitorListener listener;
    private final Logger logger;
    private volatile SQLiteBackupManager backupManager;

    PlayerMonitorManagementCommand(PlayerMonitorService service, MonitorSettingsStore settings,
                                   PlayerMonitorListener listener, Logger logger) {
        this.service = service;
        this.settings = settings;
        this.listener = listener;
        this.logger = logger;
    }

    void setBackupManager(SQLiteBackupManager backupManager) {
        this.backupManager = backupManager;
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
        if ("scan-stat".equalsIgnoreCase(args[0])) {
            if (args.length == 1) {
                scan();
            } else {
                help();
            }
            return;
        }
        if ("db-stat".equalsIgnoreCase(args[0])) {
            if (args.length == 1) {
                databaseStats();
            } else {
                help();
            }
            return;
        }
        player(args);
    }

    @Override
    public List<String> onTabComplete(Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            return List.of("setting", "scan-stat", "db-stat", PLAYER_PLACEHOLDER);
        }
        if ("setting".equalsIgnoreCase(args[0])) {
            return completeSetting(args);
        }
        if ("scan-stat".equalsIgnoreCase(args[0]) && args.length > 1) {
            return List.of();
        }
        if ("db-stat".equalsIgnoreCase(args[0]) && args.length > 1) {
            return List.of();
        }
        if (args.length == 1) {
            return completePlayerNames(args[0]);
        }
        if (args.length == 2) {
            return matching(args[1], PLAYER_ACTIONS);
        }
        if (args.length == 3) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if ("chat".equals(action)) {
                return matching(args[2], List.of(Integer.toString(settings.chatCount())));
            }
            if ("recentlogin".equals(action)) {
                return matching(args[2], List.of(Integer.toString(settings.recentLoginCount())));
            }
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

    static AttributedStyle styleForArgument(String[] args, int index) {
        if (args == null || index < 0 || index >= args.length) {
            return AttributedStyle.DEFAULT;
        }
        String value = lower(args[index]);
        if (index == 0) {
            if ("setting".equals(value) || "scan-stat".equals(value) || "db-stat".equals(value)) {
                return ROOT_COMMAND_STYLE;
            }
            return PLAYER_STYLE;
        }
        String root = lower(args[0]);
        if ("scan-stat".equals(root) || "db-stat".equals(root)) {
            return AttributedStyle.DEFAULT;
        }
        if ("setting".equals(root)) {
            if (index == 1 && SETTING_ITEMS.contains(value)) {
                return SETTING_NAME_STYLE;
            }
            if (index == 2 && SETTING_ITEMS.contains(lower(args[1]))) {
                return SETTING_VALUE_STYLE;
            }
            return AttributedStyle.DEFAULT;
        }
        if (index == 1 && PLAYER_ACTIONS.contains(value)) {
            return PLAYER_ACTION_STYLE;
        }
        if (index == 2) {
            String action = lower(args[1]);
            if ("chat".equals(action) || "recentlogin".equals(action)) {
                return COUNT_ARG_STYLE;
            }
        }
        return AttributedStyle.DEFAULT;
    }

    static String colorUsage(String usage) {
        if (usage.startsWith("Usage: playermonitor setting ")) {
            String commandPart = usage.substring("Usage: playermonitor setting ".length());
            int separator = commandPart.indexOf(' ');
            String settingName = separator < 0 ? commandPart : commandPart.substring(0, separator);
            String remainder = separator < 0 ? "" : commandPart.substring(separator + 1);
            return "Usage: " + COMMAND_NAME + "playermonitor" + RESET + " "
                    + ROOT_COMMAND + "setting" + RESET + " "
                    + SETTING_NAME + settingName + RESET
                    + (remainder.isEmpty() ? "" : " " + SETTING_VALUE + remainder + RESET);
        }
        if (usage.equals("Usage: playermonitor scan-stat")) {
            return "Usage: " + COMMAND_NAME + "playermonitor" + RESET + " "
                    + ROOT_COMMAND + "scan-stat" + RESET;
        }
        if (usage.equals("Usage: playermonitor db-stat")) {
            return "Usage: " + COMMAND_NAME + "playermonitor" + RESET + " "
                    + ROOT_COMMAND + "db-stat" + RESET;
        }
        if (usage.startsWith("Usage: playermonitor " + PLAYER_PLACEHOLDER)) {
            return colorPlayerUsage(usage);
        }
        return usage;
    }

    private static String colorPlayerUsage(String usage) {
        String prefix = "Usage: playermonitor " + PLAYER_PLACEHOLDER;
        String remainder = usage.length() <= prefix.length() ? "" : usage.substring(prefix.length());
        String colored = "Usage: " + COMMAND_NAME + "playermonitor" + RESET + " "
                + PLAYER_COLOR + PLAYER_PLACEHOLDER + RESET;
        for (String action : PLAYER_ACTIONS) {
            remainder = remainder.replace(action, PLAYER_ACTION + action + RESET);
        }
        remainder = remainder.replace("[count]", COUNT_COLOR + "[count]" + RESET);
        return colored + remainder;
    }

    private void setting(String[] args) {
        if (args.length == 1) {
            showSettings();
            return;
        }
        if (args.length != 3) {
            settingHelp();
            return;
        }
        String action = lower(args[1]);
        String value = args[2];
        try {
            switch (action) {
                case "scan-on-entry" -> {
                    settings.setScanOnEntry(parseBoolean(value));
                    statSettingSaved("进入 Game 自动扫描已设置为 " + value.toLowerCase(Locale.ROOT));
                }
                case "disconnect-timeout" -> {
                    int parsed = parsePositiveInt(value, MINUTE_PLACEHOLDER);
                    settings.setDisconnectTimeoutMinutes(parsed);
                    listener.applyDisconnectTimeoutNow();
                    print("断线确认时间已设置为 " + parsed + " 分钟，已立即生效。");
                }
                case "stat-enabled" -> {
                    settings.setStatEnabled(parseBoolean(value));
                    statSettingSaved("Stat 自动扫描总开关已设置为 " + value.toLowerCase(Locale.ROOT));
                }
                case "stat-send-interval" -> {
                    int parsed = parsePositiveInt(value, MS_PLACEHOLDER);
                    settings.setStatSendIntervalMillis(parsed);
                    statSettingSaved("Stat 发送间隔已设置为 " + parsed + " ms");
                }
                case "stat-output-hide" -> {
                    settings.setStatOutputHide(parseBoolean(value));
                    statSettingSaved("Stat 输出隐藏已设置为 " + value.toLowerCase(Locale.ROOT));
                }
                case "stat-cooldown" -> {
                    int parsed = parseInt(value, HOUR_PLACEHOLDER);
                    settings.setStatCooldownHours(parsed);
                    statSettingSaved("自动 Stat 冷却时间已设置为 " + parsed + " 小时");
                }
                case "stat-timeout" -> {
                    int parsed = parseInt(value, MS_PLACEHOLDER);
                    settings.setStatTimeoutMillis(parsed);
                    statSettingSaved("Stat 响应超时已设置为 " + parsed + " ms");
                }
                case "stat-attempts" -> {
                    int parsed = parseInt(value, COUNT_PLACEHOLDER);
                    settings.setStatAttempts(parsed);
                    statSettingSaved("Stat 最大总尝试次数已设置为 " + parsed);
                }
                case "scan-on-join" -> {
                    settings.setScanOnJoin(parseBoolean(value));
                    statSettingSaved("玩家加入自动扫描已设置为 " + value.toLowerCase(Locale.ROOT));
                }
                case "prioritize-join-stat" -> {
                    settings.setPrioritizeJoinStat(parseBoolean(value));
                    statSettingSaved("新加入玩家优先扫描已设置为 " + value.toLowerCase(Locale.ROOT));
                }
                case "display-timezone" -> {
                    settings.setDisplayTimezone(value);
                    print("命令输出时区已设置为 " + settings.displayTimezone() + "，已立即生效。");
                }
                case "recentlogin-count" -> {
                    int parsed = parseInt(value, COUNT_PLACEHOLDER);
                    settings.setRecentLoginCount(parsed);
                    print("近期登录默认显示数量已设置为 " + parsed + "，已立即生效。");
                }
                case "chat-count" -> {
                    int parsed = parseInt(value, COUNT_PLACEHOLDER);
                    settings.setChatCount(parsed);
                    print("聊天记录默认显示数量已设置为 " + parsed + "，已立即生效。");
                }
                case "cache-idle" -> {
                    int parsed = parseInt(value, MINUTE_PLACEHOLDER);
                    settings.setCacheIdleMinutes(parsed);
                    service.applyCacheSettingsNow();
                    print("缓存空闲释放时间已设置为 " + parsed + " 分钟，已立即生效。");
                }
                case "max-cached-history" -> {
                    int parsed = parseInt(value, COUNT_PLACEHOLDER);
                    settings.setMaxCachedHistory(parsed);
                    service.applyCacheSettingsNow();
                    print("每类最大内存历史缓存已设置为 " + parsed + " 条，已立即生效。");
                }
                case "backup-interval" -> {
                    int parsed = parseBackupInterval(value);
                    settings.setBackupInterval(parsed);
                    SQLiteBackupManager mgr = backupManager;
                    if (mgr != null) {
                        mgr.reschedule(parsed);
                    }
                    String nextBackupText = formatNextBackupTime();
                    if (nextBackupText != null) {
                        print("自动备份间隔已设置为 " + parsed + " 小时，已立即生效。下次备份时间: " + nextBackupText);
                    } else {
                        print("自动备份间隔已设置为 " + parsed + " 小时，已立即生效。");
                    }
                }
                default -> settingHelp();
            }
        } catch (NumberFormatException error) {
            print("请输入有效的整数值。");
        } catch (IllegalArgumentException error) {
            print(error.getMessage());
        } catch (IOException error) {
            print("无法保存设置: " + error.getMessage());
        }
    }

    private String formatNextBackupTime() {
        SQLiteBackupManager mgr = backupManager;
        if (mgr == null) {
            return null;
        }
        long nextTime = mgr.nextBackupTimeMillis();
        ZoneId zone = settings.displayZoneId();
        return TIME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(nextTime));
    }

    private static int parseBackupInterval(String value) {
        // Accept any positive whole number; reject decimals, zero, and negatives.
        if (value == null || value.isBlank()) {
            throw new NumberFormatException(value);
        }
        String trimmed = value.trim();
        if (trimmed.contains(".")) {
            throw new IllegalArgumentException("备份间隔必须是正整数（不允许小数）。");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("请输入有效的整数值。");
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException("备份间隔必须大于 0。");
        }
        return parsed;
    }

    private void statSettingSaved(String message) {
        if (settings.hasDeferredStatSettings()) {
            print(message + "；设置已保存，将在当前 Stat 请求结束后生效。");
        } else {
            print(message + "，已立即生效。");
        }
    }

    private void player(String[] args) {
        String playerName = args[0];
        if (PLAYER_PLACEHOLDER.equals(playerName)) {
            print("请输入准确的玩家名。");
            return;
        }
        if (args.length < 2 || args.length > 3) {
            playerHelp();
            return;
        }
        String action = lower(args[1]);
        if (args.length == 3 && !"recentlogin".equals(action) && !"chat".equals(action)) {
            playerHelp();
            return;
        }
        try {
            Optional<PlayerRecord> record = service.findRecordSummary(playerName);
            if (record.isEmpty()) {
                print("未找到玩家档案: " + playerName);
                return;
            }
            String displayName = record.get().playerName;
            switch (action) {
                case "stat" -> stat(displayName, service.latestStat(playerName));
                case "latestlogin" -> latestLogin(displayName, service.latestLogin(playerName));
                case "recentlogin" -> recentLogins(displayName,
                        service.recentLogins(playerName, queryCount(args, settings.recentLoginCount())),
                        service.loginCount(playerName));
                case "chat" -> chats(displayName,
                        service.recentChats(playerName, queryCount(args, settings.chatCount())),
                        service.chatCount(playerName));
                default -> playerHelp();
            }
        } catch (NumberFormatException error) {
            print("显示数量必须是 5–50 之间的整数。");
        } catch (IOException error) {
            print("无法读取玩家记录: " + error.getMessage());
        }
    }

    private List<String> completeSetting(String[] args) {
        if (args.length == 1) {
            return SETTING_ITEMS;
        }
        if (args.length == 2) {
            return matching(args[1], SETTING_ITEMS);
        }
        if (args.length == 3) {
            List<String> candidates = switch (lower(args[1])) {
                case "scan-on-entry", "stat-enabled", "stat-output-hide", "scan-on-join",
                        "prioritize-join-stat" -> BOOLEAN_VALUES;
                case "disconnect-timeout", "cache-idle" -> List.of(MINUTE_PLACEHOLDER);
                case "stat-send-interval", "stat-timeout" -> List.of(MS_PLACEHOLDER);
                case "stat-cooldown", "backup-interval" -> List.of(HOUR_PLACEHOLDER);
                case "stat-attempts", "max-cached-history" -> List.of(COUNT_PLACEHOLDER);
                case "display-timezone" -> TIMEZONE_VALUES;
                case "recentlogin-count" -> List.of(Integer.toString(settings.recentLoginCount()));
                case "chat-count" -> List.of(Integer.toString(settings.chatCount()));
                default -> List.of();
            };
            return matching(args[2], candidates);
        }
        return List.of();
    }

    private List<String> completePlayerNames(String input) {
        if (input == null || input.isEmpty()) {
            return List.of("setting", "scan-stat", "db-stat", PLAYER_PLACEHOLDER);
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
            if ("scan-stat".startsWith(prefix)) {
                matches.add("scan-stat");
            }
            if ("db-stat".startsWith(prefix)) {
                matches.add("db-stat");
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

    private void showSettings() {
        MonitorSettings current = settings.get();
        List<String> lines = new ArrayList<>();
        lines.add("进入 Game 自动扫描: " + state(current.scanOnEntry));
        lines.add("断线确认时间: " + current.disconnectTimeoutMinutes + " min");
        lines.add("Stat 自动扫描总开关: " + state(current.statEnabled));
        lines.add("Stat 发送间隔: " + current.statSendIntervalMillis + " ms");
        lines.add("Stat 输出隐藏: " + state(current.statOutputHide));
        lines.add("自动 Stat 冷却: " + current.statCooldownHours + " h");
        lines.add("Stat 响应超时: " + current.statTimeoutMillis + " ms");
        lines.add("Stat 最大尝试次数: " + current.statAttempts);
        lines.add("玩家加入自动扫描: " + state(current.scanOnJoin));
        lines.add("新加入玩家优先扫描: " + state(current.prioritizeJoinStat));
        lines.add("显示时区: " + current.displayTimezone);
        lines.add("近期登录默认数量: " + current.recentLoginCount);
        lines.add("聊天默认数量: " + current.chatCount);
        lines.add("缓存空闲释放: " + current.cacheIdleMinutes + " min");
        lines.add("每类最大内存历史: " + current.maxCachedHistory);
        lines.add("自动备份间隔: " + current.backupInterval + " h");
        if (settings.hasDeferredStatSettings()) {
            lines.add("状态: Stat 设置已保存，等待当前请求完成后生效");
        }
        report("PlayerMonitor 设置", lines);
    }

    private void scan() {
        int queued = listener.scanAllOnlinePlayers();
        if (queued < 0) {
            print("只有处于 Game 状态时才能手动扫描 Stat。");
            return;
        }
        print("已将 " + queued + " 名在线玩家加入 Stat 扫描队列。");
    }

    private void databaseStats() {
        try {
            DatabaseStats stats = service.databaseStats();
            report("Database overview", databaseStatsLines(stats));
        } catch (IOException error) {
            print("Unable to read database stats: " + error.getMessage());
        }
    }
    private void stat(String playerName, Optional<StatSnapshot> snapshotOptional) {
        if (snapshotOptional.isEmpty()) {
            print("暂无 stat 记录: " + playerName);
            return;
        }
        StatSnapshot snapshot = snapshotOptional.get();
        PlayerPermissions permissions = snapshot.permissions == null ? new PlayerPermissions() : snapshot.permissions;
        Integer deaths = snapshot.deathCount != null ? snapshot.deathCount : snapshot.onlineCount;
        String priority = snapshot.priorityQueue != null ? snapshot.priorityQueue : snapshot.team;
        print(SectionFormatter.header("Player stat"));
        print(CYAN + "玩家名称: " + PLAYER_COLOR + playerName + RESET);
        statField("加入游戏", value(snapshot.addedGameCount) + " 次");
        statField("死亡计数", value(deaths) + " 次");
        statField("击杀计数", value(snapshot.killCount) + " 人");
        print(CYAN + "游戏时长: " + YELLOW + duration(snapshot.playtimeSeconds) + RESET);
        String priorityColor = "已过期".equals(priority) ? RED : RESET;
        print(CYAN + "优先队列: " + priorityColor + value(priority) + RESET);
        statField("特殊权限", permissionsDisplay(snapshot, permissions));
        print(SectionFormatter.divider("Player stat"));
    }

    private void statField(String label, String value) {
        print(CYAN + label + ": " + RESET + value);
    }

    private void latestLogin(String playerName, Optional<LoginSession> sessionOptional) {
        if (sessionOptional.isEmpty()) {
            report("Latest login", List.of("玩家: " + playerName, "暂无登录记录"));
            return;
        }
        LoginSession session = sessionOptional.get();
        report("Latest login", loginLines(playerName, session));
    }

    private void recentLogins(String playerName, List<LoginSession> sessions, int totalCount) {
        if (sessions.isEmpty()) {
            report("Recent logins", List.of("玩家: " + playerName, "暂无登录记录"));
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("玩家: " + playerName);
        lines.add("显示最近 " + sessions.size() + " / " + totalCount + " 次登录");
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

    private void chats(String playerName, List<ChatEntry> chats, int totalCount) {
        if (chats.isEmpty()) {
            report("Recent chat", List.of("玩家: " + playerName, "暂无聊天记录"));
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("玩家: " + playerName);
        int shown = chats.size();
        lines.add("显示最近 " + shown + " / " + totalCount + " 条消息");
        for (ChatEntry entry : chats) {
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
        print(SectionFormatter.header(title));
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
                    String label = line.substring(0, separator + 1);
                    String value = line.substring(separator + 1);
                    // Player name values should use green
                    if (label.equals("玩家:") || label.equals("玩家：")) {
                        int contentStart = 0;
                        while (contentStart < value.length() && Character.isWhitespace(value.charAt(contentStart))) {
                            contentStart++;
                        }
                        String spacing = value.substring(0, contentStart);
                        String playerName = value.substring(contentStart);
                        rendered = CYAN + label + RESET + spacing + PLAYER_COLOR + playerName + RESET;
                    } else {
                        rendered = CYAN + label + RESET + value;
                    }
                }
            }
            print("  " + highlightTimestamps(rendered));
        }
        print(SectionFormatter.divider(title));
    }

    private void help() {
        print(colorUsage("Usage: playermonitor setting <option> <value>"));
        print(colorUsage("Usage: playermonitor scan-stat"));
        print(colorUsage("Usage: playermonitor db-stat"));
        playerHelp();
    }

    private void settingHelp() {
        for (String item : SETTING_ITEMS) {
            print(colorUsage("Usage: playermonitor setting " + item + " " + placeholderFor(item)));
        }
    }

    private void playerHelp() {
        print(colorUsage("Usage: playermonitor " + PLAYER_PLACEHOLDER
                + " [stat|latestlogin|recentlogin [count]|chat [count]]"));
    }

    private void print(String message) {
        logger.info(message);
    }

    private static String placeholderFor(String setting) {
        return switch (setting) {
            case "scan-on-entry", "stat-enabled", "stat-output-hide", "scan-on-join",
                    "prioritize-join-stat" -> TRUE_FALSE_PLACEHOLDER;
            case "disconnect-timeout", "cache-idle" -> MINUTE_PLACEHOLDER;
            case "stat-send-interval", "stat-timeout" -> MS_PLACEHOLDER;
            case "stat-cooldown", "backup-interval" -> HOUR_PLACEHOLDER;
            case "display-timezone" -> TIMEZONE_PLACEHOLDER;
            default -> COUNT_PLACEHOLDER;
        };
    }

    private static List<String> matching(String input, List<String> values) {
        String prefix = input == null ? "" : input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("请输入 true 或 false。");
        };
    }

    private static int parsePositiveInt(String value, String placeholder) {
        int parsed = parseInt(value, placeholder);
        if (parsed <= 0) {
            throw new IllegalArgumentException("数值必须大于 0。");
        }
        return parsed;
    }

    private static int parseInt(String value, String placeholder) {
        if (placeholder.equalsIgnoreCase(value)) {
            throw new NumberFormatException(value);
        }
        return Integer.parseInt(value);
    }

    private static int queryCount(String[] args, int defaultValue) {
        if (args.length < 3) {
            return defaultValue;
        }
        int value = Integer.parseInt(args[2]);
        if (value < MonitorSettingsStore.MIN_QUERY_COUNT || value > MonitorSettingsStore.MAX_QUERY_COUNT) {
            throw new NumberFormatException(args[2]);
        }
        return value;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
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

    private String format(long timestamp) {
        return TIME_FORMAT.withZone(settings.displayZoneId()).format(Instant.ofEpochMilli(timestamp));
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

    static List<String> databaseStatsLines(DatabaseStats stats) {
        return List.of(
                "玩家数: " + gold(stats.players()),
                "聊天记录: " + gold(stats.chats()),
                "登录会话: " + gold(stats.sessions()),
                "玩家 Stat 信息记录: " + gold(stats.stats()),
                "未结束会话: " + gold(stats.openSessions()));
    }

    private static String gold(long value) {
        return COUNT_COLOR + value + RESET;
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
