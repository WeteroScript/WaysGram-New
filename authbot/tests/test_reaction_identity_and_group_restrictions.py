"""Реакции по идентичности эмодзи и расширенная маска прав участников.

Оба дефекта пришли из отчёта пользователя:

* клиент отправляет реакцию без U+FE0F («❤»), а вайтлист сервера написан с ним
  («❤️») — точное сравнение строк отвечало «Недопустимый набор реакций», и в
  приложении появлялось «Не удалось подтвердить реакцию»;
* экран «Права участников» показывает переключатели, которым не хватало битов
  (реакции, опросы, ссылки, голосовые, кружочки): они не сохранялись. Маска
  расширена, а старые значения читаются в прежнем смысле.
"""
from __future__ import annotations

import asyncio
import sys
import time
from pathlib import Path

import httpx
import pytest
from cryptography.fernet import Fernet
from fastapi import FastAPI

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import bot as bot_module
import db_split
import messenger_api
from messenger_api import build_messenger_router, init_messenger_storage


class FakeBot:
    def __init__(self) -> None:
        self.token = "test-telegram-bot-token"
        self.messages: list[tuple[int, str]] = []

    async def send_message(self, chat_id: int, text: str) -> None:
        self.messages.append((chat_id, text))


def run(coro):
    return asyncio.run(coro)


@pytest.fixture
def api(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    path = tmp_path / "waysgram.sqlite3"
    monkeypatch.setattr(bot_module, "DATABASE_PATH", path)
    store = bot_module.Store(path)
    store.configure_otp_encryption(Fernet.generate_key().decode("ascii"))
    run(store.init())
    run(bot_module.init_admin_storage())
    run(init_messenger_storage(path))
    fake_bot = FakeBot()
    app = FastAPI()
    app.include_router(
        build_messenger_router(path, store, lambda: fake_bot, bot_module.account_is_banned)
    )
    return path, store, app


def account(api, telegram_id: int, username: str) -> None:
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
            (__import__("hashlib").sha256(token.encode()).hexdigest(), telegram_id, now + 3600, now),
        )


def headers(telegram_id: int) -> dict[str, str]:
    return {"Authorization": f"Bearer token-{telegram_id}"}


async def client_for(app) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


def test_basic_reaction_without_variation_selector_is_accepted(api) -> None:
    """«❤» из палитры клиента — это та же реакция, что «❤️» в вайтлисте."""

    account(api, 98001, "reactor")
    account(api, 98002, "peer")
    _path, _store, app = api

    async def scenario() -> None:
        async with await client_for(app) as client:
            sent = await client.post(
                "/api/messages",
                headers=headers(98001),
                json={"recipient_id": 98002, "body": "reaction identity", "silent": True},
            )
            assert sent.status_code == 201, sent.text
            message_id = sent.json()["id"]
            endpoint = f"/api/messages/{message_id}/reactions"
            payload = {"peer_id": 98002, "reactions": ["❤"]}
            first = await client.put(endpoint, headers=headers(98001), json=payload)
            assert first.status_code == 200, first.text
            # Хранится серверная запись, поэтому читается обратно она же.
            assert first.json()["my_reactions"] == ["❤️"]
            assert first.json()["reactions"] == [{"reaction": "❤️", "count": 1}]
            # Без U+FE0F и с ним — один и тот же набор: повтор ничего не меняет.
            same = await client.put(endpoint, headers=headers(98001), json={**payload, "reactions": ["❤️"]})
            assert same.status_code == 200, same.text
            assert same.json()["status"] == "unchanged"
            # Оттенок кожи тоже не делает реакцию другой.
            toned = await client.put(endpoint, headers=headers(98001), json={**payload, "reactions": ["👍🏽"]})
            assert toned.status_code == 200, toned.text
            assert toned.json()["my_reactions"] == ["👍"]

    run(scenario())


