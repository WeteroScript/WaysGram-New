package org.telegram.tgnet;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/** Editing and deleting messages, read marks. Used for requests from the app and for changes made by other people. */
public class WaysGramMessages {

    // ---------------------------------------------------------------- loading one message

    public static TLRPC.Message fetchPrivate(int account, long other, int id, long selfId) throws WaysGramApi.ApiException {
        return fetchPrivate(account, other, id, selfId, WaysGramApi.getToken(account));
    }

    public static TLRPC.Message fetchPrivate(int account, long other, int id, long selfId, String token) throws WaysGramApi.ApiException {
        if (id <= 0) return null;
        JSONArray list = WaysGramApi.request("GET", "/api/messages?with=" + other + "&before_id=" + ((long) id + 1), null, token, 30000)
                .optJSONArray("messages");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m != null && m.optInt("id") == id) {
                    if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
                        throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
                    return WaysGramMedia.attach(account, WaysGramMapper.toMessage(m, selfId, account), m);
                }
            }
        }
        return null;
    }

    public static TLRPC.Message fetchGroup(int account, long groupId, int serverId, long selfId) throws WaysGramApi.ApiException {
        return fetchGroup(account, groupId, serverId, selfId, WaysGramApi.getToken(account));
    }

    public static TLRPC.Message fetchGroup(int account, long groupId, int serverId, long selfId, String token) throws WaysGramApi.ApiException {
        if (serverId <= 0) return null;
        JSONArray list = WaysGramApi.request("GET", "/api/groups/" + groupId + "/messages?limit=1&before_id=" + ((long) serverId + 1), null, token, 30000)
                .optJSONArray("messages");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m != null && m.optInt("id") == serverId) {
                    if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
                        throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
                    return WaysGramMedia.attach(account, WaysGramChats.groupMessage(m, groupId, selfId, account), m);
                }
            }
        }
        return null;
    }

    public static TLRPC.Message fetchChannel(int account, long channelServerId, int id, long selfId) throws WaysGramApi.ApiException {
        return fetchChannel(account, channelServerId, id, selfId, WaysGramApi.getToken(account));
    }

    public static TLRPC.Message fetchChannel(int account, long channelServerId, int id, long selfId, String token) throws WaysGramApi.ApiException {
        if (id <= 0) return null;
        JSONArray list = WaysGramApi.request("GET", "/api/channels/" + channelServerId + "/messages?limit=1&before_id=" + ((long) id + 1), null, token, 30000)
                .optJSONArray("messages");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m != null && m.optInt("id") == id) {
                    if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
                        throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
                    return WaysGramMedia.attach(account, WaysGramChats.channelMessage(m, WaysGramChats.channelTg(channelServerId), selfId), m);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- messages by id

    /**
     * channels.getMessages: the exact posts of one channel by their ids.
     *
     * <p>Replies, pinned posts, forwarded originals and album children are requested this way;
     * before, every such lookup ended with "функция не поддерживается WaysGram". One bounded
     * request is used instead of one request per id (the server accepts up to 100 ids).</p>
     */
    public static TLRPC.TL_messages_messages channelMessagesByIds(
            int account, long channelTgId, ArrayList<Integer> ids, long selfId
    ) throws WaysGramApi.ApiException {
        TLRPC.TL_messages_messages out = new TLRPC.TL_messages_messages();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        final long serverId = WaysGramChats.channelServer(channelTgId);
        final ArrayList<TLRPC.Message> list = new ArrayList<>();
        ArrayList<Integer> batch = new ArrayList<>();
        for (Integer id : ids) {
            if (id == null || id <= 0) continue;
            batch.add(id);
            if (batch.size() == 100) {
                fetchChannelBatch(account, serverId, channelTgId, batch, selfId, list);
                batch = new ArrayList<>();
            }
        }
        fetchChannelBatch(account, serverId, channelTgId, batch, selfId, list);
        if (!list.isEmpty()) {
            out.chats.add(WaysGramChats.toChannel(account, WaysGramApi.get(account, "/api/channels/" + serverId), selfId,
                    WaysGramChats.joinedChannels(account).contains(serverId)));
        }
        // The native client expects the newest message first.
        Collections.sort(list, (a, b) -> Integer.compare(b.id, a.id));
        out.messages.addAll(list);
        return out;
    }

    /** Public channel posts matching a query: the source of hashtag and news search. */
    public static TLObject searchPosts(int account, TLRPC.TL_channels_searchPosts req, long selfId) throws WaysGramApi.ApiException {
        String query = req.query == null ? "" : android.net.Uri.encode(req.query);
        int limit = Math.max(1, Math.min(50, req.limit <= 0 ? 30 : req.limit));
        JSONObject data = WaysGramApi.get(account, "/api/channels/posts/search?q=" + query
                + "&limit=" + limit + "&offset_id=" + Math.max(0, req.offset_id));
        TLRPC.TL_messages_channelMessages out = new TLRPC.TL_messages_channelMessages();
        out.count = data.optInt("count");
        out.pts = 1;
        HashSet<Long> chats = new HashSet<>();
        JSONArray items = data.optJSONArray("messages");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject m = items.optJSONObject(i);
            if (m == null) continue;
            long channelServerId = m.optLong("channel_id");
            if (channelServerId <= 0) continue;
            long channelTgId = WaysGramChats.channelTg(channelServerId);
            out.messages.add(WaysGramMedia.attach(account,
                    WaysGramChats.channelMessage(m, channelTgId, selfId), m));
            if (chats.add(channelTgId)) {
                try {
                    out.chats.add(WaysGramChats.toChannel(account,
                            WaysGramApi.get(account, "/api/channels/" + channelServerId), selfId,
                            WaysGramChats.joinedChannels(account).contains(channelServerId)));
                } catch (WaysGramApi.ApiException ignore) {
                    // A channel that disappeared mid-search simply stays out of the result.
                }
            }
        }
        return out;
    }

    private static void fetchChannelBatch(
            int account, long channelServerId, long channelTgId, ArrayList<Integer> ids,
            long selfId, ArrayList<TLRPC.Message> into
    ) throws WaysGramApi.ApiException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        StringBuilder csv = new StringBuilder();
        for (Integer id : ids) {
            if (csv.length() > 0) csv.append(',');
            csv.append(id);
        }
        JSONObject data = WaysGramApi.get(account,
                "/api/channels/" + channelServerId + "/messages?message_ids=" + csv);
        JSONArray items = data.optJSONArray("messages");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject m = items.optJSONObject(i);
            if (m == null) continue;
            into.add(WaysGramMedia.attach(account,
                    WaysGramChats.channelMessage(m, channelTgId, selfId), m));
        }
    }

    private static TLRPC.Peer reactionPeer(TLRPC.InputPeer peer, long selfId) throws WaysGramApi.ApiException {
        if (peer instanceof TLRPC.TL_inputPeerSelf) return WaysGramMapper.peerUser(selfId);
        if (peer instanceof TLRPC.TL_inputPeerUser && peer.user_id > 0) return WaysGramMapper.peerUser(peer.user_id);
        if (WaysGramChats.isGroup(peer) && WaysGramChats.groupPeerId(peer) > 0) {
            return WaysGramChats.groupPeer(WaysGramChats.groupPeerId(peer));
        }
        if (peer instanceof TLRPC.TL_inputPeerChannel && WaysGramChats.channelServer(peer.channel_id) > 0) {
            TLRPC.TL_peerChannel result = new TLRPC.TL_peerChannel(); result.channel_id = peer.channel_id; return result;
        }
        throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
    }

    private static int reactionServerId(TLRPC.InputPeer peer, int id) throws WaysGramApi.ApiException {
        int result = WaysGramChats.isGroup(peer) ? WaysGramChats.serverGroupId(id) : id;
        if (result <= 0) throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        return result;
    }

    private static TL_update.TL_updateMessageReactions reactionUpdate(TLRPC.Peer peer, int id, TLRPC.TL_messageReactions reactions) {
        TL_update.TL_updateMessageReactions update = new TL_update.TL_updateMessageReactions();
        update.peer = peer;
        update.msg_id = id;
        update.reactions = reactions;
        update.updateUnreadState = false;
        return update;
    }

    public static TLObject setReactions(int account, TLRPC.TL_messages_sendReaction req, long selfId, String token) throws WaysGramApi.ApiException {
        TLRPC.Peer peer = reactionPeer(req.peer, selfId);
        int serverId = reactionServerId(req.peer, req.msg_id);
        if (req.reaction.size() > 3) throw new WaysGramApi.ApiException(400, "REACTIONS_TOO_MANY");
        JSONArray selected = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (TLRPC.Reaction value : req.reaction) {
            if (!(value instanceof TLRPC.TL_reactionEmoji)) throw new WaysGramApi.ApiException(400, "REACTION_NOT_SUPPORTED");
            // Always send the catalog spelling: the server compares emoji identity
            // (U+FE0F and skin tones ignored) and stores its own canonical form.
            String emoji = WaysGramReactions.canonical(((TLRPC.TL_reactionEmoji) value).emoticon);
            if (!WaysGramReactions.contains(emoji) || !seen.add(emoji))
                throw new WaysGramApi.ApiException(400, "REACTION_NOT_SUPPORTED");
            selected.put(emoji);
        }
        String path;
        JSONObject body = new JSONObject();
        try {
            body.put("reactions", selected);
            body.put("big", req.big);
            if (req.peer instanceof TLRPC.TL_inputPeerSelf || req.peer instanceof TLRPC.TL_inputPeerUser) {
                body.put("peer_id", peer.user_id);
                path = "/api/messages/" + serverId + "/reactions";
            } else if (WaysGramChats.isGroup(req.peer)) {
                path = "/api/groups/" + WaysGramChats.groupPeerId(req.peer) + "/messages/" + serverId + "/reactions";
            } else {
                path = "/api/channels/" + WaysGramChats.channelServer(req.peer.channel_id) + "/messages/" + serverId + "/reactions";
            }
        } catch (Exception e) { throw new WaysGramApi.ApiException(400, "REACTION_INVALID"); }
        JSONObject response = WaysGramApi.request("PUT", path + "/v2", body, token, 30000);
        if (response.optJSONArray("reactions") == null || response.optJSONArray("my_reactions") == null)
            throw new WaysGramApi.ApiException(502, "REACTION_RESPONSE_INVALID");
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        result.updates.add(reactionUpdate(peer, req.msg_id, WaysGramMapper.readReactions(response)));
        return result;
    }

    /**
     * Платная реакция: `messages.sendPaidReaction` переводится в серверный маршрут поста.
     *
     * <p>Раньше метод нигде не обрабатывался, поэтому ⭐ в шторке реакций заканчивалась тостом
     * «Это действие пока не поддерживается», и звезда не появлялась у сообщения. Звёзды только
     * списываются с отправителя — владельцу канала в этой версии ничего не начисляется.</p>
     */
    public static TLObject sendPaidReaction(int account, TLRPC.TL_messages_sendPaidReaction req, long selfId, String token) throws WaysGramApi.ApiException {
        if (!(req.peer instanceof TLRPC.TL_inputPeerChannel) || WaysGramChats.isGroupChannel(req.peer.channel_id)) {
            throw new WaysGramApi.ApiException(400, "PAID_REACTIONS_UNAVAILABLE");
        }
        if (req.count <= 0 || req.count > 10000) {
            throw new WaysGramApi.ApiException(400, "PAID_REACTION_INVALID");
        }
        long tg = req.peer.channel_id;
        long serverId = WaysGramChats.channelServer(tg);
        int messageId = req.msg_id;
        if (messageId <= 0) {
            throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("stars", req.count);
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "PAID_REACTION_INVALID");
        }
        // random_id повторяется при повторе запроса, поэтому ключ идемпотентности стабилен.
        String key = "wg-paid-" + Long.toHexString(req.random_id) + "-" + Long.toHexString(req.random_id * 31 + 7);
        WaysGramApi.request("POST",
                "/api/channels/" + serverId + "/messages/" + messageId + "/paid-reactions",
                body, token, 30000, key);
        // Свежие реакции поста: сервер считает сумму звёзд сам, клиенту остаётся её нарисовать.
        JSONObject page = WaysGramApi.request("GET",
                "/api/channels/" + serverId + "/messages?message_ids=" + messageId, null, token, 30000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        JSONArray messages = page == null ? null : page.optJSONArray("messages");
        if (messages == null) {
            throw new WaysGramApi.ApiException(502, "REACTION_RESPONSE_INVALID");
        }
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = tg;
        TLRPC.TL_messageReactions reactions = new TLRPC.TL_messageReactions();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && message.optInt("id", 0) == messageId) {
                reactions = WaysGramMapper.readReactions(message);
                break;
            }
        }
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        result.updates.add(reactionUpdate(peer, messageId, reactions));
        return result;
    }

    public static TLObject refreshReactions(int account, TLRPC.TL_messages_getMessagesReactions req, long selfId, String token) throws WaysGramApi.ApiException {
        TLRPC.Peer peer = reactionPeer(req.peer, selfId);
        // One history point lookup per 100 IDs; unlike the former single-ID
        // fallback this neither rejects visible batches nor downloads media.
        java.util.LinkedHashMap<Integer, Integer> ids = new java.util.LinkedHashMap<>();
        for (int id : req.id) ids.put(reactionServerId(req.peer, id), id);
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        ArrayList<Integer> serverIds = new ArrayList<>(ids.keySet());
        String base;
        if (req.peer instanceof TLRPC.TL_inputPeerSelf || req.peer instanceof TLRPC.TL_inputPeerUser)
            base = "/api/messages?with=" + peer.user_id + "&message_ids=";
        else if (WaysGramChats.isGroup(req.peer))
            base = "/api/groups/" + WaysGramChats.groupPeerId(req.peer) + "/messages?message_ids=";
        else base = "/api/channels/" + WaysGramChats.channelServer(req.peer.channel_id) + "/messages?message_ids=";
        for (int start = 0; start < serverIds.size(); start += 100) {
            StringBuilder values = new StringBuilder();
            for (int i = start; i < Math.min(start + 100, serverIds.size()); i++) {
                if (values.length() != 0) values.append(',');
                values.append(serverIds.get(i));
            }
            JSONObject page = WaysGramApi.request("GET", base + values, null, token, 30000);
            if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
                throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
            JSONArray messages = page.optJSONArray("messages");
            if (messages == null) throw new WaysGramApi.ApiException(502, "REACTION_RESPONSE_INVALID");
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                Integer clientId = ids.get(message.optInt("id", 0));
                if (clientId != null) result.updates.add(reactionUpdate(peer, clientId, WaysGramMapper.readReactions(message)));
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- atomic forwarding between supported chats

    private static String forwardScope(TLRPC.InputPeer peer) throws WaysGramApi.ApiException {
        if (peer instanceof TLRPC.TL_inputPeerUser || peer instanceof TLRPC.TL_inputPeerSelf) return "dm";
        if (WaysGramChats.isGroup(peer)) return "group";
        if (peer instanceof TLRPC.TL_inputPeerChannel) return "channel";
        throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
    }

    private static long forwardPeerId(TLRPC.InputPeer peer, long selfId) throws WaysGramApi.ApiException {
        if (peer instanceof TLRPC.TL_inputPeerSelf) return selfId;
        if (peer instanceof TLRPC.TL_inputPeerUser) return peer.user_id;
        if (WaysGramChats.isGroup(peer)) return WaysGramChats.groupPeerId(peer);
        if (peer instanceof TLRPC.TL_inputPeerChannel) return WaysGramChats.channelServer(peer.channel_id);
        throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
    }

    public static TLObject forward(int account, TLRPC.TL_messages_forwardMessages req, long selfId) throws WaysGramApi.ApiException {
        if (req.id.isEmpty() || req.id.size() > 20 || req.id.size() != req.random_id.size()
                || req.schedule_date != 0 || req.schedule_repeat_period != 0 || req.reply_to != null
                || req.top_msg_id != 0 || req.send_as != null || req.noforwards || req.with_my_score
                || req.effect != 0 || req.suggested_post != null || req.allow_paid_stars != 0
                || req.from_ephemeral || (req.flags & TLObject.FLAG_20) != 0) {
            throw new WaysGramApi.ApiException(400, "FORWARD_NOT_SUPPORTED");
        }
        String sourceScope = forwardScope(req.from_peer);
        String targetScope = forwardScope(req.to_peer);
        long sourcePeer = forwardPeerId(req.from_peer, selfId);
        long targetPeer = forwardPeerId(req.to_peer, selfId);
        if (sourcePeer <= 0 || targetPeer <= 0) throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        JSONArray ids = new JSONArray();
        StringBuilder randoms = new StringBuilder();
        for (int i = 0; i < req.id.size(); i++) {
            int id = req.id.get(i);
            if (sourceScope.equals("group")) id = WaysGramChats.serverGroupId(id);
            if (id <= 0) throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
            ids.put(id);
            randoms.append(req.random_id.get(i)).append(',');
        }
        JSONObject body = new JSONObject();
        try {
            body.put("source_scope", sourceScope);
            body.put("source_peer_id", sourcePeer);
            body.put("target_scope", targetScope);
            body.put("target_peer_id", targetPeer);
            body.put("message_ids", ids);
            body.put("drop_author", req.drop_author);
            body.put("drop_media_captions", req.drop_media_captions);
            body.put("silent", req.silent);
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "FORWARD_INVALID");
        }
        // The same random IDs must yield the same key on retry. The server
        // checks the whole request payload against that key transactionally.
        String key = "wg-forward-" + java.util.UUID.nameUUIDFromBytes(
                randoms.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JSONObject result = WaysGramApi.postIdempotent(account, "/api/forward", body, key);
        JSONArray sent = result.optJSONArray("messages");
        if (sent == null || sent.length() != req.id.size()) {
            throw new WaysGramApi.ApiException(500, "FORWARD_RESPONSE_INVALID");
        }
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        long targetTgId = targetScope.equals("channel") ? WaysGramChats.channelTg(targetPeer) : targetPeer;
        TLRPC.Chat chat = targetScope.equals("dm") ? null : WaysGramChats.cachedChat(account, targetTgId);
        if (chat != null) updates.chats.add(chat);
        for (int i = 0; i < sent.length(); i++) {
            JSONObject item = sent.optJSONObject(i);
            if (item == null || item.optInt("id") <= 0) throw new WaysGramApi.ApiException(500, "FORWARD_RESPONSE_INVALID");
            int serverId = item.optInt("id");
            int clientId = targetScope.equals("group") ? WaysGramChats.clientGroupId(serverId) : serverId;
            long dialogId = targetScope.equals("dm") ? targetPeer : -targetTgId;
            WaysGramPoller.markDelivered(account, dialogId, serverId);
            TL_update.TL_updateMessageID mid = new TL_update.TL_updateMessageID();
            mid.random_id = req.random_id.get(i);
            mid.id = clientId;
            updates.updates.add(mid);
            TLRPC.Message message = targetScope.equals("group")
                    ? WaysGramChats.groupMessage(item, targetPeer, selfId, account)
                    : targetScope.equals("channel") ? WaysGramChats.channelMessage(item, targetTgId, selfId)
                    : WaysGramMapper.toMessage(item, selfId, account);
            message = WaysGramMedia.attach(account, message, item);
            if (targetScope.equals("channel")) {
                TL_update.TL_updateNewChannelMessage nm = new TL_update.TL_updateNewChannelMessage();
                nm.message = message;
                nm.pts = WaysGramChats.nextChannelPts(account, targetPeer);
                nm.pts_count = 1;
                updates.updates.add(nm);
            } else {
                TL_update.TL_updateNewMessage nm = new TL_update.TL_updateNewMessage();
                nm.message = message;
                nm.pts = WaysGramTransport.nextPts(account);
                nm.pts_count = 1;
                updates.updates.add(nm);
            }
        }
        return updates;
    }

    /** Send one native file group atomically and map every random ID back. */
    public static TLObject sendAlbum(int account, TLRPC.TL_messages_sendMultiMedia req, long selfId) throws WaysGramApi.ApiException {
        if (req.peer == null || req.multi_media.isEmpty() || req.multi_media.size() > 10
                || req.schedule_date != 0 || req.noforwards || req.send_as != null
                || req.quick_reply_shortcut != null || req.effect != 0 || req.allow_paid_stars != 0) {
            throw new WaysGramApi.ApiException(400, "ALBUM_NOT_SUPPORTED");
        }
        String scope = forwardScope(req.peer);
        long peerId = forwardPeerId(req.peer, selfId);
        if (peerId <= 0) throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        JSONArray mediaIds = new JSONArray(), captions = new JSONArray(), captionEntities = new JSONArray();
        boolean hasCaptionEntities = false;
        StringBuilder randoms = new StringBuilder();
        java.util.HashSet<Long> seenRandomIds = new java.util.HashSet<>();
        for (TLRPC.TL_inputSingleMedia item : req.multi_media) {
            if (item == null || item.media == null || !seenRandomIds.add(item.random_id) || item.media.live_photo) {
                throw new WaysGramApi.ApiException(400, "ALBUM_MEDIA_NOT_SUPPORTED");
            }
            WaysGramMedia.requirePersistentMedia(item.media);
            WaysGramMedia.requireSupportedVideoCover(item.media);
            String mediaId = WaysGramMedia.existingMediaId(item.media);
            if (TextUtils.isEmpty(mediaId)) throw new WaysGramApi.ApiException(400, "ALBUM_FILE_NOT_UPLOADED");
            mediaIds.put(mediaId);
            captions.put(item.message == null ? "" : item.message);
            JSONArray entities = WaysGramMapper.writeTextEntities(item.entities);
            captionEntities.put(entities);
            hasCaptionEntities |= entities.length() > 0;
            randoms.append(item.random_id).append(',');
        }
        JSONObject body = new JSONObject();
        JSONObject quote = WaysGramMapper.quoteFor(req.reply_to);
        try {
            body.put("media_ids", mediaIds);
            if (quote != null) body.put("quote", quote);
            body.put("captions", captions);
            if (hasCaptionEntities || quote != null) body.put("caption_entities", captionEntities);
            body.put("silent", req.silent);
            if (scope.equals("dm")) body.put("recipient_id", peerId);
            if (req.reply_to instanceof TLRPC.TL_inputReplyToMessage) {
                int reply = req.reply_to.reply_to_msg_id;
                if (scope.equals("group")) reply = WaysGramChats.serverGroupId(reply);
                if (reply > 0) body.put("reply_to_id", reply);
            } else if (req.reply_to != null) {
                throw new WaysGramApi.ApiException(400, "ALBUM_REPLY_NOT_SUPPORTED");
            }
        } catch (WaysGramApi.ApiException e) { throw e; }
          catch (Exception e) { throw new WaysGramApi.ApiException(400, "ALBUM_INVALID"); }
        if (scope.equals("channel")) {
            WaysGramChats.requireChannelPostRight(account, WaysGramChats.channelTg(peerId));
        }
        String path = scope.equals("dm") ? "/api/messages/album"
                : scope.equals("group") ? "/api/groups/" + peerId + "/messages/album"
                : "/api/channels/" + peerId + "/messages/album";
        path += quote != null ? "/files/quote/v1" : hasCaptionEntities ? "/files/v2" : "/files";
        String key = "wg-album-" + java.util.UUID.nameUUIDFromBytes(
                randoms.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JSONObject result = WaysGramApi.postIdempotent(account, path, body, key);
        JSONArray sent = result.optJSONArray("messages");
        if (sent == null || sent.length() != req.multi_media.size()) throw new WaysGramApi.ApiException(502, "ALBUM_RESPONSE_INVALID");
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        long targetTgId = scope.equals("channel") ? WaysGramChats.channelTg(peerId) : peerId;
        TLRPC.Chat chat = scope.equals("dm") ? null : WaysGramChats.cachedChat(account, targetTgId);
        if (chat != null) updates.chats.add(chat);
        for (int i = 0; i < sent.length(); i++) {
            JSONObject item = sent.optJSONObject(i);
            if (item == null || item.optInt("id") <= 0) throw new WaysGramApi.ApiException(502, "ALBUM_RESPONSE_INVALID");
            int serverId = item.optInt("id");
            int clientId = scope.equals("group") ? WaysGramChats.clientGroupId(serverId) : serverId;
            WaysGramPoller.markDelivered(account, scope.equals("dm") ? peerId : -targetTgId, serverId);
            TL_update.TL_updateMessageID mid = new TL_update.TL_updateMessageID();
            mid.random_id = req.multi_media.get(i).random_id;
            mid.id = clientId;
            updates.updates.add(mid);
            TLRPC.Message message = scope.equals("group") ? WaysGramChats.groupMessage(item, peerId, selfId, account)
                    : scope.equals("channel") ? WaysGramChats.channelMessage(item, targetTgId, selfId)
                    : WaysGramMapper.toMessage(item, selfId, account);
            message = WaysGramMedia.attach(account, message, item);
            if (scope.equals("channel")) {
                TL_update.TL_updateNewChannelMessage update = new TL_update.TL_updateNewChannelMessage();
                update.message = message;
                update.pts = WaysGramChats.nextChannelPts(account, peerId);
                update.pts_count = 1;
                updates.updates.add(update);
            } else {
                TL_update.TL_updateNewMessage update = new TL_update.TL_updateNewMessage();
                update.message = message;
                update.pts = WaysGramTransport.nextPts(account);
                update.pts_count = 1;
                updates.updates.add(update);
            }
        }
        return updates;
    }

    // ---------------------------------------------------------------- editing

    private static JSONObject textBody(String text) throws WaysGramApi.ApiException {
        if (text == null || text.trim().isEmpty()) {
            throw new WaysGramApi.ApiException(400, "MESSAGE_EMPTY");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("body", text);
        } catch (Exception ignore) {
        }
        return body;
    }

    public static TLObject edit(int account, TLRPC.TL_messages_editMessage req, long selfId, int globalPts) throws WaysGramApi.ApiException {
        if (req.media instanceof TLRPC.TL_inputMediaGeoLive) {
            throw new WaysGramApi.ApiException(400, "Трансляция геолокации пока не поддерживается в этом клиенте");
        }
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        if (req.schedule_date != 0 || req.schedule_repeat_period != 0 || req.rich_message != null) {
            throw new WaysGramApi.ApiException(400, "EDIT_NOT_SUPPORTED");
        }
        JSONObject body = new JSONObject();
        boolean removingMedia = req.media instanceof TLRPC.TL_inputMediaEmpty;
        boolean replacingMedia = req.media != null;
        if (removingMedia) {
            // Telegram's edit composer sends InputMediaEmpty when an existing
            // attachment is removed. Preserve the caption unless the user also
            // changed the text in the same edit request.
            try { body.put("remove_media", true); }
            catch (Exception e) { throw new WaysGramApi.ApiException(400, "MEDIA_NOT_SUPPORTED"); }
        } else if (replacingMedia) {
            WaysGramMedia.requirePersistentMedia(req.media);
            WaysGramMedia.requireSupportedVideoCover(req.media);
            JSONObject uploaded = WaysGramMedia.uploadFor(account, req.media);
            String mediaId = uploaded != null ? uploaded.optString("id", "") : WaysGramMedia.existingMediaId(req.media);
            if (TextUtils.isEmpty(mediaId)) throw new WaysGramApi.ApiException(400, "MEDIA_NOT_SUPPORTED");
            try {
                body.put("media_id", mediaId);
                // Editing an already uploaded attachment can change only its spoiler
                // or custom video cover. Carry those flags even when no new bytes exist.
                if (uploaded == null) body.put("media_metadata", WaysGramMedia.metadataForInput(req.media));
            } catch (WaysGramApi.ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new WaysGramApi.ApiException(400, "MEDIA_NOT_SUPPORTED");
            }
        }
        try {
            // Media-only edits leave the existing caption and entities intact.
            if (!replacingMedia || (req.flags & TLObject.FLAG_11) != 0) {
                body.put("body", req.message == null ? "" : req.message);
                body.put("entities", WaysGramMapper.writeTextEntities(req.entities));
            }
        } catch (WaysGramApi.ApiException e) { throw e; }
          catch (Exception e) { throw new WaysGramApi.ApiException(400, "FORMATTING_INVALID"); }
        if (req.peer instanceof TLRPC.TL_inputPeerUser || req.peer instanceof TLRPC.TL_inputPeerSelf) {
            WaysGramApi.put(account, "/api/messages/" + req.id + (replacingMedia ? "/media/v2" : ""), body);
            long peerId = req.peer instanceof TLRPC.TL_inputPeerSelf ? selfId : req.peer.user_id;
            TLRPC.Message message = fetchPrivate(account, peerId, req.id, selfId);
            if (message != null) {
                TL_update.TL_updateEditMessage u = new TL_update.TL_updateEditMessage();
                u.message = message;
                u.pts = globalPts;
                u.pts_count = 1;
                updates.updates.add(u);
            }
        } else if (WaysGramChats.isGroup(req.peer)) {
            long groupId = WaysGramChats.groupPeerId(req.peer);
            int serverId = WaysGramChats.serverGroupId(req.id);
            WaysGramApi.put(account, "/api/groups/" + groupId + "/messages/" + serverId + (replacingMedia ? "/media/v2" : ""), body);
            TLRPC.Message message = fetchGroup(account, groupId, serverId, selfId);
            if (message != null) {
                TL_update.TL_updateEditMessage u = new TL_update.TL_updateEditMessage();
                u.message = message;
                u.pts = globalPts;
                u.pts_count = 1;
                updates.updates.add(u);
            }
        } else if (req.peer instanceof TLRPC.TL_inputPeerChannel) {
            long serverChannel = WaysGramChats.channelServer(req.peer.channel_id);
            WaysGramApi.put(account, "/api/channels/" + serverChannel + "/messages/" + req.id + (replacingMedia ? "/media/v2" : ""), body);
            TLRPC.Message message = fetchChannel(account, serverChannel, req.id, selfId);
            if (message != null) {
                TL_update.TL_updateEditChannelMessage u = new TL_update.TL_updateEditChannelMessage();
                u.message = message;
                u.pts = WaysGramChats.nextChannelPts(account, serverChannel);
                u.pts_count = 1;
                updates.updates.add(u);
            }
        } else {
            throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        }
        if (updates.updates.isEmpty()) {
            throw new WaysGramApi.ApiException(404, "Изменение сохранено, но сообщение не удалось обновить. Откройте чат заново.");
        }
        return updates;
    }

    // ---------------------------------------------------------------- deleting

    /** Clear a bounded private/saved history; never reinterpret group or date-filtered requests. */
    public static TLObject clearHistory(int account, TLRPC.TL_messages_deleteHistory req, long selfId, int globalPts) throws WaysGramApi.ApiException {
        if (!(req.peer instanceof TLRPC.TL_inputPeerUser || req.peer instanceof TLRPC.TL_inputPeerSelf)
                || req.max_id <= 0 || req.min_date != 0 || req.max_date != 0) {
            throw new WaysGramApi.ApiException(400, "HISTORY_CLEAR_NOT_SUPPORTED");
        }
        long peerId = req.peer instanceof TLRPC.TL_inputPeerSelf ? selfId : req.peer.user_id;
        if (peerId <= 0) throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        WaysGramApi.delete(account, "/api/conversations/" + peerId + "/messages?for="
                + (req.revoke ? "all" : "me") + "&max_id=" + req.max_id);
        TLRPC.TL_messages_affectedHistory result = new TLRPC.TL_messages_affectedHistory();
        result.pts = globalPts;
        result.pts_count = 1;
        result.offset = 0;
        return result;
    }

    /** Delete selected messages; private deletions honor the revoke flag. */
    public static TLObject delete(int account, TLRPC.TL_messages_deleteMessages req, int globalPts) throws WaysGramApi.ApiException {
        for (int id : req.id) {
            try {
                if (id > WaysGramChats.GROUP_MESSAGE_OFFSET) {
                    WaysGramApi.delete(account, "/api/group-messages/" + WaysGramChats.serverGroupId(id));
                } else {
                    WaysGramApi.delete(account, "/api/messages/" + id + (req.revoke ? "?for=all" : "?for=me"));
                }
            } catch (WaysGramApi.ApiException e) {
                throw e; // Do not report success when the server rejected deletion.
            }
        }
        TLRPC.TL_messages_affectedMessages out = new TLRPC.TL_messages_affectedMessages();
        out.pts = globalPts;
        out.pts_count = 1;
        return out;
    }

    public static TLObject deleteInChannel(int account, TLRPC.TL_channels_deleteMessages req) throws WaysGramApi.ApiException {
        long serverChannel = WaysGramChats.channelServer(req.channel.channel_id);
        for (int id : req.id) {
            try {
                WaysGramApi.delete(account, "/api/channels/" + serverChannel + "/messages/" + id);
            } catch (WaysGramApi.ApiException e) {
                throw e; // Do not report success when the server rejected deletion.
            }
        }
        TLRPC.TL_messages_affectedMessages out = new TLRPC.TL_messages_affectedMessages();
        out.pts = WaysGramChats.nextChannelPts(account, serverChannel);
        out.pts_count = 1;
        return out;
    }

    // ---------------------------------------------------------------- read marks

    private static JSONArray readReceipts(int account, String scope, long peerId, int messageId) throws WaysGramApi.ApiException {
        if (peerId <= 0 || messageId <= 0) throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        JSONObject result = WaysGramApi.get(account, "/api/read/receipts?scope=" + scope + "&peer_id=" + peerId + "&message_id=" + messageId);
        JSONArray readers = result.optJSONArray("readers");
        if (readers == null) throw new WaysGramApi.ApiException(502, "READ_RESPONSE_INVALID");
        return readers;
    }

    public static TLObject getOutboxReadDate(int account, TLRPC.TL_messages_getOutboxReadDate req) throws WaysGramApi.ApiException {
        if (!(req.peer instanceof TLRPC.TL_inputPeerUser)) throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        JSONArray readers = readReceipts(account, "dm", req.peer.user_id, req.msg_id);
        JSONObject row = readers.optJSONObject(0);
        if (row == null || row.optInt("read_at", 0) <= 0)
            throw new WaysGramApi.ApiException(400, "USER_PRIVACY_RESTRICTED");
        TLRPC.TL_outboxReadDate date = new TLRPC.TL_outboxReadDate();
        date.date = row.optInt("read_at");
        return date;
    }

    public static TLObject getReadParticipants(int account, TLRPC.TL_messages_getMessageReadParticipants req) throws WaysGramApi.ApiException {
        if (!WaysGramChats.isGroup(req.peer)) throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        JSONArray readers = readReceipts(account, "group", WaysGramChats.groupPeerId(req.peer), WaysGramChats.serverGroupId(req.msg_id));
        Vector<TLRPC.TL_readParticipantDate> result = new Vector<>(TLRPC.TL_readParticipantDate::TLdeserialize);
        for (int i = 0; i < readers.length(); i++) {
            JSONObject row = readers.optJSONObject(i);
            if (row == null || row.optLong("user_id") <= 0 || row.optInt("read_at") <= 0) continue;
            TLRPC.TL_readParticipantDate date = new TLRPC.TL_readParticipantDate();
            date.user_id = row.optLong("user_id");
            date.date = row.optInt("read_at");
            result.objects.add(date);
        }
        return result;
    }

    private static void putRead(int account, String scope, long peerId, int lastReadId) throws WaysGramApi.ApiException {
        if (lastReadId <= 0 || peerId <= 0) {
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("scope", scope);
            body.put("peer_id", peerId);
            body.put("last_read_id", lastReadId);
        } catch (Exception ignore) {
        }
        try {
            WaysGramApi.request("PUT", "/api/read", body, WaysGramApi.getToken(account), 30000);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == -1000 || e.status >= 500) {
                throw e;
            }
        }
    }

    public static TLObject readHistory(int account, TLRPC.TL_messages_readHistory req, int currentPts) throws WaysGramApi.ApiException {
        if (req.peer instanceof TLRPC.TL_inputPeerUser && !WaysGramBotFather.is(req.peer.user_id)) {
            putRead(account, "dm", req.peer.user_id, req.max_id);
        } else if (WaysGramChats.isGroup(req.peer)) {
            putRead(account, "group", WaysGramChats.groupPeerId(req.peer), WaysGramChats.serverGroupId(req.max_id));
        }
        TLRPC.TL_messages_affectedMessages affected = new TLRPC.TL_messages_affectedMessages();
        affected.pts = currentPts;
        affected.pts_count = 0;
        return affected;
    }

    public static void readGroup(int account, long groupId, int maxId) throws WaysGramApi.ApiException {
        putRead(account, "group", groupId, WaysGramChats.serverGroupId(maxId));
    }

    public static TLObject readChannel(int account, TLRPC.TL_channels_readHistory req) throws WaysGramApi.ApiException {
        putRead(account, "channel", WaysGramChats.channelServer(req.channel.channel_id), req.max_id);
        return new TLRPC.TL_boolTrue();
    }

    /** Marks a channel as read up to its latest post, used right after joining it. */
    public static void readChannelFully(int account, long channelServerId) {
        try {
            JSONArray list = WaysGramApi.get(account, "/api/channels/" + channelServerId + "/messages").optJSONArray("messages");
            int max = 0;
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject m = list.optJSONObject(i);
                    if (m != null) {
                        max = Math.max(max, m.optInt("id"));
                    }
                }
            }
            putRead(account, "channel", channelServerId, max);
        } catch (WaysGramApi.ApiException ignore) {
        }
    }
}
