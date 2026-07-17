package huangdihd.xinbot.playermonitor;

import huangdihd.xinbot.playermonitor.model.StatSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StatResponseCollector {
    private static final long REQUEST_TIMEOUT_MILLIS = 3_000L;
    private static final Pattern HEADER = Pattern.compile("^玩家名称\\s*[:：]\\s*(.+?)\\s*$");
    private static final Pattern SEPARATOR = Pattern.compile("-{10,}");

    private final Map<String, Long> expectedPlayers = new LinkedHashMap<>();
    private final long requestTimeoutMillis;
    private String activePlayer;
    private List<String> activeLines;
    private boolean activeHasPermissions;

    StatResponseCollector() {
        this(REQUEST_TIMEOUT_MILLIS);
    }

    StatResponseCollector(long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    synchronized void expect(String playerName) {
        expectedPlayers.put(playerName, System.currentTimeMillis() + requestTimeoutMillis);
    }

    synchronized Optional<CapturedStat> accept(String text) {
        for (String line : text.replace("\r", "").split("\n")) {
            String trimmed = StatText.normalize(line);
            Matcher header = HEADER.matcher(trimmed);
            if (header.matches()) {
                String playerName = expectedName(header.group(1));
                if (playerName == null) {
                    activePlayer = null;
                    activeLines = null;
                    activeHasPermissions = false;
                    continue;
                }
                activePlayer = playerName;
                activeLines = new ArrayList<>();
                activeHasPermissions = false;
            }
            if (activePlayer == null || activeLines == null) {
                continue;
            }
            activeLines.add(trimmed);
            if (trimmed.startsWith("特殊权限")) {
                activeHasPermissions = true;
                continue;
            }
            if (activeHasPermissions && SEPARATOR.matcher(trimmed).matches()) {
                Optional<StatSnapshot> snapshot = StatParser.parse(activePlayer, activeLines, System.currentTimeMillis());
                String completedPlayer = activePlayer;
                activePlayer = null;
                activeLines = null;
                activeHasPermissions = false;
                if (snapshot.isPresent()) {
                    expectedPlayers.remove(completedPlayer);
                    return Optional.of(new CapturedStat(completedPlayer, snapshot.get()));
                }
            }
        }
        return Optional.empty();
    }

    synchronized List<String> expire() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        expectedPlayers.entrySet().removeIf(entry -> {
            if (entry.getValue() > now) {
                return false;
            }
            expired.add(entry.getKey());
            return true;
        });
        return expired;
    }

    synchronized void cancel(String playerName) {
        expectedPlayers.remove(playerName);
        if (playerName.equals(activePlayer)) {
            activePlayer = null;
            activeLines = null;
            activeHasPermissions = false;
        }
    }

    synchronized void clear() {
        expectedPlayers.clear();
        activePlayer = null;
        activeLines = null;
        activeHasPermissions = false;
    }

    private String expectedName(String actualName) {
        for (String expectedName : expectedPlayers.keySet()) {
            if (expectedName.equalsIgnoreCase(actualName)) {
                return expectedName;
            }
        }
        return null;
    }

    record CapturedStat(String playerName, StatSnapshot snapshot) {
    }
}