def test_group_send_restrictions_keep_every_switch(api) -> None:
    """Каждый переключатель экрана прав сохраняется и читается обратно."""

    account(api, 98101, "owner")
    _path, _store, app = api

    async def scenario() -> None:
        async with await client_for(app) as client:
            created = await client.post(
                "/api/groups",
                headers=headers(98101),
                json={"title": "Rights", "member_ids": []},
            )
            assert created.status_code == 201, created.text
            group_id = created.json()["id"]
            # Весь диапазон — отправка, реакции и остальные пять флагов прав участников.
            mask = (1 << 16) - 1
            applied = await client.put(
                f"/api/groups/{group_id}/send-permissions",
                headers=headers(98101),
                json={"default_send_restrictions": mask},
            )
            assert applied.status_code == 200, applied.text
            assert applied.json()["default_send_restrictions"] == mask
            group = await client.get(f"/api/groups/{group_id}", headers=headers(98101))
            assert group.status_code == 200
            assert group.json()["default_send_restrictions"] == mask
            # Значения вне маски отклоняются.
            too_big = await client.put(
                f"/api/groups/{group_id}/send-permissions",
                headers=headers(98101),
                json={"default_send_restrictions": 1 << 20},
            )
            assert too_big.status_code == 400

    run(scenario())


def test_legacy_mask_still_blocks_voice_and_round_video(api) -> None:
    """Старая маска (бит 8 — аудио и голосовые, бит 4 — видео и кружочки) расширяется."""

    account(api, 98201, "legacy")
    path, _store, app = api

    async def scenario() -> None:
        async with await client_for(app) as client:
            created = await client.post(
                "/api/groups",
                headers=headers(98201),
                json={"title": "Legacy", "member_ids": []},
            )
            group_id = created.json()["id"]
            with db_split.sqlite_connect(path) as db:
                db.execute(
                    "UPDATE chat_groups SET default_send_restrictions=8,send_restrictions_version=0 WHERE id=?",
                    (group_id,),
                )
                db.commit()
            group = await client.get(f"/api/groups/{group_id}", headers=headers(98201))
            assert group.status_code == 200
            # 8 (аудио) + 64 (голосовые) в текущей раскладке.
            assert group.json()["default_send_restrictions"] == 8 | 64

    run(scenario())


def test_group_reactions_restriction_is_enforced(api) -> None:
    """«Запретить реакции» действительно блокирует установку реакции участником."""

    account(api, 98301, "moderator")
    account(api, 98302, "member")
    _path, _store, app = api

    async def scenario() -> None:
        async with await client_for(app) as client:
            created = await client.post(
                "/api/groups",
                headers=headers(98301),
                json={"title": "No reactions", "member_ids": [98302]},
            )
            group_id = created.json()["id"]
            sent = await client.post(
                f"/api/groups/{group_id}/messages",
                headers=headers(98301),
                json={"body": "hello", "silent": True},
            )
            assert sent.status_code == 201, sent.text
            message_id = sent.json()["id"]
            endpoint = f"/api/groups/{group_id}/messages/{message_id}/reactions"
            before = await client.put(endpoint, headers=headers(98302), json={"reactions": ["👍"]})
            assert before.status_code == 200, before.text
            restricted = await client.put(
                f"/api/groups/{group_id}/send-permissions",
                headers=headers(98301),
                json={"default_send_restrictions": 1024},
            )
            assert restricted.status_code == 200, restricted.text
            # Набор тот же, что уже стоит: проверка прав выполняется до сравнения набора.
            denied = await client.put(endpoint, headers=headers(98302), json={"reactions": ["👍"]})
            assert denied.status_code == 403, denied.text

    run(scenario())


