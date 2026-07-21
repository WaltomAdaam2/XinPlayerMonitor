package waltomadaam2.xinbot.playermonitor;

import com.google.gson.annotations.SerializedName;

final class MonitorSettings {
    static final int DEFAULT_STAT_SEND_INTERVAL_MILLIS = 500;
    static final int DEFAULT_DISCONNECT_TIMEOUT_MINUTES = 10;
    static final int DEFAULT_STAT_COOLDOWN_HOURS = 24;
    static final int DEFAULT_STAT_TIMEOUT_MILLIS = 3_000;
    static final int DEFAULT_STAT_ATTEMPTS = 4;
    static final int DEFAULT_RECENTLOGIN_COUNT = 15;
    static final int DEFAULT_CHAT_COUNT = 10;
    static final int DEFAULT_CACHE_IDLE_MINUTES = 30;
    static final int DEFAULT_MAX_CACHED_HISTORY = 200;

    @SerializedName(value = "statSendIntervalMillis", alternate = {"statIntervalMillis"})
    int statSendIntervalMillis = DEFAULT_STAT_SEND_INTERVAL_MILLIS;

    @SerializedName(value = "disconnectTimeoutMinutes", alternate = {"disconnectFinalizationMinutes"})
    int disconnectTimeoutMinutes = DEFAULT_DISCONNECT_TIMEOUT_MINUTES;

    @SerializedName(value = "scanOnEntry", alternate = {"autoScanOnGameEntry"})
    boolean scanOnEntry = true;

    @SerializedName(value = "statEnabled", alternate = {"statScanEnabled"})
    boolean statEnabled = true;

    @SerializedName(value = "statOutputHide", alternate = {"statOutputHidden"})
    boolean statOutputHide = true;

    int statCooldownHours = DEFAULT_STAT_COOLDOWN_HOURS;
    int statTimeoutMillis = DEFAULT_STAT_TIMEOUT_MILLIS;
    int statAttempts = DEFAULT_STAT_ATTEMPTS;
    boolean scanOnJoin = true;
    boolean prioritizeJoinStat = true;
    String displayTimezone = "UTC";
    int recentLoginCount = DEFAULT_RECENTLOGIN_COUNT;
    int chatCount = DEFAULT_CHAT_COUNT;
    int cacheIdleMinutes = DEFAULT_CACHE_IDLE_MINUTES;
    int maxCachedHistory = DEFAULT_MAX_CACHED_HISTORY;

    MonitorSettings copy() {
        MonitorSettings copy = new MonitorSettings();
        copy.statSendIntervalMillis = statSendIntervalMillis;
        copy.disconnectTimeoutMinutes = disconnectTimeoutMinutes;
        copy.scanOnEntry = scanOnEntry;
        copy.statEnabled = statEnabled;
        copy.statOutputHide = statOutputHide;
        copy.statCooldownHours = statCooldownHours;
        copy.statTimeoutMillis = statTimeoutMillis;
        copy.statAttempts = statAttempts;
        copy.scanOnJoin = scanOnJoin;
        copy.prioritizeJoinStat = prioritizeJoinStat;
        copy.displayTimezone = displayTimezone;
        copy.recentLoginCount = recentLoginCount;
        copy.chatCount = chatCount;
        copy.cacheIdleMinutes = cacheIdleMinutes;
        copy.maxCachedHistory = maxCachedHistory;
        return copy;
    }
}
