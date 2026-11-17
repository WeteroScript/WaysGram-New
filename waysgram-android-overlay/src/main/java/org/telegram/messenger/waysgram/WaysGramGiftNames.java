package org.telegram.messenger.waysgram;

/** Shared display-name rules for WaysGram-owned Telegram gifts. */
public final class WaysGramGiftNames {
    private static final String PLUSH_PEPE_ID = "5936013938331222567";

    private WaysGramGiftNames() {
    }

    public static String displayName(String primary, String fallback, String telegramGiftId) {
        // Stable Telegram gift IDs and catalogue titles are resolved before
        // any user-facing card, inventory row or receipt is rendered.
        if (PLUSH_PEPE_ID.equals(trim(telegramGiftId))) {
            return "Plush Pepe";
        }
        String value = usable(primary);
        if (value.isEmpty()) {
            value = usable(fallback);
        }
        return value.isEmpty() ? "Подарок" : value;
    }

    private static String usable(String value) {
        String title = stripEmoji(trim(value));
        if (title.isEmpty()
                || title.regionMatches(true, 0, "Telegram Gift", 0, "Telegram Gift".length())
                || title.regionMatches(true, 0, "WG-", 0, 3)
                || "Gift".equalsIgnoreCase(title)
                || "Star Gift".equalsIgnoreCase(title)
                || "Подарок Telegram".equalsIgnoreCase(title)
                || !containsLetterOrDigit(title)) {
            return "";
        }
        return title;
    }

    private static String stripEmoji(String value) {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isEmojiCodePoint(codePoint)) {
                continue;
            }
            result.appendCodePoint(codePoint);
        }
        return result.toString().replaceAll("\\s+", " ").trim();
    }

    private static boolean isEmojiCodePoint(int codePoint) {
        return (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0x2300 && codePoint <= 0x23FF)
                || (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF)
                || (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF)
                || codePoint == 0x20E3
                || codePoint == 0xFE0E
                || codePoint == 0xFE0F
                || codePoint == 0x200D;
    }

    private static boolean containsLetterOrDigit(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetterOrDigit(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
