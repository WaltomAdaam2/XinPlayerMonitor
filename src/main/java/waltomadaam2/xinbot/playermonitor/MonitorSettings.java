package waltomadaam2.xinbot.playermonitor;

final class MonitorSettings {
    int statIntervalMillis = 500;
    int disconnectFinalizationMinutes = 10;
    boolean autoScanOnGameEntry = true;
    boolean statScanEnabled = true;
    boolean statOutputHidden = true;

    MonitorSettings copy() {
        MonitorSettings copy = new MonitorSettings();
        copy.statIntervalMillis = statIntervalMillis;
        copy.disconnectFinalizationMinutes = disconnectFinalizationMinutes;
        copy.autoScanOnGameEntry = autoScanOnGameEntry;
        copy.statScanEnabled = statScanEnabled;
        copy.statOutputHidden = statOutputHidden;
        return copy;
    }
}
