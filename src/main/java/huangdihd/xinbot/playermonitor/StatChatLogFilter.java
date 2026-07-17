package huangdihd.xinbot.playermonitor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

final class StatChatLogFilter extends TurboFilter {
    private static final Pattern SEPARATOR = Pattern.compile("-{10,}");
    private static final List<String> STAT_LABELS = List.of(
            "玩家名称", "加入游戏", "死亡计数", "击杀计数", "游戏时长", "优先队列", "特殊权限",
            "在线次数", "队伍");

    private final BooleanSupplier outputHidden;

    StatChatLogFilter(BooleanSupplier outputHidden) {
        this.outputHidden = outputHidden;
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                              Object[] parameters, Throwable throwable) {
        if (outputHidden.getAsBoolean() && "ChatMessagePrinter".equals(logger.getName()) && isStatOutputLine(format)) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }

    static boolean isStatOutputLine(String message) {
        String line = StatText.normalize(message);
        return SEPARATOR.matcher(line).matches()
                || STAT_LABELS.stream().anyMatch(label -> line.startsWith(label));
    }
}
