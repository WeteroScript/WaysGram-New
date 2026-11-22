package org.telegram.tgnet;

import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native media browser backed by an authorized WaysGram message index. */
public class WaysGramSharedMedia {
    private static String kind(TLRPC.MessagesFilter filter) {
        if (filter instanceof TLRPC.TL_inputMessagesFilterPhotos) return "photos";
        if (filter instanceof TLRPC.TL_inputMessagesFilterVideo) return "video";
        if (filter instanceof TLRPC.TL_inputMessagesFilterPhotoVideo) return "photo_video";
        if (filter instanceof TLRPC.TL_inputMessagesFilterMusic) return "music";
        if (filter instanceof TLRPC.TL_inputMessagesFilterRoundVoice) return "round_voice";
        if (filter instanceof TLRPC.TL_inputMessagesFilterDocument) return "document";
        return null;
    }

    public static boolean handles(TLRPC.MessagesFilter filter) { return kind(filter) != null; }

    private static final class Target {
        String scope;
        long id;
        boolean group, channel;
        Target(TLRPC.InputPeer peer, long self) throws WaysGramApi.ApiException {
            if (peer instanceof TLRPC.TL_inputPeerSelf) { scope = "dm"; id = self; }
            else if (peer instanceof TLRPC.TL_inputPeerUser) { scope = "dm"; id = peer.user_id; }
            else if (peer instanceof TLRPC.TL_inputPeerChat) { scope = "group"; id = peer.chat_id; group = true; }
            else if (peer instanceof TLRPC.TL_inputPeerChannel) { scope = "channel"; id = WaysGramChats.channelServer(peer.channel_id); channel = true; }
            if (id <= 0 || ("dm".equals(scope) && WaysGramBotFather.is(id)))
                throw new WaysGramApi.ApiException(400, "MEDIA_PEER_NOT_SUPPORTED");
        }
        int serverId(int id) throws WaysGramApi.ApiException {
            if (id == 0) return 0;
            int result = group ? WaysGramChats.serverGroupId(id) : id;
            if (result <= 0) throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
            return result;
        }
        String index(String kind) {
            return "/api/media/shared/messages/v2?scope=" + scope + "&peer_id=" + id + "&kind=" + kind;
        }
        String messages() {
            return group ? "/api/groups/" + id + "/messages?message_ids="
                    : channel ? "/api/channels/" + id + "/messages?message_ids="
                    : "/api/messages?with=" + id + "&message_ids=";
        }
    }

