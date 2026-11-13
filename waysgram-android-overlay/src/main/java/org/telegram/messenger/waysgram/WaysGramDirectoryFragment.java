package org.telegram.messenger.waysgram;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Server-backed WaysGram directory for DMs, groups and channels. */
public final class WaysGramDirectoryFragment extends BaseFragment {
    public static final int TYPE_CONVERSATIONS = 1;
    public static final int TYPE_GROUPS = 2;
    public static final int TYPE_CHANNELS = 3;
    public static final int TYPE_CONTACTS = 4;
    public static final int TYPE_BLOCKS = 5;

    private final int type;
    private WaysGramApiClient apiClient;
    private LinearLayout listContainer;
    private TextView statusView;
    private EditText primaryInput;
    private EditText usernameInput;
    private EditText inviteInput;
    private CheckBox privateChannelCheckBox;
    private Button createDirectoryButton;
    private Button joinInviteButton;
    private boolean createRequestInFlight;
    private boolean joinRequestInFlight;
    private boolean showingContactSearchResults;
    private boolean showingBlockSearchResults;
    private boolean showingConversationSearchResults;
    private boolean isScreenResumed;
    private boolean directoryEventsReady;
    private boolean directoryEventsInitializing;
    private boolean directoryEventPollInFlight;
    private long lastDirectoryEventId;
    private final Handler directoryEventHandler = new Handler(Looper.getMainLooper());
    private final Runnable directoryEventRetryRunnable = new Runnable() {
        @Override
        public void run() {
            checkDirectoryEvents();
        }
    };

    public WaysGramDirectoryFragment(int type) {
        this.type = type;
    }

