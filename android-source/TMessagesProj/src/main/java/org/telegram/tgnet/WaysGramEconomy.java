package org.telegram.tgnet;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_stats;

/**
 * Native Stars adapters.
 *
 * <p>The account wallet is loaded only for {@code inputPeerSelf}. A channel/group
 * transaction request reads that peer's own chat_stars ledger instead of silently
 * substituting the signed-in user's {@code /api/me/wallet}.</p>
 */
public class WaysGramEconomy {

    private static final long GRAM_UNIT = 1_000_000_000L;

    private static boolean isSelfPeer(TLRPC.InputPeer peer, long selfId) {
        return peer instanceof TLRPC.TL_inputPeerSelf
                || peer instanceof TLRPC.TL_inputPeerUser && peer.user_id == selfId;
    }

    private static String starsPath(TLRPC.InputPeer peer, long selfId) throws WaysGramApi.ApiException {
        if (peer == null || isSelfPeer(peer, selfId)) {
            throw new WaysGramApi.ApiException(400, "STARS_CHAT_PEER_REQUIRED");
        }
        if (WaysGramChats.isGroup(peer)) {
            long groupId = WaysGramChats.groupPeerId(peer);
            if (groupId > 0) {
                return "/api/groups/" + groupId + "/stars";
            }
        }
        if (peer instanceof TLRPC.TL_inputPeerChannel) {
            long channelId = WaysGramChats.channelServer(peer.channel_id);
            if (!WaysGramChats.isGroupChannel(peer.channel_id) && channelId > 0) {
                return "/api/channels/" + channelId + "/stars";
            }
        }
        throw new WaysGramApi.ApiException(400, "STARS_PEER_UNSUPPORTED");
    }

    private static long offsetId(String offset) throws WaysGramApi.ApiException {
        if (TextUtils.isEmpty(offset)) return 0;
        try {
            long parsed = Long.parseLong(offset);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new WaysGramApi.ApiException(400, "STARS_OFFSET_INVALID");
        }
    }

    private static String historyPath(String base, boolean ton, String offset, int limit,
                                      boolean inbound, boolean outbound, boolean ascending)
            throws WaysGramApi.ApiException {
        String cursor = String.valueOf(offsetId(offset));
        StringBuilder path = new StringBuilder(base);
        if (base.startsWith("/api/me/")) {
            path.append("?asset=").append(ton ? "grams" : "stars").append('&');
        } else {
            path.append('?');
        }
        path.append("offset_id=").append(cursor)
                .append("&limit=").append(Math.max(1, Math.min(100, limit)));
        if (inbound) path.append("&inbound=true");
        if (outbound) path.append("&outbound=true");
        if (ascending) path.append("&ascending=true");
        return path.toString();
    }

    private static JSONObject loadPeerStars(int account, TLRPC.InputPeer peer, long selfId,
                                             String offset, int limit, boolean inbound,
                                             boolean outbound, boolean ascending)
            throws WaysGramApi.ApiException {
        String base = starsPath(peer, selfId);
        return WaysGramApi.get(account,
                historyPath(base, false, offset, limit, inbound, outbound, ascending));
    }

