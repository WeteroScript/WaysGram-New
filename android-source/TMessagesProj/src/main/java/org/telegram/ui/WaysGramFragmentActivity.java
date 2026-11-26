package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Рынок Fragment внутри WaysGram — один экран с тремя вкладками, как на fragment.com:
 * «Usernames», «Gifts» и «Inventory».
 *
 * <p>Вкладки — это части одного фрагмента: у экрана свой {@code ActionBar}, строка
 * вкладок с подчёркиванием и прокручиваемое содержимое. Аукционов, номеров, звёзд,
 * Premium и рекламы здесь нет — только юзернеймы, подарки и инвентарь.</p>
 *
 * <p>Всё, что делает деньги, считает сервер: юзернеймы идут через
 * {@code /api/collectibles}, подарки — через {@code /api/gift-market} и
 * {@code /api/gifts/{serial}/list}; инвентарь пополняется подарком, отправленным
 * служебному аккаунту {@code @fragment}.</p>
 */
final class WaysGramFragmentActivity extends BaseFragment {

    private static final int TAB_USERNAMES = 0;
    private static final int TAB_GIFTS = 1;
    private static final int TAB_INVENTORY = 2;

    private static final String FILTER_ALL = "all";
    private static final String FILTER_SALE = "sale";
    private static final String SORT_PRICE_DESC = "price_desc";
    private static final String SORT_PRICE_ASC = "price_asc";
    private static final String SORT_NEW = "new";

    private final int account;
    private final String token;

    private Context uiContext;
    private LinearLayout content;
    private LinearLayout tabsRow;
    private final ArrayList<TextView> tabLabels = new ArrayList<>();
    private final ArrayList<View> tabUnderlines = new ArrayList<>();

    private int tab = TAB_USERNAMES;
    private boolean loading;
    private boolean busy;
    private int generation;
    private String notice;
    private boolean noticeError;

    private JSONArray usernames;
    private JSONObject ton;
    private JSONObject giftMarket;
    private JSONObject inventory;
    private String giftFilter = FILTER_ALL;
    private String giftSort = SORT_PRICE_DESC;
    private long selectedListing;
    private String selectedGift;

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

    private static int dp(int value) {
        return AndroidUtilities.dp(value);
    }

    private boolean active() {
        return getParentActivity() != null && content != null && !TextUtils.isEmpty(token)
                && TextUtils.equals(token, WaysGramApi.getToken(account));
    }

    private Context ctx() {
        if (uiContext == null) {
            uiContext = getParentActivity();
        }
        return uiContext;
    }

