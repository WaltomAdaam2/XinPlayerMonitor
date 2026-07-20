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

    synchronized void info(String message) {
        Path file = directory.resolve("playermonitor-" + LocalDate.now(zone) + ".log");
        String line = Instant.now() + " " + message + System.lineSeparator();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            System.err.println("XinPlayerMonitor log write failed: " + error.getMessage());
        }
    }
}
