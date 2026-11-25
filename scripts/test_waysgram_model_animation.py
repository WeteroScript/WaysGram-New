#!/usr/bin/env python3
"""Regression checks for the WaysGram Plush Pepe model animation."""
from __future__ import annotations

import hashlib
import io
import json
import pathlib
import struct
import unittest
import zipfile
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / "authbot" / "assets" / "gift_models" / "5936013938331222567"
WEBM = ASSETS / "waysgram.webm"
MANIFEST = ASSETS / "manifest.json"
PREVIEW = ASSETS / "preview.png"
ARCHIVE = ROOT / "WaysGram-Android.zip.part1"
RAW_NAME = "WaysGram-Android/TMessagesProj/src/main/res/raw/waysgram_model.webm"


def archive_bytes() -> bytes:
    return b"".join(
        (ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6)
    )


def read_rgba_png(path: pathlib.Path) -> tuple[int, int, list[tuple[int, int, int, int]]]:
    """Minimal decoder for the 8-bit RGBA PNG poster (no Pillow needed)."""
    raw = path.read_bytes()
    assert raw[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, width, height = 8, bytearray(), 0, 0
    while pos < len(raw):
        length = struct.unpack(">I", raw[pos:pos + 4])[0]
        kind = raw[pos + 4:pos + 8]
        payload = raw[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", payload)
            assert (depth, color, interlace) == (8, 6, 0)
        elif kind == b"IDAT":
            idat += payload
        elif kind == b"IEND":
            break
    data = zlib.decompress(bytes(idat))
    stride, previous, rows = width * 4, bytearray(width * 4), []
    at = 0
    for _ in range(height):
        filter_type = data[at]
        at += 1
        line = bytearray(data[at:at + stride])
        at += stride
        for i in range(stride):
            left = line[i - 4] if i >= 4 else 0
            up = previous[i]
            upper_left = previous[i - 4] if i >= 4 else 0
            if filter_type == 1:
                line[i] = (line[i] + left) & 0xFF
            elif filter_type == 2:
                line[i] = (line[i] + up) & 0xFF
            elif filter_type == 3:
                line[i] = (line[i] + ((left + up) // 2)) & 0xFF
            elif filter_type == 4:
                p = left + up - upper_left
                a, b, c = abs(p - left), abs(p - up), abs(p - upper_left)
                predictor = left if a <= b and a <= c else up if b <= c else upper_left
                line[i] = (line[i] + predictor) & 0xFF
        rows.append(line)
        previous = line
    pixels = [tuple(rows[y][x:x + 4]) for y in range(height) for x in range(0, stride, 4)]
    return width, height, pixels


class WaysGramModelAnimationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.data = WEBM.read_bytes()
        cls.manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    def test_manifest_matches_the_webm(self) -> None:
        model = next(row for row in self.manifest["models"] if row["file"] == WEBM.name)
        self.assertEqual(model["sha256"], hashlib.sha256(self.data).hexdigest())
        self.assertEqual(model["bytes"], len(self.data))
        self.assertEqual(model["document_id"], -5936013938331222567)

    def test_bundled_raw_asset_matches_the_server_asset(self) -> None:
        with zipfile.ZipFile(io.BytesIO(archive_bytes())) as archive:
            raw = archive.read(RAW_NAME)
        self.assertEqual(raw, self.data, "серверная и APK-копии модели должны быть одинаковыми")
        self.assertEqual(raw[:4], b"\x1aE\xdf\xa3")
        self.assertIn(b"ALPHA_MODE", raw[:4096])

    def test_source_is_the_original_plush_pepe_motion(self) -> None:
        source = self.manifest["source"].lower()
        self.assertIn("original plush pepe tgs motion", source)
        self.assertIn("60 fps", source)
        self.assertIn("black contour", source)
        for invented in ("glow", "sparkle", "drift"):
            self.assertNotIn(invented, source)

    def test_hands_keep_the_sticker_stacking_and_the_wordmark_is_centred(self) -> None:
        """С устройства: кусок правой кисти висел на сердце, надпись была сбоку."""
        renderer = (ROOT / "scripts" / "build_waysgram_gift_model_webm.py").read_text(encoding="utf-8")
        self.assertIn("HAND_INDICES = {1, 2, 15}", renderer)
        self.assertIn("FRONT_HAND_INDICES = {1, 2}", renderer)
        self.assertIn("BEHIND_HAND_INDICES = {15}", renderer)
        # Как в исходном TGS: кисть 15 под сердцем, кисти 1 и 2 — над ним.
        self.assertLess(renderer.index("frame = Image.alpha_composite(frame, behind)"),
                        renderer.index("frame = Image.alpha_composite(frame, heart)"),
                        "правая кисть должна оставаться за сердцем")
        self.assertLess(renderer.index("frame = Image.alpha_composite(frame, heart)"),
                        renderer.index("frame = Image.alpha_composite(frame, hands)"),
                        "левые кисти должны лежать на сердце")
        # Надпись ровно по центру сердца и последним слоем, чтобы её не закрывала рука.
        self.assertIn("local_center_y = heart_anchor[1] + (first_box[1] + first_h * 0.50", renderer)
        self.assertNotIn("for shift in", renderer)
        self.assertLess(renderer.index("frame = Image.alpha_composite(frame, hands)"),
                        renderer.rindex("frame = Image.alpha_composite(frame, layer)"),
                        "надпись идёт последней")
        # Крепление: размер один раз, положение из трансформа сердечного слоя.
        self.assertNotIn("box = heart_alpha.getbbox()", renderer)
        self.assertIn("logo_scaled = logo.resize(", renderer)
        self.assertIn("heart_center_x, heart_center_y, rotation, scale = heart_transform(data, index, offset)", renderer)
        # Timer is inherited from the original TGS: no invented speed-up or drift.
        self.assertIn("fps = int(round(animation.lottie_animation_get_framerate()))", renderer)
        self.assertIn("count = int(animation.lottie_animation_get_totalframe())", renderer)

    def test_preview_has_a_black_contour(self) -> None:
        width, height, pixels = read_rgba_png(PREVIEW)
        self.assertEqual((width, height), (512, 512))
        dark = sum(
            1 for r, g, b, a in pixels
            if a > 180 and r < 40 and g < 45 and b < 40
        )
        self.assertGreater(dark, 2000, "у модели должен быть чёрный наружный контур")


if __name__ == "__main__":
    unittest.main()
