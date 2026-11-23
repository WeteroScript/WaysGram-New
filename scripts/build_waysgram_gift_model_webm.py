#!/usr/bin/env python3
"""Builds the WaysGram Plush Pepe model animation: original motion, redrawn artwork.

The animation is derived from Telegram's *original* Plush Pepe sticker
(``authbot/assets/gifts/5936013938331222567.tgs``).  Every sway, breath, eye and hand
movement is rendered from that file by ``rlottie`` with the original 60 fps timing and
the original bezier easing, so the loop is "1 в 1" with the other Plush Pepe models.

Only the artwork is changed:

* the pink heart becomes the WaysGram red heart;
* the WaysGram wordmark is painted on it and follows the heart's original transform;
* a black outer contour is added around the whole silhouette (the contour that was
  lost in the earlier, hand-drawn glow/hearts animation).

The result is WebM with alpha.  The app's Lottie renderer draws vector frames only,
so a Lottie file with a raster layer stays empty on the gift card; VP9 WebM with
``ALPHA_MODE`` is played by the regular video stack.

Usage:
    python3 scripts/build_waysgram_gift_model_webm.py \\
        --raw /path/to/WaysGram-Android/TMessagesProj/src/main/res/raw/waysgram_model.webm

FFmpeg is taken from ``--ffmpeg``, ``$FFMPEG``, ``imageio_ffmpeg`` or ``PATH``.
Requires Pillow and ``rlottie-python``.
"""
from __future__ import annotations

import argparse
import copy
import gzip
import hashlib
import json
import math
import os
import pathlib
import shutil
import subprocess
import tempfile
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]
GIFT_ID = "5936013938331222567"
DEFAULT_TGS = ROOT / "authbot" / "assets" / "gifts" / f"{GIFT_ID}.tgs"
DEFAULT_OUT = ROOT / "authbot" / "assets" / "gift_models" / GIFT_ID / "waysgram.webm"

# Original heart is magenta; the WaysGram heart is bright red.  These are the exact
# fill/stroke colours of the bundled TGS (rounded to avoid float formatting drift).
FILL_PINK_TO_RED = {
    (0.508099, 0.0, 0.387796, 1.0): (0.62, 0.012, 0.02, 1.0),
    (0.767386, 0.0, 0.585690, 1.0): (0.98, 0.10, 0.10, 1.0),
    (0.658824, 0.0, 0.501961, 1.0): (0.84, 0.035, 0.045, 1.0),
    (0.509804, 0.0, 0.388235, 1.0): (0.62, 0.012, 0.02, 1.0),
}
# Lottie stores gradient colours as flat [offset, r, g, b, ...] arrays.
GRADIENT_PINK_TO_RED = {
    (0.838, 0.311, 0.765): (0.96, 0.12, 0.14),
    (0.919, 0.655, 0.882): (1.0, 0.48, 0.48),
}


def ffmpeg_binary(explicit: str | None) -> str:
    if explicit:
        return explicit
    if os.environ.get("FFMPEG"):
        return os.environ["FFMPEG"]
    try:
        import imageio_ffmpeg  # type: ignore

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        pass
    found = shutil.which("ffmpeg")
    if found:
        return found
    raise SystemExit("ffmpeg is required: pass --ffmpeg, set $FFMPEG or install imageio-ffmpeg")


def load_composition(path: pathlib.Path) -> dict[str, Any]:
    return json.loads(gzip.decompress(path.read_bytes()).decode("utf-8"))


