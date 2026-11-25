#!/usr/bin/env python3
"""Регрессии по отчёту пользователя: реакции, канал, права группы, истории, подарки.

Проверки статические: исходники читаются из частей `WaysGram-Android.zip.part*`,
поэтому тест ловит и «забыл положить правку в архив».

Что проверяем и почему:

* реакции — сервер и клиент сравнивают эмодзи без U+FE0F, а клиент приводит любую
  запись к каталогу (`WaysGramReactions.canonical`), иначе «Не удалось подтвердить
  реакцию» и системный шрифт вместо официальной анимации;
* платные сообщения — переключатель отправляет `suggestions_allowed`, без него
  транспорт считает `enabled = false` и настройка сбрасывается;
* `premium.getBoostsStatus` обслуживается транспортом, иначе «Применить обои» для
  канала молча ничего не делает;
* платные реакции канала включаются из редактора реакций;
* маска прав участников хранит отдельный бит для каждого переключателя, а старые
  строки читаются в старой раскладке (`send_restrictions_version`);
* видео-история с пустым mime всё равно получает атрибут видео (иначе плеер
  показывает «App was unable to play this video»);
* «Value» подарка берётся из уплаченной цены, а не из возврата при продаже;
* узор носимого подарка восстанавливается по имени черты, если в записи нет id.
"""
import unittest
import zipfile
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/"
JAVA = BASE + "java/org/telegram/"
SERVER = ROOT / "authbot" / "messenger_api.py"


def archive_bytes() -> bytes:
    parts = sorted(ROOT.glob("WaysGram-Android.zip.part*"), key=lambda path: path.name)
    return b"".join(part.read_bytes() for part in parts)


