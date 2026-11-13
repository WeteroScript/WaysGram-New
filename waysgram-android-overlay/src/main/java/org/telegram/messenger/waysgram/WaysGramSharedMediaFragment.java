package org.telegram.messenger.waysgram;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
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

/** Shared attachments browser for a WaysGram conversation, group or channel. */
public final class WaysGramSharedMediaFragment extends BaseFragment {
    private static final int REQUEST_SAVE_MEDIA = 7110;
    private static final int PAGE_SIZE = 50;

    private final String scope;
    private final long peerId;
    private final String targetTitle;

    private WaysGramApiClient apiClient;
    private LinearLayout content;
    private LinearLayout mediaList;
    private TextView statusView;
    private Button loadMoreButton;
    private String mediaKind = "";
    private long beforeId;
    private boolean hasMore;
    private boolean requestInFlight;
    private String pendingMediaId;
    private String pendingMediaName;
    private String pendingMediaType;

    public WaysGramSharedMediaFragment(String scope, long peerId, String targetTitle) {
        if (!("dm".equals(scope) || "group".equals(scope) || "channel".equals(scope))
                || peerId <= 0) {
            throw new IllegalArgumentException("Invalid shared media target");
        }
        this.scope = scope;
        this.peerId = peerId;
        this.targetTitle = targetTitle == null ? "" : targetTitle;
    }

    @Override
    public boolean onFragmentCreate() {
        return true;
    }

