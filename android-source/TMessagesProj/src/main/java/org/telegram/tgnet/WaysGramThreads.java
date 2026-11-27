package org.telegram.tgnet;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Ветка комментариев к постам канала — штатными средствами Telegram.
 *
 * <p>В Telegram комментарии живут в связанной группе обсуждений: у каждого поста канала там есть
 * корневое сообщение, а сами комментарии — ответы на него. Клиент открывает такой тред через
 * {@code messages.getDiscussionMessage} (пост → корневое сообщение) и {@code messages.getReplies}
 * (сообщения треда), а кнопку комментариев рисует по полю {@code message.replies}.</p>
 *
 * <p>Раньше WaysGram показывал вместо этого собственный диалог из меню поста. Теперь сервер заводит
 * корневое сообщение треда ({@code /api/channels/{id}/messages/{post}/discussion}) и отдаёт список
 * сообщений треда, поэтому работает штатный экран с кнопкой комментариев под постом.</p>
 */
public final class WaysGramThreads {

    private WaysGramThreads() {
    }

    public static TLObject handle(int account, TLObject request, long selfId, String token) throws WaysGramApi.ApiException {
        if (request instanceof TLRPC.TL_messages_getDiscussionMessage) {
            return discussion(account, (TLRPC.TL_messages_getDiscussionMessage) request, selfId, token);
        } else if (request instanceof TLRPC.TL_messages_getReplies) {
            return replies(account, (TLRPC.TL_messages_getReplies) request, selfId, token);
        } else if (request instanceof TLRPC.TL_messages_readDiscussion) {
            // Отдельного счётчика непрочитанных комментариев на сервере нет: подтверждаем прочтение.
            return new TLRPC.TL_boolTrue();
        }
        return null;
    }

    /** Идентификатор группы в TL-пространстве совпадает с серверным (группы живут ниже CHANNEL_OFFSET). */
    private static long groupId(TLRPC.InputPeer peer) throws WaysGramApi.ApiException {
        if (peer instanceof TLRPC.TL_inputPeerChat) {
            return peer.chat_id;
        }
        if (peer instanceof TLRPC.TL_inputPeerChannel && WaysGramChats.isGroupChannel(peer.channel_id)) {
            return peer.channel_id;
        }
        throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
    }

    private static long channelServerId(TLRPC.InputPeer peer) throws WaysGramApi.ApiException {
        if (peer instanceof TLRPC.TL_inputPeerChannel && !WaysGramChats.isGroupChannel(peer.channel_id)) {
            return WaysGramChats.channelServer(peer.channel_id);
        }
        throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
    }

    /** Загруженный тред: сообщения (новые первыми), чат группы и профили авторов. */
    private static final class Thread {
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        int count;
        boolean hasMore;
    }

    private static Thread load(int account, long group, int root, int offsetId, int addOffset, int limit,
                               boolean includeRoot, long selfId, String token) throws WaysGramApi.ApiException {
        JSONObject groupInfo = WaysGramApi.request("GET", "/api/groups/" + group, null, token, 20000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        Thread thread = new Thread();
        WaysGramChats.ThreadPage page = WaysGramChats.threadMessages(
                account, group, root, offsetId, addOffset, limit, includeRoot);
        thread.count = page.count;
        thread.hasMore = page.hasMore;
        JSONArray rows = page.messages;
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                thread.messages.add(WaysGramMedia.attach(account,
                        WaysGramChats.groupMessage(row, group, selfId, account), row));
            }
        }
        // Клиент ждёт сообщения от новых к старым.
        Collections.sort(thread.messages, (first, second) -> Integer.compare(second.id, first.id));
        if (groupInfo != null) {
            thread.chats.add(WaysGramChats.toMegagroup(account, groupInfo, selfId));
            thread.users.addAll(WaysGramChats.threadUsers(groupInfo));
        }
        return thread;
    }

    // ---------------------------------------------------------------- discussion

    private static TLObject discussion(int account, TLRPC.TL_messages_getDiscussionMessage req,
                                       long selfId, String token) throws WaysGramApi.ApiException {
        long channel = channelServerId(req.peer);
        if (req.msg_id <= 0) {
            throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        }
        JSONObject info = WaysGramApi.request("GET",
                "/api/channels/" + channel + "/messages/" + req.msg_id + "/discussion", null, token, 20000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        if (info == null) {
            throw new WaysGramApi.ApiException(404, "DISCUSSION_NOT_AVAILABLE");
        }
        long group = info.optLong("group_id", 0);
        int root = info.optInt("root_message_id", 0);
        if (group <= 0 || root <= 0) {
            throw new WaysGramApi.ApiException(404, "DISCUSSION_NOT_AVAILABLE");
        }
        // getDiscussionMessage supplies only the discussion root; getReplies loads its comments.
        Thread thread = load(account, group, root, 0, 0, 1, true, selfId, token);
        TLRPC.TL_messages_discussionMessage out = new TLRPC.TL_messages_discussionMessage();
        out.messages.addAll(thread.messages);
        out.chats.addAll(thread.chats);
        out.users.addAll(thread.users);
        int last = info.optInt("last_comment_id", 0);
        int lastClientId = last > 0 ? WaysGramChats.clientGroupId(last) : 0;
        out.max_id = lastClientId;
        out.read_inbox_max_id = lastClientId;
        out.read_outbox_max_id = lastClientId;
        out.unread_count = 0;
        return out;
    }

    // ---------------------------------------------------------------- replies

    private static TLObject replies(int account, TLRPC.TL_messages_getReplies req,
                                    long selfId, String token) throws WaysGramApi.ApiException {
        long group = groupId(req.peer);
        int root = WaysGramChats.serverGroupId(req.msg_id);
        if (root <= 0) {
            throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        }
        int limit = req.limit > 0 ? Math.min(req.limit, 100) : 100;
        int offsetId = req.offset_id > WaysGramChats.GROUP_MESSAGE_OFFSET
                ? WaysGramChats.serverGroupId(req.offset_id) : req.offset_id;
        Thread thread = load(account, group, root, offsetId, req.add_offset,
                limit, false, selfId, token);
        TLRPC.TL_messages_channelMessages out = new TLRPC.TL_messages_channelMessages();
        out.messages.addAll(thread.messages);
        out.count = thread.count;
        out.chats.addAll(thread.chats);
        out.users.addAll(thread.users);
        return out;
    }
}
