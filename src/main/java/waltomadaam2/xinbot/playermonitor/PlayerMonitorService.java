package waltomadaam2.xinbot.playermonitor;

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
    private final PlayerRecordStore store;

    public PlayerMonitorService(Path directory) {
        this.store = new PlayerRecordStore(directory);
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

    public boolean hasStatCapturedAtOrAfter(String playerName, long cutoffAt) throws IOException {
        Optional<PlayerRecord> record = findRecord(playerName);
        if (record.isEmpty()) {
            return false;
        }
        List<StatSnapshot> snapshots = record.get().statSnapshots;
        for (int index = snapshots.size() - 1; index >= 0; index--) {
            if (snapshots.get(index).capturedAt >= cutoffAt) {
                return true;
            }
        }
        return false;
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
