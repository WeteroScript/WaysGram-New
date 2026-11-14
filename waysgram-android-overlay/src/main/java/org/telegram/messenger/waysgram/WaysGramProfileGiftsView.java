package org.telegram.messenger.waysgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * WaysGram-only inventory page embedded in the current user's Telegram profile.
 * Inventory ownership is loaded from the authenticated WaysGram account.
 */
public final class WaysGramProfileGiftsView extends FrameLayout {
    private final Context appContext;
    private final LinearLayout itemsContainer;
    private final TextView statusView;
    private final WaysGramApiClient stickerClient;
    private boolean loading;

    public WaysGramProfileGiftsView(Context context) {
        super(context);
        appContext = context.getApplicationContext();
        stickerClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(appContext));
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12),
                AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        addView(root, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText("Мои подарки");
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        header.addView(title, new LinearLayout.LayoutParams(0,
                LayoutParams.WRAP_CONTENT, 1f));

        Button refreshButton = new Button(context);
        refreshButton.setAllCaps(false);
        refreshButton.setText("Обновить");
        refreshButton.setOnClickListener(view -> refresh());
        header.addView(refreshButton, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        statusView = new TextView(context);
        statusView.setText("Загрузка подарков…");
        statusView.setTextSize(13);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusView.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        root.addView(statusView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        itemsContainer = new LinearLayout(context);
        itemsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(itemsContainer, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

    }

    public void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        statusView.setText("Загрузка подарков…");
        itemsContainer.removeAllViews();

        WaysGramSessionStore sessionStore = WaysGramSessionStore.getInstance(appContext);
        WaysGramGiftCatalogClient client = new WaysGramGiftCatalogClient(sessionStore);
        client.loadMyGifts(new WaysGramGiftCatalogClient.InventoryCallback() {
            @Override
            public void onLoaded(JSONArray gifts) {
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    client.close();
                    renderGifts(gifts == null ? new JSONArray() : gifts);
                });
            }

            @Override
            public void onError(String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    client.close();
                    statusView.setText("Не удалось загрузить инвентарь: " + message);
                });
            }
        });
    }

    private void renderGifts(JSONArray gifts) {
        itemsContainer.removeAllViews();
        int count = gifts.length();
        statusView.setText("В инвентаре: " + count + (count == 1 ? " подарок" : " подарков"));
        if (count == 0) {
            TextView empty = createLabel("У вас пока нет подарков WaysGram.", 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(28),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(28));
            itemsContainer.addView(empty, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            return;
        }

        int visible = Math.min(count, 100);
        for (int i = 0; i < visible; i += 2) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);

            JSONObject first = gifts.optJSONObject(i);
            JSONObject second = i + 1 < visible ? gifts.optJSONObject(i + 1) : null;
            if (first != null) {
                addGiftCard(first, row);
            } else {
                row.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            if (second != null) {
                addGiftCard(second, row);
            } else {
                row.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            itemsContainer.addView(row, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        if (count > visible) {
            itemsContainer.addView(createLabel(
                    "Показаны последние " + visible + " подарков из " + count + ".",
                    12, false));
        }
    }

    private void addGiftCard(JSONObject gift, LinearLayout row) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10),
                AndroidUtilities.dp(10), AndroidUtilities.dp(12));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        background.setCornerRadius(AndroidUtilities.dp(14));
        background.setStroke(AndroidUtilities.dp(1), Theme.getColor(Theme.key_divider));
        card.setBackground(background);
        card.setClipToOutline(true);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        cardParams.leftMargin = AndroidUtilities.dp(4);
        cardParams.rightMargin = AndroidUtilities.dp(4);
        cardParams.bottomMargin = AndroidUtilities.dp(8);
        row.addView(card, cardParams);

        FrameLayout imageFrame = new FrameLayout(getContext());
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, AndroidUtilities.dp(132));
        imageParams.bottomMargin = AndroidUtilities.dp(8);
        card.addView(imageFrame, imageParams);

        TextView placeholder = new TextView(getContext());
        placeholder.setText("🎁");
        placeholder.setTextSize(48);
        placeholder.setGravity(Gravity.CENTER);
        imageFrame.addView(placeholder, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        image.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4),
                AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        imageFrame.addView(image, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        String name = gift.optString("gift_name", "Подарок WaysGram");
        TextView title = createLabel(name, 15, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        card.addView(title, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        String serial = gift.optString("serial_number", "");
        if (!serial.isEmpty()) {
            TextView serialLabel = createLabel("№ " + serial, 12, true);
            serialLabel.setGravity(Gravity.CENTER);
            serialLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            card.addView(serialLabel);
        }

        String telegramId = gift.optString("telegram_gift_id", "");
        if (!telegramId.isEmpty()) {
            TextView idLabel = createLabel("Telegram Gift ID: " + telegramId, 10, false);
            idLabel.setGravity(Gravity.CENTER);
            card.addView(idLabel);
        }

        JSONObject attributes = gift.optJSONObject("attributes");
        if (attributes == null) {
            attributes = new JSONObject();
        }
        int level = Math.max(gift.optInt("upgrade_level", 0),
                attributes.optInt("upgrade_level", 0));
        card.addView(createLabel(
                level > 0 ? "Коллекционный · уровень " + level : "Уровень WaysGram: 0",
                12, level > 0));

        JSONObject traits = attributes.optJSONObject("collectible_traits");
        if (traits != null) {
            addTraitLabel(card, "Редкость", traits.optString("rarity", ""));
            addTraitLabel(card, "Модель", traits.optString("model", ""));
            addTraitLabel(card, "Символ", traits.optString("symbol", ""));
            addTraitLabel(card, "Фон", traits.optString("backdrop", ""));
        }

        if (gift.optInt("is_listed", gift.optBoolean("is_listed", false) ? 1 : 0) == 1) {
            card.addView(createLabel("Выставлен на маркетплейсе", 11, false));
        }
        if (gift.optInt("is_auctioned", gift.optBoolean("is_auctioned", false) ? 1 : 0) == 1) {
            card.addView(createLabel("На аукционе", 11, false));
        }

        boolean stickerAvailable = gift.optBoolean("sticker_available", !telegramId.isEmpty());
        if (!telegramId.isEmpty() && stickerAvailable) {
            stickerClient.downloadTelegramGiftSticker(telegramId,
                    new WaysGramApiClient.ImageCallback() {
                        @Override
                        public void onSuccess(Bitmap bitmap) {
                            image.setImageBitmap(bitmap);
                            image.setVisibility(View.VISIBLE);
                            placeholder.setVisibility(View.GONE);
                        }

                        @Override
                        public void onError(String message, int statusCode) {
                            // Keep the local gift placeholder when Telegram media is unavailable.
                        }
                    });
        }
    }

    private void addTraitLabel(LinearLayout card, String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        card.addView(createLabel(label + ": " + value, 11, false));
    }

    private void appendTrait(StringBuilder line, String label, String value) {
        if (line.length() > 0) {
            line.append(" · ");
        }
        line.append(label).append(": ").append(value);
    }

    private TextView createLabel(String text, int size, boolean bold) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (bold) {
            label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return label;
    }
}
