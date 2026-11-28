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

    def test_paid_dm_shows_the_amount_in_the_chat(self):
        # После оплаты в переписке должна быть плашка «Вы оплатили ⭐N», как в Telegram.
        # Её рисует штатная ячейка по `paid_message_stars`, но поле терялось: сервер
        # сумму не хранил, а ответ на отправку (`TL_updateShortSentMessage`) её не несёт.
        server = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")
        self.assertIn("paid_message_stars INTEGER NOT NULL DEFAULT 0", server)
        self.assertIn('ALTER TABLE {db_split.SCHEMA}.messages ADD COLUMN paid_message_stars', server)
        self.assertIn('UPDATE messages SET paid_message_stars=? WHERE id=?', server)
        self.assertIn('"paid_message_stars": fee,', server)
        self.assertIn('created_at,paid_message_stars,', server)
        # Уже оплаченные до обновления сообщения: сумма восстанавливается из экономики.
        self.assertIn("reason='message_fee:'||messages.id", server)

        mapper = self.source("tgnet/WaysGramMapper.java")
        self.assertIn("public static void readPaidMessage(TLRPC.Message message, JSONObject json)", mapper)
        self.assertIn('long stars = json.optLong("paid_message_stars", 0);', mapper)
        self.assertIn("message.paid_message_stars = stars;", mapper)
        self.assertIn("message.flags2 |= TLObject.FLAG_6;", mapper)
        self.assertIn("readPaidMessage(message, m);", mapper)

        # Ответ на отправку — updateShortSentMessage без суммы: транспорт запоминает её,
        # а SendMessagesHelper забирает, когда присваивает сообщению настоящий id.
        transport = self.source("tgnet/WaysGramTransport.java")
        self.assertIn('WaysGramPaidStars.remember(account, result.optInt("id"), result.optLong("paid_message_stars", 0));',
                      transport)
        helper = self.source("messenger/SendMessagesHelper.java")
        self.assertIn("final long paidStars = org.telegram.tgnet.WaysGramPaidStars.take(currentAccount, res.id);", helper)
        self.assertIn("newMsgObj.paid_message_stars = paidStars;", helper)
        # Сама плашка — штатная: ячейка берёт цену из сообщения.
        cell = self.source("ui/Cells/ChatMessageCell.java")
        self.assertIn("PaidMessageActionOut", cell)
        self.assertIn("messageOwner.paid_message_stars", cell)

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

    def test_group_paid_messages_are_confirmed_and_carried_for_text_media_and_albums(self):
        transport = self.source("tgnet/WaysGramTransport.java")
        for marker in (
            "private static boolean groupPaidMessageRequest(TLObject request)",
            "request instanceof TLRPC.TL_messages_sendMessage",
            "request instanceof TLRPC.TL_messages_sendMedia",
            "request instanceof TLRPC.TL_messages_sendMultiMedia",
            "private static void confirmPaidGroupMessage(",
            '"GROUP_MESSAGE_FEE_REQUIRED".equals(code)',
            "result = handle(account, request, token, fee);",
            "approvedPaidFee,\n                    sendAsIdentity(send.send_as, send.peer));",
            "WaysGramMessages.sendAlbum(account, album, selfId, approvedPaidFee)",
        ):
            self.assertIn(marker, transport, marker)
        self.assertIn("WaysGramChats.send(account, peer, text, randomId, mediaId, replyTo, selfId,", transport)
        self.assertIn("paidMessageRequest = directText || groupPaidRequest", transport)
        self.assertIn("paidTextPrefs().edit().putInt(slot, fee)", transport)

        chats = self.source("tgnet/WaysGramChats.java")
        self.assertIn('body.put("approved_fee_stars", approvedPaidFee);', chats)
        self.assertIn("WaysGramMapper.readPaidMessage(message, m);", chats)
        self.assertIn('"paid_messages_available"', chats)
        self.assertIn('"send_paid_messages_stars"', chats)
        self.assertIn('"/api/groups/" + groupId + "/paid-messages"', chats)

        messages = self.source("tgnet/WaysGramMessages.java")
        self.assertIn("sendAlbum(int account, TLRPC.TL_messages_sendMultiMedia req, long selfId,", messages)
        self.assertIn('body.put("approved_fee_stars", approvedPaidFee);', messages)
        paid = self.source("ui/WaysGramPaidDm.java")
        self.assertIn("int fee, int messagesCount,", paid)
        self.assertIn("Math.max(1, messagesCount)", paid)
        self.assertIn("AlertsCreator.showPayForMessageAlert(account, peer", paid)

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