def test_member_default_rights_enforce_invite_pin_rank_and_topics(api) -> None:
    """Default member bans gate invite/pin/rank/topic writes; owners retain admin access."""
    account(api, 98401, "rights_owner")
    account(api, 98402, "rights_member")
    account(api, 98403, "rights_invitee")
    account(api, 98404, "rights_second_invitee")
    _path, _store, app = api

    async def scenario() -> None:
        owner, member = headers(98401), headers(98402)
        async with await client_for(app) as client:
            created = await client.post(
                "/api/groups", headers=owner,
                json={"title": "Member permissions", "member_ids": [98402]},
            )
            assert created.status_code == 201, created.text
            group_id = created.json()["id"]
            root = f"/api/groups/{group_id}"

            # With the switches enabled by default, ordinary members can use the actions.
            invite = await client.post(root + "/members", headers=member, json={"telegram_id": 98403})
            assert invite.status_code == 201, invite.text
            rank = await client.put(root + "/members/98402/rank", headers=member, json={"rank": "Helper"})
            assert rank.status_code == 200, rank.text
            topic_response = await client.post(root + "/topics", headers=member, json={"title": "Member topic"})
            assert topic_response.status_code == 200, topic_response.text
            topic = root + f"/topics/{topic_response.json()['id']}"
            assert (await client.get(root + "/topics", headers=member)).json()["can_manage"] is True

            group_message = await client.post(root + "/messages", headers=member, json={"body": "Group pin"})
            assert group_message.status_code == 201, group_message.text
            group_pin = {"scope": "group", "peer_id": group_id, "message_id": group_message.json()["id"]}
            assert (await client.post("/api/pins", headers=member, json=group_pin)).status_code == 200
            topic_message = await client.post(topic + "/messages", headers=member, json={"body": "Topic pin"})
            assert topic_message.status_code == 201, topic_message.text
            topic_message_id = topic_message.json()["id"]
            assert (await client.put(topic + "/pin", headers=member,
                                     json={"message_id": topic_message_id})).status_code == 200

            # The five upper switches are server-enforced, not just serialized by the client.
            restricted_mask = 2048 | 4096 | 8192 | 16384
            applied = await client.put(root + "/send-permissions", headers=owner,
                                       json={"default_send_restrictions": restricted_mask})
            assert applied.status_code == 200, applied.text
            assert (await client.get(root + "/topics", headers=member)).json()["can_manage"] is False

            denied_invite = await client.post(root + "/members", headers=member, json={"telegram_id": 98404})
            assert denied_invite.status_code == 403, denied_invite.text
            denied_rank = await client.put(root + "/members/98402/rank", headers=member,
                                           json={"rank": "Blocked"})
            assert denied_rank.status_code == 403, denied_rank.text
            denied_topic = await client.post(root + "/topics", headers=member, json={"title": "Blocked topic"})
            assert denied_topic.status_code == 403, denied_topic.text
            assert (await client.patch(topic, headers=member, json={"title": "Blocked rename"})).status_code == 403

            another_group_message = await client.post(root + "/messages", headers=member,
                                                      json={"body": "Restricted group pin"})
            assert another_group_message.status_code == 201, another_group_message.text
            denied_group_pin = await client.post("/api/pins", headers=member, json={
                "scope": "group", "peer_id": group_id, "message_id": another_group_message.json()["id"],
            })
            assert denied_group_pin.status_code == 403, denied_group_pin.text
            another_topic_message = await client.post(topic + "/messages", headers=member,
                                                     json={"body": "Restricted topic pin"})
            assert another_topic_message.status_code == 201, another_topic_message.text
            denied_topic_pin = await client.put(topic + "/pin", headers=member,
                                                json={"message_id": another_topic_message.json()["id"]})
            assert denied_topic_pin.status_code == 403, denied_topic_pin.text

            # Owner privileges are independent of the default restrictions.
            assert (await client.post(root + "/members", headers=owner,
                                      json={"telegram_id": 98404})).status_code == 201
            assert (await client.put(root + "/members/98402/rank", headers=owner,
                                     json={"rank": "Trusted"})).status_code == 200
            assert (await client.post("/api/pins", headers=owner, json={
                "scope": "group", "peer_id": group_id, "message_id": another_group_message.json()["id"],
            })).status_code == 200
            assert (await client.put(topic + "/pin", headers=owner,
                                     json={"message_id": another_topic_message.json()["id"]})).status_code == 200
            assert (await client.post(root + "/topics", headers=owner,
                                      json={"title": "Owner topic"})).status_code == 200

            # Per-member mute masks accept and enforce these upper bits too.
            assert (await client.put(root + "/send-permissions", headers=owner,
                                     json={"default_send_restrictions": 0})).status_code == 200
            muted = await client.put(root + "/members/98402/mute", headers=owner,
                                     json={"until_date": 0, "rights": 8192})
            assert muted.status_code == 200, muted.text
            assert (await client.put(root + "/members/98402/rank", headers=member,
                                     json={"rank": "Muted"})).status_code == 403
            assert (await client.put(root + "/members/98402/mute", headers=owner,
                                     json={"until_date": None})).status_code == 200
            assert (await client.put(root + "/members/98402/rank", headers=member,
                                     json={"rank": "Unmuted"})).status_code == 200

    run(scenario())
