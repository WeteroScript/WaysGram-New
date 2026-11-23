#!/usr/bin/env python3
"""Платные сообщения по схеме Telegram (этап 5): цена видна до отправки, списание
только после подтверждения, возврат за недоставку и история платежей.

Читает исходники из `WaysGram-Android.zip.part1…5`.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"
RES = "WaysGram-Android/TMessagesProj/src/main/res/values/strings.xml"


class PaidMessagesChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def strings(self):
        return self.archive.read(RES).decode("utf-8")

    def test_price_is_shown_before_sending(self):
        # Кнопка отправки берёт цену собеседника: Telegram рисует «⭐ N» сам.
        enter = self.source("ui/Components/ChatActivityEnterView.java")
        self.assertIn("WaysGramPaidDm.peerFeeStars(currentAccount, dialog_id)", enter)
        paid = self.source("ui/WaysGramPaidDm.java")
        self.assertIn('/api/chats/" + peer + "/message-fee', paid)
        self.assertIn("public static void loadPeerFee(int account, long peer, Runnable changed)", paid)
        chat = self.source("ui/ChatActivity.java")
        self.assertIn("WaysGramPaidDm.loadPeerFee(currentAccount, dialog_id", chat)
        self.assertIn("chatActivityEnterView.updateSendButtonPaid();", chat)

    def test_channel_thread_shows_price_and_history(self):
        inbox = self.source("ui/WaysGramChannelInbox.java")
        self.assertIn("R.string.WaysGramInboxTitleFee", inbox)
        self.assertIn("R.string.WaysGramInboxHistory", inbox)
        self.assertIn("WaysGramPaidHistory.show(fragment, account)", inbox)
        chat = self.source("ui/ChatActivity.java")
        self.assertIn("waysgram_paid_history", chat)
        self.assertIn("WaysGramPaidHistory.show(ChatActivity.this, currentAccount)", chat)

    def test_deduction_requires_confirmation(self):
        inbox = self.source("ui/WaysGramChannelInbox.java")
        # Без подтверждённой цены запрос уходит без approved_fee_stars, и сервер отвечает 402.
        self.assertIn('put("approved_fee_stars", fee)', inbox)
        self.assertIn("private static void confirm(", inbox)
        self.assertIn("R.string.WaysGramInboxConfirm", inbox)
        # Повтор использует прежний ключ идемпотентности, а не новую оплату.
        self.assertIn("String previous", inbox)
        self.assertIn("WaysGramInboxRetry", inbox)

    def test_history_screen_reads_paid_messages(self):
        history = self.source("ui/WaysGramPaidHistory.java")
        self.assertIn('"/api/me/paid-messages?limit=25&before_id="', history)
        self.assertIn("R.string.WaysGramPaidHistoryTotals", history)
        self.assertIn("R.string.WaysGramPaidHistoryRefund", history)
        self.assertIn('"refund"', history)
        for name in ("WaysGramPaidHistoryTitle", "WaysGramPaidHistoryEmpty", "WaysGramPaidHistoryError",
                     "WaysGramPaidHistoryTotal", "WaysGramInboxHistory"):
            self.assertIn(name, self.strings(), name)


if __name__ == "__main__":
    unittest.main()
