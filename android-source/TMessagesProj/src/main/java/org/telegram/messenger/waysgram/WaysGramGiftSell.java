package org.telegram.messenger.waysgram;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

/**
 * Sells a WaysGram gift with Telegram's own conversion interface.
 *
 * <p>The saved-gift card shows the native "Convert" button in its value row; this class only
 * replaces the network call behind that button, because a WaysGram gift lives on the WaysGram
 * backend and not on the Telegram server. The confirmation dialog, its texts and the result
 * bulletin are the ones Telegram uses for {@code convertStarGift}.</p>
 */
public final class WaysGramGiftSell {

    /** WaysGram refunds a gift during the first seven days after it was received. */
    public static final int SELL_WINDOW_SECONDS = 7 * 24 * 60 * 60;

    private WaysGramGiftSell() {
    }

    /**
     * Whether the native "Convert" button may be shown for this saved gift.
     *
     * <p>Only a gift that was never upgraded can be sold: an upgraded gift is a collectible with
     * a rolled model, a pattern and a collection number.</p>
     */
    public static boolean canSell(TL_stars.SavedStarGift saved, int account) {
        if (saved == null || !(saved.gift instanceof TL_stars.TL_starGift)) {
            return false;
        }
        if (saved.refunded || saved.convert_stars <= 0) {
            return false;
        }
        final int now = ConnectionsManager.getInstance(account).getCurrentTime();
        return now - saved.date < SELL_WINDOW_SECONDS;
    }

    /**
     * Shows Telegram's conversion dialog, sells the gift on the WaysGram backend and reports the
     * result with Telegram's own bulletin.
     *
     * @param onSold run on the UI thread once the backend confirmed the refund
     */
    public static void convertNative(
            final Context context, final int account, final String serial,
            final int convertStars, final int withinDays, final Runnable onSold
    ) {
        if (context == null || TextUtils.isEmpty(serial)) {
            return;
        }
        final String senderName = selfName(account);
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.Gift2ConvertTitle))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatPluralString(
                        "Gift2ConvertText2", withinDays, senderName,
                        LocaleController.formatPluralStringComma("Gift2ConvertStars", convertStars))))
                .setPositiveButton(LocaleController.getString(R.string.Gift2ConvertButton),
                        (dialog, which) -> sell(context, account, serial, convertStars, onSold))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static String selfName(int account) {
        try {
            return UserObject.getUserName(UserConfig.getInstance(account).getCurrentUser());
        } catch (Exception exception) {
            return LocaleController.getString(R.string.StarsTransactionHidden);
        }
    }

    private static void sell(
            final Context context, final int account, final String serial,
            final int convertStars, final Runnable onSold
    ) {
        final Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        final AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.showDelayed(500);
        final WaysGramGiftCatalogClient client = new WaysGramGiftCatalogClient(
                WaysGramSessionStore.getInstance(appContext, account));
        client.sellGift(serial, new WaysGramGiftCatalogClient.ActionCallback() {
            @Override
            public void onComplete(JSONObject result) {
                client.close();
                AndroidUtilities.runOnUIThread(() -> {
                    progressDialog.dismissUnless(400);
                    if (onSold != null) {
                        onSold.run();
                    }
                    WaysGramGiftUpgrade.notifyDataChanged();
                    showConvertedBulletin(account, convertStars);
                });
            }

            @Override
            public void onError(String message) {
                client.close();
                final String text = TextUtils.isEmpty(message)
                        ? LocaleController.getString(R.string.UnknownError) : message;
                AndroidUtilities.runOnUIThread(() -> {
                    progressDialog.dismissUnless(400);
                    showError(text);
                });
            }
        });
    }

    private static void showConvertedBulletin(int account, int convertStars) {
        final BulletinFactory factory = bulletinFactory();
        if (factory == null) {
            return;
        }
        factory.createSimpleBulletin(
                R.raw.stars_topup,
                LocaleController.getString(R.string.Gift2ConvertedTitle),
                LocaleController.formatPluralStringComma("Gift2Converted", convertStars)
        ).show(true);
    }

    private static void showError(String text) {
        final BulletinFactory factory = bulletinFactory();
        if (factory != null) {
            factory.createErrorBulletin(text).show(false);
        }
    }

    private static BulletinFactory bulletinFactory() {
        try {
            final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            return fragment == null ? null : BulletinFactory.of(fragment);
        } catch (Exception exception) {
            return null;
        }
    }
}
