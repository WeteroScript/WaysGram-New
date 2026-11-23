#!/usr/bin/env python3
"""Статические проверки анимированных эмодзи и каталога реакций в архиве.

Проверяем то, что реально ломалось на устройстве:
  * в APK лежат TGS-ассеты, которые клиент может проиграть (Lottie без растровых слоёв);
  * рисунок эмодзи есть у каждого эмодзи таблицы, и его последний кадр спокойный —
    именно его клиент рисует в чипе реакции (`40_40_lastreactframe`);
  * таблица «эмодзи -> файлы» совпадает с содержимым assets (нет битых ссылок);
  * каталог реакций покрыт ассетами, иначе у реакции снова не будет текстуры;
  * транспорт отвечает на messages.getAvailableReactions и отдаёт файлы по upload.getFile.

Это не Android-сборка и не UI-тест — только регрессия исходников из частей архива.
"""
import gzip
import io
import json
import re
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/"
JAVA = BASE + "java/org/telegram/tgnet/"
ASSETS = BASE + "assets/waysgram_emoji/"

# Кадры «рисунка»: 60 fps, 42-й кадр — спокойный эмодзи.
ART_LAST_FRAME = 42


def archive_bytes() -> bytes:
    parts = sorted(ROOT.glob("WaysGram-Android.zip.part*"), key=lambda p: p.name)
    return b"".join(part.read_bytes() for part in parts)


class AnimatedEmojiChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = zipfile.ZipFile(io.BytesIO(archive_bytes()))
        cls.names = set(cls.archive.namelist())
        cls.table = cls.archive.read(JAVA + "WaysGramEmojiTable.java").decode("utf-8")
        cls.emoji, cls.art, cls.react, cls.effect = cls._table_arrays(cls.table)

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    @staticmethod
    def _table_arrays(source: str):
        def block(name):
            start = source.index("String[] %s = {" % name)
            body = source[source.index("{", start) + 1:source.index("};", start)]
            values = []
            for line in body.splitlines():
                line = line.strip().rstrip(",")
                if line == "null":
                    values.append(None)
                elif line.startswith('"'):
                    values.append(line[1:-1])
            return values

        return block("EMOJI"), block("ART"), block("REACT"), block("EFFECT")

    def source(self, name):
        return self.archive.read(JAVA + name).decode("utf-8")

    def test_every_emoji_has_a_bundled_drawing(self):
        self.assertGreaterEqual(len(self.emoji), 100, "набор рисунков должен покрывать популярные эмодзи")
        self.assertEqual(len(self.emoji), len(self.art))
        self.assertEqual(len(self.emoji), len(self.react))
        self.assertEqual(len(self.emoji), len(self.effect))
        self.assertEqual(len(set(self.emoji)), len(self.emoji), "эмодзи в таблице не должны повторяться")
        for emoji, asset in zip(self.emoji, self.art):
            self.assertNotIn("\ufe0f", emoji, "клиент сравнивает эмодзи без U+FE0F")
            self.assertIsNotNone(asset, f"у {emoji} нет рисунка — не будет текстуры")
            self.assertTrue(asset.endswith(".tgs"), asset)
            path = ASSETS + asset
            self.assertIn(path, self.names, f"нет ассета {asset} для {emoji}")
            self.assertGreater(self.archive.getinfo(path).file_size, 200, f"подозрительно маленький {asset}")

    def test_reaction_drawings_have_padding_for_the_chip(self):
        # Клиент берёт центральные 60% кадра чипа (`lastreactframe`): в варианте
        # для реакций рисунок должен занимать меньшую часть кадра, иначе обрежется.
        sample = [(e, a) for e, a in zip(self.emoji, self.react)][::13]
        for emoji, asset in sample:
            self.assertIsNotNone(asset, emoji)
            self.assertTrue(asset.startswith("react/"), asset)
            self.assertIn(ASSETS + asset, self.names, asset)
            full = self.art[self.emoji.index(emoji)]
            data = json.loads(gzip.decompress(self.archive.read(ASSETS + asset)))
            reference = json.loads(gzip.decompress(self.archive.read(ASSETS + full)))
            scale = data["layers"][0]["ks"]["s"]["k"][-1]["s"][0]
            reference_scale = reference["layers"][0]["ks"]["s"]["k"][-1]["s"][0]
            self.assertLess(scale, reference_scale, asset)

    def test_effects_cover_the_reaction_palette(self):
        for emoji, asset in zip(self.emoji, self.effect):
            if asset is None:
                continue
            self.assertTrue(asset.endswith(".tgs"), asset)
            self.assertIn(ASSETS + asset, self.names, f"нет эффекта {asset} для {emoji}")

    def test_drawings_are_vector_lottie_with_a_calm_last_frame(self):
        broken = []
        sample = list(zip(self.emoji, self.art))[::13]
        sample += [(e, a) for e, a in zip(self.emoji, self.art) if e in ("👍", "❤", "🔥")]
        for emoji, asset in sample:
            raw = self.archive.read(ASSETS + asset)
            self.assertEqual(raw[:2], b"\x1f\x8b", f"{asset} не gzip/TGS")
            data = json.loads(gzip.decompress(raw))
            self.assertEqual((data["w"], data["h"]), (512, 512), asset)
            self.assertEqual(data["op"], ART_LAST_FRAME, asset)
            self.assertIn("layers", data, asset)
            for layer in data["layers"]:
                if layer.get("ty") == 2:
                    broken.append(f"{emoji}:{asset}")
                # Последний кадр — спокойный: масштаб держится на «опорном» кадре 26.
                scale = layer["ks"]["s"]
                if scale.get("a") == 1 and len(scale["k"]) > 1:
                    self.assertEqual(scale["k"][-1]["s"], scale["k"][-2]["s"], asset)
                    self.assertLess(scale["k"][0]["s"][0], scale["k"][-1]["s"][0], asset)
            for image in data.get("assets", []):
                if isinstance(image.get("p"), str) and image["p"]:
                    broken.append(f"{emoji}:{asset}")
        self.assertEqual(broken, [], "растровые слои не рисуются нативным плеером")

    def test_heart_fire_and_thumb_are_covered(self):
        for emoji in ("❤", "🔥", "👍", "🎉", "😂", "😢"):
            self.assertIn(emoji, self.emoji, f"{emoji} должен анимироваться")

    def test_reaction_catalog_is_covered_by_assets(self):
        reactions = self.source("WaysGramReactions.java")
        entries = re.findall(r'\{"([^"]+)",\s*"([^"]+)"\}', reactions)
        self.assertGreaterEqual(len(entries), 24, "каталог реакций не должен быть пустым, как раньше")
        for emoji, title in entries:
            self.assertTrue(title.strip(), emoji)
            normalized = emoji.replace("\ufe0f", "")
            self.assertIn(normalized, self.emoji, f"для реакции {emoji} нет рисунка — не будет текстуры")

    def test_catalog_reports_documents_for_every_slot(self):
        reactions = self.source("WaysGramReactions.java")
        # Иконки и превью — рисунок, эффекты нажатия — конфетти этого эмодзи.
        for field in ("static_icon", "appear_animation", "select_animation", "center_icon",
                      "activate_animation"):
            self.assertIn(f"reaction.{field} = art;", reactions, field)
        for field in ("effect_animation", "around_animation"):
            self.assertIn(f"reaction.{field} = motion;", reactions, field)
        self.assertIn("reaction.flags |= TLObject.FLAG_1;", reactions,
                      "без FLAG_1 клиент не прочитает center_icon — чип реакции останется пустым")

    def test_transport_serves_catalog_and_files(self):
        transport = self.source("WaysGramTransport.java")
        self.assertIn("WaysGramReactions.handles(request)", transport)
        self.assertIn("WaysGramAnimatedEmoji.getFile((TLRPC.TL_upload_getFile) request)", transport)
        emoji = self.source("WaysGramAnimatedEmoji.java")
        for namespace in ("EMOJI_ID", "REACTION_ID", "EFFECT_ID"):
            self.assertIn(namespace + " + COUNT", emoji)
        self.assertIn("waysgram_emoji_v3", emoji, "кэш ассетов должен быть версионирован")


if __name__ == "__main__":
    unittest.main(verbosity=2)
