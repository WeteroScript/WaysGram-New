package org.telegram.tgnet;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_fragment;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Коллекционные юзернеймы — наш собственный рынок вместо fragment.com.
 *
 * <p>Публичного API у Fragment нет, поэтому WaysGram повторяет механику сам: сервер
 * хранит владельца имени, продажу по фикс-цене, аукцион и пополнение звёзд за TON.
 * Здесь только тонкая обёртка над REST: ни баланс, ни владение клиент не считает —
 * всё решает сервер, а клиент показывает ответ.</p>
 */
public final class WaysGramFragment {

    public static final String SECTION_MARKET = "market";
    public static final String SECTION_AUCTIONS = "auctions";
    public static final String SECTION_MINE = "mine";

    private WaysGramFragment() {
    }

    /** Ветка транспорта: штатные запросы Fragment к другим серверам не уходят. */
    public static TLObject handle(int account, TLObject request) throws WaysGramApi.ApiException {
        if (request instanceof TL_fragment.TL_getCollectibleInfo) {
            TL_fragment.InputCollectible input = ((TL_fragment.TL_getCollectibleInfo) request).collectible;
            String name = null;
            if (input instanceof TL_fragment.TL_inputCollectibleUsername) {
                name = ((TL_fragment.TL_inputCollectibleUsername) input).username;
            } else if (input instanceof TL_fragment.TL_inputCollectiblePhone) {
                name = ((TL_fragment.TL_inputCollectiblePhone) input).phone;
            }
            if (TextUtils.isEmpty(name)) {
                throw new WaysGramApi.ApiException(404, "COLLECTIBLE_NOT_FOUND");
            }
            return info(account, name);
        }
        return null;
    }

    public static String path(String name) {
        String clean = name == null ? "" : name.trim().replace("@", "").toLowerCase(Locale.ROOT);
        try {
            return URLEncoder.encode(clean, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return clean;
        }
    }

    /**
     * Карточка для штатного {@code FragmentUsernameBottomSheet}: дата покупки, цена и ссылка.
     * Форма ответа — {@link TL_fragment.TL_collectibleInfo}, поэтому профиль показывает карточку
     * без правок интерфейса.
     */
    public static TL_fragment.TL_collectibleInfo info(int account, String name) throws WaysGramApi.ApiException {
        JSONObject card = WaysGramApi.get(account, "/api/collectibles/" + path(name));
        TL_fragment.TL_collectibleInfo info = new TL_fragment.TL_collectibleInfo();
        info.purchase_date = card.optInt("purchase_date");
        info.currency = "STARS";
        info.amount = card.optLong("price_stars");
        info.crypto_currency = "TON";
        info.crypto_amount = 0;
        String url = card.optString("share_url");
        info.url = TextUtils.isEmpty(url) ? "https://waysgram.app/" + path(name) : url;
        return info;
    }

    public static JSONObject market(int account, String section) throws WaysGramApi.ApiException {
        String value = TextUtils.isEmpty(section) ? SECTION_MARKET : section;
        return WaysGramApi.get(account, "/api/collectibles?section=" + value);
    }

    public static JSONObject card(int account, String name) throws WaysGramApi.ApiException {
        return WaysGramApi.get(account, "/api/collectibles/" + path(name));
    }

    public static JSONObject bids(int account, String name) throws WaysGramApi.ApiException {
        return WaysGramApi.get(account, "/api/collectibles/" + path(name) + "/bids");
    }

    public static JSONObject buy(int account, String name) throws WaysGramApi.ApiException {
        return WaysGramApi.postIdempotent(account, "/api/collectibles/" + path(name) + "/buy",
                new JSONObject(), key("buy", name));
    }

    public static JSONObject claim(int account, String name) throws WaysGramApi.ApiException {
        return WaysGramApi.postIdempotent(account, "/api/collectibles/" + path(name) + "/claim",
                new JSONObject(), key("claim", name));
    }

    public static JSONObject list(int account, String name, int priceStars) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("price_stars", priceStars);
        } catch (Exception ignore) {
        }
        return WaysGramApi.put(account, "/api/collectibles/" + path(name) + "/listing", body);
    }

    public static JSONObject unlist(int account, String name) throws WaysGramApi.ApiException {
        return WaysGramApi.delete(account, "/api/collectibles/" + path(name) + "/listing");
    }

    public static JSONObject startAuction(int account, String name, int minBidStars, int hours)
            throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("min_bid_stars", minBidStars);
            body.put("duration_hours", hours);
        } catch (Exception ignore) {
        }
        return WaysGramApi.postIdempotent(account, "/api/collectibles/" + path(name) + "/auction",
                body, key("auction", name));
    }

    public static JSONObject cancelAuction(int account, String name) throws WaysGramApi.ApiException {
        return WaysGramApi.delete(account, "/api/collectibles/" + path(name) + "/auction");
    }

    public static JSONObject bid(int account, String name, int amountStars) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("amount_stars", amountStars);
        } catch (Exception ignore) {
        }
        return WaysGramApi.postIdempotent(account, "/api/collectibles/" + path(name) + "/bid",
                body, key("bid", name));
    }

    public static JSONObject ton(int account) throws WaysGramApi.ApiException {
        return WaysGramApi.get(account, "/api/me/ton");
    }

    public static JSONObject withdraw(int account, int stars, String address) throws WaysGramApi.ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("stars", stars);
            body.put("address", address == null ? "" : address.trim());
        } catch (Exception ignore) {
        }
        return WaysGramApi.post(account, "/api/me/ton-withdraw", body);
    }

    /**
     * Ключ идемпотентности: сервер принимает 16–128 символов из латиницы, цифр,
     * точки, двоеточия, дефиса и подчёркивания. Двойной тап по «Купить» с одним
     * ключом не спишет звёзды дважды.
     */
    public static String key(String action, String name) {
        String clean = name == null ? "x" : name.replaceAll("[^a-zA-Z0-9_-]", "").toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) {
            clean = "x";
        }
        return "wg-" + action + "-" + clean + "-" + System.currentTimeMillis();
    }

    /** Текст ошибки сервера: рынок отвечает {@code {"detail":{"code","message"}}}. */
    public static String errorMessage(WaysGramApi.ApiException error, String fallback) {
        if (error == null || TextUtils.isEmpty(error.detail)) {
            return fallback;
        }
        String detail = error.detail.trim();
        if (detail.startsWith("{")) {
            try {
                JSONObject object = new JSONObject(detail);
                String message = object.optString("message");
                if (!TextUtils.isEmpty(message)) {
                    return message;
                }
                String code = object.optString("code");
                if (!TextUtils.isEmpty(code)) {
                    return code;
                }
            } catch (Exception ignore) {
            }
        }
        if (detail.startsWith("HTTP_") || detail.length() > 200) {
            return fallback;
        }
        return detail;
    }

    public static JSONObject first(JSONArray rows, int index) {
        return rows == null ? null : rows.optJSONObject(index);
    }
}
