package org.telegram.tgnet;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replaces the MTProto transport. Requests that the app issues as TL objects are executed against the WaysGram
 * REST backend and answered with synthesized TL responses, so the existing UI keeps working unchanged.
 *
 * Nothing is ever sent to any other server: requests that have no WaysGram equivalent fail locally with
 * METHOD_NOT_SUPPORTED.
 */
public class WaysGramTransport {

    /** When true the app talks only to the WaysGram REST backend and never starts the MTProto network stack. */
    public static final boolean ENABLED = true;

    private static final AtomicInteger[] pts = new AtomicInteger[UserConfig.MAX_ACCOUNT_COUNT];

    // Preserve the order in which one account changes reaction selections.
    private static final java.util.concurrent.ExecutorService[] reactionQueues =
            new java.util.concurrent.ExecutorService[UserConfig.MAX_ACCOUNT_COUNT];

    private static java.util.concurrent.ExecutorService reactionQueue(int account) {
        synchronized (reactionQueues) {
            if (reactionQueues[account] == null) {
                reactionQueues[account] = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "waysgram-reactions-" + account);
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return reactionQueues[account];
        }
    }

    // Serialize typing/cancel for each account so a delayed typing request cannot overtake cancellation.
    private static final java.util.concurrent.ExecutorService[] typingQueues =
            new java.util.concurrent.ExecutorService[UserConfig.MAX_ACCOUNT_COUNT];

    private static java.util.concurrent.ExecutorService typingQueue(int account) {
        synchronized (typingQueues) {
            if (typingQueues[account] == null) {
                typingQueues[account] = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "waysgram-typing-" + account);
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return typingQueues[account];
        }
    }

    // Keep user pin actions in order within an Android account.
    private static final java.util.concurrent.ExecutorService[] pinQueues =
            new java.util.concurrent.ExecutorService[UserConfig.MAX_ACCOUNT_COUNT];
    private static java.util.concurrent.ExecutorService pinQueue(int account) {
        synchronized (pinQueues) {
            if (pinQueues[account] == null) {
                pinQueues[account] = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "waysgram-pins-" + account);
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return pinQueues[account];
        }
    }

    private static final java.util.concurrent.ExecutorService[] draftQueues =
            new java.util.concurrent.ExecutorService[UserConfig.MAX_ACCOUNT_COUNT];
    private static java.util.concurrent.ExecutorService draftQueue(int account) {
        synchronized (draftQueues) {
            if (draftQueues[account] == null) {
                draftQueues[account] = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "waysgram-drafts-" + account);
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return draftQueues[account];
        }
    }

    private static void clearSentDraft(int account, TLRPC.InputPeer peer, TLRPC.InputReplyTo reply, long selfId, String text, JSONArray entities, String token) {
        if (TextUtils.isEmpty(text)) return;
        draftQueue(account).execute(() -> {
            if (!TextUtils.equals(token, WaysGramApi.getToken(account))) return;
            try {
                WaysGramDrafts.clearSent(peer, reply, selfId, text, entities, token);
            } catch (WaysGramApi.ApiException ignored) {
                // A newer draft or an offline server is left intact.
            }
        });
    }