    @Override
    public View createView(android.content.Context context) {
        actionBar.setTitle(targetTitle.isEmpty() ? "Общие медиа" : "Медиа · " + targetTitle);
        apiClient = new WaysGramApiClient(WaysGramSessionStore.getInstance(context));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8),
                AndroidUtilities.dp(12), AndroidUtilities.dp(16));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        statusView = new TextView(context);
        statusView.setText("Загружаю медиа…");
        statusView.setTextSize(12);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(statusView, matchWrap());

        LinearLayout filters = new LinearLayout(context);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        addFilterButton(context, filters, "Все", "");
        addFilterButton(context, filters, "Фото", "image");
        addFilterButton(context, filters, "Видео", "video");
        addFilterButton(context, filters, "Аудио", "audio");
        addFilterButton(context, filters, "Файлы", "document");
        content.addView(filters, matchWrap());

        mediaList = new LinearLayout(context);
        mediaList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = matchWrap();
        listParams.topMargin = AndroidUtilities.dp(8);
        content.addView(mediaList, listParams);

        loadMoreButton = new Button(context);
        loadMoreButton.setAllCaps(false);
        loadMoreButton.setText("Загрузить ещё");
        content.addView(loadMoreButton, matchWrap());
        loadMoreButton.setOnClickListener(view -> loadPage(false));

        fragmentView = scroll;
        loadPage(true);
        return scroll;
    }

    private void addFilterButton(android.content.Context context, LinearLayout parent,
                                 String label, String kind) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(11);
        button.setAllCaps(false);
        parent.addView(button, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        ));
        button.setOnClickListener(view -> {
            if (mediaKind.equals(kind)) {
                return;
            }
            mediaKind = kind;
            beforeId = 0;
            hasMore = false;
            loadPage(true);
        });
    }

    private void loadPage(boolean reset) {
        if (apiClient == null || requestInFlight) {
            return;
        }
        if (reset) {
            beforeId = 0;
            mediaList.removeAllViews();
            statusView.setText("Загружаю медиа…");
        } else if (!hasMore) {
            return;
        }
        requestInFlight = true;
        loadMoreButton.setEnabled(false);
        String path = "/api/media/shared?scope=" + scope
                + "&peer_id=" + peerId
                + "&limit=" + PAGE_SIZE;
        if (!mediaKind.isEmpty()) {
            path += "&kind=" + mediaKind;
        }
        if (beforeId > 0) {
            path += "&before_id=" + beforeId;
        }
        apiClient.get(path, new WaysGramApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                requestInFlight = false;
                JSONArray media = response.optJSONArray("media");
                int count = media == null ? 0 : media.length();
                for (int i = 0; i < count; i++) {
                    JSONObject item = media.optJSONObject(i);
                    if (item != null) {
                        addMediaItem(item);
                    }
                }
                hasMore = response.optBoolean("has_more", false);
                long next = response.optLong("next_before_id", 0);
                if (next > 0) {
                    beforeId = next;
                }
                int totalVisible = mediaList.getChildCount();
                statusView.setText(totalVisible == 0
                        ? "В этой переписке нет вложений"
                        : "Загружено вложений: " + totalVisible);
                loadMoreButton.setVisibility(hasMore ? View.VISIBLE : View.GONE);
                loadMoreButton.setEnabled(hasMore);
            }

            @Override
            public void onError(String message, int statusCode) {
                requestInFlight = false;
                statusView.setText(message);
                loadMoreButton.setEnabled(hasMore);
                if (statusCode == 401) {
                    WaysGramSessionStore.getInstance(getParentActivity()).clearSession();
                    presentFragment(new WaysGramLoginFragment(), true);
                }
            }
        });
    }

    private void addMediaItem(JSONObject item) {
        String mediaId = item.optString("media_id", "");
        if (!mediaId.matches("[a-f0-9]{32}")) {
            return;
        }
        String mediaType = item.optString("media_type", "");
        String name = item.optString("media_name", "");
        String body = item.optString("body", "");
        long sender = item.optLong("sender_id", 0);
        long createdAt = item.optLong("created_at", 0);

        LinearLayout card = new LinearLayout(getParentActivity());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8),
                AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        TextView label = new TextView(getParentActivity());
        StringBuilder description = new StringBuilder();
        if (!name.isEmpty()) {
            description.append(name);
        } else if (!body.isEmpty()) {
            description.append(body);
        } else {
            description.append("Вложение");
        }
        if (sender > 0) {
            description.append("\nОтправитель: ").append(sender);
        }
        if (createdAt > 0) {
            description.append("\n").append(formatDate(createdAt));
        }
        label.setText(description.toString());
        label.setTextSize(13);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        card.addView(label, matchWrap());

        if (mediaType.startsWith("image/")) {
            ImageView image = new ImageView(getParentActivity());
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setMaxHeight(AndroidUtilities.dp(260));
            image.setMaxWidth(AndroidUtilities.dp(320));
            LinearLayout.LayoutParams imageParams = matchWrap();
            imageParams.topMargin = AndroidUtilities.dp(6);
            card.addView(image, imageParams);
            apiClient.downloadImage(mediaId, new WaysGramApiClient.ImageCallback() {
                @Override
                public void onSuccess(Bitmap bitmap) {
                    image.setImageBitmap(bitmap);
                }

                @Override
                public void onError(String message, int statusCode) {
                    image.setContentDescription("Изображение недоступно");
                }
            });
            image.setOnClickListener(view -> saveMedia(mediaId, name, mediaType));
        } else {
            Button download = new Button(getParentActivity());
            download.setAllCaps(false);
            download.setText("Сохранить файл");
            card.addView(download, matchWrap());
            download.setOnClickListener(view -> saveMedia(mediaId, name, mediaType));
        }

        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.bottomMargin = AndroidUtilities.dp(6);
        mediaList.addView(card, cardParams);
    }

    private String formatDate(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(timestamp * 1000L));
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
        if (requestCode != REQUEST_SAVE_MEDIA || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        if (pendingMediaId == null || !pendingMediaId.matches("[a-f0-9]{32}")) {
            statusView.setText("Некорректный ID медиафайла.");
            return;
        }
        ContentResolver resolver = getParentActivity().getContentResolver();
        statusView.setText("Сохраняю файл…");
        apiClient.downloadDocumentToUri(resolver, data.getData(), pendingMediaId,
                new WaysGramApiClient.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        statusView.setText("Файл сохранён");
                        pendingMediaId = null;
                    }

                    @Override
                    public void onError(String message, int statusCode) {
                        statusView.setText(message);
                    }
                });
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
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