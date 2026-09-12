package waltomadaam2.xinbot.playermonitor;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerPermissions;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    void highlightsOnlyScanAndStatusSubcommandsWithMintColor() {
        String[] args = {"scan"};
        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(args, 0));
        assertStyle(0x87D7AF, PlayerMonitorManagementCommand.styleForArgument(
                new String[]{"scan", "stat"}, 1));
        assertStyle(0x87D7AF, PlayerMonitorManagementCommand.styleForArgument(
                new String[]{"scan", "uuid"}, 1));
        assertStyle(0x87D7AF, PlayerMonitorManagementCommand.styleForArgument(
                new String[]{"status", "full"}, 1));
    }


    @Test
    void scanStatDoesNotOfferPlayerActionsAfterTheCompleteCommand() {
        String[] invalidExtraArgument = {"scan", "stat", "extra"};
        assertEquals(AttributedStyle.DEFAULT.getStyle(),
                PlayerMonitorManagementCommand.styleForArgument(invalidExtraArgument, 2).getStyle());
    }

    @Test
    void highlightsPlayerNameAndAllPlayerActions() {
        for (String action : new String[]{"playerinfo", "stat", "latestlogin", "recentlogin", "chat"}) {
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
    void scanAndStatusUsageColorsOnlySubcommandWordsWithMint() {
        assertEquals("Usage: \u001B[38;5;145mplayermonitor\u001B[0m "
                        + "\u001B[38;5;183mscan\u001B[0m \u001B[38;5;115mstat\u001B[0m",
                PlayerMonitorManagementCommand.colorUsage("Usage: playermonitor scan stat"));
        assertEquals("Usage: \u001B[38;5;145mplayermonitor\u001B[0m "
                        + "\u001B[38;5;183mscan\u001B[0m \u001B[38;5;115muuid\u001B[0m",
                PlayerMonitorManagementCommand.colorUsage("Usage: playermonitor scan uuid"));
        assertEquals("Usage: \u001B[38;5;145mplayermonitor\u001B[0m "
                        + "\u001B[38;5;183mstatus\u001B[0m [\u001B[38;5;115mfull\u001B[0m]",
                PlayerMonitorManagementCommand.colorUsage("Usage: playermonitor status [full]"));
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
        LoggerContext context = new LoggerContext();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        ch.qos.logback.classic.Logger logger = context.getLogger("limited-chat-output");
        logger.addAppender(appender);
        try {
            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    service, settings, listener, logger);
            command.onCommand(null, "playermonitor", new String[]{"Steve", "chat"});
            assertEquals(7, repository.recentChatLimit);
            assertEquals(1, repository.chatCountCalls);
            assertEquals(0, repository.fullFindCalls, "command must not load full player history for chat output");
            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(rendered.contains("1970-01-01 00:00:00  hello=123"));
            assertFalse(rendered.contains("\u001B[33m1970-01-01 00:00:00\u001B[0m"));
            assertFalse(rendered.contains("hello=\u001B[38;5;215m123\u001B[0m"));
        } finally {
            listener.close();
            service.close();
            context.stop();
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
    void playerDataPermissionsPreferRawStatDisplayAndEmojiFallback() {
        StatSnapshot snapshot = new StatSnapshot();
        snapshot.permissionsDisplay = "🎨√丨👟√丨🎒√";
        PlayerPermissions fallback = new PlayerPermissions();
        assertEquals("🎨√丨👟√丨🎒√",
                PlayerMonitorManagementCommand.paidPermissionsDisplay(snapshot, fallback));

        snapshot.permissionsDisplay = "";
        fallback.greenText = true;
        fallback.dupe = true;
        assertEquals("🎨√丨👟×丨🎒√",
                PlayerMonitorManagementCommand.paidPermissionsDisplay(snapshot, fallback));
    }

    @Test
    void priorityDurationParsesStatQueueText() {
        long expected = 897L * 24L * 60L * 60L * 1000L
                + 7L * 60L * 60L * 1000L
                + 15L * 60L * 1000L
                + 31L * 1000L;
        assertEquals(expected, PlayerMonitorManagementCommand.priorityDurationMillis("897天7时15分31秒"));
        assertEquals(expected, PlayerMonitorManagementCommand.priorityDurationMillis("897天7小时15分31秒"));
        assertEquals(null, PlayerMonitorManagementCommand.priorityDurationMillis("已过期"));
    }

    @Test
    void priorityDisplayUsesStatCaptureTimeAsExpiryBase() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-priority-display");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        settings.setDisplayTimezone("UTC");
        PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                null, settings, null, NOPLogger.NOP_LOGGER);

        assertEquals("1秒  (预计到期时间: 1970-01-01 00:00:02)",
                command.priorityDisplay("1秒", 1_000L));
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
        assertStyle(0x87D7AF,
                PlayerMonitorManagementCommand.styleForArgument(new String[]{"status", "full"}, 1));
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
        assertTrue(roots.contains("setting"));
        assertFalse(roots.contains("setting-status"));
        assertTrue(roots.contains("backup"));
        assertFalse(roots.contains("db-stat"));
        assertEquals(List.of("now", "status", "list", "verify", "limit"),
                command.onTabComplete(null, "playermonitor", new String[]{"backup", ""}));
        assertEquals(List.of("full"),
                command.onTabComplete(null, "playermonitor", new String[]{"status", ""}));
        assertEquals(List.of("stat", "uuid"),
                command.onTabComplete(null, "playermonitor", new String[]{"scan"}));
        assertEquals(List.of(),
                command.onTabComplete(null, "playermonitor", new String[]{"backup", "now", ""}));
    }

    @Test
    void settingUsageAndSettingStatusExposeRequestedLines() {
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
                "Usage: playermonitor setting uuidRecordEnable <true|false>",
                "Usage: playermonitor setting uuidRecordCooldown <hour>",
                "Usage: playermonitor setting thirdPartyYggdrasilBaseUrl <url>",
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
                "UUID 记录启用: false",
                "UUID 记录冷却: 168 h",
                "第三方 Yggdrasil 地址: https://littleskin.cn/api/yggdrasil",
                "自动备份间隔: 168 h"),
                PlayerMonitorManagementCommand.settingStatusLines(current));
    }

    @Test
    void barePlayerCommandUsesOverviewInsteadOfFullHistory() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-player-overview");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        LimitedQueryRepository repository = new LimitedQueryRepository();
        PlayerMonitorService service = new PlayerMonitorService(repository);
        service.initialize();
        PlayerMonitorListener listener = new PlayerMonitorListener(
                service, new PluginLog(directory.resolve("log")), NOPLogger.NOP_LOGGER, settings);
        try {
            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    service, settings, listener, NOPLogger.NOP_LOGGER);
            command.onCommand(null, "playermonitor", new String[]{"Steve"});
            assertEquals(1, repository.overviewCalls);
            assertEquals(0, repository.fullFindCalls);
        } finally {
            listener.close();
            service.close();
        }
    }

    @Test
    void barePlayerDataRendersMissingIdentityWhileBackgroundRefreshIsBlocked() throws Exception {
        assertPlayerDataRefreshesWithoutWaiting(false, new String[]{"Steve"});
    }

    @Test
    void explicitPlayerinfoRendersStaleIdentityWhileBackgroundRefreshIsBlocked() throws Exception {
        assertPlayerDataRefreshesWithoutWaiting(true, new String[]{"Steve", "playerinfo"});
    }

    private void assertPlayerDataRefreshesWithoutWaiting(boolean storedIdentity, String[] args) throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-player-data-refresh");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        settings.setUuidRecordEnable(true);
        settings.setDisplayTimezone("UTC");
        PlayerMonitorService service = new PlayerMonitorService(directory, settings);
        service.initialize();
        service.recordLogin("Steve", 1_000L);
        UUID serverUuid = UUID.fromString("98465ebe-e619-3b1d-8b25-98352b6abbb9");
        IdentityResolution resolution = new IdentityResolution(serverUuid.toString(), null,
                IdentityResolution.Lookup.notFound(), IdentityResolution.Lookup.notFound(),
                PlayerIdentityType.UNKNOWN, true);
        if (storedIdentity) {
            service.recordIdentityCheck("Steve", resolution, 1_000L);
        }
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        PlayerMonitorListener listener = new PlayerMonitorListener(
                service, new PluginLog(directory.resolve("log")), NOPLogger.NOP_LOGGER, settings,
                (playerName, observedUuid, previous) -> {
                    lookupStarted.countDown();
                    try {
                        if (!releaseLookup.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("test did not release identity lookup");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                    return resolution;
                }, () -> TimeUnit.DAYS.toMillis(2));
        listener.setGameActiveForTesting(true);
        listener.markOnlineForTesting(new GameProfile(serverUuid, "Steve"));
        LoggerContext context = new LoggerContext();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        ch.qos.logback.classic.Logger logger = context.getLogger("player-data-refresh-output");
        logger.addAppender(appender);
        try {
            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    service, settings, listener, logger);
            CompletableFuture<Void> commandCompleted = CompletableFuture.runAsync(
                    () -> command.onCommand(null, "playermonitor", args));
            assertTrue(lookupStarted.await(2, TimeUnit.SECONDS), "missing/stale identity must trigger refresh");
            commandCompleted.get(2, TimeUnit.SECONDS);
            assertEquals(1L, releaseLookup.getCount(), "command must return before HTTP lookup finishes");
            List<String> output = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(output.contains(SectionFormatter.header("Player Data")));
            assertTrue(output.contains(SectionFormatter.divider("Player Data")));
            String cachedValue = storedIdentity
                    ? "\u001B[38;5;29m" + serverUuid + "\u001B[0m  \u001B[38;5;215m"
                    + "(首次记录: 1970-01-01 00:00:01)\u001B[0m" : "无";
            assertTrue(output.contains("\u001B[36muuid：\u001B[0m" + cachedValue));
            assertEquals(List.of("playerinfo"), command.onTabComplete(null, "playermonitor",
                    new String[]{"Steve", "playerinfo"}));
        } finally {
            releaseLookup.countDown();
            listener.close();
            service.close();
            context.stop();
        }
    }

    @Test
    void databaseStatsLinesUseGoldNumbers() {
        List<String> lines = PlayerMonitorManagementCommand.databaseStatsLines(
                new DatabaseStats(2, 3, 4, 5, 6, 1));

        assertEquals(List.of(
                "\u001B[92m玩家数:\u001B[0m \u001B[38;5;215m2\u001B[0m",
                "\u001B[92m聊天记录:\u001B[0m \u001B[38;5;215m3\u001B[0m",
                "\u001B[92m登录会话:\u001B[0m \u001B[38;5;215m4\u001B[0m",
                "\u001B[92m玩家 Stat 信息记录:\u001B[0m \u001B[38;5;215m5\u001B[0m",
                "\u001B[92mPlayer uuid 信息记录:\u001B[0m \u001B[38;5;215m6\u001B[0m",
                "\u001B[92m未结束会话:\u001B[0m \u001B[38;5;215m1\u001B[0m"), lines);
    }

    @Test
    void settingsCommandShowsAndPersistsConfiguredUuidValues() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-uuid-settings-command");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        LoggerContext context = new LoggerContext();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        ch.qos.logback.classic.Logger logger = context.getLogger("uuid-settings-output");
        logger.addAppender(appender);
        try {
            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    null, settings, null, logger);
            command.onCommand(null, "playermonitor",
                    new String[]{"setting", "uuidRecordEnable", "true"});
            command.onCommand(null, "playermonitor",
                    new String[]{"setting", "uuidRecordCooldown", "72"});
            command.onCommand(null, "playermonitor", new String[]{"setting"});
            String rendered = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            String plain = rendered.replaceAll("\\u001B\\[[0-9;]*m", "");
            assertTrue(plain.contains("UUID 记录启用: true"));
            assertTrue(plain.contains("UUID 记录冷却: 72 h"));

            MonitorSettingsStore reloaded = new MonitorSettingsStore(directory);
            reloaded.initialize();
            assertTrue(reloaded.uuidRecordEnable());
            assertEquals(72, reloaded.uuidRecordCooldown());
        } finally {
            context.stop();
        }
    }

    @Test
    void uuidCommandUsesStoredValueWithoutColoringUuidAndHandlesMissingValue() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-uuid-output");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        settings.setDisplayTimezone("UTC");
        LimitedQueryRepository repository = new LimitedQueryRepository();
        PlayerMonitorService service = new PlayerMonitorService(repository);
        service.initialize();
        LoggerContext context = new LoggerContext();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        ch.qos.logback.classic.Logger logger = context.getLogger("uuid-command-output");
        logger.addAppender(appender);
        try {
            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    service, settings, null, logger);
            command.onCommand(null, "playermonitor", new String[]{"Steve", "uuid"});
            String rendered = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(rendered.contains(SectionFormatter.header("uuid")));
            assertTrue(rendered.contains("\u001B[36m玩家: \u001B[0m\u001B[38;5;29mSteve\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36muuid: \u001B[0m"
                    + "98465ebe-e619-3b1d-8b25-98352b6abbb9"));
            assertTrue(rendered.contains("\u001B[36m记录时间: \u001B[0m\u001B[33m1970-01-01 00:00:05\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36m最后检查时间: \u001B[0m\u001B[33m1970-01-01 00:00:05\u001B[0m"));
            assertFalse(rendered.contains("\u001B[38;5;215m98465ebe"));
            assertTrue(rendered.contains(SectionFormatter.divider("uuid")));

            appender.list.clear();
            repository.identity = null;
            command.onCommand(null, "playermonitor", new String[]{"Steve", "uuid"});
            String missing = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(missing.contains("\u001B[36muuid: \u001B[0m无"));
            assertFalse(missing.contains("\u001B[33m无"));
        } finally {
            service.close();
            context.stop();
        }
    }

    @Test
    void playerDataUsesRequestedColorsWithoutColoringHistoricalTimes() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-player-data-colors");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        settings.setDisplayTimezone("UTC");
        LimitedQueryRepository repository = new LimitedQueryRepository();
        PlayerMonitorService service = new PlayerMonitorService(repository);
        service.initialize();
        PlayerMonitorListener listener = new PlayerMonitorListener(
                service, new PluginLog(directory.resolve("log")), NOPLogger.NOP_LOGGER, settings);
        LoggerContext context = new LoggerContext();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        ch.qos.logback.classic.Logger logger = context.getLogger("player-data-colors");
        logger.addAppender(appender);
        try {
            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    service, settings, listener, logger);
            command.onCommand(null, "playermonitor", new String[]{"Steve"});
            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);

            assertTrue(rendered.contains("\u001B[36m玩家：\u001B[0m\u001B[33mSteve\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36muuid：\u001B[0m"
                    + "\u001B[38;5;29m98465ebe-e619-3b1d-8b25-98352b6abbb9\u001B[0m"));
            assertFalse(rendered.contains("\u001B[38;5;215m98465ebe"));
            assertTrue(rendered.contains("\u001B[36m> 发言次数：\u001B[0m\u001B[38;5;215m4次\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36m> 击杀数：\u001B[0m\u001B[38;5;215m1人\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36m> 死亡次数：\u001B[0m\u001B[38;5;215m2次\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36m> KD比：\u001B[0m\u001B[38;5;215m0.500\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36m[最近游玩时长]： \u001B[0m"
                    + "\u001B[38;5;215m0小时0分0秒\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36m- 总游玩时长：\u001B[0m"
                    + "\u001B[38;5;215m1.00小时\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36m- 近30天游玩时长：\u001B[0m"
                    + "\u001B[38;5;215m1.00小时\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36m- 加入游戏次数：\u001B[0m"
                    + "\u001B[38;5;215m3次\u001B[0m"));
            assertTrue(rendered.contains("\u001B[36m[首次记录]： \u001B[0m1970-01-01 00:00:00"));
            assertTrue(rendered.contains("\u001B[36m[最近上线]： \u001B[0m1970-01-01 00:00:00"));
            assertTrue(rendered.contains("\u001B[36m[最近下线]： \u001B[0m1970-01-01 00:00:00"));
            assertFalse(rendered.contains("[首次记录]： \u001B[0m\u001B["));
            assertFalse(rendered.contains("[最近上线]： \u001B[0m\u001B["));
            assertFalse(rendered.contains("[最近下线]： \u001B[0m\u001B["));
            assertTrue(rendered.contains("\u001B[36m最近发言 \u001B[0m(1/4)\u001B[36m:\u001B[0m"));
            assertTrue(rendered.contains("\u001B[92m[1970-01-01 00:00:00 CHAT]:\u001B[0m recent=123"), rendered);
            assertFalse(rendered.contains("1970 01 01"));
        } finally {
            listener.close();
            service.close();
            context.stop();
        }
    }

    @Test
    void statusSupportsCompactAndLegacyFullOutput() throws Exception {
        Path directory = temporaryDirectory.resolve("playermonitor-status-output");
        MonitorSettingsStore settings = new MonitorSettingsStore(directory);
        settings.initialize();
        LimitedQueryRepository repository = new LimitedQueryRepository();
        PlayerMonitorService service = new PlayerMonitorService(repository);
        service.initialize();
        PlayerMonitorListener listener = new PlayerMonitorListener(
                service, new PluginLog(directory.resolve("log")), NOPLogger.NOP_LOGGER, settings);
        java.lang.reflect.Field disconnectAt = PlayerMonitorListener.class.getDeclaredField("disconnectAt");
        disconnectAt.setAccessible(true);
        disconnectAt.setLong(listener, 6_000L);
        LoggerContext context = new LoggerContext();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        ch.qos.logback.classic.Logger logger = context.getLogger("status-output");
        logger.addAppender(appender);
        try {
            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    service, settings, listener, logger);
            command.onCommand(null, "playermonitor", new String[]{"status"});
            String compactRendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(compactRendered.contains(
                    "\u001B[36m健康状态:\u001B[0m \u001B[92m正常\u001B[0m"));
            assertFalse(compactRendered.contains("\u001B[92m健康状态"));
            assertTrue(compactRendered.contains(
                    "\u001B[92m聊天=\u001B[0m\u001B[33m1970-01-01 00:00:02\u001B[0m"));
            assertTrue(compactRendered.contains(
                    "> \u001B[92m聊天=\u001B[0m\u001B[33m1970-01-01 00:00:02\u001B[0m"));
            assertTrue(compactRendered.contains(
                    "> \u001B[92m会话=\u001B[0m\u001B[33m1970-01-01 00:00:03\u001B[0m"));
            String compactPlain = compactRendered.replaceAll("\\u001B\\[[0-9;]*m", "");
            assertTrue(compactPlain.contains("Plugin Version: v1.5.8"));
            assertTrue(compactPlain.contains("Database Version: v5"));
            assertTrue(compactRendered.contains(
                    "> \u001B[92mStat=\u001B[0m\u001B[33m1970-01-01 00:00:04\u001B[0m\n"
                            + "  > \u001B[92mUUID=\u001B[0m\u001B[33m1970-01-01 00:00:04\u001B[0m"));
            assertTrue(compactRendered.contains(
                    "\u001B[36m最近提交:\u001B[0m \u001B[33m1970-01-01 00:00:01\u001B[0m"));
            assertTrue(compactRendered.contains(
                    "bot=\u001B[38;5;215m0\u001B[0m, monitor=\u001B[38;5;215m0\u001B[0m"));
            assertTrue(compactRendered.contains(
                    "在线=\u001B[38;5;215m0\u001B[0m, 队列=\u001B[38;5;215m0\u001B[0m"));
            assertTrue(compactRendered.contains(
                    "等待响应=\u001B[38;5;215mfalse\u001B[0m"));
            assertTrue(compactRendered.contains("\u001B[36mUUID 扫描:\u001B[0m 在线=\u001B[38;5;215m0\u001B[0m"
                    + ", 队列=\u001B[38;5;215m0\u001B[0m"
                    + ", 活动轮次=\u001B[38;5;215m0\u001B[0m"
                    + ", 等待响应=\u001B[38;5;215mfalse\u001B[0m"));
            assertTrue(compactRendered.contains(
                    "system received=\u001B[38;5;215m0\u001B[0m"));
            assertTrue(compactRendered.contains(
                    "\u001B[36mWriter:\u001B[0m state=RUNNING, alive=\u001B[38;5;215mtrue\u001B[0m"));
            assertTrue(compactRendered.contains(
                    "recoveries=\u001B[38;5;215m6\u001B[0m"));
            assertFalse(compactRendered.contains("state=\u001B[38;5;215mRUNNING\u001B[0m"));
            String compact = compactRendered.replaceAll("\u001B\\[[0-9;]*m", "");
            assertTrue(compact.contains("Roster: bot="));
            assertTrue(compact.contains("Chat Pipeline: system received="));
            assertTrue(compact.contains("Writer: state="));
            assertTrue(compact.contains("Failed events: pending="));
            assertTrue(compact.contains("最近写入："));
            assertTrue(compact.contains("聊天=1970-01-01 00:00:02"));
            int statCount = compact.indexOf("玩家 Stat 信息记录: 0");
            int uuidCount = compact.indexOf("Player uuid 信息记录: 0");
            int openSessions = compact.indexOf("未结束会话: 0");
            assertTrue(statCount >= 0 && statCount < uuidCount && uuidCount < openSessions);
            assertFalse(compact.contains("Game Active:"));

            appender.list.clear();
            command.onCommand(null, "playermonitor", new String[]{"status", "full"});
            String fullRendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(fullRendered.contains(
                    "\u001B[36m健康状态:\u001B[0m \u001B[92mHEALTHY\u001B[0m"));
            assertFalse(fullRendered.contains("\u001B[92m健康状态"));
            assertTrue(fullRendered.contains(
                    "\u001B[36m最近Chat写入:\u001B[0m \u001B[33m1970-01-01 00:00:02\u001B[0m"));
            assertTrue(fullRendered.contains("\u001B[36mUUID 扫描:\u001B[0m 在线=\u001B[38;5;215m0\u001B[0m"
                    + ", 队列=\u001B[38;5;215m0\u001B[0m"
                    + ", 活动轮次=\u001B[38;5;215m0\u001B[0m"
                    + ", 等待响应=\u001B[38;5;215mfalse\u001B[0m"));
            assertTrue(fullRendered.contains(
                    "\u001B[36m最近Stat写入:\u001B[0m \u001B[33m1970-01-01 00:00:04\u001B[0m\n"
                            + "  \u001B[36m最近UUID写入:\u001B[0m \u001B[33m1970-01-01 00:00:04\u001B[0m"));
            assertTrue(fullRendered.contains(
                    "\u001B[36mLast Disconnect:\u001B[0m \u001B[33m1970-01-01 00:00:06\u001B[0m"));
            assertFalse(fullRendered.contains("\u001B[33mLast Disconnect"));
            assertTrue(fullRendered.contains(
                    "\u001B[36mGame Active:\u001B[0m \u001B[38;5;215mfalse\u001B[0m"));
            assertTrue(fullRendered.contains(
                    "\u001B[36mBot Roster:\u001B[0m \u001B[38;5;215m0\u001B[0m"));
            assertTrue(fullRendered.contains(
                    "missing=\u001B[38;5;215m0\u001B[0m, extra=\u001B[38;5;215m0\u001B[0m"));
            assertTrue(fullRendered.contains(
                    "waiting-response=\u001B[38;5;215mfalse\u001B[0m"));
            String full = fullRendered.replaceAll("\u001B\\[[0-9;]*m", "");
            assertTrue(full.contains("Game Active:"));
            assertTrue(full.contains("Chat Pipeline:"));
            assertTrue(full.contains("Peak queue:"));
            assertTrue(full.contains("失败事件: pending="));
            assertTrue(full.contains("最近Chat写入:"));
            assertFalse(full.contains("最近写入："));

            repository.lastUuidWrittenAt = 0L;
            appender.list.clear();
            command.onCommand(null, "playermonitor", new String[]{"status"});
            String missingUuid = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(missingUuid.contains("\u001B[92mUUID=\u001B[0m无"));
            assertFalse(missingUuid.contains("\u001B[92mUUID=\u001B[0m\u001B[33m无"));
        } finally {
            listener.close();
            service.close();
            context.stop();
        }
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
        int overviewCalls;
        StoredPlayerIdentity identity = defaultIdentity();
        long lastUuidWrittenAt = 4_500L;

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
            return List.of(new ChatEntry(200L, "hello=123"));
        }

        @Override
        public int chatCount(String playerName) {
            chatCountCalls++;
            return 123;
        }

        @Override
        public Optional<PlayerOverview> playerOverview(String playerName, long now) {
            overviewCalls++;
            StatSnapshot snapshot = new StatSnapshot();
            snapshot.capturedAt = now;
            snapshot.killCount = 1;
            snapshot.deathCount = 2;
            snapshot.playtimeSeconds = 3600L;
            snapshot.addedGameCount = 3;
            return Optional.of(new PlayerOverview("Steve", 100L, 4L,
                    List.of(new ChatEntry(400L, "recent=123")),
                    200L, 300L, 100L, 3_600_000L, snapshot, identity));
        }

        @Override
        public Optional<StoredPlayerIdentity> playerIdentity(String playerName) {
            return Optional.ofNullable(identity);
        }

        @Override
        public Optional<LoginSession> latestLogin(String playerName) {
            return Optional.empty();
        }

        @Override
        public DatabaseHealth databaseHealth() {
            return new DatabaseHealth("RUNNING", true, false, false, 2, 100, 3, 1, -2,
                    1_000L, 2_000L, 3_000L, 4_000L, lastUuidWrittenAt, 5_000L, "test failure",
                    6L, 7L, 8L, 9L, 10L, 11L, 12L, 0L, 13L, 14L, 15L, 16L);
        }

        private static StoredPlayerIdentity defaultIdentity() {
            return new StoredPlayerIdentity("Steve", "98465ebe-e619-3b1d-8b25-98352b6abbb9",
                    "11111111-1111-3111-8111-111111111111", null, null,
                    PlayerIdentityType.UNKNOWN, null, null, 5_000L, 5_000L, 5_000L);
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
