#!/usr/bin/env python3
"""Реакции выглядят как в Telegram: официальный арт и «поп» вместо рывка.

Анимированные эмодзи Telegram (TGS из `messages.getAvailableReactions`) приходят только
с серверов Telegram, публично их нет. Поэтому в клиенте официальный рисунок эмодзи из
`emoji.pack` (EPK3, 64×64) и официальные Lottie-эффекты из `ilyhalight/telegram-emoji-effects`,
а «поп» при появлении реакции клиент анимирует сам — пружинным ростом, как в Telegram.
"""
import gzip
import io
import json
import re
import struct
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/"
OVERLAY = BASE + "java/org/telegram/ui/Components/Reactions/ReactionsEffectOverlay.java"
REACTIONS = BASE + "java/org/telegram/tgnet/WaysGramReactions.java"
EFFECTS = BASE + "assets/waysgram_emoji/"
TEXTURES = BASE + "assets/telegram_reactions/"


def webp_size(raw: bytes):
    """Размер кадра WebP без декодера: VP8, VP8L или VP8X."""
    if raw[:4] != b"RIFF" or raw[8:12] != b"WEBP":
        return None
    fourcc = raw[12:16]
    if fourcc == b"VP8 ":
        width, height = struct.unpack("<HH", raw[26:30])
        return width & 0x3FFF, height & 0x3FFF
    if fourcc == b"VP8L":
        bits = int.from_bytes(raw[21:25], "little")
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    if fourcc == b"VP8X":
        return (int.from_bytes(raw[24:27], "little") + 1,
                int.from_bytes(raw[27:30], "little") + 1)
    return None


