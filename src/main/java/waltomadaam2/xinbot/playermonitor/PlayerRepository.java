package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

interface PlayerRepository extends AutoCloseable {
    void initialize() throws IOException;

    void setWarningSink(Consumer<String> warningSink);

    default void setInfoSink(Consumer<String> infoSink) {
    }

    default void setEvictionGuard(Predicate<String> evictionGuard) {
    }

    default void setStatWriteFailureForTesting(Predicate<String> statWriteFailure) {
    }

    /** Legacy JSON-store hooks. The SQLite backend intentionally does not cache player history. */
    default void trimCachedHistoryToConfiguredLimit() {
    }

    /** Legacy JSON-store hooks. The SQLite backend intentionally does not cache player history. */
    default void evictIdleRecords() {
    }

    void recordLogin(String playerName, long now) throws IOException;

    void recordLogout(String playerName, long now) throws IOException;

    void recordChat(String playerName, String message, long now) throws IOException;

    void recordStat(String playerName, StatSnapshot snapshot) throws IOException;

    /**
     * Legacy full-history read. Production command paths should prefer summary and paged query methods.
     */
    PlayerRecord read(String playerName) throws IOException;

    /**
     * Legacy full-history lookup. Production command paths should prefer summary and paged query methods.
     */
    Optional<PlayerRecord> find(String playerName) throws IOException;

    default Optional<PlayerRecord> findSummary(String playerName) throws IOException {
        return find(playerName);
    }

    default boolean playerExists(String playerName) throws IOException {
        return findSummary(playerName).isPresent();
    }

    default Optional<StatSnapshot> latestStat(String playerName) throws IOException {
        return find(playerName).flatMap(record -> record.statSnapshots.stream()
                .max(java.util.Comparator.comparingLong(snapshot -> snapshot.capturedAt)));
    }

    default Optional<LoginSession> latestLogin(String playerName) throws IOException {
        return find(playerName).flatMap(record -> record.loginSessions.stream()
                .max(java.util.Comparator.comparingLong(session -> session.loginAt)));
    }

    default List<LoginSession> recentLogins(String playerName, int limit) throws IOException {
        return find(playerName)
                .map(record -> record.loginSessions.stream()
                        .sorted(java.util.Comparator.comparingLong((LoginSession session) -> session.loginAt).reversed())
                        .limit(Math.max(0, limit))
                        .toList())
                .orElseGet(List::of);
    }

    default int loginCount(String playerName) throws IOException {
        return find(playerName).map(record -> record.loginSessions.size()).orElse(0);
    }

    default List<ChatEntry> recentChats(String playerName, int limit) throws IOException {
        return find(playerName)
                .map(record -> {
                    int start = Math.max(0, record.chatMessages.size() - Math.max(0, limit));
                    List<ChatEntry> entries = new java.util.ArrayList<>();
                    for (int index = record.chatMessages.size() - 1; index >= start; index--) {
                        entries.add(record.chatMessages.get(index));
                    }
                    return entries;
                })
                .orElseGet(List::of);
    }

    default int chatCount(String playerName) throws IOException {
        return find(playerName).map(record -> record.chatMessages.size()).orElse(0);
    }

    default Optional<PlayerOverview> playerOverview(String playerName, long now) throws IOException {
        return find(playerName).map(record -> {
            LoginSession latest = record.loginSessions.stream()
                    .max(java.util.Comparator.comparingLong(session -> session.loginAt))
                    .orElse(null);
            long cutoff = now - java.util.concurrent.TimeUnit.DAYS.toMillis(30);
            long last30Days = 0L;
            for (LoginSession session : record.loginSessions) {
                long start = Math.max(session.loginAt, cutoff);
                long end = session.logoutAt == null ? now : Math.min(session.logoutAt, now);
                if (end > start) {
                    last30Days += end - start;
                }
            }
            StatSnapshot latestStat = record.statSnapshots.stream()
                    .max(java.util.Comparator.comparingLong(snapshot -> snapshot.capturedAt))
                    .orElse(null);
            Long duration = latest == null ? null
                    : Math.max(0L, (latest.logoutAt == null ? now : latest.logoutAt) - latest.loginAt);
            return new PlayerOverview(record.playerName, record.firstSeenAt, record.chatMessages.size(),
                    latest == null ? null : latest.loginAt,
                    latest == null ? null : latest.logoutAt,
                    duration, last30Days, latestStat);
        });
    }

    List<String> listPlayerNames() throws IOException;

    /**
     * Non-blocking best-effort name snapshot for tab completion. Implementations may omit not-yet-committed names.
     */
    default List<String> listPlayerNamesSnapshot() throws IOException {
        return listPlayerNames();
    }

    default DatabaseStats databaseStats() throws IOException {
        return new DatabaseStats(listPlayerNames().size(), 0, 0, 0, 0);
    }

    default DatabaseStats databaseStatsSnapshot() throws IOException {
        return databaseStats();
    }

    default DatabaseHealth databaseHealth() throws IOException {
        return new DatabaseHealth("UNKNOWN", false, false, false, 0, 0, 0, 0, 0,
                0L, 0L, 0L, 0L, 0L, "", 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    default boolean hasStatCapturedAtOrAfter(String playerName, long cutoffAt) throws IOException {
        Optional<PlayerRecord> record = find(playerName);
        if (record.isEmpty()) {
            return false;
        }
        for (StatSnapshot snapshot : record.get().statSnapshots) {
            if (snapshot.capturedAt >= cutoffAt) {
                return true;
            }
        }
        return false;
    }

    default Set<String> playersWithStatCapturedAtOrAfter(Collection<String> playerNames, long cutoffAt)
            throws IOException {
        Set<String> result = new HashSet<>();
        for (String playerName : playerNames) {
            if (hasStatCapturedAtOrAfter(playerName, cutoffAt)) {
                result.add(playerName.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Closes sessions left open by an earlier unclean shutdown. The caller should then record the current roster
     * as a fresh set of sessions.
     *
     * @return number of stale open sessions closed
     */
    default int recoverOpenSessions(long recoveredAt) throws IOException {
        return 0;
    }

    default void flush() throws IOException {
    }

    default Path databasePath() {
        return null;
    }

    default void backupTo(Path target) throws IOException {
        throw new UnsupportedOperationException("backup not supported");
    }

    @Override
    void close();
}
