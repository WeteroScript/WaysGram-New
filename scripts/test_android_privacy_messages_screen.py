#!/usr/bin/env python3
"""Экран приватности «Сообщения»: ключ платных сообщений и глобальные настройки.

Клиент грузит экран через `account.getGlobalPrivacySettings` и `account.getPrivacy` для
`inputPrivacyKeyNoPaidMessages`. Пока эти запросы не обслуживались, экран оставался пустым,
а переключатели не сохранялись.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TG = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/tgnet/"


class PrivacyMessagesScreenChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.api = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(TG + name).decode("utf-8")

    def test_paid_messages_key_is_mapped(self):
        profile = self.source("WaysGramProfile.java")
        self.assertIn(
            'if (key instanceof TLRPC.TL_inputPrivacyKeyNoPaidMessages) return "paid-messages";',
            profile,
        )
        self.assertIn('"/api/me/privacy/" + setting', profile)

    def test_global_privacy_is_requested_and_saved(self):
        transport = self.source("WaysGramTransport.java")
        self.assertIn("request instanceof TL_account.getGlobalPrivacySettings", transport)
        self.assertIn("request instanceof TL_account.setGlobalPrivacySettings", transport)
        profile = self.source("WaysGramProfile.java")
        self.assertIn("TLObject globalPrivacy(int account", profile)
        self.assertIn('WaysGramApi.get(account, "/api/me/privacy/global")', profile)
        self.assertIn('WaysGramApi.put(account, "/api/me/privacy/global", body)', profile)
        for field in (
            "hide_read_marks",
            "keep_archived_unmuted",
            "keep_archived_folders",
            "archive_and_mute_new_noncontact_peers",
            "new_noncontact_peers_require_premium",
            "display_gifts_button",
            "noncontact_peers_paid_stars",
        ):
            self.assertIn(field, profile)

    def test_server_stores_settings(self):
        self.assertIn("CREATE TABLE IF NOT EXISTS account_global_privacy", self.api)
        self.assertIn('@router.get("/api/me/privacy/global")', self.api)
        self.assertIn('@router.put("/api/me/privacy/global")', self.api)
        # «global» не должен перехватываться общим маршрутом {setting}
        self.assertLess(
            self.api.index('@router.get("/api/me/privacy/global")'),
            self.api.index('@router.get("/api/me/privacy/{setting}")'),
        )
        self.assertIn('"paid-messages"', self.api)
        self.assertIn("GLOBAL_PRIVACY_DEFAULTS", self.api)


if __name__ == "__main__":
    unittest.main()
