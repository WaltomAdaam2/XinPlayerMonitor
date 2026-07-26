package waltomadaam2.xinbot.playermonitor;

import java.util.regex.Pattern;

final class StatText {
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern MINECRAFT_FORMATTING = Pattern.compile("(?i)§[0-9A-FK-OR]");

    private StatText() {
    }

    static String normalize(String value) {
        return MINECRAFT_FORMATTING.matcher(stripAnsi(value)).replaceAll("").trim();
    }

    static String stripAnsi(String value) {
        return value == null ? "" : ANSI_ESCAPE.matcher(value).replaceAll("");
    }
}
