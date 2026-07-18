package huangdihd.xinbot.playermonitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicPlayerQueryResponderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void repliesInChineseForOnlineAndOfflinePlayer() throws Exception {
        PlayerMonitorService service = service();
        service.recordLogin("WaltomAdaam_", 1_000L);
        List<String> replies = new CopyOnWriteArrayList<>();
        AtomicLong now = new AtomicLong(3_000L);
        PublicPlayerQueryResponder responder = responder(service, replies, now);

        responder.handle("x!player WaltomAdaam_");
        assertTrue(replies.isEmpty());

        responder.handle("!PlAyEr WaltomAdaam_ 190u");
        assertTrue(replies.get(0).contains(format(1_000L)));
        assertTrue(replies.get(0).matches(".* [A-Za-z]{3}"));

        service.recordLogout("WaltomAdaam_", 5_000L);
        now.addAndGet(60_001L);
        responder.handle("!player WaltomAdaam_");
        assertTrue(replies.get(1).contains("登出时间 " + format(5_000L)));
    }

    @Test
    void unknownPlayerDoesNotCreateRecordAndStartsCooldown() throws Exception {
        PlayerMonitorService service = service();
        List<String> replies = new CopyOnWriteArrayList<>();
        AtomicLong now = new AtomicLong(1_000L);
        PublicPlayerQueryResponder responder = responder(service, replies, now);

        responder.handle("!player _xinbot宣传");
        responder.handle("!player OtherPlayer");
        assertTrue(replies.get(0).contains("_xinbot宣传"));
        assertTrue(replies.get(0).matches(".* [A-Za-z]{3}"));
        assertFalse(Files.exists(temporaryDirectory.resolve("playermonitor/_xinbot宣传.json")));
    }

    @Test
    void acceptsOnlyOneConcurrentQueryDuringGlobalCooldown() throws Exception {
        PlayerMonitorService service = service();
        List<String> replies = new CopyOnWriteArrayList<>();
        AtomicLong now = new AtomicLong(1_000L);
        PublicPlayerQueryResponder responder = responder(service, replies, now);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> new Thread(() -> {
                    try {
                        start.await();
                        responder.handle("!player Player" + index);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }))
                .toList();

        threads.forEach(Thread::start);
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, replies.size());
    }

    private PlayerMonitorService service() throws Exception {
        PlayerMonitorService service = new PlayerMonitorService(temporaryDirectory.resolve("playermonitor"));
        service.initialize();
        return service;
    }

    private PublicPlayerQueryResponder responder(PlayerMonitorService service, List<String> replies, AtomicLong now) throws Exception {
        PluginLog log = new PluginLog(temporaryDirectory.resolve("playermonitor/log"));
        return new PublicPlayerQueryResponder(service, replies::add, log, now::get);
    }

    private static String format(long timestamp) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp));
    }
}
