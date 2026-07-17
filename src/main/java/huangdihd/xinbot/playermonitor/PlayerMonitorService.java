package huangdihd.xinbot.playermonitor;

import huangdihd.xinbot.playermonitor.model.ChatEntry;
import huangdihd.xinbot.playermonitor.model.LoginSession;
import huangdihd.xinbot.playermonitor.model.PlayerRecord;
import huangdihd.xinbot.playermonitor.model.StatSnapshot;

import java.io.IOException;
import java.nio.file.Path;
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
        store.update(playerName, now, record -> record.loginSessions.add(new LoginSession(now)));
    }

    public void recordLogout(String playerName, long now) throws IOException {
        store.update(playerName, now, record -> {
            for (int index = record.loginSessions.size() - 1; index >= 0; index--) {
                LoginSession session = record.loginSessions.get(index);
                if (session.logoutAt == null) {
                    session.logoutAt = now;
                    return;
                }
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

    public List<String> listPlayerNames() throws IOException {
        return store.listPlayerNames();
    }
}
