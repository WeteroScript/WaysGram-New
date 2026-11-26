package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.json.JSONArray;
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

/**
 * Рынок коллекционных юзернеймов — полноценный экран приложения (фрагмент со своим
 * ActionBar и списком), а не диалог.
 *
 * <p>Разделы идут один за другим, как в настройках: что продаётся по фикс-цене, что
 * торгуется на аукционе, что уже принадлежит вам, пополнение за TON. Каждое имя
 * открывается следующим экраном ({@link WaysGramCollectibleActivity}). Всё, что делает
 * деньги, живёт на сервере: клиент показывает карточку и отправляет запрос, а сумму,
 * комиссию и владельца считает сервер.</p>
 */
final class WaysGramFragmentActivity extends BaseFragment {

    private final int account;
    private final String token;

    private LinearLayout content;
    private int generation;
    private boolean loading;
    private String notice;
    private boolean noticeError;

    WaysGramFragmentActivity(int account) {
        setCurrentAccount(account);
        this.account = account;
        this.token = WaysGramApi.getToken(account);
    }

    private static String text(int resource) {
        return LocaleController.getString(resource);
    }

    private static String format(int resource, Object... args) {
        return LocaleController.formatString(resource, args);
    }

    private boolean active() {
        return getParentActivity() != null && content != null && !TextUtils.isEmpty(token)
                && TextUtils.equals(token, WaysGramApi.getToken(account));
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(text(R.string.WaysGramFragmentTitle));
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
        if (loading || !active()) {
            return;
        }
        loading = true;
        final int gen = ++generation;
        WaysGramApi.NETWORK.execute(() -> {
            JSONObject market = null;
            JSONObject auctions = null;
            JSONObject mine = null;
            JSONObject ton = null;
            String error = null;
            try {
                market = WaysGramFragment.market(account, WaysGramFragment.SECTION_MARKET);
            } catch (Exception e) {
                error = message(e);
            }
            try {
                auctions = WaysGramFragment.market(account, WaysGramFragment.SECTION_AUCTIONS);
            } catch (Exception e) {
                if (error == null) {
                    error = message(e);
                }
            }
            try {
                mine = WaysGramFragment.market(account, WaysGramFragment.SECTION_MINE);
            } catch (Exception e) {
                if (error == null) {
                    error = message(e);
                }
            }
            try {
                ton = WaysGramFragment.ton(account);
            } catch (Exception ignore) {
                // Пополнение за TON может быть не настроено на сервере — экран всё равно нужен.
            }
            final JSONObject fMarket = market;
            final JSONObject fAuctions = auctions;
            final JSONObject fMine = mine;
            final JSONObject fTon = ton;
            final String fError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || !active()) {
                    return;
                }
                loading = false;
                render(fMarket, fAuctions, fMine, fTon, fError);
            });
        });
    }

    private String message(Exception error) {
        return error instanceof WaysGramApi.ApiException
                ? WaysGramFragment.errorMessage((WaysGramApi.ApiException) error,
                text(R.string.WaysGramFragmentError))
                : text(R.string.WaysGramFragmentError);
    }

    private void render(JSONObject market, JSONObject auctions, JSONObject mine, JSONObject ton, String error) {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        if (notice != null) {
            LinearLayout block = block();
            TextSettingsCell cell = cell(block, notice, null, false, null);
            cell.setTextColor(Theme.getColor(noticeError
                    ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlueText));
            addBlock(block);
        }
        if (ton != null) {
            LinearLayout balance = block();
            cell(balance, format(R.string.WaysGramFragmentTonBalance, ton.optInt("stars_balance")),
                    null, false, null);
            addBlock(balance);
            tonBlock(ton);
        }
        section(R.string.WaysGramFragmentSectionMarket, market, market == null ? error : null);
        section(R.string.WaysGramFragmentSectionAuctions, auctions, auctions == null ? error : null);
        section(R.string.WaysGramFragmentSectionMine, mine, mine == null ? error : null);
        LinearLayout claim = block();
        cell(claim, text(R.string.WaysGramFragmentClaim), null, false,
                () -> presentFragment(new WaysGramCollectibleActivity(account, "")));
        addBlock(claim);
        if (!loading && content.getChildCount() == 0) {
            LinearLayout empty = block();
            cell(empty, text(R.string.WaysGramFragmentError), null, false, this::load);
            addBlock(empty);
        }
    }

    private void section(int title, JSONObject data, String error) {
        header(title);
        LinearLayout block = block();
        JSONArray items = data == null ? null : data.optJSONArray("items");
        if (items == null || items.length() == 0) {
            Runnable retry = null;
            if (data == null) {
                retry = this::load;
            }
            TextSettingsCell row = cell(block,
                    error != null ? error : text(R.string.WaysGramFragmentEmpty), null, false, retry);
            row.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        } else {
            for (int i = 0; i < items.length(); i++) {
                JSONObject row = items.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                final String name = row.optString("name");
                if (TextUtils.isEmpty(name)) {
                    continue;
                }
                cell(block, "@" + name, value(row), i != items.length() - 1,
                        () -> presentFragment(new WaysGramCollectibleActivity(account, name)));
            }
        }
        addBlock(block);
    }

    /** Сумма строки списка: ставка аукциона или фикс-цена. */
    private CharSequence value(JSONObject row) {
        JSONObject auction = row.optJSONObject("auction");
        if (auction != null && !auction.isNull("ends_at")) {
            int bid = auction.optInt("current_bid_stars") > 0
                    ? auction.optInt("current_bid_stars") : auction.optInt("min_bid_stars");
            return format(R.string.WaysGramFragmentSaleLine, bid);
        }
        if (row.optInt("price_stars") > 0) {
            return format(R.string.WaysGramFragmentSaleLine, row.optInt("price_stars"));
        }
        return null;
    }

    /** Пополнение звёзд за TON: адрес и memo, вывод — только если сервер его разрешил. */
    private void tonBlock(JSONObject ton) {
        header(R.string.WaysGramFragmentTon);
        LinearLayout block = block();
        String address = ton.optString("address");
        if (TextUtils.isEmpty(address)) {
            TextSettingsCell cell = cell(block, text(R.string.WaysGramFragmentTonDisabled), null, false, null);
            cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            addBlock(block);
            return;
        }
        cell(block, text(R.string.WaysGramFragmentTonAddress) + ": " + address, null, true, () -> copy(address));
        cell(block, text(R.string.WaysGramFragmentTonMemo), ton.optString("memo"), true,
                () -> copy(ton.optString("memo")));
        cell(block, format(R.string.WaysGramFragmentTonRate, ton.optInt("stars_per_ton")), null, false, null);
        addBlock(block);
        if (!ton.optBoolean("withdraw_enabled")) {
            return;
        }
        header(R.string.WaysGramFragmentWithdraw);
        LinearLayout withdraw = block();
        EditText stars = numberField(withdraw, R.string.WaysGramFragmentWithdrawStars,
                Integer.toString(Math.max(500, ton.optInt("stars_balance"))));
        EditText target = textField(withdraw, R.string.WaysGramFragmentWithdrawAddress, "");
        cell(withdraw, text(R.string.WaysGramFragmentWithdraw), null, false, () -> withdraw(stars, target));
        addBlock(withdraw);
    }

    private void copy(String value) {
        if (TextUtils.isEmpty(value) || getParentActivity() == null) {
            return;
        }
        AndroidUtilities.addToClipboard(value);
        android.widget.Toast.makeText(getParentActivity(), text(R.string.TextCopied),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void withdraw(EditText stars, EditText target) {
        int amount = number(stars);
        String address = target.getText().toString().trim();
        if (amount <= 0 || TextUtils.isEmpty(address) || loading) {
            notice = text(R.string.WaysGramFragmentError);
            noticeError = true;
            load();
            return;
        }
        loading = true;
        final int gen = generation;
        WaysGramApi.NETWORK.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = WaysGramFragment.withdraw(account, amount, address);
            } catch (Exception e) {
                error = message(e);
            }
            final JSONObject payload = data;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (!active() || gen != generation) {
                    return;
                }
                loading = false;
                if (payload != null) {
                    notice = format(R.string.WaysGramFragmentWithdrawQueued,
                            payload.optInt("stars_spent", amount));
                    noticeError = false;
                    load();
                } else {
                    notice = failure;
                    noticeError = true;
                    load();
                }
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
