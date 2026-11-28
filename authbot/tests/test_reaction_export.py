"""Выгрузка официальных документов реакций из подключённой Telethon-сессии.

Анимированные эмодзи реакций Telegram (`application/x-tgsticker`) лежат только на серверах
Telegram, поэтому набор выгружается один раз из авторизованной сессии authbot и попадает в APK.
Здесь проверяется сама выгрузка: манифест по слотам, дедупликация файлов и имена файлов.
"""
import gzip
import json
import unittest
from types import SimpleNamespace

import telegram_mtproto


def document(document_id: int, mime_type: str, payload: bytes) -> SimpleNamespace:
    return SimpleNamespace(id=document_id, dc_id=2, mime_type=mime_type, size=len(payload))


class FakeClient:
    def __init__(self, reactions, payloads) -> None:
        self.reactions = reactions
        self.payloads = payloads
        self.requests = []

    async def __call__(self, request):
        self.requests.append(request)
        return SimpleNamespace(reactions=self.reactions)

    async def download_media(self, parcel, file=None):
        return self.payloads[parcel.id]


def tgs(payload: bytes = b'{"tgs":1,"w":512,"h":512,"fr":60}') -> bytes:
    return gzip.compress(payload)


class ReactionExport(unittest.TestCase):
    def build(self):
        appear = document(5046509860389126442, "application/x-tgsticker", tgs())
        effect = document(5046509860389126443, "application/x-tgsticker", tgs(b'{"tgs":1,"w":512,"h":512,"fr":60,"nm":"effect"}'))
        icon = document(5046509860389126444, "image/webp", b"RIFF\x00\x00\x00\x00WEBPVP8X")
        reactions = [
            SimpleNamespace(
                reaction="🎉", title="Party Popper", inactive=False,
                static_icon=icon, appear_animation=appear, select_animation=appear,
                activate_animation=appear, effect_animation=effect,
                around_animation=effect, center_icon=icon,
            ),
            SimpleNamespace(
                reaction="👍", title="Thumbs Up", inactive=False,
                static_icon=icon, appear_animation=appear, select_animation=appear,
                activate_animation=appear, effect_animation=None,
                around_animation=None, center_icon=icon,
            ),
        ]
        payloads = {
            5046509860389126442: tgs(),
            5046509860389126443: tgs(b'{"tgs":1,"w":512,"h":512,"fr":60,"nm":"effect"}'),
            5046509860389126444: b"RIFF\x00\x00\x00\x00WEBPVP8X",
        }
        return FakeClient(reactions, payloads), appear, effect, icon

    def test_manifest_describes_every_slot_and_deduplicates_files(self):
        client, appear, effect, icon = self.build()
        manifest, files = self.run_export(client)

        self.assertEqual(manifest["count"], 2)
        self.assertEqual([entry["emoji"] for entry in manifest["reactions"]], ["🎉", "👍"])
        assert self.requests_are_reactions(client)

        party = manifest["reactions"][0]["documents"]
        self.assertEqual(party["appear_animation"]["file"], "files/5046509860389126442.tgs")
        self.assertEqual(party["effect_animation"]["file"], "files/5046509860389126443.tgs")
        self.assertEqual(party["static_icon"]["file"], "files/5046509860389126444.webp")
        self.assertEqual(party["static_icon"]["mime_type"], "image/webp")
        self.assertEqual(party["around_animation"]["id"], 5046509860389126443)

        # Один документ стоит в нескольких слотах — файл выгружается один раз.
        self.assertEqual(set(files), {
            "files/5046509860389126442.tgs",
            "files/5046509860389126443.tgs",
            "files/5046509860389126444.webp",
        })
        self.assertEqual(manifest["files"], 3)
        self.assertEqual(manifest["bytes"], sum(len(payload) for payload in files.values()))
        self.assertEqual(files["files/5046509860389126442.tgs"], client.payloads[appear.id])
        self.assertEqual(files["files/5046509860389126443.tgs"], client.payloads[effect.id])
        self.assertEqual(files["files/5046509860389126444.webp"], client.payloads[icon.id])

    def test_thumbs_up_without_effects_keeps_its_drawing(self):
        client, *_ = self.build()
        manifest, files = self.run_export(client)
        thumbs = manifest["reactions"][1]["documents"]
        self.assertNotIn("effect_animation", thumbs)
        self.assertNotIn("around_animation", thumbs)
        self.assertEqual(thumbs["activate_animation"]["file"], "files/5046509860389126442.tgs")
        self.assertEqual(len(files), 3)

    def test_empty_download_is_an_error(self):
        client, *_ = self.build()
        client.payloads[5046509860389126443] = b""
        with self.assertRaises(RuntimeError):
            self.run_export(client)

    def test_archive_is_split_into_uploadable_parts(self):
        payload = bytes(range(256)) * 400  # 102 400 байт
        parts = telegram_mtproto.split_archive(payload, limit=40_000)
        self.assertEqual(len(parts), 3)
        self.assertEqual(b"".join(parts), payload)
        self.assertEqual([len(part) for part in parts], [40_000, 40_000, 22_400])
        self.assertEqual(telegram_mtproto.split_archive(b""), [])
        self.assertEqual(telegram_mtproto.split_archive(b"small"), [b"small"])

    def test_part_names_keep_the_order(self):
        self.assertEqual(telegram_mtproto.archive_part_name(1), "waysgram_reaction_emoji.zip.001")
        self.assertEqual(telegram_mtproto.archive_part_name(12), "waysgram_reaction_emoji.zip.012")

    def run_export(self, client):
        import asyncio

        return asyncio.run(telegram_mtproto.export_reaction_emoji(client))

    def requests_are_reactions(self, client) -> bool:
        return [type(request).__name__ for request in client.requests] == ["GetAvailableReactionsRequest"]


if __name__ == "__main__":
    unittest.main()
