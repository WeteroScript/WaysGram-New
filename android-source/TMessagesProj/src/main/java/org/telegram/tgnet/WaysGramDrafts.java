package org.telegram.tgnet;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_update;

/** Text drafts in the existing composer, stored privately for this account. */
public final class WaysGramDrafts {
    private WaysGramDrafts() { }

    // The inherited serializer keeps the standard TL draft format on disk.
    // The optional target is supplied while applying a cloud draft on the UI thread.
    public static final class CloudDraft extends TLRPC.TL_draftMessage {
        public TLRPC.Message replyMessage;
    }

    private static int replyId(String scope, TLRPC.InputReplyTo reply) throws WaysGramApi.ApiException {
        if (reply == null) return 0;
        if (!(reply instanceof TLRPC.TL_inputReplyToMessage) || reply.reply_to_peer_id != null
                || reply.top_msg_id != 0 || reply.monoforum_peer_id != null || reply.todo_item_id != 0
                || reply.poll_option != null || (reply.quote_rich != null && !reply.quote_rich.isEmpty()))
            throw new WaysGramApi.ApiException(400, "DRAFT_REPLY_NOT_SUPPORTED");
        return "group".equals(scope) ? WaysGramChats.serverGroupId(reply.reply_to_msg_id) : reply.reply_to_msg_id;
    }

    private static String scope(TLRPC.InputPeer peer) throws WaysGramApi.ApiException {
        if (peer instanceof TLRPC.TL_inputPeerSelf || peer instanceof TLRPC.TL_inputPeerUser) return "dm";
        if (peer instanceof TLRPC.TL_inputPeerChat) return "group";
        if (peer instanceof TLRPC.TL_inputPeerChannel) return "channel";
        throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
    }

    private static long peerId(TLRPC.InputPeer peer, long selfId) throws WaysGramApi.ApiException {
        if (peer instanceof TLRPC.TL_inputPeerSelf) return selfId;
        if (peer instanceof TLRPC.TL_inputPeerUser) return peer.user_id;
        if (peer instanceof TLRPC.TL_inputPeerChat) return peer.chat_id;
        if (peer instanceof TLRPC.TL_inputPeerChannel) return WaysGramChats.channelServer(peer.channel_id);
        throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
    }

    public static TLObject save(TLRPC.TL_messages_saveDraft req, long selfId, String token) throws WaysGramApi.ApiException {
        String scope = scope(req.peer);
        long peer = peerId(req.peer, selfId);
        if (peer <= 0) throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        String text = req.message == null ? "" : req.message;
        if (req.rich_message != null || req.media != null || req.suggested_post != null || req.effect != 0)
            throw new WaysGramApi.ApiException(400, "DRAFT_CONTENT_NOT_SUPPORTED");
        int replyId = replyId(scope, req.reply_to);
        if (req.reply_to != null && replyId <= 0) throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        if (text.trim().isEmpty() && replyId == 0) {
            WaysGramApi.request("DELETE", "/api/drafts?scope=" + scope + "&peer_id=" + peer, null, token, 30000);
        } else {
            try {
                JSONObject body = new JSONObject();
                body.put("scope", scope);
                body.put("peer_id", peer);
                body.put("body", text);
                body.put("entities", WaysGramMapper.writeTextEntities(req.entities));
                if (replyId > 0) {
                    body.put("reply_to_id", replyId);
                    JSONObject quote = WaysGramMapper.quoteFor(req.reply_to);
                    if (quote != null) body.put("quote", quote);
                }
                WaysGramApi.request("PUT", "/api/drafts/v3", body, token, 30000);
            } catch (WaysGramApi.ApiException e) { throw e; }
              catch (Exception e) { throw new WaysGramApi.ApiException(400, "DRAFT_INVALID"); }
        }
        return new TLRPC.TL_boolTrue();
    }

    // Clear only the exact text that was sent and only if it has not changed
    // since the read; a newly typed draft is retained on the server.
    public static void clearSent(TLRPC.InputPeer input, TLRPC.InputReplyTo reply, long selfId, String message, JSONArray entities, String token) throws WaysGramApi.ApiException {
        String scope = scope(input);
        long peer = peerId(input, selfId);
        if (peer <= 0) return;
        String key = "scope=" + scope + "&peer_id=" + peer;
        JSONObject state = WaysGramApi.request("GET", "/api/drafts/v3?" + key, null, token, 30000);
        JSONObject draft = state.optJSONObject("draft");
        if (draft == null || !TextUtils.equals(draft.optString("body", ""), message)) return;
        JSONArray stored = draft.optJSONArray("entities");
        if (stored == null) stored = new JSONArray();
        if (!stored.toString().equals(entities.toString())) return;
        if (draft.optInt("reply_to_id", 0) != replyId(scope, reply)) return;
        JSONObject storedQuote = draft.optJSONObject("quote"), sentQuote = WaysGramMapper.quoteFor(reply);
        if ((storedQuote == null) != (sentQuote == null)
                || storedQuote != null && !storedQuote.toString().equals(sentQuote.toString())) return;
        long revision = state.optLong("revision", 0);
        WaysGramApi.request("DELETE", "/api/drafts?" + key + "&expected_revision=" + revision, null, token, 30000);
    }

