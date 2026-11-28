"""Права на управление чатом не должны появляться у участника без назначения.

`group_members.admin_rights` создавалась с DEFAULT 63, поэтому обычный участник
получал в API полную маску прав администратора группы. Клиент читает это поле как
готовые права (`WaysGramChats.toChat`/`fullChat`), из-за чего в приложении
появлялись «Редактировать» и админ-действия у людей, которых никто не назначал.
Те же маски отдавались в списке групп, в карточке общего чата и в ростере.
"""
from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)

import db_split
from messenger_api import init_messenger_storage


def test_member_without_appointment_has_no_group_rights(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            member = headers(102)

            group = await client.post("/api/groups", headers=owner, json={"title": "Rights"})
            assert group.status_code == 201, group.text
            gid = group.json()["id"]

            invite = await client.post(f"/api/groups/{gid}/permanent-invite", headers=owner, json={})
            assert invite.status_code == 200, invite.text
            joined = await client.post("/api/groups/join", headers=member,
                                       json={"invite_token": invite.json()["invite_token"]})
            assert joined.status_code == 200, joined.text

            async def row(uid: int) -> dict:
                listing = await client.get("/api/groups", headers=headers(uid))
                return next(g for g in listing.json()["groups"] if g["id"] == gid)

            owner_row = await row(101)
            assert owner_row["role"] == "owner" and owner_row["admin_rights"] == 63

            member_row = await row(102)
            assert member_row["role"] == "member"
            # Раньше здесь приходило 63 — полная маска прав администратора группы.
            assert member_row["admin_rights"] == 0

            detail = await client.get(f"/api/groups/{gid}", headers=member)
            assert detail.json()["my_role"] == "member"
            assert detail.json()["admin_rights"] == 0

            roster = detail.json()["members"]
            assert next(m for m in roster if m["telegram_id"] == 102)["admin_rights"] == 0

            # Ограничения и правка группы по-прежнему закрыты для участника.
            assert (await client.put(f"/api/groups/{gid}", headers=member,
                                     json={"title": "Taken over"})).status_code == 403

            # Назначенный администратор получает ровно выданную маску, снятие её убирает.
            assert (await client.post(f"/api/groups/{gid}/admins", headers=owner,
                                      json={"telegram_id": 102, "rights": 2})).status_code == 201
            appointed = await row(102)
            assert appointed["role"] == "admin" and appointed["admin_rights"] == 2
            assert (await client.delete(f"/api/groups/{gid}/admins/102", headers=owner)).status_code == 200
            assert (await row(102))["admin_rights"] == 0

    run(scenario())


def test_legacy_group_rows_are_cleaned_on_startup(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            group = await client.post("/api/groups", headers=headers(101), json={"title": "Legacy"})
            gid = group.json()["id"]
            globals()["LEGACY_GID"] = gid
            invite = await client.post(f"/api/groups/{gid}/permanent-invite", headers=headers(101), json={})
            await client.post("/api/groups/join", headers=headers(102),
                              json={"invite_token": invite.json()["invite_token"]})

    run(scenario())

    gid = globals().pop("LEGACY_GID")
    # Так выглядела строка участника в базах, созданных до исправления.
    with db_split.sqlite_connect(path) as db:
        db.execute("UPDATE group_members SET admin_rights=63 WHERE group_id=? AND telegram_id=102", (gid,))
        db.commit()
        assert db.execute("SELECT admin_rights FROM group_members WHERE group_id=? AND telegram_id=102",
                          (gid,)).fetchone()[0] == 63
    run(init_messenger_storage(path))
    with db_split.sqlite_connect(path) as db:
        rights, role = db.execute(
            "SELECT admin_rights,role FROM group_members WHERE group_id=? AND telegram_id=102", (gid,)
        ).fetchone()
    assert role == "member" and rights == 0


def test_channel_list_reports_owner_as_admin_but_not_members(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            channel = await client.post("/api/channels", headers=headers(101),
                                        json={"title": "Owner bits", "username": "owner_bits"})
            assert channel.status_code == 201, channel.text
            cid = channel.json()["id"]

            async def listed(uid: int) -> dict:
                items = (await client.get("/api/channels", headers=headers(uid))).json()["channels"]
                return next(c for c in items if c["id"] == cid)

            owner_item = await listed(101)
            # Деталь канала давно отвечала is_admin=True владельцу; список — нет.
            assert owner_item["is_admin"] in (1, True)
            assert owner_item["owner_telegram_id"] == 101

            member_item = await listed(102)
            assert not member_item["is_admin"]
            assert member_item["admin_rights"] == 0

            detail = await client.get(f"/api/channels/{cid}", headers=headers(102))
            assert detail.json()["is_admin"] is False

    run(scenario())
