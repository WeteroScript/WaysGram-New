package org.telegram.messenger.waysgram;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** In-app BotFather for the internal WaysGram bot registry. */
public final class WaysGramBotsFragment extends BaseFragment {
    private WaysGramApiClient apiClient;
    private EditText nameInput;
    private EditText usernameInput;
    private EditText aboutInput;
    private EditText publicBotInput;
    private TextView statusView;
    private TextView tokenView;
    private LinearLayout botsContainer;

    @Override
    public boolean onFragmentCreate() {
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("BotFather WaysGram");
        apiClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(context));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12),
                AndroidUtilities.dp(14), AndroidUtilities.dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView heading = label("Мои внутренние боты", 18, true);
        root.addView(heading, matchWrap());

        nameInput = addField(context, root, "Название бота", 64);
        usernameInput = addField(context, root, "Username, например waysgram_helper_bot", 32);
        aboutInput = addField(context, root, "Краткое описание (необязательно)", 120);

        Button createButton = button(context, "Создать бота");
        root.addView(createButton, matchWrap());
        createButton.setOnClickListener(view -> createBot());

        statusView = label("Боты создаются внутри WaysGram и не становятся Telegram-ботами.", 13, false);
        root.addView(statusView, matchWrap());

        TextView openHeading = label("Открыть существующего бота", 16, true);
        root.addView(openHeading, matchWrap());
        publicBotInput = addField(context, root, "Username бота, например @waysgram_helper_bot", 32);
        Button openBotButton = button(context, "Открыть чат");
        root.addView(openBotButton, matchWrap());
        openBotButton.setOnClickListener(view -> openPublicBot());

        tokenView = label("", 12, false);
        tokenView.setTextIsSelectable(true);
        tokenView.setVisibility(View.GONE);
        root.addView(tokenView, matchWrap());

        Button copyTokenButton = button(context, "Скопировать токен");
        copyTokenButton.setVisibility(View.GONE);
        root.addView(copyTokenButton, matchWrap());
        copyTokenButton.setOnClickListener(view -> copyToken());

        Button refreshButton = button(context, "Обновить список");
        root.addView(refreshButton, matchWrap());
        refreshButton.setOnClickListener(view -> loadBots());

        TextView listHeading = label("Созданные боты", 16, true);
        root.addView(listHeading, matchWrap());
        botsContainer = new LinearLayout(context);
        botsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(botsContainer, matchWrap());

        // Keep the copy control available only while a newly issued token is visible.
        tokenView.setTag(copyTokenButton);
        fragmentView = scroll;
        loadBots();
        return scroll;
    }

    private EditText addField(Context context, LinearLayout parent, String hint, int maxLength) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextSize(14);
        input.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(maxLength)
        });
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = AndroidUtilities.dp(5);
        parent.addView(input, params);
        return input;
    }

    private TextView label(String value, int size, boolean bold) {
        TextView view = new TextView(getParentActivity());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (bold) {
            view.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        view.setPadding(AndroidUtilities.dp(3), AndroidUtilities.dp(6),
                AndroidUtilities.dp(3), AndroidUtilities.dp(6));
        return view;
    }

    private Button button(Context context, String title) {
        Button button = new Button(context);
        button.setText(title);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private void createBot() {
        String name = textOf(nameInput);
        String username = textOf(usernameInput).replaceFirst("^@", "").toLowerCase(Locale.US);
        String about = textOf(aboutInput);
        if (name.isEmpty() || name.length() > 64) {
            statusView.setText("Название должно содержать от 1 до 64 символов.");
            return;
        }
        if (!username.matches("[a-z][a-z0-9_]{2,28}_bot")) {
            statusView.setText("Username должен соответствовать формату example_bot.");
            return;
        }
        if (about.length() > 120) {
            statusView.setText("Краткое описание не должно превышать 120 символов.");
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("name", name);
            payload.put("username", username);
            payload.put("about", about);
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить данные бота.");
            return;
        }

        statusView.setText("Создаю бота…");
        apiClient.post("/api/bots", payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                nameInput.setText("");
                usernameInput.setText("");
                aboutInput.setText("");
                showToken(response.optString("token", ""));
                statusView.setText(response.optString("token_notice",
                        "Сохраните токен: он отображается только один раз."));
                loadBots();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        });
    }

    private void showToken(String token) {
        if (token == null || token.length() < 20) {
            tokenView.setVisibility(View.GONE);
            Button copyButton = (Button) tokenView.getTag();
            copyButton.setVisibility(View.GONE);
            return;
        }
        tokenView.setText("Токен нового бота:\n" + token);
        tokenView.setVisibility(View.VISIBLE);
        Button copyButton = (Button) tokenView.getTag();
        copyButton.setVisibility(View.VISIBLE);
    }

    private void copyToken() {
        String value = tokenView.getText().toString();
        int split = value.indexOf('\n');
        String token = split >= 0 ? value.substring(split + 1).trim() : "";
        if (token.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getParentActivity()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("WaysGram bot token", token));
            Toast.makeText(getParentActivity(), "Токен скопирован", Toast.LENGTH_SHORT).show();
        }
    }

    private void openPublicBot() {
        String username = textOf(publicBotInput).replaceFirst("^@", "").toLowerCase(Locale.US);
        if (!username.matches("[a-z][a-z0-9_]{2,28}_bot")) {
            statusView.setText("Введите корректный username бота в формате example_bot.");
            return;
        }
        try {
            String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name());
            statusView.setText("Ищу бота…");
            apiClient.get("/api/bots/by-username/" + encoded, new WaysGramApiClient.Callback() {
                @Override
                public void onSuccess(JSONObject response) {
                    long id = response.optLong("id", 0);
                    String name = response.optString("name", username);
                    if (id <= 0) {
                        statusView.setText("Сервер вернул некорректную карточку бота.");
                        return;
                    }
                    presentFragment(new WaysGramBotChatFragment(id, name), false);
                }

                @Override
                public void onError(String message, int statusCode) {
                    statusView.setText(message);
                }
            });
        } catch (Exception exception) {
            statusView.setText("Не удалось выполнить поиск бота.");
        }
    }

    private void loadBots() {
        if (apiClient == null || botsContainer == null) {
            return;
        }
        statusView.setText("Загружаю список ботов…");
        apiClient.get("/api/bots", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONArray bots = response.optJSONArray("bots");
                botsContainer.removeAllViews();
                int count = bots == null ? 0 : bots.length();
                for (int i = 0; i < count; i++) {
                    JSONObject bot = bots.optJSONObject(i);
                    if (bot == null) {
                        continue;
                    }
                    long id = bot.optLong("id", 0);
                    String name = bot.optString("name", "Бот " + id);
                    String username = bot.optString("username", "");
                    boolean active = bot.optBoolean("is_active", false);
                    Button item = button(getParentActivity(),
                            name + "\n@" + username + (active ? " · активен" : " · отключён"));
                    item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    botsContainer.addView(item, matchWrap());
                    item.setOnClickListener(view -> presentFragment(
                            new WaysGramBotDetailFragment(id, name), false
                    ));
                }
                if (tokenView.getVisibility() != View.VISIBLE) {
                    statusView.setText("Активных и отключённых ботов в списке: " + count);
                }
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

    private String textOf(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
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
