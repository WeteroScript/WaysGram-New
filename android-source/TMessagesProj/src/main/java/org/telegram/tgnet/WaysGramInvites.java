package org.telegram.tgnet;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

public final class WaysGramInvites {
    private static String path(String hash) throws WaysGramApi.ApiException {
        if (hash != null && hash.matches("wg/(groups|channels)/[A-Za-z0-9_-]{20,128}")) {
            String[] parts = hash.split("/");
            return "/api/" + parts[1] + "/invites/" + parts[2];
        }
        throw new WaysGramApi.ApiException(400, "INVITE_HASH_INVALID");
    }

    static TLObject preview(int account, String hash) throws WaysGramApi.ApiException {
        String path = path(hash);
        JSONObject json;
        try {
            json = WaysGramApi.get(account, path);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 404 || e.status == 410) throw new WaysGramApi.ApiException(400, "INVITE_HASH_EXPIRED");
            throw e;
        }
        TLRPC.TL_chatInvite invite = new TLRPC.TL_chatInvite();
        invite.title = json.optString("title");
        invite.participants_count = json.optInt("member_count");
        invite.channel = invite.broadcast = path.startsWith("/api/channels/");
        invite.request_needed = json.optBoolean("require_approval");
        invite.photo = new TLRPC.TL_photoEmpty();
        return invite;
    }

    static TLObject join(int account, String hash, long selfId) throws WaysGramApi.ApiException {
        String path = path(hash);
        boolean channel = path.startsWith("/api/channels/");
        String base = channel ? "/api/channels" : "/api/groups";
        JSONObject body = new JSONObject();
        try { body.put("invite_token", path.substring(path.lastIndexOf('/') + 1)); }
        catch (JSONException e) { throw new WaysGramApi.ApiException(400, "INVITE_HASH_INVALID"); }
        JSONObject joined;
        try { joined = WaysGramApi.post(account, base + "/join", body); }
        catch (WaysGramApi.ApiException e) {
            if (e.status == 404 || e.status == 410) throw new WaysGramApi.ApiException(400, "INVITE_HASH_EXPIRED");
            throw e;
        }
        if (joined.optBoolean("join_requested")) throw new WaysGramApi.ApiException(400, "INVITE_REQUEST_SENT");
        long id = joined.optLong("id");
        JSONObject chat = WaysGramApi.get(account, base + "/" + id);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        if (channel) {
            WaysGramChats.setJoined(account, id, true);
            updates.chats.add(WaysGramChats.toChannel(account, chat, selfId, true));
        } else {
            updates.chats.add(WaysGramChats.toMegagroup(account, chat, selfId));
        }
        TLRPC.TL_chatInviteJoinResultOk result = new TLRPC.TL_chatInviteJoinResultOk();
        result.updates = updates;
        return result;
    }

    private static String inviteBase(TLRPC.InputPeer peer) throws WaysGramApi.ApiException {
        if (WaysGramChats.isGroup(peer)) return "/api/groups/" + WaysGramChats.groupPeerId(peer) + "/invites";
        if (peer instanceof TLRPC.TL_inputPeerChannel) return "/api/channels/" + WaysGramChats.channelServer(peer.channel_id) + "/invites";
        throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
    }

    static TLObject exportGroup(int account, TLRPC.TL_messages_exportChatInvite request, long selfId) throws WaysGramApi.ApiException {
        if (WaysGramChats.isGroup(request.peer) && request.legacy_revoke_permanent
                && request.expire_date == 0 && request.usage_limit == 0 && !request.request_needed
                && request.title == null && request.subscription_pricing == null) {
            return groupPermanentInvite(account, WaysGramChats.groupPeerId(request.peer), selfId, true);
        }
        if (request.peer instanceof TLRPC.TL_inputPeerChannel && request.legacy_revoke_permanent
                && request.expire_date == 0 && request.usage_limit == 0 && !request.request_needed
                && request.title == null && request.subscription_pricing == null) {
            return channelInvite(account, request.peer.channel_id, selfId, true);
        }
        if (request.subscription_pricing != null
                || request.legacy_revoke_permanent) throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
        int now = (int) (System.currentTimeMillis() / 1000);
        JSONObject body = new JSONObject();
        try {
            body.put("require_approval", request.request_needed);
            if (request.title != null) body.put("label", request.title);
            body.put("expires_in_seconds", request.expire_date == 0 ? JSONObject.NULL : (long) request.expire_date - now);
            if (request.usage_limit > 0) body.put("max_uses", request.usage_limit);
        } catch (JSONException e) { throw new WaysGramApi.ApiException(400, "INVITE_HASH_INVALID"); }
        JSONObject data = WaysGramApi.post(account, inviteBase(request.peer), body);
        TLRPC.TL_chatInviteExported result = new TLRPC.TL_chatInviteExported();
        result.link = WaysGramLinks.canonicalShareLink(data.optString("invite_link"));
        result.admin_id = selfId;
        result.date = now;
        result.expire_date = data.optInt("expires_at");
        result.usage_limit = data.optInt("max_uses");
        result.request_needed = data.optBoolean("require_approval");
        if (result.expire_date != 0) result.flags |= 2;
        if (result.usage_limit != 0) result.flags |= 4;
        applyLabel(result, data);
        return result;
    }
    public static TLRPC.TL_chatInviteExported groupPermanentInvite(int account, long groupId, long selfId, boolean rotate) throws WaysGramApi.ApiException {
        JSONObject data = WaysGramApi.post(account, "/api/groups/" + groupId + "/permanent-invite?rotate=" + rotate, new JSONObject());
        TLRPC.TL_chatInviteExported invite = new TLRPC.TL_chatInviteExported();
        invite.link = WaysGramLinks.canonicalShareLink(data.optString("invite_link"));
        invite.date = data.optInt("created_at");
        invite.admin_id = selfId;
        invite.permanent = true;
        return invite;
    }

    public static TLRPC.TL_chatInviteExported channelInvite(int account, long channelId, long selfId, boolean rotate) throws WaysGramApi.ApiException {
        String path = "/api/channels/" + WaysGramChats.channelServer(channelId) + "/invite";
        JSONObject data = rotate ? WaysGramApi.post(account, path, new JSONObject()) : WaysGramApi.get(account, path);
        TLRPC.TL_chatInviteExported invite = new TLRPC.TL_chatInviteExported();
        invite.link = WaysGramLinks.canonicalShareLink(data.optString("invite_link"));
        invite.date = data.optInt("created_at");
        invite.admin_id = selfId;
        invite.permanent = true;
        return invite;
    }

    static TLObject revokeGroup(int account, TLRPC.TL_messages_editExportedChatInvite request) throws WaysGramApi.ApiException {
        if (request.revoked && (request.flags & ~TLObject.FLAG_2) != 0) throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
        android.net.Uri uri = android.net.Uri.parse(request.link == null ? "" : request.link);
        String path = uri.getPath();
        String scope = WaysGramChats.isGroup(request.peer) ? "groups" : "channels";
        if (path == null || !path.matches("/api/" + scope + "/invites/[A-Za-z0-9_-]{20,128}")) {
            throw new WaysGramApi.ApiException(400, "INVITE_HASH_INVALID");
        }
        String endpoint = inviteBase(request.peer) + "/" + uri.getLastPathSegment();
        JSONObject data;
        if (request.revoked) {
            data = WaysGramApi.delete(account, endpoint);
        } else {
            JSONObject body = new JSONObject();
            try {
                if ((request.flags & TLObject.FLAG_0) != 0) body.put("expires_at", request.expire_date == 0 ? JSONObject.NULL : request.expire_date);
                if ((request.flags & TLObject.FLAG_1) != 0) body.put("max_uses", request.usage_limit == 0 ? JSONObject.NULL : request.usage_limit);
                if ((request.flags & TLObject.FLAG_3) != 0) body.put("require_approval", request.request_needed);
                if ((request.flags & TLObject.FLAG_4) != 0) body.put("label", request.title == null ? "" : request.title);
            } catch (JSONException e) { throw new WaysGramApi.ApiException(400, "INVITE_HASH_INVALID"); }
            data = WaysGramApi.patch(account, endpoint, body);
        }
        TLRPC.TL_chatInviteExported invite = exported(data, request.link, request.revoked);
        TLRPC.TL_messages_exportedChatInvite result = new TLRPC.TL_messages_exportedChatInvite();
        result.invite = invite;
        return result;
    }

    /** Сколько ссылок создал каждый администратор: список для экрана «Приглашения». */
    static TLRPC.TL_messages_chatAdminsWithInvites adminsWithInvites(int account, TLRPC.InputPeer peer)
            throws WaysGramApi.ApiException {
        String base = inviteBase(peer);
        JSONObject data = WaysGramApi.get(account, base + "/admins-with-invites");
        TLRPC.TL_messages_chatAdminsWithInvites out = new TLRPC.TL_messages_chatAdminsWithInvites();
        JSONArray admins = data.optJSONArray("admins");
        for (int i = 0; admins != null && i < admins.length(); i++) {
            JSONObject row = admins.optJSONObject(i);
            if (row == null || row.optLong("telegram_id") <= 0) {
                continue;
            }
            TLRPC.TL_chatAdminWithInvites item = new TLRPC.TL_chatAdminWithInvites();
            item.admin_id = row.optLong("telegram_id");
            item.invites_count = row.optInt("invites_count");
            item.revoked_invites_count = row.optInt("revoked_invites_count");
            out.admins.add(item);
            out.users.add(WaysGramMapper.toUser(row));
        }
        return out;
    }

    private static void applyLabel(TLRPC.TL_chatInviteExported invite, JSONObject data) {
        invite.title = data.optString("label", "");
        if (!invite.title.isEmpty()) invite.flags |= TLObject.FLAG_8;
    }

    private static TLRPC.TL_chatInviteExported exported(JSONObject data, String link, boolean revoked) {
        TLRPC.TL_chatInviteExported invite = new TLRPC.TL_chatInviteExported();
        invite.link = WaysGramLinks.canonicalShareLink(link);
        invite.revoked = revoked;
        invite.admin_id = data.optLong("created_by");
        invite.date = data.optInt("created_at");
        invite.expire_date = data.optInt("expires_at");
        invite.usage_limit = data.optInt("max_uses");
        invite.usage = data.optInt("uses");
        invite.request_needed = data.optBoolean("require_approval") || data.optInt("require_approval") == 1;
        if (invite.expire_date != 0) invite.flags |= TLObject.FLAG_1;
        if (invite.usage_limit != 0) invite.flags |= TLObject.FLAG_2;
        if (invite.usage != 0) invite.flags |= TLObject.FLAG_3;
        applyLabel(invite, data);
        return invite;
    }

    static TLObject deleteRevoked(int account, TLRPC.InputPeer peer, String link, TLRPC.InputUser admin, long selfId) throws WaysGramApi.ApiException {
        String scope = WaysGramChats.isGroup(peer) ? "groups" : "channels";
        String base = inviteBase(peer);
        String endpoint = base.substring(0, base.lastIndexOf('/')) + "/revoked-invites";
        if (link != null) {
            android.net.Uri uri = android.net.Uri.parse(link);
            if (uri.getPath() == null || !uri.getPath().matches("/api/" + scope + "/invites/[A-Za-z0-9_-]{20,128}")) {
                throw new WaysGramApi.ApiException(400, "INVITE_HASH_INVALID");
            }
            endpoint += "?invite_token=" + uri.getLastPathSegment();
        } else {
            long id = admin == null || admin instanceof TLRPC.TL_inputUserSelf ? selfId : admin.user_id;
            endpoint += "?admin_id=" + id;
        }
        WaysGramApi.delete(account, endpoint);
        return new TLRPC.TL_boolTrue();
    }

    static TLObject listLinks(int account, TLRPC.TL_messages_getExportedChatInvites request, long selfId) throws WaysGramApi.ApiException {
        long admin = request.admin_id instanceof TLRPC.TL_inputUserSelf || request.admin_id == null ? selfId : request.admin_id.user_id;
        String endpoint = inviteBase(request.peer) + "?admin_id=" + admin + "&revoked=" + request.revoked + "&limit=" + request.limit;
        if (request.offset_link != null && !request.offset_link.isEmpty()) {
            endpoint += "&after=" + android.net.Uri.encode(android.net.Uri.parse(request.offset_link).getLastPathSegment());
        }
        JSONObject data = WaysGramApi.get(account, endpoint);
        TLRPC.TL_messages_exportedChatInvites result = new TLRPC.TL_messages_exportedChatInvites();
        result.count = data.optInt("count");
        JSONArray links = data.optJSONArray("invites");
        for (int i = 0; links != null && i < links.length(); i++) {
            JSONObject row = links.optJSONObject(i);
            if (row != null) result.invites.add(exported(row, row.optString("invite_link"), request.revoked));
        }
        return result;
    }

    static TLObject requests(int account, TLRPC.TL_messages_getChatInviteImporters request) throws WaysGramApi.ApiException {
        if (!request.requested) {
            throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
        }
        long offsetUser = request.offset_user == null ? 0 : request.offset_user.user_id;
        String base = inviteBase(request.peer);
        String scope = WaysGramChats.isGroup(request.peer) ? "groups" : "channels";
        String endpoint = base.substring(0, base.lastIndexOf('/')) + "/join-requests?after_date=" + request.offset_date
                + "&after_user=" + offsetUser + "&limit=" + request.limit;
        if (request.q != null) endpoint += "&q=" + android.net.Uri.encode(request.q);
        if (request.link != null && !request.link.isEmpty()) {
            android.net.Uri uri = android.net.Uri.parse(request.link);
            String path = uri.getPath();
            if (path == null || !path.matches("/api/" + scope + "/invites/[A-Za-z0-9_-]{20,128}")) {
                throw new WaysGramApi.ApiException(400, "INVITE_HASH_INVALID");
            }
            endpoint += "&invite_token=" + uri.getLastPathSegment();
        }
        JSONObject data = WaysGramApi.get(account, endpoint);
        JSONArray rows = data.optJSONArray("requests");
        TLRPC.TL_messages_chatInviteImporters result = new TLRPC.TL_messages_chatInviteImporters();
        result.count = data.optInt("count");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            long id = row.optLong("telegram_id");
            int date = row.optInt("requested_at");
            TLRPC.TL_chatInviteImporter importer = new TLRPC.TL_chatInviteImporter();
            importer.requested = true;
            importer.user_id = id;
            importer.date = date;
            result.importers.add(importer);
            result.users.add(WaysGramMapper.toUser(row));
        }
        return result;
    }

    static TLObject review(int account, TLRPC.TL_messages_hideChatJoinRequest request, long selfId) throws WaysGramApi.ApiException {
        String base = inviteBase(request.peer);
        String path = base.substring(0, base.lastIndexOf('/'));
        WaysGramApi.post(account, path + "/join-requests/" + request.user_id.user_id
                + (request.approved ? "/approve" : "/decline"), new JSONObject());
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        JSONObject chat = WaysGramApi.get(account, path);
        if (WaysGramChats.isGroup(request.peer)) {
            result.chats.add(WaysGramChats.toMegagroup(account, chat, selfId));
        } else {
            result.chats.add(WaysGramChats.toChannel(account, chat, selfId, true));
        }
        return result;
    }
}
