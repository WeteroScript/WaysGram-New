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

    private static TLRPC.Message buildServiceMessage(int account, JSONObject json, long selfId) {
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
        long stars = attributes == null ? 0L : Math.max(0L, attributes.optLong("price_stars", 0L));

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
        if (!caption.isEmpty() && !looksLikeGiftReceipt(caption)) {
            TLRPC.TL_textWithEntities text = new TLRPC.TL_textWithEntities();
            text.text = caption;
            action.message = text;
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
