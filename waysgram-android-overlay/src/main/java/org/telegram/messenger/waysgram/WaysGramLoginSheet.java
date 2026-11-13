package org.telegram.messenger.waysgram;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

/**
 * Standalone WaysGram login sheet using internal virtual numbers and
 * one-time codes delivered by AuthWaysGram_bot.
 *
 * No Telegram auth code, phone login, or Telegram account session is touched.
 */
public final class WaysGramLoginSheet extends BottomSheet {
    private static final String DEFAULT_API_BASE_URL = "https://bot-1793554244-6434-weterochina.bothost.tech";
    private final WaysGramSessionStore sessionStore;
    private final Runnable onLoginSuccess;
    private WaysGramAuthClient authClient;

    private final EditText apiBaseInput;
    private final EditText virtualNumberInput;
    private final EditText codeInput;
    private final TextView statusView;
    private final Button checkServerButton;
    private final Button requestCodeButton;
    private final Button verifyButton;
    private boolean codeRequested;

    public WaysGramLoginSheet(
            Context context,
            WaysGramSessionStore sessionStore,
            Theme.ResourcesProvider resourcesProvider
    ) {
        this(context, sessionStore, resourcesProvider, null);
    }

    public WaysGramLoginSheet(
            Context context,
            WaysGramSessionStore sessionStore,
            Theme.ResourcesProvider resourcesProvider,
            Runnable onLoginSuccess
    ) {
        super(context, false, resourcesProvider);
        if (sessionStore == null) {
            throw new IllegalArgumentException("WaysGramSessionStore is required");
        }
        this.sessionStore = sessionStore;
        this.onLoginSuccess = onLoginSuccess;

        setTitle("Вход в WaysGram", true);
        setApplyTopPadding(false);
        setApplyBottomPadding(true);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                AndroidUtilities.dp(20),
                AndroidUtilities.dp(8),
                AndroidUtilities.dp(20),
                AndroidUtilities.dp(20)
        );

        TextView explanation = new TextView(context);
        explanation.setText(
                "Введите внутренний номер WaysGram, который вы получили в AuthWaysGram_bot. " +
                "Код подтверждения также приходит в этот бот. Это код WaysGram, не код входа в Telegram."
        );
        explanation.setTextSize(14);
        explanation.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        explanation.setLineSpacing(AndroidUtilities.dp(3), 1.0f);
        content.addView(explanation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        apiBaseInput = addField(
                context,
                content,
                "Адрес WaysGram API",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
        );
        String savedBase = sessionStore.getApiBaseUrl();
        apiBaseInput.setText(savedBase != null ? savedBase : DEFAULT_API_BASE_URL);
        apiBaseInput.setSingleLine(true);

        checkServerButton = new Button(context);
        checkServerButton.setText("Проверить соединение с сервером");
        content.addView(checkServerButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        virtualNumberInput = addField(
                context,
                content,
                "Внутренний номер (+1 и 10 цифр)",
                InputType.TYPE_CLASS_PHONE
        );
        virtualNumberInput.setSingleLine(true);

        requestCodeButton = new Button(context);
        requestCodeButton.setText("Запросить код в AuthWaysGram_bot");
        content.addView(requestCodeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        codeInput = addField(
                context,
                content,
                "Шестизначный код из AuthWaysGram_bot",
                InputType.TYPE_CLASS_NUMBER
        );
        codeInput.setSingleLine(true);
        codeInput.setMaxEms(6);
        codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});

        verifyButton = new Button(context);
        verifyButton.setText("Войти в WaysGram");
        content.addView(verifyButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        statusView = new TextView(context);
        statusView.setText("Сначала запросите код. Он будет отправлен в AuthWaysGram_bot.");
        statusView.setTextSize(13);
        statusView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, AndroidUtilities.dp(10), 0, 0);
        content.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        setCustomView(content);
        checkServerButton.setOnClickListener(view -> checkServer());
        requestCodeButton.setOnClickListener(view -> requestCode());
        verifyButton.setOnClickListener(view -> verifyCode());
        verifyButton.setEnabled(false);
        requestCodeButton.setEnabled(true);

        setOnDismissListener(dialog -> closeClient());
    }

