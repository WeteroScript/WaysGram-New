package org.telegram.tgnet;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Groups (/api/groups) and channels (/api/channels).
 *
 * Groups are shown as ordinary group chats. Channels are broadcast channels: the owner and admins post, everybody
 * can read. Channel membership is stored on the server and cached on the device.
 * Channel ids are shifted by CHANNEL_OFFSET so that a group and a channel can never share an id in the app.
 */
public class WaysGramChats {

    public static final long CHANNEL_OFFSET = 1_000_000_000L;

    /**
     * Ids of group messages and of private messages come from separate counters on the server. The app addresses
     * deletions by message id alone, so group message ids are shifted to keep the two ranges apart.
     */
    public static final int GROUP_MESSAGE_OFFSET = 1_000_000_000;

    public static int clientGroupId(int serverId) {
        return serverId > 0 ? serverId + GROUP_MESSAGE_OFFSET : 0;
    }

    public static int serverGroupId(int clientId) {
        return clientId > GROUP_MESSAGE_OFFSET ? clientId - GROUP_MESSAGE_OFFSET : 0;
    }

    public static long channelTg(long serverId) {
        return serverId + CHANNEL_OFFSET;
    }

    public static long channelServer(long tgId) {
        return tgId - CHANNEL_OFFSET;
    }

    private static final HashMap<Long, TLRPC.Chat> chatCache = new HashMap<>();

    public static synchronized TLRPC.Chat cachedChat(long tgId) {
        return chatCache.get(tgId);
    }

