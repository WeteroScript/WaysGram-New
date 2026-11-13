package org.telegram.messenger.waysgram;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Local, per-session draft storage for WaysGram chats and timelines.
 *
 * Draft text is kept on the device and is never uploaded until the user sends
 * the message. The session token is only hashed to namespace drafts by account.
 */
public final class WaysGramDraftStore {
    private static final String PREFERENCES_NAME = "waysgram_local_drafts";

    private WaysGramDraftStore() {
    }

    public static String load(Context context, String scope, long targetId) {
        String key = preferenceKey(context, scope, targetId);
        if (key == null) {
            return "";
        }
        SharedPreferences preferences = preferences(context);
        return preferences.getString(key, "");
    }

    public static void save(Context context, String scope, long targetId, String value) {
        String key = preferenceKey(context, scope, targetId);
        if (key == null) {
            return;
        }
        String text = value == null ? "" : value;
        SharedPreferences.Editor editor = preferences(context).edit();
        if (text.length() == 0) {
            editor.remove(key);
        } else {
            editor.putString(key, text);
        }
        editor.apply();
    }

    public static void clear(Context context, String scope, long targetId) {
        save(context, scope, targetId, "");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
    }

    private static String preferenceKey(Context context, String scope, long targetId) {
        if (context == null || scope == null || scope.trim().isEmpty() || targetId <= 0) {
            return null;
        }
        String token = WaysGramSessionStore.getInstance(context).getWaysGramSessionToken();
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(token.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(scope.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(targetId).getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder("draft_");
            for (byte value : hash) {
                result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(value & 0x0f, 16));
            }
            return result.toString();
        } catch (Exception exception) {
            return null;
        }
    }
}
