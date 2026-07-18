package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmojiFilterTest {
    @Test
    void keepsOnlyCheckMarkEmoji() {
        assertEquals("你好 ✅ 世界", EmojiFilter.removeEmojiExceptCheckMarks("你好 😀 ✅ 🧨 世界"));
    }
}
