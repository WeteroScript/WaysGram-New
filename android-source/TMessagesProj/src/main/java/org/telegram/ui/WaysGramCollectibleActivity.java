package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.tgnet.WaysGramFragment;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextSettingsCell;

import java.text.DateFormat;
import java.util.Date;

/**
 * Карточка коллекционного юзернейма — отдельный экран, как и список рынка.
 *
 * <p>Показывает владельца, цену, аукцион, заморозку и комиссию, а действия (купить,
 * занять, выставить на продажу, начать аукцион, ставка) — обычные строки списка с
 * полями ввода, без всплывающих диалогов. Пустое имя открывает экран «Занять
 * свободное имя», где сначала вводится имя.</p>
 */
final class WaysGramCollectibleActivity extends BaseFragment {

    private final int account;
    private final String token;

    private String name;
    private LinearLayout content;
    private JSONObject card;
    private boolean busy;
    private int generation;
    private String notice;
    private boolean noticeError;

    WaysGramCollectibleActivity(int account, String name) {
        setCurrentAccount(account);
        this.account = account;
        this.token = WaysGramApi.getToken(account);
        this.name = clean(name);
    }

    private static String text(int resource) {
        return LocaleController.getString(resource);
    }

    private static String format(int resource, Object... args) {
        return LocaleController.formatString(resource, args);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replace("@", "").toLowerCase();
    }

    private boolean active() {
        return getParentActivity() != null && content != null && !TextUtils.isEmpty(token)
                && TextUtils.equals(token, WaysGramApi.getToken(account));
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(name.isEmpty() ? text(R.string.WaysGramFragmentClaim) : "@" + name);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (fragmentView != null) {
            load();
        }
    }

