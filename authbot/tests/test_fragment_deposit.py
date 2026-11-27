"""Перевод подарка на аккаунт @fragment и право продавца на консигнационный лот."""

from __future__ import annotations

from pathlib import Path

import pytest
from cryptography.fernet import Fernet
from fastapi import FastAPI

import aiosqlite

import bot as bot_module
import db_split
import fragment_deposit
import messenger_api
from messenger_api import build_messenger_router, init_messenger_storage
from test_messenger_api import FakeBot, add_session, client_for, run

ADMIN = 99001


@pytest.fixture
def env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
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
        build_messenger_router(path, store, lambda: fake_bot, bot_module.account_is_banned, admin_id=ADMIN)
    )

    async def fake_fetch(bot):
        return [
            {"id": "g1", "sticker": {"file_id": "f1", "file_unique_id": "u1", "type": "regular", "width": 512,
                                     "height": 512, "is_animated": False, "is_video": False, "emoji": "🎁",
                                     "mime_type": "image/webp"}, "star_count": 25, "upgrade_star_count": 10},
        ]

    monkeypatch.setattr(messenger_api, "fetch_telegram_gifts", fake_fetch)
    for uid, name in ((ADMIN, "boss"), (101, "buyer"), (102, "friend")):
        run(store.upsert_user(uid, uid))
        run(store.set_verified(uid, True))
        number = run(store.allocate_number(uid))
        run(bot_module.ensure_account(uid, number, name, f"User {uid}"))
        add_session(path, uid, f"s{uid}-session-token")
    return path, app


def headers(uid: int) -> dict[str, str]:
    return {"Authorization": f"Bearer s{uid}-session-token"}


def query(path: Path, sql: str, params: tuple = ()):
    with db_split.sqlite_connect(path) as connection:
        return connection.execute(sql, params).fetchall()


def test_service_account_exists_after_init(env) -> None:
    path, _app = env
    rows = query(path, "SELECT telegram_id,username,display_name FROM accounts WHERE LOWER(username)=?",
                 (fragment_deposit.FRAGMENT_USERNAME,))
    assert rows == [(fragment_deposit.FRAGMENT_TELEGRAM_ID, fragment_deposit.FRAGMENT_USERNAME,
                     fragment_deposit.FRAGMENT_DISPLAY_NAME)]
    # Инициализация повторяется при каждом старте и не создаёт второй аккаунт.
    run(init_messenger_storage(path))
    assert len(query(path, "SELECT telegram_id FROM accounts WHERE LOWER(username)=?",
                     (fragment_deposit.FRAGMENT_USERNAME,))) == 1


def test_deposit_transfers_the_gift_to_fragment_and_records_the_sender(env) -> None:
    path, app = env
    run(bot_module.set_telegram_gift_values("g1", price=40, supply=5))

    async def scenario() -> None:
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            assert bought.status_code == 201
            serial = bought.json()["gift"]["serial_number"]

            sent = await client.post(
                "/api/messages/gift",
                headers=headers(101),
                json={"recipient_id": fragment_deposit.FRAGMENT_TELEGRAM_ID, "serial_number": serial},
            )
            assert sent.status_code == 201
            assert sent.json()["deposited"] is True

            fragment_inventory = (await client.get(
                "/api/me/fragment/gifts", headers=headers(101)
            )).json()["gifts"]
            assert [item["serial_number"] for item in fragment_inventory] == [serial]
            own_inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert own_inventory == []
            fragment_profile = (await client.get(
                f"/api/users/{fragment_deposit.FRAGMENT_TELEGRAM_ID}", headers=headers(101)
            )).json()
            assert [item["serial_number"] for item in fragment_profile["gifts"]] == [serial]

    run(scenario())
    assert query(path, """SELECT serial_number,telegram_id,gift_name FROM fragment_gift_deposits""")
    assert query(path, "SELECT owner_telegram_id FROM user_gifts")[0][0] == fragment_deposit.FRAGMENT_TELEGRAM_ID
    assert query(path, """SELECT event_type FROM gift_transactions ORDER BY id DESC LIMIT 1""")[0][0] == "fragment_deposit"


def test_deposited_gift_is_listed_bought_and_notifies_seller(env) -> None:
    path, app = env
    run(bot_module.set_telegram_gift_values("g1", price=40, supply=5))

    async def scenario() -> None:
        async with aiosqlite.connect(path) as db:
            await db.execute("UPDATE accounts SET grams=1000 WHERE telegram_id=102")
            await db.commit()
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            assert bought.status_code == 201, bought.text
            serial = bought.json()["gift"]["serial_number"]
            deposited = await client.post(
                "/api/messages/gift", headers=headers(101),
                json={"recipient_id": fragment_deposit.FRAGMENT_TELEGRAM_ID,
                      "serial_number": serial},
            )
            assert deposited.status_code == 201, deposited.text

            listed = await client.post(
                f"/api/gifts/{serial}/list", headers=headers(101),
                json={"price_grams": 120},
            )
            assert listed.status_code == 201, listed.text
            own = await client.get("/api/gift-market?mine=true", headers=headers(101))
            assert own.status_code == 200 and own.json()["count"] == 1
            assert own.json()["listings"][0]["serial_number"] == serial
            assert own.json()["listings"][0]["mine"] is True

            purchased = await client.post(
                f"/api/gift-market/{listed.json()['listing_id']}/purchase", headers=headers(102)
            )
            assert purchased.status_code == 201, purchased.text
            assert purchased.json()["seller_id"] == 101 and purchased.json()["buyer_id"] == 102
            assert purchased.json()["price_grams"] == 120
            own_after = await client.get("/api/gift-market?mine=true", headers=headers(101))
            assert own_after.json()["count"] == 0
            assert query(path, "SELECT owner_telegram_id FROM user_gifts WHERE serial_number=?",
                         (serial,))[0][0] == 102

    run(scenario())
    notice = query(
        path,
        """SELECT a.username,m.recipient_telegram_id,m.body FROM messages m
           JOIN accounts a ON a.telegram_id=m.sender_telegram_id
           WHERE m.sender_telegram_id=? AND m.recipient_telegram_id=? ORDER BY m.id DESC LIMIT 1""",
        (fragment_deposit.FRAGMENT_TELEGRAM_ID, 101),
    )
    assert len(notice) == 1 and notice[0][:2] == ("fragment", 101)
    assert "купили на Fragment" in notice[0][2] and "120 Gram" in notice[0][2]


