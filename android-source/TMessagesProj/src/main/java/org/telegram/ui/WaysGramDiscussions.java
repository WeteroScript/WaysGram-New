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
import org.telegram.tgnet.WaysGramChats;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.UUID;

final class WaysGramDiscussions {
    private interface Work { JSONObject run() throws Exception; }
    private interface Result { void accept(JSONObject result); }
    private interface Failure { void accept(Exception error); }

    private static boolean active(BaseFragment fragment, int account, String token) {
        return fragment.getParentActivity() != null && fragment.getCurrentAccount() == account
                && !TextUtils.isEmpty(token) && TextUtils.equals(token, WaysGramApi.getToken(account));
    }

    private static void request(BaseFragment fragment, int account, String token, Work work, Result result, Failure failure) {
        if (!active(fragment, account, token)) return;
        WaysGramApi.NETWORK.execute(() -> {
            try {
                JSONObject data = work.run();
                AndroidUtilities.runOnUIThread(() -> { if (active(fragment, account, token)) result.accept(data); });
            } catch (Exception error) {
                AndroidUtilities.runOnUIThread(() -> { if (active(fragment, account, token)) failure.accept(error); });
            }
        });
    }

    private static AlertDialog.Builder dialog(BaseFragment fragment, int title) {
        return new AlertDialog.Builder(fragment.getParentActivity()).setTitle(LocaleController.getString(title));
    }

