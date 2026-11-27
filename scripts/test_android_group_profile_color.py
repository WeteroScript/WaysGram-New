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

    def test_group_appearance_screen_has_saved_status_and_packs(self):
        chats = self.source('tgnet/WaysGramChats.java')
        transport = self.source('tgnet/WaysGramTransport.java')
        full = chats.split('public static TLRPC.TL_messages_chatFull fullChat(', 1)[1].split(
            'private static void addMember', 1)[0]
        self.assertIn('full.can_set_stickers = admin;', full)
        self.assertIn('full.emojiset = groupStickerSet(group, "emoji_set", true);', full)
        self.assertIn('full.stickerset = groupStickerSet(group, "sticker_set", false);', full)
        status = chats.split('private static void applyChannelFlags', 1)[1].split('private static TLRPC.ChannelLocation', 1)[0]
        self.assertIn('channel.emoji_status = emojiStatus;', status)
        self.assertIn('channel.flags2 |= TLObject.FLAG_9;', status)
        self.assertIn('WaysGramChats.setGroupStickerSet(account, update.channel.channel_id, update.stickerset, false)', transport)
        self.assertIn('WaysGramChats.setGroupStickerSet(account, update.channel.channel_id, update.stickerset, true)', transport)
        group_ui = self.source('ui/GroupColorActivity.java')
        shared_ui = self.source('ui/ChannelColorActivity.java')
        self.assertIn('extends ChannelColorActivity', group_ui)
        self.assertIn('buttonContainer', shared_ui)
        self.assertIn('Gravity.BOTTOM', shared_ui)


if __name__ == '__main__':
    unittest.main()
