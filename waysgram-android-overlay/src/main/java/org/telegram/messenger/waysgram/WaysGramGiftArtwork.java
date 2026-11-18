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
                // Gift artwork loops for as long as its card is on screen: playback is allowed
                // explicitly so a finished sticker starts over instead of freezing on the last
                // frame while the sheet stays open.
                imageView.getImageReceiver().setAllowStartAnimation(true);
                imageView.getImageReceiver().startAnimation(true);
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

    /**
     * Shows only the cached still frame.
     *
     * <p>The upgrade reveal uses it while its reels spin: decoding an animated sticker on the UI
     * thread at the same time as the reveal animation is what made the animation stutter.</p>
     */
    public static void applyStill(BackupImageView imageView, String serial) {
        if (imageView == null || serial == null || serial.isEmpty()) {
            return;
        }
        APPLIED_DOCUMENTS.remove(imageView);
        Bitmap bitmap = WaysGramGiftMediaStore.get(serial);
        if (bitmap != null && !bitmap.isRecycled()) {
            imageView.getImageReceiver().setImageBitmap(bitmap);
            imageView.invalidate();
        }
    }

    /** Starts (or restarts) the artwork animation and keeps it looping. */
    public static void play(BackupImageView imageView) {
        if (imageView == null) {
            return;
        }
        imageView.getImageReceiver().setAllowStartAnimation(true);
        imageView.getImageReceiver().startAnimation(true);
    }

    /** Stops the animation and drops the cached binding of this cell. */
    public static void stop(BackupImageView imageView) {
        if (imageView == null) {
            return;
        }
        imageView.getImageReceiver().stopAnimation();
        APPLIED_DOCUMENTS.remove(imageView);
    }

    /**
     * Plays one official Telegram attribute animation (a model or a pattern) on a plain
     * sticker view. The document is served by the WaysGram transport from the shared gift
     * sticker folder, so Telegram decodes and loops the real animation.
     */
    public static void applyDocument(BackupImageView imageView, TLRPC.TL_document document) {
        if (imageView == null || document == null) {
            return;
        }
        Long applied = APPLIED_DOCUMENTS.get(imageView);
        if (applied != null && applied == document.id) {
            imageView.getImageReceiver().setAllowStartAnimation(true);
            imageView.getImageReceiver().startAnimation(true);
            return;
        }
        ImageLocation location = ImageLocation.getForDocument(document);
        if (location == null) {
            return;
        }
        imageView.getImageReceiver().setImage(location, null, null, null, null, 0);
        imageView.getImageReceiver().setAllowStartAnimation(true);
        imageView.getImageReceiver().startAnimation(true);
        APPLIED_DOCUMENTS.put(imageView, document.id);
        imageView.invalidate();
    }

    /** Removes the animation from a view that showed an attribute overlay. */
    public static void clear(BackupImageView imageView) {
        if (imageView == null) {
            return;
        }
        APPLIED_DOCUMENTS.remove(imageView);
        imageView.getImageReceiver().stopAnimation();
        imageView.getImageReceiver().setImageBitmap(null);
        imageView.invalidate();
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
