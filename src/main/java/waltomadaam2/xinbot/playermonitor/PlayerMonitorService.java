package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.LoginSession;
import waltomadaam2.xinbot.playermonitor.model.PlayerRecord;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class PlayerMonitorService {
    private final PlayerRepository store;

    public PlayerMonitorService(Path directory) {
        this.store = new SQLitePlayerRecordStore(directory, new MonitorSettings.Database());
    }

    PlayerMonitorService(Path directory, MonitorSettingsStore settings) {
        this.store = new SQLitePlayerRecordStore(directory, settings.database());
    }

    PlayerMonitorService(PlayerRepository store) {
        this.store = store;
    }

    public void setWarningSink(Consumer<String> warningSink) {
        store.setWarningSink(warningSink);
    }

    public void setEvictionGuard(Predicate<String> evictionGuard) {
        store.setEvictionGuard(evictionGuard);
    }

    void setStatWriteFailureForTesting(Predicate<String> statWriteFailure) {
        store.setStatWriteFailureForTesting(statWriteFailure);
    }

    public void initialize() throws IOException {
        store.initialize();
    }

    public void close() {
        store.close();
    }

    public void applyCacheSettingsNow() {
        store.trimCachedHistoryToConfiguredLimit();
        store.evictIdleRecords();
    }

    public void recordLogin(String playerName, long now) throws IOException {
        store.recordLogin(playerName, now);
    }

    public void recordLogout(String playerName, long now) throws IOException {
        store.recordLogout(playerName, now);
    }

    public void recordChat(String playerName, String message, long now) throws IOException {
        store.recordChat(playerName, message, now);
    }

    public void recordStat(String playerName, StatSnapshot snapshot) throws IOException {
        store.recordStat(playerName, snapshot);
    }

    public PlayerRecord getRecord(String playerName) throws IOException {
        return store.read(playerName);
    }

    public Optional<PlayerRecord> findRecord(String playerName) throws IOException {
        return store.find(playerName);
    }
    public Optional<PlayerRecord> findRecordSummary(String playerName) throws IOException {
        return store.findSummary(playerName);
    }

    public Optional<StatSnapshot> latestStat(String playerName) throws IOException {
        return store.latestStat(playerName);
    }

    public Optional<LoginSession> latestLogin(String playerName) throws IOException {
        return store.latestLogin(playerName);
    }

    public List<LoginSession> recentLogins(String playerName, int maximum) throws IOException {
        return store.recentLogins(playerName, maximum);
    }

    public int loginCount(String playerName) throws IOException {
        return store.loginCount(playerName);
    }

    public List<ChatEntry> recentChats(String playerName, int maximum) throws IOException {
        return store.recentChats(playerName, maximum);
    }

    public int chatCount(String playerName) throws IOException {
        return store.chatCount(playerName);
    }

    public boolean hasStatCapturedAtOrAfter(String playerName, long cutoffAt) throws IOException {
        return store.hasStatCapturedAtOrAfter(playerName, cutoffAt);
    }

    public Optional<LoginSession> latestLogin(PlayerRecord record) {
        return record.loginSessions.stream()
                .max(Comparator.comparingLong(session -> session.loginAt));
    }

    public List<LoginSession> recentLogins(PlayerRecord record, int maximum) {
        if (maximum <= 0) {
            return List.of();
        }
        return record.loginSessions.stream()
                .sorted(Comparator.comparingLong((LoginSession session) -> session.loginAt).reversed())
                .limit(maximum)
                .toList();
    }

    public List<String> listPlayerNames() throws IOException {
        return store.listPlayerNames();
    }
}
