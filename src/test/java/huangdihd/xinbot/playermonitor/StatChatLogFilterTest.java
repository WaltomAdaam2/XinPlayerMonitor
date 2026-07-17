package huangdihd.xinbot.playermonitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatChatLogFilterTest {
    @Test
    void hidesOnlyStatResponseLines() {
        assertTrue(StatChatLogFilter.isStatOutputLine("----------------------"));
        assertTrue(StatChatLogFilter.isStatOutputLine("玩家名称： Xero_CraftStudio"));
        assertTrue(StatChatLogFilter.isStatOutputLine("特殊权限： ✅ | ❌ | ✅"));
        assertFalse(StatChatLogFilter.isStatOutputLine("<Dylan_Galaxy> 消音室要换位了"));
    }
}
