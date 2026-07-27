package waltomadaam2.xinbot.playermonitor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatChatLogFilterTest {
    @Test
    void recognizesColoredStatResponseLinesWithoutMatchingSimilarChat() {
        assertTrue(StatChatLogFilter.isStatOutputLine("§b玩家名称: Xero_CraftStudio"));
        assertTrue(StatChatLogFilter.isStatOutputLine("\u001B[36m§b特殊权限: ✅ | ✅ | ✅\u001B[0m"));
        assertTrue(StatChatLogFilter.isStatOutputLine("----------------------"));
        assertFalse(StatChatLogFilter.isStatOutputLine("玩家名称只是普通聊天内容"));
        assertFalse(StatChatLogFilter.isStatOutputLine("<Dylan_Galaxy> 玩家名称: 这不是 Stat 输出"));
    }

    @Test
    void rendersParameterizedSlf4jMessagesBeforeClassification() {
        assertEquals("玩家名称: Alice", StatChatLogFilter.render("{}", new Object[]{"玩家名称: Alice"}));
        assertEquals("游戏时长: 10秒", StatChatLogFilter.render("{}: {}", new Object[]{"游戏时长", "10秒"}));
    }

    @Test
    void hidesOnlyWhileStatCaptureIsActive() {
        LoggerContext context = new LoggerContext();
        ch.qos.logback.classic.Logger logger = context.getLogger("ChatMessagePrinter");

        StatChatLogFilter inactive = new StatChatLogFilter(() -> true, () -> false);
        assertEquals(FilterReply.NEUTRAL,
                inactive.decide(null, logger, Level.INFO, "{}", new Object[]{"玩家名称: Alice"}, null));

        StatChatLogFilter active = new StatChatLogFilter(() -> true, () -> true);
        assertEquals(FilterReply.DENY,
                active.decide(null, logger, Level.INFO, "{}", new Object[]{"玩家名称: Alice"}, null));
        assertEquals(FilterReply.NEUTRAL,
                active.decide(null, logger, Level.INFO, "{}", new Object[]{"玩家名称只是普通聊天"}, null));
    }
}
