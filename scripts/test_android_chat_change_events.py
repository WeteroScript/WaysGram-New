#!/usr/bin/env python3
"""Изменения чата применяются сразу: сервер шлёт событие, клиент перечитывает чат.

С устройства: права группы «принимаются через минуты или часы, иногда не меняются
вообще»; аватар и название у второго аккаунта тоже не появлялись. Причина — сервер о
таких изменениях не сообщал, а клиентский поллер знал только события сообщений.
"""
import io
import re
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TGNET = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/tgnet/'


class ChatChangeEventChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.chats = cls.source('WaysGramChats.java')
        cls.poller = cls.source('WaysGramPoller.java')
        cls.server = (ROOT / 'authbot' / 'messenger_api.py').read_text(encoding='utf-8')

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    @classmethod
    def source(cls, name):
        return cls.archive.read(TGNET + name).decode('utf-8')

    def test_poller_refreshes_the_chat_on_a_change_event(self):
        self.assertIn('if (kind.equals("chat_changed"))', self.poller)
        self.assertIn('WaysGramChats.chatChanged(account, scope, e.optLong("chat_id"));', self.poller)

    def test_chat_changed_drops_only_this_account_and_reloads_the_profile(self):
        start = self.chats.index('public static void chatChanged(')
        body = self.chats[start:self.chats.index('\n    }', start)]
        self.assertIn('chatCache.remove(cacheKey(account, tgId));', body,
                      'кэш другого аккаунта трогать нельзя')
        self.assertIn('"channel".equals(scope) ? channelTg(id) : id', body)
        self.assertIn('loadFullChat(tgId, 0, true)', body)
        self.assertIn('dialogsNeedReload', body)

    def test_server_emits_the_event_for_settings_rights_and_photos(self):
        handler = self.server[self.server.index('async def record_event('):]
        handler = handler[:handler.index('@router.get("/api/me/auto-delete")')]
        self.assertIn('"chat_changed"', handler + self.server)

        emitted = re.findall(r'record_event\(db, "(group|channel)", 0, "chat_changed", [^)]*\)', self.server)
        self.assertGreaterEqual(len(emitted), 8,
                                'настройки, права, аватар, название и админы обязаны сообщать об изменении')
        self.assertIn('group', emitted)
        self.assertIn('channel', emitted)

    def test_events_are_written_before_commit(self):
        for block in re.findall(r'await record_event\(db, "(?:group|channel)", 0, "chat_changed".*?\n(.*?)\n',
                                self.server)[:8]:
            self.assertIn('commit', block)


if __name__ == '__main__':
    unittest.main()
