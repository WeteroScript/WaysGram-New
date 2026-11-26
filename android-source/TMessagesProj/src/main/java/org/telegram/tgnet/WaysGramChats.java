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

    /**
     * Groups are shown to the client as megagroups. Their ids stay in the group range (below the channel
     * offset), so the app distinguishes a group from a channel by the id alone and every existing group
     * dialog keeps its id.
     */
    public static boolean isGroupChannel(long tgId) {
        return tgId > 0 && tgId < CHANNEL_OFFSET;
    }

    static boolean isGroup(TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerChat) {
            return true;
        }
        return peer instanceof TLRPC.TL_inputPeerChannel
                && isGroupChannel(((TLRPC.TL_inputPeerChannel) peer).channel_id);
    }

    static long groupPeerId(TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerChat) {
            return ((TLRPC.TL_inputPeerChat) peer).chat_id;
        }
        if (peer instanceof TLRPC.TL_inputPeerChannel) {
            return ((TLRPC.TL_inputPeerChannel) peer).channel_id;
        }
        return 0;
    }

    static TLRPC.Peer groupPeer(long tgId) {
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = tgId;
        return peer;
    }

    private static final HashMap<String, TLRPC.Chat> chatCache = new HashMap<>();

    private static String cacheKey(int account, long tgId) {
        return account + ":" + tgId;
    }

    /** Chat rights belong to one WaysGram account; a static id-only cache leaked the owner's
     * creator flag into other sessions on the same device. */
    public static synchronized TLRPC.Chat cachedChat(int account, long tgId) {
        return chatCache.get(cacheKey(account, tgId));
    }

    /**
     * Чат изменился на сервере (права, аватар, название, состав) — перечитываем его для этого аккаунта.
     *
     * <p>Раньше изменения настроек группы или канала не доставлялись вовсе: другой участник видел
     * новые права только после перезапуска приложения, а иногда не видел совсем. Сервер теперь
     * присылает событие {@code chat_changed}, здесь сбрасывается кэш именно этого аккаунта и
     * заново запрашивается профиль чата вместе с объектом {@code TLRPC.Chat}.</p>
     */
    public static void chatChanged(int account, String scope, long id) {
        if (id <= 0) {
            return;
        }
        final long tgId = "channel".equals(scope) ? channelTg(id) : id;
        synchronized (WaysGramChats.class) {
            chatCache.remove(cacheKey(account, tgId));
        }
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            org.telegram.messenger.MessagesController controller =
                    org.telegram.messenger.MessagesController.getInstance(account);
            controller.loadFullChat(tgId, 0, true);
            org.telegram.messenger.NotificationCenter.getInstance(account)
                    .postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload);
        });
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

    public static TLRPC.TL_chat toChat(int account, JSONObject g, long selfId) {
        TLRPC.TL_chat chat = new TLRPC.TL_chat();
        chat.id = g.optLong("id");
        chat.title = g.optString("title", "");
        chat.photo = WaysGramMapper.chatPhoto(g.optString("avatar_media_id", ""));
        chat.default_banned_rights = bannedRightsFor(g.optInt("default_send_restrictions"));
        chat.participants_count = Math.max(1, g.optInt("member_count", 1));
        chat.date = g.optInt("joined_at", g.optInt("created_at", (int) (System.currentTimeMillis() / 1000)));
        chat.version = 1;
        chat.creator = g.optLong("owner_telegram_id") == selfId;
        String role = g.optString("my_role", g.optString("role", ""));
        int groupMask = g.optInt("admin_rights", 0);
        if (chat.creator || ("admin".equals(role) && groupMask > 0)) {
            chat.admin_rights = groupAdminRights(groupMask);
            chat.flags |= TLObject.FLAG_14;
        }
        synchronized (WaysGramChats.class) {
            chatCache.put(cacheKey(account, chat.id), chat);
        }
        return chat;
    }

    /**
     * Права сравниваются по {@code flags} ({@code TL_chatAdminRights.equals} / {@code TL_chatBannedRights.equals}),
     * а {@code flags} в TLRPC вычисляется только в {@code serializeToStream}. Объект, собранный вручную,
     * до первой сериализации выглядит «без прав»: {@code ChatEditActivity} на {@code channelRightsUpdated}
     * сравнивал такой чат со своим снимком и закрывал редактор — с устройства это выглядело как
     * «после смены аватара группы редактирование сразу выбрасывает в чат». Клон прогоняет объект через
     * провод, поэтому в памяти он совпадает с тем, что приходит с сервера.
     */
    private static TLRPC.TL_chatAdminRights normalized(TLRPC.TL_chatAdminRights rights) {
        return TLRPC.TL_chatAdminRights.clone(rights);
    }

    private static TLRPC.TL_chatBannedRights normalized(TLRPC.TL_chatBannedRights rights) {
        return TLRPC.TL_chatBannedRights.clone(rights);
    }

    /**
     * Права администратора группы: у групп своя маска (1 — менять информацию, 2 — добавлять участников,
     * 4 — банить, 8 — закреплять, 16 — удалять сообщения, 32 — медленный режим). Раньше для групп
     * применялась канальная маска, поэтому права админа показывались не те.
     */
    private static TLRPC.TL_chatAdminRights groupAdminRights(int mask) {
        TLRPC.TL_chatAdminRights rights = new TLRPC.TL_chatAdminRights();
        rights.change_info = (mask & 1) != 0;
        rights.invite_users = (mask & 2) != 0;
        rights.ban_users = (mask & 4) != 0;
        rights.pin_messages = (mask & 8) != 0;
        rights.delete_messages = (mask & 16) != 0;
        rights.manage_call = (mask & 32) != 0;
        return normalized(rights);
    }

    /**
     * Обои чата из «Внешний вид и эмодзи» в том виде, в каком их держит клиент.
     *
     * <p>Сервер хранит настройки для аккаунта и отдаёт их в карточке чата; без этого поля
     * {@code ChatThemeController.getDialogWallpaper} всегда возвращал {@code null} и фон
     * после переоткрытия настроек сбрасывался на стандартный.</p>
     */
    static TLRPC.WallPaper wallpaper(int account, JSONObject data) {
        if (data == null) {
            return null;
        }
        TLRPC.TL_wallPaper photo = WaysGramWallpapers.fromJson(account, data);
        if (photo != null) {
            return photo;
        }
        TLRPC.TL_wallPaperNoFile paper = new TLRPC.TL_wallPaperNoFile();
        paper.id = data.optLong("id", 0);
        paper.settings = WaysGramWallpapers.settings(data);
        paper.flags |= TLObject.FLAG_2;
        return paper;
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
        return normalized(rights);
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
        updates.chats.add(toChannel(account, WaysGramApi.get(account, path), selfId, true));
        return updates;
    }

    public static TLRPC.TL_channel toChannel(int account, JSONObject c, long selfId, boolean member) {
        TLRPC.TL_channel channel = new TLRPC.TL_channel();
        channel.id = channelTg(c.optLong("id"));
        channel.access_hash = channel.id;
        channel.flags |= TLObject.FLAG_13;
        channel.title = c.optString("title", "");
        String username = WaysGramMapper.text(c, "username");
        if (!username.isEmpty()) {
            channel.username = username;
            channel.flags |= TLObject.FLAG_6;
        }
        channel.photo = WaysGramMapper.chatPhoto(c.optString("avatar_media_id", ""));
        applyChannelFlags(channel, c);
        channel.date = (int) (System.currentTimeMillis() / 1000);
        channel.broadcast = true;
        channel.creator = c.optLong("owner_telegram_id") == selfId;
        if (!channel.creator && (c.optBoolean("is_admin", false) || c.optInt("is_admin", 0) == 1)) {
            // Права берём ровно те, что прислал сервер: раньше при отсутствии поля клиент выдавал
            // маску 15 (публикация, редактирование, удаление, закрепление) и админ без права
            // публикации видел поле ввода канала, хотя сервер отвечал 403.
            int mask = c.optInt("admin_rights", 0);
            if (mask > 0) {
                channel.admin_rights = channelRights(mask);
                channel.flags |= TLObject.FLAG_14;
            }
        }
        channel.left = !member && !channel.creator;
        synchronized (WaysGramChats.class) {
            chatCache.put(cacheKey(account, channel.id), channel);
        }
        return channel;
    }

    public static TLRPC.TL_message groupMessage(JSONObject m, long chatId, long selfId, int account) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        long sender = m.optLong("sender_id");
        message.id = clientGroupId(m.optInt("id"));
        TLRPC.Peer peer = groupPeer(chatId);
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
        // Пост в канале со связанной группой обсуждений получает штатную кнопку комментариев.
        long discussionGroup = m.optLong("discussion_group_id", 0);
        if (discussionGroup > 0) {
            TLRPC.TL_messageReplies replies = new TLRPC.TL_messageReplies();
            replies.comments = true;
            replies.flags |= TLObject.FLAG_0;
            replies.replies = Math.max(0, m.optInt("comments_count", 0));
            replies.channel_id = discussionGroup;
            int lastComment = m.optInt("last_comment_id", 0);
            if (lastComment > 0) {
                replies.max_id = lastComment;
                replies.read_max_id = lastComment;
                replies.flags |= TLObject.FLAG_2 | TLObject.FLAG_3;
            }
            message.replies = replies;
            message.flags |= TLObject.FLAG_23;
        }
        return message;
    }

    /** A group rendered as a megagroup: supergroup screens (slow mode, hidden members, history) become available. */
    public static TLRPC.TL_channel toMegagroup(int account, JSONObject g, long selfId) {
        TLRPC.TL_channel chat = new TLRPC.TL_channel();
        chat.id = g.optLong("id");
        chat.access_hash = chat.id;
        chat.flags |= TLObject.FLAG_13;
        chat.title = g.optString("title", "");
        chat.photo = WaysGramMapper.chatPhoto(g.optString("avatar_media_id", ""));
        chat.default_banned_rights = bannedRightsFor(g.optInt("default_send_restrictions"));
        chat.participants_count = Math.max(1, g.optInt("member_count", 1));
        chat.date = g.optInt("joined_at", g.optInt("created_at", (int) (System.currentTimeMillis() / 1000)));
        chat.megagroup = true;
        chat.flags |= TLObject.FLAG_8;
        chat.creator = g.optLong("owner_telegram_id") == selfId;
        String username = WaysGramMapper.text(g, "username");
        if (!username.isEmpty()) {
            chat.username = username;
            chat.flags |= TLObject.FLAG_6;
        }
        String role = g.optString("my_role", g.optString("role", ""));
        int groupMask = g.optInt("admin_rights", 0);
        if (chat.creator || ("admin".equals(role) && groupMask > 0)) {
            chat.admin_rights = groupAdminRights(groupMask);
            chat.flags |= TLObject.FLAG_14;
        }
        applyChannelFlags(chat, g);
        synchronized (WaysGramChats.class) {
            chatCache.put(cacheKey(account, chat.id), chat);
        }
        return chat;
    }

    // ---------------------------------------------------------------- dialogs

    /**
     * Сообщения треда комментариев (корень поста и ответы) в том виде, в каком их отдаёт сервер.
     * Список id берётся из {@code /threads/{root}/replies}, полные сообщения — штатным
     * {@code /messages?message_ids=}, поэтому конвертация в TL остаётся в одном месте.
     */
    static org.json.JSONArray threadMessages(int account, long group, int root, int offsetId, int addOffset,
                                             int limit) throws WaysGramApi.ApiException {
        StringBuilder path = new StringBuilder("/api/groups/").append(group)
                .append("/threads/").append(root).append("/replies?limit=").append(Math.max(1, Math.min(limit, 100)));
        if (offsetId > 0) {
            path.append("&offset_id=").append(offsetId);
        }
        if (addOffset > 0) {
            path.append("&add_offset=").append(addOffset);
        }
        JSONObject page = WaysGramApi.get(account, path.toString());
        JSONArray ids = page.optJSONArray("ids");
        if (ids == null || ids.length() == 0) {
            return new JSONArray();
        }
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < ids.length(); i++) {
            if (csv.length() > 0) csv.append(',');
            csv.append(ids.optInt(i));
        }
        return WaysGramApi.get(account, "/api/groups/" + group + "/messages?message_ids=" + csv)
                .optJSONArray("messages");
    }

    /** Профили участников группы для ответа треда. */
    static ArrayList<TLRPC.User> threadUsers(JSONObject group) {
        Snapshot users = new Snapshot();
        if (group != null) {
            users.addUsers(group.optJSONArray("members"));
        }
        return users.users;
    }

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
                out.chats.add(toMegagroup(account, g, selfId));
                try {
                    out.addUsers(WaysGramApi.get(account, "/api/groups/" + id).optJSONArray("members"));
                } catch (WaysGramApi.ApiException ignore) {
                }
                TLRPC.Peer peer = groupPeer(id);
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
                out.chats.add(toChannel(account, c, selfId, true));
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
        boolean groupPeer = isGroup(req.peer);
        int after = groupPeer ? serverGroupId(req.min_id) : req.min_id;
        int before = groupPeer ? serverGroupId(req.offset_id) : req.offset_id;
        String paging = "?limit=" + Math.max(1, Math.min(req.limit > 0 ? req.limit : 100, 100));
        if (after > 0) paging += "&after_id=" + after;
        else if (before > 0) paging += "&before_id=" + before;

        if (isGroup(req.peer)) {
            long id = groupPeerId(req.peer);
            JSONObject group = WaysGramApi.get(account, "/api/groups/" + id);
            out.chats.add(toMegagroup(account, group, selfId));
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
            out.chats.add(toChannel(account, channel, selfId, joinedChannels(account).contains(serverId)));
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

    /**
     * Писать в канал может только владелец и администратор с правом публикации.
     *
     * <p>Сервер отвечает на такую попытку 403, но проверять нужно и здесь: отправка рисует
     * оптимистичный пузырь, и до ответа сервера выглядит так, будто «писать может кто угодно».
     * Группы проверяются своими правами на сервере — здесь их не трогаем.</p>
     */
    public static void requireChannelPostRight(int account, long channelTgId) throws WaysGramApi.ApiException {
        if (isGroupChannel(channelTgId)) {
            return;
        }
        TLRPC.Chat chat = cachedChat(account, channelTgId);
        if (chat == null) {
            // Кэш этого аккаунта ещё пуст (канал открыт в другой сессии). Раньше
            // проверка в этом случае просто пропускала запрос: аккаунт без прав
            // отправлял пост и получал 403 только от сервера. Права спрашиваем сразу.
            try {
                chat = toChannel(account,
                        WaysGramApi.get(account, "/api/channels/" + channelServer(channelTgId)),
                        org.telegram.messenger.UserConfig.getInstance(account).getClientUserId(), true);
            } catch (WaysGramApi.ApiException e) {
                throw new WaysGramApi.ApiException(403, "CHAT_WRITE_FORBIDDEN");
            }
        }
        if (!(chat instanceof TLRPC.TL_channel) || chat.megagroup || !chat.broadcast) {
            return;
        }
        if (chat.creator || chat.admin_rights != null && chat.admin_rights.post_messages) {
            return;
        }
        throw new WaysGramApi.ApiException(403, "CHAT_WRITE_FORBIDDEN");
    }

    public static TLObject send(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, int replyTo, long selfId, int globalPts, boolean silent, JSONObject location, JSONObject contact, JSONArray entities, JSONObject quote) throws WaysGramApi.ApiException {
        return send(account, peer, text, randomId, mediaId, replyTo, selfId, globalPts, silent, location, contact, entities, quote, null, -1L);
    }

    public static TLObject send(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, int replyTo, long selfId, int globalPts, boolean silent, JSONObject location, JSONObject contact, JSONArray entities, JSONObject quote, JSONObject card) throws WaysGramApi.ApiException {
        return send(account, peer, text, randomId, mediaId, replyTo, selfId, globalPts, silent, location, contact, entities, quote, card, -1L);
    }

    /**
     * @param identity -1 — обычная отправка от своего аккаунта; 0 — от имени самой группы
     *                 (анонимный админ); &gt; 0 — серверный id публичного канала пользователя.
     */
    public static TLObject send(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, int replyTo, long selfId, int globalPts, boolean silent, JSONObject location, JSONObject contact, JSONArray entities, JSONObject quote, JSONObject card, long identity) throws WaysGramApi.ApiException {
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
            int serverReply = isGroup(peer) ? serverGroupId(replyTo) : replyTo;
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

        if (isGroup(peer)) {
            long id = groupPeerId(peer);
            String key = "wg-group-" + java.util.UUID.nameUUIDFromBytes(
                    Long.toString(randomId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JSONObject res;
            if (identity >= 0) {
                // Альтернативный отправитель: сама группа (0) или публичный канал пользователя.
                try {
                    body.put("send_as_id", identity);
                } catch (Exception e) {
                    throw new WaysGramApi.ApiException(400, "MESSAGE_INVALID");
                }
                res = WaysGramApi.postIdempotent(account, "/api/groups/" + id + "/identity-messages", body, key);
            } else {
                res = WaysGramApi.postIdempotent(account, "/api/groups/" + id + "/messages" + (quote != null ? "/quote/v1" : ""), body, key);
            }
            mid.id = clientGroupId(res.optInt("id"));
            WaysGramPoller.markDelivered(account, -id, res.optInt("id"));
            TLRPC.TL_message message = (TLRPC.TL_message) WaysGramMedia.attach(account, groupMessage(res, id, selfId, account), res);
            updates.updates.add(mid);
            TL_update.TL_updateNewMessage nm = new TL_update.TL_updateNewMessage();
            nm.message = message;
            nm.pts = globalPts;
            nm.pts_count = 1;
            updates.updates.add(nm);
            TLRPC.Chat chat = cachedChat(account, id);
            if (chat != null) {
                updates.chats.add(chat);
            }
            return updates;
        } else if (peer instanceof TLRPC.TL_inputPeerChannel) {
            long tg = peer.channel_id;
            requireChannelPostRight(account, tg);
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
            TLRPC.Chat chat = cachedChat(account, tg);
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

    private static TLRPC.TL_updates updatesWithChat(int account, JSONObject group, long selfId) {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.chats.add(toMegagroup(account, group, selfId));
        updates.users.addAll(membersOf(group).users);
        return updates;
    }

    /**
     * Список отправителей для группы: сама группа (если владелец или админ с правом
     * анонимности) и публичные каналы пользователя. Отвечает на channels.getSendAs.
     */
    public static TLRPC.TL_channels_sendAsPeers sendAsPeers(int account, long groupTgId, long selfId) throws WaysGramApi.ApiException {
        JSONObject data = WaysGramApi.get(account, "/api/groups/" + groupTgId + "/send-identities");
        TLRPC.TL_channels_sendAsPeers result = new TLRPC.TL_channels_sendAsPeers();
        if (data.optBoolean("anonymous_allowed")) {
            TLRPC.Chat group = cachedChat(account, groupTgId);
            if (group == null) {
                group = toMegagroup(account, WaysGramApi.get(account, "/api/groups/" + groupTgId), selfId);
            }
            TLRPC.TL_sendAsPeer peer = new TLRPC.TL_sendAsPeer();
            TLRPC.TL_peerChat chatPeer = new TLRPC.TL_peerChat();
            chatPeer.chat_id = groupTgId;
            peer.peer = chatPeer;
            result.peers.add(peer);
            result.chats.add(group);
        }
        JSONArray channels = data.optJSONArray("channels");
        for (int i = 0; channels != null && i < channels.length(); i++) {
            JSONObject item = channels.optJSONObject(i);
            if (item == null) continue;
            TLRPC.TL_channel channel = toChannel(account, item, selfId, true);
            TLRPC.TL_sendAsPeer peer = new TLRPC.TL_sendAsPeer();
            TLRPC.TL_peerChannel channelPeer = new TLRPC.TL_peerChannel();
            channelPeer.channel_id = channel.id;
            peer.peer = channelPeer;
            result.peers.add(peer);
            result.chats.add(channel);
        }
        return result;
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
        updates.chats.add(toChannel(account, WaysGramApi.get(account, path), selfId, true));
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

    // ---------------------------------------------------------------- channel flags and settings

    static boolean flag(JSONObject json, String key) {
        return json != null && json.optBoolean(key, json.optInt(key) != 0);
    }

    private static void applyChannelFlags(TLRPC.TL_channel channel, JSONObject c) {
        if (flag(c, "signatures_enabled")) {
            channel.signatures = true;
            channel.flags |= TLObject.FLAG_11;
        }
        if (flag(c, "signatures_profiles")) {
            channel.signature_profiles = true;
            channel.flags2 |= TLObject.FLAG_12;
        }
        if (c.optInt("slow_mode_seconds") > 0) {
            channel.slowmode_enabled = true;
            channel.flags |= TLObject.FLAG_22;
        }
        if (flag(c, "no_forwards")) {
            channel.noforwards = true;
            channel.flags |= TLObject.FLAG_27;
        }
        long paidMessagesStars = c.optLong("send_paid_messages_stars", c.optLong("paid_messages_stars", 0));
        if (paidMessagesStars > 0) {
            channel.send_paid_messages_stars = paidMessagesStars;
            channel.flags2 |= TLObject.FLAG_14;
        }
        if (flag(c, "join_to_send")) {
            channel.join_to_send = true;
            channel.flags |= TLObject.FLAG_28;
        }
        if (flag(c, "join_request")) {
            channel.join_request = true;
            channel.flags |= TLObject.FLAG_29;
        }
        // The backend calls the linked discussion group discussion_group_id; older snapshots
        // used linked_monoforum_id. Both mean the same "Comments" button in a channel.
        long linkedMonoforum = c.optLong("linked_monoforum_id", c.optLong("discussion_group_id", 0));
        if (linkedMonoforum != 0) {
            channel.linked_monoforum_id = linkedMonoforum;
            channel.flags2 |= TLObject.FLAG_18;
            // A linked group is exactly when Telegram shows "Suggest a post"/Comments.
            channel.broadcast_messages_allowed = true;
            channel.flags2 |= TLObject.FLAG_16;
        }
        if (flag(c, "broadcast_messages_allowed")) {
            channel.broadcast_messages_allowed = true;
            channel.flags2 |= TLObject.FLAG_16;
        }
        int colorId = c.optInt("color_id");
        long colorEmoji = c.optLong("background_emoji_id");
        if (colorId > 0 || colorEmoji != 0) {
            channel.color = peerColor(colorId, colorEmoji);
            channel.flags2 |= TLObject.FLAG_7;
        }
        int profileColor = c.optInt("profile_color_id");
        long profileEmoji = c.optLong("profile_background_emoji_id");
        if (profileColor > 0 || profileEmoji != 0) {
            channel.profile_color = peerColor(profileColor, profileEmoji);
            channel.flags2 |= TLObject.FLAG_8;
        }
    }

    /** Геолокация чата: TL_channelLocation из полей location_* ответа сервера. */
    private static TLRPC.ChannelLocation chatLocation(JSONObject data) {
        JSONObject location = data == null ? null : data.optJSONObject("location");
        if (location == null) {
            return null;
        }
        double latitude = location.optDouble("latitude", Double.NaN);
        double longitude = location.optDouble("longitude", Double.NaN);
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return null;
        }
        TLRPC.TL_geoPoint point = new TLRPC.TL_geoPoint();
        point.lat = latitude;
        point._long = longitude;
        TLRPC.TL_channelLocation result = new TLRPC.TL_channelLocation();
        result.geo_point = point;
        String label = location.optString("label", "");
        String address = location.optString("address", "");
        result.address = !TextUtils.isEmpty(label) ? label : address;
        return result;
    }

    /** Сохранение геолокации канала или группы (channels.editLocation). */
    public static TLRPC.TL_boolTrue updateLocation(int account, long tgId, double latitude, double longitude,
                                                   String address) throws WaysGramApi.ApiException {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new WaysGramApi.ApiException(400, "LOCATION_INVALID");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("latitude", latitude);
            body.put("longitude", longitude);
            body.put("label", address == null ? "" : address);
            body.put("address", address == null ? "" : address);
        } catch (org.json.JSONException e) {
            throw new WaysGramApi.ApiException(400, "LOCATION_INVALID");
        }
        String path = isGroupChannel(tgId)
                ? "/api/groups/" + tgId + "/location"
                : "/api/channels/" + channelServer(tgId) + "/location";
        WaysGramApi.put(account, path, body);
        return new TLRPC.TL_boolTrue();
    }

    private static TLRPC.PeerColor peerColor(int colorId, long emojiId) {
        TLRPC.TL_peerColor color = new TLRPC.TL_peerColor();
        color.color = colorId > 0 ? colorId : -1;
        if (colorId > 0) color.flags |= TLObject.FLAG_0;
        if (emojiId != 0) {
            color.background_emoji_id = emojiId;
            color.flags |= TLObject.FLAG_1;
        }
        return color;
    }

    static JSONObject settings(Object... pairs) {
        JSONObject body = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            try {
                body.put(String.valueOf(pairs[i]), pairs[i + 1]);
            } catch (org.json.JSONException ignore) {
            }
        }
        return body;
    }

    public static TLObject updateChannelSettings(int account, long channelTgId, JSONObject fields) throws WaysGramApi.ApiException {
        try {
            WaysGramApi.put(account, "/api/channels/" + channelServer(channelTgId) + "/settings", fields);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) {
                throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            }
            throw e;
        }
        refreshChatProfile(account, channelTgId);
        return new TLRPC.TL_updates();
    }

    public static TLObject updateGroupSettings(int account, long chatId, JSONObject fields) throws WaysGramApi.ApiException {
        try {
            WaysGramApi.put(account, "/api/groups/" + chatId + "/settings", fields);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) {
                throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            }
            throw e;
        }
        refreshChatProfile(account, chatId);
        return new TLRPC.TL_updates();
    }

    /** Присутствие в группе: сколько участников заходило за последние пять минут. */
    static TLRPC.TL_chatOnlines onlines(int account, long chatId) throws WaysGramApi.ApiException {
        JSONObject data = WaysGramApi.get(account, "/api/groups/" + chatId + "/onlines");
        TLRPC.TL_chatOnlines out = new TLRPC.TL_chatOnlines();
        out.onlines = Math.max(0, data.optInt("onlines"));
        return out;
    }

    /** Общие группы текущего аккаунта и другого пользователя. */
    static TLRPC.TL_messages_chats commonChats(int account, long targetId, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_messages_chats out = new TLRPC.TL_messages_chats();
        if (targetId <= 0 || targetId == selfId) {
            out.count = 0;
            return out;
        }
        JSONObject data = WaysGramApi.get(account, "/api/users/" + targetId + "/common-chats");
        JSONArray chats = data.optJSONArray("chats");
        for (int i = 0; chats != null && i < chats.length(); i++) {
            JSONObject chat = chats.optJSONObject(i);
            if (chat == null || chat.optLong("id") <= 0) {
                continue;
            }
            out.chats.add(toMegagroup(account, chat, selfId));
        }
        out.count = out.chats.size();
        return out;
    }

    /**
     * Настройки собеседника для экрана профиля. WaysGram не предлагает добавить в контакты
     * или пожаловаться на спам автоматически, поэтому набор флагов пустой: экран работает
     * без лишних пунктов и без ошибки «не поддерживается».
     */
    static TLRPC.TL_messages_peerSettings peerSettings() {
        TLRPC.TL_messages_peerSettings out = new TLRPC.TL_messages_peerSettings();
        out.settings = new TLRPC.TL_peerSettings();
        return out;
    }

    /** Жалоба на чат или пользователя без конкретных сообщений (messages.reportSpam). */
    static TLRPC.TL_boolTrue reportPeer(int account, TLRPC.InputPeer peer, long selfId) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            if (peer instanceof TLRPC.TL_inputPeerChannel) {
                body.put("scope", isGroupChannel(((TLRPC.TL_inputPeerChannel) peer).channel_id) ? "group" : "channel");
                body.put("peer_id", ((TLRPC.TL_inputPeerChannel) peer).channel_id);
            } else if (peer instanceof TLRPC.TL_inputPeerChat) {
                body.put("scope", "group");
                body.put("peer_id", ((TLRPC.TL_inputPeerChat) peer).chat_id);
            } else if (peer instanceof TLRPC.TL_inputPeerUser) {
                body.put("scope", "dm");
                body.put("peer_id", ((TLRPC.TL_inputPeerUser) peer).user_id);
            } else {
                throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
            }
            body.put("option", "spam");
            body.put("message_ids", new JSONArray());
            body.put("message", "");
        } catch (org.json.JSONException e) {
            throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        }
        WaysGramApi.post(account, "/api/reports", body);
        return new TLRPC.TL_boolTrue();
    }

    /** Groups the current account administers: candidates for a channel discussion group. */
    public static TLRPC.TL_messages_chats discussionGroups(int account, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_messages_chats out = new TLRPC.TL_messages_chats();
        JSONArray groups = WaysGramApi.get(account, "/api/groups").optJSONArray("groups");
        for (int i = 0; groups != null && i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String role = group.optString("role", "member");
            if (!"owner".equals(role) && !"admin".equals(role)) continue;
            out.chats.add(toMegagroup(account, group, selfId));
        }
        out.count = out.chats.size();
        return out;
    }

    public static TLObject setDiscussionGroup(int account, long channelTgId, long groupId) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("group_id", groupId == 0 ? JSONObject.NULL : (Object) groupId);
        } catch (org.json.JSONException ignore) {
        }
        try {
            WaysGramApi.put(account, "/api/channels/" + channelServer(channelTgId) + "/discussion-group", body);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            if (e.status == 409) throw new WaysGramApi.ApiException(400, "CHANNEL_GROUP_ALREADY_LINKED");
            throw e;
        }
        refreshChatProfile(account, channelTgId);
        return new TLRPC.TL_boolTrue();
    }

    // ---------------------------------------------------------------- group send restrictions

    /*
     * Маска «что запрещено участникам». Биты 1..32 — исходные способы отправки, 64..1024
     * добавлены позже, потому что экран «Права участников» показывает больше переключателей,
     * чем было битов: реакции, опросы, ссылки, голосовые и кружочки раньше не сохранялись
     * вовсе (переключатель возвращался в исходное положение), а музыка и голосовые делили
     * один бит. Значения старых масок трактуются как прежде: отдельные биты лишь уточняют.
     *
     * 1 текст · 2 фото · 4 видео · 8 музыка · 16 стикеры/гифки/игры · 32 файлы ·
     * 64 голосовые · 128 кружочки · 256 ссылки · 512 опросы · 1024 реакции
     */

    /** WaysGram stores what members may send as a bitmask; the UI works with denied flags. */
    static TLRPC.TL_chatBannedRights bannedRightsFor(int mask) {
        TLRPC.TL_chatBannedRights rights = new TLRPC.TL_chatBannedRights();
        rights.send_messages = (mask & 1) != 0;
        rights.send_plain = (mask & 1) != 0;
        rights.send_inline = (mask & 1) != 0;
        rights.send_photos = (mask & 2) != 0;
        rights.send_media = (mask & 2) != 0;
        rights.send_videos = (mask & 4) != 0;
        rights.send_audios = (mask & 8) != 0;
        rights.send_stickers = (mask & 16) != 0;
        rights.send_gifs = (mask & 16) != 0;
        rights.send_games = (mask & 16) != 0;
        rights.send_docs = (mask & 32) != 0;
        rights.send_voices = (mask & 64) != 0;
        rights.send_roundvideos = (mask & 128) != 0;
        rights.embed_links = (mask & 256) != 0;
        rights.send_polls = (mask & 512) != 0;
        rights.send_reactions = (mask & 1024) != 0;
        /*
         * Флаги берём в проводном виде — по ним сравниваются права, — но решения интерфейса
         * («можно ли отправлять») остаются по исходной маске: TLRPC выводит send_messages и
         * send_media из детальных флагов, и после проводной нормализации запрет сообщений
         * выглядел бы снятым.
         */
        TLRPC.TL_chatBannedRights stable = normalized(rights);
        stable.send_messages |= (mask & 1) != 0;
        stable.send_media |= (mask & 2) != 0;
        return stable;
    }

    static int sendRestrictionMask(TLRPC.TL_chatBannedRights rights) {
        if (rights == null) return 0;
        int mask = 0;
        if (rights.send_messages || rights.send_plain || rights.send_inline) mask |= 1;
        if (rights.send_media || rights.send_photos) mask |= 2;
        if (rights.send_videos) mask |= 4;
        if (rights.send_audios) mask |= 8;
        if (rights.send_stickers || rights.send_gifs || rights.send_games) mask |= 16;
        if (rights.send_docs) mask |= 32;
        if (rights.send_voices) mask |= 64;
        if (rights.send_roundvideos) mask |= 128;
        if (rights.embed_links) mask |= 256;
        if (rights.send_polls) mask |= 512;
        if (rights.send_reactions) mask |= 1024;
        return mask;
    }

    public static TLObject setGroupDefaultRights(int account, long chatId, TLRPC.TL_chatBannedRights rights) throws WaysGramApi.ApiException {
        JSONObject body = settings("default_send_restrictions", sendRestrictionMask(rights));
        try {
            WaysGramApi.put(account, "/api/groups/" + chatId + "/send-permissions", body);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            throw e;
        }
        refreshChatProfile(account, chatId);
        return new TLRPC.TL_updates();
    }

    // ---------------------------------------------------------------- history and extra toggles

    public static TLObject clearChannelHistory(int account, long channelTgId, boolean forEveryone) throws WaysGramApi.ApiException {
        try {
            WaysGramApi.delete(account, "/api/channels/" + channelServer(channelTgId)
                    + "/messages?for_everyone=" + (forEveryone ? "true" : "false"));
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            throw e;
        }
        return new TLRPC.TL_updates();
    }

    public static TLObject clearChannelMemberHistory(int account, long channelTgId, long targetId) throws WaysGramApi.ApiException {
        try {
            WaysGramApi.delete(account, "/api/channels/" + channelServer(channelTgId)
                    + "/members/" + targetId + "/messages");
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            throw e;
        }
        TLRPC.TL_messages_affectedHistory out = new TLRPC.TL_messages_affectedHistory();
        out.pts = 1;
        out.pts_count = 1;
        return out;
    }

    /** WaysGram channels have a single public address: hiding it is the same as making the channel private. */
    public static TLObject deactivateChannelUsernames(int account, long channelTgId) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("is_public", false);
        } catch (org.json.JSONException ignore) {
        }
        try {
            WaysGramApi.put(account, "/api/channels/" + channelServer(channelTgId), body);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            throw e;
        }
        refreshChatProfile(account, channelTgId);
        return new TLRPC.TL_boolTrue();
    }

    public static TLObject toggleChannelUsername(int account, long channelTgId, String username, boolean active) throws WaysGramApi.ApiException {
        if (active) {
            return new TLRPC.TL_boolTrue();
        }
        return deactivateChannelUsernames(account, channelTgId);
    }

    // ---------------------------------------------------------------- admin log

    private static TLRPC.ChannelParticipant logParticipant(long userId, boolean banned, int untilDate) {
        if (!banned) {
            TLRPC.TL_channelParticipant participant = new TLRPC.TL_channelParticipant();
            participant.user_id = userId;
            participant.date = (int) (System.currentTimeMillis() / 1000);
            TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
            peer.user_id = userId;
            participant.peer = peer;
            return participant;
        }
        TLRPC.TL_channelParticipantBanned participant = new TLRPC.TL_channelParticipantBanned();
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = userId;
        participant.peer = peer;
        participant.date = (int) (System.currentTimeMillis() / 1000);
        TLRPC.TL_chatBannedRights participantBanned = new TLRPC.TL_chatBannedRights();
        participantBanned.view_messages = true;
        participantBanned.until_date = untilDate;
        participant.banned_rights = normalized(participantBanned);
        return participant;
    }

    private static TLRPC.ChannelAdminLogEventAction logAction(JSONObject event) {
        String action = event.optString("action");
        JSONObject payload = event.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();
        switch (action) {
            case "change_title": {
                TLRPC.TL_channelAdminLogEventActionChangeTitle change = new TLRPC.TL_channelAdminLogEventActionChangeTitle();
                change.prev_value = payload.optString("prev", "");
                change.new_value = payload.optString("new", "");
                return change;
            }
            case "change_about": {
                TLRPC.TL_channelAdminLogEventActionChangeAbout change = new TLRPC.TL_channelAdminLogEventActionChangeAbout();
                change.prev_value = payload.optString("prev", "");
                change.new_value = payload.optString("new", "");
                return change;
            }
            case "change_username": {
                TLRPC.TL_channelAdminLogEventActionChangeUsername change = new TLRPC.TL_channelAdminLogEventActionChangeUsername();
                change.prev_value = payload.optString("prev", "");
                change.new_value = payload.optString("new", "");
                return change;
            }
            case "change_photo": {
                TLRPC.TL_channelAdminLogEventActionChangePhoto change = new TLRPC.TL_channelAdminLogEventActionChangePhoto();
                change.prev_photo = new TLRPC.TL_photoEmpty();
                change.new_photo = WaysGramMapper.photo(payload.optString("avatar_media_id", ""));
                return change;
            }
            case "participant_join": {
                return new TLRPC.TL_channelAdminLogEventActionParticipantJoin();
            }
            case "participant_leave": {
                return new TLRPC.TL_channelAdminLogEventActionParticipantLeave();
            }
            case "participant_invite": {
                TLRPC.TL_channelAdminLogEventActionParticipantInvite invite = new TLRPC.TL_channelAdminLogEventActionParticipantInvite();
                invite.participant = logParticipant(payload.optLong("telegram_id"), false, 0);
                return invite;
            }
            case "participant_promote":
            case "participant_demote": {
                TLRPC.TL_channelAdminLogEventActionParticipantToggleAdmin toggle = new TLRPC.TL_channelAdminLogEventActionParticipantToggleAdmin();
                boolean promote = "participant_promote".equals(action);
                toggle.prev_participant = logParticipant(payload.optLong("telegram_id"), false, 0);
                toggle.new_participant = logParticipant(payload.optLong("telegram_id"), false, 0);
                if (promote) {
                    TLRPC.TL_channelParticipantAdmin admin = new TLRPC.TL_channelParticipantAdmin();
                    TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                    peer.user_id = payload.optLong("telegram_id");
                    admin.peer = peer;
                    admin.user_id = payload.optLong("telegram_id");
                    admin.date = (int) (System.currentTimeMillis() / 1000);
                    toggle.new_participant = admin;
                } else {
                    TLRPC.TL_channelParticipantAdmin admin = new TLRPC.TL_channelParticipantAdmin();
                    TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                    peer.user_id = payload.optLong("telegram_id");
                    admin.peer = peer;
                    admin.user_id = payload.optLong("telegram_id");
                    admin.date = (int) (System.currentTimeMillis() / 1000);
                    toggle.prev_participant = admin;
                }
                return toggle;
            }
            case "participant_ban":
            case "participant_unban": {
                TLRPC.TL_channelAdminLogEventActionParticipantToggleBan toggle = new TLRPC.TL_channelAdminLogEventActionParticipantToggleBan();
                boolean banned = "participant_ban".equals(action);
                long target = payload.optLong("telegram_id");
                toggle.prev_participant = logParticipant(target, banned, 0);
                toggle.new_participant = logParticipant(target, banned, payload.optInt("until_date"));
                return toggle;
            }
            case "toggle_slow_mode": {
                TLRPC.TL_channelAdminLogEventActionToggleSlowMode toggle = new TLRPC.TL_channelAdminLogEventActionToggleSlowMode();
                toggle.new_value = payload.optInt("value");
                return toggle;
            }
            case "toggle_signatures": {
                TLRPC.TL_channelAdminLogEventActionToggleSignatures toggle = new TLRPC.TL_channelAdminLogEventActionToggleSignatures();
                toggle.new_value = payload.optBoolean("value");
                return toggle;
            }
            case "change_linked_chat": {
                TLRPC.TL_channelAdminLogEventActionChangeLinkedChat change = new TLRPC.TL_channelAdminLogEventActionChangeLinkedChat();
                change.new_value = payload.optLong("group_id");
                return change;
            }
            default:
                return null;
        }
    }

    public static TLObject adminLog(int account, TLRPC.TL_channels_getAdminLog req, long selfId) throws WaysGramApi.ApiException {
        long channelTgId = req.channel.channel_id;
        String query = req.q == null ? "" : android.net.Uri.encode(req.q);
        int limit = Math.max(1, Math.min(100, req.limit <= 0 ? 50 : req.limit));
        JSONObject data = WaysGramApi.get(account, "/api/channels/" + channelServer(channelTgId)
                + "/admin-log?limit=" + limit + "&max_id=" + Math.max(0, req.max_id) + "&q=" + query);
        TLRPC.TL_channels_adminLogResults out = new TLRPC.TL_channels_adminLogResults();
        JSONArray events = data.optJSONArray("events");
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            TLRPC.ChannelAdminLogEventAction action = logAction(event);
            if (action == null) continue;
            TLRPC.TL_channelAdminLogEvent item = new TLRPC.TL_channelAdminLogEvent();
            item.id = event.optLong("id");
            item.date = event.optInt("date");
            item.user_id = event.optLong("actor_id");
            item.action = action;
            out.events.add(item);
        }
        JSONArray users = data.optJSONArray("users");
        for (int i = 0; users != null && i < users.length(); i++) {
            JSONObject user = users.optJSONObject(i);
            if (user != null) out.users.add(WaysGramMapper.toUser(user));
        }
        synchronized (WaysGramChats.class) {
            TLRPC.Chat cached = chatCache.get(cacheKey(account, channelTg(channelTgId)));
            if (cached != null) out.chats.add(cached);
        }
        return out;
    }

    // ---------------------------------------------------------------- chat photos

    private static String uploadedChatPhoto(int account, TLRPC.InputChatPhoto photo) throws WaysGramApi.ApiException {
        if (photo instanceof TLRPC.TL_inputChatUploadedPhoto) {
            TLRPC.TL_inputChatUploadedPhoto uploaded = (TLRPC.TL_inputChatUploadedPhoto) photo;
            if (uploaded.file != null) {
                return WaysGramMedia.uploadFile(account, uploaded.file, true);
            }
        }
        if (photo instanceof TLRPC.TL_inputChatPhotoEmpty) {
            return "";
        }
        throw new WaysGramApi.ApiException(400, "PHOTO_INVALID");
    }

    private static void storeChatPhoto(int account, String path, String mediaId) throws WaysGramApi.ApiException {
        try {
            if (mediaId.isEmpty()) {
                WaysGramApi.delete(account, path);
            } else {
                JSONObject body = new JSONObject();
                body.put("media_id", mediaId);
                WaysGramApi.put(account, path, body);
            }
        } catch (org.json.JSONException e) {
            throw new WaysGramApi.ApiException(400, "PHOTO_INVALID");
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) {
                throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            }
            if (e.status == 404) {
                throw new WaysGramApi.ApiException(400, "PHOTO_INVALID");
            }
            throw e;
        }
    }

    /** The profile screen reloads the chat after the change and takes the new avatar from it. */
    private static void refreshChatProfile(int account, long chatId) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                org.telegram.messenger.MessagesController.getInstance(account).loadFullChat(chatId, 0, true));
    }

    public static TLObject editChannelPhoto(int account, long channelTgId, TLRPC.InputChatPhoto photo) throws WaysGramApi.ApiException {
        String mediaId = uploadedChatPhoto(account, photo);
        storeChatPhoto(account, "/api/channels/" + channelServer(channelTgId) + "/avatar", mediaId);
        refreshChatProfile(account, channelTgId);
        return new TLRPC.TL_updates();
    }

    public static TLObject editGroupPhoto(int account, long chatId, TLRPC.InputChatPhoto photo) throws WaysGramApi.ApiException {
        String mediaId = uploadedChatPhoto(account, photo);
        storeChatPhoto(account, "/api/groups/" + chatId + "/avatar", mediaId);
        refreshChatProfile(account, chatId);
        return new TLRPC.TL_updates();
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
            TLRPC.TL_chatBannedRights memberBanned = new TLRPC.TL_chatBannedRights();
            memberBanned.view_messages = true;
            memberBanned.until_date = row.optInt("until_date");
            participant.banned_rights = normalized(memberBanned);
        } else if ("owner".equals(role) || "admin".equals(role)) {
            participant = "owner".equals(role) ? new TLRPC.TL_channelParticipantCreator() : new TLRPC.TL_channelParticipantAdmin();
            participant.admin_rights = channelRights("owner".equals(role) ? 127 : row.optInt("admin_rights", 0));
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
        result.chats.add(toChannel(account, WaysGramApi.get(account, path), selfId, true));
        return result;
    }

    /** setChatAvailableReactions: включение, лимит и список эмодзи для канала. */
    public static TLRPC.TL_updates setAvailableReactions(int account, TLRPC.TL_messages_setChatAvailableReactions req, long selfId) throws WaysGramApi.ApiException {
        if (!(req.peer instanceof TLRPC.TL_inputPeerChannel)) {
            throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
        }
        boolean group = WaysGramChats.isGroup(req.peer);
        long peerTgId = ((TLRPC.TL_inputPeerChannel) req.peer).channel_id;
        long channelId = group ? peerTgId : channelServer(peerTgId);
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
            if (!group && TLObject.hasFlag(req.flags, TLObject.FLAG_1)) {
                WaysGramApi.put(account, "/api/channels/" + channelId + "/paid-reactions",
                        new JSONObject().put("enabled", req.paid_enabled));
            }
        } catch (org.json.JSONException e) {
            throw new WaysGramApi.ApiException(400, "REACTIONS_INVALID");
        }
        if (group) {
            // У группы свои настройки реакций: раньше запрос молча «успешно» игнорировался,
            // поэтому выключенные реакции снова оказывались включёнными после переоткрытия.
            WaysGramApi.put(account, "/api/groups/" + channelId + "/reactions-settings", body);
            TLRPC.TL_updates groupUpdates = new TLRPC.TL_updates();
            groupUpdates.date = (int) (System.currentTimeMillis() / 1000);
            groupUpdates.chats.add(toMegagroup(account, WaysGramApi.get(account, "/api/groups/" + channelId), selfId));
            return groupUpdates;
        }
        WaysGramApi.put(account, "/api/channels/" + channelId + "/reactions-settings", body);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.chats.add(toChannel(account, WaysGramApi.get(account, "/api/channels/" + channelId), selfId,
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
        if (!reactionsEnabled) {
            // Выключенные реакции важнее сохранённого набора эмодзи: раньше набор побеждал,
            // и переключатель «Реакции» выглядел включённым после переоткрытия настроек.
            full.available_reactions = new TLRPC.TL_chatReactionsNone();
        } else if (availableReactions == null || availableReactions.length() == 0) {
            full.available_reactions = new TLRPC.TL_chatReactionsAll();
        } else {
            TLRPC.TL_chatReactionsSome some = new TLRPC.TL_chatReactionsSome();
            for (int i = 0; i < availableReactions.length(); i++) {
                String emoji = availableReactions.optString(i, "").trim();
                if (emoji.isEmpty()) continue;
                TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
                reaction.emoticon = WaysGramReactions.canonical(emoji);
                some.reactions.add(reaction);
            }
            full.available_reactions = some.reactions.isEmpty() ? new TLRPC.TL_chatReactionsAll() : some;
        }
        full.flags |= TLObject.FLAG_30;
        full.reactions_limit = channel.optInt("reactions_limit", 11);
        full.flags2 |= TLObject.FLAG_13;
        full.wallpaper = wallpaper(account, channel.optJSONObject("wallpaper"));
        if (full.wallpaper != null) full.flags2 |= 128;
        full.paid_reactions_available = channel.optBoolean("paid_reactions_enabled");
        if (full.paid_reactions_available) full.flags2 |= TLObject.FLAG_16;
        full.paid_media_allowed = full.paid_reactions_available;
        if (full.paid_media_allowed) full.flags2 |= TLObject.FLAG_14;
        full.stargifts_available = channel.optBoolean("stargifts_available");
        if (full.stargifts_available) full.flags2 |= TLObject.FLAG_19;
        full.stargifts_count = channel.optInt("stargifts_count");
        if (full.stargifts_count > 0) full.flags2 |= TLObject.FLAG_18;
        boolean canViewStats = channel.optBoolean("can_view_stats",
                channel.optLong("owner_telegram_id") == selfId || channel.optBoolean("is_admin"));
        full.can_view_stats = canViewStats;
        if (canViewStats) full.flags |= TLObject.FLAG_20;
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
        TLRPC.ChannelLocation channelLocation = chatLocation(channel);
        if (channelLocation != null) {
            full.location = channelLocation;
            full.flags |= TLObject.FLAG_2;
        }
        full.can_set_location = channel.optBoolean("can_set_location");
        if (full.can_set_location) full.flags |= TLObject.FLAG_16;
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
        int slowModeSeconds = channel.optInt("slow_mode_seconds");
        if (slowModeSeconds > 0) {
            full.slowmode_seconds = slowModeSeconds;
            full.flags |= TLObject.FLAG_17;
        }
        if (flag(channel, "pre_history_hidden")) {
            full.hidden_prehistory = true;
            full.flags |= TLObject.FLAG_10;
        }
        if (flag(channel, "participants_hidden")) {
            full.participants_hidden = true;
            full.flags2 |= TLObject.FLAG_2;
        }
        if (flag(channel, "antispam")) {
            full.antispam = true;
            full.flags2 |= TLObject.FLAG_1;
        }
        long linkedGroup = channel.optLong("discussion_group_id");
        if (linkedGroup > 0) {
            full.linked_chat_id = linkedGroup;
            full.flags |= TLObject.FLAG_14;
        }
        full.chat_photo = WaysGramMapper.photo(channel.optString("avatar_media_id", ""));
        TLRPC.TL_messages_chatFull result = new TLRPC.TL_messages_chatFull();
        result.full_chat = full;
        result.chats.add(toChannel(account, channel, selfId, joinedChannels(account).contains(channelServer(channelId))));
        return result;
    }

    /** A group as a megagroup chat: the native supergroup screens read this payload. */
    public static TLRPC.TL_messages_chatFull fullChat(int account, long chatId, long selfId) throws WaysGramApi.ApiException {
        JSONObject group = WaysGramApi.get(account, "/api/groups/" + chatId);
        Snapshot users = membersOf(group);
        long ownerId = group.optLong("owner_telegram_id");
        String role = group.optString("my_role", group.optString("role", "member"));
        boolean admin = ownerId == selfId || "admin".equals(role);

        TLRPC.TL_chatParticipants participants = new TLRPC.TL_chatParticipants();
        participants.chat_id = chatId;
        participants.version = 1;
        int created = group.optInt("created_at");
        int adminsCount = 0;
        JSONArray members = group.optJSONArray("members");
        if (members != null) {
            for (int i = 0; i < members.length(); i++) {
                JSONObject m = members.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                long uid = m.optLong("telegram_id");
                String memberRole = m.optString("role", "member");
                TLRPC.ChatParticipant participant;
                if ("owner".equals(memberRole)) {
                    participant = new TLRPC.TL_chatParticipantCreator();
                    adminsCount++;
                } else if ("admin".equals(memberRole)) {
                    participant = new TLRPC.TL_chatParticipantAdmin();
                    adminsCount++;
                    participant.waysGramAdminRights = groupAdminRights(m.optInt("admin_rights", 0));
                } else {
                    participant = new TLRPC.TL_chatParticipant();
                }
                participant.user_id = uid;
                participant.inviter_id = ownerId;
                participant.date = m.optInt("joined_at", created);
                participants.participants.add(participant);
            }
        }

        boolean membersHidden = flag(group, "members_hidden");
        TLRPC.TL_channelFull full = new TLRPC.TL_channelFull();
        full.id = chatId;
        full.about = group.optString("description", "");
        full.flags |= 1;
        boolean canViewParticipants = admin || !membersHidden;
        full.can_view_participants = canViewParticipants;
        if (canViewParticipants) full.flags |= TLObject.FLAG_3;
        full.participants_count = Math.max(1, group.optInt("member_count", 1));
        if (adminsCount > 0) {
            full.admins_count = adminsCount;
            full.flags |= TLObject.FLAG_1;
        }
        boolean groupReactionsEnabled = group.optBoolean("reactions_enabled", true);
        JSONArray groupReactions = group.optJSONArray("available_reactions");
        if (!groupReactionsEnabled) {
            full.available_reactions = new TLRPC.TL_chatReactionsNone();
        } else if (groupReactions == null || groupReactions.length() == 0) {
            full.available_reactions = new TLRPC.TL_chatReactionsAll();
        } else {
            TLRPC.TL_chatReactionsSome groupSome = new TLRPC.TL_chatReactionsSome();
            for (int i = 0; i < groupReactions.length(); i++) {
                String emoji = groupReactions.optString(i, "").trim();
                if (emoji.isEmpty()) continue;
                TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
                reaction.emoticon = WaysGramReactions.canonical(emoji);
                groupSome.reactions.add(reaction);
            }
            full.available_reactions = groupSome.reactions.isEmpty()
                    ? new TLRPC.TL_chatReactionsAll() : groupSome;
        }
        full.flags |= TLObject.FLAG_30;
        full.reactions_limit = group.optInt("reactions_limit", 11);
        full.flags2 |= TLObject.FLAG_13;
        full.wallpaper = wallpaper(account, group.optJSONObject("wallpaper"));
        if (full.wallpaper != null) full.flags2 |= 128;
        boolean canViewStats = group.optBoolean("can_view_stats", admin);
        full.can_view_stats = canViewStats;
        if (canViewStats) full.flags |= TLObject.FLAG_20;
        full.participants = participants;
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        TLRPC.ChannelLocation groupLocation = chatLocation(group);
        if (groupLocation != null) {
            full.location = groupLocation;
            full.flags |= TLObject.FLAG_2;
        }
        full.can_set_location = group.optBoolean("can_set_location");
        if (full.can_set_location) full.flags |= TLObject.FLAG_16;
        int slowModeSeconds = group.optInt("slow_mode_seconds");
        if (slowModeSeconds > 0) {
            full.slowmode_seconds = slowModeSeconds;
            full.flags |= TLObject.FLAG_17;
        }
        if (flag(group, "pre_history_hidden")) {
            full.hidden_prehistory = true;
            full.flags |= TLObject.FLAG_10;
        }
        if (membersHidden) {
            full.participants_hidden = true;
            full.flags2 |= TLObject.FLAG_2;
        }
        if (flag(group, "anti_spam_enabled")) {
            full.antispam = true;
            full.flags2 |= TLObject.FLAG_1;
        }
        full.requests_pending = group.optInt("requests_pending");
        JSONArray requesters = group.optJSONArray("recent_requesters");
        if (full.requests_pending > 0 && requesters != null) {
            full.flags |= TLObject.FLAG_28;
            for (int i = 0; i < requesters.length(); i++) full.recent_requesters.add(requesters.optLong(i));
        }
        if (admin) {
            try {
                full.exported_invite = WaysGramInvites.groupPermanentInvite(account, chatId, selfId, false);
                full.flags |= TLObject.FLAG_23;
            } catch (WaysGramApi.ApiException e) {
                // Older server or permissions changed; keep the group profile usable.
                if (e.status != 403 && e.status != 404) org.telegram.messenger.FileLog.e(e);
            }
        }
        full.stories_pinned_available = group.optBoolean("stories_pinned_available");
        if (full.stories_pinned_available) full.flags2 |= TLObject.FLAG_5;
        if (group.optBoolean("stories_active")) {
            try {
                full.stories = WaysGramStories.active(account, chatId, selfId);
                full.flags2 |= TLObject.FLAG_4;
            } catch (WaysGramApi.ApiException e) {
                if (e.status == 401) throw e;
                org.telegram.messenger.FileLog.e(e);
            }
        }
        full.chat_photo = WaysGramMapper.photo(group.optString("avatar_media_id", ""));

        TLRPC.TL_messages_chatFull out = new TLRPC.TL_messages_chatFull();
        out.full_chat = full;
        out.chats.add(toMegagroup(account, group, selfId));
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
        return updatesWithChat(account, WaysGramApi.get(account, path), selfId);
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
        out.updates = updatesWithChat(account, created, selfId);
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
        out.updates = updatesWithChat(account, WaysGramApi.get(account, "/api/groups/" + chatId), selfId);
        return out;
    }

    public static TLRPC.TL_updates removeUser(int account, long chatId, long userId, long selfId) throws WaysGramApi.ApiException {
        WaysGramApi.delete(account, "/api/groups/" + chatId + "/members/" + userId);
        if (userId == selfId) {
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = (int) (System.currentTimeMillis() / 1000);
            return updates;
        }
        return updatesWithChat(account, WaysGramApi.get(account, "/api/groups/" + chatId), selfId);
    }

    // ---------------------------------------------------------------- search and join

    public static TLRPC.TL_channel findChannelByUsername(int account, String username, long selfId) throws WaysGramApi.ApiException {
        JSONArray channels = WaysGramApi.get(account, "/api/channels").optJSONArray("channels");
        HashSet<Long> joined = joinedChannels(account);
        if (channels != null) {
            for (int i = 0; i < channels.length(); i++) {
                JSONObject c = channels.optJSONObject(i);
                if (c != null && username.equalsIgnoreCase(c.optString("username"))) {
                    return toChannel(account, c, selfId, listedChannelMember(c, joined));
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
                TLRPC.TL_channel channel = toChannel(account, c, selfId, listedChannelMember(c, joined));
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

    // ---------------------------------------------------------------- groups as megagroups (channels.* methods)

    /** Group rows carry no ban list: a banned member is removed from the group, so the list is empty. */
    static TLObject groupParticipants(int account, TLRPC.TL_channels_getParticipants req, long selfId) throws WaysGramApi.ApiException {
        long groupId = req.channel.channel_id;
        JSONObject group = WaysGramApi.get(account, "/api/groups/" + groupId);
        long owner = group.optLong("owner_telegram_id");
        boolean adminsOnly = req.filter instanceof TLRPC.TL_channelParticipantsAdmins;
        String query = req.filter.q == null ? "" : req.filter.q.trim().toLowerCase();
        TLRPC.TL_channels_channelParticipants out = new TLRPC.TL_channels_channelParticipants();
        ArrayList<JSONObject> matched = new ArrayList<>();
        JSONArray rows = group.optJSONArray("members");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String role = row.optString("role", "member");
            if (adminsOnly && !"owner".equals(role) && !"admin".equals(role)) continue;
            if (!query.isEmpty()) {
                String name = row.optString("display_name", "").toLowerCase();
                String username = row.optString("username", "").toLowerCase();
                if (!name.contains(query) && !username.contains(query)) continue;
            }
            matched.add(row);
        }
        if (req.filter instanceof TLRPC.TL_channelParticipantsBanned
                || req.filter instanceof TLRPC.TL_channelParticipantsKicked) {
            matched.clear();
        }
        out.count = matched.size();
        int offset = Math.max(0, req.offset);
        int limit = Math.max(1, req.limit);
        for (int i = offset; i < matched.size() && i < offset + limit; i++) {
            JSONObject row = matched.get(i);
            out.participants.add(toChannelParticipant(row, false, owner, selfId));
            out.users.add(WaysGramMapper.toUser(row));
        }
        return out;
    }

    static TLObject groupParticipant(int account, TLRPC.TL_channels_getParticipant req, long selfId) throws WaysGramApi.ApiException {
        if (!(req.participant instanceof TLRPC.TL_inputPeerUser)) {
            throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        }
        long target = req.participant.user_id;
        JSONObject group = WaysGramApi.get(account, "/api/groups/" + req.channel.channel_id);
        JSONArray members = group.optJSONArray("members");
        for (int i = 0; members != null && i < members.length(); i++) {
            JSONObject row = members.optJSONObject(i);
            if (row == null || row.optLong("telegram_id") != target) continue;
            TLRPC.TL_channels_channelParticipant out = new TLRPC.TL_channels_channelParticipant();
            out.participant = toChannelParticipant(row, false, group.optLong("owner_telegram_id"), selfId);
            out.users.add(WaysGramMapper.toUser(row));
            return out;
        }
        throw new WaysGramApi.ApiException(400, "USER_NOT_PARTICIPANT");
    }

    private static int adminMask(TLRPC.TL_chatAdminRights rights) {
        if (rights == null) return 0;
        int mask = 0;
        if (rights.change_info) mask |= 1;
        if (rights.invite_users) mask |= 2;
        if (rights.ban_users) mask |= 4;
        if (rights.pin_messages) mask |= 8;
        if (rights.delete_messages) mask |= 16;
        if (rights.manage_call) mask |= 32;
        return mask;
    }

    static TLObject groupEditAdmin(int account, TLRPC.TL_channels_editAdmin req, long selfId) throws WaysGramApi.ApiException {
        if (!(req.user_id instanceof TLRPC.TL_inputUser)) {
            throw new WaysGramApi.ApiException(400, "USER_ID_INVALID");
        }
        int mask = adminMask(req.admin_rights);
        editGroupAdmin(account, req.channel.channel_id, req.user_id.user_id, mask != 0, mask == 0 ? null : mask);
        return updatesWithChat(account, WaysGramApi.get(account, "/api/groups/" + req.channel.channel_id), selfId);
    }

    /** The group ban list is a mute with denied rights; a full ban removes the member. */
    static TLObject groupBanParticipant(int account, TLRPC.TL_channels_editBanned req, long selfId) throws WaysGramApi.ApiException {
        if (!(req.participant instanceof TLRPC.TL_inputPeerUser)) {
            throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        }
        long groupId = req.channel.channel_id;
        long target = req.participant.user_id;
        TLRPC.TL_chatBannedRights rights = req.banned_rights;
        if (rights == null) throw new WaysGramApi.ApiException(400, "RIGHTS_INVALID");
        if (rights.view_messages) {
            WaysGramApi.delete(account, "/api/groups/" + groupId + "/members/" + target);
        } else {
            JSONObject body = new JSONObject();
            try {
                if (rights.until_date > 0) body.put("until_date", rights.until_date);
                else body.put("until_date", JSONObject.NULL);
                int mask = sendRestrictionMask(rights);
                body.put("rights", mask == 0 ? 1 : mask);
            } catch (org.json.JSONException e) {
                throw new WaysGramApi.ApiException(400, "RIGHTS_INVALID");
            }
            WaysGramApi.put(account, "/api/groups/" + groupId + "/members/" + target + "/mute", body);
        }
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        result.chats.add(toMegagroup(account, WaysGramApi.get(account, "/api/groups/" + groupId), selfId));
        return result;
    }

    static TLObject groupInviteUsers(int account, TLRPC.TL_channels_inviteToChannel req, long selfId) throws WaysGramApi.ApiException {
        long groupId = req.channel.channel_id;
        for (TLRPC.InputUser user : req.users) {
            long id = user instanceof TLRPC.TL_inputUserSelf ? selfId : user.user_id;
            if (id <= 0) continue;
            try {
                addMember(account, groupId, id);
            } catch (WaysGramApi.ApiException e) {
                if (e.status != 409) throw e;
            }
        }
        return updatesWithChat(account, WaysGramApi.get(account, "/api/groups/" + groupId), selfId);
    }

    static TLRPC.TL_updates groupJoin(int account, long groupId, long selfId) throws WaysGramApi.ApiException {
        JSONObject joined;
        try {
            joined = WaysGramApi.post(account, "/api/groups/" + groupId + "/join", new JSONObject());
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 404) throw new WaysGramApi.ApiException(400, "CHANNEL_PRIVATE");
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHANNEL_PRIVATE");
            throw e;
        }
        if (joined.optBoolean("join_requested")) {
            throw new WaysGramApi.ApiException(400, "INVITE_REQUEST_SENT");
        }
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.chats.add(toMegagroup(account, WaysGramApi.get(account, "/api/groups/" + groupId), selfId));
        return updates;
    }

    static TLRPC.TL_updates groupLeave(int account, long groupId, long selfId) throws WaysGramApi.ApiException {
        return removeUser(account, groupId, selfId, selfId);
    }

    static TLObject groupDelete(int account, long groupId) throws WaysGramApi.ApiException {
        try {
            WaysGramApi.delete(account, "/api/groups/" + groupId);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            throw e;
        }
        return new TLRPC.TL_boolTrue();
    }

    static TLObject groupEditTitle(int account, long groupId, String title, long selfId) throws WaysGramApi.ApiException {
        return editGroup(account, groupId, "title", title, selfId);
    }

    static TLObject groupEditPhoto(int account, long groupId, TLRPC.InputChatPhoto photo) throws WaysGramApi.ApiException {
        return editGroupPhoto(account, groupId, photo);
    }

    static TLObject groupReadHistory(int account, TLRPC.TL_channels_readHistory req) throws WaysGramApi.ApiException {
        WaysGramMessages.readGroup(account, req.channel.channel_id, req.max_id);
        return new TLRPC.TL_boolTrue();
    }

    static TLObject groupDeleteMessages(int account, TLRPC.TL_channels_deleteMessages req) throws WaysGramApi.ApiException {
        JSONArray ids = new JSONArray();
        for (Integer id : req.id) {
            int serverId = serverGroupId(id);
            if (serverId > 0) ids.put(serverId);
        }
        if (ids.length() > 0) {
            JSONObject body = new JSONObject();
            try {
                body.put("ids", ids);
            } catch (org.json.JSONException e) {
                throw new WaysGramApi.ApiException(400, "MESSAGE_IDS_EMPTY");
            }
            try {
                WaysGramApi.delete(account, "/api/groups/" + req.channel.channel_id + "/messages", body);
            } catch (WaysGramApi.ApiException e) {
                if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
                throw e;
            }
        }
        TLRPC.TL_messages_affectedMessages affected = new TLRPC.TL_messages_affectedMessages();
        affected.pts = 0;
        affected.pts_count = 0;
        return affected;
    }

    /**
     * «Очистить историю» группы. Сервер удаляет переписку, а клиенту нужен
     * {@link TLRPC.TL_updates}: штатный обработчик {@code channels.deleteHistory} приводит ответ
     * ровно к этому типу ({@code MessagesController.deleteDialog}). Возврат
     * {@code TL_messages_affectedMessages} ронял приложение с ClassCastException (24.11).
     */
    static TLObject groupClearHistory(int account, long groupId) throws WaysGramApi.ApiException {
        try {
            WaysGramApi.delete(account, "/api/groups/" + groupId + "/messages");
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            throw e;
        }
        return new TLRPC.TL_updates();
    }

    /**
     * Удаление истории одного участника группы. Ответ — {@code messages.affectedHistory}:
     * обработчик {@code channels.deleteParticipantHistory} читает {@code pts}, {@code pts_count}
     * и {@code offset}. Тот же тип, что у канального варианта ({@link #clearChannelMemberHistory}).
     */
    static TLObject groupClearMemberHistory(int account, long groupId, long target) throws WaysGramApi.ApiException {
        try {
            WaysGramApi.delete(account, "/api/groups/" + groupId + "/members/" + target + "/messages");
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            throw e;
        }
        TLRPC.TL_messages_affectedHistory out = new TLRPC.TL_messages_affectedHistory();
        out.pts = 1;
        out.pts_count = 1;
        out.offset = 0;
        return out;
    }

    static TLObject groupGetMessages(int account, TLRPC.TL_channels_getMessages req, long selfId) throws WaysGramApi.ApiException {
        long groupId = req.channel.channel_id;
        TLRPC.TL_messages_messages out = new TLRPC.TL_messages_messages();
        StringBuilder csv = new StringBuilder();
        for (Integer id : req.id) {
            int serverId = serverGroupId(id);
            if (serverId <= 0) continue;
            if (csv.length() != 0) csv.append(',');
            csv.append(serverId);
        }
        if (csv.length() == 0) return out;
        JSONObject page = WaysGramApi.get(account, "/api/groups/" + groupId + "/messages?message_ids=" + csv);
        JSONArray items = page.optJSONArray("messages");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject m = items.optJSONObject(i);
            if (m == null) continue;
            out.messages.add(WaysGramMedia.attach(account, groupMessage(m, groupId, selfId, account), m));
        }
        JSONObject group = WaysGramApi.get(account, "/api/groups/" + groupId);
        out.chats.add(toMegagroup(account, group, selfId));
        out.users.addAll(membersOf(group).users);
        return out;
    }

    static TLObject groupCheckUsername(int account, String username, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_boolTrue ok = new TLRPC.TL_boolTrue();
        TLObject global = WaysGramProfile.checkUsername(account, username, selfId, true);
        if (!(global instanceof TLRPC.TL_boolTrue)) return global;
        String name = username == null ? "" : username.replace("@", "").trim();
        JSONArray groups = WaysGramApi.get(account, "/api/groups").optJSONArray("groups");
        for (int i = 0; groups != null && i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && name.equalsIgnoreCase(g.optString("username"))) {
                return new TLRPC.TL_boolFalse();
            }
        }
        return ok;
    }

    static TLObject groupUpdateUsername(int account, long groupId, String username) throws WaysGramApi.ApiException {
        String name = username == null ? "" : username.replace("@", "").trim();
        JSONObject body = new JSONObject();
        try {
            body.put("username", name);
        } catch (org.json.JSONException e) {
            throw new WaysGramApi.ApiException(400, "USERNAME_INVALID");
        }
        try {
            WaysGramApi.put(account, "/api/groups/" + groupId, body);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 409) throw new WaysGramApi.ApiException(400, "USERNAME_OCCUPIED");
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            if (e.status == 400) throw new WaysGramApi.ApiException(400, "USERNAME_INVALID");
            throw e;
        }
        return new TLRPC.TL_boolTrue();
    }

    /** Channel slow-mode values are arbitrary seconds; a group accepts a fixed ladder. */
    private static int groupSlowMode(int seconds) {
        int[] allowed = {0, 5, 10, 15, 30, 60, 120, 300};
        int best = 0;
        for (int value : allowed) {
            if (value <= seconds) best = value;
        }
        return best;
    }

    static TLObject groupToggleSlowMode(int account, long groupId, int seconds, long selfId) throws WaysGramApi.ApiException {
        JSONObject fields = settings("slow_mode_seconds", groupSlowMode(seconds));
        return updateGroupSettings(account, groupId, fields);
    }

    static TLObject groupToggleMembersHidden(int account, long groupId, boolean hidden) throws WaysGramApi.ApiException {
        return updateGroupSettings(account, groupId, settings("members_hidden", hidden));
    }

    static TLObject groupTogglePreHistoryHidden(int account, long groupId, boolean hidden) throws WaysGramApi.ApiException {
        return updateGroupSettings(account, groupId, settings("pre_history_hidden", hidden));
    }

    static TLObject groupToggleAntiSpam(int account, long groupId, boolean enabled) throws WaysGramApi.ApiException {
        JSONObject group = WaysGramApi.get(account, "/api/groups/" + groupId);
        JSONObject body = settings("default_send_restrictions", group.optInt("default_send_restrictions"),
                "anti_spam_enabled", enabled);
        try {
            WaysGramApi.put(account, "/api/groups/" + groupId + "/send-permissions", body);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 403) throw new WaysGramApi.ApiException(400, "CHAT_ADMIN_REQUIRED");
            throw e;
        }
        refreshChatProfile(account, groupId);
        return new TLRPC.TL_updates();
    }

    static TLObject groupGetChannels(int account, TLRPC.TL_channels_getChannels req, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_messages_chats result = new TLRPC.TL_messages_chats();
        for (TLRPC.InputChannel input : req.id) {
            if (isGroupChannel(input.channel_id)) {
                result.chats.add(toMegagroup(account, WaysGramApi.get(account, "/api/groups/" + input.channel_id), selfId));
                continue;
            }
            long id = channelServer(input.channel_id);
            if (id <= 0) continue;
            JSONObject data = WaysGramApi.get(account, "/api/channels/" + id);
            result.chats.add(toChannel(account, data, selfId, data.optBoolean("joined") || joinedChannels(account).contains(id)));
        }
        return result;
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
        updates.chats.add(toChannel(account, json, selfId, join));
        return updates;
    }
}
