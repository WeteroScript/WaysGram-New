#!/usr/bin/env python3
"""Проверки секретных номеров: бот, API и клиент должны понимать одинаково.

Симптом с устройства: на экране входа выбран «Секрет», введён номер вида
«+0 000 0000», и появляется «Invalid phone number». В боте то же самое —
«Введите номер в международном формате».

Причина: секретными считались только ровно «+0», «+111», «+333», «+666», «+999».
Любые цифры после префикса уже не проходили, поэтому ни создать такой аккаунт,
ни войти с ним было нельзя. Теперь секретное пространство — это префикс + цифры.
"""
import io
import re
import sys
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"

sys.path.insert(0, str(ROOT / "authbot"))
from virtual_numbers import SECRET_NUMBERS, is_secret_number, is_virtual_number, normalize_number  # noqa: E402


def archive_bytes() -> bytes:
    parts = sorted(ROOT.glob("WaysGram-Android.zip.part*"), key=lambda p: p.name)
    return b"".join(part.read_bytes() for part in parts)


class ServerSecretNumberChecks(unittest.TestCase):
    def test_secret_prefix_with_digits_is_a_number(self):
        for value in ("+0 000 0000", "+0 0000000", "+000000000", "+111 111111", "+999 0000"):
            normalized = normalize_number(value)
            self.assertTrue(is_virtual_number(normalized), value)
            self.assertTrue(is_secret_number(normalized), value)
        self.assertEqual(normalize_number("+0 000 0000"), "+00000000")

    def test_plain_secret_numbers_still_work(self):
        for value in SECRET_NUMBERS:
            self.assertTrue(is_virtual_number(value), value)
            self.assertTrue(is_secret_number(value), value)

    def test_garbage_is_still_rejected(self):
        for value in ("+222", "+12", "+", "", "not a number"):
            self.assertFalse(is_virtual_number(value), value)
        self.assertFalse(is_secret_number("+447700900123"))


class ClientSecretNumberChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = zipfile.ZipFile(io.BytesIO(archive_bytes()))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, path):
        return self.archive.read(BASE + path).decode("utf-8")

    def test_mapper_accepts_secret_prefix_with_digits(self):
        mapper = self.source("tgnet/WaysGramMapper.java")
        start = mapper.index("public static boolean isSecretNumberDigits")
        block = mapper[start:start + 800]
        self.assertIn("startsWith(prefix)", block, "префикс + цифры должен считаться секретным номером")
        self.assertIn('replaceAll("[^0-9]", "")', block)
        self.assertIn("value.length() > 15", block)

    def test_login_screen_uses_the_shared_secret_rule(self):
        login = self.source("ui/LoginActivity.java")
        self.assertIn("WaysGramMapper.isSecretNumberDigits(code + phone)", login)
        self.assertIn("if (secretNumber) {\n                params.putString(\"ephone\", phoneNumber);", login)

    def test_auth_error_shows_the_server_reason(self):
        transport = self.source("tgnet/WaysGramTransport.java")
        start = transport.index("if (e.status == 400) {", transport.index("authRequest"))
        block = transport[start:start + 300]
        self.assertIn("e.detail", block,
                      "иначе вместо объяснения сервера показывается общий «Invalid phone number»")


if __name__ == "__main__":
    unittest.main(verbosity=2)
