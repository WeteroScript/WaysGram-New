#!/usr/bin/env python3
"""Регрессии по реакциям каналов/групп и служебным сообщениям WaysGram.

1. Платная реакция-звезда должна открывать окно Stars и по одиночному тапу.
2. Реакции WaysGram должны обновлять выбранный набор и использовать локальную emoji-анимацию;
   стоковый picker и его поведение не должны ломаться.
3. Заглушка сервера METHOD_NOT_SUPPORTED не должна показываться пользователю как
   «Это действие пока не поддерживается WaysGram» после каждого действия.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class ReactionRegressionChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_paid_star_opens_sheet_on_a_simple_tap(self):
        chat = self.source("ui/ChatActivity.java")
        start = chat.index("    public void selectReaction(")
        end = chat.index("    private void updateMessageAnimated(", start)
        block = chat[start:end]
        self.assertIn("visibleReaction.isStar", block)
        self.assertIn("StarsReactionsSheet sheet = new StarsReactionsSheet(", block)
        self.assertLess(block.index("visibleReaction != null && visibleReaction.isStar"),
                        block.index("selectWaysGramReaction("))

    def test_channels_and_groups_use_the_stock_reaction_palette(self):
        chat = self.source("ui/ChatActivity.java")
        line = [l for l in chat.splitlines() if "final boolean waysGramReactionMenu =" in l][0]
        self.assertIn("currentChat == null", line)
        self.assertNotIn("waysGramReactionMenu = true", chat)

    def test_waysgram_selection_updates_and_sends_the_full_set(self):
        chat = self.source("ui/ChatActivity.java")
        helper_start = chat.index("    private boolean selectWaysGramReaction(")
        helper_end = chat.index("    public void selectReaction(", helper_start)
        helper = chat[helper_start:helper_end]
        self.assertIn("final boolean added = message.selectReaction(visibleReaction, bigEmoji, fromDoubleTap);", helper)
        self.assertIn("if (!selected && !added)", helper)
        self.assertIn("ArrayList<ReactionsLayoutInBubble.VisibleReaction> visibleReactions = message.getChoosenReactions();", helper)
        self.assertIn("waysGramReactionRequests.add(key);", helper)
        self.assertIn("sendReaction(message, visibleReactions, added ? visibleReaction : null", helper)
        self.assertIn("if (added) {", helper)
        self.assertIn("ReactionsEffectOverlay.show(ChatActivity.this", helper)
        self.assertIn("ReactionsEffectOverlay.startAnimation();", helper)

        select = chat[helper_end:chat.index("    private void updateMessageAnimated(", helper_end)]
        waysgram = select[:select.index("        if (isInScheduleMode()")]
        self.assertIn("selectWaysGramReaction(cell, primaryMessage", waysgram)
        self.assertIn("visibleReaction != null && visibleReaction.isStar", waysgram)

    def test_regular_reaction_uses_the_local_animation_fallback(self):
        chat = self.source("ui/ChatActivity.java")
        select_start = chat.index("    public void selectReaction(")
        select_end = chat.index("    private void updateMessageAnimated(", select_start)
        select = chat[select_start:select_end]
        self.assertNotIn("return false;", select,
                         "void selectReaction must exit with return, not a boolean")
        self.assertIn("if (primaryMessage == null || getParentActivity() == null) return;", select)
        self.assertIn("selectWaysGramReaction(cell, primaryMessage", select)

        helper_start = chat.index("    private boolean selectWaysGramReaction(")
        helper_end = chat.index("    public void selectReaction(", helper_start)
        helper = chat[helper_start:helper_end]
        self.assertIn(
            "int animationType = bigEmoji\n                        ? ReactionsEffectOverlay.LONG_ANIMATION\n                        : ReactionsEffectOverlay.ONLY_MOVE_ANIMATION;",
            helper,
        )
        self.assertIn("ReactionsEffectOverlay.ONLY_MOVE_ANIMATION", helper)
        self.assertNotIn("ReactionsEffectOverlay.SHORT_ANIMATION", helper)
        self.assertIn("ReactionsEffectOverlay.startAnimation();", helper)

        overlay = self.source("ui/Components/Reactions/ReactionsEffectOverlay.java")
        self.assertIn("animationType != SHORT_ANIMATION", overlay)
        self.assertIn("if (localEmojiFallback) {", overlay)
        self.assertIn("Emoji.getEmojiDrawable(reaction.emojicon)", overlay)
        self.assertIn("if (missingNativeReaction && animationType == SHORT_ANIMATION) return;", overlay)

    def test_unsupported_operation_does_not_show_a_bulletin(self):
        bulletin = self.source("ui/Components/BulletinFactory.java")
        self.assertIn("private static boolean unsupportedWaysGramOperation(String code)", bulletin)
        self.assertIn(
            "if (!LaunchActivity.isActive || error != null && unsupportedWaysGramOperation(error.text)) {\n"
            "            return new Bulletin.EmptyBulletin();",
            bulletin,
        )
        self.assertIn(
            "if (!LaunchActivity.isActive || error != null && unsupportedWaysGramOperation(error.text)) return;",
            bulletin,
        )
        self.assertIn(
            "if (!LaunchActivity.isActive || unsupportedWaysGramOperation(errorCode)) return;",
            bulletin,
        )
        self.assertIn(
            "if (error != null && (error.code == 406 || unsupportedWaysGramOperation(error.text))) return;",
            bulletin,
        )

    def test_reaction_catalog_is_published_to_the_client(self):
        reactions = self.source("tgnet/WaysGramReactions.java")
        self.assertIn("TLRPC.TL_messages_getAvailableReactions", reactions)
        self.assertIn("public static synchronized ArrayList<TLRPC.TL_availableReaction> catalog()", reactions)
        self.assertIn("WaysGramReactionTextures.get(entry[0])", reactions)
        self.assertIn("reaction.center_icon = art;", reactions)
        transport = self.source("tgnet/WaysGramTransport.java")
        self.assertIn("WaysGramReactions.handles(request)", transport)
        self.assertIn("WaysGramReactionTextures.getFile((TLRPC.TL_upload_getFile) request)", transport)

    def test_reaction_textures_are_original_telegram_artwork(self):
        textures = self.source("tgnet/WaysGramReactionTextures.java")
        self.assertIn("Original Telegram emoji artwork", textures)
        self.assertIn('doc.mime_type = "image/webp";', textures)
        self.assertIn('private static final String ASSET_DIR = "telegram_reactions/";', textures)
        self.assertNotIn("waysgram_emoji/react", textures)
        prefix = "WaysGram-Android/TMessagesProj/src/main/assets/telegram_reactions/"
        assets = [n for n in self.archive.namelist() if n.startswith(prefix) and n.endswith(".webp")]
        self.assertEqual(len(assets), 51)
        # The thumbs-up is byte-for-byte the texture decoded from Telegram's EPK3 pack.
        thumb = self.archive.read(prefix + "1f44d.webp")
        self.assertEqual(
            __import__("hashlib").sha256(thumb).hexdigest(),
            "e191ff400c87794d7ea5d721f0eab51b6d4c3189f26baba7488c4ef0de7285dd",
        )
        self.assertTrue(thumb.startswith(b"RIFF") and b"WEBP" in thumb)


if __name__ == "__main__":
    unittest.main()
