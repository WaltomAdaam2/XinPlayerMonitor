package waltomadaam2.xinbot.playermonitor.model;

public final class PlayerPermissions {
    public boolean greenText;
    public boolean runMax;
    public boolean dupe;

    public PlayerPermissions() {
    }

    public PlayerPermissions(boolean greenText, boolean runMax, boolean dupe) {
        this.greenText = greenText;
        this.runMax = runMax;
        this.dupe = dupe;
    }
}
