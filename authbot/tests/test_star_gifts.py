import asyncio
import json
import time
from pathlib import Path

import aiosqlite
import httpx
import pytest
from cryptography.fernet import Fernet
from fastapi import FastAPI

import bot as bot_module
import db_split
import messenger_api
import waysgram_gifts
from messenger_api import build_messenger_router, init_messenger_storage
from test_messenger_api import FakeBot, add_account, add_session, client_for, run

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
            {"id": "g2", "sticker": {"file_id": "f2", "file_unique_id": "u2", "type": "regular", "width": 512,
                                     "height": 512, "is_animated": False, "is_video": False, "emoji": "🧸",
                                     "mime_type": "image/webp"}, "star_count": 50},
        ]

    monkeypatch.setattr(messenger_api, "fetch_telegram_gifts", fake_fetch)
    for uid, name in ((ADMIN, "boss"), (101, "buyer"), (102, "friend")):
        number = run(store.allocate_number(uid)) if False else None
        run(store.upsert_user(uid, uid))
        run(store.set_verified(uid, True))
        number = run(store.allocate_number(uid))
        run(bot_module.ensure_account(uid, number, name, f"User {uid}"))
        add_session(path, uid, f"s{uid}-session-token")
    return path, app


def headers(uid: int) -> dict[str, str]:
    return {"Authorization": f"Bearer s{uid}-session-token"}


def test_gifts_are_hidden_until_admin_sets_price_and_supply(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            hidden = (await client.get("/api/telegram/gifts", headers=headers(101))).json()
            # Only WaysGram's own gift is on sale by default; Telegram's gifts wait for the
            # administrator to set a price and a stock.
            assert [gift["id"] for gift in hidden["gifts"]] == [waysgram_gifts.GIFT_ID]
            assert hidden["gifts"][0]["price_stars"] == 5000
            assert hidden["gifts"][0]["supply"] == 1000
            # The app storefront never exposes unlisted catalog entries, even via all=1
            # or when the authenticated WaysGram account is also an AuthBot administrator.
            full = (await client.get("/api/telegram/gifts?all=1", headers=headers(101))).json()
            assert [gift["id"] for gift in full["gifts"]] == [waysgram_gifts.GIFT_ID]
            everything = (await client.get("/api/telegram/gifts?all=1", headers=headers(ADMIN))).json()
            assert [gift["id"] for gift in everything["gifts"]] == [waysgram_gifts.GIFT_ID]

            # Price and stock are managed only through the AuthBot Telegram admin panel,
            # not through authenticated app REST sessions, even for the admin account.
            denied_user = await client.patch("/api/admin/telegram-gifts/g1", headers=headers(101), json={"price_stars": 5})
            denied_admin = await client.patch("/api/admin/telegram-gifts/g1", headers=headers(ADMIN), json={"price_stars": 40, "supply": 2})
            assert denied_user.status_code in (404, 405)
            assert denied_admin.status_code in (404, 405)
            await bot_module.set_telegram_gift_values("g1", price=40, supply=2)

            shown = (await client.get("/api/telegram/gifts", headers=headers(101))).json()
            # Telegram's catalogue keeps its order; our own gift is listed after it.
            assert [g["id"] for g in shown["gifts"]] == ["g1", waysgram_gifts.GIFT_ID]
            assert shown["gifts"][0]["price_stars"] == 40 and shown["gifts"][0]["remaining"] == 2
            assert (await client.patch("/api/admin/telegram-gifts/g1", headers=headers(ADMIN), json={"price_stars": -1})).status_code in (404, 405)

    run(scenario())


def test_buying_a_gift_charges_stars_and_reduces_stock(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            await bot_module.set_telegram_gift_values("g1", price=40, supply=2)
            # Client-supplied price and stock values must never override the
            # AuthBot administrator's configured listing.
            bought = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=headers(101),
                json={"price_stars": 1, "supply": 9999},
            )
            assert bought.status_code == 201
            assert bought.json()["price_stars"] == 40
            assert bought.json()["stars"] == 5000 - 40
            mine = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert len(mine) == 1 and mine[0]["gift_name"] == "Подарок Telegram"
            conn = db_split.sqlite_connect(path)
            try:
                self_receipt = conn.execute(
                    """SELECT m.sender_telegram_id,m.recipient_telegram_id,m.body,mg.attributes_json
                       FROM messages m JOIN message_gifts mg ON mg.message_id=m.id
                       WHERE mg.serial_number=?""",
                    (bought.json()["gift"]["serial_number"],),
                ).fetchone()
            finally:
                conn.close()
            assert self_receipt[:3] == (101, 101, "")
            self_attributes = json.loads(self_receipt[3])
            assert self_attributes["event_type"] == "purchase"
            assert self_attributes["native_action"] == "star_gift_purchase"
            assert self_attributes["price_stars"] == 40
            assert mine[0]["telegram_gift_id"] == "g1"
            assert mine[0]["sticker_available"] is True
            assert mine[0]["sticker_url"] == "/api/telegram/gifts/g1/sticker"
            assert mine[0]["gift_emoji"] == "🎁"
            assert mine[0]["telegram_gift"]["emoji"] == "🎁"
            conn = db_split.sqlite_connect(path)
            try:
                stored_snapshot = conn.execute(
                    "SELECT gift_emoji,telegram_gift_metadata_json FROM user_gifts WHERE serial_number=?",
                    (mine[0]["serial_number"],),
                ).fetchone()
            finally:
                conn.close()
            assert stored_snapshot[0] == "🎁"
            assert json.loads(stored_snapshot[1])["emoji"] == "🎁"
            assert mine[0]["telegram_gift"]["catalog_source"] == "telegram_gift_catalog"
            assert mine[0]["telegram_gift"]["emoji"] == "🎁"
            # WaysGram upgrades always cost the same fixed 25 Stars.
            assert mine[0]["telegram_gift"]["upgrade_star_count"] == 25
            assert mine[0]["upgrade_star_count"] == 25
            assert mine[0]["can_upgrade"] is True

            public_profile = await client.get("/api/users/101", headers=headers(102))
            assert public_profile.status_code == 200
            public_gift = public_profile.json()["gifts"][0]
            assert public_gift["telegram_gift"]["emoji"] == "🎁"
            assert public_gift["gift_emoji"] == "🎁"
            assert public_gift["telegram_gift"]["upgrade_star_count"] == 25
            assert public_gift["upgrade_star_count"] == 25
            assert public_gift["can_upgrade"] is True

            assert bought.json()["gift"]["telegram_gift_id"] == "g1"

            # A gift for a friend ends up in the friend's inventory, the buyer pays.
            sent = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={"recipient_id": 102})
            assert sent.status_code == 201
            assert sent.json()["stars"] == 5000 - 80
            assert len((await client.get("/api/me/gifts", headers=headers(102))).json()["gifts"]) == 1
            conn = db_split.sqlite_connect(path)
            try:
                receipts = conn.execute(
                    """SELECT m.sender_telegram_id,m.recipient_telegram_id,m.body,mg.serial_number,
                              mg.attributes_json
                       FROM messages m JOIN message_gifts mg ON mg.message_id=m.id
                       WHERE mg.serial_number=?""",
                    (sent.json()["gift"]["serial_number"],),
                ).fetchall()
                event = conn.execute(
                    """SELECT scope,user_a,user_b,message_id FROM message_events
                       WHERE message_id=(SELECT message_id FROM message_gifts WHERE serial_number=?)""",
                    (sent.json()["gift"]["serial_number"],),
                ).fetchone()
            finally:
                conn.close()
            assert len(receipts) == 1
            assert receipts[0][:3] == (101, 102, "")
            assert json.loads(receipts[0][4])["event_type"] == "purchase"
            assert json.loads(receipts[0][4])["native_action"] == "star_gift_purchase"
            assert event is not None and event[:3] == ("dm", 101, 102)

            # The stock was 2, so the next purchase fails and costs nothing.
            sold_out = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            assert sold_out.status_code == 409
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 5000 - 80
            gone = (await client.get("/api/telegram/gifts", headers=headers(101))).json()
            assert [gift["id"] for gift in gone["gifts"]] == [waysgram_gifts.GIFT_ID]

    run(scenario())


def test_gift_purchase_rules(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            # Not priced: cannot be bought.
            assert (await client.post("/api/telegram/gifts/g2/purchase", headers=headers(101), json={})).status_code == 409
            await bot_module.set_telegram_gift_values("g2", price=9000, supply=5)
            # More than the 5000 welcome stars.
            poor = await client.post("/api/telegram/gifts/g2/purchase", headers=headers(101), json={})
            assert poor.status_code == 409
            # Unknown recipient.
            await bot_module.set_telegram_gift_values("g1", price=1, supply=5)
            assert (await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={"recipient_id": 424242})).status_code == 404
            assert (await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={"recipient_id": "x"})).status_code == 400
            # Nothing was charged by the failed attempts.
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 5000

    run(scenario())


def test_public_account_never_returns_literal_null_as_phone(env) -> None:
    path, app = env
    connection = db_split.sqlite_connect(path)
    try:
        connection.execute(
            "UPDATE accounts SET virtual_number='+null' WHERE telegram_id=101"
        )
        expected_number = connection.execute(
            "SELECT number FROM numbers WHERE telegram_id=101"
        ).fetchone()[0]
        connection.commit()
    finally:
        connection.close()

    async def scenario() -> None:
        async with await client_for(app) as client:
            profile = await client.get("/api/me", headers=headers(101))
            assert profile.status_code == 200
            assert profile.json()["phone"] == expected_number
            assert profile.json()["phone"] != "+null"

    run(scenario())


