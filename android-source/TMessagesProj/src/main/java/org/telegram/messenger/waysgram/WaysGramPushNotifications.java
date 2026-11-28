package org.telegram.messenger.waysgram;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.RemoteMessage;

import org.telegram.messenger.R;
import org.telegram.tgnet.WaysGramChats;
import org.telegram.tgnet.WaysGramPoller;
import org.telegram.ui.LaunchActivity;

import java.util.Map;

/**
 * Плашки для WaysGram-сообщений, пришедших push-ом, когда приложение не работает.
 *
 * <p>Раньше каждое сообщение показывалось отдельной плашкой с заголовком из payload:
 * два сообщения одного чата висели как две одинаковые строки, а у аккаунтов без имени
 * заголовком становился номер («+00000000»). Теперь плашка одна на чат, они собраны в
 * группу со сводкой, тап открывает именно этот чат.</p>
 *
 * <p>Если пуллер уже работает, push не нужен: сообщения придут штатным путём и
 * {@code NotificationsController} покажет их сам — иначе получались бы дубли.</p>
 */
public final class WaysGramPushNotifications {
    private static final String CHAT_GROUP_PREFIX = "waysgram_chat_";
    private static final int NOTIFICATION_COLOR = 0xff11acfa;

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

        Map<String, String> data = message.getData();
        long recipient = parseLong(data.get("account_id"));
        if (pollerCovers(recipient)) {
            // Живой пуллер доставит сообщение в чат и покажет плашку сам.
            return;
        }

        WaysGramPushManager.ensureNotificationChannel(context);
        RemoteMessage.Notification remoteNotification = message.getNotification();
        boolean silent = "1".equals(data.get("silent"));

        String scope = nonEmpty(data.get("scope"));
        long chatId = parseLong(data.get("chat_id"));
        long topicId = parseLong(data.get("topic_id"));
        int messageId = (int) Math.min(Integer.MAX_VALUE, parseLong(data.get("message_id")));

        String title = firstNonEmpty(
                remoteNotification == null ? null : remoteNotification.getTitle(),
                data.get("title"));
        String body = firstNonEmpty(
                remoteNotification == null ? null : remoteNotification.getBody(),
                data.get("body"));
        if (looksLikePhone(title)) {
            // Номер телефона — не имя: в шторке он выглядит как «+00000000».
            title = "";
        }
        if (title.isEmpty()) {
            title = "WaysGram";
        }
        if (body.isEmpty()) {
            body = "Новое сообщение";
        }

        long dialogId = dialogId(scope, chatId);
        String groupKey = groupKey(recipient);
        String chatKey = groupKey + ":" + scope + ":" + chatId + (topicId > 0 ? ":" + topicId : "");
        int notificationId = chatKey.hashCode() & 0x7fffffff;

        PendingIntent contentIntent = openIntent(
                context, scope, chatId, topicId, dialogId, messageId, recipient, notificationId);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context,
                silent ? WaysGramPushManager.QUIET_NOTIFICATION_CHANNEL_ID
                        : WaysGramPushManager.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(silent ? NotificationCompat.PRIORITY_LOW : NotificationCompat.PRIORITY_HIGH)
                .setSilent(silent)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(message.getSentTime() > 0 ? message.getSentTime() : System.currentTimeMillis())
                .setColor(NOTIFICATION_COLOR)
                .setGroup(groupKey)
                .setContentIntent(contentIntent);

