package org.telegram.messenger.waysgram;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
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
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

/** WaysGram direct text conversation backed by /api/messages. */
public final class WaysGramChatFragment extends BaseFragment {
    private static final int REQUEST_ATTACHMENT = 7101;
    private static final int REQUEST_SAVE_DOCUMENT = 7103;
    private final long peerId;
    private final String peerTitle;
    private final boolean savedMessages;

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
    private final Map<Long, View> messageViews = new HashMap<>();
    private final ArrayList<MediaPlayer> activeAudioPlayers = new ArrayList<>();
    private final ArrayList<VideoView> activeVideoViews = new ArrayList<>();
    private long pendingJumpMessageId = 0;
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
    private final ArrayList<Uri> pendingAttachments = new ArrayList<>();
    private final ArrayList<String> pendingAttachmentMediaIds = new ArrayList<>();
    private String pendingAttachmentCaption = "";
    private boolean pendingAttachmentSilent = false;
    private boolean pendingAttachmentMakeAlbum = false;
    private long pendingAttachmentReplyToMessageId = 0;
    private int pendingAttachmentIndex = 0;
    private int pendingAttachmentSent = 0;
    private final Map<String, LinearLayout> mediaAlbumContainers = new HashMap<>();

    public WaysGramChatFragment(long peerId, String peerTitle) {
        this(peerId, peerTitle, false);
    }

