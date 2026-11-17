package org.telegram.messenger.waysgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.Stars.StarsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
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
            "^\\s*Вы приобрели коллекционный подарок\\s*«\\s*(.*?)\\s*».*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL
            
    );
    private static final Map<MessageObject, Boolean> HYDRATING =
            Collections.synchronizedMap(new WeakHashMap<MessageObject, Boolean>());
    private static final Set<String> INSERTING = Collections.synchronizedSet(new HashSet<String>());

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
            String waysGramTitle,
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
            String displayTitle = WaysGramGiftNames.displayName(
                    waysGramTitle, gift == null ? "" : gift.title, telegramGiftId
            );
            TL_stars.StarGift displayGift = gift == null
                    ? createSyntheticGift(giftId, displayTitle, serial)
                    : copyGiftForDisplay(gift, displayTitle, serial);
            addLocalReceipt(account, selfId, displayGift, serial, true);
            if (ownerTelegramId > 0 && ownerTelegramId != selfId) {
                addLocalReceipt(account, ownerTelegramId, displayGift, serial, false);
            }
            warmGiftMedia(account, telegramGiftId, serial);
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
            message.random_id = Utilities.random.nextLong();
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
            final String receiptTitle = matcher.group(1) == null
                    ? "" : matcher.group(1).trim();
            if (receiptTitle.isEmpty()) {
                continue;
            }
            HYDRATING.put(messageObject, Boolean.TRUE);
            resolveLegacyGift(account, receiptTitle, gift -> {
                if (messageObject.messageOwner == null || !messages.contains(messageObject)) {
                    HYDRATING.remove(messageObject);
                    return;
                }
                long fallbackGiftId = parseGiftId(receiptTitle);
                if (gift == null && fallbackGiftId <= 0) {
                    HYDRATING.remove(messageObject);
                    return;
                }
                String legacySerial = fallbackGiftId > 0
                        ? Long.toString(fallbackGiftId)
                        : "legacy-" + Integer.toUnsignedString(receiptTitle.hashCode());
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
                service.params = new java.util.HashMap<>();
                service.params.put("waysgram_gift_serial", legacySerial);
                service.params.put("waysgram_gift_source", "waysgram");
                String displayTitle = WaysGramGiftNames.displayName(
                        receiptTitle, gift == null ? "" : gift.title,
                        Long.toString(fallbackGiftId > 0 ? fallbackGiftId : gift.id)
                );
                TL_stars.StarGift displayGift = gift == null
                        ? createSyntheticGift(fallbackGiftId, displayTitle, legacySerial)
                        : copyGiftForDisplay(gift, displayTitle, legacySerial);
                service.action = createStarGiftAction(
                        displayGift, selfId, dialogId, dialogId == selfId
                );
                if (fallbackGiftId > 0) {
                    warmGiftMedia(account, Long.toString(fallbackGiftId), legacySerial);
                }
                MessageObject replacement = new MessageObject(account, service, false, true);
                if (messages.contains(messageObject)) {
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

    private static void resolveLegacyGift(
            int account,
            String receiptTitle,
            Utilities.Callback<TL_stars.StarGift> callback
    ) {
        Matcher idMatcher = Pattern.compile(
                "(?:Telegram\\s+)?Gift\\s+([0-9]{5,})",
                Pattern.CASE_INSENSITIVE
        ).matcher(receiptTitle);
        if (idMatcher.find()) {
            try {
                long giftId = Long.parseLong(idMatcher.group(1));
                StarsController.getInstance(account).getStarGift(giftId, callback);
                return;
            } catch (NumberFormatException ignored) {
            }
        }

        final String wanted = WaysGramGiftNames.displayName(receiptTitle, receiptTitle, "");
        StarsController controller = StarsController.getInstance(account);
        for (TL_stars.StarGift gift : controller.gifts) {
            String candidate = WaysGramGiftNames.displayName(
                    gift.title, "", Long.toString(gift.id)
            );
            if (candidate.equalsIgnoreCase(wanted)) {
                callback.run(gift);
                return;
            }
        }

        final NotificationCenter.NotificationCenterDelegate[] observer =
                new NotificationCenter.NotificationCenterDelegate[1];
        observer[0] = (id, currentAccount, args) -> {
            if (id != NotificationCenter.starGiftsLoaded || currentAccount != account) {
                return;
            }
            TL_stars.StarGift matched = null;
            for (TL_stars.StarGift gift : controller.gifts) {
                String candidate = WaysGramGiftNames.displayName(
                        gift.title, "", Long.toString(gift.id)
                );
                if (candidate.equalsIgnoreCase(wanted)) {
                    matched = gift;
                    break;
                }
            }
            if (matched != null || controller.giftsLoaded) {
                NotificationCenter.getInstance(account).removeObserver(
                        observer[0], NotificationCenter.starGiftsLoaded
                );
                callback.run(matched);
            }
        };
        NotificationCenter.getInstance(account).addObserver(
                observer[0], NotificationCenter.starGiftsLoaded
        );
        controller.loadStarGifts();
        if (controller.giftsLoaded && !controller.giftsLoading) {
            NotificationCenter.getInstance(account).removeObserver(
                    observer[0], NotificationCenter.starGiftsLoaded
            );
            callback.run(null);
        }
    }

    private static long parseGiftId(String title) {
        Matcher matcher = Pattern.compile(
                "(?:Telegram\\s+)?Gift\\s+([0-9]{5,})",
                Pattern.CASE_INSENSITIVE
        ).matcher(title == null ? "" : title);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static TL_stars.StarGift createSyntheticGift(
            long giftId, String displayTitle, String serial
    ) {
        TL_stars.TL_starGift gift = new TL_stars.TL_starGift();
        gift.id = giftId > 0 ? giftId : Math.max(1L, serial.hashCode() & 0xffffffffL);
        gift.gift_id = gift.id;
        gift.title = displayTitle == null || displayTitle.trim().isEmpty()
                ? "Подарок WaysGram" : displayTitle.trim();
        gift.slug = "waysgram://" + serial.trim();
        gift.sticker = new TLRPC.TL_documentEmpty();
        gift.sticker.id = gift.id;
        gift.can_upgrade = false;
        gift.flags |= 1 << 5;
        return gift;
    }

    private static void warmGiftMedia(int account, String giftId, String serial) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || giftId == null || !giftId.matches("[A-Za-z0-9_-]{1,120}")
                || serial == null || serial.trim().isEmpty()) {
            return;
        }
        WaysGramApiClient client = new WaysGramApiClient(
                WaysGramSessionStore.getInstance(context, account)
        );
        client.downloadTelegramGiftSticker(giftId, new WaysGramApiClient.ImageCallback() {
            @Override
            public void onSuccess(android.graphics.Bitmap bitmap) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    WaysGramGiftMediaStore.put(serial, bitmap);
                }
                client.close();
            }

            @Override
            public void onError(String message, int statusCode) {
                client.downloadTelegramGiftStickerAnimation(
                        giftId, context.getCacheDir(), new WaysGramApiClient.FileCallback() {
                            @Override
                            public void onSuccess(java.io.File file) {
                                WaysGramGiftMediaStore.putAnimationFile(serial, file);
                                client.close();
                            }

                            @Override
                            public void onError(String animationMessage, int animationStatus) {
                                client.close();
                            }
                        }
                );
            }
        });
    }

    private static TL_stars.StarGift copyGiftForDisplay(
            TL_stars.StarGift original,
            String displayTitle,
            String serial
    ) {
        if (original == null) {
            return null;
        }
        NativeByteBuffer buffer = null;
        try {
            buffer = new NativeByteBuffer(original.getObjectSize());
            original.serializeToStream(buffer);
            buffer.position(0);
            int constructor = buffer.readInt32(false);
            TL_stars.StarGift copy = TL_stars.StarGift.TLdeserialize(
                    buffer, constructor, false
            );
            if (copy == null) {
                return applyWaysGramReceiptFields(original, displayTitle, serial);
            }
            return applyWaysGramReceiptFields(copy, displayTitle, serial);
        } catch (Exception exception) {
            return applyWaysGramReceiptFields(original, displayTitle, serial);
        } finally {
            if (buffer != null) {
                buffer.reuse();
            }
        }
    }

    private static TL_stars.StarGift applyWaysGramReceiptFields(
            TL_stars.StarGift gift, String displayTitle, String serial
    ) {
        if (gift == null) {
            return createSyntheticGift(1, displayTitle, serial);
        }
        if (displayTitle != null && !displayTitle.trim().isEmpty()) {
            gift.title = displayTitle.trim();
            gift.flags |= 1 << 5;
        }
        gift.slug = "waysgram://" + serial.trim();
        if (gift.sticker == null) {
            gift.sticker = new TLRPC.TL_documentEmpty();
            gift.sticker.id = gift.id;
        }
        // WaysGram upgrades are handled internally; paid Telegram upgrades remain disabled.
        gift.can_upgrade = false;
        return gift;
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
