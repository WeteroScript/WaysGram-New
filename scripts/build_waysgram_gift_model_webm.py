#!/usr/bin/env python3
"""Builds the WaysGram Plush Pepe model animation: original motion, redrawn artwork.

The animation is derived from Telegram's *original* Plush Pepe sticker
(``authbot/assets/gifts/5936013938331222567.tgs``).  Every sway, breath, eye and hand
movement is rendered from that file by ``rlottie`` with the original 60 fps timing and
the original bezier easing, so the loop is "1 в 1" with the other Plush Pepe models.

Only the artwork is changed:

* the pink heart becomes the WaysGram red heart and is drawn in front of the arms,
  so the hands hold it instead of covering it;
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


# The three hands in the original Plush Pepe TGS. They are drawn after the body and
# before the heart: Pepe holds the heart, but the heart itself stays in front.
HAND_INDICES = {1, 2, 15}
#: Слой сердца: по нему считается, куда ставить надпись и какая его часть не закрыта руками.
HEART_INDEX = 3


def subset_shapes(data: dict[str, Any], *, keep: set[int] | None = None,
                  drop: set[int] | tuple[int, ...] = ()) -> dict[str, Any]:
    """Keeps every layer transform but hides the shapes of the other layer group.

    Emptying ``shapes`` (instead of deleting layers) preserves parenting, so the
    hands stay exactly where the original animation puts them in both renders.
    """
    result = copy.deepcopy(data)
    for layer in result["layers"]:
        ind = layer.get("ind")
        wanted = (keep is None or ind in keep) and ind not in set(drop)
        if not wanted:
            layer["shapes"] = []
    return result


def _no_wordmark_note() -> None:
    """Надпись «WaysGram» больше не рисуется поверх модели.

    Раньше она накладывалась в экранных координатах по пересчёту трансформов сердца:
    на устройстве буквы уезжали с сердца, не двигались вместе с ним и попадали под
    руку. Название модели и так выводится в карточке подарка («Model: WaysGram»),
    поэтому арт остаётся чистым.
    """


def render_frames(data: dict[str, Any], tgs: pathlib.Path, folder: pathlib.Path,
                  *, want_preview: pathlib.Path | None = None) -> tuple[int, int]:
    from PIL import Image, ImageFilter
    from rlottie_python import LottieAnimation

    base_tgs = folder / "plush_pepe_base.tgs"
    hands_tgs = folder / "plush_pepe_hands.tgs"
    heart_tgs = folder / "plush_pepe_heart.tgs"
    for target, payload in (
        (base_tgs, subset_shapes(data, drop=HAND_INDICES | {HEART_INDEX})),
        (hands_tgs, subset_shapes(data, keep=HAND_INDICES)),
        (heart_tgs, subset_shapes(data, keep={HEART_INDEX})),
    ):
        target.write_bytes(gzip.compress(
            json.dumps(payload, separators=(",", ":")).encode("utf-8"), mtime=0))
    animation = LottieAnimation.from_tgs(str(base_tgs))
    hands_animation = LottieAnimation.from_tgs(str(hands_tgs))
    heart_animation = LottieAnimation.from_tgs(str(heart_tgs))

    width, height = animation.lottie_animation_get_size()
    fps = int(round(animation.lottie_animation_get_framerate()))
    count = int(animation.lottie_animation_get_totalframe())
    if hands_animation.lottie_animation_get_totalframe() != count:
        raise SystemExit("hand and body animations have different lengths")
    offset = int(data.get("ip", 0))

    for index in range(count):
        body = animation.render_pillow_frame(index).convert("RGBA")
        hands = hands_animation.render_pillow_frame(index).convert("RGBA")
        heart = heart_animation.render_pillow_frame(index).convert("RGBA")
        # The contour follows the complete silhouette, including the hands.
        solid = Image.alpha_composite(Image.alpha_composite(body, hands), heart).getchannel("A").point(
            lambda value: 255 if value > 18 else 0)
        contour_mask = solid.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.GaussianBlur(0.7))
        contour = Image.new("RGBA", body.size, (0, 0, 0, 0))
        contour.paste((6, 10, 5, 255), (0, 0), contour_mask)
        frame = Image.alpha_composite(contour, body)

        # Порядок слоёв: контур → тело → руки → сердце. Сердце рисуется последним,
        # иначе правая рука перекрывает его середину (это и было на устройстве).
        frame = Image.alpha_composite(frame, hands)
        frame = Image.alpha_composite(frame, heart)

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
        "with rlottie; red WaysGram heart drawn in front of the arms and a restored black contour; "
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
