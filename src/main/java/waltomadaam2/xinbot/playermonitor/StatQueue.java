package waltomadaam2.xinbot.playermonitor;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

final class StatQueue {
    private final ConcurrentLinkedDeque<String> pending = new ConcurrentLinkedDeque<>();
    private final Set<String> pendingKeys = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XinPlayerMonitor-stat-queue");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean draining = new AtomicBoolean();
    private final BooleanSupplier gameActive;
    private final Predicate<String> online;
    private final LongSupplier intervalMillis;
    private final Consumer<String> sender;
    private final Consumer<String> dispatched;
    private final Consumer<String> sendFailed;

    StatQueue(BooleanSupplier gameActive, Predicate<String> online, LongSupplier intervalMillis,
              Consumer<String> sender, Consumer<String> dispatched, Consumer<String> sendFailed) {
        this.gameActive = gameActive;
        this.online = online;
        this.intervalMillis = intervalMillis;
        this.sender = sender;
        this.dispatched = dispatched;
        this.sendFailed = sendFailed;
    }

    boolean enqueue(String playerName) {
        boolean added = pendingKeys.add(normalize(playerName));
        if (added) {
            pending.addLast(playerName);
        }
        if (draining.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
        return added;
    }

    boolean enqueueFirst(String playerName) {
        boolean added = pendingKeys.add(normalize(playerName));
        if (added) {
            pending.addFirst(playerName);
        }
        if (draining.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
        return added;
    }

    void clear() {
        pending.clear();
        pendingKeys.clear();
    }

    void close() {
        clear();
        executor.shutdownNow();
    }

    private void drain() {
        String playerName = pending.poll();
        if (playerName == null) {
            draining.set(false);
            if (!pending.isEmpty() && draining.compareAndSet(false, true)) {
                executor.execute(this::drain);
            }
            return;
        }
        pendingKeys.remove(normalize(playerName));
        try {
            if (gameActive.getAsBoolean() && online.test(playerName)) {
                sender.accept("stat " + playerName);
                dispatched.accept(playerName);
            }
        } catch (RuntimeException error) {
            sendFailed.accept(playerName);
        } finally {
            executor.schedule(this::drain, Math.max(1L, intervalMillis.getAsLong()), TimeUnit.MILLISECONDS);
        }
    }

    private static String normalize(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }
}
