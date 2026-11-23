"""Отправка от имени группы и публичных каналов (channels.getSendAs в клиенте).

Серверные правила:
* `GET /api/groups/{id}/send-identities` — кто может быть отправителем: сама группа
  (владелец или админ с `anonymous_allowed`) и публичные каналы самого пользователя.
* `POST /api/groups/{id}/identity-messages` с `send_as_id` — отправка от этого отправителя;
  теперь разрешена и с вложением (медиа, карточка, контакт, геометка).
"""
import sqlite3
import uuid
from pathlib import Path

from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


def test_send_identities_list_group_and_owned_public_channels(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, admin, member = headers(101), headers(102), headers(ADMIN)
            group = await client.post('/api/groups', headers=owner,
                                      json={'title': 'Identities', 'member_ids': [102, ADMIN]})
            gid = group.json()['id']
            root = f'/api/groups/{gid}'
            identities = root + '/send-identities'

            mine = await client.post('/api/channels', headers=admin,
                                     json={'title': 'Admin channel', 'username': 'admin_sender', 'is_public': True})
            cid = mine.json()['id']
            await client.post('/api/channels', headers=admin,
                              json={'title': 'Private channel', 'is_public': False})
            await client.post('/api/channels', headers=owner,
                              json={'title': 'Owner channel', 'username': 'owner_sender', 'is_public': True})

            owner_list = (await client.get(identities, headers=owner)).json()
            assert owner_list['anonymous_allowed'] is True and owner_list['can_manage'] is True
            assert owner_list['title'] == 'Identities'
            assert [c['id'] for c in owner_list['channels']] and all(
                c['id'] for c in owner_list['channels'])
            owner_ids = {c['id'] for c in owner_list['channels']}
            assert cid not in owner_ids  # чужой канал не попадает в список

            admin_list = (await client.get(identities, headers=admin)).json()
            assert admin_list['anonymous_allowed'] is False
            assert [c['id'] for c in admin_list['channels']] == [cid]  # только публичный свой
            assert admin_list['admins'] == []

            assert (await client.get(identities, headers=member)).status_code == 200
            assert (await client.post(root + '/admins', headers=owner,
                                      json={'telegram_id': 102})).status_code == 201
            assert (await client.put(root + '/admins/102/anonymous', headers=owner,
                                     json={'enabled': True})).status_code == 200
            assert (await client.get(identities, headers=admin)).json()['anonymous_allowed'] is True

    run(scenario())


def test_identity_send_supports_text_media_and_rejects_foreign_channel(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, admin = headers(101), headers(102)
            group = await client.post('/api/groups', headers=owner,
                                      json={'title': 'Identity send', 'member_ids': [102]})
            gid = group.json()['id']
            root = f'/api/groups/{gid}'
            identities = root + '/identity-messages'

            channel = await client.post('/api/channels', headers=admin,
                                        json={'title': 'Public author', 'username': 'public_author_x', 'is_public': True})
            cid = channel.json()['id']

            text = await client.post(identities, headers=admin, json={'body': 'From channel', 'send_as_id': cid})
            assert text.status_code == 201
            assert text.json()['send_as_id'] == cid and text.json()['identity_title'] == 'Public author'
            assert text.json()['sender_id'] == 0

            with sqlite3.connect(Path(path).with_name('waysgram_messenger.sqlite3')) as db:
                media = uuid.uuid4().hex
                db.execute("""INSERT INTO media_assets
                    (id,owner_telegram_id,storage_name,mime_type,size_bytes,created_at,media_kind)
                    VALUES(?,102,?,?,100,1,?)""", (media, media, 'image/png', 'photo'))
                db.commit()
            photo = await client.post(identities, headers=admin,
                                      json={'body': 'Photo as channel', 'media_id': media, 'send_as_id': cid})
            assert photo.status_code == 201, photo.text
            assert photo.json()['send_as_id'] == cid and photo.json()['media_id'] == media

            # Чужой канал отправителем быть не может, и обычный участник не может писать от группы.
            foreign = await client.post(identities, headers=admin,
                                        json={'body': 'Nope', 'send_as_id': cid + 10 ** 6})
            assert foreign.status_code == 403
            assert (await client.post(identities, headers=admin,
                                      json={'body': 'Masked', 'send_as_id': 0})).status_code == 403
            anonymous = await client.post(identities, headers=owner, json={'body': 'Masked', 'send_as_id': 0})
            assert anonymous.status_code == 201 and anonymous.json()['send_as_id'] == 0

            # Заголовок канала и сам факт альтернативного отправителя видны в истории группы.
            messages = (await client.get(root + '/messages', headers=owner)).json()['messages']
            by_id = {m['id']: m for m in messages}
            assert by_id[text.json()['id']]['identity_title'] == 'Public author'
            assert by_id[text.json()['id']]['sender_id'] == 0
            assert by_id[anonymous.json()['id']]['send_as_id'] == 0

            # Обычная отправка без send_as_id по-прежнему идёт от аккаунта.
            plain = await client.post(root + '/messages', headers=admin, json={'body': 'As myself'})
            assert plain.status_code == 201 and plain.json().get('send_as_id') is None

    run(scenario())


def test_identity_is_not_allowed_in_discussion_threads(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Discuss identity', 'is_public': False})
            cid = channel.json()['id']
            post = await client.post(f'/api/channels/{cid}/messages', headers=owner, json={'body': 'News'})
            group = await client.post('/api/groups', headers=owner, json={'title': 'Threads', 'member_ids': [102]})
            gid = group.json()['id']
            assert (await client.put(f'/api/channels/{cid}/discussion-group', headers=owner,
                                     json={'group_id': gid})).status_code == 200
            url = f"/api/channels/{cid}/messages/{post.json()['id']}/comments"
            assert (await client.post(url, headers=owner,
                                      json={'body': 'Comment', 'send_as_id': 0})).status_code == 400

    run(scenario())
