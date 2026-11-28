"""WaysGram's own gift: bundled models, catalogue row, upgrade pool and admin control."""

import asyncio
import hashlib
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
import gift_model_fallbacks
import gift_upgrades
import messenger_api
import waysgram_gifts
from messenger_api import build_messenger_router, init_messenger_storage
from test_messenger_api import FakeBot, add_account, add_session, client_for, run

ADMIN = 99101
BUYER = 99102
PACK = Path(messenger_api.__file__).with_name("assets") / "gift_models" / waysgram_gifts.GIFT_ID


@pytest.fixture
def env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    path = tmp_path / "waysgram.sqlite3"
    monkeypatch.setattr(bot_module, "DATABASE_PATH", path)
    store = bot_module.Store(path)
    store.configure_otp_encryption(Fernet.generate_key().decode("ascii"))
    run(store.init())
    run(bot_module.init_admin_storage())
    run(init_messenger_storage(path))

    # The storefront refreshes Telegram's catalogue first; this test is about our own gift.
    async def empty_catalog(_bot):
        return []

    monkeypatch.setattr(messenger_api, "fetch_telegram_gifts", empty_catalog)

    app = FastAPI()
    app.include_router(
        build_messenger_router(path, store, lambda: FakeBot(), bot_module.account_is_banned, admin_id=ADMIN)
    )
    for uid, name in ((ADMIN, "boss"), (BUYER, "buyer")):
        run(store.upsert_user(uid, uid))
        run(store.set_verified(uid, True))
        number = run(store.allocate_number(uid))
        run(bot_module.ensure_account(uid, number, name, f"User {uid}"))
        add_session(path, uid, f"s{uid}-session-token")
    return path, app


def headers(uid: int) -> dict[str, str]:
    return {"Authorization": f"Bearer s{uid}-session-token"}


# ------------------------------------------------------------------ bundled artwork


