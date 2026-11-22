package org.telegram.tgnet;

import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_update;
import java.util.ArrayList;
import java.util.HashSet;

/** Text scheduling in private conversations using the existing scheduled-messages UI. */
public final class WaysGramScheduled {
    private static final int WHEN_ONLINE = 0x7ffffffe;
    private WaysGramScheduled() { }

    private static long peerId(TLRPC.InputPeer peer, long selfId) throws WaysGramApi.ApiException {
        if (peer instanceof TLRPC.TL_inputPeerSelf) return selfId;
        if (peer instanceof TLRPC.TL_inputPeerUser && peer.user_id > 0) return peer.user_id;
        throw new WaysGramApi.ApiException(400, "Планирование пока поддерживает только личные чаты");
    }

    private static void checkSession(int account, String token) throws WaysGramApi.ApiException {
        if (TextUtils.isEmpty(token) || !TextUtils.equals(token, WaysGramApi.getToken(account)))
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
    }

    private static TLRPC.Message map(JSONObject row, long peer, long selfId) throws WaysGramApi.ApiException {
        int nativeId = row.optInt("native_id", 0);
        int date = row.optInt("scheduled_at", 0);
        if (nativeId < 2 || date <= 0) throw new WaysGramApi.ApiException(502, "SCHEDULE_RESPONSE_INVALID");
        TLRPC.TL_message message = WaysGramMapper.toMessage(nativeId, selfId, peer, row.optString("body", ""), date, selfId);
        message.silent = row.optBoolean("silent", false) || row.optInt("silent", 0) != 0;
        if ("failed".equals(row.optString("status"))) message.send_state = org.telegram.messenger.MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
        int repeat = row.optInt("repeat_interval_seconds", 0);
        if (repeat > 0) { message.schedule_repeat_period = repeat; message.flags2 |= TLObject.FLAG_10; }
        WaysGramMapper.readTextEntities(message, row);
        return message;
    }

    public static TLObject send(int account, TLRPC.TL_messages_sendMessage req, long selfId, String token) throws WaysGramApi.ApiException {
        long peer = peerId(req.peer, selfId);
        if (req.schedule_date <= 0 || req.reply_to != null || req.reply_markup != null || req.send_as != null
                || req.quick_reply_shortcut != null || req.effect != 0 || req.allow_paid_stars != 0
                || req.suggested_post != null || req.rich_message != null || req.noforwards
                || req.ephemeralReceiverBotId != 0 || req.allow_paid_floodskip)
            throw new WaysGramApi.ApiException(400, "Планирование этого типа сообщения пока не поддерживается");
        boolean online = req.schedule_date == WHEN_ONLINE;
        if (online && req.schedule_repeat_period != 0)
            throw new WaysGramApi.ApiException(400, "Нельзя повторять отправку при появлении онлайн");
        JSONArray entities = WaysGramMapper.writeTextEntities(req.entities);
        JSONObject body = new JSONObject();
        try {
            body.put("recipient_id", peer);
            body.put("body", req.message == null ? "" : req.message);
            body.put("entities", entities);
            body.put("silent", req.silent);
            if (!online) {
                body.put("scheduled_at", req.schedule_date);
                body.put("repeat_interval_seconds", req.schedule_repeat_period);
                body.put("time_zone", java.util.TimeZone.getDefault().getID());
            }
        } catch (Exception e) { throw new WaysGramApi.ApiException(400, "SCHEDULE_INVALID"); }
        checkSession(account, token);
        String path = online ? "/api/messages/when-online/v2" : "/api/messages/schedule/v2";
        String key = "wg-scheduled-" + Long.toHexString(req.random_id);
        JSONObject row = WaysGramApi.request("POST", path, body, token, 30000, key);
        checkSession(account, token);
        long id = row.optLong("id", 0);
        if (id <= 0 || id > 1073741823) throw new WaysGramApi.ApiException(502, "SCHEDULE_RESPONSE_INVALID");
        try {
            row.put("native_id", id * 2 + (online ? 1 : 0));
            if (online) row.put("scheduled_at", WHEN_ONLINE);
        } catch (Exception e) { throw new WaysGramApi.ApiException(502, "SCHEDULE_RESPONSE_INVALID"); }
        TLRPC.Message message = map(row, peer, selfId);
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        TL_update.TL_updateMessageID mid = new TL_update.TL_updateMessageID();
        mid.id = message.id; mid.random_id = req.random_id;
        result.updates.add(mid);
        TL_update.TL_updateNewScheduledMessage update = new TL_update.TL_updateNewScheduledMessage();
        update.message = message; result.updates.add(update);
        return result;
    }

