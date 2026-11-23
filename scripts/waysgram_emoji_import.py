#!/usr/bin/env python3
"""Импорт анимированных эмодзи (TGS) в Android-исходники WaysGram.

Зачем
-----
Клиент рисует «живой» эмодзи и текстурки реакций только из документов
`application/x-tgsticker`. Раньше в APK лежало три собственных файла
(`heart/fire/star`), поэтому анимировались единицы эмодзи, а у реакций не было
картинок.

Наборы
------
В APK лежат два независимых набора TGS:

* **рисунок** — `assets/waysgram_emoji/art/<codepoints>.tgs` (собирается
  `scripts/waysgram_emoji_art.py` из Twemoji): сам эмодзи с короткой анимацией
  появления. Именно его клиент рисует в сообщении и в тексте.
  Вариант `assets/waysgram_emoji/react/<codepoints>.tgs` — тот же рисунок с
  отступом под чип реакции: клиент берёт центральные 60% кадра
  (`40_40_lastreactframe`), поэтому в чип идёт именно он.
* **эффект** — `assets/waysgram_emoji/<codepoints>.tgs` (набор
  `ilyhalight/telegram-emoji-effects`): конфетти, искры, салюты. Проигрывается
  по нажатию на реакцию (`activate/effect/around_animation`).

Скрипт копирует эффекты из источника, читает уже собранный арт и генерирует
Java-таблицу `WaysGramEmojiTable` (эмодзи -> файл рисунка, эмодзи -> файл
эффекта), по которой `WaysGramAnimatedEmoji` создаёт документы.

Источник эффектов
-----------------
`ilyhalight/telegram-emoji-effects` (https://github.com/ilyhalight/telegram-emoji-effects) —
имена файлов детерминированы по Unicode (`tgs/U+1F389/0.tgs`). Репозиторий нужно
скачать и распаковать вручную (в песочнице нет доступа к api.telegram.org):

    curl -L -o eefx.tgz https://codeload.github.com/ilyhalight/telegram-emoji-effects/tar.gz/refs/heads/master
    mkdir -p eefx && tar xzf eefx.tgz -C eefx --strip-components=1
    python3 scripts/waysgram_emoji_art.py --source twemoji/assets/svg --from-java
    python3 scripts/waysgram_emoji_import.py --source eefx

Использование
-------------
    python3 scripts/waysgram_emoji_import.py --source /tmp/eefx
    python3 scripts/waysgram_emoji_import.py --source /tmp/eefx --assets <dir> --java <file>
    python3 scripts/waysgram_emoji_import.py --source /tmp/eefx --skip-effects  # только таблица
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
 * Таблица «эмодзи -> файлы анимаций» для {@link WaysGramAnimatedEmoji}.
 *
 * <p>Сгенерировано scripts/waysgram_emoji_import.py — не редактировать вручную.
 * В APK два набора TGS (gzip + Lottie, векторные, без растровых слоёв):
 * art/ — рисунок эмодзи с анимацией появления, корень каталога — эффект
 * (конфетти, искры), который проигрывается по нажатию.
 */
final class WaysGramEmojiTable {
    private WaysGramEmojiTable() {
    }

    /** Эмодзи без U+FE0F — ровно в том виде, в каком их сравнивает клиент. */
    static final String[] EMOJI = {
@@EMOJI@@
    };

    /** Рисунок эмодзи в assets/waysgram_emoji или {@code null}, если его нет. */
    static final String[] ART = {
@@ART@@
    };

    /** Тот же рисунок с отступом под чип реакции или {@code null}. */
    static final String[] REACT = {
@@REACT@@
    };

    /** Эффект эмодзи в assets/waysgram_emoji или {@code null}, если его нет. */
    static final String[] EFFECT = {
@@EFFECT@@
    };
}
"""


def slug(codepoints: list[int]) -> str:
    """`U+1F3F4_U+200D_U+2620` -> `1f3f4-200d-2620`."""
    return "-".join("%x" % cp for cp in codepoints)


def parse_slug(stem: str) -> list[int] | None:
    try:
        return [int(part, 16) for part in stem.split("-")]
    except ValueError:
        return None


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


