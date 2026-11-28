#!/usr/bin/env python3
"""Статические проверки геолокации канала и группы в Android-исходниках.

`channels.editLocation` раньше входил в список заглушек: транспорт отвечал `TL_boolTrue`,
экран показывал адрес из памяти, а после перезагрузки профиля точка исчезала. Теперь
запрос уходит на сервер, а `channels.getFullChannel` возвращает `TL_channelLocation`.
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'


class ChatLocationSourceChecks(unittest.TestCase):
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

    def test_edit_location_is_not_a_stub_anymore(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        self.assertIn('request instanceof TLRPC.TL_channels_editLocation', transport)
        self.assertIn('return WaysGramChats.updateLocation(account, edit.channel.channel_id,', transport)
        stub = transport.split('request instanceof TLRPC.TL_channels_reorderUsernames', 1)[1].split('return new TLRPC.TL_boolTrue();', 1)[0]
        self.assertNotIn('TL_channels_editLocation', stub)
        self.assertIn('throw new WaysGramApi.ApiException(400, "LOCATION_INVALID");', transport)

    def test_location_is_saved_per_peer_kind(self):
        chats = self.source('tgnet/WaysGramChats.java')
        self.assertIn('public static TLRPC.TL_boolTrue updateLocation(int account, long tgId, double latitude,',
                      chats)
        self.assertIn('? "/api/groups/" + tgId + "/location"', chats)
        self.assertIn(': "/api/channels/" + channelServer(tgId) + "/location"', chats)
        self.assertIn('body.put("label", address == null ? "" : address);', chats)
        self.assertIn('body.put("address", address == null ? "" : address);', chats)

    def test_full_chat_maps_server_location(self):
        chats = self.source('tgnet/WaysGramChats.java')
        self.assertIn('private static TLRPC.ChannelLocation chatLocation(JSONObject data)', chats)
        self.assertIn('result.address = !TextUtils.isEmpty(label) ? label : address;', chats)
        self.assertEqual(chats.count('full.location = channelLocation;')
                         + chats.count('full.location = groupLocation;'), 2)
        self.assertEqual(chats.count('full.flags |= TLObject.FLAG_2;') >= 2, True)
        self.assertEqual(chats.count('full.can_set_location = '), 2)

    def test_channel_settings_use_the_stock_location_condition(self):
        editor = self.source('ui/ChatEditActivity.java')
        self.assertNotIn('waysGramChannelLocation', editor)
        self.assertIn('if (currentChat.megagroup && (info == null || info.can_set_location)) {', editor)


if __name__ == '__main__':
    unittest.main()
