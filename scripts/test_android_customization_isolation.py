#!/usr/bin/env python3
"""Статические проверки изолированной кастомизации (этап 4).

Решение пользователя: кастомизация не должна спорить с основным интерфейсом.
Кастомным остаётся только фон (окна, карточки, таб-бар — сквозь них он виден),
а кнопки, текст, суммы звёзд и граммов, индикатор вкладки и плашки — штатные.
Второго меню и «чужой» плитки поверх нативных элементов быть не должно.

Тест читает `WaysGram-Android.zip.part1…5` — источник сборки APK.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class CustomizationIsolationChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        assert len(cls.archive.namelist()) == len(set(cls.archive.namelist()))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    # ------------------------------------------------------------- фон

    def test_only_backdrop_is_customized(self):
        activity = self.source("ui/WaysGramCustomizationActivity.java")
        self.assertIn("private static final int LAYERS = 1;", activity)
        self.assertNotIn("image1", activity)
        self.assertNotIn("image2", activity)
        self.assertNotIn("image3", activity)
        # Фон живёт под интерфейсом, обои чата берут ту же картинку.
        self.assertIn("public static class BackdropView extends View", activity)
        self.assertIn("public static android.graphics.drawable.Drawable chatWallpaper(int account)", activity)
        self.assertIn("new org.telegram.ui.WaysGramCustomizationActivity.BackdropView(", self.source("ui/LaunchActivity.java"))

    def test_window_color_touches_only_surfaces(self):
        activity = self.source("ui/WaysGramCustomizationActivity.java")
        body = activity[activity.index("public static int windowColor("):]
        body = body[: body.index("\n    }")]
        self.assertIn("Theme.key_windowBackgroundWhite", body)
        self.assertIn("Theme.key_windowBackgroundGray", body)
        for banned in (
            "key_windowBackgroundWhiteBlueButton",
            "key_windowBackgroundWhiteBlueIcon",
            "key_windowBackgroundWhiteBlueText",
            "key_windowBackgroundWhiteBlueText2",
            "key_chat_messagePanelSend",
        ):
            self.assertNotIn(banned, body, banned)

    def test_accent_extraction_is_gone(self):
        activity = self.source("ui/WaysGramCustomizationActivity.java")
        self.assertNotIn("accentColor", activity)
        self.assertNotIn("layerAccent", activity)

    # ------------------------------------------------------------- без вторых слоёв

    def test_panels_are_not_replaced_anywhere(self):
        theme = self.source("ui/ActionBar/Theme.java")
        self.assertNotIn("panelShape", theme)
        self.assertNotIn("panelRowBackground", self.source("ui/Components/UniversalAdapter.java"))
        self.assertNotIn("drawPanel", self.source("ui/Components/RecyclerListView.java"))
        activity = self.source("ui/WaysGramCustomizationActivity.java")
        for removed in ("drawPanel", "panelShape", "panelBackground", "panelRowBackground", "PanelDrawable"):
            self.assertNotIn(removed, activity, removed)

    def test_tab_bar_and_indicator_stay_native(self):
        tabs = self.source("ui/MainTabsActivity.java")
        self.assertIn("tabsView.setBackground(tabsViewBackground);", tabs)
        self.assertNotIn("wrapBackground", tabs)
        layout = self.source("ui/MainTabsLayout.java")
        self.assertNotIn("WaysGramCustomizationActivity.drawIndicator", layout)
        glass = self.source("ui/Components/glass/GlassTabView.java")
        self.assertNotIn("WaysGramCustomizationActivity.drawIndicator", glass)
        for name in ("ui/Components/FilterTabsView.java", "ui/Components/PagerSlidingTabStrip.java"):
            self.assertNotIn("drawIndicatorImage", self.source(name), name)

    # ------------------------------------------------------------- экран

    def test_editor_has_one_image_and_switch(self):
        activity = self.source("ui/WaysGramCustomizationActivity.java")
        self.assertIn("private View layerRow(int index)", activity)
        self.assertIn("R.string.WgCustomBackground", activity)
        self.assertIn("R.string.WgCustomization", activity)
        # Конфиг читается по одному слоту: старые конфиги продолжают работать.
        self.assertIn('config.optString("image" + i)', activity)
        self.assertNotIn("LayersPreview", activity)

    def test_saved_configs_stay_compatible(self):
        activity = self.source("ui/WaysGramCustomizationActivity.java")
        self.assertIn("private static boolean hasAnyImage(JSONObject config)", activity)
        self.assertIn('bitmap(config.optString("image0"))', activity)
        # Импорт по ссылке t.wg/custom/<token> остаётся.
        self.assertIn("public static boolean isLink(Uri uri)", activity)
        self.assertIn("public static void open(LaunchActivity activity, int account, Uri uri)", activity)


if __name__ == "__main__":
    unittest.main()
