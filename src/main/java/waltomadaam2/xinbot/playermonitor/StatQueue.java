package waltomadaam2.xinbot.playermonitor;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

final class StatQueue {
    static final int PRIORITY_ENTRY = 0;
    static final int PRIORITY_JOIN = 10;
    static final int PRIORITY_NEW_PLAYER = 20;
    static final int PRIORITY_MANUAL = 30;

    private final PriorityBlockingQueue<QueuedPlayer> pending = new PriorityBlockingQueue<>(32,
            Comparator.comparingInt(QueuedPlayer::priority).reversed()
                    .thenComparingLong(QueuedPlayer::sequence));
    private final ConcurrentHashMap<String, QueuedPlayer> pendingByKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XinPlayerMonitor-stat-queue");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
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
        return enqueue(playerName, PRIORITY_ENTRY);
    }

    boolean enqueueFirst(String playerName) {
        return enqueue(playerName, PRIORITY_JOIN);
    }

    boolean enqueue(String playerName, int priority) {
        if (closed.get()) {
            return false;
        }
        String key = normalize(playerName);
        AtomicBoolean added = new AtomicBoolean();
        pendingByKey.compute(key, (ignored, existing) -> {
            if (closed.get()) {
                return existing;
            }
            if (existing != null && existing.priority() >= priority) {
                return existing;
            }
            if (existing != null) {
                existing.cancel();
            }
            QueuedPlayer replacement = new QueuedPlayer(key, playerName, priority, sequence.getAndIncrement());
            pending.add(replacement);
            added.set(true);
            return replacement;
        });
        if (added.get()) {
            startDrainIfNeeded();
        }
        return added.get();
    }

    boolean contains(String playerName) {
        return pendingByKey.containsKey(normalize(playerName));
    }

    void cancel(String playerName) {
        QueuedPlayer item = pendingByKey.remove(normalize(playerName));
        if (item != null) {
            item.cancel();
        }
    }

    int size() {
        return pendingByKey.size();
    }

    Set<String> pendingKeysSnapshot() {
        return Set.copyOf(pendingByKey.keySet());
    }

    void clear() {
        for (QueuedPlayer item : pendingByKey.values()) {
            item.cancel();
        }
        pending.clear();
        pendingByKey.clear();
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        clear();
        executor.shutdownNow();
    }

    private void startDrainIfNeeded() {
        if (closed.get() || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException error) {
            draining.set(false);
        }
    }

    private void drain() {
        if (closed.get()) {
            draining.set(false);
            return;
        }
        QueuedPlayer item = nextCurrentItem();
        if (item == null) {
            draining.set(false);
            if (!closed.get() && !pending.isEmpty()) {
                startDrainIfNeeded();
            }
            return;
        }
        try {
            if (gameActive.getAsBoolean() && online.test(item.playerName())) {
                sender.accept("stat " + item.playerName());
                dispatched.accept(item.playerName());
            }
        } catch (RuntimeException error) {
            sendFailed.accept(item.playerName());
        } finally {
            if (closed.get()) {
                draining.set(false);
                return;
            }
            long delay;
            try {
                delay = Math.max(1L, intervalMillis.getAsLong());
            } catch (RuntimeException ignored) {
                delay = 1L;
            }
            try {
                executor.schedule(this::drain, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException error) {
                draining.set(false);
            }
        }
    }

    private QueuedPlayer nextCurrentItem() {
        while (true) {
            QueuedPlayer item = pending.poll();
            if (item == null) {
                return null;
            }
            if (item.cancelled()) {
                continue;
            }
            if (pendingByKey.remove(item.normalizedKey(), item)) {
                return item;
            }
        }
    }

    private static String normalize(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }

    private static final class QueuedPlayer {
        private final String normalizedKey;
        private final String playerName;
        private final int priority;
        private final long sequence;
        private volatile boolean cancelled;

        private QueuedPlayer(String normalizedKey, String playerName, int priority, long sequence) {
            this.normalizedKey = normalizedKey;
            this.playerName = playerName;
            this.priority = priority;
            this.sequence = sequence;
        }

        String normalizedKey() {
            return normalizedKey;
        }

        String playerName() {
            return playerName;
        }

        int priority() {
            return priority;
        }

        long sequence() {
            return sequence;
        }

        boolean cancelled() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
        }
    }
}
