#!/usr/bin/env python3
"""Пересборка split-архива Android-исходников после правки клиента.

Части `WaysGram-Android.zip.part1…part5` — единственный источник правды для сборки
APK, поэтому любая правка клиентского кода должна попасть именно в них. Скрипт
собирает архив из частей, подменяет указанные записи содержимым зеркала
`android-source/` и снова режет результат на части по 26 000 000 байт.

Использование:
    python3 scripts/repack_android_source.py \\
        WaysGram-Android/TMessagesProj/src/main/java/org/telegram/ui/Stars/StarGiftSheet.java

Аргументы — пути внутри архива (как в `zipinfo`). Новое содержимое берётся из
`android-source/<путь без префикса WaysGram-Android/>`. Перед записью частей скрипт
проверяет, что изменились только перечисленные записи, а после записи перечитывает
готовые части и сверяет контрольные суммы.
"""

from __future__ import annotations

import argparse
import io
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
PART_COUNT = 5
PART_SIZE = 26_000_000
ARCHIVE_PREFIX = "WaysGram-Android/"


def part_names() -> list[str]:
    return [f"WaysGram-Android.zip.part{i}" for i in range(1, PART_COUNT + 1)]


def read_archive() -> bytes:
    parts = [REPO_ROOT / name for name in part_names()]
    missing = [p.name for p in parts if not p.is_file()]
    if missing:
        sys.exit(f"Не найдены части архива: {', '.join(missing)}")
    return b"".join(part.read_bytes() for part in parts)


def mirror_path(entry: str) -> Path:
    if not entry.startswith(ARCHIVE_PREFIX):
        sys.exit(f"Путь внутри архива должен начинаться с {ARCHIVE_PREFIX}: {entry}")
    return REPO_ROOT / "android-source" / entry[len(ARCHIVE_PREFIX):]


def repack(entries: list[str]) -> bytes:
    original = read_archive()
    source = zipfile.ZipFile(io.BytesIO(original))
    names = source.namelist()
    if len(names) != len(set(names)):
        sys.exit("В архиве есть повторяющиеся записи — правка небезопасна")

    replacements: dict[str, bytes] = {}
    for entry in entries:
        if entry not in names:
            sys.exit(f"Записи нет в архиве: {entry}")
        path = mirror_path(entry)
        if not path.is_file():
            sys.exit(f"Нет файла в зеркале android-source: {path}")
        replacements[entry] = path.read_bytes()

    crc_before = {info.filename: info.CRC for info in source.infolist() if info.filename not in replacements}

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", allowZip64=True) as target:
        for info in source.infolist():
            cloned = zipfile.ZipInfo(info.filename, info.date_time)
            cloned.compress_type = info.compress_type
            cloned.external_attr = info.external_attr
            cloned.internal_attr = info.internal_attr
            cloned.create_system = info.create_system
            cloned.comment = info.comment
            cloned.extra = info.extra
            data = replacements.get(info.filename)
            if data is None:
                data = source.read(info.filename)
            target.writestr(cloned, data)

    source.close()
    result = buffer.getvalue()

    check = zipfile.ZipFile(io.BytesIO(result))
    broken = check.testzip()
    if broken is not None:
        sys.exit(f"Архив не прошёл проверку CRC: {broken}")
    for name in names:
        if name not in check.namelist():
            sys.exit(f"Запись потерялась при пересборке: {name}")
    if len(check.namelist()) != len(names):
        sys.exit("Число записей изменилось при пересборке")
    for info in check.infolist():
        if info.filename in replacements:
            if check.read(info.filename) != replacements[info.filename]:
                sys.exit(f"Новое содержимое не сохранилось: {info.filename}")
        elif info.CRC != crc_before[info.filename]:
            sys.exit(f"Изменилась чужая запись: {info.filename}")
    check.close()
    for entry in entries:
        print(f"обновлено: {entry} ({len(replacements[entry])} B)")
    return result


def write_parts(data: bytes) -> None:
    chunks = [data[offset:offset + PART_SIZE] for offset in range(0, len(data), PART_SIZE)]
    if len(chunks) > PART_COUNT:
        sys.exit(f"Архив вырос до {len(chunks)} частей — увеличьте PART_COUNT")
    for index, name in enumerate(part_names(), start=1):
        path = REPO_ROOT / name
        chunk = chunks[index - 1] if index <= len(chunks) else b""
        path.write_bytes(chunk)
        print(f"{name}: {len(chunk)} B")
    # Пустые хвостовые части не нужны: склейка идёт по списку из workflow.
    for index in range(len(chunks) + 1, PART_COUNT + 1):
        path = REPO_ROOT / part_names()[index - 1]
        if path.exists():
            path.unlink()
            print(f"удалена лишняя часть: {path.name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("entries", nargs="+", help="пути записей внутри архива")
    parser.add_argument("--dry-run", action="store_true", help="только собрать и проверить архив")
    options = parser.parse_args()
    result = repack(options.entries)
    print(f"новый архив: {len(result)} B ({len(zipfile.ZipFile(io.BytesIO(result)).namelist())} записей)")
    if options.dry_run:
        print("dry-run: части не перезаписаны")
        return
    write_parts(result)


if __name__ == "__main__":
    main()
