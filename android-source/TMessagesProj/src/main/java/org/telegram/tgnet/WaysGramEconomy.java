package org.telegram.tgnet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_stars;

/**
 * Balances and history from the WaysGram server wallet (/api/me/wallet).
 * "stars" map to the Stars balance; server "grams" are shown where the app shows the Gram (TON) balance,
 * 1 gram = 1_000_000_000 units, which is how the app divides that balance for display.
 */
public class WaysGramEconomy {

    private static final long GRAM_UNIT = 1_000_000_000L;

    public static TL_stars.StarsStatus status(int account, boolean ton, boolean withHistory) throws WaysGramApi.ApiException {
        JSONObject wallet = WaysGramApi.get(account, "/api/me/wallet");
        TL_stars.TL_payments_starsStatus status = new TL_stars.TL_payments_starsStatus();
        status.balance = amount(ton, ton ? wallet.optLong("grams") : wallet.optLong("stars"));
        if (withHistory) {
            JSONObject tx = WaysGramApi.get(account, "/api/me/wallet/transactions");
            JSONArray rows = tx.optJSONArray("transactions");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) {
                        continue;
                    }
                    String asset = row.optString("asset", "");
                    boolean isGram = asset.toLowerCase().startsWith("gram");
                    if (isGram != ton) {
                        continue;
                    }
                    status.history.add(transaction(row, ton));
                }
            }
            status.flags |= TLObject.FLAG_3; // history present (flag value 8)
        }
        return status;
    }

    private static TL_stars.StarsAmount amount(boolean ton, long value) {
        if (ton) {
            TL_stars.TL_starsTonAmount a = new TL_stars.TL_starsTonAmount();
            a.amount = value * GRAM_UNIT;
            return a;
        }
        return TL_stars.StarsAmount.ofStars(value);
    }

    private static TL_stars.TL_starsTransaction transaction(JSONObject row, boolean ton) {
        TL_stars.TL_starsTransaction t = new TL_stars.TL_starsTransaction();
        t.id = String.valueOf(row.optLong("id"));
        t.amount = amount(ton, row.optLong("amount"));
        t.date = row.optInt("created_at");
        t.peer = new TL_stars.TL_starsTransactionPeerUnsupported();
        String reason = row.optString("reason", "");
        if (!reason.isEmpty()) {
            t.title = reason;
            t.flags |= TLObject.FLAG_0;
        }
        return t;
    }
}
