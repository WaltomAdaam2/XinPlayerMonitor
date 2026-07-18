package huangdihd.xinbot.playermonitor;

import huangdihd.xinbot.playermonitor.model.LoginSession;
import huangdihd.xinbot.playermonitor.model.PlayerRecord;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PublicPlayerQueryResponder {
    private static final long COOLDOWN_MILLIS = 60_000L;
    private static final int BYPASS_SUFFIX_LENGTH = 3;
    private static final String BYPASS_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern QUERY = Pattern.compile("^!player\\s+(\\S+)(?:\\s+.*)?$", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final PlayerMonitorService service;
    private final Consumer<String> sender;
    private final PluginLog log;
    private final LongSupplier clock;
    private final AtomicLong cooldownUntil = new AtomicLong();

    PublicPlayerQueryResponder(PlayerMonitorService service, Consumer<String> sender, PluginLog log) {
        this(service, sender, log, System::currentTimeMillis);
    }

    PublicPlayerQueryResponder(PlayerMonitorService service, Consumer<String> sender, PluginLog log, LongSupplier clock) {
        this.service = service;
        this.sender = sender;
        this.log = log;
        this.clock = clock;
    }

    void handle(String message) {
        String normalized = message == null ? "" : message.trim();
        Matcher matcher = QUERY.matcher(normalized);
        if (!matcher.matches() || !claimCooldown()) {
            return;
        }
        String playerName = matcher.group(1);
        try {
            Optional<PlayerRecord> record = service.findRecord(playerName);
            sender.accept(withBypassSuffix(record.map(this::reply).orElseGet(() -> "未找到玩家 " + playerName + " 的记录。")));
            log.info("queued public player query reply for " + playerName);
        } catch (IOException error) {
            sender.accept(withBypassSuffix("查询玩家 " + playerName + " 的记录失败。" ));
            log.info("failed public player query for " + playerName + ": " + error.getMessage());
        }
    }

    private static String withBypassSuffix(String reply) {
        StringBuilder suffix = new StringBuilder(BYPASS_SUFFIX_LENGTH);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < BYPASS_SUFFIX_LENGTH; index++) {
            suffix.append(BYPASS_ALPHABET.charAt(random.nextInt(BYPASS_ALPHABET.length())));
        }
        return reply + " " + suffix;
    }
    private boolean claimCooldown() {
        long now = clock.getAsLong();
        while (true) {
            long current = cooldownUntil.get();
            if (now < current) {
                return false;
            }
            if (cooldownUntil.compareAndSet(current, now + COOLDOWN_MILLIS)) {
                return true;
            }
        }
    }

    private String reply(PlayerRecord record) {
        if (record.loginSessions.isEmpty()) {
            return "玩家 " + record.playerName + " 暂无登录记录。";
        }
        LoginSession session = service.latestLogin(record).orElseThrow();
        long end = session.logoutAt == null ? clock.getAsLong() : session.logoutAt;
        String result = "玩家 " + record.playerName + "：最近登录 " + TIME.format(Instant.ofEpochMilli(session.loginAt))
                + "，游玩时长 " + duration(Duration.ofMillis(Math.max(0L, end - session.loginAt)).getSeconds());
        return session.logoutAt == null ? result : result + "，登出时间 " + TIME.format(Instant.ofEpochMilli(session.logoutAt));
    }

    private static String duration(long seconds) {
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainingSeconds = seconds % 60L;
        return hours + "小时" + minutes + "分" + remainingSeconds + "秒";
    }
}
