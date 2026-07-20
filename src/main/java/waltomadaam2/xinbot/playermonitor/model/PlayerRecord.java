package waltomadaam2.xinbot.playermonitor.model;

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

    public PlayerRecord copy() {
        PlayerRecord copy = new PlayerRecord(playerName, firstSeenAt);
        copy.schemaVersion = schemaVersion;
        for (LoginSession session : loginSessions) {
            copy.loginSessions.add(session.copy());
        }
        for (ChatEntry entry : chatMessages) {
            copy.chatMessages.add(entry.copy());
        }
        for (StatSnapshot snapshot : statSnapshots) {
            copy.statSnapshots.add(snapshot.copy());
        }
        return copy;
    }
}
