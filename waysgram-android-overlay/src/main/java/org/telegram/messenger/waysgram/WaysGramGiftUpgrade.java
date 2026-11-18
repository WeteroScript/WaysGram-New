package org.telegram.messenger.waysgram;

import android.content.Context;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONArray;
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
        final Context uiContext = context;
        final Context appContext = context.getApplicationContext();
        final WaysGramGiftCatalogClient client = new WaysGramGiftCatalogClient(
                WaysGramSessionStore.getInstance(appContext, account));
        client.upgradeGift(serial, new WaysGramGiftCatalogClient.ActionCallback() {
            @Override
            public void onComplete(JSONObject result) {
                client.close();
                // The upgrade is confirmed by the reveal itself (reels of model, pattern and
                // backdrop plus the new collection number); no toast is shown.
                AndroidUtilities.runOnUIThread(() -> WaysGramGiftUpgradeReveal.show(
                        uiContext, serial, result, new WaysGramGiftUpgradeReveal.Listener() {
                            @Override
                            public void onDone() {
                                afterUpgrade(onSuccess);
                            }

                            @Override
                            public void onNextGift() {
                                afterUpgrade(onSuccess);
                                openNextUpgradableGift(uiContext, account, serial);
                            }
                        }));
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

    /** Reloads the inventory once the reveal is gone and keeps the caller's callback. */
    private static void afterUpgrade(Runnable onSuccess) {
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
    }

    /**
     * Opens the card of the next gift that can still be upgraded.
     *
     * <p>The reveal ends with «Следующий подарок»; the inventory is loaded fresh so the gift that
     * was just upgraded is already gone from the candidates, and the same gift type is preferred
     * so a stack of identical gifts is upgraded one after another.</p>
     */
    public static void openNextUpgradableGift(
            Context context, int account, String currentSerial
    ) {
        if (context == null) {
            return;
        }
        final Context uiContext = context;
        final Context appContext = context.getApplicationContext();
        final WaysGramGiftCatalogClient client = new WaysGramGiftCatalogClient(
                WaysGramSessionStore.getInstance(appContext, account));
        client.loadMyGifts(new WaysGramGiftCatalogClient.InventoryCallback() {
            @Override
            public void onLoaded(JSONArray gifts) {
                client.close();
                final String next = nextUpgradableSerial(gifts, currentSerial);
                AndroidUtilities.runOnUIThread(() -> {
                    if (next == null) {
                        toast(appContext, "Больше нет подарков для улучшения");
                        return;
                    }
                    WaysGramGiftCatalogSheet.openGiftCard(uiContext, account, next);
                });
            }

            @Override
            public void onError(String message) {
                client.close();
                String text = TextUtils.isEmpty(message)
                        ? "Не удалось загрузить подарки" : message;
                AndroidUtilities.runOnUIThread(() -> toast(appContext, text));
            }
        });
    }

    /** Picks the next upgradable serial: same gift type first, then any other gift. */
    private static String nextUpgradableSerial(JSONArray gifts, String currentSerial) {
        if (gifts == null || gifts.length() == 0) {
            return null;
        }
        String current = currentSerial == null ? "" : currentSerial.trim();
        String currentGiftId = "";
        for (int i = 0; i < gifts.length(); i++) {
            JSONObject item = gifts.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (current.equalsIgnoreCase(item.optString("serial_number", "").trim())) {
                currentGiftId = item.optString("telegram_gift_id", "").trim();
                break;
            }
        }
        String otherCandidate = null;
        for (int i = 0; i < gifts.length(); i++) {
            JSONObject item = gifts.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String serial = item.optString("serial_number", "").trim();
            String giftId = item.optString("telegram_gift_id", "").trim();
            if (serial.isEmpty() || giftId.isEmpty() || serial.equalsIgnoreCase(current)) {
                continue;
            }
            if (!currentGiftId.isEmpty() && currentGiftId.equals(giftId)) {
                return serial;
            }
            if (otherCandidate == null) {
                otherCandidate = serial;
            }
        }
        return otherCandidate;
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            FileLog.e(exception);
        }
    }
}
