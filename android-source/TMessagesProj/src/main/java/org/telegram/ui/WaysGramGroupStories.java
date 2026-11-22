package org.telegram.ui;

import android.content.ContentResolver;
import android.net.Uri;
import android.text.InputFilter;
import android.text.TextUtils;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.tgnet.WaysGramMedia;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;

final class WaysGramGroupStories {
    private interface Work { JSONObject run() throws Exception; }
    private interface Result { void accept(JSONObject data); }
    private static boolean active(ChatActivity chat, int account, long group, String token) {
        return chat.getParentActivity() != null && !chat.isFinished && chat.getCurrentAccount() == account
                && chat.getDialogId() == -group && !TextUtils.isEmpty(token) && TextUtils.equals(token, WaysGramApi.getToken(account));
    }
    private static String root(long group) { return "/api/groups/" + group; }
    private static String text(int id) { return LocaleController.getString(id); }
    private static AlertDialog.Builder dialog(ChatActivity chat) { return new AlertDialog.Builder(chat.getParentActivity()).setTitle(text(R.string.WaysGramGroupStories)); }
    private static void error(ChatActivity chat) {
        chat.showDialog(dialog(chat).setMessage(text(R.string.WaysGramGroupStoryError)).setPositiveButton(text(R.string.OK), null).create());
    }
    private static void request(ChatActivity chat, int account, long group, String token, Work work, Result done, Runnable failed) {
        if (!active(chat, account, group, token)) return;
        WaysGramApi.NETWORK.execute(() -> {
            try {
                JSONObject data = work.run();
                AndroidUtilities.runOnUIThread(() -> { if (active(chat, account, group, token)) done.accept(data); });
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> { if (active(chat, account, group, token)) failed.run(); });
            }
        });
    }
    static void open(ChatActivity chat, int account, long group) { list(chat, account, group, WaysGramApi.getToken(account), "active"); }
    private static void list(ChatActivity chat, int account, long group, String token, String mode) {
        request(chat, account, group, token, () -> WaysGramApi.request("GET", root(group) + "/stories?mode=" + mode, null, token, 10000), data -> {
            ArrayList<CharSequence> titles = new ArrayList<>();
            ArrayList<Runnable> actions = new ArrayList<>();
            if (data.optBoolean("can_post")) {
                titles.add(text(R.string.WaysGramGroupStoryAdd));
                actions.add(() -> chat.pickWaysGramGroupStory(group, token, data.optBoolean("can_pin")));
            }
            if (data.optBoolean("can_configure")) {
                boolean allowed = data.optBoolean("members_can_post");
                titles.add(text(allowed ? R.string.WaysGramGroupStoryAdmins : R.string.WaysGramGroupStoryMembers));
                actions.add(() -> request(chat, account, group, token, () -> WaysGramApi.request("PUT", root(group) + "/story-settings",
                        new JSONObject().put("members_can_post", !allowed), token, 10000), result -> list(chat, account, group, token, mode), () -> error(chat)));
            }
            boolean pinned = "pinned".equals(mode);
            titles.add(text(pinned ? R.string.WaysGramGroupStoryActive : R.string.WaysGramGroupStoryPinned));
            actions.add(() -> list(chat, account, group, token, pinned ? "active" : "pinned"));
            JSONArray rows = data.optJSONArray("stories");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String caption = row.optString("caption");
                titles.add(caption.isEmpty() ? text(R.string.WaysGramGroupStoryItem) : caption.substring(0, Math.min(100, caption.length())));
                actions.add(() -> showStory(chat, account, group, token, mode, row));
            }
            AlertDialog.Builder builder = dialog(chat).setItems(titles.toArray(new CharSequence[0]), (d, i) -> actions.get(i).run())
                    .setNegativeButton(text(R.string.Close), null);
            if (rows == null || rows.length() == 0) builder.setMessage(text(R.string.WaysGramGroupStoryEmpty));
            chat.showDialog(builder.create());
        }, () -> error(chat));
    }
    private static void showStory(ChatActivity chat, int account, long group, String token, String mode, JSONObject row) {
        AlertDialog.Builder builder = dialog(chat).setMessage(row.optString("caption"))
                .setNegativeButton(text(R.string.Cancel), null).setPositiveButton(text(R.string.Open), (d, w) ->
                        request(chat, account, group, token, () -> {
                            JSONObject copy = new JSONObject(row.toString());
                            return copy.put("media_type", row.optString("mime_type")).put("media_name", row.optString("original_name"));
                        }, media -> viewStory(chat, account, group, token, media), () -> error(chat)));
        if (row.optBoolean("can_delete")) builder.setNeutralButton(text(R.string.Delete), (d, w) ->
                chat.showDialog(dialog(chat).setMessage(text(R.string.WaysGramGroupStoryDelete))
                        .setNegativeButton(text(R.string.Cancel), null).setPositiveButton(text(R.string.Delete), (a, b) ->
                                request(chat, account, group, token, () -> WaysGramApi.request("POST", root(group) + "/stories/delete",
                                        new JSONObject().put("ids", new JSONArray().put(row.optInt("id"))), token, 10000),
                                        result -> list(chat, account, group, token, mode), () -> error(chat))).create()));
        chat.showDialog(builder.create());
    }
    private static void viewStory(ChatActivity chat, int account, long group, String token, JSONObject row) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = row.optInt("id");
        message.date = row.optInt("created_at");
        message.message = row.optString("caption");
        message.peer_id = new TLRPC.TL_peerChat();
        message.peer_id.chat_id = group;
        message.dialog_id = -group;
        message.noforwards = true;
        WaysGramApi.NETWORK.execute(() -> {
            if (!TextUtils.equals(token, WaysGramApi.getToken(account))) return;
            WaysGramMedia.attach(account, message, row);
            AndroidUtilities.runOnUIThread(() -> {
                if (!active(chat, account, group, token)) return;
                if (message.media == null) { error(chat); return; }
                PhotoViewer viewer = PhotoViewer.getInstance();
                viewer.setParentActivity(chat);
                viewer.openPhoto(new MessageObject(account, message, false, true), null, 0, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
            });
        });
    }
    static void compose(ChatActivity chat, int account, long group, String token, boolean canPin, Uri uri) {
        if (!active(chat, account, group, token)) return;
        LinearLayout fields = new LinearLayout(chat.getParentActivity());
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText caption = new EditText(chat.getParentActivity());
        caption.setHint(text(R.string.WaysGramGroupStoryCaption));
        caption.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2048)});
        fields.addView(caption);
        CheckBox pin = new CheckBox(chat.getParentActivity());
        pin.setText(text(R.string.WaysGramGroupStoryPin));
        if (canPin) fields.addView(pin);
        chat.showDialog(dialog(chat).setView(fields).setMessage(text(R.string.WaysGramGroupStoryPrivacy))
                .setNegativeButton(text(R.string.Cancel), null).setPositiveButton(text(R.string.WaysGramGroupStoryAdd), (d, w) -> {
                    String body = caption.getText().toString();
                    boolean pinned = canPin && pin.isChecked();
                    ContentResolver resolver = chat.getParentActivity().getContentResolver();
                    request(chat, account, group, token, () -> upload(resolver, uri, token), data ->
                            publish(chat, account, group, token, data.optString("id"), body, pinned, UUID.randomUUID().getMostSignificantBits()), () -> error(chat));
                }).create());
    }
    private static JSONObject upload(ContentResolver resolver, Uri uri, String token) throws Exception {
        String mime = resolver.getType(uri);
        boolean photo = mime != null && mime.startsWith("image/");
        if (!photo && !"video/mp4".equals(mime)) throw new IOException("Unsupported story media");
        int max = (photo ? 10 : 25) * 1024 * 1024;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("Missing file");
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1;) {
                if (bytes.size() + n > max) throw new IOException("Story too large");
                bytes.write(buffer, 0, n);
            }
        }
        return WaysGramApi.uploadWithToken(photo ? "/api/media/images" : "/api/media/documents", bytes.toByteArray(), mime,
                photo ? "photo" : "video.mp4", photo ? null : "video", null, token);
    }
    private static void publish(ChatActivity chat, int account, long group, String token, String media, String caption, boolean pinned, long random) {
        request(chat, account, group, token, () -> WaysGramApi.request("POST", root(group) + "/stories",
                new JSONObject().put("media_id", media).put("caption", caption).put("pinned", pinned)
                        .put("noforwards", true).put("random_id", random), token, 15000), data -> list(chat, account, group, token, "active"), () ->
                chat.showDialog(dialog(chat).setMessage(text(R.string.WaysGramGroupStoryError)).setNegativeButton(text(R.string.Cancel), null)
                        .setPositiveButton(text(R.string.WaysGramPaidRetry), (d, w) -> publish(chat, account, group, token, media, caption, pinned, random)).create()));
    }
}
