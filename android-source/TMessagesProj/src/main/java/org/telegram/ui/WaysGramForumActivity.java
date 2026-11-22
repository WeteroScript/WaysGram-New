package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.UUID;

/** A WaysGram forum list and thread navigator independent of Telegram's forum RPC. */
final class WaysGramForumActivity extends BaseFragment {
    private static final int PICK_MEDIA = 9076;
    final long groupId;
    private final String endpoint;
    private final String token;
    private long topicId, beforeId, pickingTopic;
    private int generation;
    private ScrollView scroll;
    private LinearLayout rows, controls, composer;
    private EditText input;
    private boolean sending;
    private String snapshot;
    private final Runnable refresh = this::load;

    WaysGramForumActivity(int account, long groupId, long topicId) {
        setCurrentAccount(account);
        this.groupId = groupId;
        this.topicId = topicId;
        endpoint = "/api/groups/" + groupId + "/topics";
        token = WaysGramApi.getToken(account);
    }

    @Override public View createView(Context context) {
        actionBar.setTitle(text(R.string.WaysGramTopics));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1 && onBackPressed(true)) finishFragment(); }
        });
        LinearLayout content = column(context);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        controls = new LinearLayout(context);
        controls.setGravity(Gravity.END);
        content.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        rows = column(context);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        composer = new LinearLayout(context);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(context);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        input.setHint(text(R.string.WaysGramInboxWrite));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(4);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        button(composer, R.string.Send, () -> {
            String body = input.getText().toString().trim();
            if (body.isEmpty() || topicId <= 0 || sending || !active()) return;
            sending = true;
            WaysGramGroupTopics.send(this, getCurrentAccount(), token, endpoint, topicId, body, UUID.randomUUID().toString());
        });
        content.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        composer.setVisibility(View.GONE);
        fragmentView = content;
        return content;
    }

    @Override public void onResume() { super.onResume(); load(); }
    @Override public void onPause() {
        super.onPause();
        generation++;
        AndroidUtilities.cancelRunOnUIThread(refresh);
    }
    @Override public boolean onBackPressed(boolean invoked) {
        if (!super.onBackPressed(invoked)) return false;
        if (topicId == 0) return true;
        if (invoked) WaysGramGroupTopics.list(this, getCurrentAccount(), token, endpoint);
        return false;
    }

    private boolean active() {
        return !isFinished && !isPaused() && rows != null && !TextUtils.isEmpty(token)
                && TextUtils.equals(token, WaysGramApi.getToken(getCurrentAccount()));
    }
    private void load() {
        if (!active()) return;
        if (topicId == 0) WaysGramGroupTopics.list(this, getCurrentAccount(), token, endpoint);
        else WaysGramGroupTopics.page(this, getCurrentAccount(), token, endpoint, topicId, beforeId);
    }
    int beginLoad(long topic, long before) {
        AndroidUtilities.cancelRunOnUIThread(refresh);
        if (topicId != topic || beforeId != before) {
            rows.removeAllViews();
            controls.removeAllViews();
            if (topicId != topic) input.setText("");
            composer.setVisibility(View.GONE);
            snapshot = null;
        }
        topicId = topic;
        beforeId = before;
        return ++generation;
    }
    boolean accepts(int request) { return active() && request == generation; }
    void textSendFinished(long topic, String body, boolean sent) {
        sending = false;
        if (sent && topicId == topic && input != null && body.equals(input.getText().toString().trim())) input.setText("");
    }


    void renderTopics(JSONObject data) {
        actionBar.setTitle(text(R.string.WaysGramTopics));
        rows.removeAllViews();
        controls.removeAllViews();
        composer.setVisibility(View.GONE);
        button(controls, R.string.WaysGramCommentsLatest, this::load);
        if (data.optBoolean("can_manage")) button(controls, R.string.WaysGramTopicCreate,
                () -> WaysGramGroupTopics.create(this, getCurrentAccount(), token, endpoint));
        JSONArray topics = data.optJSONArray("topics");
        for (int i = 0; topics != null && i < topics.length(); i++) {
            JSONObject item = topics.optJSONObject(i);
            if (item == null || item.optLong("id") <= 0) continue;
            String title = item.optString("title");
            if (item.optBoolean("closed")) title = LocaleController.formatString(R.string.WaysGramTopicClosedLabel, title);
            row(title, () -> WaysGramGroupTopics.page(this, getCurrentAccount(), token, endpoint, item.optLong("id"), 0));
        }
        if (rows.getChildCount() == 0) row(text(R.string.WaysGramTopicsEmpty), null);
        scroll.scrollTo(0, 0);
    }

    void renderThread(JSONObject data) {
        JSONObject topic = data.optJSONObject("topic");
        if (topic == null) { loadFailed(404); return; }
        String value = data.toString();
        if (!value.equals(snapshot)) {
            int previousY = scroll.getScrollY();
            boolean bottom = snapshot == null || previousY + scroll.getHeight() >= rows.getHeight() - AndroidUtilities.dp(32);
            snapshot = value;
            String title = topic.optString("title");
            boolean closed = topic.optBoolean("closed");
            actionBar.setTitle(closed ? LocaleController.formatString(R.string.WaysGramTopicClosedLabel, title) : title);
            composer.setVisibility(closed ? View.GONE : View.VISIBLE);
            controls.removeAllViews();
            long older = data.optLong("next_before_id");
            if (older > 0) button(controls, R.string.WaysGramCommentsOlder,
                    () -> WaysGramGroupTopics.page(this, getCurrentAccount(), token, endpoint, topicId, older));
            if (beforeId > 0) button(controls, R.string.WaysGramCommentsLatest,
                    () -> WaysGramGroupTopics.page(this, getCurrentAccount(), token, endpoint, topicId, 0));
            button(controls, R.string.WaysGramProposalActions,
                    () -> WaysGramGroupTopics.actions(this, getCurrentAccount(), token, endpoint, topicId, beforeId));
            rows.removeAllViews();
            JSONObject pin = topic.optJSONObject("pin");
            if (pin != null) row(text(R.string.WaysGramTopicPin) + ": " + pin.optString("body"), null);
            JSONArray messages = data.optJSONArray("messages");
            if (messages == null || messages.length() == 0) row(text(R.string.WaysGramTopicEmpty), null);
            for (int i = 0; messages != null && i < messages.length(); i++) {
                JSONObject item = messages.optJSONObject(i);
                if (item == null) continue;
                String name = item.isNull("sender_name") ? "" : item.optString("sender_name");
                boolean media = !item.isNull("media_id");
                row((name.isEmpty() ? "" : name + "\n") + item.optString("body") + (media ? "\n📎 " + item.optString("media_name") : ""),
                        media ? () -> WaysGramGroupTopics.openMedia(this, getCurrentAccount(), token, item) : null);
            }
            scroll.post(() -> { if (bottom && beforeId == 0) scroll.fullScroll(View.FOCUS_DOWN); else scroll.scrollTo(0, previousY); });
        }
        if (beforeId == 0) AndroidUtilities.runOnUIThread(refresh, 5000);
    }

    void loadFailed(int status) {
        if (status == 403 || status == 404 || snapshot == null) {
            snapshot = null;
            rows.removeAllViews();
            controls.removeAllViews();
            composer.setVisibility(View.GONE);
            row(text(R.string.WaysGramTopicsError), null);
            button(controls, R.string.WaysGramCommentsLatest, this::load);
        } else if (beforeId == 0) AndroidUtilities.runOnUIThread(refresh, 5000);
    }

    void pickMedia(long topic) {
        if (!active()) return;
        pickingTopic = topic;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/mp4"});
        try { startActivityForResult(intent, PICK_MEDIA); } catch (Exception e) { FileLog.e(e); }
    }
    @Override public void onActivityResultFragment(int request, int result, Intent data) {
        if (request != PICK_MEDIA) return;
        long topic = pickingTopic;
        pickingTopic = 0;
        if (topic > 0 && result == Activity.RESULT_OK && data != null && data.getData() != null) {
            WaysGramGroupTopics.upload(this, getCurrentAccount(), token, endpoint, topic, data.getData());
        }
    }

    private static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private static String text(int id) { return LocaleController.getString(id); }
    private void row(CharSequence value, Runnable click) {
        TextView view = new TextView(rows.getContext());
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setTextSize(16);
        view.setText(value);
        int inset = AndroidUtilities.dp(16), vertical = AndroidUtilities.dp(12);
        view.setPadding(inset, vertical, inset, vertical);
        if (click != null) {
            view.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
            view.setOnClickListener(v -> click.run());
        }
        rows.addView(view, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(rows.getContext());
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        rows.addView(divider, new LinearLayout.LayoutParams(-1, 1));
    }
    private void button(LinearLayout parent, int res, Runnable click) {
        TextView view = new TextView(parent.getContext());
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        view.setText(text(res));
        view.setGravity(Gravity.CENTER);
        view.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        view.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        view.setOnClickListener(v -> click.run());
        parent.addView(view, new LinearLayout.LayoutParams(-2, AndroidUtilities.dp(48)));
    }
}
