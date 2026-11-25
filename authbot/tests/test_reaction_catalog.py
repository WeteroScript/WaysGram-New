"""Вайтлист реакций совпадает с палитрой приложения, комментарии включаются сразу.

Два дефекта из отчёта:

* серверный список реакций был записан руками и отставал от клиентского каталога:
  👌 🥱 🥴 😴 🦄 💊 🌭 🍓 🎃 🎊 ✨ приложение предлагало, а сервер отвечал 400
  «Недопустимый набор реакций» — в чате это «Не удалось подтвердить реакцию»;
* у нового канала не было связанной группы обсуждений, поэтому у постов не
  появлялась кнопка комментариев вообще.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import re
import sys
import time
import zipfile
from io import BytesIO
from pathlib import Path

import httpx
import pytest
from cryptography.fernet import Fernet
from fastapi import FastAPI

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "assets" / "reactions.json"
JAVA = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"

sys.path.insert(0, str(ROOT))

import bot as bot_module
import db_split
import messenger_api
from messenger_api import build_messenger_router, init_messenger_storage


class FakeBot:
    def __init__(self) -> None:
        self.token = "test-telegram-bot-token"

    async def send_message(self, chat_id: int, text: str) -> None:
        pass


def run(coro):
    return asyncio.run(coro)


def identity(value: str) -> str:
    return "".join(c for c in value if c != "\ufe0f" and not 0x1F3FB <= ord(c) < 0x1F400)


def client_catalog() -> list[str]:
    parts = sorted(ROOT.parent.glob("WaysGram-Android.zip.part*"), key=lambda path: path.name)
    if not parts:
        pytest.skip("архив исходников не найден рядом с репозиторием")
    archive = zipfile.ZipFile(BytesIO(b"".join(part.read_bytes() for part in parts)))
    palette_src = archive.read(JAVA + "tgnet/WaysGramReactions.java").decode("utf-8")
    block = palette_src.split("private static final String[][] REACTIONS = {", 1)[1].split("};", 1)[0]
    palette = [item.replace("\\u200d", "\u200d") for item in re.findall(r'\{"([^"]+)"', block)]
    assets_src = archive.read(JAVA + "tgnet/WaysGramReactionAssets.java").decode("utf-8")
    match = re.search(r"EMOJI = \{(.*?)\};", assets_src, re.S)
    assets = [item.replace("\\u200d", "\u200d") for item in re.findall(r'"([^"]+)"', match.group(1))]
    return palette + assets


def test_catalog_file_matches_the_client() -> None:
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    server_keys = {identity(value) for value in data["catalog"]}
    for emoji in client_catalog():
        assert identity(emoji) in server_keys, f"{emoji} нет в серверном каталоге"


def test_basic_set_is_a_subset() -> None:
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    catalog = {identity(value) for value in data["catalog"]}
    for emoji in data["basic"]:
        assert identity(emoji) in catalog


def test_previously_rejected_reactions_are_allowed() -> None:
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    catalog = {identity(value) for value in data["catalog"]}
    for emoji in ("👌", "🥱", "🥴", "😴", "🦄", "💊", "🌭", "🍓", "🎃", "🎊", "✨"):
        assert identity(emoji) in catalog, f"{emoji} всё ещё отклоняется"


@pytest.fixture
def api(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    path = tmp_path / "waysgram.sqlite3"
    monkeypatch.setattr(bot_module, "DATABASE_PATH", path)
    store = bot_module.Store(path)
    store.configure_otp_encryption(Fernet.generate_key().decode("ascii"))
    run(store.init())
    run(bot_module.init_admin_storage())
    run(init_messenger_storage(path))
    app = FastAPI()
    app.include_router(
        build_messenger_router(path, store, lambda: FakeBot(), bot_module.account_is_banned)
    )
    return path, store, app


def account(api, telegram_id: int, username: str) -> dict[str, str]:
    path, store, _app = api
    run(store.upsert_user(telegram_id, telegram_id))
    run(store.set_verified(telegram_id, True))
    number = run(store.allocate_number(telegram_id))
    run(bot_module.ensure_account(telegram_id, number, username, f"User {telegram_id}"))
    token = f"token-{telegram_id}"
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        db.execute(
            "INSERT INTO api_sessions(token_hash,telegram_id,expires_at,created_at) VALUES(?,?,?,?)",
            (hashlib.sha256(token.encode()).hexdigest(), telegram_id, now + 3600, now),
        )
    return {"Authorization": f"Bearer {token}"}


async def client_for(app) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


def test_every_catalog_reaction_is_accepted_by_the_channel_route(api) -> None:
    """Каждая реакция палитры проходит сервер, а не только «обычные» семь."""

    owner = account(api, 98601, "owner")
    _path, _store, app = api

    async def scenario() -> None:
        async with await client_for(app) as client:
            created = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Reactions", "description": "", "is_public": True, "username": "wgreactions"},
            )
            assert created.status_code == 201, created.text
            channel_id = created.json()["id"]
            post = await client.post(
                f"/api/channels/{channel_id}/messages", headers=owner,
                json={"body": "post", "silent": True},
            )
            assert post.status_code == 201, post.text
            message_id = post.json()["id"]
            url = f"/api/channels/{channel_id}/messages/{message_id}/reactions/v2"
            data = json.loads(CATALOG.read_text(encoding="utf-8"))
            rejected = []
            for emoji in data["catalog"]:
                response = await client.put(url, headers=owner, json={"reactions": [emoji], "big": False})
                if response.status_code != 200:
                    rejected.append((emoji, response.status_code, response.text[:60]))
            assert rejected == []

    run(scenario())


def test_new_channel_gets_a_discussion_group(api) -> None:
    """Комментарии включены сразу: у поста есть группа обсуждений и счётчик."""

    owner = account(api, 98602, "owner2")
    subscriber = account(api, 98603, "sub")
    _path, _store, app = api

    async def scenario() -> None:
        async with await client_for(app) as client:
            created = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Comments", "description": "", "is_public": True, "username": "wgcomments"},
            )
            assert created.status_code == 201, created.text
            body = created.json()
            channel_id = body["id"]
            group_id = body.get("discussion_group_id")
            assert isinstance(group_id, int) and group_id > 0
            detail = await client.get(f"/api/channels/{channel_id}", headers=owner)
            assert detail.json()["discussion_group_id"] == group_id
            post = await client.post(
                f"/api/channels/{channel_id}/messages", headers=owner,
                json={"body": "пост", "silent": True},
            )
            message_id = post.json()["id"]
            listing = await client.get(f"/api/channels/{channel_id}/messages", headers=subscriber, params={"limit": 5})
            item = [m for m in listing.json()["messages"] if m["id"] == message_id][0]
            assert item["discussion_group_id"] == group_id
            assert item["comments_count"] == 0
            # Подписчик может комментировать без ручного вступления в группу.
            comment = await client.post(
                f"/api/channels/{channel_id}/messages/{message_id}/comments",
                headers=subscriber, json={"body": "первый"},
            )
            assert comment.status_code in (200, 201), comment.text
            listing = await client.get(f"/api/channels/{channel_id}/messages", headers=owner, params={"limit": 5})
            item = [m for m in listing.json()["messages"] if m["id"] == message_id][0]
            assert item["comments_count"] == 1

    run(scenario())


def test_channel_without_comments_stays_without_group(api) -> None:
    owner = account(api, 98604, "owner3")
    _path, _store, app = api

    async def scenario() -> None:
        async with await client_for(app) as client:
            created = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Silent", "description": "", "is_public": True,
                      "username": "wgsilent", "comments": False},
            )
            assert created.status_code == 201, created.text
            assert "discussion_group_id" not in created.json()
            detail = await client.get(f"/api/channels/{created.json()['id']}", headers=owner)
            assert detail.json()["discussion_group_id"] is None

    run(scenario())
