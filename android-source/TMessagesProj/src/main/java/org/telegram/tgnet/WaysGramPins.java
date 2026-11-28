package org.telegram.tgnet;

import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pin operations for WaysGram chats. Never fall back to an old server for personal pins. */
public final class WaysGramPins {
    private static final class Target {
        String scope;
        long id;
        boolean group;
        boolean channel;
    }

    private static Target target(TLRPC.InputPeer peer, long selfId) throws WaysGramApi.ApiException {
        Target t = new Target();
        if (peer instanceof TLRPC.TL_inputPeerSelf) {
            t.scope = "dm"; t.id = selfId;
        } else if (peer instanceof TLRPC.TL_inputPeerUser && peer.user_id > 0) {
            t.scope = "dm"; t.id = peer.user_id;
        } else if (WaysGramChats.isGroup(peer) && WaysGramChats.groupPeerId(peer) > 0) {
            t.scope = "group"; t.id = WaysGramChats.groupPeerId(peer); t.group = true;
        } else if (peer instanceof TLRPC.TL_inputPeerChannel && WaysGramChats.channelServer(peer.channel_id) > 0) {
            t.scope = "channel"; t.id = WaysGramChats.channelServer(peer.channel_id); t.channel = true;
        } else throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        if (t.id <= 0 || (t.scope.equals("dm") && WaysGramBotFather.is(t.id)))
            throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        return t;
    }

