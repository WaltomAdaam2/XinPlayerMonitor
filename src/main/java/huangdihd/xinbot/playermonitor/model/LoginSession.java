package huangdihd.xinbot.playermonitor.model;

public final class LoginSession {
    public long loginAt;
    public Long logoutAt;

    public LoginSession() {
    }

    public LoginSession(long loginAt) {
        this.loginAt = loginAt;
    }
}