    public static TLRPC.TL_updates load(int account, long selfId, String token) throws WaysGramApi.ApiException {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        String path = "/api/drafts/list/v3?limit=200";
        while (true) {
            JSONObject page = WaysGramApi.request("GET", path, null, token, 30000);
            JSONArray drafts = page.optJSONArray("drafts");
            if (drafts == null) throw new WaysGramApi.ApiException(502, "DRAFT_RESPONSE_INVALID");
            for (int i = 0; i < drafts.length(); i++) {
                JSONObject row = drafts.optJSONObject(i);
                if (row == null) continue;
                long id = row.optLong("peer_id");
                if (id <= 0) continue;
                String kind = row.optString("scope");
                TLRPC.Peer peer;
                if ("dm".equals(kind)) {
                    TLRPC.TL_peerUser p = new TLRPC.TL_peerUser(); p.user_id = id; peer = p;
                } else if ("group".equals(kind)) {
                    TLRPC.TL_peerChat p = new TLRPC.TL_peerChat(); p.chat_id = id; peer = p;
                } else if ("channel".equals(kind)) {
                    TLRPC.TL_peerChannel p = new TLRPC.TL_peerChannel(); p.channel_id = WaysGramChats.channelTg(id); peer = p;
                } else continue;
                CloudDraft draft = new CloudDraft();
                draft.message = row.optString("body", "");
                draft.date = row.optInt("updated_at", 0);
                TLRPC.TL_message text = new TLRPC.TL_message();
                text.message = draft.message;
                WaysGramMapper.readTextEntities(text, row);
                draft.entities = text.entities;
                if (!draft.entities.isEmpty()) draft.flags |= TLObject.FLAG_3;
                int replyId = row.optInt("reply_to_id", 0);
                if (replyId > 0) {
                    TLRPC.TL_inputReplyToMessage reply = new TLRPC.TL_inputReplyToMessage();
                    reply.reply_to_msg_id = "group".equals(kind) ? WaysGramChats.clientGroupId(replyId) : replyId;
                    JSONObject quote = row.optJSONObject("quote");
                    if (quote != null) {
                        reply.quote_text = quote.optString("text", "");
                        reply.flags |= TLObject.FLAG_2;
                        TLRPC.TL_message quoted = new TLRPC.TL_message();
                        quoted.message = reply.quote_text;
                        WaysGramMapper.readTextEntities(quoted, quote);
                        reply.quote_entities = quoted.entities;
                        if (!reply.quote_entities.isEmpty()) reply.flags |= TLObject.FLAG_3;
                        if (quote.has("offset")) { reply.quote_offset = quote.optInt("offset"); reply.flags |= TLObject.FLAG_4; }
                    }
                    draft.reply_to = reply;
                    draft.flags |= TLObject.FLAG_4;
                    // Fetch the target using the normal permission-checked history endpoint.
                    String targetPath = "dm".equals(kind) ? "/api/messages?with=" + id + "&message_ids=" + replyId
                            : "group".equals(kind) ? "/api/groups/" + id + "/messages?message_ids=" + replyId
                            : "/api/channels/" + id + "/messages?message_ids=" + replyId;
                    try {
                        JSONObject targetResult = WaysGramApi.request("GET", targetPath, null, token, 30000);
                        JSONArray targets = targetResult.optJSONArray("messages");
                        JSONObject target = targets != null ? targets.optJSONObject(0) : null;
                        if (target != null) {
                            TLRPC.Message targetMessage = "dm".equals(kind) ? WaysGramMapper.toMessage(target, selfId, account)
                                    : "group".equals(kind) ? WaysGramChats.groupMessage(target, id, selfId, account)
                                    : WaysGramChats.channelMessage(target, WaysGramChats.channelTg(id), selfId);
                            draft.replyMessage = WaysGramMedia.attach(account, targetMessage, target);
                        }
                    } catch (WaysGramApi.ApiException ignored) {
                        // Keep the draft even if its target was deleted or the lookup is temporarily unavailable.
                    }
                }
                TL_update.TL_updateDraftMessage update = new TL_update.TL_updateDraftMessage();
                update.peer = peer;
                update.draft = draft;
                updates.updates.add(update);
            }
            JSONObject next = page.optJSONObject("next_cursor");
            if (next == null) return updates;
            String afterScope = next.optString("after_scope", "");
            long afterPeer = next.optLong("after_peer_id", 0);
            if (!("dm".equals(afterScope) || "group".equals(afterScope) || "channel".equals(afterScope)) || afterPeer <= 0)
                throw new WaysGramApi.ApiException(502, "DRAFT_CURSOR_INVALID");
            String nextPath = "/api/drafts/list/v3?limit=200&after_scope=" + afterScope + "&after_peer_id=" + afterPeer;
            if (nextPath.equals(path)) throw new WaysGramApi.ApiException(502, "DRAFT_CURSOR_INVALID");
            path = nextPath;
        }
    }
}
