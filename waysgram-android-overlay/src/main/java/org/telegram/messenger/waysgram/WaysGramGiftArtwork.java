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
            ImageLocation location = ImageLocation.getForDocument(document);
            if (location != null) {
                imageView.getImageReceiver().setImage(location, null, thumb, null, null, 0);
                imageView.invalidate();
                return;
            }
        }
        if (thumb != null) {
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
