package org.telegram.messenger.waysgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypted storage for WaysGram's own API session.
 *
 * This store is deliberately independent from Telegram's MTProto account
 * session. It never reads or writes Telegram user authorization data.
 */
public final class WaysGramSessionStore implements WaysGramGiftCatalogClient.SessionProvider {
    private static final String PREFS_NAME = "waysgram_private_session";
    private static final String KEY_ALIAS = "org.telegram.messenger.WaysGramSession.v1";
    private static final String PREF_API_BASE = "api_base_encrypted";
    private static final String PREF_SESSION_TOKEN = "session_token_encrypted";
    private static final int GCM_TAG_BITS = 128;

    private static volatile WaysGramSessionStore instance;

    private final SharedPreferences preferences;

    private WaysGramSessionStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
        );
    }

    public static WaysGramSessionStore getInstance(Context context) {
        WaysGramSessionStore value = instance;
        if (value == null) {
            synchronized (WaysGramSessionStore.class) {
                value = instance;
                if (value == null) {
                    value = new WaysGramSessionStore(context);
                    instance = value;
                }
            }
        }
        return value;
    }

    /**
     * Stores only the WaysGram API base URL and WaysGram session token.
     * Call this after a successful WaysGram auth-code verification.
     */
    public synchronized void saveSession(String apiBaseUrl, String sessionToken)
            throws GeneralSecurityException {
        String base = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        String token = sessionToken == null ? "" : sessionToken.trim();
        if (base.length() == 0 || token.length() == 0) {
            throw new IllegalArgumentException("WaysGram API address and session token are required");
        }
        String encryptedBase = encrypt(base);
        String encryptedToken = encrypt(token);
        preferences.edit()
                .putString(PREF_API_BASE, encryptedBase)
                .putString(PREF_SESSION_TOKEN, encryptedToken)
                .apply();
    }

    public synchronized void clearSession() {
        preferences.edit()
                .remove(PREF_API_BASE)
                .remove(PREF_SESSION_TOKEN)
                .apply();
    }

    public synchronized boolean hasSession() {
        return getWaysGramSessionToken() != null && getApiBaseUrl() != null;
    }

    @Override
    public synchronized String getApiBaseUrl() {
        return decryptPreference(PREF_API_BASE);
    }

    @Override
    public synchronized String getWaysGramSessionToken() {
        return decryptPreference(PREF_SESSION_TOKEN);
    }

    private String decryptPreference(String key) {
        String value = preferences.getString(key, null);
        if (value == null || value.length() == 0) {
            return null;
        }
        try {
            return decrypt(value);
        } catch (GeneralSecurityException | RuntimeException exception) {
            clearSession();
            return null;
        }
    }

    private String encrypt(String value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(1 + iv.length + encrypted.length);
        buffer.put((byte) iv.length);
        buffer.put(iv);
        buffer.put(encrypted);
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
    }

    private String decrypt(String value) throws GeneralSecurityException {
        byte[] encoded = Base64.decode(value, Base64.NO_WRAP);
        if (encoded.length < 2) {
            throw new GeneralSecurityException("Invalid encrypted session record");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        int ivLength = buffer.get() & 0xff;
        if (ivLength < 12 || ivLength > 16 || buffer.remaining() <= ivLength) {
            throw new GeneralSecurityException("Invalid encrypted session IV");
        }
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) {
                return (SecretKey) existing;
            }

            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
            );
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build();
            generator.init(spec);
            return generator.generateKey();
        } catch (GeneralSecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GeneralSecurityException("Android Keystore is unavailable", exception);
        }
    }
}
