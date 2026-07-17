package huangdihd.xinbot.playermonitor;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class StatQueue {
    private static final long COMMAND_INTERVAL_MILLIS = 500L;

    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final Set<String> pendingNames = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XinPlayerMonitor-stat-queue");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean draining = new AtomicBoolean();
    private final BooleanSupplier gameActive;
    private final Predicate<String> online;
    private final Consumer<String> sender;
    private final Consumer<String> dispatched;

    StatQueue(BooleanSupplier gameActive, Predicate<String> online, Consumer<String> sender, Consumer<String> dispatched) {
        this.gameActive = gameActive;
        this.online = online;
        this.sender = sender;
        this.dispatched = dispatched;
    }

    void enqueue(String playerName) {
        if (pendingNames.add(playerName)) {
            pending.add(playerName);
        }
        if (draining.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    void clear() {
        pending.clear();
        pendingNames.clear();
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
        pendingNames.remove(playerName);
        if (gameActive.getAsBoolean() && online.test(playerName)) {
            sender.accept("stat " + playerName);
            dispatched.accept(playerName);
        }
        executor.schedule(this::drain, COMMAND_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }
}
