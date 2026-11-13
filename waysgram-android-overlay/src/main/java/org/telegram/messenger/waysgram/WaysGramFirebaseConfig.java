package org.telegram.messenger.waysgram;

/**
 * Build-generated public Firebase client options for the WaysGram project.
 * A source-only checkout keeps these values empty; the APK workflow fills them
 * from WAYSGRAM_FIREBASE_CONFIG_JSON without touching Telegram's default FCM app.
 */
public final class WaysGramFirebaseConfig {
    public static final String APP_ID = "";
    public static final String API_KEY = "";
    public static final String PROJECT_ID = "";
    public static final String SENDER_ID = "";

    private WaysGramFirebaseConfig() {
    }

    public static boolean isConfigured() {
        return APP_ID.length() > 0 && API_KEY.length() > 0
                && PROJECT_ID.length() > 0 && SENDER_ID.length() > 0;
    }
}
