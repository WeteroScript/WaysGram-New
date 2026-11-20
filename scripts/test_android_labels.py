#!/usr/bin/env python3
"""Static regression checks for label sources in the split Android archive.

These checks deliberately do not build Android or pretend to exercise the UI/network.
Run with: python scripts/test_android_labels.py
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class AndroidLabelSourceChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        blob = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(blob))
        names = cls.archive.namelist()
        if len(names) != len(set(names)):
            raise AssertionError("duplicate archive members")

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_profile_uses_user_id_for_both_titles(self):
        profile = self.source("ui/ProfileActivity.java")
        start = profile.index("CharSequence waysGramTitle = newString;")
        section = profile[start:profile.index("if (a == 0 && onlineTextOverride", start)]
        self.assertIn("currentAccount, userId, waysGramTitle, waysGramBadgeChanged", section)
        self.assertNotIn("a == 0\n                            ?", section)
        self.assertIn("waysGramBadgeTick", profile)
        self.assertIn("cancelRunOnUIThread(waysGramBadgeTick)", profile)

    def test_saved_messages_suppressed_at_measure_and_draw(self):
        cell = self.source("ui/Cells/DialogCell.java")
        chat = self.source("ui/Components/ChatAvatarContainer.java")
        for source in (cell, chat):
            self.assertIn("getClientUserId() ? 0 :", source)
            self.assertIn("waysGramBadgePeer()", source)
            self.assertIn("cancelRunOnUIThread(waysGramBadgeTick)", source)
        self.assertIn("currentAccount, waysGramBadgePeer(), Theme.dialogs_namePaint", cell)
        self.assertIn("waysGramBadgePeer(),\n                            waysGramMirrored", cell)
        self.assertIn("savedMessages || peerId == UserConfig", self.source("messenger/waysgram/WaysGramChatFragment.java"))

    def test_batch_refresh_failure_removal_and_session_safety(self):
        badges = self.source("messenger/waysgram/WaysGramPeerBadges.java")
        self.assertIn("MAX_BATCH_SIZE = 100", badges)
        self.assertIn("offset += MAX_BATCH_SIZE", badges)
        self.assertIn("synchronized (LOCK) {\n            batch = new LinkedHashMap<>(PENDING);\n            PENDING.clear();", badges)
        self.assertIn("pending.generation != GENERATIONS[pending.account]", badges)
        self.assertIn("previous == null || !sameLabels(previous.labels, result)", badges)
        self.assertIn("left.get(i).color != right.get(i).color", badges)
        self.assertIn("result == null || !changed", badges)
        self.assertIn("array == null ? (fallback ? loadOne", badges)
        self.assertIn("REFRESH_INTERVAL_MS = 15 * 1000L", badges)
        api = self.source("tgnet/WaysGramApi.java")
        self.assertEqual(api.count("WaysGramPeerBadges.clearAccount(account)"), 2)

    def test_other_visible_surfaces_can_detect_shared_cache_updates(self):
        for path in ("ui/Cells/DialogCell.java", "ui/Components/ChatAvatarContainer.java", "ui/ProfileActivity.java"):
            with self.subTest(path=path):
                source = self.source(path)
                self.assertIn("snapshot != waysGramBadgeSnapshot", source)
                self.assertIn("waysGramBadgeChanged.run()", source)
        for path in ("messenger/waysgram/WaysGramChatFragment.java", "messenger/waysgram/WaysGramUserProfileFragment.java"):
            with self.subTest(path=path):
                source = self.source(path)
                self.assertIn("waysGramLabelsTick", source)
                self.assertIn("cancelRunOnUIThread(waysGramLabelsTick)", source)
                self.assertIn("waysGramLabelsInFlight", source)


if __name__ == "__main__":
    unittest.main()
