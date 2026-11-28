#!/usr/bin/env python3
"""Подарки каналу: клиент, сервер и видимость раздела в профиле канала.

Как в Telegram: подарок за звёзды уходит каналу, хранится у канала, а профиль канала
показывает счётчик подарков (`stargifts_count`, flags2 FLAG_18) и кнопку отправки
(`stargifts_available`, flags2 FLAG_19).
"""

import io
import json
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class ChannelGiftClientChecks(unittest.TestCase):
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

    def test_gift_lookup_reads_channel_gifts(self):
        gifts = self.source("tgnet/WaysGramGifts.java")
        start = gifts.index("    public static TL_stars.TL_payments_savedStarGifts savedGifts(")
        end = gifts.index("\n    }\n", start)
        block = gifts[start:end]
        self.assertIn("TLRPC.TL_inputPeerChannel", block)
        self.assertIn('"/api/channels/"', block)
        self.assertIn('+ "/gifts"', block)
        # подарки канала не должны попадать в ветку личного инвентаря
        self.assertLess(block.index("if (channelPeer)"), block.index('"/api/me/gifts"'))

    def test_sending_a_gift_to_a_channel_uses_the_channel_endpoint(self):
        gifts = self.source("tgnet/WaysGramGifts.java")
        start = gifts.index("    public static TLObject sendForm(")
        end = gifts.index("\n    }\n", start)
        block = gifts[start:end]
        self.assertIn('"/api/channels/"', block)
        self.assertIn('channelGiftBody(invoice)', block)
        # ошибка сервера (нет Stars) обязана дойти до интерфейса, а не быть проглоченной
        channel_part = block[: block.index('"/api/telegram/gifts/"')]
        self.assertIn("catch (WaysGramApi.ApiException e)", channel_part)
        self.assertIn("BALANCE_TOO_LOW", channel_part)

    def test_channel_full_reports_the_gift_counter(self):
        chats = self.source("tgnet/WaysGramChats.java")
        self.assertIn('full.stargifts_available = channel.optBoolean("stargifts_available");', chats)
        self.assertIn('full.stargifts_count = channel.optInt("stargifts_count");', chats)
        self.assertIn("if (full.stargifts_count > 0) full.flags2 |= TLObject.FLAG_18;", chats)
        self.assertIn("if (full.stargifts_available) full.flags2 |= TLObject.FLAG_19;", chats)


class ChannelGiftServerChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.api = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")

    def test_endpoints_exist(self):
        self.assertIn('@router.get("/api/channels/{channel_id}/gifts")', self.api)
        self.assertIn('@router.post("/api/channels/{channel_id}/gifts", status_code=201)', self.api)

    def test_storage_and_payload(self):
        self.assertIn("CREATE TABLE IF NOT EXISTS channel_gifts", self.api)
        self.assertIn('channel["stargifts_count"]', self.api)
        self.assertIn('channel["stargifts_available"] = True', self.api)

    def test_sending_charges_stars_and_keeps_the_gift_with_the_channel(self):
        start = self.api.index('async def send_channel_gift(')
        end = self.api.index('@router.', start)
        block = self.api[start:end]
        self.assertIn("UPDATE accounts SET stars=stars-?", block)
        self.assertIn("UPDATE telegram_gift_prices SET sold=sold+1", block)
        self.assertIn("INSERT INTO channel_gifts", block)
        self.assertIn('"Недостаточно Stars"', block)
        # подарок канала не должен попадать в личный инвентарь
        self.assertNotIn("INSERT INTO user_gifts", block)

    def test_channel_gifts_need_access(self):
        start = self.api.index('async def channel_gifts(')
        end = self.api.index('@router.', start)
        block = self.api[start:end]
        self.assertIn("require_channel_access(channel_id", block)
        self.assertIn('"hide_name"', block)


if __name__ == "__main__":
    unittest.main()
