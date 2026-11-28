"""Лента улучшения подарка: у WaysGram-моделей должен быть разный артворк.

Скриншот: на странице результата прокрутка улучшения крутит одну и ту же модель,
пока не нажмёшь «Skip».

Причина — в клиенте: `Roller.Sticker.isLoaded()` считал готовой только Lottie-анимацию
(`getLottieAnimation()`), а модели WaysGram — это WebM с альфой. Поэтому ни одна модель
пула не попадала в выбор, `AttrRoller.next()` возвращал `start` (картинку самого подарка),
и лента бесконечно показывала её же; до `finish` она не доходила, пока не срабатывал
таймаут и `skip()`.

Данные сервера при этом в порядке: пул Mellstroy содержит 14 моделей с разными
синтетическими id, и каждый id отдаётся файлом. Тесты ниже держат обе стороны.
"""
from __future__ import annotations

import sys
import unittest
import zipfile
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER_DIR = ROOT / "authbot"
sys.path.insert(0, str(SERVER_DIR))

import gift_model_fallbacks  # noqa: E402
import gift_upgrades  # noqa: E402
import messenger_api  # noqa: E402
import waysgram_gifts  # noqa: E402


def archive_bytes() -> bytes:
    parts = sorted(ROOT.glob("WaysGram-Android.zip.part*"), key=lambda path: path.name)
    return b"".join(part.read_bytes() for part in parts)


def java_source(path: str) -> str:
    archive = zipfile.ZipFile(BytesIO(archive_bytes()))
    return archive.read(
        "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/" + path
    ).decode("utf-8")


class RollerLoadsVideoModels(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sheet = java_source("ui/Stars/StarGiftSheet.java")

    def sticker_body(self) -> str:
        start = self.sheet.index("        public static class Sticker extends Attr {")
        return self.sheet[start:self.sheet.index("        public static class Symbol extends Attr {", start)]

    def test_webm_model_counts_as_loaded(self):
        body = self.sticker_body()
        self.assertIn("public boolean isLoaded()", body)
        self.assertIn("imageReceiver.getLottieAnimation() != null", body)
        self.assertIn("imageReceiver.getAnimation() != null", body)
        # Только Lottie — это прежнее поведение, из-за него лента и стояла на одной модели.
        self.assertNotEqual(
            body.split("public boolean isLoaded()")[1].split("}")[0].count("getAnimation()"), 0)

    def test_pool_scan_is_not_cut_at_twelve(self):
        self.assertIn("WAYSGRAM_POOL_LIMIT = 24", self.sheet)
        self.assertNotIn("Math.min(entries.length(), 12)", self.sheet)
        self.assertEqual(self.sheet.count("Math.min(entries.length(), WAYSGRAM_POOL_LIMIT)"), 3)

    def test_reel_still_waits_for_several_animations(self):
        self.assertIn("WAYSGRAM_REEL_MODELS = 8", self.sheet)
        self.assertIn("private boolean waysGramReelReady(String giftId, JSONObject pool)", self.sheet)


class ServerPoolHasDistinctArtwork(unittest.TestCase):
    key = waysgram_gifts.GIFT_ID

    def reveal(self) -> dict:
        pool = gift_upgrades.pool_for_gift(self.key)[1]
        rows = {"model": waysgram_gifts.model_chances(self.key)}
        documents = messenger_api._reveal_pool_documents(pool, rows)
        return gift_upgrades.reveal_pool(pool, {}, documents, gift_id=self.key)

    def test_every_reel_model_carries_its_own_document(self):
        models = self.reveal()["models"]
        self.assertGreaterEqual(len(models), 12)
        ids = [int(row[3]) for row in models]
        self.assertNotIn(0, ids, "модель без документа не нарисуется")
        self.assertEqual(len(ids), len(set(ids)), "две модели делят один документ")

    def test_each_document_is_served_by_the_backend(self):
        for row in self.reveal()["models"]:
            entry = gift_model_fallbacks.model_for_document(self.key, int(row[3]), synthetic=True)
            self.assertIsNotNone(entry, f"нет файла для модели {row[0]}")
            self.assertTrue(entry["path"].is_file())
            self.assertGreater(entry["path"].stat().st_size, 1024)

    def test_special_models_are_in_the_reel(self):
        names = [str(row[0]) for row in self.reveal()["models"]]
        for special in gift_upgrades.special_models(self.key):
            self.assertIn(str(special["name"]), names)


if __name__ == "__main__":
    unittest.main()