def collect_effects(source: Path) -> list[tuple[list[int], Path]]:
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


def collect_set(directory: Path, prefix: str) -> dict[str, str]:
    """Имена файлов набора по эмодзи."""
    found: dict[str, str] = {}
    if not directory.is_dir():
        return found
    for file in sorted(directory.glob("*.tgs")):
        codepoints = parse_slug(file.stem)
        if not codepoints:
            print(f"пропуск {file.name}: не разобрать codepoints", file=sys.stderr)
            continue
        emoji = emoji_text(codepoints)
        if emoji:
            found.setdefault(emoji, prefix + file.name)
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="распакованный telegram-emoji-effects")
    parser.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    parser.add_argument("--java", type=Path, default=DEFAULT_JAVA)
    parser.add_argument("--art-dir", type=Path, default=None, help="каталог арта (по умолчанию <assets>/art)")
    parser.add_argument("--react-dir", type=Path, default=None, help="каталог варианта для реакций (по умолчанию <assets>/react)")
    parser.add_argument("--skip-effects", action="store_true", help="не копировать эффекты, только собрать таблицу")
    parser.add_argument(
        "--legacy-star",
        type=Path,
        default=None,
        help="старый star.tgs (⭐), если набора нет в источнике",
    )
    args = parser.parse_args()

    assets: Path = args.assets
    assets.mkdir(parents=True, exist_ok=True)
    art_dir = args.art_dir or (assets / "art")
    react_dir = args.react_dir or (assets / "react")

    effects: dict[str, str] = {}
    if not args.skip_effects:
        if args.source is None:
            parser.error("нужен --source с набором эффектов (или --skip-effects)")
        for codepoints, animation in collect_effects(args.source):
            emoji = emoji_text(codepoints)
            if not emoji:
                continue
            name = slug(codepoints) + ".tgs"
            shutil.copyfile(animation, assets / name)
            effects.setdefault(emoji, name)
        if args.legacy_star and args.legacy_star.is_file():
            name = "%x.tgs" % LEGACY_STAR[0]
            shutil.copyfile(args.legacy_star, assets / name)
            effects.setdefault(emoji_text([LEGACY_STAR[0]]), name)
    else:
        for file in sorted(assets.glob("*.tgs")):
            codepoints = parse_slug(file.stem)
            if not codepoints:
                continue
            effects.setdefault(emoji_text(codepoints), file.name)

    art = collect_set(art_dir, "art/")
    react = collect_set(react_dir, "react/")
    if not art and not effects:
        raise SystemExit(f"пусто: нет ни арта в {art_dir}, ни эффектов в {assets}")

    emoji_list = sorted(set(art) | set(react) | set(effects), key=lambda item: [ord(ch) for ch in item])
    emoji_lines = "\n".join("        %s," % java_literal(e) for e in emoji_list)
    art_lines = "\n".join(
        "        %s," % (java_literal(art[e]) if e in art else "null") for e in emoji_list
    )
    react_lines = "\n".join(
        "        %s," % (java_literal(react[e]) if e in react else "null") for e in emoji_list
    )
    effect_lines = "\n".join(
        "        %s," % (java_literal(effects[e]) if e in effects else "null") for e in emoji_list
    )
    args.java.parent.mkdir(parents=True, exist_ok=True)
    table = (
        HEADER.replace("@@EMOJI@@", emoji_lines)
        .replace("@@ART@@", art_lines)
        .replace("@@REACT@@", react_lines)
        .replace("@@EFFECT@@", effect_lines)
    )
    args.java.write_text(table, encoding="utf-8")

    art_size = sum((assets / art[e]).stat().st_size for e in art)
    react_size = sum((assets / react[e]).stat().st_size for e in react)
    fx_size = sum((assets / effects[e]).stat().st_size for e in effects)
    print(
        "эмодзи: %d, рисунков: %d (%.2f МБ), реакций: %d (%.2f МБ), эффектов: %d (%.2f МБ)"
        % (len(emoji_list), len(art), art_size / 1e6, len(react), react_size / 1e6,
           len(effects), fx_size / 1e6)
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
