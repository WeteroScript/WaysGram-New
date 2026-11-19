from __future__ import annotations

import hashlib
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import db_split  # noqa: E402

from test_messenger_api import add_account, add_session, api_env, client_for, run  # noqa: E402,F401


def headers(path, uid, token):
    add_session(path, uid, token)
    return {"Authorization": f"Bearer {token}"}


def test_ai_clone_settings_summarize_rewrite_and_autoreply(api_env, monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    path, _store, _bot, app = api_env
    add_account(api_env, 91001, "alice")
    add_account(api_env, 91002, "bob")
    ha = headers(path, 91001, "tok-a")
    hb = headers(path, 91002, "tok-b")

    async def scenario():
        async with await client_for(app) as client:
            put = await client.put("/api/ai/clone", json={"enabled": True, "only_when_offline": False, "tone": "friendly"}, headers=hb)
            assert put.status_code == 200, put.text
            got = await client.get("/api/ai/clone", headers=hb)
            assert got.json()["enabled"] is True
            sent = await client.post("/api/messages", json={"recipient_id": 91002, "body": "Привет! Когда созвонимся по проекту?"}, headers=ha)
            assert sent.status_code == 201, sent.text
            rewrite = await client.post("/api/ai/rewrite", json={"text": "надо сделать завтра", "tone": "business"}, headers=ha)
            assert rewrite.status_code == 200 and rewrite.json()["text"]
            summary = await client.post("/api/ai/summarize", json={"peer_id": 91002}, headers=ha)
            assert summary.status_code == 200 and summary.json()["count"] >= 1
            history = await client.get("/api/messages?with=91002", headers=ha)
            bodies = [m["body"] for m in history.json()["messages"]]
            assert any("не на связи" in b or "отвечу" in b for b in bodies), bodies

    run(scenario())


def test_room_sync_and_streak(api_env):
    path, _store, _bot, app = api_env
    add_account(api_env, 92001, "carol")
    add_account(api_env, 92002, "dave")
    ha = headers(path, 92001, "tok-c")
    hb = headers(path, 92002, "tok-d")

    async def scenario():
        async with await client_for(app) as client:
            room = await client.post("/api/rooms", json={"scope": "dm", "chat_id": 92002, "media_title": "Lo-fi mix", "media_url": "https://example.com/a.mp3"}, headers=ha)
            assert room.status_code == 201, room.text
            room_id = room.json()["room"]["id"]
            ctl = await client.post(f"/api/rooms/{room_id}/control", json={"action": "play"}, headers=hb)
            assert ctl.status_code == 200 and ctl.json()["room"]["is_playing"] is True
            ctl = await client.post(f"/api/rooms/{room_id}/control", json={"action": "seek", "position_seconds": 42}, headers=ha)
            assert ctl.json()["room"]["position_seconds"] >= 42
            await client.post("/api/messages", json={"recipient_id": 92002, "body": "привет"}, headers=ha)
            await client.post("/api/messages", json={"recipient_id": 92001, "body": "привет!"}, headers=hb)
            streak = await client.get("/api/streaks/92002", headers=ha)
            assert streak.status_code == 200, streak.text
            assert streak.json()["current_streak"] == 1
            assert streak.json()["badge"] == "🔥 1"
            lst = await client.get("/api/streaks", headers=ha)
            assert lst.json()["streaks"]
            labels = await client.get("/api/labels?targets=account:92002", headers=ha)
            texts = [x["text"] for x in labels.json()["labels"]["account:92002"]]
            assert "🔥 1" in texts, texts

    run(scenario())


def test_streak_claim_requires_milestone(api_env):
    path, _store, _bot, app = api_env
    add_account(api_env, 92501, "eve")
    add_account(api_env, 92502, "frank")
    ha = headers(path, 92501, "tok-e")

    async def scenario():
        async with await client_for(app) as client:
            claim = await client.post("/api/streaks/92502/claim", json={"milestone": 3}, headers=ha)
            assert claim.status_code == 409

    run(scenario())


def test_fragment_bid_settle_equip_and_forbes(api_env):
    path, _store, _bot, app = api_env
    add_account(api_env, 93001, "gina")
    add_account(api_env, 93002, "henry")
    ha = headers(path, 93001, "tok-g")
    hb = headers(path, 93002, "tok-h")

    async def scenario():
        async with await client_for(app) as client:
            created = await client.post("/api/fragment/create", json={"asset_type": "username", "asset_value": "@cooltest", "currency": "stars", "starting_bid": 10, "duration_seconds": 3600}, headers=ha)
            assert created.status_code == 201, created.text
            lid = created.json()["id"]
            wallet_before = (await client.get("/api/me/wallet", headers=hb)).json()["stars"]
            bid = await client.post(f"/api/fragment/listings/{lid}/bid", json={"amount": 20}, headers=hb)
            assert bid.status_code == 200, bid.text
            wallet_after = (await client.get("/api/me/wallet", headers=hb)).json()["stars"]
            assert wallet_after == wallet_before - 20
            listings = await client.get("/api/fragment/listings", headers=ha)
            assert any(x["id"] == lid for x in listings.json()["listings"])
            forbes = await client.get("/api/forbes", headers=ha)
            assert forbes.status_code == 200 and "richest_stars" in forbes.json()

    run(scenario())
    # Force expiry and check settlement transfers ownership to the bidder.
    with db_split.sqlite_connect(path) as db:
        db.execute("UPDATE fragment_listings SET ends_at=? WHERE asset_value='@cooltest'", (int(time.time()) - 1,))
        db.commit()

    async def scenario2():
        async with await client_for(app) as client:
            await client.get("/api/fragment/listings", headers=ha)
            mine = await client.get("/api/fragment/my", headers=hb)
            assert any(o["asset_value"] == "@cooltest" for o in mine.json()["owned"]), mine.text
            equip = await client.post("/api/fragment/equip", json={"asset_type": "username", "asset_value": "@cooltest"}, headers=hb)
            assert equip.status_code == 200, equip.text
            me = await client.get("/api/me", headers=hb)
            assert "cooltest" in str(me.json()), me.text

    run(scenario2())


def test_duel_stars_escrow_and_payout(api_env):
    path, _store, _bot, app = api_env
    add_account(api_env, 94001, "ivan")
    add_account(api_env, 94002, "judy")
    ha = headers(path, 94001, "tok-i")
    hb = headers(path, 94002, "tok-j")

    async def scenario():
        async with await client_for(app) as client:
            wa = (await client.get("/api/me/wallet", headers=ha)).json()["stars"]
            wb = (await client.get("/api/me/wallet", headers=hb)).json()["stars"]
            duel = await client.post("/api/duels", json={"scope": "dm", "chat_id": 94002, "opponent_id": 94002, "game": "dice", "wager_type": "stars", "wager_amount": 100}, headers=ha)
            assert duel.status_code == 201, duel.text
            duel_id = duel.json()["id"]
            assert duel.json()["seed_hash"] == hashlib.sha256(b"").hexdigest() or len(duel.json()["seed_hash"]) == 64
            accepted = await client.post(f"/api/duels/{duel_id}/accept", json={}, headers=hb)
            assert accepted.status_code == 200, accepted.text
            payload = accepted.json()
            assert payload["status"] == "finished" and payload["winner_id"] in (94001, 94002)
            assert payload["server_seed"]
            wa2 = (await client.get("/api/me/wallet", headers=ha)).json()["stars"]
            wb2 = (await client.get("/api/me/wallet", headers=hb)).json()["stars"]
            # Total is conserved, winner is +100 net, loser -100 net.
            assert wa2 + wb2 == wa + wb
            assert max(wa2 - wa, wb2 - wb) == 100

    run(scenario())


def test_duel_cancel_refunds(api_env):
    path, _store, _bot, app = api_env
    add_account(api_env, 95001, "kate")
    add_account(api_env, 95002, "leo")
    ha = headers(path, 95001, "tok-k")

    async def scenario():
        async with await client_for(app) as client:
            before = (await client.get("/api/me/wallet", headers=ha)).json()["stars"]
            duel = await client.post("/api/duels", json={"scope": "dm", "chat_id": 95002, "opponent_id": 95002, "game": "coin", "wager_type": "stars", "wager_amount": 50}, headers=ha)
            duel_id = duel.json()["id"]
            mid = (await client.get("/api/me/wallet", headers=ha)).json()["stars"]
            assert mid == before - 50
            cancelled = await client.post(f"/api/duels/{duel_id}/cancel", headers=ha)
            assert cancelled.status_code == 200
            after = (await client.get("/api/me/wallet", headers=ha)).json()["stars"]
            assert after == before

    run(scenario())
