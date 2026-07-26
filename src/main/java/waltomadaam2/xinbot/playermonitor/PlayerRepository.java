package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    default void trimCachedHistoryToConfiguredLimit() {
    }

    default void evictIdleRecords() {
    }

    void recordLogin(String playerName, long now) throws IOException;

    void recordLogout(String playerName, long now) throws IOException;

    void recordChat(String playerName, String message, long now) throws IOException;

    void recordStat(String playerName, StatSnapshot snapshot) throws IOException;

    PlayerRecord read(String playerName) throws IOException;

    Optional<PlayerRecord> find(String playerName) throws IOException;

    default Optional<PlayerRecord> findSummary(String playerName) throws IOException {
        return find(playerName);
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

    List<String> listPlayerNames() throws IOException;

    default DatabaseOverview databaseOverview() throws IOException {
        int players = 0;
        int chats = 0;
        int sessions = 0;
        int stats = 0;
        int openSessions = 0;
        for (String playerName : listPlayerNames()) {
            Optional<PlayerRecord> record = find(playerName);
            if (record.isEmpty()) {
                continue;
            }
            PlayerRecord value = record.get();
            players++;
            chats += value.chatMessages.size();
            sessions += value.loginSessions.size();
            stats += value.statSnapshots.size();
            for (LoginSession session : value.loginSessions) {
                if (session.logoutAt == null) {
                    openSessions++;
                }
            }
        }
        return new DatabaseOverview(players, chats, sessions, stats, openSessions);
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
