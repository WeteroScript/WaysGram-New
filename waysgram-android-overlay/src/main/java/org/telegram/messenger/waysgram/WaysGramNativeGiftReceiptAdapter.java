package org.telegram.messenger.waysgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ConnectionsManager;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.StarsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges WaysGram's own gift purchases into Telegram's native service-message
 * presentation. The gift remains a WaysGram asset; this adapter only creates a
 * local Telegram-style receipt and never charges Telegram Stars.
 */
public final class WaysGramNativeGiftReceiptAdapter {
    private static final String PREFS = "waysgram_native_gift_receipts";
    private static final Pattern LEGACY_RECEIPT = Pattern.compile(
            "^\\s*Вы приобрели коллекционный подарок\\s*«\\s*(?:Telegram\\s+Gift\\s+)?([0-9]{5,})\\s*».*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Map<MessageObject, Boolean> HYDRATING =
            Collections.synchronizedMap(new WeakHashMap<MessageObject, Boolean>());
    private static final Set<String> INSERTING = ConcurrentHashMap.newKeySet();

    private WaysGramNativeGiftReceiptAdapter() {
    }

    /**
     * Adds the purchase receipt to Saved Messages and, when a gift was bought
     * for somebody else, to the recipient's DM. Receipts are stored locally in
     * the current Telegram account's message database.
     */
    public static void addPurchaseReceipt(
            int account,
            String telegramGiftId,
            String serial,
            long ownerTelegramId
    ) {
        final long giftId;
        try {
            giftId = Long.parseLong(telegramGiftId == null ? "" : telegramGiftId.trim());
        } catch (NumberFormatException exception) {
            return;
        }
        if (giftId <= 0 || serial == null || serial.trim().isEmpty()) {
            return;
        }
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId <= 0) {
            return;
        }
        StarsController.getInstance(account).getStarGift(giftId, gift -> {
            if (gift == null) {
                return;
            }
            addLocalReceipt(account, selfId, gift, serial, true);
            if (ownerTelegramId > 0 && ownerTelegramId != selfId) {
                addLocalReceipt(account, ownerTelegramId, gift, serial, false);
            }
        });
    }

