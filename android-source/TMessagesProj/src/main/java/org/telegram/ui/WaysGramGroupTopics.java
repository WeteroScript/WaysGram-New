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
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.UUID;

final class WaysGramGroupTopics {
    private interface Work { JSONObject run() throws Exception; }
    private interface Result { void accept(JSONObject data); }
    private interface Failure { void accept(int status); }
    private interface Submit { void accept(String value); }

    private static boolean active(BaseFragment fragment, int account, String token) {
        return fragment.getParentActivity() != null && fragment.getCurrentAccount() == account
                && !TextUtils.isEmpty(token) && TextUtils.equals(token, WaysGramApi.getToken(account));
    }

    private static void request(BaseFragment fragment, int account, String token, Work work, Result result, Failure failed) {
        if (!active(fragment, account, token)) return;
        WaysGramApi.NETWORK.execute(() -> {
            try {
                JSONObject data = work.run();
                AndroidUtilities.runOnUIThread(() -> { if (active(fragment, account, token)) result.accept(data); });
            } catch (Exception e) {
                int status = e instanceof WaysGramApi.ApiException ? ((WaysGramApi.ApiException) e).status : 0;
                AndroidUtilities.runOnUIThread(() -> { if (active(fragment, account, token)) failed.accept(status); });
            }
        });
    }

    private static String text(int id) { return LocaleController.getString(id); }
    private static AlertDialog.Builder dialog(BaseFragment fragment, int title) {
        return new AlertDialog.Builder(fragment.getParentActivity()).setTitle(text(title));
    }
    private static void notice(BaseFragment fragment, int message) {
        fragment.showDialog(dialog(fragment, R.string.WaysGramTopics).setMessage(text(message))
                .setPositiveButton(text(R.string.OK), null).create());
    }

    static void open(BaseFragment fragment, int account, long group) {
        openTopic(fragment, account, group, 0);
    }

    static void openTopic(BaseFragment fragment, int account, long group, long topic) {
        if (group > 0) fragment.presentFragment(new WaysGramForumActivity(account, group, topic));
    }

    static void list(WaysGramForumActivity forum, int account, String token, String root) {
        int generation = forum.beginLoad(0, 0);
        request(forum, account, token, () -> WaysGramApi.request("GET", root, null, token, 10000), data -> {
            if (forum.accepts(generation)) forum.renderTopics(data);
        }, status -> { if (forum.accepts(generation)) forum.loadFailed(status); });
    }

    static void create(WaysGramForumActivity forum, int account, String token, String root) {
        input(forum, account, token, R.string.WaysGramTopicCreate, "", 80, title ->
                request(forum, account, token, () -> WaysGramApi.request("POST", root, new JSONObject().put("title", title), token, 10000),
                        topic -> page(forum, account, token, root, topic.optLong("id"), 0), status -> notice(forum, R.string.WaysGramTopicsError)));
    }

    private static void list(BaseFragment fragment, int account, String token, String root) {
        if (fragment instanceof WaysGramForumActivity) list((WaysGramForumActivity) fragment, account, token, root);
    }

    static void page(BaseFragment fragment, int account, String token, String root, long topic, long before) {
        if (topic <= 0 || !(fragment instanceof WaysGramForumActivity)) return;
        WaysGramForumActivity forum = (WaysGramForumActivity) fragment;
        int generation = forum.beginLoad(topic, before);
        request(fragment, account, token, () -> WaysGramApi.request("GET", root + "/" + topic + "/messages?limit=25&before_id=" + before, null, token, 10000), data -> {
            if (forum.accepts(generation)) forum.renderThread(data);
        }, status -> { if (forum.accepts(generation)) forum.loadFailed(status); });
    }

    static void actions(BaseFragment fragment, int account, String token, String root, long topic, long before) {
        String path = root + "/" + topic;
        request(fragment, account, token, () -> WaysGramApi.request("GET", path + "/messages?limit=25&before_id=" + before, null, token, 10000), data -> {
            JSONObject info = data.optJSONObject("topic");
            if (info != null) showActions(fragment, account, token, root, topic, data.optLong("next_before_id"), info.optString("title"),
                    info.optBoolean("closed"), data.optBoolean("can_manage"), data.optBoolean("can_pin"), info.optJSONObject("pin"), data.optJSONArray("messages"), data.has("notifications_enabled"), data.optBoolean("notifications_enabled"));
        }, status -> notice(fragment, R.string.WaysGramTopicsError));
    }