def test_pack_contains_every_model_with_matching_bytes() -> None:
    manifest = json.loads((PACK / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["gift_id"] == waysgram_gifts.GIFT_ID
    assert len(manifest["models"]) == 15  # the shop animation plus 14 upgrade models
    assert len(waysgram_gifts.models(waysgram_gifts.GIFT_ID)) == 14
    for row in manifest["models"]:
        data = (PACK / row["file"]).read_bytes()
        assert len(data) == int(row["bytes"]), row["file"]
        assert hashlib.sha256(data).hexdigest() == row["sha256"], row["file"]
        # WebM: alpha survives only because every file is a Matroska stream.
        assert data[:4] == b"\x1aE\xdf\xa3", row["file"]
        poster = (PACK / row["file"].replace(".webm", ".png")).read_bytes()
        assert poster[:8] == b"\x89PNG\r\n\x1a\n", row["file"]


def test_sticker_and_models_are_bundled_files() -> None:
    animation = waysgram_gifts.sticker(waysgram_gifts.GIFT_ID, True)
    still = waysgram_gifts.sticker(waysgram_gifts.GIFT_ID, False)
    assert animation and animation[1] == "video/webm" and animation[0].name == "main.webm"
    assert still and still[1] == "image/png" and still[0].name == "main.png"
    # Telegram's own gift is never replaced by our pack.
    assert waysgram_gifts.sticker(gift_model_fallbacks.PLUSH_PEPE_ID, True) is None
    assert not waysgram_gifts.is_waysgram_gift(gift_model_fallbacks.PLUSH_PEPE_ID)


def test_pool_names_and_special_models_cover_the_same_pack() -> None:
    pool = json.loads(
        (Path(gift_upgrades.__file__).with_name("gift_upgrade_pools.json")).read_text(encoding="utf-8")
    )["gifts"][waysgram_gifts.GIFT_ID]
    names = {entry[0] for entry in pool["models"]}
    specials = {entry["name"] for entry in gift_upgrades.special_models(waysgram_gifts.GIFT_ID)}
    packed = {model["name"] for model in waysgram_gifts.models(waysgram_gifts.GIFT_ID)}
    assert names | specials == packed
    assert len(names) == 12 and len(specials) == 2
    # The two rarest models are exact independent draws, the rest is a full permille pool.
    assert sum(entry[1] for entry in pool["models"]) == gift_upgrades.PERMILLE_TOTAL
    chances = {entry["name"]: entry for entry in gift_upgrades.special_models(waysgram_gifts.GIFT_ID)}
    assert chances["Привет"]["one_in"] == 2000 and chances["Привет"]["chance_percent"] == 0.05
    assert chances["Танцует"]["one_in"] == 1000 and chances["Танцует"]["chance_percent"] == 0.1
    # Every special model names the document its animation is served from.
    for entry in gift_upgrades.special_models(waysgram_gifts.GIFT_ID):
        model = gift_model_fallbacks.model_for_document(
            waysgram_gifts.GIFT_ID, int(entry["document_id"]), synthetic=True
        )
        assert model is not None and model["mime"] == "video/webm"


def test_special_models_keep_their_exact_chances(monkeypatch: pytest.MonkeyPatch) -> None:
    rolls = {}

    def roll(denominator: int) -> None:
        monkeypatch.setattr(gift_upgrades.secrets, "randbelow", lambda _n: denominator)
        trait = gift_upgrades.roll_special_model(waysgram_gifts.GIFT_ID)
        rolls[denominator] = trait["name"] if trait else None

    # One shared denominator of 2000: 1 for 0.05 %, 2 for 0.1 %, and nothing else.
    for value in (0, 1, 2, 3, 1999):
        roll(value)
    assert rolls[0] == "Привет"
    assert rolls[1] == "Танцует" and rolls[2] == "Танцует"
    assert rolls[3] is None and rolls[1999] is None


def test_rolls_stay_inside_the_pack() -> None:
    pool = gift_upgrades.pool_for_gift(waysgram_gifts.GIFT_ID)[1]
    packed = {model["name"] for model in waysgram_gifts.models(waysgram_gifts.GIFT_ID)}
    traits = {"model": set(), "pattern": set(), "backdrop": set()}
    for _ in range(200):
        rolled = gift_upgrades.roll_traits(pool, int(time.time()), gift_id=waysgram_gifts.GIFT_ID)
        assert rolled["model"]["name"] in packed
        traits["model"].add(rolled["model"]["name"])
        traits["pattern"].add(rolled["pattern"]["name"])
        traits["backdrop"].add(rolled["backdrop"]["name"])
    # Patterns and backdrops come from the reused Telegram set, so they vary as well.
    assert len(traits["pattern"]) > 1 and len(traits["backdrop"]) > 1


def test_reveal_pool_lists_all_fourteen_models() -> None:
    pool = gift_upgrades.pool_for_gift(waysgram_gifts.GIFT_ID)[1]
    reveal = gift_upgrades.reveal_pool(pool, {}, gift_id=waysgram_gifts.GIFT_ID)
    names = {entry[0] for entry in reveal["models"]}
    assert names == {model["name"] for model in waysgram_gifts.models(waysgram_gifts.GIFT_ID)}
    assert reveal["patterns"] and reveal["backdrops"]


# ------------------------------------------------------------------ catalogue and prices


def test_catalogue_row_is_seeded_with_defaults(env) -> None:
    path, _app = env
    with db_split.sqlite_connect(path) as db:
        row = db.execute(
            """SELECT title,stars,upgrade_stars,is_waysgram,is_available,sticker_is_video,
                      sticker_mime_type,position
               FROM telegram_mtproto_gift_catalog WHERE gift_id=?""",
            (waysgram_gifts.GIFT_ID,),
        ).fetchone()
        price = db.execute(
            "SELECT price_stars,supply,sold FROM telegram_gift_prices WHERE gift_id=?",
            (waysgram_gifts.GIFT_ID,),
        ).fetchone()
    assert row == ("Mellstroy", 5000, 25, 1, 1, 1, "video/webm", waysgram_gifts.POSITION)
    assert price == (5000, 1000, 0)


def test_seeding_never_overwrites_the_administrator_values(env) -> None:
    path, _app = env

    async def scenario() -> None:
        async with aiosqlite.connect(path) as db:
            await db.execute(
                "UPDATE telegram_gift_prices SET price_stars=7000,supply=50 WHERE gift_id=?",
                (waysgram_gifts.GIFT_ID,),
            )
            await db.commit()
        await init_messenger_storage(path)

    run(scenario())
    with db_split.sqlite_connect(path) as db:
        assert db.execute(
            "SELECT price_stars,supply FROM telegram_gift_prices WHERE gift_id=?",
            (waysgram_gifts.GIFT_ID,),
        ).fetchone() == (7000, 50)


def test_storefront_lists_the_gift_with_its_animation(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            payload = (await client.get("/api/telegram/gifts", headers=headers(BUYER))).json()
            gift = next(item for item in payload["gifts"] if item["id"] == waysgram_gifts.GIFT_ID)
            assert gift["title"] == "Mellstroy"
            assert gift["source"] == "waysgram"
            assert gift["price_stars"] == 5000 and gift["remaining"] == 1000
            assert gift["upgrade_star_count"] == 25
            assert gift["sticker"]["type"] == "video"
            assert gift["sticker"]["mime_type"] == "video/webm"

            animation = await client.get(gift["sticker"]["url"] + "?animated=1", headers=headers(BUYER))
            assert animation.status_code == 200
            assert animation.headers["content-type"].startswith("video/webm")
            assert animation.content[:4] == b"\x1aE\xdf\xa3"
            still = await client.get(gift["sticker"]["url"], headers=headers(BUYER))
            assert still.status_code == 200
            assert still.headers["content-type"].startswith("image/png")

    run(scenario())


def test_attributes_endpoint_serves_the_bundled_models(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            payload = (
                await client.get(
                    f"/api/telegram/gifts/{waysgram_gifts.GIFT_ID}/attributes", headers=headers(BUYER)
                )
            ).json()
            assert len(payload["models"]) == 14
            by_name = {item["name"]: item for item in payload["models"]}
            assert by_name["Привет"]["document_id"] < 0
            assert payload["sync"]["source"] == "waysgram"
            document = by_name["Страшно"]
            model = await client.get(document["url"], headers=headers(BUYER))
            assert model.status_code == 200
            assert model.headers["content-type"].startswith("video/webm")
            assert model.content[:4] == b"\x1aE\xdf\xa3"

    run(scenario())


def test_administrator_takes_the_gift_out_of_the_catalogue(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            await bot_module.set_waysgram_gift_listed(waysgram_gifts.GIFT_ID, False, actor_id=ADMIN)
            payload = (await client.get("/api/telegram/gifts", headers=headers(BUYER))).json()
            assert waysgram_gifts.GIFT_ID not in {item["id"] for item in payload["gifts"]}

            card = await bot_module.admin_waysgram_gifts_page()
            assert "Mellstroy" in card[0]

            await bot_module.set_waysgram_gift_listed(waysgram_gifts.GIFT_ID, True, actor_id=ADMIN)
            payload = (await client.get("/api/telegram/gifts", headers=headers(BUYER))).json()
            assert waysgram_gifts.GIFT_ID in {item["id"] for item in payload["gifts"]}

    run(scenario())
    with db_split.sqlite_connect(path) as db:
        # The toggle is its own action, not a price change.
        assert db.execute(
            "SELECT price_stars,supply FROM telegram_gift_prices WHERE gift_id=?",
            (waysgram_gifts.GIFT_ID,),
        ).fetchone() == (5000, 1000)
        assert db.execute(
            "SELECT COUNT(*) FROM admin_logs WHERE action='waysgram_gift_catalog'"
        ).fetchone() == (2,)


def test_telegram_bulk_screens_leave_the_gift_alone(env) -> None:
    path, _app = env
    with db_split.sqlite_connect(path) as db:
        db.execute(
            """INSERT INTO telegram_mtproto_gift_catalog
               (gift_id,title,stars,source_payload,position,synced_at)
               VALUES('tg-gift','Gift',25,'{}',1,?)""",
            (int(time.time()),),
        )
        db.commit()

    async def scenario() -> None:
        # A catalogue refresh retires Telegram's empty listing but never our own gift.
        assert await bot_module.admin_refresh_gift_catalog_count() == 0
        await bot_module.set_telegram_gift_values("tg-gift", 0, 0, actor_id=ADMIN)

    run(scenario())
    with db_split.sqlite_connect(path) as db:
        assert db.execute(
            "SELECT price_stars,supply FROM telegram_gift_prices WHERE gift_id=?",
            (waysgram_gifts.GIFT_ID,),
        ).fetchone() == (5000, 1000)

    # Neither the Telegram gift list nor its bulk import knows our own gift.
    async def listing() -> set[str]:
        return {str(row["id"]) for row in await bot_module.telegram_gift_rows()}

    assert waysgram_gifts.GIFT_ID not in run(listing())


def test_upgrade_rolls_a_bundled_model_and_serves_its_animation(env) -> None:
    path, app = env
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        db.execute("UPDATE accounts SET stars=5000 WHERE telegram_id=?", (BUYER,))
        db.execute(
            """INSERT INTO user_gifts(serial_number,gift_name,telegram_gift_id,owner_telegram_id,acquired_at)
               VALUES('WG-ABCDEF123456','Mellstroy',?,?,?)""",
            (waysgram_gifts.GIFT_ID, BUYER, now),
        )
        db.commit()

    async def scenario() -> None:
        async with await client_for(app) as client:
            preview = await client.get("/api/gifts/WG-ABCDEF123456/upgrade-preview", headers=headers(BUYER))
            assert preview.status_code == 200
            candidates = {entry[0] for entry in preview.json()["candidates"]["models"]}
            assert candidates == {model["name"] for model in waysgram_gifts.models(waysgram_gifts.GIFT_ID)}

            upgraded = await client.post("/api/gifts/WG-ABCDEF123456/upgrade", headers=headers(BUYER))
            assert upgraded.status_code == 200
            traits = upgraded.json()["attributes"]["collectible_traits"]
            model = traits["model"]
            packed = gift_model_fallbacks.model_for_name(waysgram_gifts.GIFT_ID, model["name"])
            assert packed is not None
            # The card plays the bundled WebM, not a Telegram document.
            assert int(model["document_id"]) == int(packed["document_id"]) < 0
            animation = await client.get(
                f"/api/telegram/gifts/{waysgram_gifts.GIFT_ID}/attributes/{model['document_id']}",
                headers=headers(BUYER),
            )
            assert animation.status_code == 200
            assert animation.headers["content-type"].startswith("video/webm")

    run(scenario())
    with db_split.sqlite_connect(path) as db:
        # WaysGram charges its fixed upgrade price and keeps the stock untouched.
        assert db.execute("SELECT stars FROM accounts WHERE telegram_id=?", (BUYER,)).fetchone() == (5000 - 25,)
        assert db.execute(
            "SELECT supply,sold FROM telegram_gift_prices WHERE gift_id=?", (waysgram_gifts.GIFT_ID,)
        ).fetchone() == (1000, 0)
