package huangdihd.xinbot.playermonitor;

final class MonitorSettings {
    int statIntervalMillis = 500;
    boolean autoScanOnGameEntry = true;
    boolean statScanEnabled = true;
    boolean statOutputHidden = true;

    MonitorSettings copy() {
        MonitorSettings copy = new MonitorSettings();
        copy.statIntervalMillis = statIntervalMillis;
        copy.autoScanOnGameEntry = autoScanOnGameEntry;
        copy.statScanEnabled = statScanEnabled;
        copy.statOutputHidden = statOutputHidden;
        return copy;
    }
}
