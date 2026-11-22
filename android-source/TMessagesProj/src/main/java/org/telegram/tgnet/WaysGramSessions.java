package org.telegram.tgnet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_account;

final class WaysGramSessions {
    private static final String PATH = "/api/auth/sessions";

    private static JSONArray sessions(int account) throws WaysGramApi.ApiException {
        return WaysGramApi.get(account, PATH).optJSONArray("sessions");
    }

    private static long handle(String id) {
        return new java.math.BigInteger(id.substring(0, 16), 16).longValue();
    }

    static TLObject list(int account) throws WaysGramApi.ApiException {
        TL_account.authorizations out = new TL_account.authorizations();
        JSONArray rows = sessions(account);
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("id");
            if (!id.matches("[a-f0-9]{32}")) continue;
            long hash = handle(id);
            if (hash == 0) continue;
            TLRPC.TL_authorization session = new TLRPC.TL_authorization();
            session.hash = hash;
            session.current = row.optBoolean("is_current");
            session.date_created = row.optInt("created_at");
            session.date_active = session.date_created;
            session.device_model = session.current ? android.os.Build.MODEL : "";
            session.platform = session.current ? "Android" : "";
            session.system_version = session.current ? android.os.Build.VERSION.RELEASE : "";
            session.app_name = "WaysGram";
            session.app_version = session.ip = session.country = session.region = "";
            out.authorizations.add(session);
        }
        return out;
    }

    static TLObject reset(int account, long hash) throws WaysGramApi.ApiException {
        JSONArray rows = sessions(account);
        String selected = null;
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("id");
            if (!id.matches("[a-f0-9]{32}") || handle(id) != hash) continue;
            if (selected != null || row.optBoolean("is_current")) {
                throw new WaysGramApi.ApiException(400, "HASH_INVALID");
            }
            selected = id;
        }
        if (selected == null || hash == 0) throw new WaysGramApi.ApiException(400, "HASH_INVALID");
        WaysGramApi.delete(account, PATH + "/" + selected);
        return new TLRPC.TL_boolTrue();
    }

    static TLObject resetOthers(int account) throws WaysGramApi.ApiException {
        WaysGramApi.post(account, PATH + "/revoke-others", new JSONObject());
        return new TLRPC.TL_boolTrue();
    }
}
