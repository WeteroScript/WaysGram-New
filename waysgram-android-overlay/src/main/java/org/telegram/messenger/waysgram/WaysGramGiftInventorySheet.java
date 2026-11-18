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
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Gifts.GiftSheet;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayDeque;
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

    private final WaysGramGiftCatalogClient catalogClient;
    private final int telegramAccount;
    private final WaysGramApiClient imageClient;
    private final Theme.ResourcesProvider resourcesProvider;
    private final GiftSelectedListener listener;
    private final ArrayList<TL_stars.SavedStarGift> nativeGifts = new ArrayList<>();
    private final Map<String, JSONObject> giftsBySerial = new HashMap<>();
    private final Set<String> startedStickerDownloads = new HashSet<>();

    private String pendingOpenSerial;
    private final ArrayDeque<String> pendingStickerDownloads = new ArrayDeque<>();
    private boolean stickerDownloadActive;
    private LinearLayout contentView;
    private UniversalRecyclerView listView;
    private TextView emptyView;
    private TextView statusView;

    public WaysGramGiftInventorySheet(
            Context context,
            WaysGramGiftCatalogClient.SessionProvider sessionProvider,
            int telegramAccount,
            Theme.ResourcesProvider resourcesProvider,
            GiftSelectedListener listener
    ) {
        super(context, false, resourcesProvider);
        this.resourcesProvider = resourcesProvider;
        this.listener = listener;
        this.telegramAccount = telegramAccount;
        catalogClient = new WaysGramGiftCatalogClient(sessionProvider);
        imageClient = new WaysGramApiClient(sessionProvider);

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
                context, telegramAccount, 0, false,
                this::fillItems, this::onItemClick, this::onItemLongPress,
                resourcesProvider, 3, LinearLayoutManager.VERTICAL);
        listView.adapter.setApplyBackground(false);
        listView.setSelectorType(9);
        listView.setSelectorDrawableColor(0);
        listView.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(12),
                AndroidUtilities.dp(9), AndroidUtilities.dp(16));
        listView.setClipToPadding(false);
        listView.setClipChildren(false);
        // Cells change only when their own artwork arrives; the default change animation made the
        // grid fade rows in and out while stickers were still loading.
        listView.setItemAnimator(null);
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

        // The list takes whatever height the sheet gives us (see onCustomMeasure). A fixed
        // height larger than the sheet used to clip the tail of the grid, so the last gifts could
        // not be reached by scrolling at all.
        content.addView(listContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        contentView = content;
        return content;
    }

    /**
     * Sizes the WaysGram content to the space the sheet really has.
     *
     * <p>A bottom sheet measures its custom view with {@code AT_MOST} and the gift grid used to
     * keep its own fixed height, so with short screens the tail of the grid fell outside the sheet
     * and could not be reached by scrolling. Here the content height is clamped to the container
     * the sheet actually occupies, and the grid fills what is left under the header.</p>
     */
    @Override
    protected void onContainerLayout(int left, int top, int right, int bottom) {
        super.onContainerLayout(left, top, right, bottom);
        if (contentView == null) {
            return;
        }
        ViewGroup.LayoutParams params = contentView.getLayoutParams();
        if (params == null) {
            return;
        }
        int containerHeight = Math.max(0, bottom - top);
        // Room for the sheet title, its paddings and the system insets.
        int reserve = containerHeight > 0
                ? Math.min(AndroidUtilities.displaySize.y - AndroidUtilities.dp(120),
                           containerHeight - AndroidUtilities.dp(48))
                : AndroidUtilities.displaySize.y - AndroidUtilities.dp(150);
        int height = Math.max(AndroidUtilities.dp(320), reserve);
        if (params.height == height) {
            return;
        }
        params.height = height;
        contentView.setLayoutParams(params);
        contentView.requestLayout();
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (nativeGifts.isEmpty()) return;
        // Telegram profile inventory uses the same 12dp top inset and native GiftCell factory.
        items.add(UItem.asSpace(AndroidUtilities.dp(12)));
        for (TL_stars.SavedStarGift gift : nativeGifts) {
            items.add(GiftSheet.GiftCell.Factory.asStarGift(0, gift, true, false, false));
        }
        items.add(UItem.asSpace(AndroidUtilities.dp(24 + 48 + 10)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof TL_stars.SavedStarGift)) return;
        TL_stars.SavedStarGift savedGift = (TL_stars.SavedStarGift) item.object;
        if (savedGift.gift == null) return;
        String slug = savedGift.gift.slug;
        if (slug == null || !slug.startsWith("waysgram://")) return;
        JSONObject source = giftsBySerial.get(slug.substring("waysgram://".length()));
        if (source != null && listener != null) {
            listener.onGiftSelected(source);
            dismiss();
        }
    }

    /**
     * Opens the card of one gift as soon as the inventory is available.
     *
     * <p>Used by «Следующий подарок» in the upgrade reveal: the sheet loads, jumps straight to
     * the requested gift and closes itself once the card is open.</p>
     */
    public void openGiftWhenLoaded(String serial) {
        pendingOpenSerial = serial == null ? null : serial.trim();
        openPendingGift();
    }

    private void openPendingGift() {
        if (pendingOpenSerial == null || pendingOpenSerial.isEmpty() || listener == null) {
            return;
        }
        JSONObject gift = giftsBySerial.get(pendingOpenSerial);
        if (gift == null) {
            for (Map.Entry<String, JSONObject> entry : giftsBySerial.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(pendingOpenSerial)) {
                    gift = entry.getValue();
                    break;
                }
            }
        }
        if (gift == null) {
            return;
        }
        pendingOpenSerial = null;
        listener.onGiftSelected(gift);
        dismiss();
    }

    private boolean onItemLongPress(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** Re-arms a single grid cell so it picks up freshly downloaded artwork. */
    private void refreshGiftCell(String serial) {
        if (isDismissed() || listView == null || listView.adapter == null) {
            return;
        }
        for (int i = 0; i < nativeGifts.size(); i++) {
            TL_stars.SavedStarGift gift = nativeGifts.get(i);
            if (gift.gift != null && gift.gift.slug != null
                    && gift.gift.slug.equals("waysgram://" + serial)) {
                // The first row is the 12 dp top spacer added in fillItems.
                listView.adapter.notifyItemChanged(i + 1);
                return;
            }
        }
    }

    /**
     * Downloads at most one gift sticker at a time.
     *
     * <p>Downloading every sticker of the inventory at once kept the decoder busy and was the
     * second half of the jitter: the grid could not keep a steady frame while the user scrolled.</p>
     */
    private void pumpStickerDownloads() {
        if (stickerDownloadActive || pendingStickerDownloads.isEmpty() || isDismissed()) {
            return;
        }
        String serial = pendingStickerDownloads.pollFirst();
        String giftId = pendingStickerDownloads.pollFirst();
        if (serial == null || giftId == null) {
            return;
        }
        stickerDownloadActive = true;
        downloadGiftSticker(serial, giftId);
    }

    private void downloadGiftStickers(JSONArray inventory) {
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject item = inventory.optJSONObject(i);
            if (item == null || !item.optBoolean("sticker_available", false)) continue;
            String serial = item.optString("serial_number", "").trim();
            String giftId = item.optString("telegram_gift_id", "").trim();
            if (giftId.isEmpty()) {
                JSONObject telegramGift = item.optJSONObject("telegram_gift");
                giftId = telegramGift == null ? "" : telegramGift.optString("id", "").trim();
            }
            if (serial.isEmpty() || giftId.isEmpty() || !startedStickerDownloads.add(serial)) continue;
            pendingStickerDownloads.addLast(serial);
            pendingStickerDownloads.addLast(giftId);
        }
        pumpStickerDownloads();
    }

    private void downloadGiftSticker(final String serial, String giftId) {
        imageClient.downloadTelegramGiftSticker(giftId, new WaysGramApiClient.ImageCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    WaysGramGiftMediaStore.put(serial, bitmap);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    // Only this one cell is rebound: a full adapter update restarted every
                    // visible gift animation on each download, which looked like jitter.
                    refreshGiftCell(serial);
                    stickerDownloadActive = false;
                    pumpStickerDownloads();
                });
            }

            @Override
            public void onError(String message, int statusCode) {
                // Keep Telegram's native GiftCell background when no sticker can be loaded.
                AndroidUtilities.runOnUIThread(() -> {
                    stickerDownloadActive = false;
                    pumpStickerDownloads();
                });
            }
        });
    }

}
