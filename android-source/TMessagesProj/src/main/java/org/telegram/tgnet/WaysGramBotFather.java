package org.telegram.tgnet;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @BotFather: a built-in chat for creating and managing bots (/api/bots on the WaysGram server).
 * The conversation is handled on the device: what the user writes is turned into server requests, and the answers
 * are shown as messages from BotFather. The chat history is kept on the device.
 */
public class WaysGramBotFather {

    public static final long ID = 93372553L;
    public static final String USERNAME = "BotFather";
    private static final int MAX_STORED = 300;

    public static boolean is(long userId) {
        return userId == ID;
    }

    public static boolean matches(String username) {
        return username != null && username.replace("@", "").trim().equalsIgnoreCase(USERNAME);
    }

    // ---------------------------------------------------------------- the user object

    public static TLRPC.TL_user user() {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = ID;
        user.access_hash = ID;
        user.flags |= TLObject.FLAG_0;
        user.first_name = "BotFather";
        user.flags |= TLObject.FLAG_1;
        user.username = USERNAME;
        user.flags |= TLObject.FLAG_3;
        user.bot = true;
        user.bot_info_version = 1;
        user.flags |= TLObject.FLAG_14;
        user.verified = true;
        user.premium = true;
        user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        user.flags |= TLObject.FLAG_5;
        return user;
    }

