package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatChatLogFilterTest {
    @Test
    void recognizesColoredStatResponseLines() {
        assertTrue(StatChatLogFilter.isStatOutputLine("§b玩家名称: Xero_CraftStudio"));
        assertTrue(StatChatLogFilter.isStatOutputLine("\u001B[36m§b特殊权限: ✅ | ✅ | ✅\u001B[0m"));
        assertTrue(StatChatLogFilter.isStatOutputLine("----------------------"));
        assertFalse(StatChatLogFilter.isStatOutputLine("<Dylan_Galaxy> 消音室要换位了"));
    }
}
