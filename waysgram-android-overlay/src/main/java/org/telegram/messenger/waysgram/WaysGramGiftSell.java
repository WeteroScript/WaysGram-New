package org.telegram.messenger.waysgram;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;

/**
 * Sells a WaysGram gift back to WaysGram from the native saved-gift card.
 *
 * <p>Telegram's own conversion row is empty for WaysGram gifts (they are not Telegram
 * collectibles), so the card carries its own row between the acquisition date and the value.
 * Only gifts that were never upgraded can be sold: an upgraded gift is a collectible with a
 * rolled model, a pattern and a collection number.</p>
 */
public final class WaysGramGiftSell {

    private WaysGramGiftSell() {
    }

    /**
     * Asks for confirmation, then sells the gift.
     *
     * @param onSold run on the UI thread once the backend confirmed the refund
     */
    public static void confirm(
            final Context context, final int account, final String serial, final Runnable onSold
    ) {
        if (context == null || TextUtils.isEmpty(serial)) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle("Продать подарок")
                .setMessage("WaysGram вернёт половину уплаченной цены в Stars. Подарок исчезнет из"
                        + " инвентаря, а карточка останется в истории чата.\n\nВернуть подарок можно"
                        + " только в первые 7 дней после получения.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Продать", (dialog, which) -> sell(context, account, serial, onSold))
                .show();
    }

    private static void sell(
            final Context context, int account, String serial, final Runnable onSold
    ) {
        final Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        final WaysGramGiftCatalogClient client = new WaysGramGiftCatalogClient(
                WaysGramSessionStore.getInstance(appContext, account));
        client.sellGift(serial, new WaysGramGiftCatalogClient.ActionCallback() {
            @Override
            public void onComplete(JSONObject result) {
                client.close();
                AndroidUtilities.runOnUIThread(() -> {
                    toast(appContext, "Подарок продан, Stars зачислены");
                    if (onSold != null) {
                        onSold.run();
                    }
                });
            }

            @Override
            public void onError(String message) {
                client.close();
                String text = TextUtils.isEmpty(message)
                        ? "Не удалось продать подарок" : message;
                AndroidUtilities.runOnUIThread(() -> toast(appContext, text));
            }
        });
    }

    private static void toast(Context context, String text) {
        if (context == null || TextUtils.isEmpty(text)) {
            return;
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
}
