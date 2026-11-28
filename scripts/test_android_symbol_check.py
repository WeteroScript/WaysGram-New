#!/usr/bin/env python3
"""Опечатки в вызовах служебных классов архива (ловит сборку до CI).

Такие ошибки видны только компилятору: `AndroidUtilities.runOnUiThread` (маленькая
«i») прошёл все текстовые тесты и уронил сборку 329 на `cannot find symbol`. Здесь
проверяется, что каждый вызов `Класс.метод(` в архиве действительно объявлен в
этом классе — для служебных классов WaysGram и `AndroidUtilities`, у которых нет
наследования и перегрузок «через интерфейс».

Тест намеренно читает части `WaysGram-Android.zip.part*`: если правка не попала в
архив, проверка её просто не увидит — как и компилятор.
"""
import re
import unittest
import zipfile
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Утилиты без наследования: любой `X.member` обязан быть объявлен в X.
TARGETS = (
    "AndroidUtilities",
    "WaysGramReactions",
    "WaysGramGiftTraits",
    "WaysGramGiftAttributes",
    "WaysGramBundledArtwork",
    "WaysGramGiftDocuments",
    "WaysGramProfileGiftsController",
    "WaysGramMapper",
    "WaysGramChats",
    "WaysGramMedia",
    "WaysGramTransport",
    "WaysGramMessages",
    "WaysGramGiftUpgrade",
    "WaysGramExtras",
)

_CALL = re.compile(r"\b(\w+)\.([A-Za-z_]\w*)\s*\(")
_DECL = re.compile(r"(?<![\w.$])([A-Za-z_]\w*)\s*\(")


def archive_bytes() -> bytes:
    parts = sorted(ROOT.glob("WaysGram-Android.zip.part*"), key=lambda path: path.name)
    return b"".join(part.read_bytes() for part in parts)


def strip_comments(body: str) -> str:
    """Тот же текст без комментариев: закомментированный вызов не компилируется."""
    out = []
    index, size = 0, len(body)
    while index < size:
        if body.startswith("//", index):
            end = body.find("\n", index)
            end = size if end < 0 else end
            out.append(" " * (end - index))
            index = end
        elif body.startswith("/*", index):
            end = body.find("*/", index + 2)
            end = size if end < 0 else end + 2
            out.append("".join("\n" if char == "\n" else " " for char in body[index:end]))
            index = end
        elif body[index] in "\"'":
            quote = body[index]
            end = index + 1
            while end < size:
                if body[end] == "\\":
                    end += 2
                    continue
                if body[end] == quote:
                    end += 1
                    break
                end += 1
            out.append(body[index:end])
            index = end
        else:
            out.append(body[index])
            index += 1
    return "".join(out)


class Symbols(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = zipfile.ZipFile(BytesIO(archive_bytes()))
        cls.sources = {}
        for name in cls.archive.namelist():
            if name.endswith(".java"):
                cls.sources[name] = strip_comments(
                    cls.archive.read(name).decode("utf-8", "replace")
                )
        cls.declared = {}
        for name, body in cls.sources.items():
            base = name.rsplit("/", 1)[-1][:-5]
            if base in TARGETS:
                members = set(_DECL.findall(body))
                members |= set(re.findall(r"\bclass\s+([A-Za-z_]\w*)", body))
                cls.declared[base] = members

    def test_every_utility_call_is_declared(self):
        missing = []
        for name, body in self.sources.items():
            for match in _CALL.finditer(body):
                target, member = match.group(1), match.group(2)
                members = self.declared.get(target)
                if members is None or member in members:
                    continue
                before = body[max(0, match.start() - 6):match.start()]
                if before.endswith("new "):
                    continue  # `new X.Y()` — конструктор вложенного класса
                if match.start() and body[match.start() - 1] == ".":
                    continue  # полное имя: org.telegram...WaysGramGiftAttributes.Listener()

                line = body.count("\n", 0, match.start()) + 1
                context = body.splitlines()[line - 1].strip()
                missing.append(f"{name}:{line} {target}.{member} -> {context[:110]}")
        self.assertEqual([], sorted(set(missing)), "вызовы без объявления")

    def test_every_target_class_is_in_the_archive(self):
        present = {name.rsplit("/", 1)[-1][:-5] for name in self.sources}
        for target in TARGETS:
            self.assertIn(target, present, f"{target}.java отсутствует в архиве")


if __name__ == "__main__":
    unittest.main()
