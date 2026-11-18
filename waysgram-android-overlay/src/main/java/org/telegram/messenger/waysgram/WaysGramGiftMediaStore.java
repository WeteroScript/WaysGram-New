package org.telegram.messenger.waysgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded memory cache backed by app-cache files for WaysGram gift artwork. */
public final class WaysGramGiftMediaStore {
    private static final int MAX_ITEMS = 200;
    private static final int MAX_METADATA_ITEMS = 1000;
    private static final LinkedHashMap<String, Bitmap> CACHE =
            new LinkedHashMap<String, Bitmap>(MAX_ITEMS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_ITEMS;
                }
            };
    private static final LinkedHashMap<String, TLRPC.TL_document> ANIMATED_DOCUMENTS =
            new LinkedHashMap<String, TLRPC.TL_document>(MAX_ITEMS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TLRPC.TL_document> eldest) {
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
    private static File cacheDirectory;

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

    public static synchronized void configure(Context context) {
        if (context == null) {
            return;
        }
        File directory = new File(context.getApplicationContext().getCacheDir(), "waysgram_gift_media");
        if (!directory.exists() && !directory.mkdirs()) {
            return;
        }
        cacheDirectory = directory;
    }

    public static synchronized void put(String serial, Bitmap bitmap) {
        if (serial == null || serial.isEmpty() || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        CACHE.put(serial, bitmap);
        File file = fileFor(serial);
        if (file == null) {
            return;
        }
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                return;
            }
            output.flush();
            if (file.exists() && !file.delete()) {
                temporary.delete();
                return;
            }
            if (!temporary.renameTo(file)) {
                temporary.delete();
            }
        } catch (Exception exception) {
            temporary.delete();
        }
    }

    public static synchronized Bitmap get(String serial) {
        if (serial == null || serial.isEmpty()) {
            return null;
        }
        Bitmap bitmap = CACHE.get(serial);
        if (bitmap != null && !bitmap.isRecycled()) {
            return bitmap;
        }
        File file = fileFor(serial);
        if (file == null || !file.isFile()) {
            return null;
        }
        Bitmap restored = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (restored == null || restored.isRecycled()) {
            return null;
        }
        CACHE.put(serial, restored);
        return restored;
    }

    /**
     * Remembers the Telegram sticker document of an animated gift so the native
     * gift cells can play TGS/WebM artwork instead of a single still frame.
     */
    public static synchronized void putAnimationDocument(String serial, TLRPC.TL_document document) {
        if (serial == null || serial.isEmpty() || document == null) {
            return;
        }
        ANIMATED_DOCUMENTS.put(serial, document);
    }

    public static synchronized TLRPC.TL_document getAnimationDocument(String serial) {
        return serial == null ? null : ANIMATED_DOCUMENTS.get(serial);
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

    /** Clears volatile objects only; persistent sticker files survive inventory refreshes. */
    public static synchronized void clear() {
        CACHE.clear();
        METADATA.clear();
        ANIMATED_DOCUMENTS.clear();
    }

    private static File fileFor(String serial) {
        if (cacheDirectory == null || serial == null || serial.isEmpty()) {
            return null;
        }
        String safe = serial.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(cacheDirectory, safe + ".png");
    }
}
