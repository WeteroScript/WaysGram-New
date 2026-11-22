package org.telegram.messenger.waysgram;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Publishes this user's temporary typing state and displays other active typists. */
public final class WaysGramTypingIndicatorController {
    private static final long POLL_INTERVAL_MS = 2500L;
    private static final long STOP_DELAY_MS = 2500L;
    private static final long REFRESH_TYPING_MS = 4000L;

    private final WaysGramApiClient apiClient;
    private final String scope;
    private final long peerId;
    private final EditText input;
    private final TextView indicator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextWatcher watcher;
    private boolean screenActive;
    private boolean closed;
    private boolean pollInFlight;
    private boolean typingActive;
    private long lastTypingSentAt;

    private final Runnable pollRunnable = this::poll;
    private final Runnable stopTypingRunnable = this::stopTyping;

    public WaysGramTypingIndicatorController(
            WaysGramApiClient apiClient,
            String scope,
            long peerId,
            EditText input,
            TextView indicator
    ) {
        if (apiClient == null || input == null || indicator == null
                || peerId <= 0 || !("dm".equals(scope) || "group".equals(scope))) {
            throw new IllegalArgumentException("Invalid typing indicator configuration");
        }
        this.apiClient = apiClient;
        this.scope = scope;
        this.peerId = peerId;
        this.input = input;
        this.indicator = indicator;
        indicator.setVisibility(View.GONE);
        watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable value) {
                if (closed || !input.hasFocus()) {
                    return;
                }
                if (value == null || value.toString().trim().isEmpty()) {
                    stopTyping();
                    return;
                }
                publishTypingState();
            }
        };
        input.addTextChangedListener(watcher);
    }

    public void startPolling() {
        if (closed || screenActive) {
            return;
        }
        screenActive = true;
        poll();
    }

    public void stopPolling() {
        screenActive = false;
        handler.removeCallbacks(pollRunnable);
        indicator.setText("");
        indicator.setVisibility(View.GONE);
        stopTyping();
    }

    private String queryPath() {
        return "/api/typing?scope=" + scope + "&peer_id=" + peerId;
    }

    private void publishTypingState() {
        long now = SystemClock.elapsedRealtime();
        handler.removeCallbacks(stopTypingRunnable);
        if (!typingActive || now - lastTypingSentAt >= REFRESH_TYPING_MS) {
            sendTyping(true);
            lastTypingSentAt = now;
        }
        typingActive = true;
        handler.postDelayed(stopTypingRunnable, STOP_DELAY_MS);
    }

    private void stopTyping() {
        handler.removeCallbacks(stopTypingRunnable);
        if (!typingActive || closed) {
            typingActive = false;
            return;
        }
        typingActive = false;
        sendTyping(false);
    }

    private void sendTyping(boolean isTyping) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("scope", scope);
            payload.put("peer_id", peerId);
            payload.put("is_typing", isTyping);
        } catch (Exception exception) {
            return;
        }
        apiClient.put("/api/typing", payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
            }

            @Override
            public void onError(String message, int statusCode) {
            }
        });
    }

    private void poll() {
        if (closed || !screenActive || pollInFlight) {
            return;
        }
        pollInFlight = true;
        apiClient.get(queryPath(), new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                pollInFlight = false;
                if (closed || !screenActive) {
                    return;
                }
                render(response.optJSONArray("typing_users"));
                schedulePoll();
            }

            @Override
            public void onError(String message, int statusCode) {
                pollInFlight = false;
                if (closed || !screenActive) {
                    return;
                }
                indicator.setText("");
                indicator.setVisibility(View.GONE);
                schedulePoll();
            }
        });
    }

    private void schedulePoll() {
        handler.removeCallbacks(pollRunnable);
        if (!closed && screenActive) {
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        }
    }

    private void render(JSONArray users) {
        int count = users == null ? 0 : users.length();
        if (count == 0) {
            indicator.setText("");
            indicator.setVisibility(View.GONE);
            return;
        }
        StringBuilder names = new StringBuilder();
        int visible = Math.min(count, 3);
        for (int i = 0; i < visible; i++) {
            JSONObject user = users.optJSONObject(i);
            if (user == null) {
                continue;
            }
            String name = user.optString("username", "").trim();
            if (name.isEmpty()) {
                name = user.optString("display_name", "").trim();
            }
            if (name.isEmpty()) {
                name = "Пользователь";
            } else if (!name.startsWith("@")) {
                name = "@" + name;
            }
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(name);
        }
        if (count > visible) {
            names.append(" и ещё ").append(count - visible);
        }
        String text;
        if ("dm".equals(scope)) {
            text = names + " печатает…";
        } else {
            text = names + (count == 1 ? " печатает…" : " печатают…");
        }
        indicator.setText(text);
        indicator.setVisibility(View.VISIBLE);
    }

    public void close() {
        if (closed) {
            return;
        }
        stopPolling();
        closed = true;
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(stopTypingRunnable);
        input.removeTextChangedListener(watcher);
    }
}