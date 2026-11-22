#!/usr/bin/env python3
"""Статические проверки двух правок, которые видно на устройстве.

1. Подпись «#???» модели WaysGram переливается без рывка на стыке цикла.
2. В чатах показывается имя, а не номер телефона.

Источник — части архива (как и остальные scripts/test_android_*), это не сборка.
"""
import io
import re
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


def archive_bytes() -> bytes:
    parts = sorted(ROOT.glob("WaysGram-Android.zip.part*"), key=lambda p: p.name)
    return b"".join(part.read_bytes() for part in parts)


class ShimmerLoopChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = archive_bytes()
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, path):
        return self.archive.read(BASE + path).decode("utf-8")

    def test_gradient_tiles_seamlessly(self):
        number = self.source("ui/Stars/WaysGramGiftNumber.java")
        self.assertIn("Shader.TileMode.MIRROR", number, "без зеркального тайла на стыке виден рывок")
        self.assertNotIn("Shader.TileMode.CLAMP", number, "CLAMP обрывает перелив в конце цикла")
        # Палитра симметрична, иначе зеркальный тайл даст шов по цвету.
        colors = re.search(r"SHIMMER_COLORS = \{(.*?)\};", number, re.S).group(1)
        entries = [line.strip() for line in colors.split(",") if line.strip()]
        self.assertEqual(len(entries), 5, entries)
        self.assertEqual(entries[0], entries[-1], "крайние цвета градиента должны совпадать")

    def test_cycle_covers_exactly_one_mirrored_period(self):
        number = self.source("ui/Stars/WaysGramGiftNumber.java")
        self.assertIn("phase * 2f * length", number,
                      "за цикл сдвиг должен проходить ровно период зеркального тайла")
        self.assertRegex(number, r"CYCLE_MS = \d+L")
        self.assertIn("% CYCLE_MS) / (float) CYCLE_MS", number)
        self.assertNotIn("2000L", number, "старый цикл в 2 с остался")

    def test_ribbon_passes_text_origin_to_gradient(self):
        sheet = self.source("ui/Gifts/GiftSheet.java")
        self.assertIn("WaysGramGiftNumber.applyShimmer(text.paint, cx - text.getWidth() / 2f, "
                      "text.getCurrentWidth())", sheet)
        number = self.source("ui/Stars/WaysGramGiftNumber.java")
        self.assertIn("public static void applyShimmer(@Nullable Paint paint, float x, float width)", number)


class ContactNameChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = archive_bytes()
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, path):
        return self.archive.read(BASE + path).decode("utf-8")

    def test_mapper_ignores_phone_numbers_as_names(self):
        mapper = self.source("tgnet/WaysGramMapper.java")
        self.assertIn("nameFromJson(json)", mapper)
        self.assertIn("private static String nameFromJson(JSONObject json)", mapper)
        self.assertIn("private static String cleanName(String value)", mapper)
        self.assertIn('"null".equalsIgnoreCase(trimmed)', mapper, "JSON null не должен становиться именем")
        # Телефонные значения отбрасываются, прежде чем стать именем.
        name_block = mapper[mapper.index("private static String nameFromJson"):]
        name_block = name_block[:name_block.index("private static boolean looksLikePhone")]
        self.assertIn("!looksLikePhone(candidate)", name_block)
        self.assertIn("!looksLikePhone(joined)", name_block)
        self.assertIn('display = "User " + user.id;', mapper, "последний вариант — подпись по id, а не номер")

    def test_phone_is_not_used_as_chat_title(self):
        user = self.source("messenger/UserObject.java")
        start = user.index("public static String getUserName(TLRPC.User user)")
        block = user[start:start + 900]
        self.assertIn("WaysGramTransport.ENABLED", block)
        self.assertLess(block.index("WaysGramTransport.ENABLED"), block.index("PhoneFormat"),
                        "номер телефона должен оставаться последним вариантом — и не для WaysGram")


class PlushPepeChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = zipfile.ZipFile(io.BytesIO(archive_bytes()))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def test_bundled_waysgram_model_ships_in_apk(self):
        info = self.archive.getinfo(
            "WaysGram-Android/TMessagesProj/src/main/res/raw/waysgram_model.webm")
        self.assertGreater(info.file_size, 100_000, "модель WaysGram должна ехать в APK")

    def test_number_label_is_used_for_the_model(self):
        number = self.archive.read(BASE + "ui/Stars/WaysGramGiftNumber.java").decode("utf-8")
        self.assertIn('LABEL = "#???"', number)
        self.assertIn("MODEL_NAME = \"WaysGram\"", number)
        self.assertIn("WAYSGRAM_MODEL_DOCUMENT_ID", number)


if __name__ == "__main__":
    unittest.main(verbosity=2)
