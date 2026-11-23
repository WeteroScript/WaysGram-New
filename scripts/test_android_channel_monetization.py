#!/usr/bin/env python3
"""Статические проверки прав публикации и монетизации канала (этап 2).

Тест читает `WaysGram-Android.zip.part1…5` — источник сборки APK — и серверные
файлы в `authbot/`. Это не UI-тест: проверяются конкретные решения, которые
ломались на устройстве.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class ChannelMonetizationChecks(unittest.TestCase):
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

    def server(self, name):
        return (ROOT / "authbot" / name).read_text(encoding="utf-8")

    # ---------------------------------------------------------------- права

    def test_admin_rights_are_never_invented(self):
        chats = self.source("tgnet/WaysGramChats.java")
        self.assertIn('int mask = c.optInt("admin_rights", 0);', chats)
        self.assertNotIn('channelRights(c.optInt("admin_rights", 15))', chats)
        # Пустая маска не должна выдавать объект прав: иначе админ без права
        # публикации снова получит поле ввода.
        self.assertIn("if (mask > 0) {", chats)

    def test_posting_in_a_broadcast_channel_is_checked_before_sending(self):
        chats = self.source("tgnet/WaysGramChats.java")
        messages = self.source("tgnet/WaysGramMessages.java")
        self.assertIn("public static void requireChannelPostRight(int account, long channelTgId)", chats)
        self.assertIn('throw new WaysGramApi.ApiException(403, "CHAT_WRITE_FORBIDDEN");', chats)
        self.assertIn("requireChannelPostRight(account, tg);", chats)
        self.assertIn("WaysGramChats.requireChannelPostRight(account, WaysGramChats.channelTg(peerId));", messages)
        # Группы проверяет сервер своими правами — их клиент не блокирует.
        self.assertIn("if (isGroupChannel(channelTgId)) {", chats)

    # ---------------------------------------------------------------- платные реакции

    def test_paid_reaction_chip_is_built_from_star_sums(self):
        mapper = self.source("tgnet/WaysGramMapper.java")
        self.assertIn("private static void addPaidReaction(TLRPC.TL_messageReactions reactions, JSONObject json)", mapper)
        self.assertIn("result.reaction = new TLRPC.TL_reactionPaid();", mapper)
        self.assertIn('long total = json.optLong("paid_stars", 0);', mapper)
        self.assertIn('if (json.optLong("my_paid_stars", 0) > 0) {', mapper)
        self.assertIn("addPaidReaction(reactions, json);", mapper)

    def test_send_paid_reaction_uses_the_server_route(self):
        messages = self.source("tgnet/WaysGramMessages.java")
        transport = self.source("tgnet/WaysGramTransport.java")
        self.assertIn("public static TLObject sendPaidReaction(int account, TLRPC.TL_messages_sendPaidReaction req, long selfId, String token)", messages)
        self.assertIn('"/api/channels/" + serverId + "/messages/" + messageId + "/paid-reactions"', messages)
        self.assertIn("request instanceof TLRPC.TL_messages_sendPaidReaction", transport)
        self.assertIn("request instanceof TLRPC.TL_messages_getPaidReactionPrivacy", transport)
        self.assertIn("request instanceof TLRPC.TL_messages_togglePaidReactionPrivacy", transport)

    def test_paid_reaction_is_debit_only_and_reported_with_totals(self):
        api = self.server("messenger_api.py")
        self.assertIn("Платная реакция только списывается с отправителя", api)
        self.assertNotIn("for account_id, amount in ((uid, -stars), (owner, stars)):", api)
        self.assertIn('"total_stars": total_stars', api)
        self.assertIn("async def attach_paid_reactions(items: list[dict[str, Any]], channel_id: int, user_id: int)", api)
        self.assertIn('item["paid_stars"] = total', api)

    def test_analytics_counts_paid_reactions_by_debits(self):
        api = self.server("messenger_api.py")
        self.assertIn("покупки считаем по дебетам", api)
        self.assertIn('f"channel_paid_reaction:{channel_id}:*"', api)

    # ---------------------------------------------------------------- платные сообщения в канал

    def test_channel_paid_messages_price_round_trip(self):
        api = self.server("messenger_api.py")
        chats = self.source("tgnet/WaysGramChats.java")
        transport = self.source("tgnet/WaysGramTransport.java")
        screen = self.source("ui/PostSuggestionsEditActivity.java")
        self.assertIn('@router.put("/api/channels/{channel_id}/paid-messages")', api)
        self.assertIn("ADD COLUMN paid_messages_enabled", api)
        self.assertIn("ADD COLUMN paid_messages_stars", api)
        self.assertIn('channel["send_paid_messages_stars"] = int(channel.get("paid_messages_stars") or 0)', api)
        self.assertIn('c.optLong("send_paid_messages_stars"', chats)
        self.assertIn("private static TLObject paidMessagesPrice(", transport)
        self.assertIn('"/paid-messages"', transport)
        # Без монофорума цена живёт в самом канале, иначе экран показывал бы значение по умолчанию.
        self.assertIn(": currentChat.send_paid_messages_stars)", screen)
        self.assertIn("if (chat.linked_monoforum_id == 0) {", screen)


if __name__ == "__main__":
    unittest.main()
