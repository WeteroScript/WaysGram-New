package org.telegram.messenger.waysgram;

import android.content.Context;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

/**
 * Runs a WaysGram gift upgrade from the native gift screens.
 *
 * <p>The upgrade is a plain REST call against the WaysGram backend; the native Telegram Stars
 * payment flow is never used. A successful upgrade changes the gift into a collectible with the
 * traits the server rolled for it, so the caller is told to reload its inventory.</p>
 */
public final class WaysGramGiftUpgrade {

    /** Price shown to the user until the server reports its own amount. */
    public static final int DEFAULT_PRICE_STARS = 25;

    private static volatile Runnable refreshListener;

    private WaysGramGiftUpgrade() {
    }

    /** Registered by the profile gift controller so an upgrade reloads the inventory. */
    public static void setRefreshListener(Runnable listener) {
        refreshListener = listener;
    }

    public static void upgrade(Context context, int account, String serial) {
        upgrade(context, account, serial, null);
    }

    public static void upgrade(
            Context context, int account, String serial, final Runnable onSuccess
    ) {
        if (context == null || TextUtils.isEmpty(serial)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final WaysGramGiftCatalogClient client = new WaysGramGiftCatalogClient(
                WaysGramSessionStore.getInstance(appContext, account));
        client.upgradeGift(serial, new WaysGramGiftCatalogClient.ActionCallback() {
            @Override
            public void onComplete(JSONObject result) {
                client.close();
                AndroidUtilities.runOnUIThread(() -> {
                    toast(appContext, "Подарок улучшен");
                    if (onSuccess != null) {
                        try {
                            onSuccess.run();
                        } catch (Exception exception) {
                            FileLog.e(exception);
                        }
                    }
                    Runnable listener = refreshListener;
                    if (listener != null) {
                        try {
                            listener.run();
                        } catch (Exception exception) {
                            FileLog.e(exception);
                        }
                    }
                });
            }

            @Override
            public void onError(String message) {
                client.close();
                String text = TextUtils.isEmpty(message)
                        ? "Не удалось улучшить подарок" : message;
                AndroidUtilities.runOnUIThread(() -> toast(appContext, text));
            }
        });
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            FileLog.e(exception);
        }
    }
}
