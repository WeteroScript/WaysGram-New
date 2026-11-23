#!/usr/bin/env python3
"""Сборка «арта» эмодзи (TGS) из SVG-исходников Twemoji.

Зачем
-----
Набор `telegram-emoji-effects` (см. `scripts/waysgram_emoji_import.py`) содержит
только *эффекты* — конфетти, искры, салюты. Эмодзи в них нет: Lottie-слой
называется «Confetti» и по кадрам рассыпается. Клиент же рисует иконку реакции
последним кадром (`40_40_lastreactframe`), а сам эмодзи в сообщении — тем же
документом. Поэтому в интерфейсе у многих реакций не было текстуры, а «живой»
эмодзи показывал не себя, а эффект.

Скрипт собирает второй набор — рисунок самого эмодзи. Исходники берутся из
Twemoji (CC-BY 4.0, `jdecked/twemoji`): плоский вектор, без растровых слоёв,
поэтому он корректно конвертируется в Lottie и укладывается в лимит 64 КБ.
К рисунку добавляется короткая анимация появления (масштаб + лёгкий поворот),
последний кадр — спокойный эмодзи, его и берёт клиент для чипа реакции.

Использование
-------------
    curl -L -o twemoji.tgz https://codeload.github.com/jdecked/twemoji/tar.gz/refs/heads/main
    mkdir -p twemoji && tar xzf twemoji.tgz -C twemoji --strip-components=1
    python3 scripts/waysgram_emoji_art.py --source twemoji/assets/svg --from-java

Конвертация SVG -> Lottie выполняется пакетом `lottie` (`pip install lottie`);
в песочнице он ставится в отдельный каталог, поэтому путь передаётся через
`--lottie-bin` (по умолчанию `<prefix>/bin/lottie_convert.py`).

Кадр собирается в двух вариантах:

* `assets/waysgram_emoji/art/<codepoints>.tgs` — рисунок во весь кадр: он идёт в
  сообщение и в текст (там документ рисуется целиком);
* `assets/waysgram_emoji/react/<codepoints>.tgs` — тот же рисунок, но вписанный в
  центральные 58% кадра. Клиент рисует чип реакции фильтром
  `40_40_lastreactframe`, а тот вырезает из кадра центральные 60% и увеличивает
  их: без отступа эмодзи обрезался бы по краям.

Имена те же, что у набора эффектов, таблицу `WaysGramEmojiTable` собирает
`scripts/waysgram_emoji_import.py`.
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ASSETS = REPO_ROOT / "TMessagesProj/src/main/assets/waysgram_emoji"
DEFAULT_JAVA_DIR = REPO_ROOT / "TMessagesProj/src/main/java/org/telegram"
DEFAULT_LOTTIE_BIN = "/tmp/lot/bin/lottie_convert.py"

# Кадровая сетка: 60 fps, последний кадр — спокойный эмодзи (его берут чипы).
FPS = 60
LAST_FRAME = 42
SIZE = 512
# Доля кадра, которую занимает рисунок в варианте для чипа реакции (см. шапку).
REACTION_SCALE = 0.58
# Масштаб появления: (кадр, коэффициент). Последнее значение — 1.0.
POP = [(0, 0.78), (10, 1.06), (18, 0.98), (26, 1.0), (LAST_FRAME, 1.0)]
ROTATION = [(0, -8.0), (12, 2.0), (22, 0.0), (LAST_FRAME, 0.0)]
# Прозрачность не анимируем: клиент рисует первый кадр как превью (firstframe).
OPACITY = [(0, 100), (LAST_FRAME, 100)]

FE0F = 0xFE0F
ZWJ = 0x200D


def java_literals(path: Path, field: str) -> list[str]:
    """Достаёт строковые литералы массива `field` из Java-файла."""
    text = path.read_text(encoding="utf-8")
    start = text.find(field)
    if start < 0:
        return []
    end = text.find("};", start)
    if end < 0:
        return []
    body = text[start:end]
    return [literal.replace('\\"', '"') for literal in re.findall(r'"((?:[^"\\]|\\.)*)"', body)]


def reaction_emoji(path: Path) -> list[str]:
    """Первый элемент каждой пары в `REACTIONS` (эмодзи, название)."""
    text = path.read_text(encoding="utf-8")
    pairs = re.findall(r'\{"((?:[^"\\]|\\.)*)",\s*"(?:[^"\\]|\\.)*"\}', text)
    return [p.replace('\\"', '"') for p in pairs]


def needed_emoji(java_dir: Path) -> list[str]:
    """Эмодзи из таблицы анимаций и палитры реакций."""
    table = java_dir / "tgnet/WaysGramEmojiTable.java"
    reactions = java_dir / "tgnet/WaysGramReactions.java"
    items: list[str] = []
    if table.is_file():
        items.extend(java_literals(table, "EMOJI"))
    if reactions.is_file():
        items.extend(reaction_emoji(reactions))
    result: list[str] = []
    seen: set[str] = set()
    for emoji in items:
        emoji = emoji.replace("\ufe0f", "")
        if emoji and emoji not in seen:
            seen.add(emoji)
            result.append(emoji)
    return result


def slug(codepoints: list[int]) -> str:
    return "-".join("%x" % cp for cp in codepoints)


def twemoji_variants(emoji: str) -> list[str]:
    """Возможные имена файла в Twemoji для строки эмодзи."""
    cps = [ord(ch) for ch in emoji]
    stripped = [cp for cp in cps if cp != FE0F]
    variants: list[str] = []

    def add(points: list[int]) -> None:
        name = slug(points)
        if name and name not in variants:
            variants.append(name)

    add(stripped)
    add(cps)
    # Twemoji держит U+FE0F внутри ZWJ-последовательностей (флаги, семьи).
    keep_inner = []
    for index, cp in enumerate(cps):
        if cp == FE0F and 0 < index < len(cps) - 1 and cps[index - 1] != ZWJ:
            keep_inner.append(cp)
        elif cp != FE0F:
            keep_inner.append(cp)
    add(keep_inner)
    return variants


def build_index(source: Path) -> dict[str, Path]:
    """Индекс «слаг без U+FE0F» -> файл.

    Twemoji хранит U+FE0F в именах не всегда (2764.svg), а в ZWJ-последовательностях
    обязательно (2764-fe0f-200d-1f525.svg). Поэтому сравниваем имена без `fe0f`.
    """
    index: dict[str, Path] = {}
    for candidate in sorted(source.glob("*.svg")):
        parts = [part for part in candidate.stem.split("-") if part != "fe0f"]
        if not parts:
            continue
        key = "-".join(parts)
        current = index.get(key)
        if current is None or len(candidate.stem) < len(current.stem):
            index[key] = candidate
    return index


def find_svg(source: Path, index: dict[str, Path], emoji: str) -> Path | None:
    for name in twemoji_variants(emoji):
        candidate = source / f"{name}.svg"
        if candidate.is_file():
            return candidate
        found = index.get(name)
        if found is not None:
            return found
    return None


def keyframes_scale(base: list[float]) -> dict:
    keys = []
    for index, (frame, factor) in enumerate(POP):
        entry = {"t": frame, "s": [base[0] * factor, base[1] * factor]}
        if index + 1 < len(POP):
            nxt = POP[index + 1][1]
            entry["e"] = [base[0] * nxt, base[1] * nxt]
            entry["i"] = {"x": [0.25], "y": [1]}
            entry["o"] = {"x": [0.4], "y": [0]}
        keys.append(entry)
    return {"a": 1, "k": keys}


def keyframes_single(frames: list[tuple[int, float]]) -> dict:
    keys = []
    for index, (frame, value) in enumerate(frames):
        entry = {"t": frame, "s": [value]}
        if index + 1 < len(frames):
            entry["e"] = [frames[index + 1][1]]
            entry["i"] = {"x": [0.25], "y": [1]}
            entry["o"] = {"x": [0.4], "y": [0]}
        keys.append(entry)
    return {"a": 1, "k": keys}


def convert(svg: Path, lottie_bin: Path, work: Path) -> dict:
    target = work / "shape.json"
    subprocess.run(
        [sys.executable, str(lottie_bin), str(svg), str(target)],
        check=True,
        capture_output=True,
        env={**os.environ, "PYTHONPATH": str(lottie_bin.parents[1])},
    )
    return json.loads(target.read_text(encoding="utf-8"))


def animate(j: dict, fit: float = 1.0) -> dict:
    """Вписывает рисунок в 512x512 (с долей `fit`) и добавляет появление."""
    width = float(j.get("w") or 36)
    height = float(j.get("h") or 36)
    scale = SIZE * fit / max(width, height)
    offset_x = (SIZE - width * scale) / 2.0
    offset_y = (SIZE - height * scale) / 2.0
    for layer in j.get("layers", []):
        ks = layer.setdefault("ks", {})
        size = ks.get("s", {}).get("k", [100.0, 100.0])
        pos = ks.get("p", {}).get("k", [0.0, 0.0])
        anchor = ks.get("a", {}).get("k", [0.0, 0.0])
        base = [size[0] * scale, size[1] * scale]
        ks["s"] = keyframes_scale(base)
        ks["p"] = {"a": 0, "k": [pos[0] * scale + offset_x, pos[1] * scale + offset_y]}
        ks["a"] = {"a": 0, "k": [anchor[0] * scale, anchor[1] * scale]}
        ks["r"] = keyframes_single(ROTATION)
        ks["o"] = keyframes_single(OPACITY)
        layer["ip"] = 0
        layer["op"] = LAST_FRAME
        layer["st"] = 0
    j["w"] = j["h"] = SIZE
    j["fr"] = FPS
    j["ip"] = 0
    j["op"] = LAST_FRAME
    j["nm"] = "waysgram-emoji-art"
    j.pop("markers", None)
    return j


def write_tgs(j: dict, target: Path) -> int:
    raw = json.dumps(j, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    target.parent.mkdir(parents=True, exist_ok=True)
    with open(target, "wb") as handle:
        with gzip.GzipFile(filename="", mode="wb", fileobj=handle, mtime=0, compresslevel=9) as gz:
            gz.write(raw)
    return target.stat().st_size


def validate(j: dict) -> list[str]:
    """Проверки, без которых клиент откажется рисовать файл."""
    problems: list[str] = []
    if j.get("w") != SIZE or j.get("h") != SIZE:
        problems.append("canvas не 512x512")
    if not j.get("layers"):
        problems.append("нет слоёв")
    types: set[str] = set()

    def walk(node) -> None:
        if isinstance(node, dict):
            if "ty" in node:
                types.add(node["ty"])
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(j)
    for banned in (2, "image", 13, "image_sequence"):
        if banned in types:
            problems.append("растровые слои (ty=%s)" % banned)
    if "ef" in types:
        problems.append("эффекты Lottie (ef)")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="каталог assets/svg из Twemoji")
    parser.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    parser.add_argument("--java-dir", type=Path, default=DEFAULT_JAVA_DIR)
    parser.add_argument("--lottie-bin", type=Path, default=Path(DEFAULT_LOTTIE_BIN))
    parser.add_argument("--react-dir", type=Path, default=None, help="каталог варианта для реакций (по умолчанию <assets>/react)")
    parser.add_argument("--only", nargs="*", help="ограничиться этими эмодзи (отладка)")
    parser.add_argument("--dry-run", action="store_true", help="только показать, чего не хватает")
    args = parser.parse_args()

    if not args.source.is_dir():
        raise SystemExit(f"нет каталога {args.source}")
    if not args.lottie_bin.is_file():
        raise SystemExit(f"нет {args.lottie_bin} — установите пакет lottie (`pip install lottie`)")

    emoji = args.only or needed_emoji(args.java_dir)
    if not emoji:
        raise SystemExit("не удалось собрать список эмодзи из Java-исходников")

    react_dir = args.react_dir or (args.assets / "react")
    index = build_index(args.source)
    missing: list[str] = []
    written = 0
    total = 0
    work = Path(tempfile.mkdtemp(prefix="waysgram-art-"))
    try:
        for item in emoji:
            svg = find_svg(args.source, index, item)
            if svg is None:
                missing.append(item)
                continue
            if args.dry_run:
                written += 1
                continue
            source_json = convert(svg, args.lottie_bin, work)
            name = slug([ord(ch) for ch in item]) + ".tgs"
            failed = False
            for directory, fit in ((args.assets / "art", 1.0), (react_dir, REACTION_SCALE)):
                shape = animate(json.loads(json.dumps(source_json)), fit)
                problems = validate(shape)
                if problems:
                    print(f"пропуск {item}: {', '.join(problems)}", file=sys.stderr)
                    missing.append(item)
                    failed = True
                    break
                total += write_tgs(shape, directory / name)
            if not failed:
                written += 1
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print(
        "арт: %d файлов, %.2f МБ, нет исходника у %d эмодзи"
        % (written, total / 1e6, len(missing))
    )
    if missing:
        print("без арта: " + " ".join(missing), file=sys.stderr)
        return 1 if not args.dry_run else 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
