package waltomadaam2.xinbot.playermonitor.model;

public final class PlayerProfile {
    public int schemaVersion = 2;
    public String playerName;
    public long firstSeenAt;
    public long lastSeenAt;
    public boolean online;
    public LoginSession currentSession;
    public Long lastStatCapturedAt;

    public PlayerProfile() {
    }

    public PlayerProfile(String playerName, long firstSeenAt) {
        this.playerName = playerName;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = firstSeenAt;
    }

    public PlayerProfile copy() {
        PlayerProfile copy = new PlayerProfile(playerName, firstSeenAt);
        copy.schemaVersion = schemaVersion;
        copy.lastSeenAt = lastSeenAt;
        copy.online = online;
        copy.currentSession = currentSession == null ? null : currentSession.copy();
        copy.lastStatCapturedAt = lastStatCapturedAt;
        return copy;
    }
}