    private static void showActions(BaseFragment fragment, int account, String token, String root, long topic, long next,
                                String title, boolean closed, boolean manage, boolean canPin, JSONObject pin, JSONArray messages, boolean canFollow, boolean following) {
        if (!active(fragment, account, token)) return;
        String path = root + "/" + topic;
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Runnable> operations = new ArrayList<>();
        if (!closed) {
            labels.add(text(R.string.WaysGramInboxWrite));
            operations.add(() -> input(fragment, account, token, R.string.WaysGramInboxWrite, "", 4000,
                    body -> send(fragment, account, token, root, topic, body, UUID.randomUUID().toString())));
        }
        labels.add(text(R.string.WaysGramCommentsLatest));
        operations.add(() -> page(fragment, account, token, root, topic, 0));
        if (next > 0) {
            labels.add(text(R.string.WaysGramCommentsOlder));
            operations.add(() -> page(fragment, account, token, root, topic, next));
        }
        if (fragment instanceof WaysGramForumActivity && !closed) {
            labels.add(text(R.string.WaysGramTopicMedia));
            operations.add(() -> ((WaysGramForumActivity) fragment).pickMedia(topic));
        }
        if (messages != null && messages.length() > 0 && (canPin || fragment instanceof WaysGramForumActivity)) {
            labels.add(text(R.string.WaysGramTopicMessageActions));
            operations.add(() -> messageActions(fragment, account, token, root, topic, canPin, messages));
        }
        if (canPin && pin != null) {
            labels.add(text(R.string.WaysGramTopicUnpin));
            operations.add(() -> pin(fragment, account, token, root, topic, 0));
        }
        if (canFollow) {
            labels.add(text(following ? R.string.WaysGramTopicStopNotifications : R.string.WaysGramTopicStartNotifications));
            operations.add(() -> request(fragment, account, token, () -> WaysGramApi.request("PUT", path + "/notifications",
                            new JSONObject().put("enabled", !following), token, 10000),
                    result -> page(fragment, account, token, root, topic, 0), status -> notice(fragment, R.string.WaysGramTopicsError)));
        }
        if (manage) {
            labels.add(text(R.string.WaysGramTopicRename));
            operations.add(() -> input(fragment, account, token, R.string.WaysGramTopicRename, title, 80, value ->
                    change(fragment, account, token, root, topic, () -> new JSONObject().put("title", value))));
            labels.add(text(closed ? R.string.WaysGramTopicReopen : R.string.WaysGramTopicClose));
            operations.add(() -> change(fragment, account, token, root, topic, () -> new JSONObject().put("closed", !closed)));
            labels.add(text(R.string.WaysGramTopicDelete));
            operations.add(() -> fragment.showDialog(dialog(fragment, R.string.WaysGramTopicDelete)
                    .setMessage(LocaleController.formatString(R.string.WaysGramTopicDeleteConfirm, title))
                    .setNegativeButton(text(R.string.Cancel), null)
                    .setPositiveButton(text(R.string.WaysGramTopicDelete), (d, w) ->
                            request(fragment, account, token, () -> WaysGramApi.request("DELETE", path, null, token, 10000),
                                    response -> list(fragment, account, token, root), status -> notice(fragment, R.string.WaysGramTopicsError))).create()));
        }
        fragment.showDialog(dialog(fragment, R.string.WaysGramProposalActions)
                .setItems(labels.toArray(new CharSequence[0]), (d, i) -> operations.get(i).run()).create());
    }

    private static void pin(BaseFragment fragment, int account, String token, String root, long topic, long message) {
        request(fragment, account, token, () -> WaysGramApi.request("PUT", root + "/" + topic + "/pin",
                new JSONObject().put("message_id", message), token, 10000), data -> page(fragment, account, token, root, topic, 0),
                status -> notice(fragment, R.string.WaysGramTopicsError));
    }

