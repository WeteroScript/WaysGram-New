#!/usr/bin/env python3
"""Импорт анимированных эмодзи (TGS) в Android-исходники WaysGram.

Зачем
-----
Клиент рисует «живой» эмодзи и текстурки реакций только из документов
`application/x-tgsticker`. Раньше в APK лежало три собственных файла
(`heart/fire/star`), поэтому анимировались единицы эмодзи, а у реакций не было
картинок. Скрипт раскладывает полный набор TGS в
`TMessagesProj/src/main/assets/waysgram_emoji/` и генерирует Java-таблицу
`WaysGramEmojiTable` (эмодзи -> имя ассета), по которой `WaysGramAnimatedEmoji`
создаёт документы для `messages.getEmojiStickers`/реакций.

Источник ассетов
----------------
`ilyhalight/telegram-emoji-effects` (https://github.com/ilyhalight/telegram-emoji-effects) —
набор анимаций эмодзи Telegram в формате TGS/Lottie, имена файлов детерминированы
по Unicode (`tgs/U+1F389/0.tgs`). Репозиторий нужно скачать и распаковать
вручную (в песочнице нет доступа к api.telegram.org):

    curl -L -o eefx.tgz https://codeload.github.com/ilyhalight/telegram-emoji-effects/tar.gz/refs/heads/master
    mkdir -p eefx && tar xzf eefx.tgz -C eefx --strip-components=1
    python3 scripts/waysgram_emoji_import.py --source eefx

Использование
-------------
    python3 scripts/waysgram_emoji_import.py --source /tmp/eefx
    python3 scripts/waysgram_emoji_import.py --source /tmp/eefx --assets <dir> --java <file>
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ASSETS = REPO_ROOT / "TMessagesProj/src/main/assets/waysgram_emoji"
DEFAULT_JAVA = (
    REPO_ROOT
    / "TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramEmojiTable.java"
)
# ⭐ раньше был единственным «своим» анимированным эмодзи и в наборе отсутствует.
LEGACY_STAR = (0x2B50, "star.tgs")

HEADER = """package org.telegram.tgnet;

/**
 * Таблица «эмодзи -> файл анимации» для @@LINK@@.
 *
 * <p>Сгенерировано {script} — не редактировать вручную.
 * Ассеты лежат в assets/waysgram_emoji, формат TGS (gzip + Lottie, векторный,
 * без растровых слоёв), поэтому их проигрывает нативный плеер анимированных
 * стикеров.
 */
final class WaysGramEmojiTable {{
    private WaysGramEmojiTable() {{
    }}

    /** Эмодзи без U+FE0F — ровно в том виде, в каком их сравнивает клиент. */
    static final String[] EMOJI = {{
{emoji}
    }};

    /** Имена файлов в assets/waysgram_emoji. */
    static final String[] ASSETS = {{
{assets}
    }};
}}
"""


def slug(dir_name: str, codepoints: list[int]) -> str:
    """`U+1F3F4_U+200D_U+2620` -> `1f3f4-200d-2620`."""
    return "-".join("%x" % cp for cp in codepoints)


def emoji_text(codepoints: list[int]) -> str:
    return "".join(chr(cp) for cp in codepoints).replace("\ufe0f", "")


def java_literal(text: str) -> str:
    out = []
    for ch in text:
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        else:
            out.append(ch)
    return '"' + "".join(out) + '"'


def collect(source: Path) -> list[tuple[list[int], Path]]:
    tgs_dir = source / "tgs"
    if not tgs_dir.is_dir():
        raise SystemExit(f"нет каталога {tgs_dir} — это точно распакованный telegram-emoji-effects?")
    picked: list[tuple[list[int], Path]] = []
    for entry in sorted(tgs_dir.iterdir()):
        if not entry.is_dir():
            continue
        parts = entry.name.split("_")
        try:
            codepoints = [int(p[2:], 16) for p in parts]
        except ValueError:
            print(f"пропуск {entry.name}: не разобрать codepoints", file=sys.stderr)
            continue
        animation = entry / "0.tgs"
        if not animation.is_file():
            continue
        picked.append((codepoints, animation))
    return picked


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="распакованный telegram-emoji-effects")
    parser.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    parser.add_argument("--java", type=Path, default=DEFAULT_JAVA)
    parser.add_argument(
        "--legacy-star",
        type=Path,
        default=None,
        help="старый star.tgs (⭐), если набора нет в источнике",
    )
    args = parser.parse_args()

    assets: Path = args.assets
    assets.mkdir(parents=True, exist_ok=True)

    entries: list[tuple[str, str, int]] = []  # (emoji, asset, size)
    for codepoints, animation in collect(args.source):
        emoji = emoji_text(codepoints)
        if not emoji:
            continue
        name = slug(animation.parent.name, codepoints) + ".tgs"
        shutil.copyfile(animation, assets / name)
        entries.append((emoji, name, (assets / name).stat().st_size))

    if args.legacy_star and args.legacy_star.is_file():
        name = "%x.tgs" % LEGACY_STAR[0]
        shutil.copyfile(args.legacy_star, assets / name)
        entries.append((emoji_text([LEGACY_STAR[0]]), name, (assets / name).stat().st_size))

    entries.sort(key=lambda item: [ord(ch) for ch in item[0]])
    # один эмодзи — один ассет (в наборе бывает по нескольку вариантов)
    unique: list[tuple[str, str, int]] = []
    seen: set[str] = set()
    for emoji, name, size in entries:
        if emoji in seen:
            continue
        seen.add(emoji)
        unique.append((emoji, name, size))

    emoji_lines = "\n".join("        %s," % java_literal(e) for e, _, _ in unique)
    asset_lines = "\n".join("        %s," % java_literal(a) for _, a, _ in unique)
    args.java.parent.mkdir(parents=True, exist_ok=True)
    args.java.write_text(
        HEADER.replace("@@LINK@@", "{{@link WaysGramAnimatedEmoji}}").format(
            script="scripts/waysgram_emoji_import.py",
            emoji=emoji_lines,
            assets=asset_lines,
        ),
        encoding="utf-8",
    )

    total = sum(size for _, _, size in unique)
    print(f"эмодзи: {len(unique)}, ассеты: {len(list(assets.glob('*.tgs')))}, всего {total / 1e6:.2f} МБ")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