    private static JSONObject get(int account, String path, String token) throws WaysGramApi.ApiException {
        if (TextUtils.isEmpty(token) || !TextUtils.equals(token, WaysGramApi.getToken(account)))
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        JSONObject result = WaysGramApi.request("GET", path, null, token, 30000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        return result;
    }

    private static JSONObject requireCapabilities(int account, String token) throws WaysGramApi.ApiException {
        JSONObject caps = get(account, "/api/pins/capabilities", token);
        if (caps.optInt("version") < 2 || !caps.optBoolean("one_side") || !caps.optBoolean("keyset_paging") || !caps.optBoolean("clear_bounded"))
            throw new WaysGramApi.ApiException(400, "PIN_SERVER_UPDATE_REQUIRED");
        return caps;
    }

    private static int serverId(Target t, int clientId) throws WaysGramApi.ApiException {
        int id = t.group ? WaysGramChats.serverGroupId(clientId) : clientId;
        if (id <= 0) throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        return id;
    }

    public static TLObject change(int account, TLRPC.TL_messages_updatePinnedMessage req, long selfId, String token) throws WaysGramApi.ApiException {
        Target t = target(req.peer, selfId);
        int id = serverId(t, req.id);
        if (req.pm_oneside && !t.scope.equals("dm")) throw new WaysGramApi.ApiException(400, "PIN_ONESIDE_INVALID");
        requireCapabilities(account, token);
        JSONObject result;
        if (req.unpin) {
            result = WaysGramApi.request("DELETE", "/api/pins/v2?scope=" + t.scope + "&peer_id=" + t.id
                    + "&message_id=" + id + "&one_side=" + req.pm_oneside, null, token, 30000);
        } else {
            JSONObject body = new JSONObject();
            try {
                body.put("scope", t.scope); body.put("peer_id", t.id); body.put("message_id", id);
                body.put("one_side", req.pm_oneside); body.put("notify", !req.silent);
            } catch (Exception e) { throw new WaysGramApi.ApiException(400, "PIN_REQUEST_INVALID"); }
            result = WaysGramApi.request("POST", "/api/pins/v2", body, token, 30000);
        }
        if (result.optString("status", "").isEmpty()) throw new WaysGramApi.ApiException(502, "PIN_RESPONSE_INVALID");
        TLRPC.TL_updates out = new TLRPC.TL_updates();
        out.date = (int)(System.currentTimeMillis() / 1000);
        return out;
    }

    // Do not persist bearer tokens. A pending clear belongs to one authenticated session.
    private static String tokenDigest(String token) throws WaysGramApi.ApiException {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) value.append(String.format(java.util.Locale.US, "%02x", b & 255));
            return value.toString();
        } catch (Exception e) { throw new WaysGramApi.ApiException(500, "PIN_SESSION_HASH_FAILED"); }
    }

    public static TLObject clear(int account, TLRPC.TL_messages_unpinAllMessages req, long selfId, String token) throws WaysGramApi.ApiException {
        if (req.flags != 0 || req.top_msg_id != 0 || req.saved_peer_id != null)
            throw new WaysGramApi.ApiException(400, "PIN_CLEAR_SCOPE_NOT_SUPPORTED");
        Target t = target(req.peer, selfId);
        if (!requireCapabilities(account, token).optBoolean("clear_snapshot"))
            throw new WaysGramApi.ApiException(400, "PIN_SERVER_UPDATE_REQUIRED");
        android.content.SharedPreferences prefs = org.telegram.messenger.ApplicationLoader.applicationContext
                .getSharedPreferences("waysgram_pin_clear", android.content.Context.MODE_PRIVATE);
        String prefKey = "pending_" + account + "_" + t.scope + "_" + t.id;
        String digest = tokenDigest(token);
        String saved = prefs.getString(prefKey, null);
        JSONObject pending = null;
        if (saved != null) {
            try { pending = new JSONObject(saved); }
            catch (Exception e) { throw new WaysGramApi.ApiException(500, "PIN_CLEAR_STATE_INVALID"); }
            if (!TextUtils.equals(digest, pending.optString("session"))) {
                // Never replay another session's operation.
                if (!prefs.edit().remove(prefKey).commit())
                    throw new WaysGramApi.ApiException(500, "PIN_CLEAR_STATE_SAVE_FAILED");
                pending = null;
            }
        }
        if (pending != null) {
            long created = pending.optLong("created", 0);
            long age = System.currentTimeMillis() - created;
            if (created <= 0 || age < 0 || age > 6L * 24 * 60 * 60 * 1000) {
                // The server keeps idempotency records for seven days. Do not
                // silently replay outside that window or after a clock rollback.
                if (!prefs.edit().remove(prefKey).commit())
                    throw new WaysGramApi.ApiException(500, "PIN_CLEAR_STATE_SAVE_FAILED");
                throw new WaysGramApi.ApiException(400, "PIN_CLEAR_PENDING_EXPIRED");
            }
        } else {
            JSONObject page = get(account, "/api/pins/v2?scope=" + t.scope + "&peer_id=" + t.id
                    + "&order_by_id=true&limit=1", token);
            JSONArray pins = page.optJSONArray("pinned_messages");
            if (pins == null || !page.has("total_count") || !page.has("snapshot_event_id"))
                throw new WaysGramApi.ApiException(502, "PIN_RESPONSE_INVALID");
            if (pins.length() != 0) {
                JSONObject first = pins.optJSONObject(0);
                int max = first == null ? 0 : first.optInt("message_id");
                long snapshot = page.optLong("snapshot_event_id", -1);
                if (max <= 0 || snapshot < 0) throw new WaysGramApi.ApiException(502, "PIN_RESPONSE_INVALID");
                JSONObject body = new JSONObject();
                pending = new JSONObject();
                try {
                    body.put("scope", t.scope); body.put("peer_id", t.id); body.put("max_id", max);
                    body.put("snapshot_event_id", snapshot);
                    pending.put("body", body); pending.put("session", digest);
                    pending.put("key", java.util.UUID.randomUUID().toString());
                    pending.put("created", System.currentTimeMillis());
                } catch (Exception e) { throw new WaysGramApi.ApiException(400, "PIN_REQUEST_INVALID"); }
                if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
                    throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
                // The request is never sent unless both the key and the exact
                // snapshot are durable, even if the process dies mid-request.
                if (!prefs.edit().putString(prefKey, pending.toString()).commit())
                    throw new WaysGramApi.ApiException(500, "PIN_CLEAR_STATE_SAVE_FAILED");
            }
        }
        if (pending != null) sendPendingClear(account, token, t, prefs, prefKey, pending);
        TLRPC.TL_messages_affectedHistory out = new TLRPC.TL_messages_affectedHistory();
        out.pts = t.channel ? WaysGramChats.nextChannelPts(account, t.id) : WaysGramTransport.nextPts(account);
        out.pts_count = 1;
        out.offset = 0;
        return out;
    }

    private static void sendPendingClear(int account, String token, Target t,
            android.content.SharedPreferences prefs, String prefKey, JSONObject pending) throws WaysGramApi.ApiException {
        JSONObject body = pending.optJSONObject("body");
        String key = pending.optString("key", "");
        if (body == null || !t.scope.equals(body.optString("scope")) || body.optLong("peer_id") != t.id
                || body.optInt("max_id") <= 0 || !body.has("snapshot_event_id") || body.optLong("snapshot_event_id", -1) < 0
                || TextUtils.isEmpty(key))
            throw new WaysGramApi.ApiException(500, "PIN_CLEAR_STATE_INVALID");
        if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        JSONObject result;
        try {
            result = WaysGramApi.request("POST", "/api/pins/clear/v2", body, token, 30000, key);
        } catch (WaysGramApi.ApiException e) {
            if (e.status >= 400 && e.status < 500 && e.status != 401 && e.status != 429
                    && TextUtils.equals(token, WaysGramApi.getToken(account))) {
                // Do not replay a request definitively rejected for permissions
                // or invalid input, even if those conditions later change.
                if (!prefs.edit().remove(prefKey).commit())
                    throw new WaysGramApi.ApiException(500, "PIN_CLEAR_STATE_SAVE_FAILED");
            }
            throw e;
        }
        if (!TextUtils.equals(token, WaysGramApi.getToken(account)))
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        if (!"cleared".equals(result.optString("status")))
            throw new WaysGramApi.ApiException(502, "PIN_RESPONSE_INVALID");
        if (!prefs.edit().remove(prefKey).commit())
            throw new WaysGramApi.ApiException(500, "PIN_CLEAR_STATE_SAVE_FAILED");
    }

    /** Runs only on the account's pin queue. Never creates a new clear operation. */
    static String recoverPendingClear(int account, String token, String afterKey) throws WaysGramApi.ApiException {
        if (TextUtils.isEmpty(token) || !TextUtils.equals(token, WaysGramApi.getToken(account)) || !WaysGramPoller.isRunning(account))
            return afterKey;
        android.content.SharedPreferences prefs = org.telegram.messenger.ApplicationLoader.applicationContext
                .getSharedPreferences("waysgram_pin_clear", android.content.Context.MODE_PRIVATE);
        String prefix = "pending_" + account + "_";
        String digest = tokenDigest(token);
        java.util.TreeMap<String, JSONObject> eligible = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(prefix) || !(entry.getValue() instanceof String)) continue;
            try {
                JSONObject pending = new JSONObject((String) entry.getValue());
                JSONObject body = pending.optJSONObject("body");
                if (body == null || !TextUtils.equals(digest, pending.optString("session"))) continue;
                String scope = body.optString("scope");
                long peer = body.optLong("peer_id");
                long created = pending.optLong("created", 0), age = System.currentTimeMillis() - created;
                if (!(scope.equals("dm") || scope.equals("group") || scope.equals("channel")) || peer <= 0
                        || !name.equals(prefix + scope + "_" + peer) || created <= 0 || age < 0
                        || age > 6L * 24 * 60 * 60 * 1000 || body.optInt("max_id") <= 0
                        || !body.has("snapshot_event_id") || body.optLong("snapshot_event_id", -1) < 0
                        || TextUtils.isEmpty(pending.optString("key", ""))) continue;
                if (scope.equals("channel") && WaysGramChats.channelTg(peer) < peer) continue;
                eligible.put(name, pending);
            } catch (Exception ignored) {
                // Corrupt or foreign state is not safe to replay automatically.
            }
        }
        if (eligible.isEmpty()) return null;
        String name = afterKey == null ? eligible.firstKey() : eligible.higherKey(afterKey);
        if (name == null) name = eligible.firstKey();
        JSONObject pending = eligible.get(name);
        JSONObject body = pending.optJSONObject("body");
        Target t = new Target();
        t.scope = body.optString("scope"); t.id = body.optLong("peer_id");
        t.group = t.scope.equals("group"); t.channel = t.scope.equals("channel");
        final long dialogId = t.channel ? -WaysGramChats.channelTg(t.id) : t.group ? -t.id : t.id;
        if (!requireCapabilities(account, token).optBoolean("clear_snapshot")) return name;
        if (!WaysGramPoller.isRunning(account) || !TextUtils.equals(token, WaysGramApi.getToken(account))) return name;
        try {
            sendPendingClear(account, token, t, prefs, name, pending);
            refreshAfterRecovery(account, token, dialogId, null);
        } catch (WaysGramApi.ApiException e) {
            if (e.status >= 400 && e.status < 500 && e.status != 401 && e.status != 429
                    && TextUtils.equals(token, WaysGramApi.getToken(account))) {
                // sendPendingClear has discarded the definitively rejected request.
                refreshAfterRecovery(account, token, dialogId, e.detail);
            }
            // Network failures, server failures, rate limits and changed sessions
            // retain the original key and snapshot for a later retry.
        }
        return name;
    }

    private static void refreshAfterRecovery(int account, String token, long dialogId, String error) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            if (!TextUtils.equals(token, WaysGramApi.getToken(account))) return;
            if (error != null) org.telegram.messenger.NotificationCenter.getInstance(account)
                    .postNotificationName(org.telegram.messenger.NotificationCenter.waysGramPinError, dialogId, error);
            org.telegram.messenger.MediaDataController.getInstance(account).loadPinnedMessages(dialogId, 0, 0);
        });
    }

    public static TLObject search(int account, TLRPC.TL_messages_search req, long selfId, String token) throws WaysGramApi.ApiException {
        if (!(req.filter instanceof TLRPC.TL_inputMessagesFilterPinned) || !TextUtils.isEmpty(req.q)
                || req.from_id != null || req.saved_peer_id != null || req.top_msg_id != 0
                || req.min_date != 0 || req.max_date != 0 || req.add_offset != 0 || req.max_id != 0
                || req.min_id != 0 || !req.saved_reaction.isEmpty() || req.limit <= 0 || req.limit > 100)
            throw new WaysGramApi.ApiException(400, "PIN_SEARCH_NOT_SUPPORTED");
        Target t = target(req.peer, selfId);
        JSONObject capabilities = requireCapabilities(account, token);
        int before = req.offset_id == 0 ? 0 : serverId(t, req.offset_id);
        JSONObject page = get(account, "/api/pins/v2?scope=" + t.scope + "&peer_id=" + t.id
                + "&order_by_id=true&limit=" + req.limit + "&before_id=" + before, token);
        JSONArray pins = page.optJSONArray("pinned_messages");
        if (pins == null || !page.has("total_count")) throw new WaysGramApi.ApiException(502, "PIN_RESPONSE_INVALID");
        TLRPC.TL_messages_messagesSlice out = new TLRPC.TL_messages_messagesSlice();
        out.count = page.optInt("total_count");
        if (t.group) {
            JSONObject group = get(account, "/api/groups/" + t.id, token);
            out.chats.add(WaysGramChats.toMegagroup(account, group, selfId));
            WaysGramChats.Snapshot users = new WaysGramChats.Snapshot();
            users.addUsers(group.optJSONArray("members"));
            out.users.addAll(users.users);
        } else if (t.channel) {
            JSONObject channel = get(account, "/api/channels/" + t.id, token);
            out.chats.add(WaysGramChats.toChannel(account, channel, selfId, WaysGramChats.joinedChannels(account).contains(t.id)));
        } else {
            out.users.add(WaysGramMapper.toSelfUser(get(account, "/api/me", token), null));
            if (t.id != selfId) out.users.add(WaysGramMapper.toUser(get(account, "/api/users/" + t.id, token)));
        }
        java.util.HashMap<Integer, JSONObject> batch = null;
        if (capabilities.optBoolean("batch_message_ids") && pins.length() > 0) {
            StringBuilder ids = new StringBuilder();
            java.util.HashSet<Integer> requested = new java.util.HashSet<>();
            for (int i = 0; i < pins.length(); i++) {
                JSONObject entry = pins.optJSONObject(i);
                int id = entry == null ? 0 : entry.optInt("message_id");
                if (id <= 0 || !requested.add(id)) throw new WaysGramApi.ApiException(502, "PIN_RESPONSE_INVALID");
                if (ids.length() > 0) ids.append(',');
                ids.append(id);
            }
            String path = t.group ? "/api/groups/" + t.id + "/messages?message_ids="
                    : t.channel ? "/api/channels/" + t.id + "/messages?message_ids="
                    : "/api/messages?with=" + t.id + "&message_ids=";
            JSONArray rows = get(account, path + ids, token).optJSONArray("messages");
            if (rows == null) throw new WaysGramApi.ApiException(502, "PIN_RESPONSE_INVALID");
            batch = new java.util.HashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                int id = row == null ? 0 : row.optInt("id");
                if (!requested.contains(id) || batch.put(id, row) != null)
                    throw new WaysGramApi.ApiException(502, "PIN_RESPONSE_INVALID");
            }
        }
        for (int i = 0; i < pins.length(); i++) {
            JSONObject entry = pins.optJSONObject(i);
            int id = entry == null ? 0 : entry.optInt("message_id");
            if (id <= 0) throw new WaysGramApi.ApiException(502, "PIN_RESPONSE_INVALID");
            TLRPC.Message message;
            if (batch != null) {
                JSONObject row = batch.get(id);
                if (row == null) throw new WaysGramApi.ApiException(409, "PINS_CHANGED_RETRY");
                message = t.group ? WaysGramChats.groupMessage(row, t.id, selfId, account)
                        : t.channel ? WaysGramChats.channelMessage(row, WaysGramChats.channelTg(t.id), selfId)
                        : WaysGramMapper.toMessage(row, selfId, account);
                message = WaysGramMedia.attach(account, message, row);
            } else {
                // An older server ignores message_ids, so only use point lookup
                // after it explicitly advertises support.
                message = t.group ? WaysGramMessages.fetchGroup(account, t.id, id, selfId, token)
                        : t.channel ? WaysGramMessages.fetchChannel(account, t.id, id, selfId, token)
                        : WaysGramMessages.fetchPrivate(account, t.id, id, selfId, token);
            }
            // Do not report a truncated page as complete when it changed while loading.
            if (message == null) throw new WaysGramApi.ApiException(409, "PINS_CHANGED_RETRY");
            message.pinned = true;
            out.messages.add(message);
        }
        return out;
    }
}
