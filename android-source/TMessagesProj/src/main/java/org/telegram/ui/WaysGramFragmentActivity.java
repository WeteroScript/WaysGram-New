package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.waysgram.WaysGramApiClient;
import org.telegram.messenger.waysgram.WaysGramSessionStore;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.tgnet.WaysGramFragment;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Рынок Fragment внутри WaysGram — один экран с тремя вкладками, как на fragment.com:
 * «Usernames», «Gifts» и «Inventory».
 *
 * <p>Вёрстка повторяет мобильный сайт: брендовая строка с логотипом и кнопкой
 * «Подключить TON», прокручиваемые вкладки с подчёркиванием, крупный заголовок и поиск,
 * таблица аукционов с колонками «юзернейм / минимальная ставка» и сетка карточек подарков
 * с картинкой, номером, ценой и статусом. Подарок открывается отдельным экраном.</p>
 *
 * <p>Аукционов, номеров, звёзд, Premium и рекламы здесь нет — только юзернеймы, подарки и
 * инвентарь. Всё, что делает деньги, считает сервер: юзернеймы идут через
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

    /** Фоны карточек подарков: пастель, как на fragment.com. */
    private static final int[] CARD_COLORS = {
            0xFF6E7D63, 0xFF4F7F53, 0xFF7C6BB0, 0xFFB0865A, 0xFF4F7E96, 0xFFA06A88
    };

    /** Скачанные картинки подарков переживают переключение вкладок. */
    private static final HashMap<String, Bitmap> ARTWORK = new HashMap<>();

    private final int account;
    private final String token;

    private Context uiContext;
    private LinearLayout content;
    private LinearLayout tabsRow;
    private ScrollView scroll;
    private View tonAnchor;
    private LinearLayout giftGrid;
    private final ArrayList<TextView> tabLabels = new ArrayList<>();
    private final ArrayList<View> tabUnderlines = new ArrayList<>();
    private WaysGramApiClient apiClient;

    private int tab = TAB_USERNAMES;
    private boolean loading;
    private boolean busy;
    private int generation;
    private String notice;
    private boolean noticeError;
    private boolean showTon;
    private String giftQuery = "";

    private JSONArray auctions;
    private JSONArray marketNames;
    private JSONArray myNames;
    private JSONObject ton;
    private String nameFilter = "auction";
    private boolean namePriceAsc;
    private JSONObject giftMarket;
    private JSONObject inventory;
    private String giftFilter = FILTER_ALL;
    private String giftSort = SORT_PRICE_DESC;
    private String giftCollection = "";

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

    private WaysGramApiClient api() {
        if (apiClient == null) {
            apiClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(ctx(), account));
        }
        return apiClient;
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

        column.addView(brandBand(), new LinearLayout.LayoutParams(-1, -2));
        column.addView(tabStrip(), new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        column.addView(divider, new LinearLayout.LayoutParams(-1, 1));

        content = vertical();
        content.setPadding(0, dp(6), 0, dp(28));
        scroll = new ScrollView(context);
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

    // ------------------------------------------------------------ шапка и вкладки

    /** Брендовая строка как на сайте: логотип, надпись и кнопка пополнения TON. */
    private View brandBand() {
        LinearLayout band = horizontal();
        band.setPadding(dp(16), dp(8), dp(14), dp(6));

        ImageView logo = new ImageView(ctx());
        logo.setImageResource(R.drawable.fragment);
        band.addView(logo, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView wordmark = label("FRAGMENT", 19,
                Theme.key_windowBackgroundWhiteBlackText, true);
        wordmark.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(-2, -2);
        wordParams.setMargins(dp(9), 0, 0, 0);
        band.addView(wordmark, wordParams);

        View spacer = new View(ctx());
        band.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView connect = new TextView(ctx());
        connect.setText(text(R.string.WaysGramFragmentConnectTon));
        connect.setTextSize(14);
        connect.setGravity(Gravity.CENTER);
        connect.setTextColor(0xFFFFFFFF);
        connect.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        connect.setPadding(dp(16), 0, dp(16), 0);
        connect.setBackground(Theme.createRoundRectDrawable(dp(17),
                Theme.getColor(Theme.key_featuredStickers_addButton)));
        connect.setOnClickListener(v -> openTon());
        band.addView(connect, new LinearLayout.LayoutParams(-2, dp(34)));
        return band;
    }

    private View tabStrip() {
        HorizontalScrollView strip = new HorizontalScrollView(ctx());
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        tabsRow = new LinearLayout(ctx());
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setPadding(dp(12), 0, dp(12), 0);
        strip.addView(tabsRow, new FrameLayout.LayoutParams(-2, -2));
        return strip;
    }

    private void buildTabs() {
        if (tabsRow == null || !tabLabels.isEmpty()) {
            updateTabs();
            return;
        }
        for (int index = 0; index < 3; index++) {
            final int which = index;
            LinearLayout item = vertical();
            TextView label = new TextView(ctx());
            label.setTextSize(16);
            label.setText(text(tabTitle(which)));
            label.setPadding(dp(2), dp(12), dp(2), dp(6));
            label.setGravity(Gravity.CENTER);
            item.addView(label, new LinearLayout.LayoutParams(-1, -2));
            View underline = new View(ctx());
            underline.setBackground(Theme.createRoundRectDrawable(dp(2),
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)));
            item.addView(underline, new LinearLayout.LayoutParams(-1, dp(3)));
            item.setOnClickListener(v -> switchTab(which));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMargins(0, 0, dp(20), 0);
            tabsRow.addView(item, params);
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
        notice = null;
        noticeError = false;
        showTon = false;
        updateTabs();
        render();
        load();
    }

    private void openTon() {
        showTon = true;
        if (tab != TAB_USERNAMES) {
            switchTab(TAB_USERNAMES);
            return;
        }
        render();
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

    // ------------------------------------------------------------------- данные

    private void load() {
        if (loading || !active()) {
            return;
        }
        loading = true;
        final int which = tab;
        final int gen = ++generation;
        WaysGramApi.NETWORK.execute(() -> {
            JSONArray names = null;
            JSONArray mine = null;
            JSONObject market = null;
            JSONObject wallet = null;
            JSONObject gifts = null;
            String error = null;
            try {
                if (which == TAB_GIFTS) {
                    market = WaysGramApi.get(account, "/api/gift-market?limit=60");
                } else if (which == TAB_INVENTORY) {
                    gifts = WaysGramApi.get(account, "/api/me/gifts");
                } else {
                    names = WaysGramFragment.market(account,
                            WaysGramFragment.SECTION_AUCTIONS).optJSONArray("items");
                    market = WaysGramFragment.market(account,
                            WaysGramFragment.SECTION_MARKET);
                    mine = WaysGramFragment.market(account,
                            WaysGramFragment.SECTION_MINE).optJSONArray("items");
                    wallet = WaysGramFragment.ton(account);
                }
            } catch (Exception e) {
                error = message(e);
            }
            final JSONArray rows = names;
            final JSONArray mineRows = mine;
            final JSONObject giftMarketResult = market;
            final JSONObject walletResult = wallet;
            final JSONObject inventoryResult = gifts;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || !active()) {
                    return;
                }
                loading = false;
                if (failure != null) {
                    notice = failure;
                    noticeError = true;
                } else if (which == TAB_USERNAMES) {
                    auctions = rows;
                    marketNames = giftMarketResult == null ? null
                            : giftMarketResult.optJSONArray("items");
                    myNames = mineRows;
                    ton = walletResult;
                } else if (which == TAB_GIFTS) {
                    giftMarket = giftMarketResult;
                } else {
                    inventory = inventoryResult;
                }
                render();
            });
        });
    }

    private void render() {
        if (content == null || tabsRow == null) {
            return;
        }
        content.removeAllViews();
        giftGrid = null;
        tonAnchor = null;
        if (notice != null) {
            content.addView(noticeCard(), new LinearLayout.LayoutParams(-1, -2));
        }
        if (tab == TAB_GIFTS) {
            giftsTab();
        } else if (tab == TAB_INVENTORY) {
            inventoryTab();
        } else {
            usernamesTab();
        }
    }

    private View noticeCard() {
        LinearLayout card = rounded(Theme.key_windowBackgroundGray);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView message = label(notice, 13, noticeError
                ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlueText, false);
        card.addView(message, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout frame = vertical();
        frame.setPadding(dp(12), dp(6), dp(12), dp(2));
        frame.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return frame;
    }

    // ---------------------------------------------------------------- Usernames

    private void usernamesTab() {
        LinearLayout hero = vertical();
        hero.setPadding(dp(24), dp(10), dp(24), dp(2));
        TextView title = label(text(R.string.WaysGramFragmentHeroTitle), 22,
                Theme.key_windowBackgroundWhiteBlackText, true);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView info = label(text(R.string.WaysGramFragmentHeroInfo), 13,
                Theme.key_windowBackgroundWhiteGrayText2, false);
        info.setGravity(Gravity.CENTER);
        info.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.setMargins(0, dp(6), 0, 0);
        hero.addView(info, infoParams);
        content.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        content.addView(searchRow(R.string.WaysGramFragmentSearchHint, null), rowParams());

        LinearLayout head = horizontal();
        head.setPadding(dp(16), dp(14), dp(12), dp(6));
        boolean auctionList = "auction".equals(nameFilter);
        TextView heading = label(text(auctionList
                        ? R.string.WaysGramFragmentSectionAuctions
                        : R.string.WaysGramFragmentSectionMarket), 19,
                Theme.key_windowBackgroundWhiteBlackText, true);
        head.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(pill(text(R.string.WaysGramFragmentAuctionShort) + " \u25be", auctionList,
                () -> {
                    nameFilter = auctionList ? "market" : "auction";
                    render();
                }), pillParams());
        head.addView(pill(text(R.string.WaysGramFragmentColumnPrice)
                + (namePriceAsc ? " \u2191" : " \u2193"), false, () -> {
            namePriceAsc = !namePriceAsc;
            render();
        }), pillParams());
        content.addView(head, new LinearLayout.LayoutParams(-1, -2));

        usernameTable();

        sectionTitle(R.string.WaysGramFragmentSectionMine);
        if (myNames != null && myNames.length() > 0) {
            for (int i = 0; i < myNames.length(); i++) {
                JSONObject item = myNames.optJSONObject(i);
                String name = item == null ? "" : clean(item.optString("name"));
                if (name.isEmpty()) {
                    continue;
                }
                content.addView(actionRow("@" + name, summary(item),
                        () -> presentFragment(new WaysGramCollectibleActivity(account, name))),
                        rowParams());
            }
        }
        content.addView(actionRow(text(R.string.WaysGramFragmentClaim), null,
                        () -> presentFragment(new WaysGramCollectibleActivity(account, ""))),
                rowParams());
        tonSection();
    }

    /** Строка «Мои имена»: цена продажи или «в инвентаре». */
    private String summary(JSONObject item) {
        if (item == null) {
            return null;
        }
        JSONObject auction = item.optJSONObject("auction");
        int price = item.optInt("price_stars");
        if (auction != null && !auction.isNull("ends_at")) {
            return format(R.string.WaysGramFragmentAuctionLine,
                    auction.optInt("current_bid_stars") > 0
                            ? auction.optInt("current_bid_stars")
                            : auction.optInt("min_bid_stars"),
                    timeLeft(auction.optLong("ends_at")));
        }
        return price > 0 ? format(R.string.WaysGramFragmentForSale, price) : null;
    }

    private void usernameTable() {
        boolean auctionList = "auction".equals(nameFilter);
        JSONArray source = auctionList ? auctions : marketNames;
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; source != null && i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item != null) {
                items.add(item);
            }
        }
        java.util.Collections.sort(items, (a, b) -> {
            int left = namePrice(a);
            int right = namePrice(b);
            return namePriceAsc ? Integer.compare(left, right) : Integer.compare(right, left);
        });
        if (items.isEmpty()) {
            emptyRow(source == null ? text(R.string.WaysGramFragmentError)
                    : text(R.string.WaysGramFragmentEmpty));
            return;
        }
        LinearLayout card = rounded(Theme.key_windowBackgroundGray);
        card.setPadding(0, dp(4), 0, dp(4));

        LinearLayout header = horizontal();
        header.setPadding(dp(14), dp(6), dp(14), dp(6));
        header.addView(label(text(R.string.WaysGramFragmentColumnUsername), 12,
                Theme.key_windowBackgroundWhiteGrayText2, false),
                new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(label(text(R.string.WaysGramFragmentMinBid), 12,
                Theme.key_windowBackgroundWhiteGrayText2, false),
                new LinearLayout.LayoutParams(-2, -2));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        for (JSONObject item : items) {
            View row = usernameRow(item);
            if (row == null) {
                continue;
            }
            if (card.getChildCount() > 1) {
                View line = new View(ctx());
                line.setBackgroundColor(Theme.getColor(Theme.key_divider));
                LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, 1);
                lineParams.setMargins(dp(14), 0, dp(14), 0);
                card.addView(line, lineParams);
            }
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout frame = vertical();
        frame.setPadding(dp(12), dp(2), dp(12), dp(2));
        frame.addView(card, new LinearLayout.LayoutParams(-1, -2));
        content.addView(frame, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Цена лота: текущая ставка аукциона или фикс-цена. */
    private static int namePrice(JSONObject item) {
        JSONObject auction = item.optJSONObject("auction");
        if (auction != null && !auction.isNull("ends_at")) {
            return auction.optInt("current_bid_stars") > 0
                    ? auction.optInt("current_bid_stars") : auction.optInt("min_bid_stars");
        }
        return item.optInt("price_stars");
    }

    private View usernameRow(JSONObject item) {
        final String name = clean(item.optString("name"));
        if (name.isEmpty()) {
            return null;
        }
        LinearLayout row = horizontal();
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));

        LinearLayout left = vertical();
        LinearLayout titleLine = horizontal();
        titleLine.addView(label("@" + name, 15,
                Theme.key_windowBackgroundWhiteBlackText, true), new LinearLayout.LayoutParams(-2, -2));
        JSONObject auction = item.optJSONObject("auction");
        int price = item.optInt("price_stars");
        boolean onAuction = auction != null && !auction.isNull("ends_at");
        if (price > 0 && !onAuction) {
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
            badgeParams.setMargins(dp(8), 0, 0, 0);
            titleLine.addView(badge(text(R.string.WaysGramFragmentBadgeSale),
                    Theme.key_windowBackgroundWhiteGreenText), badgeParams);
        }
        left.addView(titleLine, new LinearLayout.LayoutParams(-1, -2));
        TextView link = label(name + ".t.me", 12,
                Theme.key_windowBackgroundWhiteBlueText, false);
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(-1, -2);
        linkParams.setMargins(0, dp(2), 0, 0);
        left.addView(link, linkParams);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = vertical();
        right.setGravity(Gravity.END);
        TextView amount;
        TextView state;
        if (onAuction) {
            int bid = auction.optInt("current_bid_stars") > 0
                    ? auction.optInt("current_bid_stars") : auction.optInt("min_bid_stars");
            amount = label("\u2b50 " + count(bid), 15,
                    Theme.key_windowBackgroundWhiteBlackText, true);
            state = label(timeLeft(auction.optLong("ends_at")), 12,
                    Theme.key_windowBackgroundWhiteGrayText2, false);
        } else if (price > 0) {
            amount = label("\u2b50 " + count(price), 15,
                    Theme.key_windowBackgroundWhiteBlackText, true);
            state = label(text(R.string.WaysGramFragmentForSaleShort), 12,
                    Theme.key_windowBackgroundWhiteGreenText, false);
        } else {
            amount = label(text(R.string.WaysGramFragmentInInventory), 13,
                    Theme.key_windowBackgroundWhiteGrayText2, false);
            state = null;
        }
        amount.setGravity(Gravity.END);
        right.addView(amount, new LinearLayout.LayoutParams(-1, -2));
        if (state != null) {
            state.setGravity(Gravity.END);
            right.addView(state, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(right, new LinearLayout.LayoutParams(-2, -2));

        TextView chevron = label("\u203a", 20,
                Theme.key_windowBackgroundWhiteGrayText2, false);
        chevron.setPadding(dp(10), 0, 0, 0);
        row.addView(chevron, new LinearLayout.LayoutParams(-2, -2));

        row.setOnClickListener(v ->
                presentFragment(new WaysGramCollectibleActivity(account, name)));
        return row;
    }

    /** Пополнение звёзд за TON: адрес, комментарий, курс и вывод. */
    private void tonSection() {
        sectionTitle(R.string.WaysGramFragmentTon);
        LinearLayout holder = vertical();
        if (ton == null) {
            holder.setPadding(dp(12), 0, dp(12), 0);
            tonAnchor = holder;
            content.addView(holder, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        String address = ton.optString("address");
        LinearLayout card = rounded(Theme.key_windowBackgroundGray);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (TextUtils.isEmpty(address)) {
            card.addView(label(text(R.string.WaysGramFragmentTonDisabled), 13,
                    Theme.key_windowBackgroundWhiteGrayText2, false),
                    new LinearLayout.LayoutParams(-1, -2));
            holder.setPadding(dp(12), 0, dp(12), 0);
            holder.addView(card, new LinearLayout.LayoutParams(-1, -2));
            tonAnchor = holder;
            content.addView(holder, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        card.addView(monoRow(text(R.string.WaysGramFragmentTonAddress), address,
                () -> copy(address)), new LinearLayout.LayoutParams(-1, -2));
        String memo = ton.optString("memo");
        if (!TextUtils.isEmpty(memo)) {
            card.addView(monoRow(text(R.string.WaysGramFragmentTonMemo), memo,
                    () -> copy(memo)), new LinearLayout.LayoutParams(-1, -2));
        }
        card.addView(label(format(R.string.WaysGramFragmentTonRate,
                        ton.optInt("stars_per_ton")) + "   \u00b7   "
                        + format(R.string.WaysGramFragmentTonBalance, ton.optInt("stars_balance")),
                12, Theme.key_windowBackgroundWhiteGrayText2, false),
                new LinearLayout.LayoutParams(-1, -2));

        if (ton.optBoolean("withdraw_enabled")) {
            final EditText stars = field(text(R.string.WaysGramFragmentWithdrawStars),
                    InputType.TYPE_CLASS_NUMBER);
            stars.setText(Integer.toString(Math.max(500, ton.optInt("stars_balance"))));
            final EditText target = field(text(R.string.WaysGramFragmentWithdrawAddress),
                    InputType.TYPE_CLASS_TEXT);
            LinearLayout fields = vertical();
            fields.addView(stars, new LinearLayout.LayoutParams(-1, dp(42)));
            LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(-1, dp(42));
            targetParams.setMargins(0, dp(6), 0, 0);
            fields.addView(target, targetParams);
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(-1, -2);
            fieldParams.setMargins(0, dp(10), 0, 0);
            card.addView(fields, fieldParams);
            TextView withdraw = label(text(R.string.WaysGramFragmentWithdraw), 14,
                    Theme.key_windowBackgroundWhiteBlueText, true);
            withdraw.setPadding(0, dp(10), 0, 0);
            withdraw.setOnClickListener(v -> withdraw(stars, target));
            card.addView(withdraw, new LinearLayout.LayoutParams(-1, -2));
        }

        holder.setPadding(dp(12), 0, dp(12), 0);
        holder.addView(card, new LinearLayout.LayoutParams(-1, -2));
        tonAnchor = holder;
        content.addView(holder, new LinearLayout.LayoutParams(-1, -2));
        if (showTon) {
            showTon = false;
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, holder.getTop() - dp(8))));
        }
    }

    private View monoRow(String title, String value, Runnable click) {
        LinearLayout row = vertical();
        row.setPadding(0, 0, 0, dp(8));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        row.addView(label(title, 12, Theme.key_windowBackgroundWhiteGrayText2, false),
                new LinearLayout.LayoutParams(-1, -2));
        TextView valueView = label(value, 13,
                Theme.key_windowBackgroundWhiteBlackText, true);
        valueView.setLineSpacing(0, 1.1f);
        row.addView(valueView, new LinearLayout.LayoutParams(-1, -2));
        row.setOnClickListener(v -> click.run());
        return row;
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

    // ------------------------------------------------------------------- Gifts

    private void giftsTab() {
        LinearLayout top = horizontal();
        top.setPadding(dp(12), dp(6), dp(12), dp(2));
        top.addView(searchRow(R.string.WaysGramFragmentSearchGifts, this::filterGifts),
                new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams collectionParams = new LinearLayout.LayoutParams(-2, dp(34));
        collectionParams.setMargins(dp(8), dp(6), 0, 0);
        top.addView(pill(collectionLabel() + " \u25be", !giftCollection.isEmpty(),
                this::nextCollection), collectionParams);
        content.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout head = horizontal();
        head.setPadding(dp(16), dp(12), dp(12), dp(6));
        head.addView(label(text(R.string.WaysGramFragmentTopGifts), 19,
                Theme.key_windowBackgroundWhiteBlackText, true), new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(pill(filterLabel() + " \u25be", true, () -> {
            giftFilter = FILTER_SALE.equals(giftFilter) ? FILTER_ALL : FILTER_SALE;
            render();
        }), pillParams());
        head.addView(pill(sortLabel() + " \u21c5", false, () -> {
            giftSort = SORT_PRICE_DESC.equals(giftSort) ? SORT_PRICE_ASC
                    : SORT_PRICE_ASC.equals(giftSort) ? SORT_NEW : SORT_PRICE_DESC;
            render();
        }), pillParams());
        content.addView(head, new LinearLayout.LayoutParams(-1, -2));

        giftGrid = vertical();
        content.addView(giftGrid, new LinearLayout.LayoutParams(-1, -2));
        fillGifts();
    }

    private String collectionLabel() {
        return giftCollection.isEmpty()
                ? text(R.string.WaysGramFragmentCollections)
                : giftCollection;
    }

    /** «Коллекции» — фильтр по названию подарка: подарок, все подарки, снова подарок. */
    private void nextCollection() {
        List<String> names = new ArrayList<>();
        JSONArray listings = giftMarket == null ? null : giftMarket.optJSONArray("listings");
        for (int i = 0; listings != null && i < listings.length(); i++) {
            JSONObject item = listings.optJSONObject(i);
            String name = item == null ? "" : item.optString("gift_name");
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        Collections.sort(names);
        if (names.isEmpty()) {
            return;
        }
        int index = names.indexOf(giftCollection);
        giftCollection = index < 0 || index + 1 >= names.size() ? "" : names.get(index + 1);
        render();
    }

    private String filterLabel() {
        return text(FILTER_SALE.equals(giftFilter)
                ? R.string.WaysGramFragmentSectionMarket : R.string.WaysGramFragmentAllGifts);
    }

    private String sortLabel() {
        if (SORT_PRICE_ASC.equals(giftSort)) {
            return text(R.string.WaysGramFragmentSortShort);
        }
        if (SORT_NEW.equals(giftSort)) {
            return text(R.string.WaysGramFragmentSortNewShort);
        }
        return text(R.string.WaysGramFragmentSortShort);
    }

    private void filterGifts() {
        fillGifts();
    }

    /** Перерисовывает только сетку: поле поиска не теряет курсор. */
    private void fillGifts() {
        if (giftGrid == null) {
            return;
        }
        giftGrid.removeAllViews();
        List<JSONObject> listings = sortedListings();
        if (listings.isEmpty()) {
            TextView empty = label(giftMarket == null ? text(R.string.WaysGramFragmentError)
                            : text(R.string.WaysGramFragmentEmpty),
                    13, Theme.key_windowBackgroundWhiteGrayText2, false);
            empty.setPadding(dp(16), dp(14), dp(16), dp(14));
            giftGrid.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        addGiftGrid(giftGrid, listings, false);
    }

    private void addGiftGrid(LinearLayout parent, List<JSONObject> items, boolean owned) {
        int start = 0;
        for (int rowIndex = 0; start < items.size(); rowIndex++) {
            LinearLayout line = new LinearLayout(ctx());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(dp(12), dp(4), dp(12), dp(4));
            for (int column = 0; column < 3; column++) {
                int index = start + column;
                View tile;
                if (index < items.size()) {
                    tile = giftTile(items.get(index), owned, index);
                } else {
                    tile = new View(ctx());
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
                params.setMargins(dp(3), 0, dp(3), 0);
                line.addView(tile, params);
            }
            parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
            start += 3;
        }
    }

    private View giftTile(JSONObject item, boolean owned, int index) {
        final String serial = item.optString("serial_number");
        final String giftKey = item.optString("telegram_gift_id");
        final String emoji = item.optString("gift_emoji");
        int price = owned ? 0 : item.optInt("price_grams");
        boolean listed = item.optBoolean("is_listed");

        LinearLayout tile = vertical();
        tile.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        tile.setPadding(0, 0, 0, dp(2));

        FrameLayout card = new FrameLayout(ctx());
        card.setBackground(Theme.createRoundRectDrawable(dp(10),
                CARD_COLORS[Math.abs((giftKey + serial).hashCode()) % CARD_COLORS.length]));

        TextView placeholder = new TextView(ctx());
        placeholder.setText(TextUtils.isEmpty(emoji) ? "\uD83C\uDF81" : emoji);
        placeholder.setTextSize(34);
        placeholder.setGravity(Gravity.CENTER);
        card.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));

        final ImageView art = new ImageView(ctx());
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        loadArt(giftKey, art);

        TextView serialView = new TextView(ctx());
        serialView.setText(serialLabel(item));
        serialView.setTextSize(11);
        serialView.setTextColor(0xFFFFFFFF);
        serialView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        serialView.setBackground(Theme.createRoundRectDrawable(dp(5), 0x40000000));
        serialView.setPadding(dp(5), dp(1), dp(5), dp(1));
        FrameLayout.LayoutParams serialParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.START);
        serialParams.setMargins(dp(6), 0, 0, dp(6));
        card.addView(serialView, serialParams);

        tile.addView(card, new LinearLayout.LayoutParams(-1, dp(124)));

        LinearLayout priceRow = horizontal();
        priceRow.setPadding(dp(2), dp(5), 0, 0);
        TextView amount = label("\uD83D\uDC8E " + (owned
                        ? text(listed ? R.string.WaysGramFragmentOnMarket : R.string.WaysGramFragmentInInventory)
                        : count(price)), 12,
                Theme.key_windowBackgroundWhiteBlackText, true);
        priceRow.addView(amount, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!owned) {
            priceRow.addView(badge(text(R.string.WaysGramFragmentForSaleShort),
                    Theme.key_windowBackgroundWhiteGreenText), new LinearLayout.LayoutParams(-2, -2));
        } else if (listed) {
            priceRow.addView(badge(text(R.string.WaysGramFragmentOnMarket),
                    Theme.key_windowBackgroundWhiteBlueText), new LinearLayout.LayoutParams(-2, -2));
        }
        tile.addView(priceRow, new LinearLayout.LayoutParams(-1, -2));

        tile.setOnClickListener(v -> presentFragment(new WaysGramFragmentGiftActivity(
                account, item.toString(), owned)));
        return tile;
    }

    private void loadArt(final String giftKey, final ImageView view) {
        if (TextUtils.isEmpty(giftKey)) {
            return;
        }
        Bitmap cached = ARTWORK.get(giftKey);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }
        api().downloadTelegramGiftSticker(giftKey, new WaysGramApiClient.ImageCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if (bitmap == null || bitmap.isRecycled()) {
                    return;
                }
                ARTWORK.put(giftKey, bitmap);
                if (view.getParent() != null) {
                    view.setImageBitmap(bitmap);
                }
            }

            @Override
            public void onError(String message, int statusCode) {
                // Эмодзи-заглушка остаётся, если у подарка нет картинки.
            }
        });
    }

    private String serialLabel(JSONObject item) {
        int unique = item.optInt("unique_num");
        if (unique > 0) {
            return "#" + unique;
        }
        String serial = item.optString("serial_number");
        return TextUtils.isEmpty(serial) ? "" : "#" + serial;
    }

    private List<JSONObject> sortedListings() {
        List<JSONObject> result = new ArrayList<>();
        JSONArray listings = giftMarket == null ? null : giftMarket.optJSONArray("listings");
        String query = giftQuery.trim().toLowerCase();
        for (int i = 0; listings != null && i < listings.length(); i++) {
            JSONObject item = listings.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (FILTER_SALE.equals(giftFilter) && item.optInt("price_grams") <= 0) {
                continue;
            }
            if (!query.isEmpty()
                    && !item.optString("gift_name").toLowerCase().contains(query)) {
                continue;
            }
            if (!giftCollection.isEmpty()
                    && !giftCollection.equals(item.optString("gift_name"))) {
                continue;
            }
            result.add(item);
        }
        if (SORT_PRICE_ASC.equals(giftSort)) {
            Collections.sort(result, (a, b) ->
                    Integer.compare(a.optInt("price_grams"), b.optInt("price_grams")));
        } else if (SORT_NEW.equals(giftSort)) {
            Collections.sort(result, (a, b) ->
                    Long.compare(b.optLong("created_at"), a.optLong("created_at")));
        } else {
            Collections.sort(result, (a, b) ->
                    Integer.compare(b.optInt("price_grams"), a.optInt("price_grams")));
        }
        return result;
    }

    // --------------------------------------------------------------- Inventory

    private void inventoryTab() {
        LinearLayout hint = rounded(Theme.key_windowBackgroundGray);
        hint.setPadding(dp(14), dp(10), dp(14), dp(10));
        hint.addView(label(text(R.string.WaysGramFragmentInventoryHint), 13,
                Theme.key_windowBackgroundWhiteGrayText2, false),
                new LinearLayout.LayoutParams(-1, -2));
        TextView copy = label(text(R.string.WaysGramFragmentInventoryCopy), 14,
                Theme.key_windowBackgroundWhiteBlueText, true);
        copy.setPadding(0, dp(6), 0, 0);
        copy.setOnClickListener(v -> copyFragmentUsername());
        hint.addView(copy, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout hintFrame = vertical();
        hintFrame.setPadding(dp(12), dp(6), dp(12), dp(2));
        hintFrame.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        content.addView(hintFrame, new LinearLayout.LayoutParams(-1, -2));

        List<JSONObject> gifts = new ArrayList<>();
        JSONArray rows = inventory == null ? null : inventory.optJSONArray("gifts");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item != null) {
                gifts.add(item);
            }
        }
        if (gifts.isEmpty()) {
            emptyRow(inventory == null ? text(R.string.WaysGramFragmentError)
                    : text(R.string.WaysGramFragmentEmpty));
            return;
        }
        LinearLayout grid = vertical();
        content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        addGiftGrid(grid, gifts, true);
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
                render();
                load();
            });
        });
    }

    private String message(Exception error) {
        return error instanceof WaysGramApi.ApiException
                ? WaysGramFragment.errorMessage((WaysGramApi.ApiException) error,
                text(R.string.WaysGramFragmentError))
                : text(R.string.WaysGramFragmentError);
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

    private static String count(long value) {
        return NumberFormat.getIntegerInstance().format(value);
    }

    /** «5 дн. 0 ч» — как «5 days 0 hours left» на сайте. */
    private static String timeLeft(long endsAtSeconds) {
        long left = endsAtSeconds - System.currentTimeMillis() / 1000L;
        if (left <= 0) {
            return "";
        }
        long days = left / 86400L;
        long hours = (left % 86400L) / 3600L;
        if (days > 0) {
            return LocaleController.formatString(R.string.WaysGramFragmentTimeLeftDays,
                    (int) days, (int) hours);
        }
        return LocaleController.formatString(R.string.WaysGramFragmentTimeLeftHours,
                (int) Math.max(1, hours));
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

    private LinearLayout rounded(int colorKey) {
        LinearLayout block = vertical();
        block.setBackground(Theme.createRoundRectDrawable(dp(12),
                Theme.getColor(colorKey)));
        return block;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(12), dp(4), dp(12), dp(4));
        return params;
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(32));
        params.setMargins(dp(6), 0, 0, 0);
        return params;
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

    private TextView badge(CharSequence value, int colorKey) {
        int color = Theme.getColor(colorKey);
        TextView view = new TextView(ctx());
        view.setText(value);
        view.setTextSize(11);
        view.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        view.setTextColor(color);
        view.setBackground(Theme.createRoundRectDrawable(dp(5), Theme.multAlpha(color, 0.18f)));
        view.setPadding(dp(5), dp(1), dp(5), dp(1));
        return view;
    }

    private TextView pill(CharSequence value, boolean highlighted, Runnable click) {
        TextView view = new TextView(ctx());
        view.setText(value);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        view.setTextColor(highlighted
                ? 0xFFFFFFFF
                : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setBackground(Theme.createRoundRectDrawable(dp(8), highlighted
                ? Theme.getColor(Theme.key_featuredStickers_addButton)
                : Theme.getColor(Theme.key_windowBackgroundGray)));
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(ctx());
        field.setHint(hint);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setInputType(inputType);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(Theme.createRoundRectDrawable(dp(10),
                Theme.getColor(Theme.key_windowBackgroundWhite)));
        return field;
    }

    /** Поле поиска с иконкой, как на fragment.com. */
    private View searchRow(int hintResource, Runnable onType) {
        LinearLayout box = horizontal();
        box.setBackground(Theme.createRoundRectDrawable(dp(11),
                Theme.getColor(Theme.key_windowBackgroundGray)));
        box.setPadding(dp(12), 0, dp(12), 0);

        ImageView icon = new ImageView(ctx());
        icon.setImageResource(R.drawable.msg_search);
        box.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        EditText field = new EditText(ctx());
        field.setHint(text(hintResource));
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setBackground(null);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        field.setPadding(dp(9), 0, 0, 0);
        box.addView(field, new LinearLayout.LayoutParams(0, dp(44), 1f));

        if (onType != null) {
            if (!giftQuery.isEmpty()) {
                field.setText(giftQuery);
                field.setSelection(field.getText().length());
            }
            field.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    giftQuery = s == null ? "" : s.toString();
                    onType.run();
                }
            });
        } else {
            field.setOnEditorActionListener((v, actionId, event) -> {
                String name = clean(field.getText().toString());
                if (name.isEmpty()) {
                    return false;
                }
                presentFragment(new WaysGramCollectibleActivity(account, name));
                return true;
            });
        }

        LinearLayout frame = vertical();
        frame.setPadding(dp(12), dp(6), dp(12), dp(2));
        frame.addView(box, new LinearLayout.LayoutParams(-1, dp(44)));
        return frame;
    }

    private void sectionTitle(int resource) {
        TextView title = label(text(resource), 17,
                Theme.key_windowBackgroundWhiteBlackText, true);
        title.setPadding(dp(16), dp(16), dp(16), dp(4));
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    private void emptyRow(CharSequence value) {
        TextView empty = label(value, 13, Theme.key_windowBackgroundWhiteGrayText2, false);
        empty.setPadding(dp(16), dp(14), dp(16), dp(14));
        content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }

    private View actionRow(CharSequence value, CharSequence hint, Runnable click) {
        LinearLayout card = rounded(Theme.key_windowBackgroundGray);
        card.setPadding(dp(14), dp(11), dp(14), dp(11));
        card.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_windowBackgroundGray), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        card.addView(label(value, 15, Theme.key_windowBackgroundWhiteBlackText, true),
                new LinearLayout.LayoutParams(-1, -2));
        if (hint != null) {
            card.addView(label(hint, 12, Theme.key_windowBackgroundWhiteGrayText2, false),
                    new LinearLayout.LayoutParams(-1, -2));
        }
        card.setOnClickListener(v -> click.run());
        return card;
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
