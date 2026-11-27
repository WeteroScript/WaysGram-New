"""Rights of several app sessions on one device (P0 E1/E2).

A WaysGram session only authenticates the caller; it never grants channel
ownership. Posts, albums and edits must be checked against the channel owner
and per-admin rights for *every* token, including additional sessions of the
same installation.
"""
from test_messenger_api import add_session, client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


def _session(path, telegram_id, suffix):
    """Create one more live token for a user, like another app session."""
    token = f"multi-{telegram_id}-{suffix}-token"
    add_session(path, telegram_id, token)
    return {"Authorization": f"Bearer {token}"}


def test_three_sessions_do_not_grant_channel_rights(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            owner_extra = _session(path, 101, "second")
            owner_third = _session(path, 101, "third")
            outsider = headers(102)
            outsider_extra = _session(path, 102, "second")
            outsider_third = _session(path, 102, "third")

            channel = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Session rights", "username": "session_rights", "is_public": True},
            )
            assert channel.status_code == 201, channel.text
            cid = channel.json()["id"]
            root = f"/api/channels/{cid}"

            # All three sessions of the real owner keep full rights.
            for h in (owner, owner_extra, owner_third):
                post = await client.post(root + "/messages", headers=h, json={"body": "from owner"})
                assert post.status_code == 201, post.text
                edit = await client.patch(root + f"/messages/{post.json()['id']}", headers=h,
                                          json={"body": "edited by owner"})
                assert edit.status_code == 200, edit.text
                assert (await client.patch(root, headers=h, json={"title": "Renamed by owner"})).status_code == 200

            # Every session of an unrelated account is rejected on all three writes.
            for h in (outsider, outsider_extra, outsider_third):
                post = await client.post(root + "/messages", headers=h, json={"body": "intruder"})
                assert post.status_code == 403, post.text
                album = await client.post(root + "/messages/album", headers=h,
                                          json={"media_ids": ["0" * 32, "1" * 32], "caption": ""})
                assert album.status_code == 403, album.text
                edit = await client.patch(root + "/messages/1", headers=h, json={"body": "intruder edit"})
                assert edit.status_code == 403, edit.text
                assert (await client.patch(root, headers=h, json={"title": "Intruder"})).status_code == 403

            feed = await client.get(root + "/messages", headers=owner)
            assert feed.status_code == 200
            assert len(feed.json()["messages"]) == 3
            assert (await client.get(root, headers=owner)).json()["title"] == "Renamed by owner"

    run(scenario())


def test_admin_without_post_right_cannot_publish_through_any_channel_route(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, admin = headers(101), headers(102)
            channel = await client.post(
                "/api/channels", headers=owner,
                json={"title": "No post right", "username": "no_post_right", "is_public": True},
            )
            cid = channel.json()["id"]
            root = f"/api/channels/{cid}"
            assert (await client.post(root + "/admins", headers=owner,
                                      json={"telegram_id": 102, "rights": 64})).status_code == 201
            profile = (await client.get(root, headers=admin)).json()
            assert profile["admin_rights"] == 64
            assert profile["can_post"] is False

            source = await client.post(root + "/messages", headers=owner, json={"body": "forward source"})
            assert source.status_code == 201, source.text
            denied_post = await client.post(root + "/messages", headers=admin, json={"body": "not allowed"})
            assert denied_post.status_code == 403, denied_post.text
            denied_album = await client.post(
                root + "/messages/album", headers=admin,
                json={"media_ids": ["0" * 32, "1" * 32], "caption": ""},
            )
            assert denied_album.status_code == 403, denied_album.text
            denied_forward = await client.post("/api/forward", headers=admin, json={
                "source_scope": "channel", "source_peer_id": cid,
                "target_scope": "channel", "target_peer_id": cid,
                "message_ids": [source.json()["id"]],
            })
            assert denied_forward.status_code == 403, denied_forward.text
            denied_topic = await client.post(
                root + "/topics/1/messages", headers=admin, json={"body": "not allowed in a topic"},
            )
            assert denied_topic.status_code == 403, denied_topic.text

    run(scenario())


def test_admin_rights_are_per_session_owner_not_per_authorization(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            admin = headers(102)
            outsider = headers(ADMIN)
            admin_second = _session(path, 102, "second")
            outsider_second = _session(path, ADMIN, "second")

            channel = await client.post(
                "/api/channels", headers=owner,
                json={"title": "Admin bits", "username": "admin_bits", "is_public": True},
            )
            cid = channel.json()["id"]
            root = f"/api/channels/{cid}"
            assert (await client.post(root + "/admins", headers=owner,
                                      json={"telegram_id": 102, "rights": 1})).status_code == 201

            # Bit 1 (post) alone never implies edit (bit 2) or change-info (bit 64).
            for h in (admin, admin_second):
                post = await client.post(root + "/messages", headers=h, json={"body": "admin post"})
                assert post.status_code == 201, post.text
                assert (await client.patch(root + f"/messages/{post.json()['id']}", headers=h,
                                           json={"body": "denied"})).status_code == 403
                assert (await client.patch(root, headers=h, json={"description": "denied"})).status_code == 403

            # Even an AuthBot administrator has no implicit channel rights, in any session.
            for h in (outsider, outsider_second):
                assert (await client.post(root + "/messages", headers=h, json={"body": "denied"})).status_code == 403
                assert (await client.patch(root + "/messages/1", headers=h,
                                           json={"body": "denied"})).status_code == 403

            # Revoking the right invalidates every session of that admin immediately.
            assert (await client.delete(root + "/admins/102", headers=owner)).status_code == 200
            for h in (admin, admin_second):
                assert (await client.post(root + "/messages", headers=h,
                                          json={"body": "denied"})).status_code == 403

    run(scenario())
