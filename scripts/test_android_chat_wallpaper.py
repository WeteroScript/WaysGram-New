#!/usr/bin/env python3
"""Обои из «Внешний вид и эмодзи» доезжают до чата.

С устройства: выбрал обои в группе или канале — фон не меняется. `messages.setChatWallPaper`
не обслуживался вовсе: сервер о выборе не знал, карточка чата не возвращала обои, и
`ChatThemeController.getDialogWallpaper` всегда получал `null`.
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'
TGNET = BASE + 'tgnet/'


class ChatWallpaper(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.extras = cls.archive.read(TGNET + 'WaysGramExtras.java').decode('utf-8')
        cls.chats = cls.archive.read(TGNET + 'WaysGramChats.java').decode('utf-8')
        cls.server = (ROOT / 'authbot' / 'messenger_api.py').read_text(encoding='utf-8')

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    @staticmethod
    def body(source, signature):
        start = source.index(signature)
        return source[start:source.index('\n    }', start)]

    def test_client_sends_the_chosen_wallpaper_to_the_chat_endpoint(self):
        self.assertIn('request instanceof TLRPC.TL_messages_setChatWallPaper', self.extras)
        handler = self.body(self.extras, 'private static TLObject setChatWallpaper(')
        self.assertIn('Target peer = target(req.peer, selfId);', handler)
        self.assertIn('"/api/chats/" + peer.scope + "/" + peer.chatId + "/wallpaper"', handler)
        self.assertIn('WaysGramApi.put(account, path, body);', handler)
        self.assertIn('if (req.revert) {', handler)
        self.assertIn('WaysGramApi.delete(account, path);', handler)
        self.assertIn('if ("dm".equals(peer.scope)) {', handler)

    def test_settings_are_serialised_field_by_field(self):
        body = self.body(self.extras, 'private static JSONObject wallpaperJson(')
        for field in ('background_color', 'second_background_color', 'third_background_color',
                      'fourth_background_color', 'intensity', 'rotation', 'blur', 'motion', 'emoticon'):
            self.assertIn(f'"{field}"', body, field)

    def test_chat_card_carries_the_wallpaper(self):
        builder = self.body(self.chats, 'static TLRPC.WallPaper wallpaper(JSONObject data)')
        self.assertIn('TLRPC.TL_wallPaperNoFile paper = new TLRPC.TL_wallPaperNoFile();', builder)
        self.assertIn('paper.settings = settings;', builder)
        for signature in ('public static TLRPC.TL_messages_chatFull fullChannel(',
                          'public static TLRPC.TL_messages_chatFull fullChat('):
            body = self.body(self.chats, signature)
            self.assertIn('full.wallpaper = wallpaper(', body, signature)
            self.assertIn('if (full.wallpaper != null) full.flags2 |= 128;', body, signature)

    def test_server_stores_and_returns_the_wallpaper(self):
        self.assertIn('@router.put("/api/chats/{scope}/{chat_id}/wallpaper")', self.server)
        self.assertIn('@router.delete("/api/chats/{scope}/{chat_id}/wallpaper")', self.server)
        self.assertIn('CREATE TABLE IF NOT EXISTS main.chat_wallpapers', self.server)
        self.assertIn('result["wallpaper"] = await chat_wallpaper(db, "group", group_id, uid)', self.server)
        self.assertIn('channel["wallpaper"] = await chat_wallpaper(db, "channel", channel_id', self.server)


if __name__ == '__main__':
    unittest.main()
