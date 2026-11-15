package org.telegram.messenger.waysgram;

import android.graphics.Bitmap;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small in-memory cache for WaysGram sticker bitmaps used by Telegram GiftCell. */
public final class WaysGramGiftMediaStore {
    private static final int MAX_ITEMS = 32;
    private static final LinkedHashMap<String, Bitmap> CACHE =
            new LinkedHashMap<String, Bitmap>(MAX_ITEMS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_ITEMS;
                }
            };

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

    public static synchronized void clear() {
        CACHE.clear();
    }
}