def test_every_account_has_premium_and_admin_flag(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            me = (await client.get("/api/me", headers=headers(101))).json()
            assert me["is_premium"] is True and me["is_admin"] is False
            boss = (await client.get("/api/me", headers=headers(ADMIN))).json()
            assert boss["is_admin"] is True

    run(scenario())


def _png() -> bytes:
    import io

    from PIL import Image

    buffer = io.BytesIO()
    Image.new("RGB", (64, 64), (200, 30, 30)).save(buffer, format="PNG")
    return buffer.getvalue()


def test_profile_avatar_lifecycle(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            png = {"Content-Type": "image/png"}
            uploaded = await client.post("/api/media/images", headers={**headers(101), **png}, content=_png())
            assert uploaded.status_code == 201
            media_id = uploaded.json()["id"]

            # Before it is set, nobody else can download the picture.
            assert (await client.get(f"/api/media/{media_id}", headers=headers(102))).status_code == 404
            assert (await client.get("/api/me", headers=headers(101))).json()["avatar_media_id"] == ""

            assert (await client.put("/api/me/avatar", headers=headers(101), json={"media_id": "zz"})).status_code == 400
            # Somebody else's picture cannot become your avatar.
            assert (await client.put("/api/me/avatar", headers=headers(102), json={"media_id": media_id})).status_code == 404
            set_ok = await client.put("/api/me/avatar", headers=headers(101), json={"media_id": media_id})
            assert set_ok.status_code == 200

            assert (await client.get("/api/me", headers=headers(101))).json()["avatar_media_id"] == media_id
            # Everybody sees the avatar on the profile and in search, and can download it.
            assert (await client.get("/api/users/101", headers=headers(102))).json()["avatar_media_id"] == media_id
            found = (await client.get("/api/users?q=buyer", headers=headers(102))).json()["users"]
            assert found and found[0]["avatar_media_id"] == media_id
            shown = await client.get(f"/api/media/{media_id}", headers=headers(102))
            assert shown.status_code == 200 and shown.headers["content-type"].startswith("image/")

            removed = await client.delete("/api/me/avatar", headers=headers(101))
            assert removed.status_code == 200
            assert (await client.get("/api/users/101", headers=headers(102))).json()["avatar_media_id"] == ""
            assert (await client.get(f"/api/media/{media_id}", headers=headers(102))).status_code == 404

    run(scenario())


def test_bio_is_saved_and_visible_to_others(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            saved = await client.patch("/api/me", headers=headers(101), json={"bio": "  Hello there  "})
            assert saved.status_code == 200 and saved.json()["bio"] == "Hello there"
            assert (await client.get("/api/users/101", headers=headers(102))).json()["bio"] == "Hello there"
            assert (await client.patch("/api/me", headers=headers(101), json={"bio": "x" * 141})).status_code == 400
            # Changing the name does not touch the bio.
            await client.patch("/api/me", headers=headers(101), json={"display_name": "Renamed"})
            assert (await client.get("/api/me", headers=headers(101))).json()["bio"] == "Hello there"

    run(scenario())


def test_telegram_gift_purchase_is_idempotent(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            await bot_module.set_telegram_gift_values("g1", price=40, supply=2)
            key = "wg-telegram-gift-buy-0001"
            first = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers={**headers(101), "Idempotency-Key": key},
                json={},
            )
            second = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers={**headers(101), "Idempotency-Key": key},
                json={},
            )
            assert first.status_code == 201 and second.status_code == 201
            assert second.json() == first.json()
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 4960
            assert (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"][0]["telegram_gift_id"] == "g1"

            reused = await client.post(
                "/api/telegram/gifts/g2/purchase",
                headers={**headers(101), "Idempotency-Key": key},
                json={},
            )
            assert reused.status_code == 409
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 4960

    run(scenario())


def test_removed_telegram_gift_cannot_be_purchased(env, monkeypatch: pytest.MonkeyPatch) -> None:
    path, app = env

    gift_one = {
        "id": "g1",
        "sticker": {
            "file_id": "f1", "file_unique_id": "u1", "type": "regular",
            "width": 512, "height": 512, "is_animated": False, "is_video": False,
            "emoji": "🎁", "mime_type": "image/webp",
        },
        "star_count": 25,
    }
    gift_two = {
        "id": "g2",
        "sticker": {
            "file_id": "f2", "file_unique_id": "u2", "type": "regular",
            "width": 512, "height": 512, "is_animated": False, "is_video": False,
            "emoji": "🧸", "mime_type": "image/webp",
        },
        "star_count": 50,
    }

    async def scenario() -> None:
        # Start with both gifts active and configure a sale for g1.
        await messenger_api.store_telegram_gifts(path, [gift_one, gift_two], synced_at=1)
        await bot_module.set_telegram_gift_values("g1", price=40, supply=2)

        # Telegram later removes g1. Force the next request to refresh the catalog.
        async def current_catalog(bot):
            return [gift_two]

        monkeypatch.setattr(messenger_api, "fetch_telegram_gifts", current_catalog)

        async with await client_for(app) as client:
            result = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=headers(101),
                json={},
            )
            assert result.status_code == 201
            assert result.json()["gift"]["telegram_gift_id"] == "g1"
            balance = (await client.get("/api/me", headers=headers(101))).json()["stars"]
            assert balance == 4960
            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert len(inventory) == 1 and inventory[0]["telegram_gift_id"] == "g1"
            archived_admin_rows = await bot_module.telegram_gift_rows()
            # Own gifts have their own AuthBot screen, so the Telegram list stays Telegram's.
            assert {str(row["id"]) for row in archived_admin_rows} == {"g1", "g2"}

        connection = db_split.sqlite_connect(path)
        try:
            assert connection.execute(
                "SELECT sold FROM telegram_gift_prices WHERE gift_id='g1'"
            ).fetchone() == (1,)
            assert connection.execute(
                "SELECT is_available FROM telegram_gift_catalog WHERE gift_id='g1'"
            ).fetchone() == (0,)
            receipt = connection.execute(
                """SELECT m.sender_telegram_id,m.recipient_telegram_id,m.body,mg.serial_number,mg.telegram_gift_id
                   FROM messages m JOIN message_gifts mg ON mg.message_id=m.id
                   WHERE m.sender_telegram_id=101 AND m.recipient_telegram_id=101"""
            ).fetchone()
            assert receipt is not None
            assert receipt[0:2] == (101, 101)
            assert receipt[2] == ""
            event = connection.execute(
                "SELECT attributes_json FROM message_gifts WHERE serial_number=?",
                (receipt[3],),
            ).fetchone()
            event_attributes = json.loads(event[0])
            assert event_attributes["native_action"] == "star_gift_purchase"
            assert event_attributes["event_type"] == "purchase"
            assert receipt[4] == "g1"
        finally:
            connection.close()

    run(scenario())



def test_simultaneous_telegram_gift_purchases_cannot_oversell(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=40, supply=1)
        async with await client_for(app) as client:
            async def buy(key: str):
                return await client.post(
                    "/api/telegram/gifts/g1/purchase",
                    headers={**headers(101), "Idempotency-Key": key},
                    json={},
                )

            first, second = await asyncio.gather(
                buy("wg-concurrent-gift-buy-0001"),
                buy("wg-concurrent-gift-buy-0002"),
            )
            assert sorted([first.status_code, second.status_code]) == [201, 409]
            balance = (await client.get("/api/me", headers=headers(101))).json()["stars"]
            assert balance == 4960
            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert len(inventory) == 1 and inventory[0]["telegram_gift_id"] == "g1"

        connection = db_split.sqlite_connect(path)
        try:
            assert connection.execute(
                "SELECT sold FROM telegram_gift_prices WHERE gift_id='g1'"
            ).fetchone() == (1,)
        finally:
            connection.close()

    run(scenario())


def test_auth_sessions_can_be_listed_and_revoked(env) -> None:
    path, app = env
    secondary = "s101-secondary-session-token"
    tertiary = "s101-tertiary-session-token"
    add_session(path, 101, secondary)

    async def scenario() -> None:
        async with await client_for(app) as client:
            listing = await client.get("/api/auth/sessions", headers=headers(101))
            assert listing.status_code == 200
            sessions = listing.json()["sessions"]
            assert listing.json()["count"] == 2
            current = [item for item in sessions if item["is_current"]]
            other = [item for item in sessions if not item["is_current"]]
            assert len(current) == 1 and len(other) == 1
            assert "token_hash" not in str(listing.json())
            assert "s101-session-token" not in str(listing.json())

            revoked = await client.delete(
                "/api/auth/sessions/" + other[0]["id"],
                headers=headers(101),
            )
            assert revoked.status_code == 200
            assert (await client.get("/api/auth/sessions", headers=headers(101))).json()["count"] == 1

            add_session(path, 101, tertiary)
            revoke_others = await client.post(
                "/api/auth/sessions/revoke-others",
                headers=headers(101),
                json={},
            )
            assert revoke_others.status_code == 200
            assert revoke_others.json()["revoked_sessions"] == 1
            assert (await client.get("/api/auth/sessions", headers=headers(101))).json()["count"] == 1
            assert (await client.get("/api/me", headers={"Authorization": "Bearer " + tertiary})).status_code == 401

    run(scenario())


def test_public_profile_includes_received_gifts(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=35, supply=1)
        async with await client_for(app) as client:
            purchase = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=headers(101),
                json={"recipient_id": 102},
            )
            assert purchase.status_code == 201

            profile = await client.get("/api/users/102", headers=headers(101))
            assert profile.status_code == 200
            data = profile.json()
            assert data["id"] == 102
            assert len(data["gifts"]) == 1
            gift = data["gifts"][0]
            assert gift["telegram_gift_id"] == "g1"
            assert gift["gift_name"] == "Подарок Telegram"
            assert gift["serial_number"] == purchase.json()["gift"]["serial_number"]

            # Public profile output must not include account credentials or phone data.
            assert "phone" not in data
            assert "token" not in str(data)
            assert "stars" not in data

    run(scenario())


def test_gift_market_listing_purchase_and_cancellation(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=30, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=headers(101),
                json={},
            )
            assert bought.status_code == 201
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(101))
            assert upgraded.status_code == 200
            traits = upgraded.json()["attributes"]["collectible_traits"]

            listed = await client.post(
                f"/api/gifts/{serial}/list",
                headers=headers(101),
                json={"price_grams": 7},
            )
            assert listed.status_code == 201
            listing_id = listed.json()["listing_id"]
            assert listed.json()["currency"] == "grams"

            # A listed gift is locked against transfer and upgrade.
            assert (await client.post(
                "/api/gifts/transfer",
                headers=headers(101),
                json={"serial_number": serial, "recipient_id": 102},
            )).status_code == 409
            assert (await client.post(
                f"/api/gifts/{serial}/upgrade",
                headers=headers(101),
            )).status_code == 409

            market = await client.get("/api/gift-market", headers=headers(102))
            assert market.status_code == 200
            assert len(market.json()["listings"]) == 1
            assert market.json()["listings"][0]["listing_id"] == listing_id
            assert market.json()["listings"][0]["price_grams"] == 7
            assert market.json()["listings"][0]["attributes"]["collectible_traits"] == traits

            purchased = await client.post(
                f"/api/gift-market/{listing_id}/purchase",
                headers=headers(102),
            )
            assert purchased.status_code == 201
            assert purchased.json()["grams_balance"] == 8
            assert (await client.get("/api/me", headers=headers(101))).json()["grams"] == 22
            assert (await client.get("/api/me", headers=headers(102))).json()["grams"] == 8
            buyer_inventory = (await client.get("/api/me/gifts", headers=headers(102))).json()["gifts"]
            assert len(buyer_inventory) == 1 and buyer_inventory[0]["serial_number"] == serial
            assert buyer_inventory[0]["attributes"]["collectible_traits"] == traits
            saved = await client.get("/api/messages?with=102", headers=headers(102))
            purchase_receipts = [
                message for message in saved.json()["messages"]
                if message.get("gift_serial_number") == serial
            ]
            assert len(purchase_receipts) == 1
            assert purchase_receipts[0]["gift_name"] == buyer_inventory[0]["gift_name"]
            assert purchase_receipts[0]["gift_telegram_gift_id"] == "g1"
            assert purchase_receipts[0]["body"] == ""
            assert purchase_receipts[0]["gift_upgrade_level"] == 1
            market_attributes = purchase_receipts[0]["gift_attributes"]
            assert market_attributes["event_type"] == "purchase"
            assert market_attributes["native_action"] == "star_gift_purchase"
            assert market_attributes["price_grams"] == 7
            assert market_attributes["currency"] == "grams"
            assert market_attributes["upgrade_level"] == 1
            assert market_attributes["collectible_traits"] == traits
            assert (await client.get("/api/gift-market", headers=headers(101))).json()["count"] == 0

            # A sold listing cannot be purchased a second time.
            assert (await client.post(
                f"/api/gift-market/{listing_id}/purchase",
                headers=headers(102),
            )).status_code == 404

    run(scenario())


def test_gift_market_listing_can_be_cancelled(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=20, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            serial = bought.json()["gift"]["serial_number"]
            listed = await client.post(
                f"/api/gifts/{serial}/list", headers=headers(101), json={"price_grams": 5}
            )
            assert listed.status_code == 201
            assert (await client.delete(f"/api/gifts/{serial}/list", headers=headers(102))).status_code == 404
            cancelled = await client.delete(f"/api/gifts/{serial}/list", headers=headers(101))
            assert cancelled.status_code == 200 and cancelled.json()["status"] == "cancelled"
            assert (await client.get("/api/gift-market", headers=headers(102))).json()["count"] == 0
            assert (await client.post(
                f"/api/gifts/{serial}/list", headers=headers(101), json={"price_grams": 9}
            )).status_code == 201

    run(scenario())



def test_collectible_gift_auction_refunds_outbidder_and_settles_ownership(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=20, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=headers(101),
                json={},
            )
            assert bought.status_code == 201
            serial = bought.json()["gift"]["serial_number"]

            # An unupgraded gift is not eligible for collectible auctions.
            rejected = await client.post(
                f"/api/gifts/{serial}/auction",
                headers=headers(101),
                json={"starting_price_grams": 5, "duration_seconds": 3600},
            )
            assert rejected.status_code == 409

            upgraded = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(101))
            assert upgraded.status_code == 200
            traits = upgraded.json()["attributes"]["collectible_traits"]

            # This model is registered as an original Telegram auction release.
            connection = db_split.sqlite_connect(path)
            try:
                connection.execute(
                    """INSERT INTO telegram_auction_gift_registry
                       (gift_id,title,auction_slug,auction_start_date,gifts_per_round,
                        discovered_at,source_payload)
                       VALUES(?,?,?,?,?,?,?)""",
                    ("g1", "🎁", "historic-g1", 1700000000, 1000, 1700000001, "{}"),
                )
                connection.commit()
            finally:
                connection.close()

            created = await client.post(
                f"/api/gifts/{serial}/auction",
                headers=headers(101),
                json={"starting_price_grams": 5, "duration_seconds": 3600},
            )
            assert created.status_code == 201
            auction_id = created.json()["auction_id"]
            assert created.json()["currency"] == "grams"
            assert created.json()["minimum_bid_grams"] == 5

            # Fixed-price listing, direct transfer and chat transfer are locked during an auction.
            assert (await client.post(
                f"/api/gifts/{serial}/list",
                headers=headers(101),
                json={"price_grams": 8},
            )).status_code == 409
            assert (await client.post(
                "/api/gifts/transfer",
                headers=headers(101),
                json={"serial_number": serial, "recipient_id": 102},
            )).status_code == 409
            assert (await client.post(
                "/api/messages/gift",
                headers=headers(101),
                json={"recipient_id": 102, "serial_number": serial},
            )).status_code == 409
            assert (await client.post(
                f"/api/gift-auctions/{auction_id}/bid",
                headers=headers(101),
                json={"amount_grams": 5},
            )).status_code == 403

            auctions = (await client.get("/api/gift-auctions", headers=headers(102))).json()["auctions"]
            assert len(auctions) == 1
            assert auctions[0]["auction_id"] == auction_id
            assert auctions[0]["attributes"]["collectible_traits"] == traits

            too_low = await client.post(
                f"/api/gift-auctions/{auction_id}/bid",
                headers=headers(102),
                json={"amount_grams": 4},
            )
            assert too_low.status_code == 409

            first_bid = await client.post(
                f"/api/gift-auctions/{auction_id}/bid",
                headers=headers(102),
                json={"amount_grams": 5},
            )
            assert first_bid.status_code == 201
            assert first_bid.json()["grams_balance"] == 10
            outbid = await client.post(
                f"/api/gift-auctions/{auction_id}/bid",
                headers=headers(ADMIN),
                json={"amount_grams": 7},
            )
            assert outbid.status_code == 201
            assert outbid.json()["grams_balance"] == 8
            assert (await client.get("/api/me", headers=headers(102))).json()["grams"] == 15
            assert (await client.get("/api/me", headers=headers(ADMIN))).json()["grams"] == 8

            cannot_cancel = await client.delete(
                f"/api/gifts/{serial}/auction",
                headers=headers(101),
            )
            assert cannot_cancel.status_code == 409

            connection = db_split.sqlite_connect(path)
            try:
                connection.execute(
                    "UPDATE gift_collectible_auctions SET ends_at=? WHERE id=?",
                    (int(time.time()) - 1, auction_id),
                )
                connection.commit()
            finally:
                connection.close()

            settled = await client.get("/api/gift-auctions", headers=headers(102))
            assert settled.status_code == 200
            assert settled.json()["count"] == 0
            assert settled.json()["settled_count"] == 1

            seller_inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            winner_inventory = (await client.get("/api/me/gifts", headers=headers(ADMIN))).json()["gifts"]
            assert seller_inventory == []
            assert len(winner_inventory) == 1
            assert winner_inventory[0]["serial_number"] == serial
            assert winner_inventory[0]["attributes"]["collectible_traits"] == traits
            assert (await client.get("/api/me", headers=headers(101))).json()["grams"] == 22

            connection = db_split.sqlite_connect(path)
            try:
                status = connection.execute(
                    "SELECT status FROM gift_collectible_auctions WHERE id=?",
                    (auction_id,),
                ).fetchone()
                assert status == ("sold",)
                transaction = connection.execute(
                    """SELECT event_type,from_telegram_id,to_telegram_id
                       FROM gift_transactions WHERE serial_number=? AND event_type='auction_sale'""",
                    (serial,),
                ).fetchone()
                assert transaction == ("auction_sale", 101, ADMIN)
            finally:
                connection.close()

    run(scenario())


def test_collectible_gift_auction_can_be_cancelled_before_first_bid(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=20, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(101))
            assert upgraded.status_code == 200

            connection = db_split.sqlite_connect(path)
            try:
                connection.execute(
                    """INSERT INTO telegram_auction_gift_registry
                       (gift_id,title,auction_slug,auction_start_date,gifts_per_round,
                        discovered_at,source_payload)
                       VALUES(?,?,?,?,?,?,?)""",
                    ("g1", "🎁", "historic-g1", 1700000000, 1000, 1700000001, "{}"),
                )
                connection.commit()
            finally:
                connection.close()

            created = await client.post(
                f"/api/gifts/{serial}/auction",
                headers=headers(101),
                json={"starting_price_grams": 6},
            )
            assert created.status_code == 201
            denied = await client.delete(f"/api/gifts/{serial}/auction", headers=headers(102))
            assert denied.status_code == 404

            cancelled = await client.delete(f"/api/gifts/{serial}/auction", headers=headers(101))
            assert cancelled.status_code == 200
            relisted = await client.post(
                f"/api/gifts/{serial}/list",
                headers=headers(101),
                json={"price_grams": 9},
            )
            assert relisted.status_code == 201

    run(scenario())


