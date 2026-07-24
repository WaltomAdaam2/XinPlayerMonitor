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

    static final class Database {
        static final String DEFAULT_PATH = "xinpm.db";
        static final int DEFAULT_QUEUE_CAPACITY = 50_000;
        static final int DEFAULT_BATCH_SIZE = 250;
        static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 250;
        static final int DEFAULT_BUSY_TIMEOUT_MILLIS = 10_000;
        static final int DEFAULT_CACHE_SIZE_KIB = 32_768;
        static final int DEFAULT_SHUTDOWN_FLUSH_TIMEOUT_MILLIS = 30_000;

        String path = DEFAULT_PATH;
        int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        int batchSize = DEFAULT_BATCH_SIZE;
        int flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MILLIS;
        int busyTimeoutMs = DEFAULT_BUSY_TIMEOUT_MILLIS;
        int cacheSizeKiB = DEFAULT_CACHE_SIZE_KIB;
        int shutdownFlushTimeoutMs = DEFAULT_SHUTDOWN_FLUSH_TIMEOUT_MILLIS;

        Database copy() {
            Database copy = new Database();
            copy.path = path;
            copy.queueCapacity = queueCapacity;
            copy.batchSize = batchSize;
            copy.flushIntervalMs = flushIntervalMs;
            copy.busyTimeoutMs = busyTimeoutMs;
            copy.cacheSizeKiB = cacheSizeKiB;
            copy.shutdownFlushTimeoutMs = shutdownFlushTimeoutMs;
            return copy;
        }
    }

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
    Database database = new Database();

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
        copy.database = database == null ? new Database() : database.copy();
        return copy;
    }
}
