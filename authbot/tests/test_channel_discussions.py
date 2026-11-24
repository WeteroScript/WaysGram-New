"""Обсуждение канала открывается подписчику, а не только администратору.

С устройства приходило: у обычного аккаунта кнопка «Discuss» не открывает группу,
а комментарии под постом не грузятся. Причина — сервер требовал членства в
связанной группе обсуждений, хотя в неё никто никого не добавлял.
"""
from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401


def test_channel_subscriber_can_open_discussion(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            subscriber = headers(102)

            channel = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Discussed", "username": "discussed_channel", "is_public": True},
            )
            assert channel.status_code == 201, channel.text
            cid = channel.json()["id"]
            post = await client.post(f"/api/channels/{cid}/messages", headers=owner, json={"body": "post"})
            assert post.status_code == 201, post.text
            pid = post.json()["id"]

            group = await client.post("/api/groups", headers=owner, json={"title": "Discuss chat"})
            assert group.status_code == 201, group.text
            gid = group.json()["id"]
            linked = await client.put(f"/api/channels/{cid}/discussion-group", headers=owner,
                                      json={"group_id": gid})
            assert linked.status_code == 200, linked.text

            # Подписчик канала ещё не состоит в группе обсуждений.
            before = await client.get(f"/api/groups/{gid}/messages", headers=subscriber)
            assert before.status_code in (403, 404), before.text

            discussion = await client.get(f"/api/channels/{cid}/messages/{pid}/discussion", headers=subscriber)
            assert discussion.status_code == 200, discussion.text
            body = discussion.json()
            assert body["group_id"] == gid
            assert body["root_message_id"] > 0

            # После открытия обсуждения группа доступна: сервер сам добавил подписчика.
            after = await client.get(f"/api/groups/{gid}/messages", headers=subscriber)
            assert after.status_code == 200, after.text

            # Посторонний, у которого нет доступа к каналу, по-прежнему не проходит.
            private = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Private", "username": "private_discussed", "is_public": False},
            )
            assert private.status_code == 201, private.text
            private_id = private.json()["id"]
            outsider = await client.get(
                f"/api/channels/{private_id}/discussion-group", headers=subscriber
            )
            assert outsider.status_code == 404, outsider.text

    run(scenario())
