#!/usr/bin/env python3
"""Static guards for javac errors that the UI-oriented tests cannot see.

Reads the shipped source archive, which is what the APK workflow actually compiles.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class CompileRegressions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_matcher_uses_regex_package(self):
        links = self.source("tgnet/WaysGramLinks.java")
        self.assertIn("java.util.regex.Matcher matcher", links)
        self.assertNotIn("java.util.Matcher matcher", links)

    def test_updates_with_chat_propagates_account(self):
        chats = self.source("tgnet/WaysGramChats.java")
        self.assertIn("updatesWithChat(int account, JSONObject group, long selfId)", chats)
        self.assertNotIn("updatesWithChat(JSONObject group, long selfId)", chats)
        self.assertEqual(chats.count("updatesWithChat(account,"), 6)

    def test_channel_inbox_does_not_call_missing_fragment_method(self):
        inbox = self.source("ui/WaysGramChannelInbox.java")
        self.assertNotIn("fragment.getDialogId()", inbox)
        self.assertIn("showPayForMessageAlert(account, dialogId(path)", inbox)

    def test_privacy_callbacks_have_a_guard(self):
        settings = self.source("ui/PrivacySettingsActivity.java")
        self.assertEqual(settings.count("canShowWaysGramScreen(token)"), 9)
        self.assertIn("private boolean canShowWaysGramScreen(String token)", settings)


if __name__ == "__main__":
    unittest.main()
