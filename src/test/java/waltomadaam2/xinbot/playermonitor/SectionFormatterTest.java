package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionFormatterTest {

    @Test
    void headerContainsTitleTextWithCorrectColor() {
        String header = SectionFormatter.header("Recent logins");
        assertTrue(header.contains(SectionFormatter.TITLE_COLOR + "Recent logins"));
    }

    @Test
    void headerColorsBothEqualSignGroups() {
        String expected = SectionFormatter.RESET
                + SectionFormatter.BORDER_COLOR + "===== "
                + SectionFormatter.TITLE_COLOR + "Player stat"
                + SectionFormatter.BORDER_COLOR + " ====="
                + SectionFormatter.RESET;
        assertEquals(expected, SectionFormatter.header("Player stat"));
    }

    @Test
    void dividerMatchesHeaderTerminalWidth() {
        for (String title : new String[]{"Recent logins", "Latest login", "Player stat",
                "Recent chat", "PlayerMonitor \u8bbe\u7f6e", "Short"}) {
            String header = stripAnsi(SectionFormatter.header(title));
            String divider = stripAnsi(SectionFormatter.divider(title));
            assertEquals(terminalWidth(header), terminalWidth(divider),
                    "divider width must match header width for title: " + title);
        }
    }

    @Test
    void dividerUsesBorderColorForTheWholeLine() {
        String title = "Test";
        String expected = SectionFormatter.RESET
                + SectionFormatter.BORDER_COLOR
                + "=".repeat(SectionFormatter.visibleWidth(title))
                + SectionFormatter.RESET;
        assertEquals(expected, SectionFormatter.divider(title));
    }

    @Test
    void titleUsesTrueColorPurpleForeground() {
        assertEquals("38;2;95;95;215", extractColorCode(SectionFormatter.TITLE_COLOR));
    }

    @Test
    void bordersUseTrueColorLavenderForeground() {
        assertEquals("38;2;215;175;255", extractColorCode(SectionFormatter.BORDER_COLOR));
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
        String title = "PlayerMonitor \u8bbe\u7f6e";
        assertEquals(30, SectionFormatter.visibleWidth(title));
        assertEquals(
                terminalWidth(stripAnsi(SectionFormatter.header(title))),
                terminalWidth(stripAnsi(SectionFormatter.divider(title))));
    }

    @Test
    void headerUsesExpectedColorOrderAndFinalReset() {
        String expected = SectionFormatter.RESET
                + SectionFormatter.BORDER_COLOR + "===== "
                + SectionFormatter.TITLE_COLOR + "Recent logins"
                + SectionFormatter.BORDER_COLOR + " ====="
                + SectionFormatter.RESET;
        assertEquals(expected, SectionFormatter.header("Recent logins"));
    }

    @Test
    void outputDoesNotUseBackgroundSequences() {
        String rendered = SectionFormatter.header("Recent chat")
                + SectionFormatter.divider("Recent chat");
        assertTrue(!rendered.contains("[48;"), "section formatting must never set a background color");
    }

    private static String stripAnsi(String text) {
        return StatText.stripAnsi(text);
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
