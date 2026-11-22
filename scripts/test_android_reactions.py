#!/usr/bin/env python3
"""Статические проверки анимированных эмодзи и каталога реакций в архиве.

Проверяем то, что реально ломалось на устройстве:
  * в APK лежат TGS-ассеты, которые клиент может проиграть (Lottie без растровых слоёв);
  * таблица «эмодзи -> файл» совпадает с содержимым assets (нет битых ссылок);
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

# В наборе telegram-emoji-effects этих анимаций нет: реакции из палитры остаются
# статичными, но кнопка выбора и отправка работают.
NO_ANIMATION = {"😮", "🙌"}


def archive_bytes() -> bytes:
    parts = sorted(ROOT.glob("WaysGram-Android.zip.part*"), key=lambda p: p.name)
    return b"".join(part.read_bytes() for part in parts)


class AnimatedEmojiChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = zipfile.ZipFile(io.BytesIO(archive_bytes()))
        cls.names = set(cls.archive.namelist())
        cls.table = cls.archive.read(JAVA + "WaysGramEmojiTable.java").decode("utf-8")
        cls.emoji, cls.assets = cls._table_arrays(cls.table)

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    @staticmethod
    def _table_arrays(source: str):
        def block(name):
            start = source.index(name)
            body = source[source.index("{", start) + 1:source.index("};", start)]
            return re.findall(r'"((?:[^"\\]|\\.)*)"', body)

        return block("EMOJI ="), block("ASSETS =")

    def source(self, name):
        return self.archive.read(JAVA + name).decode("utf-8")

    def test_every_emoji_has_a_bundled_animation(self):
        self.assertGreaterEqual(len(self.emoji), 100, "набор анимаций должен покрывать популярные эмодзи")
        self.assertEqual(len(self.emoji), len(self.assets))
        self.assertEqual(len(set(self.emoji)), len(self.emoji), "эмодзи в таблице не должны повторяться")
        for emoji, asset in zip(self.emoji, self.assets):
            self.assertNotIn("\ufe0f", emoji, "клиент сравнивает эмодзи без U+FE0F")
            self.assertTrue(asset.endswith(".tgs"), asset)
            path = ASSETS + asset
            self.assertIn(path, self.names, f"нет ассета {asset} для {emoji}")
            self.assertGreater(self.archive.getinfo(path).file_size, 200, f"подозрительно маленький {asset}")

    def test_bundled_animations_are_vector_lottie(self):
        broken = []
        sample = list(zip(self.emoji, self.assets))[::17]  # каждый 17-й, но с якорем на конкретные эмодзи
        sample += [(e, a) for e, a in zip(self.emoji, self.assets) if e in ("👍", "❤", "🔥")]
        for emoji, asset in sample:
            raw = self.archive.read(ASSETS + asset)
            self.assertEqual(raw[:2], b"\x1f\x8b", f"{asset} не gzip/TGS")
            data = json.loads(gzip.decompress(raw))
            self.assertIn("layers", data, asset)
            for layer in data["layers"]:
                if layer.get("ty") == 2:
                    broken.append(f"{emoji}:{asset}")
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
            if normalized in NO_ANIMATION:
                continue
            self.assertIn(normalized, self.emoji, f"для реакции {emoji} нет анимации — не будет текстуры")

    def test_catalog_reports_documents_for_every_slot(self):
        reactions = self.source("WaysGramReactions.java")
        for field in ("static_icon", "appear_animation", "select_animation",
                      "activate_animation", "effect_animation", "center_icon", "around_animation"):
            self.assertIn(f"reaction.{field} = animation;", reactions, field)
        self.assertIn("reaction.flags |= TLObject.FLAG_1;", reactions,
                      "без FLAG_1 клиент не прочитает center_icon — чип реакции останется пустым")

    def test_transport_serves_catalog_and_files(self):
        transport = self.source("WaysGramTransport.java")
        self.assertIn("WaysGramReactions.handles(request)", transport)
        self.assertIn("WaysGramAnimatedEmoji.getFile((TLRPC.TL_upload_getFile) request)", transport)
        emoji = self.source("WaysGramAnimatedEmoji.java")
        self.assertIn("EMOJI_ID + COUNT", emoji)
        self.assertIn("REACTION_ID + COUNT", emoji)
        self.assertIn("waysgram_emoji_v2", emoji, "кэш ассетов должен быть версионирован")


if __name__ == "__main__":
    unittest.main(verbosity=2)
