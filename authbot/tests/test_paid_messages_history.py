"""Платные сообщения по схеме Telegram: возврат за недоставку и история платежей.

Схема: звёзды списываются только после подтверждения цены, история показывает и
списания, и возвраты, а сообщение, которое владелец канала не открыл за неделю,
возвращает звёзды.
"""

import asyncio
import time

import aiosqlite

from messenger_api import CHANNEL_INBOX_REFUND_AFTER
from test_messenger_api import client_for, run
from test_star_gifts import env, headers  # noqa: F401  (env is a pytest fixture)


def paid_history_entry(transactions, kind):
    for item in transactions:
        if item["kind"] == kind:
            return item
    return None


def test_paid_channel_message_is_history_and_refund_on_undelivered(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with aiosqlite.connect(path) as db:
            await db.execute("UPDATE accounts SET stars=50 WHERE telegram_id=102")
            await db.commit()
        async with await client_for(app) as client:
            owner, reader = headers(101), headers(102)
            channel = await client.post("/api/channels", headers=owner,
                                        json={"title": "Paid inbox", "username": "paid_inbox", "is_public": True})
            channel_id = channel.json()["id"]
            inbox = f"/api/channels/{channel_id}/inbox"
            assert (await client.put(inbox + "/settings", headers=owner,
                                     json={"enabled": True, "fee_stars": 6})).status_code == 200

            # 1. Отправка без подтверждения цены не списывает звёзды.
            key = {**reader, "Idempotency-Key": "paid-history-0001"}
            unpaid = await client.post(inbox + "/me", headers=key, json={"body": "Hello"})
            assert unpaid.status_code == 402 and unpaid.json()["detail"]["stars"] == 6
            before = (await client.get("/api/me", headers=reader)).json()["stars"]

            sent = await client.post(inbox + "/me", headers=key,
                                     json={"body": "Hello", "approved_fee_stars": 6})
            assert sent.status_code == 200 and sent.json()["fee_stars"] == 6
            after = (await client.get("/api/me", headers=reader)).json()["stars"]
            assert before - after == 6

            # 2. История платежей: списание видно отправителю и получателю.
            mine = (await client.get("/api/me/paid-messages", headers=reader)).json()
            entry = paid_history_entry(mine["transactions"], "channel")
            assert entry is not None and entry["title"] == "Paid inbox"
            assert entry["stars"] == 6 and entry["refunded"] is False
            assert mine["spent_stars"] == 6 and mine["refunded_stars"] == 0
            assert mine["refund_after_seconds"] == CHANNEL_INBOX_REFUND_AFTER

            # 3. Владелец открыл диалог — сообщение доставлено, возврата не будет.
            assert (await client.get(inbox + "/threads/102", headers=owner)).status_code == 200
            async with aiosqlite.connect(path) as db:
                await db.execute("UPDATE channel_inbox_messages SET created_at=? WHERE direction='incoming'",
                                 (int(time.time()) - CHANNEL_INBOX_REFUND_AFTER - 60,))
                await db.commit()
            assert (await client.get(inbox + "/me", headers=reader)).json()["messages"]
            stable = (await client.get("/api/me/paid-messages", headers=reader)).json()
            assert stable["refunded_stars"] == 0

            # 4. Новое сообщение, которое владелец не открыл, возвращает звёзды.
            await client.put(inbox + "/settings", headers=owner, json={"enabled": True, "fee_stars": 4})
            second = {**reader, "Idempotency-Key": "paid-history-0002"}
            assert (await client.post(inbox + "/me", headers=second,
                                      json={"body": "Nobody reads", "approved_fee_stars": 4})).status_code == 200
            async with aiosqlite.connect(path) as db:
                await db.execute("UPDATE channel_inbox_messages SET created_at=? WHERE refunded=0 AND direction='incoming'",
                                 (int(time.time()) - CHANNEL_INBOX_REFUND_AFTER - 60,))
                await db.commit()
            refunded = (await client.get("/api/me/paid-messages", headers=reader)).json()
            refund = paid_history_entry(refunded["transactions"], "refund")
            assert refund is not None and refund["stars"] == 4 and refund["refunded"] is True
            assert refunded["spent_stars"] == 10 and refunded["refunded_stars"] == 4
            assert (await client.get("/api/me", headers=reader)).json()["stars"] == before - 6
            # Повторный запрос не возвращает те же звёзды второй раз.
            again = (await client.get("/api/me/paid-messages", headers=reader)).json()
            assert again["total"] == refunded["total"]

    run(scenario())


def test_chat_theme_is_stored_and_channel_stars_stay_invisible(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with aiosqlite.connect(path) as db:
            await db.execute("UPDATE accounts SET stars=50 WHERE telegram_id=102")
            await db.commit()
        async with await client_for(app) as client:
            owner, reader = headers(101), headers(102)
            channel = await client.post("/api/channels", headers=owner,
                                        json={"title": "Themed", "username": "themed_chat", "is_public": True})
            channel_id = channel.json()["id"]

            # Тему канала ставит владелец; подписчик её не меняет.
            empty = await client.get(f"/api/chats/{channel_id}/theme", headers=owner)
            assert empty.status_code == 200 and empty.json()["emoticon"] is None
            assert (await client.put(f"/api/chats/{channel_id}/theme", headers=reader,
                                     json={"emoticon": "🌊"})).status_code == 403
            saved = await client.put(f"/api/chats/{channel_id}/theme", headers=owner, json={"emoticon": "🌊"})
            assert saved.status_code == 200 and saved.json()["emoticon"] == "🌊"
            assert (await client.get(f"/api/chats/{channel_id}/theme", headers=reader)).json()["emoticon"] == "🌊"
            assert (await client.put(f"/api/chats/{channel_id}/theme", headers=owner,
                                     json={"emoticon": "x" * 33})).status_code == 400
            assert (await client.put(f"/api/chats/{channel_id}/theme", headers=owner, json={})).status_code == 200
            assert (await client.get(f"/api/chats/{channel_id}/theme", headers=owner)).json()["emoticon"] is None

            # Баланс звёзд канала копится, но нигде в ответах не показывается.
            inbox = f"/api/channels/{channel_id}/inbox"
            owner_before = (await client.get("/api/me", headers=owner)).json()["stars"]
            assert (await client.put(inbox + "/settings", headers=owner,
                                     json={"enabled": True, "fee_stars": 6})).status_code == 200
            assert (await client.post(inbox + "/me", headers={**reader, "Idempotency-Key": "theme-paid-inbox-0001"},
                                      json={"body": "Paid", "approved_fee_stars": 6})).status_code == 200
            payload = (await client.get(f"/api/channels/{channel_id}", headers=owner)).json()
            assert "chat_stars" not in payload and "stars_balance" not in payload
            assert "chat_stars" not in (await client.get("/api/channels", headers=owner)).json()
        async with aiosqlite.connect(path) as db:
            cur = await db.execute("SELECT stars FROM chat_stars WHERE chat_id=?", (channel_id,))
            assert (await cur.fetchone())[0] == 6
            # В личный кошелёк владельца ничего не начислено.
            cur = await db.execute("SELECT stars FROM accounts WHERE telegram_id=101")
            assert (await cur.fetchone())[0] == owner_before

    run(scenario())


def test_paid_direct_message_history_and_pagination(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with aiosqlite.connect(path) as db:
            await db.execute("UPDATE accounts SET stars=50 WHERE telegram_id=101")
            await db.commit()
        async with await client_for(app) as client:
            assert (await client.put("/api/me/message-fee", headers=headers(102),
                                     json={"stars": 5, "exempt_users": []})).status_code == 200
            for index in range(3):
                response = await client.post(
                    "/api/messages", headers={**headers(101), "Idempotency-Key": f"paid-dm-history-000{index}"},
                    json={"recipient_id": 102, "body": f"paid {index}", "approved_fee_stars": 5},
                )
                assert response.status_code == 201, response.text

            first = (await client.get("/api/me/paid-messages", headers=headers(101))).json()
            assert [item["kind"] for item in first["transactions"]] == ["direct"] * 3
            assert first["transactions"][0]["stars"] == 5
            assert first["transactions"][0]["title"] == "User 102"
            assert first["spent_stars"] == 15 and first["total"] == 3

            page = (await client.get("/api/me/paid-messages?limit=2", headers=headers(101))).json()
            assert len(page["transactions"]) == 2 and page["next_before_id"] > 0
            rest = (await client.get(
                f"/api/me/paid-messages?limit=2&before_id={page['next_before_id']}",
                headers=headers(101))).json()
            assert len(rest["transactions"]) == 1
            ids = {item["id"] for item in page["transactions"]} | {item["id"] for item in rest["transactions"]}
            assert len(ids) == 3

            # У получателя та же лента, но только доход; расход у него нулевой.
            other = (await client.get("/api/me/paid-messages", headers=headers(102))).json()
            assert other["spent_stars"] == 0 and other["earned_stars"] == 15
            assert all(item["stars"] > 0 for item in other["transactions"])

    run(scenario())
