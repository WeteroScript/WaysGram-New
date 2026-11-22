package org.telegram.tgnet;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.tl.TL_account;


/**
 * Own profile (name, username) and channel creation/renaming.
 * The server stores a single display name and a username per account; there is no "about" text and no avatar,
 * so those fields are accepted by the app but not stored.
 */
public class WaysGramProfile {

    private static final String USERNAME_PATTERN = "[a-z][a-z0-9_]{0,31}";

    private static String clean(String username) {
        return username == null ? "" : username.trim().replace("@", "").toLowerCase();
    }

    // ---------------------------------------------------------------- own profile

    public static TLObject updateProfile(int account, TL_account.updateProfile req, long selfId) throws WaysGramApi.ApiException {
        boolean hasFirst = (req.flags & TLObject.FLAG_0) != 0;
        boolean hasLast = (req.flags & TLObject.FLAG_1) != 0;
        boolean hasAbout = (req.flags & TLObject.FLAG_2) != 0;
        JSONObject body = new JSONObject();
        try {
            if (hasFirst || hasLast) {
                String first = hasFirst && req.first_name != null ? req.first_name.trim() : "";
                String last = hasLast && req.last_name != null ? req.last_name.trim() : "";
                if (!hasFirst || !hasLast) {
                    // The server keeps one name: take the missing half from the current profile.
                    TLRPC.User current = selfUser(WaysGramApi.me(account));
                    if (!hasFirst) {
                        first = current.first_name == null ? "" : current.first_name;
                    }
                    if (!hasLast) {
                        last = current.last_name == null ? "" : current.last_name;
                    }
                }
                String display = (first + " " + last).trim();
                if (display.isEmpty()) {
                    throw new WaysGramApi.ApiException(400, "FIRSTNAME_INVALID");
                }
                body.put("display_name", display);
            }
            if (hasAbout) {
                body.put("bio", req.about == null ? "" : req.about.trim());
            }
        } catch (org.json.JSONException ignore) {
        }
        if (body.length() == 0) {
            return selfUser(WaysGramApi.me(account));
        }
        try {
            return selfUser(WaysGramApi.patch(account, "/api/me", body));
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 400) {
                boolean aboutProblem = e.detail != null && e.detail.contains("О себе");
                throw new WaysGramApi.ApiException(400, aboutProblem ? "ABOUT_TOO_LONG" : "FIRSTNAME_INVALID");
            }
            throw e;
        }
    }

    public static TLObject updateUsername(int account, String username) throws WaysGramApi.ApiException {
        String name = clean(username);
        if (!name.matches(USERNAME_PATTERN)) {
            throw new WaysGramApi.ApiException(400, "USERNAME_INVALID");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("username", name);
        } catch (Exception ignore) {
        }
        try {
            return selfUser(WaysGramApi.patch(account, "/api/me", body));
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 409) {
                throw new WaysGramApi.ApiException(400, "USERNAME_OCCUPIED");
            }
            if (e.status == 400) {
                throw new WaysGramApi.ApiException(400, "USERNAME_INVALID");
            }
            throw e;
        }
    }

    /** True when the username is free (or already yours). */
    public static TLObject checkUsername(int account, String username, long selfId, boolean includeChannels) throws WaysGramApi.ApiException {
        String name = clean(username);
        if (!name.matches(USERNAME_PATTERN)) {
            throw new WaysGramApi.ApiException(400, "USERNAME_INVALID");
        }
        JSONObject users = WaysGramApi.get(account, "/api/users?q=" + android.net.Uri.encode(name));
        JSONArray list = users.optJSONArray("users");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject u = list.optJSONObject(i);
                if (u != null && name.equalsIgnoreCase(u.optString("username")) && u.optLong("id") != selfId) {
                    return new TLRPC.TL_boolFalse();
                }
            }
        }
        if (includeChannels) {
            JSONArray channels = WaysGramApi.get(account, "/api/channels").optJSONArray("channels");
            if (channels != null) {
                for (int i = 0; i < channels.length(); i++) {
                    JSONObject c = channels.optJSONObject(i);
                    if (c != null && name.equalsIgnoreCase(c.optString("username"))) {
                        return new TLRPC.TL_boolFalse();
                    }
                }
            }
        }
        return new TLRPC.TL_boolTrue();
    }

    private static TLRPC.User selfUser(JSONObject json) {
        return WaysGramMapper.toSelfUser(json, null);
    }

    // ---------------------------------------------------------------- avatar

    public static TLObject uploadPhoto(int account, TLRPC.TL_photos_uploadProfilePhoto req) throws WaysGramApi.ApiException {
        if (req.file == null) {
            throw new WaysGramApi.ApiException(400, "PHOTO_INVALID");
        }
        String mediaId = WaysGramMedia.uploadFile(account, req.file, true);
        JSONObject body = new JSONObject();
        try {
            body.put("media_id", mediaId);
        } catch (Exception ignore) {
        }
        WaysGramApi.request("PUT", "/api/me/avatar", body, WaysGramApi.getToken(account), 30000);
        TLRPC.TL_photos_photo out = new TLRPC.TL_photos_photo();
        TLRPC.MessageMedia media = WaysGramMedia.buildMedia(account, mediaId, "image/jpeg", "");
        out.photo = ((TLRPC.TL_messageMediaPhoto) media).photo;
        out.users.add(selfUser(WaysGramApi.me(account)));
        return out;
    }

    public static TLObject deletePhoto(int account) throws WaysGramApi.ApiException {
        WaysGramApi.delete(account, "/api/me/avatar");
        return new TLRPC.TL_boolTrue();
    }

    /** The profile pictures of a user: the server keeps one (the current avatar). */
    public static TLObject userPhotos(int account, long userId, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_photos_photos out = new TLRPC.TL_photos_photos();
        JSONObject json = userId == selfId ? WaysGramApi.me(account) : WaysGramApi.get(account, "/api/users/" + userId);
        String avatar = json.optString("avatar_media_id", "");
        if (avatar.length() >= 15) {
            TLRPC.MessageMedia media = WaysGramMedia.buildMedia(account, avatar, "image/jpeg", "");
            out.photos.add(((TLRPC.TL_messageMediaPhoto) media).photo);
        }
        TLRPC.TL_user user = WaysGramMapper.toUser(json);
        user.self = userId == selfId;
        out.users.add(user);
        out.count = out.photos.size();
        return out;
    }

    // UI-thread cache: use the document that was actually resolved, including legacy traits without IDs.
    private static final java.util.LinkedHashMap<String, TLRPC.Document> readyWornPatterns = new java.util.LinkedHashMap<>();

    public static TLRPC.Document wornPatternDocument(int account, long giftId) {
        return readyWornPatterns.get(account + ":" + giftId);
    }

    public static void rememberWornPattern(int account, long giftId, TLRPC.Document pattern) {
        if (pattern == null) return;
        String key = account + ":" + giftId;
        TLRPC.Document previous = readyWornPatterns.put(key, pattern);
        if (readyWornPatterns.size() > 64) readyWornPatterns.remove(readyWornPatterns.keySet().iterator().next());
        if (previous != pattern) {
            org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(
                    org.telegram.messenger.NotificationCenter.updateInterfaces,
                    org.telegram.messenger.MessagesController.UPDATE_MASK_EMOJI_STATUS);
        }
    }

    private static final java.util.LinkedHashMap<String, JSONObject> pendingWornArtwork = new java.util.LinkedHashMap<>();
    private static final org.telegram.messenger.waysgram.WaysGramGiftAttributes.Listener wornArtworkListener =
            new org.telegram.messenger.waysgram.WaysGramGiftAttributes.Listener() {
                @Override public void onGiftAttributesChanged(String giftId) {
                    AndroidUtilities.runOnUIThread(() -> retryWornArtwork(giftId));
                }
                @Override public void onGiftMetadataLoaded(String giftId) {
                    onGiftAttributesChanged(giftId);
                }
            };

    private static void retryWornArtwork(String giftId) {
        for (String key : new java.util.ArrayList<>(pendingWornArtwork.keySet())) {
            JSONObject gift = pendingWornArtwork.get(key);
            if (gift != null && giftId.equals(gift.optString("telegram_gift_id"))) {
                int account = Integer.parseInt(key.substring(0, key.indexOf(':')));
                if (registerWornArtwork(account, gift)) pendingWornArtwork.remove(key);
            }
        }
    }

    private static boolean registerWornArtwork(int account, JSONObject gift) {
        String giftId = gift.optString("telegram_gift_id");
        JSONObject traits = gift.optJSONObject("traits");
        org.telegram.messenger.waysgram.WaysGramGiftAttributes.wantRolled(giftId, traits);
        TLRPC.Document model = org.telegram.messenger.waysgram.WaysGramGiftAttributes.traitDocument(giftId, "model", traits, "model");
        TLRPC.Document pattern = org.telegram.messenger.waysgram.WaysGramGiftAttributes.traitDocument(giftId, "pattern", traits, "pattern");
        // Resolve the pattern by its exact name if the old payload lacks a
        // distinct document ID. Never let the model become the header pattern.
        TLRPC.TL_emojiStatusCollectible status = (TLRPC.TL_emojiStatusCollectible) WaysGramMapper.wornGiftStatus(gift);
        long modelId = model == null ? status.document_id : model.id;
        if (pattern == null || pattern.id == modelId) {
            pattern = org.telegram.messenger.waysgram.WaysGramGiftAttributes.document(giftId, "pattern",
                    org.telegram.messenger.waysgram.WaysGramGiftTraits.name(traits, "pattern"));
        }
        if (pattern != null && pattern.id == modelId) pattern = null;
        java.util.ArrayList<TLRPC.Document> ready = new java.util.ArrayList<>();
        final org.telegram.ui.Components.AnimatedEmojiDrawable.EmojiDocumentFetcher fetcher =
                org.telegram.ui.Components.AnimatedEmojiDrawable.getDocumentFetcher(account);
        if (model != null && org.telegram.ui.Components.AnimatedEmojiDrawable.findDocument(account, model.id) == null) ready.add(model);
        if (pattern != null && org.telegram.ui.Components.AnimatedEmojiDrawable.findDocument(account, pattern.id) == null) ready.add(pattern);
        if (!ready.isEmpty()) fetcher.processDocuments(ready);
        rememberWornPattern(account, gift.optLong("id"), pattern);
        if (!ready.isEmpty() && pattern == null) {
            org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(
                    org.telegram.messenger.NotificationCenter.updateInterfaces,
                    org.telegram.messenger.MessagesController.UPDATE_MASK_EMOJI_STATUS);
        }
        return model != null && pattern != null;
    }

    public static void prepareWornArtwork(int account, JSONObject gift) {
        if (gift == null || gift.optLong("id") <= 0) return;
        AndroidUtilities.runOnUIThread(() -> {
            org.telegram.messenger.waysgram.WaysGramGiftAttributes.attach(org.telegram.messenger.ApplicationLoader.applicationContext, account);
            org.telegram.messenger.waysgram.WaysGramGiftAttributes.addListener(wornArtworkListener);
            if (registerWornArtwork(account, gift)) return;
            String key = account + ":" + gift.optLong("id");
            pendingWornArtwork.put(key, gift);
            if (pendingWornArtwork.size() > 64) pendingWornArtwork.remove(pendingWornArtwork.keySet().iterator().next());
        });
    }

    /** The profile card: about text and picture. */
    private static org.telegram.tgnet.tl.TL_stars.Tl_starsRating readStarRating(JSONObject value) {
        if (value == null) return null;
        org.telegram.tgnet.tl.TL_stars.Tl_starsRating rating = new org.telegram.tgnet.tl.TL_stars.Tl_starsRating();
        rating.level = value.optInt("level");
        rating.stars = value.optLong("stars");
        rating.current_level_stars = value.optLong("current_level_stars");
        if (!value.isNull("next_level_stars")) {
            rating.flags |= TLObject.FLAG_0;
            rating.next_level_stars = value.optLong("next_level_stars");
        }
        return rating;
    }

    public static TLObject fullUser(int account, long userId, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_users_userFull out = new TLRPC.TL_users_userFull();
        JSONObject json;
        if (WaysGramBotFather.is(userId)) {
            json = new JSONObject();
        } else {
            json = userId == selfId ? WaysGramApi.me(account) : WaysGramApi.get(account, "/api/users/" + userId);
        }
        TLRPC.TL_user user;
        if (WaysGramBotFather.is(userId)) {
            user = WaysGramBotFather.user();
        } else {
            user = userId == selfId ? WaysGramMapper.toSelfUser(json, null) : WaysGramMapper.toUser(json);
        }
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = user.id;
        if (json.has("dm_auto_delete_seconds")) {
            full.ttl_period = json.optInt("dm_auto_delete_seconds");
            full.flags |= 16384;
        }
        full.can_pin_message = !WaysGramBotFather.is(userId);
        full.about = json.optString("bio", "");
        full.flags |= TLObject.FLAG_1;
        JSONObject birthday = json.optJSONObject("birthday");
        if (birthday != null) {
            full.birthday = new TL_account.TL_birthday();
            full.birthday.day = birthday.optInt("day");
            full.birthday.month = birthday.optInt("month");
            if (!birthday.isNull("year")) {
                full.birthday.year = birthday.optInt("year");
                full.birthday.flags |= TLObject.FLAG_0;
            }
            full.flags2 |= TLObject.FLAG_5;
        }
        full.settings = new TLRPC.TL_peerSettings();
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        full.common_chats_count = 0;
        String avatar = json.optString("avatar_media_id", "");
        if (avatar.length() >= 15) {
            TLRPC.MessageMedia media = WaysGramMedia.buildMedia(account, avatar, "image/jpeg", "");
            full.profile_photo = ((TLRPC.TL_messageMediaPhoto) media).photo;
            full.flags |= TLObject.FLAG_2;
        }
        full.stars_rating = readStarRating(json.optJSONObject("stars_rating"));
        if (userId == selfId) {
            full.stars_my_pending_rating = readStarRating(json.optJSONObject("stars_my_pending_rating"));
            full.stars_my_pending_rating_date = json.optInt("stars_my_pending_rating_date");
        }
        if (!WaysGramBotFather.is(userId)) {
            try {
                TLRPC.TL_savedMusic music = WaysGramMusic.list(account, userId, 0, 1);
                if (!music.documents.isEmpty()) {
                    full.saved_music = music.documents.get(0);
                    full.flags2 |= TLObject.FLAG_21;
                }
            } catch (WaysGramApi.ApiException e) {
                if (e.status != 404) throw e;
            }
        }
        JSONObject personal = json.optJSONObject("personal_channel");
        if (personal != null && personal.optLong("id") > 0) {
            TLRPC.Chat chat = WaysGramChats.toChannel(personal, selfId, userId == selfId);
            full.personal_channel_id = chat.id;
            full.flags2 |= TLObject.FLAG_6;
            out.chats.add(chat);
        }
        out.full_user = full;
        prepareWornArtwork(account, json.optJSONObject("worn_gift"));
        out.users.add(user);
        return out;
    }

    public static TLObject publicChannels(int account, long selfId, boolean ownerOnly) throws WaysGramApi.ApiException {
        TLRPC.TL_messages_chats out = new TLRPC.TL_messages_chats();
        org.json.JSONArray channels = WaysGramApi.get(account, "/api/channels").optJSONArray("channels");
        if (channels != null) for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.optJSONObject(i);
            if (channel == null || channel.optString("username").isEmpty()) continue;
            boolean owner = channel.optLong("owner_telegram_id") == selfId;
            if (owner || !ownerOnly && (channel.optInt("is_admin") != 0 || channel.optBoolean("is_admin"))) {
                out.chats.add(WaysGramChats.toChannel(channel, selfId, true));
            }
        }
        return out;
    }

    public static TLObject setPersonalChannel(int account, TLRPC.InputChannel channel) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        long id = channel instanceof TLRPC.TL_inputChannelEmpty ? 0 : WaysGramChats.channelServer(channel.channel_id);
        if (id < 0) throw new WaysGramApi.ApiException(400, "CHANNEL_INVALID");
        try { body.put("channel_id", id); }
        catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "CHANNEL_INVALID"); }
        WaysGramApi.put(account, "/api/me/profile-channel", body);
        return new TLRPC.TL_boolTrue();
    }

    public static TLObject updateBirthday(int account, TL_account.TL_birthday birthday) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            if (birthday == null) {
                body.put("birthday", JSONObject.NULL);
            } else {
                body.put("birthday", new JSONObject().put("day", birthday.day).put("month", birthday.month)
                        .put("year", (birthday.flags & 1) != 0 ? birthday.year : JSONObject.NULL));
            }
        } catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "BIRTHDAY_INVALID"); }
        WaysGramApi.put(account, "/api/me/birthday", body);
        return new TLRPC.TL_boolTrue();
    }

    public static String privacySetting(TLRPC.InputPrivacyKey key) {
        if (key instanceof TLRPC.TL_inputPrivacyKeyChatInvite) return "invites";
        if (key instanceof TLRPC.TL_inputPrivacyKeyPhoneCall) return "calls";
        if (key instanceof TLRPC.TL_inputPrivacyKeyPhoneP2P) return "calls-p2p";
        if (key instanceof TLRPC.TL_inputPrivacyKeyPhoneNumber) return "phone";
        if (key instanceof TLRPC.TL_inputPrivacyKeyAddedByPhone) return "find-by-phone";
        if (key instanceof TLRPC.TL_inputPrivacyKeyProfilePhoto) return "avatar";
        if (key instanceof TLRPC.TL_inputPrivacyKeyAbout) return "bio";
        if (key instanceof TLRPC.TL_inputPrivacyKeyBirthday) return "birthday";
        if (key instanceof TLRPC.TL_inputPrivacyKeySavedMusic) return "music";
        if (key instanceof TLRPC.TL_inputPrivacyKeyVoiceMessages) return "voice";
        if (key instanceof TLRPC.TL_inputPrivacyKeyForwards) return "forwards";
        if (key instanceof TLRPC.TL_inputPrivacyKeyStarGiftsAutoSave) return "gift-autosave";
        if (key instanceof TLRPC.TL_inputPrivacyKeyStatusTimestamp) return "last-seen";
        return null;
    }

    public static TLObject privacy(int account, long selfId, String setting, java.util.List<TLRPC.InputPrivacyRule> rules) throws WaysGramApi.ApiException {
        String path = "/api/me/privacy/" + setting;
        JSONObject data;
        if (rules == null) {
            data = WaysGramApi.get(account, path);
        } else {
            JSONObject body = new JSONObject();
            org.json.JSONArray allow = new org.json.JSONArray(), deny = new org.json.JSONArray();
            String mode = "nobody";
            for (TLRPC.InputPrivacyRule rule : rules) {
                if (rule instanceof TLRPC.TL_inputPrivacyValueAllowAll) mode = "everyone";
                else if (rule instanceof TLRPC.TL_inputPrivacyValueAllowContacts) mode = "contacts";
                else if (rule instanceof TLRPC.TL_inputPrivacyValueDisallowAll) mode = "nobody";
                else if (rule instanceof TLRPC.TL_inputPrivacyValueAllowUsers) {
                    for (TLRPC.InputUser user : ((TLRPC.TL_inputPrivacyValueAllowUsers) rule).users)
                        allow.put(user instanceof TLRPC.TL_inputUserSelf ? selfId : user.user_id);
                } else if (rule instanceof TLRPC.TL_inputPrivacyValueDisallowUsers) {
                    for (TLRPC.InputUser user : ((TLRPC.TL_inputPrivacyValueDisallowUsers) rule).users)
                        deny.put(user instanceof TLRPC.TL_inputUserSelf ? selfId : user.user_id);
                } else throw new WaysGramApi.ApiException(400, "PRIVACY_RULE_INVALID");
            }
            try { body.put("mode", mode).put("allow", allow).put("deny", deny); }
            catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "PRIVACY_RULE_INVALID"); }
            data = WaysGramApi.put(account, path, body);
        }
        TL_account.privacyRules out = new TL_account.privacyRules();
        TLRPC.TL_privacyValueAllowUsers allow = new TLRPC.TL_privacyValueAllowUsers();
        TLRPC.TL_privacyValueDisallowUsers deny = new TLRPC.TL_privacyValueDisallowUsers();
        org.json.JSONArray allowed = data.optJSONArray("allow"), denied = data.optJSONArray("deny");
        if (allowed != null) for (int i = 0; i < allowed.length(); i++) allow.users.add(allowed.optLong(i));
        if (denied != null) for (int i = 0; i < denied.length(); i++) deny.users.add(denied.optLong(i));
        if (!deny.users.isEmpty()) out.rules.add(deny);
        if (!allow.users.isEmpty()) out.rules.add(allow);
        String mode = data.optString("mode");
        out.rules.add("everyone".equals(mode) ? new TLRPC.TL_privacyValueAllowAll() :
                "contacts".equals(mode) ? new TLRPC.TL_privacyValueAllowContacts() : new TLRPC.TL_privacyValueDisallowAll());
        return out;
    }

    // ---------------------------------------------------------------- channels

    // Create privately until the owner chooses a public username.
    public static TLObject createChannel(int account, TLRPC.TL_channels_createChannel req, long selfId) throws WaysGramApi.ApiException {
        String title = req.title == null ? "" : req.title.trim();
        if (req.megagroup) {
            TLRPC.TL_messages_createChat chat = new TLRPC.TL_messages_createChat();
            chat.title = title;
            TLRPC.TL_messages_invitedUsers invited = WaysGramChats.createChat(account, chat, selfId);
            return invited.updates;
        }
        JSONObject body = new JSONObject();
        JSONObject created = null;
        for (int attempt = 0; attempt < 3 && created == null; attempt++) {
            try {
                body.put("title", title);
                body.put("is_public", false);
                body.put("description", req.about == null ? "" : req.about);
            } catch (Exception ignore) {
            }
            try {
                created = WaysGramApi.post(account, "/api/channels", body);
            } catch (WaysGramApi.ApiException e) {
                if (e.status != 409) {
                    throw e;
                }
            }
        }
        if (created == null) {
            throw new WaysGramApi.ApiException(400, "USERNAME_OCCUPIED");
        }
        WaysGramChats.setJoined(account, created.optLong("id"), true);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.chats.add(WaysGramChats.toChannel(created, selfId, true));
        return updates;
    }

    public static TLObject updateChannelUsername(int account, TLRPC.TL_channels_updateUsername req) throws WaysGramApi.ApiException {
        String name = clean(req.username);
        if (!name.isEmpty() && !name.matches(USERNAME_PATTERN)) {
            throw new WaysGramApi.ApiException(400, "USERNAME_INVALID");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("is_public", !name.isEmpty());
            if (!name.isEmpty()) body.put("username", name);
        } catch (Exception ignore) {
        }
        patchChannel(account, req.channel.channel_id, body);
        return new TLRPC.TL_boolTrue();
    }

    public static TLObject editChannelTitle(int account, long channelTgId, String title) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("title", title == null ? "" : title.trim());
        } catch (Exception ignore) {
        }
        patchChannel(account, channelTgId, body);
        return new TLRPC.TL_updates();
    }

    public static TLObject editChannelAbout(int account, long channelTgId, String about) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("description", about == null ? "" : about);
        } catch (org.json.JSONException e) {
            throw new WaysGramApi.ApiException(400, "CHAT_SETTINGS_INVALID");
        }
        patchChannel(account, channelTgId, body);
        return new TLRPC.TL_boolTrue();
    }

    private static void patchChannel(int account, long channelTgId, JSONObject body) throws WaysGramApi.ApiException {
        try {
            WaysGramApi.put(account, "/api/channels/" + WaysGramChats.channelServer(channelTgId), body);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 409) {
                throw new WaysGramApi.ApiException(400, "USERNAME_OCCUPIED");
            }
            if (e.status == 403) {
                throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            }
            if (e.status == 400 && !TextUtils.isEmpty(e.detail) && e.detail.contains("Username")) {
                throw new WaysGramApi.ApiException(400, "USERNAME_INVALID");
            }
            throw e;
        }
    }
}
