#!/usr/bin/env python3
"""Turns plain WebM clips on a black background into a WaysGram gift model pack.

The gift artwork arrives as VP9 clips without an alpha channel: the animation is drawn
on a pure black canvas.  Telegram gift models are transparent animations, so every clip
has to be re-encoded with an alpha channel before it can be bundled with the backend.

The black canvas is removed by a luminance key that only follows black pixels which are
connected to the frame border.  Dark clothing inside the silhouette therefore stays
opaque (a plain threshold would punch holes in a black jacket), while the surrounding
canvas becomes transparent.  One pixel is eroded from the matte to kill the black fringe
that a subsampled chroma plane leaves on the edge, and the edge is feathered slightly.

Everything is deterministic: fixed ffmpeg settings, no timestamps in the output.

Usage:
    python3 scripts/prepare_gift_model_pack.py --gift-id 7390000000000000001 \\
        --spec /tmp/gift-spec.json --out authbot/assets/gift_models/7390000000000000001
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
from collections import deque
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]

#: ``lum <= LEVEL`` is a candidate for the canvas; 3 keeps the darkest cloth peaks.
BLACK_LEVEL = 3
#: Пил отступа от края мата, чтобы убрать чёрную кайму от цветовой субдискретизации.
ERODE = 1
FEATHER = 0.9


def ffmpeg_binary(explicit: str | None = None) -> str:
    import os

    if explicit:
        return explicit
    if os.environ.get("FFMPEG"):
        return os.environ["FFMPEG"]
    try:
        import imageio_ffmpeg  # type: ignore

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:  # pragma: no cover - fallback for a system install
        pass
    found = shutil.which("ffmpeg")
    if found:
        return found
    raise SystemExit("ffmpeg is required: pass --ffmpeg, set $FFMPEG or install imageio-ffmpeg")


def extract_frames(ffmpeg: str, source: pathlib.Path, folder: pathlib.Path) -> int:
    folder.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(source),
        "-fps_mode", "passthrough", "-pix_fmt", "rgb24", str(folder / "in_%04d.png"),
    ], check=True)
    return len(list(folder.glob("in_*.png")))


def key_out_black(path: pathlib.Path, target: pathlib.Path) -> None:
    from PIL import Image, ImageFilter
    import numpy as np

    image = Image.alpha_composite(
        Image.new("RGBA", Image.open(path).size, (0, 0, 0, 255)),
        Image.open(path).convert("RGBA"),
    ).convert("RGB")
    pixels = np.asarray(image).astype("uint8")
    height, width, _ = pixels.shape
    candidate = pixels.max(axis=2) <= BLACK_LEVEL
    canvas = np.zeros((height, width), dtype=bool)
    queue: deque[tuple[int, int]] = deque()

    def seed(y: int, x: int) -> None:
        if candidate[y, x] and not canvas[y, x]:
            canvas[y, x] = True
            queue.append((y, x))

    for x in range(width):
        seed(0, x)
        seed(height - 1, x)
    for y in range(height):
        seed(y, 0)
        seed(y, width - 1)
    while queue:
        y, x = queue.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < height and 0 <= nx < width and candidate[ny, nx] and not canvas[ny, nx]:
                canvas[ny, nx] = True
                queue.append((ny, nx))

    matte = Image.fromarray(np.where(canvas, 0, 255).astype("uint8"), "L")
    if ERODE:
        matte = matte.filter(ImageFilter.MinFilter(ERODE * 2 + 1))
    if FEATHER:
        matte = matte.filter(ImageFilter.GaussianBlur(FEATHER))
    result = image.convert("RGBA")
    result.putalpha(matte)
    result.save(target, optimize=False)


def encode(ffmpeg: str, frames: pathlib.Path, target: pathlib.Path, fps: int) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-fflags", "+bitexact",
        "-framerate", str(fps), "-i", str(frames / "keyed_%04d.png"),
        "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p",
        "-b:v", "0", "-crf", "33", "-threads", "1", "-row-mt", "0",
        "-auto-alt-ref", "0", "-an", "-metadata:s:v:0", "alpha_mode=1", str(target),
    ], check=True)
    normalize_webm(target)


def normalize_webm(path: pathlib.Path) -> None:
    """Zeroes the random Matroska segment UIDs so rebuilds stay byte-identical."""
    raw = bytearray(path.read_bytes())
    for marker, size in ((b"\x73\xc5\x88", 8), (b"\x63\xc5\x88", 8),
                         (b"\x73\xa4\x88", 8), (b"\x73\xa4\x90", 16)):
        start = 0
        while True:
            at = raw.find(marker, start, 1024)
            if at < 0:
                break
            raw[at + 3:at + 3 + size] = b"\x00" * size
            start = at + 3 + size
    path.write_bytes(bytes(raw))


def poster(frame: pathlib.Path, target: pathlib.Path) -> None:
    from PIL import Image

    image = Image.open(frame).convert("RGBA")
    image.save(target, optimize=False)


def convert(ffmpeg: str, source: pathlib.Path, name: str, folder: pathlib.Path,
            target: pathlib.Path, poster_path: pathlib.Path) -> dict[str, Any]:
    work = folder / name
    work.mkdir(parents=True, exist_ok=True)
    count = extract_frames(ffmpeg, source, work)
    if not count:
        raise SystemExit(f"{source} contains no video frames")
    for index in range(1, count + 1):
        key_out_black(work / f"in_{index:04d}.png", work / f"keyed_{index:04d}.png")
    fps = 30
    encode(ffmpeg, work, target, fps)
    poster(work / "keyed_0001.png", poster_path)
    data = target.read_bytes()
    return {
        "file": target.name,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "frames": count,
        "fps": fps,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gift-id", required=True)
    parser.add_argument("--spec", required=True, type=pathlib.Path,
                        help='{"main": {"file": ..., "name": ...}, "models": [...]}')
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument("--doc-base", type=int, default=7700000000000000,
                        help="first synthetic negative document id used for the models")
    parser.add_argument("--ffmpeg")
    args = parser.parse_args()

    ffmpeg = ffmpeg_binary(args.ffmpeg)
    doc_base = args.doc_base
    spec = json.loads(args.spec.read_text(encoding="utf-8"))
    out = args.out
    out.mkdir(parents=True, exist_ok=True)

    models: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="gift-pack-") as tmp:
        folder = pathlib.Path(tmp)
        main = spec["main"]
        info = convert(ffmpeg, pathlib.Path(main["file"]), "main", folder,
                       out / "main.webm", out / "main.png")
        models.append({"name": main["name"], "file": "main.webm", "kind": "main", **info})
        for index, entry in enumerate(spec.get("models", [])):
            slug = pathlib.Path(entry["file"]).name.split(".webm")[0].lower()
            info = convert(ffmpeg, pathlib.Path(entry["file"]), slug, folder,
                           out / f"{slug}.webm", out / f"{slug}.png")
            models.append({"name": entry["name"], "file": f"{slug}.webm", "kind": "model",
                           "document_id": -(doc_base + index), **info})

    manifest = {
        "gift_id": args.gift_id,
        "title": spec.get("title", ""),
        "source": "WaysGram model pack: original clips re-encoded with an alpha channel "
                  "(black canvas keyed out, dark clothing kept opaque)",
        "models": models,
    }
    (out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    total = sum(int(row["bytes"]) for row in models)
    print(f"{len(models)} files, {total} bytes -> {out}")


if __name__ == "__main__":
    main()
