package org.telegram.messenger.waysgram;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/** Initial WaysGram sign-in screen shown before Telegram's legacy login view. */
public final class WaysGramLoginFragment extends BaseFragment {
    private WaysGramSessionStore sessionStore;

    @Override
    public boolean onFragmentCreate() {
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("WaysGram");
        sessionStore = WaysGramSessionStore.getInstance(context);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(
                AndroidUtilities.dp(24),
                AndroidUtilities.dp(32),
                AndroidUtilities.dp(24),
                AndroidUtilities.dp(32)
        );
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView title = new TextView(context);
        title.setText("WaysGram");
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(context);
        subtitle.setText("Вход через внутренний номер и AuthWaysGram_bot");
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = AndroidUtilities.dp(14);
        content.addView(subtitle, subtitleParams);

        Button loginButton = new Button(context);
        loginButton.setText("Войти в WaysGram");
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        buttonParams.topMargin = AndroidUtilities.dp(28);
        content.addView(loginButton, buttonParams);
        loginButton.setOnClickListener(view -> openLoginSheet(context));

        TextView instructions = new TextView(context);
        instructions.setText(
                "1. Получите внутренний номер в AuthWaysGram_bot.\n" +
                "2. Укажите адрес API вашего сервера.\n" +
                "3. Запросите код, затем получите его в боте.\n" +
                "4. Введите код в WaysGram.\n\n" +
                "Это отдельная авторизация WaysGram. Коды входа в Telegram не используются."
        );
        instructions.setTextSize(14);
        instructions.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        instructions.setLineSpacing(AndroidUtilities.dp(4), 1.0f);
        LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        instructionParams.topMargin = AndroidUtilities.dp(24);
        content.addView(instructions, instructionParams);

        fragmentView = scroll;
        return scroll;
    }

    private void openLoginSheet(Context context) {
        new WaysGramLoginSheet(context, sessionStore, resourceProvider, () ->
                presentFragment(new WaysGramHomeFragment(), true)
        ).show();
    }
}
