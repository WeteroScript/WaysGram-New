#!/usr/bin/env python3
"""Статические проверки краша из отчёта 24.11: очистка истории группы.

На устройстве падало с ``ClassCastException: TL_messages_affectedMessages cannot be
cast to TLRPC$Updates`` в ``MessagesController.lambda$deleteDialog$145``. Группы в
клиенте — мегагруппы, поэтому «Очистить историю» уходит запросом
``channels.deleteHistory``, а его обслуживает ``WaysGramChats.groupClearHistory``.
Штатный обработчик приводит ответ ровно к ``TLRPC.Updates``.

Тот же дефект был у удаления истории участника: ``channels.deleteParticipantHistory``
ждёт ``messages.affectedHistory``, а группа возвращала ``messages.affectedMessages``.

Отдельно проверяется, что исключение внутри колбэка запроса больше не уходит в пул
потоков и не валит приложение.
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TGNET = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/tgnet/'
MESSENGER = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/messenger/'


class GroupHistoryCrashChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.chats = cls.source('WaysGramChats.java')
        cls.transport = cls.source('WaysGramTransport.java')

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    @classmethod
    def source(cls, name):
        return cls.archive.read(TGNET + name).decode('utf-8')

    def method_body(self, start_marker, end_marker):
        start = self.chats.index(start_marker)
        return self.chats[start:self.chats.index(end_marker, start)]

    def test_clear_group_history_returns_updates(self):
        body = self.method_body('static TLObject groupClearHistory(',
                                'static TLObject groupClearMemberHistory(')
        self.assertIn('return new TLRPC.TL_updates();', body)
        self.assertNotIn('TL_messages_affectedMessages', body,
                         'channels.deleteHistory ждёт Updates, иначе снова ClassCastException')

    def test_clear_group_member_history_returns_affected_history(self):
        body = self.method_body('static TLObject groupClearMemberHistory(',
                                'static TLObject groupGetMessages(')
        self.assertIn('new TLRPC.TL_messages_affectedHistory()', body)
        self.assertNotIn('TL_messages_affectedMessages', body)
        for field in ('out.pts = ', 'out.pts_count = ', 'out.offset = '):
            self.assertIn(field, body, 'обработчик канала читает ' + field.strip())

    def test_group_requests_are_dispatched_to_these_handlers(self):
        megagroups = self.source('WaysGramMegagroups.java')
        self.assertIn('WaysGramChats.groupClearHistory(account, req.channel.channel_id)', megagroups)
        self.assertIn('WaysGramChats.groupClearMemberHistory(', megagroups)

    def test_callers_still_expect_the_native_types(self):
        controller = self.archive.read(MESSENGER + 'MessagesController.java').decode('utf-8')
        self.assertIn('processUpdates((TLRPC.Updates) response, false);', controller)
        self.assertIn('TLRPC.TL_messages_affectedHistory res = (TLRPC.TL_messages_affectedHistory) response;',
                      controller)

    def test_request_callback_exception_does_not_kill_the_process(self):
        self.assertIn('private static void deliver(RequestDelegate delegate, TLObject response, '
                      'TLRPC.TL_error error)', self.transport)
        self.assertEqual(self.transport.count('delegate.run('), 1,
                         'колбэк вызывается только из deliver()')
        self.assertIn('try {\n            delegate.run(response, error);\n        } catch (Throwable t) {\n'
                      '            FileLog.e(t);', self.transport)
        self.assertEqual(self.transport.count('deliver(delegate'), 7,
                         'execute() и оба direct/group paid-message ответных пути идут через deliver()')


if __name__ == '__main__':
    unittest.main()