    // ---------------------------------------------------------------- local storage

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("waysgram_botfather", Context.MODE_PRIVATE);
    }

    private static synchronized JSONArray stored(int account) {
        try {
            return new JSONArray(prefs().getString("msgs_" + account, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static synchronized int append(int account, boolean out, String text, int date) {
        JSONArray all = stored(account);
        int id = prefs().getInt("seq_" + account, 0) + 1;
        try {
            JSONObject m = new JSONObject();
            m.put("id", id);
            m.put("out", out);
            m.put("date", date);
            m.put("text", text);
            all.put(m);
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, all.length() - MAX_STORED); i < all.length(); i++) {
                trimmed.put(all.get(i));
            }
            prefs().edit().putString("msgs_" + account, trimmed.toString()).putInt("seq_" + account, id).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return id;
    }

    public static synchronized boolean hasHistory(int account) {
        return stored(account).length() > 0;
    }

    private static String state(int account) {
        return prefs().getString("state_" + account, "");
    }

    private static void setState(int account, String state) {
        prefs().edit().putString("state_" + account, state).apply();
    }

    // ---------------------------------------------------------------- chat history and dialog

    public static ArrayList<TLRPC.Message> history(int account, long selfId, int offsetId, int minId, int limit) {
        ArrayList<TLRPC.Message> list = new ArrayList<>();
        JSONArray all = stored(account);
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m == null) {
                continue;
            }
            int id = m.optInt("id");
            if ((minId > 0 && id <= minId) || (offsetId > 0 && id >= offsetId)) {
                continue;
            }
            list.add(message(m, selfId));
        }
        Collections.sort(list, (a, b) -> Integer.compare(b.id, a.id));
        if (limit > 0 && list.size() > limit) {
            return new ArrayList<>(list.subList(0, limit));
        }
        return list;
    }

    private static TLRPC.TL_message message(JSONObject m, long selfId) {
        boolean out = m.optBoolean("out");
        return WaysGramMapper.toMessage(m.optInt("id"), out ? selfId : ID, out ? ID : selfId,
                m.optString("text"), m.optInt("date"), selfId);
    }

    /** The last message, for the chat list; null when there is no conversation yet. */
    public static TLRPC.TL_message lastMessage(int account, long selfId) {
        JSONArray all = stored(account);
        if (all.length() == 0) {
            return null;
        }
        return message(all.optJSONObject(all.length() - 1), selfId);
    }

    // ---------------------------------------------------------------- sending

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    /** The user wrote to BotFather (or pressed START: text is then "/start"). */
    public static TLObject send(int account, String text, long selfId, int pts) {
        final String input = text == null ? "" : text.trim();
        final int sentId = append(account, true, input, now());
        final ArrayList<String> replies = new ArrayList<>();
        try {
            process(account, input, replies);
        } catch (WaysGramApi.ApiException e) {
            replies.add(e.status == -1000
                    ? "Нет связи с сервером WaysGram. Попробуйте ещё раз позже."
                    : (TextUtils.isEmpty(e.detail) ? "Не получилось выполнить команду." : e.detail));
        } catch (Exception e) {
            FileLog.e(e);
            replies.add("Не получилось выполнить команду.");
        }
        final ArrayList<TLRPC.Message> delivered = new ArrayList<>();
        for (String reply : replies) {
            int id = append(account, false, reply, now());
            delivered.add(WaysGramMapper.toMessage(id, ID, selfId, reply, now(), selfId));
        }
        WaysGramApi.POLLER.schedule(() -> {
            ArrayList<TLRPC.User> users = new ArrayList<>();
            users.add(user());
            WaysGramPoller.deliver(account, users, null, delivered);
        }, 350, TimeUnit.MILLISECONDS);

        TLRPC.TL_updateShortSentMessage sent = new TLRPC.TL_updateShortSentMessage();
        sent.out = true;
        sent.flags = TLObject.FLAG_1;
        sent.id = sentId;
        sent.date = now();
        sent.pts = pts;
        sent.pts_count = 1;
        return sent;
    }

    /** startBot answers with updates (the user's own "/start" message). */
    public static TLObject start(int account, long randomId, long selfId, int pts) {
        TLRPC.TL_updateShortSentMessage sent = (TLRPC.TL_updateShortSentMessage) send(account, "/start", selfId, pts);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = sent.date;
        TL_update.TL_updateMessageID mid = new TL_update.TL_updateMessageID();
        mid.id = sent.id;
        mid.random_id = randomId;
        updates.updates.add(mid);
        TL_update.TL_updateNewMessage nm = new TL_update.TL_updateNewMessage();
        nm.message = WaysGramMapper.toMessage(sent.id, selfId, ID, "/start", sent.date, selfId);
        nm.pts = pts;
        nm.pts_count = 1;
        updates.updates.add(nm);
        updates.users.add(user());
        return updates;
    }

    // ---------------------------------------------------------------- conversation

    private static final String HELP = "Я помогаю создавать ботов WaysGram и управлять ими.\n\n"
            + "Команды:\n\n"
            + "/newbot — создать нового бота\n"
            + "/mybots — список ваших ботов\n"
            + "/token @бот — выпустить новый токен (старый перестаёт работать)\n"
            + "/setname @бот — изменить имя\n"
            + "/setabouttext @бот — краткое описание\n"
            + "/setdescription @бот — описание\n"
            + "/setcommands @бот — список команд\n"
            + "/deletebot @бот — удалить бота\n"
            + "/cancel — отменить текущее действие";

    private static void process(int account, String input, ArrayList<String> out) throws WaysGramApi.ApiException {
        String state = state(account);
        String lower = input.toLowerCase();

        if (lower.startsWith("/")) {
            String[] parts = input.split("\\s+", 2);
            String command = parts[0].toLowerCase();
            int at = command.indexOf('@');
            if (at > 0) {
                command = command.substring(0, at);
            }
            String argument = parts.length > 1 ? parts[1].trim() : "";
            setState(account, "");
            switch (command) {
                case "/start":
                case "/help":
                    out.add(HELP);
                    return;
                case "/cancel":
                    out.add("Хорошо, действие отменено.");
                    return;
                case "/newbot":
                    setState(account, "newbot_name");
                    out.add("Создаём нового бота. Как его назвать? Напишите имя бота (до 64 символов).");
                    return;
                case "/mybots":
                    listBots(account, out);
                    return;
                case "/token":
                case "/revoke":
                    withBot(account, argument, command.substring(1), out);
                    return;
                case "/setname":
                case "/setabouttext":
                case "/setdescription":
                case "/setcommands":
                case "/deletebot":
                    withBot(account, argument, command.substring(1), out);
                    return;
                default:
                    out.add("Не знаю такой команды. Список команд: /help");
                    return;
            }
        }

        if (state.equals("newbot_name")) {
            if (input.isEmpty() || input.length() > 64) {
                out.add("Имя должно содержать от 1 до 64 символов. Попробуйте ещё раз или /cancel.");
                return;
            }
            setState(account, "newbot_username:" + input);
            out.add("Теперь выберите username бота. Он должен оканчиваться на bot, например: tetris_bot или TetrisBot.");
            return;
        }
        if (state.startsWith("newbot_username:")) {
            createBot(account, state.substring("newbot_username:".length()), input, out);
            return;
        }
        if (state.startsWith("edit:")) {
            // edit:<field>:<botId>
            String[] parts = state.split(":", 3);
            applyEdit(account, parts[1], Long.parseLong(parts[2]), input, out);
            return;
        }
        if (state.startsWith("delete:")) {
            String[] parts = state.split(":", 3);
            String answer = lower.replace(".", "").trim();
            if (answer.equals("да, я уверен") || answer.equals("yes, i am totally sure")) {
                WaysGramApi.delete(account, "/api/bots/" + parts[1]);
                setState(account, "");
                out.add("Готово. Бот @" + parts[2] + " удалён.");
            } else {
                setState(account, "");
                out.add("Удаление отменено.");
            }
            return;
        }
        out.add("Чтобы начать, напишите /newbot или откройте список команд: /help");
    }

    private static void createBot(int account, String name, String typed, ArrayList<String> out) throws WaysGramApi.ApiException {
        String username = typed.trim().replace("@", "").toLowerCase();
        if (username.endsWith("bot") && !username.endsWith("_bot")) {
            username = username.substring(0, username.length() - 3) + "_bot";
        }
        if (!username.matches("[a-z][a-z0-9_]{2,28}_bot")) {
            out.add("Username должен оканчиваться на bot, содержать латиницу, цифры и _ (например, tetris_bot). Попробуйте ещё раз или /cancel.");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
            body.put("username", username);
        } catch (Exception ignore) {
        }
        JSONObject created;
        try {
            created = WaysGramApi.post(account, "/api/bots", body);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 409) {
                out.add(e.detail + ". Выберите другой username или /cancel.");
                return;
            }
            throw e;
        }
        setState(account, "");
        out.add("Готово! Бот создан: @" + created.optString("username") + "\n\n"
                + "Токен для доступа к API бота:\n" + created.optString("token") + "\n\n"
                + "Токен показывается только сейчас. Сохраните его и никому не показывайте. "
                + "Если он потеряется, выпустите новый командой /token.");
    }

    private static void listBots(int account, ArrayList<String> out) throws WaysGramApi.ApiException {
        JSONArray bots = WaysGramApi.get(account, "/api/bots").optJSONArray("bots");
        StringBuilder sb = new StringBuilder();
        if (bots != null) {
            for (int i = 0; i < bots.length(); i++) {
                JSONObject b = bots.optJSONObject(i);
                if (b != null && b.optBoolean("is_active", true)) {
                    sb.append("@").append(b.optString("username")).append(" — ").append(b.optString("name")).append("\n");
                }
            }
        }
        if (sb.length() == 0) {
            out.add("У вас пока нет ботов. Создайте первого: /newbot");
        } else {
            out.add("Ваши боты:\n\n" + sb + "\nУправление: /token @бот, /setname @бот, /setcommands @бот, /deletebot @бот");
        }
    }

    private static JSONObject findBot(int account, String reference) throws WaysGramApi.ApiException {
        String username = reference.trim().replace("@", "").toLowerCase();
        if (username.isEmpty()) {
            return null;
        }
        JSONArray bots = WaysGramApi.get(account, "/api/bots").optJSONArray("bots");
        if (bots != null) {
            for (int i = 0; i < bots.length(); i++) {
                JSONObject b = bots.optJSONObject(i);
                if (b != null && b.optBoolean("is_active", true) && username.equalsIgnoreCase(b.optString("username"))) {
                    return b;
                }
            }
        }
        return null;
    }

    private static void withBot(int account, String reference, String action, ArrayList<String> out) throws WaysGramApi.ApiException {
        if (reference.isEmpty()) {
            out.add("Укажите бота: /" + action + " @username_bot. Список ваших ботов: /mybots");
            return;
        }
        JSONObject bot = findBot(account, reference);
        if (bot == null) {
            out.add("Бот не найден среди ваших. Список: /mybots");
            return;
        }
        long id = bot.optLong("id");
        String username = bot.optString("username");
        switch (action) {
            case "token":
            case "revoke": {
                JSONObject reset = WaysGramApi.post(account, "/api/bots/" + id + "/token/reset", new JSONObject());
                out.add("Новый токен для @" + username + ":\n" + reset.optString("token") + "\n\nСтарый токен больше не работает.");
                return;
            }
            case "deletebot":
                setState(account, "delete:" + id + ":" + username);
                out.add("Вы уверены, что хотите удалить @" + username + "? Напишите «Да, я уверен.» для подтверждения или /cancel.");
                return;
            case "setname":
                setState(account, "edit:name:" + id);
                out.add("Отправьте новое имя для @" + username + ".");
                return;
            case "setabouttext":
                setState(account, "edit:about:" + id);
                out.add("Отправьте краткое описание для @" + username + " (до 120 символов).");
                return;
            case "setdescription":
                setState(account, "edit:description:" + id);
                out.add("Отправьте описание для @" + username + " (до 512 символов).");
                return;
            case "setcommands":
                setState(account, "edit:commands:" + id);
                out.add("Отправьте команды для @" + username + ", по одной в строке:\ncommand - описание\n\nНапример:\nstart - Запустить бота\nhelp - Помощь");
                return;
            default:
                out.add(HELP);
        }
    }

    private static void applyEdit(int account, String field, long botId, String input, ArrayList<String> out) throws WaysGramApi.ApiException {
        try {
            if (field.equals("commands")) {
                JSONArray commands = new JSONArray();
                for (String line : input.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    int dash = trimmed.indexOf(" - ");
                    String command = (dash > 0 ? trimmed.substring(0, dash) : trimmed).trim().replace("/", "");
                    String description = dash > 0 ? trimmed.substring(dash + 3).trim() : "";
                    JSONObject item = new JSONObject();
                    item.put("command", command);
                    item.put("description", description);
                    commands.put(item);
                }
                JSONObject body = new JSONObject();
                body.put("commands", commands);
                WaysGramApi.request("PUT", "/api/bots/" + botId + "/commands", body, WaysGramApi.getToken(account), 30000);
            } else {
                JSONObject body = new JSONObject();
                body.put(field, input);
                WaysGramApi.patch(account, "/api/bots/" + botId, body);
            }
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 400) {
                out.add(e.detail + ". Попробуйте ещё раз или /cancel.");
                return;
            }
            throw e;
        } catch (org.json.JSONException e) {
            FileLog.e(e);
            out.add("Не получилось выполнить команду.");
            return;
        }
        setState(account, "");
        out.add("Готово, изменения сохранены.");
    }
}
