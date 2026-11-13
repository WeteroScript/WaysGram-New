package org.telegram.messenger.waysgram;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WaysGram Telegram gift storefront.
 *
 * The AuthBot admin panel controls the sale price and available stock. This
 * client displays those values and lets signed-in users purchase items into
 * their WaysGram inventory.
 */
public final class WaysGramGiftCatalogSheet extends BottomSheet {
    private final WaysGramGiftCatalogClient catalogClient;
    private final Theme.ResourcesProvider resourcesProvider;
    private final GiftAdapter adapter;
    private final Runnable onPurchaseComplete;

    private TextView statusView;
    private ProgressBar progressBar;
    private final Set<String> purchasingGiftIds = new HashSet<>();
    private AlertDialog inventoryDialog;

    public WaysGramGiftCatalogSheet(
            Context context,
            WaysGramGiftCatalogClient.SessionProvider sessionProvider,
            @Nullable Theme.ResourcesProvider resourcesProvider
    ) {
        this(context, sessionProvider, resourcesProvider, null);
    }

    public WaysGramGiftCatalogSheet(
            Context context,
            WaysGramGiftCatalogClient.SessionProvider sessionProvider,
            @Nullable Theme.ResourcesProvider resourcesProvider,
            @Nullable Runnable onPurchaseComplete
    ) {
        super(context, false, resourcesProvider);
        this.resourcesProvider = resourcesProvider;
        this.onPurchaseComplete = onPurchaseComplete;
        catalogClient = new WaysGramGiftCatalogClient(sessionProvider);
        adapter = new GiftAdapter();

        setTitle("Подарки WaysGram", true);
        setApplyTopPadding(false);
        setApplyBottomPadding(true);
        setCustomView(createContent(context));

        setOnDismissListener(dialog -> {
            if (inventoryDialog != null && inventoryDialog.isShowing()) {
                inventoryDialog.dismiss();
            }
            inventoryDialog = null;
            catalogClient.close();
        });
        loadCatalog();
    }

    private View createContent(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                AndroidUtilities.dp(16),
                AndroidUtilities.dp(4),
                AndroidUtilities.dp(16),
                AndroidUtilities.dp(8)
        );

        TextView subtitle = new TextView(context);
        subtitle.setText("Внутренний каталог WaysGram · Telegram Gift ID используется только как источник данных");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        subtitle.setPadding(AndroidUtilities.dp(2), 0, AndroidUtilities.dp(2), AndroidUtilities.dp(10));
        content.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button inventoryButton = new Button(context);
        inventoryButton.setAllCaps(false);
        inventoryButton.setText("Мои подарки");
        inventoryButton.setOnClickListener(view -> showMyGifts());
        content.addView(inventoryButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button marketButton = new Button(context);
        marketButton.setAllCaps(false);
        marketButton.setText("Маркетплейс подарков");
        marketButton.setOnClickListener(view -> showGiftMarket());
        content.addView(marketButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout statusContainer = new FrameLayout(context);
        statusView = new TextView(context);
        statusView.setText("Загрузка каталога…");
        statusView.setTextSize(13);
        statusView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        statusView.setGravity(Gravity.CENTER);
        statusContainer.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(44)
        ));

