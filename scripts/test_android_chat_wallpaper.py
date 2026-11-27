#!/usr/bin/env python3
"""Обои из «Внешний вид и эмодзи» доезжают до чата.

С устройства: выбрал обои в группе или канале — фон не меняется. `messages.setChatWallPaper`
не обслуживался вовсе: сервер о выборе не знал, карточка чата не возвращала обои, и
`ChatThemeController.getDialogWallpaper` всегда получал `null`.
"""
import io
import re
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
        cls.theme = cls.archive.read(BASE + 'messenger/ChatThemeController.java').decode('utf-8')
        cls.wallpapers = cls.archive.read(TGNET + 'WaysGramWallpapers.java').decode('utf-8')
        cls.transport = cls.archive.read(TGNET + 'WaysGramTransport.java').decode('utf-8')

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
        self.assertIn('WaysGramChats.chatChanged(account, peer.scope, peer.chatId);', handler)
        self.assertIn('if ("dm".equals(peer.scope)) {', handler)

    def test_settings_are_serialised_field_by_field(self):
        body = self.body(self.extras, 'private static JSONObject wallpaperJson(')
        for field in ('background_color', 'second_background_color', 'third_background_color',
                      'fourth_background_color', 'intensity', 'rotation', 'blur', 'motion', 'emoticon'):
            self.assertIn(f'"{field}"', body, field)

    def test_settings_come_from_the_request_not_from_the_input_wallpaper(self):
        # `request.wallpaper` имеет тип TLRPC.InputWallPaper, у которого нет поля settings;
        # обращение к нему не компилируется (упавшая сборка 326). Градиент/узор лежат
        # только в `request.settings`.
        body = self.body(self.extras, 'private static JSONObject wallpaperJson(')
        self.assertIn('TLRPC.WallPaperSettings settings = req.settings;', body)
        self.assertNotIn('req.wallpaper.settings', body)

    def test_chat_card_carries_the_wallpaper(self):
        builder = self.body(self.chats, 'static TLRPC.WallPaper wallpaper(int account, JSONObject data)')
        # Картинка из галереи отдаётся документом, цвет и градиент — `TL_wallPaperNoFile`.
        self.assertIn('WaysGramWallpapers.fromJson(account, data)', builder)
        self.assertIn('TLRPC.TL_wallPaperNoFile paper = new TLRPC.TL_wallPaperNoFile();', builder)
        self.assertIn('paper.settings = WaysGramWallpapers.settings(data);', builder)
        for signature in ('public static TLRPC.TL_messages_chatFull fullChannel(',
                          'public static TLRPC.TL_messages_chatFull fullChat('):
            body = self.body(self.chats, signature)
            self.assertIn('full.wallpaper = wallpaper(account,', body, signature)
            self.assertIn('if (full.wallpaper != null) full.flags2 |= 128;', body, signature)

    def test_wallpaper_survives_reopening_the_chat(self):
        # Настоящий Telegram отвечает updateNewMessage со служебным «обои изменены», и только
        # из него клиент пишет chatFull.wallpaper и prefs. WaysGram отвечает пустым updates,
        # поэтому обои применялись и пропадали при следующем открытии чата.
        self.assertIn('final boolean[] wallpaperApplied = new boolean[1];', self.theme)
        self.assertIn('if (!wallpaperApplied[0] && finalApplyOnRequest) {', self.theme)
        self.assertIn('applyWaysGramWallpaper(dialogId, req, userFull, chatFull);', self.theme)
        body = self.body(self.theme, 'private void applyWaysGramWallpaper(')
        self.assertIn('org.telegram.tgnet.WaysGramTransport.ENABLED', body)
        self.assertIn('saveChatWallpaper(dialogId, wallpaper);', body)
        self.assertIn('chatFull.flags2 |= 128;', body)
        self.assertIn('userFull.flags |= 16777216;', body)

    def test_photo_wallpaper_is_uploaded_as_a_media_file(self):
        # Каталога обоев у WaysGram нет, поэтому обои-картинка приходят из галереи:
        # `account.uploadWallPaper` должен загрузить файл и вернуть `TL_wallPaper`
        # с документом, иначе выбранная картинка никуда не уходит.
        self.assertIn('TLObject wallpaper = WaysGramWallpapers.handle(account, request);', self.transport)
        handler = self.body(self.wallpapers, 'private static TLObject upload(')
        self.assertIn('WaysGramMedia.uploadFile(account, req.file, true)', handler)
        self.assertIn('fromMedia(account, mediaId, req.settings)', handler)
        builder = self.body(self.wallpapers, 'public static TLRPC.TL_wallPaper fromMedia(')
        self.assertIn('WaysGramMedia.buildMedia(', builder)
        self.assertIn('paper.document = document;', builder)
        self.assertIn('TLRPC.TL_documentAttributeImageSize', builder)

    def test_slug_links_the_chosen_wallpaper_with_the_uploaded_file(self):
        # Клиент запоминает обои как `slug`, а не как id: `messages.setChatWallPaper`
        # присылает `TL_inputWallPaperSlug`, и по нему нужно найти загруженный файл,
        # в том числе после перезапуска приложения.
        slug = self.body(self.wallpapers, 'static String slug(String mediaId)')
        self.assertIn('return SLUG_PREFIX + mediaId;', slug)
        resolve = self.body(self.wallpapers, 'public static String mediaId(int account, TLRPC.InputWallPaper input)')
        self.assertIn('TLRPC.TL_inputWallPaperSlug', resolve)
        self.assertIn('slug.startsWith(SLUG_PREFIX) && slug.length() == SLUG_PREFIX.length() + 32', resolve)
        self.assertIn('WaysGramMedia.mediaIdFor(((TLRPC.TL_inputWallPaper) input).id)', resolve)

    def test_server_gets_document_id_for_a_photo_wallpaper(self):
        body = self.body(self.extras, 'private static JSONObject wallpaperJson(')
        self.assertIn('String documentId = WaysGramWallpapers.mediaId(account, req.wallpaper);', body)
        self.assertIn('out.put("document_id", documentId);', body)
        # Сервер сохраняет обои как произвольный JSON, поэтому `document_id` переживает
        # перезапуск сервера и возвращается в карточке чата.
        self.assertIn('wallpaper = {key: value for key, value in (wallpaper or {}).items() if value is not None}',
                      self.server)
        self.assertIn('out.put("for_both", req.for_both);', body)

    def test_applied_wallpaper_is_the_photo_when_a_file_was_uploaded(self):
        body = self.body(self.theme, 'private void applyWaysGramWallpaper(')
        self.assertIn('org.telegram.tgnet.WaysGramWallpapers.fromInput(currentAccount, req.wallpaper, req.settings)',
                      body)

    def test_every_used_wallpaper_helper_exists(self):
        # Новый класс: сборка падает на любой опечатке в имени метода, а javac в тестах нет.
        declared = set(re.findall(r'(?:public|private|static|static final)[^;{]*?\s(\w+)\(', self.wallpapers))
        used = set()
        for name in ('tgnet/WaysGramChats.java', 'tgnet/WaysGramExtras.java', 'tgnet/WaysGramTransport.java',
                     'messenger/ChatThemeController.java'):
            used |= set(re.findall(r'WaysGramWallpapers\.(\w+)\(', self.archive.read(BASE + name).decode('utf-8')))
        self.assertTrue(used)
        self.assertEqual(used - declared - {'ApiException'}, set())

    def test_chat_wallpaper_is_shared_for_chat_admins_and_notified_to_members(self):
        self.assertIn('for telegram_id in (uid, 0):', self.server)
        self.assertIn('can_share = await group_can_change_info(db, chat_id, membership)', self.server)
        self.assertIn('can_share = await channel_has_right(db, chat_id, uid, 64)', self.server)
        self.assertIn('target_uid = 0 if shared else uid', self.server)
        self.assertIn('record_event(db, scope, 0, "chat_changed", uid, chat_id=chat_id)', self.server)
        self.assertIn('CREATE TABLE IF NOT EXISTS main.chat_wallpaper_migrations', self.server)

    def test_server_stores_and_returns_the_wallpaper(self):
        self.assertIn('@router.put("/api/chats/{scope}/{chat_id}/wallpaper")', self.server)
        self.assertIn('@router.delete("/api/chats/{scope}/{chat_id}/wallpaper")', self.server)
        self.assertIn('CREATE TABLE IF NOT EXISTS main.chat_wallpapers', self.server)
        self.assertIn('result["wallpaper"] = await chat_wallpaper(db, "group", group_id, uid)', self.server)
        self.assertIn('channel["wallpaper"] = await chat_wallpaper(db, "channel", channel_id', self.server)


if __name__ == '__main__':
    unittest.main()
