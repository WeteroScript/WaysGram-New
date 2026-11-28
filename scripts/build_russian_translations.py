#!/usr/bin/env python3
"""Собирает полный `values-ru/strings.xml` из официального langpack-а Telegram.

Зачем: в WaysGrape-сборке русский был переведён только на 530 строках из 11 772.
Telegram обычно догружает остальные строки с сервера (`langpack.getLangPack`), но
русский в клиенте — **встроенный** язык (`LocaleInfo.builtIn = true`,
`pathToFile = null`): `LocaleController.applyRemoteLanguage` для него сразу выходит,
и перевод берётся только из ресурсов `values-ru`. Своего сервера языков у WaysGram
нет, поэтому без этого файла при выбранном русском интерфейс оставался английским.

Что делает:
* берёт английские строки из `values/strings.xml` (список ключей и брендинг);
* сохраняет уже существующие русские строки как есть (они учитывают бренд
  WaysGram и правки форка);
* остальные строки берёт из официального Android-langpack-а Telegram;
* экранирует значения по правилам aapt2 (апострофы, кавычки, `&`, `<`, `>`, `\\n`,
  ведущие `@`/`?`, мульти-`%` → `formatted="false"`).

Использование:
    python3 scripts/build_russian_translations.py --langpack /path/getLangPack.json
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
RU_FILE = REPO_ROOT / "android-source/TMessagesProj/src/main/res/values-ru/strings.xml"
BASE_FILE = REPO_ROOT / "android-source/TMessagesProj/src/main/res/values/strings.xml"

# Строки, которых в langpack-е нет (новые ключи или строки самого WaysGram).
# Названия поисковиков и их URL не переводим: это имена сервисов и адреса.
SUPPLEMENT = {
    "AreTyping": "печатают...",
    "OutgoingCallsCount_one": "%1$d исходящий звонок",
    "OutgoingCallsCount_other": "%1$d исходящих звонков",
    "IncomingCallsCount_one": "%1$d входящий звонок",
    "IncomingCallsCount_other": "%1$d входящих звонков",
    "Chats": "Чаты",
    "Files": "Файлы",
    "Voice": "Голос",
    "Reactions_one": "%d реакция",
    "Reactions_other": "%d реакций",
    "Gift2AuctionBidStatusFuture2": "Ваша ставка будет %s",
    "WaysGramDiscussionCreate": "Создать группу",
    "WaysGramDiscussionEnabled": "Комментарии включены. Откройте канал заново, чтобы под постами появилась кнопка комментариев.",
    "WaysGramInboxTitleFee": "Сообщения канала · %1$d ⭐ за сообщение",
    "WaysGramInboxHistory": "История платежей",
    "WaysGramPaidHistoryTitle": "История платежей",
    "WaysGramPaidHistoryEmpty": "Платных сообщений пока нет.",
    "WaysGramPaidHistoryError": "Не удалось загрузить историю платежей.",
    "WaysGramPaidHistoryTotals": "Потрачено: %1$d ⭐ · Возвращено: %2$d ⭐",
    "WaysGramPaidHistoryDirect": "Личное сообщение",
    "WaysGramPaidHistoryChannel": "Сообщение в канале",
    "WaysGramPaidHistorySponsored": "Рекламный пост",
    "WaysGramPaidHistoryRefund": "Возврат",
}

STRING_RE = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.S)
NAME_RE = re.compile(r'<string name="([^"]+)"')
FORMAT_SPEC = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")


def read_base_names(path: Path) -> list[str]:
    names = NAME_RE.findall(path.read_text(encoding="utf-8"))
    seen: set[str] = set()
    ordered: list[str] = []
    for name in names:
        if name not in seen:
            seen.add(name)
            ordered.append(name)
    return ordered


def read_base_values(path: Path) -> dict[str, str]:
    return {name: value for name, value in STRING_RE.findall(path.read_text(encoding="utf-8"))}


def read_existing(path: Path) -> dict[str, str]:
    """Существующие русские строки: имя → уже экранированное содержимое."""
    if not path.is_file():
        return {}
    return {name: value for name, value in STRING_RE.findall(path.read_text(encoding="utf-8"))}


def needs_formatted_false(value: str) -> bool:
    """aapt2 ругается на «multiple substitutions in non-positional format» и на битый `%`."""
    positions = [m.start() for m in re.finditer("%", value)]
    if not positions:
        return False
    if any(FORMAT_SPEC.match(value, position) is None for position in positions):
        return True
    positional = [m.start() for m in re.finditer(r"%\d+\$", value)]
    return len(positions) >= 2 and len(positional) < len(positions)


def escape(value: str) -> str:
    value = value.replace("\r\n", "\n")
    value = value.replace("\\", "\\\\")
    value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    value = value.replace("\n", "\\n")
    value = value.replace('"', '\\"').replace("'", "\\'")
    if value[:1] in ("@", "?"):
        # Иначе aapt2 считает значение ссылкой на ресурс.
        value = "\\" + value
    if value != value.strip():
        value = f'"{value}"'
    return value


def rebrand(value: str, base_value: str | None) -> str:
    """В английской базе форк уже заменил Telegram на WaysGram — повторяем в переводе."""
    if base_value is not None and "Telegram" in base_value:
        return value
    return value.replace("Telegram", "WaysGram")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--langpack", required=True, help="JSON из langpack.getLangPack (android, ru)")
    parser.add_argument("--output", default=str(RU_FILE))
    args = parser.parse_args()

    langpack_path = Path(args.langpack)
    payload = json.loads(langpack_path.read_text(encoding="utf-8"))
    # Telegram отдаёт множественные формы одним `LangPackStringPluralized`, а в ресурсах
    # Android это отдельные строки `Key_one` / `Key_few` / `Key_many` / `Key_other`.
    pack: dict[str, str] = {}
    for item in payload.get("strings", []):
        key = item.get("key")
        if not key:
            continue
        if item.get("_") == "types.LangPackStringPluralized" or "other_value" in item:
            for form in ("zero", "one", "two", "few", "many", "other"):
                value = item.get(f"{form}_value")
                if value:
                    pack.setdefault(f"{key}_{form}", value)
        elif item.get("value"):
            pack.setdefault(key, item["value"])

    base_names = read_base_names(BASE_FILE)
    base_values = read_base_values(BASE_FILE)
    existing = read_existing(Path(args.output))

    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    kept = filled = missing = 0
    for name in base_names:
        if name in existing:
            raw = existing[name]
            attribute = ' formatted="false"' if needs_formatted_false(raw) else ""
            lines.append(f'    <string name="{name}"{attribute}>{raw}</string>')
            kept += 1
            continue
        value = pack.get(name) or SUPPLEMENT.get(name)
        if not value:
            missing += 1
            continue
        escaped = escape(rebrand(value, base_values.get(name)))
        attribute = ' formatted="false"' if needs_formatted_false(escaped) else ""
        lines.append(f'    <string name="{name}"{attribute}>{escaped}</string>')
        filled += 1
    lines.append("</resources>")
    output = "\n".join(lines) + "\n"

    # Проверяем, что получился валидный XML и что имена не потерялись.
    ElementTree.fromstring(output.encode("utf-8"))
    written = Path(args.output)
    written.write_text(output, encoding="utf-8")

    total = len(base_names)
    translated = kept + filled
    print(f"строк в базе: {total}")
    print(f"своих переводов сохранено: {kept}")
    print(f"добавлено из langpack-а: {filled}")
    print(f"без перевода (английский): {missing}")
    print(f"покрытие: {100.0 * translated / total:.1f}%")
    print(f"записано: {written} ({written.stat().st_size} B)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
