package org.telegram.tgnet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.tgnet.tl.TL_update;

/** Maps the native channel story recorder and viewers onto WaysGram's story API. */
final class WaysGramStories {
    private WaysGramStories() {}

    /**
     * Истории есть и у каналов, и у групп: группы живут в диапазоне ниже {@code CHANNEL_OFFSET},
     * каналы — выше. Раньше каналом считался только id больше смещения, поэтому в группе
     * {@code stories.canSendStory}/{@code sendStory}/{@code getPeerStories} возвращали
     * {@code METHOD_NOT_SUPPORTED} и истории группы видел только тот, кто их постил в этой же сессии.
     */
    private static boolean isChat(TLRPC.InputPeer peer) {
        return peer instanceof TLRPC.TL_inputPeerChannel && peer.channel_id > 0;
    }

    private static boolean isGroup(long peerId) {
        return WaysGramChats.isGroupChannel(peerId);
    }

    private static String path(long peerId) {
        if (isGroup(peerId)) {
            return "/api/groups/" + peerId + "/stories";
        }
        return "/api/channels/" + WaysGramChats.channelServer(peerId) + "/stories";
    }

    private static TLRPC.TL_peerChannel peer(long channelId) {
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = channelId;
        return peer;
    }

    private static TL_stories.TL_storyItem item(int account, long channelId, JSONObject row, long selfId) throws WaysGramApi.ApiException {
        TL_stories.TL_storyItem story = new TL_stories.TL_storyItem();
        story.id = row.optInt("id");
        story.date = row.optInt("created_at");
        story.expire_date = row.optInt("expires_at");
        story.dialogId = -channelId;
        story.pinned = row.optInt("pinned") != 0;
        story.noforwards = row.optInt("noforwards") != 0;
        story.out = row.optLong("author_id") == selfId;
        story.caption = row.optString("caption");
        if (!story.caption.isEmpty()) story.flags |= TLObject.FLAG_0;
        JSONObject metadata = null;
        try { metadata = new JSONObject(row.optString("media_metadata", "{}")); }
        catch (JSONException ignored) {}
        story.media = WaysGramMedia.buildMedia(account, row.optString("media_id"), row.optString("mime_type"),
                row.optString("original_name"), row.optString("media_kind"), metadata, false);
        return story;
    }

