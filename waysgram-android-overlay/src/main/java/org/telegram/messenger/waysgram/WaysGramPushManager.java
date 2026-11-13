package org.telegram.messenger.waysgram;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.util.List;

/**
 * Registers the WaysGram FCM token against the active WaysGram API session.
 *
 * Telegram remains the default Firebase project. WaysGram uses a secondary
 * FirebaseApp so its notification credentials and token stay separate.
 */
public final class WaysGramPushManager {
    static final String NOTIFICATION_CHANNEL_ID = "waysgram_messages";
    private static final String FIREBASE_APP_NAME = "waysgram-push";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 7312;
    private static final String PERMISSION_PREFS = "waysgram_push_permissions";
    private static final String PERMISSION_REQUESTED = "post_notifications_requested";

    private static final Object FIREBASE_LOCK = new Object();

    private WaysGramPushManager() {
    }

    public static void sync(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        ensureNotificationChannel(appContext);

        WaysGramSessionStore sessionStore = WaysGramSessionStore.getInstance(appContext);
        if (!sessionStore.hasSession() || !WaysGramFirebaseConfig.isConfigured()) {
            return;
        }

        FirebaseMessaging messaging = getWaysGramMessaging(appContext);
        if (messaging == null) {
            return;
        }

        messaging.getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                return;
            }
            String token = task.getResult();
            if (token == null || token.trim().length() == 0 || !sessionStore.hasSession()) {
                return;
            }

            JSONObject payload = new JSONObject();
            try {
                payload.put("token", token);
                payload.put("platform", "android");
            } catch (Exception ignored) {
                return;
            }

            WaysGramApiClient apiClient = new WaysGramApiClient(sessionStore);
            apiClient.post("/api/push/register", payload, new WaysGramApiClient.Callback() {
                @Override
                public void onSuccess(JSONObject response) {
                    apiClient.close();
                }

                @Override
                public void onError(String message, int statusCode) {
                    apiClient.close();
                }
            });
        });
    }

    public static void requestNotificationPermission(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33
                || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        SharedPreferences preferences = activity.getSharedPreferences(
                PERMISSION_PREFS,
                Context.MODE_PRIVATE
        );
        if (preferences.getBoolean(PERMISSION_REQUESTED, false)) {
            return;
        }
        preferences.edit().putBoolean(PERMISSION_REQUESTED, true).apply();
        activity.requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST
        );
    }

    static void ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26 || context == null) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WaysGram messages",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Notifications for WaysGram chats, groups and channels");
        manager.createNotificationChannel(channel);
    }

    private static FirebaseMessaging getWaysGramMessaging(Context context) {
        if (context == null || !WaysGramFirebaseConfig.isConfigured()) {
            return null;
        }

        synchronized (FIREBASE_LOCK) {
            FirebaseApp app = null;
            List<FirebaseApp> apps = FirebaseApp.getApps(context);
            for (FirebaseApp candidate : apps) {
                if (FIREBASE_APP_NAME.equals(candidate.getName())) {
                    app = candidate;
                    break;
                }
            }

            if (app == null) {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setApplicationId(WaysGramFirebaseConfig.APP_ID)
                        .setApiKey(WaysGramFirebaseConfig.API_KEY)
                        .setProjectId(WaysGramFirebaseConfig.PROJECT_ID)
                        .setGcmSenderId(WaysGramFirebaseConfig.SENDER_ID)
                        .build();
                app = FirebaseApp.initializeApp(context, options, FIREBASE_APP_NAME);
            }

            if (app == null) {
                return null;
            }

            // FirebaseMessaging.getInstance(FirebaseApp) is package-private in
            // the pinned SDK. FirebaseApp.get(Class) is a public-but-hidden
            // @KeepForSdk workaround; keep the Firebase Messaging version pinned
            // and verify this integration after any SDK upgrade.
            return app.get(FirebaseMessaging.class);
        }
    }
}
