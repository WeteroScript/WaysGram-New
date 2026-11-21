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
            assert (await client.put("/api/me/privacy/music", headers=headers(101), json={"mode": "nobody"})).status_code == 200
            assert await tracks() == (0, [])
            assert await tracks(viewer=101) == (2, [a, b])
            assert (await client.put("/api/me/privacy/music", headers=headers(101), json={"mode": "everyone"})).status_code == 200
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
            cleanup = group + '/revoked-invites'
            active_token = second['invite_link'].rsplit('/', 1)[-1]
            revoked_token = first['invite_link'].rsplit('/', 1)[-1]
            assert (await client.delete(cleanup, headers=member)).status_code == 403
            assert (await client.delete(cleanup, headers=owner, params={'invite_token': active_token})).status_code == 404
            assert (await client.delete(cleanup, headers=owner, params={'invite_token': revoked_token})).json()['deleted'] == 1
            assert (await client.delete(item.replace(revoked_token, active_token), headers=owner)).status_code == 200
            assert (await client.delete(cleanup, headers=owner, params={'admin_id': 101})).json()['deleted'] == 1
            assert (await client.get(path, headers=owner, params={'revoked': 'true'})).json()['count'] == 0

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


def test_channel_moderation_blocks_rejoin_and_hides_public_history(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            created = await client.post('/api/channels', headers=owner, json={'title': 'Moderated', 'username': 'moderated'})
            assert created.status_code == 201, created.text
            cid = created.json()['id']
            path = f'/api/channels/{cid}'
            ban = path + '/members/102/ban'
            assert (await client.put(path + '/membership', headers=member, json={'joined': True})).status_code == 200
            assert (await client.get(path + '/members', headers=member)).status_code == 403
            assert (await client.put(ban, headers=member, json={'banned': True})).status_code == 403
            assert (await client.get(path + '/members', headers=owner, params={'q': 'friend'})).json()['count'] == 1
            assert (await client.put(ban, headers=owner, json={'banned': True})).status_code == 200
            assert (await client.get(path + '/members', headers=owner, params={'banned': True})).json()['members'][0]['telegram_id'] == 102
            assert (await client.get(path + '/messages', headers=member)).status_code == 403
            assert cid not in [c['id'] for c in (await client.get('/api/channels', headers=member)).json()['channels']]
            assert (await client.put(path + '/membership', headers=member, json={'joined': True})).status_code == 403
            assert (await client.post(path + '/members', headers=owner, json={'user_ids': [102]})).status_code == 403
            assert (await client.post(path + '/admins', headers=owner, json={'telegram_id': 102})).status_code == 403
            assert (await client.put(ban, headers=owner, json={'banned': False})).status_code == 200
            assert (await client.get(path + '/members', headers=owner)).json()['count'] == 1
            assert (await client.put(path + '/membership', headers=member, json={'joined': True})).status_code == 200

    run(scenario())


def test_temporary_channel_ban_expires_without_restoring_subscription(env):
    import time
    import db_split

    path, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            created = await client.post('/api/channels', headers=owner, json={'title': 'Temporary', 'username': 'temporary'})
            assert created.status_code == 201, created.text
            cid = created.json()['id']
            channel = f'/api/channels/{cid}'
            ban = channel + '/members/102/ban'
            assert (await client.put(channel + '/membership', headers=member, json={'joined': True})).status_code == 200
            assert (await client.put(ban, headers=owner, json={'banned': True, 'until_date': 1})).status_code == 400
            until = int(time.time()) + 600
            response = await client.put(ban, headers=owner, json={'banned': True, 'until_date': until})
            assert response.status_code == 200, response.text
            blocked = (await client.get(channel + '/members', headers=owner, params={'banned': True})).json()
            assert blocked['members'][0]['until_date'] == until
            assert (await client.get(channel + '/messages', headers=member)).status_code == 403
            with db_split.sqlite_connect(path) as db:
                db.execute('UPDATE channel_bans SET until_date=1 WHERE channel_id=? AND telegram_id=102', (cid,))
            assert (await client.get(channel + '/members', headers=owner, params={'banned': True})).json()['count'] == 0
            assert (await client.get(channel + '/messages', headers=member)).status_code == 200
            listed = (await client.get('/api/channels', headers=member)).json()['channels']
            assert next(c for c in listed if c['id'] == cid)['is_member'] == 0
            assert (await client.put(channel + '/membership', headers=member, json={'joined': True})).status_code == 200

    run(scenario())


def test_channel_admin_rights_are_independent(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, admin, member = headers(101), headers(102), headers(ADMIN)
            created = await client.post('/api/channels', headers=owner, json={'title': 'Permissions', 'username': 'permissions'})
            assert created.status_code == 201, created.text
            path = f"/api/channels/{created.json()['id']}"

            async def grant(mask):
                response = await client.post(path + '/admins', headers=owner, json={'telegram_id': 102, 'rights': mask})
                assert response.status_code == 201, response.text

            await grant(1)
            post = await client.post(path + '/messages', headers=admin, json={'body': 'Post only'})
            assert post.status_code == 201, post.text
            message = path + f"/messages/{post.json()['id']}"
            assert (await client.patch(message, headers=admin, json={'body': 'Denied'})).status_code == 403
            assert (await client.delete(message, headers=admin)).status_code == 403
            assert (await client.post(path + '/members', headers=admin, json={'user_ids': [ADMIN]})).status_code == 403
            assert (await client.put(path + f'/members/{ADMIN}/ban', headers=admin, json={'banned': True})).status_code == 403

            await grant(2 | 16)
            assert (await client.get(path, headers=admin)).json()['admin_rights'] == 18
            assert (await client.post(path + '/messages', headers=admin, json={'body': 'Denied'})).status_code == 403
            assert (await client.patch(message, headers=admin, json={'body': 'Allowed'})).status_code == 200
            assert (await client.post(path + '/members', headers=admin, json={'user_ids': [ADMIN]})).status_code == 201
            assert (await client.get(path, headers=member)).status_code == 200
            assert (await client.post(path + '/admins', headers=admin, json={'telegram_id': ADMIN, 'rights': 63})).status_code == 403

            assert (await client.put(path, headers=admin, json={'title': 'Denied'})).status_code == 403
            await grant(64)
            changed = await client.put(path, headers=admin, json={'title': 'Updated', 'description': 'New description'})
            assert changed.status_code == 200, changed.text
            assert changed.json()['title'] == 'Updated' and changed.json()['description'] == 'New description'
            assert (await client.put(path, headers=admin, json={'username': 'another', 'title': 'Must not change'})).status_code == 403
            assert (await client.get(path, headers=owner)).json()['title'] == 'Updated'
            assert (await client.post(path + '/messages', headers=admin, json={'body': 'Still denied'})).status_code == 403

            await grant(4 | 32)
            assert (await client.delete(message, headers=admin)).status_code == 200
            assert (await client.put(path + '/members/101/ban', headers=admin, json={'banned': True})).status_code == 403
            assert (await client.put(path + f'/members/{ADMIN}/ban', headers=admin, json={'banned': True})).status_code == 200

    run(scenario())


def test_additional_channel_invites_limit_and_revoke_independently(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member, guest = headers(101), headers(102), headers(ADMIN)
            create = await client.post('/api/channels', headers=owner, json={'title': 'Private links', 'is_public': False})
            assert create.status_code == 201, create.text
            path = f"/api/channels/{create.json()['id']}/invites"
            limited = await client.post(path, headers=owner, json={'max_uses': 1, 'expires_in_seconds': 600, 'label': 'One use'})
            assert limited.status_code == 201, limited.text
            link = limited.json()['invite_link']
            assert (await client.get(path, headers=member)).status_code == 403
            assert (await client.get(link)).status_code == 200
            for _ in range(2):
                assert (await client.post('/api/channels/join', headers=member, json={'invite_link': link})).status_code == 200
            assert (await client.post('/api/channels/join', headers=guest, json={'invite_link': link})).status_code == 404
            active = (await client.get(path, headers=owner)).json()['invites']
            assert active[0]['uses'] == 1 and active[0]['label'] == 'One use'
            endpoint = path + '/' + link.rsplit('/', 1)[-1]
            assert (await client.patch(endpoint, headers=member, json={'label': 'Denied'})).status_code == 403
            edited = await client.patch(endpoint, headers=owner, json={'label': 'Updated', 'max_uses': 2, 'expires_at': None})
            assert edited.status_code == 200 and edited.json()['uses'] == 1
            assert edited.json()['label'] == 'Updated' and edited.json()['expires_at'] is None
            assert (await client.post('/api/channels/join', headers=guest, json={'invite_link': link})).status_code == 200
            cleanup = path.rsplit('/', 1)[0] + '/revoked-invites'
            params = {'invite_token': link.rsplit('/', 1)[-1]}
            assert (await client.delete(cleanup, headers=owner, params=params)).status_code == 404
            assert (await client.delete(endpoint, headers=member)).status_code == 403
            assert (await client.delete(endpoint, headers=owner)).status_code == 200
            assert (await client.get(path, headers=owner, params={'revoked': 'true'})).json()['count'] == 1
            assert (await client.patch(endpoint, headers=owner, json={'label': 'Denied'})).status_code == 404
            assert (await client.delete(cleanup, headers=member, params=params)).status_code == 403
            assert (await client.delete(cleanup, headers=owner, params=params)).json()['deleted'] == 1
            assert (await client.get(create.json()['invite_link'])).status_code == 200
            assert (await client.get(path.rsplit('/', 1)[0], headers=member)).status_code == 200

    run(scenario())


def test_private_channel_join_requests_require_owner_approval(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member, guest = headers(101), headers(102), headers(ADMIN)
            channel = await client.post('/api/channels', headers=owner, json={'title': 'Approval', 'is_public': False})
            assert channel.status_code == 201
            path = f"/api/channels/{channel.json()['id']}"
            invite = await client.post(path + '/invites', headers=owner, json={'require_approval': True, 'max_uses': 1})
            assert invite.status_code == 201, invite.text
            link = invite.json()['invite_link']
            assert (await client.get(link)).json()['require_approval'] is True
            for user in (member, guest, member):
                result = await client.post('/api/channels/join', headers=user, json={'invite_link': link})
                assert result.status_code == 200 and result.json()['join_requested'] is True
                assert (await client.get(path, headers=user)).status_code == 403
            requests = path + '/join-requests'
            assert (await client.get(requests, headers=member)).status_code == 403
            assert (await client.get(path, headers=owner)).json()['requests_pending'] == 2
            assert (await client.get(requests, headers=owner)).json()['count'] == 2
            assert (await client.post(requests + '/102/approve', headers=member)).status_code == 403
            assert (await client.post(requests + '/102/approve', headers=owner)).status_code == 200
            assert (await client.get(path, headers=member)).status_code == 200
            assert (await client.post(requests + f'/{ADMIN}/approve', headers=owner)).status_code == 410
            assert (await client.post(requests + f'/{ADMIN}/decline', headers=owner)).status_code == 200
            assert (await client.get(path, headers=guest)).status_code == 403
            assert (await client.get(path, headers=owner)).json()['requests_pending'] == 0

    run(scenario())


def test_group_admin_rights_are_independent(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, admin = headers(101), headers(102)
            group = await client.post('/api/groups', headers=owner, json={'title': 'Group rights'})
            assert group.status_code == 201
            path = f"/api/groups/{group.json()['id']}"
            assert (await client.post(path + '/members', headers=owner, json={'telegram_id': 102})).status_code == 201
            assert (await client.post(path + '/admins', headers=owner, json={'telegram_id': 102, 'rights': 2})).status_code == 201
            assert (await client.post(path + '/admins', headers=owner, json={'telegram_id': 102})).status_code == 201
            roster = (await client.get(path, headers=owner)).json()['members']
            assert next(member for member in roster if member['telegram_id'] == 102)['admin_rights'] == 2
            assert (await client.get(path, headers=admin)).json()['admin_rights'] == 2
            assert (await client.post(path + '/invites', headers=admin, json={})).status_code == 201
            assert (await client.put(path, headers=admin, json={'title': 'Denied'})).status_code == 403
            assert (await client.put(path + '/slow-mode', headers=admin, json={'seconds': 5})).status_code == 403
            assert (await client.post(path + '/admins', headers=owner, json={'telegram_id': 102, 'rights': 1})).status_code == 201
            assert (await client.put(path, headers=admin, json={'title': 'Allowed'})).status_code == 200
            assert (await client.post(path + '/invites', headers=admin, json={})).status_code == 403

    run(scenario())


def test_group_mute_blocks_sending_without_removing_read_access(env):
    import sqlite3
    import uuid

    db_path, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            group = await client.post('/api/groups', headers=owner, json={'title': 'Mute test'})
            path = f"/api/groups/{group.json()['id']}"
            assert (await client.post(path + '/members', headers=owner, json={'telegram_id': 102})).status_code == 201
            mute = path + '/members/102/mute'
            assert (await client.put(mute, headers=member, json={'until_date': 0})).status_code == 403
            assert (await client.put(mute, headers=owner, json={'until_date': 1})).status_code == 400
            assert (await client.put(mute, headers=owner, json={'until_date': 0})).status_code == 200
            assert (await client.get(path + '/messages', headers=member)).status_code == 200
            blocked = await client.post(path + '/messages', headers=member, json={'body': 'Blocked'})
            assert blocked.status_code == 403 and blocked.json()['detail'] == 'CHAT_WRITE_FORBIDDEN'
            assert (await client.put(mute, headers=owner, json={'until_date': None})).status_code == 200
            assert (await client.post(path + '/messages', headers=member, json={'body': 'Allowed'})).status_code == 201
            photo, voice = uuid.uuid4().hex, uuid.uuid4().hex
            with sqlite3.connect(db_path.with_name("waysgram_messenger.sqlite3")) as db:
                db.executemany("""INSERT INTO media_assets
                    (id,owner_telegram_id,storage_name,mime_type,size_bytes,created_at,media_kind)
                    VALUES(?,102,?,?,100,1,?)""", [(photo, photo, "image/png", ""), (voice, voice, "audio/ogg", "voice")])
            assert (await client.put(mute, headers=owner, json={'until_date': 0, 'rights': 2})).status_code == 200
            assert (await client.post(path + '/messages', headers=member, json={'body': 'Text still allowed'})).status_code == 201
            assert (await client.post(path + '/messages', headers=member, json={'media_id': photo})).status_code == 403
            assert (await client.post(path + '/messages', headers=member, json={'media_id': voice})).status_code == 201

            defaults = path + '/send-permissions'
            assert (await client.put(mute, headers=owner, json={'until_date': None})).status_code == 200
            assert (await client.put(defaults, headers=member, json={'default_send_restrictions': 2})).status_code == 403
            assert (await client.put(defaults, headers=owner, json={'default_send_restrictions': 2})).status_code == 200
            assert (await client.get(path, headers=member)).json()['default_send_restrictions'] == 2
            assert (await client.post(path + '/messages', headers=member, json={'media_id': photo})).status_code == 403
            assert (await client.post(path + '/messages', headers=member, json={'media_id': voice})).status_code == 201
            assert (await client.put(defaults, headers=owner, json={'default_send_restrictions': 1})).status_code == 200
            assert (await client.post(path + '/messages', headers=member, json={'body': 'Blocked'})).status_code == 403
            assert (await client.post(path + '/messages', headers=owner, json={'body': 'Admin bypass'})).status_code == 201
            assert (await client.put(defaults, headers=owner, json={'default_send_restrictions': 0})).status_code == 200
            assert (await client.post(path + '/messages', headers=member, json={'media_id': photo})).status_code == 201


    run(scenario())


def test_profile_channel_and_invite_privacy(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, other = headers(101), headers(102)
            channel = await client.post('/api/channels', headers=owner, json={'username': 'profilechannel', 'title': 'Profile channel'})
            assert channel.status_code == 201
            cid = channel.json()['id']
            route = '/api/me/profile-channel'
            assert (await client.put(route, headers=other, json={'channel_id': cid})).status_code == 403
            assert (await client.put(route, headers=owner, json={'channel_id': cid})).status_code == 200
            profile = await client.get('/api/users/101', headers=other)
            assert profile.json()['personal_channel']['id'] == cid
            assert (await client.put(route, headers=owner, json={'channel_id': 0})).status_code == 200
            assert (await client.get('/api/users/101', headers=other)).json()['personal_channel'] is None

            privacy = '/api/me/privacy/invites'
            assert (await client.put(privacy, headers=other, json={'mode': 'contacts'})).status_code == 200
            group = await client.post('/api/groups', headers=owner, json={'title': 'Invite privacy'})
            route = f"/api/groups/{group.json()['id']}/members"
            assert (await client.post(route, headers=owner, json={'telegram_id': 102})).json()['detail'] == 'USER_PRIVACY_RESTRICTED'
            assert (await client.put(privacy, headers=other, json={'mode': 'contacts', 'allow': [101]})).status_code == 200
            assert (await client.get(privacy, headers=other)).json()['allow'] == [101]
            assert (await client.post(route, headers=owner, json={'telegram_id': 102})).status_code == 201

    run(scenario())


def test_profile_phone_and_last_seen_visibility(env):
    import sqlite3

    db_path, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, viewer = headers(101), headers(102)
            with sqlite3.connect(db_path) as db:
                db.execute("UPDATE accounts SET virtual_number=? WHERE telegram_id=101", ('+48123456789',))
            await client.get('/api/me', headers=owner)

            async def profile():
                response = await client.get('/api/users/101', headers=viewer)
                assert response.status_code == 200
                return response.json()

            assert 'phone' not in await profile()
            assert 'last_seen_at' not in await profile()
            for setting in ('phone', 'last-seen'):
                response = await client.put('/api/me/privacy/' + setting, headers=owner, json={'mode': 'nobody', 'allow': [102]})
                assert response.status_code == 200
            visible = await profile()
            assert visible['phone'] == '+48123456789'
            assert visible['last_seen_at'] > 0
            assert (await client.put('/api/me/privacy/phone', headers=owner, json={'mode': 'everyone', 'deny': [102]})).status_code == 200
            assert 'phone' not in await profile()
            assert 'last_seen_at' in await profile()

    run(scenario())


def test_find_by_phone_is_separate_from_number_visibility(env):
    import sqlite3

    db_path, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, viewer = headers(101), headers(102)
            phone = '+48123456789'
            with sqlite3.connect(db_path) as db:
                db.execute('UPDATE accounts SET virtual_number=? WHERE telegram_id=101', (phone,))
            route = '/api/contacts/resolve-phone'
            settings = '/api/me/privacy/find-by-phone'
            assert (await client.get(settings, headers=owner)).json()['mode'] == 'contacts'
            hidden = await client.post(route, headers=viewer, json={'phone': phone})
            missing = await client.post(route, headers=viewer, json={'phone': '+48123456780'})
            assert hidden.status_code == missing.status_code == 404
            assert hidden.json() == missing.json()
            assert (await client.post('/api/contacts', headers=owner, json={'user_id': 102})).status_code == 201
            visible = await client.post(route, headers=viewer, json={'phone': phone.lstrip('+')})
            assert visible.status_code == 200 and visible.json()['id'] == 101
            assert 'phone' not in visible.json()
            assert 'phone' not in (await client.get('/api/users/101', headers=viewer)).json()
            assert (await client.put(settings, headers=owner, json={'mode': 'nobody'})).status_code == 200
            assert (await client.post(route, headers=viewer, json={'phone': phone})).status_code == 404

    run(scenario())


def test_avatar_privacy_hides_profile_search_and_direct_download(env):
    import sqlite3
    import uuid

    db_path, app = env
    media = uuid.uuid4().hex

    async def scenario():
        async with await client_for(app) as client:
            owner, viewer = headers(101), headers(102)
            storage = db_path.parent / 'waysgram_media'
            storage.mkdir(exist_ok=True)
            (storage / media).write_bytes(b'avatar')
            with sqlite3.connect(db_path.with_name('waysgram_messenger.sqlite3')) as db:
                db.execute('''INSERT INTO media_assets (id,owner_telegram_id,storage_name,mime_type,size_bytes,created_at)
                    VALUES(?,101,?,'image/png',6,1)''', (media, media))
            assert (await client.put('/api/me/avatar', headers=owner, json={'media_id': media})).status_code == 200
            media_path = '/api/media/' + media
            allowed = await client.get(media_path, headers=viewer)
            assert allowed.status_code == 200 and 'no-store' in allowed.headers['cache-control']
            setting = '/api/me/privacy/avatar'
            assert (await client.put(setting, headers=owner, json={'mode': 'nobody'})).status_code == 200
            assert (await client.get('/api/users/101', headers=viewer)).json()['avatar_media_id'] == ''
            assert (await client.get('/api/users?q=101', headers=viewer)).json()['users'][0]['avatar_media_id'] == ''
            assert (await client.get(media_path, headers={**viewer, 'Range': 'bytes=0-1'})).status_code == 404
            assert (await client.get(media_path, headers=owner)).status_code == 200
            assert (await client.put(setting, headers=owner, json={'mode': 'nobody', 'allow': [102]})).status_code == 200
            assert (await client.get(media_path, headers=viewer)).status_code == 200

    run(scenario())


def test_group_permanent_invite_is_reused_until_rotated(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, outsider = headers(101), headers(102)
            group = await client.post('/api/groups', headers=owner, json={'title': 'Permanent link'})
            path = f"/api/groups/{group.json()['id']}/permanent-invite"
            assert (await client.post(path, headers=outsider)).status_code in (403, 404)
            first = await client.post(path, headers=owner)
            assert first.status_code == 200, first.text
            invite = first.json()['invite_link']
            assert (await client.post(path, headers=owner)).json()['invite_link'] == invite
            assert (await client.get(invite)).status_code == 200
            second = await client.post(path + '?rotate=true', headers=owner)
            assert second.status_code == 200 and second.json()['invite_link'] != invite
            assert (await client.get(invite)).status_code in (404, 410)
            assert (await client.get(second.json()['invite_link'])).status_code == 200

    run(scenario())


def test_channel_creation_visibility_and_subscriber_lookup(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, subscriber = headers(101), headers(102)
            created = await client.post('/api/channels', headers=owner, json={'title': 'Private first', 'is_public': False})
            assert created.status_code == 201
            route = f"/api/channels/{created.json()['id']}"
            assert (await client.get(route + '/invite', headers=owner)).json()['invite_link']
            assert (await client.get(route, headers=subscriber)).status_code in (403, 404)
            assert (await client.put(route, headers=owner, json={'is_public': True, 'username': 'privatefirst'})).status_code == 200
            assert (await client.get(route + '/members/102', headers=subscriber)).status_code == 404
            assert (await client.put(route + '/membership', headers=subscriber, json={'joined': True})).status_code == 200
            assert (await client.get(route + '/members/102', headers=subscriber)).json()['role'] == 'member'
            assert (await client.get(route + '/members/102', headers=owner)).json()['telegram_id'] == 102
            assert (await client.get(route + '/members/101', headers=subscriber)).status_code == 403
            assert any(row['telegram_id'] == 102 for row in (await client.get(route + '/members', headers=owner)).json()['members'])
            assert (await client.put(route, headers=owner, json={'is_public': False})).json()['is_public'] is False

    run(scenario())


def test_channel_story_publish_expire_and_delete(env):
    import sqlite3
    import uuid

    db_path, app = env
    media = uuid.uuid4().hex

    async def scenario():
        async with await client_for(app) as client:
            owner, subscriber = headers(101), headers(102)
            channel = await client.post('/api/channels', headers=owner, json={'title': 'Stories', 'is_public': False})
            assert channel.status_code == 201
            base = f"/api/channels/{channel.json()['id']}"
            path = base + '/stories'
            with sqlite3.connect(db_path.with_name('waysgram_messenger.sqlite3')) as db:
                db.execute('''INSERT INTO media_assets (id,owner_telegram_id,storage_name,mime_type,size_bytes,created_at)
                    VALUES(?,101,?,'image/png',5,1)''', (media, media))
            storage = db_path.parent / 'waysgram_media'
            storage.mkdir(exist_ok=True)
            (storage / media).write_bytes(b'story')
            media_path = '/api/media/' + media
            body = {'media_id': media, 'random_id': 42, 'caption': 'Hello', 'period': 21600}
            assert (await client.get(path + '/can-post', headers=owner)).json()['count_remains'] == 20
            assert (await client.post(path, headers=subscriber, json=body)).status_code == 403
            created = await client.post(path, headers=owner, json=body)
            assert created.status_code == 201, created.text
            story = created.json()
            assert story['expires_at'] - story['created_at'] == 21600
            assert (await client.post(path, headers=owner, json=body)).json()['id'] == story['id']
            assert (await client.get(path, headers=subscriber)).status_code == 403
            assert (await client.get(media_path, headers=subscriber)).status_code == 404
            assert (await client.post(base + '/members', headers=owner, json={'user_ids': [102]})).status_code == 201
            assert (await client.get(path, headers=subscriber)).json()['stories'][0]['caption'] == 'Hello'
            assert (await client.get(media_path, headers=subscriber)).content == b'story'
            with sqlite3.connect(db_path) as db:
                db.execute('UPDATE channel_stories SET expires_at=1 WHERE id=?', (story['id'],))
            assert (await client.get(path, headers=subscriber)).json()['stories'] == []
            assert (await client.get(media_path, headers=subscriber)).status_code == 404
            assert (await client.get(path + '?mode=archive', headers=owner)).json()['stories'][0]['id'] == story['id']
            assert (await client.post(path + '/delete', headers=owner, json={'ids': [story['id']]})).json()['ids'] == [story['id']]
            assert (await client.get(path + '?mode=archive', headers=owner)).json()['stories'] == []

    run(scenario())


def test_profile_birthday_and_bio_privacy(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, other = headers(101), headers(102)
            assert (await client.put('/api/me', headers=owner, json={'bio': 'Private bio'})).status_code == 200
            endpoint = '/api/me/birthday'
            assert (await client.put(endpoint, headers=owner, json={'birthday': {'day': 30, 'month': 2}})).status_code == 400
            birthday = {'day': 29, 'month': 2, 'year': None}
            assert (await client.put(endpoint, headers=owner, json={'birthday': birthday})).json()['birthday'] == birthday
            assert (await client.get('/api/me', headers=owner)).json()['birthday'] == birthday
            assert 'birthday' not in (await client.get('/api/users/101', headers=other)).json()
            assert (await client.put('/api/me/privacy/birthday', headers=owner, json={'mode': 'nobody', 'allow': [102]})).status_code == 200
            assert (await client.put('/api/me/privacy/bio', headers=owner, json={'mode': 'nobody'})).status_code == 200
            profile = (await client.get('/api/users/101', headers=other)).json()
            assert profile['birthday'] == birthday and profile['bio'] == ''
            contact = await client.post('/api/contacts', headers=other, json={'user_id': 101})
            assert contact.json()['contact']['bio'] == ''
            assert (await client.put(endpoint, headers=owner, json={'birthday': None})).json()['birthday'] is None

    run(scenario())


def test_voice_privacy_rejects_direct_and_forwarded_voice_but_not_text(env):
    import sqlite3
    import uuid

    db_path, app = env
    media = uuid.uuid4().hex

    async def scenario():
        async with await client_for(app) as client:
            sender, recipient = headers(101), headers(102)
            with sqlite3.connect(db_path.with_name('waysgram_messenger.sqlite3')) as db:
                db.execute('''INSERT INTO media_assets (id,owner_telegram_id,storage_name,mime_type,size_bytes,created_at,media_kind)
                    VALUES(?,101,?,'audio/ogg',10,1,'voice')''', (media, media))
            original = await client.post('/api/messages', headers=sender, json={'recipient_id': 102, 'media_id': media})
            assert original.status_code == 201
            mid = original.json()['id']
            privacy = '/api/me/privacy/voice'
            assert (await client.put(privacy, headers=recipient, json={'mode': 'nobody'})).status_code == 200
            direct = await client.post('/api/messages', headers=sender, json={'recipient_id': 102, 'media_id': media})
            assert direct.status_code == 403 and direct.json()['detail'] == 'VOICE_MESSAGES_FORBIDDEN'
            legacy = await client.post(f'/api/messages/{mid}/forward', headers=sender, json={'recipient_id': 102})
            assert legacy.status_code == 403 and legacy.json()['detail'] == 'VOICE_MESSAGES_FORBIDDEN'
            batch = await client.post('/api/forward', headers=sender, json={'source_scope': 'dm', 'target_scope': 'dm', 'source_peer_id': 102, 'target_peer_id': 102, 'message_ids': [mid]})
            assert batch.status_code == 403 and batch.json()['detail'] == 'VOICE_MESSAGES_FORBIDDEN'
            assert (await client.post('/api/messages', headers=sender, json={'recipient_id': 102, 'body': 'Text works'})).status_code == 201
            assert (await client.put(privacy, headers=recipient, json={'mode': 'nobody', 'allow': [101]})).status_code == 200
            assert (await client.post('/api/messages', headers=sender, json={'recipient_id': 102, 'media_id': media})).status_code == 201

    run(scenario())


def test_forward_privacy_controls_profile_link_for_each_viewer(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            sender, forwarder, viewer = headers(101), headers(102), headers(ADMIN)
            original = await client.post('/api/messages', headers=sender, json={'recipient_id': 102, 'body': 'Forward me'})
            assert original.status_code == 201
            mid = original.json()['id']
            for endpoint, body in (
                ('/api/forward', {'source_scope': 'dm', 'target_scope': 'dm', 'source_peer_id': 101, 'target_peer_id': ADMIN, 'message_ids': [mid]}),
                (f'/api/messages/{mid}/forward', {'recipient_id': ADMIN}),
            ):
                assert (await client.post(endpoint, headers=forwarder, json=body)).status_code == 201

            async def history():
                return (await client.get('/api/messages?with=102', headers=viewer)).json()['messages']

            assert len(await history()) == 2
            assert all(row['forwarded_from_telegram_id'] == 101 for row in await history())
            settings = '/api/me/privacy/forwards'
            assert (await client.put(settings, headers=sender, json={'mode': 'nobody'})).status_code == 200
            assert all(row['forwarded_from_telegram_id'] is None and row['forwarded_from_message_id'] is None
                       and row['forwarded_from_display_name'] for row in await history())
            assert (await client.put(settings, headers=sender, json={'mode': 'nobody', 'allow': [ADMIN]})).status_code == 200
            assert all(row['forwarded_from_telegram_id'] == 101 for row in await history())

    run(scenario())


def test_blocked_users_list_and_unblock_restore_dm_access(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, peer = headers(101), headers(102)
            assert (await client.post('/api/blocks', headers=owner, json={'user_id': 102})).status_code == 201
            rows = (await client.get('/api/blocks', headers=owner)).json()
            assert rows['count'] == 1 and rows['blocked_users'][0]['id'] == 102
            assert (await client.get('/api/blocks', headers=peer)).json()['count'] == 0
            assert (await client.post('/api/messages', headers=peer, json={'recipient_id': 101, 'body': 'Blocked'})).status_code == 403
            assert (await client.delete('/api/blocks/102', headers=owner)).status_code == 200
            assert (await client.get('/api/blocks', headers=owner)).json()['count'] == 0
            assert (await client.post('/api/messages', headers=peer, json={'recipient_id': 101, 'body': 'Allowed'})).status_code == 201

    run(scenario())


def test_dm_auto_delete_applies_to_new_messages_and_syncs_both_participants(env):
    import db_split

    path, app = env

    async def scenario():
        async with await client_for(app) as client:
            async def send(sender, recipient, body):
                response = await client.post('/api/messages', headers=headers(sender), json={'recipient_id': recipient, 'body': body})
                assert response.status_code == 201
                return response.json()['id']

            old = await send(101, 102, 'old')
            route = '/api/chats/102/auto-delete'
            assert (await client.put(route, headers=headers(101), json={'seconds': True})).status_code == 400
            assert (await client.put(route, headers=headers(101), json={'seconds': 86400})).status_code == 200
            assert (await client.get('/api/chats/101/auto-delete', headers=headers(102))).json() == {'seconds': 86400}
            assert (await client.get('/api/users/101', headers=headers(102))).json()['dm_auto_delete_seconds'] == 86400
            assert (await client.get('/api/users/101', headers=headers(ADMIN))).json()['dm_auto_delete_seconds'] == 0
            due = await send(102, 101, 'expires')
            assert (await client.put(route, headers=headers(101), json={'seconds': 0})).status_code == 200
            new = await send(101, 102, 'not expiring')
            with db_split.sqlite_connect(path) as db:
                rows = dict(db.execute('SELECT id,auto_delete_at-created_at FROM messages WHERE id IN (?,?,?)', (old, due, new)))
                assert rows == {old: None, due: 86400, new: None}
                db.execute('UPDATE messages SET auto_delete_at=1 WHERE id=?', (due,))
                db.commit()
            cursor = (await client.get('/api/events', headers=headers(101))).json()['latest_id']
            for start in app.router.on_startup:
                await start()
            try:
                for _ in range(50):
                    events = (await client.get(f'/api/events?after_id={cursor}', headers=headers(101))).json()['events']
                    if any(e['kind'] == 'delete' and e['message_id'] == due for e in events):
                        break
                    await asyncio.sleep(.05)
                else:
                    raise AssertionError('expiry worker did not publish deletion')
                events = (await client.get(f'/api/events?after_id={cursor}', headers=headers(102))).json()['events']
                assert any(e['kind'] == 'delete' and e['message_id'] == due for e in events)
                listed = (await client.get('/api/messages?with=102', headers=headers(101))).json()['messages']
                assert {row['id'] for row in listed} & {old, due, new} == {old, new}
            finally:
                for stop in app.router.on_shutdown:
                    await stop()

    run(scenario())


def test_default_auto_delete_is_inherited_once_by_new_private_chats(env):
    import db_split

    path, app = env
    pair = sorted((102, ADMIN))
    with db_split.sqlite_connect(path) as db:
        db.execute('DELETE FROM messages WHERE MIN(sender_telegram_id,recipient_telegram_id)=? AND MAX(sender_telegram_id,recipient_telegram_id)=?', pair)
        db.execute('DELETE FROM dm_auto_delete_seen WHERE user_low=? AND user_high=?', pair)
        db.commit()

    async def scenario():
        async with await client_for(app) as client:
            async def send(sender, recipient):
                response = await client.post('/api/messages', headers=headers(sender), json={'recipient_id': recipient, 'body': 'TTL check'})
                assert response.status_code == 201
                return response.json()['id']

            route = '/api/me/auto-delete'
            assert (await client.get(route, headers=headers(102))).json() == {'seconds': 0}
            existing = await send(102, 101)
            assert (await client.put(route, headers=headers(102), json={'seconds': 2678400})).status_code == 400
            assert (await client.put(route, headers=headers(102), json={'seconds': 2592000})).status_code == 200
            assert (await client.get(route, headers=headers(102))).json() == {'seconds': 2592000}
            assert (await client.get(route, headers=headers(ADMIN))).json() == {'seconds': 0}
            still_existing = await send(102, 101)
            first = await send(102, ADMIN)
            timer = f'/api/chats/{ADMIN}/auto-delete'
            assert (await client.get(timer, headers=headers(102))).json() == {'seconds': 2592000}
            assert (await client.put(route, headers=headers(102), json={'seconds': 604800})).status_code == 200
            second = await send(ADMIN, 102)
            assert (await client.put('/api/chats/102/auto-delete', headers=headers(ADMIN), json={'seconds': 0})).status_code == 200
            after_off = await send(102, ADMIN)
            with db_split.sqlite_connect(path) as db:
                rows = dict(db.execute('SELECT id,auto_delete_at-created_at FROM messages WHERE id IN (?,?,?,?,?)', (existing, still_existing, first, second, after_off)))
            assert rows == {existing: None, still_existing: None, first: 2592000, second: 2592000, after_off: None}

    run(scenario())


def test_call_privacy_preferences_are_independent_and_account_scoped(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, other = headers(101), headers(102)
            calls = '/api/me/privacy/calls'
            p2p = '/api/me/privacy/calls-p2p'
            assert (await client.get(calls, headers=owner)).json()['mode'] == 'everyone'
            assert (await client.get(p2p, headers=owner)).json()['mode'] == 'nobody'
            rules = {'mode': 'contacts', 'allow': [102], 'deny': [ADMIN]}
            assert (await client.put(calls, headers=owner, json=rules)).json() == rules
            assert (await client.get(calls, headers=owner)).json() == rules
            assert (await client.get(calls, headers=other)).json()['mode'] == 'everyone'
            assert (await client.put(p2p, headers=owner, json={'mode': 'contacts'})).status_code == 200
            assert (await client.get(p2p, headers=owner)).json() == {'mode': 'contacts', 'allow': [], 'deny': []}
            assert (await client.get(p2p, headers=other)).json()['mode'] == 'nobody'
            assert (await client.put(p2p, headers=owner, json={'mode': 'invalid'})).status_code == 400
            assert (await client.get(calls)).status_code == 401

    run(scenario())


def test_paid_dm_requires_current_quote_and_charges_once(env):
    import db_split
    path, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, sender = headers(102), headers(101)
            route = '/api/me/message-fee'
            assert (await client.get(route, headers=owner)).json()['stars'] == 0
            assert (await client.put(route, headers=owner, json={'stars': 3})).status_code == 200
            quote = '/api/chats/102/message-fee'
            assert (await client.get(quote, headers=sender)).json() == {'stars': 3}
            body = {'recipient_id': 102, 'body': 'paid hello'}
            denied = await client.post('/api/messages', headers=sender, json=body)
            assert denied.status_code == 402 and denied.json()['detail']['stars'] == 3
            assert (await client.post('/api/messages', headers=sender, json={**body, 'approved_fee_stars': 2})).status_code == 402
            body['approved_fee_stars'] = 3
            assert (await client.post('/api/messages', headers=sender, json=body)).status_code == 400
            with db_split.sqlite_connect(path) as db:
                initial = dict(db.execute('SELECT telegram_id,stars FROM accounts WHERE telegram_id IN (101,102)'))
            keyed = {**sender, 'Idempotency-Key': 'message-fee-test-0001'}
            sent = await client.post('/api/messages', headers=keyed, json=body)
            assert sent.status_code == 201
            replay = await client.post('/api/messages', headers=keyed, json=body)
            assert replay.json()['id'] == sent.json()['id']
            with db_split.sqlite_connect(path) as db:
                balances = dict(db.execute('SELECT telegram_id,stars FROM accounts WHERE telegram_id IN (101,102)'))
                assert balances == {101: initial[101] - 3, 102: initial[102] + 3}
                assert db.execute('SELECT COUNT(*) FROM economy_transactions WHERE reason=?', (f"message_fee:{sent.json()['id']}",)).fetchone()[0] == 2
                db.execute('UPDATE accounts SET stars=0 WHERE telegram_id=101')
                db.commit()
            poor = await client.post('/api/messages', headers={**sender, 'Idempotency-Key': 'message-fee-test-0002'}, json={**body, 'body': 'unfunded'})
            assert poor.status_code == 409
            listed = (await client.get('/api/messages?with=102', headers=sender)).json()['messages']
            assert all(item['body'] != 'unfunded' for item in listed)
            assert (await client.put(route, headers=owner, json={'stars': 3, 'exempt_users': [101]})).status_code == 200
            assert (await client.get(quote, headers=sender)).json() == {'stars': 0}
            retry_key = {**sender, 'Idempotency-Key': 'message-fee-test-0003'}
            changed_body = {**body, 'body': 'now free'}
            changed = await client.post('/api/messages', headers=retry_key, json=changed_body)
            assert changed.status_code == 402 and changed.json()['detail']['stars'] == 0
            free = await client.post('/api/messages', headers=retry_key, json={**changed_body, 'approved_fee_stars': 0})
            assert free.status_code == 201
            assert (await client.post('/api/messages', headers=keyed, json=body)).json()['id'] == sent.json()['id']

    run(scenario())


def test_group_create_returns_group_with_initial_members_and_privacy_skips(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            assert (await client.put('/api/me/privacy/invites', headers=headers(ADMIN), json={'mode': 'nobody'})).status_code == 200
            created = await client.post('/api/groups', headers=headers(101), json={
                'title': 'WaysGram chat', 'member_ids': [101, 102, 102, ADMIN],
            })
            assert created.status_code == 201
            group = created.json()
            assert group['my_role'] == 'owner' and group['member_count'] == 2
            assert group['skipped_member_ids'] == [ADMIN]
            result = await client.get(f"/api/groups/{group['id']}", headers=headers(102))
            assert result.status_code == 200
            assert (await client.post('/api/groups', headers=headers(101), json={'title': 'Invalid', 'member_ids': [True]})).status_code == 400

    run(scenario())


def test_live_dm_event_wakes_waiting_recipient_and_exact_fetch_includes_peer(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            recipient = headers(102)
            cursor = (await client.get('/api/events', headers=recipient)).json()['latest_id']
            pending = asyncio.create_task(client.get(f'/api/events?after_id={cursor}&wait_seconds=5', headers=recipient))
            await asyncio.sleep(.06)
            assert not pending.done()
            sent = await client.post('/api/messages', headers=headers(101), json={'recipient_id': 102, 'body': 'Live DM'})
            assert sent.status_code == 201
            response = await asyncio.wait_for(pending, timeout=1.0)
            event = next(e for e in response.json()['events'] if e['kind'] == 'new_message')
            assert event['scope'] == 'dm' and event['message_id'] == sent.json()['id']
            exact = (await client.get(f"/api/messages?with=101&message_ids={event['message_id']}&include_peer=true", headers=recipient)).json()
            assert exact['peer']['id'] == 101
            assert [message['body'] for message in exact['messages']] == ['Live DM']

    run(scenario())


def test_channel_paid_reactions_are_opt_in_atomic_and_idempotent(env):
    import aiosqlite

    path, app = env

    async def scenario():
        async with aiosqlite.connect(path) as db:
            await db.execute('UPDATE accounts SET stars=20 WHERE telegram_id=102')
            await db.commit()
        async with await client_for(app) as client:
            owner, reader = headers(101), headers(102)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'News', 'username': 'paid_news', 'is_public': True})
            base = f"/api/channels/{channel.json()['id']}"
            post = await client.post(base + '/messages', headers=owner, json={'body': 'News'})
            url = base + f"/messages/{post.json()['id']}/paid-reactions"
            key = {**reader, 'Idempotency-Key': 'paid-reaction-0001'}
            assert (await client.post(url, headers=key, json={'stars': 5})).status_code == 403
            assert (await client.put(base + '/paid-reactions', headers=reader, json={'enabled': True})).status_code == 404
            assert (await client.put(base + '/paid-reactions', headers=owner, json={'enabled': True})).status_code == 200
            results = await asyncio.gather(*(client.post(url, headers=key, json={'stars': 5}) for _ in range(2)))
            assert [r.json() for r in results] == [{'stars': 5, 'balance_after': 15}] * 2
            assert (await client.post(url, headers=key, json={'stars': 6})).status_code == 409
            assert (await client.post(url, headers={**reader, 'Idempotency-Key': 'paid-reaction-0002'}, json={'stars': 16})).status_code == 409
            assert (await client.get(url, headers=owner)).json() == {'total_stars': 5, 'my_stars': 0}
            assert (await client.get(url, headers=reader)).json() == {'total_stars': 5, 'my_stars': 5}
            assert (await client.get(base + '/analytics', headers=reader)).status_code == 404
            assert (await client.get(base + '/analytics?days=999', headers=owner)).status_code == 422
            analytics = (await client.get(base + '/analytics?days=7', headers=owner)).json()
            assert (analytics['days'], analytics['published_posts_remaining']) == (7, 1)
            assert (analytics['paid_reaction_purchases'], analytics['paid_reaction_stars']) == (1, 5)
            assert (await client.delete(url.removesuffix('/paid-reactions'), headers=owner)).status_code == 200
            analytics = (await client.get(base + '/analytics?days=7', headers=owner)).json()
            assert (analytics['published_posts_remaining'], analytics['paid_reaction_stars']) == (0, 5)
        async with aiosqlite.connect(path) as db:
            cur = await db.execute("SELECT telegram_id,amount FROM economy_transactions WHERE reason LIKE 'channel_paid_reaction:%' ORDER BY telegram_id")
            assert await cur.fetchall() == [(101, 5), (102, -5)]

    run(scenario())


def test_group_hidden_roster_preserves_counts_and_admin_access(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member, other = headers(101), headers(102), headers(ADMIN)
            created = await client.post('/api/groups', headers=owner, json={'title': 'Private roster', 'member_ids': [102, ADMIN]})
            assert created.status_code == 201 and created.json()['member_count'] == 3
            base = f"/api/groups/{created.json()['id']}"
            setting = base + '/member-visibility'
            async def roster(who):
                result = (await client.get(base, headers=who)).json()
                assert result['member_count'] == 3
                return {m['telegram_id'] for m in result['members']}

            everyone = {101, 102, ADMIN}
            assert await roster(member) == everyone
            assert (await client.put(setting, headers=member, json={'members_hidden': True})).status_code == 404
            assert (await client.put(setting, headers=owner, json={'members_hidden': 'true'})).status_code == 400
            assert (await client.put(setting, headers=owner, json={'members_hidden': True})).status_code == 200
            assert await roster(member) == {101, 102}
            assert await roster(other) == {101, ADMIN}
            assert await roster(owner) == everyone
            assert (await client.post(base + '/admins', headers=owner, json={'telegram_id': ADMIN})).status_code in (200, 201)
            assert await roster(other) == everyone
            assert (await client.put(setting, headers=owner, json={'members_hidden': False})).status_code == 200
            assert await roster(member) == everyone

    run(scenario())


def test_group_analytics_excludes_old_and_deleted_messages_and_requires_admin(env):
    import aiosqlite
    import time

    path, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member, outsider = headers(101), headers(102), headers(ADMIN)
            created = await client.post('/api/groups', headers=owner, json={'title': 'Analytics group', 'member_ids': [102]})
            base = f"/api/groups/{created.json()['id']}"
            old = await client.post(base + '/messages', headers=owner, json={'body': 'Old'})
            current = await client.post(base + '/messages', headers=member, json={'body': 'Current'})
            assert old.status_code == current.status_code == 201
            async with aiosqlite.connect(path) as db:
                await db.execute('UPDATE group_messages SET created_at=? WHERE id=?', (int(time.time()) - 10 * 86400, old.json()['id']))
                await db.commit()
            url = base + '/analytics?days=7'
            assert (await client.get(url, headers=member)).status_code == 403
            assert (await client.get(url, headers=outsider)).status_code == 403
            stats = (await client.get(url, headers=owner)).json()
            assert (stats['members'], stats['messages_remaining'], stats['unique_authors']) == (2, 1, 1)
            assert (await client.get(base + '/analytics?days=30', headers=owner)).json()['unique_authors'] == 2
            assert (await client.post(base + '/admins', headers=owner, json={'telegram_id': 102})).status_code in (200, 201)
            assert (await client.get(url, headers=member)).status_code == 200
            assert (await client.delete(base + f"/messages/{current.json()['id']}", headers=member)).status_code == 200
            assert (await client.get(url, headers=owner)).json()['messages_remaining'] == 0

    run(scenario())


def test_channel_paid_media_requires_purchase_and_charges_once(env):
    import aiosqlite
    import sqlite3
    import uuid

    path, app = env
    media = uuid.uuid4().hex
    storage = path.parent / 'waysgram_media'
    storage.mkdir(exist_ok=True)
    (storage / media).write_bytes(b'photo')
    with sqlite3.connect(path.with_name('waysgram_messenger.sqlite3')) as db:
        db.execute("""INSERT INTO media_assets(id,owner_telegram_id,storage_name,mime_type,size_bytes,created_at)
                       VALUES(?,101,?,'image/png',5,1)""", (media, media))
    with sqlite3.connect(path) as db:
        db.execute('UPDATE accounts SET stars=10 WHERE telegram_id=102')

    async def scenario():
        async with await client_for(app) as client:
            owner, buyer, other = headers(101), headers(102), headers(ADMIN)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Paid photos', 'username': 'paid_photos', 'is_public': True})
            base = f"/api/channels/{channel.json()['id']}"
            post = await client.post(base + '/messages', headers=owner, json={'body': 'Paid photo'})
            post_url = base + f"/messages/{post.json()['id']}"
            url = post_url + '/paid-media'
            file_url = '/api/media/' + media
            assert (await client.put(url, headers=owner, json={'price_stars': 5, 'media_id': media})).status_code == 200
            assert (await client.get(url, headers=buyer)).json() == {'price_stars': 5, 'unlocked': False}
            assert (await client.get(file_url, headers=buyer)).status_code == 404
            assert (await client.post(base + '/messages', headers=owner, json={'media_id': media})).status_code == 404
            assert (await client.post(url, headers=buyer, json={'approved_price_stars': 4})).status_code == 402
            payments = await asyncio.gather(*(client.post(url, headers=buyer, json={'approved_price_stars': 5}) for _ in range(2)))
            assert all(p.status_code == 200 and p.json()['media_id'] == media for p in payments)
            result = await client.get(file_url, headers=buyer)
            assert result.content == b'photo' and 'no-store' in result.headers['cache-control']
            assert (await client.get(file_url, headers=other)).status_code == 404
            assert (await client.delete('/api/media/storage/' + media, headers=owner)).status_code == 409
            stats = (await client.get(base + '/analytics', headers=owner)).json()
            assert stats['paid_media_stars'] == stats['total_revenue_stars'] == 5
            assert (await client.delete(post_url, headers=owner)).status_code == 200
            assert (await client.get(file_url, headers=buyer)).status_code == 404
        async with aiosqlite.connect(path) as db:
            cur = await db.execute("SELECT telegram_id,amount FROM economy_transactions WHERE reason LIKE 'channel_paid_media:%' ORDER BY telegram_id")
            assert await cur.fetchall() == [(101, 5), (102, -5)]

    run(scenario())


def test_paid_channel_subscription_expires_without_converting_to_free_membership(env):
    import aiosqlite

    path, app = env

    async def scenario():
        async with aiosqlite.connect(path) as db:
            await db.execute('UPDATE accounts SET stars=20 WHERE telegram_id=102')
            await db.commit()
        async with await client_for(app) as client:
            owner, buyer = headers(101), headers(102)
            channel = await client.post('/api/channels', headers=owner, json={'title': 'Subscriber news', 'is_public': False})
            cid = channel.json()['id']
            base = f'/api/channels/{cid}'
            assert (await client.post(base + '/messages', headers=owner, json={'body': 'Private news'})).status_code == 201
            offer = await client.put(base + '/subscription-offer', headers=owner, json={'price_stars': 6})
            assert offer.status_code == 200
            token = offer.json()['token']
            assert (await client.get(base + '/subscription-offer', headers=owner)).json() == offer.json()
            assert (await client.get(base + '/subscription-offer', headers=buyer)).status_code == 404
            url = '/api/channel-subscriptions/' + token
            assert (await client.get(url, headers=buyer)).json()['already_has_access'] is False
            pay = {**buyer, 'Idempotency-Key': 'subscription-0001'}
            assert (await client.get(base + '/messages', headers=buyer)).status_code == 403
            assert (await client.post(url, headers=pay, json={'approved_price_stars': 5})).status_code == 402
            responses = await asyncio.gather(*(client.post(url, headers=pay, json={'approved_price_stars': 6}) for _ in range(2)))
            assert responses[0].status_code == responses[1].status_code == 200
            assert responses[0].json() == responses[1].json()
            assert (await client.get(base + '/messages', headers=buyer)).json()['messages'][0]['body'] == 'Private news'
            assert (await client.put(base + '/membership', headers=buyer, json={'joined': True})).status_code == 200
            quote = (await client.get(url, headers=buyer)).json()
            assert quote['already_has_access'] is True
            assert quote['purchased_until'] == responses[0].json()['purchased_until']
            async with aiosqlite.connect(path) as db:
                await db.execute('UPDATE channel_subscriptions SET expires_at=0 WHERE channel_id=? AND telegram_id=102', (cid,))
                await db.commit()
            assert (await client.get(base + '/messages', headers=buyer)).status_code == 403
            assert not (await client.get('/api/channels', headers=buyer)).json()['channels']
            assert (await client.get(url, headers=buyer)).json()['already_has_access'] is False
            assert (await client.post(url, headers=pay, json={'approved_price_stars': 6})).json() == responses[0].json()
            assert (await client.get(base + '/messages', headers=buyer)).status_code == 403
            renewal = await client.post(url, headers={**buyer, 'Idempotency-Key': 'subscription-0002'}, json={'approved_price_stars': 6})
            assert renewal.status_code == 200
            assert (await client.get(base + '/messages', headers=buyer)).status_code == 200
            stats = (await client.get(base + '/analytics', headers=owner)).json()
            assert stats['subscription_stars'] == stats['total_revenue_stars'] == 12
        async with aiosqlite.connect(path) as db:
            cur = await db.execute("SELECT telegram_id,SUM(amount),COUNT(*) FROM economy_transactions WHERE reason=? GROUP BY telegram_id ORDER BY telegram_id", (f'channel_subscription:{cid}',))
            assert await cur.fetchall() == [(101, 12, 2), (102, -12, 2)]

    run(scenario())


def test_channel_discussion_comments_use_group_permissions_and_moderation(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member, outsider = headers(101), headers(102), headers(ADMIN)
            channel = await client.post('/api/channels', headers=owner, json={'title': 'Discuss news', 'is_public': False})
            cid = channel.json()['id']
            base = f'/api/channels/{cid}'
            post = await client.post(base + '/messages', headers=owner, json={'body': 'News'})
            url = base + f"/messages/{post.json()['id']}/comments"
            group = await client.post('/api/groups', headers=owner, json={'title': 'News discussion', 'member_ids': [102]})
            gid = group.json()['id']
            group_api = f'/api/groups/{gid}'
            link = base + '/discussion-group'
            assert (await client.get(url, headers=owner)).status_code == 404
            assert (await client.put(link, headers=member, json={'group_id': gid})).status_code == 404
            assert (await client.put(link, headers=owner, json={'group_id': gid})).json() == {'group_id': gid}
            assert (await client.get(link, headers=owner)).json()['title'] == 'News discussion'
            assert (await client.get(url, headers=member)).status_code == 404  # group membership alone does not grant post access
            assert (await client.post(base + '/members', headers=owner, json={'user_ids': [102, ADMIN]})).status_code == 201
            assert (await client.get(url, headers=outsider)).status_code == 403  # channel membership alone is not enough
            key = {**member, 'Idempotency-Key': 'channel-comment-0001'}
            first = await client.post(url, headers=key, json={'body': 'Comment'})
            assert first.status_code == 201
            again = await client.post(url, headers=key, json={'body': 'Comment'})
            assert again.json()['id'] == first.json()['id']
            comments = (await client.get(url, headers=owner)).json()['comments']
            assert [row['body'] for row in comments] == ['Comment']
            assert [row['id'] for row in (await client.get(group_api + '/messages', headers=owner)).json()['messages']] == [first.json()['id']]
            second_post = await client.post(base + '/messages', headers=owner, json={'body': 'Different post'})
            second_url = base + f"/messages/{second_post.json()['id']}/comments"
            assert (await client.post(second_url, headers=member, json={'body': 'Cross-thread reply', 'reply_to_id': first.json()['id']})).status_code == 404
            ordinary = await client.post(group_api + '/messages', headers=member, json={'body': 'Not a comment'})
            assert (await client.post(url, headers=member, json={'body': 'Ordinary reply', 'reply_to_id': ordinary.json()['id']})).status_code == 404
            reply = await client.post(url, headers=member, json={'body': 'Reply', 'reply_to_id': first.json()['id']})
            assert reply.status_code == 201
            last = (await client.get(url, headers=owner)).json()['comments'][-1]
            assert (last['reply_to_id'], last['reply_to_body']) == (first.json()['id'], 'Comment')

            assert (await client.put(group_api + '/send-permissions', headers=owner, json={'default_send_restrictions': 1})).status_code == 200
            assert (await client.post(url, headers=member, json={'body': 'Blocked'})).status_code == 403
            assert (await client.delete(group_api + f"/messages/{first.json()['id']}", headers=owner)).status_code == 200
            remaining = (await client.get(url, headers=owner)).json()['comments']
            assert len(remaining) == 1 and remaining[0]['body'] == 'Reply' and remaining[0]['reply_to_body'] is None
            assert (await client.put(link, headers=owner, json={'group_id': None})).status_code == 200
            assert (await client.get(url, headers=owner)).status_code == 404

    run(scenario())


def test_channel_inbox_is_private_and_requires_current_confirmed_fee(env):
    import aiosqlite

    path, app = env

    async def scenario():
        async with aiosqlite.connect(path) as db:
            await db.execute('UPDATE accounts SET stars=20 WHERE telegram_id=102')
            await db.commit()
        async with await client_for(app) as client:
            owner, reader, other = headers(101), headers(102), headers(ADMIN)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Reader inbox', 'username': 'reader_inbox', 'is_public': True})
            base = f"/api/channels/{channel.json()['id']}/inbox"
            key = {**reader, 'Idempotency-Key': 'inbox-payment-0001'}
            assert (await client.get(base + '/me', headers=reader)).json()['can_send'] is False
            assert (await client.post(base + '/me', headers=key, json={'body': 'Hello'})).status_code == 403
            assert (await client.put(base + '/settings', headers=reader, json={'enabled': True, 'fee_stars': 6})).status_code == 404
            assert (await client.put(base + '/settings', headers=owner, json={'enabled': True, 'fee_stars': 6})).status_code == 200
            assert (await client.get(base + '/me', headers=reader)).json()['fee_stars'] == 6
            wrong = await client.post(base + '/me', headers=key, json={'body': 'Hello', 'approved_fee_stars': 5})
            assert wrong.status_code == 402 and wrong.json()['detail']['stars'] == 6
            paid = {'body': 'Hello', 'approved_fee_stars': 6}
            first, duplicate = await asyncio.gather(*(client.post(base + '/me', headers=key, json=paid) for _ in range(2)))
            assert first.status_code == duplicate.status_code == 200
            assert first.json() == duplicate.json()
            assert (await client.post(base + '/me', headers=key, json={**paid, 'body': 'Other'})).status_code == 409
            assert (await client.get(base, headers=other)).status_code == 404
            assert (await client.get(base + '/threads/102', headers=other)).status_code == 404
            assert (await client.get(base + '/me', headers=other)).json()['messages'] == []
            assert (await client.get(base, headers=owner)).json()['threads'][0]['reader_id'] == 102
            assert (await client.post(base + '/threads/102', headers={**owner, 'Idempotency-Key': 'inbox-response-0001'}, json={'body': 'Welcome'})).status_code == 200
            thread = (await client.get(base + '/me', headers=reader)).json()['messages']
            assert [(m['body'], m['direction'], m['fee_stars']) for m in thread] == [('Hello', 'incoming', 6), ('Welcome', 'reply', 0)]
            assert not any('sender_id' in m or 'owner_telegram_id' in m for m in thread)
            assert (await client.put(base + '/settings', headers=owner, json={'enabled': False, 'fee_stars': 0})).status_code == 200
            assert (await client.post(base + '/me', headers=key, json=paid)).json() == first.json()
            assert (await client.get(base + '/me', headers=reader)).json()['can_send'] is False
            stats = (await client.get(base.removesuffix('/inbox') + '/analytics', headers=owner)).json()
            assert stats['inbox_stars'] == stats['total_revenue_stars'] == 6
        async with aiosqlite.connect(path) as db:
            cur = await db.execute("SELECT telegram_id,amount FROM economy_transactions WHERE reason LIKE 'channel_inbox:%' ORDER BY telegram_id")
            assert await cur.fetchall() == [(101, 6), (102, -6)]

    run(scenario())


def test_reader_post_proposals_require_owner_approval_and_publish_once(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, reader, other = headers(101), headers(102), headers(ADMIN)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Proposals', 'username': 'post_proposals', 'is_public': True})
            base = f"/api/channels/{channel.json()['id']}"
            inbox = base + '/inbox'
            assert (await client.put(inbox + '/settings', headers=owner, json={'enabled': True, 'fee_stars': 0})).status_code == 200

            async def propose(suffix):
                key = {**reader, 'Idempotency-Key': f'proposed-post-{suffix}'}
                payload = {'body': 'Publish this text', 'propose_post': True}
                response = await client.post(inbox + '/me', headers=key, json=payload)
                assert response.status_code == 200 and response.json()['proposal_state'] == 'pending'
                assert (await client.post(inbox + '/me', headers=key, json={**payload, 'propose_post': False})).status_code == 409
                return f"{inbox}/proposals/{response.json()['id']}"

            path = await propose('0001')
            assert (await client.get(base + '/messages', headers=other)).json()['messages'] == []
            assert (await client.post(path + '/publish', headers=reader)).status_code == 404
            assert (await client.post(path + '/withdraw', headers=other)).status_code == 404
            results = await asyncio.gather(*(client.post(path + '/publish', headers=owner) for _ in range(2)))
            assert results[0].status_code == results[1].status_code == 200 and results[0].json() == results[1].json()
            published = (await client.get(base + '/messages', headers=reader)).json()['messages']
            assert [(row['id'], row['body']) for row in published] == [(results[0].json()['published_post_id'], 'Publish this text')]
            assert (await client.post(path + '/withdraw', headers=reader)).status_code == 409
            path = await propose('0002')
            assert (await client.post(path + '/withdraw', headers=reader)).json()['proposal_state'] == 'withdrawn'
            assert (await client.post(path + '/publish', headers=owner)).status_code == 409
            path = await propose('0003')
            assert (await client.post(path + '/decline', headers=owner)).json()['proposal_state'] == 'declined'
            messages = (await client.get(inbox + '/me', headers=reader)).json()['messages']
            assert [m['proposal_state'] for m in messages] == ['published', 'withdrawn', 'declined']

    run(scenario())


def test_group_topics_have_separate_history_and_enforce_membership_and_closure(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member, outsider = headers(101), headers(102), headers(ADMIN)
            group = await client.post('/api/groups', headers=owner, json={'title': 'Topic group', 'member_ids': [102]})
            path = f"/api/groups/{group.json()['id']}"
            topics = path + '/topics'
            assert (await client.get(topics, headers=outsider)).status_code == 403
            assert (await client.post(topics, headers=member, json={'title': 'Denied'})).status_code == 403
            results = [await client.post(topics, headers=owner, json={'title': title}) for title in ('Planning', 'Ideas')]
            assert [r.status_code for r in results] == [200, 200]
            assert (await client.post(topics, headers=owner, json={'title': 'planning'})).status_code == 409
            topic, other = [topics + f"/{r.json()['id']}" for r in results]
            key = {**member, 'Idempotency-Key': 'topic-message-0001'}
            sent = await client.post(topic + '/messages', headers=key, json={'body': 'Topic only'})
            assert sent.status_code == 201
            assert (await client.post(topic + '/messages', headers=key, json={'body': 'Topic only'})).json()['id'] == sent.json()['id']
            assert (await client.get(topic + '/messages', headers=outsider)).status_code == 403
            assert (await client.get(other + '/messages', headers=member)).json()['messages'] == []
            assert (await client.get(path + '/messages', headers=member)).json()['messages'] == []
            assert (await client.get(path + f"/messages?message_ids={sent.json()['id']}", headers=member)).json()['messages'] == []
            assert (await client.get(path + '/messages/search?q=Topic', headers=member)).json()['messages'] == []
            assert (await client.patch(topic, headers=owner, json={'closed': True, 'title': 'Plans'})).json()['closed'] is True
            assert (await client.get(topic + '/messages', headers=member)).json()['messages'][0]['body'] == 'Topic only'
            assert (await client.post(topic + '/messages', headers=member, json={'body': 'Closed'})).status_code == 403
            assert (await client.patch(topic, headers=owner, json={'closed': False})).json()['closed'] is False
            assert (await client.put(path + '/send-permissions', headers=owner, json={'default_send_restrictions': 1})).status_code == 200
            assert (await client.post(topic + '/messages', headers=member, json={'body': 'Blocked'})).status_code == 403
            assert (await client.delete(topic, headers=owner)).status_code == 200
            assert (await client.get(topic + '/messages', headers=member)).status_code == 404

    run(scenario())


def test_group_antispam_blocks_bursts_and_repeats_but_not_retries_or_admins(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            created = await client.post('/api/groups', headers=owner, json={'title': 'Anti-spam', 'member_ids': [102]})
            base = f"/api/groups/{created.json()['id']}"
            msg = base + '/messages'
            setting = {'default_send_restrictions': 0, 'anti_spam_enabled': True}
            assert (await client.put(base + '/send-permissions', headers=member, json=setting)).status_code == 403
            assert (await client.put(base + '/send-permissions', headers=owner, json=setting)).json()['anti_spam_enabled'] is True
            assert (await client.get(base, headers=member)).json()['anti_spam_enabled'] is True
            body = {'body': 'Repeated promotional offer'}
            key = {**member, 'Idempotency-Key': 'antispam-message-01'}
            first = await client.post(msg, headers=key, json=body)
            assert first.status_code == 201
            assert (await client.post(msg, headers=member, json={'body': 'REPEATED   promotional offer'})).status_code == 201
            repeat = await client.post(msg, headers=member, json=body)
            assert repeat.status_code == 429 and repeat.json()['detail'] == 'GROUP_ANTI_SPAM_REPEAT'
            assert (await client.post(msg, headers=key, json=body)).json()['id'] == first.json()['id']
            for i in range(6):
                assert (await client.post(msg, headers=member, json={'body': f'Distinct message {i}'})).status_code == 201
            burst = await client.post(msg, headers=member, json={'body': 'One too many'})
            assert burst.status_code == 429 and burst.json()['detail'] == 'GROUP_ANTI_SPAM_BURST'
            for _ in range(3):
                assert (await client.post(msg, headers=owner, json=body)).status_code == 201
            assert (await client.put(base + '/send-permissions', headers=owner, json={**setting, 'anti_spam_enabled': False})).status_code == 200
            assert (await client.post(msg, headers=member, json=body)).status_code == 201

    run(scenario())


def test_community_boost_has_one_slot_and_optional_slow_mode_exception(env):
    import aiosqlite

    path, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, member, other = headers(101), headers(102), headers(ADMIN)
            group = await client.post('/api/groups', headers=owner, json={'title': 'Boosted group', 'member_ids': [102]})
            gid = group.json()['id']
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Boosted channel', 'username': 'boosted_channel', 'is_public': True})
            cid = channel.json()['id']
            root = f'/api/groups/{gid}'
            group_boost = f'/api/community-boosts/group/{gid}'
            channel_boost = f'/api/community-boosts/channel/{cid}'
            assert (await client.get(group_boost, headers=other)).status_code == 403
            assert (await client.put(channel_boost, headers=member)).status_code == 403
            assert (await client.put(root + '/slow-mode', headers=owner, json={'seconds': 300})).status_code == 200
            assert (await client.post(root + '/messages', headers=member, json={'body': 'First'})).status_code == 201
            boost = await client.put(group_boost, headers=member)
            assert boost.status_code == 200 and boost.json()['level'] == 1 and boost.json()['boosts'] == 1
            assert (await client.put(group_boost, headers=member)).json() == boost.json()
            assert (await client.post(root + '/messages', headers=member, json={'body': 'Still slowed'})).status_code == 429
            assert (await client.put(root + '/boost-settings', headers=member, json={'slow_mode_bypass': True})).status_code == 403
            assert (await client.put(root + '/boost-settings', headers=owner, json={'slow_mode_bypass': True})).status_code == 200
            assert (await client.post(root + '/messages', headers=member, json={'body': 'Boost bypass'})).status_code == 201
            assert (await client.put(root + '/send-permissions', headers=owner, json={'default_send_restrictions': 1})).status_code == 200
            assert (await client.post(root + '/messages', headers=member, json={'body': 'Still restricted'})).status_code == 403
            assert (await client.put(f'/api/channels/{cid}/membership', headers=member, json={'joined': True})).status_code == 200
            assert (await client.put(channel_boost, headers=member)).status_code == 409
            async with aiosqlite.connect(path) as db:
                await db.execute('UPDATE community_boosts SET assigned_at=0 WHERE account_id=102')
                await db.commit()
            assert (await client.put(channel_boost, headers=member)).json()['assigned_here'] is True
            assert (await client.get(group_boost, headers=owner)).json()['boosts'] == 0
            assert (await client.get(channel_boost, headers=owner)).json()['boosts'] == 1
            assert (await client.put(f'/api/channels/{cid}/membership', headers=member, json={'joined': False})).status_code == 200
            assert (await client.get(channel_boost, headers=owner)).json()['boosts'] == 0

    run(scenario())


def test_channel_post_views_deduplicate_and_respect_channel_access(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, reader = headers(101), headers(102)
            channel = await client.post('/api/channels', headers=owner, json={'title': 'Reach', 'is_public': False})
            base = f"/api/channels/{channel.json()['id']}"
            first, second = [(await client.post(base + '/messages', headers=owner, json={'body': body})).json()['id']
                             for body in ('First', 'Second')]
            url = base + '/post-views'
            viewed = {'ids': [first, second, first, 99999], 'increment': True}
            assert (await client.post(url, headers=reader, json=viewed)).status_code == 404
            assert (await client.post(base + '/members', headers=owner, json={'user_ids': [102]})).status_code == 201
            assert (await client.post(url, headers=owner, json=viewed)).json()['views'] == [0, 0, 0, 0]
            assert (await client.post(url, headers=reader, json={'ids': [first]})).json()['views'] == [0]
            for _ in range(2):
                assert (await client.post(url, headers=reader, json=viewed)).json()['views'] == [1, 1, 1, 0]
            stats = (await client.get(base + '/analytics', headers=owner)).json()
            assert (stats['unique_post_views'], stats['reached_accounts']) == (2, 1)
            assert (await client.delete(base + f'/messages/{first}', headers=owner)).status_code == 200
            assert (await client.post(url, headers=reader, json=viewed)).json()['views'] == [0, 1, 0, 0]

    run(scenario())


def test_channel_analytics_counts_actual_departures_not_retries(env):
    _, app = env

    async def scenario():
        async with await client_for(app) as client:
            owner, reader = headers(101), headers(102)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Churn', 'username': 'churn_news', 'is_public': True})
            base = f"/api/channels/{channel.json()['id']}"
            membership = base + '/membership'
            for joined in (False, True, False, False, True):
                assert (await client.put(membership, headers=reader, json={'joined': joined})).status_code == 200
            for _ in range(2):
                assert (await client.put(base + '/members/102/ban', headers=owner, json={'banned': True})).status_code == 200
            stats = (await client.get(base + '/analytics?days=7', headers=owner)).json()
            assert (stats['voluntary_leaves'], stats['moderated_removals']) == (1, 1)

    run(scenario())


def test_scheduled_proposal_publishes_once_and_transfers_reward(env):
    import aiosqlite
    import inspect
    import time

    path, app = env
    startup = next(fn for fn in app.router.on_startup if fn.__name__ == 'start_scheduled_message_worker')
    worker = inspect.getclosurevars(startup).nonlocals['scheduled_message_worker']
    process = inspect.getclosurevars(worker).nonlocals['process_due_inbox_proposals']

    async def scenario():
        async with aiosqlite.connect(path) as db:
            await db.execute('UPDATE accounts SET stars=20 WHERE telegram_id=101')
            await db.commit()
        async with await client_for(app) as client:
            owner, reader = headers(101), headers(102)
            channel = await client.post('/api/channels', headers=owner, json={'title': 'Scheduled', 'username': 'scheduled_news', 'is_public': True})
            root = f"/api/channels/{channel.json()['id']}"
            inbox = root + '/inbox'
            assert (await client.put(inbox + '/settings', headers=owner, json={'enabled': True, 'fee_stars': 0})).status_code == 200
            for index, reward in enumerate((7, 10000), 1):
                response = await client.post(inbox + '/me', headers={**reader, 'Idempotency-Key': f'scheduled-post-000{index}'},
                                             json={'body': f'Scheduled proposal {index}', 'propose_post': True})
                pid = response.json()['id']
                route = f'{inbox}/proposals/{pid}/schedule'
                payload = {'scheduled_at': int(time.time()) + 60, 'reward_stars': reward}
                assert (await client.post(route, headers=reader, json=payload)).status_code == 404
                assert (await client.post(route, headers=owner, json=payload)).json()['proposal_state'] == 'scheduled'
                await process()
                async with aiosqlite.connect(path) as db:
                    await db.execute('UPDATE channel_inbox_messages SET scheduled_at=0 WHERE id=?', (pid,))
                    await db.commit()
                await asyncio.gather(process(), process())
            posts = (await client.get(root + '/messages', headers=reader)).json()['messages']
            assert [p['body'] for p in posts] == ['Scheduled proposal 1']
            states = (await client.get(inbox + '/me', headers=reader)).json()['messages']
            assert [m['proposal_state'] for m in states] == ['published', 'schedule_failed']
            async with aiosqlite.connect(path) as db:
                cur = await db.execute("SELECT amount FROM economy_transactions WHERE reason LIKE 'channel_proposal_reward:%' ORDER BY amount")
                assert await cur.fetchall() == [(-7,), (7,)]

    run(scenario())


def test_channel_giveaway_eligible_members_only_settles_once(env):
    import aiosqlite
    import inspect
    import time

    path, app = env
    startup = next(fn for fn in app.router.on_startup if fn.__name__ == 'start_scheduled_message_worker')
    worker = inspect.getclosurevars(startup).nonlocals['scheduled_message_worker']
    settle = inspect.getclosurevars(worker).nonlocals['process_due_channel_giveaways']

    async def scenario():
        async with aiosqlite.connect(path) as db:
            await db.execute('UPDATE accounts SET stars=20 WHERE telegram_id=101')
            await db.commit()
        async with await client_for(app) as client:
            owner, member, other = headers(101), headers(102), headers(ADMIN)
            ch = await client.post('/api/channels', headers=owner, json={'title': 'Draw', 'username': 'draw_channel', 'is_public': True})
            base = f"/api/channels/{ch.json()['id']}"
            root = base + '/giveaways'
            payload = {'ends_at': int(time.time()) + 120, 'winner_count': 2, 'prize_stars': 7}
            assert (await client.post(root, headers=member, json=payload)).status_code == 404
            draw = await client.post(root, headers=owner, json=payload)
            assert draw.status_code == 201
            gid = draw.json()['id']
            entry = f'{root}/{gid}/entry'
            assert (await client.post(root, headers=owner, json=payload)).status_code == 409
            assert (await client.put(entry, headers=owner)).status_code == 404
            assert (await client.put(entry, headers=other)).status_code == 404
            for user in (member, other):
                assert (await client.put(base + '/membership', headers=user, json={'joined': True})).status_code == 200
                for _ in range(2):
                    assert (await client.put(entry, headers=user)).json() == {'entered': True}
            assert (await client.put(base + '/membership', headers=other, json={'joined': False})).status_code == 200
            await settle()
            assert (await client.get(root, headers=member)).json()['giveaways'][0]['state'] == 'open'
            async with aiosqlite.connect(path) as db:
                await db.execute('UPDATE channel_giveaways SET ends_at=0 WHERE id=?', (gid,))
                await db.commit()
            await asyncio.gather(settle(), settle())
            result = (await client.get(root, headers=member)).json()['giveaways'][0]
            assert (result['state'], result['drawn_count'], result['won']) == ('drawn', 1, True)
            assert (await client.get(root, headers=other)).json()['giveaways'][0]['won'] is False
            async with aiosqlite.connect(path) as db:
                cur = await db.execute("SELECT telegram_id,amount FROM economy_transactions WHERE reason LIKE 'channel_giveaway:%' ORDER BY telegram_id")
                assert await cur.fetchall() == [(101, -7), (102, 7)]

    run(scenario())
