package org.telegram.messenger.waysgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.UserConfig;

import java.util.HashMap;
import java.util.Map;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * WaysGram API session for one Telegram account slot.
 *
 * <p>The WaysGram session is created by the application sign-in flow itself
 * ({@code WaysGramTransport} stores {@code token_<account>} in the
 * {@code waysgram_session} preferences while the account is being
 * authorized). Gift screens must use exactly that session: it belongs to the
 * Telegram account that is currently open, so every account sees its own
 * inventory and no separate "sign in to WaysGram again" prompt is needed.</p>
 *
 * <p>Older builds kept a second, global WaysGram token and copied it into
 * every account slot, which made all Telegram accounts display the same gift
 * inventory. Those shared leftovers are removed once by
 * {@link #dropSharedSessionsOnce(Context)}.</p>
 */
public final class WaysGramSessionStore implements WaysGramGiftCatalogClient.SessionProvider {
    private static final String ACCOUNT_PREFS_PREFIX = "waysgram_private_session_account_";
    private static final String LEGACY_PREFS_NAME = "waysgram_private_session";
    private static final String MIGRATION_PREFS_NAME = "waysgram_session_migration";
    private static final String NATIVE_PREFS_NAME = "waysgram_session";
    private static final String NATIVE_TOKEN_PREFIX = "token_";
    private static final String NATIVE_EXPIRES_PREFIX = "expires_";
    private static final String PREF_SHARED_SESSIONS_DROPPED = "shared_sessions_dropped_v2";
    private static final String KEY_ALIAS = "org.telegram.messenger.WaysGramSession.v1";
    private static final String PREF_API_BASE = "api_base_encrypted";
    private static final String PREF_SESSION_TOKEN = "session_token_encrypted";
    private static final String TAG = "WaysGramSessionStore";
    private static final int GCM_TAG_BITS = 128;

    private static final Map<Integer, WaysGramSessionStore> INSTANCES = new HashMap<>();

    private final Context appContext;
    private final SharedPreferences preferences;
    private final int telegramAccount;

    private WaysGramSessionStore(Context context, int telegramAccount) {
        this.appContext = context.getApplicationContext();
        this.telegramAccount = telegramAccount;
        // Every Telegram slot resolves its own WaysGram session; nothing is shared between slots.
        preferences = appContext.getSharedPreferences(
                ACCOUNT_PREFS_PREFIX + telegramAccount, Context.MODE_PRIVATE
        );
        dropSharedSessionsOnce(appContext);
    }

    public static WaysGramSessionStore getInstance(Context context) {
        return getInstance(context, UserConfig.selectedAccount);
    }

    public static synchronized WaysGramSessionStore getInstance(Context context, int telegramAccount) {
        WaysGramSessionStore value = INSTANCES.get(telegramAccount);
        if (value == null) {
            value = new WaysGramSessionStore(context, telegramAccount);
            INSTANCES.put(telegramAccount, value);
        }
        return value;
    }

    public int getTelegramAccount() {
        return telegramAccount;
    }

    /**
     * Deletes every session that older builds shared between account slots.
     *
     * <p>Runs once per installation. After this call the only WaysGram session
     * used by gift screens is the per-account token written during sign-in.</p>
     */
    private static synchronized void dropSharedSessionsOnce(Context context) {
        SharedPreferences migration = context.getSharedPreferences(
                MIGRATION_PREFS_NAME, Context.MODE_PRIVATE
        );
        if (migration.getBoolean(PREF_SHARED_SESSIONS_DROPPED, false)) {
            return;
        }
        try {
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().clear().commit();
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                context.getSharedPreferences(
                        ACCOUNT_PREFS_PREFIX + account, Context.MODE_PRIVATE
                ).edit().clear().commit();
            }
            migration.edit().clear()
                    .putBoolean(PREF_SHARED_SESSIONS_DROPPED, true)
                    .commit();
            Log.i(TAG, "Removed WaysGram sessions that older builds shared between accounts");
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to clear shared WaysGram sessions", exception);
        }
    }

    /**
     * Stores only the WaysGram API base URL and WaysGram session token.
     * Kept for compatibility with the optional WaysGram sign-in client; the
     * per-account sign-in token is preferred whenever it is available.
     */
    public synchronized void saveSession(String apiBaseUrl, String sessionToken)
            throws GeneralSecurityException {
        String base = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        String token = sessionToken == null ? "" : sessionToken.trim();
        if (base.length() == 0 || token.length() == 0) {
            throw new IllegalArgumentException("WaysGram API address and session token are required");
        }
        Log.i(TAG, "Saving WaysGram session (apiBasePresent=true, tokenPresent=true)");
        try {
            String encryptedBase = encrypt(base);
            String encryptedToken = encrypt(token);
            boolean committed = preferences.edit()
                    .putString(PREF_API_BASE, encryptedBase)
                    .putString(PREF_SESSION_TOKEN, encryptedToken)
                    .commit();
            if (!committed) {
                throw new GeneralSecurityException("Android did not persist the encrypted WaysGram session");
            }
            Log.i(TAG, "WaysGram session preferences committed");
        } catch (GeneralSecurityException | RuntimeException exception) {
            Log.e(TAG, "Failed to encrypt and save WaysGram session (" +
                    exception.getClass().getSimpleName() + ")", exception);
            throw exception;
        }
    }

    public synchronized void clearSession() {
        Log.w(TAG, "Clearing encrypted WaysGram session preferences");
        preferences.edit()
                .remove(PREF_API_BASE)
                .remove(PREF_SESSION_TOKEN)
                .apply();
    }

    public synchronized boolean hasSession() {
        String token = getWaysGramSessionToken();
        return token != null && token.trim().length() > 0;
    }

    @Override
    public synchronized String getApiBaseUrl() {
        String savedBase = decryptPreference(PREF_API_BASE);
        if (savedBase != null && !savedBase.trim().isEmpty()) {
            return savedBase.trim();
        }
        String configured = BuildVars.WAYSGRAM_API_BASE_URL;
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return null;
    }

    @Override
    public synchronized String getWaysGramSessionToken() {
        String nativeToken = readNativeToken();
        if (nativeToken != null && !nativeToken.trim().isEmpty()) {
            return nativeToken.trim();
        }
        String storedToken = decryptPreference(PREF_SESSION_TOKEN);
        return storedToken == null || storedToken.trim().isEmpty() ? null : storedToken.trim();
    }

    /** WaysGram token written by the application sign-in flow for this account slot. */
    private String readNativeToken() {
        if (appContext == null) {
            return null;
        }
        try {
            SharedPreferences nativePreferences = appContext.getSharedPreferences(
                    NATIVE_PREFS_NAME, Context.MODE_PRIVATE
            );
            String token = nativePreferences.getString(
                    NATIVE_TOKEN_PREFIX + telegramAccount, null
            );
            long expiresAt = nativePreferences.getLong(
                    NATIVE_EXPIRES_PREFIX + telegramAccount, 0L
            );
            if (token == null || token.trim().isEmpty()) {
                return null;
            }
            if (expiresAt > 0 && expiresAt < System.currentTimeMillis() / 1000L) {
                // An expired sign-in token is useless; let the caller report the
                // inventory error instead of opening a second login screen.
                return null;
            }
            return token;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to read the WaysGram sign-in token", exception);
            return null;
        }
    }

    private String decryptPreference(String key) {
        String value;
        try {
            value = preferences.getString(key, null);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to read session preference " + key, exception);
            return null;
        }
        if (value == null || value.length() == 0) {
            return null;
        }
        try {
            String decrypted = decrypt(value);
            if (decrypted == null || decrypted.length() == 0) {
                return null;
            }
            Log.d(TAG, "Successfully decrypted session preference: " + key);
            return decrypted;
        } catch (GeneralSecurityException | RuntimeException exception) {
            Log.e(TAG, "Unable to decrypt session preference " + key + " ("
                    + exception.getClass().getSimpleName() + "); dropping the stored value",
                    exception);
            // Only the optional private copy is dropped here. The account's
            // sign-in session is never touched from this path.
            preferences.edit().remove(key).apply();
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
