#!/usr/bin/env python3
"""Собирает `authbot/assets/reactions.json` из клиентского каталога реакций.

Вайтлист реакций на сервере был записан руками и отставал от палитры приложения:
клиент предлагал 👌 🥱 🥴 😴 🦄 💊 🌭 🍓 🎃 🎊 ✨, а сервер отвечал 400
«Недопустимый набор реакций». Теперь список один и тот же — он генерируется из
тех же исходников, что попадают в APK:

* `WaysGramReactions.REACTIONS` — палитра меню и шторки реакций;
* `WaysGramReactionAssets.EMOJI` — каталог официальных анимаций.

Запуск: `python3 scripts/generate_reaction_catalog.py [--check]`.
`--check` только сверяет файл с архивом (это же делает тест).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"
OUT = ROOT / "authbot" / "assets" / "reactions.json"

# Семь «обычных» реакций Telegram: их принимает и v1-маршрут одиночной реакции.
BASIC = ["👍", "❤️", "😂", "😮", "😢", "🎉", "🙏"]

_SKIN = range(0x1F3FB, 0x1F400)


def identity(value: str) -> str:
    """Идентичность эмодзи: без U+FE0F и без оттенков кожи (как на сервере)."""
    return "".join(
        char for char in value if char != "\ufe0f" and ord(char) not in _SKIN
    )


def _unescape(value: str) -> str:
    return value.replace("\\u200d", "\u200d").replace("\\ufe0f", "\ufe0f")


def _archive() -> zipfile.ZipFile:
    parts = sorted(ROOT.glob("WaysGram-Android.zip.part*"), key=lambda path: path.name)
    if not parts:
        raise SystemExit("части WaysGram-Android.zip.part* не найдены")
    return zipfile.ZipFile(BytesIO(b"".join(part.read_bytes() for part in parts)))


def client_palettes() -> tuple[list[str], list[str]]:
    archive = _archive()
    palette_src = archive.read(JAVA + "tgnet/WaysGramReactions.java").decode("utf-8")
    block = palette_src.split("private static final String[][] REACTIONS = {", 1)[1].split("};", 1)[0]
    palette = [_unescape(item) for item in re.findall(r'\{"([^"]+)"', block)]

    assets_src = archive.read(JAVA + "tgnet/WaysGramReactionAssets.java").decode("utf-8")
    match = re.search(r"EMOJI = \{(.*?)\};", assets_src, re.S)
    if match is None:
        raise SystemExit("WaysGramReactionAssets.EMOJI не найден")
    assets = [_unescape(item) for item in re.findall(r'"([^"]+)"', match.group(1))]
    return palette, assets


def build() -> dict[str, list[str]]:
    palette, assets = client_palettes()
    ordered: list[str] = []
    seen: dict[str, int] = {}
    for emoji in palette + assets:
        key = identity(emoji)
        if not key:
            continue
        if key in seen:
            # Одна и та же реакция может быть записана с U+FE0F и без него:
            # сервер хранит вариант с селектором, если он есть в каталоге.
            index = seen[key]
            if "\ufe0f" in emoji and "\ufe0f" not in ordered[index]:
                ordered[index] = emoji
            continue
        seen[key] = len(ordered)
        ordered.append(emoji)
    catalog_keys = {identity(value) for value in ordered}
    basic = [value for value in BASIC if identity(value) in catalog_keys]
    missing = [value for value in BASIC if identity(value) not in catalog_keys]
    if missing:
        ordered.extend(missing)
    return {"basic": basic, "catalog": ordered}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="не писать, только сверить")
    args = parser.parse_args()
    data = build()
    payload = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != payload:
            print("reactions.json отличается от клиентского каталога — перегенерируйте", file=sys.stderr)
            return 1
        print(f"ok: {len(data['catalog'])} реакций, {len(data['basic'])} базовых")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(payload, encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)}: {len(data['catalog'])} реакций, {len(data['basic'])} базовых")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
