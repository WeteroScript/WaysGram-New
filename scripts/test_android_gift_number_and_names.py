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

    def test_palette_stays_readable_on_the_dark_gift_card(self):
        """«#???» рисуется на тёмной карточке (Theme.key_dialogGiftsBackground).

        Раньше градиент шёл от 0xFF05070B к 0xFF24406B: на скриншоте номер сливался
        с фоном. Проверяем, что самый тёмный цвет палитры заметно светлее карточки,
        а самая светлая точка перелива действительно светлая.
        """
        number = self.source("ui/Stars/WaysGramGiftNumber.java")
        names = re.findall(r"private static final int (COLOR_[A-Z_]+) = 0x([0-9A-Fa-f]{8});", number)
        self.assertTrue(names, "палитра перелива не найдена")
        colors = {name: int(value, 16) for name, value in names}

        def luminance(color):
            def channel(value):
                value /= 255.0
                return value / 12.92 if value <= 0.03928 else ((value + 0.055) / 1.055) ** 2.4
            return (0.2126 * channel((color >> 16) & 0xFF)
                    + 0.7152 * channel((color >> 8) & 0xFF)
                    + 0.0722 * channel(color & 0xFF))

        darkest = min(luminance(c) for c in colors.values())
        brightest = max(luminance(c) for c in colors.values())
        self.assertGreater(darkest, 0.03, f"самый тёмный цвет палитры сливается с фоном: {colors}")
        self.assertGreater(brightest, 0.45, f"перелив не даёт светлой точки: {colors}")

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

    def test_chat_header_does_not_fall_back_to_phone_for_waysgram(self):
        """Штатная ветка Telegram печатает номер, если пользователя нет в контактах."""
        chat = self.source("ui/ChatActivity.java")
        marker = "PhoneFormat.getInstance().format(\"+\" + currentUser.phone)"
        self.assertIn(marker, chat)
        condition = chat[chat.rindex("} else if (", 0, chat.index(marker)):]
        condition = condition[:condition.index("{")]
        self.assertIn("!org.telegram.tgnet.WaysGramTransport.ENABLED", condition,
                      "для WaysGram шапка чата берёт имя, а не номер телефона")


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

    def test_bundled_model_carries_alpha_and_fills_its_frame(self):
        raw = self.archive.read(
            "WaysGram-Android/TMessagesProj/src/main/res/raw/waysgram_model.webm")
        self.assertEqual(raw[:4], b"\x1aE\xdf\xa3", "модель должна быть WebM, а не Lottie")
        self.assertIn(b"ALPHA_MODE", raw[:4096],
                      "у анимации модели должен быть альфа-канал, иначе вокруг пепе чёрный квадрат")
        self.assertGreater(len(raw), 300_000, "авторская анимация не должна схлопнуться в один кадр")

    def test_gift_cell_does_not_use_the_frame_cache(self):
        sheet = self.archive.read(BASE + "ui/Gifts/GiftSheet.java").decode("utf-8")
        start = sheet.index("private void setSticker(TLRPC.Document document, Object parentObject)")
        block = sheet[start:start + 2400]
        self.assertIn('"80_80_nolimit"', block,
                      "ячейка «Мои подарки» должна рисовать кадр из файла модели")
        self.assertNotIn("80_80_nolimit_pcache", block,
                         "pcache оставлял ячейку с синтетическим артом пустой")

    def test_model_square_and_caption_offset_use_one_constant(self):
        """Подпись «Название #номер» начинается под квадратом модели.

        Раньше квадрат увеличили до 208 dp, а отступ подписи в onSwitchPage остался
        зашитым (160): подпись уезжала под модель, и «Plush Pepe» закрывали лапы.
        """
        sheet = self.archive.read(BASE + "ui/Stars/StarGiftSheet.java").decode("utf-8")
        size = int(re.search(r"MODEL_IMAGE_SIZE_DP = (\d+);", sheet).group(1))
        self.assertEqual(size, 160, "квадрат модели — штатный размер Telegram")
        self.assertIn("createFrame(MODEL_IMAGE_SIZE_DP, MODEL_IMAGE_SIZE_DP", sheet)
        self.assertNotIn("dp(24 + 160)", sheet, "отступ подписи должен считаться от константы")
        self.assertNotIn("dp(8 + 160 + 2)", sheet, "отступ подписи должен считаться от константы")
        self.assertIn("layoutLayoutParams[i].topMargin = dp(24 + MODEL_IMAGE_SIZE_DP);", sheet)
        self.assertIn("layoutLayoutParams[i].topMargin = dp(8 + MODEL_IMAGE_SIZE_DP + 2);", sheet)

    def test_inventory_always_prefers_the_bundled_model(self):
        controller = self.archive.read(
            BASE + "messenger/waysgram/WaysGramProfileGiftsController.java").decode("utf-8")
        start = controller.index("TLRPC.TL_document modelDocument = WaysGramGiftAttributes.traitDocument(")
        block = controller[start:start + 1400]
        self.assertIn("WaysGramBundledArtwork.isBundledName(model)", block)
        self.assertIn("WaysGramBundledArtwork.isBundled(modelDocument.id)", block,
                      "серверный документ модели тоже заменяется встроенной анимацией")
        self.assertIn("modelDocument = bundled;", block)


if __name__ == "__main__":
    unittest.main(verbosity=2)
