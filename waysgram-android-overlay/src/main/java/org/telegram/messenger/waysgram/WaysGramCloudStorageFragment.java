package org.telegram.messenger.waysgram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.text.DateFormat;
import java.util.Date;

/** Personal WaysGram cloud storage manager backed by /api/media/storage. */
public final class WaysGramCloudStorageFragment extends BaseFragment {
    private static final int REQUEST_SAVE_MEDIA = 7114;
    private static final int PAGE_SIZE = 50;

    private WaysGramApiClient apiClient;
    private TextView statusView;
    private LinearLayout fileList;
    private Button loadMoreButton;
    private int nextOffset;
    private boolean hasMore;
    private boolean requestInFlight;
    private String pendingMediaId;
    private String pendingMediaName;
    private String pendingMediaType;

    @Override
    public boolean onFragmentCreate() {
        return true;
    }

    @Override
    public View createView(android.content.Context context) {
        actionBar.setTitle("Облачные файлы");
        apiClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(context));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8),
                AndroidUtilities.dp(12), AndroidUtilities.dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        statusView = new TextView(context);
        statusView.setText("Загружаю облачное хранилище…");
        statusView.setTextSize(13);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(statusView, matchWrap());

        Button refreshButton = new Button(context);
        refreshButton.setAllCaps(false);
        refreshButton.setText("Обновить");
        content.addView(refreshButton, matchWrap());
        refreshButton.setOnClickListener(view -> loadPage(true));

        fileList = new LinearLayout(context);
        fileList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = matchWrap();
        listParams.topMargin = AndroidUtilities.dp(8);
        content.addView(fileList, listParams);

        loadMoreButton = new Button(context);
        loadMoreButton.setAllCaps(false);
        loadMoreButton.setText("Загрузить ещё");
        loadMoreButton.setVisibility(View.GONE);
        content.addView(loadMoreButton, matchWrap());
        loadMoreButton.setOnClickListener(view -> loadPage(false));

        fragmentView = scroll;
        loadPage(true);
        return scroll;
    }

    private void loadPage(boolean reset) {
        if (apiClient == null || requestInFlight) return;
        if (reset) {
            nextOffset = 0;
            fileList.removeAllViews();
            statusView.setText("Загружаю облачное хранилище…");
        } else if (!hasMore) {
            return;
        }

        requestInFlight = true;
        loadMoreButton.setEnabled(false);
        apiClient.get("/api/media/storage?limit=" + PAGE_SIZE + "&offset=" + nextOffset,
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        long used = response.optLong("used_bytes", 0);
                        long quota = response.optLong("quota_bytes", 0);
                        int total = response.optInt("total_files", 0);
                        JSONArray files = response.optJSONArray("files");
                        int count = files == null ? 0 : files.length();
                        statusView.setText("Занято " + formatBytes(used) + " из "
                                + formatBytes(quota) + " · файлов: " + total);
                        for (int i = 0; i < count; i++) {
                            JSONObject item = files.optJSONObject(i);
                            if (item != null) addFile(item);
                        }
                        nextOffset = response.optInt("next_offset", nextOffset + count);
                        hasMore = response.optBoolean("has_more", false);
                        loadMoreButton.setVisibility(hasMore ? View.VISIBLE : View.GONE);
                        loadMoreButton.setEnabled(hasMore);
                        if (count == 0 && total == 0) {
                            TextView empty = new TextView(getParentActivity());
                            empty.setText("В облачном хранилище пока нет файлов.");
                            empty.setTextSize(14);
                            fileList.addView(empty, matchWrap());
                        }
                        requestInFlight = false;
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        requestInFlight = false;
                        loadMoreButton.setEnabled(hasMore);
                        statusView.setText(message);
                        if (statusCode == 401 && getParentActivity() != null) {
                            WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                            presentFragment(new WaysGramLoginFragment(), true);
                        }
                    }
                });
    }

    private void addFile(JSONObject item) {
        String mediaId = item.optString("id", "");
        if (!mediaId.matches("[a-f0-9]{32}")) return;
        String name = item.optString("original_name", "waysgram_file");
        String mimeType = item.optString("mime_type", "application/octet-stream");
        long size = item.optLong("size_bytes", 0);
        long createdAt = item.optLong("created_at", 0);
        int messageRefs = item.optInt("message_references", 0);
        int avatarRefs = item.optInt("avatar_references", 0);
        boolean canDelete = item.optBoolean("can_delete", false);

        LinearLayout card = new LinearLayout(getParentActivity());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8),
                AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        card.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(8), Theme.getColor(Theme.key_windowBackgroundWhite)
        ));

        TextView title = new TextView(getParentActivity());
        title.setText(name);
        title.setTextSize(14);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        card.addView(title, matchWrap());

        TextView metadata = new TextView(getParentActivity());
        metadata.setText(mimeType + " · " + formatBytes(size)
                + (createdAt > 0 ? " · " + formatDate(createdAt) : ""));
        metadata.setTextSize(12);
        metadata.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        card.addView(metadata, matchWrap());

        TextView usage = new TextView(getParentActivity());
        usage.setText("В сообщениях: " + messageRefs + " · в аватарах: " + avatarRefs);
        usage.setTextSize(12);
        usage.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        card.addView(usage, matchWrap());

        LinearLayout actions = new LinearLayout(getParentActivity());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button download = new Button(getParentActivity());
        download.setAllCaps(false);
        download.setText("Скачать");
        actions.addView(download, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        download.setOnClickListener(view -> saveMedia(mediaId, name, mimeType));

        if (canDelete) {
            Button delete = new Button(getParentActivity());
            delete.setAllCaps(false);
            delete.setText("Удалить");
            actions.addView(delete, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ));
            delete.setOnClickListener(view -> confirmDelete(mediaId, name));
        } else {
            TextView protectedLabel = new TextView(getParentActivity());
            protectedLabel.setText("Используется");
            protectedLabel.setTextSize(12);
            protectedLabel.setGravity(android.view.Gravity.CENTER);
            actions.addView(protectedLabel, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ));
        }
        card.addView(actions, matchWrap());

        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.bottomMargin = AndroidUtilities.dp(8);
        fileList.addView(card, cardParams);
    }

    private void confirmDelete(String mediaId, String name) {
        if (getParentActivity() == null) return;
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Удалить файл?")
                .setMessage("Файл «" + name + "» будет удалён из облачного хранилища.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> deleteMedia(mediaId))
                .show();
    }

    private void deleteMedia(String mediaId) {
        if (apiClient == null || requestInFlight) return;
        requestInFlight = true;
        statusView.setText("Удаляю файл…");
        apiClient.delete("/api/media/storage/" + mediaId, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                requestInFlight = false;
                long freed = response.optLong("freed_bytes", 0);
                statusView.setText("Файл удалён · освобождено " + formatBytes(freed));
                loadPage(true);
            }

            @Override
            public void onError(String message, int statusCode) {
                requestInFlight = false;
                statusView.setText(message);
                if (statusCode == 409) loadPage(true);
            }
        });
    }

    private void saveMedia(String mediaId, String name, String mediaType) {
        pendingMediaId = mediaId;
        pendingMediaName = name == null || name.isEmpty() ? "waysgram_file" : name;
        pendingMediaType = mediaType == null || mediaType.isEmpty()
                ? "application/octet-stream" : mediaType;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(pendingMediaType);
        intent.putExtra(Intent.EXTRA_TITLE, pendingMediaName);
        startActivityForResult(intent, REQUEST_SAVE_MEDIA);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_SAVE_MEDIA) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pendingMediaId = null;
            return;
        }
        if (pendingMediaId == null || !pendingMediaId.matches("[a-f0-9]{32}")) {
            statusView.setText("Некорректный ID медиафайла.");
            pendingMediaId = null;
            return;
        }
        ContentResolver resolver = getParentActivity().getContentResolver();
        String mediaId = pendingMediaId;
        statusView.setText("Сохраняю файл…");
        apiClient.downloadDocumentToUri(resolver, data.getData(), mediaId,
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        pendingMediaId = null;
                        statusView.setText("Файл сохранён.");
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        statusView.setText(message);
                    }
                });
    }

    private String formatBytes(long value) {
        if (value < 1024) return value + " Б";
        if (value < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f КБ", value / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f МБ", value / (1024.0 * 1024.0));
    }

    private String formatDate(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(timestamp * 1000L));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
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
