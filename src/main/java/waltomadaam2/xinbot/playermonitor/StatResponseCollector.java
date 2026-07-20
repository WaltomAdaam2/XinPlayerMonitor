package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StatResponseCollector {
    private static final long REQUEST_TIMEOUT_MILLIS = 3_000L;
    private static final Pattern HEADER = Pattern.compile("^玩家名称\\s*[:：]\\s*(.+?)\\s*$");
    private static final Pattern SEPARATOR = Pattern.compile("-{10,}");

    private final Map<String, Expectation> expectedPlayers = new LinkedHashMap<>();
    private final long requestTimeoutMillis;
    private String activeKey;
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
        expectedPlayers.put(normalize(playerName),
                new Expectation(playerName, System.currentTimeMillis() + requestTimeoutMillis));
    }

    synchronized Optional<CapturedStat> accept(String text) {
        for (String line : text.replace("\r", "").split("\n")) {
            String trimmed = StatText.normalize(line);
            Matcher header = HEADER.matcher(trimmed);
            if (header.matches()) {
                String playerName = expectedName(header.group(1));
                if (playerName == null) {
                    resetActiveResponse();
                    continue;
                }
                activeKey = normalize(playerName);
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
                String completedKey = activeKey;
                String completedPlayer = activePlayer;
                resetActiveResponse();
                if (snapshot.isPresent()) {
                    expectedPlayers.remove(completedKey);
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
            if (entry.getValue().expiresAt > now) {
                return false;
            }
            String displayName = entry.getValue().displayName;
            expired.add(displayName);
            if (normalize(displayName).equals(activeKey)) {
                resetActiveResponse();
            }
            return true;
        });
        return expired;
    }

    synchronized boolean isExpecting(String playerName) {
        String normalized = normalize(playerName);
        return normalized.equals(activeKey) || expectedPlayers.containsKey(normalized);
    }

    synchronized void cancel(String playerName) {
        String normalized = normalize(playerName);
        expectedPlayers.remove(normalized);
        if (normalized.equals(activeKey)) {
            resetActiveResponse();
        }
    }

    synchronized void clear() {
        expectedPlayers.clear();
        resetActiveResponse();
    }

    private void resetActiveResponse() {
        activeKey = null;
        activePlayer = null;
        activeLines = null;
        activeHasPermissions = false;
    }

    private String expectedName(String actualName) {
        Expectation expectation = expectedPlayers.get(normalize(actualName));
        return expectation == null ? null : expectation.displayName;
    }

    private static String normalize(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }

    private static final class Expectation {
        final String displayName;
        final long expiresAt;

        Expectation(String displayName, long expiresAt) {
            this.displayName = displayName;
            this.expiresAt = expiresAt;
        }
    }

    record CapturedStat(String playerName, StatSnapshot snapshot) {
    }
}
