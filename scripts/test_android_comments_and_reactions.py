#!/usr/bin/env python3
"""Комментарии под постами канала и единый каталог реакций.

Проверки статические и по архиву `WaysGram-Android.zip.part*`: правка, не попавшая
в архив, не увидит ни компилятор, ни приложение.

Что проверяем:

* у нового канала сразу есть связанная группа комментариев (`comments`, по умолчанию
  включено) — иначе у постов нет кнопки комментариев вообще;
* каналу, созданному раньше, группу можно выбрать или создать из настроек через
  штатный `ChatLinkActivity` с поиском и нативной строкой «Создать группу»;
* выбор проходит через WaysGram transport: `getGroupsForDiscussion`, `createChat`
  и `setDiscussionGroup` отображаются на backend-группы, а не на Telegram RPC;
* серверный вайтлист реакций совпадает с палитрой приложения: `reactions.json`
  собирается из архива, а не пишется руками.
"""
import json
import re
import subprocess
import sys
import unittest
import zipfile
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"
SERVER = ROOT / "authbot" / "messenger_api.py"
CATALOG = ROOT / "authbot" / "assets" / "reactions.json"

sys.path.insert(0, str(ROOT / "scripts"))


def archive_bytes() -> bytes:
    parts = sorted(ROOT.glob("WaysGram-Android.zip.part*"), key=lambda path: path.name)
    return b"".join(part.read_bytes() for part in parts)


class CommentsAndReactions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = zipfile.ZipFile(BytesIO(archive_bytes()))
        cls.server = SERVER.read_text(encoding="utf-8")
        cls.discussions = cls.read(JAVA + "ui/WaysGramDiscussions.java")
        cls.chat_link = cls.read(JAVA + "ui/ChatLinkActivity.java")
        cls.transport = cls.read(JAVA + "tgnet/WaysGramTransport.java")
        cls.chats = cls.read(JAVA + "tgnet/WaysGramChats.java")
        cls.edit = cls.read(JAVA + "ui/ChatEditActivity.java")
        cls.strings = cls.read("WaysGram-Android/TMessagesProj/src/main/res/values/strings.xml")

    @classmethod
    def read(cls, path: str) -> str:
        return cls.archive.read(path).decode("utf-8")

    # ------------------------------------------------------------ комментарии

    def test_new_channel_creates_a_discussion_group(self):
        self.assertIn('comments = payload.get("comments", True)', self.server)
        self.assertIn("create_channel_discussion_group(", self.server)

    def test_existing_channel_can_create_a_group(self):
        self.assertIn("new ChatLinkActivity(channel, true)", self.discussions)
        self.assertIn("new GroupCreateFinalActivity(args)", self.chat_link)
        self.assertIn("linkChat(getMessagesController().getChat(chatId), fragment);", self.chat_link)
        self.assertIn("request instanceof TLRPC.TL_messages_createChat", self.transport)
        self.assertIn("WaysGramChats.createChat(account", self.transport)

    def test_no_dead_end_without_groups(self):
        self.assertNotIn("WaysGramDiscussionNoGroups", self.discussions)
        self.assertIn("public ChatLinkActivity(long chatId, boolean chooseDiscussionGroup)", self.chat_link)
        self.assertIn("if (info.linked_chat_id != 0 || chooseDiscussionGroup)", self.chat_link)
        self.assertIn("if (position == createChatRow)", self.chat_link)
        self.assertIn("R.string.DiscussionCreateGroup", self.chat_link)
        self.assertIn("WaysGramChats.discussionGroups(account, selfId)", self.transport)
        self.assertIn("WaysGramChats.setDiscussionGroup(account", self.transport)

    def test_channel_settings_open_the_waysgram_dialog(self):
        self.assertIn("WaysGramDiscussions.settings(this, currentAccount, chatId)", self.edit)
        self.assertIn("org.telegram.tgnet.WaysGramTransport.ENABLED", self.edit)

    def test_linked_group_opens_as_a_screen(self):
        self.assertIn("static void openGroup(BaseFragment fragment, int account, long channel, long group)", self.discussions)
        self.assertIn("fragment.presentFragment(ChatActivity.of(-id))", self.discussions)

    def test_linking_refreshes_the_channel(self):
        self.assertIn("loadFullChat(channel, 0, true)", self.discussions)
        self.assertIn("NotificationCenter.dialogsNeedReload", self.discussions)

    def test_posts_show_the_comment_button_and_open_native_threads(self):
        # «Прокомментировать» показывает штатную кнопку обсуждения и открывает native thread,
        # не старый самописный AlertDialog.
        chats = self.read(JAVA + "tgnet/WaysGramChats.java")
        self.assertIn("channel.has_link = true;", chats)
        self.assertIn("channel.flags |= TLObject.FLAG_20;", chats)
        self.assertIn("replies.channel_id = discussionGroup;", chats)
        self.assertIn("message.replies = replies;", chats)
        chat = self.read(JAVA + "ui/ChatActivity.java")
        self.assertIn("public void didPressCommentButton(ChatMessageCell cell)", chat)
        self.assertIn("openDiscussionMessageChat(currentChat.id, message, message.getId(), linkedChatId, maxReadId, 0, null);", chat)
        self.assertNotIn("WaysGramDiscussions.open(", chat)

    def test_strings_exist(self):
        for name in ("DiscussionCreateGroup", "WaysGramDiscussionEnabled"):
            self.assertIn(f'name="{name}"', self.strings)

    # -------------------------------------------------------------- реакции

    def test_server_takes_the_catalog_from_the_file(self):
        self.assertIn("REACTION_CATALOG_FILE", self.server)
        self.assertIn("ALL_REACTIONS", self.server)
        self.assertIn("BASIC_REACTIONS", self.server)
        # Ручных списков в маршрутах больше нет.
        self.assertNotIn('allowed = _reaction_catalog({"👍"', self.server)

    def test_catalog_matches_the_client_palette(self):
        code = (
            "import sys, json, pathlib;"
            f"sys.path.insert(0, {str(ROOT / 'scripts')!r});"
            "import generate_reaction_catalog as gen;"
            "print(json.dumps(gen.build(), ensure_ascii=False))"
        )
        generated = json.loads(subprocess.check_output([sys.executable, "-c", code], text=True))
        current = json.loads(CATALOG.read_text(encoding="utf-8"))
        self.assertEqual(generated, current, "reactions.json устарел: запустите scripts/generate_reaction_catalog.py")

    def test_palette_is_wide_enough(self):
        data = json.loads(CATALOG.read_text(encoding="utf-8"))
        keys = {"".join(c for c in value if c != "\ufe0f") for value in data["catalog"]}
        for emoji in ("👌", "🥱", "🥴", "😴", "🦄", "💊", "🌭", "🍓", "🎃", "🎊", "✨"):
            self.assertIn(emoji, keys, f"{emoji} снова отклонится")

    def test_archive_still_has_the_full_string_table(self):
        """Зеркало `android-source/` отстаёт от архива: пересборка не должна терять строки."""
        for name in ("WaysGramTransferChannel", "WaysGramPaidHistoryTitle", "WaysGramChannelTopics"):
            self.assertIn(f'name="{name}"', self.strings)


if __name__ == "__main__":
    unittest.main()