        notify(context, notificationId, builder.build());
        postSummary(context, groupKey, title, body, contentIntent, silent);
    }

    /** Сводка группы: одна строка «WaysGram» вместо отдельных строк на каждый чат. */
    private static void postSummary(
            Context context, String groupKey, String title, String body,
            PendingIntent contentIntent, boolean silent) {
        int chats = 0;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                NotificationManager manager =
                        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                StatusBarNotification[] active = manager == null ? null : manager.getActiveNotifications();
                for (int i = 0; active != null && i < active.length; i++) {
                    String group = active[i].getNotification().getGroup();
                    boolean isSummary = (active[i].getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
                    if (groupKey.equals(group) && !isSummary) {
                        chats++;
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        if (chats == 0) {
            chats = 1;
        }
        NotificationCompat.Builder summary = new NotificationCompat.Builder(
                context,
                silent ? WaysGramPushManager.QUIET_NOTIFICATION_CHANNEL_ID
                        : WaysGramPushManager.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle("WaysGram")
                .setContentText(chats > 1 ? chats + " · " + title : title + ": " + body)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(chats > 1 ? title + ": " + body : title + ": " + body))
                .setNumber(chats)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(silent ? NotificationCompat.PRIORITY_LOW : NotificationCompat.PRIORITY_HIGH)
                .setSilent(silent)
                .setAutoCancel(true)
                .setColor(NOTIFICATION_COLOR)
                .setGroup(groupKey)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setContentIntent(contentIntent);
        notify(context, groupKey.hashCode() & 0x7fffffff, summary.build());
    }

    /**
     * Убирает наши плашки, когда приложение открыто: штатный контроллер чистит только
     * свои уведомления и про push-запасной путь не знает.
     */
    public static void dismissAll(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            StatusBarNotification[] active = manager == null ? null : manager.getActiveNotifications();
            for (int i = 0; active != null && i < active.length; i++) {
                String group = active[i].getNotification().getGroup();
                if (group != null && group.startsWith(CHAT_GROUP_PREFIX)) {
                    manager.cancel(active[i].getTag(), active[i].getId());
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static void notify(Context context, int id, Notification notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification);
        } catch (SecurityException ignored) {
            // Notification permission can be revoked between the permission check and notify().
        }
    }

    /**
     * Тап по плашке открывает свой чат. Для тем сохраняется прежний адрес — по нему
     * LaunchActivity находит аккаунт-получателя и открывает тему.
     */
    private static PendingIntent openIntent(
            Context context,
            String scope,
            long chatId,
            long topicId,
            long dialogId,
            int messageId,
            long recipient,
            int requestCode) {
        Intent launchIntent = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent == null) {
            launchIntent = new Intent(context, LaunchActivity.class);
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launchIntent.putExtra("waysgram_push", true);
        launchIntent.putExtra("waysgram_account", recipient);

        if ("group_topic".equals(scope) && chatId > 0 && topicId > 0 && recipient > 0) {
            launchIntent.setAction("com.tmessages.openchat.waysgram.topic");
            launchIntent.putExtra("chatId", chatId);
            launchIntent.putExtra("waysgram_topic_id", topicId);
            launchIntent.putExtra("waysgram_topic_recipient", recipient);
            launchIntent.putExtra("waysgram_scope", scope);
        } else if (dialogId != 0) {
            launchIntent.setAction("com.tmessages.openchat");
            launchIntent.putExtra("chatId", dialogId);
            launchIntent.putExtra("waysgram_scope", scope);
            if (messageId > 0) {
                launchIntent.putExtra("message_id", messageId);
            }
        }
        return PendingIntent.getActivity(
                context,
                requestCode,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    /**
     * Идентификатор диалога, как его знает клиент: личный чат — сам собеседник,
     * группы и каналы — со знаком минус (канал ещё и со смещением id).
     */
    private static long dialogId(String scope, long chatId) {
        if (chatId <= 0) {
            return 0;
        }
        if ("dm".equals(scope)) {
            return chatId;
        }
        if ("channel".equals(scope)) {
            return -WaysGramChats.channelTg(chatId);
        }
        return -chatId;
    }

    private static String groupKey(long recipient) {
        return CHAT_GROUP_PREFIX + (recipient > 0 ? Long.toString(recipient) : "all");
    }

    private static boolean pollerCovers(long recipient) {
        if (recipient <= 0) {
            return false;
        }
        try {
            for (int account = 0; account < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (org.telegram.messenger.UserConfig.getInstance(account).getClientUserId() == recipient
                        && WaysGramPoller.isRunning(account)) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
            // Сервис мог проснуться без инициализированного ApplicationLoader.
        }
        return false;
    }

    private static boolean looksLikePhone(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        if (text.length() < 5) {
            return false;
        }
        int digits = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits++;
            } else if (ch != '+' && ch != ' ' && ch != '(' && ch != ')' && ch != '-' && ch != '\u00a0') {
                return false;
            }
        }
        return digits >= 5;
    }

    private static String nonEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonEmpty(String first, String second) {
        String value = nonEmpty(first);
        return value.isEmpty() ? nonEmpty(second) : value;
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