    private void load() {
        if (name.isEmpty() || busy || !active()) {
            render();
            return;
        }
        busy = true;
        final int gen = ++generation;
        WaysGramApi.NETWORK.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = WaysGramFragment.card(account, name);
            } catch (Exception e) {
                error = message(e);
            }
            final JSONObject payload = data;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || !active()) {
                    return;
                }
                busy = false;
                card = payload;
                if (payload == null && notice == null) {
                    notice = failure;
                    noticeError = true;
                }
                render();
            });
        });
    }

    private String message(Exception error) {
        return error instanceof WaysGramApi.ApiException
                ? WaysGramFragment.errorMessage((WaysGramApi.ApiException) error,
                text(R.string.WaysGramFragmentError))
                : text(R.string.WaysGramFragmentError);
    }

    private void render() {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        if (name.isEmpty()) {
            claimInput();
            return;
        }
        if (notice != null) {
            LinearLayout block = block();
            TextSettingsCell cell = cell(block, notice, null, false, null);
            cell.setTextColor(Theme.getColor(noticeError
                    ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlueText));
            addBlock(block);
        }
        if (card == null) {
            if (!busy) {
                LinearLayout retry = block();
                cell(retry, text(R.string.WaysGramFragmentError), null, false, this::load);
                addBlock(retry);
            }
            return;
        }
        infoBlock(card);
        actionsBlock(card);
    }

    /** Режим «Занять свободное имя»: сначала имя, потом обычная карточка. */
    private void claimInput() {
        header(R.string.WaysGramFragmentClaim);
        LinearLayout block = block();
        final EditText input = textField(block, R.string.WaysGramFragmentNameHint, "");
        TextSettingsCell hint = cell(block, text(R.string.WaysGramFragmentClaimHint), null, true, null);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        TextSettingsCell check = cell(block, text(R.string.OK), null, false, () -> {
            String value = clean(input.getText().toString());
            if (value.isEmpty() || busy) {
                return;
            }
            name = value;
            card = null;
            notice = null;
            actionBar.setTitle("@" + name);
            load();
        });
        check.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        addBlock(block);
    }

    private void infoBlock(JSONObject data) {
        LinearLayout info = block();
        JSONObject owner = data.optJSONObject("owner");
        boolean free = data.optBoolean("free");
        if (data.optBoolean("reserved")) {
            TextSettingsCell cell = cell(info, text(R.string.WaysGramFragmentUnavailable), null, true, null);
            cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        } else if (free) {
            cell(info, format(R.string.WaysGramFragmentFreeLine, data.optInt("system_price_stars")),
                    null, true, null);
        } else if (owner != null) {
            String ownerName = owner.optString("display_name");
            if (TextUtils.isEmpty(ownerName)) {
                ownerName = owner.optString("username");
            }
            cell(info, format(R.string.WaysGramFragmentOwnedBy,
                    TextUtils.isEmpty(ownerName) ? Integer.toString(owner.optInt("telegram_id"))
                            : ownerName), null, true, null);
        }
        if (data.optBoolean("for_sale")) {
            cell(info, format(R.string.WaysGramFragmentForSale, data.optInt("price_stars")), null, true, null);
        }
        JSONObject auction = data.optJSONObject("auction");
        if (auction != null) {
            cell(info, format(R.string.WaysGramFragmentAuctionLine, bid(auction),
                    date(auction.optLong("ends_at"))), null, true, null);
        }
        if (data.optBoolean("frozen") && data.optInt("frozen_until") > 0) {
            cell(info, format(R.string.WaysGramFragmentFrozen, date(data.optLong("frozen_until"))),
                    null, true, null);
        }
        TextSettingsCell commission = cell(info,
                format(R.string.WaysGramFragmentCommission, data.optInt("commission_percent", 5)),
                null, false, null);
        commission.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        addBlock(info);
    }

    private boolean canAct(JSONObject data) {
        return !busy && !data.optBoolean("reserved");
    }

    private void actionsBlock(JSONObject data) {
        if (!canAct(data)) {
            return;
        }
        boolean mine = data.optBoolean("mine");
        boolean free = data.optBoolean("free");
        boolean forSale = data.optBoolean("for_sale");
        JSONObject auction = data.optJSONObject("auction");
        LinearLayout actions = block();
        if (mine) {
            if (forSale) {
                cell(actions, text(R.string.WaysGramFragmentUnlist), null, false,
                        () -> unlist());
            } else if (auction != null) {
                cell(actions, text(R.string.WaysGramFragmentCancelAuction), null, false,
                        () -> cancelAuction());
            } else {
                final EditText price = numberField(actions, R.string.WaysGramFragmentPriceHint, "");
                cell(actions, text(R.string.WaysGramFragmentListing), null, true,
                        () -> list(price));
                final EditText minBid = numberField(actions, R.string.WaysGramFragmentBidHint, "");
                final EditText hours = numberField(actions, R.string.WaysGramFragmentAuctionHours, "24");
                cell(actions, text(R.string.WaysGramFragmentStartAuction), null, false,
                        () -> startAuction(minBid, hours));
            }
        } else if (auction != null) {
            int minimum = Math.max(1, auction.optInt("minimum_next_bid_stars"));
            TextSettingsCell hint = cell(actions, format(R.string.WaysGramFragmentMinimumBid, minimum),
                    null, true, null);
            hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            final EditText amount = numberField(actions, R.string.WaysGramFragmentBidHint,
                    Integer.toString(minimum));
            cell(actions, text(R.string.WaysGramFragmentBid), null, false, () -> bid(amount));
        } else if (forSale) {
            TextSettingsCell buy = cell(actions,
                    format(R.string.WaysGramFragmentBuy, data.optInt("price_stars")), null, false, this::buy);
            buy.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        } else if (free) {
            TextSettingsCell claim = cell(actions,
                    format(R.string.WaysGramFragmentClaimPrice, data.optInt("system_price_stars")),
                    null, false, this::claim);
            claim.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        } else if (data.optBoolean("frozen")) {
            TextSettingsCell note = cell(actions,
                    format(R.string.WaysGramFragmentFrozen, date(data.optLong("frozen_until"))),
                    null, false, null);
            note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        }
        addBlock(actions);
    }

    private int bid(JSONObject auction) {
        return auction.optInt("current_bid_stars") > 0
                ? auction.optInt("current_bid_stars") : auction.optInt("min_bid_stars");
    }

    private static String date(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(seconds * 1000L));
    }

    private void buy() {
        run(() -> WaysGramFragment.buy(account, name), data -> {
            notice = format(R.string.WaysGramFragmentBought, data.optString("name", name));
            noticeError = false;
            WaysGramApi.NETWORK.execute(() -> WaysGramFragment.refreshSelf(account));
        });
    }

    private void claim() {
        run(() -> WaysGramFragment.claim(account, name), data -> {
            notice = format(R.string.WaysGramFragmentBought, data.optString("name", name));
            noticeError = false;
            WaysGramApi.NETWORK.execute(() -> WaysGramFragment.refreshSelf(account));
        });
    }

    private void unlist() {
        run(() -> WaysGramFragment.unlist(account, name), data -> {
            notice = format(R.string.WaysGramFragmentUnlisted, data.optString("name", name));
            noticeError = false;
        });
    }

    private void cancelAuction() {
        run(() -> WaysGramFragment.cancelAuction(account, name), data -> {
            notice = format(R.string.WaysGramFragmentAuctionCancelled, data.optString("name", name));
            noticeError = false;
        });
    }

    private void list(EditText price) {
        int value = number(price);
        if (value <= 0) {
            notice = text(R.string.WaysGramFragmentPriceHint);
            noticeError = true;
            render();
            return;
        }
        run(() -> WaysGramFragment.list(account, name, value), data -> {
            notice = format(R.string.WaysGramFragmentListed, data.optString("name", name),
                    data.optInt("price_stars", value));
            noticeError = false;
        });
    }

    private void startAuction(EditText minBid, EditText hours) {
        int value = number(minBid);
        int duration = number(hours);
        if (value <= 0 || duration <= 0) {
            notice = text(R.string.WaysGramFragmentAuctionHours);
            noticeError = true;
            render();
            return;
        }
        run(() -> WaysGramFragment.startAuction(account, name, value, duration), data -> {
            notice = format(R.string.WaysGramFragmentAuctionStarted, data.optString("name", name));
            noticeError = false;
        });
    }

    private void bid(EditText amount) {
        int value = number(amount);
        if (value <= 0) {
            notice = text(R.string.WaysGramFragmentBidHint);
            noticeError = true;
            render();
            return;
        }
        run(() -> WaysGramFragment.bid(account, name, value), data -> {
            notice = format(R.string.WaysGramFragmentBidPlaced, data.optInt("bid_stars", value));
            noticeError = false;
        });
    }

    private interface Work {
        JSONObject run() throws Exception;
    }

    private interface Done {
        void accept(JSONObject data);
    }

    /** Один запрос к рынку: блокируем строки, ошибку показываем строкой, не диалогом. */
    private void run(Work work, Done done) {
        if (busy || !active()) {
            return;
        }
        busy = true;
        final int gen = generation;
        WaysGramApi.NETWORK.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = work.run();
            } catch (Exception e) {
                error = message(e);
            }
            final JSONObject payload = data;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || !active()) {
                    return;
                }
                busy = false;
                if (payload != null) {
                    done.accept(payload);
                } else {
                    notice = failure;
                    noticeError = true;
                }
                load();
            });
        });
    }

    private static int number(EditText field) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private EditText numberField(LinearLayout parent, int hint, String value) {
        EditText field = textField(parent, hint, value);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        return field;
    }

    private EditText textField(LinearLayout parent, int hint, String value) {
        EditText field = new EditText(content.getContext());
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        field.setHint(text(hint));
        field.setTextSize(16);
        field.setSingleLine(true);
        if (!TextUtils.isEmpty(value)) {
            field.setText(value);
        }
        int inset = AndroidUtilities.dp(20);
        field.setPadding(inset, 0, inset, 0);
        parent.addView(field, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(48)));
        return field;
    }

    private void header(int title) {
        HeaderCell header = new HeaderCell(content.getContext(), getResourceProvider());
        header.setText(text(title));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout block() {
        LinearLayout block = new LinearLayout(content.getContext());
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return block;
    }

    private void addBlock(LinearLayout block) {
        if (content == null) {
            return;
        }
        content.addView(block, new LinearLayout.LayoutParams(-1, -2));
        content.addView(new ShadowSectionCell(content.getContext(), getResourceProvider()),
                new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(12)));
    }

    private TextSettingsCell cell(LinearLayout parent, CharSequence text, CharSequence value,
                                  boolean divider, Runnable click) {
        TextSettingsCell cell = new TextSettingsCell(content.getContext(), getResourceProvider());
        cell.setTextAndValue(text, value, divider);
        if (click != null) {
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
            cell.setOnClickListener(v -> click.run());
        }
        parent.addView(cell, new LinearLayout.LayoutParams(-1, -2));
        return cell;
    }
}
