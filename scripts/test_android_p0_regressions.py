#!/usr/bin/env python3
"""P0 regressions: channel rights across sessions, privacy rows, delayed paid
reactions and the channel "Comments" button.

The tests read the authoritative split Android archive; no APK is built.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class P0RegressionChecks(unittest.TestCase):
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

    # ------------------------------------------------------------------ E

    def test_chat_rights_are_cached_per_account(self):
        chats = self.source("tgnet/WaysGramChats.java")
        self.assertIn("private static final HashMap<String, TLRPC.Chat> chatCache", chats)
        self.assertIn('return account + ":" + tgId;', chats)
        self.assertIn("public static synchronized TLRPC.Chat cachedChat(int account, long tgId)", chats)
        self.assertNotIn("public static synchronized TLRPC.Chat cachedChat(long tgId)", chats)
        self.assertIn("public static TLRPC.TL_channel toChannel(int account,", chats)
        self.assertIn('channel.creator = c.optLong("owner_telegram_id") == selfId;', chats)
        self.assertIn("public static void requireChannelPostRight(int account, long channelTgId)", chats)
        self.assertIn("requireChannelPostRight(account, tg);", chats)
        messages = self.source("tgnet/WaysGramMessages.java")
        self.assertIn("WaysGramChats.requireChannelPostRight(account, WaysGramChats.channelTg(peerId));", messages)

    def test_server_has_three_session_rights_regression(self):
        test = (ROOT / "authbot" / "tests" / "test_channel_multi_session_rights.py").read_text(encoding="utf-8")
        self.assertIn("def test_three_sessions_do_not_grant_channel_rights", test)
        self.assertIn("outsider_extra = _session(path, 102", test)
        self.assertIn("outsider_third = _session(path, 102", test)
        self.assertIn("status_code == 403", test)

    # ------------------------------------------------------------------ F

    def test_extra_incoming_gifts_privacy_row_is_gone(self):
        settings = self.source("ui/PrivacySettingsActivity.java")
        for removed in (
            "waysGramGiftReceiveRow",
            "loadWaysGramGiftReceive",
            "showWaysGramGiftReceiveError",
            "saveWaysGramGiftReceive",
            "/api/me/gift-receive",
            "WaysGramGiftReceiveTitle",
        ):
            self.assertNotIn(removed, settings, removed)
        self.assertIn("position == giftsRow", settings)
        self.assertIn("R.string.PrivacyGifts", settings)
        # Every renamed callback must have a matching guard; without it javac fails.
        self.assertEqual(settings.count("canShowWaysGramScreen(token)"), 9)
        self.assertIn("    private boolean canShowWaysGramScreen(String token) {", settings)
        self.assertIn("TextUtils.equals(token, WaysGramApi.getToken(currentAccount))", settings)

    def test_messages_privacy_opens_stock_rules(self):
        control = self.source("ui/PrivacyControlActivity.java")
        # Экран «Сообщения» — штатный Telegram: никакого своего пути цены,
        # ни при открытии, ни при сохранении.
        start = control.index("    private void applyCurrentPrivacySettings() {")
        end = control.index("    private void ", start + 10)
        block = control[start:end]
        self.assertNotIn("applyWaysGramMessagesSettings", block)
        # Подарки по-прежнему идут своим путём.
        self.assertIn("applyWaysGramGiftsSettings()", block)
        self.assertIn("TL_inputPrivacyKeyNoPaidMessages", control)
        self.assertIn("starsPaidMessageAmountMax", control)
        start = control.index("    private void checkPrivacy() {")
        block = control[start:control.index("    private void ", start + 10)]
        self.assertIn("PRIVACY_RULES_TYPE_MESSAGES", block)
        self.assertIn("noncontact_peers_paid_stars", block)
        self.assertNotIn("applyWaysGramFeeValues", block)
        self.assertNotIn("WaysGramPaidDm", block)
        # Платные сообщения остаются явным выбором на этом экране.
        self.assertIn("TYPE_PAY", control)

    # ------------------------------------------------------------------ B

    def test_paid_reaction_carries_the_session_token_at_any_time(self):
        transport = self.source("tgnet/WaysGramTransport.java")
        self.assertIn("|| request instanceof TLRPC.TL_messages_sendPaidReaction;", transport)
        self.assertIn("handle(account, request, reactionToken)", transport)
        messages = self.source("tgnet/WaysGramMessages.java")
        self.assertIn('"/api/channels/" + serverId + "/messages/" + messageId + "/paid-reactions"', messages)
        self.assertIn("body, token, 30000, key);", messages)
        self.assertIn('String key = "wg-paid-" + Long.toHexString(req.random_id)', messages)
        self.assertNotIn("5000", transport)
        chat = self.source("ui/ChatActivity.java")
        start = chat.index("    public void selectReaction(")
        end = chat.index("    private void updateMessageAnimated(", start)
        self.assertNotIn("5000", chat[start:end])

    def test_paid_reaction_uses_stock_sheet_and_no_waysgram_palette(self):
        chat = self.source("ui/ChatActivity.java")
        self.assertIn("StarsReactionsSheet sheet = new StarsReactionsSheet(", chat)
        self.assertIn("visibleReaction.isStar", chat)
        line = [l for l in chat.splitlines() if "final boolean waysGramReactionMenu =" in l][0]
        self.assertIn("currentChat == null", line)

    # ------------------------------------------------------------------ C

    def test_linked_discussion_group_enables_stock_comments_button(self):
        chats = self.source("tgnet/WaysGramChats.java")
        self.assertIn('c.optLong("linked_monoforum_id", c.optLong("discussion_group_id", 0))', chats)
        self.assertIn("channel.linked_monoforum_id = linkedMonoforum;", chats)
        self.assertIn("channel.flags2 |= TLObject.FLAG_18;", chats)
        self.assertIn('flag(c, "broadcast_messages_allowed")', chats)
        chat = self.source("ui/ChatActivity.java")
        self.assertEqual(chat.count("showSuggestButton = currentChat.linked_monoforum_id != 0;"), 2)
        self.assertNotIn("showSuggestButton = currentChat.broadcast_messages_allowed &&", chat)
        self.assertIn(
            "bottomChannelButtonsLayout.showButton(ChatActivityChannelButtonsLayout.BUTTON_DIRECT, showSuggestButton",
            chat,
        )


if __name__ == "__main__":
    unittest.main()
