package huangdihd.xinbot.playermonitor;

import org.slf4j.Logger;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.TabExecutor;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

final class PlayerMonitorManagementCommand extends TabExecutor {
    private final MonitorSettingsStore settings;
    private final PlayerMonitorListener listener;
    private final Logger logger;

    PlayerMonitorManagementCommand(MonitorSettingsStore settings, PlayerMonitorListener listener, Logger logger) {
        this.settings = settings;
        this.listener = listener;
        this.logger = logger;
    }

    @Override
    public void onCommand(Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            help();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setting" -> setting(args);
            case "stat" -> stat(args);
            default -> help();
        }
    }

    @Override
    public List<String> onTabComplete(Command command, String label, String[] args) {
        if (args == null || args.length == 0) {
            return List.of("setting", "stat");
        }
        if (args.length == 1) {
            return matching(args[0], List.of("setting", "stat"));
        }
        if (args.length == 2 && "setting".equalsIgnoreCase(args[0])) {
            return matching(args[1], List.of("interval", "auto", "enabled"));
        }
        if (args.length == 2 && "stat".equalsIgnoreCase(args[0])) {
            return matching(args[1], List.of("scan"));
        }
        if (args.length == 3 && "setting".equalsIgnoreCase(args[0])
                && ("auto".equalsIgnoreCase(args[1]) || "enabled".equalsIgnoreCase(args[1]))) {
            return matching(args[2], List.of("on", "off"));
        }
        return List.of();
    }

    private void setting(String[] args) {
        if (args.length == 1) {
            MonitorSettings current = settings.get();
            print("Stat interval: " + current.statIntervalMillis + "ms");
            print("Auto scan on Game entry: " + state(current.autoScanOnGameEntry));
            print("Automatic stat scan: " + state(current.statScanEnabled));
            print("Usage: playermonitor setting interval <ms>");
            print("Usage: playermonitor setting auto <on|off>");
            print("Usage: playermonitor setting enabled <on|off>");
            return;
        }
        if (args.length != 3) {
            help();
            return;
        }
        try {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "interval" -> {
                    int interval = Integer.parseInt(args[2]);
                    settings.setStatIntervalMillis(interval);
                    print("Stat interval set to " + interval + "ms.");
                }
                case "auto" -> {
                    boolean enabled = parseState(args[2]);
                    settings.setAutoScanOnGameEntry(enabled);
                    print("Auto scan on Game entry set to " + state(enabled) + ".");
                }
                case "enabled" -> {
                    boolean enabled = parseState(args[2]);
                    settings.setStatScanEnabled(enabled);
                    print("Automatic stat scan set to " + state(enabled) + ".");
                }
                default -> help();
            }
        } catch (NumberFormatException error) {
            print("Stat interval must be a whole number of milliseconds.");
        } catch (IllegalArgumentException error) {
            print(error.getMessage());
        } catch (IOException error) {
            print("Unable to save monitor settings: " + error.getMessage());
        }
    }

    private void stat(String[] args) {
        if (args.length != 2 || !"scan".equalsIgnoreCase(args[1])) {
            help();
            return;
        }
        int queued = listener.scanAllOnlinePlayers();
        if (queued < 0) {
            print("Stat scan is only available in Game.");
            return;
        }
        print("Queued stat scan for " + queued + " online players.");
    }

    private void help() {
        print("Usage: playermonitor setting [interval <ms>|auto <on|off>|enabled <on|off>]");
        print("Usage: playermonitor stat scan");
    }

    private void print(String message) {
        logger.info(message);
    }

    private static List<String> matching(String input, List<String> values) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private static boolean parseState(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("State must be on or off.");
        };
    }

    private static String state(boolean value) {
        return value ? "on" : "off";
    }
}