        progressBar = new ProgressBar(context);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                AndroidUtilities.dp(24),
                AndroidUtilities.dp(24),
                Gravity.CENTER
        );
        statusContainer.addView(progressBar, progressParams);
        content.addView(statusContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(44)
        ));

        RecyclerView recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(adapter);
        recyclerView.setClipToPadding(false);
        recyclerView.setPadding(0, 0, 0, AndroidUtilities.dp(8));

        int availableHeight = Math.max(
                AndroidUtilities.dp(260),
                Math.min(AndroidUtilities.dp(560), AndroidUtilities.displaySize.y - AndroidUtilities.dp(220))
        );
        content.addView(recyclerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                availableHeight
        ));

        return content;
    }

    private void showMyGifts() {
        if (inventoryDialog != null && inventoryDialog.isShowing()) {
            inventoryDialog.dismiss();
        }
        AlertDialog loading = new AlertDialog.Builder(getContext())
                .setTitle("Мои подарки")
                .setMessage("Загружаю инвентарь…")
                .setPositiveButton("Закрыть", null)
                .create();
        inventoryDialog = loading;
        loading.setOnDismissListener(ignored -> {
            if (inventoryDialog == loading) {
                inventoryDialog = null;
            }
        });
        loading.show();
        catalogClient.loadMyGifts(new WaysGramGiftCatalogClient.InventoryCallback() {
            @Override
            public void onLoaded(JSONArray gifts) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (isDismissed() || !loading.isShowing()) {
                        return;
                    }
                    if (gifts.length() == 0) {
                        loading.setMessage("Инвентарь пока пуст.");
                        return;
                    }
                    ArrayList<JSONObject> items = new ArrayList<>();
                    String[] labels = new String[gifts.length()];
                    for (int i = 0; i < gifts.length(); i++) {
                        JSONObject gift = gifts.optJSONObject(i);
                        if (gift == null) {
                            labels[i] = "Подарок";
                            items.add(new JSONObject());
                            continue;
                        }
                        items.add(gift);
                        String name = gift.optString("gift_name", "Подарок WaysGram");
                        String serial = gift.optString("serial_number", "");
                        int level = gift.optInt("upgrade_level", 0);
                        JSONObject giftAttributes = gift.optJSONObject("attributes");
                        JSONObject collectibleTraits = giftAttributes == null
                                ? null : giftAttributes.optJSONObject("collectible_traits");
                        String rarity = collectibleTraits == null
                                ? "" : collectibleTraits.optString("rarity", "");
                        labels[i] = name
                                + (rarity.isEmpty() ? "" : " · " + rarity)
                                + (level > 0 ? " · уровень " + level : "")
                                + (gift.optInt("is_listed", gift.optBoolean("is_listed", false) ? 1 : 0) == 1 ? " · выставлен" : "")
                                + (serial.isEmpty() ? "" : "\n" + serial);
                    }
                    loading.dismiss();
                    AlertDialog list = new AlertDialog.Builder(getContext())
                            .setTitle("Мои подарки")
                            .setItems(labels, (dialog, which) -> {
                                if (which >= 0 && which < items.size()) {
                                    showGiftActions(items.get(which));
                                }
                            })
                            .setPositiveButton("Закрыть", null)
                            .create();
                    inventoryDialog = list;
                    list.setOnDismissListener(ignored -> {
                        if (inventoryDialog == list) {
                            inventoryDialog = null;
                        }
                    });
                    list.show();
                });
            }

            @Override
            public void onError(String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isDismissed() && loading.isShowing()) {
                        loading.setMessage("Не удалось загрузить инвентарь: " + message);
                    }
                });
            }
        });
    }

    private void showGiftActions(JSONObject gift) {
        String serial = gift.optString("serial_number", "");
        String name = gift.optString("gift_name", "Подарок WaysGram");
        if (serial.isEmpty()) {
            return;
        }
        ArrayList<String> actions = new ArrayList<>();
        boolean listed = gift.optInt("is_listed", gift.optBoolean("is_listed", false) ? 1 : 0) == 1;
        actions.add(listed ? "Снять с продажи" : "Выставить на продажу");
        boolean canUpgrade = gift.optString("telegram_gift_id", "").length() > 0;
        if (canUpgrade) {
            actions.add("Улучшить за WaysGram Stars");
        }
        actions.add("Передать пользователю");
        StringBuilder giftDetails = new StringBuilder("Серийный номер: ").append(serial);
        JSONObject giftAttributes = gift.optJSONObject("attributes");
        JSONObject collectibleTraits = giftAttributes == null
                ? null : giftAttributes.optJSONObject("collectible_traits");
        if (collectibleTraits != null) {
            giftDetails.append("\\nРедкость: ").append(collectibleTraits.optString("rarity", "—"));
            giftDetails.append("\\nМодель: ").append(collectibleTraits.optString("model", "—"));
            giftDetails.append("\\nСимвол: ").append(collectibleTraits.optString("symbol", "—"));
            giftDetails.append("\\nФон: ").append(collectibleTraits.optString("backdrop", "—"));
        }
        new AlertDialog.Builder(getContext())
                .setTitle(name)
                .setMessage(giftDetails.toString())
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    if (which < 0 || which >= actions.size()) {
                        return;
                    }
                    String action = actions.get(which);
                    if (action.equals("Выставить на продажу")) {
                        showGiftListingPrice(serial);
                    } else if (action.equals("Снять с продажи")) {
                        catalogClient.cancelGiftListing(serial, giftActionCallback("Объявление снято"));
                    } else if (action.equals("Улучшить за WaysGram Stars")) {
                        new AlertDialog.Builder(getContext())
                                .setTitle("Улучшить подарок")
                                .setMessage("Стоимость улучшения будет списана с внутреннего баланса WaysGram Stars.")
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Улучшить", (d, w) ->
                                        catalogClient.upgradeGift(serial, giftActionCallback("Подарок улучшен")))
                                .show();
                    } else if (action.equals("Передать пользователю")) {
                        showGiftTransfer(serial);
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showGiftListingPrice(String serial) {
        EditText priceInput = new EditText(getContext());
        priceInput.setSingleLine(true);
        priceInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        priceInput.setHint("Цена в Gram");
        new AlertDialog.Builder(getContext())
                .setTitle("Выставить подарок")
                .setMessage("Укажите фиксированную цену в Gram.")
                .setView(priceInput)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Выставить", (dialog, which) -> {
                    try {
                        int price = Integer.parseInt(priceInput.getText().toString().trim());
                        catalogClient.listGift(serial, price, giftActionCallback("Подарок выставлен на маркетплейсе"));
                    } catch (NumberFormatException exception) {
                        Toast.makeText(getContext(), "Укажите целую цену в Gram", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showGiftTransfer(String serial) {
        EditText recipientInput = new EditText(getContext());
        recipientInput.setSingleLine(true);
        recipientInput.setHint("Username или ID пользователя");
        new AlertDialog.Builder(getContext())
                .setTitle("Передать подарок")
                .setView(recipientInput)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Передать", (dialog, which) -> {
                    String recipient = recipientInput.getText().toString().trim();
                    if (recipient.isEmpty()) {
                        Toast.makeText(getContext(), "Укажите получателя", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    catalogClient.transferGift(serial, recipient, giftActionCallback("Подарок передан"));
                })
                .show();
    }

    private WaysGramGiftCatalogClient.ActionCallback giftActionCallback(String successMessage) {
        return new WaysGramGiftCatalogClient.ActionCallback() {
            @Override
            public void onComplete(JSONObject result) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (isDismissed()) {
                        return;
                    }
                    Toast.makeText(getContext(), successMessage, Toast.LENGTH_LONG).show();
                    showMyGifts();
                    loadCatalog();
                    if (onPurchaseComplete != null) {
                        onPurchaseComplete.run();
                    }
                });
            }

            @Override
            public void onError(String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isDismissed()) {
                        Toast.makeText(getContext(), "Операция не выполнена: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        };
    }

    private void showGiftMarket() {
        AlertDialog loading = new AlertDialog.Builder(getContext())
                .setTitle("Маркетплейс подарков")
                .setMessage("Загружаю объявления…")
                .setPositiveButton("Закрыть", null)
                .create();
        loading.show();
        catalogClient.loadMarket(new WaysGramGiftCatalogClient.MarketCallback() {
            @Override
            public void onLoaded(JSONArray listings) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (isDismissed() || !loading.isShowing()) {
                        return;
                    }
                    if (listings.length() == 0) {
                        loading.setMessage("Активных объявлений пока нет.");
                        return;
                    }
                    ArrayList<JSONObject> items = new ArrayList<>();
                    String[] labels = new String[listings.length()];
                    for (int i = 0; i < listings.length(); i++) {
                        JSONObject listing = listings.optJSONObject(i);
                        if (listing == null) {
                            items.add(new JSONObject());
                            labels[i] = "Подарок";
                            continue;
                        }
                        items.add(listing);
                        String seller = listing.optString("seller_username", "");
                        JSONObject marketAttributes = listing.optJSONObject("attributes");
                        JSONObject marketTraits = marketAttributes == null
                                ? null : marketAttributes.optJSONObject("collectible_traits");
                        String rarity = marketTraits == null ? "" : marketTraits.optString("rarity", "");
                        labels[i] = listing.optString("gift_name", "Подарок WaysGram")
                                + (rarity.isEmpty() ? "" : " · " + rarity)
                                + " · " + listing.optInt("price_grams", 0) + " Gram"
                                + (seller.isEmpty() ? "" : " · @" + seller);
                    }
                    loading.dismiss();
                    new AlertDialog.Builder(getContext())
                            .setTitle("Маркетплейс подарков")
                            .setItems(labels, (dialog, which) -> {
                                if (which < 0 || which >= items.size()) {
                                    return;
                                }
                                JSONObject listing = items.get(which);
                                int listingId = listing.optInt("listing_id", 0);
                                String giftName = listing.optString("gift_name", "Подарок WaysGram");
                                int price = listing.optInt("price_grams", 0);
                                StringBuilder listingDetails = new StringBuilder(giftName)
                                        .append("\\nЦена: ").append(price).append(" Gram");
                                JSONObject marketAttributes = listing.optJSONObject("attributes");
                                JSONObject marketTraits = marketAttributes == null
                                        ? null : marketAttributes.optJSONObject("collectible_traits");
                                if (marketTraits != null) {
                                    listingDetails.append("\\nРедкость: ").append(marketTraits.optString("rarity", "—"));
                                    listingDetails.append("\\nМодель: ").append(marketTraits.optString("model", "—"));
                                    listingDetails.append("\\nСимвол: ").append(marketTraits.optString("symbol", "—"));
                                    listingDetails.append("\\nФон: ").append(marketTraits.optString("backdrop", "—"));
                                }
                                new AlertDialog.Builder(getContext())
                                        .setTitle("Купить подарок")
                                        .setMessage(listingDetails.toString())
                                        .setNegativeButton("Отмена", null)
                                        .setPositiveButton("Купить", (d, w) ->
                                                catalogClient.purchaseMarketGift(
                                                        listingId,
                                                        giftActionCallback("Подарок куплен")
                                                ))
                                        .show();
                            })
                            .setPositiveButton("Закрыть", null)
                            .show();
                });
            }

            @Override
            public void onError(String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isDismissed() && loading.isShowing()) {
                        loading.setMessage("Не удалось загрузить маркетплейс: " + message);
                    }
                });
            }
        });
    }

    private void loadCatalog() {
        catalogClient.loadCatalog(new WaysGramGiftCatalogClient.CatalogCallback() {
            @Override
            public void onLoaded(List<WaysGramGiftCatalogClient.Gift> gifts, boolean stale, long syncedAt) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (isDismissed()) {
                        return;
                    }
                    adapter.setItems(gifts);
                    progressBar.setVisibility(View.GONE);
                    String state = stale ? "Показан последний сохранённый каталог" : "Каталог обновлён";
                    statusView.setText(gifts.isEmpty()
                            ? state + " · подарков нет"
                            : state + " · " + gifts.size() + " подарков");
                });
            }

            @Override
            public void onError(String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (isDismissed()) {
                        return;
                    }
                    progressBar.setVisibility(View.GONE);
                    statusView.setText(message);
                });
            }
        });
    }

    private void showPurchaseConfirmation(WaysGramGiftCatalogClient.Gift gift) {
        if (gift == null || !gift.forSale || !purchasingGiftIds.add(gift.id)) {
            return;
        }
        adapter.notifyDataSetChanged();
        new AlertDialog.Builder(getContext())
                .setTitle("Подтвердить покупку")
                .setMessage(
                        gift.title + "\n\nЦена: " + gift.storePriceStars
                                + " WaysGram Stars\nОсталось в магазине: " + gift.storeRemaining
                )
                .setNegativeButton("Отмена", (dialog, which) -> finishPurchaseAttempt(gift.id))
                .setPositiveButton("Купить", (dialog, which) -> {
                    statusView.setText("Покупка подарка…");
                    catalogClient.purchaseGift(gift, new WaysGramGiftCatalogClient.PurchaseCallback() {
                        @Override
                        public void onPurchased(JSONObject result) {
                            AndroidUtilities.runOnUIThread(() -> {
                                if (isDismissed()) {
                                    return;
                                }
                                finishPurchaseAttempt(gift.id);
                                JSONObject purchasedGift = result.optJSONObject("gift");
                                String serial = purchasedGift == null
                                        ? ""
                                        : purchasedGift.optString("serial_number", "");
                                int balance = result.optInt("stars", -1);
                                String confirmation = "Подарок куплен"
                                        + (serial.length() > 0 ? " · " + serial : "")
                                        + (balance >= 0 ? " · баланс " + balance + " Stars" : "");
                                statusView.setText(confirmation);
                                Toast.makeText(getContext(), confirmation, Toast.LENGTH_LONG).show();
                                loadCatalog();
                                if (onPurchaseComplete != null) {
                                    onPurchaseComplete.run();
                                }
                            });
                        }

                        @Override
                        public void onError(String message) {
                            AndroidUtilities.runOnUIThread(() -> {
                                if (isDismissed()) {
                                    return;
                                }
                                finishPurchaseAttempt(gift.id);
                                statusView.setText("Покупка не выполнена: " + message);
                            });
                        }
                    });
                })
                .setOnCancelListener(dialog -> finishPurchaseAttempt(gift.id))
                .show();
    }

    private void finishPurchaseAttempt(String giftId) {
        purchasingGiftIds.remove(giftId);
        if (!isDismissed() && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private final class GiftAdapter extends RecyclerView.Adapter<GiftHolder> {
        private final ArrayList<WaysGramGiftCatalogClient.Gift> items = new ArrayList<>();
        private final Map<String, Bitmap> stickers = new HashMap<>();
        private final Set<String> requestedStickers = new HashSet<>();

        void setItems(List<WaysGramGiftCatalogClient.Gift> gifts) {
            items.clear();
            if (gifts != null) {
                items.addAll(gifts);
            }
            notifyDataSetChanged();
        }

        void setSticker(String giftId, Bitmap bitmap) {
            Bitmap previous = stickers.put(giftId, bitmap);
            if (previous != null && previous != bitmap && !previous.isRecycled()) {
                previous.recycle();
            }
            for (int i = 0; i < items.size(); i++) {
                if (giftId.equals(items.get(i).id)) {
                    notifyItemChanged(i);
                    return;
                }
            }
        }

        @NonNull
        @Override
        public GiftHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new GiftHolder(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull GiftHolder holder, int position) {
            WaysGramGiftCatalogClient.Gift gift = items.get(position);
            holder.bind(gift, stickers.get(gift.id));
            requestSticker(gift);
        }

        private void requestSticker(WaysGramGiftCatalogClient.Gift gift) {
            if (gift.animatedSticker || gift.videoSticker || gift.stickerUrl.length() == 0
                    || stickers.containsKey(gift.id) || !requestedStickers.add(gift.id)) {
                return;
            }
            catalogClient.downloadSticker(gift, new WaysGramGiftCatalogClient.StickerCallback() {
                @Override
                public void onLoaded(byte[] bytes, String mimeType) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap == null) {
                        return;
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!isDismissed()) {
                            setSticker(gift.id, bitmap);
                        } else {
                            bitmap.recycle();
                        }
                    });
                }

                @Override
                public void onError(String message) {
                    // Keep the emoji placeholder if Telegram cannot deliver this sticker.
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        void clear() {
            for (Bitmap bitmap : stickers.values()) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
            stickers.clear();
            requestedStickers.clear();
            items.clear();
        }
    }

    private final class GiftHolder extends RecyclerView.ViewHolder {
        private final ImageView imageView;
        private final TextView emojiView;
        private final TextView titleView;
        private final TextView priceView;
        private final TextView stockView;
        private final Button buyButton;

        GiftHolder(Context context) {
            super(new LinearLayout(context));
            LinearLayout row = (LinearLayout) itemView;
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(8),
                    AndroidUtilities.dp(6), AndroidUtilities.dp(8));

            FrameLayout imageBox = new FrameLayout(context);
            GradientDrawable imageBackground = new GradientDrawable();
            imageBackground.setColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
            imageBackground.setCornerRadius(AndroidUtilities.dp(14));
            imageBox.setBackground(imageBackground);
            imageView = new ImageView(context);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageBox.addView(imageView, new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(64), AndroidUtilities.dp(64), Gravity.CENTER
            ));
            emojiView = new TextView(context);
            emojiView.setTextSize(30);
            emojiView.setGravity(Gravity.CENTER);
            imageBox.addView(emojiView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            row.addView(imageBox, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(72), AndroidUtilities.dp(72)
            ));

            LinearLayout details = new LinearLayout(context);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setGravity(Gravity.CENTER_VERTICAL);
            details.setPadding(AndroidUtilities.dp(12), 0, 0, 0);

            titleView = new TextView(context);
            titleView.setTextSize(15);
            titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            details.addView(titleView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            priceView = new TextView(context);
            priceView.setTextSize(14);
            priceView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            LinearLayout.LayoutParams priceParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            priceParams.topMargin = AndroidUtilities.dp(3);
            details.addView(priceView, priceParams);

            stockView = new TextView(context);
            stockView.setTextSize(12);
            stockView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
            LinearLayout.LayoutParams stockParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            stockParams.topMargin = AndroidUtilities.dp(2);
            details.addView(stockView, stockParams);

            row.addView(details, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
            ));

            buyButton = new Button(context);
            buyButton.setText("Купить");
            buyButton.setTextSize(12);
            LinearLayout.LayoutParams buyParams = new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(88), AndroidUtilities.dp(48)
            );
            buyParams.leftMargin = AndroidUtilities.dp(6);
            row.addView(buyButton, buyParams);
        }

        void bind(WaysGramGiftCatalogClient.Gift gift, @Nullable Bitmap sticker) {
            titleView.setText(gift.title);
            if (gift.forSale) {
                priceView.setText("Цена: " + gift.storePriceStars + " WaysGram Stars");
                stockView.setText("В наличии: " + gift.storeRemaining);
                stockView.setVisibility(View.VISIBLE);
                buyButton.setVisibility(View.VISIBLE);
                buyButton.setEnabled(!purchasingGiftIds.contains(gift.id));
                buyButton.setText("Купить");
                buyButton.setOnClickListener(view -> showPurchaseConfirmation(gift));
            } else {
                priceView.setText("Не выставлен на продажу");
                buyButton.setVisibility(View.GONE);
                buyButton.setOnClickListener(null);
            }
            if (!gift.forSale && gift.auction) {
                stockView.setText("Аукционный тип подарка");
                stockView.setVisibility(View.VISIBLE);
            } else if (!gift.forSale && gift.soldOut) {
                stockView.setText("Распродан");
                stockView.setVisibility(View.VISIBLE);
            } else if (!gift.forSale && gift.limited && gift.remainingCount != null && gift.totalCount != null) {
                stockView.setText("Осталось " + gift.remainingCount + " из " + gift.totalCount);
                stockView.setVisibility(View.VISIBLE);
            } else if (!gift.forSale && gift.premium) {
                stockView.setText("Требуется Premium");
                stockView.setVisibility(View.VISIBLE);
            } else if (!gift.forSale) {
                stockView.setVisibility(View.GONE);
            }
            if (sticker != null && !sticker.isRecycled()) {
                imageView.setImageBitmap(sticker);
                imageView.setVisibility(View.VISIBLE);
                emojiView.setVisibility(View.GONE);
            } else {
                imageView.setImageDrawable(null);
                imageView.setVisibility(View.GONE);
                emojiView.setText(gift.emoji.length() > 0 ? gift.emoji : "🎁");
                emojiView.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void dismiss() {
        purchasingGiftIds.clear();
        if (adapter != null) {
            adapter.clear();
        }
        super.dismiss();
    }
}
