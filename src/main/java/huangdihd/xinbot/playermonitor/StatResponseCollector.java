package huangdihd.xinbot.playermonitor;

import huangdihd.xinbot.playermonitor.model.StatSnapshot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StatResponseCollector {
    private static final long REQUEST_TIMEOUT_MILLIS = 10_000L;
    private static final Pattern HEADER = Pattern.compile("^玩家名称\\s*[:：]\\s*(.+?)\\s*$");

    private final Map<String, Long> expectedPlayers = new LinkedHashMap<>();
    private String activePlayer;
    private List<String> activeLines;

    synchronized void expect(String playerName) {
        expectedPlayers.put(playerName, System.currentTimeMillis() + REQUEST_TIMEOUT_MILLIS);
    }

    synchronized Optional<CapturedStat> accept(String text) {
        discardExpired();
        for (String line : text.replace("\r", "").split("\n")) {
            String trimmed = line.trim();
            Matcher header = HEADER.matcher(trimmed);
            if (header.matches()) {
                String playerName = expectedName(header.group(1));
                if (playerName == null) {
                    activePlayer = null;
                    activeLines = null;
                    continue;
                }
                activePlayer = playerName;
                activeLines = new ArrayList<>();
            }
            if (activePlayer == null || activeLines == null) {
                continue;
            }
            activeLines.add(trimmed);
            if (trimmed.startsWith("特殊权限")) {
                Optional<StatSnapshot> snapshot = StatParser.parse(activePlayer, activeLines, System.currentTimeMillis());
                String completedPlayer = activePlayer;
                activePlayer = null;
                activeLines = null;
                expectedPlayers.remove(completedPlayer);
                if (snapshot.isPresent()) {
                    return Optional.of(new CapturedStat(completedPlayer, snapshot.get()));
                }
            }
        }
        return Optional.empty();
    }

    private String expectedName(String actualName) {
        for (String expectedName : expectedPlayers.keySet()) {
            if (expectedName.equalsIgnoreCase(actualName)) {
                return expectedName;
            }
        }
        return null;
    }

    private void discardExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = expectedPlayers.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() < now) {
                iterator.remove();
            }
        }
    }

    record CapturedStat(String playerName, StatSnapshot snapshot) {
    }
}