class ReportFixes(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = zipfile.ZipFile(BytesIO(archive_bytes()))
        cls.server = SERVER.read_text(encoding="utf-8")

    def source(self, relative: str) -> str:
        return self.archive.read(BASE + relative).decode("utf-8")

    # ---------------------------------------------------------------- реакции

    def test_reaction_identity_ignores_variation_selector(self):
        self.assertIn("def _reaction_key(", self.server)
        self.assertIn("def _reaction_catalog(", self.server)
        self.assertIn('if char != "\\ufe0f" and char not in _SKIN_TONES', self.server)
        # Оба маршрута реакций сравнивают по ключу, а не по точной строке.
        self.assertIn("allowed = _reaction_catalog(", self.server)
        self.assertIn("reaction = allowed[reaction_key]", self.server)
        self.assertIn("requested = [allowed[key] for key in requested_keys]", self.server)
        self.assertIn("any(_reaction_key(value) not in basic for value in previous)", self.server)

    def test_client_stores_reactions_in_catalog_form(self):
        reactions = self.source("java/org/telegram/tgnet/WaysGramReactions.java")
        self.assertIn("public static String canonical(String value)", reactions)
        mapper = self.source("java/org/telegram/tgnet/WaysGramMapper.java")
        self.assertIn("emoji = WaysGramReactions.canonical(emoji);", mapper)
        self.assertIn('String emoji = WaysGramReactions.canonical(item.optString("reaction", ""));', mapper)
        self.assertIn("emoji.equals(WaysGramReactions.canonical(mine.optString(i)))", mapper)
        messages = self.source("java/org/telegram/tgnet/WaysGramMessages.java")
        self.assertIn("String emoji = WaysGramReactions.canonical(((TLRPC.TL_reactionEmoji) value).emoticon);", messages)
        chats = self.source("java/org/telegram/tgnet/WaysGramChats.java")
        self.assertEqual(chats.count("reaction.emoticon = WaysGramReactions.canonical(emoji);"), 2)

    # ------------------------------------------------------- платные сообщения

    def test_paid_messages_toggle_sends_suggestions_allowed(self):
        users = self.source("java/org/telegram/ui/ChatUsersActivity.java")
        self.assertIn("req.suggestions_allowed = enablePrice;", users)
        self.assertIn("req.flags |= 1;", users)
        transport = self.source("java/org/telegram/tgnet/WaysGramTransport.java")
        self.assertIn("boolean enabled = price.suggestions_allowed && stars > 0;", transport)

    # ---------------------------------------------------------------- бусты

    def test_boosts_status_is_served(self):
        transport = self.source("java/org/telegram/tgnet/WaysGramTransport.java")
        self.assertIn("request instanceof TL_stories.TL_premium_getBoostsStatus", transport)
        self.assertIn("private static TLObject boostsStatus(TL_stories.TL_premium_getBoostsStatus req)", transport)
        self.assertIn("status.level = 1;", transport)

    # -------------------------------------------------------- платные реакции

    def test_paid_reactions_toggle_exists(self):
        editor = self.source("java/org/telegram/ui/ChatReactionsEditActivity.java")
        self.assertIn("private TextCheckCell paidReactionsCell;", editor)
        self.assertIn("R.string.ChannelEnablePaidReactions", editor)
        self.assertIn("isChannel ? paidReactionsEnabled : null", editor)
        controller = self.source("java/org/telegram/messenger/MessagesController.java")
        self.assertIn("public void setChatReactions(long chatId, int type, List<String> reactions, Boolean paidEnabled)", controller)
        self.assertIn("req.paid_enabled = paidEnabled;", controller)
        self.assertIn("full.paid_reactions_available = paidEnabled;", controller)

    # ------------------------------------------------------------ права группы

    def test_group_restriction_mask_has_a_bit_per_switch(self):
        chats = self.source("java/org/telegram/tgnet/WaysGramChats.java")
        for bit, field in (
            (8, "send_audios"),
            (16, "send_stickers"),
            (32, "send_docs"),
            (64, "send_voices"),
            (128, "send_roundvideos"),
            (256, "embed_links"),
            (512, "send_polls"),
            (1024, "send_reactions"),
        ):
            self.assertIn(f"rights.{field} = (mask & {bit}) != 0;", chats)
        self.assertIn("if (rights.send_voices) mask |= 64;", chats)
        self.assertIn("if (rights.embed_links) mask |= 256;", chats)
        self.assertIn("if (rights.send_reactions) mask |= 1024;", chats)

    def test_server_accepts_extended_mask_and_reads_old_rows(self):
        self.assertIn("SEND_REACTIONS = 1024", self.server)
        self.assertIn("MAX_SEND_RESTRICTIONS = SEND_TEXT | SEND_PHOTOS", self.server)
        self.assertIn("if type(mask) is not int or not 0 <= mask <= MAX_SEND_RESTRICTIONS:", self.server)
        self.assertIn("send_restrictions_version INTEGER NOT NULL DEFAULT 0", self.server)
        self.assertIn("def send_restrictions(mask: int, version: int) -> int:", self.server)
        self.assertIn("await db.execute(\"\"\"UPDATE chat_groups SET default_send_restrictions=?,send_restrictions_version=1,", self.server)
        # Старые маски читаются в прежнем смысле: бит 8 запрещал и голосовые.
        self.assertIn("        if mask & SEND_AUDIO:\n            mask |= SEND_VOICE", self.server)
        self.assertIn("bit = SEND_ROUND_VIDEO", self.server)
        self.assertIn("bit = SEND_VOICE", self.server)

    def test_group_reactions_restriction_is_enforced(self):
        self.assertIn("& SEND_REACTIONS:", self.server)
        self.assertIn('detail="Реакции запрещены в этой группе"', self.server)

    # --------------------------------------------------------------- истории

    def test_video_without_mime_still_gets_video_attribute(self):
        media = self.source("java/org/telegram/tgnet/WaysGramMedia.java")
        self.assertIn('boolean videoKind = "video".equals(kind) || "video_note".equals(kind) || "gif".equals(kind);', media)
        self.assertIn('if (videoKind && (mime == null || !mime.startsWith("video/")))', media)
        self.assertIn("} else if (videoKind && mime != null && mime.startsWith(\"video/\")) {", media)

    # ---------------------------------------------------------------- подарки

    def test_gift_value_uses_purchase_price(self):
        controller = self.source("java/org/telegram/messenger/waysgram/WaysGramProfileGiftsController.java")
        self.assertIn('int purchasePrice = Math.max(0, item.optInt("purchase_price", 0));', controller)
        self.assertIn("int paidPrice = purchasePrice > 0", controller)
        self.assertIn('"purchase_price": int(gift["purchase_price"] or 0),', self.server)

    def test_worn_gift_pattern_is_resolved_by_name(self):
        mapper = self.source("java/org/telegram/tgnet/WaysGramMapper.java")
        self.assertIn("private static long resolveWornDocument(", mapper)
        self.assertIn('relaxed.remove("strict_artwork");', mapper)
        self.assertIn("status.pattern_document_id = resolveWornDocument(gift, traits, \"pattern\", pattern);", mapper)
        self.assertIn("WaysGramBundledArtwork.install();", mapper)


if __name__ == "__main__":
    unittest.main()
