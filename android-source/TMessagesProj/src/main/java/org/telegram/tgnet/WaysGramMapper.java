package org.telegram.tgnet;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_update;

/** Converts WaysGram REST JSON into the TL objects the existing UI works with. */
public class WaysGramMapper {

    private static final String[] SECRET_NUMBER_DIGITS = {"111", "999", "0", "333", "666"};

    public static String[] secretNumberDigits() {
        return SECRET_NUMBER_DIGITS.clone();
    }

    public static boolean isSecretNumberDigits(String digits) {
        for (String value : SECRET_NUMBER_DIGITS) {
            if (value.equals(digits)) return true;
        }
        return false;
    }

    public static TLRPC.TL_user toSelfUser(JSONObject json, String phoneWithPlus) {
        TLRPC.TL_user user = toUser(json);
        user.self = true;
        String digits = waysGramPhoneDigits(json);
        if (digits.isEmpty() && phoneWithPlus != null) {
            digits = phoneWithPlus.replaceAll("[^0-9]", "");
        }
        if ((digits.matches("[0-9]{8,15}") || isSecretNumberDigits(digits))) {
            user.phone = digits;
            user.flags |= TLObject.FLAG_4;
        }
        return user;
    }

    /** Digits of the account phone; the API sends it as "+<number>" or not at all. */
    private static String waysGramPhoneDigits(JSONObject json) {
        if (json == null) {
            return "";
        }
        for (String key : new String[]{"phone", "phone_number", "virtual_number", "number"}) {
            String digits = json.optString(key, "").replaceAll("[^0-9]", "");
            if ((digits.matches("[0-9]{8,15}") || isSecretNumberDigits(digits))) {
                return digits;
            }
        }
        return "";
    }