    @Override
    public View createView(Context context) {
        uiContext = context;
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
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout column = vertical();

        TextView wordmark = new TextView(context);
        wordmark.setText("FRAGMENT");
        wordmark.setTextSize(20);
        wordmark.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        wordmark.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        wordmark.setPadding(dp(16), dp(12), dp(16), dp(6));
        column.addView(wordmark, new LinearLayout.LayoutParams(-1, -2));

        tabsRow = new LinearLayout(context);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(tabsRow, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        column.addView(divider, new LinearLayout.LayoutParams(-1, 1));

        content = vertical();
        content.setPadding(0, dp(8), 0, dp(16));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        fragmentView = root;
        buildTabs();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (fragmentView != null) {
            load();
        }
    }

    private void buildTabs() {
        if (tabsRow == null || !tabLabels.isEmpty()) {
            updateTabs();
            return;
        }
        for (int index = 0; index < 3; index++) {
            final int which = index;
            LinearLayout wrap = vertical();
            wrap.setBackground(Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector)));
            TextView label = new TextView(ctx());
            label.setTextSize(15);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(8), dp(12), dp(8), dp(10));
            label.setText(text(tabTitle(which)));
            wrap.addView(label, new LinearLayout.LayoutParams(-1, -2));
            View underline = new View(ctx());
            underline.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            wrap.addView(underline, new LinearLayout.LayoutParams(-1, dp(2)));
            wrap.setOnClickListener(v -> switchTab(which));
            tabsRow.addView(wrap, new LinearLayout.LayoutParams(0, -2, 1f));
            tabLabels.add(label);
            tabUnderlines.add(underline);
        }
        updateTabs();
    }

    private int tabTitle(int which) {
        if (which == TAB_GIFTS) {
            return R.string.WaysGramFragmentTabGifts;
        }
        if (which == TAB_INVENTORY) {
            return R.string.WaysGramFragmentTabInventory;
        }
        return R.string.WaysGramFragmentTabUsernames;
    }

    private void switchTab(int which) {
        if (tab == which) {
            return;
        }
        tab = which;
        selectedListing = 0;
        selectedGift = null;
        notice = null;
        noticeError = false;
        updateTabs();
        render();
        load();
    }

    private void updateTabs() {
        for (int i = 0; i < tabLabels.size(); i++) {
            boolean selected = i == tab;
            tabLabels.get(i).setTextColor(Theme.getColor(selected
                    ? Theme.key_windowBackgroundWhiteBlackText
                    : Theme.key_windowBackgroundWhiteGrayText2));
            tabLabels.get(i).setTypeface(selected
                    ? AndroidUtilities.getTypeface("fonts/rmedium.ttf") : null);
            tabUnderlines.get(i).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void load() {
        if (loading || !active()) {
            return;
        }
        loading = true;
        final int which = tab;
        final int gen = ++generation;
        WaysGramApi.NETWORK.execute(() -> {
            JSONArray names = null;
            JSONObject market = null;
            JSONObject wallet = null;
            String error = null;
            try {
                if (which == TAB_USERNAMES) {
                    try {
                        ton = WaysGramFragment.ton(account);
                    } catch (Exception ignore) {
                        // Пополнение за TON может быть не настроено — экран всё равно нужен.
                    }
                    JSONArray forSale = WaysGramFragment.market(account,
                            WaysGramFragment.SECTION_MARKET).optJSONArray("items");
                    JSONArray auctions = WaysGramFragment.market(account,
                            WaysGramFragment.SECTION_AUCTIONS).optJSONArray("items");
                    JSONArray mine = WaysGramFragment.market(account,
                            WaysGramFragment.SECTION_MINE).optJSONArray("items");
                    names = new JSONArray();
                    append(names, forSale);
                    append(names, auctions);
                    append(names, mine);
                } else if (which == TAB_GIFTS) {
                    market = WaysGramApi.get(account, "/api/gift-market?limit=60");
                } else {
                    wallet = WaysGramApi.get(account, "/api/me/gifts");
                }
            } catch (Exception e) {
                error = message(e);
            }
            final JSONArray fNames = names;
            final JSONObject fTon = ton;
            final JSONObject fMarket = market;
            final JSONObject fInventory = wallet;
            final String fError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || !active()) {
                    return;
                }
                loading = false;
                if (which == TAB_USERNAMES) {
                    usernames = fNames;
                    ton = fTon;
                } else if (which == TAB_GIFTS) {
                    giftMarket = fMarket;
                } else {
                    inventory = fInventory;
                }
                if (fError != null) {
                    notice = fError;
                    noticeError = true;
                }
                render();
            });
        });
    }

    private static void append(JSONArray target, JSONArray source) {
        for (int i = 0; source != null && i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item != null) {
                target.put(item);
            }
        }
    }

    private String message(Exception error) {
        return error instanceof WaysGramApi.ApiException
                ? WaysGramFragment.errorMessage((WaysGramApi.ApiException) error,
                text(R.string.WaysGramFragmentError))
                : text(R.string.WaysGramFragmentError);
    }

    private void render() {
        if (content == null || tabsRow == null) {
            return;
        }
        content.removeAllViews();
        if (notice != null) {
            LinearLayout block = block();
            TextView message = label(notice, 14, noticeError
                    ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlueText, false);
            message.setPadding(dp(16), dp(12), dp(16), dp(12));
            block.addView(message, new LinearLayout.LayoutParams(-1, -2));
            content.addView(block, new LinearLayout.LayoutParams(-1, -2));
        }
        if (tab == TAB_GIFTS) {
            giftsTab();
        } else if (tab == TAB_INVENTORY) {
            inventoryTab();
        } else {
            usernamesTab();
        }
    }

    // ---------------------------------------------------------------- Usernames

    private void usernamesTab() {
        LinearLayout search = new LinearLayout(ctx());
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(16), dp(10), dp(16), dp(6));

        final EditText field = new EditText(ctx());
        field.setHint(text(R.string.WaysGramFragmentSearchHint));
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(Theme.createRoundRectDrawable(dp(12),
                Theme.getColor(Theme.key_windowBackgroundGray)));
        search.addView(field, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView action = label(text(R.string.Search), 15,
                Theme.key_windowBackgroundWhiteBlueText, true);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(14), 0, 0, 0);
        action.setOnClickListener(v -> {
            String name = clean(field.getText().toString());
            if (name.isEmpty()) {
                return;
            }
            presentFragment(new WaysGramCollectibleActivity(account, name));
        });
        search.addView(action, new LinearLayout.LayoutParams(-2, dp(44)));
        content.addView(search, new LinearLayout.LayoutParams(-1, -2));

        sectionTitle(R.string.WaysGramFragmentTabUsernames);
        tableHeader();
        JSONArray items = usernames;
        if (items == null || items.length() == 0) {
            emptyRow(text(R.string.WaysGramFragmentEmpty));
        } else {
            for (int i = 0; i < items.length(); i++) {
                usernameRow(items.optJSONObject(i));
            }
        }

        sectionTitle(R.string.WaysGramFragmentSectionMine);
        textRow(text(R.string.WaysGramFragmentClaim), null, null,
                () -> presentFragment(new WaysGramCollectibleActivity(account, "")));
        tonSection();
    }

    /** Пополнение звёзд за TON: адрес, memo, курс и вывод, если сервер его разрешил. */
    private void tonSection() {
        if (ton == null) {
            return;
        }
        sectionTitle(R.string.WaysGramFragmentTon);
        String address = ton.optString("address");
        if (TextUtils.isEmpty(address)) {
            emptyRow(text(R.string.WaysGramFragmentTonDisabled));
            return;
        }
        textRow(text(R.string.WaysGramFragmentTonAddress) + ": " + address,
                null, null, () -> copy(address));
        textRow(text(R.string.WaysGramFragmentTonMemo), ton.optString("memo"), null,
                () -> copy(ton.optString("memo")));
        emptyRow(format(R.string.WaysGramFragmentTonRate, ton.optInt("stars_per_ton"))
                + "  \u00b7  " + format(R.string.WaysGramFragmentTonBalance,
                ton.optInt("stars_balance")));
        if (!ton.optBoolean("withdraw_enabled")) {
            return;
        }
        final EditText stars = new EditText(ctx());
        stars.setHint(text(R.string.WaysGramFragmentWithdrawStars));
        stars.setInputType(InputType.TYPE_CLASS_NUMBER);
        stars.setSingleLine(true);
        stars.setTextSize(15);
        stars.setText(Integer.toString(Math.max(500, ton.optInt("stars_balance"))));
        stars.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        stars.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        stars.setPadding(dp(16), 0, dp(16), 0);
        stars.setBackground(Theme.createRoundRectDrawable(dp(10),
                Theme.getColor(Theme.key_windowBackgroundGray)));
        final EditText target = new EditText(ctx());
        target.setHint(text(R.string.WaysGramFragmentWithdrawAddress));
        target.setSingleLine(true);
        target.setTextSize(15);
        target.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        target.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        target.setPadding(dp(16), 0, dp(16), 0);
        target.setBackground(Theme.createRoundRectDrawable(dp(10),
                Theme.getColor(Theme.key_windowBackgroundGray)));
        LinearLayout holder = vertical();
        holder.setPadding(dp(16), dp(8), dp(16), dp(4));
        holder.addView(stars, new LinearLayout.LayoutParams(-1, dp(44)));
        holder.addView(target, new LinearLayout.LayoutParams(-1, dp(44)));
        content.addView(holder, new LinearLayout.LayoutParams(-1, -2));
        textRow(text(R.string.WaysGramFragmentWithdraw), null,
                Theme.key_windowBackgroundWhiteBlueText, () -> withdraw(stars, target));
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
        if (amount <= 0 || TextUtils.isEmpty(address)) {
            notice = text(R.string.WaysGramFragmentError);
            noticeError = true;
            render();
            return;
        }
        run(() -> WaysGramFragment.withdraw(account, amount, address),
                () -> {
                    notice = format(R.string.WaysGramFragmentWithdrawQueued, amount);
                    noticeError = false;
                });
    }

    private void tableHeader() {
        LinearLayout header = new LinearLayout(ctx());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(16), dp(6), dp(16), dp(4));
        TextView left = label(text(R.string.WaysGramFragmentColumnUsername), 12,
                Theme.key_windowBackgroundWhiteGrayText2, false);
        header.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView right = label(text(R.string.WaysGramFragmentColumnPrice), 12,
                Theme.key_windowBackgroundWhiteGrayText2, false);
        right.setGravity(Gravity.END);
        header.addView(right, new LinearLayout.LayoutParams(-2, -2));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private void usernameRow(JSONObject item) {
        if (item == null) {
            return;
        }
        final String name = clean(item.optString("name"));
        if (name.isEmpty()) {
            return;
        }
        LinearLayout row = horizontal();
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));

        LinearLayout left = vertical();
        TextView title = label("@" + name, 15, Theme.key_windowBackgroundWhiteBlackText, true);
        left.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView link = label(name + ".t.me", 12, Theme.key_windowBackgroundWhiteGrayText2, false);
        left.addView(link, new LinearLayout.LayoutParams(-1, -2));
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = vertical();
        right.setGravity(Gravity.END);
        JSONObject auction = item.optJSONObject("auction");
        if (auction != null && !auction.isNull("ends_at")) {
            int bid = auction.optInt("current_bid_stars") > 0
                    ? auction.optInt("current_bid_stars") : auction.optInt("min_bid_stars");
            TextView price = label(format(R.string.WaysGramFragmentSaleLine, bid), 14,
                    Theme.key_windowBackgroundWhiteBlackText, true);
            price.setGravity(Gravity.END);
            right.addView(price, new LinearLayout.LayoutParams(-1, -2));
            TextView state = label(format(R.string.WaysGramFragmentAuctionLine, bid,
                    date(auction.optLong("ends_at"))), 11,
                    Theme.key_windowBackgroundWhiteGrayText2, false);
            state.setGravity(Gravity.END);
            state.setMaxLines(1);
            state.setEllipsize(TextUtils.TruncateAt.END);
            right.addView(state, new LinearLayout.LayoutParams(-1, -2));
        } else if (item.optInt("price_stars") > 0) {
            TextView price = label(format(R.string.WaysGramFragmentSaleLine,
                    item.optInt("price_stars")), 14,
                    Theme.key_windowBackgroundWhiteBlackText, true);
            price.setGravity(Gravity.END);
            right.addView(price, new LinearLayout.LayoutParams(-1, -2));
            TextView state = label(text(R.string.WaysGramFragmentSectionMarket), 11,
                    Theme.key_windowBackgroundWhiteGrayText2, false);
            state.setGravity(Gravity.END);
            right.addView(state, new LinearLayout.LayoutParams(-1, -2));
        } else {
            TextView state = label(text(R.string.WaysGramFragmentInInventory), 12,
                    Theme.key_windowBackgroundWhiteGrayText2, false);
            state.setGravity(Gravity.END);
            right.addView(state, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(right, new LinearLayout.LayoutParams(-2, -2));

        TextView chevron = label("\u203a", 20, Theme.key_windowBackgroundWhiteGrayText2, false);
        chevron.setPadding(dp(10), 0, 0, 0);
        row.addView(chevron, new LinearLayout.LayoutParams(-2, -2));

        row.setOnClickListener(v ->
                presentFragment(new WaysGramCollectibleActivity(account, name)));
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        addDivider();
    }

    // ------------------------------------------------------------------- Gifts

    private void giftsTab() {
        LinearLayout chips = new LinearLayout(ctx());
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(16), dp(10), dp(16), dp(2));
        chip(chips, R.string.WaysGramFragmentAllGifts, FILTER_ALL.equals(giftFilter),
                () -> setFilter(FILTER_ALL));
        chip(chips, R.string.WaysGramFragmentSectionMarket, FILTER_SALE.equals(giftFilter),
                () -> setFilter(FILTER_SALE));
        content.addView(chips, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout sorts = new LinearLayout(ctx());
        sorts.setOrientation(LinearLayout.HORIZONTAL);
        sorts.setPadding(dp(16), dp(6), dp(16), dp(6));
        chip(sorts, R.string.WaysGramFragmentSortPriceDesc, SORT_PRICE_DESC.equals(giftSort),
                () -> setSort(SORT_PRICE_DESC));
        chip(sorts, R.string.WaysGramFragmentSortPriceAsc, SORT_PRICE_ASC.equals(giftSort),
                () -> setSort(SORT_PRICE_ASC));
        chip(sorts, R.string.WaysGramFragmentSortNew, SORT_NEW.equals(giftSort),
                () -> setSort(SORT_NEW));
        content.addView(sorts, new LinearLayout.LayoutParams(-1, -2));

        List<JSONObject> listings = sortedListings();
        if (listings.isEmpty()) {
            emptyRow(giftMarket == null ? text(R.string.WaysGramFragmentError)
                    : text(R.string.WaysGramFragmentEmpty));
            return;
        }
        for (int start = 0; start < listings.size(); start += 3) {
            LinearLayout line = new LinearLayout(ctx());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(dp(16), dp(4), dp(16), dp(4));
            for (int column = 0; column < 3; column++) {
                int index = start + column;
                View card;
                if (index < listings.size()) {
                    card = giftCard(listings.get(index));
                } else {
                    card = new View(ctx());
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
                params.setMargins(dp(4), 0, dp(4), 0);
                line.addView(card, params);
            }
            content.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }

        JSONObject selected = listing(selectedListing);
        if (selected != null) {
            textRow(format(R.string.WaysGramFragmentBuyGram, selected.optInt("price_grams")), null,
                    Theme.key_windowBackgroundWhiteBlueText,
                    () -> buy(selectedListing));
        }
    }

    private void setFilter(String value) {
        giftFilter = value;
        selectedListing = 0;
        render();
    }

    private void setSort(String value) {
        giftSort = value;
        render();
    }

    private List<JSONObject> sortedListings() {
        List<JSONObject> result = new ArrayList<>();
        JSONArray listings = giftMarket == null ? null : giftMarket.optJSONArray("listings");
        for (int i = 0; listings != null && i < listings.length(); i++) {
            JSONObject item = listings.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (FILTER_SALE.equals(giftFilter) && item.optInt("price_grams") <= 0) {
                continue;
            }
            result.add(item);
        }
        if (SORT_PRICE_ASC.equals(giftSort)) {
            Collections.sort(result, (a, b) -> Integer.compare(a.optInt("price_grams"), b.optInt("price_grams")));
        } else if (SORT_NEW.equals(giftSort)) {
            Collections.sort(result, (a, b) -> Long.compare(b.optLong("created_at"), a.optLong("created_at")));
        } else {
            Collections.sort(result, (a, b) -> Integer.compare(b.optInt("price_grams"), a.optInt("price_grams")));
        }
        return result;
    }

    private JSONObject listing(long id) {
        if (id <= 0) {
            return null;
        }
        for (JSONObject item : sortedListings()) {
            if (item.optLong("listing_id") == id) {
                return item;
            }
        }
        return null;
    }

    private View giftCard(JSONObject item) {
        boolean selected = item.optLong("listing_id") == selectedListing;
        LinearLayout card = vertical();
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(Theme.createRoundRectDrawable(dp(12),
                Theme.getColor(Theme.key_windowBackgroundGray)));

        JSONObject attributes = item.optJSONObject("attributes");
        String emoji = attributes == null ? "" : attributes.optString("emoji");
        if (TextUtils.isEmpty(emoji)) {
            emoji = "\uD83C\uDF81";
        }
        TextView art = label(emoji, 28, Theme.key_windowBackgroundWhiteBlackText, false);
        art.setGravity(Gravity.CENTER);
        card.addView(art, new LinearLayout.LayoutParams(-1, -2));

        TextView title = label(item.optString("gift_name"), 12,
                Theme.key_windowBackgroundWhiteBlackText, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView price = label(format(R.string.WaysGramFragmentGram, item.optInt("price_grams")),
                12, selected ? Theme.key_windowBackgroundWhiteBlueText
                        : Theme.key_windowBackgroundWhiteGrayText2, false);
        price.setGravity(Gravity.CENTER);
        card.addView(price, new LinearLayout.LayoutParams(-1, -2));

        card.setOnClickListener(v -> {
            selectedListing = item.optLong("listing_id");
            render();
        });
        return card;
    }

    // --------------------------------------------------------------- Inventory

    private void inventoryTab() {
        LinearLayout hint = block();
        TextView instruction = label(text(R.string.WaysGramFragmentInventoryHint), 13,
                Theme.key_windowBackgroundWhiteGrayText2, false);
        instruction.setPadding(dp(16), dp(12), dp(16), dp(6));
        hint.addView(instruction, new LinearLayout.LayoutParams(-1, -2));
        TextView copy = label(text(R.string.WaysGramFragmentInventoryCopy), 14,
                Theme.key_windowBackgroundWhiteBlueText, true);
        copy.setPadding(dp(16), dp(4), dp(16), dp(12));
        copy.setOnClickListener(v -> copyFragmentUsername());
        hint.addView(copy, new LinearLayout.LayoutParams(-1, -2));
        content.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        JSONArray gifts = inventory == null ? null : inventory.optJSONArray("gifts");
        if (gifts == null || gifts.length() == 0) {
            emptyRow(inventory == null ? text(R.string.WaysGramFragmentError)
                    : text(R.string.WaysGramFragmentEmpty));
            return;
        }
        for (int i = 0; i < gifts.length(); i++) {
            inventoryRow(gifts.optJSONObject(i));
        }

        String serial = selectedGift;
        if (serial != null) {
            JSONObject gift = inventoryGift(serial);
            boolean listed = gift != null && gift.optBoolean("is_listed");
            if (listed) {
                textRow(text(R.string.WaysGramFragmentUnlistGift), null,
                        Theme.key_windowBackgroundWhiteBlueText,
                        () -> unlist(serial));
            } else {
                final EditText price = new EditText(ctx());
                price.setHint(text(R.string.WaysGramFragmentSellPriceHint));
                price.setInputType(InputType.TYPE_CLASS_NUMBER);
                price.setSingleLine(true);
                price.setTextSize(15);
                price.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                price.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                price.setPadding(dp(16), 0, dp(16), 0);
                price.setBackground(Theme.createRoundRectDrawable(dp(10),
                        Theme.getColor(Theme.key_windowBackgroundGray)));
                LinearLayout holder = vertical();
                holder.setPadding(dp(16), dp(8), dp(16), dp(4));
                holder.addView(price, new LinearLayout.LayoutParams(-1, dp(44)));
                content.addView(holder, new LinearLayout.LayoutParams(-1, -2));
                textRow(text(R.string.WaysGramFragmentSell), null,
                        Theme.key_windowBackgroundWhiteBlueText,
                        () -> sell(serial, price));
            }
        }
    }

    private void inventoryRow(JSONObject item) {
        if (item == null) {
            return;
        }
        final String serial = item.optString("serial_number");
        if (TextUtils.isEmpty(serial)) {
            return;
        }
        boolean listed = item.optBoolean("is_listed");
        boolean selected = serial.equals(selectedGift);
        LinearLayout row = horizontal();
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));

        LinearLayout left = vertical();
        String emoji = item.optString("gift_emoji");
        String title = (TextUtils.isEmpty(emoji) ? "" : emoji + " ") + item.optString("gift_name");
        int unique = item.optInt("unique_num");
        left.addView(label(title + (unique > 0 ? " #" + unique : ""), 15,
                Theme.key_windowBackgroundWhiteBlackText, selected), new LinearLayout.LayoutParams(-1, -2));
        left.addView(label(serial, 11, Theme.key_windowBackgroundWhiteGrayText2, false),
                new LinearLayout.LayoutParams(-1, -2));
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView state = label(text(listed ? R.string.WaysGramFragmentOnMarket
                : R.string.WaysGramFragmentInInventory), 12,
                listed ? Theme.key_windowBackgroundWhiteBlueText
                        : Theme.key_windowBackgroundWhiteGrayText2, false);
        state.setGravity(Gravity.END);
        row.addView(state, new LinearLayout.LayoutParams(-2, -2));

        row.setOnClickListener(v -> {
            selectedGift = serial;
            render();
        });
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        addDivider();
    }

    private JSONObject inventoryGift(String serial) {
        JSONArray gifts = inventory == null ? null : inventory.optJSONArray("gifts");
        for (int i = 0; gifts != null && i < gifts.length(); i++) {
            JSONObject item = gifts.optJSONObject(i);
            if (item != null && serial.equals(item.optString("serial_number"))) {
                return item;
            }
        }
        return null;
    }

    private void copyFragmentUsername() {
        if (getParentActivity() == null) {
            return;
        }
        AndroidUtilities.addToClipboard("@" + "fragment");
        android.widget.Toast.makeText(getParentActivity(), text(R.string.TextCopied),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ----------------------------------------------------------------- actions

    private void sell(final String serial, EditText priceField) {
        int price = number(priceField);
        if (price <= 0) {
            notice = text(R.string.WaysGramFragmentSellPriceHint);
            noticeError = true;
            render();
            return;
        }
        run(() -> WaysGramApi.post(account, "/api/gifts/" + serial + "/list",
                        new JSONObject().put("price_grams", price)),
                () -> {
                    notice = text(R.string.WaysGramFragmentSellDone);
                    noticeError = false;
                    selectedGift = null;
                });
    }

    private void unlist(final String serial) {
        run(() -> WaysGramApi.delete(account, "/api/gifts/" + serial + "/list"),
                () -> {
                    notice = text(R.string.WaysGramFragmentUnlistDone);
                    noticeError = false;
                    selectedGift = null;
                });
    }

    private void buy(final long listingId) {
        run(() -> WaysGramApi.post(account, "/api/gift-market/" + listingId + "/purchase",
                        new JSONObject()),
                () -> {
                    notice = text(R.string.WaysGramFragmentBuyDone);
                    noticeError = false;
                    selectedListing = 0;
                });
    }

    private interface Work {
        JSONObject run() throws Exception;
    }

    private interface Done {
        void run();
    }

    /** Один запрос к серверу: ошибка показывается строкой, без диалогов. */
    private void run(Work work, Done done) {
        if (busy || !active()) {
            return;
        }
        busy = true;
        final int gen = generation;
        WaysGramApi.NETWORK.execute(() -> {
            String error = null;
            try {
                work.run();
            } catch (Exception e) {
                error = message(e);
            }
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || !active()) {
                    return;
                }
                busy = false;
                if (failure == null) {
                    done.run();
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

    private static String clean(String value) {
        return value == null ? "" : value.trim().replace("@", "").toLowerCase();
    }

    private static String date(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(seconds * 1000L));
    }

    // ------------------------------------------------------------- примитивы

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(ctx());
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(ctx());
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout block() {
        LinearLayout block = vertical();
        block.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return block;
    }

    private TextView label(CharSequence value, int size, int colorKey, boolean bold) {
        TextView view = new TextView(ctx());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(colorKey));
        if (bold) {
            view.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        }
        return view;
    }

    private void sectionTitle(int resource) {
        TextView title = label(text(resource), 15,
                Theme.key_windowBackgroundWhiteBlackText, true);
        title.setPadding(dp(16), dp(14), dp(16), dp(4));
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    private void emptyRow(CharSequence value) {
        TextView empty = label(value, 13, Theme.key_windowBackgroundWhiteGrayText2, false);
        empty.setPadding(dp(16), dp(14), dp(16), dp(14));
        content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addDivider() {
        View divider = new View(ctx());
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 1);
        params.setMargins(dp(16), 0, 0, 0);
        content.addView(divider, params);
    }

    private void textRow(CharSequence value, CharSequence hint, Integer colorKey, Runnable click) {
        LinearLayout row = vertical();
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        row.addView(label(value, 15,
                colorKey == null ? Theme.key_windowBackgroundWhiteBlackText : colorKey, true),
                new LinearLayout.LayoutParams(-1, -2));
        if (hint != null) {
            row.addView(label(hint, 12, Theme.key_windowBackgroundWhiteGrayText2, false),
                    new LinearLayout.LayoutParams(-1, -2));
        }
        row.setOnClickListener(v -> click.run());
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        addDivider();
    }

    private void chip(LinearLayout parent, int resource, boolean selected, Runnable click) {
        TextView chip = new TextView(ctx());
        chip.setText(text(resource));
        chip.setTextSize(13);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        chip.setTextColor(Theme.getColor(selected
                ? Theme.key_windowBackgroundWhiteBlueText
                : Theme.key_windowBackgroundWhiteGrayText2));
        if (selected) {
            chip.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        }
        chip.setBackground(Theme.createRoundRectDrawable(dp(14),
                Theme.getColor(Theme.key_windowBackgroundGray)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, 0, dp(8), 0);
        parent.addView(chip, params);
        chip.setOnClickListener(v -> click.run());
    }
}
