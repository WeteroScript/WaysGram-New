#!/usr/bin/env python3
"""Статические проверки поведения реакций и анимированных эмодзи (этап 3).

Регрессия на то, что ломалось на устройстве: по нажатию не проигрывались
эффекты эмодзи, а одиночный эмодзи в сообщении никак не анимировался, потому
что пак интеракций `emojicom` у WaysGram-сервера не загружается. Тест читает
`WaysGram-Android.zip.part1…5` — источник сборки APK. Ассеты и таблица
проверяются в `test_android_reactions.py`.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class AnimatedEmojiBehaviourChecks(unittest.TestCase):
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

    # ------------------------------------------------------------- документы

    def test_document_id_namespaces_are_separate(self):
        java = self.source("tgnet/WaysGramAnimatedEmoji.java")
        self.assertIn("private static final long EMOJI_ID = 0x6A52000000000000L;", java)
        self.assertIn("private static final long REACTION_ID = 0x6A53000000000000L;", java)
        self.assertIn("private static final long EFFECT_ID = 0x6A54000000000000L;", java)
        self.assertIn("WaysGramEmojiTable.ART[", java)
        self.assertIn("WaysGramEmojiTable.EFFECT[", java)
        # upload.getFile обслуживает все три набора, иначе файл не скачается.
        self.assertIn("private static String assetName(long id)", java)
        for namespace in ("EMOJI_ID", "REACTION_ID", "EFFECT_ID"):
            self.assertIn("id >= %s" % namespace, java)

    def test_reaction_slots_use_art_for_icons_and_effect_for_taps(self):
        java = self.source("tgnet/WaysGramReactions.java")
        body = java[java.index("private static TLRPC.TL_availableReaction available("):]
        body = body[: body.index("\n    }")]
        for slot in ("static_icon", "center_icon", "appear_animation", "select_animation",
                     "activate_animation"):
            self.assertIn("reaction.%s = art;" % slot, body)
        # Только эффекты нажатия берут набор конфетти.
        for slot in ("effect_animation", "around_animation"):
            self.assertIn("reaction.%s = motion;" % slot, body)
        # Если эффекта нет (🙌), нажатие проигрывает хотя бы рисунок.
        self.assertIn("TLRPC.Document motion = effect != null ? effect : art;", body)

    # ------------------------------------------------------------- поведение

    def test_single_emoji_plays_its_effect_when_it_appears(self):
        chat = self.source("ui/ChatActivity.java")
        self.assertIn("WaysGramReactions.consumeEmojiEffect(messageObject)", chat)
        self.assertIn("emojiAnimationsOverlay.onTapItem(messageCell, ChatActivity.this, false);", chat)
        # В каналах и группах currentUser == null — нажатие на эмодзи тоже должно работать.
        self.assertIn("currentUser != null || org.telegram.tgnet.WaysGramReactions.hasEmojiEffect(message)", chat)
        reactions = self.source("tgnet/WaysGramReactions.java")
        self.assertIn("public static boolean consumeEmojiEffect(MessageObject message)", reactions)
        self.assertIn("EFFECT_FRESH_SECONDS", reactions)
        self.assertIn("PLAYED_EFFECTS", reactions)

    def test_tapping_emoji_in_channel_does_not_dereference_current_user(self):
        # В канале currentUser == null, но эффект по нажатию всё равно должен играть.
        # Подсказку про интерактивный эмодзи показываем только в личном чате.
        overlay = self.source("ui/EmojiAnimationsOverlay.java")
        start = overlay.index("public boolean onTapItem(")
        end = overlay.index("\n    public void cancelHintRunnable()", start)
        body = overlay[start:end]
        guard = "if (chatActivity.currentUser == null) return show;"
        self.assertIn(guard + "\n        Integer printingType", body)
        self.assertLess(body.index("showAnimationForCell("), body.index(guard))
        hint = body.index("chatActivity.currentUser.id")
        self.assertLess(body.index(guard), hint)
        self.assertIn("chatActivity.currentUser.first_name", body[body.index(guard):])

    def test_effects_are_registered_for_taps_even_without_the_pack(self):
        overlay = self.source("ui/EmojiAnimationsOverlay.java")
        self.assertIn(
            "WaysGramAnimatedEmoji.fillInteractions(supportedEmoji, emojiInteractionsStickersMap);",
            overlay,
        )
        self.assertIn("WaysGramReactions.hasEmojiEffect(view.getMessageObject())", overlay)
        emoji = self.source("tgnet/WaysGramAnimatedEmoji.java")
        self.assertIn("public static void fillInteractions(", emoji)
        # «Короткий» вид эмодзи: клиент сравнивает реакции без U+FE0F и ZWJ-пола.
        self.assertIn("public static String unwrap(String emoji)", emoji)
        self.assertIn("ALIAS", emoji)


if __name__ == "__main__":
    unittest.main()
