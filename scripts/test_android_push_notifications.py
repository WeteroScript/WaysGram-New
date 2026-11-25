#!/usr/bin/env python3
"""Плашки push-сообщений: один чат — одна плашка, имя вместо номера, переход в чат.

Скриншот из шторки: две строки «+00000000» от аккаунтов без имени, каждая на своё
сообщение, без перехода в чат. Причины и правки:

* заголовок брался из payload как есть, а у аккаунтов, заведённых администратором,
  в `display_name` лежал номер телефона → сервер больше не отдаёт телефон в заголовке;
* `notificationId` считался по `message_id`, поэтому каждое сообщение висело отдельной
  плашкой → id теперь по чату, плашки собраны в группу со сводкой;
* тап открывал приложение на списке чатов → добавлен переход в нужный чат и аккаунт.
"""
import re
import unittest
import zipfile
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "authbot" / "messenger_api.py"
BOT = ROOT / "authbot" / "bot.py"


def archive_bytes() -> bytes:
    parts = sorted(ROOT.glob("WaysGram-Android.zip.part*"), key=lambda path: path.name)
    return b"".join(part.read_bytes() for part in parts)


def source(java_path: str) -> str:
    zip_file = zipfile.ZipFile(BytesIO(archive_bytes()))
    return zip_file.read("WaysGram-Android/TMessagesProj/src/main/java/org/telegram/" + java_path).decode("utf-8")


class PushNotificationChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.notifications = source("messenger/waysgram/WaysGramPushNotifications.java")
        cls.launch = source("ui/LaunchActivity.java")
        cls.controller = source("messenger/NotificationsController.java")
        cls.poller = source("tgnet/WaysGramPoller.java")
        cls.server = SERVER.read_text(encoding="utf-8")
        cls.bot = BOT.read_text(encoding="utf-8")

    # ------------------------------------------------------------- заголовок

    def test_client_rejects_a_phone_as_a_title(self):
        self.assertIn("private static boolean looksLikePhone(String value)", self.notifications)
        self.assertIn("title = \"\";", self.notifications)
        # Текст из payload больше не берётся в заголо­вок без проверки.
        self.assertLess(
            self.notifications.index("looksLikePhone(title)"),
            self.notifications.index("if (title.isEmpty())"),
        )

    def test_server_never_sends_a_phone_as_a_title(self):
        self.assertIn("def looks_like_phone_title(", self.server)
        self.assertIn("def sender_push_title(", self.server)
        self.assertIn("def push_title(", self.server)
        self.assertIn("title = push_title(title, \"WaysGram\")", self.server)
        self.assertNotIn('str(user.get("display_name")', self.server)

    def test_admin_created_account_does_not_get_the_number_as_a_name(self):
        admin = self.bot.split("async def admin_create_account", 1)[1].split("async def ", 1)[0]
        self.assertIn("name_or_empty(number)", admin)
        self.assertNotIn('"", number, stars', admin)

    # --------------------------------------------------------------- плашки

    def test_one_notification_per_chat(self):
        self.assertIn("String chatKey = groupKey + \":\" + scope + \":\" + chatId", self.notifications)
        self.assertIn("int notificationId = chatKey.hashCode() & 0x7fffffff;", self.notifications)
        self.assertNotIn("messageId).hashCode()", self.notifications)

    def test_notifications_are_grouped_with_a_summary(self):
        self.assertIn(".setGroup(groupKey)", self.notifications)
        self.assertIn(".setGroupSummary(true)", self.notifications)
        self.assertIn("postSummary(", self.notifications)
        self.assertIn("private static String groupKey(long recipient)", self.notifications)

    def test_tap_opens_the_chat_and_the_right_account(self):
        self.assertIn('launchIntent.setAction("com.tmessages.openchat")', self.notifications)
        self.assertIn('launchIntent.putExtra("chatId", dialogId)', self.notifications)
        self.assertIn('launchIntent.putExtra("waysgram_account", recipient)', self.notifications)
        self.assertIn("intent.getLongExtra(\"waysgram_account\", -1)", self.launch)
        # Канал в клиенте живёт под id со смещением.
        self.assertIn("-WaysGramChats.channelTg(chatId)", self.notifications)

    def test_system_icon_is_replaced_with_the_app_icon(self):
        self.assertIn("R.drawable.notification", self.notifications)
        self.assertNotIn("android.R.drawable.stat_notify_chat", self.notifications)

    def test_live_poller_takes_over_and_stale_plates_are_cleared(self):
        self.assertIn("public static boolean isRunning(int account)", self.poller)
        self.assertIn("if (pollerCovers(recipient))", self.notifications)
        self.assertIn("WaysGramPushNotifications.dismissAll", self.controller)
        self.assertIn("public static void dismissAll(Context context)", self.notifications)

    # ---------------------------------------------------------------- данные

    def test_push_data_always_carries_the_account(self):
        delivery = self.server.split("async def deliver_push_notifications", 1)[1].split(
            "async def ", 1)[0]
        self.assertIn('message_data = {**notification_data, "account_id": str(device_account)}',
                      delivery)
        self.assertIn('"title": title', delivery)
        self.assertIn('"body": body', delivery)


if __name__ == "__main__":
    unittest.main()
