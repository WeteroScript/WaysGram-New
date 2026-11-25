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
        self.assertNotIn("headerItem.lazilyAddSubItem(waysgram_paid_history", chat)

    def test_deduction_requires_stock_confirmation(self):
        inbox = self.source("ui/WaysGramChannelInbox.java")
        # Без подтверждённой цены запрос уходит без approved_fee_stars, и сервер отвечает 402.
        self.assertIn('put("approved_fee_stars", fee)', inbox)
        self.assertIn("private static void confirm(", inbox)
        # Стоковый диалог Telegram, внутреннее списание остаётся в send().
        self.assertIn("AlertsCreator.showPayForMessageAlert(account, dialogId(path)", inbox)
        self.assertIn("private static long dialogId(String path)", inbox)
        self.assertIn("-WaysGramChats.channelTg(Long.parseLong(matcher.group(1)))", inbox)
        self.assertNotIn("R.string.WaysGramInboxConfirm", inbox)
        self.assertNotIn("R.string.WaysGramInboxRetry", inbox)
        # Повтор использует прежний ключ идемпотентности, а не новую оплату.
        self.assertIn("String previous", inbox)
        dm = self.source("ui/WaysGramPaidDm.java")
        self.assertIn("AlertsCreator.showPayForMessageAlert(account, peer", dm)
        self.assertNotIn("R.string.WaysGramPaidDmConfirm", dm)
        self.assertNotIn("R.string.WaysGramPaidDmRetry", dm)
        transport = self.source("tgnet/WaysGramTransport.java")
        self.assertIn("sendTo(account, send.peer, send.message, send.random_id", transport)
        self.assertIn("token, fee, -1L);", transport)

    def test_stock_paid_message_alert_is_available(self):
        alerts = self.source("ui/Components/AlertsCreator.java")
        self.assertIn("public static void showPayForMessageAlert(int currentAccount, long dialogId, long stars", alerts)
        self.assertIn("R.string.MessageLockedStarsConfirmTitle", alerts)
        self.assertIn("showAlertWithCheckboxWithBalance", alerts)

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


class PrivacyMessagesRowChecks(unittest.TestCase):
    """Скриншот «Privacy and Security»: строка «Messages» открывала свой диалог
    «Платные личные сообщения» (10000 / Cancel / Save) и подписывалась его
    заголовком, а не ценой. Из-за этого не было видно ни штатного выбора «кто
    может писать», ни сохранённой цены, и правка не проверялась глазом.
    """

    @classmethod
    def setUpClass(cls):
        data = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def res(self, name="values/strings.xml"):
        return self.archive.read(f"WaysGram-Android/TMessagesProj/src/main/res/{name}").decode("utf-8")

    def row_branch(self, needs: str) -> str:
        """Кусок кода строки «Messages»: в файле две ветки — клик и отрисовка."""
        settings = self.source("ui/PrivacySettingsActivity.java")
        for chunk in settings.split("position == noncontactsRow) {")[1:]:
            chunk = chunk.split("} else if (position ==")[0]
            if needs in chunk:
                return chunk
        self.fail("не нашёл ветку строки «Messages»")

    def test_row_opens_the_standard_messages_screen(self):
        branch = self.row_branch("presentFragment")
        self.assertNotIn("WaysGramPaidDm.settings", branch)
        self.assertIn("new PrivacyControlActivity(ContactsController.PRIVACY_RULES_TYPE_MESSAGES)", branch)

    def test_row_value_is_the_saved_price(self):
        branch = self.row_branch("setTextAndValue")
        self.assertIn("WaysGramPaidDm.cachedFeeStars(currentAccount)", branch)
        self.assertIn("AndroidUtilities.formatWholeNumber(feeStars, 0)", branch)
        self.assertIn("R.string.P2PEverybody", branch)
        # Раньше в подписи стоял заголовок диалога — отсюда «Платные личные сообщения».
        self.assertNotIn("WaysGramPaidDmTitle", branch)

    def test_reopening_the_screen_keeps_the_price(self):
        control = self.source("ui/PrivacyControlActivity.java")
        self.assertIn("loadWaysGramMessagesFee();", control)
        load = control.split("private void loadWaysGramMessagesFee() {")[1].split("private void ")[0]
        self.assertIn("WaysGramPaidDm.loadFee(currentAccount", load)
        self.assertIn("applyWaysGramFeeValues(stars, users)", load)
        apply = control.split("private void applyWaysGramFeeValues(int stars, ArrayList<Long> users) {")[1]
        self.assertIn("currentType = TYPE_PAY", apply)
        self.assertIn("currentType = TYPE_EVERYBODY", apply)
        # Без подгрузки экран стартовал как «все могут писать» и обнулял цену при Save.
        self.assertIn(
            "initialRulesType = currentType = TYPE_EVERYBODY;\n            loadWaysGramMessagesFee();",
            control.replace("\r\n", "\n"))

    def test_own_fee_dialog_is_gone(self):
        paid = self.source("ui/WaysGramPaidDm.java")
        for gone in ("public static void settings(", "private static void editor(", "private static void notice("):
            self.assertNotIn(gone, paid, f"мёртвый диалог остался: {gone}")
        # Отправка платного сообщения показывает стоковое подтверждение Telegram.
        self.assertIn("AlertsCreator.showPayForMessageAlert(account, peer", paid)
        self.assertIn("public static int peerFeeStars(int account, long peer)", paid)

    def test_dead_dialog_strings_are_removed(self):
        dead = ("WaysGramPaidDmTitle", "WaysGramPaidDmPrice", "WaysGramPaidDmExceptions",
                "WaysGramPaidDmExceptionCount", "WaysGramPaidDmExceptionLimit", "WaysGramPaidDmSaved",
                "WaysGramPaidDmConfirm", "WaysGramPaidDmRetry", "WaysGramPaidDmNowFree")
        for name in ("values/strings.xml", "values-ru/strings.xml"):
            text = self.res(name)
            for key in dead:
                self.assertNotIn(f'name="{key}"', text, f"{name}: остался {key}")
            # Ошибка сохранения цены нужна экрану «Сообщения».
            self.assertIn('name="WaysGramPaidDmError"', text)

    def test_russian_strings_keep_the_paths_that_are_used(self):
        ru = self.res("values-ru/strings.xml")
        for key in ("WaysGramTransferChannel", "WaysGramChannelTopics", "WaysGramTopicsEnable"):
            self.assertIn(f'name="{key}"', ru)


if __name__ == "__main__":
    unittest.main()