class PrivacyMessagesRowChecks(unittest.TestCase):
    """Скриншот «Privacy and Security»: строка «Messages» открывала свой диалог
    «Платные личные сообщения» (10000 / Cancel / Save) и подписывалась его
    заголовком. Нужен штатный экран Telegram: «Кто может отправлять мне
    сообщения?» → Все / Контакты и Premium-подписчики / Сообщения за звёзды,
    слайдер «Стоимость одного сообщения» и подпись 85%.
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
        self.assertNotIn("WaysGramPaidDm", branch)
        self.assertIn("new PrivacyControlActivity(ContactsController.PRIVACY_RULES_TYPE_MESSAGES)", branch)

    def test_row_value_is_the_saved_price(self):
        branch = self.row_branch("setTextAndValue")
        self.assertIn("(paidSettings.flags & 32) != 0", branch)
        self.assertIn("paidSettings.noncontact_peers_paid_stars", branch)
        self.assertIn("AndroidUtilities.formatWholeNumber(feeStars, 0)", branch)
        self.assertIn("R.string.ContactsAndPremium", branch)
        self.assertIn("R.string.P2PEverybody", branch)
        # Раньше в подписи стоял заголовок диалога — отсюда «Платные личные сообщения».
        self.assertNotIn("WaysGramPaidDmTitle", branch)

    def test_screen_is_stock_telegram(self):
        control = self.source("ui/PrivacyControlActivity.java")
        # Штатные строки экрана: список «кто может писать», описание, цена, доход.
        for row in ("priceHeaderRow", "priceRow", "priceInfoRow", "payRow", "detailRow2"):
            self.assertIn(row, control, row)
        for string in ("R.string.PrivateMessagesChargePrice", "R.string.PrivacyMessagesContactsAndPremium"):
            self.assertIn(string, control)
        # Ключ приватности и цена — те же запросы, что шлёт оригинальный клиент.
        self.assertIn("TLRPC.TL_inputPrivacyKeyNoPaidMessages", control)
        self.assertIn("ContactsController.PRIVACY_RULES_TYPE_NO_PAID_MESSAGES", control)
        self.assertIn("getMessagesController().starsPaidMessageAmountMax", control)

    def test_no_waysgram_branch_on_the_messages_screen(self):
        control = self.source("ui/PrivacyControlActivity.java")
        for gone in ("applyWaysGramMessagesSettings", "loadWaysGramMessagesFee", "applyWaysGramFeeValues"):
            self.assertNotIn(gone, control, gone)
        # Ни загрузка, ни сохранение не перехватываются WaysGram-веткой.
        self.assertNotIn(
            "rulesType == PRIVACY_RULES_TYPE_MESSAGES && org.telegram.tgnet.WaysGramTransport.ENABLED", control)
        # Подарки по-прежнему идут своим путём.
        self.assertIn("applyWaysGramGiftsSettings", control)

    def test_own_fee_dialog_is_gone(self):
        paid = self.source("ui/WaysGramPaidDm.java")
        for gone in ("public static void settings(", "private static void editor(", "private static void notice(",
                     "public static void loadFee(", "public static void saveFee(", "hasCachedFee("):
            self.assertNotIn(gone, paid, f"мёртвый код остался: {gone}")
        # Отправка платного сообщения показывает стоковое подтверждение Telegram.
        self.assertIn("AlertsCreator.showPayForMessageAlert(account, peer", paid)
        self.assertIn("public static int peerFeeStars(int account, long peer)", paid)
        # Кэш цены: без объявлений этих полей javac падал на «cannot find symbol»,
        # потому что методы остались, а поля потерялись при чистке мёртвого диалога.
        self.assertIn("private static final long PEER_FEE_TTL", paid)
        self.assertIn("private static final java.util.HashMap<String, Integer> peerFees", paid)
        self.assertIn("private static final java.util.HashMap<String, Long> peerFetched", paid)
        self.assertIn('/api/chats/" + peer + "/message-fee', paid)

    def test_dead_dialog_strings_are_removed(self):
        dead = ("WaysGramPaidDmTitle", "WaysGramPaidDmPrice", "WaysGramPaidDmExceptions",
                "WaysGramPaidDmExceptionCount", "WaysGramPaidDmExceptionLimit", "WaysGramPaidDmSaved",
                "WaysGramPaidDmConfirm", "WaysGramPaidDmRetry", "WaysGramPaidDmNowFree", "WaysGramPaidDmError")
        for name in ("values/strings.xml", "values-ru/strings.xml"):
            text = self.res(name)
            for key in dead:
                self.assertNotIn(f'name="{key}"', text, f"{name}: остался {key}")

    def test_russian_strings_keep_the_paths_that_are_used(self):
        ru = self.res("values-ru/strings.xml")
        for key in ("WaysGramTransferChannel", "WaysGramChannelTopics", "WaysGramTopicsEnable"):
            self.assertIn(f'name="{key}"', ru)


if __name__ == "__main__":
    unittest.main()
