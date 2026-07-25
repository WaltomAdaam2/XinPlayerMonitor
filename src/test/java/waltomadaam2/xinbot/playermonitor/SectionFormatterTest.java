package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionFormatterTest {

    @Test
    void headerContainsTitleTextWithCorrectColor() {
        String header = SectionFormatter.header("Recent logins");
        assertTrue(header.contains(SectionFormatter.TITLE_COLOR + "Recent logins" + SectionFormatter.RESET));
    }

    @Test
    void headerColorsBothEqualSignGroups() {
        String expected = SectionFormatter.EQUAL_COLOR + "=====" + SectionFormatter.RESET
                + " " + SectionFormatter.TITLE_COLOR + "Player stat" + SectionFormatter.RESET + " "
                + SectionFormatter.EQUAL_COLOR + "=====" + SectionFormatter.RESET;
        assertEquals(expected, SectionFormatter.header("Player stat"));
    }

    @Test
    void dividerMatchesHeaderTerminalWidth() {
        for (String title : new String[]{"Recent logins", "Latest login", "Player stat",
                "Recent chat", "PlayerMonitor 设置", "Short"}) {
            String header = stripAnsi(SectionFormatter.header(title));
            String divider = stripAnsi(SectionFormatter.divider(title));
            assertEquals(terminalWidth(header), terminalWidth(divider),
                    "divider width must match header width for title: " + title);
        }
    }

    @Test
    void dividerUsesEqualColorForTheWholeLine() {
        String title = "Test";
        String expected = SectionFormatter.EQUAL_COLOR
                + "=".repeat(SectionFormatter.visibleWidth(title))
                + SectionFormatter.RESET;
        assertEquals(expected, SectionFormatter.divider(title));
    }

    @Test
    void titleUsesCompatiblePurpleForeground() {
        assertEquals("38;5;62", extractColorCode(SectionFormatter.TITLE_COLOR));
    }

    @Test
    void equalSignsUseCompatibleLavenderForeground() {
        assertEquals("38;5;183", extractColorCode(SectionFormatter.EQUAL_COLOR));
    }

    @Test
    void colorFormattingDoesNotAlterPlainText() {
        assertEquals("===== Recent logins =====",
                stripAnsi(SectionFormatter.header("Recent logins")));
    }

    @Test
    void bottomDividerHasExactSameTerminalWidthAsFullHeader() {
        String title = "Recent logins";
        assertEquals(
                terminalWidth(stripAnsi(SectionFormatter.header(title))),
                terminalWidth(stripAnsi(SectionFormatter.divider(title))));
    }

    @Test
    void emptyTitleWorks() {
        assertEquals("=====  =====", stripAnsi(SectionFormatter.header("")));
        assertEquals(12, SectionFormatter.visibleWidth(""));
    }

    @Test
    void chineseTitleUsesTerminalCellWidth() {
        String title = "PlayerMonitor 设置";
        // ASCII part is 14 cells and the two CJK characters are 2 cells each.
        assertEquals(30, SectionFormatter.visibleWidth(title));
        assertEquals(
                terminalWidth(stripAnsi(SectionFormatter.header(title))),
                terminalWidth(stripAnsi(SectionFormatter.divider(title))));
    }


    @Test
    void outputDoesNotUseTrueColorOrBackgroundSequences() {
        String rendered = SectionFormatter.header("Recent chat")
                + SectionFormatter.divider("Recent chat");
        assertTrue(!rendered.contains("[38;2;"), "24-bit SGR is misrendered by the target terminal");
        assertTrue(!rendered.contains("[48;"), "section formatting must never set a background color");
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\\u001B\\[[0-9;]*m", "");
    }

    private static String extractColorCode(String ansiSequence) {
        return ansiSequence.replaceAll("\\u001B\\[", "").replaceAll("m$", "");
    }

    private static int terminalWidth(String text) {
        int width = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || codePoint == 0x200D
                    || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)) {
                continue;
            }
            width += isWide(codePoint) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(int cp) {
        return cp >= 0x1100 && (cp <= 0x115F
                || cp == 0x2329 || cp == 0x232A
                || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x20000 && cp <= 0x3FFFD));
    }
}
