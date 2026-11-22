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
    private final Context appContext;
    private final BaseFragment fragment;
    private final ProfileGiftsContainer container;
    private final long ownerId;
    private final WaysGramSessionStore sessionStore;
    private final WaysGramApiClient imageClient;
    private boolean loading;
    private boolean pendingRefresh;
    private boolean editingOrder;
    private boolean savingOrder;
    private boolean inventoryRebuildScheduled;
    /** Share of the paid price WaysGram refunds on a sale, in per cent. */
    private static final int SELL_REFUND_PERCENT = 50;
    private final WaysGramGiftAttributes.Listener attributesListener;
    private final Runnable refreshListener;
    private boolean destroyed;
    private JSONArray lastInventory;
    private int lastGiftAccount = UserConfig.selectedAccount;
    private boolean metadataRefreshScheduled;

    public WaysGramProfileGiftsController(
            Context context,
            BaseFragment fragment,
            ProfileGiftsContainer container,
            long ownerId
    ) {
        if (context == null || container == null) {
            throw new IllegalArgumentException("WaysGram profile gifts dependencies are required");
        }
        appContext = context.getApplicationContext();
        this.fragment = fragment;
        this.container = container;
        this.ownerId = ownerId;
        sessionStore = WaysGramSessionStore.getInstance(appContext,
                fragment == null ? UserConfig.selectedAccount : fragment.getCurrentAccount());
        WaysGramGiftMediaStore.configure(appContext);
        imageClient = new WaysGramApiClient(sessionStore);
        WaysGramGiftAttributes.attach(appContext, fragment == null
                ? UserConfig.selectedAccount : fragment.getCurrentAccount());
        // Official Telegram model and pattern animations arrive one by one. A card can only
        // show the real model once its document is on disk, so the inventory is rebuilt once
        // (debounced) instead of stamping the gift's own sticker into the model slot.
        attributesListener = new WaysGramGiftAttributes.Listener() {
            @Override
            public void onGiftAttributesChanged(String changedGiftId) {
                scheduleInventoryRebuild();
            }

            @Override
            public void onGiftMetadataLoaded(String giftId) {
                // Old gifts can gain document IDs on the server after the initial inventory
                // response. Reload it once metadata arrives rather than rebuilding stale JSON.
                if (metadataRefreshScheduled) return;
                metadataRefreshScheduled = true;
                AndroidUtilities.runOnUIThread(() -> {
                    metadataRefreshScheduled = false;
                    if (lastInventory != null) refresh();
                }, 1000L);
            }
        };
        container.setWaysGramOrderListener(new ProfileGiftsContainer.WaysGramOrderListener() {
            @Override public boolean canEdit() {
                return !destroyed && !loading && !savingOrder && lastInventory != null;
            }
            @Override public void onEditingChanged(boolean editing) {
                editingOrder = editing;
                if (!editing && !savingOrder && pendingRefresh) {
                    pendingRefresh = false;
                    refresh();
                }
            }
            @Override public void onOrderChanged(ArrayList<Long> ids) { saveOrder(ids); }
        });
        WaysGramGiftAttributes.addListener(attributesListener);
        // An upgrade started from the native gift sheet must reload this inventory.
        refreshListener = this::refresh;
        if (ownerId == UserConfig.getInstance(fragment == null ? UserConfig.selectedAccount : fragment.getCurrentAccount()).getClientUserId()) {
            WaysGramGiftUpgrade.setRefreshListener(refreshListener);
        }
    }

    private Runnable inventoryVisibilityChanged;
    private boolean hasVisibleGifts;

    public boolean hasVisibleGifts() {
        return hasVisibleGifts;
    }

    public void setInventoryVisibilityChanged(Runnable listener) {
        inventoryVisibilityChanged = listener;
    }

    public void dispose() {
        destroyed = true;
        WaysGramGiftAttributes.removeListener(attributesListener);
        WaysGramGiftUpgrade.clearRefreshListener(refreshListener);
    }

    public void refresh() {
        if (destroyed) return;
        if (editingOrder || savingOrder) {
            pendingRefresh = true;
            return;
        }
        if (loading) {
            // An upgrade (or a sale) that lands while the inventory is already loading must
            // not be lost: the fresh data is requested again right after this one.
            pendingRefresh = true;
            return;
        }
        loading = true;
        // A download that failed earlier is retried on every refresh, so the rolled model
        // and pattern always end up on the card instead of staying hidden for the session.
        WaysGramGiftAttributes.retryFailed();
        WaysGramGiftCatalogClient client = new WaysGramGiftCatalogClient(sessionStore);
        client.loadGifts(ownerId, new WaysGramGiftCatalogClient.InventoryCallback() {
            @Override
            public void onLoaded(JSONArray gifts) {
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    client.close();
                    if (destroyed) return;
                    JSONArray inventory = gifts == null ? new JSONArray() : gifts;
                    boolean wasLoaded = lastInventory != null;
                    boolean changed = !wasLoaded || !lastInventory.toString().equals(inventory.toString());
                    lastInventory = inventory;
                    lastGiftAccount = fragment == null
                            ? UserConfig.selectedAccount : fragment.getCurrentAccount();
                    // Still frames and animated documents stay cached: dropping them makes every
                    // card decode its artwork again, which reads as flicker in the grid.
                    // Inventory cards show artwork only: no serial, level or caption on them.
                    ArrayList<TL_stars.SavedStarGift> nativeGifts = toNativeGifts(inventory,
                            fragment == null ? UserConfig.selectedAccount : fragment.getCurrentAccount(),
                            false);
                    updateProfileCover(nativeGifts);
                    if (!wasLoaded) {
                        container.setWaysGramInventory(nativeGifts);
                    } else if (changed) {
                        container.replaceWaysGramInventory(nativeGifts);
                    }
                    boolean visible = !nativeGifts.isEmpty();
                    if (hasVisibleGifts != visible) {
                        hasVisibleGifts = visible;
                        if (inventoryVisibilityChanged != null) inventoryVisibilityChanged.run();
                    }
                    downloadGiftAnimations(inventory);
                    if (pendingRefresh) {
                        pendingRefresh = false;
                        refresh();
                    }
                });
            }

            @Override
            public void onError(String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    client.close();
                    if (destroyed) return;
                    if (pendingRefresh) {
                        pendingRefresh = false;
                        refresh();
                    }
                    // Never navigate the user away to a login screen from here. A missing or
                    // expired WaysGram session for this Telegram account is reported inline
                    // only; the account's own session is left untouched so a wrong/expired
                    // session on account A can never force-close or redirect a screen that
                    // the user opened on account B.
                    showError(message);
                });
            }
        });
    }

    public static ArrayList<TL_stars.SavedStarGift> toNativeGifts(JSONArray inventory) {
        return toNativeGifts(inventory, UserConfig.selectedAccount);
    }

    public static ArrayList<TL_stars.SavedStarGift> toNativeGifts(JSONArray inventory, int currentAccount) {
        return toNativeGifts(inventory, currentAccount, false);
    }

    public static ArrayList<TL_stars.SavedStarGift> toNativeGifts(
            JSONArray inventory, int currentAccount, boolean showSerialAsTitle
    ) {
        ArrayList<TL_stars.SavedStarGift> result = new ArrayList<>();
        for (int i = 0; i < inventory.length(); i++) {
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
            // Telegram numbers every collection separately; the server hands out
            // "1" for the first upgraded Plush Pepe, "1" again for the first Sakura
            // Flower and increments per upgrade of the same gift type.
            int collectionNumber = Math.max(0, item.optInt("unique_num", 0));
            long acquiredAt = Math.max(0L, item.optLong("acquired_at", 0L));
            JSONObject attributes = item.optJSONObject("attributes");
            JSONObject traits = attributes == null ? null : attributes.optJSONObject("collectible_traits");
            WaysGramGiftTraits.remember(currentAccount, serial, traits);
            int upgradeLevel = item.has("upgrade_level")
                    ? Math.max(0, item.optInt("upgrade_level", 0))
                    : Math.max(0, attributes == null ? 0 : attributes.optInt("upgrade_level", 0));
            // v3 traits keep the official Telegram pools: model, pattern and backdrop
            // are objects with their name, published chance and rarity class.
            String rarityKey = optText(traits, "rarity");
            String model = traitName(traits, "model");
            String pattern = traitName(traits, "pattern");
            if (pattern.isEmpty()) {
                pattern = traitName(traits, "symbol");
            }
            String modelRarity = traitRarity(traits, "model", rarityKey);
            String patternRarity = traitRarity(traits, "pattern", rarityKey);
            String rarity = rarityLabel(rarityKey);
            String backdropName = backdropLabel(traits, rarityKey);
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
            boolean isCollectible = upgradeLevel > 0 || (traits != null && traits.length() > 0);
            int sellRefund = Math.max(0, item.optInt("sell_refund", 0));
            // The WaysGram API refunds a share of the paid price, so the price itself is the
            // refund scaled back up; the native card shows it in its value row.
            int paidPrice = sellRefund > 0
                    ? (int) Math.min(Integer.MAX_VALUE, (long) sellRefund * 100L / Math.max(1, SELL_REFUND_PERCENT))
                    : 0;
            boolean isListed = optBooleanLike(item, "is_listed");
            boolean isAuctioned = optBooleanLike(item, "is_auctioned");
            boolean canUpgrade = !isCollectible && !isListed && !isAuctioned && optBooleanLike(item, "can_upgrade");

            WaysGramGiftMediaStore.putMetadata(
                    serial,
                    showSerialAsTitle ? waysGramTitle(serial, uniqueId) : giftTitle,
                    showSerialAsTitle
                            ? "Уровень " + upgradeLevel
                            : buildGiftSubtitle(
                                    serial, upgradeLevel, rarity, model, pattern, giftEmoji,
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
                uniqueGift.num = collectionNumber > 0 ? collectionNumber : serialNumber;
                // Inventory counts come from the WaysGram storefront, not Telegram's external supply.
                int supplyTotal = item.optInt("shop_supply", 0);
                int supplyRemaining = item.optInt("shop_remaining", -1);
                if (supplyTotal > 0 && supplyTotal < 2_000_000_000 && supplyRemaining >= 0) {
                    uniqueGift.availability_total = supplyTotal;
                    uniqueGift.availability_issued = supplyTotal - Math.min(supplyTotal, supplyRemaining);
                }
                TLRPC.TL_peerUser ownerPeer = new TLRPC.TL_peerUser();
                ownerPeer.user_id = item.optLong("owner_id", UserConfig.getInstance(currentAccount).getClientUserId());
                uniqueGift.owner_id = ownerPeer;
                uniqueGift.flags |= 1; // owner_id is present in the native unique-gift model.
                TL_stars.starGiftAttributeBackdrop nativeBackdrop =
                        createNativeBackdrop(traits, rarityKey);
                if (nativeBackdrop != null) {
                    uniqueGift.attributes.add(nativeBackdrop);
                }
                // Model and symbol are rolled once by the server for this collectible and never
                // change. Telegram publishes every model and pattern as its own animation, so the
                // native card plays the official art once it is downloaded.
                if (!telegramGiftIdText.isEmpty() && traits != null) {
                    WaysGramGiftAttributes.wantRolled(telegramGiftIdText, traits);
                }
                // The rolled traits carry the Telegram document id, so the official model and
                // pattern animations are used even if Telegram localises their names.
                //
                // A document that is not on disk yet must never be replaced by the gift's own
                // sticker: that turned every upgraded gift back into its stock artwork and put a
                // square sticker layer behind the model. The attribute is simply left out until
                // the download lands and the inventory is rebuilt.
                TLRPC.TL_document modelDocument = WaysGramGiftAttributes.traitDocument(
                        telegramGiftIdText, WaysGramGiftAttributes.MODEL, traits, "model"
                );
                if (modelDocument == null && WaysGramBundledArtwork.isBundledName(model)) {
                    // Анимация эксклюзивной модели WaysGram лежит в приложении: даже если
                    // сеть ещё не отдала документ, подарок не должен оставаться без модели.
                    modelDocument = WaysGramBundledArtwork.document();
                }
                TLRPC.TL_document patternDocument = WaysGramGiftAttributes.traitDocument(
                        telegramGiftIdText, WaysGramGiftAttributes.PATTERN, traits, "pattern"
                );
                if (modelDocument != null) {
                    TL_stars.starGiftAttributeModel nativeModel = new TL_stars.starGiftAttributeModel();
                    nativeModel.name = model.isEmpty() ? "Модель" : model;
                    nativeModel.document = modelDocument;
                    nativeModel.rarity = nativeRarity(traitEntry(traits, "model"), modelRarity);
                    uniqueGift.attributes.add(nativeModel);
                }
                if (patternDocument != null) {
                    TL_stars.starGiftAttributePattern nativePattern = new TL_stars.starGiftAttributePattern();
                    nativePattern.name = pattern.isEmpty() ? "Узор" : pattern;
                    nativePattern.document = patternDocument;
                    nativePattern.rarity = nativeRarity(traitEntry(traits, "pattern"), patternRarity);
                    uniqueGift.attributes.add(nativePattern);
                }
                JSONObject original = attributes == null ? null : attributes.optJSONObject("original_details");
                if (original != null && original.optLong("recipient_id", 0) > 0) {
                    TL_stars.starGiftAttributeOriginalDetails details = new TL_stars.starGiftAttributeOriginalDetails();
                    TLRPC.TL_peerUser recipient = new TLRPC.TL_peerUser();
                    recipient.user_id = original.optLong("recipient_id");
                    details.recipient_id = recipient;
                    long sender = original.optLong("sender_id", 0);
                    if (sender > 0) {
                        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                        peer.user_id = sender;
                        details.sender_id = peer;
                        details.flags |= 1;
                    }
                    details.date = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, original.optLong("date", 0)));
                    String comment = optText(original, "message");
                    if (!comment.isEmpty()) {
                        details.message = new TLRPC.TL_textWithEntities();
                        details.message.text = comment;
                        details.flags |= 2;
                    }
                    uniqueGift.attributes.add(details);
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
                // Telegram's own gift cells draw the sticker of the gift. Handing them the
                // WaysGram artwork document is what makes the grid, the chat card and the saved
                // gift sheet look exactly like the official client, animation included.
                regularGift.sticker = WaysGramGiftDocuments.documentFor(telegramGiftIdText, giftEmoji);
                regularGift.stars = paidPrice;
                regularGift.convert_stars = sellRefund;
                nativeGift = regularGift;
            }

            TL_stars.TL_savedStarGift savedGift = new TL_stars.TL_savedStarGift();
            savedGift.date = (int) Math.min(Integer.MAX_VALUE, acquiredAt);
            savedGift.gift = nativeGift;
            savedGift.saved_id = uniqueId;
            savedGift.gift_num = isCollectible
                    ? (collectionNumber > 0 ? collectionNumber : serialNumber) : 0;
            long senderId = isCollectible ? 0 : item.optLong("sender_id", 0);
            if (senderId > 0) {
                JSONObject senderProfile = item.optJSONObject("sender");
                if (senderProfile != null && org.telegram.messenger.MessagesController.getInstance(currentAccount).getUser(senderId) == null) {
                    org.telegram.messenger.MessagesController.getInstance(currentAccount).putUser(
                            org.telegram.tgnet.WaysGramMapper.toUser(senderProfile), false);
                }
                TLRPC.TL_peerUser senderPeer = new TLRPC.TL_peerUser();
                senderPeer.user_id = senderId;
                savedGift.from_id = senderPeer;
                savedGift.flags |= 2;
            } else {
                savedGift.name_hidden = true;
            }
            String giftMessage = isCollectible ? "" : optText(item, "gift_message");
            if (!giftMessage.isEmpty()) {
                savedGift.message = new TLRPC.TL_textWithEntities();
                savedGift.message.text = giftMessage;
                savedGift.flags |= 4;
            }
            savedGift.pinned_to_top = isCollectible && optBooleanLike(item, "profile_pinned");
            savedGift.unsaved = optBooleanLike(item, "profile_hidden");
            savedGift.can_upgrade = canUpgrade;
            savedGift.convert_stars = optBooleanLike(item, "sellable") ? sellRefund : 0;
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
        StringBuilder firstLine = new StringBuilder();
        if (isAuctioned) {
            firstLine.append("Аукцион");
        } else if (isListed) {
            firstLine.append("Продажа");
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
        if (firstLine.length() == 0) {
            return secondLine.toString();
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

    private static TL_stars.starGiftAttributeBackdrop createNativeBackdrop(
            JSONObject traits, String rarityKey
    ) {
        JSONObject backdropEntry = traitEntry(traits, "backdrop");
        if (backdropEntry != null) {
            int center = backdropEntry.optInt("center_color", 0) & 0x00FFFFFF;
            int edge = backdropEntry.optInt("edge_color", 0) & 0x00FFFFFF;
            if (center != 0 || edge != 0) {
                String backdropTitle = optText(backdropEntry, "name");
                TL_stars.starGiftAttributeBackdrop nativeBackdrop =
                        new TL_stars.starGiftAttributeBackdrop();
                nativeBackdrop.name = backdropTitle.isEmpty() ? "WaysGram" : backdropTitle;
                // Telegram ships the four colours with every backdrop; WaysGram keeps
                // exactly the ones the official catalog published for it.
                nativeBackdrop.backdrop_id = Math.max(0, backdropEntry.optInt("backdrop_id", 0));
                nativeBackdrop.center_color = center;
                nativeBackdrop.edge_color = edge != 0 ? edge : darken(center, 0.78f);
                nativeBackdrop.pattern_color =
                        backdropEntry.optInt("pattern_color", 0) & 0x00FFFFFF;
                nativeBackdrop.text_color = backdropEntry.optInt("text_color", 0) & 0x00FFFFFF;
                // No contrast fallback here: Telegram draws pattern_color as a flat square
                // behind the model, which is the pale box that appeared behind every gift.
                if (nativeBackdrop.text_color == 0) {
                    nativeBackdrop.text_color = contrastColor(center);
                }
                nativeBackdrop.rarity = nativeRarity(backdropEntry, backdropEntry.optString("rarity", rarityKey));
                return nativeBackdrop;
            }
        }
        // Legacy (v1) traits stored the colour inside the name, "Аметистовый · #7C4DFF".
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
        // Legacy backdrops carry no pattern colour: the pale square behind the model was this
        // invented value, so it stays empty and only the text colour keeps its contrast.
        backdrop.pattern_color = 0;
        backdrop.text_color = contrast;
        backdrop.rarity = nativeRarity(rarityKey);
        return backdrop;
    }

    /** Trait entry as an object; v1 kept plain strings, so they are skipped here. */
    private static JSONObject traitEntry(JSONObject traits, String key) {
        if (traits == null) {
            return null;
        }
        JSONObject entry = traits.optJSONObject(key);
        return entry;
    }

    /** Name of a trait, either from the v3 object or from the legacy string. */
    private static String traitName(JSONObject traits, String key) {
        JSONObject entry = traitEntry(traits, key);
        if (entry != null) {
            return optText(entry, "name");
        }
        return optText(traits, key);
    }

    /** Rarity class of one attribute, falling back to the rarity of the whole card. */
    private static String traitRarity(JSONObject traits, String key, String fallback) {
        JSONObject entry = traitEntry(traits, key);
        String rarity = entry == null ? "" : optText(entry, "rarity");
        if (!rarity.isEmpty()) {
            return rarity;
        }
        return fallback == null ? "" : fallback;
    }

    /** Russian label of a rarity class, shown in the inventory subtitle. */
    private static String rarityLabel(String rarityKey) {
        String rarity = rarityKey == null ? "" : rarityKey.trim().toLowerCase(Locale.ROOT);
        if (rarity.equals("uncommon") || rarity.equals("необычная")) {
            return "Необычная";
        }
        if (rarity.equals("rare") || rarity.equals("редкая")) {
            return "Редкая";
        }
        if (rarity.equals("epic") || rarity.equals("эпическая")) {
            return "Эпическая";
        }
        return rarity.equals("legendary") || rarity.equals("легендарная")
                ? "Легендарная" : "Обычная";
    }

    /** Backdrop name shown in the inventory subtitle. */
    private static String backdropLabel(JSONObject traits, String rarityKey) {
        JSONObject backdrop = traitEntry(traits, "backdrop");
        if (backdrop != null) {
            return optText(backdrop, "name");
        }
        return backdropName(optText(traits, "backdrop"));
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

    /**
     * Artwork document used by the model and symbol attributes. It is built from the cached
     * sticker file when the download already happened, so the traits never require a rebind of
     * the whole inventory (a rebind restarts the playing animations).
     */
    private static TLRPC.TL_document traitDocument(
            String serial, String telegramGiftId, String emoji
    ) {
        TLRPC.TL_document document = WaysGramGiftMediaStore.getAnimationDocument(serial);
        if (document != null) {
            return document;
        }
        long documentId = WaysGramGiftDocuments.documentId(telegramGiftId);
        java.io.File cached = documentId == 0L ? null : WaysGramGiftDocuments.stickerFile(documentId);
        if (cached == null || !cached.isFile() || cached.length() == 0) {
            return null;
        }
        return WaysGramGiftDocuments.buildDocument(documentId, cached, emoji);
    }

    private static TL_stars.StarGiftAttributeRarity nativeRarity(JSONObject trait, String rarityName) {
        int permille = trait == null ? 0 : trait.optInt("permille", 0);
        if (permille > 0) {
            TL_stars.TL_starGiftAttributeRarity chance = new TL_stars.TL_starGiftAttributeRarity();
            chance.permille = Math.min(1000, permille);
            return chance;
        }
        return nativeRarity(rarityName);
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
        // Current TL attributes require a non-null rarity when persisted. -1 means
        // unknown, and the WaysGram sheet hides it rather than claiming a probability.
        TL_stars.TL_starGiftAttributeRarity unknown = new TL_stars.TL_starGiftAttributeRarity();
        unknown.permille = -1;
        return unknown;
    }

    /** Downloads the animated artwork of one gift; the still frame stays as its placeholder. */
    private void downloadGiftAnimation(String serial, String telegramGiftId, String emoji) {
        if (serial == null || serial.isEmpty() || telegramGiftId == null || telegramGiftId.isEmpty()) {
            return;
        }
        final String cacheKey = serial;
        final String giftEmoji = emoji == null ? "" : emoji;
        final long documentId = WaysGramGiftDocuments.documentId(telegramGiftId);
        if (documentId == 0L) {
            return;
        }
        java.io.File cached = WaysGramGiftDocuments.stickerFile(documentId);
        if (cached != null && cached.isFile() && cached.length() > 0) {
            applyAnimationDocument(cacheKey, documentId, cached, giftEmoji);
            return;
        }
        imageClient.downloadTelegramGiftStickerBytes(
                telegramGiftId,
                true,
                new WaysGramApiClient.StickerBytesCallback() {
                    @Override
                    public void onSuccess(byte[] bytes, String mimeType) {
                        java.io.File stored = WaysGramGiftDocuments.storeSticker(documentId, bytes);
                        if (stored == null) {
                            return;
                        }
                        applyAnimationDocument(cacheKey, documentId, stored, giftEmoji);
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        // Some catalog items only have a still image; do not leave them blank.
                        imageClient.downloadTelegramGiftStickerBytes(telegramGiftId, false,
                                new WaysGramApiClient.StickerBytesCallback() {
                                    @Override public void onSuccess(byte[] bytes, String mimeType) {
                                        java.io.File stored = WaysGramGiftDocuments.storeSticker(documentId, bytes);
                                        if (stored != null) applyAnimationDocument(cacheKey, documentId, stored, giftEmoji);
                                    }
                                    @Override public void onError(String error, int status) { }
                                });
                    }
                }
        );
    }

    /**
     * Makes animated (TGS/WebM) gift artwork playable in the native gift grid.
     *
     * <p>The original animation is stored in the shared WaysGram sticker folder and exposed to
     * Telegram as a sticker document, so the native GiftCell decodes and loops it exactly as it
     * does for a Telegram gift. Nothing draws the cells by hand any more.</p>
     */
    private void downloadGiftAnimations(JSONArray inventory) {
        Set<String> started = new HashSet<>();
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject item = inventory.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String serial = item.optString("serial_number", "").trim();
            String telegramGiftId = item.optString("telegram_gift_id", "").trim();
            JSONObject telegramGift = item.optJSONObject("telegram_gift");
            if (telegramGiftId.isEmpty()) {
                telegramGiftId = optText(telegramGift, "id");
            }
            if (serial.isEmpty() || telegramGiftId.isEmpty() || !started.add(serial)) {
                continue;
            }
            String emoji = optText(item, "gift_emoji");
            if (emoji.isEmpty()) {
                emoji = optText(telegramGift, "emoji");
            }
            downloadGiftAnimation(serial, telegramGiftId, emoji);
        }
    }

    private void applyAnimationDocument(
            String serial, long documentId, java.io.File file, String emoji
    ) {
        org.telegram.tgnet.TLRPC.TL_document document =
                WaysGramGiftDocuments.buildDocument(documentId, file, emoji);
        if (document == null) {
            return;
        }
        org.telegram.tgnet.TLRPC.TL_document previous = WaysGramGiftMediaStore.getAnimationDocument(serial);
        if (previous != null && previous.id == document.id && java.util.Objects.equals(previous.mime_type, document.mime_type)) return;
        WaysGramGiftMediaStore.putAnimationDocument(serial, document);
        // The document is now on disk, so the native card can play it: rebuild the inventory
        // once (debounced) instead of rebinding a cell by hand.
        scheduleInventoryRebuild();
    }

    /**
     * Model and pattern animations arrive one by one. Rebuilding the whole inventory for each of
     * them makes the grid flicker, so the rebuild is collapsed into a single pass and the pages
     * keep their scroll position.
     */
    private void scheduleInventoryRebuild() {
        if (inventoryRebuildScheduled) {
            return;
        }
        inventoryRebuildScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            inventoryRebuildScheduled = false;
            rebuildInventory();
        }, 600L);
    }

    /** Rebuilds the native cards of the current inventory, keeping the scroll position. */
    private void rebuildInventory() {
        if (destroyed || container == null || lastInventory == null) {
            return;
        }
        if (editingOrder || savingOrder) {
            pendingRefresh = true;
            return;
        }
        ArrayList<TL_stars.SavedStarGift> nativeGifts = toNativeGifts(lastInventory, lastGiftAccount, false);
        container.replaceWaysGramInventory(nativeGifts);
        updateProfileCover(nativeGifts);
    }

    private void updateProfileCover(ArrayList<TL_stars.SavedStarGift> nativeGifts) {
        if (fragment instanceof org.telegram.ui.ProfileActivity) {
            org.telegram.ui.Stars.ProfileGiftsView cover = ((org.telegram.ui.ProfileActivity) fragment).giftsView;
            org.telegram.messenger.MessagesController messages = org.telegram.messenger.MessagesController.getInstance(lastGiftAccount);
            TLRPC.User user = messages.getUser(ownerId);
            if (user != null && user.emoji_status instanceof TLRPC.TL_emojiStatusCollectible) {
                long wornId = ((TLRPC.TL_emojiStatusCollectible) user.emoji_status).collectible_id;
                for (TL_stars.SavedStarGift saved : nativeGifts) {
                    if (saved.gift.id != wornId || !(saved.gift instanceof TL_stars.TL_starGiftUnique)) continue;
                    for (TL_stars.StarGiftAttribute attribute : ((TL_stars.TL_starGiftUnique) saved.gift).attributes) {
                        if (attribute instanceof TL_stars.starGiftAttributePattern) {
                            org.telegram.tgnet.WaysGramProfile.rememberWornPattern(lastGiftAccount, wornId,
                                    ((TL_stars.starGiftAttributePattern) attribute).document);
                            break;
                        }
                    }
                    break;
                }
            }
            if (cover != null) cover.setWaysGramGifts(nativeGifts);
        }
    }

    private void saveOrder(ArrayList<Long> ids) {
        if (destroyed || lastInventory == null || savingOrder) return;
        JSONArray expected = new JSONArray();
        JSONArray order = new JSONArray();
        java.util.HashMap<Long, JSONObject> byId = new java.util.HashMap<>();
        for (int i = 0; i < lastInventory.length(); i++) {
            JSONObject item = lastInventory.optJSONObject(i);
            if (item == null) continue;
            long id = item.optLong("id", 0);
            expected.put(id);
            byId.put(id, item);
        }
        JSONArray reordered = new JSONArray();
        for (Long id : ids) {
            order.put(id);
            JSONObject item = byId.get(id);
            if (item != null) reordered.put(item);
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("expected", expected);
            payload.put("order", order);
        } catch (org.json.JSONException e) {
            container.setWaysGramOrderBusy(false);
            container.setReordering(false);
            lastInventory = null;
            refresh();
            return;
        }
        savingOrder = true;
        WaysGramApiClient client = new WaysGramApiClient(sessionStore);
        client.put("/api/me/gifts/order", payload, new WaysGramApiClient.Callback() {
            @Override public void onSuccess(JSONObject response) {
                client.close();
                savingOrder = false;
                if (destroyed) return;
                lastInventory = reordered;
                container.setWaysGramOrderBusy(false);
                if (!editingOrder && pendingRefresh) {
                    pendingRefresh = false;
                    refresh();
                }
            }
            @Override public void onError(String message, int statusCode) {
                client.close();
                savingOrder = false;
                if (destroyed) return;
                // The server may have committed even when the response was lost. Always
                // reload instead of claiming that the previous order was restored.
                lastInventory = null;
                container.setWaysGramOrderBusy(false);
                pendingRefresh = false;
                container.setReordering(false);
                refresh();
                if (fragment != null) BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error,
                        "Не удалось подтвердить порядок. Обновляем список подарков").show(true);
            }
        });
    }

    private void showError(String message) {
        // Gift screens report problems inline and never open a second sign-in
        // screen: the WaysGram session is the one this Telegram account is
        // already authorized with.
        String text;
        if (message == null || message.trim().isEmpty()) {
            text = "Подарки временно недоступны: сервер WaysGram не ответил";
        } else if (message.contains("WaysGram session is missing")
                || message.contains("HTTP 401")) {
            text = "Подарки недоступны: сервер WaysGram отклонил текущий сеанс";
        } else if (message.contains("HTTP 404")) {
            text = "Сервер WaysGram не поддерживает загрузку подарков";
        } else if (message.contains("HTTP 403")) {
            text = "Сервер WaysGram запретил доступ к подаркам";
        } else {
            text = "Не удалось загрузить подарки: " + message;
        }
        if (fragment != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, text).show();
        }
    }

    /**
     * Inventory cards are labelled with the internal WaysGram number and the
     * gift level, e.g. {@code WG-5170145012310081615} / {@code Уровень 3}.
     */
    private static String waysGramTitle(String serial, long fallbackId) {
        String value = serial == null ? "" : serial.trim();
        if (value.length() > 3 && (value.startsWith("WG-") || value.startsWith("wg-"))) {
            String rest = value.substring(3).trim();
            if (!rest.isEmpty()) {
                // Serials are WG-<hex>, so the letters are part of the number:
                // dropping them would make different gifts look identical.
                return "WG-" + rest.replace(' ', '-').toUpperCase(Locale.ROOT);
            }
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
            return "WG-" + digits;
        }
        return "WG-" + Math.max(1L, fallbackId);
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
