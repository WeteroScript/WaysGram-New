package org.telegram.messenger.waysgram;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Gifts.ProfileGiftsContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Loads WaysGram-owned gifts into Telegram's native ProfileGiftsContainer.
 * Gift ownership and data stay on the WaysGram API; Telegram is used only
 * for the native profile layout and GiftCell rendering.
 */
public final class WaysGramProfileGiftsController {
    private static final int MAX_VISIBLE_GIFTS = 200;
    private static final int MAX_STICKER_DOWNLOADS = 32;

    private final Context appContext;
    private final BaseFragment fragment;
    private final ProfileGiftsContainer container;
    private final WaysGramSessionStore sessionStore;
    private final WaysGramApiClient imageClient;
    private boolean loading;

    public WaysGramProfileGiftsController(
            Context context,
            BaseFragment fragment,
            ProfileGiftsContainer container
    ) {
        if (context == null || container == null) {
            throw new IllegalArgumentException("WaysGram profile gifts dependencies are required");
        }
        appContext = context.getApplicationContext();
        this.fragment = fragment;
        this.container = container;
        sessionStore = WaysGramSessionStore.getInstance(appContext);
        imageClient = new WaysGramApiClient(sessionStore);
    }

    public void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        WaysGramGiftCatalogClient client = new WaysGramGiftCatalogClient(sessionStore);
        client.loadMyGifts(new WaysGramGiftCatalogClient.InventoryCallback() {
            @Override
            public void onLoaded(JSONArray gifts) {
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    client.close();
                    JSONArray inventory = gifts == null ? new JSONArray() : gifts;
                    ArrayList<TL_stars.SavedStarGift> nativeGifts = toNativeGifts(inventory);
                    WaysGramGiftMediaStore.clear();
                    container.setWaysGramInventory(nativeGifts);
                    downloadGiftStickers(inventory);
                });
            }

            @Override
            public void onError(String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    client.close();
                    showError(message);
                });
            }
        });
    }

    private ArrayList<TL_stars.SavedStarGift> toNativeGifts(JSONArray inventory) {
        ArrayList<TL_stars.SavedStarGift> result = new ArrayList<>();
        int limit = Math.min(inventory.length(), MAX_VISIBLE_GIFTS);
        for (int i = 0; i < limit; i++) {
            JSONObject item = inventory.optJSONObject(i);
            if (item == null) {
                continue;
            }

            String serial = item.optString("serial_number", "").trim();
            if (serial.isEmpty()) {
                serial = "WG-" + item.optString("id", Integer.toString(i + 1));
            }

            long uniqueId = positiveLong(item, "id", stableId(serial));
            long telegramGiftId = positiveLong(item, "telegram_gift_id", uniqueId);
            int serialNumber = serialNumber(item, serial);
            long acquiredAt = Math.max(0L, item.optLong("acquired_at", 0L));

            TL_stars.TL_starGiftUnique nativeGift = new TL_stars.TL_starGiftUnique();
            nativeGift.id = uniqueId;
            nativeGift.gift_id = telegramGiftId;
            nativeGift.title = item.optString("gift_name", "Gift");
            nativeGift.slug = "waysgram://" + serial;
            nativeGift.num = serialNumber;

            TL_stars.TL_savedStarGift savedGift = new TL_stars.TL_savedStarGift();
            savedGift.date = (int) Math.min(Integer.MAX_VALUE, acquiredAt);
            savedGift.gift = nativeGift;
            savedGift.saved_id = uniqueId;
            savedGift.gift_num = serialNumber;
            savedGift.can_upgrade = item.optBoolean("can_upgrade", false);
            result.add(savedGift);
        }
        return result;
    }

    private void downloadGiftStickers(JSONArray inventory) {
        Set<String> started = new HashSet<>();
        int scheduled = 0;
        for (int i = 0; i < inventory.length() && scheduled < MAX_STICKER_DOWNLOADS; i++) {
            JSONObject item = inventory.optJSONObject(i);
            if (item == null || !item.optBoolean("sticker_available", false)) {
                continue;
            }
            String serial = item.optString("serial_number", "").trim();
            String telegramGiftId = item.optString("telegram_gift_id", "").trim();
            if (serial.isEmpty() || telegramGiftId.isEmpty() || !started.add(serial)) {
                continue;
            }
            scheduled++;
            final String cacheKey = serial;
            imageClient.downloadTelegramGiftSticker(telegramGiftId, new WaysGramApiClient.ImageCallback() {
                @Override
                public void onSuccess(Bitmap bitmap) {
                    if (bitmap == null || bitmap.isRecycled()) {
                        return;
                    }
                    WaysGramGiftMediaStore.put(cacheKey, bitmap);
                    container.refreshWaysGramGiftViews();
                }

                @Override
                public void onError(String message, int statusCode) {
                    // Keep Telegram's native GiftCell background when no sticker is available.
                }
            });
        }
    }

    private void showError(String message) {
        String text;
        if (message != null && message.contains("HTTP 401")) {
            text = "Сессия WaysGram недействительна. Войдите в WaysGram повторно.";
        } else if (message != null && message.contains("HTTP 404")) {
            text = "Сервер не поддерживает загрузку инвентаря. Обновите AuthBot на сервере.";
        } else if (message != null && message.contains("HTTP 403")) {
            text = "Сервер запретил доступ к инвентарю WaysGram.";
        } else {
            text = "Не удалось загрузить инвентарь WaysGram: "
                    + (message == null || message.trim().isEmpty() ? "ошибка запроса" : message);
        }
        if (fragment != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, text).show();
        }
    }

    private static long positiveLong(JSONObject object, String key, long fallback) {
        String value = object.optString(key, "").trim();
        if (value.isEmpty()) {
            return fallback > 0 ? fallback : 1L;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : (fallback > 0 ? fallback : 1L);
        } catch (NumberFormatException ignored) {
            return fallback > 0 ? fallback : stableId(value);
        }
    }

    private static long stableId(String value) {
        long result = Integer.toUnsignedLong(value == null ? 0 : value.hashCode()) + 1L;
        return result > 0 ? result : 1L;
    }

    private static int serialNumber(JSONObject item, String serial) {
        String value = item.optString("serial", "").trim();
        if (value.isEmpty()) {
            value = serial.replaceAll("[^0-9]", "");
        }
        try {
            long parsed = Long.parseLong(value);
            return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, parsed));
        } catch (NumberFormatException ignored) {
            return (int) (stableId(serial) & 0x7fffffff);
        }
    }
}
