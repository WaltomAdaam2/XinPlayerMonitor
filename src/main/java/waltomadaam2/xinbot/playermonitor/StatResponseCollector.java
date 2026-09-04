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
    private static final Pattern PLAYER_NOT_FOUND = Pattern.compile("^玩家不存在\\s*[!！]?$");
    private static final Pattern SEPARATOR = Pattern.compile("-{10,}");
    private static final Pattern STAT_FIELD = Pattern.compile(
            "^(加入游戏|在线次数|死亡计数|击杀数|击杀计数|游戏时长|队伍|优先队列|特殊权限)\\s*[:：].*$");

    private final Map<String, Expectation> expectedPlayers = new LinkedHashMap<>();
    private final long requestTimeoutMillis;
    private String activeKey;
    private String activePlayer;
    private List<String> activeLines;
    private boolean activeHasStatField;

    StatResponseCollector() {
        this(REQUEST_TIMEOUT_MILLIS);
    }

    StatResponseCollector(long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    synchronized void expect(String playerName) {
        expect(playerName, requestTimeoutMillis);
    }

    synchronized void expect(String playerName, long timeoutMillis) {
        long safeTimeout = Math.max(1L, timeoutMillis);
        expectedPlayers.put(normalize(playerName),
                new Expectation(playerName, System.currentTimeMillis() + safeTimeout));
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
                activeHasStatField = false;
            }
            if (activePlayer == null || activeLines == null) {
                continue;
            }
            activeLines.add(trimmed);
            if (STAT_FIELD.matcher(trimmed).matches()) {
                activeHasStatField = true;
            }
            if (activeHasStatField && SEPARATOR.matcher(trimmed).matches()) {
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

    synchronized Optional<String> rejectMissingPlayer(String text) {
        boolean missing = PLAYER_NOT_FOUND.matcher(StatText.normalize(text)).matches();
        if (!missing || expectedPlayers.isEmpty()) {
            return Optional.empty();
        }
        Map.Entry<String, Expectation> oldest = expectedPlayers.entrySet().iterator().next();
        expectedPlayers.remove(oldest.getKey());
        if (oldest.getKey().equals(activeKey)) {
            resetActiveResponse();
        }
        return Optional.of(oldest.getValue().displayName);
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

    synchronized boolean hasPending() {
        return activePlayer != null || !expectedPlayers.isEmpty();
    }

    private void resetActiveResponse() {
        activeKey = null;
        activePlayer = null;
        activeLines = null;
        activeHasStatField = false;
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