    // ---------------------------------------------------------------- joined channels (device-side)

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("waysgram_session", Context.MODE_PRIVATE);
    }

    public static HashSet<Long> joinedChannels(int account) {
        HashSet<Long> set = new HashSet<>();
        String raw = prefs().getString("channels_" + account, "");
        for (String part : raw.split(",")) {
            if (!part.isEmpty()) {
                try {
                    set.add(Long.parseLong(part));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return set;
    }

    public static void setJoined(int account, long serverId, boolean joined) {
        HashSet<Long> set = joinedChannels(account);
        if (joined) {
            set.add(serverId);
        } else {
            set.remove(serverId);
        }
        prefs().edit().putString("channels_" + account, TextUtils.join(",", set)).apply();
    }

    // ---------------------------------------------------------------- per-channel counters

    static int nextChannelPts(int account, long channelServerId) {
        String key = "chpts_" + account + "_" + channelServerId;
        int value = prefs().getInt(key, 1) + 1;
        prefs().edit().putInt(key, value).apply();
        return value;
    }

    // ---------------------------------------------------------------- builders

    public static TLRPC.TL_chat toChat(JSONObject g, long selfId) {
        TLRPC.TL_chat chat = new TLRPC.TL_chat();
        chat.id = g.optLong("id");
        chat.title = g.optString("title", "");
        chat.photo = new TLRPC.TL_chatPhotoEmpty();
        chat.participants_count = Math.max(1, g.optInt("member_count", 1));
        chat.date = g.optInt("joined_at", g.optInt("created_at", (int) (System.currentTimeMillis() / 1000)));
        chat.version = 1;
        chat.creator = g.optLong("owner_telegram_id") == selfId;
        String role = g.optString("my_role", g.optString("role", ""));
        if ("admin".equals(role)) {
            chat.admin_rights = new TLRPC.TL_chatAdminRights();
            int rights = g.optInt("admin_rights", 63);
            chat.admin_rights.change_info = (rights & 1) != 0;
            chat.admin_rights.invite_users = (rights & 2) != 0;
            chat.admin_rights.ban_users = (rights & 4) != 0;
            chat.admin_rights.pin_messages = (rights & 8) != 0;
            chat.admin_rights.manage_call = (rights & 32) != 0;
            chat.admin_rights.delete_messages = (rights & 16) != 0;
            chat.flags |= TLObject.FLAG_14;
        }
        synchronized (WaysGramChats.class) {
            chatCache.put(chat.id, chat);
        }
        return chat;
    }

    private static TLRPC.TL_chatAdminRights channelRights(int mask) {
        TLRPC.TL_chatAdminRights rights = new TLRPC.TL_chatAdminRights();
        rights.post_messages = (mask & 1) != 0;
        rights.edit_messages = (mask & 2) != 0;
        rights.delete_messages = (mask & 4) != 0;
        rights.pin_messages = (mask & 8) != 0;
        rights.invite_users = (mask & 16) != 0;
        rights.ban_users = (mask & 32) != 0;
        rights.change_info = (mask & 64) != 0;
        return rights;
    }

    static TLObject editChannelAdmin(int account, TLRPC.TL_channels_editAdmin req, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_chatAdminRights rights = req.admin_rights;
        if (rights == null || req.user_id == null || rights.add_admins || rights.anonymous
                || rights.manage_call || rights.manage_topics || rights.post_stories || rights.edit_stories
                || rights.delete_stories || rights.manage_direct_messages || rights.manage_ranks
                || rights.manage_linked_peers || rights.manage_welcome_messages
                || (req.rank != null && !req.rank.isEmpty())) {
            throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_RIGHTS_UNSUPPORTED");
        }
        int mask = (rights.post_messages ? 1 : 0) | (rights.edit_messages ? 2 : 0)
                | (rights.delete_messages ? 4 : 0) | (rights.pin_messages ? 8 : 0)
                | (rights.invite_users ? 16 : 0) | (rights.ban_users ? 32 : 0) | (rights.change_info ? 64 : 0);
        if (mask == 0 && rights.other) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_RIGHTS_UNSUPPORTED");
        String path = "/api/channels/" + channelServer(req.channel.channel_id);
        long target = req.user_id instanceof TLRPC.TL_inputUserSelf ? selfId : req.user_id.user_id;
        if (mask == 0) {
            WaysGramApi.delete(account, path + "/admins/" + target);
        } else {
            JSONObject body = new JSONObject();
            try { body.put("telegram_id", target); body.put("rights", mask); }
            catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "USER_ID_INVALID"); }
            WaysGramApi.post(account, path + "/admins", body);
        }
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.chats.add(toChannel(WaysGramApi.get(account, path), selfId, true));
        return updates;
    }

    public static TLRPC.TL_channel toChannel(JSONObject c, long selfId, boolean member) {
        TLRPC.TL_channel channel = new TLRPC.TL_channel();
        channel.id = channelTg(c.optLong("id"));
        channel.access_hash = channel.id;
        channel.flags |= TLObject.FLAG_13;
        channel.title = c.optString("title", "");
        String username = c.optString("username", "");
        if (!username.isEmpty()) {
            channel.username = username;
            channel.flags |= TLObject.FLAG_6;
        }
        channel.photo = new TLRPC.TL_chatPhotoEmpty();
        channel.date = (int) (System.currentTimeMillis() / 1000);
        channel.broadcast = true;
        channel.creator = c.optLong("owner_telegram_id") == selfId;
        if (!channel.creator && (c.optBoolean("is_admin", false) || c.optInt("is_admin", 0) == 1)) {
            channel.admin_rights = channelRights(c.optInt("admin_rights", 15));
            channel.flags |= TLObject.FLAG_14;
        }
        channel.left = !member && !channel.creator;
        synchronized (WaysGramChats.class) {
            chatCache.put(channel.id, channel);
        }
        return channel;
    }

    public static TLRPC.TL_message groupMessage(JSONObject m, long chatId, long selfId, int account) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        long sender = m.optLong("sender_id");
        message.id = clientGroupId(m.optInt("id"));
        TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
        peer.chat_id = chatId;
        long identity = m.optLong("send_as_id", -1);
        message.out = identity >= 0 ? m.optBoolean("is_mine") : sender == selfId;
        if (identity == 0) {
            message.from_id = peer;
        } else if (identity > 0) {
            TLRPC.TL_peerChannel from = new TLRPC.TL_peerChannel();
            from.channel_id = channelTg(identity);
            message.from_id = from;
            String title = m.optString("identity_title");
            if (!TextUtils.isEmpty(title)) AndroidUtilities.runOnUIThread(() -> {
                org.telegram.messenger.MessagesController controller = org.telegram.messenger.MessagesController.getInstance(account);
                if (controller.getChat(from.channel_id) == null) {
                    TLRPC.TL_channel display = new TLRPC.TL_channel();
                    display.id = from.channel_id;
                    display.title = title;
                    display.broadcast = true;
                    display.left = true;
                    display.photo = new TLRPC.TL_chatPhotoEmpty();
                    controller.putChat(display, true);
                }
            });
        } else {
            message.from_id = WaysGramMapper.peerUser(sender);
        }
        message.noforwards = identity >= 0;
        message.peer_id = peer;
        message.dialog_id = -chatId;
        message.date = m.optInt("created_at");
        message.message = m.optString("body", "");
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID | TLObject.FLAG_9 | (message.out ? TLObject.FLAG_1 : 0);
        return message;
    }

    public static TLRPC.TL_message channelMessage(JSONObject m, long channelTgId, long selfId) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = m.optInt("id");
        message.out = m.optLong("author_id") == selfId;
        message.post = true;
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = channelTgId;
        message.peer_id = peer;
        message.dialog_id = -channelTgId;
        message.date = m.optInt("created_at");
        message.message = m.optString("body", "");
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.flags = TLObject.FLAG_9 | TLObject.FLAG_10 | TLObject.FLAG_14 | (message.out ? TLObject.FLAG_1 : 0);
        return message;
    }

    // ---------------------------------------------------------------- dialogs

    public static class Snapshot {
        public final ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
        public final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        public final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        public final ArrayList<TLRPC.User> users = new ArrayList<>();
        private final HashSet<Long> userIds = new HashSet<>();

        void addUsers(JSONArray members) {
            if (members == null) {
                return;
            }
            for (int i = 0; i < members.length(); i++) {
                JSONObject u = members.optJSONObject(i);
                if (u == null) {
                    continue;
                }
                TLRPC.TL_user user = WaysGramMapper.toUser(u);
                if (userIds.add(user.id)) {
                    users.add(user);
                }
            }
        }
    }

    private static JSONObject lastOf(JSONObject json) {
        JSONArray items = json.optJSONArray("messages");
        if (items == null || items.length() == 0) {
            return null;
        }
        JSONObject best = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject m = items.optJSONObject(i);
            if (m != null && (best == null || m.optInt("id") > best.optInt("id"))) {
                best = m;
            }
        }
        return best;
    }

    /** Groups the user belongs to and channels the user owns or has joined. Empty chats have no top message. */
    public static Snapshot collect(int account, long selfId) throws WaysGramApi.ApiException {
        Snapshot out = new Snapshot();

        JSONArray groups = WaysGramApi.get(account, "/api/groups").optJSONArray("groups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) {
                    continue;
                }
                long id = g.optLong("id");
                JSONObject last = g.optInt("message_count") > 0
                        ? lastOf(WaysGramApi.get(account, "/api/groups/" + id + "/messages?limit=1")) : null;
                out.chats.add(toChat(g, selfId));
                try {
                    out.addUsers(WaysGramApi.get(account, "/api/groups/" + id).optJSONArray("members"));
                } catch (WaysGramApi.ApiException ignore) {
                }
                TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
                peer.chat_id = id;
                int top = last == null ? 0 : clientGroupId(last.optInt("id"));
                TLRPC.TL_dialog groupDialog = WaysGramMapper.toDialog(peer, top);
                if (last == null) groupDialog.last_message_date = g.optInt("joined_at");
                WaysGramMapper.applyReadState(groupDialog, g.optInt("unread_count"),
                        clientGroupId(g.optInt("last_read_id")), top);
                out.dialogs.add(groupDialog);
                if (last != null) out.messages.add(groupMessage(last, id, selfId, account));
            }
        }

        JSONArray channels = WaysGramApi.get(account, "/api/channels").optJSONArray("channels");
        HashSet<Long> joined = joinedChannels(account);
        if (channels != null) {
            for (int i = 0; i < channels.length(); i++) {
                JSONObject c = channels.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                long serverId = c.optLong("id");
                boolean mine = c.optLong("owner_telegram_id") == selfId;
                if (!mine && !listedChannelMember(c, joined)) {
                    continue;
                }
                JSONObject last = lastOf(WaysGramApi.get(account, "/api/channels/" + serverId + "/messages?limit=1"));
                long tg = channelTg(serverId);
                out.chats.add(toChannel(c, selfId, true));
                TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
                peer.channel_id = tg;
                int top = last == null ? 0 : last.optInt("id");
                TLRPC.TL_dialog channelDialog = WaysGramMapper.toDialog(peer, top);
                channelDialog.flags |= TLObject.FLAG_0;
                if (last == null) channelDialog.last_message_date = c.optInt("created_at");
                WaysGramMapper.applyReadState(channelDialog, c.optInt("unread_count"),
                        c.optInt("last_read_id"), top);
                out.dialogs.add(channelDialog);
                if (last != null) out.messages.add(channelMessage(last, tg, selfId));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- history

    public static TLRPC.TL_messages_messages history(int account, TLRPC.TL_messages_getHistory req, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_messages_messages out = new TLRPC.TL_messages_messages();
        ArrayList<TLRPC.Message> list = new ArrayList<>();
        boolean groupPeer = req.peer instanceof TLRPC.TL_inputPeerChat;
        int after = groupPeer ? serverGroupId(req.min_id) : req.min_id;
        int before = groupPeer ? serverGroupId(req.offset_id) : req.offset_id;
        String paging = "?limit=" + Math.max(1, Math.min(req.limit > 0 ? req.limit : 100, 100));
        if (after > 0) paging += "&after_id=" + after;
        else if (before > 0) paging += "&before_id=" + before;

        if (req.peer instanceof TLRPC.TL_inputPeerChat) {
            long id = req.peer.chat_id;
            JSONObject group = WaysGramApi.get(account, "/api/groups/" + id);
            out.chats.add(toChat(group, selfId));
            Snapshot users = new Snapshot();
            users.addUsers(group.optJSONArray("members"));
            out.users.addAll(users.users);
            JSONArray items = WaysGramApi.get(account, "/api/groups/" + id + "/messages" + paging).optJSONArray("messages");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject m = items.optJSONObject(i);
                    if (m != null) {
                        list.add(WaysGramMedia.attach(account, groupMessage(m, id, selfId, account), m));
                    }
                }
            }
        } else if (req.peer instanceof TLRPC.TL_inputPeerChannel) {
            long tg = req.peer.channel_id;
            long serverId = channelServer(tg);
            JSONObject channel = WaysGramApi.get(account, "/api/channels/" + serverId);
            out.chats.add(toChannel(channel, selfId, joinedChannels(account).contains(serverId)));
            JSONArray items = WaysGramApi.get(account, "/api/channels/" + serverId + "/messages" + paging).optJSONArray("messages");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject m = items.optJSONObject(i);
                    if (m != null) {
                        list.add(WaysGramMedia.attach(account, channelMessage(m, tg, selfId), m));
                    }
                }
            }
        } else {
            return out;
        }
        // The API returns ascending pages; the native client expects newest first.
        ArrayList<TLRPC.Message> filtered = new ArrayList<>();
        for (TLRPC.Message m : list) {
            if (req.min_id > 0 && m.id <= req.min_id) {
                continue;
            }
            if (req.offset_id > 0 && m.id >= req.offset_id) {
                continue;
            }
            filtered.add(m);
        }
        Collections.sort(filtered, (a, b) -> Integer.compare(b.id, a.id));
        if (req.limit > 0 && filtered.size() > req.limit) {
            out.messages.addAll(filtered.subList(0, req.limit));
        } else {
            out.messages.addAll(filtered);
        }
        return out;
    }

    // ---------------------------------------------------------------- send

    public static TLObject send(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, int replyTo, long selfId, int globalPts, boolean silent, JSONObject location, JSONObject contact, JSONArray entities, JSONObject quote) throws WaysGramApi.ApiException {
        return send(account, peer, text, randomId, mediaId, replyTo, selfId, globalPts, silent, location, contact, entities, quote, null);
    }

    public static TLObject send(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, int replyTo, long selfId, int globalPts, boolean silent, JSONObject location, JSONObject contact, JSONArray entities, JSONObject quote, JSONObject card) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            if (card != null) body.put("card", card);
            body.put("body", text == null ? "" : text);
            if (entities != null && entities.length() != 0) body.put("entities", entities);
            body.put("silent", silent);
            if (quote != null) body.put("quote", quote);
            if (location != null) {
                body.put("location", location);
            }
            if (contact != null) {
                body.put("contact", contact);
            }
            if (!TextUtils.isEmpty(mediaId)) {
                body.put("media_id", mediaId);
            }
            int serverReply = peer instanceof TLRPC.TL_inputPeerChat ? serverGroupId(replyTo) : replyTo;
            if (serverReply > 0) {
                body.put("reply_to_id", serverReply);
            }
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "MESSAGE_INVALID");
        }
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.seq = 0;
        TL_update.TL_updateMessageID mid = new TL_update.TL_updateMessageID();
        mid.random_id = randomId;

        if (peer instanceof TLRPC.TL_inputPeerChat) {
            long id = peer.chat_id;
            String key = "wg-group-" + java.util.UUID.nameUUIDFromBytes(
                    Long.toString(randomId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JSONObject res = WaysGramApi.postIdempotent(account, "/api/groups/" + id + "/messages" + (quote != null ? "/quote/v1" : ""), body, key);
            mid.id = clientGroupId(res.optInt("id"));
            WaysGramPoller.markDelivered(account, -id, res.optInt("id"));
            TLRPC.TL_message message = (TLRPC.TL_message) WaysGramMedia.attach(account, groupMessage(res, id, selfId, account), res);
            updates.updates.add(mid);
            TL_update.TL_updateNewMessage nm = new TL_update.TL_updateNewMessage();
            nm.message = message;
            nm.pts = globalPts;
            nm.pts_count = 1;
            updates.updates.add(nm);
            TLRPC.Chat chat = cachedChat(id);
            if (chat != null) {
                updates.chats.add(chat);
            }
            return updates;
        } else if (peer instanceof TLRPC.TL_inputPeerChannel) {
            long tg = peer.channel_id;
            long serverId = channelServer(tg);
            String key = "wg-ch-" + Long.toHexString(randomId) + "-" + Long.toHexString(randomId * 31 + 7);
            JSONObject res = WaysGramApi.postIdempotent(account, "/api/channels/" + serverId + "/messages" + (quote != null ? "/quote/v1" : ""), body, key);
            mid.id = res.optInt("id");
            WaysGramPoller.markDelivered(account, -tg, mid.id);
            TLRPC.TL_message message = (TLRPC.TL_message) WaysGramMedia.attach(account, channelMessage(res, tg, selfId), res);
            updates.updates.add(mid);
            TL_update.TL_updateNewChannelMessage nm = new TL_update.TL_updateNewChannelMessage();
            nm.message = message;
            nm.pts = nextChannelPts(account, serverId);
            nm.pts_count = 1;
            updates.updates.add(nm);
            TLRPC.Chat chat = cachedChat(tg);
            if (chat != null) {
                updates.chats.add(chat);
            }
            return updates;
        }
        throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
    }

    // ---------------------------------------------------------------- group management

    private static Snapshot membersOf(JSONObject group) {
        Snapshot snapshot = new Snapshot();
        snapshot.addUsers(group.optJSONArray("members"));
        return snapshot;
    }

    private static TLRPC.TL_updates updatesWithChat(JSONObject group, long selfId) {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.chats.add(toChat(group, selfId));
        updates.users.addAll(membersOf(group).users);
        return updates;
    }

    public static TLRPC.TL_messages_invitedUsers inviteChannelUsers(int account, long channelId, ArrayList<TLRPC.InputUser> users, long selfId) throws WaysGramApi.ApiException {
        JSONArray targets = new JSONArray();
        for (TLRPC.InputUser user : users) {
            long id = user instanceof TLRPC.TL_inputUserSelf ? selfId : user.user_id;
            if (id <= 0) throw new WaysGramApi.ApiException(400, "USER_ID_INVALID");
            targets.put(id);
        }
        JSONObject body = new JSONObject();
        try { body.put("user_ids", targets); }
        catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "USERS_INVALID"); }
        String path = "/api/channels/" + channelServer(channelId);
        WaysGramApi.post(account, path + "/members", body);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.chats.add(toChannel(WaysGramApi.get(account, path), selfId, true));
        TLRPC.TL_messages_invitedUsers response = new TLRPC.TL_messages_invitedUsers();
        response.updates = updates;
        return response;
    }

    public static TLObject editGroupAdmin(int account, long id, long target, boolean admin, Integer rights) throws WaysGramApi.ApiException {
        String path = "/api/groups/" + id + "/admins";
        if (admin) {
            JSONObject body = new JSONObject();
            try {
                body.put("telegram_id", target);
                if (rights != null && rights > 0) {
                    body.put("rights", rights);
                }
            }
            catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "USER_ID_INVALID"); }
            WaysGramApi.post(account, path, body);
        } else {
            WaysGramApi.delete(account, path + "/" + target);
        }
        return new TLRPC.TL_boolTrue();
    }

    static TLObject channelParticipants(int account, TLRPC.TL_channels_getParticipants req, long selfId) throws WaysGramApi.ApiException {
        boolean banned = req.filter instanceof TLRPC.TL_channelParticipantsKicked
                || req.filter instanceof TLRPC.TL_channelParticipantsBanned;
        boolean admins = req.filter instanceof TLRPC.TL_channelParticipantsAdmins;
        if (!banned && !admins && !(req.filter instanceof TLRPC.TL_channelParticipantsRecent)
                && !(req.filter instanceof TLRPC.TL_channelParticipantsSearch)) {
            throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
        }
        long id = channelServer(req.channel.channel_id);
        JSONObject channel = WaysGramApi.get(account, "/api/channels/" + id);
        long owner = channel.optLong("owner_telegram_id");
        String query = req.filter.q == null ? "" : android.net.Uri.encode(req.filter.q);
        JSONObject data = WaysGramApi.get(account, "/api/channels/" + id + "/members?offset=" + req.offset
                + "&limit=" + req.limit + "&banned=" + banned + "&admins=" + admins + "&q=" + query);
        TLRPC.TL_channels_channelParticipants out = new TLRPC.TL_channels_channelParticipants();
        out.count = data.optInt("count");
        JSONArray rows = data.optJSONArray("members");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            TLRPC.ChannelParticipant participant = toChannelParticipant(row, banned, owner, selfId);
            out.participants.add(participant);
            out.users.add(WaysGramMapper.toUser(row));
        }
        return out;
    }

    private static TLRPC.ChannelParticipant toChannelParticipant(JSONObject row, boolean banned, long owner, long selfId) {
        TLRPC.ChannelParticipant participant;
        String role = row.optString("role");
        if (banned) {
            participant = new TLRPC.TL_channelParticipantBanned();
            participant.left = true;
            participant.kicked_by = owner;
            participant.banned_rights = new TLRPC.TL_chatBannedRights();
            participant.banned_rights.view_messages = true;
            participant.banned_rights.until_date = row.optInt("until_date");
        } else if ("owner".equals(role) || "admin".equals(role)) {
            participant = "owner".equals(role) ? new TLRPC.TL_channelParticipantCreator() : new TLRPC.TL_channelParticipantAdmin();
            participant.admin_rights = channelRights("owner".equals(role) ? 127 : row.optInt("admin_rights", 15));
            participant.can_edit = "admin".equals(role) && owner == selfId;
            participant.promoted_by = owner;
        } else {
            participant = new TLRPC.TL_channelParticipant();
        }
        participant.user_id = row.optLong("telegram_id");
        participant.peer = WaysGramMapper.peerUser(participant.user_id);
        participant.date = row.optInt("date");
        return participant;
    }

    static TLObject channelParticipant(int account, TLRPC.TL_channels_getParticipant req, long selfId) throws WaysGramApi.ApiException {
        long target;
        if (req.participant instanceof TLRPC.TL_inputPeerSelf) target = selfId;
        else if (req.participant instanceof TLRPC.TL_inputPeerUser) target = req.participant.user_id;
        else throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        String path = "/api/channels/" + channelServer(req.channel.channel_id);
        JSONObject row = WaysGramApi.get(account, path + "/members/" + target);
        JSONObject channel = WaysGramApi.get(account, path);
        TLRPC.TL_channels_channelParticipant out = new TLRPC.TL_channels_channelParticipant();
        out.participant = toChannelParticipant(row, false, channel.optLong("owner_telegram_id"), selfId);
        out.users.add(WaysGramMapper.toUser(row));
        return out;
    }

    static TLObject banChannelParticipant(int account, TLRPC.TL_channels_editBanned req, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_chatBannedRights rights = TLRPC.TL_chatBannedRights.clone(req.banned_rights);
        if (!(req.participant instanceof TLRPC.TL_inputPeerUser) || rights == null
                || (!rights.view_messages && rights.flags != 0)) {
            throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
        }
        String path = "/api/channels/" + channelServer(req.channel.channel_id);
        JSONObject body = new JSONObject();
        try {
            body.put("banned", rights.view_messages);
            body.put("until_date", rights.view_messages ? rights.until_date : 0);
        }
        catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "USER_ID_INVALID"); }
        WaysGramApi.put(account, path + "/members/" + req.participant.user_id + "/ban", body);
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        result.chats.add(toChannel(WaysGramApi.get(account, path), selfId, true));
        return result;
    }

    /** setChatAvailableReactions: включение, лимит и список эмодзи для канала. */
    public static TLRPC.TL_updates setAvailableReactions(int account, TLRPC.TL_messages_setChatAvailableReactions req, long selfId) throws WaysGramApi.ApiException {
        if (!(req.peer instanceof TLRPC.TL_inputPeerChannel)) {
            throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
        }
        long channelId = channelServer(((TLRPC.TL_inputPeerChannel) req.peer).channel_id);
        JSONObject body = new JSONObject();
        try {
            if (req.available_reactions instanceof TLRPC.TL_chatReactionsNone) {
                body.put("enabled", false);
            } else {
                body.put("enabled", true);
                if (req.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
                    JSONArray reactions = new JSONArray();
                    for (TLRPC.Reaction reaction : ((TLRPC.TL_chatReactionsSome) req.available_reactions).reactions) {
                        if (reaction instanceof TLRPC.TL_reactionEmoji
                                && !android.text.TextUtils.isEmpty(((TLRPC.TL_reactionEmoji) reaction).emoticon)) {
                            reactions.put(((TLRPC.TL_reactionEmoji) reaction).emoticon);
                        }
                    }
                    body.put("reactions", reactions);
                } else {
                    body.put("reactions", new JSONArray());
                }
            }
            if (TLObject.hasFlag(req.flags, TLObject.FLAG_0)) {
                body.put("limit", Math.max(1, Math.min(11, req.reactions_limit)));
            }
            if (TLObject.hasFlag(req.flags, TLObject.FLAG_1)) {
                WaysGramApi.put(account, "/api/channels/" + channelId + "/paid-reactions",
                        new JSONObject().put("enabled", req.paid_enabled));
            }
        } catch (org.json.JSONException e) {
            throw new WaysGramApi.ApiException(400, "REACTIONS_INVALID");
        }
        WaysGramApi.put(account, "/api/channels/" + channelId + "/reactions-settings", body);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.chats.add(toChannel(WaysGramApi.get(account, "/api/channels/" + channelId), selfId,
                joinedChannels(account).contains(channelId)));
        return updates;
    }

    public static TLRPC.TL_messages_chatFull fullChannel(int account, long channelId, long selfId) throws WaysGramApi.ApiException {
        JSONObject channel = WaysGramApi.get(account, "/api/channels/" + channelServer(channelId));
        TLRPC.TL_channelFull full = new TLRPC.TL_channelFull();
        full.id = channelId;
        full.about = channel.optString("description", "");
        full.flags |= 1;
        full.can_view_participants = channel.optLong("owner_telegram_id") == selfId || channel.optBoolean("is_admin");
        if (full.can_view_participants) full.flags |= TLObject.FLAG_3;
        full.stories_pinned_available = channel.optBoolean("stories_pinned_available");
        if (full.stories_pinned_available) full.flags2 |= TLObject.FLAG_5;
        if (channel.optBoolean("stories_active")) {
            try {
                full.stories = WaysGramStories.active(account, channelId, selfId);
                full.flags2 |= TLObject.FLAG_4;
            } catch (WaysGramApi.ApiException e) {
                if (e.status == 401) throw e;
                org.telegram.messenger.FileLog.e(e);
            }
        }
        JSONArray availableReactions = channel.optJSONArray("available_reactions");
        boolean reactionsEnabled = channel.optBoolean("reactions_enabled", true);
        if (availableReactions == null || availableReactions.length() == 0) {
            full.available_reactions = reactionsEnabled ? new TLRPC.TL_chatReactionsAll() : new TLRPC.TL_chatReactionsNone();
        } else {
            TLRPC.TL_chatReactionsSome some = new TLRPC.TL_chatReactionsSome();
            for (int i = 0; i < availableReactions.length(); i++) {
                String emoji = availableReactions.optString(i, "").trim();
                if (emoji.isEmpty()) continue;
                TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
                reaction.emoticon = emoji;
                some.reactions.add(reaction);
            }
            full.available_reactions = some.reactions.isEmpty() ? new TLRPC.TL_chatReactionsAll() : some;
        }
        full.flags |= TLObject.FLAG_30;
        full.reactions_limit = channel.optInt("reactions_limit", 11);
        full.flags2 |= TLObject.FLAG_13;
        full.paid_reactions_available = channel.optBoolean("paid_reactions_enabled");
        if (full.paid_reactions_available) full.flags2 |= TLObject.FLAG_16;
        full.participants_count = channel.optInt("member_count");
        boolean channelOwner = channel.optLong("owner_telegram_id") == selfId;
        int adminsCount = Math.max(0, channel.optInt("admin_count", 0));
        if (channelOwner) {
            adminsCount = Math.max(1, adminsCount + 1);
        }
        if (adminsCount > 0) {
            full.admins_count = adminsCount;
            full.flags |= TLObject.FLAG_1;
        }
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        if (channel.optInt("is_public", 1) == 0 && channel.optLong("owner_telegram_id") == selfId) {
            try {
                full.exported_invite = WaysGramInvites.channelInvite(account, channelId, selfId, false);
                full.flags |= TLObject.FLAG_23;
            } catch (WaysGramApi.ApiException e) {
                if (e.status != 404) throw e;
            }
        }
        full.requests_pending = channel.optInt("requests_pending");
        JSONArray requesters = channel.optJSONArray("recent_requesters");
        if (full.requests_pending > 0 && requesters != null) {
            full.flags |= TLObject.FLAG_28;
            for (int i = 0; i < requesters.length(); i++) full.recent_requesters.add(requesters.optLong(i));
        }
        full.chat_photo = new TLRPC.TL_photoEmpty();
        TLRPC.TL_messages_chatFull result = new TLRPC.TL_messages_chatFull();
        result.full_chat = full;
        result.chats.add(toChannel(channel, selfId, joinedChannels(account).contains(channelServer(channelId))));
        return result;
    }

    public static TLRPC.TL_messages_chatFull fullChat(int account, long chatId, long selfId) throws WaysGramApi.ApiException {
        JSONObject group = WaysGramApi.get(account, "/api/groups/" + chatId);
        Snapshot users = membersOf(group);

        TLRPC.TL_chatParticipants participants = new TLRPC.TL_chatParticipants();
        participants.chat_id = chatId;
        participants.version = 1;
        long ownerId = group.optLong("owner_telegram_id");
        int created = group.optInt("created_at");
        JSONArray members = group.optJSONArray("members");
        if (members != null) {
            for (int i = 0; i < members.length(); i++) {
                JSONObject m = members.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                long uid = m.optLong("telegram_id");
                String role = m.optString("role", "member");
                TLRPC.ChatParticipant participant;
                if ("owner".equals(role)) {
                    participant = new TLRPC.TL_chatParticipantCreator();
                } else if ("admin".equals(role)) {
                    participant = new TLRPC.TL_chatParticipantAdmin();
                    int mask = m.optInt("admin_rights", 63);
                    TLRPC.TL_chatAdminRights rights = new TLRPC.TL_chatAdminRights();
                    rights.change_info = (mask & 1) != 0;
                    rights.invite_users = (mask & 2) != 0;
                    rights.ban_users = (mask & 4) != 0;
                    rights.pin_messages = (mask & 8) != 0;
                    rights.manage_call = (mask & 32) != 0;
                    rights.delete_messages = (mask & 16) != 0;
                    participant.waysGramAdminRights = rights;
                } else {
                    participant = new TLRPC.TL_chatParticipant();
                }
                participant.user_id = uid;
                participant.inviter_id = ownerId;
                participant.date = m.optInt("joined_at", created);
                participants.participants.add(participant);
            }
        }

        TLRPC.TL_chatFull full = new TLRPC.TL_chatFull();
        full.id = chatId;
        full.about = group.optString("description", "");
        full.requests_pending = group.optInt("requests_pending");
        JSONArray requesters = group.optJSONArray("recent_requesters");
        if (full.requests_pending > 0 && requesters != null) {
            full.flags |= TLObject.FLAG_17;
            for (int i = 0; i < requesters.length(); i++) full.recent_requesters.add(requesters.optLong(i));
        }
        if ("owner".equals(group.optString("my_role")) || (group.optInt("admin_rights") & 2) != 0) {
            try {
                full.exported_invite = WaysGramInvites.groupPermanentInvite(account, chatId, selfId, false);
                full.flags |= TLObject.FLAG_13;
            } catch (WaysGramApi.ApiException e) {
                // Older server or permissions changed; keep the group profile usable.
                if (e.status != 403 && e.status != 404) org.telegram.messenger.FileLog.e(e);
            }
        }
        full.participants = participants;
        full.notify_settings = new TLRPC.TL_peerNotifySettings();

        TLRPC.TL_messages_chatFull out = new TLRPC.TL_messages_chatFull();
        out.full_chat = full;
        out.chats.add(toChat(group, selfId));
        out.users.addAll(users.users);
        return out;
    }

    private static void addMember(int account, long chatId, long userId) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("telegram_id", userId);
        } catch (Exception ignore) {
        }
        WaysGramApi.post(account, "/api/groups/" + chatId + "/members", body);
    }

    public static TLRPC.TL_updates editGroup(int account, long chatId, String key, String value, long selfId) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put(key, value == null ? "" : value);
        } catch (org.json.JSONException e) {
            throw new WaysGramApi.ApiException(400, "CHAT_SETTINGS_INVALID");
        }
        String path = "/api/groups/" + chatId;
        WaysGramApi.put(account, path, body);
        return updatesWithChat(WaysGramApi.get(account, path), selfId);
    }

    public static TLRPC.TL_messages_invitedUsers createChat(int account, TLRPC.TL_messages_createChat req, long selfId) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        JSONArray members = new JSONArray();
        for (TLRPC.InputUser user : req.users) {
            if (user instanceof TLRPC.TL_inputUser && user.user_id > 0 && user.user_id != selfId) members.put(user.user_id);
        }
        try {
            body.put("title", req.title == null ? "" : req.title.trim());
            body.put("member_ids", members);
        } catch (org.json.JSONException e) {
            throw new WaysGramApi.ApiException(400, "GROUP_MEMBERS_INVALID");
        }
        JSONObject created = WaysGramApi.post(account, "/api/groups", body);
        if (created.optLong("id") <= 0) throw new WaysGramApi.ApiException(502, "BAD_RESPONSE");
        TLRPC.TL_messages_invitedUsers out = new TLRPC.TL_messages_invitedUsers();
        out.updates = updatesWithChat(created, selfId);
        JSONArray skipped = created.optJSONArray("skipped_member_ids");
        for (int i = 0; skipped != null && i < skipped.length(); i++) {
            long userId = skipped.optLong(i);
            if (userId > 0) {
                TLRPC.TL_missingInvitee missing = new TLRPC.TL_missingInvitee();
                missing.user_id = userId;
                out.missing_invitees.add(missing);
            }
        }
        return out;
    }

    public static TLRPC.TL_messages_invitedUsers addUser(int account, long chatId, long userId, long selfId) throws WaysGramApi.ApiException {
        addMember(account, chatId, userId);
        TLRPC.TL_messages_invitedUsers out = new TLRPC.TL_messages_invitedUsers();
        out.updates = updatesWithChat(WaysGramApi.get(account, "/api/groups/" + chatId), selfId);
        return out;
    }

    public static TLRPC.TL_updates removeUser(int account, long chatId, long userId, long selfId) throws WaysGramApi.ApiException {
        WaysGramApi.delete(account, "/api/groups/" + chatId + "/members/" + userId);
        if (userId == selfId) {
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = (int) (System.currentTimeMillis() / 1000);
            return updates;
        }
        return updatesWithChat(WaysGramApi.get(account, "/api/groups/" + chatId), selfId);
    }

    // ---------------------------------------------------------------- search and join

    public static TLRPC.TL_channel findChannelByUsername(int account, String username, long selfId) throws WaysGramApi.ApiException {
        JSONArray channels = WaysGramApi.get(account, "/api/channels").optJSONArray("channels");
        HashSet<Long> joined = joinedChannels(account);
        if (channels != null) {
            for (int i = 0; i < channels.length(); i++) {
                JSONObject c = channels.optJSONObject(i);
                if (c != null && username.equalsIgnoreCase(c.optString("username"))) {
                    return toChannel(c, selfId, listedChannelMember(c, joined));
                }
            }
        }
        return null;
    }

    public static void addChannelMatches(int account, String q, long selfId, TLRPC.TL_contacts_found found) throws WaysGramApi.ApiException {
        String needle = q.replace("@", "").trim().toLowerCase();
        if (needle.isEmpty()) {
            return;
        }
        JSONArray channels = WaysGramApi.get(account, "/api/channels").optJSONArray("channels");
        HashSet<Long> joined = joinedChannels(account);
        if (channels == null) {
            return;
        }
        for (int i = 0; i < channels.length(); i++) {
            JSONObject c = channels.optJSONObject(i);
            if (c == null) {
                continue;
            }
            String username = c.optString("username").toLowerCase();
            String title = c.optString("title").toLowerCase();
            if (username.contains(needle) || title.contains(needle)) {
                TLRPC.TL_channel channel = toChannel(c, selfId, listedChannelMember(c, joined));
                found.chats.add(channel);
                TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
                peer.channel_id = channel.id;
                found.results.add(peer);
            }
        }
    }

    static void cacheListedChannelMembers(int account, JSONArray channels) {
        HashSet<Long> members = new HashSet<>();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.optJSONObject(i);
            if (channel == null || !channel.has("is_member")) return;
            if (channel.optInt("is_member") == 1) members.add(channel.optLong("id"));
        }
        prefs().edit().putString("channels_" + account, TextUtils.join(",", members)).apply();
    }

    static boolean listedChannelMember(JSONObject channel, HashSet<Long> cached) {
        return channel.has("is_member") ? channel.optInt("is_member") == 1 : cached.contains(channel.optLong("id"));
    }

    public static TLRPC.TL_updates joinOrLeave(int account, long channelTgId, boolean join, long selfId) throws WaysGramApi.ApiException {
        long serverId = channelServer(channelTgId);
        JSONObject json = WaysGramApi.get(account, "/api/channels/" + serverId);
        JSONObject body = new JSONObject();
        try { body.put("joined", join); }
        catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "CHAT_SETTINGS_INVALID"); }
        WaysGramApi.put(account, "/api/channels/" + serverId + "/membership", body);
        setJoined(account, serverId, join);
        if (join) {
            WaysGramMessages.readChannelFully(account, serverId); // old posts are not "unread" for a new subscriber
        }
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.chats.add(toChannel(json, selfId, join));
        return updates;
    }
}