def redraw_heart(data: dict[str, Any]) -> dict[str, Any]:
    """Returns a copy of the TGS with a WaysGram-red heart instead of the pink one."""
    heart = next((layer for layer in data["layers"] if layer.get("nm") == "heart"), None)
    if heart is None:
        raise SystemExit("original Plush Pepe TGS has no 'heart' layer")

    def patch(node: Any) -> None:
        if isinstance(node, dict):
            for value in node.values():
                patch(value)
        elif isinstance(node, list):
            if all(isinstance(value, (int, float)) for value in node):
                for i in range(len(node) - 3):
                    key = tuple(round(float(v), 6) for v in node[i:i + 4])
                    if key in FILL_PINK_TO_RED:
                        node[i:i + 4] = list(FILL_PINK_TO_RED[key])
                for i in range(len(node) - 2):
                    key = tuple(round(float(v), 3) for v in node[i:i + 3])
                    if key in GRADIENT_PINK_TO_RED:
                        node[i:i + 3] = list(GRADIENT_PINK_TO_RED[key])
            else:
                for value in node:
                    patch(value)

    patch(heart)
    return data


# The three hands in the original Plush Pepe TGS. They must be drawn *after* the
# WaysGram wordmark: in the original art the arms cross in front of the heart.
HAND_INDICES = {1, 2, 15}


def subset_shapes(data: dict[str, Any], *, keep_hands: bool) -> dict[str, Any]:
    """Keeps every layer transform but hides the shapes of the other layer group.

    Emptying ``shapes`` (instead of deleting layers) preserves parenting, so the
    hands stay exactly where the original animation puts them in both renders.
    """
    result = copy.deepcopy(data)
    for layer in result["layers"]:
        wanted = layer.get("ind") in HAND_INDICES
        if wanted != keep_hands:
            layer["shapes"] = []
    return result


def bezier(progress: float, x1: float, y1: float, x2: float, y2: float) -> float:
    low, high = 0.0, 1.0
    for _ in range(24):
        mid = (low + high) / 2.0
        x = 3 * (1 - mid) ** 2 * mid * x1 + 3 * (1 - mid) * mid * mid * x2 + mid ** 3
        if x < progress:
            low = mid
        else:
            high = mid
    mid = (low + high) / 2.0
    return 3 * (1 - mid) ** 2 * mid * y1 + 3 * (1 - mid) * mid * mid * y2 + mid ** 3


def value_at(prop: dict[str, Any], frame: float) -> Any:
    """Lottie keyframe value at ``frame`` (with the file's original easing)."""
    keys = prop.get("k")
    if prop.get("a") == 0 or not isinstance(keys, list) or not keys or not isinstance(keys[0], dict):
        return keys
    if "t" not in keys[0]:
        return keys
    for start, end in zip(keys, keys[1:]):
        if start["t"] <= frame <= end["t"]:
            u = (frame - start["t"]) / max(1e-9, end["t"] - start["t"])
            out, inc = start.get("o", {}), start.get("i", {})
            ox, oy = out.get("x", 0.333), out.get("y", 0.0)
            ix, iy = inc.get("x", 0.667), inc.get("y", 1.0)
            if isinstance(ox, list):
                ox = ox[0]
            if isinstance(oy, list):
                oy = oy[0]
            if isinstance(ix, list):
                ix = ix[0]
            if isinstance(iy, list):
                iy = iy[0]
            progress = bezier(u, float(ox), float(oy), float(ix), float(iy))
            a, b = start.get("s", end.get("s")), end.get("s", start.get("s"))
            if isinstance(a, list) and isinstance(b, list):
                return [a[i] + (b[i] - a[i]) * progress for i in range(min(len(a), len(b)))]
            if isinstance(a, (int, float)) and isinstance(b, (int, float)):
                return a + (b - a) * progress
            return a
    return keys[-1].get("s", keys[-1])


