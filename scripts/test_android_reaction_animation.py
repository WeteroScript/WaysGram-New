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
