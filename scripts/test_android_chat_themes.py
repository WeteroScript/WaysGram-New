#!/usr/bin/env python3
"""Темы каналов поверх кастомизации и баланс звёзд чата (этап 6).

Пользователь: «основные темы каналов поверх кастомизации, если тема есть, иначе
как обычно» и «баланс звёзд канала/группы копится, но не отображается».
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class ChatThemesChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.names = set(cls.archive.namelist())

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_catalog_and_set_theme_are_served(self):
        themes = self.source("tgnet/WaysGramChatThemes.java")
        # Каталог отдаётся вместо METHOD_NOT_SUPPORTED, тема — с палитрой и градиентом.
        self.assertIn("instanceof TL_account.getChatThemes", themes)
        self.assertIn("TL_account.TL_themes", themes)
        self.assertIn("instanceof TLRPC.TL_messages_setChatTheme", themes)
        self.assertIn("TLRPC.TL_baseThemeDay", themes)
        self.assertIn("TLRPC.TL_baseThemeNight", themes)
        self.assertIn("TLRPC.TL_wallPaperNoFile", themes)
        transport = self.source("tgnet/WaysGramTransport.java")
        self.assertIn("WaysGramChatThemes.handle(account, request)", transport)

    def test_theme_is_stored_on_server(self):
        themes = self.source("tgnet/WaysGramChatThemes.java")
        self.assertIn('WaysGramApi.put(account, "/api/chats/" + chatId + "/theme", body)', themes)
        self.assertIn("WaysGramChats.isGroupChannel(chatId)", themes)

    def test_theme_overrides_customization(self):
        chat = self.source("ui/ChatActivity.java")
        body = chat[chat.index("private void updateBackground() {"):]
        body = body[: body.index("\n    }")]
        self.assertIn("ChatThemeController.getInstance(currentAccount).getDialogTheme(dialog_id) != null", body)
        self.assertLess(body.index("getDialogTheme(dialog_id)"), body.index("WaysGramCustomizationActivity.chatWallpaper"))
        # Тема канала, выбранная на другом устройстве, подтягивается при открытии чата.
        self.assertIn("private void restoreWaysGramChatTheme()", chat)
        self.assertIn("restoreWaysGramChatTheme();", chat)
        self.assertIn("WaysGramChatThemes.loadTheme(account, serverChatId)", chat)
        self.assertIn("setChatThemeEmoticon(TLRPC.ChatTheme.ofEmoticon(emoticon))", chat)

    def test_chat_stars_balance_is_never_displayed(self):
        # В клиенте нет ни поля, ни экрана с балансом звёзд канала: он только копится.
        wg = [name for name in self.names
              if name.endswith(".java") and ("/WaysGram" in name or "/waysgram/" in name or "/tgnet/WaysGram" in name)]
        self.assertTrue(wg)
        for name in wg:
            text = self.archive.read(name).decode("utf-8", "ignore")
            self.assertNotIn("chat_stars", text, name)
            self.assertNotIn("chatStars", text, name)


if __name__ == "__main__":
    unittest.main()
