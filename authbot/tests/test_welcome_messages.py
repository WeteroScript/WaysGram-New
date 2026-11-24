"""«Welcome message» в группе и канале: экран отвечает, а не крутит загрузку.

С устройства: «Welcome message» крутит бесконечную загрузку. Экран ждёт ответ на
`ephemeral.getWelcomeMessages`, а транспорт его не обслуживал — ответ не приходил вовсе.
Здесь проверяется серверная часть: список, добавление, удаление и права.
"""
from test_messenger_api import client_for, run
from test_star_gifts import env, headers  # noqa: F401


def test_group_welcome_messages_round_trip(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            group = await client.post("/api/groups", headers=owner, json={"title": "Welcome"})
            gid = group.json()["id"]
            await client.post(f"/api/groups/{gid}/members", headers=owner, json={"telegram_id": 102})

            empty = await client.get(f"/api/chats/group/{gid}/welcome", headers=owner)
            assert empty.status_code == 200, empty.text
            assert empty.json()["messages"] == []
            assert empty.json()["hash"] == 0

            created = await client.post(f"/api/chats/group/{gid}/welcome", headers=owner,
                                        json={"message": "Привет, {name}!"})
            assert created.status_code == 201, created.text
            message_id = created.json()["id"]

            listed = await client.get(f"/api/chats/group/{gid}/welcome", headers=owner)
            assert [m["body"] for m in listed.json()["messages"]] == ["Привет, {name}!"], listed.text
            assert listed.json()["hash"] != 0

            removed = await client.delete(f"/api/chats/group/{gid}/welcome?message_id={message_id}", headers=owner)
            assert removed.status_code == 200, removed.text
            assert (await client.get(f"/api/chats/group/{gid}/welcome", headers=owner)).json()["messages"] == []

            # Обычный участник экран не открывает: значит, и запросов от него быть не должно.
            denied = await client.get(f"/api/chats/group/{gid}/welcome", headers=member)
            assert denied.status_code == 403, denied.text
    run(scenario())


def test_channel_welcome_messages_limit_and_clear(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, subscriber = headers(101), headers(102)
            channel = await client.post("/api/channels", headers=owner,
                                        json={"title": "Welcome", "username": "welcome_channel", "is_public": True})
            cid = channel.json()["id"]
            await client.put(f"/api/channels/{cid}/membership", headers=subscriber, json={"joined": True})

            for index in range(5):
                created = await client.post(f"/api/chats/channel/{cid}/welcome", headers=owner,
                                            json={"message": f"Шаблон {index}"})
                assert created.status_code == 201, created.text
            too_much = await client.post(f"/api/chats/channel/{cid}/welcome", headers=owner,
                                         json={"message": "Шестой"})
            assert too_much.status_code == 429, too_much.text

            cleared = await client.delete(f"/api/chats/channel/{cid}/welcome", headers=owner)
            assert cleared.status_code == 200, cleared.text
            assert (await client.get(f"/api/chats/channel/{cid}/welcome", headers=owner)).json()["messages"] == []

            denied = await client.get(f"/api/chats/channel/{cid}/welcome", headers=subscriber)
            assert denied.status_code == 403, denied.text
    run(scenario())


def test_empty_welcome_text_is_rejected(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            group = await client.post("/api/groups", headers=owner, json={"title": "Blank"})
            gid = group.json()["id"]
            blank = await client.post(f"/api/chats/group/{gid}/welcome", headers=owner, json={"message": "   "})
            assert blank.status_code == 400, blank.text
    run(scenario())
