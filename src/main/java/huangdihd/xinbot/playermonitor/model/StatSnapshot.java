package huangdihd.xinbot.playermonitor.model;

public final class StatSnapshot {
    public long capturedAt;
    public Integer addedGameCount;
    public Integer onlineCount;
    public Integer killCount;
    public Long playtimeSeconds;
    public String team;
    public PlayerPermissions permissions = new PlayerPermissions();

    public StatSnapshot() {
    }
}
