package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.waysgram.WaysGramApiClient;
import org.telegram.messenger.waysgram.WaysGramSessionStore;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.tgnet.WaysGramFragment;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.text.NumberFormat;

/**
 * Карточка подарка Fragment — отдельный экран, как страница лота на fragment.com.
 *
 * <p>Крупная картинка на цветном фоне, таблица свойств (владелец, модель, узор, фон,
 * серийный номер), цена и синяя кнопка действия. Всё, что меняет деньги, делает сервер:
 * покупка — {@code /api/gift-market/{id}/purchase}, продажа и снятие —
 * {@code /api/gifts/{serial}/list}. Всплывающих диалогов нет: результат показывается
 * строкой на самом экране.</p>
 */
final class WaysGramFragmentGiftActivity extends BaseFragment {

    private static final int[] CARD_COLORS = {
            0xFF6E7D63, 0xFF4F7F53, 0xFF7C6BB0, 0xFFB0865A, 0xFF4F7E96, 0xFFA06A88
    };

    private final int account;
    private final String token;
    private final boolean owned;
    private final JSONObject item;

    private Context uiContext;
    private LinearLayout content;
    private boolean busy;
    private int generation;
    private String notice;
    private boolean noticeError;

    WaysGramFragmentGiftActivity(int account, String payload, boolean owned) {
        setCurrentAccount(account);
        this.account = account;
        this.token = WaysGramApi.getToken(account);
        this.owned = owned;
        JSONObject parsed;
        try {
            parsed = new JSONObject(payload == null ? "{}" : payload);
        } catch (Exception ignore) {
            parsed = new JSONObject();
        }
        this.item = parsed;
    }

    private static String text(int resource) {
        return LocaleController.getString(resource);
    }

    private static String format(int resource, Object... args) {
        return LocaleController.formatString(resource, args);
    }

    private static int dp(int value) {
        return AndroidUtilities.dp(value);
    }

    private boolean active() {
        return getParentActivity() != null && content != null && !TextUtils.isEmpty(token)
                && TextUtils.equals(token, WaysGramApi.getToken(account));
    }

    private Context ctx() {
        if (uiContext == null) {
            uiContext = getParentActivity();
        }
        return uiContext;
    }

    private String giftTitle() {
        String name = item.optString("gift_name");
        if (TextUtils.isEmpty(name)) {
            name = text(R.string.WaysGramFragmentGiftTitle);
        }
        int unique = item.optInt("unique_num");
        if (unique > 0) {
            return name + " #" + unique;
        }
        String serial = item.optString("serial_number");
        return TextUtils.isEmpty(serial) ? name : name + " #" + serial;
    }

