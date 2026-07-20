package waltomadaam2.xinbot.playermonitor.model;

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

    public StatSnapshot copy() {
        StatSnapshot copy = new StatSnapshot();
        copy.capturedAt = capturedAt;
        copy.addedGameCount = addedGameCount;
        copy.onlineCount = onlineCount;
        copy.deathCount = deathCount;
        copy.killCount = killCount;
        copy.playtimeSeconds = playtimeSeconds;
        copy.team = team;
        copy.priorityQueue = priorityQueue;
        copy.permissions = permissions == null ? null : permissions.copy();
        copy.permissionsDisplay = permissionsDisplay;
        return copy;
    }
}
