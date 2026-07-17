package huangdihd.xinbot.playermonitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import huangdihd.xinbot.playermonitor.model.PlayerRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class PlayerRecordStore {
    private final Path directory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    PlayerRecordStore(Path directory) {
        this.directory = directory;
    }

    void initialize() throws IOException {
        Files.createDirectories(directory);
    }

    PlayerRecord read(String playerName) throws IOException {
        synchronized (lockFor(playerName)) {
            return loadOrCreate(playerName, System.currentTimeMillis());
        }
    }

    void update(String playerName, long now, Consumer<PlayerRecord> mutation) throws IOException {
        synchronized (lockFor(playerName)) {
            PlayerRecord record = loadOrCreate(playerName, now);
            mutation.accept(record);
            write(record);
        }
    }

    private Object lockFor(String playerName) {
        return locks.computeIfAbsent(playerName, ignored -> new Object());
    }

    private PlayerRecord loadOrCreate(String playerName, long now) throws IOException {
        Path path = pathFor(playerName);
        if (!Files.exists(path)) {
            return new PlayerRecord(playerName, now);
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            PlayerRecord record = gson.fromJson(reader, PlayerRecord.class);
            if (record == null || record.playerName == null) {
                throw new IOException("Invalid player record: " + path.getFileName());
            }
            if (record.loginSessions == null) record.loginSessions = new java.util.ArrayList<>();
            if (record.chatMessages == null) record.chatMessages = new java.util.ArrayList<>();
            if (record.statSnapshots == null) record.statSnapshots = new java.util.ArrayList<>();
            return record;
        }
    }

    private void write(PlayerRecord record) throws IOException {
        Path target = pathFor(record.playerName);
        Path temporary = Files.createTempFile(directory, record.playerName + ".", ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            gson.toJson(record, writer);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path pathFor(String playerName) throws IOException {
        Objects.requireNonNull(playerName, "playerName");
        if (playerName.isBlank() || playerName.indexOf('/') >= 0 || playerName.indexOf('\\') >= 0
                || playerName.matches(".*[:*?\"<>|].*")) {
            throw new IOException("Unsafe player name for file storage: " + playerName);
        }
        Path path = directory.resolve(playerName + ".json").normalize();
        if (!path.getParent().equals(directory)) {
            throw new IOException("Unsafe player record path");
        }
        return path;
    }
}
