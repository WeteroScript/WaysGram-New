#!/usr/bin/env python3
"""«Welcome message» отвечает, а не крутит бесконечную загрузку.

С устройства: экран «Welcome message» в канале и группе висит на загрузке. Экран ждёт
ответ `ephemeral.getWelcomeMessages`, а транспорт его не обслуживал — `WaysGramExtras`
возвращал null, ответ не приходил, загрузчик ждал вечно.
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TGNET = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/tgnet/'


class WelcomeMessages(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.extras = cls.archive.read(TGNET + 'WaysGramExtras.java').decode('utf-8')
        cls.server = (ROOT / 'authbot' / 'messenger_api.py').read_text(encoding='utf-8')

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    @staticmethod
    def body(source, signature):
        start = source.index(signature)
        return source[start:source.index('\n    }', start)]

    def test_transport_answers_the_welcome_screen(self):
        self.assertIn('import org.telegram.tgnet.tl.TL_ephemeral;', self.extras)
        self.assertIn('request instanceof TL_ephemeral.TL_getWelcomeMessages', self.extras)
        self.assertIn('request instanceof TL_ephemeral.TL_deleteWelcomeMessage', self.extras)
        self.assertIn('request instanceof TL_ephemeral.TL_deleteAllWelcomeMessages', self.extras)
        self.assertIn('((TL_ephemeral.TL_sendMessage) request).welcome', self.extras)

    def test_empty_list_is_a_normal_answer(self):
        body = self.body(self.extras, 'private static TLObject welcomeMessages(')
        self.assertIn('TL_ephemeral.TL_welcomeMessages out = new TL_ephemeral.TL_welcomeMessages();', body)
        self.assertIn('new TL_ephemeral.TL_welcomeMessagesNotModified()', body)
        self.assertIn('"/api/chats/" + peer.scope + "/" + peer.chatId + "/welcome"', body)

    def test_created_message_is_returned_as_an_ephemeral_update(self):
        body = self.body(self.extras, 'private static TLObject sendWelcomeMessage(')
        self.assertIn('TL_update.TL_updateNewEphemeralMessage update', body)
        self.assertIn('update.message = ephemeral(account, row, peer, selfId);', body)
        built = self.body(self.extras, 'private static TL_ephemeral.TL_ephemeralMessage ephemeral(')
        self.assertIn('message.welcome = true;', built)
        self.assertIn('message.peer_id = chatPeer;', built)

    def test_server_serves_the_welcome_screen(self):
        for route in ('@router.get("/api/chats/{scope}/{chat_id}/welcome")',
                      '@router.post("/api/chats/{scope}/{chat_id}/welcome", status_code=201)',
                      '@router.delete("/api/chats/{scope}/{chat_id}/welcome")'):
            self.assertIn(route, self.server)
        self.assertIn('CREATE TABLE IF NOT EXISTS main.chat_welcome_messages', self.server)
        handler = self.server[self.server.index('async def require_welcome_manager('):]
        self.assertIn('CHAT_ADMIN_REQUIRED', handler[:600])


if __name__ == '__main__':
    unittest.main()