    private static void messageActions(BaseFragment fragment, int account, String token, String root, long topic, boolean canPin, JSONArray messages) {
        ArrayList<JSONObject> rows = new ArrayList<>();
        ArrayList<CharSequence> titles = new ArrayList<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null || (!canPin && row.isNull("media_id"))) continue;
            String body = row.optString("body");
            rows.add(row);
            titles.add(body.isEmpty() ? row.optString("media_name") : body.substring(0, Math.min(100, body.length())));
        }
        if (rows.isEmpty()) return;
        fragment.showDialog(dialog(fragment, R.string.WaysGramTopicMessageActions).setItems(titles.toArray(new CharSequence[0]), (d, index) -> {
            JSONObject row = rows.get(index);
            AlertDialog.Builder builder = dialog(fragment, R.string.WaysGramTopicMessageActions).setMessage(titles.get(index)).setNegativeButton(text(R.string.Cancel), null);
            if (canPin) builder.setPositiveButton(text(R.string.WaysGramTopicPin), (a, b) -> pin(fragment, account, token, root, topic, row.optLong("id")));
            if (fragment instanceof WaysGramForumActivity && !row.isNull("media_id")) builder.setNeutralButton(text(R.string.Open), (a, b) ->
                    openMedia((WaysGramForumActivity) fragment, account, token, row));
            fragment.showDialog(builder.create());
        }).setNegativeButton(text(R.string.Cancel), null).create());
    }

    static void openMedia(WaysGramForumActivity forum, int account, String token, JSONObject row) {
        if (!active(forum, account, token)) return;
        org.telegram.tgnet.TLRPC.TL_message message = new org.telegram.tgnet.TLRPC.TL_message();
        message.id = row.optInt("id");
        message.message = row.optString("body");
        message.peer_id = new org.telegram.tgnet.TLRPC.TL_peerChat();
        message.peer_id.chat_id = forum.groupId;
        message.dialog_id = -forum.groupId;
        WaysGramApi.NETWORK.execute(() -> {
            if (!TextUtils.equals(token, WaysGramApi.getToken(account))) return;
            org.telegram.tgnet.WaysGramMedia.attach(account, message, row);
            AndroidUtilities.runOnUIThread(() -> {
                if (!active(forum, account, token) || forum.isPaused() || message.media == null) return;
                PhotoViewer viewer = PhotoViewer.getInstance();
                viewer.setParentActivity(forum);
                viewer.openPhoto(new org.telegram.messenger.MessageObject(account, message, false, true),
                        null, 0, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
            });
        });
    }

    static void upload(BaseFragment fragment, int account, String token, String root, long topic, android.net.Uri uri) {
        if (!active(fragment, account, token)) return;
        android.content.ContentResolver resolver = fragment.getParentActivity().getContentResolver();
        request(fragment, account, token, () -> {
            String mime = resolver.getType(uri);
            boolean image = mime != null && mime.startsWith("image/");
            if (!image && !"video/mp4".equals(mime)) throw new java.io.IOException("Unsupported media");
            int max = (image ? 10 : 25) * 1024 * 1024;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = resolver.openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("Missing file");
                byte[] buffer = new byte[65536];
                for (int length; (length = in.read(buffer)) != -1;) {
                    if (bytes.size() + length > max) throw new java.io.IOException("File too large");
                    bytes.write(buffer, 0, length);
                }
            }
            return WaysGramApi.uploadWithToken(image ? "/api/media/images" : "/api/media/documents", bytes.toByteArray(), mime,
                    image ? "photo" : "video.mp4", image ? null : "video", null, token);
        }, data -> sendMedia(fragment, account, token, root, topic, data.optString("id"), UUID.randomUUID().toString()),
                status -> notice(fragment, R.string.WaysGramTopicsError));
    }

    private static void sendMedia(BaseFragment fragment, int account, String token, String root, long topic, String media, String key) {
        request(fragment, account, token, () -> WaysGramApi.request("POST", root + "/" + topic + "/messages",
                new JSONObject().put("media_id", media), token, 15000, key), data -> page(fragment, account, token, root, topic, 0), status -> {
            if (status >= 400 && status < 500) notice(fragment, status == 403 || status == 429 ? R.string.WaysGramTopicSendRestricted : R.string.WaysGramTopicsError);
            else fragment.showDialog(dialog(fragment, R.string.WaysGramTopics).setMessage(text(R.string.WaysGramCommentsSendError))
                    .setNegativeButton(text(R.string.Cancel), null)
                    .setPositiveButton(text(R.string.WaysGramPaidRetry), (d, w) -> sendMedia(fragment, account, token, root, topic, media, key)).create());
        });
    }

    private static void change(BaseFragment fragment, int account, String token, String root, long topic, Work payload) {
        request(fragment, account, token, () -> WaysGramApi.request("PATCH", root + "/" + topic, payload.run(), token, 10000),
                data -> page(fragment, account, token, root, topic, 0), status -> notice(fragment, R.string.WaysGramTopicsError));
    }

    private static void input(BaseFragment fragment, int account, String token, int title, String initial, int limit, Submit submit) {
        if (!active(fragment, account, token)) return;
        EditText field = new EditText(fragment.getParentActivity());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | (limit > 80 ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        field.setText(initial);
        fragment.showDialog(dialog(fragment, title).setView(field).setNegativeButton(text(R.string.Cancel), null)
                .setPositiveButton(text(limit > 80 ? R.string.Send : R.string.Save), (d, w) -> {
                    String value = field.getText().toString().trim();
                    if (!value.isEmpty() && active(fragment, account, token)) submit.accept(value);
                }).create());
    }

    static void send(BaseFragment fragment, int account, String token, String root, long topic, String body, String key) {
        request(fragment, account, token, () -> WaysGramApi.request("POST", root + "/" + topic + "/messages",
                new JSONObject().put("body", body), token, 15000, key), data -> {
            if (fragment instanceof WaysGramForumActivity) ((WaysGramForumActivity) fragment).textSendFinished(topic, body, true);
            page(fragment, account, token, root, topic, 0);
        }, status -> {
            if (fragment instanceof WaysGramForumActivity) ((WaysGramForumActivity) fragment).textSendFinished(topic, body, false);
            if (status == 403 || status == 429) notice(fragment, R.string.WaysGramTopicSendRestricted);
            else if (status >= 400 && status < 500) notice(fragment, R.string.WaysGramTopicsError);
            else fragment.showDialog(dialog(fragment, R.string.WaysGramTopics).setMessage(text(R.string.WaysGramCommentsSendError))
                    .setNegativeButton(text(R.string.Cancel), null)
                    .setPositiveButton(text(R.string.WaysGramPaidRetry), (d, w) -> send(fragment, account, token, root, topic, body, key)).create());
        });
    }
}
