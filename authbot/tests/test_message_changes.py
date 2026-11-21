import asyncio

from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


def test_replies_are_validated_and_returned(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            first = (await client.post("/api/messages", headers=headers(101), json={"recipient_id": 102, "body": "hi"})).json()
            reply = await client.post(
                "/api/messages", headers=headers(102),
                json={"recipient_id": 101, "body": "hello", "reply_to_id": first["id"]},
            )
            assert reply.status_code == 201 and reply.json()["reply_to_id"] == first["id"]
            listed = (await client.get("/api/messages?with=102", headers=headers(101))).json()["messages"]
            assert listed[-1]["reply_to_id"] == first["id"] and listed[0]["reply_to_id"] is None
            assert listed[-1]["reply_to_body"] == "hi"
            assert listed[-1]["reply_to_sender_id"] == 101

            # A message of another conversation cannot be replied to.
            other = (await client.post("/api/messages", headers=headers(101), json={"recipient_id": ADMIN, "body": "x"})).json()
            bad = await client.post(
                "/api/messages", headers=headers(101),
                json={"recipient_id": 102, "body": "no", "reply_to_id": other["id"]},
            )
            assert bad.status_code == 404
            assert (await client.post(
                "/api/messages", headers=headers(101), json={"recipient_id": 102, "body": "no", "reply_to_id": "x"}
            )).status_code == 400

    run(scenario())


def test_edit_and_delete_private_messages_with_events(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            sent = (await client.post("/api/messages", headers=headers(101), json={"recipient_id": 102, "body": "typo"})).json()
            mid = sent["id"]
            cursor = (await client.get("/api/events", headers=headers(102))).json()["latest_id"]

            assert (await client.patch(f"/api/messages/{mid}", headers=headers(102), json={"body": "x"})).status_code == 403
            assert (await client.patch(f"/api/messages/{mid}", headers=headers(101), json={"body": "  "})).status_code == 400
            edited = await client.patch(f"/api/messages/{mid}", headers=headers(101), json={"body": "fixed"})
            assert edited.status_code == 200
            put_edited = await client.put(f"/api/messages/{mid}", headers=headers(101), json={"body": "fixed via PUT"})
            assert put_edited.status_code == 200
            listed = (await client.get("/api/messages?with=101", headers=headers(102))).json()["messages"]
            assert listed[-1]["body"] == "fixed via PUT" and listed[-1]["edited_at"]

            events = (await client.get(f"/api/events?after_id={cursor}", headers=headers(102))).json()
            assert [(e["kind"], e["message_id"], e["scope"]) for e in events["events"]] == [
                ("edit", mid, "dm"), ("edit", mid, "dm")
            ]
            # The author does not get an event for their own action.
            assert (await client.get(f"/api/events?after_id={cursor}", headers=headers(101))).json()["events"] == []
            # Someone outside the conversation sees nothing.
            assert (await client.get(f"/api/events?after_id={cursor}", headers=headers(ADMIN))).json()["events"] == []

            assert (await client.delete(f"/api/messages/{mid}", headers=headers(ADMIN))).status_code == 404
            assert (await client.delete(f"/api/messages/{mid}", headers=headers(101))).status_code == 200
            assert (await client.delete(f"/api/messages/{mid}", headers=headers(101))).status_code == 404
            assert (await client.get("/api/messages?with=101", headers=headers(102))).json()["messages"] == []
            events = (await client.get(f"/api/events?after_id={events['latest_id']}", headers=headers(102))).json()
            assert [(e["kind"], e["message_id"]) for e in events["events"]] == [("delete", mid)]

    run(scenario())


def test_edit_saved_message_via_put_and_lookup(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            sent = await client.post("/api/messages", headers=headers(101), json={"recipient_id": 101, "body": "saved"})
            assert sent.status_code == 201
            mid = sent.json()["id"]
            assert (await client.put(f"/api/messages/{mid}", headers=headers(102), json={"body": "not mine"})).status_code == 403
            assert (await client.put(f"/api/messages/{mid}", headers=headers(101), json={"body": "edited"})).status_code == 200
            found = await client.get(f"/api/messages?with=101&after_id={max(mid - 1, 0)}", headers=headers(101))
            assert found.status_code == 200
            assert any(item["id"] == mid and item["body"] == "edited" for item in found.json()["messages"])

    run(scenario())


def test_group_edit_delete_and_admin_rights(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            group = (await client.post("/api/groups", headers=headers(101), json={"title": "Team"})).json()
            gid = group["id"]
            assert (await client.post(f"/api/groups/{gid}/members", headers=headers(101), json={"telegram_id": 102})).status_code in (200, 201)
            m1 = (await client.post(f"/api/groups/{gid}/messages", headers=headers(102), json={"body": "from member"})).json()
            m2 = (await client.post(f"/api/groups/{gid}/messages", headers=headers(101), json={"body": "from owner", "reply_to_id": m1["id"]})).json()
            assert m2["reply_to_id"] == m1["id"]
            assert (await client.post(f"/api/groups/{gid}/messages", headers=headers(101), json={"body": "x", "reply_to_id": 99999})).status_code == 404

            # Only the author edits.
            assert (await client.patch(f"/api/groups/{gid}/messages/{m1['id']}", headers=headers(101), json={"body": "hack"})).status_code == 403
            assert (await client.patch(f"/api/groups/{gid}/messages/{m1['id']}", headers=headers(102), json={"body": "edited"})).status_code == 200
            assert (await client.put(f"/api/groups/{gid}/messages/{m1['id']}", headers=headers(102), json={"body": "edited via PUT"})).status_code == 200
            before_delete = (await client.get(
                f"/api/groups/{gid}/messages", headers=headers(102)
            )).json()["messages"]
            assert before_delete[1]["reply_to_body"] == "edited via PUT"
            assert before_delete[1]["reply_to_sender_id"] == 102
            # A member cannot delete the owner's message, the owner can delete anybody's.
            assert (await client.delete(f"/api/groups/{gid}/messages/{m2['id']}", headers=headers(102))).status_code == 403
            assert (await client.delete(f"/api/groups/{gid}/messages/{m1['id']}", headers=headers(101))).status_code == 200
            # Outsiders are refused.
            assert (await client.delete(f"/api/groups/{gid}/messages/{m2['id']}", headers=headers(ADMIN))).status_code == 403

            remaining = (await client.get(f"/api/groups/{gid}/messages", headers=headers(102))).json()["messages"]
            assert [m["id"] for m in remaining] == [m2["id"]]
            assert remaining[0]["reply_to_body"] is None
            assert remaining[0]["reply_to_sender_id"] is None

    run(scenario())


def test_channel_posts_can_be_edited_and_deleted_by_the_owner_only(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            channel = (await client.post("/api/channels", headers=headers(101), json={"title": "News", "username": "news_ch"})).json()
            cid = channel["id"]
            post = (await client.post(f"/api/channels/{cid}/messages", headers=headers(101), json={"body": "first"})).json()
            assert (await client.patch(f"/api/channels/{cid}/messages/{post['id']}", headers=headers(102), json={"body": "x"})).status_code == 403
            assert (await client.patch(f"/api/channels/{cid}/messages/{post['id']}", headers=headers(101), json={"body": "second"})).status_code == 200
            assert (await client.put(f"/api/channels/{cid}/messages/{post['id']}", headers=headers(101), json={"body": "second via PUT"})).status_code == 200
            reply_post = await client.post(
                f"/api/channels/{cid}/messages",
                headers=headers(101),
                json={"body": "reply to channel post", "reply_to_id": post["id"]},
            )
            assert reply_post.status_code == 201
            listed = (await client.get(f"/api/channels/{cid}/messages", headers=headers(102))).json()["messages"]
            assert listed[0]["body"] == "second via PUT" and listed[0]["edited_at"]
            assert listed[1]["reply_to_body"] == "second via PUT"
            assert listed[1]["reply_to_sender_id"] == 101
            assert (await client.delete(f"/api/channels/{cid}/messages/{post['id']}", headers=headers(102))).status_code == 403
            assert (await client.delete(f"/api/channels/{cid}/messages/{post['id']}", headers=headers(101))).status_code == 200
            remaining = (await client.get(f"/api/channels/{cid}/messages", headers=headers(102))).json()["messages"]
            assert [message["id"] for message in remaining] == [reply_post.json()["id"]]
            assert remaining[0]["reply_to_body"] is None
            assert remaining[0]["reply_to_sender_id"] is None

    run(scenario())


def test_read_markers_and_unread_counters(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            ids = []
            for text in ("a", "b", "c"):
                ids.append((await client.post("/api/messages", headers=headers(101), json={"recipient_id": 102, "body": text})).json()["id"])

            conv = (await client.get("/api/conversations", headers=headers(102))).json()["conversations"][0]
            assert conv["unread_count"] == 3 and conv["my_read_id"] == 0
            # The sender has nothing unread and sees that nothing was read yet.
            mine = (await client.get("/api/conversations", headers=headers(101))).json()["conversations"][0]
            assert mine["unread_count"] == 0 and mine["peer_read_id"] == 0

            cursor = (await client.get("/api/events", headers=headers(101))).json()["latest_id"]
            read = await client.put("/api/read", headers=headers(102), json={"scope": "dm", "peer_id": 101, "last_read_id": ids[1]})
            assert read.status_code == 200 and read.json()["last_read_id"] == ids[1]
            conv = (await client.get("/api/conversations", headers=headers(102))).json()["conversations"][0]
            assert conv["unread_count"] == 1
            mine = (await client.get("/api/conversations", headers=headers(101))).json()["conversations"][0]
            assert mine["peer_read_id"] == ids[1]
            history = (await client.get("/api/messages?with=102", headers=headers(101))).json()
            assert history["peer_read_id"] == ids[1]

            # The sender is told that the messages were read; the marker never moves back.
            events = (await client.get(f"/api/events?after_id={cursor}", headers=headers(101))).json()["events"]
            assert [(e["kind"], e["message_id"]) for e in events] == [("read", ids[1])]
            back = await client.put("/api/read", headers=headers(102), json={"scope": "dm", "peer_id": 101, "last_read_id": 1})
            assert back.json()["last_read_id"] == ids[1]

            assert (await client.put("/api/read", headers=headers(102), json={"scope": "x", "peer_id": 1, "last_read_id": 1})).status_code == 400
            assert (await client.put("/api/read", headers=headers(102), json={"scope": "dm", "peer_id": 424242, "last_read_id": 1})).status_code == 404

    run(scenario())


def test_group_and_channel_unread_counters(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            gid = (await client.post("/api/groups", headers=headers(101), json={"title": "G"})).json()["id"]
            await client.post(f"/api/groups/{gid}/members", headers=headers(101), json={"telegram_id": 102})
            g1 = (await client.post(f"/api/groups/{gid}/messages", headers=headers(101), json={"body": "1"})).json()["id"]
            await client.post(f"/api/groups/{gid}/messages", headers=headers(101), json={"body": "2"})
            await client.post(f"/api/groups/{gid}/messages", headers=headers(102), json={"body": "own"})
            group = next(g for g in (await client.get("/api/groups", headers=headers(102))).json()["groups"] if g["id"] == gid)
            assert group["unread_count"] == 2  # own messages do not count
            await client.put("/api/read", headers=headers(102), json={"scope": "group", "peer_id": gid, "last_read_id": g1})
            group = next(g for g in (await client.get("/api/groups", headers=headers(102))).json()["groups"] if g["id"] == gid)
            assert group["unread_count"] == 1 and group["last_read_id"] == g1
            # A non-member cannot set a marker for the group.
            assert (await client.put("/api/read", headers=headers(ADMIN), json={"scope": "group", "peer_id": gid, "last_read_id": 1})).status_code == 403

            cid = (await client.post("/api/channels", headers=headers(101), json={"title": "C", "username": "c_chan"})).json()["id"]
            await client.post(f"/api/channels/{cid}/messages", headers=headers(101), json={"body": "p1"})
            p2 = (await client.post(f"/api/channels/{cid}/messages", headers=headers(101), json={"body": "p2"})).json()["id"]
            channel = next(c for c in (await client.get("/api/channels", headers=headers(102))).json()["channels"] if c["id"] == cid)
            assert channel["unread_count"] == 2
            await client.put("/api/read", headers=headers(102), json={"scope": "channel", "peer_id": cid, "last_read_id": p2})
            channel = next(c for c in (await client.get("/api/channels", headers=headers(102))).json()["channels"] if c["id"] == cid)
            assert channel["unread_count"] == 0

    run(scenario())


def test_existing_messages_count_as_read_after_the_upgrade(tmp_path, monkeypatch) -> None:
    """A database from before read marks must not show every old message as unread."""
    import sqlite3

    import db_split
    from cryptography.fernet import Fernet

    import bot as bot_module
    from messenger_api import init_messenger_storage

    path = tmp_path / "w.sqlite3"
    monkeypatch.setattr(bot_module, "DATABASE_PATH", path)
    store = bot_module.Store(path)
    store.configure_otp_encryption(Fernet.generate_key().decode("ascii"))
    run(store.init())
    run(bot_module.init_admin_storage())
    run(init_messenger_storage(path))
    conn = db_split.sqlite_connect(path)
    conn.execute("INSERT INTO messages(sender_telegram_id,recipient_telegram_id,body,created_at) VALUES(1,2,'old',1)")
    conn.execute("INSERT INTO messages(sender_telegram_id,recipient_telegram_id,body,created_at) VALUES(1,2,'older',2)")
    conn.execute("PRAGMA messenger.user_version=0")  # as if the database predates read marks
    conn.execute("DELETE FROM read_markers")
    conn.commit()
    conn.close()

    run(init_messenger_storage(path))
    conn = db_split.sqlite_connect(path)
    marks = conn.execute("SELECT telegram_id,scope,peer_id,last_read_id FROM read_markers").fetchall()
    conn.close()
    assert marks == [(2, "dm", 1, 2)]

    # Running it again must not hide messages that arrive later.
    conn = db_split.sqlite_connect(path)
    conn.execute("INSERT INTO messages(sender_telegram_id,recipient_telegram_id,body,created_at) VALUES(1,2,'new',3)")
    conn.execute("DELETE FROM read_markers")
    conn.commit()
    conn.close()
    run(init_messenger_storage(path))
    conn = db_split.sqlite_connect(path)
    assert conn.execute("SELECT COUNT(*) FROM read_markers").fetchone()[0] == 0
    conn.close()


def test_group_message_can_be_deleted_knowing_only_its_id(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            gid = (await client.post("/api/groups", headers=headers(101), json={"title": "ById"})).json()["id"]
            await client.post(f"/api/groups/{gid}/members", headers=headers(101), json={"telegram_id": 102})
            mid = (await client.post(f"/api/groups/{gid}/messages", headers=headers(102), json={"body": "bye"})).json()["id"]
            assert (await client.delete(f"/api/group-messages/{mid}", headers=headers(ADMIN))).status_code == 403
            assert (await client.delete(f"/api/group-messages/{mid}", headers=headers(102))).status_code == 200
            assert (await client.delete(f"/api/group-messages/{mid}", headers=headers(102))).status_code == 404

    run(scenario())


def test_direct_message_search_is_scoped_to_conversation(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            await client.post("/api/messages", headers=headers(101), json={
                "recipient_id": 102, "body": "Searchable hello world",
            })
            await client.post("/api/messages", headers=headers(102), json={
                "recipient_id": 101, "body": "Another HELLO from peer",
            })
            await client.post("/api/messages", headers=headers(101), json={
                "recipient_id": ADMIN, "body": "HELLO in another conversation",
            })

            found = await client.get(
                "/api/messages/search?with=102&q=hello",
                headers=headers(101),
            )
            assert found.status_code == 200
            payload = found.json()
            assert payload["count"] == 2
            assert [item["body"] for item in payload["messages"]] == [
                "Searchable hello world",
                "Another HELLO from peer",
            ]
            assert payload["has_more"] is False

            none = await client.get(
                "/api/messages/search?with=102&q=another%20conversation",
                headers=headers(101),
            )
            assert none.status_code == 200 and none.json()["count"] == 0

            invalid = await client.get(
                "/api/messages/search?with=102&q=%20%20",
                headers=headers(101),
            )
            assert invalid.status_code == 400

            missing_auth = await client.get("/api/messages/search?with=102&q=hello")
            assert missing_auth.status_code == 401

    run(scenario())



def test_group_and_channel_message_search_enforces_access(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            group = (await client.post(
                "/api/groups", headers=headers(101), json={"title": "Search Group"},
            )).json()
            group_id = group["id"]
            await client.post(
                f"/api/groups/{group_id}/members",
                headers=headers(101),
                json={"telegram_id": 102},
            )
            await client.post(
                f"/api/groups/{group_id}/messages",
                headers=headers(102),
                json={"body": "Group search marker"},
            )
            group_found = await client.get(
                f"/api/groups/{group_id}/messages/search?q=search",
                headers=headers(101),
            )
            assert group_found.status_code == 200
            assert group_found.json()["count"] == 1
            denied_group = await client.get(
                f"/api/groups/{group_id}/messages/search?q=search",
                headers=headers(ADMIN),
            )
            assert denied_group.status_code == 403

            channel = (await client.post(
                "/api/channels",
                headers=headers(101),
                json={"title": "Search Channel", "username": "search_channel"},
            )).json()
            channel_id = channel["id"]
            await client.post(
                f"/api/channels/{channel_id}/messages",
                headers=headers(101),
                json={"body": "Channel search marker"},
            )
            channel_found = await client.get(
                f"/api/channels/{channel_id}/messages/search?q=marker",
                headers=headers(102),
            )
            assert channel_found.status_code == 200
            assert channel_found.json()["count"] == 1

    run(scenario())



def test_contacts_can_be_added_listed_and_removed(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            auth = headers(101)
            initial = await client.get("/api/contacts", headers=auth)
            assert initial.status_code == 200
            assert initial.json()["count"] == 0

            added = await client.post(
                "/api/contacts", headers=auth, json={"user_id": 102},
            )
            assert added.status_code == 201
            assert added.json()["status"] == "added"
            assert added.json()["contact"]["id"] == 102

            duplicate = await client.post(
                "/api/contacts", headers=auth, json={"user_id": 102},
            )
            assert duplicate.status_code == 201
            assert duplicate.json()["status"] == "already_exists"

            contacts = await client.get("/api/contacts", headers=auth)
            assert contacts.json()["count"] == 1
            assert contacts.json()["contacts"][0]["id"] == 102

            assert (await client.post(
                "/api/contacts", headers=auth, json={"user_id": 101},
            )).status_code == 400
            assert (await client.post(
                "/api/contacts", headers=auth, json={"user_id": "102"},
            )).status_code == 400
            assert (await client.post(
                "/api/contacts", headers=auth, json={"user_id": 999999},
            )).status_code == 404

            removed = await client.delete("/api/contacts/102", headers=auth)
            assert removed.status_code == 200
            assert removed.json()["status"] == "removed"
            assert (await client.get("/api/contacts", headers=auth)).json()["count"] == 0
            assert (await client.delete("/api/contacts/102", headers=auth)).status_code == 404
            assert (await client.get("/api/contacts")).status_code == 401

    run(scenario())



def test_direct_messages_can_be_forwarded_with_original_attribution(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            original = await client.post(
                "/api/messages",
                headers=headers(101),
                json={"recipient_id": 102, "body": "Forward this message"},
            )
            assert original.status_code == 201
            original_id = original.json()["id"]

            key = "wg-forward-message-00001"
            forwarded = await client.post(
                f"/api/messages/{original_id}/forward",
                headers={**headers(102), "Idempotency-Key": key},
                json={"recipient_id": ADMIN},
            )
            assert forwarded.status_code == 201
            result = forwarded.json()
            assert result["status"] == "forwarded"
            assert result["body"] == "Forward this message"
            assert result["forwarded_from_telegram_id"] == 101
            assert result["forwarded_from_message_id"] == original_id

            retry = await client.post(
                f"/api/messages/{original_id}/forward",
                headers={**headers(102), "Idempotency-Key": key},
                json={"recipient_id": ADMIN},
            )
            assert retry.status_code == 201
            assert retry.json()["id"] == result["id"]

            history = await client.get(
                f"/api/messages?with=102",
                headers=headers(ADMIN),
            )
            forwarded_rows = [
                item for item in history.json()["messages"]
                if item["id"] == result["id"]
            ]
            assert len(forwarded_rows) == 1
            assert forwarded_rows[0]["forwarded_from_telegram_id"] == 101
            assert forwarded_rows[0]["forwarded_from_message_id"] == original_id
            assert forwarded_rows[0]["forwarded_from_display_name"]

            denied = await client.post(
                f"/api/messages/{original_id}/forward",
                headers=headers(ADMIN),
                json={"recipient_id": 101},
            )
            assert denied.status_code == 404

            missing_target = await client.post(
                f"/api/messages/{original_id}/forward",
                headers=headers(102),
                json={"recipient_id": 999999},
            )
            assert missing_target.status_code == 404

    run(scenario())



def test_user_blocklist_prevents_direct_messages_both_directions(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            auth_a = headers(101)
            auth_b = headers(102)

            invalid_self = await client.post("/api/blocks", headers=auth_a, json={"user_id": 101})
            assert invalid_self.status_code == 400
            invalid_id = await client.post("/api/blocks", headers=auth_a, json={"user_id": "102"})
            assert invalid_id.status_code == 400

            blocked = await client.post("/api/blocks", headers=auth_a, json={"user_id": 102})
            assert blocked.status_code == 201
            assert blocked.json()["status"] == "blocked"
            assert blocked.json()["user"]["id"] == 102

            duplicate = await client.post("/api/blocks", headers=auth_a, json={"user_id": 102})
            assert duplicate.status_code == 201
            assert duplicate.json()["status"] == "already_blocked"

            listed = await client.get("/api/blocks", headers=auth_a)
            assert listed.status_code == 200
            assert listed.json()["count"] == 1
            assert listed.json()["blocked_users"][0]["id"] == 102

            outbound = await client.post(
                "/api/messages", headers=auth_a,
                json={"recipient_id": 102, "body": "This should be denied"},
            )
            assert outbound.status_code == 403

            inbound = await client.post(
                "/api/messages", headers=auth_b,
                json={"recipient_id": 101, "body": "This should also be denied"},
            )
            assert inbound.status_code == 403

            forwarded_source = await client.post(
                "/api/messages", headers=auth_b,
                json={"recipient_id": ADMIN, "body": "Forward source"},
            )
            assert forwarded_source.status_code == 201
            forwarding = await client.post(
                f"/api/messages/{forwarded_source.json()['id']}/forward",
                headers=auth_b,
                json={"recipient_id": 101},
            )
            assert forwarding.status_code == 403

            unblocked = await client.delete("/api/blocks/102", headers=auth_a)
            assert unblocked.status_code == 200
            assert unblocked.json()["status"] == "unblocked"
            assert (await client.get("/api/blocks", headers=auth_a)).json()["count"] == 0

            allowed_after_unblock = await client.post(
                "/api/messages", headers=auth_b,
                json={"recipient_id": 101, "body": "Now it is allowed"},
            )
            assert allowed_after_unblock.status_code == 201
            assert (await client.delete("/api/blocks/102", headers=auth_a)).status_code == 404
            assert (await client.get("/api/blocks")).status_code == 401

    run(scenario())



def test_message_reactions_toggle_and_respect_chat_access(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            dm = (await client.post(
                "/api/messages", headers=headers(101),
                json={"recipient_id": 102, "body": "React here"},
            )).json()
            dm_id = dm["id"]

            first = await client.post(
                f"/api/messages/{dm_id}/reactions",
                headers=headers(102),
                json={"reaction": "👍"},
            )
            assert first.status_code == 200
            assert first.json()["status"] == "added"
            assert first.json()["reactions"] == [{"reaction": "👍", "count": 1}]
            assert first.json()["my_reactions"] == ["👍"]

            second = await client.post(
                f"/api/messages/{dm_id}/reactions",
                headers=headers(101),
                json={"reaction": "👍"},
            )
            assert second.status_code == 200
            assert second.json()["reactions"] == [{"reaction": "👍", "count": 2}]

            removed = await client.post(
                f"/api/messages/{dm_id}/reactions",
                headers=headers(101),
                json={"reaction": "👍"},
            )
            assert removed.status_code == 200 and removed.json()["status"] == "removed"
            assert removed.json()["reactions"] == [{"reaction": "👍", "count": 1}]
            assert removed.json()["my_reactions"] == []

            for emoji in ("❤️", "😂", "😮"):
                assert (await client.post(
                    f"/api/messages/{dm_id}/reactions",
                    headers=headers(101),
                    json={"reaction": emoji},
                )).status_code == 200
            too_many = await client.post(
                f"/api/messages/{dm_id}/reactions",
                headers=headers(101),
                json={"reaction": "🎉"},
            )
            assert too_many.status_code == 409

            invalid = await client.post(
                f"/api/messages/{dm_id}/reactions",
                headers=headers(101),
                json={"reaction": "not-an-emoji"},
            )
            assert invalid.status_code == 400

            outsider = await client.post(
                f"/api/messages/{dm_id}/reactions",
                headers=headers(ADMIN),
                json={"reaction": "👍"},
            )
            assert outsider.status_code == 404

            history = (await client.get(
                "/api/messages?with=102", headers=headers(101),
            )).json()["messages"]
            assert history[0]["reaction_summary"]

            group = (await client.post(
                "/api/groups", headers=headers(101), json={"title": "Reaction Group"},
            )).json()
            group_id = group["id"]
            await client.post(
                f"/api/groups/{group_id}/members",
                headers=headers(101),
                json={"telegram_id": 102},
            )
            group_message = (await client.post(
                f"/api/groups/{group_id}/messages",
                headers=headers(102),
                json={"body": "Group reaction"},
            )).json()
            group_reaction = await client.post(
                f"/api/groups/{group_id}/messages/{group_message['id']}/reactions",
                headers=headers(101),
                json={"reaction": "🎉"},
            )
            assert group_reaction.status_code == 200
            assert group_reaction.json()["reactions"] == [{"reaction": "🎉", "count": 1}]
            denied_group = await client.post(
                f"/api/groups/{group_id}/messages/{group_message['id']}/reactions",
                headers=headers(ADMIN),
                json={"reaction": "👍"},
            )
            assert denied_group.status_code == 403
            group_history = (await client.get(
                f"/api/groups/{group_id}/messages", headers=headers(102),
            )).json()["messages"]
            assert group_history[0]["reaction_summary"] == "🎉:1"

            channel = (await client.post(
                "/api/channels",
                headers=headers(101),
                json={"title": "Reaction Channel", "username": "reaction_channel"},
            )).json()
            channel_id = channel["id"]
            channel_post = (await client.post(
                f"/api/channels/{channel_id}/messages",
                headers=headers(101),
                json={"body": "Channel reaction"},
            )).json()
            channel_reaction = await client.post(
                f"/api/channels/{channel_id}/messages/{channel_post['id']}/reactions",
                headers=headers(101),
                json={"reaction": "❤️"},
            )
            assert channel_reaction.status_code == 200
            channel_history = (await client.get(
                f"/api/channels/{channel_id}/messages", headers=headers(101),
            )).json()["messages"]
            assert channel_history[0]["reaction_summary"] == "❤️:1"

            assert (await client.post(
                f"/api/messages/{dm_id}/reactions",
                json={"reaction": "👍"},
            )).status_code == 401

    run(scenario())


def test_poll_and_checklist_round_trip(env) -> None:
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            async def send(card):
                response = await client.post('/api/messages', headers=headers(101), json={'recipient_id': 102, 'card': card})
                assert response.status_code == 201, response.text
                return response.json()['id']

            async def history(viewer, other):
                response = await client.get(f'/api/messages?with={other}', headers=headers(viewer))
                assert response.status_code == 200, response.text
                return {row['id']: row['card'] for row in response.json()['messages']}

            items = [{'id': 1, 'text': 'A'}, {'id': 2, 'text': 'B'}]
            poll = await send({'kind': 'poll', 'title': 'Pick one', 'items': items})
            checklist = await send({'kind': 'checklist', 'title': 'Tasks', 'items': items, 'others_can_complete': True})
            private_list = await send({'kind': 'checklist', 'title': 'Owner only', 'items': items})
            assert (await history(102, 101))[poll]['items'] == items
            vote_url = f'/api/message-cards/dm/101/{poll}'
            assert (await client.post(vote_url, headers=headers(ADMIN), json={'votes': [1]})).status_code == 404
            assert (await client.post(vote_url, headers=headers(102), json={'votes': [1, 2]})).status_code == 400
            vote = await client.post(vote_url, headers=headers(102), json={'votes': [2]})
            assert vote.status_code == 200, vote.text
            assert (await history(102, 101))[poll]['votes']['2'] == {'count': 1, 'chosen': True}
            assert (await history(101, 102))[poll]['votes']['2'] == {'count': 1, 'chosen': False}
            assert (await client.post(vote_url, headers=headers(102), json={'votes': []})).status_code == 200
            assert not (await history(101, 102))[poll].get('votes')
            toggle_url = f'/api/message-cards/dm/101/{checklist}'
            assert (await client.post(toggle_url, headers=headers(102), json={'completed': [1]})).status_code == 200
            assert (await history(101, 102))[checklist]['done']['1']['user'] == 102
            assert (await client.post(toggle_url, headers=headers(102), json={'incompleted': [1]})).status_code == 200
            assert (await history(101, 102))[checklist]['done'] == {}
            assert (await client.post(f'/api/message-cards/dm/101/{private_list}', headers=headers(102), json={'completed': [1]})).status_code == 403

    run(scenario())


def test_message_cards_in_group_and_channel(env) -> None:
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            group = (await client.post('/api/groups', headers=headers(101), json={'title': 'Cards'})).json()['id']
            assert (await client.post(f'/api/groups/{group}/members', headers=headers(101), json={'telegram_id': 102})).status_code in (200, 201)
            channel = (await client.post('/api/channels', headers=headers(101), json={'title': 'Cards', 'username': 'card_news'})).json()['id']
            card = {'kind': 'poll', 'title': 'Pick', 'multiple_choice': True,
                    'items': [{'id': 0, 'text': 'A'}, {'id': 1, 'text': 'B'}]}
            for scope, peer in [('group', group), ('channel', channel)]:
                path = f'/api/{scope}s/{peer}/messages'
                sent = await client.post(path, headers=headers(101), json={'card': card})
                assert sent.status_code == 201, sent.text
                mid = sent.json()['id']
                update = f'/api/message-cards/{scope}/{peer}/{mid}'
                if scope == 'group':
                    assert (await client.post(update, headers=headers(ADMIN), json={'votes': [0]})).status_code == 403
                resp = await client.post(update, headers=headers(102), json={'votes': [0, 1]})
                assert resp.status_code == 200, resp.text
                rows = (await client.get(path, headers=headers(102))).json()['messages']
                assert rows[0]['card']['voters'] == 1
                assert rows[0]['card']['votes'] == {'0': {'chosen': True, 'count': 1}, '1': {'chosen': True, 'count': 1}}
                assert (await client.delete(f'{path}/{mid}', headers=headers(101))).status_code == 200
                assert (await client.post(update, headers=headers(102), json={'votes': [0]})).status_code == 404

    run(scenario())


def test_group_and_channel_settings_put(env) -> None:
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            group = await client.post('/api/groups', headers=headers(101), json={'title': 'Group'})
            channel = await client.post('/api/channels', headers=headers(101), json={'title': 'Channel', 'username': 'settings_news', 'description': 'Initial'})
            assert group.status_code == channel.status_code == 201
            gid, cid = group.json()['id'], channel.json()['id']
            group_path, channel_path = f'/api/groups/{gid}', f'/api/channels/{cid}'
            await client.post(f'{group_path}/members', headers=headers(101), json={'telegram_id': 102})
            assert (await client.get(channel_path, headers=headers(102))).json()['description'] == 'Initial'
            for path in (group_path, channel_path):
                data = {'title': 'Renamed', 'description': 'New description'}
                assert (await client.put(path, headers=headers(102), json=data)).status_code == 403
                assert (await client.put(path, headers=headers(101), json={'title': ' '})).status_code == 400
                response = await client.put(path, headers=headers(101), json=data)
                assert response.status_code == 200, response.text
                fetched = (await client.get(path, headers=headers(102))).json()
                assert all(fetched[key] == value for key, value in data.items())
            assert (await client.put(channel_path, headers=headers(101), json={'username': 'renamed_news'})).status_code == 200

    run(scenario())


def test_channel_membership_and_owner_invite_are_server_authoritative(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member, outsider = headers(101), headers(102), headers(ADMIN)
            channel = await client.post('/api/channels', headers=owner, json={'title': 'Private', 'is_public': False})
            assert channel.status_code == 201, channel.text
            cid = channel.json()['id']
            path = f'/api/channels/{cid}'
            assert (await client.put(path + '/membership', headers=member, json={'joined': True})).status_code == 403
            assert (await client.post(path + '/members', headers=outsider, json={'user_ids': [102]})).status_code == 403
            assert (await client.post(path + '/members', headers=owner, json={'user_ids': [102, 9999999]})).status_code == 404
            assert (await client.get(path, headers=member)).status_code == 403
            assert (await client.post(path + '/members', headers=owner, json={'user_ids': [102, 102]})).status_code == 201
            listed = (await client.get('/api/channels', headers=member)).json()['channels']
            assert next(c for c in listed if c['id'] == cid)['is_member'] == 1
            assert (await client.put(path + '/membership', headers=owner, json={'joined': False})).status_code == 409
            assert (await client.put(path + '/membership', headers=member, json={'joined': False})).status_code == 200
            assert (await client.get(path, headers=member)).status_code == 403

    run(scenario())


def test_profile_music_publish_reorder_and_remove(env):
    import sqlite3

    path, app = env
    a, b, voice = 'a' * 32, 'b' * 32, 'c' * 32


    async def scenario():
        async with await client_for(app) as client:
            assert (await client.get("/api/users/101/music", headers=headers(101))).status_code == 200
            with sqlite3.connect(path.with_name("waysgram_messenger.sqlite3")) as db:
                db.executemany('''INSERT INTO media_assets
                    (id,owner_telegram_id,storage_name,mime_type,size_bytes,created_at,media_kind)
                    VALUES(?,101,?,'audio/mpeg',100,1,?)''', [(a, a, 'audio'), (b, b, 'audio'), (voice, voice, 'voice')])
            async def save(media, author=101, **kwargs):
                return await client.put('/api/me/music', headers=headers(author), json={'media_id': media, **kwargs})

            async def tracks(query='', viewer=102):
                response = await client.get('/api/users/101/music' + query, headers=headers(viewer))
                assert response.status_code == 200
                return response.json()['count'], [t['media_id'] for t in response.json()['tracks']]

            assert (await save(a, 102)).status_code == 403
            assert (await save(voice)).status_code == 403
            assert (await save(a)).status_code == 200
            assert (await save(b, after_id='missing')).status_code == 400
            assert (await save(b)).status_code == 200
            assert await tracks('?offset=1&limit=1') == (2, [a])
            assert (await save(b, after_id=a)).status_code == 200
            assert await tracks() == (2, [a, b])
            assert (await client.delete('/api/media/storage/' + a, headers=headers(101))).status_code == 409
            assert (await client.post('/api/blocks', headers=headers(101), json={'user_id': 102})).status_code == 201
            assert await tracks() == (0, [])
            assert (await save(a, remove=True)).status_code == 200
            assert await tracks(viewer=101) == (1, [b])

    run(scenario())


def test_private_channel_invite_rotation_preserves_membership(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member, guest = headers(101), headers(102), headers(ADMIN)
            created = await client.post('/api/channels', headers=owner, json={'title': 'Invite rotation', 'is_public': False})
            assert created.status_code == 201, created.text
            data = created.json()
            path = f"/api/channels/{data['id']}/invite"
            old = data['invite_link']
            assert (await client.get(path, headers=owner)).json()['invite_link'] == old
            assert (await client.post('/api/channels/join', headers=member, json={'invite_link': old})).status_code == 200
            assert (await client.get(path, headers=member)).status_code == 403
            assert (await client.post(path, headers=member)).status_code == 403
            rotated = await client.post(path, headers=owner)
            assert rotated.status_code == 200
            new = rotated.json()['invite_link']
            assert new != old
            assert (await client.get(old)).status_code == 404
            assert (await client.post('/api/channels/join', headers=guest, json={'invite_link': old})).status_code == 404
            assert (await client.get(path.rsplit('/', 1)[0], headers=member)).status_code == 200
            assert (await client.post('/api/channels/join', headers=guest, json={'invite_link': new})).status_code == 200

    run(scenario())


def test_group_invite_list_edit_and_revoke(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            created = await client.post('/api/groups', headers=owner, json={'title': 'Invite management'})
            assert created.status_code == 201, created.text
            group = f"/api/groups/{created.json()['id']}"
            assert (await client.post(group + '/members', headers=owner, json={'telegram_id': 102})).status_code == 201
            path = group + '/invites'
            first = (await client.post(path, headers=owner, json={'label': 'First'})).json()
            second = (await client.post(path, headers=owner, json={'label': 'Second'})).json()
            assert (await client.get(path, headers=member)).status_code == 403
            page = (await client.get(path, headers=owner, params={'limit': 1})).json()
            assert page['count'] == 2 and len(page['invites']) == 1
            token = page['invites'][0]['invite_link'].rsplit('/', 1)[-1]
            next_page = (await client.get(path, headers=owner, params={'after': token})).json()
            assert {x['label'] for x in page['invites'] + next_page['invites']} == {'First', 'Second'}
            item = path + '/' + first['invite_link'].rsplit('/', 1)[-1]
            assert (await client.patch(item, headers=member, json={'label': 'No'})).status_code == 403
            edited = await client.patch(item, headers=owner, json={'label': 'Updated', 'max_uses': 5, 'expires_at': None, 'require_approval': True})
            assert edited.status_code == 200, edited.text
            assert edited.json()['label'] == 'Updated'
            preview = (await client.get(first['invite_link'])).json()
            assert preview['max_uses'] == 5 and preview['expires_at'] is None and preview['require_approval'] is True
            assert (await client.delete(item, headers=owner)).status_code == 200
            assert (await client.patch(item, headers=owner, json={'label': 'No'})).status_code == 404
            active = (await client.get(path, headers=owner)).json()['invites']
            assert [x['invite_link'] for x in active] == [second['invite_link']]
            revoked = (await client.get(path, headers=owner, params={'revoked': 'true'})).json()['invites']
            assert [x['label'] for x in revoked] == ['Updated']

    run(scenario())


def test_group_delete_owner_only_and_admin_cannot_remove_peer_admin(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, admin, peer = headers(101), headers(102), headers(ADMIN)
            created = await client.post('/api/groups', headers=owner, json={'title': 'Delete group'})
            assert created.status_code == 201, created.text
            path = f"/api/groups/{created.json()['id']}"
            for uid in (102, ADMIN):
                assert (await client.post(path + '/members', headers=owner, json={'telegram_id': uid})).status_code == 201
                assert (await client.post(path + '/admins', headers=owner, json={'telegram_id': uid})).status_code == 201
            assert (await client.delete(path + f'/members/{ADMIN}', headers=admin)).status_code == 403
            assert (await client.get(path, headers=peer)).status_code == 200
            assert (await client.delete(path, headers=admin)).status_code == 403
            invite = await client.post(path + '/invites', headers=owner, json={})
            assert invite.status_code == 201
            assert (await client.post(path + '/messages', headers=owner, json={'body': 'Before deletion'})).status_code == 201
            deleted = await client.delete(path, headers=owner)
            assert deleted.status_code == 200, deleted.text
            assert (await client.get(invite.json()['invite_link'])).status_code == 404
            assert (await client.get(path + '/messages', headers=peer)).status_code in (403, 404)
            assert (await client.delete(path, headers=owner)).status_code == 404

    run(scenario())
