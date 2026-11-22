package org.telegram.tgnet;

import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;

/** Resolves the selected chat message before requesting server-side transcription. */
public class WaysGramTranscription {
    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() << 12);

    public static TLObject transcribe(int account, TLRPC.TL_messages_transcribeAudio req, long self, String token) throws WaysGramApi.ApiException {
        if (req.peer == null || req.msg_id <= 0) throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        int id = req.msg_id;
        String path;
        if (req.peer instanceof TLRPC.TL_inputPeerSelf || req.peer instanceof TLRPC.TL_inputPeerUser) {
            long peer = req.peer instanceof TLRPC.TL_inputPeerSelf ? self : req.peer.user_id;
            if (peer <= 0 || WaysGramBotFather.is(peer)) throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
            path = "/api/messages?with=" + peer + "&message_ids=";
        } else if (req.peer instanceof TLRPC.TL_inputPeerChat) {
            id = WaysGramChats.serverGroupId(id);
            if (id <= 0 || req.peer.chat_id <= 0) throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
            path = "/api/groups/" + req.peer.chat_id + "/messages?message_ids=";
        } else if (req.peer instanceof TLRPC.TL_inputPeerChannel) {
            long channel = WaysGramChats.channelServer(req.peer.channel_id);
            if (channel <= 0) throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
            path = "/api/channels/" + channel + "/messages?message_ids=";
        } else {
            throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        }
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        JSONArray rows = WaysGramApi.request("GET", path + id, null, token, 30000).optJSONArray("messages");
        JSONObject message = null;
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && row.optInt("id") == id) { message = row; break; }
        }
        if (message == null) throw new WaysGramApi.ApiException(404, "Сообщение не найдено");
        String mediaId = message.optString("media_id", "");
        String kind = message.optString("media_kind", "");
        String name = message.optString("media_name", "").toLowerCase(java.util.Locale.ROOT);
        String mime = message.optString("media_type", "").toLowerCase(java.util.Locale.ROOT);
        boolean voice = mime.startsWith("audio/") && ("voice".equals(kind) || (TextUtils.isEmpty(kind) && name.startsWith("audio_recording_voice_")));
        boolean round = mime.equals("video/mp4") && ("video_note".equals(kind) || (TextUtils.isEmpty(kind) && name.startsWith("round_video_")));
        if (!mediaId.matches("[a-f0-9]{32}") || (!voice && !round))
            throw new WaysGramApi.ApiException(400, "Можно расшифровать только голосовое сообщение или видеокружок");
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        JSONObject result = WaysGramApi.request("POST", "/api/media/" + mediaId + "/transcription", new JSONObject(), token, 150000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        String text = result.optString("text", "");
        if (TextUtils.isEmpty(text)) throw new WaysGramApi.ApiException(502, "Пустой ответ расшифровки");
        TLRPC.TL_messages_transcribedAudio output = new TLRPC.TL_messages_transcribedAudio();
        output.transcription_id = IDS.incrementAndGet();
        output.text = text;
        output.pending = false;
        return output;
    }
}
