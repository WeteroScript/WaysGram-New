#!/usr/bin/env python3
"""Список чатов: подарок «на себе» сразу и «Избранное» не превращается в профиль.

1. Подарок, который пользователь носит, рисуется рядом с именем из `user.emoji_status`,
   поэтому `/api/conversations` обязан отдавать `worn_gift` вместе с собеседником —
   иначе значок появлялся только спустя минуты, когда открывался профиль.
2. Строка «Избранное» — это чат с самим собой. Без флага `user.self` клиент рисует
   имя и аватарку аккаунта вместо закладки (`UserObject.isUserSelf`).
3. Видео без media_kind (старые записи, истории) получает `TL_documentAttributeVideo`
   по mime, иначе файл не проигрывается («App was unable to play this video»).
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"
TG = BASE + "tgnet/"


class ChatListIdentityChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.api = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(TG + name).decode("utf-8")

    def test_conversations_carry_the_worn_gift(self):
        start = self.api.index("async def list_conversations(")
        end = self.api.index("@router.", start)
        block = self.api[start:end]
        self.assertIn("worn_gift_map(db,", block)
        self.assertIn('item["worn_gift"] = worn[int(item["id"])]', block)

        helper = self.api[self.api.index("async def worn_gift_map("):]
        helper = helper[: helper.index("\n    async def ", 10)]
        self.assertIn("profile_worn=1", helper)
        self.assertIn("profile_hidden=0", helper)
        self.assertIn('"traits": traits', helper)

    def test_client_maps_worn_gift_into_emoji_status(self):
        mapper = self.source("WaysGramMapper.java")
        self.assertIn('if (json.has("worn_gift"))', mapper)
        self.assertIn("user.emoji_status = wornGiftStatus(json.optJSONObject(\"worn_gift\"));", mapper)

    def test_saved_chat_user_is_marked_as_self(self):
        transport = self.source("WaysGramTransport.java")
        start = transport.index("    private static Conversations loadConversations(")
        end = transport.index("\n    }\n", start)
        block = transport[start:end]
        self.assertIn("user.self = user.id == selfId;", block)

    def test_video_without_media_kind_still_becomes_a_video(self):
        media = self.source("WaysGramMedia.java")
        self.assertIn(
            'if (!forceDocument && TextUtils.isEmpty(kind) && mime != null && mime.startsWith("video/")) kind = "video";',
            media,
        )
        start = media.index("    public static TLRPC.MessageMedia buildMedia(int account, String mediaId, String mime, String name,")
        end = media.index("\n    }\n", start)
        block = media[start:end]
        # тип должен определиться до ветки, которая добавляет атрибут видео
        self.assertLess(block.index('kind = "video";'), block.index("TL_documentAttributeVideo video"))


if __name__ == "__main__":
    unittest.main()
