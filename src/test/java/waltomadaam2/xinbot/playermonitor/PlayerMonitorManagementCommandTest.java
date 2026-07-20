package waltomadaam2.xinbot.playermonitor;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerMonitorManagementCommandTest {
    @Test
    void highlightsSettingStatAutoscanTrueWithContextAwareColors() {
        String[] args = {"setting", "stat", "autoscan", "true"};

        // index 0: root "setting" → #E0B0FF
        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(args, 0));
        // index 1: "stat" after setting → #FFC067
        assertStyle(0xFFC067, PlayerMonitorManagementCommand.styleForArgument(args, 1));
        // index 2: setting name "autoscan" → #007BA7
        assertStyle(0x007BA7, PlayerMonitorManagementCommand.styleForArgument(args, 2));
        // index 3: value "true" for a boolean setting → #DEA193
        assertStyle(0xDEA193, PlayerMonitorManagementCommand.styleForArgument(args, 3));
    }

    @Test
    void highlightsStatScanAsRootAndAction() {
        String[] args = {"stat", "scan"};

        // index 0: root "stat" → #E0B0FF
        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(args, 0));
        // index 1: "scan" after stat → #FFB343
        assertStyle(0xFFB343, PlayerMonitorManagementCommand.styleForArgument(args, 1));
    }

    @Test
    void highlightsPlayerNameAndPlayerSubcommand() {
        String[] args = {"WaltomAdaam_", "recentlogin"};

        // index 0: player name → #2E6F40
        assertStyle(0x2E6F40, PlayerMonitorManagementCommand.styleForArgument(args, 0));
        // index 1: player action after name → #E0B0FF
        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(args, 1));
    }

    @Test
    void highlightsIntervalSettingWithNumericValue() {
        String[] args = {"setting", "stat", "interval", "1500"};

        assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(args, 0));
        assertStyle(0xFFC067, PlayerMonitorManagementCommand.styleForArgument(args, 1));
        assertStyle(0x007BA7, PlayerMonitorManagementCommand.styleForArgument(args, 2));
        // numeric value for interval → #DEA193
        assertStyle(0xDEA193, PlayerMonitorManagementCommand.styleForArgument(args, 3));
    }

    @Test
    void settingStatUsageContainsCorrectColors() {
        String colored = PlayerMonitorManagementCommand.colorUsage(
                "Usage: playermonitor setting stat interval <ms>");
        assertEquals(1, count(colored, "38;2;166;173;180mplayermonitor"),
                "playermonitor must be #A6ADB4");
        assertEquals(1, count(colored, "38;2;224;176;255msetting"),
                "setting must be #E0B0FF");
        assertEquals(1, count(colored, "38;2;255;192;103mstat"),
                "stat after setting must be #FFC067");
        assertEquals(1, count(colored, "38;2;0;123;167minterval"),
                "setting name must be #007BA7");
        assertEquals(1, count(colored, "38;2;222;161;147m<ms>"),
                "value placeholder must be #DEA193");
    }


    @Test
    void allPlayerActionsUseRootLavenderInInteractiveHighlighting() {
        for (String action : new String[]{"stat", "latestlogin", "recentlogin", "chat"}) {
            String[] args = {"Steve", action};
            assertStyle(0x2E6F40, PlayerMonitorManagementCommand.styleForArgument(args, 0));
            assertStyle(0xE0B0FF, PlayerMonitorManagementCommand.styleForArgument(args, 1));
        }
    }

    @Test
    void playerUsageColorsAllActionsLavender() {
        String colored = PlayerMonitorManagementCommand.colorUsage(
                "Usage: playermonitor <玩家名> [stat|latestlogin|recentlogin|chat]");
        assertEquals(4, count(colored, "38;2;224;176;255m"),
                "all four player actions must use #E0B0FF");
        assertEquals(1, count(colored, "38;2;46;111;64m<玩家名>"),
                "player placeholder must remain #2E6F40");
    }
    private static void assertStyle(int rgb, AttributedStyle actual) {
        assertEquals(AttributedStyle.DEFAULT.foregroundRgb(rgb).getStyle(), actual.getStyle());
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
