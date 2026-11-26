#!/usr/bin/env python3
"""Русский интерфейс: переводы лежат в ресурсах, а не в langpack-е с сервера.

С устройства: в приложении выбран русский, а интерфейс английский. Русский в клиенте —
**встроенный** язык (`LocaleInfo.builtIn = true`, `pathToFile = null`), поэтому
`LocaleController.applyRemoteLanguage` для него сразу выходит и перевод берётся только из
`values-ru`. Своего сервера языков у WaysGram нет, а в сборке было переведено 530 строк из
11 772 — остальное падало в английский.

Тест держит файл полным и валидным для aapt2: имена только из базовых ресурсов, корректное
экранирование и `formatted="false"` там, где строка не является форматом.
"""
import io
import re
import unittest
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = 'WaysGram-Android/TMessagesProj/src/main/res/'
FORMAT_SPEC = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")
NAME_RE = re.compile(r'<string name="([^"]+)"')


class RussianTranslations(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.base = cls.archive.read(RES + 'values/strings.xml').decode('utf-8')
        cls.ru = cls.archive.read(RES + 'values-ru/strings.xml').decode('utf-8')
        cls.base_names = set(NAME_RE.findall(cls.base))
        cls.ru_root = ElementTree.fromstring(cls.ru.encode('utf-8'))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def strings(self) -> dict[str, ElementTree.Element]:
        return {element.get('name'): element for element in self.ru_root if element.tag == 'string'}

    def test_most_of_the_interface_is_translated(self):
        translated = len(set(self.strings()) & self.base_names)
        total = len(self.base_names)
        # Было 530 из 11 772 (4.5%): интерфейс оставался английским.
        self.assertGreaterEqual(translated, int(total * 0.98),
                                f'переведено {translated} из {total}')
        for key, expected in (('Settings', 'Настройки'), ('Cancel', 'Отмена'),
                              ('Delete', 'Удалить'), ('Search', 'Поиск')):
            self.assertEqual(self.strings()[key].text, expected, key)

    def test_branding_stays_waysgram(self):
        self.assertEqual(self.strings()['AppName'].text, 'WaysGram')
        self.assertEqual(self.strings()['LanguageName'].text, 'Русский')

    def test_only_base_string_names_are_translated(self):
        extra = sorted(set(self.strings()) - self.base_names)
        self.assertEqual(extra, [], 'в values-ru есть строки, которых нет в базовых ресурсах')

    def test_values_are_escaped_for_aapt2(self):
        problems = []
        for name, element in self.strings().items():
            value = element.text or ''
            if not value.strip():
                problems.append((name, 'пустая строка'))
                continue
            if '\n' in value or '\r' in value:
                problems.append((name, 'перенос строки вместо \\n'))
            if value[:1] in ('@', '?'):
                problems.append((name, 'ведущий @ или ? не экранирован'))
            for match in re.finditer("'", value):
                if match.start() == 0 or value[match.start() - 1] != '\\':
                    problems.append((name, "неэкранированный апостроф"))
                    break
            positions = [m.start() for m in re.finditer('%', value)]
            if positions:
                invalid = any(FORMAT_SPEC.match(value, position) is None for position in positions)
                positional = len(re.findall(r'%\d+\$', value))
                needs_attr = invalid or (len(positions) >= 2 and positional < len(positions))
                if needs_attr and element.get('formatted') != 'false':
                    problems.append((name, 'нужен formatted="false"'))
        self.assertEqual(problems, [])

    def test_russian_is_a_builtin_language_so_resources_are_the_only_source(self):
        # Если русского не будет в builtin-списке, клиент начнёт просить langpack с сервера,
        # которого у WaysGram нет, и снова останется без перевода.
        locale = self.archive.read('WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'
                                   'messenger/LocaleController.java').decode('utf-8')
        self.assertIn('localeInfo.name = "Русский";', locale)
        self.assertIn('localeInfo.shortName = localeInfo.pluralLangCode = "ru";', locale)
        self.assertIn('localeInfo.builtIn = true;', locale)


if __name__ == '__main__':
    unittest.main()