    private static void notice(BaseFragment fragment, int title, int text) {
        fragment.showDialog(dialog(fragment, title).setMessage(LocaleController.getString(text))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    private static String channelPath(long channel) {
        return "/api/channels/" + WaysGramChats.channelServer(channel);
    }

    static void settings(BaseFragment fragment, int account, long channel) {
        String token = WaysGramApi.getToken(account);
        String path = channelPath(channel) + "/discussion-group";
        request(fragment, account, token, () -> WaysGramApi.request("GET", path, null, token, 10000), linked -> {
            long current = linked.optLong("group_id");
            AlertDialog.Builder builder = dialog(fragment, R.string.WaysGramDiscussionGroup)
                    .setMessage(current > 0 ? LocaleController.formatString(R.string.WaysGramDiscussionLinked, linked.optString("title"))
                            : LocaleController.getString(R.string.WaysGramDiscussionNotLinked))
                    .setPositiveButton(LocaleController.getString(R.string.WaysGramDiscussionChoose), (d, w) ->
                            choose(fragment, account, token, path, channel));
            if (current > 0) {
                builder.setNeutralButton(LocaleController.getString(R.string.WaysGramDiscussionUnlink), (d, w) -> {
                    if (!active(fragment, account, token)) return;
                    fragment.showDialog(dialog(fragment, R.string.WaysGramDiscussionGroup)
                            .setMessage(LocaleController.getString(R.string.WaysGramDiscussionUnlinkConfirm))
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .setPositiveButton(LocaleController.getString(R.string.WaysGramDiscussionUnlink), (confirm, button) ->
                                    save(fragment, account, token, path, channel, 0)).create());
                });
            }
            fragment.showDialog(builder.setNegativeButton(LocaleController.getString(R.string.Close), null).create());
        }, error -> notice(fragment, R.string.WaysGramDiscussionGroup, R.string.WaysGramDiscussionError));
    }

    private static void choose(BaseFragment fragment, int account, String token, String path, long channel) {
        request(fragment, account, token, () -> WaysGramApi.request("GET", "/api/groups", null, token, 10000), data -> {
            JSONArray groups = data.optJSONArray("groups");
            ArrayList<String> names = new ArrayList<>();
            ArrayList<Long> ids = new ArrayList<>();
            for (int i = 0; groups != null && i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group != null && "owner".equals(group.optString("role")) && group.optLong("id") > 0) {
                    names.add(group.optString("title"));
                    ids.add(group.optLong("id"));
                }
            }
            if (ids.isEmpty()) {
                notice(fragment, R.string.WaysGramDiscussionGroup, R.string.WaysGramDiscussionNoGroups);
                return;
            }
            fragment.showDialog(dialog(fragment, R.string.WaysGramDiscussionChoose).setItems(names.toArray(new CharSequence[0]), (d, index) -> {
                if (!active(fragment, account, token)) return;
                fragment.showDialog(dialog(fragment, R.string.WaysGramDiscussionGroup)
                        .setMessage(LocaleController.formatString(R.string.WaysGramDiscussionConfirm, names.get(index)))
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .setPositiveButton(LocaleController.getString(R.string.OK), (confirm, button) ->
                                save(fragment, account, token, path, channel, ids.get(index))).create());
            }).create());
        }, error -> notice(fragment, R.string.WaysGramDiscussionGroup, R.string.WaysGramDiscussionError));
    }

    private static void save(BaseFragment fragment, int account, String token, String path, long channel, long group) {
        request(fragment, account, token, () -> WaysGramApi.request("PUT", path,
                new JSONObject().put("group_id", group > 0 ? Long.valueOf(group) : JSONObject.NULL), token, 15000),
                data -> settings(fragment, account, channel), error -> notice(fragment, R.string.WaysGramDiscussionGroup, R.string.WaysGramDiscussionError));
    }

    static void open(BaseFragment fragment, int account, long channel, int post) {
        page(fragment, account, WaysGramApi.getToken(account), channelPath(channel) + "/messages/" + post + "/comments", 0);
    }

    private static void page(BaseFragment fragment, int account, String token, String path, long before) {
        request(fragment, account, token, () -> WaysGramApi.request("GET", path + "?limit=25&before_id=" + before, null, token, 10000), data -> {
            JSONArray rows = data.optJSONArray("comments");
            long older = data.optLong("next_before_id");
            AlertDialog.Builder builder = dialog(fragment, R.string.WaysGramPostComments)
                    .setPositiveButton(LocaleController.getString(R.string.WaysGramCommentsWrite), (d, w) ->
                            compose(fragment, account, token, path, 0, ""))
                    .setNegativeButton(LocaleController.getString(R.string.Close), null);
            if (rows == null || rows.length() == 0) {
                builder.setMessage(LocaleController.getString(R.string.WaysGramCommentsEmpty));
            } else {
                android.widget.ScrollView scroll = new android.widget.ScrollView(fragment.getParentActivity());
                android.widget.LinearLayout list = new android.widget.LinearLayout(fragment.getParentActivity());
                list.setOrientation(android.widget.LinearLayout.VERTICAL);
                scroll.addView(list);
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    long id = row.optLong("id");
                    String name = row.isNull("sender_name") ? "" : row.optString("sender_name");
                    StringBuilder text = new StringBuilder();
                    if (!name.isEmpty()) text.append(name).append('\n');
                    if (row.optLong("reply_to_id") > 0) {
                        if (row.isNull("reply_to_body")) text.append(LocaleController.getString(R.string.WaysGramCommentsParentUnavailable));
                        else text.append(LocaleController.formatString(R.string.WaysGramCommentsReplyQuote, row.optString("reply_to_body")));
                        text.append('\n');
                    }
                    text.append(row.optString("body"));
                    android.widget.TextView label = new android.widget.TextView(fragment.getParentActivity());
                    label.setText(text);
                    label.setTextSize(16);
                    label.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
                    label.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(10), AndroidUtilities.dp(24), 0);
                    list.addView(label);
                    if (id > 0) {
                        android.widget.Button reply = new android.widget.Button(fragment.getParentActivity(), null, android.R.attr.borderlessButtonStyle);
                        reply.setText(LocaleController.getString(R.string.WaysGramCommentsReply));
                        reply.setOnClickListener(v -> compose(fragment, account, token, path, id, name));
                        list.addView(reply);
                    }
                }
                builder.setView(scroll, AndroidUtilities.dp(340));
            }
            if (older > 0) builder.setNeutralButton(LocaleController.getString(R.string.WaysGramCommentsOlder), (d, w) ->
                    page(fragment, account, token, path, older));
            else if (before > 0) builder.setNeutralButton(LocaleController.getString(R.string.WaysGramCommentsLatest), (d, w) ->
                    page(fragment, account, token, path, 0));
            fragment.showDialog(builder.create());
        }, error -> {
            int text = R.string.WaysGramDiscussionError;
            if (error instanceof WaysGramApi.ApiException) {
                int status = ((WaysGramApi.ApiException) error).status;
                if (status == 403) text = R.string.WaysGramCommentsJoin;
                if (status == 404) text = R.string.WaysGramCommentsUnavailable;
            }
            notice(fragment, R.string.WaysGramPostComments, text);
        });
    }

    private static void compose(BaseFragment fragment, int account, String token, String path, long replyToId, String sender) {
        if (!active(fragment, account, token)) return;
        EditText field = new EditText(fragment.getParentActivity());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        field.setHint(LocaleController.getString(R.string.WaysGramCommentsWrite));
        AlertDialog.Builder builder = dialog(fragment, R.string.WaysGramPostComments).setView(field);
        if (replyToId > 0) builder.setMessage(sender.isEmpty() ? LocaleController.getString(R.string.WaysGramCommentsReply)
                : LocaleController.formatString(R.string.WaysGramCommentsReplyTo, sender));
        fragment.showDialog(builder
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Send), (d, w) -> {
                    String body = field.getText().toString().trim();
                    if (!body.isEmpty()) send(fragment, account, token, path, body, replyToId, UUID.randomUUID().toString());
                }).create());
    }

    private static void send(BaseFragment fragment, int account, String token, String path, String body, long replyToId, String key) {
        request(fragment, account, token, () -> {
            JSONObject payload = new JSONObject().put("body", body);
            if (replyToId > 0) payload.put("reply_to_id", replyToId);
            return WaysGramApi.request("POST", path, payload, token, 15000, key);
        }, data -> page(fragment, account, token, path, 0), error -> {
            int status = error instanceof WaysGramApi.ApiException ? ((WaysGramApi.ApiException) error).status : 0;
            if (status == 403 || status == 429) {
                notice(fragment, R.string.WaysGramPostComments, R.string.WaysGramCommentsRestricted);
            } else if (status == 404) {
                notice(fragment, R.string.WaysGramPostComments, replyToId > 0 ? R.string.WaysGramCommentsParentUnavailable : R.string.WaysGramCommentsUnavailable);
            } else {
                fragment.showDialog(dialog(fragment, R.string.WaysGramPostComments)
                        .setMessage(LocaleController.getString(R.string.WaysGramCommentsSendError))
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .setPositiveButton(LocaleController.getString(R.string.WaysGramPaidRetry), (d, w) ->
                                send(fragment, account, token, path, body, replyToId, key)).create());
            }
        });
    }
}
