package org.telegram.ui;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;

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
            String description = retry ? LocaleController.formatString(R.string.WaysGramPaidDmRetry, fee)
                    : fee > 0 ? LocaleController.formatString(R.string.WaysGramPaidDmConfirm, fee) : text(R.string.WaysGramPaidDmNowFree);
            AlertDialog dialog = new AlertDialog.Builder(fragment.getParentActivity()).setTitle(text(R.string.WaysGramPaidDmTitle))
                    .setMessage(description)
                    .setNegativeButton(text(R.string.Cancel), (d, w) -> reject.run())
                    .setPositiveButton(text(retry ? R.string.WaysGramPaidRetry : R.string.Send), (d, w) -> {
                        if (!active(fragment, account, token) || UserConfig.selectedAccount != account
                                || LaunchActivity.getLastFragment() != fragment) {
                            reject.run();
                        } else if (finished.compareAndSet(false, true)) {
                            approved.run();
                        }
                    }).create();
            if (fragment.showDialog(dialog, ignored -> reject.run()) == null) reject.run();
        });
    }

    static void settings(BaseFragment fragment, int account) {
        String token = WaysGramApi.getToken(account);
        if (!active(fragment, account, token)) return;
        WaysGramApi.NETWORK.execute(() -> {
            try {
                JSONObject data = WaysGramApi.request("GET", "/api/me/message-fee", null, token, 10000);
                JSONArray exempt = data.optJSONArray("exempt_users");
                ArrayList<Long> ids = new ArrayList<>();
                for (int i = 0; exempt != null && i < exempt.length(); i++) if (exempt.optLong(i) > 0) ids.add(exempt.optLong(i));
                AndroidUtilities.runOnUIThread(() -> editSettings(fragment, account, token, data.optInt("stars"), ids));
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> { if (active(fragment, account, token)) notice(fragment, R.string.WaysGramPaidDmError); });
            }
        });
    }

    private static void editSettings(BaseFragment fragment, int account, String token, int stars, ArrayList<Long> ids) {
        if (!active(fragment, account, token)) return;
        EditText price = new EditText(fragment.getParentActivity());
        price.setInputType(InputType.TYPE_CLASS_NUMBER);
        price.setText(Integer.toString(stars));
        price.setHint("0–10000 Stars");
        String description = text(R.string.WaysGramPaidDmPrice) + "\n\n" + LocaleController.formatString(R.string.WaysGramPaidDmExceptionCount, ids.size());
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity()).setTitle(text(R.string.WaysGramPaidDmTitle)).setView(price)
                .setMessage(description).setNegativeButton(text(R.string.Cancel), null)
                .setNeutralButton(text(R.string.WaysGramPaidDmExceptions), (d, w) -> {
                    if (!active(fragment, account, token)) return;
                    int value = parsePrice(price);
                    if (value < 0) { notice(fragment, R.string.WaysGramPaidDmPrice); return; }
                    Bundle args = new Bundle();
                    args.putBoolean("isAlwaysShare", true);
                    args.putInt("chatAddType", 1);
                    GroupCreateActivity picker = new GroupCreateActivity(args);
                    picker.setTitle(text(R.string.WaysGramPaidDmExceptions));
                    picker.select(ids, false, false);
                    picker.setDelegate((premium, miniapps, selected) -> {
                        ArrayList<Long> clean = new ArrayList<>();
                        if (selected != null) for (Long id : selected) if (id != null && id > 0) clean.add(id);
                        AndroidUtilities.runOnUIThread(() -> editSettings(fragment, account, token, value, clean), 300);
                    });
                    fragment.presentFragment(picker);
                })
                .setPositiveButton(text(R.string.Save), (d, w) -> {
                    if (!active(fragment, account, token)) return;
                    int fee = parsePrice(price);
                    if (fee < 0 || ids.size() > 200) {
                        notice(fragment, fee < 0 ? R.string.WaysGramPaidDmPrice : R.string.WaysGramPaidDmExceptionLimit);
                        return;
                    }
                    WaysGramApi.NETWORK.execute(() -> {
                        int message = R.string.WaysGramPaidDmSaved;
                        try {
                            WaysGramApi.request("PUT", "/api/me/message-fee", new JSONObject().put("stars", fee)
                                    .put("exempt_users", new JSONArray(ids)), token, 10000);
                        } catch (Exception e) {
                            message = R.string.WaysGramPaidDmError;
                        }
                        final int result = message;
                        AndroidUtilities.runOnUIThread(() -> { if (active(fragment, account, token)) notice(fragment, result); });
                    });
                }).create());
    }

    private static int parsePrice(EditText field) {
        try {
            int price = Integer.parseInt(field.getText().toString().trim());
            return price >= 0 && price <= 10000 ? price : -1;
        } catch (NumberFormatException ignored) { return -1; }
    }

    private static void notice(BaseFragment fragment, int message) {
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity()).setTitle(text(R.string.WaysGramPaidDmTitle))
                .setMessage(text(message)).setPositiveButton(text(R.string.OK), null).create());
    }
}
