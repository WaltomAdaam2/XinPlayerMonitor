package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatQueueTest {
    @Test
    void processesNewPlayersBeforeJoinPlayersBeforeEntryRoster() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allSent = new CountDownLatch(4);

        StatQueue queue = new StatQueue(
                () -> true,
                ignored -> true,
                () -> 1L,
                command -> {
                    sent.add(command.substring("stat ".length()));
                    allSent.countDown();
                    if ("First".equals(command.substring("stat ".length()))) {
                        firstStarted.countDown();
                        try {
                            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(error);
                        }
                    }
                },
                ignored -> {
                },
                ignored -> {
                });
        try {
            queue.enqueue("First", StatQueue.PRIORITY_MANUAL);
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            queue.enqueue("Entry", StatQueue.PRIORITY_ENTRY);
            queue.enqueue("Join", StatQueue.PRIORITY_JOIN);
            queue.enqueue("New", StatQueue.PRIORITY_NEW_PLAYER);
            releaseFirst.countDown();

            assertTrue(allSent.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("First", "New", "Join", "Entry"), sent);
        } finally {
            releaseFirst.countDown();
            queue.close();
        }
    }

    @Test
    void upgradesQueuedCaseVariantWithoutCreatingDuplicateEntry() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allSent = new CountDownLatch(2);

        StatQueue queue = new StatQueue(
                () -> true,
                ignored -> true,
                () -> 1L,
                command -> {
                    String player = command.substring("stat ".length());
                    sent.add(player);
                    allSent.countDown();
                    if ("Blocker".equals(player)) {
                        firstStarted.countDown();
                        try {
                            releaseFirst.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                    }
                },
                ignored -> {
                },
                ignored -> {
                });
        try {
            queue.enqueue("Blocker", StatQueue.PRIORITY_MANUAL);
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            queue.enqueue("Steve", StatQueue.PRIORITY_ENTRY);
            queue.enqueue("steve", StatQueue.PRIORITY_NEW_PLAYER);
            releaseFirst.countDown();

            assertTrue(allSent.await(3, TimeUnit.SECONDS));
            assertEquals(2, sent.size());
            assertEquals("steve", sent.get(1));
        } finally {
            releaseFirst.countDown();
            queue.close();
        }
    }
}
