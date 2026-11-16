package org.telegram.messenger.waysgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Gifts.ProfileGiftsContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Loads WaysGram-owned gifts into Telegram's native ProfileGiftsContainer.
 * Gift ownership and data stay on the WaysGram API; Telegram is used only
 * for the native profile layout and GiftCell rendering.
 */
public final class WaysGramProfileGiftsController {
    private static final int MAX_VISIBLE_GIFTS = 200;
    private static final int MAX_STICKER_DOWNLOADS = MAX_VISIBLE_GIFTS;

    private final Context appContext;
    private final BaseFragment fragment;
    private final ProfileGiftsContainer container;
    private final WaysGramSessionStore sessionStore;
    private final WaysGramApiClient imageClient;
    private boolean loading;
    private boolean loginRedirected;

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
        sessionStore = WaysGramSessionStore.getInstance(appContext,
                fragment == null ? UserConfig.selectedAccount : fragment.getCurrentAccount());
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
                    loginRedirected = false;
                    client.close();
                    JSONArray inventory = gifts == null ? new JSONArray() : gifts;
                    WaysGramGiftMediaStore.clear();
                    ArrayList<TL_stars.SavedStarGift> nativeGifts = toNativeGifts(inventory,
                            fragment == null ? UserConfig.selectedAccount : fragment.getCurrentAccount());
                    container.setWaysGramInventory(nativeGifts);
                    downloadGiftStickers(inventory);
                });
            }

            @Override
            public void onError(String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    client.close();
                    boolean loginRequired = message != null
                            && (message.contains("WaysGram session is missing")
                            || message.contains("HTTP 401"));
                    if (message != null && message.contains("HTTP 401")) {
                        sessionStore.clearSession();
                    }
                    if (loginRequired) {
                        container.setWaysGramInventory(new ArrayList<>());
                        showError(message);
                        if (fragment != null && !loginRedirected
                                && fragment.getParentActivity() != null) {
                            loginRedirected = true;
                            fragment.presentFragment(
                                    new WaysGramLoginFragment(sessionStore.getTelegramAccount()), true);
                        }
                        return;
                    }
                    showError(message);
                });
            }
        });
    }

    public static ArrayList<TL_stars.SavedStarGift> toNativeGifts(JSONArray inventory) {
        return toNativeGifts(inventory, UserConfig.selectedAccount);
    }

    public static ArrayList<TL_stars.SavedStarGift> toNativeGifts(JSONArray inventory, int currentAccount) {
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
            JSONObject telegramGift = item.optJSONObject("telegram_gift");
            String telegramGiftIdText = optText(item, "telegram_gift_id");
            if (telegramGiftIdText.isEmpty()) {
                telegramGiftIdText = optText(telegramGift, "id");
            }
            boolean hasTelegramGiftId = !telegramGiftIdText.isEmpty();
            long telegramGiftId = uniqueId;
            if (hasTelegramGiftId) {
                try {
                    long parsedGiftId = Long.parseLong(telegramGiftIdText);
                    if (parsedGiftId > 0) telegramGiftId = parsedGiftId;
                    else hasTelegramGiftId = false;
                } catch (NumberFormatException ignored) {
                    hasTelegramGiftId = false;
                }
            }
            int serialNumber = serialNumber(item, serial);
            long acquiredAt = Math.max(0L, item.optLong("acquired_at", 0L));
            JSONObject attributes = item.optJSONObject("attributes");
            JSONObject traits = attributes == null ? null : attributes.optJSONObject("collectible_traits");
            int upgradeLevel = item.has("upgrade_level")
                    ? Math.max(0, item.optInt("upgrade_level", 0))
                    : Math.max(0, attributes == null ? 0 : attributes.optInt("upgrade_level", 0));
            String rarity = optText(traits, "rarity");
            String model = optText(traits, "model");
            String symbol = optText(traits, "symbol");
            String backdropName = backdropName(optText(traits, "backdrop"));
            String giftEmoji = optText(item, "gift_emoji");
            if (giftEmoji.isEmpty()) {
                giftEmoji = optText(telegramGift, "emoji");
            }
            String catalogTitle = optText(telegramGift, "title");
            String giftTitle = catalogTitle.isEmpty()
                    ? optText(item, "gift_name")
                    : catalogTitle;
            if ("Подарок Telegram".equalsIgnoreCase(giftTitle)
                    || "Telegram Gift".equalsIgnoreCase(giftTitle)) {
                giftTitle = "";
            }
            if (giftTitle.isEmpty()) {
                giftTitle = giftEmoji.isEmpty() ? "Подарок" : giftEmoji;
            }
            int telegramUpgradePrice = telegramGift == null
                    ? 0 : Math.max(0, telegramGift.optInt("upgrade_star_count", 0));
            int telegramUpgradeVariants = telegramGift == null
                    ? 0 : Math.max(0, telegramGift.optInt("upgrade_variants", 0));
            // Keep paid, chance-based trait upgrades out of the profile detail flow.
            boolean canUpgrade = false;
            boolean isCollectible = upgradeLevel > 0 || (traits != null && traits.length() > 0);
            boolean isListed = optBooleanLike(item, "is_listed");
            boolean isAuctioned = optBooleanLike(item, "is_auctioned");

            WaysGramGiftMediaStore.putMetadata(
                    serial,
                    giftTitle,
                    buildGiftSubtitle(
                            serial, upgradeLevel, rarity, model, symbol, giftEmoji,
                            canUpgrade, telegramUpgradePrice, telegramUpgradeVariants,
                            isListed, isAuctioned
                    ),
                    giftEmoji
            );

            TL_stars.StarGift nativeGift;
            if (isCollectible) {
                TL_stars.TL_starGiftUnique uniqueGift = new TL_stars.TL_starGiftUnique();
                uniqueGift.id = uniqueId;
                uniqueGift.gift_id = telegramGiftId;
                uniqueGift.title = giftTitle;
                uniqueGift.slug = "waysgram://" + serial;
                uniqueGift.num = serialNumber;
                TLRPC.TL_peerUser ownerPeer = new TLRPC.TL_peerUser();
                ownerPeer.user_id = UserConfig.getInstance(currentAccount).getClientUserId();
                uniqueGift.owner_id = ownerPeer;
                uniqueGift.flags |= 1; // owner_id is present in the native unique-gift model.
                TL_stars.starGiftAttributeBackdrop nativeBackdrop = createNativeBackdrop(traits, rarity);
                if (nativeBackdrop != null) {
                    uniqueGift.attributes.add(nativeBackdrop);
                }
                nativeGift = uniqueGift;
            } else {
                TL_stars.TL_starGift regularGift = new TL_stars.TL_starGift();
                regularGift.id = telegramGiftId;
                regularGift.gift_id = telegramGiftId;
                regularGift.title = giftTitle;
                regularGift.slug = "waysgram://" + serial;
                regularGift.can_upgrade = canUpgrade;
                regularGift.upgrade_stars = telegramUpgradePrice;
                regularGift.upgrade_variants = telegramUpgradeVariants;
                nativeGift = regularGift;
            }

            TL_stars.TL_savedStarGift savedGift = new TL_stars.TL_savedStarGift();
            savedGift.date = (int) Math.min(Integer.MAX_VALUE, acquiredAt);
            savedGift.gift = nativeGift;
            savedGift.saved_id = uniqueId;
            savedGift.gift_num = isCollectible ? serialNumber : 0;
            TLRPC.TL_peerUser senderPeer = new TLRPC.TL_peerUser();
            senderPeer.user_id = UserConfig.getInstance(currentAccount).getClientUserId();
            savedGift.from_id = senderPeer;
            savedGift.flags |= 2; // from_id is present; Telegram uses it to identify the owner profile.
            savedGift.can_upgrade = canUpgrade;
            result.add(savedGift);
        }
        return result;
    }

    private static String optText(JSONObject object, String key) {
        if (object == null) {
            return "";
        }
        String value = object.optString(key, "").trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    private static boolean optBooleanLike(JSONObject object, String key) {
        if (object == null) {
            return false;
        }
        Object value = object.opt(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            return "true".equalsIgnoreCase(text) || "1".equals(text);
        }
        return false;
    }

    private static String backdropName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        int colorIndex = value.lastIndexOf('#');
        String name = colorIndex >= 0 ? value.substring(0, colorIndex) : value;
        return name.replace('·', ' ').trim();
    }

    private static String buildGiftSubtitle(
            String serial,
            int upgradeLevel,
            String rarity,
            String model,
            String symbol,
            String giftEmoji,
            boolean canUpgrade,
            int telegramUpgradePrice,
            int telegramUpgradeVariants,
            boolean isListed,
            boolean isAuctioned
    ) {
        StringBuilder firstLine = new StringBuilder(serial)
                .append(" · Ур. ")
                .append(upgradeLevel);
        if (isAuctioned) {
            firstLine.append(" · Аукцион");
        } else if (isListed) {
            firstLine.append(" · Продажа");
        }

        StringBuilder secondLine = new StringBuilder();
        appendPart(secondLine, giftEmoji);
        appendPart(secondLine, rarity);
        appendPart(secondLine, model);
        appendPart(secondLine, symbol);
        if (canUpgrade) {
            appendPart(secondLine, telegramUpgradePrice > 0
                    ? "Апгрейд Telegram · " + telegramUpgradePrice + " Stars"
                    : "Доступно улучшение");
        }
        if (telegramUpgradeVariants > 0) {
            appendPart(secondLine, "Вариантов: " + telegramUpgradeVariants);
        }
        return secondLine.length() == 0
                ? firstLine.toString()
                : firstLine.append('\n').append(secondLine).toString();
    }

    private static void appendPart(StringBuilder target, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (target.length() > 0) {
            target.append(" · ");
        }
        target.append(value.trim());
    }

    private static TL_stars.starGiftAttributeBackdrop createNativeBackdrop(JSONObject traits, String rarityName) {
        String value = optText(traits, "backdrop");
        int hashIndex = value.lastIndexOf('#');
        if (hashIndex < 0) {
            return null;
        }

        int hexStart = hashIndex + 1;
        int hexLength = 0;
        while (hexStart + hexLength < value.length()
                && isHex(value.charAt(hexStart + hexLength))
                && hexLength < 8) {
            hexLength++;
        }
        if (hexLength != 6 && hexLength != 8) {
            return null;
        }

        final int parsedColor;
        try {
            parsedColor = Color.parseColor("#" + value.substring(hexStart, hexStart + hexLength));
        } catch (IllegalArgumentException ignored) {
            return null;
        }

        int center = parsedColor & 0x00FFFFFF;
        int contrast = contrastColor(center);
        String name = backdropName(value);
        if (name.isEmpty()) {
            name = "WaysGram";
        }

        TL_stars.starGiftAttributeBackdrop backdrop = new TL_stars.starGiftAttributeBackdrop();
        backdrop.name = name;
        backdrop.backdrop_id = 0; // WaysGram API exposes the name and color, not Telegram's backdrop ID.
        backdrop.center_color = center;
        backdrop.edge_color = darken(center, 0.78f);
        backdrop.pattern_color = contrast;
        backdrop.text_color = contrast;
        backdrop.rarity = nativeRarity(rarityName);
        return backdrop;
    }

    private static boolean isHex(char value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static int darken(int color, float factor) {
        return Color.rgb(
                Math.max(0, Math.min(255, Math.round(Color.red(color) * factor))),
                Math.max(0, Math.min(255, Math.round(Color.green(color) * factor))),
                Math.max(0, Math.min(255, Math.round(Color.blue(color) * factor)))
        );
    }

    private static int contrastColor(int color) {
        int luminance = (Color.red(color) * 299
                + Color.green(color) * 587
                + Color.blue(color) * 114) / 1000;
        return luminance >= 150 ? 0x00202020 : 0x00FFFFFF;
    }

    private static TL_stars.StarGiftAttributeRarity nativeRarity(String rarityName) {
        String rarity = rarityName == null ? "" : rarityName.trim().toLowerCase(Locale.ROOT);
        if (rarity.equals("необычная") || rarity.equals("uncommon")) {
            return new TL_stars.TL_starGiftAttributeRarityUncommon();
        }
        if (rarity.equals("редкая") || rarity.equals("rare")) {
            return new TL_stars.TL_starGiftAttributeRarityRare();
        }
        if (rarity.equals("эпическая") || rarity.equals("epic")) {
            return new TL_stars.TL_starGiftAttributeRarityEpic();
        }
        if (rarity.equals("легендарная") || rarity.equals("legendary")) {
            return new TL_stars.TL_starGiftAttributeRarityLegendary();
        }
        TL_stars.TL_starGiftAttributeRarity common = new TL_stars.TL_starGiftAttributeRarity();
        common.permille = 0;
        return common;
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
            if (telegramGiftId.isEmpty()) {
                JSONObject telegramGift = item.optJSONObject("telegram_gift");
                telegramGiftId = optText(telegramGift, "id");
            }
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
        if (message != null && (message.contains("HTTP 401")
                || message.contains("WaysGram session is missing"))) {
            text = "Для этого аккаунта требуется войти в WaysGram, чтобы открыть инвентарь подарков.";
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
