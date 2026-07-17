package huangdihd.xinbot.playermonitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

final class PluginLog {
    private final Path file;

    PluginLog(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("playermonitor.log");
    }

    synchronized void info(String message) {
        String line = Instant.now() + " " + message + System.lineSeparator();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            System.err.println("XinPlayerMonitor log write failed: " + error.getMessage());
        }
    }
}
