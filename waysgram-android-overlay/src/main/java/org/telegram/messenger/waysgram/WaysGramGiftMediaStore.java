package org.telegram.messenger.waysgram;

import android.graphics.Bitmap;

import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory sticker and metadata cache for WaysGram gifts rendered by Telegram GiftCell. */
public final class WaysGramGiftMediaStore {
    private static final int MAX_ITEMS = 32;
    private static final int MAX_METADATA_ITEMS = 200;
    private static final LinkedHashMap<String, Bitmap> CACHE =
            new LinkedHashMap<String, Bitmap>(MAX_ITEMS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_ITEMS;
                }
            };
    private static final LinkedHashMap<String, GiftMetadata> METADATA =
            new LinkedHashMap<String, GiftMetadata>(MAX_METADATA_ITEMS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, GiftMetadata> eldest) {
                    return size() > MAX_METADATA_ITEMS;
                }
            };

    public static final class GiftMetadata {
        public final String title;
        public final String subtitle;

        private GiftMetadata(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private WaysGramGiftMediaStore() {
    }

    public static synchronized void put(String serial, Bitmap bitmap) {
        if (serial == null || serial.isEmpty() || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        CACHE.put(serial, bitmap);
    }

    public static synchronized Bitmap get(String serial) {
        Bitmap bitmap = CACHE.get(serial);
        return bitmap == null || bitmap.isRecycled() ? null : bitmap;
    }

    public static synchronized void putMetadata(String serial, String title, String subtitle) {
        if (serial == null || serial.isEmpty()) {
            return;
        }
        String safeTitle = title == null || title.trim().isEmpty() ? "Gift" : title.trim();
        String safeSubtitle = subtitle == null ? "" : subtitle.trim();
        METADATA.put(serial, new GiftMetadata(safeTitle, safeSubtitle));
    }

    public static synchronized GiftMetadata getMetadata(String serial) {
        return serial == null ? null : METADATA.get(serial);
    }

    public static synchronized void clear() {
        CACHE.clear();
        METADATA.clear();
    }
}