    private static boolean looksLikePhone(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        int digits = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits++;
            } else if (ch != '+' && ch != ' ' && ch != '(' && ch != ')' && ch != '-') {
                return false;
            }
        }
        return digits >= 5;
    }

    public static TLRPC.TL_user toUser(JSONObject json) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        if (json == null) {
            json = new JSONObject();
        }
        user.id = json.optLong("id", json.optLong("telegram_id", 0));
        user.access_hash = user.id;
        user.flags |= TLObject.FLAG_0;

        String display = json.optString("display_name", "").trim();
        String username = json.optString("username", "").trim();
        // Служебные аккаунты создаются с именем-номером («+888 888 8888»): если у человека
        // есть ник, показываем ник, а не телефон.
        if (!username.isEmpty() && looksLikePhone(display)) {
            display = username;
        }
        if (display.isEmpty()) {
            display = !username.isEmpty() ? username : "User " + user.id;
        }
        int space = display.indexOf(' ');
        if (space > 0) {
            user.first_name = display.substring(0, space);
            user.last_name = display.substring(space + 1).trim();
        } else {
            user.first_name = display;
            user.last_name = "";
        }
        user.flags |= TLObject.FLAG_1;
        if (!user.last_name.isEmpty()) {
            user.flags |= TLObject.FLAG_2;
        }
        if (!username.isEmpty()) {
            user.username = username;
            user.flags |= TLObject.FLAG_3;
        }
        user.premium = true; // every WaysGram account has Premium, permanently
        String waysGramPhone = waysGramPhoneDigits(json);
        if (!waysGramPhone.isEmpty()) {
            user.phone = waysGramPhone;
            user.flags |= TLObject.FLAG_4;
        }
        String avatar = json.optString("avatar_media_id", "");
        if (avatar.length() >= 15 && !"null".equals(avatar)) {
            long photoId = WaysGramMedia.register(avatar);
            TLRPC.TL_userProfilePhoto photo = new TLRPC.TL_userProfilePhoto();
            photo.photo_id = photoId;
            photo.dc_id = 2;
            photo.photo_small = new TLRPC.TL_fileLocationToBeDeprecated();
            photo.photo_small.volume_id = -photoId;
            photo.photo_small.local_id = 'a';
            photo.photo_big = new TLRPC.TL_fileLocationToBeDeprecated();
            photo.photo_big.volume_id = -photoId;
            photo.photo_big.local_id = 'c';
            user.photo = photo;
        } else {
            user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        }
        user.flags |= TLObject.FLAG_5;
        int lastSeen = json.optInt("last_seen_at");
        if (lastSeen > 0) {
            TLRPC.TL_userStatusOffline status = new TLRPC.TL_userStatusOffline();
            status.expires = lastSeen;
            user.status = status;
        } else {
            user.status = new TLRPC.TL_userStatusRecently();
        }
        user.flags |= TLObject.FLAG_6;
        if (json.has("worn_gift")) {
            user.emoji_status = wornGiftStatus(json.optJSONObject("worn_gift"));
            user.flags |= TLObject.FLAG_30;
        }
        return user;
    }

    public static TLRPC.EmojiStatus wornGiftStatus(JSONObject gift) {
        if (gift == null || gift.optLong("id") <= 0) return new TLRPC.TL_emojiStatusEmpty();
        TLRPC.TL_emojiStatusCollectible status = new TLRPC.TL_emojiStatusCollectible();
        status.collectible_id = gift.optLong("id");
        status.title = gift.optString("title");
        status.slug = "waysgram://" + gift.optString("serial_number");
        JSONObject traits = gift.optJSONObject("traits");
        JSONObject model = traits == null ? null : traits.optJSONObject("model");
        JSONObject pattern = traits == null ? null : traits.optJSONObject("pattern");
        JSONObject backdrop = traits == null ? null : traits.optJSONObject("backdrop");
        status.document_id = model == null ? 0 : model.optLong("document_id");
        status.pattern_document_id = pattern == null ? 0 : pattern.optLong("document_id");
        status.center_color = backdrop == null ? 0 : backdrop.optInt("center_color");
        status.edge_color = backdrop == null ? 0 : backdrop.optInt("edge_color");
        status.pattern_color = backdrop == null ? 0 : backdrop.optInt("pattern_color");
        status.text_color = backdrop == null ? 0 : backdrop.optInt("text_color");
        return status;
    }

    public static TLRPC.TL_peerUser peerUser(long id) {
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = id;
        return peer;
    }

    /**
     * Builds a private-chat message following the same convention as the original protocol:
     * outgoing: from = me, peer = other; incoming: from = other, peer = me.
     */
    public static TLRPC.TL_message toMessage(long id, long senderId, long recipientId, String body, int date, long selfId) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = Math.toIntExact(id);
        message.out = senderId == selfId;
        message.from_id = peerUser(senderId);
        message.peer_id = peerUser(message.out ? recipientId : selfId);
        message.dialog_id = message.out ? recipientId : senderId;
        message.date = date;
        message.message = body == null ? "" : body;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID | TLObject.FLAG_9;
        return message;
    }

    public static TLRPC.Message toMessage(JSONObject m, long selfId) {
        return toMessage(m, selfId, org.telegram.messenger.UserConfig.selectedAccount);
    }

    /**
     * Gift purchases, transfers and received gifts are drawn with Telegram's
     * native gift card instead of a plain text bubble.
     */
    public static TLRPC.Message toMessage(JSONObject m, long selfId, int account) {
        final TLRPC.Message waysGramGiftMessage =
                org.telegram.messenger.waysgram.WaysGramGiftMessages.toServiceMessage(account, m, selfId);
        if (waysGramGiftMessage != null) {
            return waysGramGiftMessage;
        }
        return toMessage(
                m.optLong("id"),
                m.optLong("sender_id"),
                m.optLong("recipient_id"),
                m.optString("body", ""),
                m.optInt("created_at"),
                selfId);
    }

    /** Snapshot the selected text from the existing native reply composer. */
    public static JSONObject quoteFor(TLRPC.InputReplyTo reply) throws WaysGramApi.ApiException {
        if (!(reply instanceof TLRPC.TL_inputReplyToMessage)) return null;
        if (reply.quote_rich != null && !reply.quote_rich.isEmpty())
            throw new WaysGramApi.ApiException(400, "RICH_QUOTE_NOT_SUPPORTED");
        if (TextUtils.isEmpty(reply.quote_text)) return null;
        if (reply.reply_to_peer_id != null)
            throw new WaysGramApi.ApiException(400, "CROSS_CHAT_QUOTE_NOT_SUPPORTED");
        try {
            JSONObject quote = new JSONObject();
            quote.put("text", reply.quote_text);
            quote.put("entities", writeTextEntities(reply.quote_entities));
            if ((reply.flags & TLObject.FLAG_4) != 0 && reply.quote_offset >= 0)
                quote.put("offset", reply.quote_offset);
            return quote;
        } catch (WaysGramApi.ApiException e) { throw e; }
          catch (Exception e) { throw new WaysGramApi.ApiException(400, "QUOTE_INVALID"); }
    }

    /** Encode only types that the server can preserve; never silently discard styling. */
    public static JSONArray writeTextEntities(java.util.ArrayList<TLRPC.MessageEntity> entities) throws WaysGramApi.ApiException {
        JSONArray out = new JSONArray();
        if (entities == null) return out;
        for (TLRPC.MessageEntity entity : entities) {
            String type;
            if (entity instanceof TLRPC.TL_messageEntityBold) type = "bold";
            else if (entity instanceof TLRPC.TL_messageEntityItalic) type = "italic";
            else if (entity instanceof TLRPC.TL_messageEntityUnderline) type = "underline";
            else if (entity instanceof TLRPC.TL_messageEntityStrike) type = "strike";
            else if (entity instanceof TLRPC.TL_messageEntitySpoiler) type = "spoiler";
            else if (entity instanceof TLRPC.TL_messageEntityCode) type = "code";
            else if (entity instanceof TLRPC.TL_messageEntityPre) type = "pre";
            else if (entity instanceof TLRPC.TL_messageEntityTextUrl) type = "text_url";
            else if (entity instanceof TLRPC.TL_messageEntityUrl) type = "url";
            else if (entity instanceof TLRPC.TL_messageEntityMention) type = "mention";
            else if (entity instanceof TLRPC.TL_messageEntityHashtag) type = "hashtag";
            else throw new WaysGramApi.ApiException(400, "FORMATTING_NOT_SUPPORTED");
            try {
                JSONObject item = new JSONObject();
                item.put("type", type);
                item.put("offset", entity.offset);
                item.put("length", entity.length);
                if (type.equals("pre")) item.put("language", entity.language == null ? "" : entity.language);
                if (type.equals("text_url")) item.put("url", entity.url == null ? "" : entity.url);
                out.put(item);
            } catch (Exception e) {
                throw new WaysGramApi.ApiException(400, "FORMATTING_INVALID");
            }
        }
        return out;
    }

    public static boolean hasFormatting(java.util.ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null) return false;
        for (TLRPC.MessageEntity entity : entities) {
            if (!(entity instanceof TLRPC.TL_messageEntityUrl || entity instanceof TLRPC.TL_messageEntityMention
                    || entity instanceof TLRPC.TL_messageEntityHashtag)) return true;
        }
        return false;
    }

    /** Restore server-validated UTF-16 ranges. Unknown types are ignored for forward compatibility. */
    public static void readTextEntities(TLRPC.Message message, JSONObject json) {
        JSONArray entries = json.optJSONArray("entities");
        if (entries == null || message.message == null) return;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "");
            TLRPC.MessageEntity entity;
            switch (type) {
                case "bold": entity = new TLRPC.TL_messageEntityBold(); break;
                case "italic": entity = new TLRPC.TL_messageEntityItalic(); break;
                case "underline": entity = new TLRPC.TL_messageEntityUnderline(); break;
                case "strike": entity = new TLRPC.TL_messageEntityStrike(); break;
                case "spoiler": entity = new TLRPC.TL_messageEntitySpoiler(); break;
                case "code": entity = new TLRPC.TL_messageEntityCode(); break;
                case "pre": entity = new TLRPC.TL_messageEntityPre(); entity.language = item.optString("language", ""); break;
                case "text_url": entity = new TLRPC.TL_messageEntityTextUrl(); entity.url = item.optString("url", ""); break;
                case "url": entity = new TLRPC.TL_messageEntityUrl(); break;
                case "mention": entity = new TLRPC.TL_messageEntityMention(); break;
                case "hashtag": entity = new TLRPC.TL_messageEntityHashtag(); break;
                default: continue;
            }
            int offset = item.optInt("offset", -1), length = item.optInt("length", -1);
            if (offset < 0 || length <= 0 || offset > message.message.length() || length > message.message.length() - offset) continue;
            int end = offset + length;
            if ((offset > 0 && offset < message.message.length() && Character.isLowSurrogate(message.message.charAt(offset)) && Character.isHighSurrogate(message.message.charAt(offset - 1)))
                    || (end > 0 && end < message.message.length() && Character.isLowSurrogate(message.message.charAt(end)) && Character.isHighSurrogate(message.message.charAt(end - 1)))) continue;
            entity.offset = offset;
            entity.length = length;
            message.entities.add(entity);
        }
        if (!message.entities.isEmpty()) message.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
    }

    /** Link entities are detected locally for plain-text servers and older messages. */
    // Java regex offsets are UTF-16, matching Telegram entity offsets even after emoji.
    private static final java.util.regex.Pattern LINK_ENTITY_PATTERN = java.util.regex.Pattern.compile(
            "(?i)https?://[^\\s<>\"']+|(?<![\\p{L}\\p{N}_.+\\-])@[A-Za-z][A-Za-z0-9_]{1,31}(?![A-Za-z0-9_])|(?<![\\p{L}\\p{N}_])#[\\p{L}\\p{N}_]{1,64}"
    );

    public static void applyTextEntities(TLRPC.Message message) {
        if (message == null || message.message == null) return;
        java.util.regex.Matcher matcher = LINK_ENTITY_PATTERN.matcher(message.message);
        while (matcher.find()) {
            int end = matcher.end();
            char first = message.message.charAt(matcher.start());
            if (first != '@' && first != '#') {
                // Do not absorb sentence punctuation into a URL.
                while (end > matcher.start() && ".,!?;:)".indexOf(message.message.charAt(end - 1)) >= 0) end--;
            }
            if (end <= matcher.start()) continue;
            // Styling may overlap links; avoid a second actionable entity, or links inside code/spoilers.
            boolean blocked = false;
            for (TLRPC.MessageEntity existing : message.entities) {
                if ((existing instanceof TLRPC.TL_messageEntityUrl || existing instanceof TLRPC.TL_messageEntityTextUrl
                        || existing instanceof TLRPC.TL_messageEntityMention || existing instanceof TLRPC.TL_messageEntityHashtag
                        || existing instanceof TLRPC.TL_messageEntityCode || existing instanceof TLRPC.TL_messageEntityPre
                        || existing instanceof TLRPC.TL_messageEntitySpoiler)
                        && existing.offset < end && matcher.start() < (long) existing.offset + existing.length) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) continue;
            TLRPC.MessageEntity entity = first == '@' ? new TLRPC.TL_messageEntityMention()
                    : first == '#' ? new TLRPC.TL_messageEntityHashtag() : new TLRPC.TL_messageEntityUrl();
            entity.offset = matcher.start();
            entity.length = end - matcher.start();
            message.entities.add(entity);
        }
        if (!message.entities.isEmpty()) message.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
    }

    private static void addReaction(TLRPC.TL_messageReactions reactions, String emoji, int count,
                                    JSONArray mine, java.util.HashSet<String> seen) {
        if (!WaysGramReactions.contains(emoji) || count <= 0 || !seen.add(emoji)) return;
        TLRPC.TL_reactionEmoji emojiValue = new TLRPC.TL_reactionEmoji();
        emojiValue.emoticon = emoji;
        TLRPC.TL_reactionCount result = new TLRPC.TL_reactionCount();
        result.reaction = emojiValue;
        result.count = count;
        if (mine != null) {
            for (int i = 0; i < mine.length(); i++) {
                if (emoji.equals(mine.optString(i))) {
                    result.flags |= TLObject.FLAG_0;
                    result.chosen = true;
                    result.chosen_order = i;
                    break;
                }
            }
        }
        reactions.results.add(result);
    }

    private static void addRecentReactions(TLRPC.TL_messageReactions reactions, JSONArray recent) {
        if (recent == null) return;
        for (int i = 0; i < recent.length(); i++) {
            JSONObject item = recent.optJSONObject(i);
            if (item == null) continue;
            long userId = item.optLong("user_id", 0);
            String emoji = item.optString("reaction", "");
            if (userId <= 0 || !WaysGramReactions.contains(emoji)) continue;
            TLRPC.TL_messagePeerReaction peerReaction = new TLRPC.TL_messagePeerReaction();
            peerReaction.peer_id = peerUser(userId);
            TLRPC.TL_reactionEmoji value = new TLRPC.TL_reactionEmoji();
            value.emoticon = emoji;
            peerReaction.reaction = value;
            peerReaction.big = item.optBoolean("big", false);
            peerReaction.unread = item.optBoolean("unread", false);
            peerReaction.date = item.optInt("date", 0);
            reactions.recent_reactions.add(peerReaction);
        }
        if (!reactions.recent_reactions.isEmpty()) reactions.flags |= TLObject.FLAG_1;
    }

    /** Read a history summary or an authoritative response from a set request. */
    public static TLRPC.TL_messageReactions readReactions(JSONObject json) {
        TLRPC.TL_messageReactions reactions = new TLRPC.TL_messageReactions();
        JSONArray mine = json.optJSONArray("my_reactions");
        JSONArray counts = json.optJSONArray("reactions");
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        if (counts != null) {
            for (int i = 0; i < counts.length(); i++) {
                JSONObject entry = counts.optJSONObject(i);
                if (entry != null) addReaction(reactions, entry.optString("reaction", ""), entry.optInt("count"), mine, seen);
            }
        } else {
            String summary = json.optString("reaction_summary", "");
            if (!TextUtils.isEmpty(summary) && !"null".equals(summary)) {
                for (String entry : summary.split(",")) {
                    int split = entry.lastIndexOf(':');
                    if (split <= 0 || split == entry.length() - 1) continue;
                    try {
                        addReaction(reactions, entry.substring(0, split), Integer.parseInt(entry.substring(split + 1)), mine, seen);
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        addRecentReactions(reactions, json.optJSONArray("recent_reactions"));
        return reactions;
    }

    /** Edit mark and reply link of a message built from server JSON. idOffset shifts ids of group messages. */
    public static void applyExtras(TLRPC.Message message, JSONObject json, int idOffset) {
        // Persist the sender's silent flag so the native notification controller
        // does not play a sound when the polling transport delivers the message.
        message.silent = json.optInt("silent", 0) != 0 || json.optBoolean("silent", false);
        readTextEntities(message, json);
        TLRPC.TL_messageReactions reactions = readReactions(json);
        if (!reactions.results.isEmpty()) {
            message.reactions = reactions;
            message.flags |= TLObject.FLAG_20;
        }
        long forwardedFrom = json.optLong("forwarded_from_telegram_id", 0);
        String name = json.optString("forwarded_from_display_name", "");
        if ("null".equals(name)) name = "";
        if (forwardedFrom > 0 || !name.isEmpty()) {
            TLRPC.TL_messageFwdHeader header = new TLRPC.TL_messageFwdHeader();
            // Use a plain name when the server withheld the original author link.
            if (forwardedFrom <= 0) {
                header.from_name = name;
                header.flags |= TLObject.FLAG_5;
            } else {
                header.from_id = peerUser(forwardedFrom);
                header.flags |= TLObject.FLAG_0;
            }
            header.date = json.optInt("forwarded_from_date", json.optInt("created_at", 0));
            message.fwd_from = header;
            message.flags |= TLObject.FLAG_2;
        }
        String albumId = json.optString("media_album_id", "");
        if (json.optInt("media_album_count", 0) > 1 && albumId.matches("[a-f0-9]{32}")) {
            // First 60 random bits fit a signed long and stay stable across reloads.
            message.grouped_id = Long.parseLong(albumId.substring(0, 15), 16);
            if (message.grouped_id != 0) message.flags |= TLObject.FLAG_17;
        }
        int edited = json.optInt("edited_at", 0);
        if (edited > 0) {
            message.edit_date = edited;
            message.flags |= TLObject.FLAG_15;
        }
        int reply = json.optInt("reply_to_id", 0);
        if (reply > 0) {
            TLRPC.TL_messageReplyHeader header = new TLRPC.TL_messageReplyHeader();
            header.reply_to_msg_id = reply + idOffset;
            header.flags |= TLObject.FLAG_4;
            JSONObject quote = json.optJSONObject("quote");
            if (quote != null && !TextUtils.isEmpty(quote.optString("text", ""))) {
                header.quote = true;
                header.quote_text = quote.optString("text", "");
                header.flags |= TLObject.FLAG_6;
                TLRPC.TL_message quoted = new TLRPC.TL_message();
                quoted.message = header.quote_text;
                readTextEntities(quoted, quote);
                if (!quoted.entities.isEmpty()) {
                    header.quote_entities = quoted.entities;
                    header.flags |= TLObject.FLAG_7;
                }
                if (quote.has("offset") && !quote.isNull("offset")) {
                    header.quote_offset = quote.optInt("offset");
                    header.flags |= TLObject.FLAG_10;
                }
            }
            message.reply_to = header;
            message.flags |= TLObject.FLAG_3;
        }
    }

    /** Unread counter and read marks of a chat in the chat list. */
    public static void applyReadState(TLRPC.Dialog dialog, int unread, int readInboxMax, int readOutboxMax) {
        dialog.unread_count = Math.max(unread, 0);
        dialog.read_inbox_max_id = readInboxMax;
        dialog.read_outbox_max_id = readOutboxMax;
    }

    public static TLRPC.TL_dialog toDialog(long peerId, int topMessageId) {
        return toDialog(peerUser(peerId), topMessageId);
    }

    public static TLRPC.TL_dialog toDialog(TLRPC.Peer peer, int topMessageId) {
        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
        dialog.peer = peer;
        dialog.top_message = topMessageId;
        dialog.read_inbox_max_id = topMessageId;
        dialog.read_outbox_max_id = topMessageId;
        dialog.unread_count = 0;
        dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
        return dialog;
    }

    public static TL_update.TL_updateNewMessage newMessageUpdate(TLRPC.Message message, int pts) {
        TL_update.TL_updateNewMessage update = new TL_update.TL_updateNewMessage();
        update.message = message;
        update.pts = pts;
        update.pts_count = 0;
        return update;
    }
}
