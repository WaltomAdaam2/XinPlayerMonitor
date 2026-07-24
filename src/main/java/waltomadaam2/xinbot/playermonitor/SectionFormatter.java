package waltomadaam2.xinbot.playermonitor;

/**
 * Formats section headers and bottom dividers with consistent colors.
 * <p>
 * Every {@code =} character uses {@code #E0B0FF} and title text uses {@code #6a5acd}.
 * The bottom divider always contains exactly the same number of visible {@code =}
 * characters as the full header line, so the two lines are always visually aligned.
 */
final class SectionFormatter {
    static final String TITLE_COLOR = "[38;2;106;90;205m";   // #6a5acd
    static final String EQUAL_COLOR = "[38;2;224;176;255m";   // #E0B0FF
    static final String RESET = "[0m";

    private static final int PREFIX_LEN = 6; // "===== "
    private static final int SUFFIX_LEN = 6; // " ====="

    private SectionFormatter() {
    }

    /**
     * Returns the formatted header line.
     * <p>
     * Example for title {@code "Recent logins"}:<br>
     * {@code ===== Recent logins =====}
     * <p>
     * All {@code =} signs use {@code #E0B0FF} and the title text uses {@code #6a5acd}.
     */
    static String header(String title) {
        StringBuilder sb = new StringBuilder();
        // Prefix: 5 equals signs, each individually colored
        for (int i = 0; i < 5; i++) {
            sb.append(EQUAL_COLOR).append('=');
        }
        sb.append(RESET);
        String coloredPrefix = sb.toString();

        // Title part
        String titlePart = " " + TITLE_COLOR + title + RESET + " ";

        // Suffix: 5 equals signs, each individually colored
        StringBuilder suffixSb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            suffixSb.append(EQUAL_COLOR).append('=');
        }
        suffixSb.append(RESET);

        return coloredPrefix + titlePart + suffixSb.toString();
    }

    /**
     * Returns a bottom divider line whose visible width exactly matches that
     * of the header produced by {@link #header(String)} for the same title.
     * <p>
     * Every character is an {@code =} colored with {@code #E0B0FF}.
     */
    static String divider(String title) {
        int visibleWidth = PREFIX_LEN + title.length() + SUFFIX_LEN;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < visibleWidth; i++) {
            sb.append(EQUAL_COLOR).append('=');
        }
        sb.append(RESET);
        return sb.toString();
    }

    /**
     * Returns the visible (non-color-code) character count of the header line.
     */
    static int visibleWidth(String title) {
        return PREFIX_LEN + title.length() + SUFFIX_LEN;
    }
}