class ReactionAnimation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.overlay = cls.archive.read(OVERLAY).decode("utf-8")
        cls.reactions = cls.archive.read(REACTIONS).decode("utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def test_static_artwork_pops_like_telegram(self):
        self.assertIn("private static boolean isWaysGramStaticReaction(TLRPC.Document document)", self.overlay)
        self.assertIn('"image/webp".equals(document.mime_type)', self.overlay)
        body = self.overlay[self.overlay.index("private void popWaysGramStaticReaction("):]
        body = body[:body.index("\n    }")]
        self.assertIn("emojiImageView.setScaleX(0.35f);", body)
        self.assertIn("emojiImageView.animate().scaleX(1f).scaleY(1f)", body)
        self.assertIn("CubicBezierInterpolator.EASE_OUT_BACK", body)
        self.assertIn(".setDuration(260)", body)

    def test_pop_runs_for_appear_select_and_activate(self):
        block = self.overlay[
            self.overlay.index("if (animationType == ONLY_MOVE_ANIMATION || animationType == LONG_ANIMATION) {"):
        ]
        block = block[:block.index("} else if (localEmojiFallback)")]
        self.assertIn("availableReaction.select_animation : availableReaction.appear_animation", block)
        self.assertIn(": availableReaction.activate_animation;", block)
        self.assertIn("popWaysGramStaticReaction(document);", block)

    def test_catalog_uses_telegram_documents_when_they_are_bundled(self):
        table = self.archive.read(
            BASE + "java/org/telegram/tgnet/WaysGramReactionAssets.java").decode("utf-8")
        self.assertIn("final class WaysGramReactionAssets {", table)
        self.assertIn("static synchronized TLRPC.Document document(int reaction, String slot)", table)
        self.assertIn("static String assetName(long id)", table)
        self.assertIn("static TLObject getFile(TLRPC.TL_upload_getFile request)", table)
        self.assertIn("WaysGramReactionAssets.count() > 0", self.reactions)
        self.assertIn("telegramCatalog()", self.reactions)
        self.assertIn('WaysGramReactionAssets.document(index, "static_icon")', self.reactions)
        self.assertIn('WaysGramReactionAssets.document(index, "appear_animation")', self.reactions)
        self.assertIn('reaction.static_icon = icon;', self.reactions)
        self.assertIn('reaction.appear_animation = appear;', self.reactions)
        for slot in ("select_animation", "activate_animation", "effect_animation",
                     "around_animation", "center_icon"):
            self.assertIn(f'WaysGramReactionAssets.document(index, "{slot}")', self.reactions,
                          f"слот {slot} должен браться из выгрузки Telegram")
        transport = self.archive.read(
            BASE + "java/org/telegram/tgnet/WaysGramTransport.java").decode("utf-8")
        self.assertIn("WaysGramReactionAssets.getFile((TLRPC.TL_upload_getFile) request)", transport)

    def test_bundled_documents_match_the_java_table(self):
        import re

        table = self.archive.read(
            BASE + "java/org/telegram/tgnet/WaysGramReactionAssets.java").decode("utf-8")
        block = table.split("long[] DOC_ID = {", 1)[1].split("};", 1)[0]
        ids = [int(value) for value in re.findall(r"(\d+)L", block)]
        files = re.findall(r'"(tg/[0-9]+\.(?:tgs|webp))"', table)
        self.assertGreaterEqual(len(ids), 500)
        self.assertEqual(len(ids), len(files))
        self.assertEqual(len(set(ids)), len(ids))
        assets = {n.rsplit("/", 1)[-1] for n in self.archive.namelist() if "/waysgram_emoji/tg/" in n}
        self.assertEqual(len(assets), len(ids))
        for document_id, name in zip(ids, files):
            self.assertEqual(name, f"tg/{document_id}.{name.rsplit('.', 1)[-1]}")
            self.assertIn(f"{document_id}.{name.rsplit('.', 1)[-1]}", assets)

    def test_bundled_documents_are_official_telegram_animations(self):
        names = [n for n in self.archive.namelist()
                 if "/waysgram_emoji/tg/" in n and n.endswith(".tgs")]
        self.assertGreaterEqual(len(names), 400)
        for name in names[:40]:
            document = json.loads(gzip.decompress(self.archive.read(name)))
            self.assertEqual(document.get("tgs"), 1, name)
            self.assertEqual((document.get("w"), document.get("h")), (512, 512), name)
            self.assertEqual(document.get("fr"), 60, name)
            self.assertLessEqual(self.archive.getinfo(name).file_size, 64 * 1024, name)
        icons = [n for n in self.archive.namelist() if "/waysgram_emoji/tg/" in n and n.endswith(".webp")]
        self.assertGreaterEqual(len(icons), 70)
        self.assertEqual({webp_size(self.archive.read(n)) for n in icons[:10]}, {(72, 72)})

    def test_catalog_uses_telegram_artwork_and_telegram_effects(self):
        self.assertIn("TLRPC.Document art = WaysGramReactionTextures.get(entry[0]);", self.reactions)
        self.assertIn("reaction.static_icon = art;", self.reactions)
        self.assertIn("reaction.center_icon = art;", self.reactions)
        self.assertIn("reaction.appear_animation = art;", self.reactions)
        self.assertIn("reaction.activate_animation = art;", self.reactions)
        # Эффекты — официальные Lottie-файлы, рисунок эмодзи — официальная текстура.
        self.assertIn("TLRPC.Document effect = WaysGramAnimatedEmoji.reactionEffect(emoji);", self.reactions)
        self.assertIn("reaction.effect_animation = motion;", self.reactions)
        self.assertIn("reaction.around_animation = motion;", self.reactions)

    def test_catalog_hash_follows_the_documents(self):
        # Клиент кэширует каталог реакций в базе вместе с хешем и при совпадении
        # просит notModified. Пока хеш был константой, после подмены самодельного
        # арта на документы Telegram установленное приложение продолжало рисовать
        # старый каталог: реакции оставались «старыми» и после обновления.
        self.assertNotIn("REACTIONS_HASH = 0x5757_0001;", self.reactions)
        self.assertIn("private static final int REACTIONS_HASH = catalogHash(0x5757_0000);", self.reactions)
        body = self.reactions[self.reactions.index("private static int catalogHash("):]
        body = body[:body.index("\n    }")]
        self.assertIn("WaysGramReactionAssets.rawId(i, slot)", body)
        table = self.archive.read(
            BASE + "java/org/telegram/tgnet/WaysGramReactionAssets.java").decode("utf-8")
        self.assertIn("static long rawId(int reaction, String slot)", table)
        self.assertIn("return index < 0 ? -1 : DOC_ID[index];", table)

    def test_catalog_hash_differs_from_the_legacy_one(self):
        # Хеш считается по документам; проверяем тем же алгоритмом, что и в Java:
        # установленное приложение прислало 0x57570001, получило notModified и
        # осталось со старым каталогом. Новый хеш обязан отличаться.
        table = self.archive.read(
            BASE + "java/org/telegram/tgnet/WaysGramReactionAssets.java").decode("utf-8")

        def table_block(name):
            start = table.index(name)
            return table[start:table.index("};", start)]

        doc_ids = [int(value.rstrip("L"))
                   for value in re.findall(r"-?\d+L", table_block("private static final long[] DOC_ID"))]
        slot_doc = [[int(value) for value in row.split(",") if value.strip()]
                    for row in re.findall(r"\{([-\d,\s]+)\}", table_block("private static final int[][] SLOT_DOC"))]
        self.assertEqual(len(doc_ids), 516)
        self.assertEqual(len(slot_doc), 74)

        def to_int32(value):
            value &= 0xFFFFFFFF
            return value - 2 ** 32 if value >= 2 ** 31 else value

        hash_value = to_int32(0x57570000)
        for row in slot_doc:
            for index in row:
                document = -1 if index < 0 else doc_ids[index]
                document &= 0xFFFFFFFFFFFFFFFF
                hash_value = to_int32(hash_value * 31 + to_int32(to_int32(document) ^ to_int32(document >> 32)))
        self.assertNotEqual(hash_value & 0xFFFFFFFF, 0x57570001)

    def test_emoji_messages_use_telegram_artwork(self):
        # В сообщениях эмодзи рисовались самодельным Twemoji-артом — «старые» эмодзи
        # вместо телеграмных. Теперь берём официальный анимированный эмодзи из того же
        # набора, что и реакции, а для остальных оставляем обычный статичный вид.
        emoji = self.archive.read(
            BASE + "java/org/telegram/tgnet/WaysGramAnimatedEmoji.java").decode("utf-8")
        find = emoji[emoji.index("public static synchronized TLRPC.Document find(String emoji) {"):]
        find = find[:find.index("\n    }")]
        self.assertIn("return telegramEmoji(emoji);", find)
        self.assertNotIn("EMOJI_ID", find)
        telegram = emoji[emoji.index("public static synchronized TLRPC.Document telegramEmoji(String emoji) {"):]
        telegram = telegram[:telegram.index("\n    }")]
        self.assertIn('WaysGramReactionAssets.document(index, "appear_animation")', telegram)
        self.assertIn('"application/x-tgsticker".equals(base.mime_type)', telegram)
        # Клиент считает документ анимированным эмодзи только по этому атрибуту.
        self.assertIn("TLRPC.TL_documentAttributeCustomEmoji attribute", telegram)
        self.assertIn("attribute.alt = WaysGramReactionAssets.EMOJI[index];", telegram)

    def test_emoji_effects_come_from_telegram_not_hand_made(self):
        emoji = self.archive.read(
            BASE + "java/org/telegram/tgnet/WaysGramAnimatedEmoji.java").decode("utf-8")
        fill = emoji[emoji.index("public static void fillInteractions("):]
        fill = fill[:fill.index("\n    }")]
        self.assertIn('WaysGramReactionAssets.document(i, "effect_animation")', fill)
        self.assertIn('WaysGramReactionAssets.document(i, "around_animation")', fill)
        # Самодельные эффекты остаются только там, где Telegram ничего не отдал.
        self.assertLess(fill.index('"effect_animation"'), fill.index("WaysGramEmojiTable.EFFECT[i]"))

    def test_reaction_textures_are_the_telegram_emoji_pack(self):
        names = [n for n in self.archive.namelist() if n.startswith(TEXTURES) and n.endswith(".webp")]
        self.assertEqual(len(names), 51)
        sizes = {webp_size(self.archive.read(n)) for n in names}
        self.assertEqual(sizes, {(64, 64)})
        thumb = self.archive.read(TEXTURES + "1f44d.webp")
        self.assertTrue(thumb.startswith(b"RIFF"))

    def test_effect_animations_are_telegram_lottie(self):
        names = [n for n in self.archive.namelist()
                 if n.startswith(EFFECTS) and n.endswith(".tgs") and "/" not in n[len(EFFECTS):]]
        self.assertGreaterEqual(len(names), 120)
        for name in names[:12]:
            document = json.loads(gzip.decompress(self.archive.read(name)))
            self.assertEqual(document.get("tgs"), 1, name)
            self.assertEqual((document.get("w"), document.get("h")), (512, 512), name)
            self.assertEqual(document.get("fr"), 60, name)
            self.assertFalse([a for a in document.get("assets", []) if isinstance(a, dict) and a.get("p")],
                             f"растровые слои в TGS недопустимы: {name}")


if __name__ == "__main__":
    unittest.main()
