package waltomadaam2.xinbot.playermonitor.model;

public final class LoginSession {
    public long loginAt;
    public Long logoutAt;

    public LoginSession() {
    }

    public LoginSession(long loginAt) {
        this.loginAt = loginAt;
    }

    public LoginSession copy() {
        LoginSession copy = new LoginSession(loginAt);
        copy.logoutAt = logoutAt;
        return copy;
    }
}