    private static void addLocalReceipt(
            int account,
            long dialogUserId,
            TL_stars.StarGift gift,
            String serial,
            boolean savedMessage
    ) {
        String key = account + ":" + dialogUserId + ":" + serial.trim();
        if (!INSERTING.add(key)) {
            return;
        }
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String preferenceKey = "receipt_" + key.replace(':', '_');
            if (preferences.getBoolean(preferenceKey, false)) {
                return;
            }

            long selfId = UserConfig.getInstance(account).getClientUserId();
            TLRPC.TL_messageService message = new TLRPC.TL_messageService();
            message.local_id = message.id = UserConfig.getInstance(account).getNewMessageId();
            message.random_id = Utilities.randomLong();
            message.dialog_id = dialogUserId;
            message.peer_id = new TLRPC.TL_peerUser();
            message.peer_id.user_id = dialogUserId;
            message.from_id = new TLRPC.TL_peerUser();
            message.from_id.user_id = selfId;
            message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID | 2;
            message.out = true;
            message.unread = false;
            message.date = ConnectionsManager.getInstance(account).getCurrentTime();
            message.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
            message.message = "";
            message.params = new java.util.HashMap<>();
            message.params.put("waysgram_gift_serial", serial.trim());
            message.params.put("waysgram_gift_source", "waysgram");

            TLRPC.TL_messageActionStarGift action = new TLRPC.TL_messageActionStarGift();
            action.gift = gift;
            action.saved = savedMessage;
            action.can_upgrade = false;
            action.from_id = new TLRPC.TL_peerUser();
            action.from_id.user_id = selfId;
            action.flags = 1 << 11;
            if (savedMessage) {
                action.flags |= 1 << 2;
            } else {
                action.to_id = new TLRPC.TL_peerUser();
                action.to_id.user_id = dialogUserId;
                action.flags |= 1 << 18;
            }
            message.action = action;

            ArrayList<TLRPC.Message> rawMessages = new ArrayList<>();
            rawMessages.add(message);
            MessagesStorage.getInstance(account).putMessages(
                    rawMessages, false, true, false, 0, 0, 0
            );

            ArrayList<MessageObject> messageObjects = new ArrayList<>();
            messageObjects.add(new MessageObject(account, message, false, true));
            MessagesController.getInstance(account).updateInterfaceWithMessages(
                    dialogUserId, messageObjects, 0
            );
            NotificationCenter.getInstance(account).postNotificationName(
                    NotificationCenter.dialogsNeedReload
            );
            UserConfig.getInstance(account).saveConfig(false);
            preferences.edit().putBoolean(preferenceKey, true).commit();
        } catch (Exception ignored) {
            // A failed local receipt must not block the completed WaysGram purchase.
        } finally {
            INSERTING.remove(key);
        }
    }

    /**
     * Replaces legacy text-only receipts in the currently loaded native chat
     * with Telegram's standard StarGift service-message model.
     */
    public static void hydrateLegacyReceipts(
            int account,
            long dialogId,
            ArrayList<MessageObject> messages
    ) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        for (MessageObject messageObject : new ArrayList<>(messages)) {
            if (messageObject == null || messageObject.messageOwner == null
                    || messageObject.messageOwner.action != null
                    || HYDRATING.containsKey(messageObject)) {
                continue;
            }
            String body = messageObject.messageOwner.message;
            if (TextUtils.isEmpty(body)) {
                continue;
            }
            Matcher matcher = LEGACY_RECEIPT.matcher(body.trim());
            if (!matcher.matches()) {
                continue;
            }
            final long giftId;
            try {
                giftId = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException exception) {
                continue;
            }
            if (giftId <= 0) {
                continue;
            }
            HYDRATING.put(messageObject, Boolean.TRUE);
            StarsController.getInstance(account).getStarGift(giftId, gift -> {
                if (gift == null || messageObject.messageOwner == null
                        || !messages.contains(messageObject)) {
                    HYDRATING.remove(messageObject);
                    return;
                }
                TLRPC.Message original = messageObject.messageOwner;
                TLRPC.TL_messageService service = new TLRPC.TL_messageService();
                service.id = original.id;
                service.local_id = original.local_id;
                service.random_id = original.random_id;
                service.dialog_id = dialogId;
                service.peer_id = original.peer_id;
                service.from_id = original.from_id;
                service.flags = original.flags & (TLRPC.MESSAGE_FLAG_HAS_FROM_ID | 2);
                if (service.from_id == null) {
                    service.from_id = new TLRPC.TL_peerUser();
                    service.from_id.user_id = selfId;
                    service.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
                }
                service.out = original.out;
                service.unread = original.unread;
                service.date = original.date;
                service.send_state = original.send_state;
                service.message = "";
                service.action = createStarGiftAction(
                        gift,
                        selfId,
                        dialogId,
                        dialogId == selfId
                );
                MessageObject replacement = new MessageObject(account, service, false, true);
                int index = messages.indexOf(messageObject);
                if (index >= 0) {
                    ArrayList<MessageObject> replacements = new ArrayList<>();
                    replacements.add(replacement);
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.replaceMessagesObjects,
                            dialogId,
                            replacements
                    );
                }
                HYDRATING.remove(messageObject);
            });
        }
    }

    private static TLRPC.TL_messageActionStarGift createStarGiftAction(
            TL_stars.StarGift gift,
            long selfId,
            long dialogId,
            boolean savedMessage
    ) {
        TLRPC.TL_messageActionStarGift action = new TLRPC.TL_messageActionStarGift();
        action.gift = gift;
        action.saved = savedMessage;
        action.can_upgrade = false;
        action.from_id = new TLRPC.TL_peerUser();
        action.from_id.user_id = selfId;
        action.flags = 1 << 11;
        if (savedMessage) {
            action.flags |= 1 << 2;
        } else if (dialogId > 0) {
            action.to_id = new TLRPC.TL_peerUser();
            action.to_id.user_id = dialogId;
            action.flags |= 1 << 18;
        }
        return action;
    }
}
