package waltomadaam2.xinbot.playermonitor;

import java.util.regex.Pattern;

final class MinecraftFormatting {
    private static final Pattern CODE = Pattern.compile("(?i)§[0-9a-fk-orx]");

    private MinecraftFormatting() {
    }

    static String strip(String value) {
        return CODE.matcher(value).replaceAll("");
    }
}
