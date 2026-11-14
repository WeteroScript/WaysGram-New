package org.telegram.messenger.waysgram;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client-side GIPHY search dialog. Search results are filtered to G-rated content. */
public final class WaysGramGifSearchDialog {
    public interface Callback {
        void onGifSelected(Uri mp4File);
        void onError(String message);
    }

    private WaysGramGifSearchDialog() {
    }

    public static void show(Context context, Callback callback) {
        if (context == null || callback == null) return;
        new Controller(context, callback).show();
    }

    private static final class Controller {
        private static final int PAGE_SIZE = 15;
        private static final int MAX_GIF_BYTES = 25 * 1024 * 1024;
        private static final String BASE_URL = "https://api.giphy.com/v1/gifs/search";

        private final Context context;
        private final Callback callback;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService executor = Executors.newFixedThreadPool(3);
        private final LinearLayout results;
        private EditText queryInput;
        private TextView status;
        private Button searchButton;
        private Button moreButton;
        private AlertDialog dialog;
        private String lastQuery = "";
        private int offset;
        private boolean requestInFlight;
        private boolean closed;

        Controller(Context context, Callback callback) {
            this.context = context;
            this.callback = callback;
            this.results = new LinearLayout(context);
            this.results.setOrientation(LinearLayout.VERTICAL);
            results.setOrientation(LinearLayout.VERTICAL);
        }

        void show() {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(10);
            root.setPadding(pad, pad, pad, pad);

            LinearLayout searchRow = new LinearLayout(context);
            searchRow.setOrientation(LinearLayout.HORIZONTAL);
            queryInput = new EditText(context);
            queryInput.setSingleLine(true);
            queryInput.setHint("Поиск GIF");
            searchRow.addView(queryInput, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ));
            searchButton = new Button(context);
            searchButton.setText("Найти");
            searchButton.setAllCaps(false);
            searchRow.addView(searchButton);
            root.addView(searchRow);

            status = new TextView(context);
            status.setTextSize(13);
            root.addView(status, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            scroll.addView(results);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(390), 1f
            ));

            TextView attribution = new TextView(context);
            attribution.setText("Powered by GIPHY");
            attribution.setTextSize(12);
            attribution.setGravity(android.view.Gravity.CENTER);
            attribution.setPadding(0, dp(6), 0, 0);
            root.addView(attribution);

            moreButton = new Button(context);
            moreButton.setText("Загрузить ещё");
            moreButton.setAllCaps(false);
            moreButton.setVisibility(View.GONE);
            root.addView(moreButton);

            dialog = new AlertDialog.Builder(context)
                    .setTitle("GIF")
                    .setView(root)
                    .setNegativeButton("Закрыть", null)
                    .create();
            searchButton.setOnClickListener(view -> search(true));
            queryInput.setOnEditorActionListener((view, actionId, event) -> {
                search(true);
                return true;
            });
            moreButton.setOnClickListener(view -> search(false));
            dialog.setOnDismissListener(ignored -> close());
            dialog.show();

