package org.telegram.messenger.waysgram;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.app.AlertDialog;
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

import java.util.ArrayList;
import java.util.Locale;

/** Owner-only settings for a single internal WaysGram bot. */
public final class WaysGramBotDetailFragment extends BaseFragment {
    private final long botId;
    private final String botTitle;

    private WaysGramApiClient apiClient;
    private TextView detailsView;
    private TextView statusView;
    private TextView tokenView;
    private LinearLayout commandsContainer;
    private EditText commandInput;
    private EditText descriptionInput;
    private Button addCommandButton;
    private Button saveCommandsButton;
    private Button resetTokenButton;
    private Button chatButton;
    private Button disableBotButton;
    private final ArrayList<BotCommand> commands = new ArrayList<>();

    public WaysGramBotDetailFragment(long botId, String botTitle) {
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
        actionBar.setTitle(botTitle.length() > 0 ? botTitle : "Настройки бота");
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

        detailsView = label("Загрузка бота…", 14, false);
        root.addView(detailsView, matchWrap());

        statusView = label("", 12, false);
        root.addView(statusView, matchWrap());

        tokenView = label("", 12, false);
        tokenView.setTextIsSelectable(true);
        tokenView.setVisibility(View.GONE);
        root.addView(tokenView, matchWrap());

        Button copyButton = button(context, "Скопировать новый токен");
        copyButton.setVisibility(View.GONE);
        root.addView(copyButton, matchWrap());
        copyButton.setOnClickListener(view -> copyToken());
        tokenView.setTag(copyButton);

        chatButton = button(context, "Открыть чат с ботом");
        root.addView(chatButton, matchWrap());
        chatButton.setOnClickListener(view -> presentFragment(
                new WaysGramBotChatFragment(botId, botTitle), false
        ));

        TextView commandHeading = label("Команды бота", 17, true);
        root.addView(commandHeading, matchWrap());
        commandsContainer = new LinearLayout(context);
        commandsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(commandsContainer, matchWrap());

        commandInput = addField(context, root, "Команда без слеша, например start", 32);
        descriptionInput = addField(context, root, "Описание команды", 256);
        addCommandButton = button(context, "Добавить команду");
        root.addView(addCommandButton, matchWrap());
        addCommandButton.setOnClickListener(view -> addCommand());

        saveCommandsButton = button(context, "Сохранить список команд");
        root.addView(saveCommandsButton, matchWrap());
        saveCommandsButton.setOnClickListener(view -> saveCommands());

        resetTokenButton = button(context, "Сбросить токен");
        root.addView(resetTokenButton, matchWrap());
        resetTokenButton.setOnClickListener(view -> resetToken());

        disableBotButton = button(context, "Отключить бота");
        root.addView(disableBotButton, matchWrap());
        disableBotButton.setOnClickListener(view -> confirmDisable());

        Button refreshButton = button(context, "Обновить сведения");
        root.addView(refreshButton, matchWrap());
        refreshButton.setOnClickListener(view -> {
            loadBot();
            loadCommands();
        });

        fragmentView = scroll;
        loadBot();
        loadCommands();
        return scroll;
    }

    private EditText addField(Context context, LinearLayout parent, String hint, int maximum) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setHint(hint);
        input.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(maximum)
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

