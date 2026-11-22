package org.telegram.ui;

import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.ArrayList;
import java.util.UUID;

final class WaysGramGroupIdentity {
    private interface Work { JSONObject run() throws Exception; }
    private interface Result { void accept(JSONObject response); }
    private interface Failed { void accept(int status); }

    private static String text(int id) { return LocaleController.getString(id); }
    private static String root(long group) { return "/api/groups/" + group; }
    private static boolean active(ChatActivity chat, int account, long group, String token) {
        return !chat.isFinished && chat.getParentActivity() != null && chat.getDialogId() == -group
                && chat.getCurrentAccount() == account && !TextUtils.isEmpty(token) && TextUtils.equals(token, WaysGramApi.getToken(account));
    }
    private static AlertDialog.Builder dialog(ChatActivity chat) {
        return new AlertDialog.Builder(chat.getParentActivity()).setTitle(text(R.string.WaysGramGroupIdentity));
    }
    private static void notice(ChatActivity chat, int message) {
        chat.showDialog(dialog(chat).setMessage(text(message)).setPositiveButton(text(R.string.OK), null).create());
    }
    private static void request(ChatActivity chat, int account, long group, String token, Work work, Result done, Failed failed) {
        if (!active(chat, account, group, token)) return;
        WaysGramApi.NETWORK.execute(() -> {
            try {
                JSONObject result = work.run();
                AndroidUtilities.runOnUIThread(() -> { if (active(chat, account, group, token)) done.accept(result); });
            } catch (Exception e) {
                int status = e instanceof WaysGramApi.ApiException ? ((WaysGramApi.ApiException) e).status : 0;
                AndroidUtilities.runOnUIThread(() -> { if (active(chat, account, group, token)) failed.accept(status); });
            }
        });
    }
    static void open(ChatActivity chat, int account, long group) {
        String token = WaysGramApi.getToken(account);
        request(chat, account, group, token, () -> WaysGramApi.request("GET", root(group) + "/send-identities", null, token, 10000), result -> {
            ArrayList<CharSequence> labels = new ArrayList<>();
            ArrayList<Runnable> actions = new ArrayList<>();
            if (result.optBoolean("anonymous_allowed")) {
                String title = result.optString("title");
                labels.add(LocaleController.formatString(R.string.WaysGramGroupIdentityAnonymous, title));
                actions.add(() -> compose(chat, account, group, token, 0, title));
            }
            JSONArray channels = result.optJSONArray("channels");
            for (int i = 0; channels != null && i < channels.length(); i++) {
                JSONObject item = channels.optJSONObject(i);
                if (item == null || item.optLong("id") <= 0) continue;
                long id = item.optLong("id");
                String title = item.optString("title");
                labels.add(LocaleController.formatString(R.string.WaysGramGroupIdentityChannel, title));
                actions.add(() -> compose(chat, account, group, token, id, title));
            }
            if (result.optBoolean("can_manage")) {
                labels.add(text(R.string.WaysGramGroupIdentityManage));
                actions.add(() -> admins(chat, account, group, token, result.optJSONArray("admins")));
            }
            if (labels.isEmpty()) { notice(chat, R.string.WaysGramGroupIdentityEmpty); return; }
            chat.showDialog(dialog(chat).setItems(labels.toArray(new CharSequence[0]), (d, i) -> actions.get(i).run())
                    .setNegativeButton(text(R.string.Close), null).create());
        }, status -> notice(chat, R.string.WaysGramGroupIdentityError));
    }
    private static void admins(ChatActivity chat, int account, long group, String token, JSONArray rows) {
        ArrayList<JSONObject> entries = new ArrayList<>();
        ArrayList<CharSequence> labels = new ArrayList<>();
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null || item.optLong("telegram_id") <= 0) continue;
            entries.add(item);
            String name = item.optString("display_name");
            if (name.isEmpty()) name = item.optString("username", Long.toString(item.optLong("telegram_id")));
            labels.add(LocaleController.formatString(item.optBoolean("anonymous_allowed") ? R.string.WaysGramGroupIdentityDisable : R.string.WaysGramGroupIdentityEnable, name));
        }
        if (labels.isEmpty()) { notice(chat, R.string.WaysGramGroupIdentityNoAdmins); return; }
        chat.showDialog(dialog(chat).setItems(labels.toArray(new CharSequence[0]), (d, i) -> {
            JSONObject entry = entries.get(i);
            request(chat, account, group, token, () -> WaysGramApi.request("PUT", root(group) + "/admins/" + entry.optLong("telegram_id") + "/anonymous",
                            new JSONObject().put("enabled", !entry.optBoolean("anonymous_allowed")), token, 10000),
                    response -> open(chat, account, group), status -> notice(chat, R.string.WaysGramGroupIdentityError));
        }).setNegativeButton(text(R.string.Cancel), null).create());
    }
    private static void compose(ChatActivity chat, int account, long group, String token, long identity, String title) {
        if (!active(chat, account, group, token)) return;
        EditText field = new EditText(chat.getParentActivity());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        chat.showDialog(dialog(chat).setMessage(LocaleController.formatString(R.string.WaysGramGroupIdentityConfirm, title)).setView(field)
                .setNegativeButton(text(R.string.Cancel), null).setPositiveButton(text(R.string.Send), (d, w) -> {
                    String body = field.getText().toString().trim();
                    if (!body.isEmpty()) send(chat, account, group, token, identity, body, UUID.randomUUID().toString());
                }).create());
    }
    private static void send(ChatActivity chat, int account, long group, String token, long identity, String body, String key) {
        request(chat, account, group, token, () -> WaysGramApi.request("POST", root(group) + "/identity-messages",
                new JSONObject().put("body", body).put("send_as_id", identity), token, 15000, key), response -> {}, status -> {
            if (status >= 400 && status < 500) { notice(chat, R.string.WaysGramGroupIdentityError); return; }
            chat.showDialog(dialog(chat).setMessage(text(R.string.WaysGramGroupIdentityError)).setNegativeButton(text(R.string.Cancel), null)
                    .setPositiveButton(text(R.string.WaysGramPaidRetry), (d, w) -> send(chat, account, group, token, identity, body, key)).create());
        });
    }
}
