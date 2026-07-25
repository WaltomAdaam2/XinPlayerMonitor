package waltomadaam2.xinbot.playermonitor;

/** Formats section headers and bottom dividers with consistent colors. */
final class SectionFormatter {
    static final String TITLE_COLOR = "\u001B[38;5;62m";  // xterm-256 approximation of #6a5acd
    static final String EQUAL_COLOR = "\u001B[38;5;183m"; // xterm-256 approximation of #E0B0FF
    static final String RESET = "\u001B[0m";

    private static final int FIXED_VISIBLE_WIDTH = 12; // "===== " + " ====="

    private SectionFormatter() {
    }

    static String header(String title) {
        String safeTitle = title == null ? "" : title;
        return EQUAL_COLOR + "=====" + RESET + " "
                + TITLE_COLOR + safeTitle + RESET + " "
                + EQUAL_COLOR + "=====" + RESET;
    }

    static String divider(String title) {
        return EQUAL_COLOR + "=".repeat(visibleWidth(title)) + RESET;
    }

    /** Returns terminal-cell width, not UTF-16 String.length(). */
    static int visibleWidth(String title) {
        return FIXED_VISIBLE_WIDTH + terminalWidth(title == null ? "" : title);
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