    private static final boolean[] recoveringPins = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] lastPinRecovery = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final String[] pinRecoveryCursor = new String[UserConfig.MAX_ACCOUNT_COUNT];

    // At most one recovery in the same queue as user actions; never block the poller thread.
    static void schedulePendingPinRecovery(int account) {
        final String token = WaysGramApi.getToken(account);
        if (TextUtils.isEmpty(token)) return;
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (recoveringPins) {
            if (recoveringPins[account] || (lastPinRecovery[account] != 0 && now - lastPinRecovery[account] < 30000)) return;
            recoveringPins[account] = true;
            lastPinRecovery[account] = now;
        }
        pinQueue(account).execute(() -> {
            try {
                if (WaysGramPoller.isRunning(account) && TextUtils.equals(token, WaysGramApi.getToken(account))) {
                    String next = WaysGramPins.recoverPendingClear(account, token, pinRecoveryCursor[account]);
                    pinRecoveryCursor[account] = next;
                }
            } catch (WaysGramApi.ApiException e) {
                // Keep the persisted operation for the next pass.
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                synchronized (recoveringPins) { recoveringPins[account] = false; }
            }
        });
    }

    /** Synthetic update counter. The app's own bookkeeping needs a consistent value, the server has no such notion. */
    public static AtomicInteger ptsCounter(int account) {
        synchronized (pts) {
            if (pts[account] == null) {
                int saved = ApplicationLoader.applicationContext
                        .getSharedPreferences("waysgram_session", Context.MODE_PRIVATE).getInt("pts_" + account, 1000);
                pts[account] = new AtomicInteger(saved);
            }
            return pts[account];
        }
    }

    static int nextPts(int account) {
        int value = ptsCounter(account).incrementAndGet();
        ApplicationLoader.applicationContext.getSharedPreferences("waysgram_session", Context.MODE_PRIVATE)
                .edit().putInt("pts_" + account, value).apply();
        return value;
    }

    public static void execute(final int account, final TLObject request, final RequestDelegate delegate) {
        boolean reactionRequest = request instanceof TLRPC.TL_messages_sendReaction
                || request instanceof TLRPC.TL_messages_getMessagesReactions
                // A paid reaction is also a reaction: capture and re-check the token for it.
                // Without this it reached WaysGramMessages.sendPaidReaction with a null token,
                // the server saw no Authorization header and the request died as 401.
                || request instanceof TLRPC.TL_messages_sendPaidReaction;
        boolean typingRequest = request instanceof TLRPC.TL_messages_setTyping;
        boolean pinRequest = request instanceof TLRPC.TL_messages_updatePinnedMessage
                || request instanceof TLRPC.TL_messages_unpinAllMessages;
        boolean pinReadRequest = request instanceof TLRPC.TL_messages_search
                && ((TLRPC.TL_messages_search) request).filter instanceof TLRPC.TL_inputMessagesFilterPinned;
        boolean transcriptionRequest = request instanceof TLRPC.TL_messages_transcribeAudio;
        boolean draftRequest = request instanceof TLRPC.TL_messages_saveDraft || request instanceof TLRPC.TL_messages_getAllDrafts;
        boolean scheduledRequest = request instanceof WaysGramScheduled.RescheduleRequest
                || request instanceof TLRPC.TL_messages_getScheduledHistory
                || request instanceof TLRPC.TL_messages_getScheduledMessages
                || request instanceof TLRPC.TL_messages_deleteScheduledMessages
                || request instanceof TLRPC.TL_messages_sendScheduledMessages
                || request instanceof TLRPC.TL_messages_sendMessage && ((TLRPC.TL_messages_sendMessage) request).schedule_date != 0;
        boolean mediaReadRequest = request instanceof TLRPC.TL_messages_getSearchCounters
                || request instanceof TLRPC.TL_messages_search && WaysGramSharedMedia.handles(((TLRPC.TL_messages_search) request).filter);
        final TLRPC.TL_messages_sendMessage textSend = request instanceof TLRPC.TL_messages_sendMessage
                ? (TLRPC.TL_messages_sendMessage) request : null;
        final boolean directText = textSend != null && textSend.schedule_date == 0 && textSend.schedule_repeat_period == 0
                && textSend.random_id != 0 && textSend.peer instanceof TLRPC.TL_inputPeerUser && textSend.peer.user_id > 0
                && !WaysGramBotFather.is(textSend.peer.user_id);
        final String reactionToken = reactionRequest || typingRequest || pinRequest || pinReadRequest || mediaReadRequest || transcriptionRequest || draftRequest || scheduledRequest || directText ? WaysGramApi.getToken(account) : null;
        final long queuedAt = android.os.SystemClock.elapsedRealtime();
        java.util.concurrent.Executor executor = reactionRequest ? reactionQueue(account)
                : typingRequest ? typingQueue(account) : pinRequest ? pinQueue(account) : draftRequest ? draftQueue(account) : WaysGramApi.NETWORK;
        executor.execute(() -> {
            TLObject response = null;
            TLRPC.TL_error error = null;
            try {
                if ((reactionRequest || typingRequest || pinRequest || pinReadRequest || mediaReadRequest || transcriptionRequest || draftRequest || scheduledRequest || directText) && !TextUtils.equals(reactionToken, WaysGramApi.getToken(account)))
                    throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
                if (directText) {
                    String slot = paidTextSlot(account, textSend);
                    int oldPrice = paidTextPrefs().getInt(slot, -1);
                    if (oldPrice >= 0) {
                        long since = paidTextPrefs().getLong(slot + ":since", 0);
                        if (since <= 0 || System.currentTimeMillis() - since > 24 * 60 * 60 * 1000L) {
                            throw new WaysGramApi.ApiException(409, "MESSAGE_FEE_RETRY_EXPIRED");
                        }
                        confirmPaidText(account, textSend, reactionToken, oldPrice, true, since, delegate);
                        return;
                    }
                }
                // A delayed activity indication must not outlive its eight-second server TTL.
                response = typingRequest && android.os.SystemClock.elapsedRealtime() - queuedAt > 8000
                        ? new TLRPC.TL_boolTrue() : handle(account, request, reactionToken);
                if ((reactionRequest || pinRequest || pinReadRequest || mediaReadRequest || transcriptionRequest || draftRequest || scheduledRequest || directText) && !TextUtils.equals(reactionToken, WaysGramApi.getToken(account)))
                    throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
                if (response == null) {
                    error = unsupportedRequest(request);
                }
            } catch (WaysGramApi.ApiException e) {
                response = null;
                int fee = directText ? requiredMessageFee(e) : -1;
                if (fee >= 0) {
                    confirmPaidText(account, textSend, reactionToken, fee, false, System.currentTimeMillis(), delegate);
                    return;
                }
                boolean authRequest = request instanceof TLRPC.TL_auth_sendCode
                        || request instanceof TLRPC.TL_auth_resendCode
                        || request instanceof TLRPC.TL_auth_signIn;
                error = "METHOD_NOT_SUPPORTED".equals(e.detail) ? unsupportedRequest(request) : mapError(e, authRequest);
            } catch (Throwable t) {
                FileLog.e(t);
                error = error(500, "INTERNAL");
            }
            if (delegate != null) {
                delegate.run(response, error);
            }
        });
    }

    private static android.content.SharedPreferences paidTextPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("waysgram_paid_text", Context.MODE_PRIVATE);
    }

    private static String paidTextSlot(int account, TLRPC.TL_messages_sendMessage send) {
        return UserConfig.getInstance(account).getClientUserId() + ":" + send.peer.user_id + ":" + send.random_id;
    }

    private static int requiredMessageFee(WaysGramApi.ApiException e) {
        if (e.status != 402) return -1;
        try {
            JSONObject detail = new JSONObject(e.detail);
            int fee = detail.optInt("stars", -1);
            return "MESSAGE_FEE_REQUIRED".equals(detail.optString("code")) && fee >= 0 && fee <= 10000 ? fee : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static void confirmPaidText(int account, TLRPC.TL_messages_sendMessage send, String token, int fee,
                                        boolean retry, long since, RequestDelegate delegate) {
        final String slot = paidTextSlot(account, send);
        org.telegram.ui.WaysGramPaidDm.confirm(account, send.peer.user_id, token, fee, retry, () -> WaysGramApi.NETWORK.execute(() -> {
            TLObject result = null;
            TLRPC.TL_error failure = null;
            try {
                if (TextUtils.isEmpty(token) || !TextUtils.equals(token, WaysGramApi.getToken(account))) {
                    throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
                }
                if (!paidTextPrefs().edit().putInt(slot, fee).putLong(slot + ":since", since).commit()) {
                    throw new WaysGramApi.ApiException(400, "MESSAGE_FEE_RETRY_STORAGE_FAILED");
                }
                JSONArray entities = WaysGramMapper.writeTextEntities(send.entities);
                long self = UserConfig.getInstance(account).getClientUserId();
                result = sendTo(account, send.peer, send.message, send.random_id, null, send.reply_to,
                        self, null, null, send.silent, entities, null, token, fee, -1L);
                paidTextPrefs().edit().remove(slot).remove(slot + ":since").apply();
                if (send.clear_draft) clearSentDraft(account, send.peer, send.reply_to, self, send.message, entities, token);
            } catch (WaysGramApi.ApiException e) {
                int changed = requiredMessageFee(e);
                if (changed >= 0) {
                    paidTextPrefs().edit().remove(slot).remove(slot + ":since").apply();
                    confirmPaidText(account, send, token, changed, false, System.currentTimeMillis(), delegate);
                    return;
                }
                if ("MESSAGE_FEE_INSUFFICIENT_STARS".equals(e.detail)) paidTextPrefs().edit().remove(slot).remove(slot + ":since").apply();
                failure = mapError(e, false);
            } catch (Throwable e) {
                FileLog.e(e);
                failure = error(500, "INTERNAL");
            }
            if (delegate != null) delegate.run(result, failure);
        }), () -> WaysGramApi.NETWORK.execute(() -> {
            if (delegate != null) delegate.run(null, error(400, "MESSAGE_FEE_NOT_CONFIRMED"));
        }));
    }

    /**
     * «Direct Messages» в карточке канала — цена платного сообщения подписчика.
     *
     * <p>Нативный экран отправляет `stars.updatePaidMessagesPrice`; метода не было в
     * обработчиках, поэтому сохранение цены заканчивалось тостом «не поддерживается».
     * Цена хранится на сервере, а в ответ уходит обновлённый канал, чтобы экран сразу
     * показал новое состояние.</p>
     */
    private static TLObject paidMessagesPrice(int account, TL_stars.updatePaidMessagesPrice price, long selfId)
            throws WaysGramApi.ApiException {
        if (!(price.channel instanceof TLRPC.TL_inputChannel)) {
            throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        }
        long tg = ((TLRPC.TL_inputChannel) price.channel).channel_id;
        if (WaysGramChats.isGroupChannel(tg)) {
            throw new WaysGramApi.ApiException(400, "PAID_MESSAGES_NOT_SUPPORTED");
        }
        long serverId = WaysGramChats.channelServer(tg);
        long stars = Math.max(0, Math.min(10000, price.send_paid_messages_stars));
        boolean enabled = price.suggestions_allowed && stars > 0;
        JSONObject body = new JSONObject();
        try {
            body.put("enabled", enabled);
            body.put("stars", enabled ? stars : 0);
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "PAID_MESSAGES_INVALID");
        }
        WaysGramApi.put(account, "/api/channels/" + serverId + "/paid-messages", body);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = (int) (System.currentTimeMillis() / 1000);
        updates.chats.add(WaysGramChats.toChannel(account, WaysGramApi.get(account, "/api/channels/" + serverId), selfId, true));
        return updates;
    }

    private static TLRPC.TL_error unsupportedRequest(TLObject request) {
        // Record only the request type, never peer IDs or request contents.
        final String method = request.getClass().getSimpleName();
        FileLog.d("WaysGram: unsupported request " + method);
        return error(400, "METHOD_NOT_SUPPORTED:" + method);
    }

    private static TLObject handle(int account, TLObject request, String expectedToken) throws WaysGramApi.ApiException {
        // ---- authorization ----
        if (request instanceof TLRPC.TL_auth_sendCode) {
            return requestCode(account, ((TLRPC.TL_auth_sendCode) request).phone_number);
        } else if (request instanceof TLRPC.TL_auth_resendCode) {
            return requestCode(account, ((TLRPC.TL_auth_resendCode) request).phone_number);
        } else if (request instanceof TLRPC.TL_auth_cancelCode) {
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_auth_signIn) {
            TLRPC.TL_auth_signIn signIn = (TLRPC.TL_auth_signIn) request;
            return verifyCode(account, signIn.phone_number, signIn.phone_code);
        } else if (request instanceof TLRPC.TL_auth_logOut) {
            WaysGramPoller.stop(account);
            try {
                WaysGramApi.post(account, "/api/auth/logout", new JSONObject());
            } catch (WaysGramApi.ApiException ignore) {
            }
            WaysGramApi.clearToken(account);
            return new TLRPC.TL_auth_loggedOut();
        }

        // Everything below requires a session.
        if (TextUtils.isEmpty(WaysGramApi.getToken(account))) {
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        }
        WaysGramApi.refreshTokenIfNeeded(account);
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        TLObject stories = WaysGramStories.handle(account, request, selfId);
        if (stories != null) return stories;
        TLObject megagroup = WaysGramMegagroups.handle(account, request, selfId);
        if (megagroup != null) return megagroup;

        TLObject thread = WaysGramThreads.handle(account, request, selfId, expectedToken);
        if (thread != null) return thread;

        TLObject extra = WaysGramExtras.handle(account, request, selfId, expectedToken);
        if (extra != null) return extra;

        TLObject chatThemes = WaysGramChatThemes.handle(account, request);
        if (chatThemes != null) return chatThemes;

        TLObject forum = WaysGramChannelTopics.handle(account, request, expectedToken);
        if (forum != null) return forum;

        if (WaysGramReactions.handles(request)) {
            TLObject reactions = WaysGramReactions.handle(request);
            if (reactions != null) return reactions;
        }

        if (request instanceof TLRPC.TL_messages_saveDraft) {
            return WaysGramDrafts.save((TLRPC.TL_messages_saveDraft) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_getAllDrafts) {
            return WaysGramDrafts.load(account, selfId, expectedToken);
        }

        if (request instanceof TL_stars.updatePaidMessagesPrice) {
            return paidMessagesPrice(account, (TL_stars.updatePaidMessagesPrice) request, selfId);
        }

        // ---- update state (the server is polled, there is no push channel) ----
        if (request instanceof TLRPC.TL_updates_getState) {
            return state(account);
        } else if (request instanceof TLRPC.TL_updates_getDifference) {
            TLRPC.TL_updates_differenceEmpty empty = new TLRPC.TL_updates_differenceEmpty();
            empty.date = (int) (System.currentTimeMillis() / 1000);
            empty.seq = 1;
            return empty;
        }

        // ---- users ----
        if (request instanceof TLRPC.TL_users_getUsers) {
            Vector<TLRPC.User> vector = new Vector<>(TLRPC.User::TLdeserialize);
            for (TLRPC.InputUser input : ((TLRPC.TL_users_getUsers) request).id) {
                JSONObject json = null;
                if (input instanceof TLRPC.TL_inputUserSelf) {
                    TLRPC.User self = WaysGramMapper.toSelfUser(WaysGramApi.me(account), null);
                    vector.objects.add(self);
                    continue;
                } else if (input instanceof TLRPC.TL_inputUser && WaysGramBotFather.is(input.user_id)) {
                    vector.objects.add(WaysGramBotFather.user());
                    continue;
                } else if (input instanceof TLRPC.TL_inputUser) {
                    try {
                        json = WaysGramApi.get(account, "/api/users/" + input.user_id);
                    } catch (WaysGramApi.ApiException e) {
                        if (e.status != 404) {
                            throw e;
                        }
                    }
                }
                if (json != null) {
                    TLRPC.TL_user user = WaysGramMapper.toUser(json);
                    user.self = user.id == selfId;
                    vector.objects.add(user);
                }
            }
            return vector;
        } else if (request instanceof TLRPC.TL_contacts_getContacts) {
            TLRPC.TL_contacts_contacts contacts = new TLRPC.TL_contacts_contacts();
            contacts.saved_count = 0;
            return contacts;
        } else if (request instanceof TLRPC.TL_contacts_search) {
            TLRPC.TL_contacts_found found = searchUsers(account, ((TLRPC.TL_contacts_search) request).q, selfId);
            WaysGramChats.addChannelMatches(account, ((TLRPC.TL_contacts_search) request).q, selfId, found);
            return found;
        } else if (request instanceof TLRPC.TL_contacts_getBlocked) {
            TLRPC.TL_contacts_getBlocked blocked = (TLRPC.TL_contacts_getBlocked) request;
            if (blocked.my_stories_from) throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
            JSONObject response = WaysGramApi.get(account, "/api/blocks");
            JSONArray rows = response.optJSONArray("blocked_users");
            TLRPC.TL_contacts_blockedSlice out = new TLRPC.TL_contacts_blockedSlice();
            out.count = response.optInt("count");
            int offset = Math.max(0, blocked.offset);
            int limit = Math.max(0, Math.min(200, blocked.limit));
            for (int i = offset; rows != null && i < rows.length() && i - offset < limit; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || row.optLong("id") <= 0) continue;
                TLRPC.TL_user user = WaysGramMapper.toUser(row);
                TLRPC.TL_peerBlocked peer = new TLRPC.TL_peerBlocked();
                peer.peer_id = WaysGramMapper.peerUser(user.id);
                peer.date = row.optInt("created_at");
                out.blocked.add(peer);
                out.users.add(user);
            }
            return out;
        } else if (request instanceof TLRPC.TL_contacts_block) {
            TLRPC.TL_contacts_block block = (TLRPC.TL_contacts_block) request;
            return updateBlockedUser(account, block.id, block.my_stories_from, true);
        } else if (request instanceof TLRPC.TL_contacts_unblock) {
            TLRPC.TL_contacts_unblock unblock = (TLRPC.TL_contacts_unblock) request;
            return updateBlockedUser(account, unblock.id, unblock.my_stories_from, false);
        } else if (request instanceof TLRPC.TL_contacts_resolvePhone) {
            JSONObject body = new JSONObject();
            try { body.put("phone", ((TLRPC.TL_contacts_resolvePhone) request).phone); }
            catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "PHONE_NUMBER_INVALID"); }
            TLRPC.TL_user user = WaysGramMapper.toUser(WaysGramApi.post(account, "/api/contacts/resolve-phone", body));
            user.self = user.id == selfId;
            TLRPC.TL_contacts_resolvedPeer out = new TLRPC.TL_contacts_resolvedPeer();
            out.peer = WaysGramMapper.peerUser(user.id);
            out.users.add(user);
            return out;
        } else if (request instanceof TLRPC.TL_contacts_resolveUsername) {
            return resolveUsername(account, ((TLRPC.TL_contacts_resolveUsername) request).username, selfId);
        }

        // ---- wallet: Stars and Gram balances with their history ----
        if (request instanceof TL_stars.TL_payments_getStarsStatus) {
            TL_stars.TL_payments_getStarsStatus req = (TL_stars.TL_payments_getStarsStatus) request;
            return WaysGramEconomy.status(account, req.ton, true);
        } else if (request instanceof TL_stars.TL_payments_getStarsTransactions) {
            TL_stars.TL_payments_getStarsTransactions req = (TL_stars.TL_payments_getStarsTransactions) request;
            if (!TextUtils.isEmpty(req.offset)) {
                return WaysGramEconomy.status(account, req.ton, false); // everything was returned on the first page
            }
            return WaysGramEconomy.status(account, req.ton, true);
        }

        // ---- gift shop: catalog and artwork from Telegram, purchase on the WaysGram server ----
        if (request instanceof TL_stars.getStarGifts) {
            return WaysGramGifts.catalog(account);
        } else if (request instanceof TLRPC.TL_payments_getPaymentForm
                && ((TLRPC.TL_payments_getPaymentForm) request).invoice instanceof TLRPC.TL_inputInvoiceStarGift) {
            return WaysGramGifts.paymentForm(account, (TLRPC.TL_inputInvoiceStarGift) ((TLRPC.TL_payments_getPaymentForm) request).invoice);
        } else if (request instanceof TL_stars.TL_payments_sendStarsForm) {
            return WaysGramGifts.sendForm(account, (TL_stars.TL_payments_sendStarsForm) request);
        } else if (request instanceof TL_stars.getSavedStarGifts) {
            return WaysGramGifts.savedGifts(account, (TL_stars.getSavedStarGifts) request, selfId);
        } else if (request instanceof TLRPC.TL_upload_getFile) {
            TLObject localEmoji = WaysGramAnimatedEmoji.getFile((TLRPC.TL_upload_getFile) request);
            if (localEmoji != null) return localEmoji;
            TLObject reactionTexture = WaysGramReactionTextures.getFile((TLRPC.TL_upload_getFile) request);
            if (reactionTexture != null) return reactionTexture;
            TLObject file = WaysGramMedia.getFile(account, (TLRPC.TL_upload_getFile) request);
            return file != null ? file : WaysGramGifts.getFile(account, (TLRPC.TL_upload_getFile) request);
        }

        if (request instanceof TLRPC.TL_channels_getChannels) {
            TLRPC.TL_channels_getChannels req = (TLRPC.TL_channels_getChannels) request;
            if (req.id.size() > 100) throw new WaysGramApi.ApiException(400, "CHANNEL_LIST_INVALID");
            TLRPC.TL_messages_chats result = new TLRPC.TL_messages_chats();
            for (TLRPC.InputChannel input : req.id) {
                long id = WaysGramChats.channelServer(input.channel_id);
                if (id <= 0) continue;
                JSONObject data = WaysGramApi.get(account, "/api/channels/" + id);
                result.chats.add(WaysGramChats.toChannel(account, data, selfId, data.optBoolean("joined") || WaysGramChats.joinedChannels(account).contains(id)));
            }
            return result;
        } else if (request instanceof TLRPC.TL_channels_exportMessageLink) {
            TLRPC.TL_channels_exportMessageLink req = (TLRPC.TL_channels_exportMessageLink) request;
            long tgId = req.channel == null ? 0 : req.channel.channel_id;
            long channel = WaysGramChats.channelServer(tgId);
            if (channel <= 0 || req.id <= 0 || WaysGramMessages.fetchChannel(account, channel, req.id, selfId) == null)
                throw new WaysGramApi.ApiException(404, "MESSAGE_NOT_FOUND");
            JSONObject data = WaysGramApi.get(account, "/api/channels/" + channel);
            String username = data.optString("username", "");
            String path = username.matches("[A-Za-z0-9_]+") ? "/" + username : "/c/" + tgId;
            TLRPC.TL_exportedMessageLink response = new TLRPC.TL_exportedMessageLink();
            response.link = WaysGramLinks.BASE + path + "/" + req.id;
            response.html = "";
            return response;
        } else if (request instanceof TLRPC.TL_messages_checkChatInvite) {
            return WaysGramInvites.preview(account, ((TLRPC.TL_messages_checkChatInvite) request).hash);
        } else if (request instanceof TLRPC.TL_messages_importChatInvite) {
            return WaysGramInvites.join(account, ((TLRPC.TL_messages_importChatInvite) request).hash, selfId);
        } else if (request instanceof TLRPC.TL_messages_exportChatInvite) {
            return WaysGramInvites.exportGroup(account, (TLRPC.TL_messages_exportChatInvite) request, selfId);
        } else if (request instanceof TLRPC.TL_messages_getExportedChatInvites) {
            return WaysGramInvites.listLinks(account, (TLRPC.TL_messages_getExportedChatInvites) request, selfId);
        } else if (request instanceof TLRPC.TL_messages_deleteExportedChatInvite) {
            TLRPC.TL_messages_deleteExportedChatInvite invite = (TLRPC.TL_messages_deleteExportedChatInvite) request;
            return WaysGramInvites.deleteRevoked(account, invite.peer, invite.link == null ? "" : invite.link, null, selfId);
        } else if (request instanceof TLRPC.TL_messages_deleteRevokedExportedChatInvites) {
            TLRPC.TL_messages_deleteRevokedExportedChatInvites invites = (TLRPC.TL_messages_deleteRevokedExportedChatInvites) request;
            return WaysGramInvites.deleteRevoked(account, invites.peer, null, invites.admin_id, selfId);
        } else if (request instanceof TLRPC.TL_messages_editExportedChatInvite) {
            return WaysGramInvites.revokeGroup(account, (TLRPC.TL_messages_editExportedChatInvite) request);
        } else if (request instanceof TLRPC.TL_messages_getChatInviteImporters) {
            return WaysGramInvites.requests(account, (TLRPC.TL_messages_getChatInviteImporters) request);
        } else if (request instanceof TLRPC.TL_messages_hideChatJoinRequest) {
            return WaysGramInvites.review(account, (TLRPC.TL_messages_hideChatJoinRequest) request, selfId);
        }

        // ---- channels: joining is remembered on the device ----
        if (request instanceof TLRPC.TL_channels_joinChannel) {
            TLRPC.TL_chatInviteJoinResultOk ok = new TLRPC.TL_chatInviteJoinResultOk();
            ok.updates = WaysGramChats.joinOrLeave(account, ((TLRPC.TL_channels_joinChannel) request).channel.channel_id, true, selfId);
            return ok;
        } else if (request instanceof TLRPC.TL_channels_getParticipant) {
            return WaysGramChats.channelParticipant(account, (TLRPC.TL_channels_getParticipant) request, selfId);
        } else if (request instanceof TLRPC.TL_channels_getParticipants) {
            return WaysGramChats.channelParticipants(account, (TLRPC.TL_channels_getParticipants) request, selfId);
        } else if (request instanceof TLRPC.TL_channels_editAdmin) {
            return WaysGramChats.editChannelAdmin(account, (TLRPC.TL_channels_editAdmin) request, selfId);
        } else if (request instanceof TLRPC.TL_channels_editBanned) {
            return WaysGramChats.banChannelParticipant(account, (TLRPC.TL_channels_editBanned) request, selfId);
        } else if (request instanceof TLRPC.TL_channels_inviteToChannel) {
            TLRPC.TL_channels_inviteToChannel invite = (TLRPC.TL_channels_inviteToChannel) request;
            return WaysGramChats.inviteChannelUsers(account, invite.channel.channel_id, invite.users, selfId);
        } else if (request instanceof TLRPC.TL_channels_deleteChannel) {
            long id = WaysGramChats.channelServer(((TLRPC.TL_channels_deleteChannel) request).channel.channel_id);
            WaysGramApi.delete(account, "/api/channels/" + id);
            WaysGramChats.setJoined(account, id, false);
            return new TLRPC.TL_updates();
        } else if (request instanceof TLRPC.TL_channels_leaveChannel) {
            return WaysGramChats.joinOrLeave(account, ((TLRPC.TL_channels_leaveChannel) request).channel.channel_id, false, selfId);
        }

        // ---- avatars and the profile card ----
        if (request instanceof TLRPC.TL_photos_uploadProfilePhoto) {
            return WaysGramProfile.uploadPhoto(account, (TLRPC.TL_photos_uploadProfilePhoto) request);
        } else if (request instanceof TLRPC.TL_photos_deletePhotos) {
            return WaysGramProfile.deletePhoto(account);
        } else if (request instanceof TLRPC.TL_photos_getUserPhotos) {
            TLRPC.TL_photos_getUserPhotos photos = (TLRPC.TL_photos_getUserPhotos) request;
            long target = photos.user_id instanceof TLRPC.TL_inputUserSelf ? selfId : photos.user_id.user_id;
            return WaysGramProfile.userPhotos(account, target, selfId);
        } else if (request instanceof TLRPC.TL_messages_getDefaultHistoryTTL) {
            TLRPC.TL_defaultHistoryTTL ttl = new TLRPC.TL_defaultHistoryTTL();
            ttl.period = WaysGramApi.get(account, "/api/me/auto-delete").optInt("seconds");
            return ttl;
        } else if (request instanceof TLRPC.TL_messages_setDefaultHistoryTTL) {
            JSONObject body = new JSONObject();
            try { body.put("seconds", ((TLRPC.TL_messages_setDefaultHistoryTTL) request).period); }
            catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "AUTO_DELETE_INVALID"); }
            WaysGramApi.put(account, "/api/me/auto-delete", body);
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_messages_setHistoryTTL) {
            TLRPC.TL_messages_setHistoryTTL ttl = (TLRPC.TL_messages_setHistoryTTL) request;
            if (!(ttl.peer instanceof TLRPC.TL_inputPeerUser) || ttl.peer.user_id <= 0 || ttl.peer.user_id == selfId) {
                throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
            }
            JSONObject body = new JSONObject();
            try { body.put("seconds", ttl.period); }
            catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "AUTO_DELETE_INVALID"); }
            WaysGramApi.put(account, "/api/chats/" + ttl.peer.user_id + "/auto-delete", body);
            return new TLRPC.TL_updates();
        } else if (request instanceof TLRPC.TL_users_getFullUser) {
            TLRPC.TL_users_getFullUser full = (TLRPC.TL_users_getFullUser) request;
            long target = full.id instanceof TLRPC.TL_inputUserSelf ? selfId : full.id.user_id;
            return WaysGramProfile.fullUser(account, target, selfId);
        }

        if (request instanceof TLRPC.TL_getSavedMusic) {
            TLRPC.TL_getSavedMusic music = (TLRPC.TL_getSavedMusic) request;
            long user = music.id instanceof TLRPC.TL_inputUserSelf ? selfId : music.id.user_id;
            return WaysGramMusic.list(account, user, music.offset, music.limit);
        } else if (request instanceof TL_account.getSavedMusicIds) {
            return WaysGramMusic.ids(account, selfId);
        } else if (request instanceof TLRPC.TL_account_saveMusic) {
            return WaysGramMusic.save(account, (TLRPC.TL_account_saveMusic) request);
        }

        if (request instanceof TL_account.getAuthorizations) {
            return WaysGramSessions.list(account);
        } else if (request instanceof TL_account.resetAuthorization) {
            return WaysGramSessions.reset(account, ((TL_account.resetAuthorization) request).hash);
        } else if (request instanceof TLRPC.TL_auth_resetAuthorizations) {
            return WaysGramSessions.resetOthers(account);
        }

        // ---- own profile and channel creation ----
        if (request instanceof TL_account.updatePersonalChannel) {
            return WaysGramProfile.setPersonalChannel(account, ((TL_account.updatePersonalChannel) request).channel);
        } else if (request instanceof TLRPC.TL_channels_getAdminedPublicChannels) {
            return WaysGramProfile.publicChannels(account, selfId, ((TLRPC.TL_channels_getAdminedPublicChannels) request).for_personal);
        } else if (request instanceof TLRPC.TL_channels_getSendAs) {
            // Отправители для группы: сама группа (анонимный админ) и публичные каналы пользователя.
            TLRPC.TL_channels_getSendAs req = (TLRPC.TL_channels_getSendAs) request;
            long group = WaysGramChats.groupPeerId(req.peer);
            if (req.for_live_stories || group <= 0 || !WaysGramChats.isGroup(req.peer)) {
                return new TLRPC.TL_channels_sendAsPeers();
            }
            return WaysGramChats.sendAsPeers(account, group, selfId);
        } else if (request instanceof TL_account.getPrivacy && WaysGramProfile.privacySetting(((TL_account.getPrivacy) request).key) != null) {
            TL_account.getPrivacy privacy = (TL_account.getPrivacy) request;
            return WaysGramProfile.privacy(account, selfId, WaysGramProfile.privacySetting(privacy.key), null);
        } else if (request instanceof TL_account.setPrivacy && WaysGramProfile.privacySetting(((TL_account.setPrivacy) request).key) != null) {
            TL_account.setPrivacy privacy = (TL_account.setPrivacy) request;
            return WaysGramProfile.privacy(account, selfId, WaysGramProfile.privacySetting(privacy.key), privacy.rules);
        } else if (request instanceof TL_account.updateBirthday) {
            return WaysGramProfile.updateBirthday(account, ((TL_account.updateBirthday) request).birthday);
        } else if (request instanceof TL_account.updateProfile) {
            return WaysGramProfile.updateProfile(account, (TL_account.updateProfile) request, selfId);
        } else if (request instanceof TL_account.updateUsername) {
            return WaysGramProfile.updateUsername(account, ((TL_account.updateUsername) request).username);
        } else if (request instanceof TL_account.checkUsername) {
            return WaysGramProfile.checkUsername(account, ((TL_account.checkUsername) request).username, selfId, false);
        } else if (request instanceof TLRPC.TL_channels_getMessages) {
            // Посты канала по id: ответы, закреплённые сообщения, альбомы.
            TLRPC.TL_channels_getMessages req = (TLRPC.TL_channels_getMessages) request;
            return WaysGramMessages.channelMessagesByIds(account, req.channel.channel_id, req.id, selfId);
        } else if (request instanceof TLRPC.TL_messages_getMessages) {
            // Список без пира (кэш ссылок на файлы): пустой ответ вместо ошибки, иначе
            // каждое открытие чата показывало «функция не поддерживается».
            return new TLRPC.TL_messages_messages();
        } else if (request instanceof TLRPC.TL_channels_readMessageContents) {
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_messages_readMessageContents) {
            TLRPC.TL_messages_affectedMessages read = new TLRPC.TL_messages_affectedMessages();
            read.pts = nextPts(account);
            read.pts_count = 1;
            return read;
        } else if (request instanceof TLRPC.TL_channels_getInactiveChannels) {
            return new TLRPC.TL_messages_inactiveChats();
        } else if (request instanceof TLRPC.TL_channels_reportSpam) {
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_channels_getFullChannel) {
            return WaysGramChats.fullChannel(account, ((TLRPC.TL_channels_getFullChannel) request).channel.channel_id, selfId);
        } else if (request instanceof TLRPC.TL_channels_createChannel) {
            return WaysGramProfile.createChannel(account, (TLRPC.TL_channels_createChannel) request, selfId);
        } else if (request instanceof TLRPC.TL_channels_checkUsername) {
            return WaysGramProfile.checkUsername(account, ((TLRPC.TL_channels_checkUsername) request).username, selfId, true);
        } else if (request instanceof TLRPC.TL_channels_updateUsername) {
            return WaysGramProfile.updateChannelUsername(account, (TLRPC.TL_channels_updateUsername) request);
        } else if (request instanceof TLRPC.TL_channels_editTitle) {
            TLRPC.TL_channels_editTitle edit = (TLRPC.TL_channels_editTitle) request;
            return WaysGramProfile.editChannelTitle(account, edit.channel.channel_id, edit.title);
        } else if (request instanceof TLRPC.TL_channels_editPhoto) {
            TLRPC.TL_channels_editPhoto edit = (TLRPC.TL_channels_editPhoto) request;
            return WaysGramChats.editChannelPhoto(account, edit.channel.channel_id, edit.photo);
        } else if (request instanceof TLRPC.TL_channels_toggleSignatures) {
            TLRPC.TL_channels_toggleSignatures toggle = (TLRPC.TL_channels_toggleSignatures) request;
            return WaysGramChats.updateChannelSettings(account, toggle.channel.channel_id,
                    WaysGramChats.settings("signatures_enabled", toggle.signatures_enabled,
                            "signatures_profiles", toggle.profiles_enabled));
        } else if (request instanceof TLRPC.TL_channels_toggleSlowMode) {
            TLRPC.TL_channels_toggleSlowMode toggle = (TLRPC.TL_channels_toggleSlowMode) request;
            return WaysGramChats.updateChannelSettings(account, toggle.channel.channel_id,
                    WaysGramChats.settings("slow_mode_seconds", toggle.seconds));
        } else if (request instanceof TLRPC.TL_channels_toggleParticipantsHidden) {
            TLRPC.TL_channels_toggleParticipantsHidden toggle = (TLRPC.TL_channels_toggleParticipantsHidden) request;
            return WaysGramChats.updateChannelSettings(account, toggle.channel.channel_id,
                    WaysGramChats.settings("participants_hidden", toggle.enabled));
        } else if (request instanceof TLRPC.TL_channels_togglePreHistoryHidden) {
            TLRPC.TL_channels_togglePreHistoryHidden toggle = (TLRPC.TL_channels_togglePreHistoryHidden) request;
            return WaysGramChats.updateChannelSettings(account, toggle.channel.channel_id,
                    WaysGramChats.settings("pre_history_hidden", toggle.enabled));
        } else if (request instanceof TLRPC.TL_channels_toggleJoinToSend) {
            TLRPC.TL_channels_toggleJoinToSend toggle = (TLRPC.TL_channels_toggleJoinToSend) request;
            return WaysGramChats.updateChannelSettings(account, toggle.channel.channel_id,
                    WaysGramChats.settings("join_to_send", toggle.enabled));
        } else if (request instanceof TLRPC.TL_channels_toggleJoinRequest) {
            TLRPC.TL_channels_toggleJoinRequest toggle = (TLRPC.TL_channels_toggleJoinRequest) request;
            return WaysGramChats.updateChannelSettings(account, toggle.channel.channel_id,
                    WaysGramChats.settings("join_request", toggle.enabled));
        } else if (request instanceof TLRPC.TL_channels_updateColor) {
            TLRPC.TL_channels_updateColor update = (TLRPC.TL_channels_updateColor) request;
            int colorValue = (update.flags & TLObject.FLAG_2) != 0 ? update.color : 0;
            long emojiValue = (update.flags & TLObject.FLAG_0) != 0 ? update.background_emoji_id : 0;
            JSONObject body = WaysGramChats.settings(
                    update.for_profile ? "profile_color_id" : "color_id", colorValue,
                    update.for_profile ? "profile_background_emoji_id" : "background_emoji_id", emojiValue);
            // Мегагруппа живёт в групповом API: у неё и настройки, и цвет профиля свои.
            if (WaysGramChats.isGroupChannel(update.channel.channel_id)) {
                return WaysGramChats.updateGroupSettings(account, update.channel.channel_id, body);
            }
            return WaysGramChats.updateChannelSettings(account, update.channel.channel_id, body);
        } else if (request instanceof TLRPC.TL_channels_updateEmojiStatus) {
            TLRPC.TL_channels_updateEmojiStatus update = (TLRPC.TL_channels_updateEmojiStatus) request;
            long documentId = 0;
            if (update.emoji_status instanceof TLRPC.TL_emojiStatus) {
                documentId = ((TLRPC.TL_emojiStatus) update.emoji_status).document_id;
            } else if (update.emoji_status instanceof TLRPC.TL_emojiStatusCollectible) {
                documentId = ((TLRPC.TL_emojiStatusCollectible) update.emoji_status).document_id;
            }
            return WaysGramChats.updateChannelSettings(account, update.channel.channel_id,
                    WaysGramChats.settings("emoji_status_id", documentId));
        } else if (request instanceof TLRPC.TL_channels_deleteHistory) {
            TLRPC.TL_channels_deleteHistory clear = (TLRPC.TL_channels_deleteHistory) request;
            return WaysGramChats.clearChannelHistory(account, clear.channel.channel_id, clear.for_everyone);
        } else if (request instanceof TLRPC.TL_channels_deleteParticipantHistory) {
            TLRPC.TL_channels_deleteParticipantHistory clear = (TLRPC.TL_channels_deleteParticipantHistory) request;
            long target = 0;
            if (clear.participant instanceof TLRPC.TL_inputPeerUser) {
                target = ((TLRPC.TL_inputPeerUser) clear.participant).user_id;
            } else if (clear.participant instanceof TLRPC.TL_inputPeerChannel) {
                target = ((TLRPC.TL_inputPeerChannel) clear.participant).channel_id;
            } else if (clear.participant instanceof TLRPC.TL_inputPeerChat) {
                target = ((TLRPC.TL_inputPeerChat) clear.participant).chat_id;
            }
            return WaysGramChats.clearChannelMemberHistory(account, clear.channel.channel_id, target);
        } else if (request instanceof TLRPC.TL_channels_checkSearchPostsFlood) {
            TLRPC.SearchPostsFlood flood = new TLRPC.SearchPostsFlood();
            flood.query_is_free = true;
            return flood;
        } else if (request instanceof TLRPC.TL_channels_toggleAntiSpam) {
            TLRPC.TL_channels_toggleAntiSpam toggle = (TLRPC.TL_channels_toggleAntiSpam) request;
            return WaysGramChats.updateChannelSettings(account, toggle.channel.channel_id,
                    WaysGramChats.settings("antispam", toggle.enabled));
        } else if (request instanceof TLRPC.TL_channels_toggleAutotranslation) {
            // WaysGram does not translate messages itself; the toggle is a no-op rather than an error.
            return new TLRPC.TL_updates();
        } else if (request instanceof TLRPC.TL_channels_restrictSponsoredMessages) {
            return new TLRPC.TL_updates();
        } else if (request instanceof TLRPC.TL_channels_editCreator) {
            // Штатный флоу требует SRP-пароль, которого в WaysGram нет: передача идёт
            // через настройки канала («Передать канал»).
            throw new WaysGramApi.ApiException(400, "Передача канала доступна в настройках канала WaysGram");
        } else if (request instanceof TLRPC.TL_channels_editLocation) {
            // Геолокация канала и мегагруппы: точка + подпись. Возвращаем bool, как и Telegram.
            TLRPC.TL_channels_editLocation edit = (TLRPC.TL_channels_editLocation) request;
            if (edit.channel == null || edit.geo_point == null) {
                throw new WaysGramApi.ApiException(400, "LOCATION_INVALID");
            }
            return WaysGramChats.updateLocation(account, edit.channel.channel_id,
                    edit.geo_point.lat, edit.geo_point._long, edit.address);
        } else if (request instanceof TLRPC.TL_channels_reorderUsernames
                || request instanceof TLRPC.TL_channels_setStickers
                || request instanceof TLRPC.TL_channels_setEmojiStickers
                || request instanceof TLRPC.TL_channels_setMainProfileTab) {
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_channels_getChannelRecommendations) {
            return new TLRPC.TL_messages_chats();
        } else if (request instanceof TLRPC.TL_channels_getFutureCreatorAfterLeave
                || request instanceof TLRPC.TL_channels_getMessageAuthor) {
            return new TLRPC.TL_userEmpty();
        } else if (request instanceof TLRPC.TL_channels_toggleViewForumAsMessages
                || request instanceof TLRPC.TL_channels_setBoostsToUnblockRestrictions) {
            return new TLRPC.TL_updates();
        } else if (request instanceof TLRPC.TL_channels_reportAntiSpamFalsePositive) {
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_channels_toggleUsername) {
            TLRPC.TL_channels_toggleUsername toggle = (TLRPC.TL_channels_toggleUsername) request;
            return WaysGramChats.toggleChannelUsername(account, toggle.channel.channel_id, toggle.username, toggle.active);
        } else if (request instanceof TLRPC.TL_channels_deactivateAllUsernames) {
            return WaysGramChats.deactivateChannelUsernames(account,
                    ((TLRPC.TL_channels_deactivateAllUsernames) request).channel.channel_id);
        } else if (request instanceof TLRPC.TL_channels_searchPosts) {
            return WaysGramMessages.searchPosts(account, (TLRPC.TL_channels_searchPosts) request, selfId);
        } else if (request instanceof TLRPC.TL_channels_getAdminLog) {
            return WaysGramChats.adminLog(account, (TLRPC.TL_channels_getAdminLog) request, selfId);
        } else if (request instanceof TLRPC.TL_channels_getGroupsForDiscussion) {
            return WaysGramChats.discussionGroups(account, selfId);
        } else if (request instanceof TLRPC.TL_channels_setDiscussionGroup) {
            TLRPC.TL_channels_setDiscussionGroup link = (TLRPC.TL_channels_setDiscussionGroup) request;
            long groupId = link.group instanceof TLRPC.TL_inputChannel ? link.group.channel_id : 0;
            return WaysGramChats.setDiscussionGroup(account, link.broadcast.channel_id, groupId);
        }

        // ---- the START button of a bot chat ----
        if (request instanceof TLRPC.TL_messages_startBot) {
            TLRPC.TL_messages_startBot start = (TLRPC.TL_messages_startBot) request;
            if (start.bot != null && WaysGramBotFather.is(start.bot.user_id)) {
                return WaysGramBotFather.start(account, start.random_id, selfId, nextPts(account));
            }
            return null;
        }

        if (request instanceof TLRPC.TL_messages_editChatDefaultBannedRights) {
            TLRPC.TL_messages_editChatDefaultBannedRights edit = (TLRPC.TL_messages_editChatDefaultBannedRights) request;
            if (WaysGramChats.isGroup(edit.peer)) {
                return WaysGramChats.setGroupDefaultRights(account,
                        WaysGramChats.groupPeerId(edit.peer), edit.banned_rights);
            }
            return new TLRPC.TL_updates();
        }
        if (request instanceof TLRPC.TL_messages_toggleNoForwards) {
            TLRPC.TL_messages_toggleNoForwards toggle = (TLRPC.TL_messages_toggleNoForwards) request;
            if (WaysGramChats.isGroup(toggle.peer)) {
                return WaysGramChats.updateGroupSettings(account,
                        WaysGramChats.groupPeerId(toggle.peer),
                        WaysGramChats.settings("no_forwards", toggle.enabled));
            }
            if (toggle.peer instanceof TLRPC.TL_inputPeerChannel) {
                return WaysGramChats.updateChannelSettings(account,
                        ((TLRPC.TL_inputPeerChannel) toggle.peer).channel_id,
                        WaysGramChats.settings("no_forwards", toggle.enabled));
            }
            return new TLRPC.TL_updates();
        }
        if (request instanceof TLRPC.TL_messages_getOnlines) {
            TLRPC.TL_messages_getOnlines onlines = (TLRPC.TL_messages_getOnlines) request;
            long onlinesChat = WaysGramChats.groupPeerId(onlines.peer);
            if (onlinesChat <= 0 || !WaysGramChats.isGroup(onlines.peer)) {
                return new TLRPC.TL_chatOnlines();
            }
            return WaysGramChats.onlines(account, onlinesChat);
        }
        if (request instanceof TLRPC.TL_messages_getCommonChats) {
            TLRPC.TL_messages_getCommonChats common = (TLRPC.TL_messages_getCommonChats) request;
            long target = 0;
            if (common.user_id instanceof TLRPC.TL_inputUser) {
                target = ((TLRPC.TL_inputUser) common.user_id).user_id;
            } else if (common.user_id instanceof TLRPC.TL_inputUserSelf) {
                target = selfId;
            }
            return WaysGramChats.commonChats(account, target, selfId);
        }
        if (request instanceof TLRPC.TL_messages_getPeerSettings) {
            return WaysGramChats.peerSettings();
        }
        if (request instanceof TLRPC.TL_messages_reportSpam) {
            return WaysGramChats.reportPeer(account, ((TLRPC.TL_messages_reportSpam) request).peer, selfId);
        }
        if (request instanceof TLRPC.TL_messages_getAdminsWithInvites) {
            return WaysGramInvites.adminsWithInvites(account,
                    ((TLRPC.TL_messages_getAdminsWithInvites) request).peer);
        }
        if (request instanceof TLRPC.TL_messages_setChatTheme) {
            // WaysGram keeps one chat appearance per dialog on the device.
            return new TLRPC.TL_updates();
        }

        // ---- group management ----
        if (request instanceof TLRPC.TL_messages_getFullChat) {
            return WaysGramChats.fullChat(account, ((TLRPC.TL_messages_getFullChat) request).chat_id, selfId);
        } else if (request instanceof TLRPC.TL_messages_createChat) {
            return WaysGramChats.createChat(account, (TLRPC.TL_messages_createChat) request, selfId);
        } else if (request instanceof TLRPC.TL_messages_setChatAvailableReactions) {
            return WaysGramChats.setAvailableReactions(account, (TLRPC.TL_messages_setChatAvailableReactions) request, selfId);
        } else if (request instanceof TLRPC.TL_messages_editChatTitle) {
            TLRPC.TL_messages_editChatTitle edit = (TLRPC.TL_messages_editChatTitle) request;
            return WaysGramChats.editGroup(account, edit.chat_id, "title", edit.title, selfId);
        } else if (request instanceof TLRPC.TL_messages_editChatPhoto) {
            TLRPC.TL_messages_editChatPhoto edit = (TLRPC.TL_messages_editChatPhoto) request;
            return WaysGramChats.editGroupPhoto(account, edit.chat_id, edit.photo);
        } else if (request instanceof TLRPC.TL_messages_editChatAbout) {
            TLRPC.TL_messages_editChatAbout edit = (TLRPC.TL_messages_editChatAbout) request;
            if (WaysGramChats.isGroup(edit.peer)) {
                WaysGramChats.editGroup(account, WaysGramChats.groupPeerId(edit.peer), "description", edit.about, selfId);
                return new TLRPC.TL_boolTrue();
            }
            if (edit.peer instanceof TLRPC.TL_inputPeerChannel) {
                return WaysGramProfile.editChannelAbout(account, edit.peer.channel_id, edit.about);
            }
        } else if (request instanceof TLRPC.TL_messages_editChatAdmin) {
            TLRPC.TL_messages_editChatAdmin edit = (TLRPC.TL_messages_editChatAdmin) request;
            return WaysGramChats.editGroupAdmin(account, edit.chat_id, edit.user_id.user_id, edit.is_admin, edit.waysGramRights);
        } else if (request instanceof TLRPC.TL_messages_addChatUser) {
            TLRPC.TL_messages_addChatUser add = (TLRPC.TL_messages_addChatUser) request;
            return WaysGramChats.addUser(account, add.chat_id, add.user_id.user_id, selfId);
        } else if (request instanceof TLRPC.TL_messages_deleteChat) {
            long id = ((TLRPC.TL_messages_deleteChat) request).chat_id;
            WaysGramApi.delete(account, "/api/groups/" + id);
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_messages_deleteChatUser) {
            TLRPC.TL_messages_deleteChatUser del = (TLRPC.TL_messages_deleteChatUser) request;
            long target = del.user_id instanceof TLRPC.TL_inputUserSelf ? selfId : del.user_id.user_id;
            return WaysGramChats.removeUser(account, del.chat_id, target, selfId);
        }

        // ---- private chats ----
        if (request instanceof TLRPC.TL_messages_getDialogs) {
            return getDialogs(account, (TLRPC.TL_messages_getDialogs) request, selfId);
        } else if (request instanceof TLRPC.TL_messages_getPeerDialogs) {
            return getPeerDialogs(account, (TLRPC.TL_messages_getPeerDialogs) request, selfId);
        } else if (request instanceof TLRPC.TL_messages_getHistory) {
            return getHistory(account, (TLRPC.TL_messages_getHistory) request, selfId);
        } else if (request instanceof TLRPC.TL_messages_updatePinnedMessage) {
            return WaysGramPins.change(account, (TLRPC.TL_messages_updatePinnedMessage) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_unpinAllMessages) {
            return WaysGramPins.clear(account, (TLRPC.TL_messages_unpinAllMessages) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_search
                && ((TLRPC.TL_messages_search) request).filter instanceof TLRPC.TL_inputMessagesFilterPinned) {
            return WaysGramPins.search(account, (TLRPC.TL_messages_search) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_search
                && WaysGramSharedMedia.handles(((TLRPC.TL_messages_search) request).filter)) {
            return WaysGramSharedMedia.search(account, (TLRPC.TL_messages_search) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_getSearchCounters) {
            return WaysGramSharedMedia.counters(account, (TLRPC.TL_messages_getSearchCounters) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_transcribeAudio) {
            return WaysGramTranscription.transcribe(account, (TLRPC.TL_messages_transcribeAudio) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_setTyping) {
            if (!TextUtils.equals(expectedToken, WaysGramApi.getToken(account)))
                throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
            return WaysGramTyping.send(account, (TLRPC.TL_messages_setTyping) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_sendReaction) {
            return WaysGramMessages.setReactions(account, (TLRPC.TL_messages_sendReaction) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_getMessagesReactions) {
            return WaysGramMessages.refreshReactions(account, (TLRPC.TL_messages_getMessagesReactions) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_sendPaidReaction) {
            return WaysGramMessages.sendPaidReaction(account, (TLRPC.TL_messages_sendPaidReaction) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_getPaidReactionPrivacy) {
            // Анонимность платной реакции WaysGram не разделяет: звёзды списываются, автор виден.
            return new TL_stars.paidReactionPrivacyDefault();
        } else if (request instanceof TLRPC.TL_messages_togglePaidReactionPrivacy) {
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof WaysGramScheduled.RescheduleRequest) {
            return WaysGramScheduled.reschedule(account, (WaysGramScheduled.RescheduleRequest) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_getScheduledHistory) {
            return WaysGramScheduled.list(account, ((TLRPC.TL_messages_getScheduledHistory) request).peer, selfId, expectedToken, null);
        } else if (request instanceof TLRPC.TL_messages_getScheduledMessages) {
            TLRPC.TL_messages_getScheduledMessages scheduled = (TLRPC.TL_messages_getScheduledMessages) request;
            return WaysGramScheduled.list(account, scheduled.peer, selfId, expectedToken, scheduled.id);
        } else if (request instanceof TLRPC.TL_messages_deleteScheduledMessages) {
            return WaysGramScheduled.cancel(account, (TLRPC.TL_messages_deleteScheduledMessages) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_sendScheduledMessages) {
            return WaysGramScheduled.sendNow(account, (TLRPC.TL_messages_sendScheduledMessages) request, selfId, expectedToken);
        } else if (request instanceof TLRPC.TL_messages_getMessagesViews) {
            TLRPC.TL_messages_getMessagesViews req = (TLRPC.TL_messages_getMessagesViews) request;
            TLRPC.TL_messages_messageViews response = new TLRPC.TL_messages_messageViews();
            if (!(req.peer instanceof TLRPC.TL_inputPeerChannel)) return response;
            long channel = WaysGramChats.channelServer(req.peer.channel_id);
            if (channel <= 0) return response;
            String token = WaysGramApi.getToken(account);
            for (int offset = 0; offset < req.id.size(); offset += 100) {
                JSONArray ids = new JSONArray(req.id.subList(offset, Math.min(offset + 100, req.id.size())));
                JSONObject payload = new JSONObject();
                try { payload.put("ids", ids).put("increment", req.increment); }
                catch (Exception e) { throw new WaysGramApi.ApiException(400, "INVALID_CHANNEL_VIEW_REQUEST"); }
                JSONArray counts = WaysGramApi.request("POST", "/api/channels/" + channel + "/post-views", payload, token, 10000).optJSONArray("views");
                if (!TextUtils.equals(token, WaysGramApi.getToken(account))) throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
                for (int j = 0; j < ids.length(); j++) {
                    TLRPC.TL_messageViews item = new TLRPC.TL_messageViews();
                    item.flags = 1;
                    item.views = counts == null ? 0 : counts.optInt(j);
                    response.views.add(item);
                }
            }
            return response;
        } else if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage send = (TLRPC.TL_messages_sendMessage) request;
            if (send.schedule_date != 0) {
                TLObject scheduled = WaysGramScheduled.send(account, send, selfId, expectedToken);
                if (send.clear_draft) clearSentDraft(account, send.peer, send.reply_to, selfId, send.message,
                        WaysGramMapper.writeTextEntities(send.entities), expectedToken);
                return scheduled;
            }
            if (send.schedule_repeat_period != 0) throw new WaysGramApi.ApiException(400, "SCHEDULE_NOT_SUPPORTED");
            JSONArray entities = WaysGramMapper.writeTextEntities(send.entities);
            String token = WaysGramApi.getToken(account);
            TLObject sent = sendTo(account, send.peer, send.message, send.random_id, null, send.reply_to, selfId,
                    null, null, send.silent, entities, null, expectedToken, null, sendAsIdentity(send.send_as, send.peer));
            if (send.clear_draft) clearSentDraft(account, send.peer, send.reply_to, selfId, send.message, entities, token);
            return sent;

        } else if (request instanceof TLRPC.TL_messages_sendVote) {
            TLRPC.TL_messages_sendVote vote = (TLRPC.TL_messages_sendVote) request;
            JSONArray options = new JSONArray();
            for (byte[] option : vote.options) {
                if (option == null || option.length != 4) throw new WaysGramApi.ApiException(400, "POLL_OPTION_INVALID");
                options.put(java.nio.ByteBuffer.wrap(option).getInt());
            }
            return updateCard(account, vote.peer, vote.msg_id, selfId, "votes", options, null);
        } else if (request instanceof TLRPC.TL_messages_getPollResults) {
            TLRPC.TL_messages_getPollResults poll = (TLRPC.TL_messages_getPollResults) request;
            return updateCard(account, poll.peer, poll.msg_id, selfId, null, null, null);
        } else if (request instanceof TLRPC.TL_messages_toggleTodoCompleted) {
            TLRPC.TL_messages_toggleTodoCompleted todo = (TLRPC.TL_messages_toggleTodoCompleted) request;
            return updateCard(account, todo.peer, todo.msg_id, selfId, "completed", new JSONArray(todo.completed), new JSONArray(todo.incompleted));
        } else if (request instanceof TLRPC.TL_messages_sendMedia) {
            TLRPC.TL_messages_sendMedia send = (TLRPC.TL_messages_sendMedia) request;
            WaysGramMedia.requirePersistentMedia(send.media);
            WaysGramMedia.requireSupportedVideoCover(send.media);
            // Do not pretend a one-time point is an ongoing live broadcast.
            if (send.media instanceof TLRPC.TL_inputMediaGeoLive) {
                throw new WaysGramApi.ApiException(400, "Трансляция геолокации пока не поддерживается в этом клиенте");
            }
            if (send.schedule_date != 0 || send.schedule_repeat_period != 0) {
                throw new WaysGramApi.ApiException(400, "SCHEDULE_NOT_SUPPORTED");
            }
            if (send.media instanceof TLRPC.TL_inputMediaContact) {
                if (!(send.peer instanceof TLRPC.TL_inputPeerUser || send.peer instanceof TLRPC.TL_inputPeerSelf
                        || send.peer instanceof TLRPC.TL_inputPeerChat || send.peer instanceof TLRPC.TL_inputPeerChannel)
                        || send.noforwards || send.send_as != null || send.quick_reply_shortcut != null
                        || send.effect != 0 || send.allow_paid_stars != 0 || send.suggested_post != null
                        || send.reply_markup != null || !send.entities.isEmpty() || send.ephemeralReceiverBotId != 0
                        || (send.reply_to != null && !(send.reply_to instanceof TLRPC.TL_inputReplyToMessage))) {
                    throw new WaysGramApi.ApiException(400, "CONTACT_NOT_SUPPORTED");
                }
                TLRPC.TL_inputMediaContact media = (TLRPC.TL_inputMediaContact) send.media;
                JSONObject contact = new JSONObject();
                try {
                    contact.put("first_name", media.first_name == null ? "" : media.first_name);
                    contact.put("last_name", media.last_name == null ? "" : media.last_name);
                    contact.put("phone", media.phone_number == null ? "" : media.phone_number);
                    contact.put("vcard", media.vcard == null ? "" : media.vcard);
                } catch (Exception e) {
                    throw new WaysGramApi.ApiException(400, "CONTACT_INVALID");
                }
                return sendTo(account, send.peer, send.message, send.random_id, null,
                        send.reply_to, selfId, null, contact, send.silent, sendAsIdentity(send.send_as, send.peer));
            }
            if (send.media instanceof TLRPC.TL_inputMediaGeoPoint || send.media instanceof TLRPC.TL_inputMediaVenue) {
                if (!(send.peer instanceof TLRPC.TL_inputPeerUser || send.peer instanceof TLRPC.TL_inputPeerSelf
                        || send.peer instanceof TLRPC.TL_inputPeerChat || send.peer instanceof TLRPC.TL_inputPeerChannel)
                        || !(send.media.geo_point instanceof TLRPC.TL_inputGeoPoint)
                        || send.schedule_date != 0 || send.schedule_repeat_period != 0 || send.noforwards
                        || !usesDefaultLocationSender(send.send_as, send.peer, selfId) || send.quick_reply_shortcut != null || send.effect != 0
                        || send.allow_paid_stars != 0 || send.suggested_post != null || send.reply_markup != null
                        || !send.entities.isEmpty() || send.ephemeralReceiverBotId != 0
                        || (send.reply_to != null && !(send.reply_to instanceof TLRPC.TL_inputReplyToMessage))) {
                    throw new WaysGramApi.ApiException(400, "LOCATION_NOT_SUPPORTED");
                }
                JSONObject location = new JSONObject();
                try {
                    location.put("latitude", send.media.geo_point.lat);
                    location.put("longitude", send.media.geo_point._long);
                    if (send.media instanceof TLRPC.TL_inputMediaVenue) {
                        TLRPC.TL_inputMediaVenue place = (TLRPC.TL_inputMediaVenue) send.media;
                        location.put("label", place.title == null ? "" : place.title);
                        JSONObject venue = new JSONObject();
                        venue.put("address", place.address == null ? "" : place.address);
                        venue.put("provider", place.provider == null ? "" : place.provider);
                        venue.put("venue_id", place.venue_id == null ? "" : place.venue_id);
                        venue.put("venue_type", place.venue_type == null ? "" : place.venue_type);
                        location.put("venue", venue);
                    }
                } catch (Exception e) {
                    throw new WaysGramApi.ApiException(400, "LOCATION_INVALID");
                }
                return sendTo(account, send.peer, send.message, send.random_id, null,
                        send.reply_to, selfId, location, send.silent);
            }
            if (send.media instanceof TLRPC.TL_inputMediaPoll || send.media instanceof TLRPC.TL_inputMediaTodo) {
                if (!usesDefaultLocationSender(send.send_as, send.peer, selfId) || send.noforwards
                        || send.quick_reply_shortcut != null || send.effect != 0 || send.allow_paid_stars != 0
                        || send.suggested_post != null || send.reply_markup != null || send.ephemeralReceiverBotId != 0
                        || (send.reply_to != null && !(send.reply_to instanceof TLRPC.TL_inputReplyToMessage))) {
                    throw new WaysGramApi.ApiException(400, "CARD_OPTIONS_NOT_SUPPORTED");
                }
                JSONObject card = WaysGramCards.encode(send.media);
                return sendTo(account, send.peer, "", send.random_id, null, send.reply_to, selfId, null, null, send.silent, null, card);
            }
            JSONArray captionEntities = WaysGramMapper.writeTextEntities(send.entities);
            JSONObject uploaded = WaysGramMedia.uploadFor(account, send.media);
            String mediaId = uploaded != null ? uploaded.optString("id", "") : WaysGramMedia.existingMediaId(send.media);
            if (TextUtils.isEmpty(mediaId)) {
                throw new WaysGramApi.ApiException(400, "MEDIA_NOT_SUPPORTED");
            }
            String token = WaysGramApi.getToken(account);
            TLObject sent = sendTo(account, send.peer, send.message, send.random_id, mediaId, send.reply_to, selfId, null, null, send.silent, captionEntities, sendAsIdentity(send.send_as, send.peer));
            if (send.clear_draft) clearSentDraft(account, send.peer, send.reply_to, selfId, send.message, captionEntities, token);
            return sent;
        } else if (request instanceof TLRPC.TL_messages_uploadMedia) {
            TLRPC.TL_messages_uploadMedia upload = (TLRPC.TL_messages_uploadMedia) request;
            return WaysGramMedia.uploadMedia(account, upload.media);
        } else if (request instanceof TLRPC.TL_messages_sendMultiMedia) {
            TLRPC.TL_messages_sendMultiMedia album = (TLRPC.TL_messages_sendMultiMedia) request;
            String token = WaysGramApi.getToken(account);
            TLObject sent = WaysGramMessages.sendAlbum(account, album, selfId);
            if (album.clear_draft) {
                // A single caption can represent the composer draft; do not guess
                // when an album contains multiple independently authored captions.
                TLRPC.TL_inputSingleMedia caption = null;
                boolean ambiguous = false;
                for (TLRPC.TL_inputSingleMedia item : album.multi_media) {
                    if (item != null && !TextUtils.isEmpty(item.message)) {
                        if (caption != null) { ambiguous = true; break; }
                        caption = item;
                    }
                }
                if (!ambiguous && caption != null) {
                    clearSentDraft(account, album.peer, album.reply_to, selfId, caption.message,
                            WaysGramMapper.writeTextEntities(caption.entities), token);
                }
            }
            return sent;
        } else if (request instanceof TLRPC.TL_upload_saveFilePart) {
            TLRPC.TL_upload_saveFilePart part = (TLRPC.TL_upload_saveFilePart) request;
            return WaysGramMedia.savePart(part.file_id, part.file_part, part.bytes);
        } else if (request instanceof TLRPC.TL_upload_saveBigFilePart) {
            TLRPC.TL_upload_saveBigFilePart part = (TLRPC.TL_upload_saveBigFilePart) request;
            return WaysGramMedia.savePart(part.file_id, part.file_part, part.bytes);
        } else if (request instanceof TLRPC.TL_messages_forwardMessages) {
            return WaysGramMessages.forward(account, (TLRPC.TL_messages_forwardMessages) request, selfId);
        } else if (request instanceof TLRPC.TL_messages_getOutboxReadDate) {
            return WaysGramMessages.getOutboxReadDate(account, (TLRPC.TL_messages_getOutboxReadDate) request);
        } else if (request instanceof TLRPC.TL_messages_getMessageReadParticipants) {
            return WaysGramMessages.getReadParticipants(account, (TLRPC.TL_messages_getMessageReadParticipants) request);
        } else if (request instanceof TLRPC.TL_messages_readHistory) {
            return WaysGramMessages.readHistory(account, (TLRPC.TL_messages_readHistory) request, ptsCounter(account).get());
        } else if (request instanceof TLRPC.TL_channels_readHistory) {
            return WaysGramMessages.readChannel(account, (TLRPC.TL_channels_readHistory) request);
        } else if (request instanceof TLRPC.TL_messages_editMessage) {
            return WaysGramMessages.edit(account, (TLRPC.TL_messages_editMessage) request, selfId, nextPts(account));
        } else if (request instanceof TLRPC.TL_messages_deleteHistory) {
            return WaysGramMessages.clearHistory(account, (TLRPC.TL_messages_deleteHistory) request, selfId, nextPts(account));
        } else if (request instanceof TLRPC.TL_messages_deleteMessages) {
            return WaysGramMessages.delete(account, (TLRPC.TL_messages_deleteMessages) request, nextPts(account));
        } else if (request instanceof TLRPC.TL_channels_deleteMessages) {
            return WaysGramMessages.deleteInChannel(account, (TLRPC.TL_channels_deleteMessages) request);
        }
        return null;
    }

    // ---------------------------------------------------------------- auth

    public static TLRPC.TL_error error(int code, String text) {
        TLRPC.TL_error e = new TLRPC.TL_error();
        e.code = code;
        e.text = text;
        return e;
    }

    private static String normalizePhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        return "+" + digits;
    }

    private static TLRPC.TL_auth_sentCode requestCode(int account, String rawPhone) throws WaysGramApi.ApiException {
        String phone = normalizePhone(rawPhone);
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone);
        } catch (Exception ignore) {
        }
        WaysGramApi.request("POST", "/api/auth/request-code", body, null, 30000);

        TLRPC.TL_auth_sentCode sent = new TLRPC.TL_auth_sentCode();
        TLRPC.TL_auth_sentCodeTypeApp type = new TLRPC.TL_auth_sentCodeTypeApp();
        type.length = 6;
        sent.type = type;
        sent.phone_code_hash = phone;
        sent.next_type = new TLRPC.TL_auth_codeTypeSms();
        sent.timeout = 30;
        sent.flags = TLObject.FLAG_1 | TLObject.FLAG_2;
        return sent;
    }

    private static TLObject verifyCode(int account, String rawPhone, String code) throws WaysGramApi.ApiException {
        String phone = normalizePhone(rawPhone);
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone);
            body.put("code", code == null ? "" : code.trim());
        } catch (Exception ignore) {
        }
        JSONObject result = WaysGramApi.request("POST", "/api/auth/verify-code", body, null, 30000);
        if ("password_required".equals(result.optString("status"))) {
            String challenge = result.optString("challenge");
            if (!challenge.matches("[A-Za-z0-9_-]{43}")) throw new WaysGramApi.ApiException(502, "BAD_RESPONSE");
            return new CloudPasswordRequired(challenge, phone);
        }
        return completeCloudLogin(account, result, phone);
    }

    public static final class CloudPasswordRequired extends TLObject {
        public final String challenge;
        public final String phone;

        private CloudPasswordRequired(String challenge, String phone) {
            this.challenge = challenge;
            this.phone = phone;
        }
    }

    public static TLRPC.TL_auth_authorization completeCloudLogin(int account, JSONObject result, String phone) throws WaysGramApi.ApiException {
        String token = result.optString("token", null);
        JSONObject user = result.optJSONObject("user");
        if (TextUtils.isEmpty(token) || user == null || user.optLong("id") <= 0) {
            throw new WaysGramApi.ApiException(502, "BAD_RESPONSE");
        }
        TLRPC.TL_auth_authorization auth = new TLRPC.TL_auth_authorization();
        auth.user = WaysGramMapper.toSelfUser(user, phone);
        WaysGramApi.saveToken(account, token, result.optLong("expires_at", 0));
        WaysGramPoller.start(account);
        return auth;
    }

    // ---------------------------------------------------------------- state

    private static TLRPC.TL_updates_state state(int account) {
        TLRPC.TL_updates_state state = new TLRPC.TL_updates_state();
        state.pts = ptsCounter(account).get();
        state.qts = 0;
        state.date = (int) (System.currentTimeMillis() / 1000);
        state.seq = 1;
        state.unread_count = 0;
        return state;
    }

    // ---------------------------------------------------------------- users

    private static TLRPC.TL_contacts_found searchUsers(int account, String q, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_contacts_found found = new TLRPC.TL_contacts_found();
        if (TextUtils.isEmpty(q)) {
            return found;
        }
        if (WaysGramBotFather.USERNAME.toLowerCase().contains(q.replace("@", "").trim().toLowerCase())
                && q.replace("@", "").trim().length() >= 2) {
            found.users.add(WaysGramBotFather.user());
            found.results.add(WaysGramMapper.peerUser(WaysGramBotFather.ID));
        }
        JSONObject json = WaysGramApi.get(account, "/api/users?q=" + android.net.Uri.encode(q));
        JSONArray users = json.optJSONArray("users");
        if (users != null) {
            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u == null) {
                    continue;
                }
                TLRPC.TL_user user = WaysGramMapper.toUser(u);
                if (user.id == selfId) {
                    continue;
                }
                found.users.add(user);
                found.results.add(WaysGramMapper.peerUser(user.id));
            }
        }
        return found;
    }

    private static TLObject updateBlockedUser(int account, TLRPC.InputPeer peer, boolean storiesOnly, boolean block) throws WaysGramApi.ApiException {
        if (storiesOnly || !(peer instanceof TLRPC.TL_inputPeerUser) || peer.user_id <= 0) {
            throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        }
        if (block) {
            JSONObject body = new JSONObject();
            try { body.put("user_id", peer.user_id); }
            catch (org.json.JSONException e) { throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID"); }
            WaysGramApi.post(account, "/api/blocks", body);
        } else {
            WaysGramApi.delete(account, "/api/blocks/" + peer.user_id);
        }
        return new TLRPC.TL_boolTrue();
    }

    private static TLRPC.TL_contacts_resolvedPeer resolveUsername(int account, String username, long selfId) throws WaysGramApi.ApiException {
        String name = username == null ? "" : username.replace("@", "").trim();
        if (WaysGramBotFather.matches(name)) {
            TLRPC.TL_contacts_resolvedPeer father = new TLRPC.TL_contacts_resolvedPeer();
            father.peer = WaysGramMapper.peerUser(WaysGramBotFather.ID);
            father.users.add(WaysGramBotFather.user());
            return father;
        }
        JSONObject json = WaysGramApi.get(account, "/api/users?q=" + android.net.Uri.encode(name));
        JSONArray users = json.optJSONArray("users");
        if (users != null) {
            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u != null && name.equalsIgnoreCase(u.optString("username"))) {
                    TLRPC.TL_user user = WaysGramMapper.toUser(u);
                    user.self = user.id == selfId;
                    TLRPC.TL_contacts_resolvedPeer resolved = new TLRPC.TL_contacts_resolvedPeer();
                    resolved.peer = WaysGramMapper.peerUser(user.id);
                    resolved.users.add(user);
                    return resolved;
                }
            }
        }
        TLRPC.TL_channel channel = WaysGramChats.findChannelByUsername(account, name, selfId);
        if (channel != null) {
            TLRPC.TL_contacts_resolvedPeer resolved = new TLRPC.TL_contacts_resolvedPeer();
            TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
            peer.channel_id = channel.id;
            resolved.peer = peer;
            resolved.chats.add(channel);
            return resolved;
        }
        throw new WaysGramApi.ApiException(404, "USERNAME_NOT_OCCUPIED");
    }

    // ---------------------------------------------------------------- dialogs and history

    private static class Conversations {
        final ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
    }

    private static Conversations loadConversations(int account, long selfId) throws WaysGramApi.ApiException {
        Conversations result = new Conversations();
        JSONObject json = WaysGramApi.get(account, "/api/conversations");
        JSONArray items = json.optJSONArray("conversations");
        HashSet<Long> seen = new HashSet<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject c = items.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                long peerId = c.optLong("id");
                if (!seen.add(peerId)) {
                    continue;
                }
                int lastId = c.optInt("last_message_id");
                long lastSender = c.optLong("last_sender_id");
                long recipient = lastSender == selfId ? peerId : selfId;
                TLRPC.TL_dialog privateDialog = WaysGramMapper.toDialog(peerId, lastId);
                WaysGramMapper.applyReadState(privateDialog, c.optInt("unread_count"),
                        c.optInt("my_read_id"), c.optInt("peer_read_id"));
                result.dialogs.add(privateDialog);
                result.messages.add(WaysGramMapper.toMessage(lastId, lastSender, recipient,
                        c.optString("last_message", ""), c.optInt("created_at"), selfId));
                TLRPC.TL_user user = WaysGramMapper.toUser(c);
                result.users.add(user);
            }
        }
        return result;
    }

    private static TLRPC.User selfUser(int account) throws WaysGramApi.ApiException {
        return WaysGramMapper.toSelfUser(WaysGramApi.me(account), null);
    }

    private static TLObject getDialogs(int account, TLRPC.TL_messages_getDialogs req, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_messages_dialogs out = new TLRPC.TL_messages_dialogs();
        // Archive folder and "load more" pages are always empty: the first page already contains everything.
        if (req.folder_id == 0 && req.offset_id == 0 && req.offset_date == 0) {
            Conversations c = loadConversations(account, selfId);
            out.dialogs.addAll(c.dialogs);
            out.messages.addAll(c.messages);
            out.users.addAll(c.users);
            WaysGramChats.Snapshot chats = WaysGramChats.collect(account, selfId);
            out.dialogs.addAll(chats.dialogs);
            out.messages.addAll(chats.messages);
            out.chats.addAll(chats.chats);
            out.users.addAll(chats.users);
            TLRPC.TL_message father = WaysGramBotFather.lastMessage(account, selfId);
            if (father != null) {
                out.dialogs.add(WaysGramMapper.toDialog(WaysGramBotFather.ID, father.id));
                out.messages.add(father);
                out.users.add(WaysGramBotFather.user());
            }
        }
        out.users.add(selfUser(account));
        out.count = out.dialogs.size();
        return out;
    }

    private static TLObject getPeerDialogs(int account, TLRPC.TL_messages_getPeerDialogs req, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_messages_peerDialogs out = new TLRPC.TL_messages_peerDialogs();
        Conversations c = loadConversations(account, selfId);
        HashSet<Long> wanted = new HashSet<>();
        HashSet<Long> wantedChats = new HashSet<>();
        HashSet<Long> wantedChannels = new HashSet<>();
        for (TLRPC.InputDialogPeer p : req.peers) {
            if (p instanceof TLRPC.TL_inputDialogPeer) {
                TLRPC.InputPeer inputPeer = ((TLRPC.TL_inputDialogPeer) p).peer;
                if (inputPeer instanceof TLRPC.TL_inputPeerSelf) {
                    wanted.add(selfId);
                } else if (inputPeer instanceof TLRPC.TL_inputPeerUser) {
                    wanted.add(inputPeer.user_id);
                } else if (WaysGramChats.isGroup(inputPeer)) {
                    wantedChats.add(WaysGramChats.groupPeerId(inputPeer));
                } else if (inputPeer instanceof TLRPC.TL_inputPeerChannel) {
                    wantedChannels.add(inputPeer.channel_id);
                }
            }
        }
        if (wanted.contains(WaysGramBotFather.ID)) {
            TLRPC.TL_message father = WaysGramBotFather.lastMessage(account, selfId);
            if (father != null) {
                out.dialogs.add(WaysGramMapper.toDialog(WaysGramBotFather.ID, father.id));
                out.messages.add(father);
                out.users.add(WaysGramBotFather.user());
            }
        }
        if (!wantedChats.isEmpty() || !wantedChannels.isEmpty()) {
            WaysGramChats.Snapshot chats = WaysGramChats.collect(account, selfId);
            for (TLRPC.Dialog d : chats.dialogs) {
                if ((d.peer instanceof TLRPC.TL_peerChat && wantedChats.contains(d.peer.chat_id))
                        || (d.peer instanceof TLRPC.TL_peerChannel
                            && (wantedChannels.contains(d.peer.channel_id)
                                || (WaysGramChats.isGroupChannel(d.peer.channel_id)
                                    && wantedChats.contains(d.peer.channel_id))))) {
                    out.dialogs.add(d);

                }
            }
            for (TLRPC.Message message : chats.messages) {
                if ((message.peer_id instanceof TLRPC.TL_peerChat && wantedChats.contains(message.peer_id.chat_id))
                        || (message.peer_id instanceof TLRPC.TL_peerChannel
                            && (wantedChannels.contains(message.peer_id.channel_id)
                                || (WaysGramChats.isGroupChannel(message.peer_id.channel_id)
                                    && wantedChats.contains(message.peer_id.channel_id))))) {
                    out.messages.add(message);
                }
            }
            out.chats.addAll(chats.chats);
            out.users.addAll(chats.users);
        }
        for (int i = 0; i < c.dialogs.size(); i++) {
            TLRPC.Dialog d = c.dialogs.get(i);
            if (wanted.contains(d.peer.user_id)) {
                out.dialogs.add(d);
                out.messages.add(c.messages.get(i));
                out.users.add(c.users.get(i));
            }
        }
        // A chat without messages has no server-side conversation yet; the app still needs a
        // dialog for it, otherwise a freshly opened chat stays in its loading state.
        for (long wantedPeer : wanted) {
            boolean present = false;
            for (int i = 0; i < out.dialogs.size(); i++) {
                TLRPC.Dialog existing = out.dialogs.get(i);
                if (existing.peer instanceof TLRPC.TL_peerUser && existing.peer.user_id == wantedPeer) {
                    present = true;
                    break;
                }
            }
            if (present) {
                continue;
            }
            TLRPC.User wantedUser = wantedPeer == selfId ? selfUser(account) : null;
            if (wantedUser == null) {
                try {
                    wantedUser = WaysGramMapper.toUser(WaysGramApi.get(account, "/api/users/" + wantedPeer));
                } catch (WaysGramApi.ApiException e) {
                    FileLog.e(e);
                    continue;
                }
            }
            out.dialogs.add(WaysGramMapper.toDialog(wantedPeer, 0));
            out.users.add(wantedUser);
        }
        out.users.add(selfUser(account));
        out.state = state(account);
        return out;
    }

    private static TLObject getHistory(int account, TLRPC.TL_messages_getHistory req, long selfId) throws WaysGramApi.ApiException {
        TLRPC.TL_messages_messages out = new TLRPC.TL_messages_messages();
        if (req.peer instanceof TLRPC.TL_inputPeerChat || req.peer instanceof TLRPC.TL_inputPeerChannel) {
            return WaysGramChats.history(account, req, selfId);
        }
        // WaysGram saved messages: the chat with yourself is a normal user peer.
        if (!(req.peer instanceof TLRPC.TL_inputPeerUser) && !(req.peer instanceof TLRPC.TL_inputPeerSelf)) {
            return out;
        }
        long peerId = req.peer instanceof TLRPC.TL_inputPeerSelf ? selfId : req.peer.user_id;
        if (WaysGramBotFather.is(peerId)) {
            out.messages.addAll(WaysGramBotFather.history(account, selfId, req.offset_id, req.min_id, req.limit));
            out.users.add(WaysGramBotFather.user());
            out.users.add(selfUser(account));
            return out;
        }
        StringBuilder path = new StringBuilder("/api/messages?with=").append(peerId);
        if (req.min_id > 0) {
            path.append("&after_id=").append(req.min_id);
        } else if (req.offset_id > 0) {
            path.append("&before_id=").append(req.offset_id);
        }
        JSONObject json;
        try {
            json = WaysGramApi.get(account, path.toString());
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 404) {
                return out;
            }
            throw e;
        }
        JSONArray items = json.optJSONArray("messages");
        ArrayList<TLRPC.Message> list = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject m = items.optJSONObject(i);
                if (m != null) {
                    list.add(WaysGramMedia.attach(account, WaysGramMapper.toMessage(m, selfId, account), m));
                }
            }
        }
        // The server returns ascending order, the client expects the newest message first.
        Collections.sort(list, (a, b) -> Integer.compare(b.id, a.id));
        if (req.limit > 0 && list.size() > req.limit) {
            out.messages.addAll(list.subList(0, req.limit));
        } else {
            out.messages.addAll(list);
        }
        try {
            out.users.add(WaysGramMapper.toUser(WaysGramApi.get(account, "/api/users/" + peerId)));
        } catch (WaysGramApi.ApiException e) {
            FileLog.e(e);
        }
        out.users.add(selfUser(account));
        return out;
    }

    private static int replyIdOf(TLRPC.InputReplyTo reply) {
        return reply instanceof TLRPC.TL_inputReplyToMessage ? reply.reply_to_msg_id : 0;
    }

    private static TLObject updateCard(int account, TLRPC.InputPeer peer, int id, long selfId,
                                       String action, JSONArray values, JSONArray undo) throws WaysGramApi.ApiException {
        String scope;
        long target;
        if (WaysGramChats.isGroup(peer)) {
            scope = "group"; target = WaysGramChats.groupPeerId(peer); id = WaysGramChats.serverGroupId(id);
        } else if (peer instanceof TLRPC.TL_inputPeerChannel) {
            scope = "channel"; target = WaysGramChats.channelServer(peer.channel_id);
        } else if (peer instanceof TLRPC.TL_inputPeerUser || peer instanceof TLRPC.TL_inputPeerSelf) {
            scope = "dm"; target = peer instanceof TLRPC.TL_inputPeerSelf ? selfId : peer.user_id;
        } else throw new WaysGramApi.ApiException(400, "PEER_ID_INVALID");
        if (action != null) {
            JSONObject payload = new JSONObject();
            try {
                payload.put(action, values);
                if (undo != null) payload.put("incompleted", undo);
            } catch (org.json.JSONException e) {
                throw new WaysGramApi.ApiException(400, "CARD_INVALID");
            }
            WaysGramApi.post(account, "/api/message-cards/" + scope + "/" + target + "/" + id, payload);
        }
        TLRPC.Message message = scope.equals("group") ? WaysGramMessages.fetchGroup(account, target, id, selfId)
                : scope.equals("channel") ? WaysGramMessages.fetchChannel(account, target, id, selfId)
                : WaysGramMessages.fetchPrivate(account, target, id, selfId);
        if (message == null) throw new WaysGramApi.ApiException(404, "MESSAGE_ID_INVALID");
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.date = (int) (System.currentTimeMillis() / 1000);
        result.updates.add(WaysGramCards.update(message));
        return result;
    }

    /**
     * Кто фактически отправляет сообщение в группу.
     * -1 — обычный аккаунт, 0 — сама группа (анонимный админ), &gt; 0 — серверный id канала.
     * Локации и опросы по-прежнему требуют обычного отправителя — см. usesDefaultLocationSender.
     */
    private static long sendAsIdentity(TLRPC.InputPeer sendAs, TLRPC.InputPeer target) {
        if (sendAs == null || sendAs instanceof TLRPC.TL_inputPeerSelf || sendAs instanceof TLRPC.TL_inputPeerUser) {
            return -1;
        }
        if (sendAs instanceof TLRPC.TL_inputPeerChat) {
            return target instanceof TLRPC.TL_inputPeerChat && sendAs.chat_id == target.chat_id ? 0 : -1;
        }
        if (sendAs instanceof TLRPC.TL_inputPeerChannel) {
            if (target instanceof TLRPC.TL_inputPeerChannel && sendAs.channel_id == target.channel_id) {
                return 0;
            }
            return WaysGramChats.channelServer(sendAs.channel_id);
        }
        return -1;
    }

    // The native composer fills send_as even when no alternate sender is selected.
    private static boolean usesDefaultLocationSender(TLRPC.InputPeer sender, TLRPC.InputPeer target, long selfId) {
        return sender == null || sender instanceof TLRPC.TL_inputPeerSelf
                || (sender instanceof TLRPC.TL_inputPeerUser && sender.user_id == selfId)
                || (sender instanceof TLRPC.TL_inputPeerChannel && target instanceof TLRPC.TL_inputPeerChannel
                    && sender.channel_id == target.channel_id);
    }

    private static TLObject sendTo(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, TLRPC.InputReplyTo reply, long selfId, JSONObject location, boolean silent) throws WaysGramApi.ApiException {
        return sendTo(account, peer, text, randomId, mediaId, reply, selfId, location, null, silent, null, null, null, null, -1L);
    }

    private static TLObject sendTo(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, TLRPC.InputReplyTo reply, long selfId, JSONObject location, JSONObject contact, boolean silent) throws WaysGramApi.ApiException {
        return sendTo(account, peer, text, randomId, mediaId, reply, selfId, location, contact, silent, null, null, null, null, -1L);
    }

    private static TLObject sendTo(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, TLRPC.InputReplyTo reply, long selfId, JSONObject location, JSONObject contact, boolean silent, JSONArray entities) throws WaysGramApi.ApiException {
        return sendTo(account, peer, text, randomId, mediaId, reply, selfId, location, contact, silent, entities, null, null, null, -1L);
    }

    private static TLObject sendTo(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, TLRPC.InputReplyTo reply, long selfId, JSONObject location, JSONObject contact, boolean silent, JSONArray entities, JSONObject card) throws WaysGramApi.ApiException {
        return sendTo(account, peer, text, randomId, mediaId, reply, selfId, location, contact, silent, entities, card, null, null, -1L);
    }

    private static TLObject sendTo(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, TLRPC.InputReplyTo reply, long selfId, JSONObject location, JSONObject contact, boolean silent, JSONArray entities, long identity) throws WaysGramApi.ApiException {
        return sendTo(account, peer, text, randomId, mediaId, reply, selfId, location, contact, silent, entities, null, null, null, identity);
    }

    private static TLObject sendTo(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, TLRPC.InputReplyTo reply, long selfId, JSONObject location, JSONObject contact, boolean silent, long identity) throws WaysGramApi.ApiException {
        return sendTo(account, peer, text, randomId, mediaId, reply, selfId, location, contact, silent, null, null, null, null, identity);
    }

    private static TLObject sendTo(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId,
                                   TLRPC.InputReplyTo reply, long selfId, JSONObject location, JSONObject contact,
                                   boolean silent, JSONArray entities, JSONObject card, String expectedToken, Integer approvedFee, long identity) throws WaysGramApi.ApiException {
        int replyTo = replyIdOf(reply);
        JSONObject quote = WaysGramMapper.quoteFor(reply);
        if (peer instanceof TLRPC.TL_inputPeerChat || peer instanceof TLRPC.TL_inputPeerChannel) {
            if (card == null) {
                return identity < 0
                        ? WaysGramChats.send(account, peer, text, randomId, mediaId, replyTo, selfId, nextPts(account), silent, location, contact, entities, quote)
                        : WaysGramChats.send(account, peer, text, randomId, mediaId, replyTo, selfId, nextPts(account), silent, location, contact, entities, quote, null, identity);
            }
            return WaysGramChats.send(account, peer, text, randomId, mediaId, replyTo, selfId, nextPts(account), silent, location, contact, entities, quote, card, identity);
        }
        // WaysGram saved messages: the recipient is the account itself.
        if (!(peer instanceof TLRPC.TL_inputPeerUser) && !(peer instanceof TLRPC.TL_inputPeerSelf)) {
            throw new WaysGramApi.ApiException(400, "METHOD_NOT_SUPPORTED");
        }
        final long targetId = peer instanceof TLRPC.TL_inputPeerSelf ? selfId : peer.user_id;
        if (peer instanceof TLRPC.TL_inputPeerUser && WaysGramBotFather.is(peer.user_id)) {
            if (contact != null || card != null) throw new WaysGramApi.ApiException(400, "MEDIA_NOT_SUPPORTED");
            if (entities != null) {
                for (int i = 0; i < entities.length(); i++) {
                    JSONObject entry = entities.optJSONObject(i);
                    String type = entry == null ? "" : entry.optString("type", "");
                    if (!(type.equals("url") || type.equals("mention") || type.equals("hashtag"))) {
                        throw new WaysGramApi.ApiException(400, "FORMATTING_NOT_SUPPORTED");
                    }
                }
            }
            return WaysGramBotFather.send(account, text, selfId, nextPts(account));
        }
        JSONObject body = new JSONObject();
        try {
            if (card != null) body.put("card", card);
            body.put("recipient_id", targetId);
            if (approvedFee != null) body.put("approved_fee_stars", approvedFee);
            body.put("body", text == null ? "" : text);
            if (entities != null && entities.length() != 0) body.put("entities", entities);
            if (!TextUtils.isEmpty(mediaId)) {
                body.put("media_id", mediaId);
            }
            if (location != null) {
                body.put("location", location);
            }
            if (contact != null) {
                body.put("contact", contact);
            }
            body.put("silent", silent);
            if (quote != null) body.put("quote", quote);
            if (replyTo > 0) {
                body.put("reply_to_id", replyTo);
            }
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "MESSAGE_INVALID");
        }
        // The same random_id means the same message, so a retry after a network failure cannot duplicate it.
        String key = "wg-" + Long.toHexString(randomId) + "-" + Long.toHexString(randomId * 31 + 7);
        String path = quote == null ? "/api/messages" : "/api/messages/quote/v1";
        JSONObject result = expectedToken == null ? WaysGramApi.postIdempotent(account, path, body, key)
                : WaysGramApi.request("POST", path, body, expectedToken, 30000, key);
        if (expectedToken != null && !TextUtils.equals(expectedToken, WaysGramApi.getToken(account)))
            throw new WaysGramApi.ApiException(401, "AUTH_KEY_UNREGISTERED");
        WaysGramPoller.markDelivered(account, targetId, result.optInt("id"));

        TLRPC.TL_updateShortSentMessage sent = new TLRPC.TL_updateShortSentMessage();
        sent.out = true;
        sent.flags = TLObject.FLAG_1;
        sent.id = result.optInt("id");
        sent.date = result.optInt("created_at", (int) (System.currentTimeMillis() / 1000));
        sent.pts = nextPts(account);
        sent.pts_count = 1;
        TLRPC.TL_message formatted = new TLRPC.TL_message();
        formatted.message = result.optString("body", "");
        WaysGramMapper.readTextEntities(formatted, result);
        if (!formatted.entities.isEmpty()) {
            sent.entities = formatted.entities;
            sent.flags |= TLObject.FLAG_7;
        }
        if (!TextUtils.isEmpty(mediaId) || location != null || contact != null || card != null) {
            TLRPC.Message holder = WaysGramMedia.attach(account, new TLRPC.TL_message(), result);
            sent.media = holder.media;
            if (sent.media != null) {
                sent.flags |= TLObject.FLAG_9;
            }
        }
        return sent;
    }

    private static TLRPC.TL_error mapError(WaysGramApi.ApiException e, boolean authRequest) {
        if (e.status == -1000) {
            if (authRequest) {
                // The login screen ignores code -1000 silently; a visible message tells the user what is wrong.
                return error(400, "Нет связи с сервером WaysGram (" + e.detail + ")");
            }
            return error(-1000, "NETWORK_ERROR");
        }
        if (e.status == 429) {
            return error(420, "FLOOD_WAIT_30");
        }
        if (!authRequest) {
            if (e.status == 401) {
                // The session is gone (expired or revoked): the app treats this as a logout.
                return error(401, "AUTH_KEY_UNREGISTERED");
            }
            final String detail = e.detail == null ? "" : e.detail;
            if (detail.contains("MESSAGE_FEE_UNSUPPORTED_FOR_SEND_TYPE")) {
                // Медиаальбом, пересылка, живая геолокация и отложенная отправка в платный чат
                // сервер не принимает бесплатно, а оплату для них проводит только сам текст.
                return error(402, "Пользователь принимает только платные сообщения");
            }
            if (detail.contains("GIFT_PRIVACY_FORBIDDEN")) {
                return error(403, "Пользователь ограничил получение подарков");
            }
            return error(e.status >= 500 ? 500 : 400, TextUtils.isEmpty(e.detail) ? "ERROR" : e.detail);
        }
        if (e.status == 401) {
            return error(400, "PHONE_CODE_INVALID");
        }
        if (e.status == 400) {
            // Сервер объясняет, что именно не так с номером («+код страны и номер»,
            // «номер не закреплён за аккаунтом»): показываем это вместо общего текста.
            return error(400, TextUtils.isEmpty(e.detail) ? "PHONE_NUMBER_INVALID" : e.detail);
        }
        if (e.status >= 500) {
            return error(400, "Сервер WaysGram недоступен (" + e.status + ")");
        }
        // 403 / 404: show the server's own explanation (for example "verify in the bot first")
        return error(400, TextUtils.isEmpty(e.detail) ? "ERROR" : e.detail);
    }
}