    @Override
    public View createView(Context context) {
        uiContext = context;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(giftTitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content = vertical();
        content.setPadding(0, dp(4), 0, dp(24));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        fragmentView = root;
        render();
        return fragmentView;
    }

    private void render() {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        if (notice != null) {
            LinearLayout card = rounded(Theme.key_windowBackgroundGray);
            card.setPadding(dp(14), dp(10), dp(14), dp(10));
            card.addView(label(notice, 13, noticeError
                    ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlueText, false),
                    new LinearLayout.LayoutParams(-1, -2));
            content.addView(card, rowParams());
        }

        content.addView(titleRow(), rowParams());
        content.addView(artCard(), new LinearLayout.LayoutParams(-1, -2));
        content.addView(attributesCard(), rowParams());
        content.addView(priceCard(), rowParams());
        content.addView(actionButton(), rowParams());
    }

    /** «Vice Cream #154249 · Продаётся» как на странице лота. */
    private View titleRow() {
        LinearLayout row = vertical();
        row.setPadding(dp(4), dp(4), dp(4), dp(2));
        LinearLayout line = horizontal();
        TextView title = label(giftTitle(), 20,
                Theme.key_windowBackgroundWhiteBlackText, true);
        line.addView(title, new LinearLayout.LayoutParams(-2, -2));
        line.addView(badge(statusText(), statusColor()), new LinearLayout.LayoutParams(-2, -2));
        row.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private String statusText() {
        if (!owned) {
            return text(R.string.WaysGramFragmentForSaleShort);
        }
        return text(item.optBoolean("is_listed")
                ? R.string.WaysGramFragmentOnMarket : R.string.WaysGramFragmentInInventory);
    }

    private int statusColor() {
        if (!owned) {
            return Theme.key_windowBackgroundWhiteGreenText;
        }
        return item.optBoolean("is_listed")
                ? Theme.key_windowBackgroundWhiteBlueText
                : Theme.key_windowBackgroundWhiteGrayText2;
    }

    private View artCard() {
        FrameLayout card = new FrameLayout(ctx());
        String key = item.optString("telegram_gift_id") + item.optString("serial_number");
        card.setBackground(Theme.createRoundRectDrawable(dp(14),
                CARD_COLORS[Math.abs(key.hashCode()) % CARD_COLORS.length]));

        TextView placeholder = new TextView(ctx());
        placeholder.setText(item.optString("gift_emoji").isEmpty()
                ? "\uD83C\uDF81" : item.optString("gift_emoji"));
        placeholder.setTextSize(64);
        placeholder.setGravity(Gravity.CENTER);
        card.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));

        ImageView art = new ImageView(ctx());
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setPadding(dp(24), dp(16), dp(24), dp(16));
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        loadArt(item.optString("telegram_gift_id"), art);

        LinearLayout frame = vertical();
        frame.setPadding(dp(12), dp(4), dp(12), dp(6));
        frame.addView(card, new LinearLayout.LayoutParams(-1, dp(200)));
        return frame;
    }

    private View attributesCard() {
        LinearLayout card = rounded(Theme.key_windowBackgroundGray);
        card.setPadding(dp(14), dp(4), dp(14), dp(4));

        String seller = item.optString("seller_username");
        if (!TextUtils.isEmpty(seller)) {
            addAttribute(card, text(R.string.WaysGramFragmentGiftOwner), "@" + seller, null);
        } else if (owned) {
            addAttribute(card, text(R.string.WaysGramFragmentGiftOwner),
                    text(R.string.WaysGramFragmentYou), null);
        }

        JSONObject traits = null;
        JSONObject attributes = item.optJSONObject("attributes");
        if (attributes != null) {
            traits = attributes.optJSONObject("collectible_traits");
        }
        if (traits != null) {
            addAttribute(card, text(R.string.WaysGramFragmentGiftModel),
                    traitName(traits, "model"), traitPercent(traits, "model"));
            addAttribute(card, text(R.string.WaysGramFragmentGiftPattern),
                    traitName(traits, "pattern"), traitPercent(traits, "pattern"));
            addAttribute(card, text(R.string.WaysGramFragmentGiftBackdrop),
                    traitName(traits, "backdrop"), traitPercent(traits, "backdrop"));
        }
        String serial = item.optString("serial_number");
        if (!TextUtils.isEmpty(serial)) {
            addAttribute(card, text(R.string.WaysGramFragmentGiftSerial), serial, null);
        }
        int unique = item.optInt("unique_num");
        if (unique > 0) {
            addAttribute(card, text(R.string.WaysGramFragmentGiftNumber), "#" + unique, null);
        }
        return card;
    }

    private String traitName(JSONObject traits, String key) {
        JSONObject trait = traits.optJSONObject(key);
        return trait == null ? "" : trait.optString("name");
    }

    private String traitPercent(JSONObject traits, String key) {
        JSONObject trait = traits.optJSONObject(key);
        if (trait == null) {
            return null;
        }
        double percent = trait.optDouble("permille", 0) / 10.0;
        if (percent <= 0) {
            percent = trait.optDouble("chance_percent", 0);
        }
        if (percent <= 0) {
            return null;
        }
        String value = percent >= 1
                ? Long.toString(Math.round(percent))
                : String.format(java.util.Locale.US, "%.1f", percent);
        return value + "%";
    }

