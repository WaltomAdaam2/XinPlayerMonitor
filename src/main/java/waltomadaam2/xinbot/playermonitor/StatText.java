package waltomadaam2.xinbot.playermonitor;

import java.util.regex.Pattern;

final class StatText {
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;\\d]*m");
    private static final Pattern MINECRAFT_FORMATTING = Pattern.compile("(?i)§[0-9A-FK-OR]");

    private StatText() {
    }

    static String normalize(String value) {
        return MINECRAFT_FORMATTING.matcher(ANSI_ESCAPE.matcher(value).replaceAll("")).replaceAll("").trim();
    }
}
