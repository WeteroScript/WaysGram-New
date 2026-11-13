package org.telegram.messenger.waysgram;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/** User-to-bot conversation in the internal WaysGram bot registry. */
public final class WaysGramBotChatFragment extends BaseFragment {
    private final long botId;
    private final String botTitle;

    private WaysGramApiClient apiClient;
    private LinearLayout messagesContainer;
    private EditText messageInput;
    private TextView statusView;
    private Button sendButton;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollNewMessages();
        }
    };
    private long lastMessageId = 0;
    private boolean pollInFlight = false;
    private boolean isScreenResumed = false;

    public WaysGramBotChatFragment(long botId, String botTitle) {
        if (botId <= 0) {
            throw new IllegalArgumentException("Invalid WaysGram bot ID");
        }
        this.botId = botId;
        this.botTitle = botTitle == null ? "" : botTitle;
    }

    @Override
    public boolean onFragmentCreate() {
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(botTitle.length() > 0 ? botTitle : "Чат с ботом");
        apiClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(context));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8),
                AndroidUtilities.dp(10), AndroidUtilities.dp(8));

        statusView = new TextView(context);
        statusView.setText("Откройте диалог сообщением боту.");
        statusView.setTextSize(12);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ScrollView scroll = new ScrollView(context);
        messagesContainer = new LinearLayout(context);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messagesContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        LinearLayout composer = new LinearLayout(context);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        messageInput = new EditText(context);
        messageInput.setHint("Сообщение боту");
        messageInput.setMaxLines(4);
        composer.addView(messageInput, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        ));
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

        Button refreshButton = new Button(context);
        refreshButton.setText("Обновить");
        refreshButton.setAllCaps(false);
        root.addView(refreshButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        refreshButton.setOnClickListener(view -> loadMessages());

        fragmentView = root;
        loadMessages();
        return root;
    }

    private String messagesPath() {
        return "/api/bots/" + botId + "/messages";
    }

    private void loadMessages() {
        apiClient.get(messagesPath(), new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
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
                statusView.setText(count == 0 ? "Ожидается ответ бота." : "Сообщений: " + count);
                scheduleNextPoll();
            }

            @Override
            public void onError(String message, int statusCode) {
                if (statusCode == 404) {
                    statusView.setText("Диалог ещё не создан. Отправьте первое сообщение.");
                    scheduleNextPoll();
                } else {
                    statusView.setText(message);
                    handleAuthError(statusCode);
                    if (statusCode != 401) {
                        scheduleNextPoll();
                    }
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
        apiClient.getLongPoll(messagesPath() + "?after_id=" + lastMessageId + "&wait_seconds=20", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                pollInFlight = false;
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
                }
                scheduleNextPoll();
            }

            @Override
            public void onError(String message, int statusCode) {
                pollInFlight = false;
                if (statusCode == 404) {
                    scheduleNextPoll(4000);
                } else {
                    handleAuthError(statusCode);
                    if (statusCode != 401) {
                        scheduleNextPoll(4000);
                    }
                }
            }
        });
    }

    private void handleAuthError(int statusCode) {
        if (statusCode == 401) {
            WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
            presentFragment(new WaysGramLoginFragment(), true);
        }
    }

    private void addMessage(JSONObject message) {
        TextView row = new TextView(getParentActivity());
        String sender = "bot".equals(message.optString("sender", "user")) ? botTitle : "Вы";
        row.setText(sender + ": " + message.optString("body", ""));
        row.setTextSize(14);
        row.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8),
                AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        messagesContainer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void sendMessage() {
        String body = messageInput.getText() == null
                ? ""
                : messageInput.getText().toString().trim();
        if (body.isEmpty() || body.length() > 4000) {
            statusView.setText("Сообщение должно содержать от 1 до 4000 символов.");
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("body", body);
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить сообщение.");
            return;
        }
        sendButton.setEnabled(false);
        statusView.setText("Отправляю боту…");
        apiClient.post(messagesPath(), payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                messageInput.setText("");
                sendButton.setEnabled(true);
                statusView.setText("Сообщение отправлено боту.");
                loadMessages();
            }

            @Override
            public void onError(String message, int statusCode) {
                sendButton.setEnabled(true);
                statusView.setText(message);
                handleAuthError(statusCode);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        isScreenResumed = true;
        pollHandler.removeCallbacks(pollRunnable);
        pollNewMessages();
    }

    @Override
    public void onPause() {
        isScreenResumed = false;
        pollHandler.removeCallbacks(pollRunnable);
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        isScreenResumed = false;
        pollHandler.removeCallbacks(pollRunnable);
        if (apiClient != null) {
            apiClient.close();
            apiClient = null;
        }
        super.onFragmentDestroy();
    }
}
