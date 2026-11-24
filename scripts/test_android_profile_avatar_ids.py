#!/usr/bin/env python3
"""Аватар канала в профиле: отрицательные volume_id должны находить файл.

`WaysGramMapper.avatarLocation()` кладёт в `TL_fileLocationToBeDeprecated.volume_id`
значение `-photoId`, а `WaysGramMedia.register()` запоминает файл по положительному id.
Поэтому `upload.getFile` для большого аватара приходил с отрицательным id, поиск в
`prefs` не находил медиа и картинка в профиле канала оставалась пустой.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/tgnet/"


class ProfileAvatarIdChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_media_lookup_accepts_negative_ids(self):
        media = self.source("WaysGramMedia.java")
        start = media.index("    public static String mediaIdFor(long id) {")
        end = media.index("\n    }", start)
        block = media[start:end]
        self.assertIn('prefs().getString("m_" + id, null)', block)
        self.assertIn('prefs().getString("m_" + (-id), null)', block)
        self.assertIn("if (found == null && id < 0)", block)

    def test_avatars_are_built_with_negative_volume_ids(self):
        mapper = self.source("WaysGramMapper.java")
        self.assertIn("avatarLocation(-photoId, 'a')", mapper)
        self.assertIn("avatarLocation(-photoId, 'c')", mapper)
        start = mapper.index("    private static TLRPC.FileLocation avatarLocation(")
        end = mapper.index("\n    }", start)
        self.assertIn("location.volume_id = volumeId;", mapper[start:end])

    def test_upload_get_file_resolves_photo_locations_through_the_lookup(self):
        media = self.source("WaysGramMedia.java")
        start = media.index("    public static TLObject getFile(")
        end = media.index("\n    }", start)
        block = media[start:end]
        self.assertIn("mediaIdFor(id)", block)
        self.assertIn("TLRPC.TL_inputPhotoFileLocation", block)
        self.assertIn("TLRPC.TL_inputPeerPhotoFileLocation", block)


if __name__ == "__main__":
    unittest.main()
