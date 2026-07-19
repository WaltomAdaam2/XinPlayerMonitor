package waltomadaam2.xinbot.playermonitor;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerMonitorManagementCommandTest {
    @Test
    void highlightsSettingCommandArgumentsWithConfiguredColors() {
        String[] args = {"setting", "stat", "autoscan", "true"};

        assertStyle(0xFAEBD7, PlayerMonitorManagementCommand.styleForArgument(args, 0));
        assertStyle(0xFAEBD7, PlayerMonitorManagementCommand.styleForArgument(args, 1));
        assertStyle(0xF7DCEF, PlayerMonitorManagementCommand.styleForArgument(args, 2));
        assertEquals(AttributedStyle.DEFAULT.getStyle(), PlayerMonitorManagementCommand.styleForArgument(args, 3).getStyle());
    }

    @Test
    void highlightsPlayerNameAndPlayerSubcommandSeparately() {
        String[] args = {"WaltomAdaam_", "recentlogin"};

        assertStyle(0x2E6F40, PlayerMonitorManagementCommand.styleForArgument(args, 0));
        assertStyle(0xFAEBD7, PlayerMonitorManagementCommand.styleForArgument(args, 1));
    }

    @Test
    void highlightsManualStatScanAsSubcommands() {
        String[] args = {"stat", "scan"};

        assertStyle(0xFAEBD7, PlayerMonitorManagementCommand.styleForArgument(args, 0));
        assertStyle(0xFAEBD7, PlayerMonitorManagementCommand.styleForArgument(args, 1));
    }


    @Test
    void colorsUsageWithoutRecoloringNestedScanText() {
        String colored = PlayerMonitorManagementCommand.colorUsage("Usage: playermonitor setting stat autoscan scan");

        assertEquals(1, count(colored, "38;2;247;220;239mautoscan"));
        assertEquals(1, count(colored, "38;2;250;235;215mscan"));
        assertEquals(0, count(colored, "autos\u001B"));
    }

    private static void assertStyle(int rgb, AttributedStyle actual) {
        assertEquals(AttributedStyle.DEFAULT.foregroundRgb(rgb).getStyle(), actual.getStyle());
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}