#!/usr/bin/env python3
"""Комментарии под постами канала и единый каталог реакций.

Проверки статические и по архиву `WaysGram-Android.zip.part*`: правка, не попавшая
в архив, не увидит ни компилятор, ни приложение.

Что проверяем:

* у нового канала сразу есть связанная группа комментариев (`comments`, по умолчанию
  включено) — иначе у постов нет кнопки комментариев вообще;
* каналу, созданному раньше, группу можно создать из настроек:
  `POST /api/channels/{id}/discussion-group/create` + диалог «Обсуждение» в клиенте
  (`WaysGramDiscussions`) с кнопкой «Создать группу», а не тупик «у вас нет групп»;
* «Обсуждение» в настройках канала открывает этот диалог, а не штатный
  `ChatLinkActivity`, который умеет только выбирать существующие группы;
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
        self.assertIn('/api/channels/{channel_id}/discussion-group/create', self.server)
        self.assertIn('path + "/create"', self.discussions)

    def test_no_dead_end_without_groups(self):
        self.assertNotIn(
            "notice(fragment, R.string.WaysGramDiscussionGroup, R.string.WaysGramDiscussionNoGroups);\n                return;",
            self.discussions,
        )
        self.assertIn("WaysGramDiscussionCreate", self.discussions)

    def test_channel_settings_open_the_waysgram_dialog(self):
        self.assertIn("WaysGramDiscussions.settings(this, currentAccount, chatId)", self.edit)
        self.assertIn("org.telegram.tgnet.WaysGramTransport.ENABLED", self.edit)

    def test_linked_group_opens_as_a_screen(self):
        self.assertIn("static void openGroup(BaseFragment fragment, int account, long channel, long group)", self.discussions)
        self.assertIn("fragment.presentFragment(ChatActivity.of(-id))", self.discussions)

    def test_linking_refreshes_the_channel(self):
        self.assertIn("loadFullChat(channel, 0, true)", self.discussions)
        self.assertIn("NotificationCenter.dialogsNeedReload", self.discussions)

    def test_posts_show_the_comment_button(self):
        # Кнопка «Прокомментировать» под постом требует трёх вещей: has_link у канала,
        # replies с channel_id связанной группы и переход в наши комментарии.
        chats = self.read(JAVA + "tgnet/WaysGramChats.java")
        self.assertIn("channel.has_link = true;", chats)
        self.assertIn("channel.flags |= TLObject.FLAG_20;", chats)
        self.assertIn("replies.channel_id = discussionGroup;", chats)
        self.assertIn("message.replies = replies;", chats)
        chat = self.read(JAVA + "ui/ChatActivity.java")
        self.assertIn("WaysGramDiscussions.open(this, currentAccount, currentChat.id, message.getId())", chat)
        self.assertIn("public void didPressCommentButton(ChatMessageCell cell)", chat)

    def test_strings_exist(self):
        for name in ("WaysGramDiscussionCreate", "WaysGramDiscussionEnabled"):
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