    private static JSONObject get(int account, String path, String token) throws WaysGramApi.ApiException {
        if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        JSONObject result = WaysGramApi.request("GET", path, null, token, 30000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        return result;
    }

    public static TLObject search(int account, TLRPC.TL_messages_search req, long self, String token) throws WaysGramApi.ApiException {
        String kind = kind(req.filter);
        if (kind == null || !TextUtils.isEmpty(req.q) || req.from_id != null || req.saved_peer_id != null
                || req.top_msg_id != 0 || !req.saved_reaction.isEmpty() || req.min_date != 0 || req.max_date != 0
                || req.min_id != 0 || req.max_id != 0 || req.limit <= 0 || req.limit > 100
                || (req.add_offset != 0 && req.add_offset != -req.limit))
            throw new WaysGramApi.ApiException(400, "MEDIA_SEARCH_NOT_SUPPORTED");
        Target target = new Target(req.peer, self);
        int anchor = target.serverId(req.offset_id);
        boolean newer = req.add_offset < 0;
        if (newer && anchor == 0) throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        String path = target.index(kind) + "&limit=" + req.limit
                + (anchor == 0 ? "" : (newer ? "&from_id=" : "&before_id=") + anchor);
        JSONObject page = get(account, path, token);
        JSONArray ids = page.optJSONArray("ids");
        if (ids == null || ids.length() > req.limit || !page.optBoolean("batch_message_ids") || !page.has("total"))
            throw new WaysGramApi.ApiException(502, "MEDIA_PAGE_INVALID");
        TLRPC.TL_messages_messagesSlice out = new TLRPC.TL_messages_messagesSlice();
        out.count = page.optInt("total", -1);
        if (out.count < 0) throw new WaysGramApi.ApiException(502, "MEDIA_PAGE_INVALID");
        if (target.group) {
            JSONObject group = get(account, "/api/groups/" + target.id, token);
            out.chats.add(WaysGramChats.toChat(group, self));
            WaysGramChats.Snapshot snapshot = new WaysGramChats.Snapshot();
            snapshot.addUsers(group.optJSONArray("members"));
            out.users.addAll(snapshot.users);
        } else if (target.channel) {
            JSONObject channel = get(account, "/api/channels/" + target.id, token);
            out.chats.add(WaysGramChats.toChannel(channel, self, WaysGramChats.joinedChannels(account).contains(target.id)));
        } else {
            out.users.add(WaysGramMapper.toSelfUser(get(account, "/api/me", token), null));
            if (target.id != self) out.users.add(WaysGramMapper.toUser(get(account, "/api/users/" + target.id, token)));
        }
        if (ids.length() == 0) return out;
        StringBuilder query = new StringBuilder();
        java.util.HashSet<Integer> requested = new java.util.HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            int id = ids.optInt(i, 0);
            if (id <= 0 || !requested.add(id)) throw new WaysGramApi.ApiException(502, "MEDIA_PAGE_INVALID");
            if (query.length() != 0) query.append(',');
            query.append(id);
        }
        JSONArray rows = get(account, target.messages() + query, token).optJSONArray("messages");
        if (rows == null) throw new WaysGramApi.ApiException(502, "MEDIA_PAGE_INVALID");
        java.util.HashMap<Integer, JSONObject> byId = new java.util.HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            int id = row == null ? 0 : row.optInt("id");
            if (!requested.contains(id) || byId.put(id, row) != null)
                throw new WaysGramApi.ApiException(502, "MEDIA_PAGE_INVALID");
        }
        for (int i = 0; i < ids.length(); i++) {
            JSONObject row = byId.get(ids.optInt(i));
            // Deletion or revoked access between the two snapshots must not
            // silently produce a complete-looking, truncated page.
            if (row == null) throw new WaysGramApi.ApiException(409, "MEDIA_PAGE_CHANGED_RETRY");
            TLRPC.Message m = target.group ? WaysGramChats.groupMessage(row, target.id, self, account)
                    : target.channel ? WaysGramChats.channelMessage(row, WaysGramChats.channelTg(target.id), self)
                    : WaysGramMapper.toMessage(row, self, account);
            if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
                throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
            m = WaysGramMedia.attach(account, m, row);
            if (m.media == null || m.media instanceof TLRPC.TL_messageMediaEmpty)
                throw new WaysGramApi.ApiException(502, "MEDIA_LOAD_FAILED");
            out.messages.add(m);
        }
        if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        return out;
    }

    public static TLObject counters(int account, TLRPC.TL_messages_getSearchCounters req, long self, String token) throws WaysGramApi.ApiException {
        if (req.saved_peer_id != null || req.top_msg_id != 0)
            throw new WaysGramApi.ApiException(400, "MEDIA_SEARCH_NOT_SUPPORTED");
        Target target = new Target(req.peer, self);
        Vector<TLRPC.TL_messages_searchCounter> result = new Vector<>(TLRPC.TL_messages_searchCounter::TLdeserialize);
        java.util.HashMap<String, Integer> totals = new java.util.HashMap<>();
        for (TLRPC.MessagesFilter filter : req.filters) {
            String kind = kind(filter);
            if (kind == null) continue;
            Integer total = totals.get(kind);
            if (total == null) {
                JSONObject page = get(account, target.index(kind) + "&limit=1", token);
                if (!page.has("total") || !page.optBoolean("batch_message_ids"))
                    throw new WaysGramApi.ApiException(502, "MEDIA_PAGE_INVALID");
                total = page.optInt("total", -1);
                if (total < 0) throw new WaysGramApi.ApiException(502, "MEDIA_PAGE_INVALID");
                totals.put(kind, total);
            }
            TLRPC.TL_messages_searchCounter counter = new TLRPC.TL_messages_searchCounter();
            counter.filter = filter;
            counter.count = total;
            result.objects.add(counter);
        }
        if (result.objects.isEmpty()) throw new WaysGramApi.ApiException(400, "MEDIA_SEARCH_NOT_SUPPORTED");
        return result;
    }
}
