package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionFormatterTest {

    @Test
    void headerContainsTitleTextWithCorrectColor() {
        String header = SectionFormatter.header("Recent logins");
        // Title color #6a5acd = 106;90;205
        assertTrue(header.contains("38;2;106;90;205mRecent logins"));
    }

    @Test
    void headerContainsEqualSignsWithMauveColor() {
        String header = SectionFormatter.header("Player stat");
        // Equal color #E0B0FF = 224;176;255
        assertEquals(10, count(header, "38;2;224;176;255m="),
                "header should have 10 = signs total (5 on each side)");
    }

    @Test
    void dividerMatchesHeaderVisibleWidth() {
        for (String title : new String[]{"Recent logins", "Latest login", "Player stat",
                "Recent chat", "PlayerMonitor 设置", "Short"}) {
            int headerWidth = SectionFormatter.visibleWidth(title);
            String divider = SectionFormatter.divider(title);
            // Count visible = characters by counting the equals sign characters
            // Each = is preceded by the color code and followed by nothing visible
            int equalsCount = count(divider, "38;2;224;176;255m=");
            assertEquals(headerWidth, equalsCount,
                    "divider width must match header width for title: " + title);
        }
    }

    @Test
    void dividerUsesEqualColorEverywhere() {
        String divider = SectionFormatter.divider("Test");
        // All visible characters should be = colored with #E0B0FF
        int colorSequences = count(divider, "38;2;224;176;255m");
        int visibleWidth = SectionFormatter.visibleWidth("Test");
        assertEquals(visibleWidth, colorSequences,
                "every = character should have its own color escape");
    }

    @Test
    void titleColorIs6a5acd() {
        // Verify the exact ANSI code for #6a5acd
        assertEquals("38;2;106;90;205", extractColorCode(SectionFormatter.TITLE_COLOR));
    }

    @Test
    void equalColorIsE0B0FF() {
        assertEquals("38;2;224;176;255", extractColorCode(SectionFormatter.EQUAL_COLOR));
    }

    @Test
    void colorFormattingDoesNotAlterPlainText() {
        String title = "Recent logins";
        String header = SectionFormatter.header(title);
        // Strip all ANSI codes and verify plain text
        String plain = header.replaceAll("\\u001B\\[[0-9;]*m", "");
        assertEquals("===== Recent logins =====", plain);
    }

    @Test
    void bottomDividerHasExactSameVisibleWidthAsFullHeader() {
        String title = "Recent logins";
        String header = SectionFormatter.header(title);
        int headerVisibleWidth = header.replaceAll("\\u001B\\[[0-9;]*m", "").length();
        String divider = SectionFormatter.divider(title);
        int dividerVisibleWidth = divider.replaceAll("\\u001B\\[[0-9;]*m", "").length();
        assertEquals(headerVisibleWidth, dividerVisibleWidth);
    }

    @Test
    void emptyTitleWorks() {
        String header = SectionFormatter.header("");
        String plain = header.replaceAll("\\u001B\\[[0-9;]*m", "");
        assertEquals("=====  =====", plain);
        assertEquals(12, SectionFormatter.visibleWidth(""));
    }

    @Test
    void chineseTitleWidthCalculatedCorrectly() {
        String title = "PlayerMonitor 设置";
        int visibleWidth = SectionFormatter.visibleWidth(title);
        // 6 + title.length() + 6
        assertEquals(6 + title.length() + 6, visibleWidth);
        String divider = SectionFormatter.divider(title);
        int dividerVisible = divider.replaceAll("\\u001B\\[[0-9;]*m", "").length();
        assertEquals(visibleWidth, dividerVisible);
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static String extractColorCode(String ansiSequence) {
        // Extract "38;2;R;G;B" from "[38;2;R;G;Bm"
        return ansiSequence.replaceAll("\\u001B\\[", "").replaceAll("m$", "");
    }
}
