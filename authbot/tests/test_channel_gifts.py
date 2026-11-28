"""Подарки каналу: подарок за звёзды остаётся у канала и виден в его профиле.

Как в Telegram: отправитель платит звёзды, подарок попадает в список подарков канала,
а профиль канала показывает их количество (`stargifts_count`) и возможность отправки
(`stargifts_available`).
"""

import pytest

import bot as bot_module
from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


def test_channel_gift_is_paid_and_stored_on_the_channel(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            await bot_module.set_telegram_gift_values("g1", price=40, supply=5)
            created = await client.post(
                "/api/channels", headers=headers(101), json={"username": "giftchannel", "title": "Gifts"}
            )
            assert created.status_code == 201
            channel_id = created.json()["id"]

            sent = await client.post(
                f"/api/channels/{channel_id}/gifts",
                headers=headers(102),
                json={"telegram_gift_id": "g1", "message": "С днём рождения"},
            )
            assert sent.status_code == 201, sent.text
            body = sent.json()
            assert body["price_stars"] == 40
            assert body["stars"] == 5000 - 40
            assert body["gift"]["gift_name"]
            assert body["gift"]["sender_id"] == 102

            card = (await client.get(f"/api/channels/{channel_id}", headers=headers(102))).json()
            assert card["stargifts_count"] == 1
            assert card["stargifts_available"] is True

            listed = (await client.get(f"/api/channels/{channel_id}/gifts", headers=headers(101))).json()
            assert listed["count"] == 1
            gift = listed["gifts"][0]
            assert gift["telegram_gift_id"] == "g1"
            assert gift["sender_id"] == 102
            assert gift["message"] == "С днём рождения"
            assert gift["sender_name"]

            # Подарок канала не попадает в личный инвентарь отправителя.
            mine = (await client.get("/api/me/gifts", headers=headers(102))).json()
            assert mine["gifts"] == []

            anonymous = await client.post(
                f"/api/channels/{channel_id}/gifts",
                headers=headers(102),
                json={"telegram_gift_id": "g1", "hide_name": True},
            )
            assert anonymous.status_code == 201
            hidden = (await client.get(f"/api/channels/{channel_id}/gifts", headers=headers(102))).json()
            assert hidden["count"] == 2
            assert hidden["gifts"][0]["sender_id"] == 0
            assert hidden["gifts"][0]["hide_name"] is True

            # Пустой/битый идентификатор подарка не должен стоить звёзд.
            assert (
                await client.post(
                    f"/api/channels/{channel_id}/gifts", headers=headers(102), json={"telegram_gift_id": ""}
                )
            ).status_code == 400
            assert (
                await client.post(
                    f"/api/channels/{channel_id}/gifts", headers=headers(102), json={"telegram_gift_id": "nope"}
                )
            ).status_code == 409

    run(scenario())


def test_private_channel_gifts_need_access_and_stars(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            await bot_module.set_telegram_gift_values("g1", price=40, supply=5)
            private = await client.post("/api/channels", headers=headers(101), json={"title": "Closed", "is_public": False})
            assert private.status_code == 201
            channel_id = private.json()["id"]
            assert (
                await client.post(
                    f"/api/channels/{channel_id}/gifts", headers=headers(102), json={"telegram_gift_id": "g1"}
                )
            ).status_code == 403
            assert (await client.get(f"/api/channels/{channel_id}/gifts", headers=headers(102))).status_code == 403

            # Без звёзд подарок не уходит и сток не тратится.
            import sqlite3
            import db_split

            conn = db_split.sqlite_connect(path)
            try:
                conn.execute("UPDATE accounts SET stars=10 WHERE telegram_id=101")
                conn.commit()
            finally:
                conn.close()
            poor = await client.post(
                f"/api/channels/{channel_id}/gifts", headers=headers(101), json={"telegram_gift_id": "g1"}
            )
            assert poor.status_code == 409
            assert "Stars" in poor.json()["detail"]
            conn = db_split.sqlite_connect(path)
            try:
                sold = conn.execute("SELECT sold FROM telegram_gift_prices WHERE gift_id='g1'").fetchone()[0]
            finally:
                conn.close()
            assert sold == 0

    run(scenario())


def test_conversations_include_the_worn_gift(env) -> None:
    """Значок подарка в списке чатов не должен ждать открытия профиля."""
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            await bot_module.set_telegram_gift_values("g1", price=40, supply=5)
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(102), json={})
            assert bought.status_code == 201
            serial = bought.json()["gift"]["serial_number"]

            await client.post("/api/messages", headers=headers(102), json={"recipient_id": 101, "body": "hi"})
            plain = (await client.get("/api/conversations", headers=headers(101))).json()["conversations"]
            peer = next(item for item in plain if item["id"] == 102)
            assert "worn_gift" not in peer

            # «Носить» можно только коллекционный подарок; для списка чатов важен сам
            # факт profile_worn, поэтому ставим его напрямую в хранилище.
            import db_split

            conn = db_split.sqlite_connect(path)
            try:
                conn.execute("UPDATE user_gifts SET profile_worn=1 WHERE serial_number=?", (serial,))
                conn.commit()
            finally:
                conn.close()
            with_gift = (await client.get("/api/conversations", headers=headers(101))).json()["conversations"]
            peer = next(item for item in with_gift if item["id"] == 102)
            assert peer["worn_gift"]["serial_number"] == serial
            assert peer["worn_gift"]["telegram_gift_id"] == "g1"
            assert isinstance(peer["worn_gift"]["traits"], dict)

    run(scenario())
