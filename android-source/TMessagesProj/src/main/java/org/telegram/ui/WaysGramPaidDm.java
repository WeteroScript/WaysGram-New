package org.telegram.ui;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WaysGramPaidDm {
    private WaysGramPaidDm() {}

    private static String text(int resource) { return LocaleController.getString(resource); }

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

    public interface FeeSink {
        void onFee(int stars, ArrayList<Long> users);
    }

    private static final java.util.HashMap<Integer, Integer> feeStars = new java.util.HashMap<>();
    private static final java.util.HashMap<Integer, ArrayList<Long>> feeUsers = new java.util.HashMap<>();

    private static void storeFee(int account, int stars, ArrayList<Long> users) {
        synchronized (feeStars) {
            feeStars.put(account, stars);
            feeUsers.put(account, new ArrayList<>(users));
        }
    }

    public static boolean hasCachedFee(int account) {
        synchronized (feeStars) {
            return feeStars.containsKey(account);
        }
    }

    public static int cachedFeeStars(int account) {
        synchronized (feeStars) {
            Integer value = feeStars.get(account);
            return value == null ? 0 : value;
        }
    }

    public static ArrayList<Long> cachedFeeUsers(int account) {
        synchronized (feeStars) {
            ArrayList<Long> value = feeUsers.get(account);
            return value == null ? new ArrayList<>() : new ArrayList<>(value);
        }
    }

    private static ArrayList<Long> parseUsers(JSONObject data) {
        ArrayList<Long> ids = new ArrayList<>();
        JSONArray exempt = data == null ? null : data.optJSONArray("exempt_users");
        for (int i = 0; exempt != null && i < exempt.length(); i++) {
            long id = exempt.optLong(i);
            if (id > 0 && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    public static void loadFee(int account, FeeSink sink) {
        String token = WaysGramApi.getToken(account);
        if (TextUtils.isEmpty(token)) {
            return;
        }
        WaysGramApi.NETWORK.execute(() -> {
            try {
                JSONObject data = WaysGramApi.request("GET", "/api/me/message-fee", null, token, 10000);
                int stars = Math.max(0, data.optInt("stars"));
                ArrayList<Long> ids = parseUsers(data);
                storeFee(account, stars, ids);
                AndroidUtilities.runOnUIThread(() -> sink.onFee(stars, new ArrayList<>(ids)));
            } catch (Exception ignored) {
            }
        });
    }

    public static void saveFee(int account, int stars, ArrayList<Long> users, Runnable done, Runnable fail) {
        String token = WaysGramApi.getToken(account);
        ArrayList<Long> clean = new ArrayList<>();
        if (users != null) {
            for (Long id : users) {
                if (id != null && id > 0 && !clean.contains(id)) {
                    clean.add(id);
                }
            }
        }
        final int fee = Math.max(0, Math.min(10000, stars));
        if (TextUtils.isEmpty(token)) {
            if (fail != null) {
                fail.run();
            }
            return;
        }
        WaysGramApi.NETWORK.execute(() -> {
            try {
                WaysGramApi.request("PUT", "/api/me/message-fee",
                        new JSONObject().put("stars", fee).put("exempt_users", new JSONArray(clean)), token, 10000);
                storeFee(account, fee, clean);
                if (done != null) {
                    AndroidUtilities.runOnUIThread(done);
                }
            } catch (Exception e) {
                if (fail != null) {
                    AndroidUtilities.runOnUIThread(fail);
                }
            }
        });
    }
}