def test_telegram_gift_upgrade_uses_internal_stars_and_records_level(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=20, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            serial = bought.json()["gift"]["serial_number"]
            upgrade_headers = {**headers(101), "Idempotency-Key": "wg-gift-upgrade-test-0001"}
            upgraded = await client.post(
                f"/api/gifts/{serial}/upgrade", headers=upgrade_headers
            )
            assert upgraded.status_code == 200
            assert upgraded.json()["upgrade_level"] == 1
            # Every WaysGram upgrade costs the same fixed 25 Stars, regardless of the
            # Telegram catalog price of the gift.
            assert upgraded.json()["price_stars"] == 25
            assert upgraded.json()["upgrade_star_count"] == 25
            assert upgraded.json()["stars_balance"] == 4955

            # Replaying a successful upgrade request must not debit Stars twice.
            replayed = await client.post(
                f"/api/gifts/{serial}/upgrade", headers=upgrade_headers
            )
            assert replayed.status_code == 200
            assert replayed.json() == upgraded.json()
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 4955
            traits = upgraded.json()["attributes"]["collectible_traits"]
            # Traits come from the official Telegram pools: model, pattern and
            # backdrop are objects with the published name and chance.
            assert traits["version"] == 3
            assert traits["rarity"] in {"common", "uncommon", "rare", "epic", "legendary"}
            assert traits["model"]["name"] and traits["pattern"]["name"]
            assert traits["backdrop"]["name"]
            assert upgraded.json()["unique_num"] == 1

            mine = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert mine[0]["upgrade_level"] == 1
            assert mine[0]["attributes"]["upgrade_source"] == "waysgram_internal"

            saved = await client.get("/api/messages?with=101", headers=headers(101))
            receipt = next(
                item for item in saved.json()["messages"]
                if item.get("gift_serial_number") == serial
            )
            assert receipt["body"] == ""
            assert receipt["gift_upgrade_level"] == 1
            assert receipt["gift_attributes"]["event_type"] == "purchase"
            assert receipt["gift_attributes"]["native_action"] == "star_gift_purchase"
            assert receipt["gift_attributes"]["upgrade_source"] == "waysgram_internal"
            assert receipt["gift_attributes"]["collectible_traits"] == upgraded.json()["attributes"]["collectible_traits"]
            assert mine[0]["attributes"]["collectible_traits"] == traits

            # A new request cannot upgrade the collectible or debit it again.
            second_headers = {**headers(101), "Idempotency-Key": "wg-gift-upgrade-test-0002"}
            second_upgrade = await client.post(
                f"/api/gifts/{serial}/upgrade", headers=second_headers
            )
            assert second_upgrade.status_code == 409
            assert second_upgrade.json()["detail"] == "Подарок уже улучшен"
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 4955

    run(scenario())


def test_startup_backfills_traits_for_previously_upgraded_gifts(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=20, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=headers(101),
                json={},
            )
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(101))
            assert upgraded.status_code == 200

        # Simulate the attributes JSON written by the previous backend version.
        connection = db_split.sqlite_connect(path)
        try:
            connection.execute(
                "UPDATE user_gifts SET attributes_json=? WHERE serial_number=?",
                (json.dumps({
                    "upgrade_level": 1,
                    "last_upgraded_at": 123,
                    "upgrade_source": "waysgram_internal",
                }), serial),
            )
            connection.commit()
        finally:
            connection.close()

        await init_messenger_storage(path)
        async with await client_for(app) as client:
            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            traits = inventory[0]["attributes"]["collectible_traits"]
            assert traits["version"] == 3
            assert traits["rarity"] in {"common", "uncommon", "rare", "epic", "legendary"}
            assert traits["model"]["name"] and traits["pattern"]["name"]
            assert traits["backdrop"]["name"]
            # The backfill also hands out the collection number.
            assert inventory[0]["unique_num"] == 1

        # Startup is idempotent: a second initialization must preserve the assigned traits.
        await init_messenger_storage(path)
        async with await client_for(app) as client:
            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert inventory[0]["attributes"]["collectible_traits"] == traits

    run(scenario())

def test_gift_purchase_creates_one_collectible_receipt_in_saved_messages(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=35, supply=2)
        async with await client_for(app) as client:
            key = "wg-saved-gift-purchase-0001"
            purchase_headers = {**headers(101), "Idempotency-Key": key}
            purchased = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=purchase_headers,
                json={},
            )
            retried = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=purchase_headers,
                json={},
            )

            assert purchased.status_code == 201
            assert retried.status_code == 201
            assert retried.json() == purchased.json()
            assert purchased.json()["price_stars"] == 35
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 5000 - 35

            serial = purchased.json()["gift"]["serial_number"]
            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert len(inventory) == 1
            assert inventory[0]["serial_number"] == serial
            assert inventory[0]["telegram_gift_id"] == "g1"

            saved = await client.get("/api/messages?with=101", headers=headers(101))
            assert saved.status_code == 200
            receipts = [
                message for message in saved.json()["messages"]
                if message.get("gift_serial_number") == serial
            ]
            assert len(receipts) == 1
            receipt = receipts[0]
            assert receipt["sender_id"] == 101
            assert receipt["recipient_id"] == 101
            assert receipt["body"] == ""
            assert receipt["gift_name"] == "Подарок Telegram"
            assert receipt["gift_telegram_gift_id"] == "g1"
            assert receipt["gift_upgrade_level"] == 0
            assert receipt["gift_attributes"] == {
                "event_type": "purchase",
                "native_action": "star_gift_purchase",
                "price_stars": 35,
            }
            assert receipt["gift_emoji"] == "🎁"
            assert receipt["gift_telegram_metadata"]["emoji"] == "🎁"
            assert receipt["gift_telegram_metadata"]["upgrade_star_count"] == 10
            assert receipt["gift_telegram_metadata"]["emoji"] == "🎁"
            assert receipt["gift_emoji"] == "🎁"

    run(scenario())


def test_waysgram_catalog_purchase_creates_one_saved_gift_card(env) -> None:
    path, app = env
    conn = db_split.sqlite_connect(path)
    try:
        now = int(time.time())
        conn.execute(
            "INSERT INTO gift_catalog(name,price_grams,supply,created_at) VALUES('Plush WG',25,2,?)",
            (now,),
        )
        conn.execute("UPDATE accounts SET grams=5000 WHERE telegram_id=101")
        conn.commit()
    finally:
        conn.close()

    async def scenario() -> None:
        async with await client_for(app) as client:
            purchase_headers = {
                **headers(101),
                "Idempotency-Key": "wg-grams-purchase-receipt-0001",
            }
            purchased = await client.post(
                "/api/gifts/1/purchase",
                headers=purchase_headers,
                json={},
            )
            retried = await client.post(
                "/api/gifts/1/purchase",
                headers=purchase_headers,
                json={},
            )
            assert purchased.status_code == 201
            assert retried.status_code == 201
            assert retried.json() == purchased.json()

            serial = purchased.json()["gift"]["serial_number"]
            saved = await client.get("/api/messages?with=101", headers=headers(101))
            assert saved.status_code == 200
            receipts = [
                message for message in saved.json()["messages"]
                if message.get("gift_serial_number") == serial
            ]
            assert len(receipts) == 1
            receipt = receipts[0]
            assert receipt["sender_id"] == 101
            assert receipt["recipient_id"] == 101
            assert receipt["body"] == ""
            assert receipt["gift_name"] == "Plush WG"
            assert receipt["gift_attributes"] == {
                "event_type": "purchase",
                "native_action": "star_gift_purchase",
                "price_grams": 25,
                "currency": "grams",
            }

    run(scenario())


def test_my_gifts_pagination_returns_all_items_without_overlap(env) -> None:
    path, app = env
    conn = db_split.sqlite_connect(path)
    try:
        now = int(time.time())
        for index in range(5):
            conn.execute(
                """INSERT INTO user_gifts
                   (serial_number,gift_id,gift_name,telegram_gift_id,owner_telegram_id,acquired_at)
                   VALUES(?,NULL,?,'',?,?)""",
                (f"WG-PAGE-{index}", f"Gift {index}", 101, now + index),
            )
        conn.commit()
    finally:
        conn.close()

    async def scenario() -> None:
        async with await client_for(app) as client:
            first = await client.get(
                "/api/me/gifts?limit=2&offset=0",
                headers=headers(101),
            )
            second = await client.get(
                "/api/me/gifts?limit=2&offset=2",
                headers=headers(101),
            )
            third = await client.get(
                "/api/me/gifts?limit=2&offset=4",
                headers=headers(101),
            )
            assert first.status_code == second.status_code == third.status_code == 200
            assert first.json()["has_more"] is True
            assert first.json()["next_offset"] == 2
            assert second.json()["has_more"] is True
            assert second.json()["next_offset"] == 4
            assert third.json()["has_more"] is False
            serials = [
                item["serial_number"]
                for page in (first, second, third)
                for item in page.json()["gifts"]
            ]
            assert len(serials) == 5
            assert len(set(serials)) == 5

    run(scenario())


def test_my_gifts_total_is_independent_of_pagination(env) -> None:
    path, app = env
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        db.executemany(
            """INSERT INTO user_gifts
               (serial_number,gift_id,gift_name,telegram_gift_id,owner_telegram_id,acquired_at)
               VALUES(?,NULL,?,'',?,?)""",
            [
                ("WG-TOTAL-1", "Gift 1", 101, now),
                ("WG-TOTAL-2", "Gift 2", 101, now + 1),
                ("WG-TOTAL-3", "Gift 3", 101, now + 2),
            ],
        )
        db.commit()

    async def scenario() -> None:
        async with await client_for(app) as client:
            first = await client.get("/api/me/gifts?limit=2&offset=0", headers=headers(101))
            second = await client.get("/api/me/gifts?limit=2&offset=2", headers=headers(101))
            assert first.status_code == second.status_code == 200
            assert first.json()["total"] == second.json()["total"] == 3
            assert len(first.json()["gifts"]) == 2
            assert len(second.json()["gifts"]) == 1
            assert first.json()["has_more"] is True
            assert second.json()["has_more"] is False

    run(scenario())


def test_ordinary_chat_text_is_not_treated_as_gift_purchase_receipt() -> None:
    assert messenger_api._legacy_gift_purchase_details("Я купил подарок сегодня") is None
    assert messenger_api._legacy_gift_purchase_details("I bought a gift yesterday") is None


def test_purchase_snapshot_recovers_missing_sticker_available_flag() -> None:
    current = {
        "id": "5936013938331222567",
        "sticker_available": False,
        "sticker": {"type": "video", "url": ""},
    }
    snapshot = {
        "sticker_available": False,
        "sticker": {
            "set_name": "PlushPepe",
            "mime_type": "video/webm",
            "is_video": True,
        },
    }

    merged = messenger_api._merge_telegram_gift_metadata(current, snapshot)

    assert merged is not None
    assert merged["sticker_available"] is True
    assert merged["sticker"]["set_name"] == "PlushPepe"
    assert merged["sticker"]["mime_type"] == "video/webm"


def test_mtproto_metadata_falls_back_to_bot_api_emoji_for_owned_gifts(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=35, supply=1)
        async with await client_for(app) as client:
            purchased = await client.post(
                "/api/telegram/gifts/g1/purchase", headers=headers(101), json={}
            )
            assert purchased.status_code == 201
            serial = purchased.json()["gift"]["serial_number"]

            conn = db_split.sqlite_connect(path)
            try:
                conn.execute(
                    """INSERT OR REPLACE INTO telegram_mtproto_gift_catalog
                       (gift_id,title,sticker_emoji,sticker_document_id,source_payload,synced_at)
                       VALUES('g1','Gift title','',987654,'{}',?)""",
                    (int(time.time()),),
                )
                conn.execute(
                    "UPDATE telegram_gift_sync_state SET source='telegram_mtproto' WHERE id=1"
                )
                conn.execute(
                    """UPDATE user_gifts
                       SET gift_emoji='',telegram_gift_metadata_json='{}'
                       WHERE serial_number=?""",
                    (serial,),
                )
                conn.commit()
            finally:
                conn.close()

            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert inventory[0]["gift_emoji"] == "🎁"
            assert inventory[0]["telegram_gift"]["emoji"] == "🎁"
            assert inventory[0]["sticker_available"] is True
            assert inventory[0]["sticker_url"] == "/api/telegram/gifts/g1/sticker"

            saved = await client.get("/api/messages?with=101", headers=headers(101))
            receipt = next(
                item for item in saved.json()["messages"]
                if item.get("gift_serial_number") == serial
            )
            assert receipt["gift_emoji"] == "🎁"
            assert receipt["gift_telegram_metadata"]["emoji"] == "🎁"

    run(scenario())


def test_regular_gift_transfer_message_stays_unupgraded(env) -> None:
    _path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=40, supply=1)
        async with await client_for(app) as client:
            purchase = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            serial = purchase.json()["gift"]["serial_number"]
            sent = await client.post("/api/messages/gift", headers=headers(101), json={"recipient_id": 102, "serial_number": serial})
            assert sent.status_code == 201
            assert sent.json()["gift_upgrade_level"] == 0
            assert sent.json()["gift_unique_num"] == 0
            for viewer, peer in ((101, 102), (102, 101)):
                history = (await client.get(f"/api/messages?with={peer}", headers=headers(viewer))).json()["messages"]
                card = next(item for item in history if item["id"] == sent.json()["id"])
                assert card["gift_upgrade_level"] == 0 and card["gift_unique_num"] is None
                assert not card["gift_attributes"].get("collectible_traits")
                assert card["gift_instance_id"] == sent.json()["gift_instance_id"]

    run(scenario())


def test_gift_can_be_sent_as_a_dm_message_with_snapshot_and_atomic_ownership(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=40, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=headers(101),
                json={},
            )
            assert bought.status_code == 201
            serial = bought.json()["gift"]["serial_number"]

            upgraded = await client.post(
                f"/api/gifts/{serial}/upgrade",
                headers=headers(101),
            )
            assert upgraded.status_code == 200
            assert upgraded.json()["upgrade_level"] == 1

            # An active marketplace listing locks the gift from chat transfers too.
            listing = await client.post(
                f"/api/gifts/{serial}/list",
                headers=headers(101),
                json={"price_grams": 5},
            )
            assert listing.status_code == 201
            blocked = await client.post(
                "/api/messages/gift",
                headers=headers(101),
                json={"recipient_id": 102, "serial_number": serial},
            )
            assert blocked.status_code == 409
            cancelled = await client.delete(
                f"/api/gifts/{serial}/list",
                headers=headers(101),
            )
            assert cancelled.status_code == 200

            key = "wg-chat-gift-send-0001"
            payload = {
                "recipient_id": 102,
                "serial_number": serial,
                "body": "Подарок для тебя",
            }
            sent = await client.post(
                "/api/messages/gift",
                headers={**headers(101), "Idempotency-Key": key},
                json=payload,
            )
            retried = await client.post(
                "/api/messages/gift",
                headers={**headers(101), "Idempotency-Key": key},
                json=payload,
            )
            assert sent.status_code == 201
            assert retried.status_code == 201 and retried.json() == sent.json()
            assert sent.json()["type"] == "gift"
            assert sent.json()["gift_serial_number"] == serial
            assert sent.json()["gift_upgrade_level"] == 1
            assert sent.json()["gift_unique_num"] == upgraded.json()["unique_num"] > 0
            assert sent.json()["gift_instance_id"] > 0
            assert sent.json()["gift_attributes"]["upgrade_source"] == "waysgram_internal"
            traits = upgraded.json()["attributes"]["collectible_traits"]
            assert sent.json()["gift_attributes"]["collectible_traits"] == traits

            sender_inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            recipient_inventory = (await client.get("/api/me/gifts", headers=headers(102))).json()["gifts"]
            assert sender_inventory == []
            assert len(recipient_inventory) == 1
            assert recipient_inventory[0]["serial_number"] == serial
            assert recipient_inventory[0]["upgrade_level"] == 1

            sender_history = (await client.get("/api/messages?with=102", headers=headers(101))).json()
            receiver_history = (await client.get("/api/messages?with=101", headers=headers(102))).json()
            for history in (sender_history, receiver_history):
                item = next(message for message in history["messages"] if message["id"] == sent.json()["id"])
                assert item["gift_serial_number"] == serial
                assert item["gift_name"] == "Подарок Telegram"
                assert item["gift_upgrade_level"] == 1
                assert item["gift_unique_num"] == sent.json()["gift_unique_num"]
                assert item["gift_instance_id"] == sent.json()["gift_instance_id"]
                assert item["gift_emoji"] == "🎁"
                assert item["gift_telegram_metadata"]["emoji"] == "🎁"
                assert item["gift_telegram_metadata"]["upgrade_star_count"] == 10
                assert item["gift_attributes"]["upgrade_source"] == "waysgram_internal"
                assert item["gift_attributes"]["collectible_traits"] == traits

            profile = (await client.get("/api/users/102", headers=headers(101))).json()
            public_gift = profile["gifts"][0]
            assert public_gift["serial_number"] == serial
            assert public_gift["upgrade_level"] == 1
            assert public_gift["attributes"]["upgrade_source"] == "waysgram_internal"
            assert public_gift["attributes"]["collectible_traits"] == traits
            assert public_gift["can_upgrade"] is False
            assert public_gift["is_listed"] == 0
            # A later transfer must not reroll the old message or change its number.
            onward = await client.post(
                "/api/messages/gift", headers=headers(102),
                json={"recipient_id": ADMIN, "serial_number": serial},
            )
            assert onward.status_code == 201
            assert onward.json()["gift_unique_num"] == sent.json()["gift_unique_num"]
            assert onward.json()["gift_instance_id"] == sent.json()["gift_instance_id"]
            previous = (await client.get("/api/messages?with=102", headers=headers(101))).json()["messages"]
            old_card = next(item for item in previous if item["id"] == sent.json()["id"])
            assert old_card["gift_unique_num"] == sent.json()["gift_unique_num"]
            assert old_card["gift_attributes"]["collectible_traits"] == traits
            # Legacy data with no assigned collection number must not invent one.
            with db_split.sqlite_connect(path) as db:
                db.execute("DELETE FROM gift_unique_numbers WHERE serial_number=?", (serial,))
                db.commit()
            legacy_history = (await client.get("/api/messages?with=102", headers=headers(101))).json()["messages"]
            legacy_card = next(item for item in legacy_history if item["id"] == sent.json()["id"])
            assert legacy_card["gift_unique_num"] is None
            assert legacy_card["gift_attributes"]["collectible_traits"] == traits

    run(scenario())

