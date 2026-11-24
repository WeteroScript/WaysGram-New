package org.telegram.tgnet;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_ephemeral;
import org.telegram.tgnet.tl.TL_update;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Дополнительные TL-методы канальных, групповых и общих экранов.
 *
 * <p>Раньше всё, что не относилось к диалогам, медиа и админке канала, проваливалось в
 * {@code unsupportedRequest()}: клиент получал {@code METHOD_NOT_SUPPORTED}, а пользователь —
 * bulletin «Это действие пока не поддерживается WaysGram». Так вели себя опросы (результаты и
 * голосование), раздел «Реакции», перевод публикации, «Пожаловаться», «Передать канал»,
 * тумблер «Форум», «Должность» администратора, список стран, кнопки ботов, инлайн-боты,
 * url-auth ссылки и глобальный поиск.</p>
 *
 * <p>Здесь для каждого метода есть обработчик. Где серверная функциональность есть, вызывается
 * REST-маршрут ({@code /poll-votes}, {@code /api/reports}, {@code /api/translate},
 * {@code /owner}, {@code /members/{id}/rank}, настройка {@code forum_enabled}). Где её нет по
 * определению платформы — возвращается корректный пустой ответ, а не ошибка: например,
 * глобального поиска по всем чатам сервер не индексирует, поэтому отдаётся пустая выдача, а не
 * «не поддерживается».</p>
 */
public final class WaysGramExtras {

    /** Предел выдачи участников опроса, как у Telegram. */
    private static final int POLL_VOTES_LIMIT = 50;

    private WaysGramExtras() {
    }

    // ---------------------------------------------------------------- dispatch