    public static TLRPC.TL_messages_messages list(int account, TLRPC.InputPeer input, long selfId, String token, ArrayList<Integer> selected) throws WaysGramApi.ApiException {
        long peer = peerId(input, selfId);
        checkSession(account, token);
        JSONObject data = WaysGramApi.request("GET", "/api/messages/scheduled/native/v1?with=" + peer, null, token, 30000);
        checkSession(account, token);
        JSONArray rows = data.optJSONArray("scheduled_messages");
        if (rows == null) throw new WaysGramApi.ApiException(502, "SCHEDULE_RESPONSE_INVALID");
        HashSet<Integer> wanted = selected == null ? null : new HashSet<>(selected);
        TLRPC.TL_messages_messages out = new TLRPC.TL_messages_messages();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && (wanted == null || wanted.contains(row.optInt("native_id")))) out.messages.add(map(row, peer, selfId));
        }
        try {
            JSONObject user = WaysGramApi.request("GET", "/api/users/" + peer, null, token, 30000);
            checkSession(account, token);
            out.users.add(WaysGramMapper.toUser(user));
        } catch (WaysGramApi.ApiException e) { if (e.status == 401 || e.status == -1000 || e.status >= 500) throw e; }
        return out;
    }

    /** Local-only marker: never interpret ordinary message IDs as scheduling IDs. */
    public static final class RescheduleRequest extends TLRPC.TL_messages_editMessage { }

    public static TLObject reschedule(int account, RescheduleRequest req, long selfId, String token) throws WaysGramApi.ApiException {
        long peer = peerId(req.peer, selfId);
        if (req.id < 2 || (req.id & 1) != 0 || req.schedule_date <= 0 || req.schedule_date == WHEN_ONLINE)
            throw new WaysGramApi.ApiException(400, "Изменение времени доступно только для отправки по дате");
        JSONObject body = new JSONObject();
        try {
            body.put("peer_id", peer);
            body.put("scheduled_at", req.schedule_date);
            body.put("repeat_interval_seconds", req.schedule_repeat_period);
        } catch (Exception e) { throw new WaysGramApi.ApiException(400, "SCHEDULE_INVALID"); }
        checkSession(account, token);
        JSONObject row = WaysGramApi.request("PATCH", "/api/messages/scheduled/" + (req.id / 2), body, token, 30000);
        checkSession(account, token);
        if (row.optInt("native_id") != req.id || row.optLong("recipient_id") != peer || row.optInt("scheduled_at") != req.schedule_date
                || row.optInt("repeat_interval_seconds", -1) != req.schedule_repeat_period)
            throw new WaysGramApi.ApiException(502, "SCHEDULE_RESPONSE_INVALID");
        TL_update.TL_updateNewScheduledMessage update = new TL_update.TL_updateNewScheduledMessage();
        update.message = map(row, peer, selfId);
        TLRPC.TL_updateShort result = new TLRPC.TL_updateShort();
        result.date = (int) (System.currentTimeMillis() / 1000);
        result.update = update;
        return result;
    }

    public static TLObject sendNow(int account, TLRPC.TL_messages_sendScheduledMessages req, long selfId, String token) throws WaysGramApi.ApiException {
        long peer = peerId(req.peer, selfId);
        if (req.id.isEmpty()) throw new WaysGramApi.ApiException(400, "SCHEDULE_INVALID");
        JSONObject body = new JSONObject();
        try {
            body.put("peer_id", peer);
            JSONArray ids = new JSONArray();
            for (int id : req.id) ids.put(id);
            body.put("ids", ids);
        } catch (Exception e) { throw new WaysGramApi.ApiException(400, "SCHEDULE_INVALID"); }
        checkSession(account, token);
        JSONObject data = WaysGramApi.request("POST", "/api/messages/scheduled/send-now/native/v1", body, token, 30000);
        checkSession(account, token);
        JSONArray messages = data.optJSONArray("messages");
        if (messages == null || messages.length() != req.id.size()) throw new WaysGramApi.ApiException(502, "SCHEDULE_RESPONSE_INVALID");
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        HashSet<Integer> expected = new HashSet<>(req.id);
        ArrayList<TLRPC.Message> sent = new ArrayList<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null || !expected.remove(row.optInt("scheduled_native_id")) || row.optInt("id") <= 0)
                throw new WaysGramApi.ApiException(502, "SCHEDULE_RESPONSE_INVALID");
            TLRPC.Message message = WaysGramMapper.toMessage(row, selfId, account);
            WaysGramMapper.readTextEntities(message, row);
            message.silent = row.optBoolean("silent", false) || row.optInt("silent", 0) != 0;
            message.from_scheduled = true;
            sent.add(message);
        }
        if (!expected.isEmpty()) throw new WaysGramApi.ApiException(502, "SCHEDULE_RESPONSE_INVALID");
        TL_update.TL_updateDeleteScheduledMessages removed = new TL_update.TL_updateDeleteScheduledMessages();
        removed.peer = WaysGramMapper.peerUser(peer);
        removed.messages.addAll(req.id);
        result.updates.add(removed);
        for (TLRPC.Message message : sent) {
            WaysGramPoller.markDelivered(account, peer, message.id);
            result.updates.add(WaysGramMapper.newMessageUpdate(message, 0));
        }
        return result;
    }

    public static TLObject cancel(int account, TLRPC.TL_messages_deleteScheduledMessages req, long selfId, String token) throws WaysGramApi.ApiException {
        long peer = peerId(req.peer, selfId);
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        if (req.id.isEmpty()) return result;
        JSONObject body = new JSONObject();
        try {
            body.put("peer_id", peer);
            JSONArray ids = new JSONArray();
            for (int id : req.id) ids.put(id);
            body.put("ids", ids);
        } catch (Exception e) { throw new WaysGramApi.ApiException(400, "SCHEDULE_INVALID"); }
        checkSession(account, token);
        WaysGramApi.request("POST", "/api/messages/scheduled/cancel/native/v1", body, token, 30000);
        checkSession(account, token);
        TL_update.TL_updateDeleteScheduledMessages update = new TL_update.TL_updateDeleteScheduledMessages();
        update.peer = WaysGramMapper.peerUser(peer); update.messages.addAll(req.id);
        result.updates.add(update);
        return result;
    }
}
