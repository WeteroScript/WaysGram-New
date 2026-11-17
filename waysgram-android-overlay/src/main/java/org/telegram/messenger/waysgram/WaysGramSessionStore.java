package org.telegram.messenger.waysgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

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
 * Encrypted storage for WaysGram's own API session.
 *
 * This store is deliberately independent from Telegram's MTProto account
 * session. It never reads or writes Telegram user authorization data.
 */
public final class WaysGramSessionStore implements WaysGramGiftCatalogClient.SessionProvider {
    private static final String LEGACY_PREFS_NAME = "waysgram_private_session";
    private static final String ACCOUNT_PREFS_PREFIX = "waysgram_private_session_account_";
    private static final String MIGRATION_PREFS_NAME = "waysgram_session_migration";
    private static final String PREF_LEGACY_OWNER_ACCOUNT = "legacy_session_owner_account";
    private static final String KEY_ALIAS = "org.telegram.messenger.WaysGramSession.v1";
    private static final String PREF_API_BASE = "api_base_encrypted";
    private static final String PREF_SESSION_TOKEN = "session_token_encrypted";
    private static final String TAG = "WaysGramSessionStore";
    private static final int GCM_TAG_BITS = 128;

    private static final Map<Integer, WaysGramSessionStore> INSTANCES = new HashMap<>();

    private final SharedPreferences preferences;
    private final int telegramAccount;

    private WaysGramSessionStore(Context context, int telegramAccount) {
        Context appContext = context.getApplicationContext();
        this.telegramAccount = telegramAccount;
        preferences = appContext.getSharedPreferences(
                ACCOUNT_PREFS_PREFIX + telegramAccount,
                Context.MODE_PRIVATE
        );
        migrateLegacySessionOnce(appContext);
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
        value.clearCopiedLegacySessionForNonOwner(context);
        return value;
    }

    public int getTelegramAccount() {
        return telegramAccount;
    }

    private void clearCopiedLegacySessionForNonOwner(Context context) {
        SharedPreferences migration = context.getApplicationContext().getSharedPreferences(
                MIGRATION_PREFS_NAME, Context.MODE_PRIVATE
        );
        String owner = migration.getString(PREF_LEGACY_OWNER_ACCOUNT, null);
        if (owner == null || owner.equals(Integer.toString(telegramAccount))) {
            return;
        }
        SharedPreferences legacy = context.getApplicationContext().getSharedPreferences(
                LEGACY_PREFS_NAME, Context.MODE_PRIVATE
        );
        String legacyToken = legacy.getString(PREF_SESSION_TOKEN, null);
        String storedToken = preferences.getString(PREF_SESSION_TOKEN, null);
        // Previous app releases could have copied the same global token into every
        // account-scoped preference file. Remove only exact copies of that legacy
        // token from non-owner slots; independently authenticated tokens survive.
        if (hasText(legacyToken) && legacyToken.equals(storedToken)) {
            Log.w(TAG, "Removing duplicated legacy WaysGram session from Telegram account "
                    + telegramAccount);
            preferences.edit()
                    .remove(PREF_API_BASE)
                    .remove(PREF_SESSION_TOKEN)
                    .apply();
        }
    }

    private void migrateLegacySessionOnce(Context context) {
        SharedPreferences migration = context.getSharedPreferences(
                MIGRATION_PREFS_NAME, Context.MODE_PRIVATE
        );
        // Before account-scoped storage was introduced, all Telegram slots read
        // the same WaysGram token. Copy that legacy token to exactly one slot:
        // the Telegram account selected when the upgrade is first opened.
        // Never fan one WaysGram identity out to every Telegram account.
        if (migration.contains(PREF_LEGACY_OWNER_ACCOUNT)
                || telegramAccount != UserConfig.selectedAccount) {
            return;
        }

        SharedPreferences legacy = context.getSharedPreferences(
                LEGACY_PREFS_NAME, Context.MODE_PRIVATE
        );
        String legacyToken = legacy.getString(PREF_SESSION_TOKEN, null);
        String legacyBase = legacy.getString(PREF_API_BASE, null);
        boolean needsToken = !hasText(preferences.getString(PREF_SESSION_TOKEN, null));
        boolean needsBase = !hasText(preferences.getString(PREF_API_BASE, null));

        if (needsBase && hasText(legacyBase) || needsToken && hasText(legacyToken)) {
            SharedPreferences.Editor editor = preferences.edit();
            if (needsBase && hasText(legacyBase)) {
                editor.putString(PREF_API_BASE, legacyBase);
            }
            if (needsToken && hasText(legacyToken)) {
                editor.putString(PREF_SESSION_TOKEN, legacyToken);
            }
            if (!editor.commit()) {
                Log.e(TAG, "Unable to migrate the legacy WaysGram session for Telegram account "
                        + telegramAccount);
                return;
            }
        }

        // Persist the chosen owner even when this slot already had its own
        // session. That prevents a later account switch from claiming the
        // old global token as a second account's identity.
        if (!migration.edit()
                .putString(PREF_LEGACY_OWNER_ACCOUNT, Integer.toString(telegramAccount))
                .commit()) {
            Log.e(TAG, "Unable to persist the legacy WaysGram session owner");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
        return getWaysGramSessionToken() != null && getApiBaseUrl() != null;
    }

    @Override
    public synchronized String getApiBaseUrl() {
        String savedBase = decryptPreference(PREF_API_BASE);
        if (savedBase != null && !savedBase.trim().isEmpty()) {
            return savedBase;
        }
        String configuredDefault = WaysGramLoginSheet.getDefaultApiBaseUrl();
        if (configuredDefault != null && !configuredDefault.trim().isEmpty()) {
            Log.w(TAG, "Stored API base URL is missing; using the build-configured public API origin");
            return configuredDefault.trim();
        }
        return null;
    }

    @Override
    public synchronized String getWaysGramSessionToken() {
        return decryptPreference(PREF_SESSION_TOKEN);
    }

    private String decryptPreference(String key) {
        String value = preferences.getString(key, null);
        if (value == null || value.length() == 0) {
            Log.w(TAG, "Encrypted session preference is missing: " + key);
            return null;
        }
        try {
            String decrypted = decrypt(value);
            if (decrypted == null || decrypted.length() == 0) {
                Log.e(TAG, "Decrypted session preference is empty: " + key);
                return null;
            }
            Log.d(TAG, "Successfully decrypted session preference: " + key);
            return decrypted;
        } catch (GeneralSecurityException | RuntimeException exception) {
            Log.e(TAG, "Unable to decrypt session preference " + key + " (" +
                    exception.getClass().getSimpleName() + "); clearing the WaysGram session",
                    exception);
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
