package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.tgnet.WaysGramFragment;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * Экран рынка коллекционных юзернеймов — наш Fragment.
 *
 * <p>Три раздела: что продаётся по фикс-цене, что торгуется на аукционе и что уже
 * принадлежит вам. Всё, что делает деньги, живёт на сервере: клиент показывает
 * карточку и отправляет запрос, а сумму, комиссию и владельца считает сервер.</p>
 */
final class WaysGramFragmentMarket {

    private interface Work {
        JSONObject run() throws Exception;
    }

    private interface Result {
        void accept(JSONObject data);
    }

    private WaysGramFragmentMarket() {
    }

    private static String text(int resource) {
        return LocaleController.getString(resource);
    }

    private static String format(int resource, Object... args) {
        return LocaleController.formatString(resource, args);
    }

    private static AlertDialog.Builder builder(BaseFragment fragment, int title) {
        return new AlertDialog.Builder(fragment.getParentActivity()).setTitle(text(title));
    }

    private static boolean active(BaseFragment fragment, int account, String token) {
        return fragment != null && fragment.getParentActivity() != null
                && fragment.getCurrentAccount() == account
                && !TextUtils.isEmpty(token) && TextUtils.equals(token, WaysGramApi.getToken(account));
    }

    private static void request(BaseFragment fragment, int account, Work work, Result done) {
        String token = WaysGramApi.getToken(account);
        if (fragment == null || fragment.getParentActivity() == null || TextUtils.isEmpty(token)) {
            return;
        }
        WaysGramApi.NETWORK.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = work.run();
            } catch (Exception e) {
                error = e instanceof WaysGramApi.ApiException
                        ? WaysGramFragment.errorMessage((WaysGramApi.ApiException) e,
                        text(R.string.WaysGramFragmentError))
                        : text(R.string.WaysGramFragmentError);
            }
            final JSONObject payload = data;
            final String message = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (!active(fragment, account, token)) {
                    return;
                }
                if (payload != null) {
                    done.accept(payload);
                } else {
                    fragment.showDialog(builder(fragment, R.string.WaysGramFragmentTitle)
                            .setMessage(message)
                            .setPositiveButton(text(R.string.OK), null)
                            .create());
                }
            });
        });
    }

    private static void notice(BaseFragment fragment, String message) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(builder(fragment, R.string.WaysGramFragmentTitle)
                .setMessage(message)
                .setPositiveButton(text(R.string.OK), null)
                .create());
    }

    static void open(BaseFragment fragment, int account) {
        ArrayList<CharSequence> items = new ArrayList<>();
        items.add(text(R.string.WaysGramFragmentSectionMarket));
        items.add(text(R.string.WaysGramFragmentSectionAuctions));
        items.add(text(R.string.WaysGramFragmentSectionMine));
        items.add(text(R.string.WaysGramFragmentClaim));
        items.add(text(R.string.WaysGramFragmentTon));
        fragment.showDialog(builder(fragment, R.string.WaysGramFragmentTitle)
                .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
                    switch (which) {
                        case 0:
                            section(fragment, account, WaysGramFragment.SECTION_MARKET,
                                    R.string.WaysGramFragmentSectionMarket);
                            break;
                        case 1:
                            section(fragment, account, WaysGramFragment.SECTION_AUCTIONS,
                                    R.string.WaysGramFragmentSectionAuctions);
                            break;
                        case 2:
                            section(fragment, account, WaysGramFragment.SECTION_MINE,
                                    R.string.WaysGramFragmentSectionMine);
                            break;
                        case 3:
                            promptClaim(fragment, account);
                            break;
                        default:
                            ton(fragment, account);
                            break;
                    }
                })
                .setNegativeButton(text(R.string.Close), null)
                .create());
    }

    private static void section(BaseFragment fragment, int account, String section, int title) {
        request(fragment, account, () -> WaysGramFragment.market(account, section), data -> {
            JSONArray rows = data.optJSONArray("items");
            ArrayList<String> names = new ArrayList<>();
            ArrayList<CharSequence> labels = new ArrayList<>();
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                names.add(row.optString("name"));
                labels.add(label(row));
            }
            if (names.isEmpty()) {
                notice(fragment, text(R.string.WaysGramFragmentEmpty));
                return;
            }
            fragment.showDialog(builder(fragment, title)
                    .setItems(labels.toArray(new CharSequence[0]), (dialog, which) ->
                            details(fragment, account, names.get(which)))
                    .setNegativeButton(text(R.string.Close), null)
                    .create());
        });
    }

    /** Строка списка: имя и то, что с ним можно сделать прямо сейчас. */
    private static CharSequence label(JSONObject row) {
        String name = "@" + row.optString("name");
        JSONObject auction = row.optJSONObject("auction");
        if (auction != null && !auction.isNull("ends_at")) {
            return name + " · " + format(R.string.WaysGramFragmentAuctionLine,
                    auction.optInt("current_bid_stars") > 0 ? auction.optInt("current_bid_stars")
                            : auction.optInt("min_bid_stars"),
                    date(auction.optLong("ends_at")));
        }
        if (row.optInt("price_stars") > 0) {
            return name + " · " + format(R.string.WaysGramFragmentSaleLine, row.optInt("price_stars"));
        }
        return name;
    }

    private static String date(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(seconds * 1000L));
    }

    private static void details(BaseFragment fragment, int account, String name) {
        request(fragment, account, () -> WaysGramFragment.card(account, name), data -> {
            JSONObject owner = data.optJSONObject("owner");
            JSONObject auction = data.optJSONObject("auction");
            boolean mine = data.optBoolean("mine");
            boolean free = data.optBoolean("free");
            StringBuilder body = new StringBuilder();
            body.append('@').append(data.optString("name")).append('\n');
            if (owner != null && !free) {
                String ownerName = owner.optString("display_name");
                if (TextUtils.isEmpty(ownerName)) {
                    ownerName = owner.optString("username");
                }
                body.append(format(R.string.WaysGramFragmentOwnedBy,
                        TextUtils.isEmpty(ownerName) ? Integer.toString(owner.optInt("telegram_id"))
                                : ownerName)).append('\n');
            } else if (data.optBoolean("reserved")) {
                body.append(text(R.string.WaysGramFragmentUnavailable)).append('\n');
            } else {
                body.append(format(R.string.WaysGramFragmentFreeLine,
                        data.optInt("system_price_stars"))).append('\n');
            }
            if (data.optBoolean("for_sale")) {
                body.append(format(R.string.WaysGramFragmentForSale,
                        data.optInt("price_stars"))).append('\n');
            }
            if (auction != null) {
                body.append(format(R.string.WaysGramFragmentAuctionLine,
                        auction.optInt("current_bid_stars") > 0 ? auction.optInt("current_bid_stars")
                                : auction.optInt("min_bid_stars"),
                        date(auction.optLong("ends_at")))).append('\n');
            }
            if (data.optBoolean("frozen") && data.optInt("frozen_until") > 0) {
                body.append(format(R.string.WaysGramFragmentFrozen,
                        date(data.optLong("frozen_until")))).append('\n');
            }
            body.append(format(R.string.WaysGramFragmentCommission,
                    data.optInt("commission_percent", 5)));

            AlertDialog.Builder dialog = builder(fragment, R.string.WaysGramFragmentTitle)
                    .setMessage(body.toString())
                    .setNegativeButton(text(R.string.Close), null);
            if (!mine && data.optBoolean("for_sale")) {
                dialog.setPositiveButton(format(R.string.WaysGramFragmentBuy,
                        data.optInt("price_stars")), (d, w) -> buy(fragment, account, name));
            } else if (free) {
                dialog.setPositiveButton(format(R.string.WaysGramFragmentClaimPrice,
                        data.optInt("system_price_stars")), (d, w) -> claim(fragment, account, name));
            } else if (auction != null && !mine) {
                dialog.setPositiveButton(text(R.string.WaysGramFragmentBid),
                        (d, w) -> promptBid(fragment, account, name, auction.optInt("minimum_next_bid_stars")));
            } else if (mine) {
                if (data.optBoolean("for_sale")) {
                    dialog.setNeutralButton(text(R.string.WaysGramFragmentUnlist),
                            (d, w) -> unlist(fragment, account, name));
                } else if (auction != null) {
                    dialog.setNeutralButton(text(R.string.WaysGramFragmentCancelAuction),
                            (d, w) -> cancelAuction(fragment, account, name));
                } else {
                    // Своё имя можно и продать по фикс-цене, и выставить на аукцион.
                    dialog.setNeutralButton(text(R.string.WaysGramFragmentListing),
                            (d, w) -> promptPrice(fragment, account, name));
                    dialog.setPositiveButton(text(R.string.WaysGramFragmentStartAuction),
                            (d, w) -> promptAuction(fragment, account, name));
                }
            }
            fragment.showDialog(dialog.create());
        });
    }

    private static void buy(BaseFragment fragment, int account, String name) {
        request(fragment, account, () -> WaysGramFragment.buy(account, name), data -> {
            notice(fragment, format(R.string.WaysGramFragmentBought, data.optString("name")));
            WaysGramApi.NETWORK.execute(() -> WaysGramFragment.refreshSelf(account));
        });
    }

    private static void claim(BaseFragment fragment, int account, String name) {
        request(fragment, account, () -> WaysGramFragment.claim(account, name), data -> {
            notice(fragment, format(R.string.WaysGramFragmentBought, data.optString("name")));
            WaysGramApi.NETWORK.execute(() -> WaysGramFragment.refreshSelf(account));
        });
    }

    private static void unlist(BaseFragment fragment, int account, String name) {
        request(fragment, account, () -> WaysGramFragment.unlist(account, name),
                data -> notice(fragment, format(R.string.WaysGramFragmentUnlisted, data.optString("name"))));
    }

    private static void cancelAuction(BaseFragment fragment, int account, String name) {
        request(fragment, account, () -> WaysGramFragment.cancelAuction(account, name),
                data -> notice(fragment, format(R.string.WaysGramFragmentAuctionCancelled, data.optString("name"))));
    }

    private static EditText numberField(Context context, int hint, int value) {
        EditText field = new EditText(context);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setHint(text(hint));
        if (value > 0) {
            field.setText(Integer.toString(value));
        }
        return field;
    }

    private static int number(EditText field) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void promptPrice(BaseFragment fragment, int account, String name) {
        EditText price = numberField(fragment.getParentActivity(), R.string.WaysGramFragmentPriceHint, 0);
        LinearLayout fields = new LinearLayout(fragment.getParentActivity());
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(price);
        fragment.showDialog(builder(fragment, R.string.WaysGramFragmentListing)
                .setView(fields)
                .setNegativeButton(text(R.string.Cancel), null)
                .setPositiveButton(text(R.string.Save), (d, w) -> {
                    int value = number(price);
                    if (value <= 0) {
                        notice(fragment, text(R.string.WaysGramFragmentPriceHint));
                        return;
                    }
                    request(fragment, account, () -> WaysGramFragment.list(account, name, value),
                            data -> notice(fragment, format(R.string.WaysGramFragmentListed,
                                    data.optString("name"), data.optInt("price_stars"))));
                })
                .create());
    }

    private static void promptBid(BaseFragment fragment, int account, String name, int minimum) {
        EditText amount = numberField(fragment.getParentActivity(),
                R.string.WaysGramFragmentBidHint, Math.max(1, minimum));
        LinearLayout fields = new LinearLayout(fragment.getParentActivity());
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(amount);
        TextView hint = new TextView(fragment.getParentActivity());
        hint.setText(format(R.string.WaysGramFragmentMinimumBid, Math.max(1, minimum)));
        fields.addView(hint);
        fragment.showDialog(builder(fragment, R.string.WaysGramFragmentBid)
                .setView(fields)
                .setNegativeButton(text(R.string.Cancel), null)
                .setPositiveButton(text(R.string.OK), (d, w) -> {
                    int value = number(amount);
                    if (value <= 0) {
                        notice(fragment, format(R.string.WaysGramFragmentMinimumBid, Math.max(1, minimum)));
                        return;
                    }
                    request(fragment, account, () -> WaysGramFragment.bid(account, name, value),
                            data -> notice(fragment, format(R.string.WaysGramFragmentBidPlaced,
                                    data.optInt("bid_stars"))));
                })
                .create());
    }

    private static void promptClaim(BaseFragment fragment, int account) {
        EditText name = new EditText(fragment.getParentActivity());
        name.setHint(text(R.string.WaysGramFragmentNameHint));
        LinearLayout fields = new LinearLayout(fragment.getParentActivity());
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(name);
        fragment.showDialog(builder(fragment, R.string.WaysGramFragmentClaim)
                .setView(fields)
                .setMessage(text(R.string.WaysGramFragmentClaimHint))
                .setNegativeButton(text(R.string.Cancel), null)
                .setPositiveButton(text(R.string.OK), (d, w) -> {
                    String value = name.getText().toString().trim().replace("@", "");
                    if (TextUtils.isEmpty(value)) {
                        return;
                    }
                    details(fragment, account, value);
                })
                .create());
    }

    private static void promptAuction(BaseFragment fragment, int account, String name) {
        EditText minBid = numberField(fragment.getParentActivity(),
                R.string.WaysGramFragmentBidHint, 0);
        EditText hours = numberField(fragment.getParentActivity(),
                R.string.WaysGramFragmentAuctionHours, 24);
        LinearLayout fields = new LinearLayout(fragment.getParentActivity());
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(minBid);
        fields.addView(hours);
        fragment.showDialog(builder(fragment, R.string.WaysGramFragmentStartAuction)
                .setView(fields)
                .setNegativeButton(text(R.string.Cancel), null)
                .setPositiveButton(text(R.string.OK), (d, w) -> {
                    int bid = number(minBid);
                    int duration = number(hours);
                    if (bid <= 0 || duration <= 0) {
                        return;
                    }
                    request(fragment, account,
                            () -> WaysGramFragment.startAuction(account, name, bid, duration),
                            data -> notice(fragment, format(R.string.WaysGramFragmentAuctionStarted,
                                    data.optString("name"))));
                })
                .create());
    }

    /** Пополнение звёзд за TON: адрес и memo, вывод — только если сервер его разрешил. */
    private static void ton(BaseFragment fragment, int account) {
        request(fragment, account, () -> WaysGramFragment.ton(account), data -> {
            StringBuilder body = new StringBuilder();
            String address = data.optString("address");
            if (TextUtils.isEmpty(address)) {
                body.append(text(R.string.WaysGramFragmentTonDisabled));
            } else {
                body.append(text(R.string.WaysGramFragmentTonAddress)).append(": ").append(address).append('\n');
                body.append(text(R.string.WaysGramFragmentTonMemo)).append(": ")
                        .append(data.optString("memo")).append('\n');
            }
            body.append('\n').append(format(R.string.WaysGramFragmentTonRate, data.optInt("stars_per_ton")));
            body.append('\n').append(format(R.string.WaysGramFragmentTonBalance,
                    data.optInt("stars_balance")));
            AlertDialog.Builder dialog = builder(fragment, R.string.WaysGramFragmentTon)
                    .setMessage(body.toString())
                    .setNegativeButton(text(R.string.Close), null);
            if (data.optBoolean("withdraw_enabled")) {
                dialog.setPositiveButton(text(R.string.WaysGramFragmentWithdraw),
                        (d, w) -> promptWithdraw(fragment, account, data.optInt("stars_balance")));
            }
            fragment.showDialog(dialog.create());
        });
    }

    private static void promptWithdraw(BaseFragment fragment, int account, int balance) {
        EditText stars = numberField(fragment.getParentActivity(),
                R.string.WaysGramFragmentWithdrawStars, balance);
        EditText address = new EditText(fragment.getParentActivity());
        address.setHint(text(R.string.WaysGramFragmentWithdrawAddress));
        LinearLayout fields = new LinearLayout(fragment.getParentActivity());
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(stars);
        fields.addView(address);
        fragment.showDialog(builder(fragment, R.string.WaysGramFragmentWithdraw)
                .setView(fields)
                .setNegativeButton(text(R.string.Cancel), null)
                .setPositiveButton(text(R.string.OK), (d, w) -> {
                    int value = number(stars);
                    String target = address.getText().toString().trim();
                    if (value <= 0 || TextUtils.isEmpty(target)) {
                        return;
                    }
                    request(fragment, account, () -> WaysGramFragment.withdraw(account, value, target),
                            result -> notice(fragment, format(R.string.WaysGramFragmentWithdrawQueued,
                                    result.optInt("stars_spent"))));
                })
                .create());
    }
}
