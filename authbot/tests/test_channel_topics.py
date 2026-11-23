"""Темы канала (форум-режим): серверный слой без Telegram-форума.

`channels.toggleForum` уже сохранял `forum_enabled`, но топиков у канала не было: пост жил
только в общей ленте. Здесь проверяется полный цикл — включение форума, права (change_info
на управление, post_messages на публикацию, pin_messages на закрепление), публикация в тему,
изоляция от общей ленты, идемпотентность, закрепление и удаление.
"""
import sqlite3

from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


async def _channel(client, owner, username: str, forum: bool = True) -> int:
    created = await client.post('/api/channels', headers=owner,
                                json={'title': 'Topics', 'username': username, 'is_public': True})
    assert created.status_code in (200, 201), created.text
    cid = created.json()['id']
    if forum:
        enabled = await client.put(f'/api/channels/{cid}/settings', headers=owner,
                                   json={'forum_enabled': True})
        assert enabled.status_code == 200 and enabled.json()['forum_enabled'] is True
    return cid


def test_channel_topics_require_forum_and_manage_rights(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, pinner = headers(101), headers(102)
            cid = await _channel(client, owner, 'topics_rights', forum=False)
            root = f'/api/channels/{cid}/topics'
            assert (await client.post(f'/api/channels/{cid}/admins', headers=owner,
                                      json={'telegram_id': 102, 'rights': 8})).status_code == 201

            listed = (await client.get(root, headers=owner)).json()
            assert listed['forum_enabled'] is False
            assert listed['can_manage'] is True and listed['can_post'] is True
            assert listed['topics'] == []

            # Форум выключен — темы не создаются, пока владелец не включит режим.
            disabled = await client.post(root, headers=owner, json={'title': 'Off'})
            assert disabled.status_code == 409 and disabled.json()['detail'] == 'CHANNEL_FORUM_DISABLED'

            assert (await client.put(f'/api/channels/{cid}/settings', headers=owner,
                                     json={'forum_enabled': True})).status_code == 200
            # Право 8 — только закрепление: управлять темами и публиковать нельзя.
            assert (await client.post(root, headers=pinner,
                                      json={'title': 'Nope'})).status_code == 403
            assert (await client.post(root, headers=owner, json={'title': ' '})).status_code == 400

            created = await client.post(root, headers=owner, json={'title': 'Новости'})
            assert created.status_code == 201, created.text
            topic = created.json()
            assert topic['title'] == 'Новости' and topic['closed'] is False
            assert topic['message_count'] == 0 and topic['pin'] is None
            duplicate = await client.post(root, headers=owner, json={'title': 'Новости'})
            assert duplicate.status_code == 409
            assert duplicate.json()['detail'] == 'CHANNEL_TOPIC_TITLE_EXISTS'

            renamed = await client.patch(f'{root}/{topic["id"]}', headers=owner, json={'title': 'Анонсы'})
            assert renamed.status_code == 200 and renamed.json()['title'] == 'Анонсы'
            closed = await client.patch(f'{root}/{topic["id"]}', headers=owner, json={'closed': True})
            assert closed.json()['closed'] is True
            assert (await client.patch(f'{root}/{topic["id"]}', headers=pinner,
                                       json={'closed': False})).status_code == 403
            bad = await client.patch(f'{root}/{topic["id"]}', headers=owner, json={'icon': '🎯'})
            assert bad.status_code == 400 and bad.json()['detail'] == 'INVALID_TOPIC_SETTINGS'

            view = (await client.get(root, headers=pinner)).json()
            assert view['can_manage'] is False
            assert [t['title'] for t in view['topics']] == ['Анонсы']
            assert view['topics'][0]['closed'] is True

    run(scenario())


def test_topic_posts_are_isolated_and_follow_rights(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, poster, outsider = headers(101), headers(102), headers(ADMIN)
            cid = await _channel(client, owner, 'topics_posts')
            root = f'/api/channels/{cid}/topics'
            assert (await client.post(f'/api/channels/{cid}/admins', headers=owner,
                                      json={'telegram_id': 102, 'rights': 1})).status_code == 201
            topic_id = (await client.post(root, headers=owner, json={'title': 'Релизы'})).json()['id']
            messages = f'{root}/{topic_id}/messages'

            # Подписчик без права публикации читает тему, но не пишет в неё.
            assert (await client.get(messages, headers=outsider)).status_code == 200
            denied = await client.post(messages, headers=outsider, json={'body': 'Привет'})
            assert denied.status_code == 403 and denied.json()['detail'] == 'CHANNEL_TOPIC_POST_REQUIRED'
            empty = await client.post(messages, headers=owner, json={'body': '   '})
            assert empty.status_code == 400 and empty.json()['detail'] == 'CHANNEL_TOPIC_MESSAGE_REQUIRED'
            broken = await client.post(messages, headers=owner, json={'media_id': 'not-a-media-id'})
            assert broken.status_code == 400

            key = 'topic-post-00000001'
            keyed = {**owner, 'Idempotency-Key': key}
            first = await client.post(messages, headers=keyed, json={'body': 'Первый релиз'})
            assert first.status_code == 201, first.text
            post = first.json()
            assert post['topic_id'] == topic_id and post['body'] == 'Первый релиз'
            replay = await client.post(messages, headers=keyed, json={'body': 'Первый релиз'})
            assert replay.status_code == 201 and replay.json()['id'] == post['id']

            # Право post_messages у админа работает, а в общей ленте постов темы нет.
            sent = await client.post(messages, headers=poster, json={'body': 'От админа'})
            assert sent.status_code == 201
            feed = (await client.get(f'/api/channels/{cid}/messages', headers=owner)).json()
            assert feed['messages'] == []
            page = (await client.get(messages + '?limit=25&before_id=0', headers=owner)).json()
            assert [m['body'] for m in page['messages']] == ['Первый релиз', 'От админа']
            assert page['messages'][0]['sender_name'] == 'Topics'
            assert page['can_manage'] is True and page['can_post'] is True
            assert page['next_before_id'] == 0

            # Закрепление темы: нужно право pin_messages.
            assert (await client.put(f'{root}/{topic_id}/pin', headers=poster,
                                     json={'message_id': post['id']})).status_code == 403
            pinned = await client.put(f'{root}/{topic_id}/pin', headers=owner,
                                      json={'message_id': post['id']})
            assert pinned.status_code == 200 and pinned.json()['pin']['body'] == 'Первый релиз'
            missing = await client.put(f'{root}/{topic_id}/pin', headers=owner, json={'message_id': 999999})
            assert missing.status_code == 404
            unpinned = await client.put(f'{root}/{topic_id}/pin', headers=owner, json={'message_id': 0})
            assert unpinned.json()['pin'] is None

            followed = await client.put(f'{root}/{topic_id}/notifications', headers=outsider,
                                        json={'enabled': True})
            assert followed.json()['notifications_enabled'] is True
            seen = (await client.get(messages, headers=outsider)).json()
            assert seen['notifications_enabled'] is True
            assert seen['can_post'] is False

            # В закрытую тему писать нельзя, но история остаётся.
            await client.patch(f'{root}/{topic_id}', headers=owner, json={'closed': True})
            blocked = await client.post(messages, headers=owner, json={'body': 'Поздно'})
            assert blocked.status_code == 409 and blocked.json()['detail'] == 'CHANNEL_TOPIC_CLOSED'
            assert (await client.get(messages, headers=owner)).status_code == 200

    run(scenario())


def test_topic_deletion_keeps_history_out_of_the_list(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            cid = await _channel(client, owner, 'topics_delete')
            root = f'/api/channels/{cid}/topics'
            topic_id = (await client.post(root, headers=owner, json={'title': 'Черновики'})).json()['id']
            await client.post(f'{root}/{topic_id}/messages', headers=owner, json={'body': 'шум'})

            removed = await client.delete(f'{root}/{topic_id}', headers=owner)
            assert removed.status_code == 200 and removed.json()['deleted'] is True
            assert (await client.get(root, headers=owner)).json()['topics'] == []
            gone = await client.get(f'{root}/{topic_id}/messages', headers=owner)
            assert gone.status_code == 404 and gone.json()['detail'] == 'CHANNEL_TOPIC_NOT_FOUND'
            assert (await client.delete(f'{root}/{topic_id}', headers=owner)).status_code == 404

            with sqlite3.connect(path) as db:
                rows = db.execute("SELECT title,closed,deleted_at FROM channel_topics WHERE channel_id=?",
                                  (cid,)).fetchall()
            assert len(rows) == 1 and rows[0][1] == 1 and rows[0][2] is not None

    run(scenario())
