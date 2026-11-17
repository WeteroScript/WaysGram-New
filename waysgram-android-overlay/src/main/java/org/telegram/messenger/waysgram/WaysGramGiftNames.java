package org.telegram.messenger.waysgram;

/** Shared display-name rules for WaysGram-owned Telegram gifts. */
public final class WaysGramGiftNames {
    private static final String PLUSH_PEPE_ID = "5936013938331222567";

    private WaysGramGiftNames() {
    }

    public static String displayName(String primary, String fallback, String telegramGiftId) {
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
        String title = trim(value);
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
