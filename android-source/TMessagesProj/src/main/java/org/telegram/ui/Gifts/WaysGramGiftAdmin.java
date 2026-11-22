package org.telegram.ui.Gifts;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.tgnet.WaysGramGifts;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stars.StarsController;

/**
 * Editor of a gift's price and stock, shown to the administrator when a gift is tapped in the gift shop.
 * A gift whose price or stock is 0 is hidden from everybody else.
 */
public class WaysGramGiftAdmin {

    public static void show(Context context, int account, TL_stars.TL_starGift gift, Theme.ResourcesProvider resourcesProvider) {
        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
        final int hintColor = Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);

        TextView note = new TextView(context);
        note.setTextColor(hintColor);
        note.setTextSize(14);
        note.setText("Цена 0 или остаток 0 — подарок скрыт из магазина.");
        layout.addView(note);

        final EditText price = field(context, "Цена в Stars", gift.stars, textColor, hintColor);
        final EditText supply = field(context, "Сколько штук продать", gift.availability_total, textColor, hintColor);
        layout.addView(price);
        layout.addView(supply);

        new AlertDialog.Builder(context, resourcesProvider)
                .setTitle("Подарок " + (TextUtils.isEmpty(gift.title) ? "" : gift.title))
                .setView(layout)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    final long newPrice = parse(price.getText().toString());
                    final long newSupply = parse(supply.getText().toString());
                    if (newPrice < 0 || newSupply < 0) {
                        Toast.makeText(context, "Нужны целые числа от 0 до 1000000000", Toast.LENGTH_LONG).show();
                        return;
                    }
                    save(context, account, gift, newPrice, newSupply);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static EditText field(Context context, String hint, long value, int textColor, int hintColor) {
        EditText edit = new EditText(context);
        edit.setHint(hint);
        edit.setText(String.valueOf(Math.max(value, 0)));
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setTextColor(textColor);
        edit.setHintTextColor(hintColor);
        edit.setSingleLine(true);
        edit.setGravity(Gravity.START);
        edit.setSelection(edit.getText().length());
        return edit;
    }

    private static long parse(String text) {
        try {
            long value = Long.parseLong(text.trim());
            return value >= 0 && value <= 1_000_000_000L ? value : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void save(Context context, int account, TL_stars.TL_starGift gift, long price, long supply) {
        final String id = WaysGramGifts.originalId(gift.id);
        WaysGramApi.NETWORK.execute(() -> {
            String message;
            try {
                JSONObject body = new JSONObject();
                body.put("price_stars", price);
                body.put("supply", supply);
                WaysGramApi.patch(account, "/api/admin/telegram-gifts/" + android.net.Uri.encode(id), body);
                message = "Сохранено";
            } catch (WaysGramApi.ApiException e) {
                message = e.status == -1000 ? "Нет связи с сервером WaysGram" : e.detail;
            } catch (Exception e) {
                FileLog.e(e);
                message = "Ошибка";
            }
            final String text = message;
            AndroidUtilities.runOnUIThread(() -> {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show();
                WaysGramGifts.invalidate();
                StarsController controller = StarsController.getInstance(account);
                controller.invalidateStarGifts();
                controller.loadStarGifts();
            });
        });
    }
}
