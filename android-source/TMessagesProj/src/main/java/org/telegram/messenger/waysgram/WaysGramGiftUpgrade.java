package org.telegram.messenger.waysgram;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

/**
 * Runs a WaysGram gift upgrade from the native gift screens.
 *
 * <p>The upgrade is a plain REST call against the WaysGram backend; the native Telegram Stars
 * payment flow is never used. A successful upgrade turns the gift into a collectible with the
 * traits the server rolled for it, so the caller is told to reload its inventory and the native
 * card is reopened from the refreshed data. No WaysGram screen of its own is involved.</p>
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

    public static void clearRefreshListener(Runnable listener) {
        if (refreshListener == listener) refreshListener = null;
    }

    public static void upgrade(Context context, int account, String serial) {
        upgrade(context, account, serial, null);
    }

    public interface ResultCallback {
        void onSuccess(JSONObject result);
        void onError(String message);
    }

    public static boolean hasPending(Context context, int account, String serial) {
        return context.getSharedPreferences("waysgram_pending_upgrades_" + account, Context.MODE_PRIVATE)
                .contains(serial);
    }

    public static boolean pendingKeepOriginal(Context context, int account, String serial) {
        return context.getSharedPreferences("waysgram_pending_upgrades_" + account, Context.MODE_PRIVATE)
                .getBoolean(serial + ":keep", false);
    }

    public static void upgrade(Context context, int account, String serial, final Runnable onSuccess) {
        upgrade(context, account, serial, false, new ResultCallback() {
            @Override public void onSuccess(JSONObject result) { if (onSuccess != null) runSafely(onSuccess); }
            @Override public void onError(String message) { toast(context, message); }
        });
    }

    public static void upgrade(
            Context context, int account, String serial, boolean keepOriginal, final ResultCallback callback
    ) {
        if (context == null || TextUtils.isEmpty(serial) || callback == null) {
            return;
        }
        final Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        final WaysGramGiftCatalogClient client = new WaysGramGiftCatalogClient(
                WaysGramSessionStore.getInstance(appContext, account));
        // Keep the transaction key across sheet recreation, network errors and process death.
        // The server stores its response against this key and never rolls or debits twice.
        final SharedPreferences pending = appContext.getSharedPreferences(
                "waysgram_pending_upgrades_" + account, Context.MODE_PRIVATE);
        final String key;
        synchronized (WaysGramGiftUpgrade.class) {
            String existing = pending.getString(serial, "");
            if (TextUtils.isEmpty(existing)) {
                existing = "wg-gift-upgrade-" + UUID.randomUUID();
                if (!pending.edit().putString(serial, existing).putBoolean(serial + ":keep", keepOriginal).commit()) {
                    client.close();
                    callback.onError("Не удалось сохранить запрос на улучшение");
                    return;
                }
            } else if (pending.getBoolean(serial + ":keep", false) != keepOriginal) {
                client.close();
                callback.onError("Повторите запрос с прежним выбором сохранения данных.");
                return;
            }
            key = existing;
        }
        client.upgradeGift(serial, key, keepOriginal, new WaysGramGiftCatalogClient.ActionCallback() {
            @Override
            public void onComplete(JSONObject result) {
                client.close();
                synchronized (WaysGramGiftUpgrade.class) {
                    if (key.equals(pending.getString(serial, ""))) {
                        pending.edit().remove(serial).remove(serial + ":keep").commit();
                    }
                }
                // Telegram confirms its own upgrades by rolling the new model, pattern and
                // backdrop inside the gift card, so the caller only refreshes its data.
                AndroidUtilities.runOnUIThread(() -> {
                    notifyDataChanged();
                    callback.onSuccess(result);
                });
            }

            @Override
            public void onError(String message) {
                client.close();
                String text = TextUtils.isEmpty(message)
                        ? "Не удалось улучшить подарок" : message;
                AndroidUtilities.runOnUIThread(() -> callback.onError(text));
            }
        });
    }

    /** Reloads the WaysGram inventory so every native gift screen shows the new state. */
    public static void notifyDataChanged() {
        Runnable listener = refreshListener;
        if (listener != null) {
            runSafely(listener);
        }
    }

    private static void runSafely(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception exception) {
            FileLog.e(exception);
        }
    }

    private static void toast(Context context, String text) {
        if (context == null || TextUtils.isEmpty(text)) {
            return;
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
}
