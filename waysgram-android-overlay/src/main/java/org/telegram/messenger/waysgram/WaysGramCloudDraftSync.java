package org.telegram.messenger.waysgram;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import org.json.JSONObject;

/**
 * Keeps the local conversation draft and its WaysGram cloud copy synchronized.
 * The server copy is scoped to the current account, conversation and chat access.
 */
public final class WaysGramCloudDraftSync {
    private static final long SAVE_DELAY_MS = 750L;

    private final Context appContext;
    private final WaysGramApiClient apiClient;
    private final String scope;
    private final long peerId;
    private final EditText input;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextWatcher watcher;
    private String lastServerBody;
    private boolean started;
    private boolean closed;
    private boolean applyingServerDraft;
    private int localEditVersion;

    private final Runnable saveRunnable = this::saveNow;

    public WaysGramCloudDraftSync(
            Context context,
            WaysGramApiClient apiClient,
            String scope,
            long peerId,
            EditText input
    ) {
        if (context == null || apiClient == null || input == null
                || peerId <= 0
                || !("dm".equals(scope) || "group".equals(scope) || "channel".equals(scope))) {
            throw new IllegalArgumentException("Invalid cloud draft configuration");
        }
        this.appContext = context.getApplicationContext();
        this.apiClient = apiClient;
        this.scope = scope;
        this.peerId = peerId;
        this.input = input;
        watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable value) {
                if (closed) {
                    return;
                }
                String body = value == null ? "" : value.toString();
                WaysGramDraftStore.save(appContext, WaysGramCloudDraftSync.this.scope,
                        WaysGramCloudDraftSync.this.peerId, body);
                if (started && !applyingServerDraft) {
                    localEditVersion++;
                    scheduleSave();
                }
            }
        };
        input.addTextChangedListener(watcher);
    }

    /** Load the cloud copy; when none exists, upload the device's current local draft. */
    public void start() {
        if (closed || started) {
            return;
        }
        started = true;
        apiClient.get(path(), new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                if (closed) {
                    return;
                }
                JSONObject draft = response.optJSONObject("draft");
                String remoteBody = draft == null ? "" : draft.optString("body", "");
                if (localEditVersion == 0 && !remoteBody.isEmpty()) {
                    applyingServerDraft = true;
                    input.setText(remoteBody);
                    WaysGramDraftStore.save(appContext, scope, peerId, remoteBody);
                    applyingServerDraft = false;
                    lastServerBody = remoteBody;
                } else {
                    lastServerBody = remoteBody;
                    // Local changes made while the initial request was running take priority.
                    scheduleSave();
                }
            }

            @Override
            public void onError(String message, int statusCode) {
                // Local drafts remain available when the account is offline or the API is unavailable.
            }
        });
    }

    /** Flush pending edits when the chat screen goes to the background. */
    public void flush() {
        if (closed || !started) {
            return;
        }
        handler.removeCallbacks(saveRunnable);
        saveNow();
    }

    /** Clear both local and cloud copies after a message has been sent successfully. */
    public void clear() {
        if (closed) {
            return;
        }
        input.setText("");
        WaysGramDraftStore.clear(appContext, scope, peerId);
        handler.removeCallbacks(saveRunnable);
        if (started) {
            saveNow();
        }
    }

    private String path() {
        return "/api/drafts?scope=" + scope + "&peer_id=" + peerId;
    }

    private void scheduleSave() {
        handler.removeCallbacks(saveRunnable);
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS);
    }

    private void saveNow() {
        if (closed || !started) {
            return;
        }
        String body = input.getText() == null ? "" : input.getText().toString();
        if (body.equals(lastServerBody)) {
            return;
        }
        if (body.isEmpty()) {
            apiClient.delete(path(), new WaysGramApiClient.Callback() {
                @Override
                public void onSuccess(JSONObject response) {
                    lastServerBody = "";
                }

                @Override
                public void onError(String message, int statusCode) {
                }
            });
            return;
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("scope", scope);
            payload.put("peer_id", peerId);
            payload.put("body", body);
        } catch (Exception exception) {
            return;
        }
        apiClient.put("/api/drafts", payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                lastServerBody = body;
            }

            @Override
            public void onError(String message, int statusCode) {
            }
        });
    }

    public void close() {
        if (closed) {
            return;
        }
        flush();
        closed = true;
        handler.removeCallbacks(saveRunnable);
        input.removeTextChangedListener(watcher);
    }
}