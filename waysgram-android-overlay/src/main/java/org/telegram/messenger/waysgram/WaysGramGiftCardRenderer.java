package org.telegram.messenger.waysgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/** Shared Telegram-gift preview used by profile, inventory summaries and DM gift cards. */
public final class WaysGramGiftCardRenderer {
    private WaysGramGiftCardRenderer() {
    }

    public static void addPreview(
            Context context,
            LinearLayout parent,
            WaysGramApiClient apiClient,
            String telegramGiftId,
            String emoji
    ) {
        if (context == null || parent == null) {
            return;
        }

        FrameLayout frame = new FrameLayout(context);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(112), AndroidUtilities.dp(112)
        );
        frameParams.bottomMargin = AndroidUtilities.dp(8);
        parent.addView(frame, frameParams);

        TextView placeholder = new TextView(context);
        placeholder.setText(emoji == null || emoji.trim().isEmpty() ? "🎁" : emoji.trim());
        placeholder.setTextSize(40);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        frame.addView(placeholder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        frame.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        String giftId = telegramGiftId == null ? "" : telegramGiftId.trim();
        if (apiClient == null || !giftId.matches("[A-Za-z0-9_-]{1,120}")) {
            return;
        }

        apiClient.downloadTelegramGiftSticker(giftId, new WaysGramApiClient.ImageCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if (bitmap == null || bitmap.isRecycled() || frame.getParent() == null) {
                    return;
                }
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
                placeholder.setVisibility(View.GONE);
            }

            @Override
            public void onError(String message, int statusCode) {
                // The Telegram emoji remains visible when the sticker is animated or unavailable.
            }
        });
    }
}
