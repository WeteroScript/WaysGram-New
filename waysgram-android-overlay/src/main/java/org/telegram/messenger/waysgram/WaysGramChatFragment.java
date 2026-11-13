package org.telegram.messenger.waysgram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

/** WaysGram direct text conversation backed by /api/messages. */
public final class WaysGramChatFragment extends BaseFragment {
    private static final int REQUEST_ATTACHMENT = 7101;
    private static final int REQUEST_SAVE_DOCUMENT = 7103;
    private final long peerId;
    private final String peerTitle;

    private WaysGramApiClient apiClient;
    private WaysGramCloudDraftSync cloudDraftSync;
    private WaysGramTypingIndicatorController typingIndicatorController;
    private LinearLayout messagesContainer;
    private ScrollView messageScrollView;
    private Button loadOlderButton;
    private EditText messageInput;
    private CheckBox silentSendCheckBox;
    private Button sendButton;
    private TextView statusView;
    private TextView typingView;
    private boolean messageSendInFlight = false;
    private long replyToMessageId = 0;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollNewMessages();
        }
    };
    private long lastMessageId = 0;
    private long oldestMessageId = 0;
    private boolean historyLoading = false;
    private long peerReadMessageId = 0;
    private final Map<String, String> forwardIdempotencyKeys = new HashMap<>();
    private final Map<String, String> messageIdempotencyKeys = new HashMap<>();
    private boolean pollInFlight = false;
    private boolean eventPollInFlight = false;
    private boolean isScreenResumed = false;
    private long lastMessageEventId = 0;
    private boolean messageEventsReady = false;
    private boolean messageEventsInitializing = false;
    private final Runnable eventPollRetryRunnable = new Runnable() {
        @Override
        public void run() {
            checkMessageEvents();
        }
    };
    private String pendingSaveMediaId;

    public WaysGramChatFragment(long peerId, String peerTitle) {
        if (peerId <= 0) {
            throw new IllegalArgumentException("Invalid WaysGram peer ID");
        }
        this.peerId = peerId;
        this.peerTitle = peerTitle == null ? "" : peerTitle;
    }

    @Override
    public boolean onFragmentCreate() {
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(peerTitle.length() > 0 ? peerTitle : "Диалог");
        apiClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(context));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8),
                AndroidUtilities.dp(10), AndroidUtilities.dp(8));

        statusView = new TextView(context);
        statusView.setText("Загрузка истории…");
        statusView.setTextSize(12);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        typingView = new TextView(context);
        typingView.setTextSize(12);
        typingView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        typingView.setVisibility(View.GONE);
        root.addView(typingView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        loadOlderButton = new Button(context);
        loadOlderButton.setText("Загрузить предыдущие сообщения");
        loadOlderButton.setAllCaps(false);
        loadOlderButton.setVisibility(View.GONE);
        root.addView(loadOlderButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        loadOlderButton.setOnClickListener(view -> loadOlderMessages());

        messageScrollView = new ScrollView(context);
        messagesContainer = new LinearLayout(context);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messageScrollView.addView(messagesContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(messageScrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        LinearLayout composer = new LinearLayout(context);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        messageInput = new EditText(context);
        messageInput.setHint("Сообщение или подпись к фото");
        messageInput.setSingleLine(false);
        messageInput.setMaxLines(4);
        messageInput.setText(WaysGramDraftStore.load(context, "dm", peerId));
        cloudDraftSync = new WaysGramCloudDraftSync(context, apiClient, "dm", peerId, messageInput);
        cloudDraftSync.start();
        typingIndicatorController = new WaysGramTypingIndicatorController(
                apiClient, "dm", peerId, messageInput, typingView
        );
        composer.addView(messageInput, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        ));
        Button attachButton = new Button(context);
        attachButton.setText("Файл");
        attachButton.setAllCaps(false);
        composer.addView(attachButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        attachButton.setOnClickListener(view -> pickAttachment());

        Button giftButton = new Button(context);
        giftButton.setText("Подарок");
        giftButton.setAllCaps(false);
        composer.addView(giftButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        giftButton.setOnClickListener(view -> showGiftPicker());

        sendButton = new Button(context);
        sendButton.setText("Отправить");
        sendButton.setAllCaps(false);
        composer.addView(sendButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(composer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        sendButton.setOnClickListener(view -> sendMessage());

        Button formatButton = new Button(context);
        formatButton.setText("Форматирование");
        formatButton.setAllCaps(false);
        root.addView(formatButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        formatButton.setOnClickListener(view -> showFormattingOptions());

        silentSendCheckBox = new CheckBox(context);
        silentSendCheckBox.setText("Отправить без звука");
        silentSendCheckBox.setTextSize(12);
        root.addView(silentSendCheckBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button refreshButton = new Button(context);
        refreshButton.setText("Обновить историю");
        root.addView(refreshButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        refreshButton.setOnClickListener(view -> loadMessages());

        Button searchButton = new Button(context);
        searchButton.setText("Поиск по переписке");
        searchButton.setAllCaps(false);
        root.addView(searchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        searchButton.setOnClickListener(view -> showMessageSearchDialog());

        Button mediaButton = new Button(context);
        mediaButton.setText("Общие медиа");
        mediaButton.setAllCaps(false);
        root.addView(mediaButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        mediaButton.setOnClickListener(view ->
                presentFragment(new WaysGramSharedMediaFragment("dm", peerId, peerTitle), false));

        Button exportButton = new Button(context);
        exportButton.setText("Экспорт переписки");
        exportButton.setAllCaps(false);
        root.addView(exportButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        exportButton.setOnClickListener(view -> exportChatHistory());

        Button pinnedButton = new Button(context);
        pinnedButton.setText("Закреплённые сообщения");
        pinnedButton.setAllCaps(false);
        root.addView(pinnedButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        pinnedButton.setOnClickListener(view -> showPinnedMessages());

        Button clearHistoryButton = new Button(context);
        clearHistoryButton.setText("Очистить историю у себя");
        clearHistoryButton.setAllCaps(false);
        root.addView(clearHistoryButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        clearHistoryButton.setOnClickListener(view -> confirmClearHistory());

        fragmentView = root;
        initializeMessageEventCursor();
        return root;
    }

    private void exportChatHistory() {
        if (apiClient == null || getParentActivity() == null) {
            return;
        }
        statusView.setText("Подготавливаю экспорт переписки…");
        apiClient.get("/api/messages/export?with=" + peerId,
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        String exportedText = response.optString("text", "");
                        if (exportedText.isEmpty()) {
                            statusView.setText("В переписке нет сообщений для экспорта.");
                            return;
                        }
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(
                                Intent.EXTRA_SUBJECT,
                                "WaysGram — " + peerTitle
                        );
                        shareIntent.putExtra(Intent.EXTRA_TEXT, exportedText);
                        try {
                            getParentActivity().startActivity(
                                    Intent.createChooser(shareIntent, "Экспорт переписки")
                            );
                            String suffix = response.optBoolean("truncated")
                                    ? " Экспорт ограничен последними сообщениями."
                                    : "";
                            statusView.setText(
                                    "Подготовлено сообщений: " + response.optInt("count", 0) + "." + suffix
                            );
                        } catch (Exception exception) {
                            statusView.setText("Не удалось открыть меню отправки экспорта.");
                        }
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        statusView.setText(message);
                        if (statusCode == 401 && getParentActivity() != null) {
                            WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                            presentFragment(new WaysGramLoginFragment(), true);
                        }
                    }
                });
    }

    private void showFormattingOptions() {
        String[] labels = {
                "Жирный",
                "Курсив",
                "Подчёркнутый",
                "Зачёркнутый",
                "Спойлер",
                "Моноширинный"
        };
        String[] markers = {"**", "*", "--", "~~", "||", "`"};
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Форматирование текста")
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < markers.length) {
                        wrapSelectedText(markers[which]);
                    }
                })
                .show();
    }

    private void wrapSelectedText(String marker) {
        if (messageInput == null) {
            return;
        }
        int start = messageInput.getSelectionStart();
        int end = messageInput.getSelectionEnd();
        if (start < 0 || end < 0) {
            start = end = messageInput.length();
        }
        if (start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        android.text.Editable editable = messageInput.getText();
        String selected = editable.subSequence(start, end).toString();
        String wrapped = marker + selected + marker;
        editable.replace(start, end, wrapped);
        if (selected.isEmpty()) {
            messageInput.setSelection(start + marker.length());
        } else {
            messageInput.setSelection(
                    start + marker.length(),
                    start + marker.length() + selected.length()
            );
        }
        messageInput.requestFocus();
    }

    private CharSequence formatInlineMarkup(String source) {
        SpannableStringBuilder formatted = new SpannableStringBuilder(
                source == null ? "" : source
        );
        applyInlineMarkup(formatted, "**", 0);
        applyInlineMarkup(formatted, "*", 1);
        applyInlineMarkup(formatted, "--", 2);
        applyInlineMarkup(formatted, "~~", 3);
        applyInlineMarkup(formatted, "||", 4);
        applyInlineMarkup(formatted, "`", 5);
        return formatted;
    }

    private void applyInlineMarkup(
            SpannableStringBuilder text,
            String marker,
            int style
    ) {
        Pattern pattern = Pattern.compile(
                Pattern.quote(marker) + "(.+?)" + Pattern.quote(marker),
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(text);
        ArrayList<int[]> ranges = new ArrayList<>();
        while (matcher.find()) {
            ranges.add(new int[]{matcher.start(1), matcher.end(1)});
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int start = ranges.get(i)[0];
            int end = ranges.get(i)[1];
            Object span;
            switch (style) {
                case 0:
                    span = new StyleSpan(android.graphics.Typeface.BOLD);
                    break;
                case 1:
                    span = new StyleSpan(android.graphics.Typeface.ITALIC);
                    break;
                case 2:
                    span = new UnderlineSpan();
                    break;
                case 3:
                    span = new StrikethroughSpan();
                    break;
                case 4:
                    int spoilerColor = Theme.getColor(
                            Theme.key_windowBackgroundWhiteGrayText
                    );
                    span = new ClickableSpan() {
                        @Override
                        public void updateDrawState(android.text.TextPaint paint) {
                            paint.setColor(spoilerColor);
                            paint.bgColor = spoilerColor;
                            paint.setUnderlineText(false);
                        }

                        @Override
                        public void onClick(View widget) {
                            Spannable visibleText = (Spannable) ((TextView) widget).getText();
                            int spoilerStart = visibleText.getSpanStart(this);
                            int spoilerEnd = visibleText.getSpanEnd(this);
                            visibleText.removeSpan(this);
                            if (spoilerStart >= 0 && spoilerEnd > spoilerStart) {
                                visibleText.setSpan(
                                        new ForegroundColorSpan(
                                                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)
                                        ),
                                        spoilerStart,
                                        spoilerEnd,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                );
                            }
                        }
                    };
                    break;
                case 5:
                    span = new TypefaceSpan("monospace");
                    break;
                default:
                    continue;
            }
            text.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.delete(end, end + marker.length());
            text.delete(start - marker.length(), start);
        }
    }

    private void showMessageSearchDialog() {
        EditText queryInput = new EditText(getParentActivity());
        queryInput.setSingleLine(true);
        queryInput.setHint("Текст сообщения");
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Поиск по переписке")
                .setView(queryInput)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Найти", (dialog, which) -> {
                    String query = queryInput.getText() == null
                            ? ""
                            : queryInput.getText().toString().trim();
                    if (query.isEmpty()) {
                        statusView.setText("Введите текст для поиска");
                        return;
                    }
                    if (query.length() > 128) {
                        statusView.setText("Запрос не должен превышать 128 символов");
                        return;
                    }
                    statusView.setText("Ищу сообщения…");
                    String encodedQuery = Uri.encode(query);
                    apiClient.get(
                            "/api/messages/search?with=" + peerId + "&q=" + encodedQuery,
                            new WaysGramApiClient.Callback() {
                                @Override
                                public void onSuccess(JSONObject response) {
                                    JSONArray messages = response.optJSONArray("messages");
                                    int count = messages == null ? 0 : messages.length();
                                    if (count == 0) {
                                        statusView.setText("Совпадений не найдено");
                                        new AlertDialog.Builder(getParentActivity())
                                                .setTitle("Результаты поиска")
                                                .setMessage("По запросу «" + query + "» ничего не найдено.")
                                                .setPositiveButton("Закрыть", null)
                                                .show();
                                        return;
                                    }
                                    StringBuilder results = new StringBuilder();
                                    int displayCount = Math.min(count, 20);
                                    for (int i = 0; i < displayCount; i++) {
                                        JSONObject message = messages.optJSONObject(i);
                                        if (message == null) {
                                            continue;
                                        }
                                        long sender = message.optLong("sender_id", 0);
                                        String author = sender == peerId ? peerTitle : "Вы";
                                        long createdAt = message.optLong("created_at", 0);
                                        String time = createdAt > 0
                                                ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                                        .format(new Date(createdAt * 1000L))
                                                : "";
                                        if (results.length() > 0) {
                                            results.append("\n\n");
                                        }
                                        results.append(author);
                                        if (!time.isEmpty()) {
                                            results.append(" · ").append(time);
                                        }
                                        String body = message.optString("body", "");
                                        if (body.length() > 600) {
                                            body = body.substring(0, 600) + "…";
                                        }
                                        results.append("\n").append(body);
                                    }
                                    statusView.setText("Найдено сообщений: " + count);
                                    if (count > displayCount) {
                                        results.append("\n\nПоказаны первые ").append(displayCount)
                                                .append(" из ").append(count).append(" совпадений.");
                                    }
                                    new AlertDialog.Builder(getParentActivity())
                                            .setTitle("Результаты поиска · " + count)
                                            .setMessage(results.toString())
                                            .setPositiveButton("Закрыть", null)
                                            .show();
                                }

                                @Override
                                public void onError(String message, int statusCode) {
                                    statusView.setText(message);
                                    if (statusCode == 401) {
                                        WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                                        presentFragment(new WaysGramLoginFragment(), true);
                                    }
                                }
                            }
                    );
                })
                .show();
    }

    private void initializeMessageEventCursor() {
        if (apiClient == null || messageEventsReady || messageEventsInitializing) {
            return;
        }
        messageEventsInitializing = true;
        apiClient.get("/api/events", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                messageEventsInitializing = false;
                lastMessageEventId = response.optLong("latest_id", 0);
                messageEventsReady = true;
                loadMessages();
                if (isScreenResumed) {
                    pollNewMessages();
                    checkMessageEvents();
                }
            }

            @Override
            public void onError(String message, int statusCode) {
                messageEventsInitializing = false;
                messageEventsReady = false;
                if (statusCode == 401) {
                    WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                    return;
                }
                loadMessages();
                if (isScreenResumed) {
                    scheduleNextPoll(4000);
                }
            }
        });
    }

    private void scheduleNextEventPoll(int delayMs) {
        if (apiClient != null && isScreenResumed) {
            pollHandler.removeCallbacks(eventPollRetryRunnable);
            pollHandler.postDelayed(eventPollRetryRunnable, delayMs);
        }
    }

    private void checkMessageEvents() {
        if (apiClient == null || !isScreenResumed || eventPollInFlight) {
            return;
        }
        if (!messageEventsReady) {
            initializeMessageEventCursor();
            return;
        }
        eventPollInFlight = true;
        apiClient.getEventLongPoll(
                "/api/events?after_id=" + lastMessageEventId + "&wait_seconds=20",
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        eventPollInFlight = false;
                        if (!isScreenResumed) {
                            return;
                        }
                        JSONArray events = response.optJSONArray("events");
                        boolean refreshCurrentChat = false;
                        int count = events == null ? 0 : events.length();
                        for (int i = 0; i < count; i++) {
                            JSONObject event = events.optJSONObject(i);
                            if (event != null && "dm".equals(event.optString("scope"))
                                    && (event.optLong("user_a", 0) == peerId
                                    || event.optLong("user_b", 0) == peerId)) {
                                refreshCurrentChat = true;
                                break;
                            }
                        }
                        lastMessageEventId = Math.max(
                                lastMessageEventId,
                                response.optLong("latest_id", lastMessageEventId)
                        );
                        if (refreshCurrentChat) {
                            loadMessages();
                        }
                        checkMessageEvents();
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        eventPollInFlight = false;
                        if (statusCode == 401) {
                            WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                            presentFragment(new WaysGramLoginFragment(), true);
                        } else {
                            scheduleNextEventPoll(4000);
                        }
                    }
                }
        );
    }

    private void loadMessages() {
        apiClient.get("/api/messages?with=" + peerId, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                peerReadMessageId = response.optLong("peer_read_id", peerReadMessageId);
                JSONArray messages = response.optJSONArray("messages");
                messagesContainer.removeAllViews();
                int count = messages == null ? 0 : messages.length();
                long newestId = lastMessageId;
                for (int i = 0; i < count; i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message != null) {
                        addMessage(message);
                        newestId = Math.max(newestId, message.optLong("id", 0));
                    }
                }
                lastMessageId = newestId;
                oldestMessageId = response.optLong("next_before_id", 0);
                if (loadOlderButton != null) {
                    loadOlderButton.setVisibility(count >= 100 && oldestMessageId > 0
                            ? View.VISIBLE : View.GONE);
                    loadOlderButton.setEnabled(true);
                }
                statusView.setText(count == 0 ? "История пока пуста" : "Сообщений: " + count);
                markMessagesRead();
                scheduleNextPoll();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
                if (statusCode == 401) {
                    WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                } else {
                    scheduleNextPoll();
                }
            }
        });
    }

    private void loadOlderMessages() {
        if (apiClient == null || historyLoading || oldestMessageId <= 0
                || messageScrollView == null || messagesContainer == null) {
            return;
        }
        historyLoading = true;
        loadOlderButton.setEnabled(false);
        loadOlderButton.setText("Загружаю…");

        final long beforeId = oldestMessageId;
        final int oldContentHeight = messagesContainer.getHeight();
        final int oldScrollY = messageScrollView.getScrollY();
        apiClient.get("/api/messages?with=" + peerId + "&before_id=" + beforeId,
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        historyLoading = false;
                        JSONArray messages = response.optJSONArray("messages");
                        int count = messages == null ? 0 : messages.length();
                        if (count == 0) {
                            oldestMessageId = 0;
                            loadOlderButton.setVisibility(View.GONE);
                            statusView.setText("Более ранних сообщений нет");
                            loadOlderButton.setEnabled(true);
                            loadOlderButton.setText("Загрузить предыдущие сообщения");
                            return;
                        }

                        int firstNewIndex = messagesContainer.getChildCount();
                        for (int i = 0; i < count; i++) {
                            JSONObject message = messages.optJSONObject(i);
                            if (message != null) {
                                addMessage(message);
                            }
                        }

                        ArrayList<View> olderViews = new ArrayList<>();
                        while (messagesContainer.getChildCount() > firstNewIndex) {
                            View child = messagesContainer.getChildAt(firstNewIndex);
                            messagesContainer.removeViewAt(firstNewIndex);
                            olderViews.add(child);
                        }
                        for (int i = 0; i < olderViews.size(); i++) {
                            messagesContainer.addView(olderViews.get(i), i);
                        }

                        oldestMessageId = response.optLong("next_before_id", 0);
                        loadOlderButton.setVisibility(count >= 100 && oldestMessageId > 0
                                ? View.VISIBLE : View.GONE);
                        loadOlderButton.setEnabled(true);
                        loadOlderButton.setText("Загрузить предыдущие сообщения");
                        statusView.setText("Загружено предыдущих сообщений: " + count);

                        messageScrollView.post(() -> {
                            int insertedHeight = Math.max(
                                    0, messagesContainer.getHeight() - oldContentHeight
                            );
                            messageScrollView.scrollTo(0, oldScrollY + insertedHeight);
                        });
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        historyLoading = false;
                        loadOlderButton.setEnabled(true);
                        loadOlderButton.setText("Загрузить предыдущие сообщения");
                        statusView.setText(message);
                        if (statusCode == 401 && getParentActivity() != null) {
                            WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                            presentFragment(new WaysGramLoginFragment(), true);
                        }
                    }
                });
    }

    private void markMessagesRead() {
        if (apiClient == null || lastMessageId <= 0) {
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("scope", "dm");
            payload.put("peer_id", peerId);
            payload.put("last_read_id", lastMessageId);
        } catch (Exception exception) {
            return;
        }
        apiClient.put("/api/read", payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                // Read markers are background state; keep the chat status unchanged.
            }

            @Override
            public void onError(String message, int statusCode) {
                if (statusCode == 401 && getParentActivity() != null) {
                    WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
    }

    private void scheduleNextPoll() {
        scheduleNextPoll(500);
    }

    private void scheduleNextPoll(int delayMs) {
        if (apiClient != null && isScreenResumed) {
            pollHandler.removeCallbacks(pollRunnable);
            pollHandler.postDelayed(pollRunnable, delayMs);
        }
    }

    private void pollNewMessages() {
        if (apiClient == null || !isScreenResumed || pollInFlight) {
            return;
        }
        pollInFlight = true;
        apiClient.getLongPoll("/api/messages?with=" + peerId + "&after_id=" + lastMessageId + "&wait_seconds=20",
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        pollInFlight = false;
                        long latestPeerReadId = response.optLong("peer_read_id", peerReadMessageId);
                        if (latestPeerReadId > peerReadMessageId) {
                            peerReadMessageId = latestPeerReadId;
                            loadMessages();
                        }
                        JSONArray messages = response.optJSONArray("messages");
                        int count = messages == null ? 0 : messages.length();
                        for (int i = 0; i < count; i++) {
                            JSONObject message = messages.optJSONObject(i);
                            if (message != null) {
                                long messageId = message.optLong("id", 0);
                                if (messageId > lastMessageId) {
                                    addMessage(message);
                                    lastMessageId = messageId;
                                }
                            }
                        }
                        if (count > 0) {
                            statusView.setText("Новые сообщения: " + count);
                            markMessagesRead();
                        }
                        scheduleNextPoll();
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        pollInFlight = false;
                        if (statusCode == 401) {
                            WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                            presentFragment(new WaysGramLoginFragment(), true);
                        } else {
                            scheduleNextPoll(4000);
                        }
                    }
                });
    }

    private void pickAttachment() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"image/*", "application/pdf", "text/plain", "text/csv"});
        startActivityForResult(intent, REQUEST_ATTACHMENT);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_SAVE_DOCUMENT) {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                pendingSaveMediaId = null;
                return;
            }
            String mediaId = pendingSaveMediaId;
            pendingSaveMediaId = null;
            if (mediaId == null) {
                statusView.setText("Не выбран документ для сохранения.");
                return;
            }
            statusView.setText("Сохраняю документ…");
            apiClient.downloadDocumentToUri(getParentActivity().getContentResolver(), data.getData(),
                    mediaId, new WaysGramApiClient.Callback() {
                        @Override
                        public void onSuccess(JSONObject response) {
                            statusView.setText("Документ сохранён.");
                        }

                        @Override
                        public void onError(String message, int statusCode) {
                            statusView.setText(message);
                        }
                    });
            return;
        }
        if (requestCode != REQUEST_ATTACHMENT || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            statusView.setText("Не удалось получить выбранный файл.");
            return;
        }
        String mimeType = getParentActivity().getContentResolver().getType(uri);
        statusView.setText("Загружаю вложение…");
        if (mimeType != null && mimeType.startsWith("image/")) {
            apiClient.uploadImage(getParentActivity().getContentResolver(), uri,
                    new WaysGramApiClient.Callback() {
                        @Override
                        public void onSuccess(JSONObject response) {
                            sendImage(response.optString("id", ""));
                        }

                        @Override
                        public void onError(String message, int statusCode) {
                            statusView.setText(message);
                        }
                    });
        } else {
            apiClient.uploadDocument(getParentActivity().getContentResolver(), uri,
                    new WaysGramApiClient.Callback() {
                        @Override
                        public void onSuccess(JSONObject response) {
                            sendDocument(response.optString("id", ""));
                        }

                        @Override
                        public void onError(String message, int statusCode) {
                            statusView.setText(message);
                        }
                    });
        }
    }

    private void sendImage(String mediaId) {
        if (mediaId == null || !mediaId.matches("[a-f0-9]{32}")) {
            statusView.setText("Сервер вернул некорректный ID изображения.");
            return;
        }
        String caption = messageInput.getText() == null
                ? ""
                : messageInput.getText().toString().trim();
        JSONObject payload = new JSONObject();
        try {
            payload.put("recipient_id", peerId);
            payload.put("body", caption.length() == 0 ? "Изображение" : caption);
            payload.put("media_id", mediaId);
            payload.put("silent", isSilentSendEnabled());
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить изображение к отправке.");
            return;
        }
        statusView.setText("Отправляю изображение…");
        String requestFingerprint = payload.toString();
        String idempotencyKey = messageIdempotencyKey(requestFingerprint);
        apiClient.post("/api/messages", payload, idempotencyKey, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                messageIdempotencyKeys.remove(requestFingerprint);
                clearChatDraft();
                resetSilentSendMode();
                statusView.setText("Изображение отправлено.");
                loadMessages();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        });
    }

    private void showGiftPicker() {
        if (messageSendInFlight || apiClient == null) {
            return;
        }
        statusView.setText("Загружаю ваши подарки…");
        apiClient.get("/api/me/gifts", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONArray gifts = response.optJSONArray("gifts");
                java.util.ArrayList<JSONObject> available = new java.util.ArrayList<>();
                if (gifts != null) {
                    for (int i = 0; i < gifts.length(); i++) {
                        JSONObject gift = gifts.optJSONObject(i);
                        if (gift != null && gift.optInt("is_listed", 0) != 1) {
                            available.add(gift);
                        }
                    }
                }
                if (available.isEmpty()) {
                    statusView.setText(gifts == null || gifts.length() == 0
                            ? "У вас пока нет подарков для отправки"
                            : "Снимите подарок с маркетплейса перед отправкой");
                    return;
                }
                String[] labels = new String[available.size()];
                for (int i = 0; i < available.size(); i++) {
                    JSONObject gift = available.get(i);
                    labels[i] = gift.optString("gift_name", "Подарок WaysGram")
                            + " · уровень " + gift.optInt("upgrade_level", 0)
                            + "\n" + gift.optString("serial_number", "");
                }
                statusView.setText("Выберите подарок для отправки");
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Ваши подарки")
                        .setItems(labels, (dialog, which) -> {
                            if (which >= 0 && which < available.size()) {
                                confirmSendGift(available.get(which));
                            }
                        })
                        .setNegativeButton("Закрыть", null)
                        .show();
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

    private void confirmSendGift(JSONObject gift) {
        if (gift == null) {
            return;
        }
        String giftName = gift.optString("gift_name", "Подарок WaysGram");
        String serial = gift.optString("serial_number", "");
        String caption = messageInput.getText() == null
                ? ""
                : messageInput.getText().toString().trim();
        String message = "Передать «" + giftName + "» пользователю "
                + (peerTitle.isEmpty() ? peerId : peerTitle)
                + "?\nСерийный номер: " + serial;
        if (!caption.isEmpty()) {
            message += "\n\nПодпись: " + caption;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Отправка подарка")
                .setMessage(message)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отправить", (dialog, which) -> sendGiftMessage(gift, caption))
                .show();
    }

    private void sendGiftMessage(JSONObject gift, String caption) {
        String serial = gift.optString("serial_number", "");
        if (serial.isEmpty() || messageSendInFlight) {
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("recipient_id", peerId);
            payload.put("serial_number", serial);
            if (caption != null && !caption.isEmpty()) {
                payload.put("body", caption);
            }
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить подарок к отправке");
            return;
        }
        setMessageSendInFlight(true);
        statusView.setText("Передаю подарок…");
        String idempotencyKey = "wg-chat-gift-" + UUID.randomUUID().toString().replace("-", "");
        apiClient.post("/api/messages/gift", payload, idempotencyKey, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                setMessageSendInFlight(false);
                clearChatDraft();
                replyToMessageId = 0;
                statusView.setOnClickListener(null);
                statusView.setText("Подарок отправлен");
                loadMessages();
            }

            @Override
            public void onError(String message, int statusCode) {
                setMessageSendInFlight(false);
                statusView.setText(message);
                if (statusCode == 401) {
                    WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
    }

    private void addMessage(JSONObject message) {
        TextView row = new TextView(getParentActivity());
        long sender = message.optLong("sender_id", 0);
        long recipient = message.optLong("recipient_id", 0);
        String prefix = sender == peerId && recipient != peerId ? peerTitle + ": " : "Вы: ";
        long messageId = message.optLong("id", 0);
        long repliedToId = message.optLong("reply_to_id", 0);
        String body = prefix + message.optString("body", "");
        String displayText = body;
        if (repliedToId > 0) {
            String quotedBody = message.optString("reply_to_body", "").trim();
            long quotedSender = message.optLong("reply_to_sender_id", 0);
            if (quotedBody.length() > 160) {
                quotedBody = quotedBody.substring(0, 160) + "…";
            }
            String quotePreview = quotedBody.isEmpty()
                    ? "Сообщение #" + repliedToId
                    : (quotedSender > 0 ? "@" + quotedSender + ": " : "") + quotedBody;
            displayText = "Цитата: " + quotePreview + "\n" + body;
        }
        long forwardedFromId = message.optLong("forwarded_from_telegram_id", 0);
        if (forwardedFromId > 0) {
            String forwardedName = message.optString("forwarded_from_display_name", "");
            if (forwardedName.isEmpty()) {
                forwardedName = "пользователя " + forwardedFromId;
            }
            displayText = "Переслано от " + forwardedName + "\n" + displayText;
        }
        long createdAt = message.optLong("created_at", 0);
        if (createdAt > 0) {
            String time = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
                    .format(new Date(createdAt * 1000L));
            displayText += "\n" + time;
            if (sender != peerId) {
                displayText += " · " + (messageId > 0 && messageId <= peerReadMessageId
                        ? "Прочитано"
                        : "Отправлено");
            }
        }
        String reactionSummary = message.optString("reaction_summary", "");
        if (!reactionSummary.isEmpty()) {
            displayText += "\n" + reactionSummary.replace(",", " · ").replace(":", " ");
        }
        JSONArray myReactions = message.optJSONArray("my_reactions");
        if (myReactions != null && myReactions.length() > 0) {
            StringBuilder mine = new StringBuilder("\nВаша реакция: ");
            for (int i = 0; i < myReactions.length(); i++) {
                if (i > 0) {
                    mine.append(" · ");
                }
                mine.append(myReactions.optString(i));
            }
            displayText += mine.toString();
        }
        CharSequence renderedMessage = formatInlineMarkup(displayText);
        row.setText(renderedMessage);
        if (renderedMessage instanceof Spannable
                && ((Spannable) renderedMessage).getSpans(
                        0, renderedMessage.length(), ClickableSpan.class
                ).length > 0) {
            row.setMovementMethod(LinkMovementMethod.getInstance());
        }
        row.setContentDescription("Удерживайте сообщение, чтобы ответить или поставить реакцию");
        if (messageId > 0) {
            row.setOnLongClickListener(view -> {
                showMessageActions(message);
                return true;
            });
        }
        row.setTextSize(14);
        row.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8),
                AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = AndroidUtilities.dp(4);
        messagesContainer.addView(row, params);

        String giftSerial = message.optString("gift_serial_number", "");
        if (!giftSerial.isEmpty()) {
            JSONObject giftAttributes = message.optJSONObject("gift_attributes");
            int giftLevel = Math.max(
                    message.optInt("gift_upgrade_level", 0),
                    giftAttributes == null ? 0 : giftAttributes.optInt("upgrade_level", 0)
            );
            String giftName = message.optString("gift_name", "Подарок WaysGram");
            LinearLayout giftCard = new LinearLayout(getParentActivity());
            giftCard.setOrientation(LinearLayout.VERTICAL);
            giftCard.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10),
                    AndroidUtilities.dp(14), AndroidUtilities.dp(10));
            GradientDrawable giftBackground = new GradientDrawable();
            giftBackground.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            giftBackground.setCornerRadius(AndroidUtilities.dp(14));
            giftCard.setBackground(giftBackground);

            TextView giftTitle = new TextView(getParentActivity());
            giftTitle.setText("🎁  " + giftName);
            giftTitle.setTextSize(16);
            giftTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            giftTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            giftCard.addView(giftTitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            TextView giftDetails = new TextView(getParentActivity());
            StringBuilder giftInfo = new StringBuilder("Коллекция WaysGram · уровень ")
                    .append(giftLevel)
                    .append("\nСерийный номер: ").append(giftSerial);
            String giftTelegramId = message.optString("gift_telegram_gift_id", "");
            if (!giftTelegramId.isEmpty()) {
                giftInfo.append("\nTelegram Gift ID: ").append(giftTelegramId);
            }
            if (giftAttributes != null
                    && "waysgram_internal".equals(giftAttributes.optString("upgrade_source", ""))) {
                giftInfo.append("\nУлучшение выполнено внутри WaysGram");
                long upgradedAt = giftAttributes.optLong("last_upgraded_at", 0);
                if (upgradedAt > 0) {
                    String upgradedAtText = DateFormat.getDateTimeInstance(
                            DateFormat.SHORT, DateFormat.SHORT
                    ).format(new Date(upgradedAt * 1000L));
                    giftInfo.append("\nДата улучшения: ").append(upgradedAtText);
                }
                Iterator<String> attributeKeys = giftAttributes.keys();
                while (attributeKeys.hasNext()) {
                    String key = attributeKeys.next();
                    if ("upgrade_level".equals(key) || "last_upgraded_at".equals(key)
                            || "upgrade_source".equals(key)) {
                        continue;
                    }
                    Object value = giftAttributes.opt(key);
                    if (value == null || value == JSONObject.NULL) {
                        continue;
                    }
                    String valueText = value.toString();
                    if (valueText.length() > 100) {
                        valueText = valueText.substring(0, 100) + "…";
                    }
                    giftInfo.append("\n").append(key).append(": ").append(valueText);
                }
            }
            giftDetails.setText(giftInfo.toString());
            giftDetails.setTextSize(12);
            giftDetails.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            giftDetails.setPadding(0, AndroidUtilities.dp(5), 0, 0);
            giftCard.addView(giftDetails, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            LinearLayout.LayoutParams giftParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            giftParams.bottomMargin = AndroidUtilities.dp(8);
            messagesContainer.addView(giftCard, giftParams);
        }

        String mediaId = message.optString("media_id", "");
        String mediaType = message.optString("media_type", "");
        if (mediaId.matches("[a-f0-9]{32}") && mediaType.startsWith("image/")) {
            ImageView image = new ImageView(getParentActivity());
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setMaxHeight(AndroidUtilities.dp(260));
            image.setMaxWidth(AndroidUtilities.dp(320));
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            imageParams.bottomMargin = AndroidUtilities.dp(8);
            messagesContainer.addView(image, imageParams);
            apiClient.downloadImage(mediaId, new WaysGramApiClient.ImageCallback() {
                @Override
                public void onSuccess(Bitmap bitmap) {
                    image.setImageBitmap(bitmap);
                }

                @Override
                public void onError(String message, int statusCode) {
                    image.setContentDescription("Изображение недоступно");
                }
            });
        } else if (mediaId.matches("[a-f0-9]{32}") && !mediaType.isEmpty()) {
            String mediaName = message.optString("media_name", "document");
            Button fileButton = new Button(getParentActivity());
            fileButton.setAllCaps(false);
            fileButton.setText("Сохранить документ: " + mediaName);
            messagesContainer.addView(fileButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            fileButton.setOnClickListener(view -> saveDocument(mediaId, mediaName, mediaType));
        }
    }

    private void showMessageActions(JSONObject message) {
        long messageId = message.optLong("id", 0);
        if (messageId <= 0) {
            return;
        }
        long senderId = message.optLong("sender_id", 0);
        long recipientId = message.optLong("recipient_id", 0);
        boolean ownMessage = senderId == recipientId || senderId != peerId;

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> actionCodes = new ArrayList<>();
        labels.add("Ответить");
        actionCodes.add(0);
        labels.add("Реакция");
        actionCodes.add(1);
        labels.add("Копировать");
        actionCodes.add(2);
        if (ownMessage) {
            labels.add("Редактировать");
            actionCodes.add(3);
        }
        labels.add("Удалить у меня");
        actionCodes.add(4);
        if (ownMessage) {
            labels.add("Удалить у всех");
            actionCodes.add(5);
        }
        labels.add("Переслать");
        actionCodes.add(6);
        labels.add("Закрепить");
        actionCodes.add(7);
        labels.add("Снять закрепление");
        actionCodes.add(8);

        new AlertDialog.Builder(getParentActivity())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < 0 || which >= actionCodes.size()) {
                        return;
                    }
                    int action = actionCodes.get(which);
                    if (action == 0) {
                        setReplyTarget(messageId);
                    } else if (action == 1) {
                        showReactionPicker(messageId);
                    } else if (action == 2) {
                        copyMessageText(message.optString("body", ""));
                    } else if (action == 3) {
                        editMessage(messageId, message.optString("body", ""));
                    } else if (action == 4) {
                        confirmDeleteMessageForMe(messageId);
                    } else if (action == 5) {
                        confirmDeleteMessage(messageId);
                    } else if (action == 6) {
                        chooseForwardRecipient(messageId);
                    } else if (action == 7) {
                        changePinnedMessage(messageId, true);
                    } else if (action == 8) {
                        changePinnedMessage(messageId, false);
                    }
                })
                .show();
    }


    private void showPinnedMessages() {
        statusView.setText("Загружаю закреплённые сообщения…");
        apiClient.get("/api/pins?scope=dm&peer_id=" + peerId, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONArray pinned = response.optJSONArray("pinned_messages");
                int count = pinned == null ? 0 : pinned.length();
                if (count == 0) {
                    statusView.setText("Закреплённых сообщений нет");
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Закреплённые сообщения")
                            .setMessage("В этой переписке пока нет закреплённых сообщений.")
                            .setPositiveButton("Закрыть", null)
                            .show();
                    return;
                }
                StringBuilder text = new StringBuilder();
                int visible = Math.min(count, 100);
                for (int i = 0; i < visible; i++) {
                    JSONObject item = pinned.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    if (text.length() > 0) {
                        text.append("\n\n");
                    }
                    text.append("#").append(item.optLong("message_id", 0));
                    long sender = item.optLong("sender_id", 0);
                    if (sender > 0) {
                        text.append(" · ").append(sender == peerId ? peerTitle : "Вы");
                    }
                    String body = item.optString("body", "");
                    if (body.length() > 280) {
                        body = body.substring(0, 280) + "…";
                    }
                    text.append("\n").append(body);
                }
                statusView.setText("Закреплённых сообщений: " + count);
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Закреплённые сообщения · " + count)
                        .setMessage(text.toString())
                        .setPositiveButton("Закрыть", null)
                        .show();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        });
    }

    private void changePinnedMessage(long messageId, boolean pin) {
        if (messageId <= 0) {
            return;
        }
        WaysGramApiClient.Callback callback = new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                statusView.setText(pin ? "Сообщение закреплено" : "Закрепление снято");
                loadMessages();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        };
        if (pin) {
            JSONObject payload = new JSONObject();
            try {
                payload.put("scope", "dm");
                payload.put("peer_id", peerId);
                payload.put("message_id", messageId);
            } catch (Exception exception) {
                statusView.setText("Не удалось подготовить закрепление");
                return;
            }
            apiClient.post("/api/pins", payload, callback);
        } else {
            apiClient.delete(
                    "/api/pins?scope=dm&peer_id=" + peerId + "&message_id=" + messageId,
                    callback
            );
        }
    }

    private void copyMessageText(String body) {
        if (body == null || body.isEmpty()) {
            statusView.setText("В сообщении нет текста для копирования");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getParentActivity()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            statusView.setText("Буфер обмена недоступен");
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("WaysGram message", body));
        statusView.setText("Текст сообщения скопирован");
    }

    private void showReactionPicker(long messageId) {
        String[] reactions = {"👍", "❤️", "😂", "😮", "😢", "🎉", "🙏"};
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Выберите реакцию")
                .setItems(reactions, (dialog, which) -> {
                    if (which >= 0 && which < reactions.length) {
                        sendReaction("/api/messages/" + messageId + "/reactions", reactions[which]);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void sendReaction(String path, String reaction) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("reaction", reaction);
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить реакцию");
            return;
        }
        statusView.setText("Сохраняю реакцию…");
        apiClient.post(path, payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                statusView.setText("Реакция " + (response.optString("status").equals("removed")
                        ? "удалена"
                        : "добавлена"));
                loadMessages();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        });
    }

    private void chooseForwardRecipient(long messageId) {
        statusView.setText("Загружаю список контактов…");
        apiClient.get("/api/contacts", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONArray contacts = response.optJSONArray("contacts");
                int count = contacts == null ? 0 : contacts.length();
                if (count == 0) {
                    statusView.setText("Сначала добавьте получателя в контакты");
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Пересылка сообщения")
                            .setMessage("В списке контактов пока нет получателей. Откройте раздел «Контакты» и добавьте пользователя.")
                            .setPositiveButton("Закрыть", null)
                            .show();
                    return;
                }
                String[] labels = new String[count];
                long[] userIds = new long[count];
                for (int i = 0; i < count; i++) {
                    JSONObject contact = contacts.optJSONObject(i);
                    if (contact == null) {
                        labels[i] = "Пользователь";
                        continue;
                    }
                    userIds[i] = contact.optLong("id", 0);
                    String name = contact.optString("display_name", "");
                    String username = contact.optString("username", "");
                    if (name.isEmpty()) {
                        name = username.isEmpty() ? "Пользователь " + userIds[i] : username;
                    }
                    labels[i] = username.isEmpty() ? name : name + " · @" + username;
                }
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Переслать кому?")
                        .setItems(labels, (dialog, which) -> {
                            if (which >= 0 && which < userIds.length && userIds[which] > 0) {
                                forwardMessage(messageId, userIds[which], labels[which]);
                            }
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
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

    private void forwardMessage(long messageId, long recipientId, String recipientLabel) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("recipient_id", recipientId);
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить пересылку");
            return;
        }
        String keyScope = messageId + ":" + recipientId;
        String idempotencyKey = forwardIdempotencyKeys.computeIfAbsent(
                keyScope, ignored -> "wg-forward-" + UUID.randomUUID()
        );
        statusView.setText("Пересылаю сообщение…");
        apiClient.post("/api/messages/" + messageId + "/forward", payload, idempotencyKey,
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        forwardIdempotencyKeys.remove(keyScope);
                        statusView.setText("Сообщение переслано: " + recipientLabel);
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        statusView.setText(message);
                    }
                });
    }

    private void editMessage(long messageId, String currentBody) {
        EditText editor = new EditText(getParentActivity());
        editor.setText(currentBody);
        editor.setSelection(editor.length());
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Редактировать сообщение")
                .setView(editor)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String body = editor.getText() == null ? "" : editor.getText().toString().trim();
                    if (body.length() == 0 || body.length() > 4000) {
                        statusView.setText("Сообщение должно содержать от 1 до 4000 символов");
                        return;
                    }
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("body", body);
                    } catch (Exception exception) {
                        statusView.setText("Не удалось подготовить изменение");
                        return;
                    }
                    apiClient.put("/api/messages/" + messageId, payload, new WaysGramApiClient.Callback() {
                        @Override
                        public void onSuccess(JSONObject response) {
                            statusView.setText("Сообщение изменено");
                            loadMessages();
                        }

                        @Override
                        public void onError(String message, int statusCode) {
                            statusView.setText(message);
                        }
                    });
                })
                .show();
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Очистить историю у себя?")
                .setMessage("Все сообщения исчезнут только из вашей истории. У собеседника они останутся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить", (dialog, which) ->
                        apiClient.delete("/api/conversations/" + peerId + "/messages?for=me",
                                new WaysGramApiClient.Callback() {
                                    @Override
                                    public void onSuccess(JSONObject response) {
                                        long count = response.optLong("deleted_count", 0);
                                        statusView.setText("Удалено из вашей истории: " + count);
                                        loadMessages();
                                    }

                                    @Override
                                    public void onError(String message, int statusCode) {
                                        statusView.setText(message);
                                        if (statusCode == 401 && getParentActivity() != null) {
                                            WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                                            presentFragment(new WaysGramLoginFragment(), true);
                                        }
                                    }
                                }))
                .show();
    }

    private void confirmDeleteMessageForMe(long messageId) {
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Удалить у себя?")
                .setMessage("Сообщение исчезнет только из вашей истории. У собеседника оно останется.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) ->
                        apiClient.delete("/api/messages/" + messageId + "?for=me",
                                new WaysGramApiClient.Callback() {
                                    @Override
                                    public void onSuccess(JSONObject response) {
                                        statusView.setText("Сообщение удалено у вас");
                                        loadMessages();
                                    }

                                    @Override
                                    public void onError(String message, int statusCode) {
                                        statusView.setText(message);
                                        if (statusCode == 401 && getParentActivity() != null) {
                                            WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                                            presentFragment(new WaysGramLoginFragment(), true);
                                        }
                                    }
                                }))
                .show();
    }

    private void confirmDeleteMessage(long messageId) {
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Удалить сообщение?")
                .setMessage("Сообщение будет удалено у участников переписки.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) ->
                        apiClient.delete("/api/messages/" + messageId, new WaysGramApiClient.Callback() {
                            @Override
                            public void onSuccess(JSONObject response) {
                                statusView.setText("Сообщение удалено");
                                loadMessages();
                            }

                            @Override
                            public void onError(String message, int statusCode) {
                                statusView.setText(message);
                            }
                        }))
                .show();
    }

    private void setReplyTarget(long messageId) {
        replyToMessageId = messageId;
        statusView.setText("Ответ на сообщение #" + messageId + " · нажмите сюда, чтобы отменить");
        statusView.setOnClickListener(view -> clearReplyTarget());
    }

    private void clearReplyTarget() {
        replyToMessageId = 0;
        statusView.setOnClickListener(null);
        statusView.setText("Ответ отменён");
    }

    private void saveDocument(String mediaId, String mediaName, String mediaType) {
        if (mediaId == null || !mediaId.matches("[a-f0-9]{32}")) {
            statusView.setText("Некорректный ID документа.");
            return;
        }
        pendingSaveMediaId = mediaId;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mediaType == null || mediaType.length() == 0 ? "application/octet-stream" : mediaType);
        intent.putExtra(Intent.EXTRA_TITLE, mediaName == null || mediaName.length() == 0 ? "document" : mediaName);
        startActivityForResult(intent, REQUEST_SAVE_DOCUMENT);
    }

    private void sendDocument(String mediaId) {
        if (mediaId == null || !mediaId.matches("[a-f0-9]{32}")) {
            statusView.setText("Сервер вернул некорректный ID документа.");
            return;
        }
        String caption = messageInput.getText() == null ? "" : messageInput.getText().toString().trim();
        JSONObject payload = new JSONObject();
        try {
            payload.put("recipient_id", peerId);
            payload.put("body", caption);
            payload.put("media_id", mediaId);
            payload.put("silent", isSilentSendEnabled());
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить документ к отправке.");
            return;
        }
        statusView.setText("Отправляю документ…");
        String requestFingerprint = payload.toString();
        String idempotencyKey = messageIdempotencyKey(requestFingerprint);
        apiClient.post("/api/messages", payload, idempotencyKey, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                messageIdempotencyKeys.remove(requestFingerprint);
                clearChatDraft();
                resetSilentSendMode();
                statusView.setText("Документ отправлен.");
                loadMessages();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        });
    }

    private String messageIdempotencyKey(String fingerprint) {
        return messageIdempotencyKeys.computeIfAbsent(
                fingerprint, ignored -> "wg-message-" + UUID.randomUUID()
        );
    }

    private boolean isSilentSendEnabled() {
        return silentSendCheckBox != null && silentSendCheckBox.isChecked();
    }

    private void resetSilentSendMode() {
        if (silentSendCheckBox != null) {
            silentSendCheckBox.setChecked(false);
        }
    }

    private void clearChatDraft() {
        if (cloudDraftSync != null) {
            cloudDraftSync.clear();
        } else if (messageInput != null) {
            messageInput.setText("");
        }
    }

    private void setMessageSendInFlight(boolean inFlight) {
        messageSendInFlight = inFlight;
        if (sendButton != null) {
            sendButton.setEnabled(!inFlight);
        }
    }

    private void sendMessage() {
        if (messageSendInFlight) {
            return;
        }
        String body = messageInput.getText() == null ? "" : messageInput.getText().toString().trim();
        if (body.length() == 0 || body.length() > 4000) {
            statusView.setText("Сообщение должно содержать от 1 до 4000 символов");
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("recipient_id", peerId);
            payload.put("body", body);
            payload.put("silent", isSilentSendEnabled());
            if (replyToMessageId > 0) {
                payload.put("reply_to_id", replyToMessageId);
            }
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить сообщение");
            return;
        }
        statusView.setText("Отправляю…");
        setMessageSendInFlight(true);
        String requestFingerprint = payload.toString();
        String idempotencyKey = messageIdempotencyKey(requestFingerprint);
        apiClient.post("/api/messages", payload, idempotencyKey, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                setMessageSendInFlight(false);
                messageIdempotencyKeys.remove(requestFingerprint);
                clearChatDraft();
                resetSilentSendMode();
                replyToMessageId = 0;
                statusView.setOnClickListener(null);
                statusView.setText("Сообщение отправлено");
                loadMessages();
            }

            @Override
            public void onError(String message, int statusCode) {
                setMessageSendInFlight(false);
                statusView.setText(message);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        isScreenResumed = true;
        if (typingIndicatorController != null) {
            typingIndicatorController.startPolling();
        }
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.removeCallbacks(eventPollRetryRunnable);
        if (messageEventsReady) {
            pollNewMessages();
            checkMessageEvents();
        } else {
            initializeMessageEventCursor();
        }
    }

    @Override
    public void onPause() {
        isScreenResumed = false;
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.removeCallbacks(eventPollRetryRunnable);
        if (typingIndicatorController != null) {
            typingIndicatorController.stopPolling();
        }
        if (cloudDraftSync != null) {
            cloudDraftSync.flush();
        }
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        isScreenResumed = false;
        pollHandler.removeCallbacks(pollRunnable);
        if (typingIndicatorController != null) {
            typingIndicatorController.close();
            typingIndicatorController = null;
        }
        if (cloudDraftSync != null) {
            cloudDraftSync.close();
            cloudDraftSync = null;
        }
        forwardIdempotencyKeys.clear();
        messageIdempotencyKeys.clear();
        if (apiClient != null) {
            apiClient.close();
            apiClient = null;
        }
        super.onFragmentDestroy();
    }
}
