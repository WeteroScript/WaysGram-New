#!/usr/bin/env python3
"""Статические проверки релизной подписи и «тихих» флагов манифеста.

Play Protect ругался на APK по двум причинам, которые видно в исходниках: подпись
публичным ключом Telegram и разрешение на установку APK из чата. Тест закрепляет, что
сборка остаётся на своём ключе WaysGram и без лишних опасных разрешений.
"""
import hashlib
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/"

# Наш PKCS12-ключ, а не публичный release.keystore Telegram (2 623 байта, alias androidkey).
KEYSTORE = BASE + "TMessagesProj/config/release.keystore"
KEYSTORE_SHA256 = "64562f299fd64cb03882ffccf4c817d979a31518d8ff60cdcdc22765885ccbe0"
TELEGRAM_KEYSTORE_BYTES = 2623


class AndroidReleaseSigningChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_the_apk_is_signed_with_our_own_key(self):
        blob = self.archive.read(KEYSTORE)
        self.assertEqual(hashlib.sha256(blob).hexdigest(), KEYSTORE_SHA256)
        self.assertNotEqual(len(blob), TELEGRAM_KEYSTORE_BYTES)
        # DER SEQUENCE: PKCS12, а не JKS — тип хранилища объявлен в build.gradle.
        self.assertEqual(blob[:2], b"\x30\x82")

        properties = self.source("gradle.properties")
        self.assertIn("RELEASE_KEY_ALIAS=waysgram", properties)
        self.assertNotIn("RELEASE_KEY_ALIAS=androidkey", properties)
        self.assertNotIn("RELEASE_STORE_PASSWORD=android\n", properties)
        self.assertNotIn("RELEASE_KEY_PASSWORD=android\n", properties)

        gradle = self.source("TMessagesProj_App/build.gradle")
        self.assertIn("storeFile file(\"../TMessagesProj/config/release.keystore\")", gradle)
        self.assertEqual(gradle.count("storeType 'PKCS12'"), 2)

    def test_the_keystore_carries_the_waysgram_certificate(self):
        try:
            from cryptography.hazmat.primitives.serialization import pkcs12
        except ImportError:
            self.skipTest("cryptography не установлена")
        properties = self.source("gradle.properties")
        password = ""
        for line in properties.splitlines():
            if line.startswith("RELEASE_STORE_PASSWORD="):
                password = line.split("=", 1)[1].strip()
        self.assertTrue(password)
        key, cert, _ = pkcs12.load_key_and_certificates(
            self.archive.read(KEYSTORE), password.encode()
        )
        self.assertIsNotNone(key)
        self.assertEqual(cert.subject.rfc4514_string(), "CN=WaysGram,OU=Android release,O=WaysGram,C=PL")
        self.assertEqual(key.key_size, 4096)
        self.assertGreater(cert.not_valid_after_utc.year, 2050)

    def test_the_release_manifest_keeps_quiet_permissions_out(self):
        manifest = self.source("TMessagesProj/src/main/AndroidManifest.xml")
        # Установка APK из чата — главный флаг «дроппера» для Play Protect.
        self.assertNotIn('android.permission.REQUEST_INSTALL_PACKAGES" />', manifest)
        self.assertIn('android:allowBackup="false"', manifest)
        self.assertNotIn('android:allowBackup="true"', manifest)
        # Cleartext остаётся: он нужен встроенному браузеру для http:// ссылок.
        self.assertIn('android:usesCleartextTraffic="true"', manifest)

    def test_the_build_refuses_an_apk_signed_with_another_key(self):
        workflow = (ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
        self.assertIn('grep -Fq "CN=WaysGram"', workflow)
        self.assertIn("APK is not signed with the WaysGram release key", workflow)


if __name__ == "__main__":
    unittest.main()
