package org.telegram.tgnet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.telegram.tgnet.tl.TL_account;

final class WaysGramMusic {
    private static JSONObject load(int account, long user, int offset, int limit) throws WaysGramApi.ApiException {
        return WaysGramApi.get(account, "/api/users/" + user + "/music?offset=" + offset + "&limit=" + limit);
    }

    private static TLRPC.Document document(JSONObject row) {
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = WaysGramMedia.register(row.optString("media_id"));
        doc.access_hash = doc.id;
        doc.file_reference = new byte[0];
        doc.mime_type = row.optString("media_type", "audio/mpeg");
        doc.size = row.optLong("size_bytes");
        doc.dc_id = 2;
        TLRPC.TL_documentAttributeFilename file = new TLRPC.TL_documentAttributeFilename();
        file.file_name = row.optString("media_name", "audio");
        doc.attributes.add(file);
        TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
        try {
            JSONObject metadata = new JSONObject(row.optString("media_metadata", "{}"));
            audio.duration = Math.max(0, metadata.optDouble("duration", 0));
            audio.title = metadata.optString("title", "");
            audio.performer = metadata.optString("performer", "");
            if (!audio.title.isEmpty()) audio.flags |= 1;
            if (!audio.performer.isEmpty()) audio.flags |= 2;
        } catch (JSONException ignored) {}
        doc.attributes.add(audio);
        return doc;
    }

    static TLRPC.TL_savedMusic list(int account, long user, int offset, int limit) throws WaysGramApi.ApiException {
        JSONObject json = load(account, user, offset, limit);
        TLRPC.TL_savedMusic result = new TLRPC.TL_savedMusic();
        result.count = json.optInt("count");
        JSONArray rows = json.optJSONArray("tracks");
        for (int i = 0; rows != null && i < rows.length(); i++) result.documents.add(document(rows.optJSONObject(i)));
        return result;
    }

    static TL_account.TL_savedMusicIds ids(int account, long self) throws WaysGramApi.ApiException {
        TL_account.TL_savedMusicIds result = new TL_account.TL_savedMusicIds();
        JSONArray rows = load(account, self, 0, 100).optJSONArray("tracks");
        for (int i = 0; rows != null && i < rows.length(); i++) result.ids.add(WaysGramMedia.register(rows.optJSONObject(i).optString("media_id")));
        return result;
    }

    static TLObject save(int account, TLRPC.TL_account_saveMusic request) throws WaysGramApi.ApiException {
        String media = request.id == null ? null : WaysGramMedia.mediaIdFor(request.id.id);
        String after = request.after_id == null ? null : WaysGramMedia.mediaIdFor(request.after_id.id);
        if (media == null || (request.after_id != null && after == null)) throw new WaysGramApi.ApiException(400, "MEDIA_INVALID");
        JSONObject json = new JSONObject();
        try {
            json.put("media_id", media);
            json.put("remove", request.unsave);
            if (after != null) json.put("after_id", after);
        } catch (JSONException e) { throw new WaysGramApi.ApiException(400, "MEDIA_INVALID"); }
        WaysGramApi.put(account, "/api/me/music", json);
        return new TLRPC.TL_boolTrue();
    }
}