    public WaysGramChatFragment(long peerId, String peerTitle, boolean savedMessages) {
        if (peerId <= 0) {
            throw new IllegalArgumentException("Invalid WaysGram peer ID");
        }
        this.peerId = peerId;
        this.peerTitle = peerTitle == null ? "" : peerTitle;
        this.savedMessages = savedMessages;
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
        if (!savedMessages) {
            typingIndicatorController = new WaysGramTypingIndicatorController(
                    apiClient, "dm", peerId, messageInput, typingView
            );
        }
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

        Button scheduleButton = new Button(context);
        scheduleButton.setText("Отложить отправку");
        scheduleButton.setAllCaps(false);
        root.addView(scheduleButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scheduleButton.setOnClickListener(view -> showScheduleMessagePicker());

        Button scheduledListButton = new Button(context);
        scheduledListButton.setText("Отложенные сообщения");
        scheduledListButton.setAllCaps(false);
        root.addView(scheduledListButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scheduledListButton.setOnClickListener(view -> loadScheduledMessages());

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

    private void showScheduleMessagePicker() {
        String body = messageInput == null || messageInput.getText() == null
                ? "" : messageInput.getText().toString().trim();
        if (body.isEmpty() || body.length() > 4000) {
            statusView.setText("Для отложенной отправки введите текст длиной до 4000 символов.");
            return;
        }
        Calendar now = Calendar.getInstance();
        Calendar maximum = (Calendar) now.clone();
        maximum.add(Calendar.DAY_OF_YEAR, 30);
        DatePickerDialog dateDialog = new DatePickerDialog(
                getParentActivity(),
                (picker, year, month, day) -> {
                    Calendar selected = Calendar.getInstance();
                    selected.set(Calendar.YEAR, year);
                    selected.set(Calendar.MONTH, month);
                    selected.set(Calendar.DAY_OF_MONTH, day);
                    Calendar current = Calendar.getInstance();
                    TimePickerDialog timeDialog = new TimePickerDialog(
                            getParentActivity(),
                            (timePicker, hour, minute) -> {
                                selected.set(Calendar.HOUR_OF_DAY, hour);
                                selected.set(Calendar.MINUTE, minute);
                                selected.set(Calendar.SECOND, 0);
                                selected.set(Calendar.MILLISECOND, 0);
                                showScheduleRepeatOptions(
                                        selected.getTimeInMillis() / 1000L
                                );
                            },
                            current.get(Calendar.HOUR_OF_DAY),
                            current.get(Calendar.MINUTE),
                            true
                    );
                    timeDialog.show();
                },
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH)
        );
        dateDialog.getDatePicker().setMinDate(now.getTimeInMillis());
        dateDialog.getDatePicker().setMaxDate(maximum.getTimeInMillis());
        dateDialog.show();
    }

    private void showScheduleRepeatOptions(long scheduledAt) {
        String[] labels = {"Не повторять", "Каждый день", "Каждую неделю"};
        long[] intervals = {0, 86400, 604800};
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Повторять отправку?")
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < intervals.length) {
                        scheduleMessage(scheduledAt, intervals[which]);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void scheduleMessage(long scheduledAt, long repeatIntervalSeconds) {
        if (apiClient == null || messageInput == null) {
            return;
        }
        String body = messageInput.getText() == null
                ? "" : messageInput.getText().toString().trim();
        if (body.isEmpty() || body.length() > 4000) {
            statusView.setText("Сообщение должно содержать от 1 до 4000 символов.");
            return;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (scheduledAt < now + 10 || scheduledAt > now + 30L * 24L * 60L * 60L) {
            statusView.setText("Выберите время не раньше чем через 10 секунд и не позже чем через 30 дней.");
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("recipient_id", peerId);
            payload.put("body", body);
            payload.put("scheduled_at", scheduledAt);
            payload.put("repeat_interval_seconds", repeatIntervalSeconds);
            payload.put("time_zone", TimeZone.getDefault().getID());
            payload.put("silent", isSilentSendEnabled());
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить отложенное сообщение.");
            return;
        }
        String fingerprint = payload.toString();
        String idempotencyKey = messageIdempotencyKey(fingerprint);
        statusView.setText("Сохраняю отложенное сообщение…");
        apiClient.post("/api/messages/schedule", payload, idempotencyKey,
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        messageIdempotencyKeys.remove(fingerprint);
                        clearChatDraft();
                        resetSilentSendMode();
                        Date scheduledDate = new Date(
                                response.optLong("scheduled_at", scheduledAt) * 1000L
                        );
                        String formatted = DateFormat.getDateTimeInstance(
                                DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()
                        ).format(scheduledDate);
                        String repeatText = repeatIntervalSeconds == 86400
                                ? " · ежедневно"
                                : repeatIntervalSeconds == 604800
                                    ? " · еженедельно" : "";
                        statusView.setText("Сообщение запланировано на " + formatted + repeatText);
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

    private void loadScheduledMessages() {
        if (apiClient == null) {
            return;
        }
        statusView.setText("Загружаю отложенные сообщения…");
        apiClient.get("/api/messages/scheduled?with=" + peerId,
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        JSONArray items = response.optJSONArray("scheduled_messages");
                        int count = items == null ? 0 : items.length();
                        if (count == 0) {
                            statusView.setText("Отложенных сообщений нет.");
                            new AlertDialog.Builder(getParentActivity())
                                    .setTitle("Отложенные сообщения")
                                    .setMessage("Для этой переписки нет ожидающих или неотправленных сообщений.")
                                    .setPositiveButton("Закрыть", null)
                                    .show();
                            return;
                        }
                        String[] labels = new String[count];
                        long[] ids = new long[count];
                        boolean[] pending = new boolean[count];
                        for (int i = 0; i < count; i++) {
                            JSONObject item = items.optJSONObject(i);
                            if (item == null) {
                                labels[i] = "Недоступная запись";
                                continue;
                            }
                            ids[i] = item.optLong("id", 0);
                            pending[i] = "pending".equals(item.optString("status", ""));
                            Date date = new Date(item.optLong("scheduled_at", 0) * 1000L);
                            long repeatInterval = item.optLong("repeat_interval_seconds", 0);
                            String timeZoneId = item.optString("time_zone", "UTC");
                            DateFormat dateFormatter = DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()
                            );
                            if (repeatInterval > 0 && dateFormatter instanceof SimpleDateFormat) {
                                ((SimpleDateFormat) dateFormatter).setTimeZone(
                                        TimeZone.getTimeZone(timeZoneId)
                                );
                            }
                            String dateText = dateFormatter.format(date);
                            String body = item.optString("body", "");
                            if (body.length() > 70) {
                                body = body.substring(0, 70) + "…";
                            }
                            String repeatLabel = repeatInterval == 86400
                                    ? " · ежедневно"
                                    : repeatInterval == 604800 ? " · еженедельно" : "";
                            if (repeatInterval > 0 && !timeZoneId.isEmpty()) {
                                repeatLabel += " · " + timeZoneId;
                            }
                            labels[i] = (pending[i] ? "Ожидает · " : "Ошибка · ")
                                    + dateText + repeatLabel + "\n" + body;
                            String error = item.optString("error", "");
                            if (!pending[i] && !error.isEmpty()) {
                                labels[i] += "\n" + error;
                            }
                        }
                        new AlertDialog.Builder(getParentActivity())
                                .setTitle("Отложенные сообщения")
                                .setItems(labels, (dialog, which) -> {
                                    if (which >= 0 && which < ids.length && ids[which] > 0) {
                                        if (pending[which]) {
                                            confirmCancelScheduledMessage(ids[which]);
                                        } else {
                                            statusView.setText("Отправка не выполнена: "
                                                    + items.optJSONObject(which).optString("error", ""));
                                        }
                                    }
                                })
                                .setPositiveButton("Закрыть", null)
                                .show();
                        statusView.setText("Отложенных сообщений: " + count);
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

    private void confirmCancelScheduledMessage(long scheduledId) {
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Отменить отправку?")
                .setMessage("Запланированное сообщение не будет отправлено.")
                .setNegativeButton("Назад", null)
                .setPositiveButton("Отменить", (dialog, which) ->
                        apiClient.delete("/api/messages/scheduled/" + scheduledId,
                                new WaysGramApiClient.Callback() {
                                    @Override
                                    public void onSuccess(JSONObject response) {
                                        statusView.setText("Отложенная отправка отменена.");
                                        loadScheduledMessages();
                                    }

                                    @Override
                                    public void onError(String message, int statusCode) {
                                        statusView.setText(message);
                                    }
                                }))
                .show();
    }

    private void showFormattingOptions() {
        String[] labels = {
                "Жирный",
                "Курсив",
                "Подчёркнутый",
                "Зачёркнутый",
                "Спойлер",
                "Моноширинный",
                "Цитата"
        };
        String[] opening = {
                "<b>", "<i>", "<u>", "<s>", "<tg-spoiler>", "<code>", "<blockquote>"
        };
        String[] closing = {
                "</b>", "</i>", "</u>", "</s>", "</tg-spoiler>", "</code>", "</blockquote>"
        };
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Форматирование текста")
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < opening.length) {
                        wrapSelectedText(opening[which], closing[which]);
                    }
                })
                .show();
    }

    private void wrapSelectedText(String opening, String closing) {
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
        editable.replace(start, end, opening + selected + closing);
        if (selected.isEmpty()) {
            messageInput.setSelection(start + opening.length());
        } else {
            messageInput.setSelection(
                    start + opening.length(),
                    start + opening.length() + selected.length()
            );
        }
        messageInput.requestFocus();
    }

    private CharSequence formatInlineMarkup(String source) {
        SpannableStringBuilder formatted = new SpannableStringBuilder(
                source == null ? "" : source
        );
        applyTagMarkup(formatted, "<b>", "</b>", 0);
        applyTagMarkup(formatted, "<i>", "</i>", 1);
        applyTagMarkup(formatted, "<u>", "</u>", 2);
        applyTagMarkup(formatted, "<s>", "</s>", 3);
        applyTagMarkup(formatted, "<tg-spoiler>", "</tg-spoiler>", 4);
        applyTagMarkup(formatted, "<code>", "</code>", 5);
        applyTagMarkup(formatted, "<blockquote>", "</blockquote>", 6);

        // Keep messages created by earlier WaysGram builds readable.
        applyInlineMarkup(formatted, "**", 0);
        applyInlineMarkup(formatted, "*", 1);
        applyInlineMarkup(formatted, "--", 2);
        applyInlineMarkup(formatted, "~~", 3);
        applyInlineMarkup(formatted, "||", 4);
        applyInlineMarkup(formatted, "`", 5);
        return formatted;
    }

    private void applyTagMarkup(
            SpannableStringBuilder text,
            String opening,
            String closing,
            int style
    ) {
        Pattern pattern = Pattern.compile(
                Pattern.quote(opening) + "(.+?)" + Pattern.quote(closing),
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
                case 6:
                    span = new android.text.style.QuoteSpan();
                    break;
                default:
                    continue;
            }
            text.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.delete(end, end + closing.length());
            text.delete(start - opening.length(), start);
        }
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


    private void applyMentionLinks(SpannableStringBuilder text) {
        Pattern pattern = Pattern.compile(
                "(?<![A-Za-z0-9_])@([A-Za-z][A-Za-z0-9_]{2,31})"
        );
        Matcher matcher = pattern.matcher(text);
        ArrayList<int[]> ranges = new ArrayList<>();
        while (matcher.find()) {
            ranges.add(new int[]{matcher.start(), matcher.end()});
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int start = ranges.get(i)[0];
            int end = ranges.get(i)[1];
            String token = text.subSequence(start + 1, end).toString();
            text.setSpan(new ClickableSpan() {
                @Override
                public void updateDrawState(android.text.TextPaint paint) {
                    paint.setColor(android.graphics.Color.rgb(50, 130, 220));
                    paint.setUnderlineText(true);
                }

                @Override
                public void onClick(View widget) {
                    openMentionProfile(token);
                }
            }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void applyHashtagLinks(SpannableStringBuilder text) {
        Pattern pattern = Pattern.compile(
                "(?<![\\p{L}\\p{N}_])#[\\p{L}\\p{N}_]{1,64}"
        );
        Matcher matcher = pattern.matcher(text);
        ArrayList<int[]> ranges = new ArrayList<>();
        while (matcher.find()) {
            ranges.add(new int[]{matcher.start(), matcher.end()});
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int start = ranges.get(i)[0];
            int end = ranges.get(i)[1];
            String hashtag = text.subSequence(start, end).toString();
            text.setSpan(new ClickableSpan() {
                @Override
                public void updateDrawState(android.text.TextPaint paint) {
                    paint.setColor(android.graphics.Color.rgb(50, 130, 220));
                    paint.setUnderlineText(true);
                }

                @Override
                public void onClick(View widget) {
                    searchMessages(hashtag);
                }
            }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void openMentionProfile(String username) {
        if (apiClient == null) {
            return;
        }
        statusView.setText("Открываю профиль @" + username + "…");
        apiClient.get("/api/users?q=" + Uri.encode(username),
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        JSONArray users = response.optJSONArray("users");
                        int count = users == null ? 0 : users.length();
                        for (int i = 0; i < count; i++) {
                            JSONObject user = users.optJSONObject(i);
                            if (user != null && username.equalsIgnoreCase(
                                    user.optString("username", "")
                            )) {
                                long userId = user.optLong("id", 0);
                                if (userId > 0) {
                                    presentFragment(
                                            new WaysGramUserProfileFragment(userId), false
                                    );
                                    return;
                                }
                            }
                        }
                        statusView.setText("Пользователь @" + username + " не найден.");
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

    private void searchMessages(String query) {
        String term = query == null ? "" : query.trim();
        if (term.isEmpty()) {
            statusView.setText("Введите текст для поиска");
            return;
        }
        if (term.length() > 128) {
            statusView.setText("Запрос не должен превышать 128 символов");
            return;
        }
        statusView.setText("Ищу сообщения…");
        apiClient.get(
                "/api/messages/search?with=" + peerId + "&q=" + Uri.encode(term),
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        JSONArray messages = response.optJSONArray("messages");
                        int count = messages == null ? 0 : messages.length();
                        if (count == 0) {
                            statusView.setText("Совпадений не найдено");
                            new AlertDialog.Builder(getParentActivity())
                                    .setTitle("Результаты поиска")
                                    .setMessage("По запросу «" + term + "» ничего не найдено.")
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
                                    ? DateFormat.getDateTimeInstance(
                                            DateFormat.SHORT, DateFormat.SHORT
                                    ).format(new Date(createdAt * 1000L))
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
                            results.append("\n\nПоказаны первые ")
                                    .append(displayCount).append(" из ")
                                    .append(count).append(" совпадений.");
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
    }

    private void showMessageSearchDialog() {
        EditText queryInput = new EditText(getParentActivity());
        queryInput.setSingleLine(true);
        queryInput.setHint("Текст сообщения или #хэштег");
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Поиск по переписке")
                .setView(queryInput)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Найти", (dialog, which) ->
                        searchMessages(queryInput.getText() == null
                                ? "" : queryInput.getText().toString()))
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
                messageViews.clear();
                mediaAlbumContainers.clear();
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
                messageScrollView.post(() -> continuePendingMessageJump());
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
                            continuePendingMessageJump();
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
                            continuePendingMessageJump();
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

    private void jumpToMessage(long messageId) {
        if (messageId <= 0 || messageScrollView == null) {
            return;
        }
        pendingJumpMessageId = messageId;
        statusView.setText("Ищу исходное сообщение в истории…");
        continuePendingMessageJump();
    }

    private void continuePendingMessageJump() {
        if (pendingJumpMessageId <= 0 || historyLoading) {
            return;
        }
        View target = messageViews.get(pendingJumpMessageId);
        if (target != null) {
            pendingJumpMessageId = 0;
            messageScrollView.post(() -> messageScrollView.smoothScrollTo(
                    0, Math.max(0, target.getTop() - AndroidUtilities.dp(72))
            ));
            statusView.setText("Исходное сообщение найдено");
            return;
        }
        if (oldestMessageId > 0) {
            loadOlderMessages();
            return;
        }
        pendingJumpMessageId = 0;
        statusView.setText("Исходное сообщение отсутствует в истории");
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
        if (messageSendInFlight) {
            statusView.setText("Дождитесь завершения текущей отправки.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "image/*", "video/mp4", "video/webm", "video/quicktime",
                "video/x-matroska", "audio/mpeg", "audio/mp4", "audio/ogg",
                "audio/wav", "application/pdf", "text/plain", "text/csv",
                "application/zip"
        });
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
        ArrayList<Uri> selectedUris = new ArrayList<>();
        ClipData selectedItems = data.getClipData();
        if (selectedItems != null && selectedItems.getItemCount() > 0) {
            for (int i = 0; i < selectedItems.getItemCount(); i++) {
                Uri selectedUri = selectedItems.getItemAt(i).getUri();
                if (selectedUri != null && !selectedUris.contains(selectedUri)) {
                    selectedUris.add(selectedUri);
                }
            }
        } else {
            Uri selectedUri = data.getData();
            if (selectedUri != null) {
                selectedUris.add(selectedUri);
            }
        }
        if (selectedUris.isEmpty()) {
            statusView.setText("Не удалось получить выбранные файлы.");
            return;
        }
        startAttachmentBatch(selectedUris);
    }

    private void startAttachmentBatch(ArrayList<Uri> selectedUris) {
        if (messageSendInFlight || selectedUris == null || selectedUris.isEmpty()) {
            return;
        }
        pendingAttachments.clear();
        pendingAttachments.addAll(selectedUris);
        pendingAttachmentMediaIds.clear();
        pendingAttachmentCaption = messageInput.getText() == null
                ? ""
                : messageInput.getText().toString().trim();
        pendingAttachmentSilent = isSilentSendEnabled();
        pendingAttachmentReplyToMessageId = replyToMessageId;
        pendingAttachmentMakeAlbum = selectedUris.size() >= 2 && selectedUris.size() <= 10;
        if (pendingAttachmentMakeAlbum && getParentActivity() != null) {
            for (Uri uri : selectedUris) {
                String type = getParentActivity().getContentResolver().getType(uri);
                if (type == null || !(type.startsWith("image/") || type.startsWith("video/"))) {
                    pendingAttachmentMakeAlbum = false;
                    break;
                }
            }
        }
        pendingAttachmentIndex = 0;
        pendingAttachmentSent = 0;
        setMessageSendInFlight(true);
        statusView.setText(selectedUris.size() == 1
                ? "Подготавливаю вложение…"
                : "Подготавливаю вложения: " + selectedUris.size());
        uploadNextAttachment();
    }

    private void uploadNextAttachment() {
        if (pendingAttachmentIndex >= pendingAttachments.size()) {
            finishAttachmentBatch();
            return;
        }
        if (getParentActivity() == null || apiClient == null) {
            failAttachmentBatch("Не удалось продолжить отправку вложений.");
            return;
        }

        Uri uri = pendingAttachments.get(pendingAttachmentIndex);
        String mimeType = getParentActivity().getContentResolver().getType(uri);
        int currentIndex = pendingAttachmentIndex;
        int total = pendingAttachments.size();
        statusView.setText("Загружаю вложение " + (currentIndex + 1) + " из " + total + "…");

        WaysGramApiClient.Callback uploadCallback = new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                sendUploadedAttachment(response, mimeType, currentIndex);
            }

            @Override
            public void onError(String message, int statusCode) {
                failAttachmentBatch(message);
            }
        };
        if (mimeType != null && mimeType.startsWith("image/")) {
            apiClient.uploadImage(getParentActivity().getContentResolver(), uri, uploadCallback);
        } else {
            apiClient.uploadDocument(getParentActivity().getContentResolver(), uri, uploadCallback);
        }
    }

    private void sendUploadedAttachment(JSONObject response, String mimeType, int index) {
        String mediaId = response == null ? "" : response.optString("id", "");
        if (!mediaId.matches("[a-f0-9]{32}")) {
            failAttachmentBatch("Сервер вернул некорректный ID вложения.");
            return;
        }

        if (pendingAttachmentMakeAlbum) {
            pendingAttachmentMediaIds.add(mediaId);
            pendingAttachmentIndex++;
            if (pendingAttachmentIndex >= pendingAttachments.size()) {
                sendAttachmentAlbum();
            } else {
                uploadNextAttachment();
            }
            return;
        }

        JSONObject payload = new JSONObject();
        String body = index == 0 ? pendingAttachmentCaption : "";
        if (body.isEmpty() && mimeType != null && mimeType.startsWith("image/")) {
            body = "Изображение";
        }
        try {
            payload.put("recipient_id", peerId);
            payload.put("body", body);
            payload.put("media_id", mediaId);
            payload.put("silent", pendingAttachmentSilent);
        } catch (Exception exception) {
            failAttachmentBatch("Не удалось подготовить вложение к отправке.");
            return;
        }

        String requestFingerprint = payload.toString();
        String idempotencyKey = messageIdempotencyKey(requestFingerprint);
        statusView.setText("Отправляю вложение " + (index + 1) + " из "
                + pendingAttachments.size() + "…");
        apiClient.post("/api/messages", payload, idempotencyKey, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject sentMessage) {
                messageIdempotencyKeys.remove(requestFingerprint);
                pendingAttachmentSent++;
                pendingAttachmentIndex++;
                uploadNextAttachment();
            }

            @Override
            public void onError(String message, int statusCode) {
                failAttachmentBatch(message);
            }
        });
    }

    private void sendAttachmentAlbum() {
        if (pendingAttachmentMediaIds.size() < 2 || pendingAttachmentMediaIds.size() > 10) {
            failAttachmentBatch("Некорректное количество файлов для альбома.");
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("recipient_id", peerId);
            payload.put("body", pendingAttachmentCaption);
            payload.put("silent", pendingAttachmentSilent);
            if (pendingAttachmentReplyToMessageId > 0) {
                payload.put("reply_to_id", pendingAttachmentReplyToMessageId);
            }
            JSONArray mediaIds = new JSONArray();
            for (String mediaId : pendingAttachmentMediaIds) {
                mediaIds.put(mediaId);
            }
            payload.put("media_ids", mediaIds);
        } catch (Exception exception) {
            failAttachmentBatch("Не удалось подготовить медиаальбом к отправке.");
            return;
        }

        String requestFingerprint = payload.toString();
        String idempotencyKey = messageIdempotencyKey(requestFingerprint);
        statusView.setText("Отправляю альбом из " + pendingAttachmentMediaIds.size() + " файлов…");
        apiClient.post("/api/messages/album", payload, idempotencyKey,
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        messageIdempotencyKeys.remove(requestFingerprint);
                        int sentCount = pendingAttachmentMediaIds.size();
                        String sentCaption = pendingAttachmentCaption;
                        boolean canClearDraft = messageInput == null || messageInput.getText() == null
                                || messageInput.getText().toString().trim().equals(sentCaption);
                        long sentReplyTarget = pendingAttachmentReplyToMessageId;
                        resetAttachmentBatchState();
                        setMessageSendInFlight(false);
                        if (canClearDraft) {
                            clearChatDraft();
                        }
                        resetSilentSendMode();
                        if (replyToMessageId == sentReplyTarget) {
                            replyToMessageId = 0;
                            statusView.setOnClickListener(null);
                        }
                        statusView.setText("Медиаальбом отправлен · " + sentCount + " файлов");
                        loadMessages();
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        failAttachmentBatch(message);
                    }
                });
    }

    private void resetAttachmentBatchState() {
        pendingAttachments.clear();
        pendingAttachmentMediaIds.clear();
        pendingAttachmentCaption = "";
        pendingAttachmentSilent = false;
        pendingAttachmentMakeAlbum = false;
        pendingAttachmentReplyToMessageId = 0;
        pendingAttachmentIndex = 0;
        pendingAttachmentSent = 0;
    }

    private void finishAttachmentBatch() {
        int sentCount = pendingAttachmentSent;
        String sentCaption = pendingAttachmentCaption;
        boolean canClearDraft = messageInput == null || messageInput.getText() == null
                || messageInput.getText().toString().trim().equals(sentCaption);
        resetAttachmentBatchState();
        setMessageSendInFlight(false);
        if (canClearDraft) {
            clearChatDraft();
        }
        resetSilentSendMode();
        statusView.setText(sentCount == 1
                ? "Вложение отправлено."
                : "Отправлено вложений: " + sentCount);
        loadMessages();
    }

    private void failAttachmentBatch(String message) {
        int sentCount = pendingAttachmentSent;
        int totalCount = pendingAttachments.size();
        resetAttachmentBatchState();
        setMessageSendInFlight(false);
        if (sentCount > 0) {
            statusView.setText("Отправлено " + sentCount + " из " + totalCount
                    + ". Остальные файлы не отправлены: " + message);
            loadMessages();
        } else {
            statusView.setText(message == null || message.trim().isEmpty()
                    ? "Не удалось отправить вложения."
                    : message);
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
        String albumId = message.optString("media_album_id", "");
        boolean isAlbum = albumId.matches("[a-f0-9]{32}");
        LinearLayout messageTarget = messagesContainer;
        if (isAlbum) {
            messageTarget = mediaAlbumContainers.get(albumId);
            if (messageTarget == null) {
                messageTarget = new LinearLayout(getParentActivity());
                messageTarget.setOrientation(LinearLayout.VERTICAL);
                messageTarget.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8),
                        AndroidUtilities.dp(8), AndroidUtilities.dp(8));
                GradientDrawable albumBackground = new GradientDrawable();
                albumBackground.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                albumBackground.setCornerRadius(AndroidUtilities.dp(14));
                messageTarget.setBackground(albumBackground);
                TextView albumLabel = new TextView(getParentActivity());
                albumLabel.setText("Медиаальбом · " + message.optInt("media_album_count", 0) + " файлов");
                albumLabel.setTextSize(11);
                albumLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                messageTarget.addView(albumLabel, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                LinearLayout.LayoutParams albumParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                albumParams.bottomMargin = AndroidUtilities.dp(8);
                messagesContainer.addView(messageTarget, albumParams);
                mediaAlbumContainers.put(albumId, messageTarget);
            }
        }
        boolean showMessageHeader = !isAlbum
                || message.optInt("media_album_index", 0) == 0;
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
        SpannableStringBuilder renderedMessage = new SpannableStringBuilder(
                formatInlineMarkup(displayText)
        );
        if (repliedToId > 0) {
            String renderedText = renderedMessage.toString();
            int quoteStart = renderedText.indexOf("Цитата: ");
            int quoteEnd = quoteStart < 0 ? -1 : renderedText.indexOf("\n", quoteStart);
            if (quoteStart >= 0 && quoteEnd > quoteStart) {
                renderedMessage.setSpan(new ClickableSpan() {
                    @Override
                    public void updateDrawState(android.text.TextPaint paint) {
                        paint.setColor(android.graphics.Color.rgb(50, 130, 220));
                        paint.setUnderlineText(true);
                    }

                    @Override
                    public void onClick(View widget) {
                        jumpToMessage(repliedToId);
                    }
                }, quoteStart, quoteEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
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
        if (showMessageHeader) {
            messageTarget.addView(row, params);
        }
        if (messageId > 0) {
            messageViews.put(messageId, isAlbum ? messageTarget : row);
        }

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
                JSONObject collectibleTraits = giftAttributes.optJSONObject("collectible_traits");
                if (collectibleTraits != null) {
                    giftInfo.append("\nРедкость: ").append(collectibleTraits.optString("rarity", "—"));
                    giftInfo.append("\nМодель: ").append(collectibleTraits.optString("model", "—"));
                    giftInfo.append("\nСимвол: ").append(collectibleTraits.optString("symbol", "—"));
                    giftInfo.append("\nФон: ").append(collectibleTraits.optString("backdrop", "—"));
                }
                Iterator<String> attributeKeys = giftAttributes.keys();
                while (attributeKeys.hasNext()) {
                    String key = attributeKeys.next();
                    if ("upgrade_level".equals(key) || "last_upgraded_at".equals(key)
                            || "upgrade_source".equals(key) || "collectible_traits".equals(key)) {
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
            messageTarget.addView(giftCard, giftParams);
        }

        String mediaId = message.optString("media_id", "");
        String mediaType = message.optString("media_type", "");
        String mediaName = message.optString("media_name", "Вложение");
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
            messageTarget.addView(image, imageParams);
            apiClient.downloadImage(mediaId, new WaysGramApiClient.ImageCallback() {
                @Override
                public void onSuccess(Bitmap bitmap) {
                    image.setImageBitmap(bitmap);
                    image.setOnClickListener(view -> showImagePreview(bitmap));
                }

                @Override
                public void onError(String message, int statusCode) {
                    image.setContentDescription("Изображение недоступно");
                }
            });
            addMediaSaveButton(messageTarget, mediaId, mediaName, mediaType);
        } else if (mediaId.matches("[a-f0-9]{32}") && mediaType.startsWith("video/")) {
            addVideoMessage(messageTarget, mediaId, mediaName, mediaType);
        } else if (mediaId.matches("[a-f0-9]{32}") && mediaType.startsWith("audio/")) {
            addAudioMessage(messageTarget, mediaId, mediaName, mediaType);
        } else if (mediaId.matches("[a-f0-9]{32}") && !mediaType.isEmpty()) {
            addMediaSaveButton(messageTarget, mediaId, mediaName, mediaType);
        }

    }

    private void showImagePreview(Bitmap bitmap) {
        Context context = getParentActivity();
        if (context == null || bitmap == null) {
            return;
        }
        ImageView preview = new ImageView(context);
        preview.setImageBitmap(bitmap);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setMaxWidth(AndroidUtilities.dp(360));
        preview.setMaxHeight(AndroidUtilities.dp(520));
        new AlertDialog.Builder(context)
                .setView(preview)
                .setPositiveButton("Закрыть", null)
                .show();
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
        labels.add("Цитировать");
        actionCodes.add(10);
        labels.add("Реакция");
        actionCodes.add(1);
        labels.add("Копировать");
        actionCodes.add(2);
        labels.add("Перевести");
        actionCodes.add(9);
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
                    } else if (action == 10) {
                        quoteMessage(messageId, message.optString("body", ""));
                    } else if (action == 1) {
                        showReactionPicker(messageId);
                    } else if (action == 2) {
                        copyMessageText(message.optString("body", ""));
                    } else if (action == 9) {
                        showTranslationTargets(message.optString("body", ""));
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
                int visible = Math.min(count, 100);
                String[] labels = new String[visible];
                long[] messageIds = new long[visible];
                for (int i = 0; i < visible; i++) {
                    JSONObject item = pinned.optJSONObject(i);
                    if (item == null) {
                        labels[i] = "Сообщение недоступно";
                        continue;
                    }
                    long messageId = item.optLong("message_id", 0);
                    messageIds[i] = messageId;
                    long sender = item.optLong("sender_id", 0);
                    String author = sender > 0
                            ? (sender == peerId ? peerTitle : "Вы")
                            : "Пользователь";
                    String body = item.optString("body", "");
                    if (body.length() > 160) {
                        body = body.substring(0, 160) + "…";
                    }
                    labels[i] = "#" + messageId + " · " + author
                            + (body.isEmpty() ? "" : "\n" + body);
                }
                statusView.setText("Закреплённых сообщений: " + count);
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Закреплённые сообщения · " + count)
                        .setItems(labels, (dialog, which) -> {
                            if (which >= 0 && which < messageIds.length
                                    && messageIds[which] > 0) {
                                jumpToMessage(messageIds[which]);
                            }
                        })
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

    private void showTranslationTargets(String originalText) {
        String rawText = originalText == null ? "" : originalText.trim();
        String text = formatInlineMarkup(rawText).toString().trim();
        if (text.isEmpty()) {
            statusView.setText("В сообщении нет текста для перевода.");
            return;
        }
        if (text.length() > 4000) {
            statusView.setText("Сообщение слишком длинное для перевода.");
            return;
        }
        ArrayList<String> languageTags = new ArrayList<>(
                TranslateLanguage.getAllLanguages()
        );
        languageTags.sort((first, second) ->
                Locale.forLanguageTag(first).getDisplayLanguage(Locale.getDefault())
                        .compareToIgnoreCase(
                                Locale.forLanguageTag(second).getDisplayLanguage(Locale.getDefault())
                        )
        );
        String[] labels = new String[languageTags.size()];
        for (int i = 0; i < languageTags.size(); i++) {
            String code = languageTags.get(i);
            String displayName = Locale.forLanguageTag(code)
                    .getDisplayLanguage(Locale.getDefault());
            labels[i] = (displayName.isEmpty() ? code : displayName) + " (" + code + ")";
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Перевести сообщение на")
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < labels.length) {
                        String targetCode = TranslateLanguage.fromLanguageTag(languageTags.get(which));
                        if (targetCode == null) {
                            statusView.setText("Выбранный язык не поддерживается переводчиком.");
                            return;
                        }
                        translateMessage(text, targetCode, labels[which], false);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void translateMessage(
            String originalText,
            String targetLanguage,
            String targetLabel,
            boolean allowMobileData
    ) {
        LanguageIdentifier identifier = LanguageIdentification.getClient();
        statusView.setText("Определяю язык сообщения…");
        identifier.identifyLanguage(originalText)
                .addOnSuccessListener(sourceTag -> {
                    String sourceCode = TranslateLanguage.fromLanguageTag(sourceTag);
                    if ("und".equals(sourceTag) || sourceCode == null) {
                        identifier.close();
                        statusView.setText("Не удалось определить исходный язык.");
                        return;
                    }
                    String sourceName = Locale.forLanguageTag(sourceTag)
                            .getDisplayLanguage(Locale.getDefault());
                    if (sourceCode.equals(targetLanguage)) {
                        identifier.close();
                        showTranslatedMessage(
                                originalText,
                                originalText,
                                sourceName,
                                targetLabel,
                                true
                        );
                        return;
                    }

                    TranslatorOptions options = new TranslatorOptions.Builder()
                            .setSourceLanguage(sourceCode)
                            .setTargetLanguage(targetLanguage)
                            .build();
                    Translator translator = Translation.getClient(options);
                    DownloadConditions.Builder conditions = new DownloadConditions.Builder();
                    if (!allowMobileData) {
                        conditions.requireWifi();
                    }
                    statusView.setText("Проверяю модель перевода…");
                    translator.downloadModelIfNeeded(conditions.build())
                            .addOnSuccessListener(ignored -> {
                                statusView.setText("Перевожу сообщение…");
                                translator.translate(originalText)
                                        .addOnSuccessListener(translated -> {
                                            translator.close();
                                            identifier.close();
                                            showTranslatedMessage(
                                                    originalText,
                                                    translated,
                                                    sourceName,
                                                    targetLabel,
                                                    false
                                            );
                                        })
                                        .addOnFailureListener(error -> {
                                            translator.close();
                                            identifier.close();
                                            statusView.setText(
                                                    "Не удалось перевести сообщение: "
                                                            + error.getLocalizedMessage()
                                            );
                                        });
                            })
                            .addOnFailureListener(error -> {
                                translator.close();
                                identifier.close();
                                if (!allowMobileData) {
                                    confirmMobileTranslationDownload(
                                            originalText, targetLanguage, targetLabel
                                    );
                                } else {
                                    statusView.setText(
                                            "Не удалось загрузить языковую модель. "
                                                    + "Проверьте интернет-соединение."
                                    );
                                }
                            });
                })
                .addOnFailureListener(error -> {
                    identifier.close();
                    statusView.setText("Не удалось определить язык сообщения.");
                });
    }

    private void confirmMobileTranslationDownload(
            String originalText,
            String targetLanguage,
            String targetLabel
    ) {
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Нужна языковая модель")
                .setMessage(
                        "Модель перевода может занимать около 30 МБ. "
                                + "Загрузка по Wi-Fi не удалась. Разрешить загрузку через мобильную сеть?"
                )
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Продолжить", (dialog, which) ->
                        translateMessage(
                                originalText, targetLanguage, targetLabel, true
                        ))
                .show();
    }

    private void showTranslatedMessage(
            String originalText,
            String translatedText,
            String sourceLanguage,
            String targetLanguage,
            boolean sameLanguage
    ) {
        String message = "Исходный язык: " + sourceLanguage
                + "\n\nОригинал:\n" + originalText
                + "\n\n" + (sameLanguage ? "Текст уже на выбранном языке." : "Перевод (" + targetLanguage + "):")
                + (sameLanguage ? "" : "\n" + translatedText);
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Перевод сообщения")
                .setMessage(message)
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Копировать перевод", (dialog, which) ->
                        copyMessageText(translatedText))
                .show();
        statusView.setText("Перевод готов.");
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

    private void quoteMessage(long messageId, String quotedBody) {
        if (messageInput == null || messageId <= 0) {
            return;
        }
        String quoteText = quotedBody == null ? "" : quotedBody.trim();
        if (quoteText.isEmpty()) {
            statusView.setText("В этом сообщении нет текста для цитирования.");
            return;
        }
        String quoteBlock = "<blockquote>" + quoteText + "</blockquote>";
        android.text.Editable editable = messageInput.getText();
        String existing = editable == null ? "" : editable.toString();
        String separator = existing.isEmpty() ? "" : existing.endsWith("\n") ? "\n" : "\n\n";
        if (existing.length() + separator.length() + quoteBlock.length() > 4000) {
            statusView.setText("Цитата не помещается в лимит сообщения 4000 символов.");
            return;
        }
        editable.append(separator).append(quoteBlock).append("\n");
        messageInput.setSelection(editable.length());
        setReplyTarget(messageId);
        statusView.setText("Цитата добавлена · ответ привязан к исходному сообщению");
        messageInput.requestFocus();
    }

    private void clearReplyTarget() {
        replyToMessageId = 0;
        statusView.setOnClickListener(null);
        statusView.setText("Ответ отменён");
    }

    private Uri mediaStreamUri(String mediaId) {
        if (mediaId == null || !mediaId.matches("[a-f0-9]{32}")
                || getParentActivity() == null) {
            return null;
        }
        WaysGramSessionStore store = WaysGramSessionStore.getInstance(getParentActivity());
        String baseUrl = store.getApiBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return null;
        }
        return Uri.parse(baseUrl.replaceAll("/+$", "") + "/api/media/" + mediaId);
    }

    private Map<String, String> mediaAuthorizationHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        if (getParentActivity() != null) {
            String token = WaysGramSessionStore.getInstance(getParentActivity())
                    .getWaysGramSessionToken();
            if (token != null && !token.isEmpty()) {
                headers.put("Authorization", "Bearer " + token);
            }
        }
        return headers;
    }

    private void addMediaSaveButton(LinearLayout target, String mediaId, String mediaName, String mediaType) {
        Button saveButton = new Button(getParentActivity());
        saveButton.setAllCaps(false);
        saveButton.setText("Сохранить файл: " + mediaName);
        target.addView(saveButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        saveButton.setOnClickListener(view -> saveDocument(mediaId, mediaName, mediaType));
    }

    private void addVideoMessage(LinearLayout target, String mediaId, String mediaName, String mediaType) {
        Context context = getParentActivity();
        Uri uri = mediaStreamUri(mediaId);
        if (context == null || uri == null) {
            addMediaSaveButton(target, mediaId, mediaName, mediaType);
            return;
        }
        TextView label = new TextView(context);
        label.setText("Видео · " + mediaName);
        label.setTextSize(12);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        target.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        VideoView videoView = new VideoView(context);
        videoView.setVideoURI(uri, mediaAuthorizationHeaders());
        MediaController controller = new MediaController(context);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setOnErrorListener((player, what, extra) -> {
            statusView.setText("Этот формат видео не поддерживается устройством. Сохраните файл для открытия в другом приложении.");
            return true;
        });
        activeVideoViews.add(videoView);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(210)
        );
        videoParams.bottomMargin = AndroidUtilities.dp(5);
        target.addView(videoView, videoParams);
        addMediaSaveButton(target, mediaId, mediaName, mediaType);
    }

    private void addAudioMessage(LinearLayout target, String mediaId, String mediaName, String mediaType) {
        Context context = getParentActivity();
        Uri uri = mediaStreamUri(mediaId);
        if (context == null || uri == null) {
            addMediaSaveButton(target, mediaId, mediaName, mediaType);
            return;
        }
        Button playButton = new Button(context);
        playButton.setAllCaps(false);
        playButton.setText("▶ Воспроизвести аудио: " + mediaName);
        target.addView(playButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final MediaPlayer[] playerHolder = new MediaPlayer[1];
        final boolean[] prepared = {false};
        playButton.setOnClickListener(view -> {
            MediaPlayer player = playerHolder[0];
            if (player == null) {
                MediaPlayer created = new MediaPlayer();
                playerHolder[0] = created;
                activeAudioPlayers.add(created);
                try {
                    created.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build());
                    created.setDataSource(context, uri, mediaAuthorizationHeaders());
                    created.setOnPreparedListener(preparedPlayer -> {
                        prepared[0] = true;
                        preparedPlayer.start();
                        playButton.setText("⏸ Пауза: " + mediaName);
                    });
                    created.setOnCompletionListener(completedPlayer -> {
                        completedPlayer.seekTo(0);
                        playButton.setText("▶ Повторить: " + mediaName);
                    });
                    created.setOnErrorListener((errorPlayer, what, extra) -> {
                        prepared[0] = false;
                        activeAudioPlayers.remove(errorPlayer);
                        try {
                            errorPlayer.release();
                        } catch (Exception ignored) {
                        }
                        playerHolder[0] = null;
                        playButton.setText("Не удалось воспроизвести аудио");
                        statusView.setText("Формат аудио не поддерживается устройством. Сохраните файл для открытия в другом приложении.");
                        return true;
                    });
                    playButton.setText("Загрузка аудио…");
                    created.prepareAsync();
                } catch (Exception exception) {
                    activeAudioPlayers.remove(created);
                    try {
                        created.release();
                    } catch (Exception ignored) {
                    }
                    playerHolder[0] = null;
                    playButton.setText("Не удалось открыть аудио");
                }
                return;
            }
            if (!prepared[0]) {
                return;
            }
            try {
                if (player.isPlaying()) {
                    player.pause();
                    playButton.setText("▶ Продолжить: " + mediaName);
                } else {
                    player.start();
                    playButton.setText("⏸ Пауза: " + mediaName);
                }
            } catch (IllegalStateException exception) {
                playButton.setText("Не удалось воспроизвести аудио");
            }
        });
        addMediaSaveButton(target, mediaId, mediaName, mediaType);
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
        if (silentSendCheckBox != null) {
            silentSendCheckBox.setEnabled(!inFlight);
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
        for (VideoView videoView : activeVideoViews) {
            try {
                videoView.stopPlayback();
            } catch (Exception ignored) {
            }
        }
        activeVideoViews.clear();
        for (MediaPlayer player : activeAudioPlayers) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
        }
        activeAudioPlayers.clear();
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
