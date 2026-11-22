#!/usr/bin/env python3
"""Распаковка Android-исходников WaysGram из частей архива в рабочее дерево.

Части `WaysGram-Android.zip.part1…part5` по-прежнему остаются единственным
источником правды для сборки: workflow `.github/workflows/build-apk.yml`
склеивает их и запускает Gradle. Этот скрипт нужен только для того, чтобы
читать/искать код обычными файлами (grep, diff, IDE, агент), не распаковывая
весь архив вручную.

По умолчанию распаковываются только текстовые исходники и ресурсы:
  * `**/src/**` (java, kotlin, xml, assets-текст),
  * gradle/manifest/proguard/конфиги,
  * документация архива (README.md, WAYSGRAM_CHANGES.md).

Исключается всё, что не является исходным кодом и весит десятки мегабайт:
  * `jni/**` — нативный C/C++ (в т.ч. 215 МБ прекомпилированных `*.a`),
  * `*.a`, `*.so`, архивы и прочие бинарники,
  * `res/raw/*.json` — анимации реакций,
  * `assets/*.pack`, `assets/*.dat`, `assets/fonts/**`, `assets/models/**`,
  * растровые ресурсы (`*.webp`, `*.png`, `*.jpg`) — им место в архиве.

Использование:
    python3 scripts/extract_android_source.py                 # -> android-source/
    python3 scripts/extract_android_source.py --dest android-source
    python3 scripts/extract_android_source.py --with-images   # + растровые ресурсы
    python3 scripts/extract_android_source.py --full          # вообще всё, 381 МБ
"""

from __future__ import annotations

import argparse
import shutil
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
PARTS = [f"WaysGram-Android.zip.part{i}" for i in range(1, 6)]
ARCHIVE_ROOT = "WaysGram-Android"

BINARY_SUFFIXES = {
    ".a", ".so", ".o", ".jar", ".aar", ".zip", ".apk", ".pack", ".dat",
    ".attheme", ".ttf", ".otf", ".woff", ".woff2", ".mp3", ".ogg", ".wav",
    ".mp4", ".webm", ".jks", ".keystore",
}
RASTER_SUFFIXES = {".webp", ".png", ".jpg", ".jpeg", ".gif", ".9.png"}


def reconstruct_archive(tmp_zip: Path) -> Path:
    parts = [REPO_ROOT / name for name in PARTS]
    missing = [str(p.name) for p in parts if not p.is_file()]
    if missing:
        sys.exit(f"Не найдены части архива: {', '.join(missing)}")
    tmp_zip.parent.mkdir(parents=True, exist_ok=True)
    with tmp_zip.open("wb") as out:
        for part in parts:
            with part.open("rb") as src:
                shutil.copyfileobj(src, out, length=1 << 20)
    return tmp_zip


def should_extract(name: str, *, full: bool, with_images: bool) -> bool:
    if full:
        return True

    rel = name[len(ARCHIVE_ROOT) + 1:] if name.startswith(ARCHIVE_ROOT + "/") else name
    if "jni" in rel.split("/"):
        return False

    lower = rel.lower()
    suffix = "." + lower.rsplit(".", 1)[-1] if "." in lower.rsplit("/", 1)[-1] else ""

    if suffix in BINARY_SUFFIXES:
        return False
    if not with_images and suffix in RASTER_SUFFIXES:
        return False
    # Анимации реакций и прочие крупные json-блобы в res/raw.
    if "/res/raw/" in f"/{rel}" and lower.endswith(".json"):
        return False
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dest", default="android-source", help="каталог распаковки внутри репозитория")
    parser.add_argument("--full", action="store_true", help="распаковать архив целиком (≈381 МБ)")
    parser.add_argument("--with-images", action="store_true", help="добавить растровые ресурсы (.webp/.png)")
    parser.add_argument("--clean", action="store_true", help="удалить каталог назначения перед распаковкой")
    args = parser.parse_args()

    dest = (REPO_ROOT / args.dest).resolve()
    if dest != REPO_ROOT and REPO_ROOT not in dest.parents:
        sys.exit(f"Каталог назначения должен быть внутри репозитория: {dest}")

    zip_path = reconstruct_archive(Path("/tmp/WaysGram-Android.zip"))
    print(f"архив восстановлен: {zip_path} ({zip_path.stat().st_size / 1e6:.1f} МБ)")

    if args.clean and dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True, exist_ok=True)

    extracted = 0
    total_bytes = 0
    with zipfile.ZipFile(zip_path) as zf:
        bad = zf.testzip()
        if bad is not None:
            sys.exit(f"Архив повреждён на файле: {bad}")
        for info in zf.infolist():
            if info.is_dir():
                continue
            if not should_extract(info.filename, full=args.full, with_images=args.with_images):
                continue
            rel = info.filename[len(ARCHIVE_ROOT) + 1:] if info.filename.startswith(ARCHIVE_ROOT + "/") else info.filename
            target = dest / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, target.open("wb") as out:
                shutil.copyfileobj(src, out, length=1 << 20)
            extracted += 1
            total_bytes += info.file_size

    print(f"распаковано файлов: {extracted}, {total_bytes / 1e6:.1f} МБ -> {dest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
