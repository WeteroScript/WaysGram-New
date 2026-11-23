#!/usr/bin/env python3
"""Статические проверки передачи владения каналом.

Штатный флоу Telegram (`channels.editCreator` + SRP-пароль) в WaysGram не работает: сервер
не умеет SRP. Поэтому в настройках канала есть своя строка «Передать канал», которая
ходит в `/api/channels/{id}/transfer`, а сам `channels.editCreator` отвечает понятной
ошибкой вместо «не поддерживается».
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'


class ChannelTransferSourceChecks(unittest.TestCase):
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

    def test_native_edit_creator_explains_where_to_transfer(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        block = transport.split('request instanceof TLRPC.TL_channels_editCreator', 1)[1].split(
            'request instanceof TLRPC.TL_channels_editLocation', 1)[0]
        self.assertIn('throw new WaysGramApi.ApiException(400,', block)
        self.assertIn('Передача канала доступна в настройках канала WaysGram', block)

    def test_transfer_helper_uses_rest_endpoint(self):
        helper = self.source('ui/WaysGramChannelTransfer.java')
        self.assertIn('return "/api/channels/" + WaysGramChats.channelServer(channel);', helper)
        self.assertIn('final String path = channelPath(channel) + "/transfer";', helper)
        self.assertIn('WaysGramApi.request("GET", path, null, token, 10000)', helper)
        self.assertIn('WaysGramApi.request("POST", path, body, token, 15000)', helper)
        self.assertIn('body.put("new_owner_id", newOwner);', helper)
        self.assertIn('data.optBoolean("password_required")', helper)
        self.assertIn('password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);',
                      helper)
        self.assertIn('R.string.WaysGramTransferDone', helper)
        self.assertIn('loadFullChat(channel, 0, true)', helper)

    def test_settings_do_not_expose_the_waysgram_transfer_row(self):
        editor = self.source('ui/ChatEditActivity.java')
        self.assertNotIn('WaysGramChannelTransfer.open(', editor)
        self.assertNotIn('R.string.WaysGramTransferChannel', editor)

    def test_transfer_strings_are_localized(self):
        for language in ('values', 'values-ru'):
            path = f'WaysGram-Android/TMessagesProj/src/main/res/{language}/strings.xml'
            strings = self.archive.read(path).decode('utf-8')
            for name in ('WaysGramTransferChannel', 'WaysGramTransferChoose', 'WaysGramTransferConfirm',
                         'WaysGramTransferPassword', 'WaysGramTransferDone', 'WaysGramTransferError',
                         'WaysGramTransferNoAdmins'):
                self.assertIn(f'name="{name}"', strings)


if __name__ == '__main__':
    unittest.main()