def test_fragment_inventory_filters_out_gifts_not_sent_to_fragment(env) -> None:
    _path, app = env
    run(bot_module.set_telegram_gift_values("g1", price=40, supply=5))

    async def scenario() -> None:
        async with await client_for(app) as client:
            serials = []
            for _ in range(2):
                bought = await client.post(
                    "/api/telegram/gifts/g1/purchase", headers=headers(101), json={}
                )
                assert bought.status_code == 201, bought.text
                serials.append(bought.json()["gift"]["serial_number"])

            sent = await client.post(
                "/api/messages/gift",
                headers=headers(101),
                json={"recipient_id": fragment_deposit.FRAGMENT_TELEGRAM_ID, "serial_number": serials[0]},
            )
            assert sent.status_code == 201, sent.text
            assert sent.json()["deposited"] is True

            fragment = await client.get(
                "/api/me/fragment/gifts?limit=1&offset=0", headers=headers(101)
            )
            assert fragment.status_code == 200, fragment.text
            payload = fragment.json()
            assert [gift["serial_number"] for gift in payload["gifts"]] == [serials[0]]
            assert payload["total"] == 1
            assert payload["has_more"] is False

            # The first gift now belongs to @fragment; the second remains with the sender.
            full = await client.get("/api/me/gifts", headers=headers(101))
            assert full.status_code == 200, full.text
            assert {gift["serial_number"] for gift in full.json()["gifts"]} == {serials[1]}

    run(scenario())


def test_repeated_deposit_of_the_same_gift_is_recorded_once(env) -> None:
    path, app = env
    run(bot_module.set_telegram_gift_values("g1", price=40, supply=5))

    async def scenario() -> None:
        async with await client_for(app) as client:
            serial = (await client.post("/api/telegram/gifts/g1/purchase",
                                        headers=headers(101), json={})).json()["gift"]["serial_number"]
            sent = await client.post(
                "/api/messages/gift",
                headers=headers(101),
                json={"recipient_id": fragment_deposit.FRAGMENT_TELEGRAM_ID, "serial_number": serial},
            )
            assert sent.status_code == 201
            assert sent.json()["deposited"] is True
            # Ownership changed at the first send, so the former sender cannot deposit it again.
            repeated = await client.post(
                "/api/messages/gift",
                headers=headers(101),
                json={"recipient_id": fragment_deposit.FRAGMENT_TELEGRAM_ID, "serial_number": serial},
            )
            assert repeated.status_code == 404

    run(scenario())
    assert query(path, "SELECT COUNT(*) FROM fragment_gift_deposits")[0][0] == 1


def test_deposit_targets_include_only_the_service_account(env) -> None:
    path, _app = env

    async def scenario() -> None:
        async with aiosqlite.connect(path) as db:
            assert await fragment_deposit.is_deposit_target(db, fragment_deposit.FRAGMENT_TELEGRAM_ID)
            assert not await fragment_deposit.is_deposit_target(db, 102)

    run(scenario())


def test_collectible_art_document_ids_are_safe_for_webviews() -> None:
    attributes = {
        "collectible_traits": {
            "model": {"name": "Mirror", "document_id": 9_007_199_254_741_099_123},
            "pattern": {"name": "Pixel", "document_id": 8_888_888_888_888_888_888},
        }
    }
    assert messenger_api._collectible_model_document_id(attributes) == "9007199254741099123"
    assert messenger_api._collectible_pattern_document_id(attributes) == "8888888888888888888"


def test_sending_to_a_person_still_transfers_ownership(env) -> None:
    path, app = env
    run(bot_module.set_telegram_gift_values("g1", price=40, supply=5))

    async def scenario() -> None:
        async with await client_for(app) as client:
            serial = (await client.post("/api/telegram/gifts/g1/purchase",
                                        headers=headers(101), json={})).json()["gift"]["serial_number"]
            sent = await client.post(
                "/api/messages/gift",
                headers=headers(101),
                json={"recipient_id": 102, "serial_number": serial},
            )
            assert sent.status_code == 201
            assert sent.json()["deposited"] is False

    run(scenario())
    assert query(path, "SELECT owner_telegram_id FROM user_gifts")[0][0] == 102
    assert query(path, "SELECT COUNT(*) FROM fragment_gift_deposits")[0][0] == 0
    assert query(path, """SELECT event_type FROM gift_transactions ORDER BY id DESC LIMIT 1""")[0][0] == "chat_send"
