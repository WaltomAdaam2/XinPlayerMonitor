package huangdihd.xinbot.playermonitor.model;

import java.util.ArrayList;
import java.util.List;

public final class PlayerRecord {
    public int schemaVersion = 1;
    public String playerName;
    public long firstSeenAt;
    public List<LoginSession> loginSessions = new ArrayList<>();
    public List<ChatEntry> chatMessages = new ArrayList<>();
    public List<StatSnapshot> statSnapshots = new ArrayList<>();

    public PlayerRecord() {
    }

    public PlayerRecord(String playerName, long firstSeenAt) {
        this.playerName = playerName;
        this.firstSeenAt = firstSeenAt;
    }
}
