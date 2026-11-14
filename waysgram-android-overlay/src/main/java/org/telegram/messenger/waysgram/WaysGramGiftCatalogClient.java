package org.telegram.messenger.waysgram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WaysGram client for Telegram's bot-scoped gift catalog.
 *
 * The base URL and the WaysGram session token are supplied by the application.
 * No Telegram bot token or Telegram file_id is stored in this class or sent to
 * the Android client. Catalog, sticker and purchase callbacks run on this
 * class's IO thread.
 */
public final class WaysGramGiftCatalogClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STICKER_BYTES = 20 * 1024 * 1024;

    public interface SessionProvider {
        String getApiBaseUrl();
        String getWaysGramSessionToken();
    }

    public interface CatalogCallback {
        void onLoaded(List<Gift> gifts, boolean stale, long syncedAt);
        void onError(String message);
    }

    public interface StickerCallback {
        void onLoaded(byte[] bytes, String mimeType);
        void onError(String message);
    }

    public interface PurchaseCallback {
        void onPurchased(JSONObject result);
        void onError(String message);
    }

    public interface InventoryCallback {
        void onLoaded(JSONArray gifts);
        void onError(String message);
    }

    public interface MarketCallback {
        void onLoaded(JSONArray listings);
        void onError(String message);
    }

    public interface ActionCallback {
        void onComplete(JSONObject result);
        void onError(String message);
    }

    public static final class Gift {
        public final String id;
        public final String title;
        public final String emoji;
        public final String currency;
        public final int starCount;
        public final int storePriceStars;
        public final int storeRemaining;
        public final boolean forSale;
        public final Integer upgradeStarCount;
        public final boolean premium;
        public final boolean hasColors;
        public final boolean limited;
        public final boolean soldOut;
        public final boolean auction;
        public final boolean birthday;
        public final boolean requirePremium;
        public final boolean limitedPerUser;
        public final Integer availabilityResale;
        public final Integer convertStars;
        public final Integer lockedUntilDate;
        public final String auctionSlug;
        public final Integer giftsPerRound;
        public final Integer auctionStartDate;
        public final Integer upgradeVariants;
        public final Integer totalCount;
        public final Integer remainingCount;
        public final Integer personalTotalCount;
        public final Integer personalRemainingCount;
        public final int uniqueGiftVariantCount;
        public final String stickerType;
        public final String stickerMimeType;
        public final String stickerUrl;
        public final boolean animatedSticker;
        public final boolean videoSticker;

        private Gift(JSONObject object, String baseUrl) {
            id = object.optString("id", "");
            title = object.optString("title", id);
            emoji = object.optString("emoji", "");
            currency = object.optString("currency", "telegram_stars");
            starCount = Math.max(0, object.optInt("star_count", 0));
            storePriceStars = Math.max(0, object.optInt("price_stars", 0));
            storeRemaining = Math.max(0, object.optInt("remaining", 0));
            forSale = object.optBoolean("for_sale", storePriceStars > 0 && storeRemaining > 0);
            upgradeStarCount = optionalInt(object, "upgrade_star_count");
            premium = object.optBoolean("is_premium", false);
            hasColors = object.optBoolean("has_colors", false);
            limited = object.optBoolean("is_limited", false);
            soldOut = object.optBoolean("sold_out", false);
            auction = object.optBoolean("auction", false);
            birthday = object.optBoolean("birthday", false);
            requirePremium = object.optBoolean("require_premium", false);
            limitedPerUser = object.optBoolean("limited_per_user", false);
            availabilityResale = optionalInt(object, "availability_resale");
            convertStars = optionalInt(object, "convert_stars");
            lockedUntilDate = optionalInt(object, "locked_until_date");
            auctionSlug = object.optString("auction_slug", "");
            giftsPerRound = optionalInt(object, "gifts_per_round");
            auctionStartDate = optionalInt(object, "auction_start_date");
            upgradeVariants = optionalInt(object, "upgrade_variants");
            Integer totalCountValue = optionalInt(object, "availability_total");
            if (totalCountValue == null) {
                totalCountValue = optionalInt(object, "total_count");
            }
            totalCount = totalCountValue;
            Integer remainingCountValue = optionalInt(object, "availability_remains");
            if (remainingCountValue == null) {
                remainingCountValue = optionalInt(object, "remaining_count");
            }
            remainingCount = remainingCountValue;
            personalTotalCount = optionalInt(object, "personal_total_count");
            personalRemainingCount = optionalInt(object, "personal_remaining_count");
            uniqueGiftVariantCount = Math.max(0, object.optInt("unique_gift_variant_count", 0));

            JSONObject sticker = object.optJSONObject("sticker");
            if (sticker == null) {
                sticker = new JSONObject();
            }
            stickerType = sticker.optString("type", "regular");
            stickerMimeType = sticker.optString("mime_type", "application/octet-stream");
            animatedSticker = sticker.optBoolean("is_animated", false);
            videoSticker = sticker.optBoolean("is_video", false);
            String path = sticker.optString("url", "");
            if (path.startsWith("/")) {
                stickerUrl = baseUrl + path;
            } else {
                // The server contract uses relative API paths only. Do not allow
                // a remote catalog response to redirect authenticated sticker
                // downloads to an unrelated host.
                stickerUrl = "";
            }
        }
    }

    private final SessionProvider sessionProvider;
    private final ExecutorService ioExecutor;
    private final ConcurrentHashMap<String, String> purchaseIdempotencyKeys = new ConcurrentHashMap<>();

    public WaysGramGiftCatalogClient(SessionProvider sessionProvider) {
        if (sessionProvider == null) {
            throw new IllegalArgumentException("SessionProvider is required");
        }
        this.sessionProvider = sessionProvider;
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WaysGramGiftCatalog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Loads the cached Telegram catalog from AuthBot. AuthBot refreshes its own
     * cache when the last successful Telegram sync is older than 60 seconds.
     */
    public void loadCatalog(CatalogCallback callback) {
        if (callback == null) {
            return;
        }
        ioExecutor.execute(() -> {
            try {
                String baseUrl = getBaseUrl();
                JSONObject response = requestJson(baseUrl + "/api/telegram/gifts?all=1");
                JSONArray source = response.optJSONArray("gifts");
                if (source == null) {
                    throw new IOException("WaysGram returned no gift list");
                }
                ArrayList<Gift> gifts = new ArrayList<>(source.length());
                for (int i = 0; i < source.length(); i++) {
                    JSONObject item = source.optJSONObject(i);
                    if (item != null && item.optString("id", "").length() > 0) {
                        gifts.add(new Gift(item, baseUrl));
                    }
                }
                callback.onLoaded(
                        Collections.unmodifiableList(gifts),
                        response.optBoolean("stale", false),
                        response.optLong("synced_at", 0L)
                );
            } catch (Exception exception) {
                callback.onError(toSafeError(exception));
            }
        });
    }

    public void loadMyGifts(InventoryCallback callback) {
        if (callback == null) {
            return;
        }
        ioExecutor.execute(() -> {
            try {
                JSONObject response = requestJson(getBaseUrl() + "/api/me/gifts");
                JSONArray gifts = response.optJSONArray("gifts");
                callback.onLoaded(gifts == null ? new JSONArray() : gifts);
            } catch (Exception exception) {
                callback.onError(toSafeError(exception));
            }
        });
    }

    /**
     * Purchases a gift from the WaysGram storefront. Price and supply are
     * controlled only by the AuthBot administrator panel.
     */
    public void purchaseGift(Gift gift, PurchaseCallback callback) {
        if (gift == null || callback == null || !gift.forSale) {
            if (callback != null) {
                callback.onError("Подарок сейчас недоступен для покупки");
            }
            return;
        }
        ioExecutor.execute(() -> {
            try {
                String encodedGiftId = URLEncoder.encode(gift.id, "UTF-8").replace("+", "%20");
                JSONObject payload = new JSONObject();
                String idempotencyKey = purchaseIdempotencyKeys.computeIfAbsent(
                        gift.id,
                        ignored -> "wg-" + UUID.randomUUID().toString()
                );
                JSONObject response = requestJson(
                        getBaseUrl() + "/api/telegram/gifts/" + encodedGiftId + "/purchase",
                        "POST",
                        payload.toString(),
                        idempotencyKey
                );
                purchaseIdempotencyKeys.remove(gift.id, idempotencyKey);
                callback.onPurchased(response);
            } catch (Exception exception) {
                callback.onError(toSafeError(exception));
            }
        });
    }

    public void loadMarket(MarketCallback callback) {
        if (callback == null) {
            return;
        }
        ioExecutor.execute(() -> {
            try {
                JSONObject response = requestJson(getBaseUrl() + "/api/gift-market");
                JSONArray listings = response.optJSONArray("listings");
                callback.onLoaded(listings == null ? new JSONArray() : listings);
            } catch (Exception exception) {
                callback.onError(toSafeError(exception));
            }
        });
    }

    public void loadCollectibleAuctions(MarketCallback callback) {
        if (callback == null) {
            return;
        }
        ioExecutor.execute(() -> {
            try {
                JSONObject response = requestJson(getBaseUrl() + "/api/gift-auctions");
                JSONArray auctions = response.optJSONArray("auctions");
                callback.onLoaded(auctions == null ? new JSONArray() : auctions);
            } catch (Exception exception) {
                callback.onError(toSafeError(exception));
            }
        });
    }

    public void createCollectibleAuction(String serial, int startingPriceGrams,
                                         int durationSeconds, ActionCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("starting_price_grams", startingPriceGrams);
            body.put("duration_seconds", durationSeconds);
        } catch (Exception ignored) {
        }
        runGiftAction("/api/gifts/" + encodePathSegment(serial) + "/auction",
                "POST", body.toString(), callback);
    }

    public void cancelCollectibleAuction(String serial, ActionCallback callback) {
        runGiftAction("/api/gifts/" + encodePathSegment(serial) + "/auction",
                "DELETE", null, callback);
    }

    public void placeCollectibleAuctionBid(int auctionId, int amountGrams,
                                           ActionCallback callback) {
        runGiftAction("/api/gift-auctions/" + auctionId + "/bid",
                "POST", jsonBody("amount_grams", amountGrams), callback);
    }

    public void listGift(String serial, int priceGrams, ActionCallback callback) {
        runGiftAction("/api/gifts/" + encodePathSegment(serial) + "/list", "POST",
                jsonBody("price_grams", priceGrams), callback);
    }

    public void cancelGiftListing(String serial, ActionCallback callback) {
        runGiftAction("/api/gifts/" + encodePathSegment(serial) + "/list", "DELETE", null, callback);
    }

    public void upgradeGift(String serial, ActionCallback callback) {
        runGiftAction("/api/gifts/" + encodePathSegment(serial) + "/upgrade", "POST", "{}", callback);
    }

    public void transferGift(String serial, String recipient, ActionCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("serial_number", serial);
            body.put("recipient", recipient);
        } catch (Exception ignored) {
        }
        runGiftAction("/api/gifts/transfer", "POST", body.toString(), callback);
    }

    public void purchaseMarketGift(int listingId, ActionCallback callback) {
        runGiftAction("/api/gift-market/" + listingId + "/purchase", "POST", "{}", callback);
    }

    private void runGiftAction(String path, String method, String body, ActionCallback callback) {
        if (callback == null) {
            return;
        }
        ioExecutor.execute(() -> {
            try {
                JSONObject response = requestJson(getBaseUrl() + path, method, body, null);
                callback.onComplete(response);
            } catch (Exception exception) {
                callback.onError(toSafeError(exception));
            }
        });
    }

    private static String jsonBody(String key, int value) {
        JSONObject body = new JSONObject();
        try {
            body.put(key, value);
        } catch (Exception ignored) {
        }
        return body.toString();
    }

    private static String encodePathSegment(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Downloads a gift's sticker bytes through the authenticated WaysGram
     * proxy. The Telegram bot token and Telegram file_id never reach the app.
     */
    public void downloadSticker(Gift gift, StickerCallback callback) {
        if (gift == null || callback == null || gift.stickerUrl.length() == 0) {
            if (callback != null) {
                callback.onError("Стикер подарка недоступен");
            }
            return;
        }
        ioExecutor.execute(() -> {
            try {
                byte[] bytes = requestBytes(gift.stickerUrl, MAX_STICKER_BYTES);
                callback.onLoaded(bytes, gift.stickerMimeType);
            } catch (Exception exception) {
                callback.onError(toSafeError(exception));
            }
        });
    }

    private JSONObject requestJson(String url) throws IOException {
        return requestJson(url, "GET", null, null);
    }

    private JSONObject requestJson(
            String url,
            String method,
            String requestBody,
            String idempotencyKey
    ) throws IOException {
        byte[] body = requestBytes(url, MAX_JSON_BYTES, method, requestBody, idempotencyKey);
        try {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IOException("WaysGram returned invalid JSON", exception);
        }
    }

    private byte[] requestBytes(String url, int maxBytes) throws IOException {
        return requestBytes(url, maxBytes, "GET", null, null);
    }

    private byte[] requestBytes(
            String url,
            int maxBytes,
            String method,
            String requestBody,
            String idempotencyKey
    ) throws IOException {
        String token = sessionProvider.getWaysGramSessionToken();
        if (token == null || token.trim().length() == 0) {
            throw new IOException("WaysGram session is missing");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            if (idempotencyKey != null && idempotencyKey.length() > 0) {
                connection.setRequestProperty("Idempotency-Key", idempotencyKey);
            }
            if ("POST".equals(method)) {
                byte[] requestBytes = (requestBody == null ? "" : requestBody).getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(requestBytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(requestBytes);
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new HttpStatusException(status);
            }
            int contentLength = connection.getContentLength();
            if (contentLength > maxBytes) {
                throw new IOException("WaysGram file exceeds the allowed size");
            }
            try (InputStream input = connection.getInputStream()) {
                return readLimited(input, maxBytes);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String getBaseUrl() throws IOException {
        String baseUrl = sessionProvider.getApiBaseUrl();
        if (baseUrl == null || baseUrl.trim().length() == 0) {
            throw new IOException("WaysGram API address is missing");
        }
        baseUrl = baseUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        URL parsed = new URL(baseUrl);
        String protocol = parsed.getProtocol();
        String host = parsed.getHost();
        boolean secure = "https".equalsIgnoreCase(protocol);
        boolean localHttp = "http".equalsIgnoreCase(protocol)
                && ("127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "10.0.2.2".equals(host));
        String basePath = parsed.getPath();
        boolean rootPath = basePath == null || basePath.length() == 0 || "/".equals(basePath);
        if ((!secure && !localHttp) || !rootPath || parsed.getUserInfo() != null
                || parsed.getQuery() != null || parsed.getRef() != null) {
            throw new IOException("WaysGram API must use HTTPS outside local testing");
        }
        return baseUrl;
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("WaysGram response exceeds the allowed size");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Integer optionalInt(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.optInt(key);
    }

    private static String toSafeError(Exception exception) {
        if (exception instanceof HttpStatusException) {
            int status = ((HttpStatusException) exception).statusCode;
            if (status == 409) {
                return "Покупка отклонена: проверьте баланс и наличие подарка";
            }
            if (status == 403) {
                return "Покупка недоступна для этого аккаунта";
            }
            return "WaysGram API returned HTTP " + status;
        }
        if (exception instanceof IOException && exception.getMessage() != null
                && exception.getMessage().startsWith("WaysGram")) {
            return exception.getMessage();
        }
        return "Не удалось выполнить запрос к магазину подарков WaysGram";
    }

    @Override
    public void close() {
        ioExecutor.shutdownNow();
        purchaseIdempotencyKeys.clear();
    }

    private static final class HttpStatusException extends IOException {
        final int statusCode;

        HttpStatusException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }
}
