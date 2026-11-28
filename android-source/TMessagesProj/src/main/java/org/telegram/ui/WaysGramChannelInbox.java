package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.tgnet.WaysGramChats;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.UUID;

final class WaysGramChannelInbox {
    private interface Work { JSONObject run() throws Exception; }
    private interface Result { void accept(JSONObject data); }
    private interface Failure { void accept(int status); }

    private static boolean active(BaseFragment fragment, int account, String token) {
        return fragment.getParentActivity() != null && fragment.getCurrentAccount() == account
                && !TextUtils.isEmpty(token) && TextUtils.equals(token, WaysGramApi.getToken(account));
    }

    private static void request(BaseFragment fragment, int account, String token, Work work, Result done, Failure failed) {
        if (!active(fragment, account, token)) return;
        WaysGramApi.NETWORK.execute(() -> {
            try {
                JSONObject data = work.run();
                AndroidUtilities.runOnUIThread(() -> { if (active(fragment, account, token)) done.accept(data); });
            } catch (Exception e) {
                int status = e instanceof WaysGramApi.ApiException ? ((WaysGramApi.ApiException) e).status : 0;
                AndroidUtilities.runOnUIThread(() -> { if (active(fragment, account, token)) failed.accept(status); });
            }
        });
    }

    private static long dialogId(String path) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("/api/channels/(\\d+)/inbox").matcher(path);
        return matcher.find() ? -WaysGramChats.channelTg(Long.parseLong(matcher.group(1))) : 0;
    }

    private static String base(long id) { return "/api/channels/" + WaysGramChats.channelServer(id) + "/inbox"; }
    private static String text(int resource) { return LocaleController.getString(resource); }

    private static AlertDialog.Builder dialog(BaseFragment fragment, int title) {
        return new AlertDialog.Builder(fragment.getParentActivity()).setTitle(text(title));
    }

    private static void notice(BaseFragment fragment, int title, int message) {
        fragment.showDialog(dialog(fragment, title).setMessage(text(message)).setPositiveButton(text(R.string.OK), null).create());
    }

    static void settings(BaseFragment fragment, int account, long channel) {
        String token = WaysGramApi.getToken(account);
        String path = base(channel) + "/settings";
        request(fragment, account, token, () -> WaysGramApi.request("GET", path, null, token, 10000), data -> {
            LinearLayout fields = new LinearLayout(fragment.getParentActivity());
            fields.setOrientation(LinearLayout.VERTICAL);
            CheckBox enabled = new CheckBox(fragment.getParentActivity());
            enabled.setText(text(R.string.WaysGramInboxEnable));
            enabled.setChecked(data.optBoolean("enabled"));
            EditText price = new EditText(fragment.getParentActivity());
            price.setInputType(InputType.TYPE_CLASS_NUMBER);
            price.setText(Integer.toString(data.optInt("fee_stars")));
            price.setHint("0–10000 Stars");
            fields.addView(enabled);
            fields.addView(price);
            fragment.showDialog(dialog(fragment, R.string.WaysGramInboxSettings).setView(fields)
                    .setMessage(text(R.string.WaysGramInboxPriceHint))
                    .setNegativeButton(text(R.string.Cancel), null)
                    .setPositiveButton(text(R.string.Save), (d, w) -> {
                        int value;
                        try { value = Integer.parseInt(price.getText().toString()); } catch (NumberFormatException e) { value = -1; }
                        if (value < 0 || value > 10000) {
                            notice(fragment, R.string.WaysGramInboxSettings, R.string.WaysGramInboxPriceHint);
                            return;
                        }
                        final int fee = value;
                        final boolean allow = enabled.isChecked();
                        request(fragment, account, token, () -> WaysGramApi.request("PUT", path,
                                new JSONObject().put("enabled", allow).put("fee_stars", fee), token, 10000),
                                result -> notice(fragment, R.string.WaysGramInboxSettings, R.string.WaysGramInboxSaved),
                                status -> notice(fragment, R.string.WaysGramInboxSettings, R.string.WaysGramInboxError));
                    }).create());
        }, status -> notice(fragment, R.string.WaysGramInboxSettings, R.string.WaysGramInboxError));
    }

    static void open(BaseFragment fragment, int account, long channel, boolean owner) {
        String token = WaysGramApi.getToken(account);
        String root = base(channel);
        if (owner) threads(fragment, account, token, root, 0);
        else thread(fragment, account, token, root + "/me", 0, false);
    }

    private static void threads(BaseFragment fragment, int account, String token, String root, long before) {
        request(fragment, account, token, () -> WaysGramApi.request("GET", root + "?limit=25&before_id=" + before, null, token, 10000), data -> {
            ArrayList<Long> ids = new ArrayList<>();
            ArrayList<CharSequence> labels = new ArrayList<>();
            JSONArray rows = data.optJSONArray("threads");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || row.optLong("reader_id") <= 0) continue;
                long id = row.optLong("reader_id");
                String name = row.isNull("reader_name") ? "" : row.optString("reader_name");
                String preview = row.optString("body").replace('\n', ' ');
                ids.add(id);
                labels.add((name.isEmpty() ? Long.toString(id) : name) + "\n" + preview.substring(0, Math.min(100, preview.length())));
            }
            AlertDialog.Builder builder = dialog(fragment, R.string.WaysGramInboxTitle).setNegativeButton(text(R.string.Close), null);
            if (ids.isEmpty()) builder.setMessage(text(R.string.WaysGramInboxEmpty));
            else builder.setItems(labels.toArray(new CharSequence[0]), (d, which) -> thread(fragment, account, token, root + "/threads/" + ids.get(which), 0, true));
            long older = data.optLong("next_before_id");
            if (older > 0) builder.setNeutralButton(text(R.string.WaysGramCommentsOlder), (d, w) -> threads(fragment, account, token, root, older));
            else if (before > 0) builder.setNeutralButton(text(R.string.WaysGramCommentsLatest), (d, w) -> threads(fragment, account, token, root, 0));
            fragment.showDialog(builder.create());
        }, status -> notice(fragment, R.string.WaysGramInboxTitle, R.string.WaysGramInboxError));
    }

    private static void thread(BaseFragment fragment, int account, String token, String path, long before, boolean owner) {
        if (!active(fragment, account, token)) return;
        SharedPreferences pending = fragment.getParentActivity().getSharedPreferences("waysgram_channel_inbox", Context.MODE_PRIVATE);
        String slot = UserConfig.getInstance(account).getClientUserId() + ":" + path;
        request(fragment, account, token, () -> WaysGramApi.request("GET", path + "?limit=25&before_id=" + before, null, token, 10000), data -> {
            JSONArray rows = data.optJSONArray("messages");
            StringBuilder messages = new StringBuilder();
            ArrayList<JSONObject> pendingProposals = new ArrayList<>();
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                boolean incoming = "incoming".equals(row.optString("direction"));
                if (messages.length() > 0) messages.append("\n\n");
                messages.append(text(incoming ? (owner ? R.string.WaysGramInboxReader : R.string.WaysGramInboxYou) : R.string.WaysGramInboxChannel));
                int paid = row.optInt("fee_stars");
                if (paid > 0) messages.append(" · ").append(LocaleController.formatString(R.string.WaysGramPaidAmount, paid));
                String state = row.optString("proposal_state", "");
                int stateLabel = proposalStateLabel(state);
                if (stateLabel != 0) messages.append(" · ").append(text(stateLabel));
                if (row.optLong("scheduled_at") > 0 && "scheduled".equals(state)) messages.append(" · ").append(
                        java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(row.optLong("scheduled_at") * 1000L)));
                if (row.optInt("sponsor_stars") > 0) messages.append(" · ").append(LocaleController.formatString(R.string.WaysGramSponsorAmount, row.optInt("sponsor_stars")));
                if (row.optInt("reward_stars") > 0) messages.append(" · ").append(LocaleController.formatString(R.string.WaysGramProposalReward, row.optInt("reward_stars")));
                if (("pending".equals(state) || "scheduled".equals(state) || "schedule_failed".equals(state)) && row.optLong("id") > 0) pendingProposals.add(row);
                messages.append('\n').append(row.optString("body"));
            }
            boolean canSend = data.optBoolean("can_send");
            if (messages.length() == 0) messages.append(text(R.string.WaysGramInboxEmpty));
            if (!canSend) messages.append("\n\n").append(text(R.string.WaysGramInboxDisabled));
            AlertDialog.Builder builder = dialog(fragment, R.string.WaysGramInboxTitle).setMessage(messages)
                    .setNegativeButton(text(R.string.Close), null);
            String key = pending.getString(slot, null);
            if (key != null) {
                builder.setPositiveButton(text(R.string.WaysGramPaidRetry), (d, w) -> {
                    String body = pending.getString(slot + ":body", "");
                    int fee = pending.getInt(slot + ":fee", 0);
                    if (!body.isEmpty()) confirm(fragment, account, token, path, owner, pending, slot, body, fee, pending.getBoolean(slot + ":proposal", false), pending.getInt(slot + ":sponsor", 0), key);
                });
            } else if (canSend || !pendingProposals.isEmpty()) {
                int fee = data.optInt("fee_stars", -1);
                boolean allowCompose = canSend && fee >= 0 && fee <= 10000;
                if (pendingProposals.isEmpty() && owner && allowCompose) {
                    builder.setPositiveButton(text(R.string.WaysGramInboxWrite), (d, w) ->
                            compose(fragment, account, token, path, owner, pending, slot, fee, false));
                } else {
                    builder.setPositiveButton(text(R.string.WaysGramProposalActions), (d, w) ->
                            threadActions(fragment, account, token, path, owner, pending, slot, fee, allowCompose, pendingProposals, data.optBoolean("sponsored_offers_supported")));
                }
            }
            long older = data.optLong("next_before_id");
            if (older > 0) builder.setNeutralButton(text(R.string.WaysGramCommentsOlder), (d, w) -> thread(fragment, account, token, path, older, owner));
            else if (before > 0) builder.setNeutralButton(text(R.string.WaysGramCommentsLatest), (d, w) -> thread(fragment, account, token, path, 0, owner));
            fragment.showDialog(builder.create());
        }, status -> notice(fragment, R.string.WaysGramInboxTitle, R.string.WaysGramInboxError));
    }

    private static int proposalStateLabel(String state) {
        switch (state) {
            case "scheduled": return R.string.WaysGramProposalScheduled;
            case "schedule_failed": return R.string.WaysGramProposalScheduleFailed;
            case "pending": return R.string.WaysGramProposalPending;
            case "published": return R.string.WaysGramProposalPublished;
            case "declined": return R.string.WaysGramProposalDeclined;
            case "withdrawn": return R.string.WaysGramProposalWithdrawn;
            default: return 0;
        }
    }

    private static void threadActions(BaseFragment fragment, int account, String token, String path, boolean owner,
                                      SharedPreferences pending, String slot, int fee, boolean canSend, ArrayList<JSONObject> proposals, boolean sponsorSupported) {
        if (!active(fragment, account, token)) return;
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (canSend) {
            labels.add(text(R.string.WaysGramInboxWrite));
            actions.add(() -> compose(fragment, account, token, path, owner, pending, slot, fee, false));
            if (!owner) {
                labels.add(text(R.string.WaysGramProposalCompose));
                actions.add(() -> compose(fragment, account, token, path, false, pending, slot, fee, true));
                if (sponsorSupported) {
                    labels.add(text(R.string.WaysGramSponsorCompose));
                    actions.add(() -> sponsorAmount(fragment, account, token, path, pending, slot, fee));
                }
            }
        }
        for (JSONObject proposal : proposals) {
            String body = proposal.optString("body").replace('\n', ' ');
            labels.add(text(owner ? R.string.WaysGramProposalReview : R.string.WaysGramProposalWithdraw) + ": "
                    + body.substring(0, Math.min(100, body.length())));
            actions.add(() -> reviewProposal(fragment, account, token, path, owner, proposal));
        }
        if (actions.isEmpty()) return;
        fragment.showDialog(dialog(fragment, R.string.WaysGramProposalActions)
                .setItems(labels.toArray(new CharSequence[0]), (d, i) -> actions.get(i).run()).create());
    }

    private static void reviewProposal(BaseFragment fragment, int account, String token, String path, boolean owner, JSONObject proposal) {
        if (!active(fragment, account, token)) return;
        long id = proposal.optLong("id");
        int end = path.indexOf("/inbox/");
        if (id <= 0 || end < 0) return;
        String actionPath = path.substring(0, end) + "/inbox/proposals/" + id + "/";
        int sponsor = proposal.optInt("sponsor_stars");
        if (owner && sponsor > 0) {
            fragment.showDialog(dialog(fragment, R.string.WaysGramSponsorCompose)
                    .setMessage(LocaleController.formatString(R.string.WaysGramSponsorAccept, sponsor) + "\n\n" + proposal.optString("body"))
                    .setNegativeButton(text(R.string.Cancel), null)
                    .setNeutralButton(text(R.string.WaysGramProposalDecline), (d, w) ->
                            resolveProposal(fragment, account, token, path, true, actionPath + "decline", new JSONObject()))
                    .setPositiveButton(text(R.string.WaysGramProposalPublish), (d, w) -> {
                        try { resolveProposal(fragment, account, token, path, true, actionPath + "publish-sponsored", new JSONObject().put("approved_sponsor_stars", sponsor)); }
                        catch (org.json.JSONException e) { notice(fragment, R.string.WaysGramProposalReview, R.string.WaysGramProposalError); }
                    }).create());
        } else if (owner) {
            CharSequence[] options = {text(R.string.WaysGramProposalPublish), text(R.string.WaysGramProposalSchedule), text(R.string.WaysGramProposalDecline)};
            fragment.showDialog(dialog(fragment, R.string.WaysGramProposalReview).setMessage(proposal.optString("body"))
                    .setItems(options, (d, which) -> {
                        if (which == 2) resolveProposal(fragment, account, token, path, true, actionPath + "decline", new JSONObject());
                        else if (which == 0) proposalReward(fragment, account, token, path, actionPath, 0);
                        else {
                            java.util.Calendar when = java.util.Calendar.getInstance();
                            when.add(java.util.Calendar.MINUTE, 15);
                            new android.app.DatePickerDialog(fragment.getParentActivity(), (picker, year, month, day) -> {
                                when.set(year, month, day);
                                new android.app.TimePickerDialog(fragment.getParentActivity(), (clock, hour, minute) -> {
                                    when.set(java.util.Calendar.HOUR_OF_DAY, hour);
                                    when.set(java.util.Calendar.MINUTE, minute);
                                    when.set(java.util.Calendar.SECOND, 0);
                                    proposalReward(fragment, account, token, path, actionPath, when.getTimeInMillis() / 1000L);
                                }, when.get(java.util.Calendar.HOUR_OF_DAY), when.get(java.util.Calendar.MINUTE), true).show();
                            }, when.get(java.util.Calendar.YEAR), when.get(java.util.Calendar.MONTH), when.get(java.util.Calendar.DAY_OF_MONTH)).show();
                        }
                    }).setNegativeButton(text(R.string.Cancel), null).create());
        } else {
            fragment.showDialog(dialog(fragment, R.string.WaysGramProposalReview).setMessage(text(R.string.WaysGramProposalWithdrawHint)
                    + "\n\n" + proposal.optString("body")).setNegativeButton(text(R.string.Cancel), null)
                    .setPositiveButton(text(R.string.WaysGramProposalWithdraw), (d, w) ->
                            resolveProposal(fragment, account, token, path, false, actionPath + "withdraw", new JSONObject())).create());
        }
    }

    private static void proposalReward(BaseFragment fragment, int account, String token, String path, String actionPath, long when) {
        if (!active(fragment, account, token)) return;
        android.widget.EditText reward = new android.widget.EditText(fragment.getParentActivity());
        reward.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        reward.setText("0");
        int title = when > 0 ? R.string.WaysGramProposalSchedule : R.string.WaysGramProposalPublish;
        String message = text(R.string.WaysGramProposalRewardHint);
        if (when > 0) message += "\n\n" + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(when * 1000L));
        fragment.showDialog(dialog(fragment, title).setMessage(message).setView(reward)
                .setNegativeButton(text(R.string.Cancel), null).setPositiveButton(text(title), (d, w) -> {
                    try {
                        int stars = Integer.parseInt(reward.getText().toString().trim());
                        if (stars < 0 || stars > 10000) throw new IllegalArgumentException();
                        JSONObject payload = new JSONObject().put("reward_stars", stars);
                        if (when > 0) payload.put("scheduled_at", when);
                        resolveProposal(fragment, account, token, path, true, actionPath + (when > 0 ? "schedule" : "publish"), payload);
                    } catch (Exception e) { notice(fragment, R.string.WaysGramProposalReview, R.string.WaysGramProposalError); }
                }).create());
    }

    private static void resolveProposal(BaseFragment fragment, int account, String token, String path, boolean owner, String action, JSONObject payload) {
        request(fragment, account, token, () -> WaysGramApi.request("POST", action, payload, token, 15000),
                data -> thread(fragment, account, token, path, 0, owner),
                status -> notice(fragment, R.string.WaysGramProposalReview, R.string.WaysGramProposalError));
    }

    private static void sponsorAmount(BaseFragment fragment, int account, String token, String path, SharedPreferences pending, String slot, int fee) {
        if (!active(fragment, account, token)) return;
        EditText value = new EditText(fragment.getParentActivity());
        value.setInputType(InputType.TYPE_CLASS_NUMBER);
        value.setHint("1–10000 Stars");
        fragment.showDialog(dialog(fragment, R.string.WaysGramSponsorCompose).setView(value)
                .setMessage(text(R.string.WaysGramSponsorDetails)).setNegativeButton(text(R.string.Cancel), null)
                .setPositiveButton(text(R.string.OK), (d, w) -> {
                    try {
                        int amount = Integer.parseInt(value.getText().toString().trim());
                        if (amount < 1 || amount > 10000) throw new IllegalArgumentException();
                        compose(fragment, account, token, path, false, pending, slot, fee, true, amount);
                    } catch (Exception e) { notice(fragment, R.string.WaysGramSponsorCompose, R.string.WaysGramSponsorDetails); }
                }).create());
    }

    private static void compose(BaseFragment fragment, int account, String token, String path, boolean owner, SharedPreferences pending, String slot, int fee, boolean proposal) {
        compose(fragment, account, token, path, owner, pending, slot, fee, proposal, 0);
    }

    private static void compose(BaseFragment fragment, int account, String token, String path, boolean owner, SharedPreferences pending, String slot, int fee, boolean proposal, int sponsor) {
        if (!active(fragment, account, token)) return;
        EditText field = new EditText(fragment.getParentActivity());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(sponsor > 0 ? 3950 : 4000)});
        fragment.showDialog(dialog(fragment, sponsor > 0 ? R.string.WaysGramSponsorCompose : proposal ? R.string.WaysGramProposalCompose : R.string.WaysGramInboxWrite).setView(field)
                .setNegativeButton(text(R.string.Cancel), null).setPositiveButton(text(R.string.Send), (d, w) -> {
                    String body = field.getText().toString().trim();
                    if (body.isEmpty()) return;
                    if (fee > 0 || proposal) confirm(fragment, account, token, path, owner, pending, slot, body, fee, proposal, sponsor, null);
                    else send(fragment, account, token, path, owner, pending, slot, body, fee, false, 0, null);
                }).create());
    }

    private static void confirm(BaseFragment fragment, int account, String token, String path, boolean owner,
                                SharedPreferences pending, String slot, String body, int fee, boolean proposal, int sponsor, String key) {
        if (!active(fragment, account, token)) return;
        // Stock Telegram confirmation; WaysGram still attaches the internal debit
        // in send() while keeping its own idempotency key and payment history.
        org.telegram.ui.Components.AlertsCreator.showPayForMessageAlert(account, dialogId(path), Math.max(0, fee), 1,
                () -> send(fragment, account, token, path, owner, pending, slot, body, fee, proposal, sponsor, key));
    }

    private static void clear(SharedPreferences pending, String slot, String key) {
        if (key.equals(pending.getString(slot, null))) pending.edit().remove(slot).remove(slot + ":body").remove(slot + ":fee").remove(slot + ":proposal").remove(slot + ":sponsor").apply();
    }

    private static void send(BaseFragment fragment, int account, String token, String path, boolean owner,
                             SharedPreferences pending, String slot, String body, int fee, boolean proposal, int sponsor, String previous) {
        if (!active(fragment, account, token) || !TextUtils.equals(previous, pending.getString(slot, null))) return;
        String key = previous == null ? UUID.randomUUID().toString() : previous;
        if (!pending.edit().putString(slot, key).putString(slot + ":body", body).putInt(slot + ":fee", fee).putBoolean(slot + ":proposal", proposal).putInt(slot + ":sponsor", sponsor).commit()) {
            notice(fragment, R.string.WaysGramInboxTitle, R.string.WaysGramInboxError);
            return;
        }
        request(fragment, account, token, () -> {
            try {
                JSONObject result = WaysGramApi.request("POST", sponsor > 0 ? path + "/sponsored-offers" : path,
                        new JSONObject().put("body", body).put("propose_post", proposal).put("approved_fee_stars", fee).put("sponsor_stars", sponsor), token, 15000, key);
                clear(pending, slot, key);
                return result;
            } catch (WaysGramApi.ApiException e) {
                if (e.status == 400 || e.status == 402 || e.status == 403 || e.status == 404 || e.status == 409 || e.status == 429) clear(pending, slot, key);
                throw e;
            }
        }, data -> thread(fragment, account, token, path, 0, owner), status -> {
            int message = status == 402 ? R.string.WaysGramInboxFeeChanged
                    : pending.contains(slot) ? R.string.WaysGramInboxUnknown : R.string.WaysGramInboxRejected;
            notice(fragment, R.string.WaysGramInboxTitle, message);
        });
    }
}
