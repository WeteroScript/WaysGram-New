package org.telegram.ui;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.atomic.AtomicBoolean;

public final class WaysGramPaidDm {
    private WaysGramPaidDm() {}

    private static boolean active(BaseFragment fragment, int account, String token) {
        return fragment != null && fragment.getParentActivity() != null && fragment.getCurrentAccount() == account
                && !TextUtils.isEmpty(token) && TextUtils.equals(token, WaysGramApi.getToken(account));
    }

    /**
     * Stock Telegram paid-message confirmation. WaysGram only appends the internal
     * debit to the confirmed callback (see WaysGramTransport.confirmPaidText); all
     * UI strings, balance checks and "don't ask again" behaviour are Telegram's own.
     */
    public static void confirm(int account, long peer, String token, int fee, boolean retry, Runnable approved, Runnable cancelled) {
        AndroidUtilities.runOnUIThread(() -> {
            BaseFragment fragment = LaunchActivity.getLastFragment();
            if (!active(fragment, account, token) || UserConfig.selectedAccount != account
                    || !(fragment instanceof ChatActivity) || ((ChatActivity) fragment).getDialogId() != peer) {
                cancelled.run();
                return;
            }
            AtomicBoolean finished = new AtomicBoolean();
            Runnable reject = () -> { if (finished.compareAndSet(false, true)) cancelled.run(); };
            org.telegram.ui.Components.AlertsCreator.showPayForMessageAlert(account, peer, Math.max(0, fee), 1, () -> {
                if (!active(fragment, account, token) || UserConfig.selectedAccount != account
                        || LaunchActivity.getLastFragment() != fragment) {
                    reject.run();
                } else if (finished.compareAndSet(false, true)) {
                    approved.run();
                }
            });
        });
    }

    private static String peerKey(int account, long peer) {
        return account + ":" + peer;
    }

    public static int peerFeeStars(int account, long peer) {
        if (peer <= 0) {
            return 0;
        }
        synchronized (peerFees) {
            Long fetched = peerFetched.get(peerKey(account, peer));
            if (fetched == null || System.currentTimeMillis() - fetched > PEER_FEE_TTL) {
                return 0;
            }
            Integer value = peerFees.get(peerKey(account, peer));
            return value == null ? 0 : value;
        }
    }

    /** Загружает цену сообщения в этот чат и сообщает, если она изменилась. */
    public static void loadPeerFee(int account, long peer, Runnable changed) {
        if (peer <= 0) {
            return;
        }
        String token = WaysGramApi.getToken(account);
        if (TextUtils.isEmpty(token)) {
            return;
        }
        final String key = peerKey(account, peer);
        final int before;
        synchronized (peerFees) {
            Integer cached = peerFees.get(key);
            before = cached == null ? 0 : cached;
        }
        WaysGramApi.NETWORK.execute(() -> {
            try {
                JSONObject data = WaysGramApi.request("GET", "/api/chats/" + peer + "/message-fee", null, token, 10000);
                final int stars = Math.max(0, Math.min(10000, data.optInt("stars")));
                synchronized (peerFees) {
                    peerFees.put(key, stars);
                    peerFetched.put(key, System.currentTimeMillis());
                }
                if (stars != before && changed != null) {
                    AndroidUtilities.runOnUIThread(changed);
                }
            } catch (Exception ignored) {
            }
        });
    }
}
