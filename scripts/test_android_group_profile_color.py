#!/usr/bin/env python3
"""Статические проверки цвета профиля группы.

`channels.updateColor` для мегагруппы уходил на `/api/channels/{id}` (у группы такого канала
нет) — цвет не сохранялся. Теперь запрос маршрутизируется по диапазону id, а поля цвета
маппятся в `TL_channel` из того же `applyChannelFlags`, что и у канала.
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'


class GroupProfileColorSourceChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        assert len(cls.archive.namelist()) == len(set(cls.archive.namelist()))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, path):
        return self.archive.read(BASE + path).decode('utf-8')

    def test_update_color_routes_groups_to_group_settings(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        block = transport.split('request instanceof TLRPC.TL_channels_updateColor', 1)[1].split(
            'request instanceof TLRPC.TL_channels_updateEmojiStatus', 1)[0]
        self.assertIn('if (WaysGramChats.isGroupChannel(update.channel.channel_id)) {', block)
        self.assertIn('return WaysGramChats.updateGroupSettings(account, update.channel.channel_id, body);', block)
        self.assertIn('return WaysGramChats.updateChannelSettings(account, update.channel.channel_id, body);', block)
        self.assertIn('update.for_profile ? "profile_color_id" : "color_id"', block)

    def test_group_chat_gets_peer_color_from_server_fields(self):
        chats = self.source('tgnet/WaysGramChats.java')
        megagroup = chats.split('public static TLRPC.TL_channel toMegagroup', 1)[1].split(
            '// ---------------------------------------------------------------- dialogs', 1)[0]
        self.assertIn('applyChannelFlags(chat, g);', megagroup)
        flags = chats.split('private static void applyChannelFlags', 1)[1].split('int colorId', 1)[0]
        self.assertIn('if (flag(c, "signatures_enabled")) {', flags)
        color = chats.split('int colorId = c.optInt("color_id");', 1)[1].split('int profileColor', 1)[0]
        self.assertIn('channel.color = peerColor(colorId, colorEmoji);', color)
        self.assertIn('channel.flags2 |= TLObject.FLAG_7;', color)


if __name__ == '__main__':
    unittest.main()
