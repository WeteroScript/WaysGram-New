package org.telegram.messenger.waysgram;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WaysGram's own virtual-number authentication API.
 *
 * This client only calls the WaysGram AuthBot HTTP API. It does not call
 * Telegram auth.sendCode/auth.signIn and never requests Telegram account codes.
 */
public final class WaysGramAuthClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    public interface Callback {
        void onSuccess(JSONObject response);
        void onError(String message);
    }

    public interface LoginCallback {
        void onSuccess(JSONObject user, long expiresAt);
        void onError(String message);
    }

    private final String apiBaseUrl;
    private final WaysGramSessionStore sessionStore;
    private final ExecutorService ioExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public WaysGramAuthClient(String apiBaseUrl, WaysGramSessionStore sessionStore) {
        this.apiBaseUrl = normalizeBaseUrl(apiBaseUrl);
        if (sessionStore == null) {
            throw new IllegalArgumentException("WaysGramSessionStore is required");
        }
        this.sessionStore = sessionStore;
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WaysGramAuth");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void checkServer(Callback callback) {
        execute(callback, () -> {
            JSONObject health = getHealth();
            JSONObject config = getApiConfig();
            if (!"WaysGram".equals(config.optString("brand"))
                    || config.optInt("api_version", 0) < 1
                    || !"authwaysgram_bot".equals(config.optString("auth"))) {
                throw new IOException("Сервер отвечает, но WaysGram API не прошёл проверку конфигурации");
            }
            health.put("api_version", config.optInt("api_version", 0));
            health.put("authbot_delivery_ready", config.optBoolean("authbot_delivery_ready", false));
            return health;
        });
    }

    public void requestCode(String virtualNumber, Callback callback) {
        final String number;
        try {
            number = normalizeNumber(virtualNumber);
        } catch (Exception exception) {
            deliverError(callback, safeMessage(exception));
            return;
        }
        execute(callback, () -> {
            JSONObject payload = new JSONObject();
            payload.put("phone", number);
            return postJson("/api/auth/request-code", payload, null);
        });
    }

    public void verifyCode(String virtualNumber, String code, LoginCallback callback) {
        final String number;
        final String normalizedCode;
        try {
            number = normalizeNumber(virtualNumber);
            normalizedCode = code == null ? "" : code.trim();
            if (!normalizedCode.matches("[0-9]{6}")) {
                throw new IllegalArgumentException("Введите шестизначный код из AuthWaysGram_bot");
            }
        } catch (Exception exception) {
            deliverLoginError(callback, safeMessage(exception));
            return;
        }

        ioExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("phone", number);
                payload.put("code", normalizedCode);
                JSONObject response = postJson("/api/auth/verify-code", payload, null);
                String token = response.optString("token", "");
                long expiresAt = response.optLong("expires_at", 0L);
                JSONObject user = response.optJSONObject("user");
                if (token.length() < 20 || user == null || expiresAt <= 0) {
                    throw new IOException("WaysGram returned an invalid session");
                }
                sessionStore.saveSession(apiBaseUrl, token);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onSuccess(user, expiresAt);
                    }
                });
            } catch (Exception exception) {
                deliverLoginError(callback, safeMessage(exception));
            }
        });
    }

    public void refreshSession(Callback callback) {
        String token = sessionStore.getWaysGramSessionToken();
        if (token == null || token.length() == 0) {
            deliverError(callback, "Нет активной сессии WaysGram");
            return;
        }
        execute(callback, () -> postJson("/api/auth/refresh", new JSONObject(), token));
    }

    public void logout(Callback callback) {
        String token = sessionStore.getWaysGramSessionToken();
        if (token == null || token.length() == 0) {
            sessionStore.clearSession();
            deliverSuccess(callback, new JSONObject());
            return;
        }
        execute(callback, () -> {
            JSONObject result = postJson("/api/auth/logout", new JSONObject(), token);
            sessionStore.clearSession();
            return result;
        });
    }

    private void execute(Callback callback, RequestAction action) {
        ioExecutor.execute(() -> {
            try {
                JSONObject response = action.run();
                deliverSuccess(callback, response);
            } catch (Exception exception) {
                deliverError(callback, safeMessage(exception));
            }
        });
    }

    private JSONObject getHealth() throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(apiBaseUrl + "/health").openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            byte[] bytes = stream == null ? new byte[0] : readLimited(stream, MAX_RESPONSE_BYTES);
            JSONObject response;
            try {
                response = bytes.length == 0
                        ? new JSONObject()
                        : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new IOException("WaysGram вернул некорректный ответ проверки сервера", exception);
            }
            if (status < 200 || status >= 300) {
                String detail = response.optString("detail", "");
                throw new ApiException(status, detail);
            }
            if (!"online".equals(response.optString("status"))
                    || !"waysgram-authbot".equals(response.optString("service"))) {
                throw new IOException("Адрес не отвечает как сервер WaysGram AuthBot");
            }
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject getApiConfig() throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(apiBaseUrl + "/api/config").openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            byte[] bytes = stream == null ? new byte[0] : readLimited(stream, MAX_RESPONSE_BYTES);
            JSONObject response;
            try {
                response = bytes.length == 0
                        ? new JSONObject()
                        : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new IOException("WaysGram вернул некорректный ответ конфигурации API", exception);
            }
            if (status < 200 || status >= 300) {
                String detail = response.optString("detail", "");
                throw new ApiException(status, detail);
            }
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject postJson(String path, JSONObject payload, String sessionToken) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(apiBaseUrl + path).openConnection();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (sessionToken != null && sessionToken.length() > 0) {
                connection.setRequestProperty("Authorization", "Bearer " + sessionToken);
            }
            byte[] requestBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            byte[] bytes = stream == null ? new byte[0] : readLimited(stream, MAX_RESPONSE_BYTES);
            JSONObject response;
            try {
                response = bytes.length == 0
                        ? new JSONObject()
                        : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new IOException("WaysGram вернул некорректный ответ", exception);
            }
            if (status < 200 || status >= 300) {
                String detail = response.optString("detail", "");
                throw new ApiException(status, detail);
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
                    throw new IOException("WaysGram response exceeds the allowed size");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String normalizeNumber(String value) {
        String number = value == null ? "" : value.replace(" ", "").replace("-", "").trim();
        if (!number.matches("\\+1[0-9]{10}")) {
            throw new IllegalArgumentException("Нужен внутренний номер WaysGram вида +1 и десять цифр");
        }
        return number;
    }

    private static String normalizeBaseUrl(String value) {
        try {
            String base = value == null ? "" : value.trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            URL parsed = new URL(base);
            String protocol = parsed.getProtocol();
            String host = parsed.getHost();
            boolean secure = "https".equalsIgnoreCase(protocol);
            boolean localHttp = "http".equalsIgnoreCase(protocol)
                    && ("127.0.0.1".equals(host)
                    || "localhost".equalsIgnoreCase(host)
                    || "10.0.2.2".equals(host));
            String path = parsed.getPath();
            boolean rootPath = path == null || path.length() == 0 || "/".equals(path);
            if ((!secure && !localHttp) || !rootPath || parsed.getUserInfo() != null
                    || parsed.getQuery() != null || parsed.getRef() != null) {
                throw new IllegalArgumentException("Для удалённого сервера требуется HTTPS");
            }
            return base;
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) exception;
            }
            throw new IllegalArgumentException("Укажите корректный адрес WaysGram API", exception);
        }
    }

    private void deliverSuccess(Callback callback, JSONObject response) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(response));
        }
    }

    private void deliverError(Callback callback, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(message));
        }
    }

    private void deliverLoginError(LoginCallback callback, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(message));
        }
    }

    private static String safeMessage(Exception exception) {
        if (exception instanceof ApiException) {
            ApiException apiException = (ApiException) exception;
            if (apiException.detail.length() > 0 && apiException.detail.length() <= 240) {
                return apiException.detail;
            }
            return "Ошибка WaysGram API: HTTP " + apiException.status;
        }
        if (exception instanceof IllegalArgumentException && exception.getMessage() != null) {
            return exception.getMessage();
        }
        return "Не удалось связаться с сервером WaysGram";
    }

    @Override
    public void close() {
        ioExecutor.shutdownNow();
    }

    private interface RequestAction {
        JSONObject run() throws Exception;
    }

    private static final class ApiException extends IOException {
        final int status;
        final String detail;

        ApiException(int status, String detail) {
            super("HTTP " + status);
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }
    }
}
