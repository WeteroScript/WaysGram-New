"""Group and channel wallpapers are shared, with optional personal overrides."""
from __future__ import annotations

import json
import time

from test_messenger_api import client_for, run
import db_split
from messenger_api import init_messenger_storage
from test_star_gifts import env, headers  # noqa: F401

WALLPAPER = {
    "background_color": 0x1F2B44,
    "second_background_color": 0x38688A,
    "third_background_color": 0x000000,
    "fourth_background_color": 0x000000,
    "intensity": -40,
    "motion": True,
    "blur": False,
    "rotation": 30,
    "emoticon": "🎨",
}
PERSONAL_WALLPAPER = {**WALLPAPER, "background_color": 0x7A3C88}
UPDATED_WALLPAPER = {**WALLPAPER, "background_color": 0x356B43}


def test_group_wallpaper_is_shared_and_personal_override_is_local(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            group = await client.post("/api/groups", headers=owner, json={"title": "Wallpaper"})
            gid = group.json()["id"]
            await client.post(f"/api/groups/{gid}/members", headers=owner, json={"telegram_id": 102})
            # Keep this member in personal-appearance mode; the default group permission
            # otherwise lets every member change group information and shared wallpaper.
            with db_split.sqlite_connect(path) as db:
                db.execute("UPDATE messenger.chat_groups SET default_change_info_restricted=1 WHERE id=?", (gid,))
                db.commit()

            assert (await client.get(f"/api/groups/{gid}", headers=owner)).json()["wallpaper"] is None
            cursor = (await client.get("/api/events", headers=member)).json()["latest_id"]

            saved = await client.put(f"/api/chats/group/{gid}/wallpaper", headers=owner,
                                     json={"wallpaper": WALLPAPER})
            assert saved.status_code == 200, saved.text
            assert saved.json()["wallpaper"] == WALLPAPER
            assert (await client.get(f"/api/groups/{gid}", headers=owner)).json()["wallpaper"] == WALLPAPER
            assert (await client.get(f"/api/groups/{gid}", headers=member)).json()["wallpaper"] == WALLPAPER

            events = (await client.get("/api/events", headers=member,
                                       params={"after_id": cursor})).json()["events"]
            assert any(event["scope"] == "group" and event["chat_id"] == gid
                       and event["kind"] == "chat_changed" and event["actor_id"] == 101
                       for event in events)

            # A regular participant may keep a personal appearance without changing the
            # shared wallpaper that every other participant sees.
            saved_personal = await client.put(f"/api/chats/group/{gid}/wallpaper", headers=member,
                                              json={"wallpaper": PERSONAL_WALLPAPER})
            assert saved_personal.status_code == 200, saved_personal.text
            assert (await client.get(f"/api/groups/{gid}", headers=member)).json()["wallpaper"] == PERSONAL_WALLPAPER
            assert (await client.get(f"/api/groups/{gid}", headers=owner)).json()["wallpaper"] == WALLPAPER
            cleared_personal = await client.delete(f"/api/chats/group/{gid}/wallpaper", headers=member)
            assert cleared_personal.json()["wallpaper"] == WALLPAPER
            assert (await client.get(f"/api/groups/{gid}", headers=member)).json()["wallpaper"] == WALLPAPER
            await client.put(f"/api/chats/group/{gid}/wallpaper", headers=member,
                             json={"wallpaper": PERSONAL_WALLPAPER})
            denied_share = await client.put(
                f"/api/chats/group/{gid}/wallpaper", headers=member,
                json={"wallpaper": {**WALLPAPER, "for_both": True}},
            )
            assert denied_share.status_code == 403, denied_share.text

            # An authorized shared update supersedes local overrides and is pushed to all
            # other members through chat_changed.
            updated = await client.put(f"/api/chats/group/{gid}/wallpaper", headers=owner,
                                       json={"wallpaper": UPDATED_WALLPAPER})
            assert updated.status_code == 200, updated.text
            assert (await client.get(f"/api/groups/{gid}", headers=member)).json()["wallpaper"] == UPDATED_WALLPAPER

            cleared = await client.delete(f"/api/chats/group/{gid}/wallpaper", headers=owner)
            assert cleared.status_code == 200, cleared.text
            assert (await client.get(f"/api/groups/{gid}", headers=owner)).json()["wallpaper"] is None
            assert (await client.get(f"/api/groups/{gid}", headers=member)).json()["wallpaper"] is None
    run(scenario())


def test_channel_wallpaper_is_shared_only_for_change_info_admins(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, subscriber, admin = headers(101), headers(102), headers(99001)
            channel = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Wallpaper", "username": "wallpaper_channel", "is_public": True},
            )
            cid = channel.json()["id"]
            root = f"/api/channels/{cid}"
            await client.put(root + "/membership", headers=subscriber, json={"joined": True})
            await client.post(root + "/admins", headers=owner,
                              json={"telegram_id": 99001, "rights": 64})

            saved = await client.put(f"/api/chats/channel/{cid}/wallpaper", headers=admin,
                                     json={"wallpaper": WALLPAPER})
            assert saved.status_code == 200, saved.text
            assert (await client.get(root, headers=admin)).json()["wallpaper"] == WALLPAPER
            assert (await client.get(root, headers=subscriber)).json()["wallpaper"] == WALLPAPER
            assert (await client.get(root, headers=owner)).json()["wallpaper"] == WALLPAPER

            saved_personal = await client.put(f"/api/chats/channel/{cid}/wallpaper", headers=subscriber,
                                              json={"wallpaper": PERSONAL_WALLPAPER})
            assert saved_personal.status_code == 200, saved_personal.text
            assert (await client.get(root, headers=subscriber)).json()["wallpaper"] == PERSONAL_WALLPAPER
            assert (await client.get(root, headers=admin)).json()["wallpaper"] == WALLPAPER
            cleared_personal = await client.delete(f"/api/chats/channel/{cid}/wallpaper", headers=subscriber)
            assert cleared_personal.json()["wallpaper"] == WALLPAPER
            assert (await client.get(root, headers=subscriber)).json()["wallpaper"] == WALLPAPER
            await client.put(f"/api/chats/channel/{cid}/wallpaper", headers=subscriber,
                             json={"wallpaper": PERSONAL_WALLPAPER})
            denied_share = await client.put(
                f"/api/chats/channel/{cid}/wallpaper", headers=subscriber,
                json={"wallpaper": {**WALLPAPER, "for_both": True}},
            )
            assert denied_share.status_code == 403, denied_share.text

            cleared = await client.delete(f"/api/chats/channel/{cid}/wallpaper", headers=admin)
            assert cleared.status_code == 200, cleared.text
            assert (await client.get(root, headers=subscriber)).json()["wallpaper"] == PERSONAL_WALLPAPER
            assert (await client.get(root, headers=owner)).json()["wallpaper"] is None
    run(scenario())


def test_legacy_owner_wallpaper_is_promoted_to_shared_for_groups_and_channels(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            group = await client.post("/api/groups", headers=owner, json={"title": "Legacy wallpaper"})
            gid = group.json()["id"]
            await client.post(f"/api/groups/{gid}/members", headers=owner, json={"telegram_id": 102})
            channel = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Legacy wallpaper", "username": "legacy_wallpaper", "is_public": True},
            )
            cid = channel.json()["id"]
            await client.put(f"/api/channels/{cid}/membership", headers=member, json={"joined": True})

            now = int(time.time())
            with db_split.sqlite_connect(path) as db:
                # Simulate the per-account records stored by the old API before its first
                # shared-wallpaper migration.
                for scope, chat_id in (("group", gid), ("channel", cid)):
                    db.execute(
                        "INSERT INTO main.chat_wallpapers(scope,chat_id,telegram_id,settings,updated_at) "
                        "VALUES(?,?,?,?,?)",
                        (scope, chat_id, 101, json.dumps(WALLPAPER), now),
                    )
                db.execute("DELETE FROM main.chat_wallpaper_migrations WHERE version=1")
                db.commit()

            await init_messenger_storage(path)
            assert (await client.get(f"/api/groups/{gid}", headers=member)).json()["wallpaper"] == WALLPAPER
            assert (await client.get(f"/api/channels/{cid}", headers=member)).json()["wallpaper"] == WALLPAPER
            with db_split.sqlite_connect(path) as db:
                for scope, chat_id in (("group", gid), ("channel", cid)):
                    rows = db.execute(
                        "SELECT telegram_id FROM main.chat_wallpapers WHERE scope=? AND chat_id=?",
                        (scope, chat_id),
                    ).fetchall()
                    assert [row[0] for row in rows] == [0]
    run(scenario())


def test_outsider_cannot_attach_wallpaper_to_a_group(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, outsider = headers(101), headers(102)
            group = await client.post("/api/groups", headers=owner, json={"title": "Private"})
            gid = group.json()["id"]
            denied = await client.put(f"/api/chats/group/{gid}/wallpaper", headers=outsider,
                                      json={"wallpaper": WALLPAPER})
            assert denied.status_code in (403, 404), denied.text
    run(scenario())
