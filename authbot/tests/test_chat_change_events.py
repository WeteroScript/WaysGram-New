"""Изменения чата доезжают до других участников событием, а не «когда-нибудь».

С устройства: права группы менялись «через минуты или часы, иногда никогда»; аватар и
название тоже не появлялись у второго аккаунта. Причина — сервер не сообщал об
изменениях вовсе, клиенту нечего было перечитывать. Теперь настройки, права, аватар,
название и выдача прав администратора пишут событие ``chat_changed``.
"""
from test_messenger_api import client_for, run
from test_star_gifts import env, headers  # noqa: F401


def _kinds(events: list[dict], scope: str, chat_id: int) -> list[str]:
    return [e["kind"] for e in events if e["scope"] == scope and e["chat_id"] == chat_id]


def test_group_permission_change_reaches_the_other_member(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            member = headers(102)

            group = await client.post("/api/groups", headers=owner, json={"title": "Rights"})
            assert group.status_code == 201, group.text
            gid = group.json()["id"]
            added = await client.post(f"/api/groups/{gid}/members", headers=owner,
                                      json={"telegram_id": 102})
            assert added.status_code in (200, 201), added.text

            cursor = (await client.get("/api/events", headers=member)).json()["latest_id"]

            before = await client.get(f"/api/events?after_id={cursor}", headers=member)
            assert not _kinds(before.json()["events"], "group", gid)

            changed = await client.put(f"/api/groups/{gid}/send-permissions", headers=owner,
                                       json={"default_send_restrictions": 1})
            assert changed.status_code == 200, changed.text

            after = await client.get(f"/api/events?after_id={cursor}", headers=member)
            assert "chat_changed" in _kinds(after.json()["events"], "group", gid), after.text

            # Автор изменения не получает своё же событие: он уже обновил экран сам.
            own = await client.get(f"/api/events?after_id={cursor}", headers=owner)
            assert "chat_changed" not in _kinds(own.json()["events"], "group", gid), own.text
    run(scenario())


def test_group_settings_and_avatar_emit_changes(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            member = headers(102)

            group = await client.post("/api/groups", headers=owner, json={"title": "Settings"})
            gid = group.json()["id"]
            await client.post(f"/api/groups/{gid}/members", headers=owner, json={"telegram_id": 102})
            cursor = (await client.get("/api/events", headers=member)).json()["latest_id"]

            settings = await client.put(f"/api/groups/{gid}/settings", headers=owner,
                                        json={"slow_mode_seconds": 30, "members_hidden": True})
            assert settings.status_code == 200, settings.text
            avatar = await client.put(f"/api/groups/{gid}/avatar", headers=owner, json={"media_id": ""})
            assert avatar.status_code == 200, avatar.text

            after = await client.get(f"/api/events?after_id={cursor}", headers=member)
            assert _kinds(after.json()["events"], "group", gid).count("chat_changed") >= 2, after.text
    run(scenario())


def test_channel_edit_reaches_a_subscriber(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            subscriber = headers(102)

            channel = await client.post("/api/channels", headers=owner,
                                        json={"title": "Editable", "username": "editable_channel", "is_public": True})
            cid = channel.json()["id"]
            joined = await client.put(f"/api/channels/{cid}/membership", headers=subscriber, json={"joined": True})
            assert joined.status_code == 200, joined.text
            cursor = (await client.get("/api/events", headers=subscriber)).json()["latest_id"]

            edited = await client.patch(f"/api/channels/{cid}", headers=owner, json={"title": "Edited"})
            assert edited.status_code == 200, edited.text

            after = await client.get(f"/api/events?after_id={cursor}", headers=subscriber)
            assert "chat_changed" in _kinds(after.json()["events"], "channel", cid), after.text
    run(scenario())
