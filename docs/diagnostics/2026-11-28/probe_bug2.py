from __future__ import annotations
import sys, asyncio, time, json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3] / "authbot"
sys.path.insert(0, str(ROOT)); sys.path.insert(0, str(ROOT/'tests'))
import db_split
from test_messenger_api import api_env, add_account, add_session, client_for  # noqa


def test_bug2_paid_reaction_roundtrip(api_env) -> None:
    path, *_ , app = api_env
    add_account(api_env, 91001, "p_owner")
    add_account(api_env, 91002, "p_reader")
    add_session(path, 91001, "o"); add_session(path, 91002, "r")
    with db_split.sqlite_connect(path) as db:
        db.execute("UPDATE accounts SET stars=1000 WHERE telegram_id=91002")
        db.commit()

    async def go():
        owner = {"Authorization": "Bearer o"}; reader = {"Authorization": "Bearer r"}
        async with await client_for(app) as client:
            ch = await client.post("/api/channels", headers=owner, json={"title": "PR", "is_public": True, "username": "pr_ch"})
            cid = ch.json()["id"]
            print("enable", (await client.put(f"/api/channels/{cid}/paid-reactions", headers=owner, json={"enabled": True})).text)
            post = await client.post(f"/api/channels/{cid}/messages", headers=owner, json={"body": "post"})
            pid = post.json()["id"]
            r = await client.post(f"/api/channels/{cid}/messages/{pid}/paid-reactions", headers=reader,
                                  json={"stars": 5}, headers_extra=None) if False else None
            # send with idempotency key
            hdr = dict(reader); hdr["Idempotency-Key"] = "wg-paid-abcdef0123456789-1"
            resp = await client.post(f"/api/channels/{cid}/messages/{pid}/paid-reactions", headers=hdr, json={"stars": 5})
            print("POST", resp.status_code, resp.text)
            page = await client.get(f"/api/channels/{cid}/messages?message_ids={pid}", headers=reader)
            row = page.json()["messages"][0]
            print("PAGE paid_stars", row.get("paid_stars"), "my_paid_stars", row.get("my_paid_stars"))
            page2 = await client.get(f"/api/channels/{cid}/messages", headers=reader)
            row2 = next(m for m in page2.json()["messages"] if m["id"] == pid)
            print("LIST paid_stars", row2.get("paid_stars"), "my_paid_stars", row2.get("my_paid_stars"))
    asyncio.run(go())