            if (WaysGramGiphyConfig.API_KEY == null || WaysGramGiphyConfig.API_KEY.trim().isEmpty()) {
                status.setText("GIF-поиск пока не настроен: для него нужен GIPHY_API_KEY.");
                searchButton.setEnabled(false);
            } else {
                status.setText("Введите запрос. Для результатов используется рейтинг G.");
                queryInput.requestFocus();
            }
        }

        private void search(boolean reset) {
            if (closed || requestInFlight) return;
            String query = queryInput.getText() == null ? "" : queryInput.getText().toString().trim();
            if (query.isEmpty()) {
                status.setText("Введите слово или фразу для поиска.");
                return;
            }
            if (query.length() > 50) {
                status.setText("Запрос должен быть не длиннее 50 символов.");
                return;
            }
            if (reset || !query.equals(lastQuery)) {
                lastQuery = query;
                offset = 0;
                results.removeAllViews();
                moreButton.setVisibility(View.GONE);
            }
            requestInFlight = true;
            searchButton.setEnabled(false);
            moreButton.setEnabled(false);
            status.setText("Ищу GIF…");
            int requestedOffset = offset;
            executor.execute(() -> {
                try {
                    JSONArray items = fetchResults(query, requestedOffset);
                    main.post(() -> {
                        if (closed) return;
                        int count = items == null ? 0 : items.length();
                        for (int i = 0; i < count; i++) {
                            JSONObject item = items.optJSONObject(i);
                            if (item != null) addResult(item);
                        }
                        offset += count;
                        status.setText(count == 0
                                ? "По этому запросу ничего не найдено."
                                : "Найдено результатов на этой странице: " + count);
                        moreButton.setVisibility(count == PAGE_SIZE ? View.VISIBLE : View.GONE);
                        requestInFlight = false;
                        searchButton.setEnabled(true);
                        moreButton.setEnabled(true);
                    });
                } catch (Exception exception) {
                    main.post(() -> {
                        if (closed) return;
                        status.setText("Не удалось найти GIF: " + safeMessage(exception));
                        requestInFlight = false;
                        searchButton.setEnabled(true);
                        moreButton.setEnabled(true);
                    });
                }
            });
        }

        private JSONArray fetchResults(String query, int pageOffset) throws Exception {
            String encoded = URLEncoder.encode(query, "UTF-8");
            String requestUrl = BASE_URL
                    + "?api_key=" + URLEncoder.encode(WaysGramGiphyConfig.API_KEY.trim(), "UTF-8")
                    + "&q=" + encoded
                    + "&limit=" + PAGE_SIZE
                    + "&offset=" + pageOffset
                    + "&rating=g&lang=ru";
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(requestUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                byte[] bytes = stream == null ? new byte[0] : readBounded(stream, 2 * 1024 * 1024);
                JSONObject response = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException(response.optJSONObject("meta") == null
                            ? "GIPHY вернул HTTP " + code
                            : response.optJSONObject("meta").optString("msg", "GIPHY вернул HTTP " + code));
                }
                JSONArray data = response.optJSONArray("data");
                return data == null ? new JSONArray() : data;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        private void addResult(JSONObject item) {
            JSONObject images = item.optJSONObject("images");
            JSONObject fixed = images == null ? null : images.optJSONObject("fixed_height");
            JSONObject previewData = images == null ? null : images.optJSONObject("fixed_height_small_still");
            JSONObject original = images == null ? null : images.optJSONObject("original");
            String mp4 = fixed == null ? "" : fixed.optString("mp4", "");
            if (mp4.isEmpty() && original != null) mp4 = original.optString("mp4", "");
            if (mp4.isEmpty()) return;
            String preview = previewData == null ? "" : previewData.optString("url", "");
            String title = item.optString("title", "").trim();
            if (title.isEmpty()) title = "GIF";
            final String selectedMp4 = mp4;

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setPadding(dp(4), dp(5), dp(4), dp(5));
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(dp(112), dp(82)));
            TextView label = new TextView(context);
            label.setText(title + "\nНажмите, чтобы отправить");
            label.setTextSize(13);
            label.setPadding(dp(8), 0, dp(4), 0);
            label.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            ));
            results.addView(card, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            View separator = new View(context);
            separator.setBackgroundColor(0x22000000);
            results.addView(separator, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ));
            card.setOnClickListener(view -> downloadSelected(selectedMp4));

            if (!preview.isEmpty() && isGiphyHost(preview)) {
                executor.execute(() -> {
                    Bitmap bitmap = null;
                    try {
                        bitmap = downloadPreview(preview);
                    } catch (Exception ignored) {
                    }
                    Bitmap finalBitmap = bitmap;
                    main.post(() -> {
                        if (!closed && finalBitmap != null) image.setImageBitmap(finalBitmap);
                    });
                });
            }
        }

        private void downloadSelected(String mp4Url) {
            if (requestInFlight || closed) return;
            if (!isGiphyHost(mp4Url)) {
                status.setText("GIPHY вернул неподдерживаемую ссылку на файл.");
                return;
            }
            requestInFlight = true;
            status.setText("Загружаю GIF…");
            executor.execute(() -> {
                try {
                    File file = downloadMp4(mp4Url);
                    Uri uri = Uri.fromFile(file);
                    main.post(() -> {
                        if (closed) {
                            file.delete();
                            return;
                        }
                        close();
                        callback.onGifSelected(uri);
                    });
                } catch (Exception exception) {
                    main.post(() -> {
                        if (closed) return;
                        status.setText("Не удалось загрузить GIF: " + safeMessage(exception));
                        requestInFlight = false;
                    });
                }
            });
        }

        private Bitmap downloadPreview(String value) throws Exception {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(value).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "image/*");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) return null;
                byte[] bytes = readBounded(connection.getInputStream(), 2 * 1024 * 1024);
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        private File downloadMp4(String value) throws Exception {
            HttpURLConnection connection = null;
            File outputFile = null;
            try {
                connection = (HttpURLConnection) new URL(value).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(25000);
                connection.setRequestProperty("Accept", "video/mp4");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                int declaredLength = connection.getContentLength();
                if (declaredLength > MAX_GIF_BYTES) {
                    throw new IllegalStateException("GIF превышает допустимый размер 25 МБ");
                }
                File directory = new File(context.getCacheDir(), "waysgram_gifs");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IllegalStateException("Не удалось подготовить временное хранилище");
                }
                outputFile = File.createTempFile("waysgram_giphy_", ".mp4", directory);
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    int total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_GIF_BYTES) {
                            throw new IllegalStateException("GIF превышает допустимый размер 25 МБ");
                        }
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                }
                if (outputFile.length() < 12) {
                    throw new IllegalStateException("GIPHY вернул пустой MP4");
                }
                return outputFile;
            } catch (Exception exception) {
                if (outputFile != null) outputFile.delete();
                throw exception;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        private boolean isGiphyHost(String value) {
            try {
                URL url = new URL(value);
                String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
                return "https".equalsIgnoreCase(url.getProtocol())
                        && (host.equals("giphy.com") || host.endsWith(".giphy.com"));
            } catch (Exception exception) {
                return false;
            }
        }

        private byte[] readBounded(InputStream input, int maxBytes) throws Exception {
            try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = stream.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) throw new IllegalStateException("Ответ GIPHY слишком большой");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        }

        private int dp(int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        private String safeMessage(Exception exception) {
            String message = exception.getMessage();
            return message == null || message.trim().isEmpty() ? "ошибка сети" : message;
        }

        private void close() {
            if (closed) return;
            closed = true;
            executor.shutdownNow();
        }
    }
}
