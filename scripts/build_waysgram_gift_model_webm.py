#!/usr/bin/env python3
"""Builds the WaysGram model animation (WebM with alpha) for the Plush Pepe gift.

The artwork is ours (``preview.png`` of the gift), the motion is authored here: the
pepe breathes and sways, a soft mint glow pulses behind it, small hearts drift upwards
and four-point sparkles twinkle around it. Every curve completes a whole number of
periods per loop, so the last frame joins the first one without a seam.

Why WebM and not TGS: the animation is one raster layer, and the app's Lottie renderer
(``tlottie``) draws vector frames only — a Lottie file with an image layer stays empty
on the card. A WebM with an alpha channel is played by the regular video stack.

Usage:
    python3 scripts/build_waysgram_gift_model_webm.py \\
        --art authbot/assets/gift_models/5936013938331222567/preview.png \\
        --out authbot/assets/gift_models/5936013938331222567/waysgram.webm \\
        --raw apk-source/TMessagesProj/src/main/res/raw/waysgram_model.webm

FFmpeg is taken from ``--ffmpeg``, ``$FFMPEG``, ``imageio_ffmpeg`` or ``PATH``.
Requires Pillow.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

SIZE = 512
SUPERSAMPLE = 2
FPS = 30
SECONDS = 3.0
FRAMES = int(round(FPS * SECONDS))

#: Art is scaled so its content fills almost the whole frame: the model used to look small
#: because the drawing occupied barely half of the canvas.
ART_FILL = 0.93

MINT = (118, 226, 158)
HEART = (226, 59, 78)
HEART_LIGHT = (255, 128, 146)
SPARKLE = (214, 255, 226)


def _ffmpeg(explicit: str | None) -> str:
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


def load_art(path: pathlib.Path):
    from PIL import Image

    art = Image.open(path).convert("RGBA")
    bbox = art.getchannel("A").point(lambda v: 255 if v > 16 else 0).getbbox()
    if bbox is None:
        raise SystemExit(f"{path} is fully transparent")
    art = art.crop(bbox)
    canvas = SIZE * SUPERSAMPLE
    # Content should cover ART_FILL of the frame; keep the artwork's own aspect ratio.
    scale = canvas * ART_FILL / max(art.width, art.height)
    resized = art.resize(
        (max(1, round(art.width * scale)), max(1, round(art.height * scale))),
        Image.LANCZOS,
    )
    base = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    base.paste(resized, ((canvas - resized.width) // 2, (canvas - resized.height) // 2), resized)
    return base


def transformed(img, sx: float, sy: float, rotation_deg: float, dx: float, dy: float):
    """Scale + rotate + translate around the centre, with a bicubic resample."""
    from PIL import Image

    w, h = img.size
    cx, cy = w / 2.0, h / 2.0
    th = math.radians(rotation_deg)
    cos_t, sin_t = math.cos(th), math.sin(th)
    a, b = cos_t * sx, -sin_t * sy
    d, e = sin_t * sx, cos_t * sy
    det = a * e - b * d
    if abs(det) < 1e-9:
        return img
    n11, n12 = e / det, -b / det
    n21, n22 = -d / det, a / det
    tx = cx - (n11 * (cx + dx) + n12 * (cy + dy))
    ty = cy - (n21 * (cx + dx) + n22 * (cy + dy))
    return img.transform(
        (w, h), Image.AFFINE, (n11, n12, tx, n21, n22, ty), resample=Image.BICUBIC
    )


#: Radial falloff mask, built once per pixel and reused for every frame.
_GLOW_RADIUS = 192
_GLOW_MASK = None


def _glow_mask():
    global _GLOW_MASK
    from PIL import Image

    if _GLOW_MASK is not None:
        return _GLOW_MASK
    mask = Image.new("L", (_GLOW_RADIUS * 2, _GLOW_RADIUS * 2), 0)
    pixels = mask.load()
    for y in range(_GLOW_RADIUS * 2):
        dy = (y - _GLOW_RADIUS) / _GLOW_RADIUS
        for x in range(_GLOW_RADIUS * 2):
            dx = (x - _GLOW_RADIUS) / _GLOW_RADIUS
            r2 = dx * dx + dy * dy
            if r2 >= 1.0:
                continue
            pixels[x, y] = int(255 * math.exp(-3.4 * r2) * (1.0 - r2))
    _GLOW_MASK = mask
    return mask


def glow_layer(canvas: int, cx: float, cy: float, radius: float, alpha: float):
    from PIL import Image

    mask = _glow_mask().point(lambda v: int(v * alpha))
    size = max(2, int(round(radius * 2)))
    mask = mask.resize((size, size), Image.BILINEAR)
    layer = Image.new("RGBA", (canvas, canvas), MINT + (0,))
    layer.paste(MINT + (255,), (int(cx - size / 2), int(cy - size / 2)), mask)
    return layer


def heart_points(cx: float, cy: float, size: float):
    points = []
    steps = 48
    for i in range(steps):
        u = 2.0 * math.pi * i / steps
        x = 16 * math.sin(u) ** 3
        y = -(13 * math.cos(u) - 5 * math.cos(2 * u) - 2 * math.cos(3 * u) - math.cos(4 * u))
        points.append((cx + x * size / 32.0, cy + y * size / 32.0))
    return points


def star_points(cx: float, cy: float, radius: float):
    points = []
    for i in range(8):
        angle = math.pi / 2.0 + i * math.pi / 4.0
        r = radius if i % 2 == 0 else radius * 0.20
        points.append((cx + math.cos(angle) * r, cy - math.sin(angle) * r))
    return points


def draw_overlay(canvas: int, t: float):
    """Hearts (behind the body) and sparkles (in front of it)."""
    from PIL import Image, ImageDraw

    behind = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    front = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    k = canvas / SIZE

    # Three hearts rise one full loop per period, so their end state is their start state.
    # They float in front of the body: a heart half-hidden behind the outline reads as a
    # smudge on the edge of the artwork.
    for index, (base_x, phase, size, color) in enumerate(
        ((0.15, 0.18, 34.0, HEART), (0.85, 0.52, 28.0, HEART_LIGHT), (0.30, 0.82, 24.0, HEART))
    ):
        u = (t + phase) % 1.0
        alpha = math.sin(math.pi * u) ** 1.3
        if alpha <= 0.02:
            continue
        y = (0.74 - 0.52 * u) * canvas
        x = (base_x + 0.028 * math.sin(2.0 * math.pi * u + index)) * canvas
        points = heart_points(x, y, size * k * (0.80 + 0.30 * alpha))
        ImageDraw.Draw(front).polygon(points, fill=color + (int(240 * alpha),))

    # Sparkles twinkle twice per period.
    for base_x, base_y, phase, radius in (
        (0.16, 0.24, 0.25, 13.0),
        (0.84, 0.30, 0.60, 11.0),
        (0.10, 0.62, 0.45, 9.0),
        (0.90, 0.66, 0.10, 10.0),
        (0.50, 0.10, 0.35, 8.0),
    ):
        u = (2.0 * t + phase) % 1.0
        alpha = math.sin(math.pi * u) ** 2
        if alpha <= 0.03:
            continue
        x, y = base_x * canvas, base_y * canvas
        draw = ImageDraw.Draw(front)
        draw.polygon(star_points(x, y, radius * k * (0.55 + 0.65 * alpha)), fill=SPARKLE + (int(230 * alpha),))
        draw.ellipse(
            (x - 2.2 * k, y - 2.2 * k, x + 2.2 * k, y + 2.2 * k),
            fill=(255, 255, 255, int(255 * alpha)),
        )
    return behind, front


def render_frames(art, folder: pathlib.Path) -> list[pathlib.Path]:
    from PIL import Image

    canvas = SIZE * SUPERSAMPLE
    paths = []
    for index in range(FRAMES):
        t = index / FRAMES
        phase = 2.0 * math.pi * t
        breathe = math.sin(phase)
        sx = 1.0 + 0.024 * breathe
        sy = 1.0 + 0.050 * breathe
        dy = -9.0 * SUPERSAMPLE * breathe
        rotation = 2.0 * math.sin(phase - 0.9)

        frame = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        glow = glow_layer(
            canvas,
            canvas / 2.0,
            canvas * 0.54,
            canvas * (0.42 + 0.025 * breathe),
            0.30 + 0.07 * breathe,
        )
        frame = Image.alpha_composite(frame, glow)
        behind, front = draw_overlay(canvas, t)
        frame = Image.alpha_composite(frame, behind)
        frame = Image.alpha_composite(frame, transformed(art, sx, sy, rotation, 0.0, dy))
        frame = Image.alpha_composite(frame, front)

        out = folder / f"frame_{index:03d}.png"
        frame.resize((SIZE, SIZE), Image.LANCZOS).save(out, optimize=False)
        paths.append(out)
    return paths


def encode(ffmpeg: str, frames: pathlib.Path, target: pathlib.Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    command = [
        ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
        "-framerate", str(FPS), "-i", str(frames / "frame_%03d.png"),
        "-c:v", "libvpx-vp9",
        "-pix_fmt", "yuva420p",
        "-b:v", "0", "-crf", "32",
        "-row-mt", "1", "-auto-alt-ref", "1",
        "-an", "-metadata:s:v:0", "alpha_mode=1",
        str(target),
    ]
    subprocess.run(command, check=True)


def update_manifest(webm: pathlib.Path, manifest: pathlib.Path) -> dict:
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    raw = webm.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    for model in payload.get("models", []):
        if model.get("file") == webm.name:
            model["sha256"] = digest
            model["bytes"] = len(raw)
            model["mime_type"] = "video/webm"
    payload["source"] = (
        "WaysGram original artwork, authored loop (breathing, sway, glow, hearts, sparkles) "
        "encoded as WebM with alpha - the app's Lottie renderer draws vector frames only"
    )
    manifest.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"sha256": digest, "bytes": len(raw)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--art", required=True, type=pathlib.Path,
                        help="transparent PNG of the WaysGram artwork")
    parser.add_argument("--out", required=True, type=pathlib.Path,
                        help="WebM written for the server gift assets")
    parser.add_argument("--raw", type=pathlib.Path,
                        help="copy of the WebM bundled in the APK (res/raw)")
    parser.add_argument("--manifest", type=pathlib.Path,
                        help="manifest.json to refresh (defaults to the one next to --out)")
    parser.add_argument("--ffmpeg", help="ffmpeg executable")
    args = parser.parse_args()

    art = load_art(args.art)
    with tempfile.TemporaryDirectory(prefix="waysgram-model-") as tmp:
        folder = pathlib.Path(tmp)
        render_frames(art, folder)
        encode(_ffmpeg(args.ffmpeg), folder, args.out)

    if args.raw:
        args.raw.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(args.out, args.raw)

    manifest = args.manifest or args.out.parent / "manifest.json"
    info = {"bytes": args.out.stat().st_size, "sha256": ""}
    if manifest.is_file():
        info.update(update_manifest(args.out, manifest))
    print(f"{args.out}: {info['bytes']} bytes, sha256 {info['sha256']}")
    print(f"{FRAMES} frames, {FPS} fps, {SIZE}x{SIZE}, loop {SECONDS:g}s")


if __name__ == "__main__":
    main()
