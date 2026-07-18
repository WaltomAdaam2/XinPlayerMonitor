package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerRecordStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cachesPlayerNamesWithoutParsingEveryPlayerFile() throws Exception {
        Files.writeString(temporaryDirectory.resolve("LegacyPlayer.json"), "not valid json", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("settings.json"), "{}", StandardCharsets.UTF_8);
        PlayerRecordStore store = new PlayerRecordStore(temporaryDirectory);

        store.initialize();
        store.update("NewPlayer", 100L, record -> {
        });

        assertEquals(List.of("LegacyPlayer", "NewPlayer"), store.listPlayerNames());
    }
}
