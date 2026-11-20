"""NFT profile showcase (pin + premium emoji status), date-bounded history
clearing, and the push-worker wakeup ordering.

The showcase rides on the existing gift inventory: one owned gift can be
pinned on the profile and another (or the same) worn as the emoji status
next to the owner's name. Both slots must follow ownership, never survive
a transfer, and stay unique per owner.
"""
from __future__ import annotations

import time
from pathlib import Path

import db_split
from test_messenger_api import add_account, add_session, api_env, client_for, run  # noqa: F401  (api_env is a fixture)
from test_star_gifts import env, headers  # noqa: F401  (env is a fixture)

import bot as bot_module


def test_clear_history_by_date_range(api_env) -> None:  # noqa: F811
    path, _, _, app = api_env
    for uid in (88701, 88702):
        add_account(api_env, uid, f"range_{uid}")
        add_session(path, uid, f"range-{uid}")
    now = int(time.time())
    day = 86400
    with db_split.sqlite_connect(path) as db:
        db.executemany(
            "INSERT INTO messages(sender_telegram_id,recipient_telegram_id,body,created_at) VALUES(?,?,?,?)",
            [
                (88701, 88702, "old", now - 10 * day),
                (88701, 88702, "mid", now - 5 * day),
                (88702, 88701, "fresh", now),
            ],
        )
        ids = {
            body: mid
            for mid, body in db.execute(
                "SELECT id,body FROM messages WHERE sender_telegram_id IN (88701,88702)"
            ).fetchall()
        }

    async def scenario() -> None:
        h = lambda n: {"Authorization": f"Bearer range-{n}"}  # noqa: E731
        async with await client_for(app) as client:
            # Only the message inside [min_date, max_date) is removed.
            cleared = await client.delete(
                f"/api/conversations/88702/messages?for=all"
                f"&min_date={now - 7 * day}&max_date={now - day}",
                headers=h(88701),
            )
            assert cleared.status_code == 200, cleared.text
            assert cleared.json()["deleted_count"] == 1
            history = await client.get("/api/messages?with=88702", headers=h(88701))
            assert [m["id"] for m in history.json()["messages"]] == [ids["old"], ids["fresh"]]

            # The same bound for=me hides only the in-range remainder.
            cleared_me = await client.delete(
                f"/api/conversations/88702/messages?for=me&min_date={now - 11 * day}&max_date={now - 9 * day}",
                headers=h(88701),
            )
            assert cleared_me.json()["deleted_count"] == 1
            history = await client.get("/api/messages?with=88702", headers=h(88701))
            assert [m["id"] for m in history.json()["messages"]] == [ids["fresh"]]
            partner = await client.get("/api/messages?with=88701", headers=h(88702))
            assert ids["old"] in [m["id"] for m in partner.json()["messages"]]

            # A range without dates still clears everything (old behaviour),
            # including the message only hidden for the actor.
            cleared_all = await client.delete(
                "/api/conversations/88702/messages?for=all", headers=h(88701)
            )
            assert cleared_all.json()["deleted_count"] == 2

    run(scenario())


async def _buy(client, uid: int, gift_id: str) -> str:
    bought = await client.post(f"/api/telegram/gifts/{gift_id}/purchase", headers=headers(uid), json={})
    assert bought.status_code == 201, bought.text
    return bought.json()["gift"]["serial_number"]


def test_nft_showcase_pin_and_emoji(env) -> None:  # noqa: F811
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            await bot_module.set_telegram_gift_values("g1", price=40, supply=3)
            await bot_module.set_telegram_gift_values("g2", price=50, supply=1)
            first = await _buy(client, 101, "g1")
            second = await _buy(client, 101, "g1")
            bear = await _buy(client, 101, "g2")

            # Pin the second gift: it leads the inventory and flags exactly one.
            pinned = await client.put(
                "/api/me/gifts/showcase", headers=headers(101), json={"pinned_serial": second}
            )
            assert pinned.status_code == 200, pinned.text
            assert pinned.json() == {"ok": True, "pinned_serial": second, "emoji_serial": None}
            mine = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert mine[0]["serial_number"] == second and mine[0]["is_pinned"] is True
            assert sum(1 for g in mine if g["is_pinned"]) == 1

            # Re-pinning moves the slot instead of adding a second pin.
            await client.put(
                "/api/me/gifts/showcase", headers=headers(101), json={"pinned_serial": first}
            )
            mine = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert mine[0]["serial_number"] == first
            assert sum(1 for g in mine if g["is_pinned"]) == 1

            # Wear the bear as the premium emoji status.
            worn = await client.put(
                "/api/me/gifts/showcase", headers=headers(101), json={"emoji_serial": bear}
            )
            assert worn.json()["emoji_serial"] == bear
            showcase = (await client.get("/api/me/gifts/showcase", headers=headers(101))).json()
            assert showcase == {"pinned_serial": first, "emoji_serial": bear}

            # Other users see the pin, the emoji status and the NFT name pill.
            profile = (await client.get("/api/users/101", headers=headers(102))).json()
            assert profile["pinned_gift"]["serial_number"] == first
            assert profile["emoji_status"]["serial_number"] == bear
            assert any(label["text"] == "🧸 NFT" for label in profile["labels"])
            labels = (await client.get("/api/labels?targets=account:101", headers=headers(102))).json()
            assert any(
                label["text"] == "🧸 NFT" for label in labels["labels"]["account:101"]
            )

            # Clearing a slot with null leaves the other one untouched.
            cleared = await client.put(
                "/api/me/gifts/showcase", headers=headers(101), json={"emoji_serial": None}
            )
            assert cleared.json() == {"ok": True, "pinned_serial": first, "emoji_serial": None}
            profile = (await client.get("/api/users/101", headers=headers(102))).json()
            assert profile["emoji_status"] is None
            assert profile["pinned_gift"]["serial_number"] == first

    run(scenario())


def test_nft_showcase_rejects_foreign_and_clears_on_transfer(env) -> None:  # noqa: F811
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            await bot_module.set_telegram_gift_values("g1", price=40, supply=3)
            mine = await _buy(client, 101, "g1")
            foreign = await _buy(client, 102, "g1")

            # Someone else's NFT cannot be pinned or worn.
            for payload in ({"pinned_serial": foreign}, {"emoji_serial": foreign}):
                denied = await client.put(
                    "/api/me/gifts/showcase", headers=headers(101), json=payload
                )
                assert denied.status_code == 404
            unknown = await client.put(
                "/api/me/gifts/showcase", headers=headers(101), json={"pinned_serial": "WG-NOPE"}
            )
            assert unknown.status_code == 404

            await client.put(
                "/api/me/gifts/showcase",
                headers=headers(101),
                json={"pinned_serial": mine, "emoji_serial": mine},
            )
            transferred = await client.post(
                "/api/gifts/transfer",
                headers=headers(101),
                json={"serial_number": mine, "recipient_id": 102},
            )
            assert transferred.status_code == 200, transferred.text

            # The showcase follows ownership: both slots are gone after transfer.
            showcase = (await client.get("/api/me/gifts/showcase", headers=headers(101))).json()
            assert showcase == {"pinned_serial": None, "emoji_serial": None}
            profile = (await client.get("/api/users/101", headers=headers(102))).json()
            assert profile["pinned_gift"] is None and profile["emoji_status"] is None
            # The new owner can pin it right away.
            repinned = await client.put(
                "/api/me/gifts/showcase", headers=headers(102), json={"pinned_serial": mine}
            )
            assert repinned.json()["pinned_serial"] == mine

    run(scenario())
