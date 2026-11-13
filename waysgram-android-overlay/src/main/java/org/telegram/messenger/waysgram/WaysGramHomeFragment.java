package org.telegram.messenger.waysgram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.text.DateFormat;
import java.util.Date;
import java.util.Iterator;

/** WaysGram-only home screen backed by AuthBot's REST API. */
public final class WaysGramHomeFragment extends BaseFragment {
    private static final int REQUEST_AVATAR = 7106;
    private WaysGramSessionStore sessionStore;
    private WaysGramApiClient apiClient;
    private TextView profileView;
    private TextView walletView;
    private TextView statusView;
    private TextView inboxSummaryView;
    private LinearLayout giftsContainer;
    private ImageView avatarView;
    private final Handler eventHandler = new Handler(Looper.getMainLooper());
    private final Runnable eventRetryRunnable = new Runnable() {
        @Override
        public void run() {
            pollUnreadEvents();
        }
    };
    private boolean isScreenResumed;
    private boolean eventsReady;
    private boolean eventsInitializing;
    private boolean eventPollInFlight;
    private long lastEventId;
    private int unreadDmCount;
    private int unreadGroupCount;
    private int unreadChannelCount;
    private String currentDisplayName = "";
    private String currentUsername = "";
    private String currentBio = "";
    private String currentAvatarMediaId = "";

