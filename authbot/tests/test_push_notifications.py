"""Плашки push-сообщений: имя вместо номера и правильный аккаунт в данных.

Скриншот из шторки: две одинаковые строки «+00000000» от аккаунтов, заведённых
администратором (в `display_name` лежал номер телефона), и ни одного признака, в каком
чате сообщение. Заголовок push берётся из `display_name`, поэтому телефон уезжал прямо
в уведомление, а клиент не мог открыть нужный чат.
"""
from __future__ import annotations

import asyncio
import hashlib
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import db_split
import messenger_api
from test_messenger_api import add_account, add_session, api_env, client_for, run


# ------------------------------------------------------------------ помощники


def push_device(path: Path, telegram_id: int, session_token: str, token_value: str) -> None:
    with db_split.sqlite_connect(path) as db:
        db.execute(
            "INSERT INTO push_devices(token_hash,token_value,telegram_id,session_token_hash,"
            "created_at,updated_at) VALUES(?,?,?,?,?,?)",
            (hashlib.sha256(token_value.encode()).hexdigest(), token_value, telegram_id,
             hashlib.sha256(session_token.encode()).hexdigest(), 1, 1),
        )
        db.commit()


def phone_named_account(api_env, telegram_id: int, username: str = "") -> None:
    """Аккаунт, как его заводил администратор: номер вместо имени."""
    add_account(api_env, telegram_id, username)
    path = api_env[0]
    with db_split.sqlite_connect(path) as db:
        db.execute("UPDATE accounts SET display_name=? WHERE telegram_id=?", ("+00000000", telegram_id))
        db.commit()
    add_session(path, telegram_id, f"phone-session-{telegram_id}")


def capture_push(monkeypatch) -> list[dict]:
    """Перехватывает тело запроса к FCM, не отправляя его."""
    monkeypatch.setattr(messenger_api, "_firebase_service_account", lambda: {
        "project_id": "test", "token_uri": "https://oauth.test/token",
    })
    monkeypatch.setattr(messenger_api, "_firebase_assertion", lambda credentials, now: "fake")
    sent: list[dict] = []
    original_post = httpx.AsyncClient.post

    async def fake_post(self, url, *args, **kwargs):
        if str(url) == "https://oauth.test/token":
            return httpx.Response(200, json={"access_token": "fake", "expires_in": 3600},
                                  request=httpx.Request("POST", str(url)))
        if str(url).startswith("https://fcm.googleapis.com/"):
            sent.append(kwargs["json"]["message"])
            return httpx.Response(200, json={}, request=httpx.Request("POST", str(url)))
        return await original_post(self, url, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    return sent


# ------------------------------------------------------------- заголовки


def test_phone_is_not_a_name() -> None:
    assert messenger_api.looks_like_phone_title("+00000000")
    assert messenger_api.looks_like_phone_title("+0 (000) 000-00")
    assert not messenger_api.looks_like_phone_title("Tester Group")
    assert not messenger_api.looks_like_phone_title("@tester")


def test_sender_title_prefers_a_real_name() -> None:
    assert messenger_api.sender_push_title(
        {"display_name": "+00000000", "username": "", "telegram_id": 900001}) == "User 900001"
    assert messenger_api.sender_push_title(
        {"display_name": "+00000000", "username": "tester", "telegram_id": 9}) == "@tester"
    assert messenger_api.sender_push_title(
        {"display_name": "Tester", "username": "tester", "telegram_id": 9}) == "Tester"
    assert messenger_api.sender_push_title({"display_name": "", "username": "", "telegram_id": 0}) \
        == "Новое сообщение"


def test_push_title_replaces_a_bare_number() -> None:
    assert messenger_api.push_title("+00000000") == "Новое сообщение"
    assert messenger_api.push_title("+00000000", "WaysGram") == "WaysGram"
    assert messenger_api.push_title("Tester") == "Tester"


# --------------------------------------------------------------- доставка


def test_channel_push_names_the_author_and_the_device_account(api_env, monkeypatch) -> None:
    """Публикация в канале доставляет push сразу: заголовок — имя, не номер.

    У канала push уходит из самого запроса публикации, поэтому проверка не зависит
    от фонового воркера очереди.
    """
    path = api_env[0]
    phone_named_account(api_env, 98901)
    add_account(api_env, 98902, "subscriber")
    add_session(path, 98902, "subscriber-session")
    push_device(path, 98902, "subscriber-session", "device-token-98902")
    sent = capture_push(monkeypatch)

    async def scenario() -> None:
        async with await client_for(api_env[3]) as client:
            owner = {"Authorization": "Bearer phone-session-98901"}
            channel = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Новости", "is_public": False},
            )
            assert channel.status_code == 201, channel.text
            channel_id = channel.json()["id"]
            invited = await client.post(
                f"/api/channels/{channel_id}/members", headers=owner,
                json={"user_ids": [98902]},
            )
            assert invited.status_code == 201, invited.text
            published = await client.post(
                f"/api/channels/{channel_id}/messages", headers=owner,
                json={"body": "Привет"},
            )
            assert published.status_code == 201, published.text

    run(scenario())

    assert sent, "push не дошёл до FCM"
    data = sent[0]["data"]
    assert data["scope"] == "channel"
    assert data["chat_id"] == "1"
    # Номер вместо имени в шторке больше не появляется.
    assert "00000000" not in data["title"]
    assert data["title"] == "User 98901"
    # Клиент по этому полю открывает чат в правильном аккаунте.
    assert data["account_id"] == "98902"
    assert data["body"] == "Привет"


def test_admin_created_account_keeps_the_number_out_of_the_name(api_env, monkeypatch) -> None:
    """Аккаунт, заведённый администратором, больше не получает номер в имя."""
    import bot as bot_module

    path = api_env[0]
    from virtual_numbers import is_virtual_number

    number = "+00000001"
    assert is_virtual_number(number)
    account_id = run(bot_module.admin_create_account(number, 0, 0))
    with db_split.sqlite_connect(path) as db:
        row = db.execute("SELECT display_name FROM accounts WHERE telegram_id=?",
                         (account_id,)).fetchone()
    assert row is not None
    assert row[0] == ""