    public static TL_stars.StarsStatus status(int account, TLRPC.InputPeer peer, long selfId,
                                               boolean ton, boolean withHistory, String offset,
                                               int limit, boolean inbound, boolean outbound,
                                               boolean ascending) throws WaysGramApi.ApiException {
        final boolean selfPeer = isSelfPeer(peer, selfId);
        final JSONObject balance;
        final JSONObject history;
        if (selfPeer) {
            if (peer == null) throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
            balance = WaysGramApi.get(account, "/api/me/wallet");
            history = withHistory
                    ? WaysGramApi.get(account, historyPath("/api/me/wallet/transactions", ton,
                            offset, limit, inbound, outbound, ascending))
                    : null;
        } else {
            if (ton) {
                throw new WaysGramApi.ApiException(400, "TON_WALLET_UNAVAILABLE");
            }
            history = withHistory
                    ? loadPeerStars(account, peer, selfId, offset, limit, inbound, outbound, ascending)
                    : null;
            balance = history != null ? history
                    : loadPeerStars(account, peer, selfId, "", 1, false, false, false);
        }

        TL_stars.TL_payments_starsStatus status = new TL_stars.TL_payments_starsStatus();
        status.balance = amount(ton, ton ? balance.optLong("grams") : balance.optLong("stars"));
        if (withHistory) {
            JSONArray rows = history == null ? null : history.optJSONArray("transactions");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) status.history.add(transaction(row, ton));
            }
            String next = history == null ? "" : history.optString("next_offset", "");
            if (!TextUtils.isEmpty(next)) {
                status.next_offset = next;
                status.flags |= TLObject.FLAG_0;
            }
            status.flags |= TLObject.FLAG_3; // history is present, including an empty page
        }
        return status;
    }

    /** Native revenue stats use the target chat's ledger and deliberately expose no withdrawal. */
    public static TLRPC.TL_payments_starsRevenueStats revenueStats(
            int account, TLRPC.TL_payments_getStarsRevenueStats request, long selfId)
            throws WaysGramApi.ApiException {
        if (request == null || request.peer == null) {
            throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        }
        if (request.ton) {
            throw new WaysGramApi.ApiException(400, "TON_REVENUE_UNAVAILABLE");
        }
        JSONObject data = WaysGramApi.get(account,
                historyPath(starsPath(request.peer, selfId), false, "", 1, false, false, false));
        TLRPC.TL_starsRevenueStatus status = new TLRPC.TL_starsRevenueStatus();
        long balance = Math.max(0, data.optLong("stars"));
        long revenue = Math.max(0, data.optLong("overall_revenue"));
        status.current_balance = TL_stars.StarsAmount.ofStars(balance);
        status.available_balance = TL_stars.StarsAmount.ofStars(balance);
        status.overall_revenue = TL_stars.StarsAmount.ofStars(revenue);
        // WaysGram Stars are an internal chat balance, not a withdrawable Telegram payout.
        status.withdrawal_enabled = false;

        TLRPC.TL_payments_starsRevenueStats result = new TLRPC.TL_payments_starsRevenueStats();
        TL_stats.TL_statsGraphError unavailable = new TL_stats.TL_statsGraphError();
        unavailable.error = "WAYS_GRAM_REVENUE_GRAPH_UNAVAILABLE";
        result.revenue_graph = unavailable;
        result.status = status;
        // There is no configured fiat quote for internal Stars; the UI uses XTR balances
        // and suppresses USD estimates when this value is zero.
        result.usd_rate = 0;
        return result;
    }

    private static TL_stars.StarsAmount amount(boolean ton, long value) {
        if (ton) {
            TL_stars.TL_starsTonAmount amount = new TL_stars.TL_starsTonAmount();
            amount.amount = value * GRAM_UNIT;
            return amount;
        }
        return TL_stars.StarsAmount.ofStars(value);
    }

    private static TL_stars.TL_starsTransaction transaction(JSONObject row, boolean ton) {
        TL_stars.TL_starsTransaction transaction = new TL_stars.TL_starsTransaction();
        transaction.id = row.optString("id", "");
        if (TextUtils.isEmpty(transaction.id)) transaction.id = String.valueOf(row.optLong("id"));
        transaction.amount = amount(ton, row.optLong("amount"));
        transaction.date = row.optInt("created_at");
        transaction.peer = new TL_stars.TL_starsTransactionPeerUnsupported();
        String reason = row.optString("reason", "");
        String title = transactionTitle(reason);
        if (!TextUtils.isEmpty(title)) {
            transaction.title = title;
            transaction.flags |= TLObject.FLAG_0;
        }
        return transaction;
    }

    private static String transactionTitle(String reason) {
        if (reason == null || reason.isEmpty()) return "";
        if (reason.startsWith("channel_paid_reaction:")) return "Paid reaction";
        if (reason.startsWith("channel_paid_media:")) return "Paid media";
        if (reason.startsWith("channel_subscription:")) return "Channel subscription";
        if (reason.startsWith("channel_inbox_refund:")) return "Paid message refund";
        if (reason.startsWith("channel_inbox:")) return "Paid channel message";
        if (reason.startsWith("channel_sponsored_post:")) return "Sponsored post";
        if (reason.startsWith("group_message_fee:")) return "Paid group message";
        if (reason.startsWith("message_fee:")) return "Paid message";
        return reason;
    }
}