    /**
     * Единая точка входа: возвращает ответ для известного метода или {@code null}, чтобы
     * транспорт продолжил обычный разбор запроса.
     */
    public static TLObject handle(int account, TLObject request, long selfId, String token) throws WaysGramApi.ApiException {
        if (request instanceof TLRPC.TL_messages_getPollVotes) {
            return pollVotes(account, (TLRPC.TL_messages_getPollVotes) request, selfId, token);
        } else if (request instanceof TLRPC.TL_messages_readPollVotes
                || request instanceof TLRPC.TL_messages_readReactions) {
            return affectedHistory();
        } else if (request instanceof TLRPC.TL_messages_getUnreadPollVotes
                || request instanceof TLRPC.TL_messages_getUnreadReactions) {
            return emptySlice();
        } else if (request instanceof TLRPC.TL_messages_addPollAnswer) {
            return pollAnswer(account, ((TLRPC.TL_messages_addPollAnswer) request).peer,
                    ((TLRPC.TL_messages_addPollAnswer) request).msg_id,
                    ((TLRPC.TL_messages_addPollAnswer) request).answer,
                    selfId, token, true);
        } else if (request instanceof TLRPC.TL_messages_deletePollAnswer) {
            return pollAnswer(account, ((TLRPC.TL_messages_deletePollAnswer) request).peer,
                    ((TLRPC.TL_messages_deletePollAnswer) request).msg_id, null,
                    selfId, token, false);
        } else if (request instanceof TLRPC.TL_messages_deleteParticipantReaction) {
            TLRPC.TL_messages_deleteParticipantReaction req = (TLRPC.TL_messages_deleteParticipantReaction) request;
            removeParticipantReactions(account, req.peer, req.participant, req.msg_id, selfId, token);
            return emptyUpdates();
        } else if (request instanceof TLRPC.TL_messages_deleteParticipantReactions) {
            TLRPC.TL_messages_deleteParticipantReactions req = (TLRPC.TL_messages_deleteParticipantReactions) request;
            removeParticipantReactions(account, req.peer, req.participant, 0, selfId, token);
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_messages_translateText) {
            return translate(account, (TLRPC.TL_messages_translateText) request, selfId, token);
        } else if (request instanceof TLRPC.TL_messages_report) {
            return report(account, (TLRPC.TL_messages_report) request, selfId, token);
        } else if (request instanceof TLRPC.TL_channels_editCreator) {
            return editCreator(account, (TLRPC.TL_channels_editCreator) request, token);
        } else if (request instanceof TLRPC.TL_channels_toggleForum) {
            return toggleForum(account, (TLRPC.TL_channels_toggleForum) request, token);
        } else if (request instanceof TLRPC.TL_messages_editChatParticipantRank) {
            return editRank(account, (TLRPC.TL_messages_editChatParticipantRank) request, selfId, token);
        } else if (request instanceof TLRPC.TL_help_getCountriesList) {
            return countries();
        } else if (request instanceof TLRPC.TL_messages_getBotCallbackAnswer) {
            return botCallbackAnswer((TLRPC.TL_messages_getBotCallbackAnswer) request);
        } else if (request instanceof TLRPC.TL_messages_getInlineBotResults) {
            return emptyBotResults((TLRPC.TL_messages_getInlineBotResults) request);
        } else if (request instanceof TLRPC.TL_messages_searchGlobal) {
            // Сервер не держит общий индекс по всем чатам: честная пустая выдача вместо ошибки.
            return emptySlice();
        } else if (request instanceof TLRPC.TL_messages_requestUrlAuth) {
            // Авторизацию на стороннем сайте WaysGram не подтверждает: открываем ссылку как обычную.
            return new TLRPC.TL_urlAuthResultDefault();
        } else if (request instanceof TLRPC.TL_messages_acceptUrlAuth) {
            TLRPC.TL_messages_acceptUrlAuth req = (TLRPC.TL_messages_acceptUrlAuth) request;
            TLRPC.TL_urlAuthResultAccepted accepted = new TLRPC.TL_urlAuthResultAccepted();
            accepted.flags = TLObject.FLAG_0;
            accepted.url = req.url;
            return accepted;
        } else if (request instanceof TLRPC.TL_messages_checkUrlAuthMatchCode) {
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TL_ephemeral.TL_getWelcomeMessages) {
            return welcomeMessages(account, (TL_ephemeral.TL_getWelcomeMessages) request, selfId);
        } else if (request instanceof TL_ephemeral.TL_deleteWelcomeMessage) {
            TL_ephemeral.TL_deleteWelcomeMessage delete = (TL_ephemeral.TL_deleteWelcomeMessage) request;
            return deleteWelcomeMessage(account, delete.peer, delete.id, selfId);
        } else if (request instanceof TL_ephemeral.TL_deleteAllWelcomeMessages) {
            return deleteWelcomeMessage(account, ((TL_ephemeral.TL_deleteAllWelcomeMessages) request).peer, 0, selfId);
        } else if (request instanceof TL_ephemeral.TL_sendMessage && ((TL_ephemeral.TL_sendMessage) request).welcome) {
            return sendWelcomeMessage(account, (TL_ephemeral.TL_sendMessage) request, selfId);
        } else if (request instanceof TLRPC.TL_messages_setChatWallPaper) {
            return setChatWallpaper(account, (TLRPC.TL_messages_setChatWallPaper) request, selfId);
        } else if (request instanceof TL_account.getChatThemes) {
            // Экран «Appearance» в чате спрашивает список тем при каждом открытии. Тем в стиле
            // Telegram у WaysGram нет, но пустая выдача — это нормальный ответ, а не METHOD_NOT_SUPPORTED
            // с тостом «Это действие пока не поддерживается».
            TL_account.TL_themes themes = new TL_account.TL_themes();
            themes.hash = 0;
            return themes;
        }
        return null;
    }

    // ---------------------------------------------------------------- приветственные сообщения

