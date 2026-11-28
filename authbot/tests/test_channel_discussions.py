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
            group_profile = await client.get(f"/api/groups/{gid}", headers=owner)
            assert group_profile.status_code == 200, group_profile.text
            assert group_profile.json()["linked_channel_id"] == cid
            assert group_profile.json()["linked_channel"]["title"] == "Discussed"

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


def test_linked_peer_default_restriction_applies_to_group_members(env) -> None:
    """A channel-owning member can manage a group link only when its policy allows it."""
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            channel_owner, group_owner = headers(101), headers(102)
            channel = await client.post(
                "/api/channels", headers=channel_owner,
                json={"title": "Member channel", "username": "member_link_channel", "is_public": True},
            )
            group = await client.post("/api/groups", headers=group_owner,
                                      json={"title": "Member-managed links"})
            assert channel.status_code == 201, channel.text
            assert group.status_code == 201, group.text
            channel_id, group_id = channel.json()["id"], group.json()["id"]
            added = await client.post(f"/api/groups/{group_id}/members", headers=group_owner,
                                      json={"telegram_id": 101})
            assert added.status_code == 201, added.text

            linked = await client.put(f"/api/channels/{channel_id}/discussion-group", headers=channel_owner,
                                      json={"group_id": group_id})
            assert linked.status_code == 200, linked.text
            restricted = await client.put(f"/api/groups/{group_id}/send-permissions", headers=group_owner,
                                          json={"default_send_restrictions": 32768})
            assert restricted.status_code == 200, restricted.text
            denied = await client.put(f"/api/channels/{channel_id}/discussion-group", headers=channel_owner,
                                      json={"group_id": None})
            assert denied.status_code == 403, denied.text
            owner_unlink = await client.put(f"/api/channels/{channel_id}/discussion-group", headers=group_owner,
                                            json={"group_id": None})
            assert owner_unlink.status_code == 200, owner_unlink.text

            # The group's owner bypasses the member-level ban, as for the other rights.
            owned_channel = await client.post(
                "/api/channels", headers=group_owner,
                json={"title": "Owner channel", "username": "owner_link_channel", "is_public": True},
            )
            owned_group = await client.post("/api/groups", headers=group_owner,
                                            json={"title": "Owner-managed links"})
            assert owned_channel.status_code == 201, owned_channel.text
            assert owned_group.status_code == 201, owned_group.text
            owned_channel_id, owned_group_id = owned_channel.json()["id"], owned_group.json()["id"]
            assert (await client.put(f"/api/groups/{owned_group_id}/send-permissions", headers=group_owner,
                                     json={"default_send_restrictions": 32768})).status_code == 200
            owner_link = await client.put(f"/api/channels/{owned_channel_id}/discussion-group",
                                          headers=group_owner, json={"group_id": owned_group_id})
            assert owner_link.status_code == 200, owner_link.text
            owner_unlink = await client.put(f"/api/channels/{owned_channel_id}/discussion-group",
                                            headers=group_owner, json={"group_id": None})
            assert owner_unlink.status_code == 200, owner_unlink.text

    run(scenario())


def test_discussion_root_survives_pre_history_floor_for_explicit_ids(env) -> None:
    """Ссылочный запрос по id возвращает корень обсуждения даже при скрытой предистории.

    Клиент загружает корень через `/messages?message_ids=`, а комментарии — через
    `/threads/{root}/replies`. Пока floor отсекал корень, первый запрос отвечал пусто,
    и пост выглядел удалённым из группы обсуждения.
    """
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, subscriber = headers(101), headers(102)
            channel = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Pre-history", "username": "pre_history_channel", "is_public": True},
            )
            assert channel.status_code == 201, channel.text
            cid = channel.json()["id"]
            group = await client.post("/api/groups", headers=owner, json={"title": "Pre-history chat"})
            assert group.status_code == 201, group.text
            gid = group.json()["id"]
            assert (await client.put(f"/api/channels/{cid}/discussion-group", headers=owner,
                                     json={"group_id": gid})).status_code == 200

            post = await client.post(f"/api/channels/{cid}/messages", headers=owner, json={"body": "old post"})
            assert post.status_code == 201, post.text
            pid = post.json()["id"]
            assert (await client.put(f"/api/groups/{gid}/settings", headers=owner,
                                     json={"pre_history_hidden": True})).status_code == 200

            # Подписчик вступает в группу обсуждений уже после публикации поста.
            discussion = await client.get(f"/api/channels/{cid}/messages/{pid}/discussion", headers=subscriber)
            assert discussion.status_code == 200, discussion.text
            root = discussion.json()["root_message_id"]
            assert root > 0

            replies = await client.get(f"/api/groups/{gid}/threads/{root}/replies?include_root=true",
                                       headers=subscriber)
            assert replies.status_code == 200, replies.text
            assert replies.json()["ids"] == [root]

            messages = await client.get(f"/api/groups/{gid}/messages?message_ids={root}", headers=subscriber)
            assert messages.status_code == 200, messages.text
            assert [item["id"] for item in messages.json()["messages"]] == [root]

            # Обычная история по-прежнему скрывает старые сообщения от новичка.
            history = await client.get(f"/api/groups/{gid}/messages", headers=subscriber)
            assert history.status_code == 200, history.text
            assert history.json()["messages"] == []

    run(scenario())
