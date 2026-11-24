#!/usr/bin/env python3
"""Переключатель «Реакции» не откатывается после переоткрытия настроек.

С устройства: в канале и группе «Реакции» включаешь/выключаешь, закрываешь «Изменить»,
открываешь снова — значение прежнее. Причин было две: у группы настройки реакций вообще
не сохранялись (запрос молча возвращал пустой успех), а у канала сохранённый набор эмодзи
перебивал `reactions_enabled = false`.
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'


class ReactionSettingsPersistence(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.chats = cls.source('tgnet/WaysGramChats.java')
        cls.server = (ROOT / 'authbot' / 'messenger_api.py').read_text(encoding='utf-8')

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    @classmethod
    def source(cls, relative):
        return cls.archive.read(BASE + relative).decode('utf-8')

    @staticmethod
    def body(source, signature):
        start = source.index(signature)
        return source[start:source.index('\n    }', start)]

    def test_group_reaction_change_reaches_its_own_endpoint(self):
        body = self.body(self.chats, 'public static TLRPC.TL_updates setAvailableReactions(')
        self.assertNotIn('Набор реакций группы сервер хранит отдельно', body)
        self.assertIn('boolean group = WaysGramChats.isGroup(req.peer);', body)
        self.assertIn('"/api/groups/" + channelId + "/reactions-settings"', body)
        self.assertIn('"/api/channels/" + channelId + "/reactions-settings"', body)

    def test_channel_disabled_reactions_win_over_the_stored_set(self):
        body = self.body(self.chats, 'public static TLRPC.TL_messages_chatFull fullChannel(')
        disabled = body.index('if (!reactionsEnabled) {')
        window = body[disabled:disabled + 400]
        self.assertIn('full.available_reactions = new TLRPC.TL_chatReactionsNone();', window,
                      'выключенные реакции должны перебивать сохранённый набор эмодзи')

    def test_group_full_chat_reads_group_reaction_settings(self):
        body = self.body(self.chats, 'public static TLRPC.TL_messages_chatFull fullChat(')
        self.assertIn('boolean groupReactionsEnabled = group.optBoolean("reactions_enabled", true);', body)
        self.assertIn('full.reactions_limit = group.optInt("reactions_limit", 11);', body)

    def test_server_stores_group_reaction_settings(self):
        self.assertIn('@router.put("/api/groups/{group_id}/reactions-settings")', self.server)
        handler = self.server[self.server.index('async def set_group_reactions_settings('):]
        handler = handler[:handler.index('@router.get("/api/channels/{channel_id}/stories/can-post")')]
        self.assertIn('GROUP_CHANGE_INFO_REQUIRED', handler)
        self.assertIn('UPDATE chat_groups SET ', handler)
        self.assertIn('record_event(db, "group", 0, "chat_changed", uid, chat_id=group_id)', handler)

    def test_group_card_returns_the_stored_settings(self):
        self.assertIn('result["reactions_enabled"] = bool(reactions_row[0]) if reactions_row else True', self.server)
        self.assertIn('result["available_reactions"] = json.loads(', self.server)
        self.assertIn('ADD COLUMN reactions_enabled INTEGER NOT NULL DEFAULT 1', self.server)


if __name__ == '__main__':
    unittest.main()
