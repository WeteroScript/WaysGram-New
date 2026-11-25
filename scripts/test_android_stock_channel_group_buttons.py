#!/usr/bin/env python3
"""Проверки того, что UI каналов и групп снова использует только стоковые кнопки.

WaysGram-входы удалены из меню чата, карточки, экрана редактирования и меню поста;
стоковые кнопки вернуты на свои позиции и включаются по стоковым условиям.
APK не собирается — проверяется исходник сборки в пяти частях архива.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class StockChannelGroupButtonChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        assert len(cls.archive.namelist()) == len(set(cls.archive.namelist()))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_chat_menu_has_no_waysgram_items_and_keeps_stock_boost(self):
        chat = self.source("ui/ChatActivity.java")
        for prefix in (
            "headerItem.lazilyAddSubItem(waysgram_",
            "items.add(LocaleController.getString(R.string.WaysGramPaidReactions));",
            "items.add(LocaleController.getString(R.string.WaysGramDiscussionGroup));",
        ):
            self.assertNotIn(prefix, chat, prefix)
        self.assertIn(
            "if (ChatObject.isBoostSupported(currentChat) && (getUserConfig().isPremium()"
            " || ChatObject.isBoosted(chatInfo) || ChatObject.hasAdminRights(currentChat))) {",
            chat,
        )
        self.assertNotIn(
            "!org.telegram.tgnet.WaysGramTransport.ENABLED && ChatObject.isBoostSupported(currentChat)",
            chat,
        )
        self.assertIn("isReactionsViewAvailable = !isEphemeral", chat)
        self.assertIn(
            "if (!selectedObject.isPaidSuggestedPostProtected() && chatMode == MODE_SCHEDULED"
            " && selectedObject.canEditMessageScheduleTime(currentChat)) {",
            chat,
        )

    def test_profile_uses_stock_calls_statistics_and_has_no_slow_mode_button(self):
        profile = self.source("ui/ProfileActivity.java")
        self.assertNotIn("waysGramBroadcast", profile)
        self.assertNotIn("waysGramStatistics", profile)
        self.assertEqual(profile.count("if (ChatObject.canManageCalls(chat) && chatInfo.call == null) {"), 2)
        self.assertNotIn("otherItem.addSubItem(WAYSGRAM_SLOW_MODE", profile)
        self.assertNotIn("WaysGramPaidReactions.showAnalytics", profile)
        self.assertNotIn("WaysGramPaidReactions.showGroupAnalytics", profile)
        self.assertIn("presentFragment(StatisticActivity.create(chat, false));", profile)

    def test_edit_screen_has_no_waysgram_rows_and_stock_rows_are_back(self):
        editor = self.source("ui/ChatEditActivity.java")
        for token in (
            "waysGramCells",
            "waysGramChannelCells",
            "waysGramChannelLocation",
            "R.string.WaysGramPaidReactions",
            "R.string.WaysGramChannelAnalytics",
            "R.string.WaysGramInboxSettings",
            "R.string.WaysGramTransferChannel",
            "R.string.WaysGramGroupAnalytics",
            "R.string.WaysGramGroupSendPermissions",
            "R.string.WaysGramSubscription",
        ):
            self.assertNotIn(token, editor, token)
        self.assertIn("ChatLinkActivity fragment = new ChatLinkActivity(chatId);", editor)
        self.assertIn(
            "if (ChatObject.canUserDoAdminAction(currentChat, ChatObject.ACTION_MANAGE_WELCOME)) {",
            editor,
        )
        self.assertIn(
            "if (ChatObject.isChannelAndNotMegaGroup(currentChat) && !ChatObject.isCommunity(currentChat)) {",
            editor,
        )
        self.assertIn(
            "if (currentChat.megagroup && (info == null || info.can_set_location)) {",
            editor,
        )

    def test_reaction_settings_have_no_waysgram_gates(self):
        reactions = self.source("ui/Components/Reactions/ChatCustomReactionsEditActivity.java")
        self.assertNotIn("WaysGramTransport.ENABLED", reactions)
        self.assertIn("if (info.paid_media_allowed) {", reactions)

    def test_mapper_exposes_stock_fields_used_by_restored_buttons(self):
        chats = self.source("tgnet/WaysGramChats.java")
        self.assertIn("full.paid_media_allowed = full.paid_reactions_available;", chats)
        self.assertIn("full.flags2 |= TLObject.FLAG_14;", chats)
        self.assertIn("full.stargifts_available = channel.optBoolean(\"stargifts_available\");", chats)
        self.assertIn("full.can_view_stats = canViewStats;", chats)
        self.assertIn("channel.linked_monoforum_id = linkedMonoforum;", chats)
        self.assertIn("channel.flags2 |= TLObject.FLAG_18;", chats)


if __name__ == "__main__":
    unittest.main()