    private static TL_stories.TL_stories_stories list(int account, long channelId, long selfId, String query) throws WaysGramApi.ApiException {
        JSONObject data = WaysGramApi.get(account, path(channelId) + "?" + query);
        TL_stories.TL_stories_stories out = new TL_stories.TL_stories_stories();
        out.count = data.optInt("count");
        JSONArray rows = data.optJSONArray("stories");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) out.stories.add(item(account, channelId, row, selfId));
        }
        return out;
    }

    static TL_stories.TL_peerStories active(int account, long channelId, long selfId) throws WaysGramApi.ApiException {
        TL_stories.TL_peerStories out = new TL_stories.TL_peerStories();
        out.peer = peer(channelId);
        out.stories.addAll(list(account, channelId, selfId, "mode=active").stories);
        return out;
    }

    /** Чат для пира истории: `stories.getAllStories` требует, чтобы чат уже был известен клиенту. */
    private static TLRPC.Chat chat(int account, String scope, long serverId, long selfId) throws WaysGramApi.ApiException {
        long peerId = "channel".equals(scope) ? WaysGramChats.channelTg(serverId) : serverId;
        TLRPC.Chat known = WaysGramChats.cachedChat(account, peerId);
        if (known != null) {
            return known;
        }
        if ("channel".equals(scope)) {
            return WaysGramChats.toChannel(account, WaysGramApi.get(account, "/api/channels/" + serverId), selfId,
                    WaysGramChats.joinedChannels(account).contains(serverId));
        }
        return WaysGramChats.toMegagroup(account, WaysGramApi.get(account, "/api/groups/" + serverId), selfId);
    }

    /**
     * Все чаты с активными историями. Раньше этот запрос вообще не обслуживался, поэтому
     * кольца историй в списке чатов не появлялись ни у кого, кроме автора: клиент не знал,
     * у какого пира есть истории.
     */
    static TL_stories.TL_stories_allStories all(int account, long selfId) throws WaysGramApi.ApiException {
        JSONObject data = WaysGramApi.get(account, "/api/stories/all");
        TL_stories.TL_stories_allStories out = new TL_stories.TL_stories_allStories();
        out.count = data.optInt("count");
        out.state = "waysgram-" + out.count + "-" + (System.currentTimeMillis() / 60000L);
        JSONArray peers = data.optJSONArray("peers");
        for (int i = 0; peers != null && i < peers.length(); i++) {
            JSONObject row = peers.optJSONObject(i);
            if (row == null) continue;
            String scope = row.optString("scope", "channel");
            long serverId = row.optLong("id");
            if (serverId <= 0) continue;
            long peerId = "channel".equals(scope) ? WaysGramChats.channelTg(serverId) : serverId;
            TL_stories.TL_peerStories peerStories = new TL_stories.TL_peerStories();
            peerStories.peer = peer(peerId);
            peerStories.max_read_id = row.optInt("max_read_id", 0);
            if (peerStories.max_read_id > 0) peerStories.flags |= TLObject.FLAG_1;
            JSONArray stories = row.optJSONArray("stories");
            for (int j = 0; stories != null && j < stories.length(); j++) {
                JSONObject story = stories.optJSONObject(j);
                if (story != null) peerStories.stories.add(item(account, peerId, story, selfId));
            }
            if (peerStories.stories.isEmpty()) continue;
            out.peer_stories.add(peerStories);
            out.chats.add(chat(account, scope, serverId, selfId));
        }
        return out;
    }

    static TLObject handle(int account, TLObject req, long selfId) throws WaysGramApi.ApiException {
        if (req instanceof TL_stories.TL_stories_getAllStories) {
            return all(account, selfId);
        }
        if (req instanceof TL_stories.TL_stories_canSendStory) {
            TLRPC.InputPeer peer = ((TL_stories.TL_stories_canSendStory) req).peer;
            if (!isChat(peer)) return null;
            TL_stories.canSendStoryCount out = new TL_stories.canSendStoryCount();
            out.count_remains = WaysGramApi.get(account, path(peer.channel_id) + "/can-post").optInt("count_remains");
            if (out.count_remains <= 0) throw new WaysGramApi.ApiException(429, "STORIES_TOO_MUCH");
            return out;
        }
        if (req instanceof TL_stories.TL_stories_sendStory) {
            TL_stories.TL_stories_sendStory send = (TL_stories.TL_stories_sendStory) req;
            if (!isChat(send.peer)) return null;
            if (send.fwd_from_id != null || send.music != null || (send.albums != null && !send.albums.isEmpty()) || !send.media_areas.isEmpty()) {
                throw new WaysGramApi.ApiException(400, "STORY_OPTION_NOT_SUPPORTED");
            }
            for (TLRPC.InputPrivacyRule rule : send.privacy_rules) {
                if (!(rule instanceof TLRPC.TL_inputPrivacyValueAllowAll)) {
                    throw new WaysGramApi.ApiException(400, "STORY_PRIVACY_NOT_SUPPORTED");
                }
            }
            long channelId = send.peer.channel_id;
            JSONObject upload = WaysGramMedia.uploadFor(account, send.media);
            if (upload == null) throw new WaysGramApi.ApiException(400, "MEDIA_NOT_SUPPORTED");
            JSONObject body = new JSONObject();
            try {
                body.put("media_id", upload.optString("id")).put("caption", send.caption == null ? "" : send.caption)
                        .put("period", send.period == 0 ? 86400 : send.period).put("random_id", send.random_id)
                        .put("pinned", send.pinned).put("noforwards", send.noforwards);
            } catch (JSONException e) { throw new WaysGramApi.ApiException(400, "STORY_INVALID"); }
            TL_stories.TL_updateStory update = new TL_stories.TL_updateStory();
            update.peer = peer(channelId);
            update.story = item(account, channelId, WaysGramApi.post(account, path(channelId), body), selfId);
            TL_update.TL_updateStoryID mapping = new TL_update.TL_updateStoryID();
            mapping.id = update.story.id;
            mapping.random_id = send.random_id;
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = update.story.date;
            updates.updates.add(update);
            updates.updates.add(mapping);
            return updates;
        }
        if (req instanceof TL_stories.TL_stories_getPeerStories) {
            TLRPC.InputPeer peer = ((TL_stories.TL_stories_getPeerStories) req).peer;
            if (!isChat(peer)) return null;
            TL_stories.TL_stories_peerStories out = new TL_stories.TL_stories_peerStories();
            out.stories = active(account, peer.channel_id, selfId);
            return out;
        }
        if (req instanceof TL_stories.TL_stories_getStoriesByID) {
            TL_stories.TL_stories_getStoriesByID byId = (TL_stories.TL_stories_getStoriesByID) req;
            if (!isChat(byId.peer)) return null;
            if (byId.id.isEmpty()) return new TL_stories.TL_stories_stories();
            return list(account, byId.peer.channel_id, selfId, "mode=ids&ids=" + android.text.TextUtils.join(",", byId.id));
        }
        if (req instanceof TL_stories.TL_stories_getPinnedStories) {
            TL_stories.TL_stories_getPinnedStories pinned = (TL_stories.TL_stories_getPinnedStories) req;
            if (!isChat(pinned.peer)) return null;
            return list(account, pinned.peer.channel_id, selfId, "mode=pinned&offset=" + pinned.offset_id + "&limit=" + Math.max(1, Math.min(100, pinned.limit)));
        }
        if (req instanceof TL_stories.TL_stories_getStoriesArchive) {
            TL_stories.TL_stories_getStoriesArchive archive = (TL_stories.TL_stories_getStoriesArchive) req;
            if (!isChat(archive.peer)) return null;
            return list(account, archive.peer.channel_id, selfId, "mode=archive&offset=" + archive.offset_id + "&limit=" + Math.max(1, Math.min(100, archive.limit)));
        }
        if (req instanceof TL_stories.TL_stories_deleteStories) {
            TL_stories.TL_stories_deleteStories delete = (TL_stories.TL_stories_deleteStories) req;
            if (!isChat(delete.peer)) return null;
            JSONObject body = new JSONObject();
            try { body.put("ids", new JSONArray(delete.id)); }
            catch (JSONException e) { throw new WaysGramApi.ApiException(400, "STORY_INVALID"); }
            JSONArray removed = WaysGramApi.post(account, path(delete.peer.channel_id) + "/delete", body).optJSONArray("ids");
            Vector<Vector.Int> out = new Vector<>(Vector.Int::TLDeserialize);
            for (int i = 0; removed != null && i < removed.length(); i++) out.objects.add(new Vector.Int(removed.optInt(i)));
            return out;
        }
        return null;
    }
}
