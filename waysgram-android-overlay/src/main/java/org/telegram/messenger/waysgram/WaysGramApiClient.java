package org.telegram.messenger.waysgram;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Authenticated JSON client for WaysGram's own REST API.
 * API sessions are read from Android Keystore-backed WaysGramSessionStore.
 */
public final class WaysGramApiClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_DOCUMENT_BYTES = 25 * 1024 * 1024;

    public interface Callback {
        void onSuccess(JSONObject response);
        void onError(String message, int statusCode);
    }

    public interface ImageCallback {
        void onSuccess(Bitmap bitmap);
        void onError(String message, int statusCode);
    }

    public void uploadDocument(ContentResolver resolver, Uri uri, Callback callback) {
        ioExecutor.execute(() -> {
            try {
                JSONObject response = performDocumentUpload(resolver, uri);
                deliverSuccess(callback, response);
            } catch (ApiException exception) {
                deliverError(callback, exception.getMessage(), exception.statusCode);
            } catch (Exception exception) {
                deliverError(callback, "Не удалось загрузить документ", 0);
            }
        });
    }

    public void downloadDocumentToUri(ContentResolver resolver, Uri destination,
                                      String mediaId, Callback callback) {
        ioExecutor.execute(() -> {
            try {
                JSONObject response = performDocumentDownloadToUri(resolver, destination, mediaId);
                deliverSuccess(callback, response);
            } catch (ApiException exception) {
                deliverError(callback, exception.getMessage(), exception.statusCode);
            } catch (Exception exception) {
                deliverError(callback, "Не удалось сохранить документ", 0);
            }
        });
    }

    private final WaysGramSessionStore sessionStore;
    private final ExecutorService ioExecutor;
    private final ExecutorService longPollExecutor;
    private final ExecutorService eventPollExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public WaysGramApiClient(WaysGramSessionStore sessionStore) {
        if (sessionStore == null) {
            throw new IllegalArgumentException("WaysGramSessionStore is required");
        }
        this.sessionStore = sessionStore;
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WaysGramApi");
            thread.setDaemon(true);
            return thread;
        });
        longPollExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WaysGramLongPoll");
            thread.setDaemon(true);
            return thread;
        });
        eventPollExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WaysGramEventPoll");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void get(String path, Callback callback) {
        execute("GET", path, null, callback);
    }

    /**
     * Runs a bounded long-poll request away from the normal API queue so sends,
     * uploads and draft synchronization are not blocked while the server waits.
     */
    public void getLongPoll(String path, Callback callback) {
        longPollExecutor.execute(() -> {
            try {
                JSONObject response = request("GET", path, null);
                deliverSuccess(callback, response);
            } catch (ApiException exception) {
                deliverError(callback, exception.getMessage(), exception.statusCode);
            } catch (Exception exception) {
                deliverError(callback, "Не удалось получить обновления WaysGram", 0);
            }
        });
    }

    /**
     * Keeps message-change events waiting independently from the message long poll.
     */
    public void getEventLongPoll(String path, Callback callback) {
        eventPollExecutor.execute(() -> {
            try {
                JSONObject response = request("GET", path, null);
                deliverSuccess(callback, response);
            } catch (ApiException exception) {
                deliverError(callback, exception.getMessage(), exception.statusCode);
            } catch (Exception exception) {
                deliverError(callback, "Не удалось получить события WaysGram", 0);
            }
        });
    }

    public void post(String path, JSONObject payload, Callback callback) {
        execute("POST", path, payload == null ? new JSONObject() : payload, callback);
    }

    public void post(String path, JSONObject payload, String idempotencyKey, Callback callback) {
        execute("POST", path, payload == null ? new JSONObject() : payload, callback, idempotencyKey);
    }

    public void put(String path, JSONObject payload, Callback callback) {
        execute("PUT", path, payload == null ? new JSONObject() : payload, callback);
    }

    public void delete(String path, Callback callback) {
        execute("DELETE", path, null, callback);
    }

    public void uploadImage(ContentResolver resolver, Uri uri, Callback callback) {
        ioExecutor.execute(() -> {
            try {
                JSONObject response = performImageUpload(resolver, uri);
                deliverSuccess(callback, response);
            } catch (ApiException exception) {
                deliverError(callback, exception.getMessage(), exception.statusCode);
            } catch (Exception exception) {
                deliverError(callback, "Не удалось загрузить изображение", 0);
            }
        });
    }

    public void downloadImage(String mediaId, ImageCallback callback) {
        ioExecutor.execute(() -> {
            try {
                Bitmap bitmap = performImageDownload(mediaId);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onSuccess(bitmap);
                    }
                });
            } catch (ApiException exception) {
                deliverImageError(callback, exception.getMessage(), exception.statusCode);
            } catch (Exception exception) {
                deliverImageError(callback, "Не удалось загрузить изображение", 0);
            }
        });
    }

    private void execute(String method, String path, JSONObject payload, Callback callback) {
        execute(method, path, payload, callback, null);
    }

    private void execute(
            String method,
            String path,
            JSONObject payload,
            Callback callback,
            String idempotencyKey
    ) {
        ioExecutor.execute(() -> {
            try {
                JSONObject response = request(method, path, payload, idempotencyKey);
                deliverSuccess(callback, response);
            } catch (ApiException exception) {
                deliverError(callback, exception.getMessage(), exception.statusCode);
            } catch (Exception exception) {
                deliverError(callback, "Не удалось связаться с сервером WaysGram", 0);
            }
        });
    }

    private JSONObject performImageUpload(ContentResolver resolver, Uri uri) throws IOException {
        if (resolver == null || uri == null) {
            throw new IOException("Не выбран файл изображения");
        }
        byte[] content;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Не удалось открыть выбранное изображение");
            }
            content = readLimited(input, MAX_IMAGE_BYTES);
        }
        if (content.length == 0) {
            throw new IOException("Файл изображения пуст");
        }
        String mimeType = detectImageMime(content);
        String baseUrl = sessionStore.getApiBaseUrl();
        String token = sessionStore.getWaysGramSessionToken();
        if (baseUrl == null || token == null || token.length() == 0) {
            throw new IOException("Нет активной сессии WaysGram");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/api/media/images").openConnection();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", mimeType);
            connection.setFixedLengthStreamingMode(content.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(content);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            byte[] responseBytes = stream == null ? new byte[0] : readLimited(stream, MAX_RESPONSE_BYTES);
            JSONObject response = parseJsonResponse(responseBytes);
            if (status < 200 || status >= 300) {
                throw new ApiException(status, safeApiMessage(response, status));
            }
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }


    private JSONObject performDocumentUpload(ContentResolver resolver, Uri uri) throws Exception {
        if (resolver == null || uri == null) {
            throw new IOException("Не выбран документ");
        }
        String filename = queryDisplayName(resolver, uri);
        String lowerName = filename.toLowerCase(Locale.ROOT);
        String mimeType;
        if (lowerName.endsWith(".pdf")) {
            mimeType = "application/pdf";
        } else if (lowerName.endsWith(".txt")) {
            mimeType = "text/plain";
        } else if (lowerName.endsWith(".csv")) {
            mimeType = "text/csv";
        } else if (lowerName.endsWith(".zip")) {
            mimeType = "application/zip";
        } else if (lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v")) {
            mimeType = "video/mp4";
        } else if (lowerName.endsWith(".mov")) {
            mimeType = "video/quicktime";
        } else if (lowerName.endsWith(".webm")) {
            mimeType = "video/webm";
        } else if (lowerName.endsWith(".mkv")) {
            mimeType = "video/x-matroska";
        } else if (lowerName.endsWith(".mp3")) {
            mimeType = "audio/mpeg";
        } else if (lowerName.endsWith(".m4a")) {
            mimeType = "audio/mp4";
        } else if (lowerName.endsWith(".ogg")) {
            mimeType = "audio/ogg";
        } else if (lowerName.endsWith(".wav")) {
            mimeType = "audio/wav";
        } else {
            throw new IOException(
                    "Поддерживаются PDF, TXT, CSV, ZIP, MP4, WebM, MOV, MKV, MP3, M4A, OGG и WAV"
            );
        }

        byte[] content;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Не удалось открыть выбранный документ");
            }
            content = readLimited(input, MAX_DOCUMENT_BYTES);
        }
        if (content.length == 0) {
            throw new IOException("Документ пуст");
        }
        String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8.name())
                .replace("+", "%20");
        String baseUrl = sessionStore.getApiBaseUrl();
        String token = sessionStore.getWaysGramSessionToken();
        if (baseUrl == null || token == null || token.length() == 0) {
            throw new IOException("Нет активной сессии WaysGram");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/api/media/documents").openConnection();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", mimeType);
            connection.setRequestProperty("X-Filename", encodedName);
            connection.setFixedLengthStreamingMode(content.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(content);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            byte[] responseBytes = stream == null ? new byte[0] : readLimited(stream, MAX_RESPONSE_BYTES);
            JSONObject response = parseJsonResponse(responseBytes);
            if (status < 200 || status >= 300) {
                throw new ApiException(status, safeApiMessage(response, status));
            }
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject performDocumentDownloadToUri(ContentResolver resolver, Uri destination,
                                                      String mediaId) throws Exception {
        if (resolver == null || destination == null
                || mediaId == null || !mediaId.matches("[a-f0-9]{32}")) {
            throw new IOException("Некорректный адрес сохранения документа");
        }
        String baseUrl = sessionStore.getApiBaseUrl();
        String token = sessionStore.getWaysGramSessionToken();
        if (baseUrl == null || token == null || token.length() == 0) {
            throw new IOException("Нет активной сессии WaysGram");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/api/media/" + mediaId).openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream error = connection.getErrorStream();
                byte[] errorBytes = error == null ? new byte[0] : readLimited(error, MAX_RESPONSE_BYTES);
                JSONObject errorJson = parseJsonResponse(errorBytes);
                throw new ApiException(status, safeApiMessage(errorJson, status));
            }
            byte[] bytes = readLimited(connection.getInputStream(), MAX_DOCUMENT_BYTES);
            try (OutputStream output = resolver.openOutputStream(destination, "w")) {
                if (output == null) {
                    throw new IOException("Не удалось открыть место сохранения документа");
                }
                output.write(bytes);
                output.flush();
            }
            JSONObject result = new JSONObject();
            result.put("saved_bytes", bytes.length);
            return result;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        if (resolver != null && uri != null) {
            try (Cursor cursor = resolver.query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String value = cursor.getString(index);
                        if (value != null && value.trim().length() > 0) {
                            return value.trim();
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall back to the URI's last path segment.
            }
        }
        String fallback = uri == null ? "document.pdf" : uri.getLastPathSegment();
        return fallback == null || fallback.trim().length() == 0 ? "document.pdf" : fallback;
    }

    private Bitmap performImageDownload(String mediaId) throws IOException {
        if (mediaId == null || !mediaId.matches("[a-f0-9]{32}")) {
            throw new IOException("Некорректный идентификатор изображения");
        }
        String baseUrl = sessionStore.getApiBaseUrl();
        String token = sessionStore.getWaysGramSessionToken();
        if (baseUrl == null || token == null || token.length() == 0) {
            throw new IOException("Нет активной сессии WaysGram");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/api/media/" + mediaId).openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream error = connection.getErrorStream();
                byte[] errorBytes = error == null ? new byte[0] : readLimited(error, MAX_RESPONSE_BYTES);
                JSONObject errorJson = parseJsonResponse(errorBytes);
                throw new ApiException(status, safeApiMessage(errorJson, status));
            }
            byte[] bytes = readLimited(connection.getInputStream(), MAX_IMAGE_BYTES);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("Сервер вернул некорректное изображение");
            }
            bounds.inSampleSize = 1;
            while (bounds.outWidth / bounds.inSampleSize > 1600
                    || bounds.outHeight / bounds.inSampleSize > 1600) {
                bounds.inSampleSize *= 2;
            }
            bounds.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bitmap == null) {
                throw new IOException("Не удалось декодировать изображение");
            }
            return bitmap;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String detectImageMime(byte[] content) throws IOException {
        if (content.length >= 3
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (content.length >= 8
                && content[0] == (byte) 0x89
                && content[1] == 0x50
                && content[2] == 0x4e
                && content[3] == 0x47
                && content[4] == 0x0d
                && content[5] == 0x0a
                && content[6] == 0x1a
                && content[7] == 0x0a) {
            return "image/png";
        }
        if (content.length >= 12
                && content[0] == 'R' && content[1] == 'I'
                && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E'
                && content[10] == 'B' && content[11] == 'P') {
            return "image/webp";
        }
        throw new IOException("Поддерживаются только JPEG, PNG и WebP");
    }

    private static JSONObject parseJsonResponse(byte[] bytes) throws IOException {
        try {
            return bytes.length == 0
                    ? new JSONObject()
                    : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IOException("WaysGram вернул некорректный JSON", exception);
        }
    }

    private static String safeApiMessage(JSONObject response, int status) {
        String message = response.optString("detail", "");
        if (message.length() == 0 || message.length() > 240) {
            return "Ошибка WaysGram API: HTTP " + status;
        }
        return message;
    }

    private void deliverImageError(ImageCallback callback, String message, int statusCode) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(message, statusCode));
        }
    }

    private JSONObject request(String method, String path, JSONObject payload) throws IOException {
        return request(method, path, payload, null);
    }

    private JSONObject request(
            String method,
            String path,
            JSONObject payload,
            String idempotencyKey
    ) throws IOException {
        String baseUrl = sessionStore.getApiBaseUrl();
        String token = sessionStore.getWaysGramSessionToken();
        if (baseUrl == null || token == null || token.length() == 0) {
            throw new IOException("Нет активной сессии WaysGram");
        }
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("\\") || path.contains("\n") || path.contains("\r")) {
            throw new IOException("Некорректный путь WaysGram API");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
                connection.setRequestProperty("Idempotency-Key", idempotencyKey.trim());
            }
            if ("POST".equals(method) || "PUT".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            byte[] bytes = stream == null ? new byte[0] : readLimited(stream);
            JSONObject response;
            try {
                response = bytes.length == 0
                        ? new JSONObject()
                        : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new IOException("WaysGram вернул некорректный JSON", exception);
            }
            if (status < 200 || status >= 300) {
                String message = response.optString("detail", "");
                if (message.length() == 0 || message.length() > 240) {
                    message = "Ошибка WaysGram API: HTTP " + status;
                }
                throw new ApiException(status, message);
            }
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maximum) {
                    throw new IOException("WaysGram media file exceeds the allowed size");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("WaysGram response is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void deliverSuccess(Callback callback, JSONObject response) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(response));
        }
    }

    private void deliverError(Callback callback, String message, int statusCode) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(message, statusCode));
        }
    }

    @Override
    public void close() {
        ioExecutor.shutdown();
        longPollExecutor.shutdown();
        eventPollExecutor.shutdown();
    }

    private static final class ApiException extends IOException {
        final int statusCode;

        ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
