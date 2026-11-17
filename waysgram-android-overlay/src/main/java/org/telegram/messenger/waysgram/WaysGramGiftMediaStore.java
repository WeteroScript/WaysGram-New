package org.telegram.messenger.waysgram;

import android.graphics.Bitmap;

import java.io.File;

import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory sticker and metadata cache for WaysGram gifts rendered by Telegram GiftCell. */
public final class WaysGramGiftMediaStore {
    private static final int MAX_ITEMS = 200;
    private static final int MAX_METADATA_ITEMS = 200;
    private static final LinkedHashMap<String, Bitmap> CACHE =
            new LinkedHashMap<String, Bitmap>(MAX_ITEMS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_ITEMS;
                }
            };
    private static final LinkedHashMap<String, File> ANIMATION_FILES =
            new LinkedHashMap<String, File>(MAX_ITEMS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, File> eldest) {
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
        public final String emoji;

        private GiftMetadata(String title, String subtitle, String emoji) {
            this.title = title;
            this.subtitle = subtitle;
            this.emoji = emoji == null ? "" : emoji.trim();
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

    public static synchronized void putAnimationFile(String serial, File file) {
        if (serial == null || serial.isEmpty() || file == null || !file.isFile() || file.length() <= 0) {
            return;
        }
        ANIMATION_FILES.put(serial, file);
        CACHE.remove(serial);
    }

    public static synchronized File getAnimationFile(String serial) {
        if (serial == null || serial.isEmpty()) {
            return null;
        }
        File file = ANIMATION_FILES.get(serial);
        if (file == null || !file.isFile() || file.length() <= 0) {
            ANIMATION_FILES.remove(serial);
            return null;
        }
        return file;
    }

    public static synchronized void putMetadata(String serial, String title, String subtitle, String emoji) {
        if (serial == null || serial.isEmpty()) {
            return;
        }
        String safeTitle = title == null || title.trim().isEmpty() ? "Подарок" : title.trim();
        String safeSubtitle = subtitle == null ? "" : subtitle.trim();
        METADATA.put(serial, new GiftMetadata(safeTitle, safeSubtitle, emoji));
    }

    public static synchronized GiftMetadata getMetadata(String serial) {
        return serial == null ? null : METADATA.get(serial);
    }

    public static synchronized void clear() {
        CACHE.clear();
        ANIMATION_FILES.clear();
        METADATA.clear();
    }
}
