package huangdihd.xinbot.playermonitor;

import huangdihd.xinbot.playermonitor.model.ChatEntry;
import huangdihd.xinbot.playermonitor.model.LoginSession;
import huangdihd.xinbot.playermonitor.model.PlayerRecord;
import huangdihd.xinbot.playermonitor.model.StatSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PlayerMonitorService {
    private final PlayerRecordStore store;

    public PlayerMonitorService(Path directory) {
        this.store = new PlayerRecordStore(directory);
    }

    public void initialize() throws IOException {
        store.initialize();
    }

    public void recordLogin(String playerName, long now) throws IOException {
        store.update(playerName, now, record -> {
            for (LoginSession session : record.loginSessions) {
                if (session.logoutAt == null) {
                    session.logoutAt = Math.max(session.loginAt, now);
                }
            }
            record.loginSessions.add(new LoginSession(now));
        });
    }

    public void recordLogout(String playerName, long now) throws IOException {
        store.update(playerName, now, record -> {
            LoginSession latestOpen = record.loginSessions.stream()
                    .filter(session -> session.logoutAt == null)
                    .max(Comparator.comparingLong(session -> session.loginAt))
                    .orElse(null);
            if (latestOpen != null) {
                latestOpen.logoutAt = Math.max(latestOpen.loginAt, now);
            }
        });
    }

    public void recordChat(String playerName, String message, long now) throws IOException {
        store.update(playerName, now, record -> record.chatMessages.add(new ChatEntry(now, message)));
    }

    public void recordStat(String playerName, StatSnapshot snapshot) throws IOException {
        store.update(playerName, snapshot.capturedAt, record -> record.statSnapshots.add(snapshot));
    }

    public PlayerRecord getRecord(String playerName) throws IOException {
        return store.read(playerName);
    }

    public Optional<PlayerRecord> findRecord(String playerName) throws IOException {
        for (String storedName : store.listPlayerNames()) {
            if (storedName.equalsIgnoreCase(playerName)) {
                return store.find(storedName);
            }
        }
        return Optional.empty();
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
