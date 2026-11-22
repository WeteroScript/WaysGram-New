package org.telegram.messenger.waysgram;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.RemoteMessage;

import org.telegram.ui.LaunchActivity;

import java.util.Map;

/** Displays WaysGram FCM messages received while the app is in the foreground. */
public final class WaysGramPushNotifications {
    private WaysGramPushNotifications() {
    }

    public static void show(Context context, RemoteMessage message) {
        if (context == null || message == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        WaysGramPushManager.ensureNotificationChannel(context);
        Map<String, String> data = message.getData();
        RemoteMessage.Notification remoteNotification = message.getNotification();
        boolean silent = "1".equals(data.get("silent"));

        String title = remoteNotification == null ? null : remoteNotification.getTitle();
        String body = remoteNotification == null ? null : remoteNotification.getBody();
        if (title == null || title.trim().length() == 0) {
            title = data.get("title");
        }
        if (body == null || body.trim().length() == 0) {
            body = data.get("body");
        }
        if (title == null || title.trim().length() == 0) {
            title = "WaysGram";
        }
        if (body == null || body.trim().length() == 0) {
            body = "Новое сообщение";
        }

        Intent launchIntent = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent == null) {
            launchIntent = new Intent(context, LaunchActivity.class);
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launchIntent.putExtra("waysgram_push", true);
        if ("group_topic".equals(data.get("scope"))) {
            try {
                long group = Long.parseLong(data.get("chat_id"));
                long topic = Long.parseLong(data.get("topic_id"));
                long recipient = Long.parseLong(data.get("account_id"));
                if (group > 0 && topic > 0 && recipient > 0) {
                    launchIntent.setAction("com.tmessages.openchat.waysgram.topic");
                    launchIntent.putExtra("chatId", group);
                    launchIntent.putExtra("waysgram_topic_id", topic);
                    launchIntent.putExtra("waysgram_topic_recipient", recipient);
                }
            } catch (NumberFormatException ignored) { }
        }


        int notificationId = notificationId(message, data);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder notification = new NotificationCompat.Builder(
                context,
                silent ? WaysGramPushManager.QUIET_NOTIFICATION_CHANNEL_ID : WaysGramPushManager.NOTIFICATION_CHANNEL_ID
        )
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(silent ? NotificationCompat.PRIORITY_LOW : NotificationCompat.PRIORITY_HIGH)
                .setSilent(silent)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification.build());
        } catch (SecurityException ignored) {
            // Notification permission can be revoked between the permission check and notify().
        }
    }

    private static int notificationId(RemoteMessage message, Map<String, String> data) {
        String messageId = data.get("message_id");
        if (messageId == null || messageId.length() == 0) {
            messageId = message.getMessageId();
        }
        if (messageId == null || messageId.length() == 0) {
            messageId = String.valueOf(System.currentTimeMillis());
        }
        return (("group_topic".equals(data.get("scope")) ? "topic:" + data.get("chat_id") + ":" + data.get("topic_id") + ":" + messageId : messageId).hashCode()) & 0x7fffffff;
    }
}
