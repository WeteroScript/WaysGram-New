#!/usr/bin/env python3
"""Короткие ссылки-приглашения t.wg/<код> из устройства.

С устройства: переход по ссылке группы показывал «There is no WaysGram account
with this username». Код приглашения уходил в resolveUsername, потому что в
ссылке нет ни «+», ни типа (группа/канал).
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class InviteLinkChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_invite_code_is_told_apart_from_username(self):
        links = self.source("tgnet/WaysGramLinks.java")
        self.assertIn("public static boolean isInviteCode(", links)
        # Имя пользователя — только строчные буквы: такой код не должен считаться приглашением.
        self.assertIn('!segment.matches("[a-z][a-z0-9_]{0,31}")', links)

    def test_launch_activity_does_not_send_invite_code_to_username_lookup(self):
        launch = self.source("ui/LaunchActivity.java")
        self.assertIn('group = "wg/invites/" + segment;', launch)
        self.assertIn("WaysGramLinks.isInviteCode(segment)", launch)

    def test_unknown_kind_tries_groups_then_channels(self):
        invites = self.source("tgnet/WaysGramInvites.java")
        self.assertIn('"wg/invites/([A-Za-z0-9_-]{20,128})"', invites)
        self.assertIn('for (String scope : new String[]{"groups", "channels"})', invites)
        self.assertIn('"__scope"', invites)
        # preview должен понимать оба типа, иначе превью приглашения останется пустым.
        self.assertIn("unknown ? resolveUnknown(account, path, false) : WaysGramApi.get(account, path)", invites)


class DiscussionAccessChecks(unittest.TestCase):
    """Кнопка «Discuss» и комментарии должны работать у подписчика канала."""

    def test_backend_joins_discussion_group_for_channel_viewers(self):
        api = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")
        self.assertIn("async def join_discussion_group_for_channel(", api)
        self.assertIn("await join_discussion_group_for_channel(db, channel_id, uid)", api)
        # Старая проверка требовала членства в группе у всех, кроме админов.
        self.assertNotIn('group = int(row[0])\n        await require_group_member(db, group, uid)',
                         api)


if __name__ == "__main__":
    unittest.main()
