"""Переключатель «Реакции» группы не откатывается после переоткрытия настроек.

С устройства: в канале и группе «Реакции» включаешь/выключаешь, закрываешь экран,
открываешь снова — значение прежнее. Сервер вообще не хранил настройки реакций группы,
а канал при `reactions_enabled=false` всё равно отдавал сохранённый набор эмодзи.
"""
from test_messenger_api import client_for, run
from test_star_gifts import env, headers  # noqa: F401


def test_group_reactions_toggle_is_stored(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            group = await client.post("/api/groups", headers=owner, json={"title": "Reactions"})
            gid = group.json()["id"]
            await client.post(f"/api/groups/{gid}/members", headers=owner, json={"telegram_id": 102})

            default = await client.get(f"/api/groups/{gid}", headers=owner)
            assert default.json()["reactions_enabled"] is True, default.text
            assert default.json()["reactions_limit"] == 11, default.text

            off = await client.put(f"/api/groups/{gid}/reactions-settings", headers=owner, json={"enabled": False})
            assert off.status_code == 200, off.text
            assert off.json()["enabled"] is False

            again = await client.get(f"/api/groups/{gid}", headers=owner)
            assert again.json()["reactions_enabled"] is False, again.text
            as_member = await client.get(f"/api/groups/{gid}", headers=member)
            assert as_member.json()["reactions_enabled"] is False, as_member.text

            picked = await client.put(f"/api/groups/{gid}/reactions-settings", headers=owner,
                                      json={"enabled": True, "limit": 3, "reactions": ["👍", "🔥", "👍"]})
            assert picked.status_code == 200, picked.text
            assert picked.json() == {"enabled": True, "limit": 3, "reactions": ["👍", "🔥"]}

            stored = await client.get(f"/api/groups/{gid}", headers=member)
            assert stored.json()["available_reactions"] == ["👍", "🔥"], stored.text
            assert stored.json()["reactions_limit"] == 3, stored.text
    run(scenario())


def test_channel_reactions_toggle_survives_the_reload(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, subscriber = headers(101), headers(102)
            channel = await client.post("/api/channels", headers=owner,
                                        json={"title": "Reactions", "username": "reactions_channel", "is_public": True})
            cid = channel.json()["id"]
            await client.put(f"/api/channels/{cid}/membership", headers=subscriber, json={"joined": True})

            picked = await client.put(f"/api/channels/{cid}/reactions-settings", headers=owner,
                                      json={"reactions": ["👍", "🔥"]})
            assert picked.status_code == 200, picked.text
            off = await client.put(f"/api/channels/{cid}/reactions-settings", headers=owner, json={"enabled": False})
            assert off.status_code == 200, off.text

            reloaded = await client.get(f"/api/channels/{cid}", headers=subscriber)
            data = reloaded.json()
            assert data["reactions_enabled"] is False, reloaded.text
            # Набор эмодзи остаётся сохранённым, но выключенный выключатель важнее.
            assert data["available_reactions"] == ["👍", "🔥"], reloaded.text
    run(scenario())


def test_only_managers_change_group_reactions(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            group = await client.post("/api/groups", headers=owner, json={"title": "Locked"})
            gid = group.json()["id"]
            await client.post(f"/api/groups/{gid}/members", headers=owner, json={"telegram_id": 102})

            denied = await client.put(f"/api/groups/{gid}/reactions-settings", headers=member, json={"enabled": False})
            assert denied.status_code == 403, denied.text
            unchanged = await client.get(f"/api/groups/{gid}", headers=member)
            assert unchanged.json()["reactions_enabled"] is True, unchanged.text
    run(scenario())
