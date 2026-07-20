package waltomadaam2.xinbot.playermonitor.model;

public final class ChatEntry {
    public long timestamp;
    public String message;

    public ChatEntry() {
    }

    public ChatEntry(long timestamp, String message) {
        this.timestamp = timestamp;
        this.message = message;
    }

    public ChatEntry copy() {
        return new ChatEntry(timestamp, message);
    }
}
