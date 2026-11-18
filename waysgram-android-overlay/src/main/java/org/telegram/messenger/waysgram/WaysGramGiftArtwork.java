package org.telegram.messenger.waysgram;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.ImageLocation;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BackupImageView;

/**
 * Applies WaysGram gift artwork to Telegram's native gift cells.
 *
 * <p>Animated gifts (TGS/WebM) are handed to Telegram's own image loader as a
 * sticker document, so the cell plays the real animation. Gifts without an
 * animation keep using the cached still frame.</p>
 */
public final class WaysGramGiftArtwork {

    private WaysGramGiftArtwork() {
    }

    /**
     * Remembers which animation a cell already plays. Re-applying the same document restarts
     * the player, which the user sees as "plays a moment, freezes, then continues".
     */
    private static final java.util.Map<BackupImageView, Long> APPLIED_DOCUMENTS =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<BackupImageView, Long>());

    public static void apply(BackupImageView imageView, String serial) {
        if (imageView == null || serial == null || serial.isEmpty()) {
            return;
        }
        Bitmap bitmap = WaysGramGiftMediaStore.get(serial);
        Drawable thumb = bitmap != null && !bitmap.isRecycled()
                ? new BitmapDrawable(imageView.getResources(), bitmap)
                : null;
        TLRPC.TL_document document = WaysGramGiftMediaStore.getAnimationDocument(serial);
        if (document != null) {
            Long applied = APPLIED_DOCUMENTS.get(imageView);
            if (applied != null && applied == document.id) {
                // Already playing this exact artwork on this cell - leave it running.
                return;
            }
            ImageLocation location = ImageLocation.getForDocument(document);
            if (location != null) {
                imageView.getImageReceiver().setImage(location, null, thumb, null, null, 0);
                APPLIED_DOCUMENTS.put(imageView, document.id);
                imageView.invalidate();
                return;
            }
        }
        if (thumb != null) {
            APPLIED_DOCUMENTS.remove(imageView);
            imageView.getImageReceiver().setImageBitmap(bitmap);
            imageView.invalidate();
        }
    }

    /** True when the gift has playable artwork available. */
    public static boolean isAnimated(String serial) {
        return serial != null && WaysGramGiftMediaStore.getAnimationDocument(serial) != null;
    }

    /** True when a still frame or an animation can be drawn for this gift. */
    public static boolean hasArtwork(String serial) {
        if (serial == null || serial.isEmpty()) {
            return false;
        }
        if (WaysGramGiftMediaStore.getAnimationDocument(serial) != null) {
            return true;
        }
        Bitmap bitmap = WaysGramGiftMediaStore.get(serial);
        return bitmap != null && !bitmap.isRecycled();
    }
}
