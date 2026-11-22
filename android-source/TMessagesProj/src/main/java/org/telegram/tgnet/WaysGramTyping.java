package org.telegram.tgnet;

import android.os.SystemClock;
import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.tl.TL_update;
import java.util.ArrayList;
import java.util.HashMap;

/** Ephemeral typing: scoped server snapshot, no persistent event or push notification. */
public final class WaysGramTyping {
    private static final int MAX_PAGES = 40; // per poll; unfinished scans resume rather than starving later chats
    private static final HashMap<String, Entry>[] previous = new HashMap[UserConfig.MAX_ACCOUNT_COUNT];
    private static final HashMap<String, Entry>[] collected = new HashMap[UserConfig.MAX_ACCOUNT_COUNT];
    private static final String[] resumeScope = new String[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] resumePeer = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] resumeUser = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final int[] generation = new int[UserConfig.MAX_ACCOUNT_COUNT];
    private static final java.util.concurrent.ScheduledExecutorService[] schedulers =
            new java.util.concurrent.ScheduledExecutorService[UserConfig.MAX_ACCOUNT_COUNT];

    private static final class Entry {
        final String scope;
        final long peer, user;
        final JSONObject json;
        final long deadline;
        Entry(String scope, long peer, long user, JSONObject json, long deadline) {
            this.scope = scope;
            this.peer = peer;
            this.user = user;
            this.json = json;
            this.deadline = deadline;
        }
        String key() { return scope + ":" + peer + ":" + user; }
    }

    public static synchronized void start(int account) {
        generation[account]++;
        previous[account] = new HashMap<>();
        collected[account] = new HashMap<>();
        resumeScope[account] = "";
        resumePeer[account] = resumeUser[account] = 0;
        if (schedulers[account] == null) {
            schedulers[account] = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "waysgram-typing-poller-" + account);
                thread.setDaemon(true);
                return thread;
            });
        }
        schedule(account, generation[account], 0);
    }

    public static synchronized void stop(int account) {
        generation[account]++;
        previous[account] = new HashMap<>();
        collected[account] = new HashMap<>();
        resumeScope[account] = "";
        resumePeer[account] = resumeUser[account] = 0;
    }

    private static void schedule(int account, int expectedGeneration, long delay) {
        schedulers[account].schedule(() -> {
            synchronized (WaysGramTyping.class) {
                if (generation[account] != expectedGeneration) return;
            }
            try {
                long selfId = UserConfig.getInstance(account).getClientUserId();
                if (selfId != 0 && !TextUtils.isEmpty(WaysGramApi.getToken(account))) poll(account, selfId, expectedGeneration);
            } catch (WaysGramApi.ApiException e) {
                // Old servers, token refresh and transient errors must not interrupt message polling.
            } catch (Throwable t) {
                org.telegram.messenger.FileLog.e(t);
            }
            synchronized (WaysGramTyping.class) {
                if (generation[account] == expectedGeneration) schedule(account, expectedGeneration, 2500);
            }
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public static TLObject send(int account, TLRPC.TL_messages_setTyping req, long selfId, String token) throws WaysGramApi.ApiException {
        // Do not misrepresent upload/recording actions, forum threads, channels or Saved Messages as ordinary typing.
        if (req.top_msg_id != 0 || !(req.action instanceof TLRPC.TL_sendMessageTypingAction
                || req.action instanceof TLRPC.TL_sendMessageCancelAction)) return new TLRPC.TL_boolTrue();
        String scope;
        long peer;
        if (req.peer instanceof TLRPC.TL_inputPeerUser) {
            scope = "dm";
            peer = req.peer.user_id;
            if (peer == selfId || WaysGramBotFather.is(peer)) return new TLRPC.TL_boolTrue();
        } else if (req.peer instanceof TLRPC.TL_inputPeerChat) {
            scope = "group";
            peer = req.peer.chat_id;
        } else {
            return new TLRPC.TL_boolTrue();
        }
        if (peer <= 0) throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        JSONObject body = new JSONObject();
        try {
            body.put("scope", scope);
            body.put("peer_id", peer);
            body.put("is_typing", req.action instanceof TLRPC.TL_sendMessageTypingAction);
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "TYPING_INVALID");
        }
        WaysGramApi.request("PUT", "/api/typing", body, token, 30000);
        return new TLRPC.TL_boolTrue();
    }

    private static TLRPC.Update update(Entry entry, boolean active) {
        TLRPC.SendMessageAction action = active ? new TLRPC.TL_sendMessageTypingAction() : new TLRPC.TL_sendMessageCancelAction();
        if (entry.scope.equals("dm")) {
            TL_update.TL_updateUserTyping update = new TL_update.TL_updateUserTyping();
            update.user_id = entry.user;
            update.action = action;
            return update;
        }
        TL_update.TL_updateChatUserTyping update = new TL_update.TL_updateChatUserTyping();
        update.chat_id = entry.peer;
        update.from_id = WaysGramMapper.peerUser(entry.user);
        update.action = action;
        return update;
    }

    private static void poll(int account, long selfId, int expectedGeneration) throws WaysGramApi.ApiException {
        final String token = WaysGramApi.getToken(account);
        HashMap<String, Entry> current;
        String afterScope;
        long afterPeer, afterUser;
        synchronized (WaysGramTyping.class) {
            if (generation[account] != expectedGeneration) return;
            current = collected[account] == null ? new HashMap<>() : new HashMap<>(collected[account]);
            afterScope = resumeScope[account] == null ? "" : resumeScope[account];
            afterPeer = resumePeer[account];
            afterUser = resumeUser[account];
        }
        HashMap<String, Entry> fresh = new HashMap<>();
        boolean complete = false;
        for (int page = 0; page < MAX_PAGES; page++) {
            long requestedAt = SystemClock.elapsedRealtime();
            String path = "/api/typing/active?limit=500&after_scope=" + afterScope
                    + "&after_peer_id=" + afterPeer + "&after_user_id=" + afterUser;
            JSONObject response = WaysGramApi.get(account, path);
            JSONArray entries = response.optJSONArray("typing_users");
            long serverTime = response.optLong("server_time", 0);
            if (entries == null || serverTime <= 0 || !response.has("has_more"))
                throw new WaysGramApi.ApiException(502, "TYPING_RESPONSE_INVALID");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) throw new WaysGramApi.ApiException(502, "TYPING_RESPONSE_INVALID");
                String scope = item.optString("scope", "");
                long peer = item.optLong("peer_id"), user = item.optLong("id");
                long remaining = item.optLong("expires_at", 0) - serverTime;
                if (!(scope.equals("dm") || scope.equals("group")) || peer <= 0 || user <= 0 || user == selfId
                        || (scope.equals("dm") && peer != user) || remaining <= 0 || remaining > 8) {
                    throw new WaysGramApi.ApiException(502, "TYPING_RESPONSE_INVALID");
                }
                Entry entry = new Entry(scope, peer, user, item, requestedAt + remaining * 1000);
                if (entry.deadline > SystemClock.elapsedRealtime()) {
                    current.put(entry.key(), entry);
                    fresh.put(entry.key(), entry);
                }
            }
            if (!response.optBoolean("has_more")) { complete = true; break; }
            JSONObject next = response.optJSONObject("next_cursor");
            if (next == null) throw new WaysGramApi.ApiException(502, "TYPING_RESPONSE_INVALID");
            String nextScope = next.optString("scope", "");
            long nextPeer = next.optLong("peer_id"), nextUser = next.optLong("user_id");
            int order = nextScope.compareTo(afterScope);
            if (!(nextScope.equals("dm") || nextScope.equals("group")) || nextPeer <= 0 || nextUser <= 0
                    || order < 0 || (order == 0 && (nextPeer < afterPeer || (nextPeer == afterPeer && nextUser <= afterUser))))
                throw new WaysGramApi.ApiException(502, "TYPING_RESPONSE_INVALID");
            afterScope = nextScope;
            afterPeer = nextPeer;
            afterUser = nextUser;
        }
        final ArrayList<Entry> stopped = new ArrayList<>();
        final ArrayList<Entry> active = new ArrayList<>();
        synchronized (WaysGramTyping.class) {
            if (generation[account] != expectedGeneration || !TextUtils.equals(token, WaysGramApi.getToken(account))) return;
            HashMap<String, Entry> old = previous[account];
            if (old == null) old = new HashMap<>();
            final long now = SystemClock.elapsedRealtime();
            // Pages may take long enough for earlier observations to expire.
            java.util.Iterator<Entry> expired = current.values().iterator();
            while (expired.hasNext()) if (expired.next().deadline <= now) expired.remove();
            if (complete) {
                for (Entry item : old.values()) if (!current.containsKey(item.key())) stopped.add(item);
                previous[account] = current;
                collected[account] = new HashMap<>();
                resumeScope[account] = "";
                resumePeer[account] = resumeUser[account] = 0;
            } else {
                // Do not issue false cancellations from a partial scan. Resume it next time.
                java.util.Iterator<Entry> oldExpired = old.values().iterator();
                while (oldExpired.hasNext()) if (oldExpired.next().deadline <= now) oldExpired.remove();
                old.putAll(fresh);
                previous[account] = old;
                collected[account] = current;
                resumeScope[account] = afterScope;
                resumePeer[account] = afterPeer;
                resumeUser[account] = afterUser;
            }
            active.addAll(fresh.values());
        }
        if (stopped.isEmpty() && active.isEmpty()) return;
        Utilities.stageQueue.postRunnable(() -> {
            synchronized (WaysGramTyping.class) {
                if (generation[account] != expectedGeneration || !TextUtils.equals(token, WaysGramApi.getToken(account))) return;
            }
            MessagesController controller = AccountInstance.getInstance(account).getMessagesController();
            for (Entry item : stopped) deliver(controller, item, false);
            for (Entry item : active) {
                // Network and stage-queue delays must never revive an expired status.
                if (item.deadline <= SystemClock.elapsedRealtime()) continue;
                if (controller.getUser(item.user) == null) {
                    // The short typing snapshot has no avatar/profile; do not replace a full cached user.
                    controller.putUser(WaysGramMapper.toUser(item.json), true);
                }
                deliver(controller, item, true);
            }
        });
    }

    private static void deliver(MessagesController controller, Entry entry, boolean active) {
        TLRPC.TL_updateShort wrapper = new TLRPC.TL_updateShort();
        wrapper.update = update(entry, active);
        wrapper.date = (int) (System.currentTimeMillis() / 1000);
        controller.processUpdates(wrapper, false);
    }
}
