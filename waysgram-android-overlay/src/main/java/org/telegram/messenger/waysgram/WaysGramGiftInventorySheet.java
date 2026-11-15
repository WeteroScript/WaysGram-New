package org.telegram.messenger.waysgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Gifts.GiftSheet;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** WaysGram inventory rendered with Telegram's native three-column GiftCell grid. */
public final class WaysGramGiftInventorySheet extends BottomSheet {
    public interface GiftSelectedListener {
        void onGiftSelected(JSONObject gift);
    }

    private static final int MAX_STICKER_DOWNLOADS = 200;

    private final WaysGramGiftCatalogClient catalogClient;
    private final WaysGramApiClient imageClient;
    private final Theme.ResourcesProvider resourcesProvider;
    private final GiftSelectedListener listener;
    private final ArrayList<TL_stars.SavedStarGift> nativeGifts = new ArrayList<>();
    private final Map<String, JSONObject> giftsBySerial = new HashMap<>();
    private final Set<String> startedStickerDownloads = new HashSet<>();

    private UniversalRecyclerView listView;
    private TextView emptyView;
    private TextView statusView;

    public WaysGramGiftInventorySheet(
            Context context,
            WaysGramGiftCatalogClient.SessionProvider sessionProvider,
            Theme.ResourcesProvider resourcesProvider,
            GiftSelectedListener listener
    ) {
        super(context, false, resourcesProvider);
        this.resourcesProvider = resourcesProvider;
        this.listener = listener;
        catalogClient = new WaysGramGiftCatalogClient(sessionProvider);
        imageClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(context));

        setTitle("Мои подарки", true);
        setApplyTopPadding(false);
        setApplyBottomPadding(true);
        setCustomView(createContent(context));
        setOnDismissListener(dialog -> {
            catalogClient.close();
            imageClient.close();
        });
        loadInventory();
    }

    private View createContent(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4),
                AndroidUtilities.dp(4), AndroidUtilities.dp(8));

        statusView = new TextView(context);
        statusView.setText("Загрузка подарков…");
        statusView.setTextSize(13);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        content.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(38)));

        FrameLayout listContainer = new FrameLayout(context);
        listView = new UniversalRecyclerView(
                context, UserConfig.selectedAccount, 0, false,
                this::fillItems, this::onItemClick, this::onItemLongPress,
                resourcesProvider, 3, LinearLayoutManager.VERTICAL);
        listView.adapter.setApplyBackground(false);
        listView.setSelectorType(9);
        listView.setSelectorDrawableColor(0);
        listView.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(12),
                AndroidUtilities.dp(9), AndroidUtilities.dp(24));
        listView.setClipToPadding(false);
        listView.setClipChildren(false);
        listContainer.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText("Подарков пока нет");
        emptyView.setTextSize(14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        listContainer.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listView.setEmptyView(emptyView);

        int availableHeight = Math.max(AndroidUtilities.dp(300),
                AndroidUtilities.displaySize.y - AndroidUtilities.dp(190));
        content.addView(listContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, availableHeight));
        return content;
    }

    private void loadInventory() {
        catalogClient.loadMyGifts(new WaysGramGiftCatalogClient.InventoryCallback() {
            @Override
            public void onLoaded(JSONArray gifts) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (isDismissed()) return;
                    giftsBySerial.clear();
                    startedStickerDownloads.clear();
                    for (int i = 0; i < gifts.length(); i++) {
                        JSONObject item = gifts.optJSONObject(i);
                        if (item == null) continue;
                        String serial = item.optString("serial_number", "").trim();
                        if (!serial.isEmpty()) giftsBySerial.put(serial, item);
                    }
                    nativeGifts.clear();
                    nativeGifts.addAll(WaysGramProfileGiftsController.toNativeGifts(gifts));
                    statusView.setVisibility(View.GONE);
                    listView.adapter.update(false);
                    downloadGiftStickers(gifts);
                });
            }

            @Override
            public void onError(String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (isDismissed()) return;
                    statusView.setText("Не удалось загрузить подарки: " + message);
                    statusView.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (nativeGifts.isEmpty()) return;
        // Telegram profile inventory uses the same 12dp top inset and native GiftCell factory.
        items.add(UItem.asSpace(AndroidUtilities.dp(12)));
        for (TL_stars.SavedStarGift gift : nativeGifts) {
            items.add(GiftSheet.GiftCell.Factory.asStarGift(0, gift, true, false, false));
        }
        items.add(UItem.asSpace(AndroidUtilities.dp(24)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof TL_stars.SavedStarGift)) return;
        TL_stars.SavedStarGift savedGift = (TL_stars.SavedStarGift) item.object;
        if (!(savedGift.gift instanceof TL_stars.TL_starGiftUnique)) return;
        String slug = ((TL_stars.TL_starGiftUnique) savedGift.gift).slug;
        if (slug == null || !slug.startsWith("waysgram://")) return;
        JSONObject source = giftsBySerial.get(slug.substring("waysgram://".length()));
        if (source != null && listener != null) {
            listener.onGiftSelected(source);
            dismiss();
        }
    }

    private boolean onItemLongPress(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void downloadGiftStickers(JSONArray inventory) {
        int scheduled = 0;
        for (int i = 0; i < inventory.length() && scheduled < MAX_STICKER_DOWNLOADS; i++) {
            JSONObject item = inventory.optJSONObject(i);
            if (item == null || !item.optBoolean("sticker_available", false)) continue;
            String serial = item.optString("serial_number", "").trim();
            String giftId = item.optString("telegram_gift_id", "").trim();
            if (giftId.isEmpty()) {
                JSONObject telegramGift = item.optJSONObject("telegram_gift");
                giftId = telegramGift == null ? "" : telegramGift.optString("id", "").trim();
            }
            if (serial.isEmpty() || giftId.isEmpty() || !startedStickerDownloads.add(serial)) continue;
            scheduled++;
            final String cacheKey = serial;
            imageClient.downloadTelegramGiftSticker(giftId, new WaysGramApiClient.ImageCallback() {
                @Override
                public void onSuccess(Bitmap bitmap) {
                    if (bitmap == null || bitmap.isRecycled()) return;
                    WaysGramGiftMediaStore.put(cacheKey, bitmap);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!isDismissed() && listView != null && listView.adapter != null) {
                            listView.adapter.update(false);
                        }
                    });
                }

                @Override
                public void onError(String message, int statusCode) {
                    // Keep Telegram's native GiftCell background if no sticker can be loaded.
                }
            });
        }
    }
}
