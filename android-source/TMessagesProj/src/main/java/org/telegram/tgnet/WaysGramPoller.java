package org.telegram.tgnet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deliver new DMs from the event stream; use history sweeps for initial catch-up and other chat types.
 */
public class WaysGramPoller {

    private static final long RETRY_DELAY_MS = 2500;
    private static final int EVENT_WAIT_SECONDS = 15;
    private static final java.util.concurrent.atomic.AtomicIntegerArray generations =
            new java.util.concurrent.atomic.AtomicIntegerArray(UserConfig.MAX_ACCOUNT_COUNT);

    private static final AtomicBoolean[] running = new AtomicBoolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static final HashMap<Long, Integer>[] lastSeen = new HashMap[UserConfig.MAX_ACCOUNT_COUNT];
    private static final HashMap<Long, Integer>[] chatSeen = new HashMap[UserConfig.MAX_ACCOUNT_COUNT];
    private static final HashSet<Long>[] delivered = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];

    private static synchronized AtomicBoolean flag(int account) {
        if (running[account] == null) {
            running[account] = new AtomicBoolean(false);
            lastSeen[account] = new HashMap<>();
            chatSeen[account] = new HashMap<>();
            delivered[account] = new HashSet<>();
        }
        return running[account];
    }

    private static long key(long dialogId, int messageId) {
        return dialogId * 4294967296L + messageId;
    }

    public static synchronized void start(int account) {
        org.telegram.messenger.waysgram.WaysGramPushManager.sync(org.telegram.messenger.ApplicationLoader.applicationContext, account);
        AtomicBoolean f = flag(account);
        if (f.compareAndSet(false, true)) {
            synchronized (lastSeen[account]) {
                lastSeen[account].clear();
                chatSeen[account].clear();
            }
            WaysGramTyping.start(account);
            schedule(account, 0, true, generations.incrementAndGet(account));
        }
    }

    public static synchronized void stop(int account) {
        flag(account).set(false);
        generations.incrementAndGet(account);
        WaysGramTyping.stop(account);
    }

    static boolean isRunning(int account) {
        return flag(account).get();
    }

    /**
     * Remember messages the app already knows about (it sent them), so the poller does not deliver them again.
     * dialogId: the user id for private chats, minus the chat id for groups and channels.
     */
    public static void markDelivered(int account, long dialogId, int messageId) {
        flag(account);
        synchronized (delivered[account]) {
            delivered[account].add(key(dialogId, messageId));
        }
    }

    private static boolean firstDelivery(int account, long dialogId, int messageId) {
        synchronized (delivered[account]) {
            return delivered[account].add(key(dialogId, messageId));
        }
    }

    private static void schedule(int account, long delay, boolean first, int generation) {
        WaysGramApi.POLLER.schedule(() -> {
            if (!isActive(account, generation)) return;
            WaysGramTransport.schedulePendingPinRecovery(account);
            long nextDelay = RETRY_DELAY_MS;
            boolean nextFirst = first;
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                long selfId = UserConfig.getInstance(account).getClientUserId();
                if (selfId != 0) {
                    pollEvents(account, selfId, first, generation);
                    nextFirst = false;
                    // Guard against a busy loop if an older server ignores wait_seconds.
                    nextDelay = Math.max(0, 200 - (android.os.SystemClock.elapsedRealtime() - started));
                }
            } catch (WaysGramApi.ApiException e) {
                if (e.status == 401) {
                    synchronized (WaysGramPoller.class) {
                        if (isActive(account, generation)) stop(account);
                    }
                    return;
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
            if (isActive(account, generation)) schedule(account, nextDelay, nextFirst, generation);
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static boolean isActive(int account, int generation) {
        return flag(account).get() && generations.get(account) == generation;
    }

    private static void refreshMessages(int account, boolean first, long selfId, String token, int generation) throws WaysGramApi.ApiException {
        requireEventSession(account, selfId, token, generation);
        pollPrivate(account, first, selfId);
        requireEventSession(account, selfId, token, generation);
        pollGroups(account, first, selfId);
        requireEventSession(account, selfId, token, generation);
        pollChannels(account, first, selfId);
    }

    // ---------------------------------------------------------------- private chats

    private static void pollPrivate(int account, boolean first, long selfId) throws WaysGramApi.ApiException {
        JSONObject json = WaysGramApi.get(account, "/api/conversations");
        JSONArray items = json.optJSONArray("conversations");
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject c = items.optJSONObject(i);
            if (c == null) {
                continue;
            }
            long peerId = c.optLong("id");
            int lastId = c.optInt("last_message_id");
            Integer known;
            synchronized (lastSeen[account]) {
                known = lastSeen[account].get(peerId);
                if (known == null) {
                    // History may still be loading (or cached) when polling starts.
                    // Do not mark unread messages as seen before fetching them.
                    if (first && c.optInt("unread_count") == 0) {
                        lastSeen[account].put(peerId, lastId);
                        continue;
                    }
                    known = first ? c.optInt("my_read_id", 0) : 0;
                }
            }
            if (lastId <= known) {
                continue;
            }
            JSONObject page = WaysGramApi.get(account, "/api/messages?with=" + peerId + "&after_id=" + known);
            JSONArray messages = page.optJSONArray("messages");
            if (messages == null) {
                continue;
            }
            ArrayList<TLRPC.Message> fresh = new ArrayList<>();
            int maxId = known;
            for (int j = 0; j < messages.length(); j++) {
                JSONObject m = messages.optJSONObject(j);
                if (m == null) {
                    continue;
                }
                int id = m.optInt("id");
                maxId = Math.max(maxId, id);
                TLRPC.Message message = WaysGramMedia.attach(account, WaysGramMapper.toMessage(m, selfId, account), m);
                fresh.add(message);
            }
            fresh.removeIf(message -> !firstDelivery(account, peerId, message.id));
            synchronized (lastSeen[account]) {
                lastSeen[account].put(peerId, maxId);
            }
            if (!fresh.isEmpty()) {
                ArrayList<TLRPC.User> users = new ArrayList<>();
                users.add(WaysGramMapper.toUser(c));
                deliver(account, users, null, fresh);
            }
        }
    }

    // ---------------------------------------------------------------- groups and channels

    private static int maxId(JSONArray messages) {
        int max = 0;
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m != null) {
                    max = Math.max(max, m.optInt("id"));
                }
            }
        }
        return max;
    }

    private static void pollGroups(int account, boolean first, long selfId) throws WaysGramApi.ApiException {
        JSONArray groups = WaysGramApi.get(account, "/api/groups").optJSONArray("groups");
        if (groups == null) {
            return;
        }
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) {
                continue;
            }
            long id = g.optLong("id");
            long dialogId = -id;
            Integer known;
            synchronized (chatSeen[account]) {
                known = chatSeen[account].get(dialogId);
            }
            if (known == null) {
                // Learn the current position quietly (or start from zero for a group that appeared later).
                int latest = g.optInt("message_count") > 0
                        ? maxId(WaysGramApi.get(account, "/api/groups/" + id + "/messages").optJSONArray("messages")) : 0;
                synchronized (chatSeen[account]) {
                    chatSeen[account].put(dialogId, latest);
                }
                if (first || latest == 0) {
                    continue;
                }
                known = 0;
            }
            if (g.optInt("message_count") <= 0) {
                continue;
            }
            JSONArray messages = WaysGramApi.get(account, "/api/groups/" + id + "/messages?after_id=" + known).optJSONArray("messages");
            int newMax = known;
            ArrayList<TLRPC.Message> fresh = new ArrayList<>();
            if (messages != null) {
                for (int j = 0; j < messages.length(); j++) {
                    JSONObject m = messages.optJSONObject(j);
                    if (m == null) {
                        continue;
                    }
                    int mid = m.optInt("id");
                    newMax = Math.max(newMax, mid);
                    if (firstDelivery(account, dialogId, mid)) {
                        fresh.add(WaysGramMedia.attach(account, WaysGramChats.groupMessage(m, id, selfId, account), m));
                    }
                }
            }
            synchronized (chatSeen[account]) {
                chatSeen[account].put(dialogId, newMax);
            }
            if (!fresh.isEmpty()) {
                WaysGramChats.Snapshot snapshot = new WaysGramChats.Snapshot();
                JSONObject details = WaysGramApi.get(account, "/api/groups/" + id);
                snapshot.addUsers(details.optJSONArray("members"));
                ArrayList<TLRPC.Chat> chats = new ArrayList<>();
                chats.add(WaysGramChats.toMegagroup(account, details, selfId));
                deliver(account, snapshot.users, chats, fresh);
            }
        }
    }

    private static void pollChannels(int account, boolean first, long selfId) throws WaysGramApi.ApiException {
        JSONArray channels = WaysGramApi.get(account, "/api/channels").optJSONArray("channels");
        if (channels == null) {
            return;
        }
        WaysGramChats.cacheListedChannelMembers(account, channels);
        HashSet<Long> joined = WaysGramChats.joinedChannels(account);
        for (int i = 0; i < channels.length(); i++) {
            JSONObject c = channels.optJSONObject(i);
            if (c == null) {
                continue;
            }
            long serverId = c.optLong("id");
            if (c.optLong("owner_telegram_id") != selfId && !WaysGramChats.listedChannelMember(c, joined)) {
                continue;
            }
            long tg = WaysGramChats.channelTg(serverId);
            long dialogId = -tg;
            Integer known;
            synchronized (chatSeen[account]) {
                known = chatSeen[account].get(dialogId);
            }
            if (known == null) {
                int latest = maxId(WaysGramApi.get(account, "/api/channels/" + serverId + "/messages").optJSONArray("messages"));
                synchronized (chatSeen[account]) {
                    chatSeen[account].put(dialogId, latest);
                }
                if (first || latest == 0) {
                    continue;
                }
                known = 0;
            }
            JSONArray messages = WaysGramApi.get(account, "/api/channels/" + serverId + "/messages?after_id=" + known).optJSONArray("messages");
            int newMax = known;
            ArrayList<TLRPC.Message> fresh = new ArrayList<>();
            if (messages != null) {
                for (int j = 0; j < messages.length(); j++) {
                    JSONObject m = messages.optJSONObject(j);
                    if (m == null) {
                        continue;
                    }
                    int mid = m.optInt("id");
                    newMax = Math.max(newMax, mid);
                    if (firstDelivery(account, dialogId, mid)) {
                        fresh.add(WaysGramMedia.attach(account, WaysGramChats.channelMessage(m, tg, selfId), m));
                    }
                }
            }
            synchronized (chatSeen[account]) {
                chatSeen[account].put(dialogId, newMax);
            }
            if (!fresh.isEmpty()) {
                ArrayList<TLRPC.Chat> chats = new ArrayList<>();
                chats.add(WaysGramChats.toChannel(account, c, selfId, true));
                deliver(account, new ArrayList<>(), chats, fresh);
            }
        }
    }

    // ---------------------------------------------------------------- edits, deletions and read marks

    private static android.content.SharedPreferences prefs() {
        return org.telegram.messenger.ApplicationLoader.applicationContext
                .getSharedPreferences("waysgram_session", android.content.Context.MODE_PRIVATE);
    }

    /** Changes made by other people: edited and deleted messages, and "read" marks of the people we wrote to. */
    private static boolean sameEventSession(int account, long selfId, String token) {
        return !android.text.TextUtils.isEmpty(token)
                && android.text.TextUtils.equals(token, WaysGramApi.getToken(account))
                && UserConfig.getInstance(account).getClientUserId() == selfId;
    }

    private static void requireEventSession(int account, long selfId, String token, int generation) throws WaysGramApi.ApiException {
        if (!isActive(account, generation) || !sameEventSession(account, selfId, token)) throw new WaysGramApi.ApiException(-1000, "SESSION_CHANGED");
    }

    private static boolean deliverDirectEvent(int account, long selfId, String token, int generation, JSONObject event) throws WaysGramApi.ApiException {
        long a = event.optLong("user_a"), b = event.optLong("user_b");
        long peer = a == selfId ? b : b == selfId ? a : 0;
        int id = event.optInt("message_id");
        if (peer <= 0 || id <= 0) return false;
        JSONObject result = WaysGramApi.request("GET", "/api/messages?with=" + peer + "&message_ids=" + id + "&include_peer=true", null, token, 10000);
        requireEventSession(account, selfId, token, generation);
        JSONArray messages = result.optJSONArray("messages");
        JSONObject row = messages != null ? messages.optJSONObject(0) : null;
        if (row == null || row.optInt("id") != id) return false;
        JSONObject profile = result.optJSONObject("peer");
        TLRPC.User user = profile != null ? WaysGramMapper.toUser(profile) : UserConfig.getInstance(account).getCurrentUser();
        if (user == null || user.id != peer) {
            user = AccountInstance.getInstance(account).getMessagesController().getUser(peer);
        }
        if (user == null) return false; // Older server: let the history sweep load the user and message together.
        TLRPC.Message message = WaysGramMedia.attach(account, WaysGramMapper.toMessage(row, selfId, account), row);
        final TLRPC.User sender = user;
        Utilities.stageQueue.postRunnable(() -> {
            if (!isActive(account, generation) || !sameEventSession(account, selfId, token)) return;
            if (!firstDelivery(account, peer, id)) return;
            ArrayList<TLRPC.User> users = new ArrayList<>();
            users.add(sender);
            MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
            MessagesController controller = AccountInstance.getInstance(account).getMessagesController();
            controller.putUser(sender, false);
            TLRPC.TL_updateShort wrapper = new TLRPC.TL_updateShort();
            wrapper.update = WaysGramMapper.newMessageUpdate(message, 0);
            wrapper.date = message.date;
            controller.processUpdates(wrapper, false);
        });
        return true;
    }

    private static void pollEvents(int account, long selfId, boolean first, int generation) throws WaysGramApi.ApiException {
        String token = WaysGramApi.getToken(account);
        requireEventSession(account, selfId, token, generation);
        String key = "events_" + account + "_" + selfId;
        if (!prefs().contains(key)) {
            // First start: remember where the server is now; older changes are already part of the history.
            JSONObject initial = WaysGramApi.request("GET", "/api/events", null, token, 30000);
            requireEventSession(account, selfId, token, generation);
            refreshMessages(account, first, selfId, token, generation);
            requireEventSession(account, selfId, token, generation);
            prefs().edit().putLong(key, initial.optLong("latest_id")).apply();
            return;
        }
        long cursor = prefs().getLong(key, 0);
        JSONObject json = WaysGramApi.request("GET", "/api/events?after_id=" + cursor + "&wait_seconds=" + (first ? 0 : EVENT_WAIT_SECONDS), null, token, 30000);
        requireEventSession(account, selfId, token, generation);
        JSONArray events = json.optJSONArray("events");
        boolean sweep = first || events == null || events.length() == 0;
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        java.util.HashSet<Long> joined = WaysGramChats.joinedChannels(account);
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                try {
                    requireEventSession(account, selfId, token, generation);
                    if ("new_message".equals(e.optString("kind"))) {
                        if (!("dm".equals(e.optString("scope")) && deliverDirectEvent(account, selfId, token, generation, e))) sweep = true;
                    } else {
                        handleEvent(account, selfId, token, e, joined, updates);
                    }
                } catch (WaysGramApi.ApiException ex) {
                    sweep = true;
                    if (ex.status == 401 || ex.status == -1000 || ex.status >= 500) {
                        throw ex; // try again on the next round without losing the event
                    }
                }
            }
        }
        requireEventSession(account, selfId, token, generation);
        if (!updates.isEmpty()) {
            MessagesController controller = AccountInstance.getInstance(account).getMessagesController();
            Utilities.stageQueue.postRunnable(() -> {
                if (!isActive(account, generation) || !sameEventSession(account, selfId, token)) return;
                for (TLRPC.Update update : updates) {
                    if (!isActive(account, generation) || !sameEventSession(account, selfId, token)) return;
                    TLRPC.TL_updateShort wrapper = new TLRPC.TL_updateShort();
                    wrapper.update = update;
                    wrapper.date = (int) (System.currentTimeMillis() / 1000);
                    controller.processUpdates(wrapper, false);
                }
            });
        }
        requireEventSession(account, selfId, token, generation);
        prefs().edit().putLong(key, json.optLong("latest_id", cursor)).apply();
        if (sweep) refreshMessages(account, first, selfId, token, generation);
    }

    private static void handleEvent(int account, long selfId, String token, JSONObject e, java.util.HashSet<Long> joined, ArrayList<TLRPC.Update> out) throws WaysGramApi.ApiException {
        String scope = e.optString("scope");
        String kind = e.optString("kind");
        int messageId = e.optInt("message_id");
        long actor = e.optLong("actor_id");
        if (scope.equals("scheduled")) {
            long peerId = e.optLong("chat_id");
            if (peerId <= 0 || messageId < 2) return;
            TLRPC.Peer peer = WaysGramMapper.peerUser(peerId);
            if (kind.equals("scheduled_removed")) {
                TL_update.TL_updateDeleteScheduledMessages removed = new TL_update.TL_updateDeleteScheduledMessages();
                removed.peer = peer; removed.messages.add(messageId); out.add(removed);
            } else if (kind.equals("scheduled_changed")) {
                TLRPC.TL_inputPeerUser input = new TLRPC.TL_inputPeerUser(); input.user_id = peerId;
                ArrayList<Integer> ids = new ArrayList<>(); ids.add(messageId);
                TLRPC.TL_messages_messages page = WaysGramScheduled.list(account, input, selfId, token, ids);
                if (!page.messages.isEmpty()) {
                    TL_update.TL_updateNewScheduledMessage update = new TL_update.TL_updateNewScheduledMessage();
                    update.message = page.messages.get(0); out.add(update);
                }
            }
            return;
        }
        if (kind.equals("chat_changed")) {
            // Права, аватар, название или состав чата изменились на другом устройстве.
            WaysGramChats.chatChanged(account, scope, e.optLong("chat_id"));
            return;
        }
        if (kind.equals("pin") || kind.equals("unpin") || kind.equals("pin_local") || kind.equals("unpin_local")) {
            long dialogId;
            if (scope.equals("dm")) {
                long userA = e.optLong("user_a"), userB = e.optLong("user_b");
                dialogId = userA == selfId ? userB : userA;
            } else if (scope.equals("group")) {
                dialogId = -e.optLong("chat_id");
            } else if (scope.equals("channel")) {
                long id = e.optLong("chat_id");
                if (!joined.contains(id) && WaysGramChats.cachedChat(account, WaysGramChats.channelTg(id)) == null) return;
                dialogId = -WaysGramChats.channelTg(id);
            } else return;
            if (dialogId == 0) return;
            AndroidUtilities.runOnUIThread(() -> {
                if (android.text.TextUtils.equals(token, WaysGramApi.getToken(account)))
                    MediaDataController.getInstance(account).loadPinnedMessages(dialogId, 0, 0);
            });
            return;
        }
        if (scope.equals("dm")) {
            if (kind.equals("reaction")) {
                long userA = e.optLong("user_a"), userB = e.optLong("user_b");
                long other = userA == selfId ? userB : userA;
                if (other == 0) return;
                TLRPC.Message message = WaysGramMessages.fetchPrivate(account, other, messageId, selfId);
                if (message != null) addReactionUpdate(out, WaysGramMapper.peerUser(other), message);
            } else if (kind.equals("edit")) {
                long userA = e.optLong("user_a"), userB = e.optLong("user_b");
                long other = userA == selfId ? userB : userA;
                if (other == 0) other = actor;
                TLRPC.Message message = WaysGramMessages.fetchPrivate(account, other, messageId, selfId);
                if (message != null) out.add(WaysGramCards.update(message));
            } else if (kind.equals("delete") || kind.equals("delete_for_me") || kind.equals("delete_for_all_sync")) {
                TL_update.TL_updateDeleteMessages u = new TL_update.TL_updateDeleteMessages();
                u.messages.add(messageId);
                out.add(u);
            } else if (kind.equals("read")) {
                TL_update.TL_updateReadHistoryOutbox u = new TL_update.TL_updateReadHistoryOutbox();
                u.peer = WaysGramMapper.peerUser(actor);
                u.max_id = messageId;
                out.add(u);
            }
        } else if (scope.equals("group")) {
            long groupId = e.optLong("chat_id");
            if (kind.equals("reaction")) {
                TLRPC.Message message = WaysGramMessages.fetchGroup(account, groupId, messageId, selfId);
                if (message != null) addReactionUpdate(out, message.peer_id, message);
            } else if (kind.equals("edit")) {
                TLRPC.Message message = WaysGramMessages.fetchGroup(account, groupId, messageId, selfId);
                if (message != null) out.add(WaysGramCards.update(message));
            } else if (kind.equals("delete")) {
                TL_update.TL_updateDeleteChannelMessages u = new TL_update.TL_updateDeleteChannelMessages();
                u.channel_id = groupId;
                u.messages.add(WaysGramChats.clientGroupId(messageId));
                out.add(u);
            }
        } else if (scope.equals("channel")) {
            long serverId = e.optLong("chat_id");
            if (!joined.contains(serverId) && !(kind.equals("reaction")
                    && WaysGramChats.cachedChat(account, WaysGramChats.channelTg(serverId)) != null)) {
                return;
            }
            if (kind.equals("reaction")) {
                TLRPC.Message message = WaysGramMessages.fetchChannel(account, serverId, messageId, selfId);
                if (message != null) addReactionUpdate(out, message.peer_id, message);
            } else if (kind.equals("edit")) {
                TLRPC.Message message = WaysGramMessages.fetchChannel(account, serverId, messageId, selfId);
                if (message != null) out.add(WaysGramCards.update(message));
            } else if (kind.equals("delete")) {
                TL_update.TL_updateDeleteChannelMessages u = new TL_update.TL_updateDeleteChannelMessages();
                u.channel_id = WaysGramChats.channelTg(serverId);
                u.messages.add(messageId);
                out.add(u);
            }
        }
    }

    private static void addReactionUpdate(ArrayList<TLRPC.Update> out, TLRPC.Peer peer, TLRPC.Message message) {
        TL_update.TL_updateMessageReactions update = new TL_update.TL_updateMessageReactions();
        update.peer = peer;
        update.msg_id = message.id;
        // An empty list is a real update: it removes the final reaction.
        update.reactions = message.reactions != null ? message.reactions : new TLRPC.TL_messageReactions();
        update.updateUnreadState = false;
        out.add(update);
    }

    static void deliverUpdates(int account, ArrayList<TLRPC.Update> updates) {
        MessagesController controller = AccountInstance.getInstance(account).getMessagesController();
        Utilities.stageQueue.postRunnable(() -> {
            for (TLRPC.Update update : updates) {
                TLRPC.TL_updateShort wrapper = new TLRPC.TL_updateShort();
                wrapper.update = update;
                wrapper.date = (int) (System.currentTimeMillis() / 1000);
                controller.processUpdates(wrapper, false);
            }
        });
    }

    // ---------------------------------------------------------------- delivery

    static void deliver(int account, ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats, ArrayList<TLRPC.Message> messages) {
        MessagesStorage.getInstance(account).putUsersAndChats(users, chats, true, true);
        MessagesController controller = AccountInstance.getInstance(account).getMessagesController();
        for (TLRPC.User user : users) {
            controller.putUser(user, false);
        }
        if (chats != null) {
            for (TLRPC.Chat chat : chats) {
                controller.putChat(chat, false);
            }
        }
        Utilities.stageQueue.postRunnable(() -> {
            for (TLRPC.Message message : messages) {
                TLRPC.TL_updateShort update = new TLRPC.TL_updateShort();
                if (message.peer_id instanceof TLRPC.TL_peerChannel) {
                    TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
                    u.message = message;
                    u.pts = 0;
                    u.pts_count = 0;
                    update.update = u;
                } else {
                    update.update = WaysGramMapper.newMessageUpdate(message, 0);
                }
                update.date = message.date;
                controller.processUpdates(update, false);
            }
        });
    }
}
