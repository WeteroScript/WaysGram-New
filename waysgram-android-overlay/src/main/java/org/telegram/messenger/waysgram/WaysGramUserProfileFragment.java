package org.telegram.messenger.waysgram;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.text.DateFormat;
import java.util.Date;
import java.util.Iterator;

/** Public WaysGram profile with gifts and shared relationships. */
public final class WaysGramUserProfileFragment extends BaseFragment {
    private final long userId;
    private WaysGramApiClient apiClient;
    private ImageView avatarView;
    private TextView statusView;
    private TextView identityView;
    private TextView usernameView;
    private TextView bioView;
    private LinearLayout labelsContainer;
    private LinearLayout groupsContainer;
    private LinearLayout contactsContainer;
    private LinearLayout giftsContainer;

    public WaysGramUserProfileFragment(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Invalid WaysGram user ID");
        }
        this.userId = userId;
    }

    @Override
    public boolean onFragmentCreate() {
        return true;
    }

    @Override
    public View createView(android.content.Context context) {
        apiClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(context, getCurrentAccount()));
        actionBar.setTitle("Профиль WaysGram");

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(18),
                AndroidUtilities.dp(18), AndroidUtilities.dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        avatarView = new ImageView(context);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(88), AndroidUtilities.dp(88)
        );
        avatarParams.gravity = Gravity.CENTER_HORIZONTAL;
        avatarParams.bottomMargin = AndroidUtilities.dp(12);
        root.addView(avatarView, avatarParams);

        identityView = addLabel(context, root, "Загружаю профиль…", 18, true);
        LinearLayout identityRow = new LinearLayout(context);
        identityRow.setOrientation(LinearLayout.HORIZONTAL);
        identityRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(identityRow, matchWrap());
        usernameView = new TextView(context);
        usernameView.setTextSize(13);
        usernameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        identityRow.addView(usernameView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        labelsContainer = new LinearLayout(context);
        labelsContainer.setOrientation(LinearLayout.HORIZONTAL);
        labelsContainer.setGravity(Gravity.CENTER_VERTICAL);
        // Marks get their own row: next to the username a long mark would be squeezed out.
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        labelsParams.topMargin = AndroidUtilities.dp(8);
        root.addView(labelsContainer, labelsParams);
        bioView = addLabel(context, root, "", 13, false);
        statusView = addLabel(context, root, "", 12, false);

        Button messageButton = new Button(context);
        messageButton.setText("Написать");
        messageButton.setAllCaps(false);
        root.addView(messageButton, matchWrap());
        messageButton.setOnClickListener(view ->
                presentFragment(new WaysGramChatFragment(userId, "Диалог"), false));

        Button giftsButton = new Button(context);
        giftsButton.setText("Подарки");
        giftsButton.setAllCaps(false);
        root.addView(giftsButton, matchWrap());
        giftsButton.setOnClickListener(view -> {
            if (giftsContainer != null) {
                scroll.smoothScrollTo(0, giftsContainer.getTop());
            }
        });

        addSection(context, root, "Общие группы");
        groupsContainer = new LinearLayout(context);
        groupsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(groupsContainer, matchWrap());

        addSection(context, root, "Общие контакты");
        contactsContainer = new LinearLayout(context);
        contactsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(contactsContainer, matchWrap());

        addSection(context, root, "Подарки пользователя");
        giftsContainer = new LinearLayout(context);
        giftsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(giftsContainer, matchWrap());

        fragmentView = scroll;
        loadProfile();
        return scroll;
    }

    private void loadProfile() {
        if (apiClient == null) {
            return;
        }
        statusView.setText("Загружаю сведения…");
        apiClient.get("/api/users/" + userId, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                String name = response.optString("display_name", "").trim();
                String username = response.optString("username", "").trim();
                String bio = response.optString("bio", "").trim();
                if (name.isEmpty()) {
                    name = username.isEmpty() ? "Пользователь " + userId : username;
                }
                identityView.setText(name);
                usernameView.setText(username.isEmpty() ? "" : "@" + username);
                bioView.setText(bio);
                bioView.setVisibility(bio.isEmpty() ? View.GONE : View.VISIBLE);
                renderLabels(response.optJSONArray("labels"));

                String avatarId = response.optString("avatar_media_id", "");
                if (avatarId.matches("[a-f0-9]{32}")) {
                    apiClient.downloadImage(avatarId, new WaysGramApiClient.ImageCallback() {
                        @Override
                        public void onSuccess(Bitmap bitmap) {
                            if (avatarView != null) {
                                avatarView.setImageBitmap(bitmap);
                            }
                        }

                        @Override
                        public void onError(String message, int statusCode) {
                            if (avatarView != null) {
                                avatarView.setImageDrawable(null);
                            }
                        }
                    });
                }

                renderGroups(response.optJSONArray("common_groups"));
                renderContacts(response.optJSONArray("common_contacts"));
                renderGifts(response.optJSONArray("gifts"));
                statusView.setText("Профиль загружен");
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
                if (statusCode == 401) {
                    WaysGramSessionStore.getInstance(getParentActivity(), getCurrentAccount()).clearSession();
                }
            }
        });
    }

    private void renderLabels(JSONArray labels) {
        labelsContainer.removeAllViews();
        int count = labels == null ? 0 : labels.length();
        if (count == 0) {
            labelsContainer.setVisibility(View.GONE);
            return;
        }
        labelsContainer.setVisibility(View.VISIBLE);
        int shown = 0;
        for (int i = 0; i < count && shown < WaysGramPeerBadges.MAX_LABELS; i++) {
            JSONObject label = labels.optJSONObject(i);
            if (label == null) {
                continue;
            }
            String text = label.optString("text", "").trim();
            if (text.isEmpty()) {
                continue;
            }
            TextView chip = new TextView(labelsContainer.getContext());
            chip.setText(text);
            chip.setAllCaps(false);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            WaysGramPeerBadges.styleChip(chip, WaysGramPeerBadges.parseColor(label.optString("color", "")));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.rightMargin = AndroidUtilities.dp(6);
            labelsContainer.addView(chip, params);
            shown++;
        }
    }

    private void renderGroups(JSONArray groups) {
        groupsContainer.removeAllViews();
        int count = groups == null ? 0 : groups.length();
        if (count == 0) {
            addLabel(getParentActivity(), groupsContainer, "Общих групп нет", 13, false);
            return;
        }
        for (int i = 0; i < count; i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) {
                continue;
            }
            long id = group.optLong("id", 0);
            if (id <= 0) {
                continue;
            }
            String title = group.optString("title", "Группа " + id);
            int members = group.optInt("member_count", 0);
            Button item = new Button(getParentActivity());
            item.setAllCaps(false);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setText(title + (members > 0 ? "\n" + members + " участников" : ""));
            groupsContainer.addView(item, matchWrap());
            item.setOnClickListener(view -> presentFragment(
                    new WaysGramTimelineFragment(WaysGramTimelineFragment.TYPE_GROUP, id, title),
                    false
            ));
        }
    }

    private void renderContacts(JSONArray contacts) {
        contactsContainer.removeAllViews();
        int count = contacts == null ? 0 : contacts.length();
        if (count == 0) {
            addLabel(getParentActivity(), contactsContainer, "Общих контактов нет", 13, false);
            return;
        }
        for (int i = 0; i < count; i++) {
            JSONObject contact = contacts.optJSONObject(i);
            if (contact == null) {
                continue;
            }
            long id = contact.optLong("id", 0);
            if (id <= 0) {
                continue;
            }
            String title = contact.optString("display_name", "").trim();
            String username = contact.optString("username", "").trim();
            if (title.isEmpty()) {
                title = username.isEmpty() ? "Пользователь " + id : username;
            }
            LinearLayout contactRow = new LinearLayout(getParentActivity());
            contactRow.setOrientation(LinearLayout.HORIZONTAL);
            contactRow.setGravity(Gravity.CENTER_VERTICAL);
            Button item = new Button(getParentActivity());
            item.setAllCaps(false);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setText(username.isEmpty() ? title : title + "\n@" + username);
            contactRow.addView(item, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
            ));
            addProfileLabels(contactRow, contact.optJSONArray("labels"));
            contactsContainer.addView(contactRow, matchWrap());
            item.setOnClickListener(view ->
                    presentFragment(new WaysGramUserProfileFragment(id), false));
        }
    }

    private void addProfileLabels(LinearLayout parent, JSONArray labels) {
        if (parent == null || labels == null) {
            return;
        }
        int count = Math.min(labels.length(), 4);
        for (int i = 0; i < count; i++) {
            JSONObject label = labels.optJSONObject(i);
            if (label == null) {
                continue;
            }
            String value = label.optString("text", "").trim();
            if (value.isEmpty()) {
                continue;
            }
            int color;
            try {
                color = Color.parseColor(label.optString("color", "#6B7280"));
            } catch (IllegalArgumentException exception) {
                color = Color.GRAY;
            }
            double luminance = 0.299 * Color.red(color)
                    + 0.587 * Color.green(color)
                    + 0.114 * Color.blue(color);
            TextView chip = new TextView(getParentActivity());
            chip.setText(value);
            chip.setTextSize(10);
            chip.setSingleLine(true);
            chip.setTextColor(luminance > 150 ? Color.BLACK : Color.WHITE);
            chip.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(3),
                    AndroidUtilities.dp(5), AndroidUtilities.dp(3));
            GradientDrawable background = new GradientDrawable();
            background.setColor(color);
            background.setCornerRadius(AndroidUtilities.dp(6));
            chip.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.leftMargin = AndroidUtilities.dp(4);
            parent.addView(chip, params);
        }
    }

    private void renderGifts(JSONArray gifts) {
        giftsContainer.removeAllViews();
        int count = gifts == null ? 0 : gifts.length();
        if (count == 0) {
            addLabel(getParentActivity(), giftsContainer, "Публичных подарков не найдено", 13, false);
            return;
        }
        for (int i = 0; i < count; i++) {
            JSONObject gift = gifts.optJSONObject(i);
            if (gift == null) {
                continue;
            }
            JSONObject attributes = gift.optJSONObject("attributes");
            if (attributes == null) {
                attributes = new JSONObject();
            }
            JSONObject telegramGift = gift.optJSONObject("telegram_gift");
            String title = telegramGift == null ? "" : telegramGift.optString("title", "").trim();
            if (title.isEmpty()) {
                title = gift.optString("gift_name", "Подарок WaysGram");
            }
            String emoji = gift.optString("gift_emoji", "").trim();
            if (emoji.isEmpty() && telegramGift != null) {
                emoji = telegramGift.optString("emoji", "").trim();
            }
            String serial = gift.optString("serial_number", "");
            String telegramId = gift.optString("telegram_gift_id", "");
            int level = Math.max(
                    gift.optInt("upgrade_level", 0),
                    attributes.optInt("upgrade_level", 0)
            );

            LinearLayout card = new LinearLayout(getParentActivity());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10),
                    AndroidUtilities.dp(12), AndroidUtilities.dp(10));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            background.setCornerRadius(AndroidUtilities.dp(12));
            card.setBackground(background);

            TextView titleView = addLabel(getParentActivity(), card,
                    (emoji.isEmpty() ? "🎁" : emoji) + "  " + title, 16, true);
            titleView.setPadding(AndroidUtilities.dp(2), AndroidUtilities.dp(3),
                    AndroidUtilities.dp(2), AndroidUtilities.dp(5));
            WaysGramGiftCardRenderer.addPreview(
                    getParentActivity(), card, apiClient, telegramId, emoji
            );
            addLabel(getParentActivity(), card, "Уровень WaysGram: " + level, 13, true);
            if (telegramGift != null && gift.optBoolean("can_upgrade", false)) {
                int upgradePrice = telegramGift.optInt("upgrade_star_count", 0);
                int upgradeVariants = telegramGift.optInt("upgrade_variants", 0);
                addLabel(getParentActivity(), card,
                        "Параметры Telegram-улучшения"
                                + (upgradePrice > 0 ? " · " + upgradePrice + " Stars" : "")
                                + (upgradeVariants > 0 ? " · вариантов: " + upgradeVariants : ""),
                        12, true
                );
            }
            if (!serial.isEmpty()) {
                addLabel(getParentActivity(), card, "Серийный номер: " + serial, 12, false);
            }
            if (!telegramId.isEmpty()) {
                addLabel(getParentActivity(), card, "Источник каталога · Telegram Gift ID: " + telegramId, 12, false);
            }
            if ("waysgram_internal".equals(attributes.optString("upgrade_source", ""))) {
                addLabel(getParentActivity(), card,
                        "Улучшение выполнено внутри коллекционной системы WaysGram",
                        12, false);
            }
            long upgradedAt = attributes.optLong("last_upgraded_at", 0);
            if (upgradedAt > 0) {
                String upgradedAtText = DateFormat.getDateTimeInstance(
                        DateFormat.SHORT, DateFormat.SHORT
                ).format(new Date(upgradedAt * 1000L));
                addLabel(getParentActivity(), card, "Дата улучшения: " + upgradedAtText, 12, false);
            }
            JSONObject collectibleTraits = attributes.optJSONObject("collectible_traits");
            if (collectibleTraits != null) {
                addLabel(getParentActivity(), card,
                        "Редкость: " + WaysGramGiftTraits.rarityLabel(collectibleTraits), 13, true);
                String traitModel = WaysGramGiftTraits.name(collectibleTraits, "model");
                if (!traitModel.isEmpty()) {
                    addLabel(getParentActivity(), card, "Модель: " + traitModel, 12, false);
                }
                String traitPattern = WaysGramGiftTraits.name(collectibleTraits, "pattern");
                if (!traitPattern.isEmpty()) {
                    addLabel(getParentActivity(), card, "Узор: " + traitPattern, 12, false);
                }
                String traitBackdrop = WaysGramGiftTraits.name(collectibleTraits, "backdrop");
                if (!traitBackdrop.isEmpty()) {
                    addLabel(getParentActivity(), card, "Фон: " + traitBackdrop, 12, false);
                }
            }
            Iterator<String> attributeKeys = attributes.keys();
            while (attributeKeys.hasNext()) {
                String key = attributeKeys.next();
                if ("upgrade_level".equals(key) || "last_upgraded_at".equals(key)
                        || "upgrade_source".equals(key) || "collectible_traits".equals(key)) {
                    continue;
                }
                Object value = attributes.opt(key);
                if (value == null || value == JSONObject.NULL) {
                    continue;
                }
                String valueText = value.toString();
                if (valueText.length() > 100) {
                    valueText = valueText.substring(0, 100) + "…";
                }
                addLabel(getParentActivity(), card, "Характеристика · " + key + ": " + valueText,
                        12, false);
            }
            if (gift.optInt("is_listed", 0) == 1) {
                addLabel(getParentActivity(), card, "Выставлен на маркетплейсе", 12, false);
            }
            if (gift.optInt("is_auctioned", 0) == 1) {
                addLabel(getParentActivity(), card, "Выставлен на аукционе", 12, false);
            }
            LinearLayout.LayoutParams cardParams = matchWrap();
            cardParams.bottomMargin = AndroidUtilities.dp(8);
            giftsContainer.addView(card, cardParams);
        }
    }

    private void addSection(android.content.Context context, LinearLayout parent, String title) {
        TextView label = addLabel(context, parent, title, 15, true);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) label.getLayoutParams();
        params.topMargin = AndroidUtilities.dp(16);
        label.setLayoutParams(params);
    }

    private TextView addLabel(android.content.Context context, LinearLayout parent,
                              String text, int size, boolean bold) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (bold) {
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        label.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(5),
                AndroidUtilities.dp(4), AndroidUtilities.dp(5));
        parent.addView(label, matchWrap());
        return label;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    @Override
    public void onFragmentDestroy() {
        if (apiClient != null) {
            apiClient.close();
            apiClient = null;
        }
        super.onFragmentDestroy();
    }
}