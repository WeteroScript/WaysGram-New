package org.telegram.ui;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.WaysGramApi;

import java.util.HashMap;

/**
 * «Кто может присылать мне подарки» живёт на сервере WaysGram отдельным ключом приватности:
 * ключ Telegram StarGiftsAutoSave отвечает только за автопоказ подарков на профиле.
 */
public final class WaysGramGiftPrivacy {
    private WaysGramGiftPrivacy() {}

    public static final String PATH = "/api/me/privacy/gifts";

    private static final HashMap<Integer, String> modes = new HashMap<>();

    public static String mode(int account) {
        String mode = modes.get(account);
        return mode == null ? "everyone" : mode;
    }

    public static void setMode(int account, String mode) {
        if (!TextUtils.isEmpty(mode)) {
            modes.put(account, mode);
        }
    }

    public static int rulesType(int account) {
        String mode = mode(account);
        if ("contacts".equals(mode)) {
            return PrivacyControlActivity.TYPE_CONTACTS;
        }
        if ("nobody".equals(mode)) {
            return PrivacyControlActivity.TYPE_NOBODY;
        }
        return PrivacyControlActivity.TYPE_EVERYBODY;
    }

    public interface ModeSink {
        void onMode(String mode);
    }

    public static void load(int account, ModeSink sink) {
        final String token = WaysGramApi.getToken(account);
        if (TextUtils.isEmpty(token)) {
            return;
        }
        WaysGramApi.NETWORK.execute(() -> {
            String loaded = null;
            try {
                JSONObject data = WaysGramApi.request("GET", PATH, null, token, 20000);
                loaded = data.optString("mode", "");
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (!"everyone".equals(loaded) && !"contacts".equals(loaded) && !"nobody".equals(loaded)) {
                return;
            }
            setMode(account, loaded);
            final String value = loaded;
            AndroidUtilities.runOnUIThread(() -> {
                if (sink != null) {
                    sink.onMode(value);
                }
            });
        });
    }
}