def test_waysgram_auction_requires_historical_telegram_auction_origin(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=20, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=headers(101),
                json={},
            )
            assert bought.status_code == 201
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(
                f"/api/gifts/{serial}/upgrade", headers=headers(101)
            )
            assert upgraded.status_code == 200

            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert inventory[0]["auction_eligible"] == 0

            # Being upgraded/collectible is not sufficient on its own.
            rejected = await client.post(
                f"/api/gifts/{serial}/auction",
                headers=headers(101),
                json={"starting_price_grams": 5, "duration_seconds": 3600},
            )
            assert rejected.status_code == 409
            assert "исторически" in rejected.json()["detail"].lower()

            # A legacy active auction for a non-eligible model is cancelled,
            # and its locked leading bid is returned automatically.
            connection = db_split.sqlite_connect(path)
            try:
                connection.execute(
                    "UPDATE accounts SET grams=97 WHERE telegram_id=102"
                )
                now = int(time.time())
                connection.execute(
                    """INSERT INTO gift_collectible_auctions
                       (serial_number,seller_telegram_id,starting_price_grams,current_bid_grams,
                        highest_bidder_telegram_id,status,starts_at,ends_at,created_at)
                       VALUES(?,?,?,?,?,'active',?,?,?)""",
                    (serial, 101, 3, 3, 102, now, now + 3600, now),
                )
                connection.commit()
            finally:
                connection.close()
            hidden = await client.get("/api/gift-auctions", headers=headers(102))
            assert hidden.status_code == 200 and hidden.json()["count"] == 0
            assert (await client.get("/api/me", headers=headers(102))).json()["grams"] == 100

            # Historical provenance is stored independently from live catalogue availability.
            connection = db_split.sqlite_connect(path)
            try:
                connection.execute(
                    """INSERT INTO telegram_auction_gift_registry
                       (gift_id,title,auction_slug,auction_start_date,gifts_per_round,
                        discovered_at,source_payload)
                       VALUES(?,?,?,?,?,?,?)""",
                    ("g1", "🎁", "historic-g1", 1700000000, 1000, 1700000001, "{}"),
                )
                connection.execute(
                    "UPDATE telegram_gift_catalog SET is_available=0 WHERE gift_id='g1'"
                )
                connection.commit()
            finally:
                connection.close()

            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert inventory[0]["auction_eligible"] == 1
            profile = (await client.get("/api/users/101", headers=headers(102))).json()
            assert profile["gifts"][0]["auction_eligible"] == 1

            # No current Telegram sale/auction is required for a WaysGram auction.
            created = await client.post(
                f"/api/gifts/{serial}/auction",
                headers=headers(101),
                json={"starting_price_grams": 5, "duration_seconds": 3600},
            )
            assert created.status_code == 201

    run(scenario())



def test_messenger_storage_repairs_missing_gift_inventory_columns(env) -> None:
    path, _ = env
    connection = db_split.sqlite_connect(path)
    try:
        for column in ("telegram_gift_id", "gift_emoji", "telegram_gift_metadata_json"):
            connection.execute(f"ALTER TABLE messenger.user_gifts DROP COLUMN {column}")
        connection.execute(
            "ALTER TABLE messenger.telegram_gift_catalog DROP COLUMN sticker_thumbnail_file_id"
        )
        connection.commit()
    finally:
        connection.close()

    run(init_messenger_storage(path))

    connection = db_split.sqlite_connect(path)
    try:
        user_gift_columns = {
            row[1] for row in connection.execute("PRAGMA messenger.table_info(user_gifts)").fetchall()
        }
        catalog_columns = {
            row[1] for row in connection.execute("PRAGMA messenger.table_info(telegram_gift_catalog)").fetchall()
        }
    finally:
        connection.close()

    assert {"telegram_gift_id", "gift_emoji", "telegram_gift_metadata_json"} <= user_gift_columns
    assert "sticker_thumbnail_file_id" in catalog_columns


def test_animated_gift_preview_uses_static_thumbnail(env, monkeypatch: pytest.MonkeyPatch) -> None:
    path, app = env
    expected_png = _png()
    raw_webm = bytes.fromhex("1a45dfa3") + b"waysgram-test-animation"
    downloaded_file_ids: list[str] = []

    async def fake_download(bot, file_id: str, preferred_mime: str = ""):
        downloaded_file_ids.append(file_id)
        if file_id == "animated-sticker-file":
            return raw_webm, "video/webm"
        return expected_png, "image/png"

    monkeypatch.setattr(messenger_api, "fetch_telegram_sticker", fake_download)

    async def scenario() -> None:
        await messenger_api.store_telegram_gifts(
            path,
            [{
                "id": "g1",
                "sticker": {
                    "file_id": "animated-sticker-file",
                    "file_unique_id": "animated-sticker-unique",
                    "thumbnail": {"file_id": "static-thumbnail-file"},
                    "type": "video",
                    "width": 512,
                    "height": 512,
                    "is_animated": False,
                    "is_video": True,
                    "emoji": "🎁",
                    "mime_type": "video/webm",
                },
                "star_count": 25,
            }],
        )

        async with await client_for(app) as client:
            response = await client.get(
                "/api/telegram/gifts/g1/sticker",
                headers=headers(101),
            )
            assert response.status_code == 200
            assert response.headers["content-type"].startswith("image/png")
            assert response.content.startswith(bytes.fromhex("89504e470d0a1a0a"))
            assert downloaded_file_ids == ["static-thumbnail-file"]

            animated_response = await client.get(
                "/api/telegram/gifts/g1/sticker?animated=1",
                headers=headers(101),
            )
            assert animated_response.status_code == 200
            assert animated_response.headers["content-type"].startswith("video/webm")
            assert animated_response.content == raw_webm
            assert downloaded_file_ids[-1] == "animated-sticker-file"

    run(scenario())



def test_motion_only_gift_preview_prefers_provider_static_frame(env, monkeypatch: pytest.MonkeyPatch) -> None:
    path, _app = env
    expected_png = _png()
    raw_webm = bytes.fromhex("1a45dfa3") + b"motion-only-gift"
    downloaded_file_ids: list[str] = []
    preview_calls: list[str] = []

    async def fake_download(bot, file_id: str, preferred_mime: str = ""):
        downloaded_file_ids.append(file_id)
        return raw_webm, "video/webm"

    class MotionOnlyProvider:
        async def download_sticker_preview(self, gift_id: str):
            preview_calls.append(gift_id)
            return expected_png, "image/png"

        async def download_sticker(self, gift_id: str):
            return raw_webm, "video/webm"

    monkeypatch.setattr(messenger_api, "fetch_telegram_sticker", fake_download)

    async def scenario() -> None:
        await messenger_api.store_telegram_gifts(
            path,
            [{
                "id": "motion-only-gift",
                "sticker": {
                    "file_id": "motion-only-file",
                    "file_unique_id": "motion-only-unique",
                    "type": "video",
                    "width": 512,
                    "height": 512,
                    "is_animated": False,
                    "is_video": True,
                    "emoji": "🎁",
                    "mime_type": "video/webm",
                },
                "star_count": 25,
            }],
        )
        sticker_app = FastAPI()
        store = bot_module.Store(path)
        sticker_app.include_router(
            build_messenger_router(
                path,
                store,
                lambda: FakeBot(),
                bot_module.account_is_banned,
                mtproto_gift_provider=lambda: MotionOnlyProvider(),
                admin_id=ADMIN,
            )
        )

        async with await client_for(sticker_app) as client:
            still = await client.get(
                "/api/telegram/gifts/motion-only-gift/sticker?animated=0",
                headers=headers(101),
            )
            assert still.status_code == 200
            assert still.headers["content-type"].startswith("image/")
            assert not still.content.startswith(bytes.fromhex("1f8b"))
            assert not still.content.startswith(bytes.fromhex("1a45dfa3"))
            assert preview_calls == ["motion-only-gift"]
            assert downloaded_file_ids == ["motion-only-file"]

            animated = await client.get(
                "/api/telegram/gifts/motion-only-gift/sticker?animated=1",
                headers=headers(101),
            )
            assert animated.status_code == 200
            assert animated.headers["content-type"].startswith("video/webm")
            assert animated.content == raw_webm

    run(scenario())


def test_legacy_text_gift_receipt_is_hydrated_into_a_structured_gift_event(env) -> None:
    path, app = env
    gift_id = "5922558454332916696"
    receipt_body = (
        "Вы приобрели коллекционный подарок "
        + "«Telegram Gift " + gift_id + "»"
    )
    alternate_receipt_body = (
        "Квитанция: вы купили коллекционный подарок "
        + "“Telegram Gift " + gift_id + "”"
    )
    receipt_without_id_body = "Успешно: вы купили коллекционный подарок «Plush Pepe»"

    async def scenario() -> None:
        await messenger_api.store_telegram_gifts(
            path,
            [{
                "id": gift_id,
                "title": "Telegram Gift " + gift_id,
                "sticker_set_name": "AlgorithmCup",
                "sticker": {
                    "file_id": "legacy-gift-sticker",
                    "file_unique_id": "legacy-gift-sticker-unique",
                    "type": "regular",
                    "width": 512,
                    "height": 512,
                    "is_animated": False,
                    "is_video": False,
                    "emoji": "🏆",
                    "mime_type": "image/webp",
                    "set_name": "AlgorithmCup",
                },
                "star_count": 25,
            }],
        )
        connection = db_split.sqlite_connect(path)
        try:
            now = int(time.time())
            connection.executemany(
                """INSERT INTO messages
                   (sender_telegram_id,recipient_telegram_id,body,created_at)
                   VALUES(101,101,?,?)""",
                [
                    (receipt_body, now),
                    (alternate_receipt_body, now + 1),
                    (receipt_without_id_body, now + 2),
                ],
            )
            connection.commit()
        finally:
            connection.close()

        async with await client_for(app) as client:
            result = await client.get("/api/messages?with=101", headers=headers(101))
            assert result.status_code == 200
            receipt = next(
                item for item in result.json()["messages"]
                if item["body"] == receipt_body
            )
            assert receipt["gift_name"] == "Algorithm Cup"
            assert receipt["gift_telegram_gift_id"] == gift_id
            assert receipt["gift_telegram_metadata"]["title"] == "Algorithm Cup"
            assert receipt["gift_telegram_metadata"]["sticker_available"] is True
            assert receipt["gift_attributes"]["event_type"] == "purchase"
            assert receipt["gift_attributes"]["native_action"] == "star_gift_purchase"
            assert receipt["gift_attributes"]["legacy_receipt"] is True

            alternate = next(
                item for item in result.json()["messages"]
                if item["body"] == alternate_receipt_body
            )
            assert alternate["gift_telegram_gift_id"] == gift_id
            assert alternate["gift_attributes"]["event_type"] == "purchase"
            assert alternate["gift_attributes"]["native_action"] == "star_gift_purchase"

            without_id = next(
                item for item in result.json()["messages"]
                if item["body"] == receipt_without_id_body
            )
            assert without_id["gift_name"] == "Plush Pepe"
            assert without_id.get("gift_telegram_gift_id") in (None, "")
            assert without_id["gift_attributes"]["event_type"] == "purchase"
            assert without_id["gift_attributes"]["legacy_receipt"] is True

    run(scenario())


def test_purchase_snapshot_keeps_sticker_available_after_catalog_rows_are_removed(env) -> None:
    path, app = env
    expected_png = _png()
    raw_webm = bytes.fromhex("1a45dfa3") + b"snapshot-gift-animation"

    class SnapshotProvider:
        async def download_sticker_preview(self, gift_id: str):
            assert gift_id == "g1"
            return expected_png, "image/png"

        async def download_sticker(self, gift_id: str):
            assert gift_id == "g1"
            return raw_webm, "video/webm"

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=20, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                "/api/telegram/gifts/g1/purchase",
                headers=headers(101),
                json={},
            )
            assert bought.status_code == 201

        connection = db_split.sqlite_connect(path)
        try:
            connection.execute("DELETE FROM telegram_gift_catalog WHERE gift_id='g1'")
            connection.execute("DELETE FROM telegram_mtproto_gift_catalog WHERE gift_id='g1'")
            connection.commit()
        finally:
            connection.close()

        snapshot_app = FastAPI()
        snapshot_store = bot_module.Store(path)
        snapshot_app.include_router(
            build_messenger_router(
                path,
                snapshot_store,
                lambda: FakeBot(),
                bot_module.account_is_banned,
                mtproto_gift_provider=lambda: SnapshotProvider(),
                admin_id=ADMIN,
            )
        )

        async with await client_for(snapshot_app) as client:
            still = await client.get(
                "/api/telegram/gifts/g1/sticker",
                headers=headers(101),
            )
            assert still.status_code == 200
            assert still.headers["content-type"].startswith("image/png")
            assert still.content == expected_png

            animated = await client.get(
                "/api/telegram/gifts/g1/sticker?animated=1",
                headers=headers(101),
            )
            assert animated.status_code == 200
            assert animated.headers["content-type"].startswith("video/webm")
            assert animated.content == raw_webm

    run(scenario())


def test_public_profile_returns_all_owned_gifts_without_a_50_item_cap(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=1, supply=60)
        async with await client_for(app) as client:
            for index in range(55):
                purchased = await client.post(
                    "/api/telegram/gifts/g1/purchase",
                    headers={
                        **headers(101),
                        "Idempotency-Key": f"profile-gift-page-regression-{index:03d}",
                    },
                    json={},
                )
                assert purchased.status_code == 201

            profile = await client.get("/api/users/101", headers=headers(102))
            assert profile.status_code == 200
            assert len(profile.json()["gifts"]) == 55
            assert len({gift["serial_number"] for gift in profile.json()["gifts"]}) == 55

    run(scenario())


