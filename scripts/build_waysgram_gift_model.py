#!/usr/bin/env python3
"""Builds the WaysGram gift-model sticker (.tgs) for Plush Pepe.

The animation is not invented: the body layer's motion of the *original* Plush Pepe gift
sticker is reused verbatim, so the WaysGram model moves exactly like the stock Pepe —
same frames, same easing, same 3-second loop. Only the artwork is ours.

Usage:
    python3 scripts/build_waysgram_gift_model.py \
        --original /path/to/plush_pepe.tgs \
        --art authbot/assets/gift_models/5936013938331222567/preview.png

`plush_pepe.tgs` is the gift's own animated sticker from Telegram's catalog
(e.g. tgs/by_name/plush_pepe.tgs in the public gift-assets dump). Nothing of its artwork is
copied: only ``ks.r`` and ``ks.s`` of the layer named ``body`` are reused.

Requires Pillow.
"""
from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import io
import json
import pathlib

#: Art is inset to 80 % of the 512 canvas: the stock sway would otherwise clip the edges.
ART_SCALE = 0.80
#: Rotation pivot sits below the frame, the way the original body layer pivots on the ground.
PIVOT = [256, 600, 0]
#: Same timing as the original sticker.
FPS, IN_POINT, OUT_POINT = 60, 60, 240


def load_original_motion(path: pathlib.Path) -> tuple[dict, dict]:
    doc = json.loads(gzip.decompress(path.read_bytes()))
    body = next((layer for layer in doc.get("layers", []) if layer.get("nm") == "body"), None)
    if body is None:
        raise SystemExit("original sticker has no 'body' layer")
    rotation = body["ks"]["r"]
    scale = body["ks"]["s"]
    if not rotation.get("k") or not scale.get("k"):
        raise SystemExit("original body layer has no animated rotation/scale")
    return rotation, scale


def build_art(path: pathlib.Path) -> bytes:
    from PIL import Image

    src = Image.open(path).convert("RGBA")
    canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    size = (max(1, round(src.width * ART_SCALE)), max(1, round(src.height * ART_SCALE)))
    art = src.resize(size, Image.LANCZOS)
    canvas.paste(art, ((512 - size[0]) // 2, (512 - size[1]) // 2 - 6), art)
    out = io.BytesIO()
    canvas.quantize(colors=256, method=Image.FASTOCTREE).save(out, format="PNG", optimize=True)
    return out.getvalue()


def build(original: pathlib.Path, art: pathlib.Path) -> bytes:
    rotation, scale = load_original_motion(original)
    encoded = base64.b64encode(build_art(art)).decode()
    layer = {
        "ddd": 0, "ind": 1, "ty": 2, "nm": "waysgram-pepe", "refId": "image_0", "sr": 1,
        "ks": {
            "o": {"a": 0, "k": 100},
            "r": rotation,
            "p": {"a": 0, "k": PIVOT},
            "a": {"a": 0, "k": PIVOT},
            "s": scale,
        },
        "ao": 0, "ip": IN_POINT, "op": OUT_POINT, "st": IN_POINT, "bm": 0,
    }
    doc = {
        "v": "5.7.4", "fr": FPS, "ip": IN_POINT, "op": OUT_POINT, "w": 512, "h": 512,
        "nm": "WaysGram", "ddd": 0,
        "assets": [{"id": "image_0", "w": 512, "h": 512, "u": "",
                    "p": "data:image/png;base64," + encoded, "e": 1}],
        "layers": [layer],
    }
    return gzip.compress(json.dumps(doc, separators=(",", ":")).encode(), 9)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original", required=True, type=pathlib.Path,
                        help="original Plush Pepe sticker (.tgs) to copy the motion from")
    parser.add_argument("--art", required=True, type=pathlib.Path,
                        help="transparent PNG of the WaysGram artwork")
    parser.add_argument("--out", type=pathlib.Path,
                        default=pathlib.Path("authbot/assets/gift_models/5936013938331222567/waysgram.tgs"))
    args = parser.parse_args()
    packed = build(args.original, args.art)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(packed)
    print(f"{args.out}: {len(packed)} bytes, sha256 {hashlib.sha256(packed).hexdigest()}")


if __name__ == "__main__":
    main()
