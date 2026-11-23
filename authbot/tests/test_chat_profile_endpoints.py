"""Экран профиля группы/канала: общие группы, присутствие, приглашения админов, жалоба."""

from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


def test_common_chats_lists_only_shared_groups(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            shared = (await client.post(
                "/api/groups", headers=headers(101), json={"title": "Shared", "member_ids": [102]},
            )).json()["id"]
            lonely = (await client.post(
                "/api/groups", headers=headers(101), json={"title": "Lonely"},
            )).json()["id"]

            data = (await client.get("/api/users/102/common-chats", headers=headers(101))).json()
            ids = [chat["id"] for chat in data["chats"]]
            assert ids == [shared] and data["count"] == 1
            assert data["chats"][0]["my_role"] == "owner"
            assert lonely not in ids

            # Себе общие чаты не показываются.
            assert (await client.get("/api/users/101/common-chats", headers=headers(101))).json()["count"] == 0
            # Для постороннего пользователя в списке остаются только реально общие группы.
            other = (await client.get("/api/users/101/common-chats", headers=headers(102))).json()
            assert [chat["id"] for chat in other["chats"]] == [shared]

    run(scenario())


def test_group_onlines_counts_recent_members(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            group_id = (await client.post(
                "/api/groups", headers=headers(101), json={"title": "Online", "member_ids": [102]},
            )).json()["id"]

            # Оба участника только что обращались к API: присутствие обновилось.
            assert (await client.get(f"/api/groups/{group_id}", headers=headers(102))).status_code == 200
            onlines = (await client.get(f"/api/groups/{group_id}/onlines", headers=headers(101))).json()
            assert onlines["onlines"] >= 2

            # Посторонний не видит счётчик.
            assert (await client.get(f"/api/groups/{group_id}/onlines", headers=headers(ADMIN))).status_code in (403, 404)

    run(scenario())


def test_admins_with_invites_counts_links_per_admin(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            channel = (await client.post(
                "/api/channels", headers=headers(101), json={"title": "Links", "is_public": False},
            )).json()
            cid = channel["id"]
            for _ in range(2):
                created = await client.post(f"/api/channels/{cid}/invites", headers=headers(101), json={})
                assert created.status_code == 201

            data = (await client.get(f"/api/channels/{cid}/admins-with-invites", headers=headers(101))).json()
            owner = {row["telegram_id"]: row for row in data["admins"]}[101]
            assert owner["invites_count"] == 2 and owner["revoked_invites_count"] == 0

            # Подписчик канала не видит статистику ссылок администраторов.
            assert (await client.put(
                f"/api/channels/{cid}/membership", headers=headers(102), json={"joined": True},
            )).status_code in (200, 403)
            assert (await client.get(
                f"/api/channels/{cid}/admins-with-invites", headers=headers(102),
            )).status_code in (403, 404)

            group_id = (await client.post(
                "/api/groups", headers=headers(101), json={"title": "Group links", "member_ids": [102]},
            )).json()["id"]
            await client.post(f"/api/groups/{group_id}/invites", headers=headers(101), json={})
            group_data = (await client.get(
                f"/api/groups/{group_id}/admins-with-invites", headers=headers(101),
            )).json()
            group_owner = {row["telegram_id"]: row for row in group_data["admins"]}[101]
            assert group_owner["invites_count"] >= 1
            assert (await client.get(
                f"/api/groups/{group_id}/admins-with-invites", headers=headers(102),
            )).status_code == 403

    run(scenario())


def test_peer_report_without_message_ids_is_accepted(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            report = await client.post("/api/reports", headers=headers(101), json={
                "scope": "dm", "peer_id": 102, "option": "spam", "message_ids": [], "message": "",
            })
            assert report.status_code == 201 and report.json()["reported"] == 1
            # Жалоба с id сообщений по-прежнему работает.
            message = (await client.post(
                "/api/messages", headers=headers(102), json={"recipient_id": 101, "body": "spam"},
            )).json()
            with_id = await client.post("/api/reports", headers=headers(101), json={
                "scope": "dm", "peer_id": 102, "option": "spam", "message_ids": [message["id"]],
            })
            assert with_id.status_code == 201 and with_id.json()["reported"] == 1

    run(scenario())