def test_gift_sell_refunds_half_of_the_purchase_price(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=40, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            serial = bought.json()["gift"]["serial_number"]
            mine = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert mine[0]["purchase_asset"] == "stars"
            assert mine[0]["purchase_price"] == 40
            assert mine[0]["sell_refund"] == 20
            assert mine[0]["sell_refund_asset"] == "stars"
            assert mine[0]["sellable"] is True
            assert mine[0]["sellable_until"] - mine[0]["acquired_at"] == 7 * 86400

            sell_headers = {**headers(101), "Idempotency-Key": "wg-gift-sell-test-0001"}
            sold = await client.post(f"/api/gifts/{serial}/sell", headers=sell_headers)
            assert sold.status_code == 200
            body = sold.json()
            assert body["status"] == "sold"
            assert body["sell_refund"] == 20 and body["sell_refund_asset"] == "stars"
            assert body["purchase_price"] == 40
            assert body["stars_balance"] == 5000 - 40 + 20
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 4980
            assert (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"] == []

            conn = db_split.sqlite_connect(path)
            try:
                transaction = conn.execute(
                    "SELECT asset,amount,balance_after,reason FROM economy_transactions "
                    "WHERE telegram_id=? AND reason=?",
                    (101, f"gift_sell:{serial}"),
                ).fetchone()
                history = conn.execute(
                    "SELECT event_type FROM gift_transactions WHERE serial_number=?", (serial,)
                ).fetchall()
            finally:
                conn.close()
            assert transaction == ("stars", 20, 4980, f"gift_sell:{serial}")
            assert ("sale",) in history

            # Replaying the same request must not refund twice.
            replayed = await client.post(f"/api/gifts/{serial}/sell", headers=sell_headers)
            assert replayed.status_code == 200 and replayed.json() == body
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 4980
            # The gift is gone, so a fresh attempt is a plain 404.
            again = await client.post(f"/api/gifts/{serial}/sell", headers=headers(101))
            assert again.status_code == 404

            # The chat receipt stays in history and is marked as sold.
            saved = await client.get("/api/messages?with=101", headers=headers(101))
            receipt = next(
                item for item in saved.json()["messages"]
                if item.get("gift_serial_number") == serial
            )
            assert receipt["gift_attributes"]["sold_at"] == body["sold_at"]
            assert receipt["gift_attributes"]["native_action"] == "star_gift_sale"
            assert receipt["gift_attributes"]["sell_refund"] == 20

    run(scenario())


def test_collectible_cannot_be_sold_for_refund(env) -> None:
    _path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=100, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(101))
            assert upgraded.status_code == 200
            sold = await client.post(f"/api/gifts/{serial}/sell", headers=headers(101))
            assert sold.status_code == 409
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 4875

    run(scenario())


def test_gift_sell_is_limited_to_the_first_week(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=40, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            serial = bought.json()["gift"]["serial_number"]
            conn = db_split.sqlite_connect(path)
            try:
                conn.execute(
                    "UPDATE user_gifts SET acquired_at=? WHERE serial_number=?",
                    (int(time.time()) - 8 * 86400, serial),
                )
                conn.commit()
            finally:
                conn.close()

            mine = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert mine[0]["sellable"] is False
            assert mine[0]["sell_refund"] == 20

            expired = await client.post(f"/api/gifts/{serial}/sell", headers=headers(101))
            assert expired.status_code == 409
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 5000 - 40
            assert len((await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]) == 1

    run(scenario())


def test_gift_that_is_up_for_sale_cannot_be_sold_back(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=40, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            serial = bought.json()["gift"]["serial_number"]
            conn = db_split.sqlite_connect(path)
            try:
                conn.execute(
                    """INSERT INTO gift_market_listings
                       (serial_number,seller_telegram_id,price_grams,status,created_at)
                       VALUES(?,?,?,'active',?)""",
                    (serial, 101, 15, int(time.time())),
                )
                conn.commit()
            finally:
                conn.close()

            mine = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert mine[0]["sellable"] is False
            blocked = await client.post(f"/api/gifts/{serial}/sell", headers=headers(101))
            assert blocked.status_code == 409
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 5000 - 40

    run(scenario())


def test_upgrade_rejects_gift_without_telegram_upgrade_capability(env) -> None:
    _path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g2", price=100, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g2/purchase", headers=headers(101), json={})
            serial = bought.json()["gift"]["serial_number"]
            preview = await client.get(f"/api/gifts/{serial}/upgrade-preview", headers=headers(101))
            upgraded = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(101))
            assert preview.status_code == upgraded.status_code == 409
            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert inventory[0]["can_upgrade"] is False
            assert inventory[0]["upgrade_level"] == 0
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 4900

    run(scenario())


def test_upgrade_without_enough_stars_returns_402(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=4990, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            assert bought.json()["stars"] == 10
            serial = bought.json()["gift"]["serial_number"]
            poor = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(101))
            assert poor.status_code == 402
            assert poor.json()["detail"] == "Недостаточно Stars"
            # A rejected upgrade changes nothing.
            mine = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert mine[0]["upgrade_level"] == 0
            assert (await client.get("/api/me", headers=headers(101))).json()["stars"] == 10

    run(scenario())


def test_collectibles_are_numbered_inside_their_own_collection(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=20, supply=2)
        await bot_module.set_telegram_gift_values("g2", price=30, supply=1)
        async with await client_for(app) as client:
            serials = []
            for _ in range(2):
                bought = await client.post(
                    "/api/telegram/gifts/g1/purchase", headers=headers(101), json={}
                )
                serials.append(bought.json()["gift"]["serial_number"])
            other = await client.post(
                "/api/telegram/gifts/g2/purchase", headers=headers(101), json={}
            )
            other_serial = other.json()["gift"]["serial_number"]
            with db_split.sqlite_connect(path) as db:
                db.execute("UPDATE telegram_gift_catalog SET upgrade_star_count=12 WHERE gift_id='g2'")
                db.commit()


            numbers = {}
            for index, serial in enumerate(serials):
                upgraded = await client.post(
                    f"/api/gifts/{serial}/upgrade",
                    headers={**headers(101), "Idempotency-Key": f"wg-number-g1-test-{index:04d}"},
                )
                assert upgraded.status_code == 200
                numbers[serial] = upgraded.json()["unique_num"]
            other_upgrade = await client.post(
                f"/api/gifts/{other_serial}/upgrade",
                headers={**headers(101), "Idempotency-Key": "wg-number-g2-test-0000"},
            )
            assert other_upgrade.status_code == 200

            # Telegram numbers every collection separately: the first upgraded
            # gift of each type is #1, the second one of the same type is #2.
            assert sorted(numbers.values()) == [1, 2]
            assert other_upgrade.json()["unique_num"] == 1

            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            by_serial = {item["serial_number"]: item for item in inventory}
            assert by_serial[serials[0]]["unique_num"] == numbers[serials[0]]
            assert by_serial[other_serial]["unique_num"] == 1

            # A second upgrade is forbidden and the original number remains unchanged.
            again = await client.post(
                f"/api/gifts/{serials[0]}/upgrade",
                headers={**headers(101), "Idempotency-Key": "wg-number-g1-test-again"},
            )
            assert again.status_code == 409
            after = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert next(g for g in after if g["serial_number"] == serials[0])["unique_num"] == numbers[serials[0]]

    run(scenario())


def test_upgrade_uses_the_official_telegram_pools(env) -> None:
    path, app = env
    plush_pepe = "5936013938331222567"

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values(plush_pepe, price=25, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                f"/api/telegram/gifts/{plush_pepe}/purchase", headers=headers(101), json={}
            )
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(
                f"/api/gifts/{serial}/upgrade",
                headers={**headers(101), "Idempotency-Key": "wg-official-pools-0001"},
            )
            assert upgraded.status_code == 200
            payload = upgraded.json()
            traits = payload["traits"]
            assert traits["version"] == 3
            assert payload["unique_num"] == 1

            import gift_upgrades

            _, pool = gift_upgrades.pool_for_gift(plush_pepe, None)
            assert pool is not None
            assert traits["model"]["name"] in {entry[0] for entry in pool["models"]}
            assert traits["pattern"]["name"] in {entry[0] for entry in pool["patterns"]}
            backdrops = {entry[0]: entry for entry in pool["backdrops"]}
            rolled_backdrop = backdrops[traits["backdrop"]["name"]]
            # The card is painted exactly with the official backdrop colours.
            assert traits["backdrop"]["center_color"] == rolled_backdrop[2]
            assert traits["backdrop"]["edge_color"] == rolled_backdrop[3]

            # The client scrolls through these candidates while it spins the reels.
            reveal = payload["roll_pool"]
            assert traits["model"]["name"] in {entry[0] for entry in reveal["models"]}
            assert traits["pattern"]["name"] in {entry[0] for entry in reveal["patterns"]}
            assert traits["backdrop"]["name"] in {entry[0] for entry in reveal["backdrops"]}

    run(scenario())


def test_upgrade_rolls_inside_the_live_telegram_attribute_set(env) -> None:
    """A complete set from Telegram is what the roll uses, so the animation travels with it."""
    path, app = env
    plush_pepe = "5936013938331222567"

    async def scenario() -> None:
        import gift_upgrades

        _, pool = gift_upgrades.pool_for_gift(plush_pepe, None)
        models = pool["models"]
        patterns = pool["patterns"]
        with db_split.sqlite_connect(path) as db:
            for kind, entries, first_document in (
                ("model", models, 7000),
                ("pattern", patterns, 8000),
            ):
                for index, entry in enumerate(entries):
                    db.execute(
                        """INSERT INTO telegram_mtproto_gift_attributes
                           (gift_id,kind,position,name,rarity,permille,document_id,dc_id,
                            mime_type,size,payload_json,is_available,fetched_at)
                           VALUES(?,?,?,?,'',0,?,2,'application/x-tgsticker',1024,'{}',1,0)""",
                        (plush_pepe, kind, index, f"Локализованное имя {index}",
                         first_document + index),
                    )
            for index, entry in enumerate(pool["backdrops"]):
                db.execute(
                    """INSERT INTO telegram_mtproto_gift_attributes
                       (gift_id,kind,position,name,rarity,permille,document_id,dc_id,
                        mime_type,size,center_color,edge_color,pattern_color,text_color,
                        payload_json,is_available,fetched_at)
                       VALUES(?,'backdrop',?,?,'',0,0,2,'',0,?,?,?,?,'{}',1,0)""",
                    (plush_pepe, index, f"Фон {index}", int(entry[2]), int(entry[3]),
                     int(entry[4]), int(entry[5])),
                )

        await bot_module.set_telegram_gift_values(plush_pepe, price=25, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                f"/api/telegram/gifts/{plush_pepe}/purchase", headers=headers(101), json={}
            )
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(
                f"/api/gifts/{serial}/upgrade",
                headers={**headers(101), "Idempotency-Key": "wg-live-roll-0001"},
            )
            assert upgraded.status_code == 200
            payload = upgraded.json()
            traits = payload["traits"]
            # Telegram localises attribute names: the roll happens inside its own set, so the
            # name and the document always belong together.
            assert traits["roll_source"] == "telegram"
            assert traits["model"]["name"].startswith("Локализованное имя")
            assert traits["model"]["document_id"] == 7000 + traits["model"]["position"]
            assert traits["model"]["pool_size"] == len(models)
            assert traits["pattern"]["name"].startswith("Локализованное имя")
            assert traits["pattern"]["document_id"] == 8000 + traits["pattern"]["position"]
            # Backdrops come from the same set: the card is painted with Telegram's colours.
            assert traits["backdrop"]["name"].startswith("Фон ")
            assert traits["backdrop"]["center_color"]

            # The reels are built from the same set, so the winner is always among them.
            reveal = payload["roll_pool"]
            reveal_models = {entry[0]: entry for entry in reveal["models"]}
            rolled_model = reveal_models[traits["model"]["name"]]
            assert rolled_model[3] == traits["model"]["document_id"]
            assert all(len(entry) == 4 for entry in reveal["models"])
            assert any(entry[3] for entry in reveal["patterns"])

            # The document survives in the stored traits, so the profile card can use it too.
            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            stored = next(item for item in inventory if item["serial_number"] == serial)
            assert (
                stored["attributes"]["collectible_traits"]["model"]["document_id"]
                == traits["model"]["document_id"]
            )

    run(scenario())


class AttributeRowsProvider:
    """Stands in for the MTProto catalog: writes Telegram's attribute rows on first use."""

    def __init__(self, models: int, patterns: int, backdrops: int = 0) -> None:
        self.models = models
        self.patterns = patterns
        self.backdrops = backdrops
        self.calls = 0

    async def attributes(self, database_path, gift_id, refresh: bool = False):
        self.calls += 1
        rows = []
        async with aiosqlite.connect(database_path) as db:
            for kind, count, base in (
                ("model", self.models, 9000),
                ("pattern", self.patterns, 8000),
            ):
                for index in range(count):
                    await db.execute(
                        """INSERT INTO telegram_mtproto_gift_attributes
                           (gift_id,kind,position,name,rarity,permille,document_id,dc_id,
                            mime_type,size,payload_json,is_available,fetched_at)
                           VALUES(?,?,?,?,'',0,?,2,'application/x-tgsticker',1024,'{}',1,0)""",
                        (gift_id, kind, index, f"Локализованное {kind} {index}", base + index),
                    )
                    rows.append({
                        "kind": kind,
                        "position": index,
                        "name": f"Локализованное {kind} {index}",
                        "rarity": "",
                        "permille": 0,
                        "document_id": base + index,
                    })
            for index in range(self.backdrops):
                await db.execute(
                    """INSERT INTO telegram_mtproto_gift_attributes
                       (gift_id,kind,position,name,rarity,permille,document_id,dc_id,
                        mime_type,size,center_color,edge_color,pattern_color,text_color,
                        payload_json,is_available,fetched_at)
                       VALUES(?,'backdrop',?,?,'',0,0,2,'',0,16777215,0,0,0,'{}',1,0)""",
                    (gift_id, index, f"Локализованный фон {index}"),
                )
                rows.append({
                    "kind": "backdrop",
                    "position": index,
                    "name": f"Локализованный фон {index}",
                    "rarity": "",
                    "permille": 0,
                    "document_id": 0,
                    "center_color": 16777215,
                })
            await db.commit()
        return rows


def test_upgrade_draws_traits_once_and_rejects_new_upgrade(env) -> None:
    path, app = env
    plush_pepe = "5936013938331222567"

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values(plush_pepe, price=25, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                f"/api/telegram/gifts/{plush_pepe}/purchase", headers=headers(101), json={}
            )
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(101))
            assert upgraded.status_code == 200
            traits = upgraded.json()["traits"]
            assert all(traits[key]["name"] for key in ("model", "pattern", "backdrop"))
            for _ in range(2):
                repeated = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(101))
                assert repeated.status_code == 409
            result = (await client.get('/api/me/gifts', headers=headers(101))).json()['gifts'][0]
            assert result['upgrade_level'] == 1
            assert result['attributes']['collectible_traits'] == traits

    run(scenario())


