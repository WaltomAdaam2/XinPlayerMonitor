package huangdihd.xinbot.playermonitor.model;

import java.util.ArrayList;
import java.util.List;

public final class StatSnapshot {
    public long capturedAt;
    public Integer addedGameCount;
    public Integer onlineCount;
    public Integer killCount;
    public Long playtimeSeconds;
    public String team;
    public PlayerPermissions permissions = new PlayerPermissions();
    public List<String> sourceLines = new ArrayList<>();

    public StatSnapshot() {
    }
}
