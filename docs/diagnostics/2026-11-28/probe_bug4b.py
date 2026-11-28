from __future__ import annotations
import sys, asyncio, time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3] / "authbot"
sys.path.insert(0, str(ROOT)); sys.path.insert(0, str(ROOT/'tests'))
from test_messenger_api import api_env, add_account, add_session, client_for  # noqa


def test_bug4_hidden_prehistory(api_env) -> None:
    path, _s, _b, app = api_env
    add_account(api_env, 90011, "owner2")
    add_account(api_env, 90012, "reader2")
    add_session(path, 90011, "o2"); add_session(path, 90012, "r2")

    async def scenario():
        owner = {"Authorization": "Bearer o2"}; reader = {"Authorization": "Bearer r2"}
        async with await client_for(app) as client:
            ch = await client.post("/api/channels", headers=owner, json={"title": "C2", "is_public": True, "username": "probe2"})
            cid = ch.json()["id"]
            g = await client.post("/api/groups", headers=owner, json={"title": "G2"})
            gid = g.json()["id"]
            print("bind", (await client.put(f"/api/channels/{cid}/discussion-group", headers=owner, json={"group_id": gid})).status_code)
            post = await client.post(f"/api/channels/{cid}/messages", headers=owner, json={"body": "post"})
            pid = post.json()["id"]
            print("hide", (await client.put(f"/api/groups/{gid}/settings", headers=owner, json={"pre_history_hidden": True})).text)
            time.sleep(1.1)
            d = await client.get(f"/api/channels/{cid}/messages/{pid}/discussion", headers=reader)
            print("discussion", d.status_code, d.text)
            root = d.json()["root_message_id"]
            rep = await client.get(f"/api/groups/{gid}/threads/{root}/replies?include_root=true", headers=reader)
            print("replies", rep.status_code, rep.text)
            msgs = await client.get(f"/api/groups/{gid}/messages?message_ids={root}", headers=reader)
            print("messages", msgs.status_code, msgs.text)
    asyncio.run(scenario())
