package waltomadaam2.xinbot.playermonitor;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(1, count(colored, "38;2;166;173;180mplayermonitor"));
        assertEquals(1, count(colored, "38;2;224;176;255msetting"));
        assertEquals(1, count(colored, "38;2;173;235;179mstat-send-interval"));
        assertEquals(1, count(colored, "38;2;255;192;103m<ms>"));
    }

    @Test
    void scanStatUsageUsesLavender() {
        String colored = PlayerMonitorManagementCommand.colorUsage("Usage: playermonitor scan-stat");
        assertEquals(1, count(colored, "38;2;224;176;255mscan-stat"));
    }

    @Test
    void playerUsageColorsAllActionsLavender() {
        String colored = PlayerMonitorManagementCommand.colorUsage(
                "Usage: playermonitor <玩家名> [stat|latestlogin|recentlogin [count]|chat [count]]");
        assertEquals(4, count(colored, "38;2;224;176;255m"));
        assertEquals(1, count(colored, "38;2;46;111;64m<玩家名>"));
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

    private static void assertStyle(int rgb, AttributedStyle actual) {
        assertEquals(AttributedStyle.DEFAULT.foregroundRgb(rgb).getStyle(), actual.getStyle());
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
