#!/usr/bin/env python3
"""Регрессии клиента: NPE шторки подарков, маска «Разрешений», платные реакции, эффект emoji.

Скрипт читает **собранные** исходники (части `WaysGram-Android.zip.part1…part5`), а не
зеркало `android-source/`, поэтому ловит и случай, когда правку забыли перепаковать.
"""
from __future__ import annotations

import io
import re
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"
MEDIA_FLAGS = (
    "send_photos", "send_videos", "send_roundvideos",
    "send_audios", "send_voices", "send_docs",
)
LEGACY_ALL_MEDIA = 2 | 4 | 8 | 32 | 64 | 128


class WaysGramClientRegressions(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        archive_data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{index}").read_bytes()
            for index in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(archive_data))

    @classmethod
    def tearDownClass(cls) -> None:
        cls.archive.close()

    @classmethod
    def read(cls, relative_path: str) -> str:
        return cls.archive.read(BASE + relative_path).decode("utf-8")

    @staticmethod
    def method(source: str, signature: str, end_signature: str) -> str:
        start = source.index(signature)
        return source[start:source.index(end_signature, start)]

    # ---------------------------------------------------------------- 1. NPE

    def test_gift_sheet_guards_waysgram_list_before_field_init(self) -> None:
        """`fillItems` вызывается из конструктора суперкласса, поля класса ещё null."""
        source = self.read("ui/Gifts/GiftSheet.java")
        self.assertIn("waysGramMyGifts != null && !waysGramMyGifts.isEmpty()", source)
        unguarded = source.replace("waysGramMyGifts != null && !waysGramMyGifts.isEmpty()", "")
        self.assertNotIn("waysGramMyGifts.isEmpty()", unguarded)

    # ------------------------------------------------- 2. «Разрешения» (маска)

    def _mask_tables(self) -> tuple[dict[str, int], dict[int, list[str]], str, str]:
        chats = self.read("tgnet/WaysGramChats.java")
        read_body = self.method(
            chats,
            "static TLRPC.TL_chatBannedRights bannedRightsFor(int mask, boolean changeInfoRestricted) {",
            "static int sendRestrictionMask(",
        )
        write_body = self.method(
            chats,
            "static int sendRestrictionMask(TLRPC.TL_chatBannedRights rights) {",
            "public static TLObject setGroupDefaultRights(",
        )
        read_map = {
            name: int(bit)
            for name, bit in re.findall(r"rights\.(\w+) = \(mask & (\d+)\) != 0;", read_body)
        }
        write_map: dict[int, list[str]] = {}
        for names, bit in re.findall(r"if \((rights\.[\w. |]+?)\) mask \|= (\d+);", write_body):
            write_map[int(bit)] = re.findall(r"rights\.(\w+)", names)
        return read_map, write_map, read_body, write_body

    def test_backdrop_media_bit_is_not_aliased_to_the_aggregate_flag(self) -> None:
        read_map, write_map, read_body, write_body = self._mask_tables()
        self.assertEqual(read_map.get("send_photos"), 2, read_body)
        self.assertEqual(read_map.get("send_inline"), 16, read_body)
        self.assertNotIn("send_media", read_map)
        self.assertEqual(write_map.get(2), ["send_photos"], write_body)
        self.assertIn("send_inline", write_map.get(16, []), write_body)
        # Агрегат остаётся только как совместимость со старыми объектами прав.
        self.assertIn("rights.send_media &&", write_body)
        self.assertIn("rights.send_media = rights.send_photos && rights.send_videos", read_body)

    def test_granular_mask_survives_read_write_round_trip(self) -> None:
        read_map, write_map, _, _ = self._mask_tables()

        def read(mask: int) -> dict[str, bool]:
            flags = {name: bool(mask & bit) for name, bit in read_map.items()}
            flags["send_media"] = all(flags.get(flag) for flag in MEDIA_FLAGS)
            # TLRPC.clone(): send_messages выводится из деталей, WaysGramChats возвращает его.
            flags["send_messages"] = flags["send_plain"] and flags.get("send_stickers", False)
            flags["send_messages"] |= bool(mask & 1)
            flags["send_plain"] |= bool(mask & 1)
            flags["send_inline"] |= bool(mask & 16)
            flags["send_media"] |= all(flags.get(flag) for flag in MEDIA_FLAGS)
            return flags

        def write(flags: dict[str, bool]) -> int:
            mask = 0
            for bit, names in write_map.items():
                if any(flags.get(name) for name in names):
                    mask |= bit
            if flags.get("send_media") and not mask & LEGACY_ALL_MEDIA:
                mask |= LEGACY_ALL_MEDIA
            return mask

        for mask in (
            0, 1, 2, 4, 8, 16, 32, 64, 128,
            LEGACY_ALL_MEDIA, 1 | 16, 2 | 256, 512 | 1024,
            2048 | 4096 | 8192 | 16384 | 32768,
        ):
            self.assertEqual(write(read(mask)), mask, f"биты маски {mask} потерялись при кругообороте")

    def test_permissions_screen_keeps_flags_in_sync(self) -> None:
        source = self.read("ui/ChatUsersActivity.java")
        self.assertIn("defaultBannedRights.send_messages = defaultBannedRights.send_plain;", source)
        self.assertIn("private void updateSendMediaAggregate()", source)
        body = self.method(source, "private void updateSendMediaAggregate() {", "private void setSendMediaEnabled(")
        for flag in MEDIA_FLAGS:
            self.assertIn(f"defaultBannedRights.{flag}", body)
        toggles = self.method(
            source,
            "if (isExpandableSendMediaRow(position)) {",
            "} else if (position == dontRestrictBoostersRow) {",
        )
        self.assertIn("updateSendMediaAggregate();", toggles)

    # ------------------------------------------------------ 3. платные реакции

    def test_paid_reaction_does_not_accept_an_empty_page(self) -> None:
        source = self.read("tgnet/WaysGramMessages.java")
        body = self.method(source, "public static TLObject sendPaidReaction(", "public static TLObject refreshReactions(")
        self.assertIn("if (reactions == null) {", body)
        self.assertIn("\"REACTION_RESPONSE_INVALID\"", body)

    def test_paid_reaction_counts_as_the_same_chip(self) -> None:
        source = self.read("ui/Components/Reactions/ReactionsLayoutInBubble.java")
        body = self.method(source, "public static boolean equalsTLReaction(", "public void setMessage(")
        self.assertIn("TL_reactionPaid", body)

    # --------------------------------------------------- 4. эффект emoji

    def test_finished_effect_is_not_restarted_from_frame_zero(self) -> None:
        source = self.read("ui/EmojiAnimationsOverlay.java")
        body = self.method(source, "public void draw(Canvas canvas) {", "public void onAllEffectsEnd() {")
        self.assertNotIn("setCurrentFrame(0, true)", body)
        self.assertIn("drawingObject.removeProgress = 1f;", body)
        self.assertIn("drawingObject.wasPlayed", body)


if __name__ == "__main__":
    unittest.main()
