package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.PlayerPermissions;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StatParser {
    private static final Pattern HEADER = Pattern.compile("^玩家名称\\s*[:：]\\s*(.+?)\\s*$");
    private static final Pattern INTEGER = Pattern.compile("(\\d+)");
    private static final Pattern PLAYTIME = Pattern.compile(
            "^(?:(\\d+)天)?(?:(\\d+)(?:小时|时))?(?:(\\d+)分)?(?:(\\d+)秒)?$");

    private StatParser() {
    }

    static Optional<StatSnapshot> parse(String expectedPlayerName, List<String> inputLines, long capturedAt) {
        List<String> lines = inputLines.stream()
                .flatMap(line -> List.of(line.replace("\r", "").split("\n")).stream())
                .map(StatText::normalize)
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        String playerName = null;
        for (String line : lines) {
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                playerName = header.group(1);
                break;
            }
        }
        if (playerName == null || !playerName.equalsIgnoreCase(expectedPlayerName)) {
            return Optional.empty();
        }

        StatSnapshot snapshot = new StatSnapshot();
        snapshot.capturedAt = capturedAt;
        boolean recognizedField = false;
        for (String line : lines) {
            if (line.startsWith("加入游戏")) {
                snapshot.addedGameCount = firstInteger(line);
                recognizedField = true;
            } else if (line.startsWith("在线次数")) {
                snapshot.onlineCount = firstInteger(line);
                recognizedField = true;
            } else if (line.startsWith("死亡计数")) {
                snapshot.deathCount = firstInteger(line);
                recognizedField = true;
            } else if (line.startsWith("击杀数") || line.startsWith("击杀计数")) {
                snapshot.killCount = firstInteger(line);
                recognizedField = true;
            } else if (line.startsWith("游戏时长")) {
                snapshot.playtimeSeconds = playtimeSeconds(valueAfterColon(line));
                recognizedField = true;
            } else if (line.startsWith("优先队列")) {
                snapshot.priorityQueue = valueAfterColon(line);
                recognizedField = true;
            } else if (line.startsWith("队伍")) {
                snapshot.team = valueAfterColon(line);
                recognizedField = true;
            } else if (line.startsWith("特殊权限")) {
                snapshot.permissions = permissions(line);
                snapshot.permissionsDisplay = valueAfterColon(line);
                recognizedField = true;
            }
        }
        return recognizedField ? Optional.of(snapshot) : Optional.empty();
    }

    private static Integer firstInteger(String line) {
        Matcher matcher = INTEGER.matcher(line);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static String valueAfterColon(String line) {
        int colon = Math.max(line.indexOf(':'), line.indexOf('：'));
        return colon >= 0 ? line.substring(colon + 1).trim() : "";
    }

    private static Long playtimeSeconds(String value) {
        Matcher matcher = PLAYTIME.matcher(value);
        if (!matcher.matches() || matcher.group().isEmpty()) {
            return null;
        }
        return seconds(matcher.group(1), 86_400L)
                + seconds(matcher.group(2), 3_600L)
                + seconds(matcher.group(3), 60L)
                + seconds(matcher.group(4), 1L);
    }

    private static long seconds(String group, long multiplier) {
        return group == null ? 0L : Long.parseLong(group) * multiplier;
    }

    private static PlayerPermissions permissions(String line) {
        String value = valueAfterColon(line);
        String[] parts = value.split("[|丨]");
        if (parts.length >= 3) {
            return new PlayerPermissions(permissionEnabled(parts[0]),
                    permissionEnabled(parts[1]), permissionEnabled(parts[2]));
        }
        int checks = (int) value.codePoints()
                .filter(codePoint -> codePoint == 0x2705 || codePoint == '√' || codePoint == '✓')
                .count();
        return new PlayerPermissions(checks >= 1, checks >= 2, checks >= 3);
    }

    private static boolean permissionEnabled(String value) {
        return value.indexOf('√') >= 0
                || value.indexOf('✓') >= 0
                || value.codePoints().anyMatch(codePoint -> codePoint == 0x2705);
    }
}