    private void addAttribute(LinearLayout card, String name, String value, String percent) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        LinearLayout row = horizontal();
        row.setPadding(0, dp(8), 0, dp(8));
        TextView left = label(name, 13, Theme.key_windowBackgroundWhiteGrayText2, false);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView right = label(value, 14,
                Theme.key_windowBackgroundWhiteBlackText, true);
        right.setGravity(Gravity.END);
        row.addView(right, new LinearLayout.LayoutParams(-2, -2));
        if (!TextUtils.isEmpty(percent)) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMargins(dp(8), 0, 0, 0);
            row.addView(badge(percent, Theme.key_windowBackgroundWhiteBlueText), params);
        }
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private View priceCard() {
        LinearLayout card = rounded(Theme.key_windowBackgroundGray);
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        int price = item.optInt("price_grams");
        if (!owned) {
            card.addView(label(text(R.string.WaysGramFragmentGiftPrice), 12,
                    Theme.key_windowBackgroundWhiteGrayText2, false),
                    new LinearLayout.LayoutParams(-1, -2));
            card.addView(label("\uD83D\uDC8E " + count(price) + " Gram", 18,
                    Theme.key_windowBackgroundWhiteBlackText, true),
                    new LinearLayout.LayoutParams(-1, -2));
            TextView note = label(text(R.string.WaysGramFragmentGiftPriceNote), 12,
                    Theme.key_windowBackgroundWhiteGrayText2, false);
            note.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
            noteParams.setMargins(0, dp(4), 0, 0);
            card.addView(note, noteParams);
            return card;
        }
        if (item.optBoolean("is_listed")) {
            card.addView(label(text(R.string.WaysGramFragmentGiftListed), 13,
                    Theme.key_windowBackgroundWhiteGrayText2, false),
                    new LinearLayout.LayoutParams(-1, -2));
            return card;
        }
        card.addView(label(text(R.string.WaysGramFragmentSellPriceHint), 12,
                Theme.key_windowBackgroundWhiteGrayText2, false),
                new LinearLayout.LayoutParams(-1, -2));
        EditText field = new EditText(ctx());
        field.setHint(text(R.string.WaysGramFragmentSellPriceHint));
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(Theme.createRoundRectDrawable(dp(10),
                Theme.getColor(Theme.key_windowBackgroundWhite)));
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(-1, dp(42));
        fieldParams.setMargins(0, dp(6), 0, 0);
        card.addView(field, fieldParams);
        priceField = field;
        return card;
    }

    private EditText priceField;

    private View actionButton() {
        LinearLayout frame = vertical();
        if (busy) {
            return frame;
        }
        TextView button = new TextView(ctx());
        boolean buy = !owned;
        boolean listed = item.optBoolean("is_listed");
        if (buy) {
            button.setText(format(R.string.WaysGramFragmentBuyGram, item.optInt("price_grams")));
        } else if (listed) {
            button.setText(text(R.string.WaysGramFragmentUnlistGift));
        } else {
            button.setText(text(R.string.WaysGramFragmentSell));
        }
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(0xFFFFFFFF);
        button.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        button.setBackground(Theme.createRoundRectDrawable(dp(10),
                Theme.getColor(Theme.key_featuredStickers_addButton)));
        button.setOnClickListener(v -> {
            if (buy) {
                buy();
            } else if (listed) {
                unlist();
            } else {
                sell();
            }
        });
        frame.addView(button, new LinearLayout.LayoutParams(-1, dp(46)));
        return frame;
    }

    // ----------------------------------------------------------------- actions

    private void buy() {
        run(() -> WaysGramApi.post(account,
                        "/api/gift-market/" + item.optLong("listing_id") + "/purchase",
                        new JSONObject()),
                () -> {
                    notice = text(R.string.WaysGramFragmentBuyDone);
                    noticeError = false;
                });
    }

    private void unlist() {
        final String serial = item.optString("serial_number");
        run(() -> WaysGramApi.delete(account, "/api/gifts/" + serial + "/list"),
                () -> {
                    item.remove("is_listed");
                    notice = text(R.string.WaysGramFragmentUnlistDone);
                    noticeError = false;
                });
    }

    private void sell() {
        int price = 0;
        if (priceField != null) {
            try {
                price = Integer.parseInt(priceField.getText().toString().trim());
            } catch (NumberFormatException ignore) {
                price = 0;
            }
        }
        if (price <= 0) {
            notice = text(R.string.WaysGramFragmentSellPriceHint);
            noticeError = true;
            render();
            return;
        }
        final String serial = item.optString("serial_number");
        final int amount = price;
        run(() -> WaysGramApi.post(account, "/api/gifts/" + serial + "/list",
                        new JSONObject().put("price_grams", amount)),
                () -> {
                    notice = text(R.string.WaysGramFragmentSellDone);
                    noticeError = false;
                });
    }

    private interface Work {
        JSONObject run() throws Exception;
    }

    private interface Done {
        void run();
    }

    private void run(Work work, Done done) {
        if (busy || !active()) {
            return;
        }
        busy = true;
        final int gen = generation;
        WaysGramApi.NETWORK.execute(() -> {
            String error = null;
            try {
                work.run();
            } catch (Exception e) {
                error = message(e);
            }
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || !active()) {
                    return;
                }
                busy = false;
                if (failure == null) {
                    done.run();
                } else {
                    notice = failure;
                    noticeError = true;
                }
                render();
            });
        });
    }

    private String message(Exception error) {
        return error instanceof WaysGramApi.ApiException
                ? WaysGramFragment.errorMessage((WaysGramApi.ApiException) error,
                text(R.string.WaysGramFragmentError))
                : text(R.string.WaysGramFragmentError);
    }

    private void loadArt(final String giftId, final ImageView view) {
        if (TextUtils.isEmpty(giftId)) {
            return;
        }
        WaysGramApiClient client = new WaysGramApiClient(
                WaysGramSessionStore.getInstance(ctx(), account));
        client.downloadTelegramGiftSticker(giftId, new WaysGramApiClient.ImageCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if (bitmap == null || bitmap.isRecycled() || view.getParent() == null) {
                    return;
                }
                view.setImageBitmap(bitmap);
            }

            @Override
            public void onError(String message, int statusCode) {
                // Остаётся эмодзи, если картинки нет.
            }
        });
    }

    private static String count(long value) {
        return NumberFormat.getIntegerInstance().format(value);
    }

    // ------------------------------------------------------------- примитивы

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(ctx());
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(ctx());
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout rounded(int colorKey) {
        LinearLayout block = vertical();
        block.setBackground(Theme.createRoundRectDrawable(dp(12),
                Theme.getColor(colorKey)));
        return block;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(12), dp(4), dp(12), dp(4));
        return params;
    }

    private TextView label(CharSequence value, int size, int colorKey, boolean bold) {
        TextView view = new TextView(ctx());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(colorKey));
        if (bold) {
            view.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        }
        return view;
    }

    private TextView badge(CharSequence value, int colorKey) {
        int color = Theme.getColor(colorKey);
        TextView view = new TextView(ctx());
        view.setText(value);
        view.setTextSize(11);
        view.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        view.setTextColor(color);
        view.setBackground(Theme.createRoundRectDrawable(dp(5), Theme.multAlpha(color, 0.18f)));
        view.setPadding(dp(5), dp(1), dp(5), dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(dp(8), 0, 0, 0);
        view.setLayoutParams(params);
        return view;
    }
}
