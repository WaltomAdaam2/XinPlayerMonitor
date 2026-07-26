package waltomadaam2.xinbot.playermonitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

final class PluginLog {
    private final Path directory;
    private final ZoneId zone = ZoneId.systemDefault();

    PluginLog(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory;
    }

    /**
     * INFO messages are intentionally not persisted. XinPM's file log is reserved for WARN and ERROR
     * so normal player activity cannot grow the log indefinitely.
     */
    void info(String message) {
        // Deliberately ignored. Keep the method so existing informational call sites remain harmless.
    }

    synchronized void warn(String message) {
        write("WARN", message);
    }

    synchronized void error(String message) {
        write("ERROR", message);
    }

    private void write(String level, String message) {
        Path file = directory.resolve("playermonitor-" + LocalDate.now(zone) + ".log");
        String line = Instant.now() + " [" + level + "] " + StatText.stripAnsi(message) + System.lineSeparator();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            System.err.println("XinPlayerMonitor log write failed: " + error.getMessage());
        }
    }
}
