package org.telegram.messenger.waysgram;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.tgnet.tl.TL_stars;

import java.io.File;
import java.util.Locale;

/**
 * Converts WaysGram gift messages into Telegram's native gift cards.
 *
 * <p>A message that belongs to a gift (a purchase receipt in "Избранное", a
 * gift sent to a friend or a gift received in a private chat) becomes a
 * native {@link TLRPC.TL_messageService} with a
 * {@link TLRPC.TL_messageActionStarGift} action. Telegram then draws it with
 * its own gift card renderer instead of a plain text bubble.</p>
 */
public final class WaysGramGiftMessages {

    private WaysGramGiftMessages() {
    }

    /**
     * @return the native gift message, or {@code null} when the message is not
     * related to a gift.
     */
    public static TLRPC.Message toServiceMessage(int account, JSONObject json, long selfId) {
        if (json == null) {
            return null;
        }
        try {
            return buildServiceMessage(account, json, selfId);
        } catch (Exception exception) {
            FileLog.e(exception);
            return null;
        }
    }

    private static TLRPC.Message buildServiceMessage(int account, JSONObject json, long selfId) throws org.json.JSONException {
        JSONObject attributes = json.optJSONObject("gift_attributes");
        String serial = optionalText(json, "gift_serial_number");
        String telegramGiftId = optionalText(json, "gift_telegram_gift_id");
        String giftName = optionalText(json, "gift_name");
        String body = optionalText(json, "body");
        JSONObject telegramMetadata = json.optJSONObject("gift_telegram_metadata");
        if (telegramMetadata != null) {
            if (telegramGiftId.isEmpty()) {
                telegramGiftId = optionalText(telegramMetadata, "id");
            }
            if (giftName.isEmpty()) {
                giftName = optionalText(telegramMetadata, "title");
            }
        }
        boolean hasGift = !serial.isEmpty() || !telegramGiftId.isEmpty()
                || attributes != null || looksLikeGiftReceipt(body);
        if (!hasGift) {
            return null;
        }
        long senderId = json.optLong("sender_id", 0);
        long recipientId = json.optLong("recipient_id", 0);
        if (senderId <= 0 || recipientId <= 0) {
            return null;
        }
        long messageId = json.optLong("id", 0);
        int date = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, json.optLong("created_at", 0L)));

        String emoji = optionalText(json, "gift_emoji");
        if (emoji.isEmpty()) {
            emoji = optionalText(telegramMetadata, "emoji");
        }
        if (giftName.isEmpty()) {
            giftName = extractGiftName(body);
        }
        if (giftName.isEmpty()) {
            giftName = "Подарок WaysGram";
        }
        JSONObject traits = attributes == null ? null : attributes.optJSONObject("collectible_traits");
        boolean collectible = json.optInt("gift_upgrade_level", 0) > 0 || (traits != null && traits.length() > 0);
        // Only transfer messages: self purchase receipts retain their existing semantics.
        if (collectible && !serial.isEmpty() && senderId != recipientId) {
            return collectibleMessage(account, json, attributes, serial, telegramGiftId,
                    giftName, emoji, senderId, recipientId, selfId, date);
        }
        long stars = attributes == null ? 0L : Math.max(0L, attributes.optLong("price_stars", 0L));
        if (stars == 0L && telegramMetadata != null) {
            // Chat gifts carry no receipt price; the catalogue value is a better
            // default than a card that claims the gift cost nothing.
            stars = Math.max(0L, telegramMetadata.optLong("star_count", 0L));
        }

        long documentId = WaysGramGiftDocuments.documentId(telegramGiftId);
        TLRPC.TL_document sticker = null;
        if (documentId != 0L) {
            sticker = resolveSticker(account, documentId, telegramGiftId, emoji);
        }
        if (sticker == null) {
            // Without artwork the card would stay empty; keep the text bubble in
            // that case so the user still sees what happened.
            return null;
        }

        TL_stars.TL_starGift gift = new TL_stars.TL_starGift();
        gift.id = documentId;
        gift.gift_id = documentId;
        gift.sticker = sticker;
        gift.stars = stars;
        gift.title = giftName;
        gift.flags |= TLObject.FLAG_5;
        gift.convert_stars = 0;
        gift.limited = false;
        gift.sold_out = false;
        if (!serial.isEmpty()) {
            gift.slug = "waysgram://" + serial;
        }

        TLRPC.TL_messageActionStarGift action = new TLRPC.TL_messageActionStarGift();
        action.gift = gift;
        action.convert_stars = 0;
        action.saved_id = messageId;
        String caption = body;
        if (!caption.isEmpty() && !"Подарок WaysGram".equals(caption)
                && (!serial.isEmpty() || !looksLikeGiftReceipt(caption))) {
            TLRPC.TL_textWithEntities text = new TLRPC.TL_textWithEntities();
            text.text = caption;
            action.message = text;
            action.flags |= TLObject.FLAG_1;
        }

        boolean out = senderId == selfId;
        TLRPC.TL_peerUser senderPeer = new TLRPC.TL_peerUser();
        senderPeer.user_id = senderId;
        TLRPC.TL_peerUser dialogPeer = new TLRPC.TL_peerUser();
        dialogPeer.user_id = out ? recipientId : selfId;
        action.from_id = senderPeer;
        action.flags |= TLObject.FLAG_11;

        TLRPC.TL_messageService serviceMessage = new TLRPC.TL_messageService();
        serviceMessage.id = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, messageId));
        serviceMessage.date = date;
        serviceMessage.out = out;
        serviceMessage.from_id = senderPeer;
        serviceMessage.peer_id = dialogPeer;
        serviceMessage.dialog_id = out ? recipientId : senderId;
        serviceMessage.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID | TLObject.FLAG_9;
        serviceMessage.action = action;
        return serviceMessage;
    }

    // Bounded process-local snapshots allow visible cells to rebind when the exact
    // artwork arrives. The serialized message remains a normal Telegram TL object.
    private static final java.util.LinkedHashMap<String, JSONObject> COLLECTIBLES =
            new java.util.LinkedHashMap<String, JSONObject>(32, .75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, JSONObject> eldest) {
                    return size() > 512;
                }
            };

    private static String snapshotKey(int account, long messageId) {
        return account + ":" + messageId;
    }

    private static TL_stars.TL_starGiftUnique collectibleGift(int account, JSONObject item) {
        org.json.JSONArray inventory = new org.json.JSONArray();
        inventory.put(item);
        java.util.ArrayList<TL_stars.SavedStarGift> gifts =
                WaysGramProfileGiftsController.toNativeGifts(inventory, account);
        if (gifts.isEmpty() || !(gifts.get(0).gift instanceof TL_stars.TL_starGiftUnique)) return null;
        TL_stars.TL_starGiftUnique gift = (TL_stars.TL_starGiftUnique) gifts.get(0).gift;
        // A WaysGram serial is not a collection number. Older servers might not
        // provide one; keep it unknown rather than inventing a different number.
        gift.num = Math.max(0, item.optInt("unique_num", 0));
        return gift;
    }

    private static TLRPC.Message collectibleMessage(int account, JSONObject json, JSONObject attributes,
            String serial, String telegramGiftId, String giftName, String emoji,
            long senderId, long recipientId, long selfId, int date) throws org.json.JSONException {
        long messageId = json.optLong("id", 0L);
        JSONObject item = new JSONObject();
        item.put("serial_number", serial);
        long instanceId = json.optLong("gift_instance_id", 0L);
        if (instanceId > 0) item.put("id", instanceId);
        item.put("unique_num", Math.max(0, json.optInt("gift_unique_num", 0)));
        item.put("telegram_gift_id", telegramGiftId);
        item.put("gift_name", giftName);
        item.put("gift_emoji", emoji);
        item.put("upgrade_level", Math.max(1, json.optInt("gift_upgrade_level", 0)));
        item.put("attributes", attributes == null ? new JSONObject() : attributes);
        item.put("owner_id", recipientId); // recipient at the time of this transfer
        item.put("acquired_at", date);
        JSONObject metadata = json.optJSONObject("gift_telegram_metadata");
        if (metadata != null) item.put("telegram_gift", metadata);
        TL_stars.TL_starGiftUnique gift = collectibleGift(account, item);
        if (gift == null) return null;
        synchronized (COLLECTIBLES) { COLLECTIBLES.put(snapshotKey(account, messageId), item); }

        TLRPC.TL_peerUser sender = new TLRPC.TL_peerUser();
        sender.user_id = senderId;
        TLRPC.TL_peerUser dialog = new TLRPC.TL_peerUser();
        boolean out = senderId == selfId;
        dialog.user_id = out ? recipientId : selfId;
        TLRPC.TL_messageActionStarGiftUnique action = new TLRPC.TL_messageActionStarGiftUnique();
        action.gift = gift;
        action.transferred = true;
        action.from_id = sender;
        String caption = optionalText(json, "body");
        if (!caption.isEmpty() && !"Подарок WaysGram".equals(caption)) {
            action.message = new TLRPC.TL_textWithEntities();
            action.message.text = caption;
        }
        TLRPC.TL_messageService message = new TLRPC.TL_messageService();
        message.id = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, messageId));
        message.date = date;
        message.out = out;
        message.from_id = sender;
        message.peer_id = dialog;
        message.dialog_id = out ? recipientId : senderId;
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID | TLObject.FLAG_9;
        message.action = action;
        return message;
    }

    private static long artworkId(TL_stars.StarGift gift, boolean model) {
        if (gift == null) return 0L;
        for (TL_stars.StarGiftAttribute attribute : gift.attributes) {
            if (model && attribute instanceof TL_stars.starGiftAttributeModel) {
                TLRPC.Document document = ((TL_stars.starGiftAttributeModel) attribute).document;
                return document == null ? 0L : document.id;
            }
            if (!model && attribute instanceof TL_stars.starGiftAttributePattern) {
                TLRPC.Document document = ((TL_stars.starGiftAttributePattern) attribute).document;
                return document == null ? 0L : document.id;
            }
        }
        return 0L;
    }

    /** Rebind only a currently displayed WaysGram collectible after its exact art lands. */
    public static boolean refreshCollectibleArtwork(int account, TLRPC.Message message, String changedGiftId) {
        if (message == null || !(message.action instanceof TLRPC.TL_messageActionStarGiftUnique)) return false;
        TLRPC.TL_messageActionStarGiftUnique action = (TLRPC.TL_messageActionStarGiftUnique) message.action;
        if (action.gift == null || action.gift.slug == null || !action.gift.slug.startsWith("waysgram://")) return false;
        JSONObject item;
        synchronized (COLLECTIBLES) { item = COLLECTIBLES.get(snapshotKey(account, message.id)); }
        if (item == null || (changedGiftId != null && !changedGiftId.equals(item.optString("telegram_gift_id", "")))) return false;
        TL_stars.TL_starGiftUnique updated = collectibleGift(account, item);
        if (updated == null || (artworkId(action.gift, true) == artworkId(updated, true)
                && artworkId(action.gift, false) == artworkId(updated, false))) return false;
        action.gift = updated;
        return true;
    }

    /**
     * Stores the gift artwork in the shared WaysGram sticker folder, downloading
     * it once when the file is not there yet.
     */
    private static TLRPC.TL_document resolveSticker(
            int account, long documentId, String telegramGiftId, String emoji
    ) {
        File file = WaysGramGiftDocuments.stickerFile(documentId);
        if (file == null) {
            return null;
        }
        if (!file.isFile() || file.length() == 0) {
            byte[] bytes = downloadSticker(account, telegramGiftId);
            if (bytes == null) {
                return null;
            }
            file = WaysGramGiftDocuments.storeSticker(documentId, bytes);
            if (file == null) {
                return null;
            }
        }
        return WaysGramGiftDocuments.buildDocument(documentId, file, emoji);
    }

    private static byte[] downloadSticker(int account, String telegramGiftId) {
        if (TextUtils.isEmpty(telegramGiftId)) {
            return null;
        }
        String encoded = Uri.encode(telegramGiftId);
        try {
            byte[] bytes = WaysGramApi.download(
                    account, "/api/telegram/gifts/" + encoded + "/sticker?animated=1"
            );
            if (bytes != null && bytes.length >= 16) {
                return bytes;
            }
        } catch (Exception exception) {
            FileLog.e(exception);
        }
        try {
            return WaysGramApi.download(account, "/api/telegram/gifts/" + encoded + "/sticker");
        } catch (Exception exception) {
            FileLog.e(exception);
            return null;
        }
    }

    /** Recognizes a plain-text purchase receipt sent by older server builds. */
    static boolean looksLikeGiftReceipt(String body) {
        if (body == null || body.trim().isEmpty()) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        boolean purchase = (normalized.contains("приобрели") || normalized.contains("купили")
                || normalized.contains("подарили") || normalized.contains("получили"))
                && normalized.contains("подарок");
        boolean english = (normalized.contains("purchased") || normalized.contains("bought")
                || normalized.contains("sent you a gift") || normalized.contains("you received a gift"));
        return purchase || english;
    }

    private static String extractGiftName(String body) {
        if (body == null) {
            return "";
        }
        char[][] pairs = {{'«', '»'}, {'“', '”'}, {'"', '"'}};
        for (char[] pair : pairs) {
            int start = body.indexOf(pair[0]);
            int end = body.lastIndexOf(pair[1]);
            if (start >= 0 && end > start + 1) {
                return body.substring(start + 1, end).trim();
            }
        }
        return "";
    }

    private static String optionalText(JSONObject object, String key) {
        if (object == null || object.isNull(key)) {
            return "";
        }
        String value = object.optString(key, "").trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    /** True when the message JSON contains any gift column used by the API. */
    public static boolean hasGiftColumns(JSONObject json) {
        if (json == null) {
            return false;
        }
        if (json.optJSONObject("gift_attributes") != null) {
            return true;
        }
        return !optionalText(json, "gift_serial_number").isEmpty()
                || !optionalText(json, "gift_telegram_gift_id").isEmpty()
                || !optionalText(json, "gift_name").isEmpty();
    }
}
