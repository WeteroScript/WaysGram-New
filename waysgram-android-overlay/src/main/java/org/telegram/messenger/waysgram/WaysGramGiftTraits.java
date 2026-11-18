package org.telegram.messenger.waysgram;

import org.json.JSONObject;

/**
 * Reads the collectible traits of a WaysGram gift.
 *
 * <p>Version 3 traits keep the official Telegram pools, so model, pattern and backdrop are
 * objects ({@code {"name": "...", "permille": N, "rarity": "common"}}). Gift rows upgraded by an
 * older backend still carry the plain strings of version 1, which are handled as a fallback.</p>
 */
public final class WaysGramGiftTraits {

    private WaysGramGiftTraits() {
    }

    /** Trait entry as an object, {@code null} for version 1 traits. */
    public static JSONObject entry(JSONObject traits, String key) {
        return traits == null ? null : traits.optJSONObject(key);
    }

    /** Published name of a trait; empty when the gift has no such trait. */
    public static String name(JSONObject traits, String key) {
        JSONObject entry = entry(traits, key);
        if (entry != null) {
            return text(entry.optString("name", ""));
        }
        String legacy = text(traits == null ? "" : traits.optString(key, ""));
        if (legacy.isEmpty() && "pattern".equals(key)) {
            legacy = text(traits == null ? "" : traits.optString("symbol", ""));
        }
        return legacy;
    }

    /** Rarity class of one attribute, falling back to the rarity of the whole card. */
    public static String rarityKey(JSONObject traits, String key) {
        JSONObject entry = entry(traits, key);
        String rarity = entry == null ? "" : text(entry.optString("rarity", ""));
        return rarity.isEmpty() ? text(traits == null ? "" : traits.optString("rarity", "")) : rarity;
    }

    /** Russian rarity label shown in the WaysGram dialogs. */
    public static String rarityLabel(JSONObject traits) {
        return rarityLabel(traits == null ? "" : text(traits.optString("rarity", "")));
    }

    public static String rarityLabel(String rarityKey) {
        String rarity = rarityKey == null ? "" : rarityKey.trim().toLowerCase(java.util.Locale.ROOT);
        if (rarity.equals("uncommon") || rarity.equals("необычная")) {
            return "Необычная";
        }
        if (rarity.equals("rare") || rarity.equals("редкая")) {
            return "Редкая";
        }
        if (rarity.equals("epic") || rarity.equals("эпическая")) {
            return "Эпическая";
        }
        if (rarity.equals("legendary") || rarity.equals("легендарная")) {
            return "Легендарная";
        }
        return "Обычная";
    }

    /** Appends the trait lines (rarity, model, pattern, backdrop) to a details message. */
    public static void appendDetails(StringBuilder target, JSONObject traits) {
        if (target == null || traits == null) {
            return;
        }
        target.append("\nРедкость: ").append(rarityLabel(traits));
        String model = name(traits, "model");
        if (!model.isEmpty()) {
            target.append("\nМодель: ").append(model);
        }
        String pattern = name(traits, "pattern");
        if (!pattern.isEmpty()) {
            target.append("\nУзор: ").append(pattern);
        }
        String backdrop = name(traits, "backdrop");
        if (!backdrop.isEmpty()) {
            target.append("\nФон: ").append(backdrop);
        }
    }

    private static String text(String value) {
        String trimmed = value == null ? "" : value.trim();
        return "null".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }
}
