from __future__ import annotations
import sys, asyncio, json, time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3] / "authbot"
sys.path.insert(0, str(ROOT)); sys.path.insert(0, str(ROOT/'tests'))
import db_split
from test_messenger_api import api_env, add_account, add_session, client_for  # noqa

GIFT = "5936013938331222567"

def seed(api_env, uid, traits, gift_name="Подарок Telegram", gift_id=GIFT):
    path, *_ = api_env
    now = int(time.time())
    with db_split.sqlite_connect(path) as db:
        db.execute("UPDATE accounts SET stars=500 WHERE telegram_id=?", (uid,))
        db.execute("""INSERT OR REPLACE INTO telegram_gift_prices(gift_id,price_stars,supply,sold,updated_at)
                      VALUES(?,100,10,0,?)""", (gift_id, now))
        db.execute("""INSERT INTO user_gifts(serial_number,gift_name,telegram_gift_id,owner_telegram_id,acquired_at,attributes_json)
                      VALUES(?,?,?,?,?,?)""",
                   (f"WG-{uid}", gift_name, gift_id, uid, now,
                    json.dumps({"collectible_traits": traits}, ensure_ascii=False)))
        db.commit()

def test_bug6_wrong_backdrop_colors(api_env) -> None:
    add_account(api_env, 95001, "g6")
    add_session(api_env, 95001, "t6") if False else None
    path, *_ = api_env
    add_session(path, 95001, "t6")
    traits = {
        "version": 3,
        "model": {"name": "Gucci Leap"},
        "pattern": {"name": "Diamonds"},
        # Name says Black, colours belong to "Raspberry" (magenta) — exactly the screenshot.
        "backdrop": {"name": "Black", "center_color": 14711685, "edge_color": 11950464,
                     "pattern_color": 8980024, "text_color": 16766694},
    }
    seed(api_env, 95001, traits)

    async def go():
        async with await client_for(app_ref[0]) as client:
            inv = await client.get("/api/me/gifts", headers={"Authorization": "Bearer t6"})
            item = inv.json()["gifts"][0]
            print("BACKDROP", json.dumps(item["attributes"]["collectible_traits"]["backdrop"], ensure_ascii=False))
    app_ref = [api_env[3]]
    asyncio.run(go())
