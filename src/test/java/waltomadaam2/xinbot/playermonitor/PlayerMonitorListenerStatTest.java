package waltomadaam2.xinbot.playermonitor;

import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.events.SendCommandEvent;
import xin.bbtt.mcbot.events.ServerChangeEvent;
import xin.bbtt.mcbot.events.SystemChatMessageEvent;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PlayerMonitorListenerStatTest {
    @TempDir
    Path temporaryDirectory;

    private PlayerMonitorService service;
    private PlayerMonitorListener listener;

    @BeforeEach
    void setUp() throws Exception {
        service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        MonitorSettingsStore settings = new MonitorSettingsStore(temporaryDirectory.resolve("playermonitor"));
        settings.initialize();
        settings.setAutoScanOnGameEntry(false);
        settings.setStatScanEnabled(false);
        listener = new PlayerMonitorListener(
                service,
                new PluginLog(temporaryDirectory.resolve("playermonitor/log")),
                NOPLogger.NOP_LOGGER,
                settings);
        Bot.INSTANCE.players.clear();
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
        }
        Bot.INSTANCE.players.clear();
    }

    @Test
    void successfulStatResponseClearsRetryStateEndToEnd() throws Exception {
        GameProfile profile = profile("Alice");
        listener.onServerChange(new ServerChangeEvent(Server.Game, Server.Login));
        Bot.INSTANCE.players.put(profile.getId(), profile);
        listener.scanAllOnlinePlayers();

        waitUntil(() -> listener.isPendingStatDispatchForTesting("Alice"),
                "stat command was never dispatched for Alice");

        listener.onSendCommand(new SendCommandEvent("stat Alice"));
        assertEquals(1, listener.statAttemptsForTesting("Alice"));

        String statText = String.join("\n",
                "§b玩家名称: Alice",
                "§b加入游戏: 1 次",
                "§b死亡计数: 2 次",
                "§b击杀计数: 3 人",
                "§e游戏时长: 4秒",
                "§b优先队列: 已过期",
                "§b特殊权限: ✅",
                "----------------------");
        listener.onSystemChat(new SystemChatMessageEvent(Component.text(statText), false));

        waitUntil(() -> listener.statAttemptsForTesting("Alice") == 0,
                "retry state was never cleared after a successful stat response");
        waitUntil(() -> {
            try {
                return service.findRecord("Alice").map(record -> !record.statSnapshots.isEmpty()).orElse(false);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }, "captured stat was never persisted");
        assertFalse(listener.isPendingStatDispatchForTesting("Alice"));
    }

    @Test
    void sendFailureIsCountedAndGivesUpAtLimitWithoutAffectingOtherPlayers() {
        listener.setGameActiveForTesting(true);
        listener.markOnlineForTesting("Alice");
        listener.markOnlineForTesting("Bob");

        listener.handleStatSendFailureForTesting("Alice");
        assertEquals(1, listener.statAttemptsForTesting("Alice"));

        listener.handleStatSendFailureForTesting("Alice");
        listener.handleStatSendFailureForTesting("Alice");
        listener.handleStatSendFailureForTesting("Alice");
        assertEquals(0, listener.statAttemptsForTesting("Alice"),
                "state must be cleared once the max send count is reached");
        assertFalse(listener.isPendingStatDispatchForTesting("Alice"));

        listener.handleStatSendFailureForTesting("Bob");
        assertEquals(1, listener.statAttemptsForTesting("Bob"),
                "Alice giving up must not affect Bob's independent retry count");

        listener.handleStatSendFailureForTesting("Alice");
        assertEquals(1, listener.statAttemptsForTesting("Alice"),
                "a later scan must be able to start a fresh retry cycle for Alice");
    }

    @Test
    void timeoutRetriesWhileBelowTheSendLimit() {
        listener.setGameActiveForTesting(true);
        listener.markOnlineForTesting("Carol");
        listener.markPendingStatDispatchForTesting("Carol");
        listener.onSendCommand(new SendCommandEvent("stat Carol"));
        assertEquals(1, listener.statAttemptsForTesting("Carol"));

        listener.evaluateStatAttemptForTesting("Carol", 1);

        assertEquals(1, listener.statAttemptsForTesting("Carol"),
                "retry state must be retained while below the send limit");
    }

    @Test
    void timeoutGivesUpOnceTheSendLimitIsReached() {
        listener.setGameActiveForTesting(true);
        listener.markOnlineForTesting("Dave");
        for (int i = 0; i < 4; i++) {
            listener.markPendingStatDispatchForTesting("Dave");
            listener.onSendCommand(new SendCommandEvent("stat Dave"));
        }
        assertEquals(4, listener.statAttemptsForTesting("Dave"));

        listener.evaluateStatAttemptForTesting("Dave", 4);

        assertEquals(0, listener.statAttemptsForTesting("Dave"));
        assertFalse(listener.isPendingStatDispatchForTesting("Dave"));
    }

    @Test
    void timeoutGivesUpImmediatelyWhenPlayerIsNoLongerOnline() {
        listener.setGameActiveForTesting(true);
        listener.markPendingStatDispatchForTesting("Eve");
        listener.onSendCommand(new SendCommandEvent("stat Eve"));
        assertEquals(1, listener.statAttemptsForTesting("Eve"));

        listener.evaluateStatAttemptForTesting("Eve", 1);

        assertEquals(0, listener.statAttemptsForTesting("Eve"),
                "a player who left before the retry must not be retried further");
    }

    private static void waitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        if (!condition.getAsBoolean()) {
            fail(failureMessage);
        }
    }

    @Test
    void statStateClearedEvenWhenPersistenceFails() throws Exception {
        service.setStatWriteFailureForTesting(key -> key.equals("alice"));
        listener.setGameActiveForTesting(true);
        listener.markOnlineForTesting("Alice");

        // Simulate the full stat flow: dispatch → response → state-clearing
        listener.markPendingStatDispatchForTesting("Alice");
        listener.onSendCommand(new SendCommandEvent("stat Alice"));
        assertEquals(1, listener.statAttemptsForTesting("Alice"));

        String statText = String.join("\n",
                "§b玩家名称: Alice",
                "§b加入游戏: 1 次",
                "§b死亡计数: 2 次",
                "§b击杀计数: 3 人",
                "§e游戏时长: 4秒",
                "§b优先队列: 已过期",
                "§b特殊权限: ✅",
                "----------------------");
        listener.onSystemChat(new SystemChatMessageEvent(Component.text(statText), false));

        // State must be cleared synchronously, before the async persistence attempt
        assertEquals(0, listener.statAttemptsForTesting("Alice"),
                "stat attempts must be cleared before persistence is attempted");
        assertFalse(listener.isPendingStatDispatchForTesting("Alice"),
                "pending dispatch must be cleared even when persistence will fail");

        // Wait for the async write to complete (it will fail with IOException and be swallowed)
        waitUntil(() -> {
            try {
                // Force a read to ensure the async task had time to run
                service.findRecord("Alice");
                return true;
            } catch (java.io.IOException error) {
                return false;
            }
        }, "async stat write task never completed");

        // After the failed write, state must still be clean — no retry triggered
        assertEquals(0, listener.statAttemptsForTesting("Alice"),
                "no retry must be triggered after a persistence failure");
        assertFalse(listener.isPendingStatDispatchForTesting("Alice"));
    }

    private static GameProfile profile(String name) {
        return new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name);
    }
}