def test_upgrade_fetches_telegram_attribute_documents_when_missing(env) -> None:
    """Without Telegram's attribute rows the rolled model could not carry a document."""
    path, app = env
    plush_pepe = "5936013938331222567"

    async def scenario() -> None:
        import gift_upgrades

        _, pool = gift_upgrades.pool_for_gift(plush_pepe, None)
        provider = AttributeRowsProvider(
            len(pool["models"]), len(pool["patterns"]), len(pool["backdrops"])
        )
        provider_app = FastAPI()
        provider_app.include_router(
            build_messenger_router(
                path,
                bot_module.Store(path),
                lambda: FakeBot(),
                bot_module.account_is_banned,
                mtproto_gift_provider=lambda: provider,
                admin_id=ADMIN,
            )
        )
        await bot_module.set_telegram_gift_values(plush_pepe, price=25, supply=1)
        async with await client_for(provider_app) as client:
            bought = await client.post(
                f"/api/telegram/gifts/{plush_pepe}/purchase", headers=headers(101), json={}
            )
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(
                f"/api/gifts/{serial}/upgrade",
                headers={**headers(101), "Idempotency-Key": "wg-attr-sync-0001"},
            )
            assert upgraded.status_code == 200
            traits = upgraded.json()["traits"]
            assert provider.calls >= 1
            # The set is complete, so the roll happens inside Telegram's own rows and every
            # winner carries the animation it was published with.
            assert traits["roll_source"] == "telegram"
            assert traits["model"]["name"].startswith("Локализованное model")
            assert traits["model"]["document_id"] == 9000 + traits["model"]["position"]
            assert traits["pattern"]["document_id"] == 8000 + traits["pattern"]["position"]

    run(scenario())


def test_inventory_restores_missing_attribute_documents(env) -> None:
    """A gift upgraded before the catalog was known gets its document ids back."""
    path, app = env
    plush_pepe = "5936013938331222567"

    async def scenario() -> None:
        import gift_upgrades

        _, pool = gift_upgrades.pool_for_gift(plush_pepe, None)
        await bot_module.set_telegram_gift_values(plush_pepe, price=25, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                f"/api/telegram/gifts/{plush_pepe}/purchase", headers=headers(101), json={}
            )
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(
                f"/api/gifts/{serial}/upgrade",
                headers={**headers(101), "Idempotency-Key": "wg-attr-repair-0001"},
            )
            assert upgraded.status_code == 200
            traits = upgraded.json()["traits"]
            assert not int(traits["model"].get("document_id") or 0)

            # Telegram's catalog arrives after the upgrade: the next inventory read fills
            # the document ids in, so the card plays the official model and pattern.
            with db_split.sqlite_connect(path) as db:
                for kind, entries, base in (
                    ("model", pool["models"], 9000),
                    ("pattern", pool["patterns"], 8000),
                ):
                    for index, entry in enumerate(entries):
                        db.execute(
                            """INSERT INTO telegram_mtproto_gift_attributes
                               (gift_id,kind,position,name,rarity,permille,document_id,dc_id,
                                mime_type,size,payload_json,is_available,fetched_at)
                               VALUES(?,?,?,?,'',0,?,2,'application/x-tgsticker',1024,'{}',1,0)""",
                            (plush_pepe, kind, index, entry[0], base + index),
                        )

            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            stored = next(item for item in inventory if item["serial_number"] == serial)
            repaired = stored["attributes"]["collectible_traits"]
            assert repaired["model"]["document_id"] == (
                9000 + [entry[0] for entry in pool["models"]].index(repaired["model"]["name"])
            )
            assert repaired["pattern"]["document_id"] == (
                8000 + [entry[0] for entry in pool["patterns"]].index(repaired["pattern"]["name"])
            )
            assert repaired["model"]["name"] == traits["model"]["name"]

            # The repair is stored, not recomputed on every read.
            again = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            assert again[0]["attributes"]["collectible_traits"] == repaired

    run(scenario())


def test_inventory_repairs_documents_by_position_when_names_are_localised(env) -> None:
    """Old traits carry a pool name only: the slot inside Telegram's set restores the document."""
    path, app = env
    plush_pepe = "5936013938331222567"

    async def scenario() -> None:
        import gift_upgrades

        _, pool = gift_upgrades.pool_for_gift(plush_pepe, None)
        await bot_module.set_telegram_gift_values(plush_pepe, price=25, supply=1)
        async with await client_for(app) as client:
            bought = await client.post(
                f"/api/telegram/gifts/{plush_pepe}/purchase", headers=headers(101), json={}
            )
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(
                f"/api/gifts/{serial}/upgrade",
                headers={**headers(101), "Idempotency-Key": "wg-position-repair-0001"},
            )
            assert upgraded.status_code == 200
            rolled = upgraded.json()["traits"]
            assert not int(rolled["model"].get("document_id") or 0)

            # Simulate a gift rolled before strict artwork matching was introduced.
            # Legacy rows keep their established localized position repair behavior.
            with db_split.sqlite_connect(path) as db:
                record = db.execute("SELECT attributes_json FROM user_gifts WHERE serial_number=?", (serial,)).fetchone()
                old_attributes = json.loads(record[0])
                old_attributes["collectible_traits"].pop("strict_artwork", None)
                db.execute("UPDATE user_gifts SET attributes_json=? WHERE serial_number=?",
                           (json.dumps(old_attributes), serial))
                for kind, entries, base in (
                    ("model", pool["models"], 4000),
                    ("pattern", pool["patterns"], 5000),
                ):
                    for index, entry in enumerate(entries):
                        db.execute(
                            """INSERT INTO telegram_mtproto_gift_attributes
                               (gift_id,kind,position,name,rarity,permille,document_id,dc_id,
                                mime_type,size,payload_json,is_available,fetched_at)
                               VALUES(?,?,?,?,'',0,?,2,'application/x-tgsticker',1024,'{}',1,0)""",
                            (plush_pepe, kind, index, f"Имя {index}", base + index),
                        )

            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            stored = next(item for item in inventory if item["serial_number"] == serial)
            repaired = stored["attributes"]["collectible_traits"]
            model_position = [entry[0] for entry in pool["models"]].index(repaired["model"]["name"])
            pattern_position = [
                entry[0] for entry in pool["patterns"]
            ].index(repaired["pattern"]["name"])
            assert repaired["model"]["document_id"] == 4000 + model_position
            assert repaired["model"]["position"] == model_position
            assert repaired["model"]["pool_size"] == len(pool["models"])
            assert repaired["pattern"]["document_id"] == 5000 + pattern_position
            # The name the gift was rolled with never changes during a repair.
            assert repaired["model"]["name"] == rolled["model"]["name"]

    run(scenario())


def test_sample_only_catalog_does_not_replace_rolled_attribute(env) -> None:
    """A preview is not allowed to replace a differently rolled model or pattern."""
    path, app = env
    plush_pepe = "5936013938331222567"

    async def scenario() -> None:
        import gift_upgrades

        _, pool = gift_upgrades.pool_for_gift(plush_pepe, None)
        provider = AttributeRowsProvider(1, 1)
        provider_app = FastAPI()
        provider_app.include_router(
            build_messenger_router(
                path,
                bot_module.Store(path),
                lambda: FakeBot(),
                bot_module.account_is_banned,
                mtproto_gift_provider=lambda: provider,
                admin_id=ADMIN,
            )
        )
        await bot_module.set_telegram_gift_values(plush_pepe, price=25, supply=1)
        async with await client_for(provider_app) as client:
            bought = await client.post(
                f"/api/telegram/gifts/{plush_pepe}/purchase", headers=headers(101), json={}
            )
            serial = bought.json()["gift"]["serial_number"]
            upgraded = await client.post(
                f"/api/gifts/{serial}/upgrade",
                headers={**headers(101), "Idempotency-Key": "wg-sample-only-0001"},
            )
            assert upgraded.status_code == 200
            traits = upgraded.json()["traits"]
            # One row per kind is not a set to roll inside, so the published pool still rolls.
            assert traits["roll_source"] == "pool"
            assert traits["model"]["name"] in {row[0] for row in pool["models"]}
            assert traits["pattern"]["name"] in {row[0] for row in pool["patterns"]}
            assert "document_id" not in traits["model"]
            assert "document_id" not in traits["pattern"]
            with db_split.sqlite_connect(path) as db:
                balance = db.execute("SELECT stars FROM accounts WHERE telegram_id=101").fetchone()[0]
            repeated = await client.post(
                f"/api/gifts/{serial}/upgrade",
                headers={**headers(101), "Idempotency-Key": "wg-sample-only-0002"},
            )
            assert repeated.status_code == 409
            with db_split.sqlite_connect(path) as db:
                assert db.execute("SELECT stars FROM accounts WHERE telegram_id=101").fetchone()[0] == balance
                assert db.execute("SELECT upgrade_level FROM user_gifts WHERE serial_number=?", (serial,)).fetchone()[0] == 1

    run(scenario())


def test_unverified_waysgram_only_gift_cannot_upgrade(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with aiosqlite.connect(path) as db:
            await db.execute(
                """INSERT INTO user_gifts
                   (serial_number,gift_id,gift_name,owner_telegram_id,acquired_at,
                    telegram_gift_id,gift_emoji,purchase_asset,purchase_price)
                   VALUES('WG-000000000001','',  'Подарок WaysGram',101,?, '',  '🎁','stars',100)""",
                (int(time.time()),),
            )
            await db.execute("UPDATE accounts SET stars=stars+100 WHERE telegram_id=101")
            await db.commit()
        async with await client_for(app) as client:
            upgraded = await client.post("/api/gifts/WG-000000000001/upgrade", headers=headers(101))
            assert upgraded.status_code == 409, upgraded.text
            inventory = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"]
            stored = next(item for item in inventory if item["serial_number"] == "WG-000000000001")
            assert stored["upgrade_level"] == 0
            assert stored["can_upgrade"] is False

    run(scenario())


def test_public_inventory_sender_message_and_collectible_privacy(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=40, supply=3)
        async with await client_for(app) as client:
            sent = await client.post(
                "/api/telegram/gifts/g1/purchase", headers=headers(101),
                json={"recipient_id": 102, "message": "С днём рождения!"},
            )
            assert sent.status_code == 201
            serial = sent.json()["gift"]["serial_number"]
            mine = (await client.get("/api/me/gifts", headers=headers(102))).json()["gifts"][0]
            public = (await client.get("/api/users/102/gifts?limit=1", headers=headers(101))).json()
            profile = (await client.get("/api/users/102", headers=headers(101))).json()
            gift = public["gifts"][0]
            assert gift["serial_number"] == mine["serial_number"] == serial
            assert gift["owner_id"] == 102
            assert gift["sender_id"] == mine["sender_id"] == 101
            assert gift["sender"]["id"] == 101
            assert gift["gift_message"] == mine["gift_message"] == "С днём рождения!"
            assert profile["gifts"][0]["sender_id"] == 101
            assert "purchase_price" not in gift and "sell_refund" not in gift
            assert (await client.get("/api/users/102/gifts")).status_code == 401
            assert (await client.get("/api/users/987654/gifts", headers=headers(101))).status_code == 404
            assert (await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(101))).status_code == 404

            # The recipient upgrades it; neither their own nor a visitor's NFT exposes
            # the original sender or personal gift message.
            upgraded = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(102))
            assert upgraded.status_code == 200
            for url, token in (("/api/me/gifts", 102), ("/api/users/102/gifts", 101)):
                collectible = (await client.get(url, headers=headers(token))).json()["gifts"][0]
                assert collectible["upgrade_level"] == 1
                assert collectible["sender_id"] is None
                assert "sender" not in collectible
                assert collectible["gift_message"] == ""
            # The original chat remains an ordinary historical message.
            messages = (await client.get("/api/messages?with=101", headers=headers(102))).json()["messages"]
            assert any(m.get("gift_serial_number") == serial and m["body"] == "С днём рождения!" for m in messages)

    run(scenario())


def test_public_gift_inventory_pagination_and_sender_after_transfer(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=1, supply=2)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            serial = bought.json()["gift"]["serial_number"]
            sent = await client.post("/api/messages/gift", headers=headers(101), json={
                "recipient_id": 102, "serial_number": serial, "body": "Привет!",
            })
            assert sent.status_code == 201
            response = await client.get("/api/users/102/gifts?limit=1&offset=0", headers=headers(101))
            item = response.json()["gifts"][0]
            assert item["sender_id"] == 101 and item["gift_message"] == "Привет!"
            returned = await client.post("/api/gifts/transfer", headers=headers(102), json={
                "recipient_id": 101, "serial_number": serial,
            })
            assert returned.status_code == 200
            mine = (await client.get("/api/me/gifts", headers=headers(101))).json()["gifts"][0]
            assert mine["sender_id"] == 102 and mine["gift_message"] == ""

    run(scenario())


def test_public_inventory_paginates_all_gifts_without_exposing_cost(env) -> None:
    path, app = env
    with db_split.sqlite_connect(path) as db:
        db.executemany(
            """INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at,purchase_price)
               VALUES(?,?,?,?,?)""",
            [(f"WG-{i:012X}", "Подарок", 102, 1_800_000_000, 50) for i in range(205)],
        )
        db.commit()

    async def scenario() -> None:
        async with await client_for(app) as client:
            seen = []
            offset = 0
            while True:
                response = await client.get(f"/api/users/102/gifts?limit=100&offset={offset}", headers=headers(101))
                assert response.status_code == 200
                page = response.json()
                assert page["total"] == 205
                assert all("purchase_price" not in gift and gift["owner_id"] == 102 for gift in page["gifts"])
                seen.extend(gift["serial_number"] for gift in page["gifts"])
                if not page["has_more"]:
                    break
                offset = page["next_offset"]
            assert len(seen) == len(set(seen)) == 205

    run(scenario())


def test_anonymous_purchase_and_later_transfers_do_not_leak_sender_or_old_caption(env) -> None:
    _, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=1, supply=2)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={
                "recipient_id": 102, "message": "Тайное пожелание", "hide_name": True,
            })
            assert bought.status_code == 201
            serial = bought.json()["gift"]["serial_number"]
            for viewer in (101, 102):
                item = (await client.get("/api/users/102/gifts", headers=headers(viewer))).json()["gifts"][0]
                assert item["sender_id"] is None and "sender" not in item
                assert item["gift_message"] == "Тайное пожелание"
            for owner, recipient in ((102, 101), (101, 102)):
                result = await client.post("/api/gifts/transfer", headers=headers(owner), json={
                    "recipient_id": recipient, "serial_number": serial,
                })
                assert result.status_code == 200
            item = (await client.get("/api/me/gifts", headers=headers(102))).json()["gifts"][0]
            assert item["sender_id"] == 101
            assert item["gift_message"] == ""
    run(scenario())


def test_sent_gift_enqueues_one_durable_push_with_idempotency(env, monkeypatch) -> None:
    path, app = env
    monkeypatch.setattr(messenger_api, "_firebase_service_account", lambda: {"project_id": "test"})
    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=1, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            serial = bought.json()["gift"]["serial_number"]
            send_headers = {**headers(101), "Idempotency-Key": "gift-push-queue-0001"}
            payload = {"recipient_id": 102, "serial_number": serial, "body": "hello"}
            first = await client.post("/api/messages/gift", headers=send_headers, json=payload)
            second = await client.post("/api/messages/gift", headers=send_headers, json=payload)
            assert first.status_code == second.status_code == 201
            assert first.json() == second.json()
            with db_split.sqlite_connect(path) as db:
                rows = db.execute("SELECT sender_telegram_id,recipient_telegram_id,data_json FROM push_outbox").fetchall()
            assert len(rows) == 1 and rows[0][:2] == (101, 102)
            assert json.loads(rows[0][2])["type"] == "gift"
    run(scenario())