def heart_transform(data: dict[str, Any], frame: int, offset: int) -> tuple[float, float, float, float]:
    """Where the wordmark has to sit: heart anchor in screen space + its rotation."""
    layers = {layer["ind"]: layer for layer in data["layers"]}
    heart = next(layer for layer in data["layers"] if layer.get("nm") == "heart")
    body = layers[heart["parent"]]

    body_rotation = value_at(body["ks"]["r"], frame + offset)
    body_position = value_at(body["ks"]["p"], frame + offset)
    body_scale = value_at(body["ks"]["s"], frame + offset)
    heart_rotation = value_at(heart["ks"]["r"], frame + offset)
    heart_position = value_at(heart["ks"]["p"], frame + offset)
    body_anchor = body["ks"]["a"]["k"][:2]
    if isinstance(body_rotation, list):
        body_rotation = body_rotation[0]
    if isinstance(heart_rotation, list):
        heart_rotation = heart_rotation[0]

    sx, sy = body_scale[0] / 100.0, body_scale[1] / 100.0
    x = (heart_position[0] - body_anchor[0]) * sx
    y = (heart_position[1] - body_anchor[1]) * sy
    angle = math.radians(body_rotation)
    cx = body_position[0] + x * math.cos(angle) - y * math.sin(angle)
    cy = body_position[1] + x * math.sin(angle) + y * math.cos(angle)
    return cx, cy, body_rotation + heart_rotation, (sx + sy) / 2.0


def wordmark() -> "Any":
    from PIL import Image, ImageDraw, ImageFont

    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 46)
    except OSError:
        font = ImageFont.load_default()
    from PIL import Image as _Image

    image = _Image.new("RGBA", (520, 150), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    x, y = 10, 34
    draw.text((x + 5, y + 5), "WaysGram", font=font, fill=(38, 38, 42, 255),
              stroke_width=6, stroke_fill=(38, 38, 42, 255))
    draw.text((x, y), "WaysGram", font=font, fill=(252, 252, 252, 255),
              stroke_width=5, stroke_fill=(48, 48, 52, 255))
    bbox = image.getbbox()
    if bbox:
        image = image.crop(bbox)
    image = image.resize((round(image.width * 0.53), round(image.height * 0.53)), _Image.LANCZOS)
    shear = 0.16
    sheared = _Image.new("RGBA", (image.width + round(image.height * shear), image.height), (0, 0, 0, 0))
    sheared.paste(image, (0, 0), image)
    return sheared.transform(sheared.size, _Image.AFFINE, (1, shear, 0, 0, 1, 0),
                             resample=_Image.BICUBIC)


def render_frames(data: dict[str, Any], tgs: pathlib.Path, folder: pathlib.Path,
                  *, want_preview: pathlib.Path | None = None) -> tuple[int, int]:
    from PIL import Image, ImageFilter
    from rlottie_python import LottieAnimation

    base_tgs = folder / "plush_pepe_base.tgs"
    hands_tgs = folder / "plush_pepe_hands.tgs"
    for target, payload in (
        (base_tgs, subset_shapes(data, keep_hands=False)),
        (hands_tgs, subset_shapes(data, keep_hands=True)),
    ):
        target.write_bytes(gzip.compress(
            json.dumps(payload, separators=(",", ":")).encode("utf-8"), mtime=0))
    animation = LottieAnimation.from_tgs(str(base_tgs))
    hands_animation = LottieAnimation.from_tgs(str(hands_tgs))

    width, height = animation.lottie_animation_get_size()
    fps = int(round(animation.lottie_animation_get_framerate()))
    count = int(animation.lottie_animation_get_totalframe())
    if hands_animation.lottie_animation_get_totalframe() != count:
        raise SystemExit("hand and body animations have different lengths")
    offset = int(data.get("ip", 0))
    logo = wordmark()

    for index in range(count):
        body = animation.render_pillow_frame(index).convert("RGBA")
        hands = hands_animation.render_pillow_frame(index).convert("RGBA")
        # The contour follows the complete silhouette, including the hands.
        solid = Image.alpha_composite(body, hands).getchannel("A").point(
            lambda value: 255 if value > 18 else 0)
        contour_mask = solid.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.GaussianBlur(0.7))
        contour = Image.new("RGBA", body.size, (0, 0, 0, 0))
        contour.paste((6, 10, 5, 255), (0, 0), contour_mask)
        frame = Image.alpha_composite(contour, body)

        cx, cy, rotation, scale = heart_transform(data, index, offset)
        text = logo.rotate(rotation - 8.0, resample=Image.BICUBIC, expand=True)
        layer = Image.new("RGBA", frame.size, (0, 0, 0, 0))
        # The wordmark sits on the upper-left of the heart; the original hands
        # still cross in front of its lower edge.
        layer.paste(text, (round(cx - 30 * scale - text.width / 2),
                           round(cy - 40 * scale - text.height / 2)), text)
        frame = Image.alpha_composite(frame, layer)
        # Hands go on top of the wordmark, exactly like the original Plush Pepe.
        frame = Image.alpha_composite(frame, hands)

        frame.save(folder / f"frame_{index:04d}.png", optimize=False)
        if want_preview is not None and index == 0:
            frame.save(want_preview)

    return (width, height), fps


