package waltomadaam2.xinbot.playermonitor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

final class StatChatLogFilter extends TurboFilter {
    private static final Pattern SEPARATOR = Pattern.compile("-{10,}");
    private static final List<Pattern> STAT_LINES = List.of(
            Pattern.compile("^玩家名称\\s*[:：].*$"),
            Pattern.compile("^加入游戏\\s*[:：].*$"),
            Pattern.compile("^死亡计数\\s*[:：].*$"),
            Pattern.compile("^击杀计数\\s*[:：].*$"),
            Pattern.compile("^游戏时长\\s*[:：].*$"),
            Pattern.compile("^优先队列\\s*[:：].*$"),
            Pattern.compile("^特殊权限\\s*[:：].*$"),
            Pattern.compile("^在线次数\\s*[:：].*$"),
            Pattern.compile("^队伍\\s*[:：].*$"));

    private final BooleanSupplier outputHidden;
    private final BooleanSupplier statCaptureActive;

    StatChatLogFilter(BooleanSupplier outputHidden) {
        this(outputHidden, () -> true);
    }

    StatChatLogFilter(BooleanSupplier outputHidden, BooleanSupplier statCaptureActive) {
        this.outputHidden = outputHidden;
        this.statCaptureActive = statCaptureActive;
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                              Object[] parameters, Throwable throwable) {
        if (!outputHidden.getAsBoolean() || !statCaptureActive.getAsBoolean()
                || logger == null || !"ChatMessagePrinter".equals(logger.getName())) {
            return FilterReply.NEUTRAL;
        }
        String rendered = render(format, parameters);
        return isStatOutputLine(rendered) ? FilterReply.DENY : FilterReply.NEUTRAL;
    }

    static String render(String format, Object[] parameters) {
        if (format == null) {
            return "";
        }
        if (parameters == null || parameters.length == 0) {
            return format;
        }
        return MessageFormatter.arrayFormat(format, parameters).getMessage();
    }

    static boolean isStatOutputLine(String message) {
        String line = StatText.normalize(message);
        return SEPARATOR.matcher(line).matches()
                || STAT_LINES.stream().anyMatch(pattern -> pattern.matcher(line).matches());
    }
}
