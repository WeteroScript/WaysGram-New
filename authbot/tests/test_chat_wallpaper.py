"""Обои чата сохраняются и возвращаются в карточке («Внешний вид и эмодзи»).

С устройства: выбрал обои в группе/канале — фон не меняется. `messages.setChatWallPaper`
уходил в обработчик по умолчанию, то есть никуда: на сервере не было ни хранения, ни
поля в карточке чата, поэтому клиенту нечего было применить.
"""
from test_messenger_api import client_for, run
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


def test_group_wallpaper_is_stored_per_account(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            group = await client.post("/api/groups", headers=owner, json={"title": "Wallpaper"})
            gid = group.json()["id"]
            await client.post(f"/api/groups/{gid}/members", headers=owner, json={"telegram_id": 102})

            assert (await client.get(f"/api/groups/{gid}", headers=owner)).json()["wallpaper"] is None

            saved = await client.put(f"/api/chats/group/{gid}/wallpaper", headers=owner,
                                     json={"wallpaper": WALLPAPER})
            assert saved.status_code == 200, saved.text
            assert saved.json()["wallpaper"] == WALLPAPER

            reloaded = await client.get(f"/api/groups/{gid}", headers=owner)
            assert reloaded.json()["wallpaper"] == WALLPAPER, reloaded.text
            # Второй аккаунт в том же чате свои обои не получает.
            assert (await client.get(f"/api/groups/{gid}", headers=member)).json()["wallpaper"] is None

            cleared = await client.delete(f"/api/chats/group/{gid}/wallpaper", headers=owner)
            assert cleared.status_code == 200, cleared.text
            assert (await client.get(f"/api/groups/{gid}", headers=owner)).json()["wallpaper"] is None
    run(scenario())


def test_channel_wallpaper_round_trip(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, subscriber = headers(101), headers(102)
            channel = await client.post("/api/channels", headers=owner,
                                        json={"title": "Wallpaper", "username": "wallpaper_channel", "is_public": True})
            cid = channel.json()["id"]
            await client.put(f"/api/channels/{cid}/membership", headers=subscriber, json={"joined": True})

            saved = await client.put(f"/api/chats/channel/{cid}/wallpaper", headers=subscriber,
                                     json={"wallpaper": WALLPAPER})
            assert saved.status_code == 200, saved.text
            assert (await client.get(f"/api/channels/{cid}", headers=subscriber)).json()["wallpaper"] == WALLPAPER
            assert (await client.get(f"/api/channels/{cid}", headers=owner)).json()["wallpaper"] is None
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
