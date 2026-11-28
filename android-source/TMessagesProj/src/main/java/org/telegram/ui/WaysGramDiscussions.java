package org.telegram.ui;

import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
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
        TLRPC.ChatFull chatInfo = MessagesController.getInstance(account).getChatFull(channel);
        request(fragment, account, token, () -> WaysGramApi.request("GET", path, null, token, 10000), linked -> {
            long current = linked.optLong("group_id");
            AlertDialog.Builder builder = dialog(fragment, R.string.WaysGramDiscussionGroup)
                    .setMessage(current > 0 ? LocaleController.formatString(R.string.WaysGramDiscussionLinked, linked.optString("title"))
                            : LocaleController.getString(R.string.WaysGramDiscussionNotLinked))
                    .setPositiveButton(LocaleController.getString(R.string.WaysGramDiscussionChoose), (d, w) ->
                            choose(fragment, account, token, channel, chatInfo));
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
        }, error -> {
            // Раньше это был тупик: «не удалось загрузить» и всё. Теперь ошибка сразу
            // предлагает привязать группу — в том числе когда карточка канала недоступна
            // не владельцу.
            fragment.showDialog(dialog(fragment, R.string.WaysGramDiscussionGroup)
                    .setMessage(LocaleController.getString(R.string.WaysGramDiscussionError))
                    .setNegativeButton(LocaleController.getString(R.string.Close), null)
                    .setPositiveButton(LocaleController.getString(R.string.WaysGramDiscussionChoose), (d, w) ->
                            choose(fragment, account, token, channel, chatInfo)).create());
        });
    }

    private static void choose(BaseFragment fragment, int account, String token, long channel,
                               TLRPC.ChatFull chatInfo) {
        if (!active(fragment, account, token)) return;
        TLRPC.ChatFull full = chatInfo != null
                ? chatInfo : MessagesController.getInstance(account).getChatFull(channel);

        // Reuse Telegram's discussion picker: avatars, search, group creation and
        // the native confirmation row all come from ChatLinkActivity. Its network
        // requests are already translated by WaysGramTransport. ChatLinkActivity
        // loads channel info itself if the cached full chat is not ready yet.
        ChatLinkActivity picker = new ChatLinkActivity(channel, true);
        picker.setInfo(full);
        fragment.presentFragment(picker);
    }

    private static void save(BaseFragment fragment, int account, String token, String path, long channel, long group) {
        request(fragment, account, token, () -> WaysGramApi.request("PUT", path,
                new JSONObject().put("group_id", group > 0 ? Long.valueOf(group) : JSONObject.NULL), token, 15000),
                data -> {
                    reload(fragment, account, channel);
                    notice(fragment, R.string.WaysGramDiscussionGroup, R.string.WaysGramDiscussionEnabled);
                    settings(fragment, account, channel);
                }, error -> notice(fragment, R.string.WaysGramDiscussionGroup, R.string.WaysGramDiscussionError));
    }

    /** Группа комментариев создаётся на сервере: и название, и привязка — его работа. */
    private static void create(BaseFragment fragment, int account, String token, String path, long channel) {
        request(fragment, account, token, () -> WaysGramApi.request("POST", path + "/create", null, token, 20000),
                created -> {
                    reload(fragment, account, channel);
                    notice(fragment, R.string.WaysGramDiscussionGroup, R.string.WaysGramDiscussionEnabled);
                }, error -> notice(fragment, R.string.WaysGramDiscussionGroup, R.string.WaysGramDiscussionError));
    }

    /**
     * Свежие данные канала после привязки группы: без них {@code linked_chat_id} в профиле
     * остаётся нулём, и кнопка комментариев не появляется до перезапуска приложения.
     */
    private static void reload(BaseFragment fragment, int account, long channel) {
        if (!active(fragment, account, WaysGramApi.getToken(account))) return;
        org.telegram.messenger.MessagesController controller =
                org.telegram.messenger.MessagesController.getInstance(account);
        controller.loadFullChat(channel, 0, true);
        fragment.getNotificationCenter().postNotificationName(
                org.telegram.messenger.NotificationCenter.dialogsNeedReload);
    }

    /** Link a channel from the group's own settings, including the currently-unlinked state. */
    static void groupSettings(BaseFragment fragment, int account, long groupId) {
        String token = WaysGramApi.getToken(account);
        if (groupId <= 0 || !active(fragment, account, token)) return;
        request(fragment, account, token,
                () -> WaysGramApi.request("GET", "/api/groups/" + groupId, null, token, 10000),
                group -> {
                    long linkedId = group.optLong("linked_channel_id");
                    JSONObject linked = group.optJSONObject("linked_channel");
                    String title = linked == null ? "" : linked.optString("title", "");
                    CharSequence message = linkedId > 0
                            ? LocaleController.formatString(R.string.WaysGramLinkedChannelLinked, title)
                            : LocaleController.getString(R.string.WaysGramLinkedChannelNotLinked);
                    AlertDialog.Builder builder = dialog(fragment, R.string.LinkedChannel)
                            .setMessage(message)
                            .setPositiveButton(LocaleController.getString(R.string.WaysGramLinkedChannelChoose),
                                    (d, w) -> chooseLinkedChannel(fragment, account, token, groupId, linkedId));
                    if (linkedId > 0) {
                        builder.setNeutralButton(LocaleController.getString(R.string.WaysGramDiscussionUnlink),
                                (d, w) -> fragment.showDialog(dialog(fragment, R.string.LinkedChannel)
                                        .setMessage(LocaleController.getString(R.string.WaysGramLinkedChannelUnlinkConfirm))
                                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                                        .setPositiveButton(LocaleController.getString(R.string.WaysGramDiscussionUnlink),
                                                (confirm, button) -> saveGroupChannelLink(fragment, account, token,
                                                        groupId, linkedId, 0))
                                        .create()));
                    }
                    fragment.showDialog(builder.setNegativeButton(LocaleController.getString(R.string.Close), null).create());
                }, error -> notice(fragment, R.string.LinkedChannel, R.string.WaysGramDiscussionError));
    }

    private static void chooseLinkedChannel(BaseFragment fragment, int account, String token,
                                            long groupId, long linkedId) {
        request(fragment, account, token,
                () -> WaysGramApi.request("GET", "/api/channels", null, token, 10000), data -> {
                    JSONArray rows = data.optJSONArray("channels");
                    long selfId = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
                    ArrayList<String> names = new ArrayList<>();
                    ArrayList<Long> ids = new ArrayList<>();
                    for (int i = 0; rows != null && i < rows.length(); i++) {
                        JSONObject channel = rows.optJSONObject(i);
                        if (channel == null || channel.optLong("owner_telegram_id") != selfId
                                || channel.optLong("id") <= 0) continue;
                        long id = channel.optLong("id");
                        String title = channel.optString("title", "");
                        String username = channel.optString("username", "");
                        names.add(!TextUtils.isEmpty(username) ? title + "  @" + username : title);
                        ids.add(id);
                    }
                    if (ids.isEmpty()) {
                        fragment.showDialog(dialog(fragment, R.string.LinkedChannel)
                                .setMessage(LocaleController.getString(R.string.WaysGramLinkedChannelNoChannels))
                                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
                        return;
                    }
                    fragment.showDialog(dialog(fragment, R.string.WaysGramLinkedChannelChoose)
                            .setItems(names.toArray(new CharSequence[0]), (d, index) -> {
                                if (!active(fragment, account, token)) return;
                                long target = ids.get(index);
                                String confirmText = LocaleController.formatString(
                                        R.string.WaysGramLinkedChannelConfirm, names.get(index));
                                fragment.showDialog(dialog(fragment, R.string.LinkedChannel)
                                        .setMessage(confirmText)
                                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                                        .setPositiveButton(LocaleController.getString(R.string.OK), (confirm, button) ->
                                                saveGroupChannelLink(fragment, account, token, groupId, linkedId, target))
                                        .create());
                            })
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
                }, error -> notice(fragment, R.string.LinkedChannel, R.string.WaysGramDiscussionError));
    }

    private static void saveGroupChannelLink(BaseFragment fragment, int account, String token,
                                             long groupId, long oldChannel, long newChannel) {
        if (oldChannel == newChannel) {
            reloadGroup(fragment, account, groupId, oldChannel);
            return;
        }
        if (oldChannel > 0) {
            String oldPath = "/api/channels/" + oldChannel + "/discussion-group";
            request(fragment, account, token,
                    () -> WaysGramApi.request("PUT", oldPath,
                            new JSONObject().put("group_id", JSONObject.NULL), token, 15000),
                    data -> {
                        if (newChannel > 0) {
                            attachGroupToChannel(fragment, account, token, groupId, oldChannel, newChannel);
                        } else {
                            finishGroupChannelChange(fragment, account, groupId, oldChannel, 0);
                        }
                    }, error -> notice(fragment, R.string.LinkedChannel, R.string.WaysGramDiscussionError));
        } else if (newChannel > 0) {
            attachGroupToChannel(fragment, account, token, groupId, oldChannel, newChannel);
        }
    }

    private static void attachGroupToChannel(BaseFragment fragment, int account, String token,
                                             long groupId, long oldChannel, long newChannel) {
        String path = "/api/channels/" + newChannel + "/discussion-group";
        request(fragment, account, token,
                () -> WaysGramApi.request("PUT", path,
                        new JSONObject().put("group_id", groupId), token, 15000),
                data -> finishGroupChannelChange(fragment, account, groupId, oldChannel, newChannel),
                error -> {
                    if (oldChannel > 0) {
                        String restorePath = "/api/channels/" + oldChannel + "/discussion-group";
                        request(fragment, account, token,
                                () -> WaysGramApi.request("PUT", restorePath,
                                        new JSONObject().put("group_id", groupId), token, 15000),
                                restored -> notice(fragment, R.string.LinkedChannel, R.string.WaysGramDiscussionError),
                                ignored -> notice(fragment, R.string.LinkedChannel, R.string.WaysGramDiscussionError));
                    } else {
                        notice(fragment, R.string.LinkedChannel, R.string.WaysGramDiscussionError);
                    }
                });
    }

    private static void finishGroupChannelChange(BaseFragment fragment, int account, long groupId,
                                                 long oldChannel, long newChannel) {
        if (!active(fragment, account, WaysGramApi.getToken(account))) return;
        org.telegram.messenger.MessagesController controller =
                org.telegram.messenger.MessagesController.getInstance(account);
        controller.loadFullChat(groupId, 0, true);
        if (oldChannel > 0) controller.loadFullChat(WaysGramChats.channelTg(oldChannel), 0, true);
        if (newChannel > 0 && newChannel != oldChannel) {
            controller.loadFullChat(WaysGramChats.channelTg(newChannel), 0, true);
        }
        fragment.getNotificationCenter().postNotificationName(
                org.telegram.messenger.NotificationCenter.dialogsNeedReload);
        if (fragment.getParentActivity() != null) {
            fragment.showDialog(dialog(fragment, R.string.LinkedChannel)
                    .setMessage(LocaleController.getString(R.string.WaysGramLinkedChannelSaved))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
        }
    }

    private static void reloadGroup(BaseFragment fragment, int account, long groupId, long channelId) {
        if (!active(fragment, account, WaysGramApi.getToken(account))) return;
        org.telegram.messenger.MessagesController controller =
                org.telegram.messenger.MessagesController.getInstance(account);
        controller.loadFullChat(groupId, 0, true);
        if (channelId > 0) controller.loadFullChat(WaysGramChats.channelTg(channelId), 0, true);
        fragment.getNotificationCenter().postNotificationName(
                org.telegram.messenger.NotificationCenter.dialogsNeedReload);
    }

    /**
     * Открывает связанную группу обсуждений отдельным экраном.
     *
     * <p>Сначала спрашиваем сервер: он же добавляет в группу того, кому доступен канал
     * (раньше у подписчиков был доступ к каналу, но не к обсуждению). Если запрос не
     * прошёл — просто открываем группу по id из профиля канала.</p>
     */
    static void openChannel(BaseFragment fragment, long channel) {
        if (channel > 0 && fragment.getParentActivity() != null) {
            fragment.presentFragment(ChatActivity.of(-channel));
        }
    }

    static void openGroup(BaseFragment fragment, int account, long channel, long group) {
        String token = WaysGramApi.getToken(account);
        if (group <= 0 || !active(fragment, account, token)) return;
        String path = channelPath(channel) + "/discussion-group";
        WaysGramApi.NETWORK.execute(() -> {
            long target = group;
            try {
                JSONObject linked = WaysGramApi.request("GET", path, null, token, 10000);
                if (linked.optLong("group_id") > 0) target = linked.optLong("group_id");
            } catch (Exception ignore) {
                // Нет прав владельца канала — открываем по данным профиля.
            }
            final long id = target;
            AndroidUtilities.runOnUIThread(() -> {
                if (id > 0 && active(fragment, account, token)) {
                    fragment.presentFragment(ChatActivity.of(-id));
                }
            });
        });
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