    /**
     * `ephemeral.getWelcomeMessages` — экран «Welcome message» в канале и группе.
     *
     * <p>Раньше запрос не обслуживался, ответ не приходил, а загрузчик экрана ждёт именно
     * ответ, поэтому «Welcome message» крутил бесконечную загрузку. Теперь список приходит с
     * сервера (пустой список — это нормальный ответ).</p>
     */
    private static TLObject welcomeMessages(int account, TL_ephemeral.TL_getWelcomeMessages req, long selfId)
            throws WaysGramApi.ApiException {
        Target peer = target(req.peer, selfId);
        if ("dm".equals(peer.scope)) {
            TL_ephemeral.TL_welcomeMessages empty = new TL_ephemeral.TL_welcomeMessages();
            return empty;
        }
        JSONObject data = WaysGramApi.get(account, "/api/chats/" + peer.scope + "/" + peer.chatId + "/welcome");
        long hash = data.optLong("hash", 0);
        if (req.hash != 0 && req.hash == hash) {
            return new TL_ephemeral.TL_welcomeMessagesNotModified();
        }
        TL_ephemeral.TL_welcomeMessages out = new TL_ephemeral.TL_welcomeMessages();
        out.hash = hash;
        JSONArray rows = data.optJSONArray("messages");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            out.messages.add(ephemeral(account, row, peer, selfId));
        }
        return out;
    }

    /** `ephemeral.sendMessage` с флагом {@code welcome}: новое приветственное сообщение. */
    private static TLObject sendWelcomeMessage(int account, TL_ephemeral.TL_sendMessage req, long selfId)
            throws WaysGramApi.ApiException {
        Target peer = target(req.peer, selfId);
        if ("dm".equals(peer.scope)) {
            throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("message", req.message == null ? "" : req.message);
        } catch (JSONException e) {
            throw new WaysGramApi.ApiException(400, "MESSAGE_EMPTY");
        }
        JSONObject row = WaysGramApi.post(account, "/api/chats/" + peer.scope + "/" + peer.chatId + "/welcome", body);
        TL_update.TL_updateNewEphemeralMessage update = new TL_update.TL_updateNewEphemeralMessage();
        update.message = ephemeral(account, row, peer, selfId);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.updates.add(update);
        return updates;
    }

    private static TLObject deleteWelcomeMessage(int account, TLRPC.InputPeer inputPeer, int messageId, long selfId)
            throws WaysGramApi.ApiException {
        Target peer = target(inputPeer, selfId);
        if (!"dm".equals(peer.scope)) {
            String path = "/api/chats/" + peer.scope + "/" + peer.chatId + "/welcome";
            if (messageId > 0) {
                path += "?message_id=" + messageId;
            }
            WaysGramApi.delete(account, path);
        }
        return new TLRPC.TL_boolTrue();
    }

    /** Строка сервера → эфемерное сообщение, которое понимает нативный экран. */
    private static TL_ephemeral.TL_ephemeralMessage ephemeral(int account, JSONObject row, Target peer, long selfId) {
        TL_ephemeral.TL_ephemeralMessage message = new TL_ephemeral.TL_ephemeralMessage();
        message.id = row.optInt("id");
        message.out = row.optLong("author_id") == selfId;
        message.welcome = true;
        message.date = row.optInt("created_at");
        message.message = row.optString("body", "");
        long chatTgId = "group".equals(peer.scope) ? peer.chatId : WaysGramChats.channelTg(peer.chatId);
        TLRPC.TL_peerChannel chatPeer = new TLRPC.TL_peerChannel();
        chatPeer.channel_id = chatTgId;
        message.peer_id = chatPeer;
        TLRPC.TL_peerChannel fromPeer = new TLRPC.TL_peerChannel();
        fromPeer.channel_id = chatTgId;
        message.from_id = fromPeer;
        return message;
    }

    // ---------------------------------------------------------------- обои чата

    /**
     * `messages.setChatWallPaper` — обои из «Внешний вид и эмодзи».
     *
     * <p>Раньше запрос уходил в обработчик по умолчанию, то есть никуда: сервер о выбранных
     * обоях не знал, карточка чата их не возвращала, и после переоткрытия настроек фон снова
     * был стандартным. Настройки градиента/узора хранятся на сервере для этого аккаунта.</p>
     */
    private static TLObject setChatWallpaper(int account, TLRPC.TL_messages_setChatWallPaper req, long selfId)
            throws WaysGramApi.ApiException {
        Target peer = target(req.peer, selfId);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        if ("dm".equals(peer.scope)) {
            // У личных чатов обои остаются локальными: в профиле пользователя их не заводят.
            return updates;
        }
        String path = "/api/chats/" + peer.scope + "/" + peer.chatId + "/wallpaper";
        if (req.revert) {
            WaysGramApi.delete(account, path);
            return updates;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("wallpaper", wallpaperJson(req));
        } catch (JSONException e) {
            throw new WaysGramApi.ApiException(400, "WALLPAPER_INVALID");
        }
        WaysGramApi.put(account, path, body);
        return updates;
    }

    /** Настройки обоев в том же виде, в каком их вернёт карточка чата. */
    private static JSONObject wallpaperJson(TLRPC.TL_messages_setChatWallPaper req) throws JSONException {
        TLRPC.WallPaperSettings settings = req.wallpaper != null ? req.wallpaper.settings : req.settings;
        JSONObject out = new JSONObject();
        out.put("for_both", req.for_both);
        if (settings == null) {
            return out;
        }
        if (TLObject.hasFlag(settings.flags, TLObject.FLAG_0)) out.put("background_color", settings.background_color);
        if (TLObject.hasFlag(settings.flags, TLObject.FLAG_4)) {
            out.put("second_background_color", settings.second_background_color);
            out.put("rotation", settings.rotation);
        }
        if (TLObject.hasFlag(settings.flags, TLObject.FLAG_5)) out.put("third_background_color", settings.third_background_color);
        if (TLObject.hasFlag(settings.flags, TLObject.FLAG_6)) out.put("fourth_background_color", settings.fourth_background_color);
        if (TLObject.hasFlag(settings.flags, TLObject.FLAG_3)) out.put("intensity", settings.intensity);
        out.put("blur", settings.blur);
        out.put("motion", settings.motion);
        if (TLObject.hasFlag(settings.flags, TLObject.FLAG_7)) {
            out.put("emoticon", settings.emoticon == null ? "" : settings.emoticon);
        }
        return out;
    }

    // ---------------------------------------------------------------- polls

    /** Описание пира для REST-маршрутов: вид чата, серверный id и путь. */
    private static final class Target {
        final String scope;
        final long chatId;
        final String base;

        Target(String scope, long chatId, String base) {
            this.scope = scope;
            this.chatId = chatId;
            this.base = base;
        }
    }

    private static Target target(TLRPC.InputPeer peer, long selfId) throws WaysGramApi.ApiException {
        if (peer instanceof TLRPC.TL_inputPeerSelf) {
            return new Target("dm", selfId, "/api/messages");
        }
        if (peer instanceof TLRPC.TL_inputPeerUser && peer.user_id > 0) {
            return new Target("dm", peer.user_id, "/api/messages");
        }
        if (peer instanceof TLRPC.TL_inputPeerChat && peer.chat_id > 0) {
            return new Target("group", peer.chat_id, "/api/groups/" + peer.chat_id);
        }
        if (peer instanceof TLRPC.TL_inputPeerChannel) {
            // У групп id лежит ниже CHANNEL_OFFSET и совпадает с серверным, у каналов — со сдвигом.
            if (WaysGramChats.isGroupChannel(peer.channel_id)) {
                return new Target("group", peer.channel_id, "/api/groups/" + peer.channel_id);
            }
            final long server = WaysGramChats.channelServer(peer.channel_id);
            if (server <= 0) {
                throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
            }
            return new Target("channel", server, "/api/channels/" + server);
        }
        throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
    }

    /** Идентификатор сообщения на сервере: у групп он сдвинут на {@code GROUP_MESSAGE_OFFSET}. */
    private static int serverMessageId(String scope, int clientId) throws WaysGramApi.ApiException {
        int result = "group".equals(scope) ? WaysGramChats.serverGroupId(clientId) : clientId;
        if (result <= 0) {
            throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        }
        return result;
    }

    /** Путь к одному сообщению: у личных чатов он не содержит id собеседника. */
    private static String messagePath(Target target, int messageId) {
        if ("dm".equals(target.scope)) {
            return "/api/messages/" + messageId;
        }
        return target.base + "/messages/" + messageId;
    }

    /** Путь к истории сообщений конкретного пира. */
    private static String historyPath(Target target, String query) {
        if ("dm".equals(target.scope)) {
            return "/api/messages?with=" + target.chatId + (query.isEmpty() ? "" : "&" + query);
        }
        return target.base + "/messages" + (query.isEmpty() ? "" : "?" + query);
    }

    private static String pollVotesUrl(Target target, int messageId, TLRPC.TL_messages_getPollVotes req) {
        StringBuilder url = new StringBuilder(messagePath(target, messageId))
                .append("/poll-votes")
                .append("?limit=").append(POLL_VOTES_LIMIT);
        if ("dm".equals(target.scope)) {
            url.append("&with=").append(target.chatId);
        }
        if (!TextUtils.isEmpty(req.offset)) {
            url.append("&offset=").append(android.net.Uri.encode(req.offset));
        }
        String option = optionText(req.option);
        if (!TextUtils.isEmpty(option)) {
            url.append("&option=").append(android.net.Uri.encode(option));
        }
        return url.toString();
    }

    private static String optionText(byte[] option) {
        if (option == null || option.length == 0) {
            return "";
        }
        return new String(option, StandardCharsets.US_ASCII).trim();
    }

    private static byte[] optionBytes(int option) {
        return String.valueOf(option).getBytes(StandardCharsets.US_ASCII);
    }

    private static TLObject pollVotes(int account, TLRPC.TL_messages_getPollVotes req, long selfId, String token)
            throws WaysGramApi.ApiException {
        Target target = target(req.peer, selfId);
        int messageId = serverMessageId(target.scope, req.id);
        JSONObject page = WaysGramApi.request("GET", pollVotesUrl(target, messageId, req), null, token, 15000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        if (page == null) {
            throw new WaysGramApi.ApiException(502, "POLL_VOTES_RESPONSE_INVALID");
        }
        TLRPC.TL_messages_votesList result = new TLRPC.TL_messages_votesList();
        result.count = page.optInt("count", 0);
        result.next_offset = page.optString("next_offset", "");
        LinkedHashMap<Long, TLRPC.User> users = new LinkedHashMap<>();
        JSONArray list = page.optJSONArray("votes");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject vote = list.optJSONObject(i);
                if (vote == null) {
                    continue;
                }
                long voter = vote.optLong("user_id", 0);
                if (voter <= 0) {
                    continue;
                }
                TLRPC.TL_messagePeerVote entry = new TLRPC.TL_messagePeerVote();
                entry.peer = WaysGramMapper.peerUser(voter);
                entry.option = optionBytes(vote.optInt("option", 0));
                entry.date = (int) (System.currentTimeMillis() / 1000);
                result.votes.add(entry);
            }
        }
        JSONArray accounts = page.optJSONArray("users");
        if (accounts != null) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject row = accounts.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                TLRPC.TL_user user = WaysGramMapper.toUser(row);
                if (user.id > 0) {
                    users.put(user.id, user);
                }
            }
        }
        // Даже если сервер не прислал профиль (аккаунт удалён), пир должен отображаться.
        for (TLRPC.MessagePeerVote vote : result.votes) {
            long id = peerUserId(vote.peer);
            if (id > 0 && !users.containsKey(id)) {
                users.put(id, WaysGramMapper.toUser(null));
            }
        }
        result.users.addAll(users.values());
        return result;
    }

    private static long peerUserId(TLRPC.Peer peer) {
        if (peer instanceof TLRPC.TL_peerUser) {
            return peer.user_id;
        }
        return 0;
    }

    /** Голосование и отзыв голоса: тот же маршрут опросов, что и у обычных карточек. */
    private static TLObject pollAnswer(int account, TLRPC.InputPeer peer, int msgId, TLRPC.PollAnswer answer,
                                       long selfId, String token, boolean add) throws WaysGramApi.ApiException {
        Target target = target(peer, selfId);
        int messageId = serverMessageId(target.scope, msgId);
        JSONArray votes = new JSONArray();
        if (add && answer != null) {
            String option = optionText(answer.option);
            if (TextUtils.isEmpty(option)) {
                throw new WaysGramApi.ApiException(400, "OPTION_INVALID");
            }
            try {
                votes.put(Integer.parseInt(option));
            } catch (Exception e) {
                throw new WaysGramApi.ApiException(400, "OPTION_INVALID");
            }
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("votes", votes);
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "POLL_VOTE_INVALID");
        }
        WaysGramApi.request("POST", "/api/message-cards/" + target.scope + "/" + target.chatId + "/" + messageId,
                payload, token, 15000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        return emptyUpdates();
    }

    // ---------------------------------------------------------------- reactions, read markers

    private static TLRPC.TL_updates emptyUpdates() {
        return emptyUpdates();
    }

    private static TLRPC.TL_messages_affectedHistory affectedHistory() {
        TLRPC.TL_messages_affectedHistory history = new TLRPC.TL_messages_affectedHistory();
        history.pts = 0;
        history.pts_count = 0;
        history.offset = 0;
        return history;
    }

    private static TLRPC.TL_messages_messagesSlice emptySlice() {
        TLRPC.TL_messages_messagesSlice slice = new TLRPC.TL_messages_messagesSlice();
        slice.count = 0;
        slice.pts = 0;
        return slice;
    }

    /** Снятие реакции участника администратором; {@code messageId == 0} — со всех сообщений. */
    private static void removeParticipantReactions(int account, TLRPC.InputPeer peer, TLRPC.InputPeer participant,
                                                  int msgId, long selfId, String token) throws WaysGramApi.ApiException {
        Target target = target(peer, selfId);
        long userId = 0;
        if (participant instanceof TLRPC.TL_inputPeerUser) {
            userId = participant.user_id;
        } else if (participant instanceof TLRPC.TL_inputPeerSelf) {
            userId = selfId;
        }
        if (userId <= 0 || "dm".equals(target.scope)) {
            throw new WaysGramApi.ApiException(400, "PARTICIPANT_ID_INVALID");
        }
        if (msgId == 0) {
            // Без конкретного сообщения убираем реакции участника на последней странице истории.
            JSONObject page = WaysGramApi.request("GET", historyPath(target, "limit=100"), null, token, 20000);
            JSONArray messages = page == null ? null : page.optJSONArray("messages");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message != null) {
                        deleteReactions(target, message.optInt("id", 0), userId, token);
                    }
                }
            }
            return;
        }
        deleteReactions(target, serverMessageId(target.scope, msgId), userId, token);
    }

    private static void deleteReactions(Target target, int messageId, long userId, String token)
            throws WaysGramApi.ApiException {
        if (messageId <= 0) {
            return;
        }
        WaysGramApi.request("DELETE",
                messagePath(target, messageId) + "/reactions?user=" + userId, null, token, 15000);
    }

    // ---------------------------------------------------------------- translation, reports

    private static TLObject translate(int account, TLRPC.TL_messages_translateText req, long selfId, String token)
            throws WaysGramApi.ApiException {
        ArrayList<String> texts = new ArrayList<>();
        if (req.text != null) {
            for (TLRPC.TL_textWithEntities entry : req.text) {
                if (entry != null && !TextUtils.isEmpty(entry.text)) {
                    texts.add(entry.text);
                }
            }
        }
        if (texts.isEmpty() && req.peer != null && req.id != null && !req.id.isEmpty()) {
            Target target = target(req.peer, selfId);
            int messageId = serverMessageId(target.scope, req.id.get(0));
            String url = historyPath(target, "message_ids=" + messageId);
            JSONObject page = WaysGramApi.request("GET", url, null, token, 20000);
            JSONArray messages = page == null ? null : page.optJSONArray("messages");
            JSONObject row = messages == null ? null : messages.optJSONObject(0);
            String body = row == null ? "" : row.optString("body", "");
            if (!TextUtils.isEmpty(body)) {
                texts.add(body);
            }
        }
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        TLRPC.TL_messages_translateResult result = new TLRPC.TL_messages_translateResult();
        String language = TextUtils.isEmpty(req.to_lang) ? "en" : req.to_lang;
        for (String text : texts) {
            JSONObject payload = new JSONObject();
            try {
                payload.put("text", text).put("to_language", language);
            } catch (Exception e) {
                throw new WaysGramApi.ApiException(400, "TRANSLATE_REQUEST_INVALID");
            }
            JSONObject response = WaysGramApi.request("POST", "/api/translate", payload, token, 90000);
            TLRPC.TL_textWithEntities entry = new TLRPC.TL_textWithEntities();
            entry.text = response == null ? "" : response.optString("text", "");
            result.result.add(entry);
        }
        return result;
    }

    private static TLObject report(int account, TLRPC.TL_messages_report req, long selfId, String token)
            throws WaysGramApi.ApiException {
        Target target = target(req.peer, selfId);
        JSONArray ids = new JSONArray();
        if (req.id != null) {
            for (Integer id : req.id) {
                if (id == null || id <= 0) {
                    continue;
                }
                int serverId;
                try {
                    serverId = serverMessageId(target.scope, id);
                } catch (WaysGramApi.ApiException ignored) {
                    // Некорректный id пропускаем: жалоба на остальные всё равно уйдёт.
                    continue;
                }
                try {
                    ids.put(serverId);
                } catch (Exception e) {
                    throw new WaysGramApi.ApiException(400, "REPORT_REQUEST_INVALID");
                }
            }
        }
        if (ids.length() == 0) {
            throw new WaysGramApi.ApiException(400, "MESSAGE_ID_INVALID");
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("scope", target.scope)
                    .put("peer_id", target.chatId)
                    .put("message_ids", ids)
                    .put("option", optionText(req.option))
                    .put("message", req.message == null ? "" : req.message);
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "REPORT_REQUEST_INVALID");
        }
        WaysGramApi.request("POST", "/api/reports", payload, token, 20000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        return new TLRPC.TL_reportResultReported();
    }

    // ---------------------------------------------------------------- channel administration

    private static Target channelTarget(long channelTgId) throws WaysGramApi.ApiException {
        if (WaysGramChats.isGroupChannel(channelTgId)) {
            return new Target("group", channelTgId, "/api/groups/" + channelTgId);
        }
        final long server = WaysGramChats.channelServer(channelTgId);
        if (server <= 0) {
            throw new WaysGramApi.ApiException(400, "CHANNEL_INVALID");
        }
        return new Target("channel", server, "/api/channels/" + server);
    }

    private static TLObject editCreator(int account, TLRPC.TL_channels_editCreator req, String token)
            throws WaysGramApi.ApiException {
        if (req.channel == null || req.user_id == null) {
            throw new WaysGramApi.ApiException(400, "CHANNEL_INVALID");
        }
        Target target = channelTarget(req.channel.channel_id);
        long userId;
        if (req.user_id instanceof TLRPC.TL_inputUserSelf) {
            userId = 0;
        } else {
            userId = req.user_id.user_id;
        }
        if (userId <= 0) {
            throw new WaysGramApi.ApiException(400, "USER_ID_INVALID");
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("telegram_id", userId);
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "USER_ID_INVALID");
        }
        WaysGramApi.request("PUT", target.base + "/owner", payload, token, 20000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        return emptyUpdates();
    }

    private static TLObject toggleForum(int account, TLRPC.TL_channels_toggleForum req, String token)
            throws WaysGramApi.ApiException {
        if (req.channel == null) {
            throw new WaysGramApi.ApiException(400, "CHANNEL_INVALID");
        }
        Target target = channelTarget(req.channel.channel_id);
        JSONObject payload = new JSONObject();
        try {
            payload.put("forum_enabled", req.enabled);
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "CHANNEL_SETTINGS_INVALID");
        }
        WaysGramApi.request("PUT", target.base + "/settings", payload, token, 15000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        return emptyUpdates();
    }

    private static TLObject editRank(int account, TLRPC.TL_messages_editChatParticipantRank req, long selfId, String token)
            throws WaysGramApi.ApiException {
        Target target = target(req.peer, selfId);
        long userId = 0;
        if (req.participant instanceof TLRPC.TL_inputPeerUser) {
            userId = req.participant.user_id;
        } else if (req.participant instanceof TLRPC.TL_inputPeerSelf) {
            userId = selfId;
        }
        if (userId <= 0 || "dm".equals(target.scope)) {
            throw new WaysGramApi.ApiException(400, "PARTICIPANT_ID_INVALID");
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("rank", req.rank == null ? "" : req.rank);
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "RANK_INVALID");
        }
        WaysGramApi.request("PUT", target.base + "/members/" + userId + "/rank", payload, token, 15000);
        if (!TextUtils.equals(token, WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        return emptyUpdates();
    }

    // ---------------------------------------------------------------- misc

    /**
     * Коды стран для экрана входа.
     *
     * <p>Официальный клиент спрашивает список у сервера; WaysGram отдаёт небольшой статический
     * набор вместо ошибки. Номер можно ввести и вручную, так что список лишь ускоряет ввод.</p>
     */
    private static TLRPC.TL_help_countriesList countries() {
        String[][] data = {
                {"RU", "Россия", "7", "7"},
                {"KZ", "Казахстан", "7", "7"},
                {"UA", "Украина", "380", "380"},
                {"BY", "Беларусь", "375", "375"},
                {"UZ", "Узбекистан", "998", "998"},
                {"KG", "Кыргызстан", "996", "996"},
                {"AM", "Армения", "374", "374"},
                {"GE", "Грузия", "995", "995"},
                {"AZ", "Азербайджан", "994", "994"},
                {"MD", "Молдова", "373", "373"},
                {"PL", "Польша", "48", "48"},
                {"DE", "Германия", "49", "49"},
                {"FR", "Франция", "33", "33"},
                {"GB", "Великобритания", "44", "44"},
                {"US", "США", "1", "1"},
                {"TR", "Турция", "90", "90"},
                {"AE", "ОАЭ", "971", "971"},
                {"IL", "Израиль", "972", "972"},
                {"ES", "Испания", "34", "34"},
                {"IT", "Италия", "39", "39"},
                {"NL", "Нидерланды", "31", "31"},
                {"CZ", "Чехия", "420", "420"},
                {"LV", "Латвия", "371", "371"},
                {"LT", "Литва", "370", "370"},
                {"EE", "Эстония", "372", "372"},
        };
        TLRPC.TL_help_countriesList list = new TLRPC.TL_help_countriesList();
        for (String[] row : data) {
            TLRPC.TL_help_country country = new TLRPC.TL_help_country();
            country.iso2 = row[0];
            country.default_name = row[1];
            country.flags |= TLObject.FLAG_0;
            TLRPC.TL_help_countryCode code = new TLRPC.TL_help_countryCode();
            code.country_code = row[2];
            code.flags |= TLObject.FLAG_0;
            code.prefixes.add(row[3]);
            country.country_codes.add(code);
            list.countries.add(country);
        }
        list.hash = data.length;
        return list;
    }

    /**
     * Ответ на нажатие кнопки бота.
     *
     * <p>Серверной обработки bot-callback у WaysGram нет. Если данные кнопки — это URL, отдаём
     * его клиенту (кнопка открывает ссылку как обычно). Иначе возвращаем пустой ответ: экран
     * остаётся без изменений и без «функция не поддерживается».</p>
     */
    private static TLRPC.TL_messages_botCallbackAnswer botCallbackAnswer(TLRPC.TL_messages_getBotCallbackAnswer req) {
        TLRPC.TL_messages_botCallbackAnswer answer = new TLRPC.TL_messages_botCallbackAnswer();
        answer.cache_time = 0;
        if (req.data != null && req.data.length > 0) {
            String data = new String(req.data, StandardCharsets.UTF_8).trim();
            if (data.startsWith("http://") || data.startsWith("https://")) {
                answer.flags |= TLObject.FLAG_1;
                answer.has_url = true;
                answer.url = data;
            }
        }
        return answer;
    }

    private static TLRPC.TL_messages_botResults emptyBotResults(TLRPC.TL_messages_getInlineBotResults req) {
        TLRPC.TL_messages_botResults results = new TLRPC.TL_messages_botResults();
        // Запрос не содержит query_id: его выдаёт сервер. Пустая выдача — «ничего не найдено».
        results.query_id = 0;
        results.cache_time = 0;
        return results;
    }
}
