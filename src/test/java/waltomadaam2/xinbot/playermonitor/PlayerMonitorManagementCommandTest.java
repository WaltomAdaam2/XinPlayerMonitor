package waltomadaam2.xinbot.playermonitor;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMonitorManagementCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void highlightsRootSettingAndSettingValueWithFinalColors() {
        String[] args = {"setting", "stat-timeout", "3000"};

        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(args, 0));
        assertStyle(0xADEBB3, PlayerMonitorManagementCommand.styleForArgument(args, 1));
        assertStyle(0xFFC067, PlayerMonitorManagementCommand.styleForArgument(args, 2));
    }

    @Test
    void highlightsScanStatAsLavenderRootCommand() {
        String[] args = {"scan-stat"};
        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(args, 0));
    }


    @Test
    void scanStatDoesNotOfferPlayerActionsAfterTheCompleteCommand() {
        String[] invalidExtraArgument = {"scan-stat", "stat"};
        assertEquals(AttributedStyle.DEFAULT.getStyle(),
                PlayerMonitorManagementCommand.styleForArgument(invalidExtraArgument, 1).getStyle());
    }

    @Test
    void highlightsPlayerNameAndAllPlayerActions() {
        for (String action : new String[]{"stat", "latestlogin", "recentlogin", "chat"}) {
            String[] args = {"WaltomAdaam_", action};
            assertStyle(0x2E6F40, PlayerMonitorManagementCommand.styleForArgument(args, 0));
            assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(args, 1));
        }
    }

    @Test
    void settingUsageContainsFinalColorMapping() {
        String colored = PlayerMonitorManagementCommand.colorUsage(
                "Usage: playermonitor setting stat-send-interval <ms>");
        assertEquals(1, count(colored, "38;5;145mplayermonitor"));
        assertEquals(1, count(colored, "38;5;183msetting"));
        assertEquals(1, count(colored, "38;5;151mstat-send-interval"));
        assertEquals(1, count(colored, "38;5;215m<ms>"));
    }

    @Test
    void scanStatUsageUsesLavender() {
        String colored = PlayerMonitorManagementCommand.colorUsage("Usage: playermonitor scan-stat");
        assertEquals(1, count(colored, "38;5;183mscan-stat"));
    }

    @Test
    void playerUsageColorsAllActionsLavender() {
        String colored = PlayerMonitorManagementCommand.colorUsage(
                "Usage: playermonitor <玩家名> [stat|latestlogin|recentlogin [count]|chat [count]]");
        assertEquals(4, count(colored, "38;5;183m"));
        assertEquals(1, count(colored, "38;5;29m<玩家名>"));
    }


    @Test
    void completesCurrentConfiguredCountsAndUtcOffsets() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        settings.setChatCount(17);
        settings.setRecentLoginCount(9);
        PlayerMonitorService service = new PlayerMonitorService(directory, settings);
        service.initialize();
        PlayerMonitorListener listener = new PlayerMonitorListener(
                service, new PluginLog(directory.resolve("log")), NOPLogger.NOP_LOGGER, settings);
        try {
            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    service, settings, listener, NOPLogger.NOP_LOGGER);
            assertEquals(List.of("17"), command.onTabComplete(null, "playermonitor",
                    new String[]{"Steve", "chat", ""}));
            assertEquals(List.of("9"), command.onTabComplete(null, "playermonitor",
                    new String[]{"Steve", "recentlogin", ""}));
            assertEquals(List.of("UTC+05:00", "UTC+05:30", "UTC+05:45"),
                    command.onTabComplete(null, "playermonitor",
                            new String[]{"setting", "display-timezone", "UTC+05"}));
        } finally {
            listener.close();
            service.close();
        }
    }

    @Test
    void playerChatQueryUsesLimitedRepositoryMethods() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-limited-query");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        settings.setChatCount(7);
        LimitedQueryRepository repository = new LimitedQueryRepository();
        PlayerMonitorService service = new PlayerMonitorService(repository);
        service.initialize();
        PlayerMonitorListener listener = new PlayerMonitorListener(
                service, new PluginLog(directory.resolve("log")), NOPLogger.NOP_LOGGER, settings);
        try {
            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    service, settings, listener, NOPLogger.NOP_LOGGER);
            command.onCommand(null, "playermonitor", new String[]{"Steve", "chat"});
            assertEquals(7, repository.recentChatLimit);
            assertEquals(1, repository.chatCountCalls);
            assertEquals(0, repository.fullFindCalls, "command must not load full player history for chat output");
        } finally {
            listener.close();
            service.close();
        }
    }
    @Test
    void countArgumentInChatAndRecentLoginUsesFfb343() {
        // Chat count arg at index 2 should use #ffb343
        assertStyle(0xFFB343, PlayerMonitorManagementCommand.styleForArgument(
                new String[]{"WaltomAdaam_", "chat", "10"}, 2));
        // Recentlogin count arg at index 2 should use #ffb343
        assertStyle(0xFFB343, PlayerMonitorManagementCommand.styleForArgument(
                new String[]{"WaltomAdaam_", "recentlogin", "15"}, 2));
    }

    @Test
    void countPlaceholderInPlayerUsageUsesCountColor() {
        String colored = PlayerMonitorManagementCommand.colorUsage(
                "Usage: playermonitor <玩家名> [stat|latestlogin|recentlogin [count]|chat [count]]");
        // [count] placeholders should use #ffb343
        assertEquals(2, count(colored, "38;5;215m[count]"),
                "[count] placeholders should use #ffb343");
    }

    @Test
    void settingTabCompleteIncludesBackupInterval() {
        PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                null, null, null, NOPLogger.NOP_LOGGER);
        List<String> completions = command.onTabComplete(null, "playermonitor",
                new String[]{"setting", ""});
        assertTrue(completions.contains("backup-interval"),
                "tab completion should include backup-interval after 'playermonitor setting'");
    }

    @Test
    void backupIntervalCompletionOffersHourPlaceholder() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        PlayerMonitorService service = new PlayerMonitorService(directory, settings);
        service.initialize();
        PlayerMonitorListener listener = new PlayerMonitorListener(
                service, new PluginLog(directory.resolve("log")), NOPLogger.NOP_LOGGER, settings);
        try {
            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    service, settings, listener, NOPLogger.NOP_LOGGER);
            List<String> completions = command.onTabComplete(null, "playermonitor",
                    new String[]{"setting", "backup-interval", ""});
            assertEquals(List.of("<hour>"), completions);
        } finally {
            listener.close();
            service.close();
        }
    }

    @Test
    void colorUsageDoesNotAlterPlainTextContent() {
        String input = "Usage: playermonitor setting stat-send-interval <ms>";
        String colored = PlayerMonitorManagementCommand.colorUsage(input);
        // Strip ANSI codes
        String plain = colored.replaceAll("\\u001B\\[[0-9;]*m", "");
        assertEquals(input, plain,
                "color formatting must not alter the plain text content");
    }


    @Test
    void loggerRenderedUsageAvoidsUnsupportedTrueColorAndBackgroundCodes() {
        String colored = PlayerMonitorManagementCommand.colorUsage(
                "Usage: playermonitor <玩家名> [stat|latestlogin|recentlogin [count]|chat [count]]");
        assertTrue(!colored.contains("[38;2;"), "logger output should use xterm-256 colors");
        assertTrue(!colored.contains("[48;"), "logger output must not set background colors");
    }


    @Test
    void statusAndBackupCommandsUseExpectedStylesAndRejectInvalidTrailingArguments() {
        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(new String[]{"status"}, 0));
        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(new String[]{"setting-status"}, 0));
        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(new String[]{"backup", "now"}, 0));
        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(new String[]{"backup", "now"}, 1));
        assertEquals(AttributedStyle.DEFAULT.getStyle(),
                PlayerMonitorManagementCommand.styleForArgument(new String[]{"status", "extra"}, 1).getStyle());
        assertEquals(AttributedStyle.DEFAULT.getStyle(),
                PlayerMonitorManagementCommand.styleForArgument(new String[]{"backup", "now", "extra"}, 2).getStyle());
        // Compatibility alias remains accepted but is intentionally omitted from normal completions/help.
        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(new String[]{"db-stat"}, 0));
    }

    @Test
    void rootAndBackupTabCompletionUseNestedBackupSyntax() throws Exception {
        PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                null, null, null, NOPLogger.NOP_LOGGER);
        List<String> roots = command.onTabComplete(null, "playermonitor", new String[]{""});
        assertTrue(roots.contains("status"));
        assertTrue(roots.contains("setting-status"));
        assertTrue(roots.contains("backup"));
        assertFalse(roots.contains("db-stat"));
        assertEquals(List.of("now", "status", "list", "verify", "limit"),
                command.onTabComplete(null, "playermonitor", new String[]{"backup", ""}));
        assertEquals(List.of(),
                command.onTabComplete(null, "playermonitor", new String[]{"backup", "now", ""}));
    }

    @Test
    void settingAndSettingStatusExposeRequestedLines() {
        assertEquals(List.of(
                "Usage: playermonitor setting scan-on-entry <true|false>",
                "Usage: playermonitor setting disconnect-timeout <minute>",
                "Usage: playermonitor setting stat-enabled <true|false>",
                "Usage: playermonitor setting stat-send-interval <ms>",
                "Usage: playermonitor setting stat-output-hide <true|false>",
                "Usage: playermonitor setting stat-cooldown <hour>",
                "Usage: playermonitor setting stat-timeout <ms>",
                "Usage: playermonitor setting stat-attempts <count>",
                "Usage: playermonitor setting scan-on-join <true|false>",
                "Usage: playermonitor setting prioritize-join-stat <true|false>",
                "Usage: playermonitor setting display-timezone <timezone>",
                "Usage: playermonitor setting recentlogin-count <count>",
                "Usage: playermonitor setting chat-count <count>",
                "Usage: playermonitor setting backup-interval <hour>"),
                PlayerMonitorManagementCommand.settingUsageLines());

        MonitorSettings current = new MonitorSettings();
        current.scanOnEntry = false;
        current.disconnectTimeoutMinutes = 30;
        current.statSendIntervalMillis = 100;
        current.statCooldownHours = 12;
        current.displayTimezone = "UTC+08:00";
        assertEquals(List.of(
                "进入 Game 自动扫描: false",
                "断线确认时间: 30 min",
                "Stat 自动扫描总开关: true",
                "Stat 发送间隔: 100 ms",
                "Stat 输出隐藏: true",
                "自动 Stat 冷却: 12 h",
                "Stat 响应超时: 3000 ms",
                "Stat 最大尝试次数: 4",
                "玩家加入自动扫描: true",
                "新加入玩家优先扫描: true",
                "显示时区: UTC+08:00",
                "近期登录默认数量: 15",
                "聊天默认数量: 10",
                "自动备份间隔: 168 h"),
                PlayerMonitorManagementCommand.settingStatusLines(current));
    }

    @Test
    void databaseStatsLinesUseGoldNumbers() {
        String rendered = String.join("\\n", PlayerMonitorManagementCommand.databaseStatsLines(
                new DatabaseStats(2, 3, 4, 5, 1)));

        assertTrue(rendered.contains("玩家数: \u001B[38;5;215m2\u001B[0m"));
        assertTrue(rendered.contains("聊天记录: \u001B[38;5;215m3\u001B[0m"));
        assertTrue(rendered.contains("登录会话: \u001B[38;5;215m4\u001B[0m"));
        assertTrue(rendered.contains("玩家 Stat 信息记录: \u001B[38;5;215m5\u001B[0m"));
        assertTrue(rendered.contains("未结束会话: \u001B[38;5;215m1\u001B[0m"));
    }
    private static void assertStyle(int rgb, AttributedStyle actual) {
        assertEquals(AttributedStyle.DEFAULT.foregroundRgb(rgb).getStyle(), actual.getStyle());
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static final class LimitedQueryRepository implements PlayerRepository {
        int recentChatLimit;
        int chatCountCalls;
        int fullFindCalls;

        @Override
        public void initialize() {
        }

        @Override
        public void setWarningSink(java.util.function.Consumer<String> warningSink) {
        }

        @Override
        public void recordLogin(String playerName, long now) {
        }

        @Override
        public void recordLogout(String playerName, long now) {
        }

        @Override
        public void recordChat(String playerName, String message, long now) {
        }

        @Override
        public void recordStat(String playerName, StatSnapshot snapshot) {
        }

        @Override
        public PlayerRecord read(String playerName) throws IOException {
            throw new IOException("full read should not be used");
        }

        @Override
        public Optional<PlayerRecord> find(String playerName) throws IOException {
            fullFindCalls++;
            throw new IOException("full find should not be used");
        }

        @Override
        public Optional<PlayerRecord> findSummary(String playerName) {
            return Optional.of(new PlayerRecord("Steve", 100L));
        }

        @Override
        public List<ChatEntry> recentChats(String playerName, int limit) {
            recentChatLimit = limit;
            return List.of(new ChatEntry(200L, "hello"));
        }

        @Override
        public int chatCount(String playerName) {
            chatCountCalls++;
            return 123;
        }

        @Override
        public Optional<LoginSession> latestLogin(String playerName) {
            return Optional.empty();
        }

        @Override
        public List<String> listPlayerNames() {
            return List.of("Steve");
        }

        @Override
        public void close() {
        }
    }
}