    private EditText addField(Context context, LinearLayout parent, String hint, int inputType) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setTextSize(16);
        input.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8),
                AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = AndroidUtilities.dp(12);
        parent.addView(input, params);
        return input;
    }

    private WaysGramAuthClient getClient() {
        String base = apiBaseInput.getText() == null
                ? ""
                : apiBaseInput.getText().toString().trim();
        if (authClient == null) {
            authClient = new WaysGramAuthClient(base, sessionStore);
        }
        return authClient;
    }

    private String getNumber() {
        return virtualNumberInput.getText() == null
                ? ""
                : virtualNumberInput.getText().toString().trim();
    }

    private void checkServer() {
        String base = apiBaseInput.getText() == null
                ? ""
                : apiBaseInput.getText().toString().trim();
        if (base.length() == 0) {
            statusView.setText("Укажите адрес WaysGram API.");
            return;
        }

        codeRequested = false;
        closeClient();
        try {
            authClient = new WaysGramAuthClient(base, sessionStore);
        } catch (IllegalArgumentException exception) {
            statusView.setText(exception.getMessage());
            return;
        }

        setBusy(true);
        statusView.setText("Проверяю доступность WaysGram API…");
        authClient.checkServer(new WaysGramAuthClient.Callback() {
            @Override
            public void onSuccess(org.json.JSONObject response) {
                setBusy(false);
                if (response.optBoolean("authbot_delivery_ready", false)) {
                    statusView.setText("WaysGram API доступен. AuthWaysGram_bot подключён, доставка кодов включена.");
                } else {
                    statusView.setText("WaysGram API доступен, но AuthWaysGram_bot не подключён. Запрос кода пока недоступен.");
                }
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                statusView.setText("Сервер WaysGram недоступен: " + message);
            }
        });
    }

    private void requestCode() {
        String number = getNumber();
        String base = apiBaseInput.getText() == null
                ? ""
                : apiBaseInput.getText().toString().trim();
        if (number.length() == 0 || base.length() == 0) {
            statusView.setText("Заполните адрес API и внутренний номер.");
            return;
        }
        codeRequested = false;
        verifyButton.setEnabled(false);
        closeClient();
        try {
            authClient = new WaysGramAuthClient(base, sessionStore);
        } catch (IllegalArgumentException exception) {
            statusView.setText(exception.getMessage());
            return;
        }

        setBusy(true);
        statusView.setText("Отправляю запрос в WaysGram…");
        authClient.requestCode(number, new WaysGramAuthClient.Callback() {
            @Override
            public void onSuccess(org.json.JSONObject response) {
                codeRequested = true;
                setBusy(false);
                statusView.setText(
                        "Запрос принят. Откройте AuthWaysGram_bot и нажмите «Получить код», затем введите его здесь."
                );
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                statusView.setText(message);
            }
        });
    }

    private void verifyCode() {
        String number = getNumber();
        String code = codeInput.getText() == null
                ? ""
                : codeInput.getText().toString().trim();
        if (authClient == null || !codeRequested) {
            statusView.setText("Сначала запросите код.");
            return;
        }
        setBusy(true);
        statusView.setText("Проверяю код WaysGram…");
        authClient.verifyCode(number, code, new WaysGramAuthClient.LoginCallback() {
            @Override
            public void onSuccess(org.json.JSONObject user, long expiresAt) {
                setBusy(false);
                String displayName = user.optString("display_name", "");
                if (displayName.length() == 0) {
                    displayName = user.optString("username", "");
                }
                statusView.setText("Вход выполнен" +
                        (displayName.length() > 0 ? ": " + displayName : "") + ".");
                Toast.makeText(getContext(), "Сессия WaysGram сохранена", Toast.LENGTH_SHORT).show();
                dismiss();
                if (onLoginSuccess != null) {
                    onLoginSuccess.run();
                }
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                statusView.setText(message);
            }
        });
    }

    private void setBusy(boolean busy) {
        checkServerButton.setEnabled(!busy);
        requestCodeButton.setEnabled(!busy);
        verifyButton.setEnabled(!busy && codeRequested);
        apiBaseInput.setEnabled(!busy);
        virtualNumberInput.setEnabled(!busy);
        codeInput.setEnabled(!busy);
    }

    private void closeClient() {
        if (authClient != null) {
            authClient.close();
            authClient = null;
        }
    }

    @Override
    public void dismiss() {
        closeClient();
        super.dismiss();
    }
}