    @Override
    public boolean onFragmentCreate() {
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("WaysGram");
        sessionStore = WaysGramSessionStore.getInstance(context);
        apiClient = new WaysGramApiClient(sessionStore);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                AndroidUtilities.dp(18),
                AndroidUtilities.dp(18),
                AndroidUtilities.dp(18),
                AndroidUtilities.dp(28)
        );
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        avatarView = new ImageView(context);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avatarPlaceholder = new GradientDrawable();
        avatarPlaceholder.setShape(GradientDrawable.OVAL);
        avatarPlaceholder.setColor(Color.LTGRAY);
        avatarView.setBackground(avatarPlaceholder);
        avatarView.setClipToOutline(true);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(88), AndroidUtilities.dp(88)
        );
        avatarParams.bottomMargin = AndroidUtilities.dp(12);
        content.addView(avatarView, avatarParams);

        profileView = addLabel(context, content, "Загрузка профиля…", 19, true);
        walletView = addLabel(context, content, "Баланс загружается…", 14, false);
        statusView = addLabel(context, content, "", 13, false);
        inboxSummaryView = addLabel(context, content, "Непрочитанные сообщения загружаются…", 13, true);
        addLabel(context, content, "Мои подарки WaysGram", 15, true);
        giftsContainer = new LinearLayout(context);
        giftsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(giftsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        addButton(context, content, "Открыть инвентарь подарков", () ->
                new WaysGramGiftCatalogSheet(context, sessionStore, resourceProvider, this::loadProfile).show()
        );

        addButton(context, content, "Редактировать профиль", this::showProfileEditor);
        addButton(context, content, "Фото профиля", this::showAvatarActions);
        addButton(context, content, "Активные сеансы", this::showSessions);
        addButton(context, content, "Контакты", () ->
                presentFragment(new WaysGramDirectoryFragment(WaysGramDirectoryFragment.TYPE_CONTACTS), false)
        );
        addButton(context, content, "Чёрный список", () ->
                presentFragment(new WaysGramDirectoryFragment(WaysGramDirectoryFragment.TYPE_BLOCKS), false)
        );
        addButton(context, content, "Сообщения", () ->
                presentFragment(new WaysGramDirectoryFragment(WaysGramDirectoryFragment.TYPE_CONVERSATIONS), false)
        );
        addButton(context, content, "Группы", () ->
                presentFragment(new WaysGramDirectoryFragment(WaysGramDirectoryFragment.TYPE_GROUPS), false)
        );
        addButton(context, content, "Каналы", () ->
                presentFragment(new WaysGramDirectoryFragment(WaysGramDirectoryFragment.TYPE_CHANNELS), false)
        );
        addButton(context, content, "BotFather · Мои боты", () ->
                presentFragment(new WaysGramBotsFragment(), false)
        );
        addButton(context, content, "Подарки WaysGram", () ->
                new WaysGramGiftCatalogSheet(context, sessionStore, resourceProvider, this::loadProfile).show()
        );
        addButton(context, content, "Обновить профиль и баланс", this::loadProfile);
        addButton(context, content, "Выйти из WaysGram", this::logout);

        fragmentView = scroll;
        loadProfile();
        return scroll;
    }

    private TextView addLabel(Context context, LinearLayout parent, String text, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = AndroidUtilities.dp(12);
        parent.addView(view, params);
        return view;
    }

    private void addButton(Context context, LinearLayout parent, String title, Runnable callback) {
        Button button = new Button(context);
        button.setText(title);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = AndroidUtilities.dp(7);
        parent.addView(button, params);
        button.setOnClickListener(view -> callback.run());
    }

    private void loadProfile() {
        if (apiClient == null) {
            return;
        }
        statusView.setText("Обновляю профиль…");
        apiClient.get("/api/me", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                currentDisplayName = response.optString("display_name", "");
                currentUsername = response.optString("username", "");
                currentBio = response.optString("bio", "");
                String avatarId = response.optString("avatar_media_id", "");
                String name = currentDisplayName;
                if (name.length() == 0) {
                    name = currentUsername;
                }
                if (name.length() == 0) {
                    name = "Пользователь WaysGram";
                }
                String profileText = name;
                if (!currentUsername.isEmpty()) {
                    profileText += "\n@" + currentUsername;
                }
                String phone = response.optString("phone", "");
                if (!phone.isEmpty()) {
                    profileText += "\n" + phone;
                }
                if (!currentBio.isEmpty()) {
                    profileText += "\n" + currentBio;
                }
                profileView.setText(profileText);
                if (!avatarId.equals(currentAvatarMediaId)) {
                    currentAvatarMediaId = avatarId;
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
                    } else {
                        avatarView.setImageDrawable(null);
                    }
                }
                statusView.setText("Профиль загружен");
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
                if (statusCode == 401) {
                    sessionStore.clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
        apiClient.get("/api/me/gifts", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                renderMyGifts(response.optJSONArray("gifts"));
            }

            @Override
            public void onError(String message, int statusCode) {
                if (giftsContainer != null) {
                    giftsContainer.removeAllViews();
                    addLabel(getParentActivity(), giftsContainer, "Не удалось загрузить подарки: " + message, 12, false);
                }
                if (statusCode == 401 && getParentActivity() != null) {
                    sessionStore.clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
        apiClient.get("/api/me/wallet", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                walletView.setText(
                        "Stars: " + response.optLong("stars", 0)
                                + "    Gram: " + response.optLong("grams", 0)
                );
            }

            @Override
            public void onError(String message, int statusCode) {
                walletView.setText(message);
            }
        });
    }

    private void renderMyGifts(JSONArray gifts) {
        if (giftsContainer == null || getParentActivity() == null) {
            return;
        }
        giftsContainer.removeAllViews();
        int count = gifts == null ? 0 : gifts.length();
        if (count == 0) {
            addLabel(getParentActivity(), giftsContainer,
                    "У вас пока нет подарков. Откройте каталог, чтобы посмотреть доступные варианты.",
                    12, false);
            return;
        }

        addLabel(getParentActivity(), giftsContainer,
                "В инвентаре: " + count + " подарков", 12, false);
        int visible = Math.min(count, 50);
        for (int i = 0; i < visible; i++) {
            JSONObject gift = gifts.optJSONObject(i);
            if (gift == null) {
                continue;
            }
            JSONObject attributes = gift.optJSONObject("attributes");
            if (attributes == null) {
                attributes = new JSONObject();
            }
            String title = gift.optString("gift_name", "Подарок WaysGram");
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

            addLabel(getParentActivity(), card, "🎁  " + title, 16, true);
            addLabel(getParentActivity(), card, "Уровень WaysGram: " + level, 13, true);
            if (!serial.isEmpty()) {
                addLabel(getParentActivity(), card, "Серийный номер: " + serial, 12, false);
            }
            if (!telegramId.isEmpty()) {
                addLabel(getParentActivity(), card, "Telegram Gift ID: " + telegramId, 12, false);
            }
            String upgradeSource = attributes.optString("upgrade_source", "");
            if ("waysgram_internal".equals(upgradeSource)) {
                addLabel(getParentActivity(), card,
                        "Улучшение выполнено внутри WaysGram", 12, false);
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
                        "Редкость: " + collectibleTraits.optString("rarity", "—"), 13, true);
                addLabel(getParentActivity(), card,
                        "Модель: " + collectibleTraits.optString("model", "—"), 12, false);
                addLabel(getParentActivity(), card,
                        "Символ: " + collectibleTraits.optString("symbol", "—"), 12, false);
                addLabel(getParentActivity(), card,
                        "Фон: " + collectibleTraits.optString("backdrop", "—"), 12, false);
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
                addLabel(getParentActivity(), card,
                        "Характеристика · " + key + ": " + valueText, 12, false);
            }
            if (gift.optInt("is_listed", 0) == 1) {
                addLabel(getParentActivity(), card, "Выставлен на маркетплейсе", 12, false);
            }

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardParams.bottomMargin = AndroidUtilities.dp(8);
            giftsContainer.addView(card, cardParams);
        }
        if (count > visible) {
            addLabel(getParentActivity(), giftsContainer,
                    "Показаны последние " + visible + " из " + count
                            + ". Полный список доступен в инвентаре.",
                    12, false);
        }
    }

    private void updateUnreadSummary() {
        if (inboxSummaryView == null) {
            return;
        }
        int total = unreadDmCount + unreadGroupCount + unreadChannelCount;
        if (total == 0) {
            inboxSummaryView.setText("Новых сообщений нет");
            return;
        }
        inboxSummaryView.setText(
                "Непрочитано: " + total
                        + " · личные: " + unreadDmCount
                        + " · группы: " + unreadGroupCount
                        + " · каналы: " + unreadChannelCount
        );
    }

    private void loadUnreadSummary() {
        if (apiClient == null) {
            return;
        }
        apiClient.get("/api/conversations", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONArray rows = response.optJSONArray("conversations");
                int count = 0;
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row != null) {
                            count += Math.max(0, row.optInt("unread_count", 0));
                        }
                    }
                }
                unreadDmCount = count;
                updateUnreadSummary();
            }

            @Override
            public void onError(String message, int statusCode) {
                if (statusCode == 401 && getParentActivity() != null) {
                    sessionStore.clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
        apiClient.get("/api/groups", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONArray rows = response.optJSONArray("groups");
                int count = 0;
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row != null) {
                            count += Math.max(0, row.optInt("unread_count", 0));
                        }
                    }
                }
                unreadGroupCount = count;
                updateUnreadSummary();
            }

            @Override
            public void onError(String message, int statusCode) {
                if (statusCode == 401 && getParentActivity() != null) {
                    sessionStore.clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
        apiClient.get("/api/channels", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONArray rows = response.optJSONArray("channels");
                int count = 0;
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row != null) {
                            count += Math.max(0, row.optInt("unread_count", 0));
                        }
                    }
                }
                unreadChannelCount = count;
                updateUnreadSummary();
            }

            @Override
            public void onError(String message, int statusCode) {
                if (statusCode == 401 && getParentActivity() != null) {
                    sessionStore.clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
    }

    private void initializeUnreadEventCursor() {
        if (apiClient == null || !isScreenResumed || eventsReady || eventsInitializing) {
            return;
        }
        eventsInitializing = true;
        apiClient.get("/api/events", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                eventsInitializing = false;
                if (!isScreenResumed) {
                    return;
                }
                lastEventId = response.optLong("latest_id", 0);
                eventsReady = true;
                pollUnreadEvents();
            }

            @Override
            public void onError(String message, int statusCode) {
                eventsInitializing = false;
                if (statusCode == 401 && getParentActivity() != null) {
                    sessionStore.clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                } else {
                    scheduleEventRetry(4000);
                }
            }
        });
    }

    private void scheduleEventRetry(int delayMs) {
        if (apiClient != null && isScreenResumed) {
            eventHandler.removeCallbacks(eventRetryRunnable);
            eventHandler.postDelayed(eventRetryRunnable, delayMs);
        }
    }

    private void pollUnreadEvents() {
        if (apiClient == null || !isScreenResumed || eventPollInFlight) {
            return;
        }
        if (!eventsReady) {
            initializeUnreadEventCursor();
            return;
        }
        eventPollInFlight = true;
        apiClient.getEventLongPoll(
                "/api/events?after_id=" + lastEventId + "&wait_seconds=20",
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        eventPollInFlight = false;
                        if (!isScreenResumed) {
                            return;
                        }
                        JSONArray events = response.optJSONArray("events");
                        boolean refreshUnread = false;
                        int count = events == null ? 0 : events.length();
                        for (int i = 0; i < count; i++) {
                            JSONObject event = events.optJSONObject(i);
                            if (event != null && "new_message".equals(event.optString("kind"))) {
                                refreshUnread = true;
                                break;
                            }
                        }
                        lastEventId = Math.max(lastEventId, response.optLong("latest_id", lastEventId));
                        if (refreshUnread) {
                            loadUnreadSummary();
                        }
                        pollUnreadEvents();
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        eventPollInFlight = false;
                        if (statusCode == 401 && getParentActivity() != null) {
                            sessionStore.clearSession();
                            presentFragment(new WaysGramLoginFragment(), true);
                        } else {
                            scheduleEventRetry(4000);
                        }
                    }
                }
        );
    }

    @Override
    public void onResume() {
        super.onResume();
        WaysGramPushManager.sync(getParentActivity());
        WaysGramPushManager.requestNotificationPermission(getParentActivity());
        isScreenResumed = true;
        loadUnreadSummary();
        eventHandler.removeCallbacks(eventRetryRunnable);
        if (eventsReady) {
            pollUnreadEvents();
        } else {
            initializeUnreadEventCursor();
        }
    }

    @Override
    public void onPause() {
        isScreenResumed = false;
        eventHandler.removeCallbacks(eventRetryRunnable);
        super.onPause();
    }

    private void showProfileEditor() {
        LinearLayout form = new LinearLayout(getParentActivity());
        form.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = AndroidUtilities.dp(20);
        form.setPadding(horizontalPadding, AndroidUtilities.dp(8), horizontalPadding, 0);

        EditText nameInput = new EditText(getParentActivity());
        nameInput.setSingleLine(true);
        nameInput.setHint("Имя и фамилия");
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        nameInput.setText(currentDisplayName);
        form.addView(nameInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        EditText usernameInput = new EditText(getParentActivity());
        usernameInput.setSingleLine(true);
        usernameInput.setHint("Username без @");
        usernameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        usernameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        usernameInput.setText(currentUsername);
        form.addView(usernameInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        EditText bioInput = new EditText(getParentActivity());
        bioInput.setHint("О себе · до 140 символов");
        bioInput.setSingleLine(false);
        bioInput.setMaxLines(3);
        bioInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(140)});
        bioInput.setText(currentBio);
        form.addView(bioInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        new AlertDialog.Builder(getParentActivity())
                .setTitle("Редактировать профиль")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("display_name", nameInput.getText() == null ? "" : nameInput.getText().toString());
                        payload.put("username", usernameInput.getText() == null ? "" : usernameInput.getText().toString());
                        payload.put("bio", bioInput.getText() == null ? "" : bioInput.getText().toString());
                    } catch (Exception exception) {
                        statusView.setText("Не удалось подготовить профиль");
                        return;
                    }
                    statusView.setText("Сохраняю профиль…");
                    apiClient.put("/api/me", payload, new WaysGramApiClient.Callback() {
                        @Override
                        public void onSuccess(JSONObject response) {
                            statusView.setText("Профиль обновлён");
                            loadProfile();
                        }

                        @Override
                        public void onError(String message, int statusCode) {
                            statusView.setText(message);
                        }
                    });
                })
                .show();
    }

    private void showAvatarActions() {
        String[] actions = currentAvatarMediaId.isEmpty()
                ? new String[]{"Выбрать фото"}
                : new String[]{"Выбрать фото", "Удалить фото"};
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Фото профиля")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("image/*");
                        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                        startActivityForResult(intent, REQUEST_AVATAR);
                    } else {
                        new AlertDialog.Builder(getParentActivity())
                                .setTitle("Удалить фото профиля?")
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Удалить", (confirm, selected) ->
                                        apiClient.delete("/api/me/avatar", new WaysGramApiClient.Callback() {
                                            @Override
                                            public void onSuccess(JSONObject response) {
                                                currentAvatarMediaId = "";
                                                avatarView.setImageDrawable(null);
                                                statusView.setText("Фото профиля удалено");
                                                loadProfile();
                                            }

                                            @Override
                                            public void onError(String message, int statusCode) {
                                                statusView.setText(message);
                                            }
                                        }))
                                .show();
                    }
                })
                .show();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_AVATAR) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri imageUri = data.getData();
        String mimeType = getParentActivity().getContentResolver().getType(imageUri);
        if (mimeType == null || !mimeType.startsWith("image/")) {
            statusView.setText("Выберите файл изображения");
            return;
        }
        statusView.setText("Загружаю фото профиля…");
        apiClient.uploadImage(getParentActivity().getContentResolver(), imageUri, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                String mediaId = response.optString("id", "");
                if (!mediaId.matches("[a-f0-9]{32}")) {
                    statusView.setText("Сервер вернул некорректный ID изображения");
                    return;
                }
                JSONObject payload = new JSONObject();
                try {
                    payload.put("media_id", mediaId);
                } catch (Exception exception) {
                    statusView.setText("Не удалось подготовить фото профиля");
                    return;
                }
                apiClient.put("/api/me/avatar", payload, new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject avatar) {
                        statusView.setText("Фото профиля обновлено");
                        loadProfile();
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        statusView.setText(message);
                    }
                });
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        });
    }

    private void showSessions() {
        if (apiClient == null) {
            return;
        }
        statusView.setText("Загружаю активные сеансы…");
        apiClient.get("/api/auth/sessions", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONArray sessions = response.optJSONArray("sessions");
                int count = sessions == null ? 0 : sessions.length();
                if (count == 0) {
                    statusView.setText("Активных сеансов не найдено");
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Активные сеансы")
                            .setMessage("Список активных сеансов пуст.")
                            .setPositiveButton("Закрыть", null)
                            .show();
                    return;
                }
                String[] labels = new String[count];
                String[] sessionIds = new String[count];
                boolean[] current = new boolean[count];
                for (int i = 0; i < count; i++) {
                    JSONObject item = sessions.optJSONObject(i);
                    if (item == null) {
                        labels[i] = "Неизвестный сеанс";
                        sessionIds[i] = "";
                        continue;
                    }
                    sessionIds[i] = item.optString("id", "");
                    current[i] = item.optBoolean("is_current", false);
                    long created = item.optLong("created_at", 0);
                    long expires = item.optLong("expires_at", 0);
                    StringBuilder label = new StringBuilder();
                    label.append(current[i] ? "Текущий сеанс" : "Другой сеанс");
                    if (created > 0) {
                        label.append("\nСоздан: ").append(formatSessionDate(created));
                    }
                    if (expires > 0) {
                        label.append("\nИстекает: ").append(formatSessionDate(expires));
                    }
                    if (current[i]) {
                        label.append("\nЭтот вход используется сейчас");
                    } else {
                        label.append("\nНажмите, чтобы завершить");
                    }
                    labels[i] = label.toString();
                }
                statusView.setText("Активных сеансов: " + count);
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Активные сеансы")
                        .setItems(labels, (dialog, which) -> {
                            if (which < 0 || which >= sessionIds.length) {
                                return;
                            }
                            if (current[which]) {
                                Toast.makeText(getParentActivity(),
                                        "Текущий сеанс нельзя завершить из этого списка.",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            String sessionId = sessionIds[which];
                            if (!sessionId.matches("[a-f0-9]{32}")) {
                                statusView.setText("Сервер вернул некорректный ID сеанса.");
                                return;
                            }
                            new AlertDialog.Builder(getParentActivity())
                                    .setTitle("Завершить сеанс?")
                                    .setMessage("Этот вход потеряет доступ к аккаунту WaysGram.")
                                    .setNegativeButton("Отмена", null)
                                    .setPositiveButton("Завершить", (confirm, selected) ->
                                            revokeSession(sessionId))
                                    .show();
                        })
                        .setNegativeButton("Закрыть", null)
                        .setPositiveButton("Завершить остальные", (dialog, which) ->
                                confirmRevokeOtherSessions())
                        .show();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
                if (statusCode == 401) {
                    sessionStore.clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
    }

    private String formatSessionDate(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(timestamp * 1000L));
    }

    private void confirmRevokeOtherSessions() {
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Завершить остальные сеансы?")
                .setMessage("Все остальные входы будут отключены. Текущий сеанс останется активным.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Завершить", (dialog, which) -> {
                    statusView.setText("Завершаю остальные сеансы…");
                    apiClient.post("/api/auth/sessions/revoke-others", new JSONObject(),
                            new WaysGramApiClient.Callback() {
                                @Override
                                public void onSuccess(JSONObject response) {
                                    int revoked = response.optInt("revoked_sessions", 0);
                                    statusView.setText("Завершено сеансов: " + revoked);
                                    Toast.makeText(getParentActivity(),
                                            "Остальные сеансы завершены: " + revoked,
                                            Toast.LENGTH_SHORT).show();
                                    showSessions();
                                }

                                @Override
                                public void onError(String message, int statusCode) {
                                    statusView.setText(message);
                                    if (statusCode == 401) {
                                        sessionStore.clearSession();
                                        presentFragment(new WaysGramLoginFragment(), true);
                                    }
                                }
                            });
                })
                .show();
    }

    private void revokeSession(String sessionId) {
        statusView.setText("Завершаю сеанс…");
        apiClient.delete("/api/auth/sessions/" + sessionId, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                statusView.setText("Сеанс завершён");
                showSessions();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
                if (statusCode == 401) {
                    sessionStore.clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
    }

    private void logout() {
        apiClient.post("/api/auth/logout", new JSONObject(), new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                sessionStore.clearSession();
                presentFragment(new WaysGramLoginFragment(), true);
            }

            @Override
            public void onError(String message, int statusCode) {
                // Clear local credentials even when the server cannot be reached.
                sessionStore.clearSession();
                Toast.makeText(getParentActivity(), "Локальная сессия очищена", Toast.LENGTH_SHORT).show();
                presentFragment(new WaysGramLoginFragment(), true);
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        isScreenResumed = false;
        eventHandler.removeCallbacks(eventRetryRunnable);
        if (apiClient != null) {
            apiClient.close();
            apiClient = null;
        }
        super.onFragmentDestroy();
    }
}