def test_shared_customization_is_bounded_and_reusable(env):
    _, app = env
    payload = {"name": "Blue", "image0": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aR/sAAAAASUVORK5CYII=", "image1": ""}

    async def scenario():
        async with await client_for(app) as client:
            path = "/api/customizations"
            assert (await client.post(path, json=payload)).status_code == 401
            assert (await client.post(path, headers=headers(101), json={**payload, "name": " "})).status_code == 400
            assert (await client.post(path, headers=headers(101), json={**payload, "image0": "broken!"})).status_code == 400
            assert (await client.post(path, headers=headers(101), content=b"x" * 355001)).status_code == 413
            shared = await client.post(path, headers=headers(101), json=payload)
            assert shared.status_code == 200, shared.text
            token = shared.json()["token"]
            assert len(token) == 32
            assert (await client.post(path, headers=headers(101), json=payload)).json()["token"] == token
            assert (await client.get(f"{path}/{token}")).status_code == 401
            assert (await client.get(f"{path}/{token}", headers=headers(102))).json() == payload
            assert (await client.get(f"{path}/bad", headers=headers(102))).status_code == 404

    run(scenario())


def test_worn_pattern_recovers_legacy_model_id_by_exact_name(env, monkeypatch):
    path, app = env
    serial = "WG-000000000010"
    traits = {"model": {"name": "Model", "document_id": 10},
              "pattern": {"name": "Actual Pattern", "document_id": 10}}
    with db_split.sqlite_connect(path) as db:
        db.execute(
            "INSERT INTO user_gifts(serial_number,gift_name,telegram_gift_id,owner_telegram_id,acquired_at,upgrade_level,profile_worn,attributes_json) VALUES(?,?,?,101,1,1,1,?)",
            (serial, "Legacy gift", "100", json.dumps({"collectible_traits": traits})),
        )
        db.commit()

    async def documents(db, gift_id):
        return {"pattern": [{"name": "Other", "document_id": 11, "position": 0},
                            {"name": "Actual Pattern", "document_id": 12, "position": 1}]}

    monkeypatch.setattr(messenger_api, "_gift_attribute_documents", documents)

    async def scenario():
        async with await client_for(app) as client:
            response = await client.get("/api/users/101", headers=headers(102))
            assert response.status_code == 200, response.text
            worn = response.json()["worn_gift"]["traits"]
            assert worn["model"]["document_id"] == 10
            assert worn["pattern"]["document_id"] == 12

    run(scenario())


def test_worn_gift_is_public_exclusive_and_clears_on_transfer(env) -> None:
    path, app = env
    with db_split.sqlite_connect(path) as db:
        db.executemany(
            "INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at,upgrade_level) VALUES(?,?,?,1,?)",
            [(f"WG-{i:012X}", f"Gift {i}", uid, level) for i, uid, level in (
                (1, 101, 1), (2, 101, 1), (3, 101, 0), (4, 102, 1),
            )],
        )
        db.commit()

    async def scenario() -> None:
        async with await client_for(app) as client:
            async def wear(i, value=True, uid=101):
                return await client.put(f"/api/me/gifts/WG-{i:012X}/wear", headers=headers(uid), json={"worn": value})

            assert (await wear(3)).status_code == 409
            assert (await wear(1, uid=102)).status_code == 404
            assert (await wear(1, value="true")).status_code == 400
            assert (await wear(1)).json()["worn_gift"]["title"] == "Gift 1"
            assert (await wear(2)).json()["worn_gift"]["title"] == "Gift 2"
            assert (await wear(1, False)).json()["worn_gift"]["title"] == "Gift 2"
            assert (await client.get("/api/users/101", headers=headers(102))).json()["worn_gift"]["title"] == "Gift 2"
            visibility = f"/api/me/gifts/WG-{2:012X}/visibility"
            assert (await client.put(visibility, headers=headers(101), json={"visible": False})).status_code == 200
            assert (await client.get("/api/users/101", headers=headers(102))).json()["worn_gift"] is None
            public = (await client.get("/api/users/101/gifts", headers=headers(102))).json()["gifts"]
            assert all(item["serial_number"] != f"WG-{2:012X}" for item in public)
            assert (await wear(2)).json()["worn_gift"]["title"] == "Gift 2"
            public = (await client.get("/api/users/101/gifts", headers=headers(102))).json()["gifts"]
            assert any(item["serial_number"] == f"WG-{2:012X}" for item in public)
            assert (await wear(2, False)).json()["worn_gift"] is None
            assert (await wear(2)).status_code == 200
            assert (await wear(4, uid=102)).status_code == 200
            with db_split.sqlite_connect(path) as db:
                db.execute("UPDATE user_gifts SET owner_telegram_id=102 WHERE serial_number='WG-000000000002'")
                db.commit()
            assert (await client.get("/api/me", headers=headers(101))).json()["worn_gift"] is None
            assert (await client.get("/api/me", headers=headers(102))).json()["worn_gift"]["title"] == "Gift 4"

    run(scenario())


def test_profile_pins_require_owned_collectibles_and_survive_paging(env) -> None:
    path, app = env
    with db_split.sqlite_connect(path) as db:
        db.executemany(
            "INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at,upgrade_level) VALUES(?,?,101,1,?)",
            [(f"WG-{i:012X}", "Gift", int(i != 8)) for i in range(1, 9)],
        )
        db.commit()

    async def scenario() -> None:
        async with await client_for(app) as client:
            async def pin(i, value=True, uid=101):
                return await client.put(f"/api/me/gifts/WG-{i:012X}/pin", headers=headers(uid), json={"pinned": value})

            assert (await pin(1, uid=102)).status_code == 404
            assert (await pin(8)).status_code == 409
            for i in (1, 2, 3, 4, 5, 6, 6):
                assert (await pin(i)).status_code == 200
            assert (await pin(7)).status_code == 409
            public = await client.get("/api/users/101/gifts?limit=6", headers=headers(102))
            rows = public.json()["gifts"]
            assert all(gift["profile_pinned"] for gift in rows)
            assert {gift["serial_number"] for gift in rows} == {f"WG-{i:012X}" for i in range(1, 7)}
            assert (await pin(1, False)).status_code == 200
            assert (await pin(7)).status_code == 200
            with db_split.sqlite_connect(path) as db:
                db.execute("UPDATE user_gifts SET owner_telegram_id=102 WHERE serial_number='WG-000000000007'")
                db.commit()
            recipient = await client.get("/api/me/gifts", headers=headers(102))
            assert recipient.json()["gifts"][0]["profile_pinned"] == 0

    run(scenario())


def test_gift_profile_order_is_atomic_private_and_survives_transfers(env) -> None:
    path, app = env
    with db_split.sqlite_connect(path) as db:
        db.executemany(
            "INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at) VALUES(?,?,?,?)",
            [(f"WG-{i:012X}", "Gift", 101, 1) for i in range(1, 5)],
        )
        db.commit()

    async def scenario() -> None:
        async with await client_for(app) as client:
            async def ids(url, user=101):
                response = await client.get(url, headers=headers(user))
                assert response.status_code == 200
                return [x['id'] for x in response.json()['gifts']]

            original = await ids('/api/me/gifts')
            order = [original[3], original[1], original[2], original[0]]
            assert (await client.put('/api/me/gifts/order', headers=headers(101), json={'expected': original, 'order': order})).status_code == 200
            assert await ids('/api/me/gifts') == order
            assert await ids('/api/users/101/gifts', 102) == order
            assert await ids('/api/users/101/gifts?limit=2&offset=2', 102) == order[2:]
            assert (await client.put('/api/me/gifts/order', headers=headers(101), json={'expected': original, 'order': order})).status_code == 409
            assert (await client.put('/api/me/gifts/order', headers=headers(102), json={'expected': order, 'order': original})).status_code == 409
            assert (await client.put('/api/me/gifts/order', headers=headers(101), json={'expected': order, 'order': [order[0]] * 4})).status_code == 400
            assert await ids('/api/me/gifts') == order
            with db_split.sqlite_connect(path) as db:
                db.execute('UPDATE user_gifts SET owner_telegram_id=102 WHERE id=?', (order[1],))
                db.execute('UPDATE user_gifts SET owner_telegram_id=101 WHERE id=?', (order[1],))
                db.execute("INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at) VALUES('WG-000000000099','new',101,1)")
                db.commit()
            current = await ids('/api/me/gifts')
            assert current[1] == order[1]
            assert current[2:] == [order[0], order[2], order[3]]
            assert (await client.put('/api/me/gifts/order', headers=headers(101), json={'expected': order, 'order': original})).status_code == 409
    run(scenario())


def test_gift_order_migration_and_concurrent_reorders(env) -> None:
    path, app = env
    with db_split.sqlite_connect(path) as db:
        db.execute('DROP TRIGGER messenger.reset_gift_profile_order')
        db.execute('ALTER TABLE messenger.user_gifts DROP COLUMN profile_order')
        db.executemany(
            'INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at) VALUES(?,?,?,?)',
            [(f'WG-{i:012X}', 'Gift', 101, 1) for i in range(20, 23)],
        )
        db.commit()
    run(init_messenger_storage(path))
    run(init_messenger_storage(path))  # migration and trigger creation are idempotent

    async def scenario():
        async with await client_for(app) as client:
            original = [g['id'] for g in (await client.get('/api/me/gifts', headers=headers(101))).json()['gifts']]
            one = list(reversed(original))
            two = [original[1], original[0], original[2]]
            responses = await asyncio.gather(*[
                client.put('/api/me/gifts/order', headers=headers(101), json={'expected': original, 'order': order})
                for order in (one, two)
            ])
            assert sorted(r.status_code for r in responses) == [200, 409]
            saved = [g['id'] for g in (await client.get('/api/me/gifts', headers=headers(101))).json()['gifts']]
            assert saved == (one if responses[0].status_code == 200 else two)
            assert (await client.put('/api/me/gifts/order', json={'expected': saved, 'order': saved})).status_code == 401
            assert (await client.put('/api/me/gifts/order', headers=headers(101), json={'expected': saved, 'order': [True] * len(saved)})).status_code == 400
    run(scenario())


def test_heart_locket_offline_models_match_exact_traits_without_changing_database(env) -> None:
    import gzip
    import hashlib
    import gift_model_fallbacks
    path, app = env
    gift_id = gift_model_fallbacks.HEART_LOCKET_ID
    manifest_path = Path(messenger_api.__file__).parent / 'assets/gift_models' / gift_id / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    assert len(manifest['models']) == 29
    for model in manifest['models']:
        raw = (manifest_path.parent / model['file']).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == model['sha256']
        assert json.loads(gzip.decompress(raw))['layers']
        assert gift_model_fallbacks.model_for_name(gift_id, model['name'])['document_id'] == model['document_id']
    assert gift_model_fallbacks.model_for_name(gift_id, 'White Wolf') is None
    assert gift_model_fallbacks.model_for_name('1', 'Mirror') is None
    original = {
        'version': 3, 'model': {'name': 'Mirror', 'permille': 10},
        'pattern': {'name': 'Heart', 'permille': 5},
        'backdrop': {'name': 'Blue', 'center_color': 1},
    }
    legacy = {'model': 'Queen Bee', 'pattern': 'Heart', 'backdrop': 'Blue'}
    authoritative = {**original, 'model': {'name': 'Mirror', 'document_id': 123456}}
    with db_split.sqlite_connect(path) as db:
        for idx, (gid, traits) in enumerate(((gift_id, original), (gift_id, legacy), (gift_id, authoritative), ('1', original))):
            db.execute('''INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at,telegram_gift_id,upgrade_level,attributes_json)
                          VALUES(?,?,?,?,?,1,?)''', (f'WG-{idx:012X}', 'Heart Locket', 101, 1, gid,
                                                    json.dumps({'collectible_traits': traits})))
        db.commit()

    async def scenario():
        async with await client_for(app) as client:
            response = await client.get('/api/me/gifts', headers=headers(101))
            assert response.status_code == 200
            items = {g['serial_number']: g for g in response.json()['gifts']}
            by_serial = lambda i: items[f'WG-{i:012X}']['attributes']['collectible_traits']['model']
            model = by_serial(0)
            assert model['name'] == 'Mirror' and model['document_id'] < 0
            assert items['WG-000000000000']['model_document_id'] == str(model['document_id'])
            assert by_serial(1)['name'] == 'Queen Bee' and by_serial(1)['document_id'] < 0
            assert by_serial(2)['document_id'] == 123456
            assert 'document_id' not in by_serial(3)
            endpoint = f"/api/telegram/gifts/{gift_id}/attributes/{model['document_id']}"
            assert (await client.get(endpoint)).status_code == 401
            result = await client.get(endpoint, headers=headers(101))
            assert result.status_code == 200 and result.headers['content-type'].startswith('application/x-tgsticker')
            assert json.loads(gzip.decompress(result.content))['layers']
            public = await client.get('/api/users/101/gifts', headers=headers(102))
            assert public.status_code == 200
            assert any(g['attributes']['collectible_traits'].get('model', {}).get('document_id') == model['document_id'] for g in public.json()['gifts'] if isinstance(g['attributes']['collectible_traits'].get('model'), dict))
            # A real Telegram ID in the pinned source can also be served when MTProto is down.
            mirror = next(x for x in manifest['models'] if x['name'] == 'Mirror')
            real = await client.get(f"/api/telegram/gifts/{gift_id}/attributes/{mirror['telegram_document_id']}", headers=headers(101))
            assert real.status_code == 200 and real.content == result.content
            assert (await client.get(f"/api/telegram/gifts/1/attributes/{model['document_id']}", headers=headers(101))).status_code != 200
        with db_split.sqlite_connect(path) as db:
            rows = db.execute('SELECT serial_number,attributes_json FROM user_gifts').fetchall()
        saved = {row[0]: json.loads(row[1])['collectible_traits'] for row in rows}
        assert saved['WG-000000000000'] == original
        assert saved['WG-000000000001'] == legacy
    run(scenario())


def test_single_upgrade_is_atomic_and_survives_transfer_and_legacy_levels(env) -> None:
    path, app = env

    async def scenario():
        await bot_module.set_telegram_gift_values('g1', price=20, supply=1)
        async with await client_for(app) as client:
            bought = await client.post('/api/telegram/gifts/g1/purchase', headers=headers(101), json={})
            serial = bought.json()['gift']['serial_number']
            url = f'/api/gifts/{serial}/upgrade'
            requests = [client.post(url, headers={**headers(101), 'Idempotency-Key': f'wg-upgrade-race-{i:04d}'}) for i in range(2)]
            results = await asyncio.gather(*requests)
            assert sorted(r.status_code for r in results) == [200, 409]
            successful = next(r for r in results if r.status_code == 200)
            winning_key = 'wg-upgrade-race-0000' if results[0].status_code == 200 else 'wg-upgrade-race-0001'
            replay = await client.post(url, headers={**headers(101), 'Idempotency-Key': winning_key})
            assert replay.status_code == 200 and replay.json() == successful.json()
            with db_split.sqlite_connect(path) as db:
                assert db.execute('SELECT stars FROM accounts WHERE telegram_id=101').fetchone()[0] == 4955
                assert db.execute("SELECT COUNT(*) FROM gift_transactions WHERE serial_number=? AND event_type='upgrade'", (serial,)).fetchone()[0] == 1
                before = db.execute('SELECT upgrade_level, attributes_json FROM user_gifts WHERE serial_number=?', (serial,)).fetchone()
            sent = await client.post('/api/gifts/transfer', headers=headers(101), json={'serial_number': serial, 'recipient_id': 102})
            assert sent.status_code == 200
            rejected = await client.post(url, headers=headers(102))
            assert rejected.status_code == 409
            with db_split.sqlite_connect(path) as db:
                assert db.execute('SELECT upgrade_level, attributes_json FROM user_gifts WHERE serial_number=?', (serial,)).fetchone() == before
                # Existing multi-level collectibles must also remain unchanged.
                db.execute('UPDATE user_gifts SET upgrade_level=3 WHERE serial_number=?', (serial,))
                db.commit()
            assert (await client.post(url, headers=headers(102))).status_code == 409
            with db_split.sqlite_connect(path) as db:
                db.execute('UPDATE user_gifts SET upgrade_level=0 WHERE serial_number=?', (serial,))
                db.commit()
            assert (await client.post(url, headers=headers(102))).status_code == 409  # legacy traits alone prove it is unique
    run(scenario())


def test_shield_of_sun_uses_known_id_without_changing_existing_traits(env) -> None:
    import gift_model_fallbacks
    path, app = env
    gift_id = gift_model_fallbacks.HEART_LOCKET_ID
    traits = {'version': 3, 'model': {'name': 'Shield of Sun', 'permille': 15},
              'pattern': {'name': 'Carrot', 'permille': 6}, 'backdrop': {'name': 'Sapphire'}}
    with db_split.sqlite_connect(path) as db:
        db.execute('''INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at,telegram_gift_id,upgrade_level,attributes_json)
                      VALUES('WG-000000000099','Heart Locket',101,1,?,1,?)''',
                   (gift_id, json.dumps({'collectible_traits': traits})))
        db.commit()
    async def scenario():
        async with await client_for(app) as client:
            inventory = (await client.get('/api/me/gifts', headers=headers(101))).json()['gifts']
            model = inventory[0]['attributes']['collectible_traits']['model']
            assert model['name'] == 'Shield of Sun' and model['document_id'] == 5226650538359158786
            # Merely knowing the ID does not guarantee Telegram will serve the bytes.
            response = await client.get(f'/api/telegram/gifts/{gift_id}/attributes/{model["document_id"]}', headers=headers(101))
            assert response.status_code == 503
        with db_split.sqlite_connect(path) as db:
            attrs = json.loads(db.execute('SELECT attributes_json FROM user_gifts').fetchone()[0])
        assert attrs['collectible_traits'] == traits
    run(scenario())


def test_startup_preserves_legacy_collectible_traits(env) -> None:
    path, _ = env
    traits = {'model': {'name': 'Shield of Sun', 'permille': 15},
              'pattern': {'name': 'Carrot', 'permille': 6}, 'backdrop': {'name': 'Sapphire'}}
    attributes = {'collectible_traits': traits, 'old_metadata': True}
    with db_split.sqlite_connect(path) as db:
        db.execute('''INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at,
                      telegram_gift_id,upgrade_level,attributes_json)
                      VALUES('WG-000000000123','Heart Locket',101,1,?,1,?)''',
                   ('5868455043362980631', json.dumps(attributes)))
        db.commit()
    run(init_messenger_storage(path))
    run(init_messenger_storage(path))
    with db_split.sqlite_connect(path) as db:
        saved = db.execute('SELECT attributes_json FROM user_gifts WHERE serial_number=?',
                           ('WG-000000000123',)).fetchone()[0]
    assert json.loads(saved) == attributes


def test_collectible_chat_transfer_concurrent_ownership_and_retry(env) -> None:
    path, app = env
    async def scenario():
        await bot_module.set_telegram_gift_values('g1', price=10, supply=1)
        async with await client_for(app) as client:
            purchase = await client.post('/api/telegram/gifts/g1/purchase', headers=headers(101), json={})
            serial = purchase.json()['gift']['serial_number']
            upgrade = await client.post(f'/api/gifts/{serial}/upgrade', headers=headers(101))
            assert upgrade.status_code == 200
            traits = upgrade.json()['attributes']['collectible_traits']
            before = (await client.get('/api/me/gifts', headers=headers(101))).json()['gifts'][0]
            body = {'serial_number': serial, 'recipient_id': 102}
            key = {**headers(101), 'Idempotency-Key': 'nft-ui-transfer-key-00001'}
            other = {**headers(101), 'Idempotency-Key': 'nft-ui-transfer-key-00002'}
            first, competing = await asyncio.gather(
                client.post('/api/messages/gift', headers=key, json=body),
                client.post('/api/messages/gift', headers=other, json={'serial_number': serial, 'recipient_id': ADMIN}))
            assert sorted((first.status_code, competing.status_code)) == [201, 404]
            winner, recipient = (first, 102) if first.status_code == 201 else (competing, ADMIN)
            winner_headers, winner_body = (key, body) if recipient == 102 else (other, {'serial_number': serial, 'recipient_id': ADMIN})
            retry = await client.post('/api/messages/gift', headers=winner_headers, json=winner_body)
            assert retry.status_code == 201 and retry.json() == winner.json()
            assert (await client.post('/api/messages/gift', headers=winner_headers, json={**winner_body, 'recipient_id': 101})).status_code in (400, 409)
            assert (await client.get('/api/me/gifts', headers=headers(101))).json()['gifts'] == []
            received = (await client.get('/api/me/gifts', headers=headers(recipient))).json()['gifts'][0]
            assert received['serial_number'] == serial
            assert received['upgrade_level'] == 1
            assert received['attributes']['collectible_traits'] == traits
            assert received['unique_num'] == before['unique_num']
            for viewer, peer in ((101, recipient), (recipient, 101)):
                history = (await client.get(f'/api/messages?with={peer}', headers=headers(viewer))).json()['messages']
                card = next(m for m in history if m['id'] == winner.json()['id'])
                assert card['gift_upgrade_level'] == 1 and card['gift_attributes']['collectible_traits'] == traits
            with db_split.sqlite_connect(path) as db:
                assert db.execute("SELECT COUNT(*) FROM gift_transactions WHERE serial_number=? AND event_type='chat_send'", (serial,)).fetchone()[0] == 1
                assert db.execute('SELECT COUNT(*) FROM message_gifts WHERE serial_number=? AND message_id=?', (serial, winner.json()['id'])).fetchone()[0] == 1
    run(scenario())


def test_upgrade_preview_is_private_read_only_and_upgrade_keeps_anonymous_origin(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=1, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={
                "recipient_id": 102, "message": "Личное пожелание", "hide_name": True,
            })
            serial = bought.json()["gift"]["serial_number"]
            url = f"/api/gifts/{serial}/upgrade-preview"
            assert (await client.get(url, headers=headers(101))).status_code == 404
            assert (await client.get(url)).status_code in (401, 403)
            before = (await client.get("/api/me", headers=headers(102))).json()["stars"]
            preview = await client.get(url, headers=headers(102))
            assert preview.status_code == 200, preview.text
            assert preview.json()["price_stars"] == 25
            assert all(preview.json()["candidates"][slot] for slot in ("models", "patterns", "backdrops"))
            assert (await client.get("/api/me", headers=headers(102))).json()["stars"] == before
            key = {**headers(102), "Idempotency-Key": "wg-preserve-details-001"}
            upgraded = await client.post(f"/api/gifts/{serial}/upgrade", headers=key,
                                         json={"keep_original_details": True})
            assert upgraded.status_code == 200, upgraded.text
            details = upgraded.json()["attributes"]["original_details"]
            assert details["sender_id"] is None
            assert details["recipient_id"] == 102
            assert details["message"] == "Личное пожелание"
            assert (await client.post(f"/api/gifts/{serial}/upgrade", headers=key,
                                      json={"keep_original_details": True})).json() == upgraded.json()
            assert (await client.post(f"/api/gifts/{serial}/upgrade", headers=key,
                                      json={"keep_original_details": False})).status_code == 409
            assert (await client.get(url, headers=headers(102))).status_code == 409
    run(scenario())


def test_effective_reveal_pool_matches_per_slot_roll_source() -> None:
    import gift_upgrades
    base = {"models": [["Fallback", 1]], "patterns": [["Pattern", 1]],
            "backdrops": [["Background", 1, 1, 2, 3, 4]]}
    rows = {"model": [{"name": "No art", "document_id": 0},
                      {"name": "Only one with art", "document_id": 1}],
            "pattern": [{"name": "Live A", "document_id": 1},
                        {"name": "Live B", "document_id": 2}]}
    effective = gift_upgrades.effective_reveal_pool(base, rows)
    assert effective["models"] == base["models"]
    assert [x[0] for x in effective["patterns"]] == ["Live A", "Live B"]
    assert effective["backdrops"] == base["backdrops"]


def test_upgrade_origin_fails_closed_when_original_receipt_was_lost(env) -> None:
    path, app = env

    async def scenario() -> None:
        await bot_module.set_telegram_gift_values("g1", price=1, supply=1)
        async with await client_for(app) as client:
            bought = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={
                "recipient_id": 102, "hide_name": True,
            })
            serial = bought.json()["gift"]["serial_number"]
            conn = db_split.sqlite_connect(path)
            try:
                conn.execute("DELETE FROM message_gifts WHERE serial_number=?", (serial,))
                conn.commit()
            finally:
                conn.close()
            response = await client.post(f"/api/gifts/{serial}/upgrade", headers=headers(102),
                                         json={"keep_original_details": True})
            assert response.status_code == 200, response.text
            assert response.json()["attributes"]["original_details"]["sender_id"] is None
    run(scenario())


