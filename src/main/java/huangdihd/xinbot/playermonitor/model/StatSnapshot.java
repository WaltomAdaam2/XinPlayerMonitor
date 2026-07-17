package huangdihd.xinbot.playermonitor.model;

public final class StatSnapshot {
    public long capturedAt;
    public Integer addedGameCount;
    public Integer onlineCount;
    public Integer deathCount;
    public Integer killCount;
    public Long playtimeSeconds;
    public String team;
    public String priorityQueue;
    public PlayerPermissions permissions = new PlayerPermissions();
    public String permissionsDisplay;

    public StatSnapshot() {
    }
}
