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
    private static final String UUID_VALUE_COLOR = "\u001B[38;5;29m";
    private static final String SETTING_NAME = "\u001B[38;5;151m"; // xterm-256 approximation of #ADEBB3
    private static final String SETTING_VALUE = "\u001B[38;5;215m"; // xterm-256 approximation of #FFC067
    private static final String PLAYER_ACTION = "\u001B[38;5;183m"; // xterm-256 approximation of #E0B0FF
    private static final String COMMAND_NAME = "\u001B[38;5;145m"; // xterm-256 approximation of #A6ADB4
    private static final String COUNT_COLOR = "\u001B[38;5;215m"; // xterm-256 approximation of #ffb343
    private static final String BRIGHT_GREEN = "\u001B[92m";

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
    private static final String URL_PLACEHOLDER = "<url>";

    private static final List<String> PLAYER_ACTIONS = List.of("playerinfo", "stat", "uuid", "latestlogin", "recentlogin", "chat");
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
            "uuidRecordEnable",
            "uuidRecordCooldown",
            "thirdPartyYggdrasilBaseUrl",
            "backup-interval");

    private static final List<String> TIMEZONE_VALUES = MonitorSettingsStore.SUPPORTED_TIMEZONES;

    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CHAT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern PRIORITY_DURATION = Pattern.compile(
            "^(?:(\\d+)天)?(?:(\\d+)(?:小时|时))?(?:(\\d+)分)?(?:(\\d+)秒)?$");

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
                scanStat();
            } else {
                help();
            }
            return;
        }
        if ("scan".equalsIgnoreCase(args[0]) && args.length == 2) {
            if ("stat".equalsIgnoreCase(args[1])) {
                scanStat();
            } else if ("uuid".equalsIgnoreCase(args[1])) {
                scanUuid();
            } else {
                help();
            }
            return;
        }
        if ("status".equalsIgnoreCase(args[0]) || "db-stat".equalsIgnoreCase(args[0])) {
            if (args.length == 1) {
                status(false);
            } else if ("status".equalsIgnoreCase(args[0])
                    && args.length == 2 && "full".equalsIgnoreCase(args[1])) {
                status(true);
            } else {
                help();
            }
            return;
        }
        if ("backup".equalsIgnoreCase(args[0])) {
            backup(args);
            return;
        }
        player(args);
    }

    @Override
    public List<String> onTabComplete(Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            return List.of("setting", "scan", "status", "backup", PLAYER_PLACEHOLDER);
        }
        if ("setting".equalsIgnoreCase(args[0])) {
            return completeSetting(args);
        }
        if ("scan-stat".equalsIgnoreCase(args[0]) && args.length > 1) {
            return List.of();
        }
        if ("scan".equalsIgnoreCase(args[0])) {
            return args.length == 1 ? List.of("stat", "uuid") : List.of();
        }
        if ("status".equalsIgnoreCase(args[0]) && args.length == 2) {
            return matching(args[1], List.of("full"));
        }
        if (("status".equalsIgnoreCase(args[0]) || "db-stat".equalsIgnoreCase(args[0])) && args.length > 1) {
            return List.of();
        }
        if ("backup".equalsIgnoreCase(args[0])) {
            return completeBackup(args);
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
            if ("setting".equals(value)
                    || "scan-stat".equals(value) || "scan".equals(value) || "status".equals(value)
                    || "db-stat".equals(value) || "backup".equals(value)) {
                return ROOT_COMMAND_STYLE;
            }
            return PLAYER_STYLE;
        }
        String root = lower(args[0]);
        if ("status".equals(root) && index == 1 && "full".equals(value)) {
            return PLAYER_ACTION_STYLE;
        }
        if ("scan-stat".equals(root)
                || "status".equals(root) || "db-stat".equals(root)) {
            return AttributedStyle.DEFAULT;
        }
        if ("scan".equals(root)) {
            return index == 1 && ("stat".equals(value) || "uuid".equals(value))
                    ? PLAYER_ACTION_STYLE : AttributedStyle.DEFAULT;
        }
        if ("backup".equals(root)) {
            return index == 1 ? PLAYER_ACTION_STYLE : AttributedStyle.DEFAULT;
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
        if (usage.equals("Usage: playermonitor scan stat") || usage.equals("Usage: playermonitor scan uuid")) {
            String action = usage.substring("Usage: playermonitor scan ".length());
            return "Usage: " + COMMAND_NAME + "playermonitor" + RESET + " "
                    + ROOT_COMMAND + "scan" + RESET + " " + PLAYER_ACTION + action + RESET;
        }
        if (usage.startsWith("Usage: playermonitor status")) {
            String remainder = usage.substring("Usage: playermonitor status".length());
            return "Usage: " + COMMAND_NAME + "playermonitor" + RESET + " "
                    + ROOT_COMMAND + "status" + RESET + PLAYER_ACTION + remainder + RESET;
        }
        if (usage.startsWith("Usage: playermonitor backup")) {
            String remainder = usage.substring("Usage: playermonitor backup".length());
            return "Usage: " + COMMAND_NAME + "playermonitor" + RESET + " "
                    + ROOT_COMMAND + "backup" + RESET + PLAYER_ACTION + remainder + RESET;
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
                    listener.applyStatSettingsNow();
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
                    listener.applyStatSettingsNow();
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
                    listener.applyStatSettingsNow();
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
                case "uuidrecordenable", "uuid-record-enable" -> {
                    boolean parsed = parseBoolean(value);
                    settings.setUuidRecordEnable(parsed);
                    if (listener != null) {
                        listener.applyUuidSettingsNow();
                    }
                    print("UUID 定期记录已设置为 " + parsed + "，已立即生效。");
                }
                case "uuidrecordcooldown", "uuid-record-cooldown" -> {
                    int parsed = parseInt(value, HOUR_PLACEHOLDER);
                    settings.setUuidRecordCooldown(parsed);
                    if (listener != null) {
                        listener.applyUuidSettingsNow();
                    }
                    print("UUID 记录冷却时间已设置为 " + parsed + " 小时，已立即生效。");
                }
                case "thirdpartyyggdrasilbaseurl", "third-party-yggdrasil-base-url" -> {
                    settings.setThirdPartyYggdrasilBaseUrl(value);
                    print("第三方 Yggdrasil 地址已设置为 " + settings.thirdPartyYggdrasilBaseUrl() + "，已立即生效。");
                }
                case "cache-idle", "max-cached-history" ->
                        print("该设置仅用于旧版内存存储，SQLite 后端不使用它，未修改设置。");
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
        if (args.length == 1 || (args.length == 2 && "playerinfo".equalsIgnoreCase(args[1]))) {
            playerData(playerName);
            return;
        }
        if (args.length > 3) {
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
                case "uuid" -> uuid(displayName, service.playerIdentity(playerName));
                default -> playerHelp();
            }
        } catch (NumberFormatException error) {
            print("显示数量必须是 5–50 之间的整数。");
        } catch (IOException error) {
            print("无法读取玩家记录: " + error.getMessage());
        }
    }

    private void playerData(String playerName) {
        try {
            long now = System.currentTimeMillis();
            Optional<PlayerOverview> overviewOptional = service.playerOverview(playerName, now);
            if (overviewOptional.isEmpty()) {
                print("未找到玩家档案: " + playerName);
                return;
            }
            PlayerOverview overview = overviewOptional.get();
            StatSnapshot snapshot = overview.latestStat();
            PlayerPermissions permissions = snapshot == null || snapshot.permissions == null
                    ? new PlayerPermissions() : snapshot.permissions;
            Integer kills = snapshot == null ? null : snapshot.killCount;
            Integer deaths = snapshot == null ? null : snapshot.deathCount;
            String priority = snapshot == null ? null
                    : snapshot.priorityQueue != null ? snapshot.priorityQueue : snapshot.team;
            print(SectionFormatter.header("Player Data"));
            playerDataField("玩家：", overview.playerName());
            playerDataUuidField("uuid：", overview.identity());
            playerDataCountField("> 发言次数：", overview.chatCount() + "次");
            playerDataCountField("> 击杀数：", value(kills) + "人");
            playerDataCountField("> 死亡次数：", value(deaths) + "次");
            playerDataCountField("> KD比：", kd(kills, deaths));
            print("");
            playerDataPlainField("[首次记录]： ", format(overview.firstSeenAt()));
            playerDataPlainField("[最近上线]： ", optionalTime(overview.latestLoginAt()));
            print(CYAN + "[最近下线]： " + RESET + latestLogout(overview));
            playerDataCountField("[最近游玩时长]： ", durationMillis(overview.latestSessionDurationMillis()));
            print("");
            playerDataField("特殊付费权限：", paidPermissionsDisplay(snapshot, permissions));
            long priorityBaseAt = snapshot == null || snapshot.capturedAt <= 0L ? now : snapshot.capturedAt;
            playerDataField("优先列队：", priorityDisplay(priority, priorityBaseAt));
            print("");
            printRecentChats(overview.recentChats(), overview.chatCount());
            print("");
            playerDataCountField("- 总游玩时长：", hours(snapshot == null ? null : snapshot.playtimeSeconds));
            playerDataCountField("- 近30天游玩时长：", hoursFromMillis(overview.playtimeLast30DaysMillis()));
            playerDataCountField("- 加入游戏次数：", value(snapshot == null ? null : snapshot.addedGameCount) + "次");
            print(SectionFormatter.divider("Player Data"));
            if (listener != null) {
                listener.refreshUuidIfEligible(playerName);
            }
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
                case "disconnect-timeout" -> List.of(MINUTE_PLACEHOLDER);
                case "stat-send-interval", "stat-timeout" -> List.of(MS_PLACEHOLDER);
                case "stat-cooldown", "backup-interval" -> List.of(HOUR_PLACEHOLDER);
                case "stat-attempts" -> List.of(COUNT_PLACEHOLDER);
                case "display-timezone" -> TIMEZONE_VALUES;
                case "recentlogin-count" -> List.of(Integer.toString(settings.recentLoginCount()));
                case "chat-count" -> List.of(Integer.toString(settings.chatCount()));
                case "uuidrecordenable", "uuid-record-enable" -> BOOLEAN_VALUES;
                case "uuidrecordcooldown", "uuid-record-cooldown" -> List.of(HOUR_PLACEHOLDER);
                case "thirdpartyyggdrasilbaseurl", "third-party-yggdrasil-base-url" -> List.of(URL_PLACEHOLDER);
                default -> List.of();
            };
            return matching(args[2], candidates);
        }
        return List.of();
    }

    private List<String> completeBackup(String[] args) {
        List<String> actions = List.of("now", "status", "list", "verify", "limit");
        if (args.length == 1) {
            return actions;
        }
        if (args.length == 2) {
            return matching(args[1], actions);
        }
        if (args.length == 3 && "limit".equalsIgnoreCase(args[1])) {
            return matching(args[2], List.of(Integer.toString(settings.backupMaxCount())));
        }
        if (args.length == 3 && "verify".equalsIgnoreCase(args[1])) {
            SQLiteBackupManager manager = backupManager;
            if (manager == null) {
                return List.of();
            }
            try {
                return matching(args[2], manager.listBackups().stream()
                        .map(SQLiteBackupManager.BackupFileInfo::filename)
                        .toList());
            } catch (IOException error) {
                logger.warn("Unable to complete backup filename", error);
                return List.of();
            }
        }
        return List.of();
    }

    private List<String> completePlayerNames(String input) {
        if (input == null || input.isEmpty()) {
            return List.of("setting", "scan", "status", "backup", PLAYER_PLACEHOLDER);
        }
        try {
            TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            names.addAll(service.listPlayerNamesSnapshot());
            names.addAll(listener.onlinePlayerNames());
            String prefix = input.toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            if ("setting".startsWith(prefix)) {
                matches.add("setting");
            }
            if ("scan".startsWith(prefix)) {
                matches.add("scan");
            }
            if ("status".startsWith(prefix)) {
                matches.add("status");
            }
            if ("backup".startsWith(prefix)) {
                matches.add("backup");
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
        List<String> lines = new ArrayList<>(settingStatusLines(current));
        if (settings.hasDeferredStatSettings()) {
            lines.add("状态: Stat 设置已保存，等待当前请求完成后生效");
        }
        report("PlayerMonitor 设置", lines);
    }

    static List<String> settingUsageLines() {
        return SETTING_ITEMS.stream()
                .map(item -> "Usage: playermonitor setting " + item + " " + placeholderFor(item))
                .toList();
    }

    static List<String> settingStatusLines(MonitorSettings current) {
        return List.of(
                "进入 Game 自动扫描: " + state(current.scanOnEntry),
                "断线确认时间: " + current.disconnectTimeoutMinutes + " min",
                "Stat 自动扫描总开关: " + state(current.statEnabled),
                "Stat 发送间隔: " + current.statSendIntervalMillis + " ms",
                "Stat 输出隐藏: " + state(current.statOutputHide),
                "自动 Stat 冷却: " + current.statCooldownHours + " h",
                "Stat 响应超时: " + current.statTimeoutMillis + " ms",
                "Stat 最大尝试次数: " + current.statAttempts,
                "玩家加入自动扫描: " + state(current.scanOnJoin),
                "新加入玩家优先扫描: " + state(current.prioritizeJoinStat),
                "显示时区: " + current.displayTimezone,
                "近期登录默认数量: " + current.recentLoginCount,
                "聊天默认数量: " + current.chatCount,
                "UUID 记录启用: " + current.uuidRecordEnable,
                "UUID 记录冷却: " + current.uuidRecordCooldown + " h",
                "第三方 Yggdrasil 地址: " + current.thirdPartyYggdrasilBaseUrl,
                "自动备份间隔: " + current.backupInterval + " h");
    }

    private void scanStat() {
        int queued = listener.scanAllOnlinePlayers();
        if (queued < 0) {
            print("只有处于 Game 状态时才能手动扫描 Stat。");
            return;
        }
        print("已将 " + queued + " 名在线玩家加入 Stat 扫描队列。");
    }

    private void scanUuid() {
        int queued = listener.scanAllOnlineUuidPlayers();
        if (queued == -1) {
            print("只有处于 Game 状态时才能手动扫描 UUID。");
        } else if (queued == -2) {
            print("UUID 扫描正在运行。");
        } else {
            print("已将 " + queued + " 名在线玩家加入 UUID 扫描队列。");
        }
    }

    private void status(boolean full) {
        List<String> lines = new ArrayList<>();
        lines.add("Plugin Version: " + XinPlayerMonitor.pluginVersion());
        PlayerMonitorListener.StatScanStatus scan = listener.statScanStatus();
        if (full) {
            lines.add("Game Active: " + countValue(scan.gameActive()));
            lines.add("Reconnect Pending: " + countValue(scan.reconnectPending()));
            lines.add("Roster Reconciling: " + countValue(scan.rosterReconciling()));
            lines.add("Force Fresh Roster: " + countValue(scan.forceFreshRoster()));
            lines.add("Reconnect Generation: " + countValue(scan.reconnectGeneration()));
            lines.add("Last Disconnect: " + statusTime(scan.lastDisconnectAt()));
            lines.add("Bot Roster: " + countValue(scan.botRoster()));
            lines.add("Monitor Roster: " + countValue(scan.monitorRoster()));
            lines.add("Roster Drift: " + statusValue("missing", scan.missingFromMonitor())
                    + ", " + statusValue("extra", scan.extraInMonitor()));
            lines.add("Stat 扫描: " + statusValue("online", scan.onlinePlayers())
                    + ", " + statusValue("queued", scan.queued())
                    + ", " + statusValue("pending", scan.pendingDispatches())
                    + ", " + statusValue("active", scan.activeCycles())
                    + ", " + statusValue("waiting-response", scan.waitingResponse()));
            lines.add("Chat Pipeline:");
            lines.add("  system received:   " + countValue(scan.systemChatReceived()));
            lines.add("  public parsed:     " + countValue(scan.publicChatParsed()));
            lines.add("  monitor accepted:  " + countValue(scan.chatAcceptedByPlayerMonitor()));
            lines.add("  rejected:          " + countValue(scan.chatRejected()));
            lines.add("  parse failed:      " + countValue(scan.chatParseFailed()));
            lines.add("  db failed:         " + countValue(scan.chatDbFailed()));
        } else {
            lines.add("连接状态: " + statusValue("游戏活跃", scan.gameActive())
                    + ", " + statusValue("等待重连", scan.reconnectPending())
                    + ", " + statusValue("名单同步中", scan.rosterReconciling())
                    + ", " + statusValue("强制刷新名单", scan.forceFreshRoster())
                    + ", " + statusValue("重连代次", scan.reconnectGeneration()));
            lines.add("最近断线: " + statusTime(scan.lastDisconnectAt()));
            lines.add("Roster: " + statusValue("bot", scan.botRoster())
                    + ", " + statusValue("monitor", scan.monitorRoster())
                    + ", " + statusValue("missing", scan.missingFromMonitor())
                    + ", " + statusValue("extra", scan.extraInMonitor()));
            lines.add("Stat 扫描: " + statusValue("在线", scan.onlinePlayers())
                    + ", " + statusValue("队列", scan.queued())
                    + ", " + statusValue("待发送", scan.pendingDispatches())
                    + ", " + statusValue("活动轮次", scan.activeCycles())
                    + ", " + statusValue("等待响应", scan.waitingResponse()));
            lines.add("Chat Pipeline: " + statusValue("system received", scan.systemChatReceived())
                    + ", " + statusValue("public parsed", scan.publicChatParsed())
                    + ", " + statusValue("monitor accepted", scan.chatAcceptedByPlayerMonitor()));
            lines.add("Chat Errors: " + statusValue("rejected", scan.chatRejected())
                    + ", " + statusValue("parse failed", scan.chatParseFailed())
                    + ", " + statusValue("db failed", scan.chatDbFailed()));
        }
        try {
            DatabaseHealth health = service.databaseHealth();
            if (full) {
                lines.add("  db committed:      " + countValue(health.chatCommitted()));
            } else {
                lines.add("Chat Writes: " + statusValue("db committed", health.chatCommitted())
                        + ", " + statusValue("queue rejected", health.chatQueueRejected()));
            }
            String condition;
            double queueUsage = health.queueCapacity() <= 0
                    ? 0.0
                    : (double) health.queueSize() / (double) health.queueCapacity();
            if ("FAILED".equals(health.writerState()) || !health.writerAlive() || health.writerStalled()) {
                condition = "错误";
            } else if (health.pendingFailedEvents() > 0 || health.writerRecovering() || queueUsage >= 0.80) {
                condition = "降级";
            } else {
                condition = "正常";
            }
            String displayedCondition = full ? switch (condition) {
                case "错误" -> "ERROR";
                case "降级" -> "DEGRADED";
                default -> "HEALTHY";
            } : condition;
            if ("正常".equals(condition)) {
                displayedCondition = BRIGHT_GREEN + displayedCondition + RESET;
            }
            lines.add("健康状态: " + displayedCondition);
            if (full) {
                lines.add("Writer 状态: " + health.writerState()
                        + " / " + statusValue("alive", health.writerAlive())
                        + " / " + statusValue("recovering", health.writerRecovering())
                        + " / " + statusValue("stalled", health.writerStalled()));
                lines.add("SQLite queue: " + countValue(health.queueSize()) + " / "
                        + countValue(health.queueCapacity()));
                lines.add("Peak queue: " + countValue(health.queueHighWaterMark()));
                lines.add("Queue growth: " + statusValue("1m", signed(health.queueDelta1m()))
                        + ", " + statusValue("5m", signed(health.queueDelta5m())));
            } else {
                lines.add("Writer: state=" + health.writerState()
                        + ", " + statusValue("alive", health.writerAlive())
                        + ", " + statusValue("recovering", health.writerRecovering())
                        + ", " + statusValue("stalled", health.writerStalled())
                        + ", " + statusValue("recoveries", health.writerRecoveryCount()));
                lines.add("SQLite queue: " + countValue(health.queueSize()) + " / " + countValue(health.queueCapacity())
                        + ", " + statusValue("peak", health.queueHighWaterMark())
                        + ", " + statusValue("1m", signed(health.queueDelta1m()))
                        + ", " + statusValue("5m", signed(health.queueDelta5m())));
            }
            lines.add("最近提交: " + statusTime(health.lastCommittedAt()));
            if (full) {
                lines.add("最近Chat写入: " + statusTime(health.lastChatCommittedAt()));
                lines.add("最近Session写入: " + statusTime(health.lastSessionCommittedAt()));
                lines.add("最近Stat写入: " + statusTime(health.lastStatCommittedAt()));
                lines.add("最近UUID写入: " + statusTime(health.lastUuidWrittenAt()));
            } else {
                lines.add("最近写入：");
                lines.add("> " + greenLabel("聊天=") + statusTime(health.lastChatCommittedAt()));
                lines.add("> " + greenLabel("会话=") + statusTime(health.lastSessionCommittedAt()));
                lines.add("> " + greenLabel("Stat=") + statusTime(health.lastStatCommittedAt()));
                lines.add("> " + greenLabel("UUID=") + statusTime(health.lastUuidWrittenAt()));
            }
            lines.add("最近失败: " + statusTime(health.lastFailureAt()));
            if (health.lastFailureAt() > 0 && health.lastFailureMessage() != null
                    && !health.lastFailureMessage().isBlank()) {
                lines.add("失败原因: " + health.lastFailureMessage());
            }
            if (full) {
                lines.add("Chat 写入: " + statusValue("db committed", health.chatCommitted())
                        + ", " + statusValue("db failed", health.chatDbFailed())
                        + ", " + statusValue("queue rejected", health.chatQueueRejected()));
                lines.add("Writer recoveries: " + countValue(health.writerRecoveryCount()));
            }
            lines.add((full ? "失败事件: " : "Failed events: ")
                    + statusValue("pending", health.pendingFailedEvents())
                    + ", " + statusValue("replayed", health.replayedFailedEvents())
                    + ", " + statusValue("malformed", health.malformedFailedEvents())
                    + ", " + statusValue("file-lines", health.failedEventLines())
                    + ", " + statusValue("persist-failed", health.failedEventPersistFailures()));
            lines.add("数据库文件: " + (full ? "db=" : "主库=") + humanBytes(health.databaseBytes())
                    + ", " + (full ? "wal=" : "WAL=") + humanBytes(health.walBytes())
                    + ", " + (full ? "shm=" : "SHM=") + humanBytes(health.shmBytes()));
            lines.add("Database Version: v" + SQLiteSchema.VERSION);
        } catch (IOException error) {
            lines.add("数据库健康信息: 无法读取 - " + error.getMessage());
        }

        try {
            DatabaseStats stats = service.databaseStatsSnapshot();
            lines.addAll(databaseStatsLines(stats));
        } catch (IOException error) {
            lines.add("数据库记录统计: 无法读取 - " + error.getMessage());
        }

        SQLiteBackupManager manager = backupManager;
        if (manager == null) {
            lines.add("备份状态: " + (full ? "unavailable" : "不可用"));
        } else {
            try {
                SQLiteBackupManager.BackupStatus backup = manager.status();
                lines.add("备份调度: "
                        + statusValue(full ? "running" : "运行", backup.schedulerRunning())
                        + ", " + statusValue(full ? "in-progress" : "进行中", backup.backupInProgress())
                        + ", " + statusValue(full ? "interval" : "间隔", backup.intervalHours()) + " h");
                lines.add("备份数量: " + countValue(backup.backupCount()) + " / " + countValue(backup.maxBackupCount()));
                lines.add("最近备份: " + (backup.lastBackupAt() <= 0
                        ? "无" : backup.lastBackupFile() + " @ " + statusTime(backup.lastBackupAt())));
                lines.add("下次备份: " + statusTime(backup.nextBackupAt()));
            } catch (IOException error) {
                lines.add("备份状态: 无法读取 - " + error.getMessage());
            }
        }
        report(full ? "PlayerMonitor status" : "PlayerMonitor 状态", lines);
    }

    private void backup(String[] args) {
        if (args.length < 2) {
            backupHelp();
            return;
        }
        SQLiteBackupManager manager = backupManager;
        if (manager == null) {
            print("备份管理器不可用。");
            return;
        }
        String action = lower(args[1]);
        switch (action) {
            case "now" -> {
                if (args.length != 2) {
                    backupHelp();
                    return;
                }
                print("正在创建并校验数据库备份...");
                if (!manager.backupNow()) {
                    print("数据库备份失败或已有备份正在执行，请查看日志。");
                    return;
                }
                try {
                    SQLiteBackupManager.BackupStatus backup = manager.status();
                    print("数据库备份完成: " + backup.lastBackupFile());
                } catch (IOException error) {
                    print("数据库备份已完成，但无法读取备份状态: " + error.getMessage());
                }
            }
            case "status" -> {
                if (args.length != 2) {
                    backupHelp();
                    return;
                }
                backupStatus(manager);
            }
            case "list" -> {
                if (args.length != 2) {
                    backupHelp();
                    return;
                }
                backupList(manager);
            }
            case "limit" -> {
                if (args.length != 3) {
                    backupHelp();
                    return;
                }
                try {
                    int parsed = parsePositiveInt(args[2], COUNT_PLACEHOLDER);
                    settings.setBackupMaxCount(parsed);
                    print("数据库备份最多保留 " + parsed + " 个，将在下一次成功备份后应用。");
                } catch (NumberFormatException error) {
                    print("请输入有效的整数值。");
                } catch (IllegalArgumentException | IOException error) {
                    print("无法保存备份保留数量: " + error.getMessage());
                }
            }
            case "verify" -> {
                if (args.length != 3) {
                    backupHelp();
                    return;
                }
                SQLiteBackupManager.BackupVerification result = manager.verify(args[2]);
                report("Backup verification", List.of(
                        "文件: " + result.filename(),
                        "结果: " + (result.valid() ? "VALID" : "INVALID"),
                        "大小: " + humanBytes(result.sizeBytes()),
                        "详情: " + result.detail()));
            }
            default -> backupHelp();
        }
    }

    private void backupStatus(SQLiteBackupManager manager) {
        try {
            SQLiteBackupManager.BackupStatus backup = manager.status();
            report("Backup status", List.of(
                    "调度器运行: " + backup.schedulerRunning(),
                    "备份进行中: " + backup.backupInProgress(),
                    "自动备份间隔: " + backup.intervalHours() + " h",
                    "备份数量: " + backup.backupCount(),
                    "最多保留: " + backup.maxBackupCount(),
                    "最近备份: " + (backup.lastBackupAt() <= 0
                            ? "无" : backup.lastBackupFile() + " @ " + format(backup.lastBackupAt())),
                    "下次备份: " + optionalTime(backup.nextBackupAt())));
        } catch (IOException error) {
            print("无法读取备份状态: " + error.getMessage());
        }
    }

    private void backupList(SQLiteBackupManager manager) {
        try {
            List<SQLiteBackupManager.BackupFileInfo> backups = manager.listBackups();
            if (backups.isEmpty()) {
                report("Backup list", List.of("暂无备份文件"));
                return;
            }
            List<String> lines = new ArrayList<>();
            lines.add("备份总数: " + backups.size());
            int shown = Math.min(20, backups.size());
            for (int index = 0; index < shown; index++) {
                SQLiteBackupManager.BackupFileInfo backup = backups.get(index);
                lines.add("#" + (index + 1) + ": " + backup.filename()
                        + " | " + format(backup.timestamp())
                        + " | " + humanBytes(backup.sizeBytes()));
            }
            if (backups.size() > shown) {
                lines.add("其余备份: " + (backups.size() - shown) + " 个未显示");
            }
            report("Backup list", lines);
        } catch (IOException error) {
            print("无法列出备份文件: " + error.getMessage());
        }
    }

    private void backupHelp() {
        print(colorUsage("Usage: playermonitor backup now"));
        print(colorUsage("Usage: playermonitor backup status"));
        print(colorUsage("Usage: playermonitor backup list"));
        print(colorUsage("Usage: playermonitor backup verify <filename>"));
        print(colorUsage("Usage: playermonitor backup limit <count>"));
    }

    private String latestLogout(PlayerOverview overview) {
        if (overview.latestLoginAt() == null) {
            return "无";
        }
        return overview.latestLogoutAt() == null ? "在线中" : format(overview.latestLogoutAt());
    }

    private void printRecentChats(List<ChatEntry> chats, long totalCount) {
        int shown = chats == null ? 0 : chats.size();
        print(CYAN + "最近发言 " + RESET + "(" + shown + "/" + totalCount + ")"
                + CYAN + ":" + RESET);
        if (chats == null) {
            return;
        }
        for (ChatEntry entry : chats) {
            print(BRIGHT_GREEN + "[" + formatChatTime(entry.timestamp) + " CHAT]:" + RESET
                    + " " + entry.message);
        }
    }

    private void playerDataField(String label, String value) {
        print(CYAN + label + RESET + YELLOW + value + RESET);
    }

    private void playerDataCountField(String label, String value) {
        print(CYAN + label + RESET + COUNT_COLOR + value + RESET);
    }

    private void playerDataUuidField(String label, StoredPlayerIdentity identity) {
        if (identity == null || identity.serverUuid() == null || identity.serverUuid().isBlank()) {
            print(CYAN + label + RESET + "无");
            return;
        }
        print(CYAN + label + RESET + UUID_VALUE_COLOR + identity.serverUuid() + RESET);
    }

    private void playerDataPlainField(String label, String value) {
        print(CYAN + label + RESET + value);
    }

    private void stat(String playerName, Optional<StatSnapshot> snapshotOptional) {
        if (snapshotOptional.isEmpty()) {
            print("暂无 stat 记录: " + playerName);
            return;
        }
        StatSnapshot snapshot = snapshotOptional.get();
        PlayerPermissions permissions = snapshot.permissions == null ? new PlayerPermissions() : snapshot.permissions;
        Integer deaths = snapshot.deathCount;
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

    private void uuid(String playerName, Optional<StoredPlayerIdentity> identity) {
        StoredPlayerIdentity value = identity.orElse(null);
        print(SectionFormatter.header("uuid"));
        print("");
        print("  " + CYAN + "玩家: " + RESET + PLAYER_COLOR + playerName + RESET);
        print("  " + CYAN + "uuid: " + RESET + uuidValue(value));
        print("    " + CYAN + "记录时间: " + RESET + uuidTime(value == null ? null : value.uuidLastWrittenAt()));
        print("    " + CYAN + "最后检查时间: " + RESET + uuidTime(value == null ? null : value.uuidLastCheckedAt()));
        print(SectionFormatter.divider("uuid"));
        if (listener != null) {
            listener.refreshUuidIfEligible(playerName);
        }
    }

    private String uuidValue(StoredPlayerIdentity identity) {
        if (identity == null || identity.serverUuid() == null || identity.serverUuid().isBlank()) {
            return "无";
        }
        return identity.serverUuid();
    }

    private String uuidTime(Long timestamp) {
        return timestamp == null || timestamp <= 0L ? "无" : YELLOW + format(timestamp) + RESET;
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
            if (TIMESTAMP.matcher(line).lookingAt()
                    || line.startsWith(BRIGHT_GREEN) || line.startsWith("> " + BRIGHT_GREEN)) {
                rendered = line;
            } else {
                int separator = Math.max(line.indexOf(':'), line.indexOf('：'));
                int equals = line.indexOf('=');
                if (equals >= 0 && equals < separator) {
                    separator = -1;
                }
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
            print("  " + rendered);
        }
        print(SectionFormatter.divider(title));
    }

    private void help() {
        print(colorUsage("Usage: playermonitor setting <option> <value>"));
        print(colorUsage("Usage: playermonitor scan stat"));
        print(colorUsage("Usage: playermonitor scan uuid"));
        print(colorUsage("Usage: playermonitor status [full]"));
        print(colorUsage("Usage: playermonitor backup [now|status|list|verify <filename>|limit <count>]"));
        playerHelp();
    }

    private void settingHelp() {
        for (String usage : settingUsageLines()) {
            print(colorUsage(usage));
        }
    }

    private void playerHelp() {
        print(colorUsage("Usage: playermonitor " + PLAYER_PLACEHOLDER
                + " [playerinfo|stat|uuid|latestlogin|recentlogin [count]|chat [count]]"));
    }

    private void print(String message) {
        logger.info(message);
    }

    private static String placeholderFor(String setting) {
        return switch (setting) {
            case "scan-on-entry", "stat-enabled", "stat-output-hide", "scan-on-join",
                    "prioritize-join-stat", "uuidRecordEnable" -> TRUE_FALSE_PLACEHOLDER;
            case "disconnect-timeout" -> MINUTE_PLACEHOLDER;
            case "stat-send-interval", "stat-timeout" -> MS_PLACEHOLDER;
            case "stat-cooldown", "backup-interval", "uuidRecordCooldown" -> HOUR_PLACEHOLDER;
            case "display-timezone" -> TIMEZONE_PLACEHOLDER;
            case "thirdPartyYggdrasilBaseUrl" -> URL_PLACEHOLDER;
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
        return paidPermissionsDisplay(snapshot, permissions);
    }

    static String paidPermissionsDisplay(StatSnapshot snapshot, PlayerPermissions permissions) {
        if (snapshot != null && snapshot.permissionsDisplay != null && !snapshot.permissionsDisplay.isBlank()) {
            return snapshot.permissionsDisplay;
        }
        return "🎨" + marker(permissions.greenText)
                + "丨👟" + marker(permissions.runMax)
                + "丨🎒" + marker(permissions.dupe);
    }

    private static String marker(boolean value) {
        return value ? "√" : "×";
    }

    String priorityDisplay(String priority, long now) {
        if (priority == null || priority.isBlank()) {
            return "未知";
        }
        Long millis = priorityDurationMillis(priority);
        if (millis == null) {
            return priority;
        }
        return priority + "  (预计到期时间: " + format(now + millis) + ")";
    }

    static Long priorityDurationMillis(String priority) {
        Matcher matcher = PRIORITY_DURATION.matcher(priority.trim());
        if (!matcher.matches()) {
            return null;
        }
        long days = parseDurationPart(matcher.group(1));
        long hours = parseDurationPart(matcher.group(2));
        long minutes = parseDurationPart(matcher.group(3));
        long seconds = parseDurationPart(matcher.group(4));
        if (days == 0L && hours == 0L && minutes == 0L && seconds == 0L) {
            return null;
        }
        return Duration.ofDays(days)
                .plusHours(hours)
                .plusMinutes(minutes)
                .plusSeconds(seconds)
                .toMillis();
    }

    private static long parseDurationPart(String value) {
        return value == null || value.isBlank() ? 0L : Long.parseLong(value);
    }

    private String format(long timestamp) {
        return TIME_FORMAT.withZone(settings.displayZoneId()).format(Instant.ofEpochMilli(timestamp));
    }

    private String formatChatTime(long timestamp) {
        return CHAT_TIME_FORMAT.withZone(settings.displayZoneId()).format(Instant.ofEpochMilli(timestamp));
    }

    private String optionalTime(long timestamp) {
        return timestamp <= 0L ? "无" : format(timestamp);
    }

    private String optionalTime(Long timestamp) {
        return timestamp == null || timestamp <= 0L ? "无" : format(timestamp);
    }

    private static String humanBytes(long bytes) {
        if (bytes < 0L) {
            return "unknown";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : Integer.toString(value);
    }

    private static String value(Object value) {
        return value == null ? "未知" : value.toString();
    }

    private String statusTime(long timestamp) {
        return timestamp <= 0L ? "无" : YELLOW + format(timestamp) + RESET;
    }

    private static String statusValue(String label, Object value) {
        return label + "=" + countValue(value);
    }

    private static String countValue(Object value) {
        return COUNT_COLOR + value + RESET;
    }

    private static String kd(Integer kills, Integer deaths) {
        if (kills == null || deaths == null) {
            return "未知";
        }
        if (deaths == 0) {
            return kills == 0 ? "0.000" : "∞";
        }
        return String.format(Locale.ROOT, "%.3f", (double) kills / (double) deaths);
    }

    private static String hours(Long seconds) {
        return seconds == null ? "未知" : String.format(Locale.ROOT, "%.2f小时", seconds / 3600.0);
    }

    private static String hoursFromMillis(long millis) {
        return String.format(Locale.ROOT, "%.2f小时", millis / 3_600_000.0);
    }

    private static String durationMillis(Long millis) {
        return millis == null ? "未知" : duration(Duration.ofMillis(millis).getSeconds());
    }

    static List<String> databaseStatsLines(DatabaseStats stats) {
        return List.of(
                greenLabel("玩家数:") + " " + gold(stats.players()),
                greenLabel("聊天记录:") + " " + gold(stats.chats()),
                greenLabel("登录会话:") + " " + gold(stats.sessions()),
                greenLabel("玩家 Stat 信息记录:") + " " + gold(stats.stats()),
                greenLabel("Player uuid 信息记录:") + " " + gold(stats.uuidRecords()),
                greenLabel("未结束会话:") + " " + gold(stats.openSessions()));
    }

    private static String greenLabel(String label) {
        return BRIGHT_GREEN + label + RESET;
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
