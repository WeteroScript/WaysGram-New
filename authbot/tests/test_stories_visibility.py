"""Истории видны не только автору.

С устройства: «истории видны только владельцу». Причин было две — на сервере не было
общего списка историй (`stories.getAllStories`), без которого клиент не знает ни одного
пира с историями, и клиент считал каналом только id выше `CHANNEL_OFFSET`, поэтому истории
группы вообще не обслуживались.
"""
from io import BytesIO

from PIL import Image

from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401


def _photo(owner: str) -> bytes:
    buffer = BytesIO()
    Image.new("RGB", (6, 4), (20, 120, 200)).save(buffer, format="JPEG")
    return buffer.getvalue()


async def _upload(client, who: dict[str, str]) -> str:
    uploaded = await client.post("/api/media/images", headers={**who, "Content-Type": "image/jpeg"},
                                 content=_photo(who.get("Authorization", "")))
    assert uploaded.status_code == 201, uploaded.text
    return uploaded.json()["id"]


def test_channel_story_is_listed_for_a_subscriber(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, subscriber = headers(101), headers(102)
            channel = await client.post("/api/channels", headers=owner,
                                        json={"title": "Stories", "username": "stories_channel", "is_public": True})
            cid = channel.json()["id"]
            joined = await client.put(f"/api/channels/{cid}/membership", headers=subscriber, json={"joined": True})
            assert joined.status_code == 200, joined.text

            media = await _upload(client, owner)
            story = await client.post(f"/api/channels/{cid}/stories", headers=owner,
                                      json={"random_id": 7, "media_id": media, "period": 86400})
            assert story.status_code == 201, story.text

            info = await client.get(f"/api/channels/{cid}", headers=subscriber)
            assert info.json()["stories_active"] is True, info.text

            listing = await client.get("/api/stories/all", headers=subscriber)
            assert listing.status_code == 200, listing.text
            peers = {p["id"]: p for p in listing.json()["peers"] if p["scope"] == "channel"}
            assert cid in peers, listing.text
            assert [s["id"] for s in peers[cid]["stories"]] == [story.json()["id"]]
    run(scenario())


def test_group_story_is_listed_for_a_member_and_hidden_from_outsiders(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            outsider = headers(ADMIN)
            group = await client.post("/api/groups", headers=owner, json={"title": "Group stories"})
            gid = group.json()["id"]
            await client.post(f"/api/groups/{gid}/members", headers=owner, json={"telegram_id": 102})

            media = await _upload(client, owner)
            story = await client.post(f"/api/groups/{gid}/stories", headers=owner,
                                      json={"random_id": 11, "media_id": media, "period": 86400})
            assert story.status_code == 201, story.text

            info = await client.get(f"/api/groups/{gid}", headers=member)
            assert info.json()["stories_active"] is True, info.text

            mine = await client.get("/api/stories/all", headers=member)
            assert gid in {p["id"] for p in mine.json()["peers"] if p["scope"] == "group"}, mine.text

            theirs = await client.get("/api/stories/all", headers=outsider)
            assert gid not in {p["id"] for p in theirs.json()["peers"] if p["scope"] == "group"}, theirs.text
    run(scenario())


def test_expired_story_leaves_the_list(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, subscriber = headers(101), headers(102)
            channel = await client.post("/api/channels", headers=owner,
                                        json={"title": "Expiring", "username": "expiring_channel", "is_public": True})
            cid = channel.json()["id"]
            await client.put(f"/api/channels/{cid}/membership", headers=subscriber, json={"joined": True})

            media = await _upload(client, owner)
            story = await client.post(f"/api/channels/{cid}/stories", headers=owner,
                                      json={"random_id": 13, "media_id": media, "period": 21600})
            assert story.status_code == 201, story.text

            import aiosqlite
            async with aiosqlite.connect(path) as db:
                await db.execute("UPDATE channel_stories SET expires_at=? WHERE channel_id=?", (1, cid))
                await db.commit()

            listing = await client.get("/api/stories/all", headers=subscriber)
            assert cid not in {p["id"] for p in listing.json()["peers"]}, listing.text
    run(scenario())
