package org.telegram.messenger.waysgram;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot "unfold" reveal for collectible (unique) gifts.
 *
 * <p>This Telegram base has no unfold animation of its own, so the collectible
 * card is unfolded the way the original client presents it: the card starts
 * turned away from the viewer and flattens while it grows into place. The
 * animation plays once per gift instance — {@code TopView.setGift} runs again on
 * every bind, and replaying the unfold there would flicker.</p>
 */
public final class WaysGramGiftUnfold {

    private static final Set<String> PLAYED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private WaysGramGiftUnfold() {
    }

    public static void playOnce(final String key, final View view) {
        if (view == null || key == null) {
            return;
        }
        if (PLAYED.size() > 512) {
            PLAYED.clear();
        }
        if (!PLAYED.add(key)) {
            return;
        }
        view.post(() -> play(view));
    }

    private static void play(View view) {
        if (view == null) {
            return;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            view.postDelayed(() -> play(view), 32L);
            return;
        }
        view.setCameraDistance(Math.max(width, height) * 6f);
        view.setPivotX(width / 2f);
        view.setPivotY(height / 2f);
        view.setRotationY(46f);
        view.setRotationX(-10f);
        view.setScaleX(0.78f);
        view.setScaleY(0.78f);
        view.setAlpha(0f);
        view.animate()
                .rotationY(0f)
                .rotationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(520L)
                .setInterpolator(new DecelerateInterpolator(1.7f))
                .start();
    }
}
