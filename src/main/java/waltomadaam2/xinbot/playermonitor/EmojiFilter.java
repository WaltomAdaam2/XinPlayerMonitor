package waltomadaam2.xinbot.playermonitor;

final class EmojiFilter {
    private static final int CHECK_MARK = 0x2705;

    private EmojiFilter() {
    }

    static String removeEmojiExceptCheckMarks(String message) {
        StringBuilder filtered = new StringBuilder();
        message.codePoints().forEach(codePoint -> {
            if (codePoint == CHECK_MARK || !isEmoji(codePoint)) {
                filtered.appendCodePoint(codePoint);
            }
        });
        return filtered.toString().trim().replaceAll("\\s+", " ");
    }

    private static boolean isEmoji(int codePoint) {
        return codePoint == 0x200D
                || codePoint == 0xFE0F
                || codePoint == 0xFE0E
                || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0x2300 && codePoint <= 0x23FF);
    }
}
