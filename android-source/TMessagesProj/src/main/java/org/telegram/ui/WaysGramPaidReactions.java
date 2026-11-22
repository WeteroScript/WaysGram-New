package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.UUID;

final class WaysGramPaidReactions {
    private interface Work { JSONObject run() throws Exception; }
    private interface Result { void accept(JSONObject data); }

    private static boolean active(BaseFragment fragment, int account, String token) {
        return fragment.getParentActivity() != null && !TextUtils.isEmpty(token)
                && TextUtils.equals(token, WaysGramApi.getToken(account));
    }

    private static void request(BaseFragment fragment, int account, String token, Work work, Result success, Runnable failure) {
        if (!active(fragment, account, token)) return;
        WaysGramApi.NETWORK.execute(() -> {
            JSONObject result;
            try {
                result = work.run();
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (active(fragment, account, token)) failure.run();
                });
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (active(fragment, account, token)) success.accept(result);
            });
        });
    }

    private static AlertDialog.Builder dialog(BaseFragment fragment) {
        return new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(LocaleController.getString(R.string.WaysGramPaidReactions));
    }

    private static void notice(BaseFragment fragment, int text) {
        fragment.showDialog(dialog(fragment).setMessage(LocaleController.getString(text))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    private static AlertDialog.Builder subscriptionDialog(BaseFragment fragment) {
        return new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(LocaleController.getString(R.string.WaysGramSubscription));
    }

    private static void subscriptionNotice(BaseFragment fragment, int text) {
        fragment.showDialog(subscriptionDialog(fragment).setMessage(LocaleController.getString(text))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    static void showSubscriptionSettings(BaseFragment fragment, int account, long channel) {
        String session = WaysGramApi.getToken(account);
        String path = "/api/channels/" + org.telegram.tgnet.WaysGramChats.channelServer(channel) + "/subscription-offer";
        request(fragment, account, session, () -> WaysGramApi.request("GET", path, null, session, 10000), offer -> {
            int price = offer.optInt("price_stars");
            String token = offer.optString("token");
            if (price <= 0 || !token.matches("[A-Za-z0-9_-]{43}")) {
                editSubscriptionPrice(fragment, account, session, path, 0);
                return;
            }
            fragment.showDialog(subscriptionDialog(fragment)
                    .setMessage(LocaleController.formatString(R.string.WaysGramSubscriptionCurrent, price))
                    .setPositiveButton(LocaleController.getString(R.string.WaysGramSubscriptionCopy), (d, w) -> {
                        if (active(fragment, account, session)) {
                            AndroidUtilities.addToClipboard(org.telegram.tgnet.WaysGramLinks.BASE + "/api/channel-subscriptions/" + token);
                            subscriptionNotice(fragment, R.string.WaysGramSubscriptionCopied);
                        }
                    })
                    .setNeutralButton(LocaleController.getString(R.string.WaysGramSubscriptionChange), (d, w) ->
                            editSubscriptionPrice(fragment, account, session, path, price))
                    .setNegativeButton(LocaleController.getString(R.string.WaysGramSubscriptionRevoke), (d, w) -> {
                        if (!active(fragment, account, session)) return;
                        fragment.showDialog(subscriptionDialog(fragment)
                                .setMessage(LocaleController.getString(R.string.WaysGramSubscriptionRevokeConfirm))
                                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                                .setPositiveButton(LocaleController.getString(R.string.WaysGramSubscriptionRevoke), (confirm, button) ->
                                        saveSubscriptionOffer(fragment, account, session, path, 0)).create());
                    }).create());
        }, () -> subscriptionNotice(fragment, R.string.WaysGramSubscriptionError));
    }

    private static void editSubscriptionPrice(BaseFragment fragment, int account, String session, String path, int price) {
        if (!active(fragment, account, session)) return;
        android.widget.EditText field = new android.widget.EditText(fragment.getParentActivity());
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        field.setHint("1–10000 Stars");
        if (price > 0) field.setText(Integer.toString(price));
        fragment.showDialog(subscriptionDialog(fragment).setView(field)
                .setMessage(LocaleController.getString(R.string.WaysGramSubscriptionPrice))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                    int value;
                    try { value = Integer.parseInt(field.getText().toString()); } catch (NumberFormatException e) { value = 0; }
                    if (value < 1 || value > 10000) {
                        subscriptionNotice(fragment, R.string.WaysGramPaidMediaInvalidPrice);
                    } else {
                        saveSubscriptionOffer(fragment, account, session, path, value);
                    }
                }).create());
    }

    private static void saveSubscriptionOffer(BaseFragment fragment, int account, String session, String path, int price) {
        request(fragment, account, session, () -> WaysGramApi.request("PUT", path,
                new JSONObject().put("price_stars", price), session, 15000), data -> {
            String token = data.optString("token");
            if (price == 0) {
                subscriptionNotice(fragment, R.string.WaysGramSubscriptionRevoked);
            } else if (token.matches("[A-Za-z0-9_-]{43}")) {
                fragment.showDialog(subscriptionDialog(fragment).setMessage(LocaleController.formatString(R.string.WaysGramSubscriptionCurrent, price))
                        .setNegativeButton(LocaleController.getString(R.string.Close), null)
                        .setPositiveButton(LocaleController.getString(R.string.WaysGramSubscriptionCopy), (d, w) -> {
                            if (active(fragment, account, session)) {
                                AndroidUtilities.addToClipboard(org.telegram.tgnet.WaysGramLinks.BASE + "/api/channel-subscriptions/" + token);
                                subscriptionNotice(fragment, R.string.WaysGramSubscriptionCopied);
                            }
                        }).create());
            } else {
                subscriptionNotice(fragment, R.string.WaysGramSubscriptionError);
            }
        }, () -> subscriptionNotice(fragment, R.string.WaysGramSubscriptionError));
    }

    static void showSubscription(BaseFragment fragment, int account, String offerToken) {
        String session = WaysGramApi.getToken(account);
        if (!active(fragment, account, session) || !offerToken.matches("[A-Za-z0-9_-]{43}")) return;
        SharedPreferences pending = fragment.getParentActivity().getSharedPreferences("waysgram_channel_subscriptions", Context.MODE_PRIVATE);
        String slot = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId() + ":" + offerToken;
        String path = "/api/channel-subscriptions/" + offerToken;
        String previous = pending.getString(slot, null);
        int amount = pending.getInt(slot + ":amount", 0);
        if (previous != null && amount > 0) {
            confirmSubscription(fragment, account, session, path, pending, slot, amount, previous, "");
            return;
        }
        request(fragment, account, session, () -> WaysGramApi.request("GET", path, null, session, 10000), quote -> {
            if (quote.optBoolean("already_has_access")) {
                subscriptionReceipt(fragment, account, session, quote);
            } else {
                int price = quote.optInt("price_stars");
                if (price < 1 || price > 10000) subscriptionNotice(fragment, R.string.WaysGramSubscriptionError);
                else confirmSubscription(fragment, account, session, path, pending, slot, price, null, quote.optString("title"));
            }
        }, () -> subscriptionNotice(fragment, R.string.WaysGramSubscriptionError));
    }

    private static void confirmSubscription(BaseFragment fragment, int account, String session, String path, SharedPreferences pending,
                                            String slot, int price, String previous, String title) {
        String text = previous == null ? LocaleController.formatString(R.string.WaysGramSubscriptionConfirm, title, price)
                : LocaleController.formatString(R.string.WaysGramSubscriptionRetry, price);
        fragment.showDialog(subscriptionDialog(fragment).setMessage(text)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(previous == null ? R.string.WaysGramSubscriptionBuy : R.string.WaysGramPaidRetry), (d, w) -> {
                    if (!active(fragment, account, session) || !TextUtils.equals(previous, pending.getString(slot, null))) return;
                    String key = previous == null ? UUID.randomUUID().toString() : previous;
                    if (!pending.edit().putString(slot, key).putInt(slot + ":amount", price).commit()) {
                        subscriptionNotice(fragment, R.string.WaysGramSubscriptionError);
                        return;
                    }
                    request(fragment, account, session, () -> {
                        try {
                            JSONObject receipt = WaysGramApi.request("POST", path, new JSONObject().put("approved_price_stars", price), session, 15000, key);
                            clear(pending, slot, key);
                            return receipt;
                        } catch (WaysGramApi.ApiException e) {
                            if (e.status == 400 || e.status == 402 || e.status == 403 || e.status == 404 || e.status == 409) {
                                clear(pending, slot, key);
                                return new JSONObject().put("rejected", true);
                            }
                            throw e;
                        }
                    }, data -> {
                        if (data.optBoolean("rejected")) subscriptionNotice(fragment, R.string.WaysGramSubscriptionRejected);
                        else subscriptionReceipt(fragment, account, session, data);
                    }, () -> subscriptionNotice(fragment, R.string.WaysGramSubscriptionUnknown));
                }).create());
    }

    private static void subscriptionReceipt(BaseFragment fragment, int account, String session, JSONObject data) {
        long expires = data.optLong("purchased_until");
        long channel = data.optLong("channel_id");
        if (channel <= 0 || expires > 0 && expires * 1000 <= System.currentTimeMillis()) {
            subscriptionNotice(fragment, R.string.WaysGramSubscriptionExpired);
            return;
        }
        String text = expires > 0 ? LocaleController.formatString(R.string.WaysGramSubscriptionUntil,
                java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(expires * 1000)))
                : LocaleController.getString(R.string.WaysGramSubscriptionAlready);
        fragment.showDialog(subscriptionDialog(fragment).setMessage(text)
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .setPositiveButton(LocaleController.getString(R.string.WaysGramSubscriptionOpen), (d, w) ->
                        request(fragment, account, session, () -> WaysGramApi.request("GET", "/api/channels/" + channel, null, session, 10000), raw -> {
                            long self = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
                            org.telegram.tgnet.TLRPC.Chat chat = org.telegram.tgnet.WaysGramChats.toChannel(raw, self, true);
                            org.telegram.tgnet.WaysGramChats.setJoined(account, channel, true);
                            org.telegram.messenger.MessagesController.getInstance(account).putChat(chat, false);
                            java.util.ArrayList<org.telegram.tgnet.TLRPC.Chat> chats = new java.util.ArrayList<>();
                            chats.add(chat);
                            org.telegram.messenger.MessagesStorage.getInstance(account).putUsersAndChats(null, chats, false, true);
                            android.os.Bundle args = new android.os.Bundle();
                            args.putLong("chat_id", chat.id);
                            fragment.presentFragment(new ChatActivity(args));
                        }, () -> subscriptionNotice(fragment, R.string.WaysGramSubscriptionError))).create());
    }

    private static String paidMediaPath(long channel, int post) {
        return "/api/channels/" + org.telegram.tgnet.WaysGramChats.channelServer(channel) + "/messages/" + post + "/paid-media";
    }

    private static AlertDialog.Builder paidMediaDialog(BaseFragment fragment) {
        return new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(LocaleController.getString(R.string.WaysGramPaidMedia));
    }

    private static void paidMediaNotice(BaseFragment fragment, int text) {
        fragment.showDialog(paidMediaDialog(fragment).setMessage(LocaleController.getString(text))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    static void showPaidMedia(ChatActivity fragment, int account, long channel, int post, Runnable pick) {
        final String token = WaysGramApi.getToken(account);
        String path = paidMediaPath(channel, post);
        request(fragment, account, token, () -> {
            try {
                return WaysGramApi.request("GET", path, null, token, 10000);
            } catch (WaysGramApi.ApiException e) {
                if (e.status == 404 && "PAID_MEDIA_NOT_FOUND".equals(e.detail)) return new JSONObject();
                throw e;
            }
        }, offer -> {
            if (offer.optBoolean("unlocked")) {
                viewPaidMedia(fragment, account, token, channel, post, offer);
                return;
            }
            int price = offer.optInt("price_stars");
            if (price <= 0) {
                if (pick == null) paidMediaNotice(fragment, R.string.WaysGramPaidMediaNone);
                else fragment.showDialog(paidMediaDialog(fragment).setMessage(LocaleController.getString(R.string.WaysGramPaidMediaAddHint))
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .setPositiveButton(LocaleController.getString(R.string.WaysGramPaidMediaAdd), (d, w) -> {
                            if (active(fragment, account, token)) pick.run();
                        }).create());
                return;
            }
            fragment.showDialog(paidMediaDialog(fragment)
                    .setMessage(LocaleController.formatString(R.string.WaysGramPaidMediaConfirm, price))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(R.string.WaysGramPaidMediaBuy), (d, w) ->
                            request(fragment, account, token, () -> WaysGramApi.request("POST", path,
                                            new JSONObject().put("approved_price_stars", price), token, 15000),
                                    data -> viewPaidMedia(fragment, account, token, channel, post, data),
                                    () -> paidMediaNotice(fragment, R.string.WaysGramPaidMediaBuyError))).create());
        }, () -> paidMediaNotice(fragment, R.string.WaysGramPaidMediaError));
    }

    private static void viewPaidMedia(ChatActivity fragment, int account, String token, long channel, int post, JSONObject media) {
        if (!active(fragment, account, token) || !media.optBoolean("unlocked") || media.optString("media_id").isEmpty()) return;
        WaysGramApi.NETWORK.execute(() -> {
            org.telegram.tgnet.TLRPC.TL_message message = new org.telegram.tgnet.TLRPC.TL_message();
            message.id = post;
            message.date = (int) (System.currentTimeMillis() / 1000);
            message.message = "";
            message.post = true;
            message.noforwards = true;
            org.telegram.tgnet.TLRPC.TL_peerChannel peer = new org.telegram.tgnet.TLRPC.TL_peerChannel();
            peer.channel_id = channel;
            message.peer_id = peer;
            message.from_id = peer;
            if (TextUtils.equals(token, WaysGramApi.getToken(account))) {
                org.telegram.tgnet.WaysGramMedia.attach(account, message, media);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!active(fragment, account, token)) return;
                if (message.media == null) {
                    paidMediaNotice(fragment, R.string.WaysGramPaidMediaError);
                    return;
                }
                PhotoViewer viewer = PhotoViewer.getInstance();
                viewer.setParentActivity(fragment);
                viewer.openPhoto(new org.telegram.messenger.MessageObject(account, message, false, true),
                        null, 0, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
            });
        });
    }

    static void confirmPaidMediaUpload(ChatActivity fragment, int account, String token, long channel, int post, android.net.Uri uri) {
        if (!active(fragment, account, token)) return;
        android.content.ContentResolver resolver = fragment.getParentActivity().getContentResolver();
        android.widget.EditText field = new android.widget.EditText(fragment.getParentActivity());
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        field.setHint("1–10000 Stars");
        fragment.showDialog(paidMediaDialog(fragment).setView(field)
                .setMessage(LocaleController.getString(R.string.WaysGramPaidMediaSetPrice))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.WaysGramPaidMediaAdd), (d, w) -> {
                    if (!active(fragment, account, token)) return;
                    int value;
                    try { value = Integer.parseInt(field.getText().toString()); } catch (NumberFormatException e) { value = 0; }
                    if (value < 1 || value > 10000) {
                        paidMediaNotice(fragment, R.string.WaysGramPaidMediaInvalidPrice);
                        return;
                    }
                    final int price = value;
                    request(fragment, account, token, () -> {
                        String mime = resolver.getType(uri);
                        boolean image = mime != null && mime.startsWith("image/");
                        if (!image && !"video/mp4".equals(mime)) throw new java.io.IOException("Unsupported file");
                        int max = (image ? 10 : 25) * 1024 * 1024;
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                        try (java.io.InputStream in = resolver.openInputStream(uri)) {
                            if (in == null) throw new java.io.IOException("Could not open file");
                            byte[] buffer = new byte[65536];
                            int size;
                            while ((size = in.read(buffer)) != -1) {
                                if (bytes.size() + size > max) throw new java.io.IOException("File too large");
                                bytes.write(buffer, 0, size);
                            }
                        }
                        JSONObject upload = WaysGramApi.uploadWithToken(image ? "/api/media/images" : "/api/media/documents",
                                bytes.toByteArray(), mime, image ? "photo" : "video.mp4", image ? null : "video", null, token);
                        return WaysGramApi.request("PUT", paidMediaPath(channel, post),
                                new JSONObject().put("price_stars", price).put("media_id", upload.getString("id")), token, 15000);
                    }, result -> paidMediaNotice(fragment, R.string.WaysGramPaidMediaAdded),
                            () -> paidMediaNotice(fragment, R.string.WaysGramPaidMediaUploadError));
                }).create());
    }

    static void showGiveaways(BaseFragment fragment, int account, long channel) {
        String token = WaysGramApi.getToken(account);
        String path = "/api/channels/" + org.telegram.tgnet.WaysGramChats.channelServer(channel) + "/giveaways";
        request(fragment, account, token, () -> WaysGramApi.request("GET", path, null, token, 10000), data -> {
            org.json.JSONArray rows = data.optJSONArray("giveaways");
            StringBuilder summary = new StringBuilder();
            long open = 0;
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                if (summary.length() > 0) summary.append("\n\n");
                summary.append(LocaleController.formatString(R.string.WaysGramGiveawayPrize, row.optInt("prize_stars"), row.optInt("winner_count"))).append('\n');
                switch (row.optString("state")) {
                    case "open":
                        long ends = row.optLong("ends_at") * 1000L;
                        summary.append(LocaleController.formatString(R.string.WaysGramGiveawayEnds,
                                java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(ends))));
                        if (row.optBoolean("entered")) summary.append('\n').append(LocaleController.getString(R.string.WaysGramGiveawayEntered));
                        else if (ends > System.currentTimeMillis()) open = row.optLong("id");
                        break;
                    case "drawn":
                        summary.append(LocaleController.formatString(R.string.WaysGramGiveawayDrawn, row.optInt("drawn_count")));
                        if (row.optBoolean("won")) summary.append('\n').append(LocaleController.getString(R.string.WaysGramGiveawayWon));
                        break;
                    case "unfunded": summary.append(LocaleController.getString(R.string.WaysGramGiveawayUnfunded)); break;
                    default: summary.append(LocaleController.getString(R.string.WaysGramGiveawayNoWinners)); break;
                }
            }
            AlertDialog.Builder builder = giveawayDialog(fragment).setMessage(summary.length() == 0 ? LocaleController.getString(R.string.WaysGramGiveawayEmpty) : summary)
                    .setNegativeButton(LocaleController.getString(R.string.Close), null);
            if (data.optBoolean("can_create")) {
                builder.setPositiveButton(LocaleController.getString(R.string.WaysGramGiveawayCreate), (d, w) -> createGiveaway(fragment, account, channel, token, path));
            } else if (open > 0 && data.optBoolean("can_enter")) {
                long id = open;
                builder.setPositiveButton(LocaleController.getString(R.string.WaysGramGiveawayEnter), (d, w) ->
                        request(fragment, account, token, () -> WaysGramApi.request("PUT", path + "/" + id + "/entry", null, token, 10000),
                                result -> showGiveaways(fragment, account, channel), () -> giveawayError(fragment)));
            }
            fragment.showDialog(builder.create());
        }, () -> giveawayError(fragment));
    }

    private static void createGiveaway(BaseFragment fragment, int account, long channel, String token, String path) {
        if (!active(fragment, account, token)) return;
        android.widget.LinearLayout fields = new android.widget.LinearLayout(fragment.getParentActivity());
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        int[] hints = {R.string.WaysGramGiveawayDays, R.string.WaysGramGiveawayPrizeInput, R.string.WaysGramGiveawayWinnerInput};
        android.widget.EditText[] inputs = new android.widget.EditText[hints.length];
        for (int i = 0; i < hints.length; i++) {
            inputs[i] = new android.widget.EditText(fragment.getParentActivity());
            inputs[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            inputs[i].setHint(LocaleController.getString(hints[i]));
            fields.addView(inputs[i]);
        }
        fragment.showDialog(giveawayDialog(fragment).setView(fields).setMessage(LocaleController.getString(R.string.WaysGramGiveawayFunding))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.WaysGramGiveawayCreate), (d, w) -> {
                    try {
                        int days = Integer.parseInt(inputs[0].getText().toString());
                        int prize = Integer.parseInt(inputs[1].getText().toString());
                        int count = Integer.parseInt(inputs[2].getText().toString());
                        if (days < 1 || days > 30 || prize < 1 || prize > 10000 || count < 1 || count > 50 || prize * count > 100000) throw new IllegalArgumentException();
                        JSONObject body = new JSONObject().put("ends_at", System.currentTimeMillis() / 1000L + days * 86400L - 60)
                                .put("prize_stars", prize).put("winner_count", count);
                        request(fragment, account, token, () -> WaysGramApi.request("POST", path, body, token, 10000),
                                result -> showGiveaways(fragment, account, channel), () -> giveawayError(fragment));
                    } catch (Exception e) { giveawayError(fragment); }
                }).create());
    }

    private static AlertDialog.Builder giveawayDialog(BaseFragment fragment) {
        return new AlertDialog.Builder(fragment.getParentActivity()).setTitle(LocaleController.getString(R.string.WaysGramGiveaways));
    }

    private static void giveawayError(BaseFragment fragment) {
        fragment.showDialog(giveawayDialog(fragment).setMessage(LocaleController.getString(R.string.WaysGramGiveawayError))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    static void showCommunityBoosts(BaseFragment fragment, int account, long chat, boolean channel) {
        String token = WaysGramApi.getToken(account);
        long serverId = channel ? org.telegram.tgnet.WaysGramChats.channelServer(chat) : chat;
        String path = "/api/community-boosts/" + (channel ? "channel/" : "group/") + serverId;
        request(fragment, account, token, () -> WaysGramApi.request("GET", path, null, token, 10000), data -> {
            int next = data.optInt("next_level_at");
            boolean here = data.optBoolean("assigned_here");
            boolean inUse = data.optBoolean("slot_in_use");
            boolean canBoost = data.optBoolean("can_boost");
            long moveAt = data.optLong("movable_at");
            String message = LocaleController.formatString(R.string.WaysGramBoostSummary, data.optInt("boosts"), data.optInt("level"));
            if (next > 0) message += "\n" + LocaleController.formatString(R.string.WaysGramBoostNext, next);
            if (here) message += "\n\n" + LocaleController.getString(R.string.WaysGramBoostAssigned);
            else if (!canBoost) message += "\n\n" + LocaleController.getString(R.string.WaysGramBoostJoin);
            else if (moveAt * 1000 > System.currentTimeMillis()) message += "\n\n" + LocaleController.formatString(R.string.WaysGramBoostCooldown,
                    java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(moveAt * 1000)));
            if (!channel && data.optBoolean("slow_mode_bypass")) message += "\n\n" + LocaleController.getString(R.string.WaysGramBoostBypassOn);
            AlertDialog.Builder builder = boostDialog(fragment).setMessage(message)
                    .setNegativeButton(LocaleController.getString(R.string.Close), null);
            if (canBoost && !here && moveAt * 1000 <= System.currentTimeMillis()) {
                builder.setPositiveButton(LocaleController.getString(inUse ? R.string.WaysGramBoostMove : R.string.WaysGramBoostApply), (d, w) -> {
                    if (!active(fragment, account, token)) return;
                    fragment.showDialog(boostDialog(fragment).setMessage(LocaleController.getString(inUse ? R.string.WaysGramBoostMoveConfirm : R.string.WaysGramBoostConfirm))
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .setPositiveButton(LocaleController.getString(R.string.OK), (confirm, button) ->
                                    request(fragment, account, token, () -> WaysGramApi.request("PUT", path, null, token, 10000),
                                            result -> showCommunityBoosts(fragment, account, chat, channel), () -> boostError(fragment))).create());
                });
            }
            if (!channel && data.optBoolean("can_configure")) {
                boolean bypass = data.optBoolean("slow_mode_bypass");
                builder.setNeutralButton(LocaleController.getString(bypass ? R.string.WaysGramBoostBypassDisable : R.string.WaysGramBoostBypassEnable),
                        (d, w) -> request(fragment, account, token, () -> WaysGramApi.request("PUT", "/api/groups/" + serverId + "/boost-settings",
                                new JSONObject().put("slow_mode_bypass", !bypass), token, 10000),
                                result -> showCommunityBoosts(fragment, account, chat, false), () -> boostError(fragment)));
            }
            fragment.showDialog(builder.create());
        }, () -> boostError(fragment));
    }

    private static AlertDialog.Builder boostDialog(BaseFragment fragment) {
        return new AlertDialog.Builder(fragment.getParentActivity()).setTitle(LocaleController.getString(R.string.WaysGramCommunityBoosts));
    }

    private static void boostError(BaseFragment fragment) {
        fragment.showDialog(boostDialog(fragment).setMessage(LocaleController.getString(R.string.WaysGramBoostError))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    static void showGroupSendPermissions(BaseFragment fragment, int account, long group) {
        String token = WaysGramApi.getToken(account);
        String path = "/api/groups/" + group;
        request(fragment, account, token, () -> WaysGramApi.request("GET", path, null, token, 10000), data -> {
            int mask = data.optInt("default_send_restrictions", -1);
            if (mask < 0 || mask > 63) {
                showGroupSendPermissionResult(fragment, R.string.WaysGramGroupSendError);
                return;
            }
            int[] labels = {R.string.WaysGramGroupSendAll, R.string.WaysGramGroupSendPhotos, R.string.WaysGramGroupSendVideos,
                    R.string.WaysGramGroupSendVoice, R.string.WaysGramGroupSendStickers, R.string.WaysGramGroupSendFiles};
            android.widget.LinearLayout layout = new android.widget.LinearLayout(fragment.getParentActivity());
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.CheckBox[] boxes = new android.widget.CheckBox[labels.length];
            for (int i = 0; i < labels.length; i++) {
                boxes[i] = new android.widget.CheckBox(fragment.getParentActivity());
                boxes[i].setText(LocaleController.getString(labels[i]));
                boxes[i].setChecked((mask & (1 << i)) != 0);
                layout.addView(boxes[i]);
            }
            boolean supportsAntiSpam = data.has("anti_spam_enabled");
            android.widget.CheckBox antiSpam = new android.widget.CheckBox(fragment.getParentActivity());
            antiSpam.setText(LocaleController.getString(R.string.WaysGramGroupAntiSpam));
            antiSpam.setChecked(data.optBoolean("anti_spam_enabled"));
            if (supportsAntiSpam) layout.addView(antiSpam);
            fragment.showDialog(groupPermissionDialog(fragment).setView(layout)
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                        int bits = 0;
                        for (int i = 0; i < boxes.length; i++) if (boxes[i].isChecked()) bits |= 1 << i;
                        final int updated = bits;
                        final boolean filter = antiSpam.isChecked();
                        request(fragment, account, token, () -> {
                            JSONObject payload = new JSONObject().put("default_send_restrictions", updated);
                            if (supportsAntiSpam) payload.put("anti_spam_enabled", filter);
                            return WaysGramApi.request("PUT", path + "/send-permissions", payload, token, 10000);
                        },
                                result -> showGroupSendPermissionResult(fragment, R.string.WaysGramGroupSendSaved),
                                () -> showGroupSendPermissionResult(fragment, R.string.WaysGramGroupSendError));
                    }).create());
        }, () -> showGroupSendPermissionResult(fragment, R.string.WaysGramGroupSendError));
    }

    private static AlertDialog.Builder groupPermissionDialog(BaseFragment fragment) {
        return new AlertDialog.Builder(fragment.getParentActivity()).setTitle(LocaleController.getString(R.string.WaysGramGroupSendPermissions));
    }

    private static void showGroupSendPermissionResult(BaseFragment fragment, int text) {
        fragment.showDialog(groupPermissionDialog(fragment).setMessage(LocaleController.getString(text))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    static void showAnalytics(BaseFragment fragment, int account, long channel) {
        showAnalytics(fragment, account, "/api/channels/" + org.telegram.tgnet.WaysGramChats.channelServer(channel), false);
    }

    static void showGroupAnalytics(BaseFragment fragment, int account, long group) {
        showAnalytics(fragment, account, "/api/groups/" + group, true);
    }

    private static void showAnalytics(BaseFragment fragment, int account, String path, boolean group) {
        String token = WaysGramApi.getToken(account);
        if (!active(fragment, account, token)) return;
        int title = group ? R.string.WaysGramGroupAnalytics : R.string.WaysGramChannelAnalytics;
        int[] days = {7, 30, 90};
        CharSequence[] ranges = new CharSequence[days.length];
        for (int i = 0; i < days.length; i++) {
            ranges[i] = LocaleController.formatString(R.string.WaysGramAnalyticsDays, days[i]);
        }
        fragment.showDialog(analyticsDialog(fragment, title).setItems(ranges, (d, which) ->
                request(fragment, account, token,
                        () -> WaysGramApi.request("GET", path + "/analytics?days=" + days[which], null, token, 10000),
                        data -> {
                            String summary = group ? LocaleController.formatString(R.string.WaysGramGroupAnalyticsSummary,
                                    data.optInt("days"), data.optLong("members"), data.optLong("current_members_joined"),
                                    data.optLong("messages_remaining"), data.optLong("unique_authors"))
                                    : LocaleController.formatString(R.string.WaysGramAnalyticsSummary,
                                    data.optInt("days"), data.optLong("members"), data.optLong("current_members_joined"),
                                    data.optLong("published_posts_remaining"), data.optLong("paid_reaction_purchases"),
                                    data.optLong("paid_reaction_stars"), data.optLong("paid_media_stars"),
                                    data.optLong("subscription_stars"), data.optLong("inbox_stars"), data.optLong("total_revenue_stars"));
                            if (!group && data.has("unique_post_views")) {
                                summary += "\n\n" + LocaleController.formatString(R.string.WaysGramChannelReachSummary,
                                        data.optLong("unique_post_views"), data.optLong("reached_accounts"));
                            }
                            if (!group && data.has("voluntary_leaves")) {
                                summary += "\n\n" + LocaleController.formatString(R.string.WaysGramChannelDepartures,
                                        data.optLong("voluntary_leaves"), data.optLong("moderated_removals"));
                            }
                            if (!group && data.has("sponsorship_stars")) {
                                summary += "\n\n" + LocaleController.formatString(R.string.WaysGramSponsorRevenue, data.optLong("sponsorship_stars"));
                            }
                            fragment.showDialog(analyticsDialog(fragment, title).setMessage(summary)
                                    .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
                        }, () -> fragment.showDialog(analyticsDialog(fragment, title)
                                .setMessage(LocaleController.getString(group ? R.string.WaysGramGroupAnalyticsError : R.string.WaysGramAnalyticsError))
                                .setPositiveButton(LocaleController.getString(R.string.OK), null).create()))).create());
    }

    private static AlertDialog.Builder analyticsDialog(BaseFragment fragment, int title) {
        return new AlertDialog.Builder(fragment.getParentActivity()).setTitle(LocaleController.getString(title));
    }

    static void showSetting(BaseFragment fragment, int account, long channel) {
        String token = WaysGramApi.getToken(account);
        String base = "/api/channels/" + org.telegram.tgnet.WaysGramChats.channelServer(channel);
        request(fragment, account, token, () -> WaysGramApi.request("GET", base, null, token, 10000), data -> {
            boolean enabled = data.optBoolean("paid_reactions_enabled");
            fragment.showDialog(dialog(fragment)
                    .setMessage(LocaleController.getString(enabled ? R.string.WaysGramPaidOn : R.string.WaysGramPaidOff))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(enabled ? R.string.WaysGramPaidDisable : R.string.WaysGramPaidEnable), (d, w) ->
                            request(fragment, account, token, () -> WaysGramApi.request("PUT", base + "/paid-reactions",
                                    new JSONObject().put("enabled", !enabled), token, 10000),
                                    result -> notice(fragment, result.optBoolean("enabled") ? R.string.WaysGramPaidOn : R.string.WaysGramPaidOff),
                                    () -> notice(fragment, R.string.WaysGramPaidError))).create());
        }, () -> notice(fragment, R.string.WaysGramPaidError));
    }

    static void showPost(BaseFragment fragment, int account, long channel, int message, boolean owner) {
        String token = WaysGramApi.getToken(account);
        if (!active(fragment, account, token)) return;
        String base = "/api/channels/" + org.telegram.tgnet.WaysGramChats.channelServer(channel);
        String path = base + "/messages/" + message + "/paid-reactions";
        SharedPreferences pending = fragment.getParentActivity().getSharedPreferences("waysgram_paid_reactions", Context.MODE_PRIVATE);
        String slot = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId() + ":" + channel + ":" + message;
        request(fragment, account, token, () -> {
            JSONObject data = WaysGramApi.request("GET", path, null, token, 10000);
            JSONObject channelData = WaysGramApi.request("GET", base, null, token, 10000);
            return data.put("enabled", channelData.optBoolean("paid_reactions_enabled"));
        }, data -> {
            String key = pending.getString(slot, null);
            int amount = pending.getInt(slot + ":amount", 0);
            AlertDialog.Builder builder = dialog(fragment).setMessage(LocaleController.formatString(
                    R.string.WaysGramPaidTotals, data.optLong("total_stars"), data.optLong("my_stars")));
            if (key != null && amount > 0) {
                builder.setPositiveButton(LocaleController.getString(R.string.WaysGramPaidRetry), (d, w) ->
                        confirm(fragment, account, token, path, pending, slot, amount, key));
            } else if (!owner && data.optBoolean("enabled")) {
                builder.setPositiveButton(LocaleController.getString(R.string.WaysGramPaidChoose), (d, w) -> {
                    int[] amounts = {1, 5, 10, 25, 50, 100};
                    CharSequence[] items = new CharSequence[amounts.length];
                    for (int i = 0; i < items.length; i++) items[i] = LocaleController.formatString(R.string.WaysGramPaidAmount, amounts[i]);
                    fragment.showDialog(dialog(fragment).setItems(items, (selection, index) ->
                            confirm(fragment, account, token, path, pending, slot, amounts[index], null)).create());
                });
            }
            fragment.showDialog(builder.setNegativeButton(LocaleController.getString(R.string.Close), null).create());
        }, () -> notice(fragment, R.string.WaysGramPaidError));
    }

    private static void confirm(BaseFragment fragment, int account, String token, String path, SharedPreferences pending,
                                String slot, int amount, String retryKey) {
        if (!active(fragment, account, token)) return;
        String text = LocaleController.formatString(retryKey == null ? R.string.WaysGramPaidConfirm : R.string.WaysGramPaidConfirmRetry, amount);
        fragment.showDialog(dialog(fragment).setMessage(text)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(retryKey == null ? R.string.WaysGramPaidChoose : R.string.WaysGramPaidRetry), (d, w) -> {
                    if (!active(fragment, account, token)) return;
                    if (!TextUtils.equals(retryKey, pending.getString(slot, null))) return;
                    String key = retryKey != null ? retryKey : UUID.randomUUID().toString();
                    if (!pending.edit().putString(slot, key).putInt(slot + ":amount", amount).commit()) {
                        notice(fragment, R.string.WaysGramPaidError);
                        return;
                    }
                    request(fragment, account, token, () -> {
                        try {
                            JSONObject result = WaysGramApi.request("POST", path, new JSONObject().put("stars", amount), token, 15000, key);
                            clear(pending, slot, key);
                            return result;
                        } catch (WaysGramApi.ApiException e) {
                            if (e.status == 400 || e.status == 403 || e.status == 404 || e.status == 409) {
                                clear(pending, slot, key);
                                return new JSONObject().put("rejected", true);
                            }
                            throw e;
                        }
                    }, result -> notice(fragment, result.optBoolean("rejected") ? R.string.WaysGramPaidRejected : R.string.WaysGramPaidSent),
                            () -> notice(fragment, R.string.WaysGramPaidUnknown));
                }).create());
    }

    private static void clear(SharedPreferences pending, String slot, String key) {
        if (key.equals(pending.getString(slot, null))) pending.edit().remove(slot).remove(slot + ":amount").apply();
    }
}