def test_reveal_art_never_matches_by_position_or_chance_alone() -> None:
    pool = {"models": [["Known", 20], ["Other", 20], ["Ambiguous", 10]]}
    documents = {"model": [{"name": "Known", "document_id": 10, "permille": 20},
                           {"name": "Wrong", "document_id": 11, "permille": 20},
                           {"name": "Ambiguous", "document_id": 12},
                           {"name": "Ambiguous", "document_id": 13}]}
    assert messenger_api._reveal_pool_documents(pool, documents)["model"] == {0: 10}


def test_new_rolls_never_infer_artwork_from_an_unrelated_row() -> None:
    traits = {"strict_artwork": True, "model": {"name": "Actual", "permille": 10},
              "pattern": {"name": "Exact", "permille": 10}}
    rows = {"model": [{"name": "Unrelated", "permille": 10, "position": 0, "document_id": 1},
                      {"name": "Other", "permille": 20, "position": 1, "document_id": 2}],
            "pattern": [{"name": "Exact", "permille": 10, "position": 0, "document_id": 3}]}
    messenger_api._enrich_traits_documents(traits, {"models": [["Actual", 10], ["Other", 20]]}, rows)
    assert "document_id" not in traits["model"]
    assert traits["pattern"]["document_id"] == 3


def test_inventory_uses_live_waysgram_shop_stock(env):
    path, app = env
    with db_split.sqlite_connect(path) as db:
        db.execute("""INSERT INTO user_gifts(serial_number,telegram_gift_id,gift_name,owner_telegram_id,acquired_at,upgrade_level)
                      VALUES('WG-000000000001','stock-test','Gift',101,1,1)""")
        db.execute("INSERT INTO telegram_gift_prices(gift_id,price_stars,supply,sold) VALUES('stock-test',25,2000,123)")
        db.commit()

    async def scenario():
        async with await client_for(app) as client:
            for url, viewer in [('/api/me/gifts', 101), ('/api/users/101/gifts', 102)]:
                response = await client.get(url, headers=headers(viewer))
                assert response.status_code == 200, response.text
                gift = response.json()['gifts'][0]
                assert (gift['shop_remaining'], gift['shop_supply']) == (1877, 2000)

    run(scenario())


def test_gift_autosave_keeps_hidden_gifts_in_owner_inventory(env):
    _, app = env

    async def scenario():
        await bot_module.set_telegram_gift_values('g1', price=1, supply=2)
        async with await client_for(app) as client:
            sender, owner = headers(101), headers(102)
            assert (await client.put('/api/me/privacy/gift-autosave', headers=owner, json={'mode': 'nobody'})).status_code == 200
            bought = await client.post('/api/telegram/gifts/g1/purchase', headers=sender, json={})
            assert bought.status_code == 201 or bought.status_code == 200
            serial = bought.json()['gift']['serial_number']
            sent = await client.post('/api/messages/gift', headers=sender, json={'recipient_id': 102, 'serial_number': serial})
            assert sent.status_code == 201
            assert (await client.get('/api/users/102/gifts', headers=sender)).json()['total'] == 0
            assert (await client.get('/api/me/gifts', headers=owner)).json()['gifts'][0]['profile_hidden'] == 1
            endpoint = f'/api/me/gifts/{serial}/visibility'
            assert (await client.put(endpoint, headers=sender, json={'visible': True})).status_code == 404
            assert (await client.put(endpoint, headers=owner, json={'visible': True})).status_code == 200
            assert (await client.get('/api/users/102/gifts', headers=sender)).json()['total'] == 1

    run(scenario())


def test_blocked_sender_cannot_deliver_gifts_through_any_route(env):
    _, app = env

    async def scenario():
        await bot_module.set_telegram_gift_values('g1', price=1, supply=2)
        async with await client_for(app) as client:
            sender, recipient = headers(101), headers(102)
            buy = '/api/telegram/gifts/g1/purchase'
            assert (await client.post('/api/blocks', headers=recipient, json={'user_id': 101})).status_code == 201
            assert (await client.post(buy, headers=sender, json={'recipient_id': 102, 'hide_name': True})).status_code == 403
            serial = (await client.post(buy, headers=sender, json={})).json()['gift']['serial_number']
            delivery = {'recipient_id': 102, 'serial_number': serial}
            for route in ('/api/gifts/transfer', '/api/messages/gift'):
                assert (await client.post(route, headers=sender, json=delivery)).status_code == 403
            mine = (await client.get('/api/me/gifts', headers=sender)).json()['gifts']
            assert [gift['serial_number'] for gift in mine] == [serial]
            assert (await client.get('/api/me/gifts', headers=recipient)).json()['total'] == 0
            assert (await client.delete('/api/blocks/101', headers=recipient)).status_code == 200
            assert (await client.post('/api/gifts/transfer', headers=sender, json=delivery)).status_code == 200
            assert (await client.post(buy, headers=sender, json={'recipient_id': 102})).status_code in (200, 201)

    run(scenario())


def test_recipient_gift_type_filter_applies_before_purchase_and_transfer(env):
    path, app = env
    serial = 'WG-000000000045'
    with db_split.sqlite_connect(path) as db:
        db.execute('INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at,upgrade_level) VALUES(?,?,?,?,?)',
                   (serial, 'Collectible', 101, 1_800_000_000, 1))
        db.commit()

    async def scenario():
        await bot_module.set_telegram_gift_values('g1', price=1, supply=1)
        async with await client_for(app) as client:
            sender, owner = headers(101), headers(102)
            settings = '/api/me/gift-receive'
            assert (await client.get(settings, headers=owner)).json()['allowed_types'] == ['regular', 'collectible']
            assert (await client.put(settings, headers=owner, json={'allowed_types': ['unknown']})).status_code == 400
            assert (await client.put(settings, headers=owner, json={'allowed_types': ['collectible']})).status_code == 200
            route = '/api/telegram/gifts/g1/purchase'
            assert (await client.post(route, headers=sender, json={'recipient_id': 102})).json()['detail'] == 'GIFT_TYPE_FORBIDDEN'
            assert (await client.put(settings, headers=owner, json={'allowed_types': ['regular']})).status_code == 200
            delivery = {'recipient_id': 102, 'serial_number': serial}
            for endpoint in ('/api/gifts/transfer', '/api/messages/gift'):
                assert (await client.post(endpoint, headers=sender, json=delivery)).json()['detail'] == 'GIFT_TYPE_FORBIDDEN'
            assert (await client.post(route, headers=sender, json={'recipient_id': 102})).status_code in (200, 201)
            assert (await client.put(settings, headers=owner, json={'allowed_types': ['collectible']})).status_code == 200
            assert (await client.post('/api/gifts/transfer', headers=sender, json=delivery)).status_code == 200

    run(scenario())
