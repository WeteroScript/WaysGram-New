#!/usr/bin/env python3
"""D1/D2 regressions: branded short invite links.

Public groups use their username at https://t.wg/<username>; private chats use
a 22-character Telegram-style code at https://t.wg/<code>. Legacy API links are
still accepted by both server and client. No APK is built.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class ShortLinkChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_client_uses_branded_host_for_invites(self):
        links = self.source("tgnet/WaysGramLinks.java")
        self.assertIn('public static final String HOST = "t.wg";', links)
        self.assertIn('public static final String BASE = "https://" + HOST;', links)
        self.assertIn('"/api/(?:groups|channels)/invites/([A-Za-z0-9_-]{20,128})"', links)
        self.assertIn("return BASE + \"/\" + matcher.group(1);", links)

    def test_server_generates_telegram_sized_codes(self):
        api = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")
        self.assertIn("return secrets.token_urlsafe(16)", api)
        self.assertIn('SHARE_BASE = f"https://{SHARE_HOST}"', api)
        self.assertIn("def _invite_code_from_link(value: str) -> str | None:", api)
        self.assertIn('return f"{SHARE_BASE}/{code_or_username}"', api)
        self.assertNotIn('_invite_share_origin(request) + f"/api/groups/invites/', api)
        self.assertNotIn('_invite_share_origin(request) + f"/api/channels/invites/', api)

    def test_backend_regression_covers_both_link_kinds(self):
        test = (ROOT / "authbot" / "tests" / "test_messenger_api.py").read_text(encoding="utf-8")
        self.assertIn("def test_share_links_use_twg_username_or_short_invite_code", test)
        self.assertIn('assert invite.json()["invite_link"] == "https://t.wg/public_link_room"', test)
        self.assertIn("assert len(code) == 22", test)


if __name__ == "__main__":
    unittest.main()
