package org.telegram.messenger.waysgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFileDrawable;

/** Shared artwork renderer for WaysGram-owned Telegram gift cards. */
public final class WaysGramGiftCardRenderer {
    private WaysGramGiftCardRenderer() {
    }

    public static void addPreview(
            Context context,
            LinearLayout parent,
            WaysGramApiClient apiClient,
            String telegramGiftId,
            String ignoredEmoji
    ) {
        addPreview(context, parent, apiClient, telegramGiftId, ignoredEmoji, false, 0);
    }

    public static void addPreview(
            Context context,
            LinearLayout parent,
            WaysGramApiClient apiClient,
            String telegramGiftId,
            String ignoredEmoji,
            boolean animated,
            int telegramAccount
    ) {
        if (context == null || parent == null) {
            return;
        }

        FrameLayout frame = new FrameLayout(context);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        background.setCornerRadius(AndroidUtilities.dp(12));
        frame.setBackground(background);
        frame.setClipToOutline(true);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(112), AndroidUtilities.dp(112)
        );
        frameParams.gravity = Gravity.CENTER_HORIZONTAL;
        frameParams.bottomMargin = AndroidUtilities.dp(8);
        parent.addView(frame, frameParams);

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        String giftId = telegramGiftId == null ? "" : telegramGiftId.trim();
        if (apiClient == null || !giftId.matches("[A-Za-z0-9_-]{1,120}")) {
            return;
        }
        if (animated) {
            downloadAnimated(apiClient, giftId, image, frame, telegramAccount, true);
        } else {
            downloadStatic(apiClient, giftId, image, frame, telegramAccount, true);
        }
    }

    private static void downloadStatic(
            WaysGramApiClient apiClient,
            String giftId,
            ImageView image,
            FrameLayout frame,
            int telegramAccount,
            boolean tryAnimationOnFailure
    ) {
        apiClient.downloadTelegramGiftSticker(giftId, new WaysGramApiClient.ImageCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if (bitmap == null || bitmap.isRecycled() || frame.getParent() == null) {
                    if (tryAnimationOnFailure) {
                        downloadAnimated(apiClient, giftId, image, frame, telegramAccount, false);
                    }
                    return;
                }
                image.setImageBitmap(bitmap);
            }

            @Override
            public void onError(String message, int statusCode) {
                if (tryAnimationOnFailure) {
                    downloadAnimated(apiClient, giftId, image, frame, telegramAccount, false);
                }
            }
        });
    }

    private static void downloadAnimated(
            WaysGramApiClient apiClient,
            String giftId,
            ImageView image,
            FrameLayout frame,
            int telegramAccount,
            boolean tryStaticOnFailure
    ) {
        apiClient.downloadTelegramGiftStickerAnimation(
                giftId, frame.getContext().getCacheDir(), new WaysGramApiClient.FileCallback() {
                    @Override
                    public void onSuccess(java.io.File file) {
                        if (file == null || !file.isFile() || frame.getParent() == null) {
                            if (tryStaticOnFailure) {
                                downloadStatic(apiClient, giftId, image, frame, telegramAccount, false);
                            }
                            return;
                        }
                        AnimatedFileDrawable drawable = new AnimatedFileDrawable(
                                file, true, 0, 0, null, null, null, 0,
                                telegramAccount, true, 64, 64, null
                        );
                        image.setImageDrawable(drawable);
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        if (tryStaticOnFailure) {
                            downloadStatic(apiClient, giftId, image, frame, telegramAccount, false);
                        }
                    }
                }
        );
    }
}