def normalize_webm(path: pathlib.Path) -> None:
    """Zero the random Matroska SegmentUID so rebuilds are byte-for-byte reproducible."""
    raw = bytearray(path.read_bytes())
    # EBML Info/Segment UID elements live in the header, well before frame data.
    for marker, size in ((b"\x73\xc5\x88", 8), (b"\x63\xc5\x88", 8), (b"\x73\xa4\x88", 8),
                         (b"\x73\xa4\x90", 16)):
        start = 0
        while True:
            at = raw.find(marker, start, 1024)
            if at < 0:
                break
            raw[at + 3:at + 3 + size] = b"\x00" * size
            start = at + 3 + size
    path.write_bytes(bytes(raw))


def encode(ffmpeg: str, frames: pathlib.Path, target: pathlib.Path, fps: int) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-fflags", "+bitexact",
        "-framerate", str(fps), "-i", str(frames / "frame_%04d.png"),
        "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p",
        "-b:v", "0", "-crf", "33", "-threads", "1", "-row-mt", "0",
        "-auto-alt-ref", "0",
        "-an", "-metadata:s:v:0", "alpha_mode=1", str(target),
    ], check=True)
    normalize_webm(target)


def update_manifest(webm: pathlib.Path, manifest: pathlib.Path) -> dict[str, str]:
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    data = webm.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    for model in payload.get("models", []):
        if model.get("file") == webm.name:
            model["sha256"] = digest
            model["bytes"] = len(data)
            model["mime_type"] = "video/webm"
    payload["source"] = (
        "Original Plush Pepe TGS motion (60 fps, 3 s, original bezier easing) rendered "
        "with rlottie; red WaysGram heart with its wordmark and a restored black contour; "
        "encoded as WebM with alpha"
    )
    manifest.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"sha256": digest, "bytes": str(len(data))}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tgs", type=pathlib.Path, default=DEFAULT_TGS,
                        help="original Plush Pepe gift sticker (gzipped Lottie)")
    parser.add_argument("--preview", type=pathlib.Path,
                        default=DEFAULT_OUT.parent / "preview.png")
    parser.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT)
    parser.add_argument("--raw", type=pathlib.Path,
                        help="copy of the WebM bundled in the APK (res/raw)")
    parser.add_argument("--manifest", type=pathlib.Path, default=DEFAULT_OUT.parent / "manifest.json")
    parser.add_argument("--ffmpeg", help="ffmpeg executable")
    args = parser.parse_args()

    data = redraw_heart(load_composition(args.tgs))
    with tempfile.TemporaryDirectory(prefix="waysgram-model-") as tmp:
        folder = pathlib.Path(tmp)
        size, fps = render_frames(data, args.tgs, folder, want_preview=args.preview)
        encode(ffmpeg_binary(args.ffmpeg), folder, args.out, fps)

    if args.raw:
        args.raw.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(args.out, args.raw)

    info = {"bytes": str(args.out.stat().st_size), "sha256": ""}
    if args.manifest.is_file():
        info.update(update_manifest(args.out, args.manifest))
    print(f"{args.out}: {info['bytes']} bytes, sha256 {info['sha256']}")
    print(f"{size[0]}x{size[1]}, {fps} fps, original 3 s Plush Pepe loop")


if __name__ == "__main__":
    main()