    @Override
    public boolean onFragmentCreate() {
        return type >= TYPE_CONVERSATIONS && type <= TYPE_BLOCKS;
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(titleForType());
        apiClient = new WaysGramApiClient(
                WaysGramSessionStore.getInstance(context)
        );

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10),
                AndroidUtilities.dp(14), AndroidUtilities.dp(10));

        statusView = new TextView(context);
        statusView.setText("Загружаю…");
        statusView.setTextSize(13);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        addDirectoryControls(context, root);

        ScrollView scroll = new ScrollView(context);
        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        Button refresh = new Button(context);
        refresh.setText("Обновить");
        root.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        refresh.setOnClickListener(view -> loadItems());

        fragmentView = root;
        loadItems();
        return root;
    }

    private boolean supportsDirectoryEvents() {
        return type == TYPE_CONVERSATIONS || type == TYPE_GROUPS || type == TYPE_CHANNELS;
    }

    @Override
    public void onResume() {
        super.onResume();
        isScreenResumed = true;
        if (supportsDirectoryEvents()) {
            directoryEventHandler.removeCallbacks(directoryEventRetryRunnable);
            if (directoryEventsReady) {
                loadItems();
                checkDirectoryEvents();
            } else {
                initializeDirectoryEventCursor();
            }
        }
    }

    @Override
    public void onPause() {
        isScreenResumed = false;
        directoryEventHandler.removeCallbacks(directoryEventRetryRunnable);
        super.onPause();
    }

    private void initializeDirectoryEventCursor() {
        if (apiClient == null || !isScreenResumed || !supportsDirectoryEvents()
                || directoryEventsReady || directoryEventsInitializing) {
            return;
        }
        directoryEventsInitializing = true;
        apiClient.get("/api/events", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                directoryEventsInitializing = false;
                if (!isScreenResumed) {
                    return;
                }
                lastDirectoryEventId = response.optLong("latest_id", 0);
                directoryEventsReady = true;
                // Re-read after obtaining the cursor so no message can fall between
                // the initial list request and event subscription.
                loadItems();
                checkDirectoryEvents();
            }

            @Override
            public void onError(String message, int statusCode) {
                directoryEventsInitializing = false;
                if (statusCode == 401) {
                    WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                } else {
                    scheduleDirectoryEventRetry(4000);
                }
            }
        });
    }

    private void scheduleDirectoryEventRetry(int delayMs) {
        if (apiClient != null && isScreenResumed && supportsDirectoryEvents()) {
            directoryEventHandler.removeCallbacks(directoryEventRetryRunnable);
            directoryEventHandler.postDelayed(directoryEventRetryRunnable, delayMs);
        }
    }

    private void checkDirectoryEvents() {
        if (apiClient == null || !isScreenResumed || !supportsDirectoryEvents()
                || directoryEventPollInFlight) {
            return;
        }
        if (!directoryEventsReady) {
            initializeDirectoryEventCursor();
            return;
        }
        directoryEventPollInFlight = true;
        apiClient.getEventLongPoll(
                "/api/events?after_id=" + lastDirectoryEventId + "&wait_seconds=20",
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        directoryEventPollInFlight = false;
                        if (!isScreenResumed) {
                            return;
                        }
                        JSONArray events = response.optJSONArray("events");
                        String expectedScope = type == TYPE_CONVERSATIONS
                                ? "dm" : type == TYPE_GROUPS ? "group" : "channel";
                        boolean refreshDirectory = false;
                        int count = events == null ? 0 : events.length();
                        for (int i = 0; i < count; i++) {
                            JSONObject event = events.optJSONObject(i);
                            if (event != null && expectedScope.equals(event.optString("scope"))
                                    && "new_message".equals(event.optString("kind"))) {
                                refreshDirectory = true;
                                break;
                            }
                        }
                        lastDirectoryEventId = Math.max(
                                lastDirectoryEventId,
                                response.optLong("latest_id", lastDirectoryEventId)
                        );
                        if (refreshDirectory
                                && !(type == TYPE_CONVERSATIONS && showingConversationSearchResults)) {
                            loadItems();
                        }
                        checkDirectoryEvents();
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        directoryEventPollInFlight = false;
                        if (statusCode == 401) {
                            WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                            presentFragment(new WaysGramLoginFragment(), true);
                        } else {
                            scheduleDirectoryEventRetry(4000);
                        }
                    }
                }
        );
    }

    private void addDirectoryControls(Context context, LinearLayout root) {
        if (type == TYPE_CONVERSATIONS || type == TYPE_CONTACTS || type == TYPE_BLOCKS) {
            if (type == TYPE_CONVERSATIONS) {
                Button savedMessages = new Button(context);
                savedMessages.setAllCaps(false);
                savedMessages.setText("Избранное");
                root.addView(savedMessages, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                savedMessages.setOnClickListener(view -> openSavedMessages());
            }

            primaryInput = new EditText(context);
            primaryInput.setSingleLine(true);
            primaryInput.setHint(type == TYPE_CONTACTS
                    ? "Найти контакт по username или имени"
                    : type == TYPE_BLOCKS
                        ? "Найти пользователя по username или имени"
                        : "Поиск по username или имени");
            root.addView(primaryInput, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            Button search = new Button(context);
            search.setText(type == TYPE_CONTACTS
                    ? "Найти и добавить контакт"
                    : type == TYPE_BLOCKS ? "Найти и заблокировать" : "Найти пользователя");
            root.addView(search, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            search.setOnClickListener(view -> searchUsers());
            return;
        }

        primaryInput = new EditText(context);
        primaryInput.setSingleLine(true);
        primaryInput.setHint(type == TYPE_GROUPS ? "Название группы" : "Название канала");
        root.addView(primaryInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        usernameInput = new EditText(context);
        usernameInput.setSingleLine(true);
        usernameInput.setHint(type == TYPE_GROUPS
                ? "Username группы (необязательно)"
                : "Username канала");
        root.addView(usernameInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        if (type == TYPE_CHANNELS) {
            privateChannelCheckBox = new CheckBox(context);
            privateChannelCheckBox.setText("Приватный канал");
            root.addView(privateChannelCheckBox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            privateChannelCheckBox.setOnCheckedChangeListener((button, checked) -> {
                usernameInput.setVisibility(checked ? View.GONE : View.VISIBLE);
                if (checked) {
                    usernameInput.setText("");
                }
            });
        }

        createDirectoryButton = new Button(context);
        createDirectoryButton.setText(type == TYPE_GROUPS ? "Создать группу" : "Создать канал");
        root.addView(createDirectoryButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        createDirectoryButton.setOnClickListener(view -> createDirectoryItem());

        if (type == TYPE_CHANNELS || type == TYPE_GROUPS) {
            inviteInput = new EditText(context);
            inviteInput.setSingleLine(true);
            inviteInput.setHint(type == TYPE_GROUPS
                    ? "Ссылка или код приглашения в группу"
                    : "Ссылка или код приглашения в канал");
            root.addView(inviteInput, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            joinInviteButton = new Button(context);
            joinInviteButton.setAllCaps(false);
            joinInviteButton.setText("Вступить по приглашению");
            root.addView(joinInviteButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            joinInviteButton.setOnClickListener(view -> {
                if (type == TYPE_GROUPS) {
                    joinGroupByInvite();
                } else {
                    joinChannelByInvite();
                }
            });
        }
    }

    private void openSavedMessages() {
        statusView.setText("Открываю Избранное…");
        apiClient.get("/api/me", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                long userId = response.optLong("id", 0);
                if (userId <= 0) {
                    statusView.setText("Не удалось определить аккаунт для Избранного.");
                    return;
                }
                presentFragment(new WaysGramChatFragment(userId, "Избранное"), false);
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
                if (statusCode == 401) {
                    WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
    }

    private void searchUsers() {
        String query = primaryInput.getText() == null ? "" : primaryInput.getText().toString().trim();
        if (type == TYPE_CONTACTS) {
            showingContactSearchResults = true;
        } else if (type == TYPE_BLOCKS) {
            showingBlockSearchResults = true;
        } else if (type == TYPE_CONVERSATIONS) {
            showingConversationSearchResults = true;
        }
        if (query.length() == 0) {
            statusView.setText("Введите username или имя для поиска");
            return;
        }
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            statusView.setText("Ищу пользователей…");
            apiClient.get("/api/users?q=" + encoded, new WaysGramApiClient.Callback() {
                @Override
                public void onSuccess(JSONObject response) {
                    JSONArray users = response.optJSONArray("users");
                    listContainer.removeAllViews();
                    int count = users == null ? 0 : users.length();
                    for (int i = 0; i < count; i++) {
                        JSONObject item = users.optJSONObject(i);
                        if (item != null) {
                            addItem(item);
                        }
                    }
                    statusView.setText(count == 0 ? "Пользователи не найдены" : "Найдено: " + count);
                }

                @Override
                public void onError(String message, int statusCode) {
                    statusView.setText(message);
                }
            });
        } catch (Exception exception) {
            statusView.setText("Не удалось выполнить поиск");
        }
    }

    private void setCreateRequestInFlight(boolean inFlight) {
        createRequestInFlight = inFlight;
        if (createDirectoryButton != null) {
            createDirectoryButton.setEnabled(!inFlight);
        }
    }

    private void setJoinRequestInFlight(boolean inFlight) {
        joinRequestInFlight = inFlight;
        if (joinInviteButton != null) {
            joinInviteButton.setEnabled(!inFlight);
        }
    }

    private void createDirectoryItem() {
        if (createRequestInFlight) {
            return;
        }
        String title = primaryInput.getText() == null ? "" : primaryInput.getText().toString().trim();
        String username = usernameInput.getText() == null
                ? ""
                : usernameInput.getText().toString().trim().replaceFirst("^@", "").toLowerCase(Locale.US);
        if (title.length() == 0) {
            statusView.setText("Введите название");
            return;
        }
        if (title.length() > 80) {
            statusView.setText("Название должно содержать не более 80 символов.");
            return;
        }
        boolean isPublic = type != TYPE_CHANNELS
                || privateChannelCheckBox == null
                || !privateChannelCheckBox.isChecked();
        if (type == TYPE_CHANNELS && isPublic && username.length() == 0) {
            statusView.setText("Для публичного канала укажите username.");
            return;
        }
        if (username.length() > 0 && isPublic) {
            boolean validUsername = type == TYPE_GROUPS
                    ? username.matches("[a-z][a-z0-9_]{3,31}")
                    : username.matches("[a-z][a-z0-9_]{0,31}");
            if (!validUsername) {
                statusView.setText(type == TYPE_GROUPS
                        ? "Username группы: 4–32 символа, латиница, цифры и _, первый символ — буква."
                        : "Username канала: 1–32 символа, латиница, цифры и _, первый символ — буква.");
                return;
            }
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("title", title);
            if (username.length() > 0 && isPublic) {
                payload.put("username", username);
            }
            if (type == TYPE_CHANNELS) {
                payload.put("is_public", isPublic);
            }
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить запрос");
            return;
        }
        String path = type == TYPE_GROUPS ? "/api/groups" : "/api/channels";
        statusView.setText(type == TYPE_GROUPS ? "Создаю группу…" : "Создаю канал…");
        setCreateRequestInFlight(true);
        apiClient.post(path, payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                setCreateRequestInFlight(false);
                primaryInput.setText("");
                usernameInput.setText("");
                if (privateChannelCheckBox != null) {
                    privateChannelCheckBox.setChecked(false);
                }
                if (type == TYPE_CHANNELS && !isPublic) {
                    String inviteLink = response.optString("invite_link", "");
                    if (inviteLink.length() > 0) {
                        ClipboardManager clipboard = (ClipboardManager) getParentActivity()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("WaysGram invite", inviteLink));
                        }
                        statusView.setText("Приватный канал создан. Полная ссылка-приглашение скопирована:\n"
                                + inviteLink);
                    } else {
                        statusView.setText("Приватный канал создан, но сервер не вернул ссылку-приглашение.");
                    }
                } else {
                    statusView.setText(type == TYPE_GROUPS ? "Группа создана" : "Публичный канал создан");
                }
                loadItems();
            }

            @Override
            public void onError(String message, int statusCode) {
                setCreateRequestInFlight(false);
                statusView.setText(message);
            }
        });
    }

    private void joinGroupByInvite() {
        if (joinRequestInFlight) {
            return;
        }
        String inviteValue = inviteInput == null || inviteInput.getText() == null
                ? ""
                : inviteInput.getText().toString().trim();
        if (inviteValue.isEmpty()) {
            statusView.setText("Вставьте ссылку или код приглашения в группу.");
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("invite_link", inviteValue);
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить приглашение.");
            return;
        }
        statusView.setText("Проверяю приглашение в группу…");
        setJoinRequestInFlight(true);
        apiClient.post("/api/groups/join", payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                setJoinRequestInFlight(false);
                inviteInput.setText("");
                statusView.setText(response.optBoolean("already_member", false)
                        ? "Вы уже состоите в группе: " + response.optString("title", "Группа")
                        : response.optBoolean("join_requested", false)
                            ? "Заявка на вступление отправлена. Ожидайте одобрения администратора."
                            : "Вы вступили в группу: " + response.optString("title", "Группа"));
                loadItems();
            }

            @Override
            public void onError(String message, int statusCode) {
                setJoinRequestInFlight(false);
                statusView.setText(message);
            }
        });
    }

    private void joinChannelByInvite() {
        if (joinRequestInFlight) {
            return;
        }
        String inviteValue = inviteInput == null || inviteInput.getText() == null
                ? ""
                : inviteInput.getText().toString().trim();
        if (inviteValue.length() == 0) {
            statusView.setText("Вставьте ссылку или код приглашения.");
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("invite_link", inviteValue);
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить приглашение.");
            return;
        }
        statusView.setText("Проверяю приглашение…");
        setJoinRequestInFlight(true);
        apiClient.post("/api/channels/join", payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                setJoinRequestInFlight(false);
                inviteInput.setText("");
                statusView.setText("Вы вступили в приватный канал: "
                        + response.optString("title", "Канал"));
                loadItems();
            }

            @Override
            public void onError(String message, int statusCode) {
                setJoinRequestInFlight(false);
                statusView.setText(message);
            }
        });
    }

    private String titleForType() {
        switch (type) {
            case TYPE_GROUPS:
                return "Группы";
            case TYPE_CHANNELS:
                return "Каналы";
            case TYPE_CONTACTS:
                return "Контакты";
            case TYPE_BLOCKS:
                return "Чёрный список";
            default:
                return "Сообщения";
        }
    }

    private void loadItems() {
        if (type == TYPE_CONVERSATIONS) {
            showingConversationSearchResults = false;
        }
        String path;
        String key;
        if (type == TYPE_GROUPS) {
            path = "/api/groups";
            key = "groups";
        } else if (type == TYPE_CHANNELS) {
            path = "/api/channels";
            key = "channels";
        } else if (type == TYPE_CONTACTS) {
            path = "/api/contacts";
            key = "contacts";
            showingContactSearchResults = false;
        } else if (type == TYPE_BLOCKS) {
            path = "/api/blocks";
            key = "blocked_users";
            showingBlockSearchResults = false;
        } else {
            path = "/api/conversations";
            key = "conversations";
        }
        statusView.setText("Загружаю…");
        apiClient.get(path, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONArray items = response.optJSONArray(key);
                listContainer.removeAllViews();
                int count = items == null ? 0 : items.length();
                for (int i = 0; i < count; i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) {
                        addItem(item);
                    }
                }
                statusView.setText(count == 0 ? "Пока нет записей" : "Найдено: " + count);
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
                if (statusCode == 401) {
                    WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
    }

    private static boolean isPublicChannel(JSONObject item) {
        Object value = item.opt("is_public");
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String flag = ((String) value).trim();
            if ("0".equals(flag) || "false".equalsIgnoreCase(flag)) {
                return false;
            }
            if ("1".equals(flag) || "true".equalsIgnoreCase(flag)) {
                return true;
            }
        }
        // Older API versions had no is_public field and exposed only public channels.
        return value == null;
    }

    private void addItem(JSONObject item) {
        String title;
        String detail = "";
        long id = item.optLong("id", 0);
        if (type == TYPE_CONVERSATIONS || type == TYPE_CONTACTS || type == TYPE_BLOCKS) {
            title = item.optString("display_name", "");
            if (title.length() == 0) {
                title = item.optString("username", "Пользователь " + id);
            }
            if (type == TYPE_CONTACTS || type == TYPE_BLOCKS) {
                String username = item.optString("username", "");
                detail = username.isEmpty() ? "" : "@" + username;
                if (type == TYPE_CONTACTS) {
                    String note = item.optString("note", "").trim();
                    if (!note.isEmpty()) {
                        if (note.length() > 120) {
                            note = note.substring(0, 120) + "…";
                        }
                        detail += (detail.isEmpty() ? "" : "\n") + "Заметка: " + note;
                    }
                }
            } else {
                detail = item.optString("last_message", "");
                int unreadCount = item.optInt("unread_count", 0);
                if (unreadCount > 0) {
                    detail += (detail.isEmpty() ? "" : "\n")
                            + "Непрочитано: " + unreadCount;
                }
            }
        } else if (type == TYPE_GROUPS) {
            title = item.optString("title", "Группа " + id);
            detail = item.optString("member_count", "0") + " участников";
            int unreadCount = item.optInt("unread_count", 0);
            if (unreadCount > 0) {
                detail += "\nНепрочитано: " + unreadCount;
            }
        } else {
            title = item.optString("title", "Канал " + id);
            if (isPublicChannel(item)) {
                String username = item.optString("username", "");
                detail = username.length() > 0 ? "@" + username : "Публичный канал";
            } else {
                detail = "Приватный канал";
            }
            int unreadCount = item.optInt("unread_count", 0);
            if (unreadCount > 0) {
                detail += "\nНепрочитано: " + unreadCount;
            }
        }

        if (type == TYPE_CONTACTS || type == TYPE_BLOCKS) {
            LinearLayout row = new LinearLayout(getParentActivity());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Button open = new Button(getParentActivity());
            open.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            open.setAllCaps(false);
            open.setText(detail.length() > 0 ? title + "\n" + detail : title);
            row.addView(open, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
            ));
            if (type == TYPE_CONTACTS) {
                Button profileButton = new Button(getParentActivity());
                profileButton.setAllCaps(false);
                profileButton.setText("Профиль");
                row.addView(profileButton, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                profileButton.setOnClickListener(view ->
                        presentFragment(new WaysGramUserProfileFragment(id), false));
            }
            Button manage = new Button(getParentActivity());
            manage.setAllCaps(false);
            boolean searching = type == TYPE_CONTACTS
                    ? showingContactSearchResults
                    : showingBlockSearchResults;
            manage.setText(type == TYPE_CONTACTS
                    ? (searching ? "Добавить" : "Удалить")
                    : (searching ? "Заблокировать" : "Разблокировать"));
            row.addView(manage, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            if (type == TYPE_CONTACTS && !searching) {
                Button noteButton = new Button(getParentActivity());
                noteButton.setAllCaps(false);
                noteButton.setText("Заметка");
                row.addView(noteButton, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                noteButton.setOnClickListener(view ->
                        editContactNote(id, item.optString("note", ""))
                );
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rowParams.topMargin = AndroidUtilities.dp(5);
            listContainer.addView(row, rowParams);
            final String selectedTitle = title;
            open.setOnClickListener(view -> {
                if (id > 0) {
                    presentFragment(new WaysGramChatFragment(id, selectedTitle), false);
                }
            });
            if (type == TYPE_CONTACTS) {
                if (showingContactSearchResults) {
                    manage.setOnClickListener(view -> addContact(id, manage));
                } else {
                    manage.setOnClickListener(view -> removeContact(id));
                }
            } else if (showingBlockSearchResults) {
                manage.setOnClickListener(view -> blockUser(id, manage));
            } else {
                manage.setOnClickListener(view -> unblockUser(id));
            }
            return;
        }

        Button button = new Button(getParentActivity());
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setAllCaps(false);
        button.setText(detail.length() > 0 ? title + "\n" + detail : title);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = AndroidUtilities.dp(5);
        listContainer.addView(button, params);
        final String selectedTitle = title;
        button.setOnClickListener(view -> {
            if (id <= 0) {
                return;
            }
            if (type == TYPE_CONVERSATIONS) {
                presentFragment(new WaysGramChatFragment(id, selectedTitle), false);
            } else {
                presentFragment(new WaysGramTimelineFragment(
                        type == TYPE_GROUPS ? WaysGramTimelineFragment.TYPE_GROUP : WaysGramTimelineFragment.TYPE_CHANNEL,
                        id,
                        selectedTitle
                ), false);
            }
        });
    }

    private void editContactNote(long userId, String currentNote) {
        if (userId <= 0) {
            return;
        }
        EditText noteInput = new EditText(getParentActivity());
        noteInput.setSingleLine(false);
        noteInput.setMaxLines(5);
        noteInput.setHint("Личная заметка · до 500 символов");
        noteInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        noteInput.setText(currentNote == null ? "" : currentNote);
        noteInput.setSelection(noteInput.length());
        LinearLayout form = new LinearLayout(getParentActivity());
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(20);
        form.setPadding(padding, AndroidUtilities.dp(8), padding, 0);
        form.addView(noteInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Личная заметка к контакту")
                .setMessage("Заметка видна только в твоём списке контактов.")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String note = noteInput.getText() == null
                            ? ""
                            : noteInput.getText().toString();
                    if (note.length() > 500) {
                        statusView.setText("Заметка не должна превышать 500 символов.");
                        return;
                    }
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("note", note);
                    } catch (Exception exception) {
                        statusView.setText("Не удалось подготовить заметку.");
                        return;
                    }
                    statusView.setText("Сохраняю личную заметку…");
                    apiClient.put("/api/contacts/" + userId + "/note", payload,
                            new WaysGramApiClient.Callback() {
                                @Override
                                public void onSuccess(JSONObject response) {
                                    statusView.setText(note.trim().isEmpty()
                                            ? "Заметка удалена"
                                            : "Личная заметка сохранена");
                                    loadItems();
                                }

                                @Override
                                public void onError(String message, int statusCode) {
                                    statusView.setText(message);
                                }
                            });
                })
                .show();
    }

    private void addContact(long userId, Button button) {
        if (userId <= 0 || button == null || !button.isEnabled()) {
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("user_id", userId);
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить контакт");
            return;
        }
        button.setEnabled(false);
        button.setText("Добавляю…");
        apiClient.post("/api/contacts", payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                button.setText("Добавлен");
                statusView.setText("Контакт добавлен");
            }

            @Override
            public void onError(String message, int statusCode) {
                button.setEnabled(true);
                button.setText("Добавить");
                statusView.setText(message);
            }
        });
    }

    private void blockUser(long userId, Button button) {
        if (userId <= 0 || button == null || !button.isEnabled()) {
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("user_id", userId);
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить блокировку");
            return;
        }
        button.setEnabled(false);
        button.setText("Блокирую…");
        apiClient.post("/api/blocks", payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                button.setText("Заблокирован");
                statusView.setText("Пользователь добавлен в чёрный список");
            }

            @Override
            public void onError(String message, int statusCode) {
                button.setEnabled(true);
                button.setText("Заблокировать");
                statusView.setText(message);
            }
        });
    }

    private void unblockUser(long userId) {
        if (userId <= 0) {
            return;
        }
        apiClient.delete("/api/blocks/" + userId, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                statusView.setText("Пользователь разблокирован");
                loadItems();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        });
    }

    private void removeContact(long userId) {
        if (userId <= 0) {
            return;
        }
        apiClient.delete("/api/contacts/" + userId, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                statusView.setText("Контакт удалён");
                loadItems();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        isScreenResumed = false;
        directoryEventHandler.removeCallbacks(directoryEventRetryRunnable);
        if (apiClient != null) {
            apiClient.close();
            apiClient = null;
        }
        super.onFragmentDestroy();
    }
}