    private void loadBot() {
        apiClient.get("/api/bots/" + botId, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                boolean active = response.optBoolean("is_active", false);
                String username = response.optString("username", "");
                detailsView.setText(
                        response.optString("name", botTitle) + "\n@" + username
                                + "\nСтатус: " + (active ? "активен" : "отключён")
                                + "\n" + response.optString("about", "")
                                + "\n" + response.optString("description", "")
                );
                addCommandButton.setEnabled(active);
                saveCommandsButton.setEnabled(active);
                resetTokenButton.setEnabled(active);
                disableBotButton.setEnabled(active);
                chatButton.setEnabled(active);
                statusView.setText(active
                        ? "Токен хранится на сервере в виде хеша; полный токен можно получить только при сбросе."
                        : "Бот отключён. Его токен больше не действует.");
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

    private void loadCommands() {
        apiClient.get("/api/bots/" + botId + "/commands", new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                JSONArray array = response.optJSONArray("commands");
                commands.clear();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item != null) {
                            commands.add(new BotCommand(
                                    item.optString("command", ""),
                                    item.optString("description", "")
                            ));
                        }
                    }
                }
                renderCommands();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        });
    }

    private void renderCommands() {
        commandsContainer.removeAllViews();
        if (commands.isEmpty()) {
            commandsContainer.addView(label("Команды пока не настроены.", 13, false), matchWrap());
            return;
        }
        for (int i = 0; i < commands.size(); i++) {
            final int index = i;
            BotCommand command = commands.get(i);
            LinearLayout row = new LinearLayout(getParentActivity());
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView title = label("/" + command.name + " — " + command.description, 13, false);
            row.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button remove = button(getParentActivity(), "Удалить");
            remove.setTextSize(11);
            remove.setOnClickListener(view -> {
                commands.remove(index);
                renderCommands();
            });
            row.addView(remove, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            commandsContainer.addView(row, matchWrap());
        }
    }

    private void addCommand() {
        String name = textOf(commandInput).toLowerCase(Locale.US).replaceFirst("^/", "");
        String description = textOf(descriptionInput);
        if (!name.matches("[a-z0-9_]{1,32}") || description.length() > 256) {
            statusView.setText("Команда: 1–32 символа, латиница/цифры/_. Описание — до 256 символов.");
            return;
        }
        for (BotCommand item : commands) {
            if (item.name.equals(name)) {
                statusView.setText("Такая команда уже есть.");
                return;
            }
        }
        if (commands.size() >= 100) {
            statusView.setText("Можно настроить не более 100 команд.");
            return;
        }
        commands.add(new BotCommand(name, description));
        commandInput.setText("");
        descriptionInput.setText("");
        statusView.setText("Команда добавлена в черновик. Нажмите «Сохранить список команд».");
        renderCommands();
    }

    private void saveCommands() {
        JSONObject payload = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            for (BotCommand item : commands) {
                JSONObject command = new JSONObject();
                command.put("command", item.name);
                command.put("description", item.description);
                array.put(command);
            }
            payload.put("commands", array);
        } catch (Exception exception) {
            statusView.setText("Не удалось подготовить список команд.");
            return;
        }
        saveCommandsButton.setEnabled(false);
        apiClient.put("/api/bots/" + botId + "/commands", payload, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                saveCommandsButton.setEnabled(true);
                statusView.setText("Список команд сохранён.");
                loadCommands();
            }

            @Override
            public void onError(String message, int statusCode) {
                saveCommandsButton.setEnabled(true);
                statusView.setText(message);
            }
        });
    }

    private void resetToken() {
        resetTokenButton.setEnabled(false);
        apiClient.post("/api/bots/" + botId + "/token/reset", new JSONObject(),
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        resetTokenButton.setEnabled(true);
                        revealToken(response.optString("token", ""));
                        statusView.setText("Старый токен отозван. Сохраните новый токен: он показан только сейчас.");
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        resetTokenButton.setEnabled(true);
                        statusView.setText(message);
                    }
                });
    }

    private void revealToken(String token) {
        if (token == null || token.length() < 20) {
            return;
        }
        tokenView.setText("Новый токен:\n" + token);
        tokenView.setVisibility(View.VISIBLE);
        Button copy = (Button) tokenView.getTag();
        copy.setVisibility(View.VISIBLE);
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
            Toast.makeText(getParentActivity(), "Новый токен скопирован", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDisable() {
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Отключить бота?")
                .setMessage("Токен будет отозван, а бот перестанет принимать новые сообщения.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отключить", (dialog, which) -> disableBot())
                .show();
    }

    private void disableBot() {
        apiClient.delete("/api/bots/" + botId, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                statusView.setText("Бот отключён. Токен больше не действует.");
                loadBot();
            }

            @Override
            public void onError(String message, int statusCode) {
                statusView.setText(message);
            }
        });
    }

    private String textOf(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static final class BotCommand {
        final String name;
        final String description;

        BotCommand(String name, String description) {
            this.name = name;
            this.description = description;
        }
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
