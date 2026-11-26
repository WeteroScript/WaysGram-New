package org.telegram.tgnet;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Minimal HTTPS client for the WaysGram backend (AuthBot REST API).
 * Everything the app stores or reads lives on the WaysGram server; there is no connection to any other backend.
 */
public class WaysGramApi {

    public static class ApiException extends Exception {
        public final int status;
        public final String detail;

        public ApiException(int status, String detail) {
            super(detail);
            this.status = status;
            this.detail = detail;
        }
    }

    private static final String PREFS = "waysgram_session";

    /**
     * Network work runs on its own threads. The app's shared background queue must never wait for the network:
     * a slow server would otherwise freeze unrelated parts of the app.
     */
    public static final ExecutorService NETWORK = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "waysgram-net");
        thread.setDaemon(true);
        return thread;
    });

    public static final ScheduledExecutorService POLLER = Executors.newScheduledThreadPool(org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT, runnable -> {
        Thread thread = new Thread(runnable, "waysgram-poll");
        thread.setDaemon(true);
        return thread;
    });

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getToken(int account) {
        return prefs().getString("token_" + account, null);
    }

    public static void saveToken(int account, String token, long expiresAt) {
        boolean changed = !TextUtils.equals(getToken(account), token);
        prefs().edit().putString("token_" + account, token).putLong("expires_" + account, expiresAt).apply();
        if (changed) org.telegram.messenger.waysgram.WaysGramPeerBadges.clearAccount(account);
    }

    public static void clearToken(int account) {
        prefs().edit().remove("token_" + account).remove("expires_" + account).apply();
        org.telegram.messenger.waysgram.WaysGramPeerBadges.clearAccount(account);
    }

    public static long getTokenExpiresAt(int account) {
        return prefs().getLong("expires_" + account, 0);
    }

    /** The server extends a session while it is still valid; the token itself never changes. */
    private static final long SESSION_REFRESH_GRACE_SECONDS = 7 * 24 * 60 * 60;
    private static final java.util.concurrent.atomic.AtomicLong LAST_REFRESH =
            new java.util.concurrent.atomic.AtomicLong(0);

    public static void refreshTokenIfNeeded(int account) {
        String token = getToken(account);
        if (TextUtils.isEmpty(token)) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        long expires = getTokenExpiresAt(account);
        if (expires > 0 && expires - now > SESSION_REFRESH_GRACE_SECONDS) {
            return;
        }
        long last = LAST_REFRESH.get();
        if (now - last < 120 || !LAST_REFRESH.compareAndSet(last, now)) {
            return;
        }
        try {
            JSONObject response = request("POST", "/api/auth/refresh", new JSONObject(), token, 20000);
            long renewed = response.optLong("expires_at", 0);
            if (renewed > 0) {
                saveToken(account, token, renewed);
            }
        } catch (ApiException e) {
            // An unusable session is reported by the next normal request as 401.
            FileLog.d("waysgram: session refresh failed (" + e.status + ")");
        }
    }

    /** The phone the server confirmed, so the UI shows the number before the profile object loads. */
    public static void rememberPhone(int account, String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (digits.length() < 8 || digits.length() > 15) {
            return;
        }
        prefs().edit().putString("phone_" + account, digits).apply();
    }

    public static String getRememberedPhone(int account) {
        return prefs().getString("phone_" + account, "");
    }

    // ---------------------------------------------------------------- administrator flag

    public static boolean isAdmin(int account) {
        return prefs().getBoolean("admin_" + account, false);
    }

    public static void setAdmin(int account, boolean admin) {
        prefs().edit().putBoolean("admin_" + account, admin).putBoolean("admin_known_" + account, true).apply();
    }

    /** Own profile from the server; remembers whether this account is the administrator. Off the UI thread. */
    public static JSONObject me(int account) throws ApiException {
        JSONObject json = get(account, "/api/me");
        if (json.has("is_admin")) {
            setAdmin(account, json.optBoolean("is_admin"));
        }
        rememberPhone(account, json.optString("phone", ""));
        return json;
    }

    public static void ensureAdminKnown(int account) {
        if (!prefs().getBoolean("admin_known_" + account, false)) {
            try {
                me(account);
            } catch (ApiException ignore) {
            }
        }
    }

    public static JSONObject get(int account, String path) throws ApiException {
        return request("GET", path, null, getToken(account), 30000);
    }

    public static JSONObject post(int account, String path, JSONObject body) throws ApiException {
        return request("POST", path, body, getToken(account), 30000);
    }

    public static JSONObject patch(int account, String path, JSONObject body) throws ApiException {
        return request("PATCH", path, body, getToken(account), 30000);
    }

    public static JSONObject put(int account, String path, JSONObject body) throws ApiException {
        return request("PUT", path, body, getToken(account), 30000);
    }

    public static JSONObject delete(int account, String path) throws ApiException {
        return request("DELETE", path, null, getToken(account), 30000);
    }

    public static JSONObject delete(int account, String path, JSONObject body) throws ApiException {
        return request("DELETE", path, body, getToken(account), 30000);
    }

    /** Synchronous. Must be called off the UI thread. Returns the parsed JSON object (arrays are wrapped as {"items": [...]}). */
    public static JSONObject request(String method, String path, JSONObject body, String token, int timeoutMs) throws ApiException {
        return request(method, path, body, token, timeoutMs, null);
    }

    public static JSONObject postIdempotent(int account, String path, JSONObject body, String idempotencyKey) throws ApiException {
        return request("POST", path, body, getToken(account), 30000, idempotencyKey);
    }

    public static JSONObject request(String method, String path, JSONObject body, String token, int timeoutMs, String idempotencyKey) throws ApiException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(BuildVars.WAYSGRAM_API_BASE_URL + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod(method); // Android's HttpURLConnection (OkHttp-backed) supports PATCH
            connection.setRequestProperty("Accept", "application/json");
            if (!TextUtils.isEmpty(token)) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (!TextUtils.isEmpty(idempotencyKey)) {
                connection.setRequestProperty("Idempotency-Key", idempotencyKey);
            }
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream is = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = is == null ? "" : readAll(is);
            if (status >= 400) {
                throw new ApiException(status, extractDetail(text, status));
            }
            if (text.isEmpty()) {
                return new JSONObject();
            }
            String trimmed = text.trim();
            if (trimmed.startsWith("[")) {
                JSONObject wrapper = new JSONObject();
                wrapper.put("items", new JSONArray(trimmed));
                return wrapper;
            }
            return new JSONObject(trimmed);
        } catch (ApiException e) {
            throw e;
        } catch (JSONException e) {
            FileLog.e(e);
            throw new ApiException(502, "BAD_RESPONSE");
        } catch (Exception e) {
            FileLog.e(e);
            throw new ApiException(-1000, describe(e));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Synchronous raw-body upload (media). Must be called off the UI thread. */
    public static JSONObject upload(int account, String path, byte[] data, String contentType, String filename) throws ApiException {
        return upload(account, path, data, contentType, filename, null, null);
    }

    public static JSONObject upload(int account, String path, byte[] data, String contentType, String filename,
                                    String mediaKind, JSONObject metadata) throws ApiException {
        return uploadWithToken(path, data, contentType, filename, mediaKind, metadata, getToken(account));
    }

    public static JSONObject uploadWithToken(String path, byte[] data, String contentType, String filename,
                                             String mediaKind, JSONObject metadata, String token) throws ApiException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(BuildVars.WAYSGRAM_API_BASE_URL + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", contentType);
            if (!TextUtils.isEmpty(filename)) {
                connection.setRequestProperty("X-Filename", java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20"));
            }
            if (!TextUtils.isEmpty(mediaKind)) connection.setRequestProperty("X-Media-Kind", mediaKind);
            if (metadata != null && metadata.length() > 0) {
                connection.setRequestProperty("X-Media-Metadata", android.util.Base64.encodeToString(
                        metadata.toString().getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
            }
            if (!TextUtils.isEmpty(token)) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(data);
            }
            int status = connection.getResponseCode();
            InputStream is = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = is == null ? "" : readAll(is);
            if (status >= 400) {
                throw new ApiException(status, extractDetail(text, status));
            }
            return new JSONObject(text);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            FileLog.e(e);
            throw new ApiException(-1000, describe(e));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Synchronous binary download (for example gift stickers). Must be called off the UI thread. */
    public static byte[] download(int account, String path) throws ApiException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(BuildVars.WAYSGRAM_API_BASE_URL + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept-Encoding", "identity");
            String token = getToken(account);
            if (!TextUtils.isEmpty(token)) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            int status = connection.getResponseCode();
            if (status >= 400) {
                InputStream err = connection.getErrorStream();
                throw new ApiException(status, extractDetail(err == null ? "" : readAll(err), status));
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                int expected = connection.getContentLength();
                if (out.size() == 0 || (expected >= 0 && expected != out.size())) {
                    throw new ApiException(502, "INCOMPLETE_DOWNLOAD");
                }
                return out.toByteArray();
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            FileLog.e(e);
            throw new ApiException(-1000, describe(e));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** A short technical reason for a failed connection, shown to the user on the login screen. */
    private static String describe(Exception e) {
        String name = e.getClass().getSimpleName();
        String message = e.getMessage();
        if (message != null && !message.isEmpty()) {
            if (message.length() > 80) {
                message = message.substring(0, 80);
            }
            return name + ": " + message;
        }
        return name;
    }

    private static String readAll(InputStream is) throws Exception {
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static String extractDetail(String text, int status) {
        try {
            JSONObject o = new JSONObject(text);
            Object d = o.opt("detail");
            if (d instanceof String) {
                return (String) d;
            }
            if (d != null) {
                return d.toString();
            }
        } catch (Exception ignore) {
        }
        return "HTTP_" + status;
    }
}
