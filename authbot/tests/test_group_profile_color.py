"""Цвет профиля группы (channels.updateColor для мегагруппы).

Клиент редактирует цвет через `channels.updateColor` (в том числе `for_profile`).
Транспорт отправлял запрос на `/api/channels/{id}` даже для мегагруппы, то есть на
несуществующий канал, а серверные настройки группы поля цвета не принимали.
"""
from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


def test_group_color_and_profile_color_are_stored_and_validated(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            group = await client.post('/api/groups', headers=owner,
                                      json={'title': 'Colored group', 'member_ids': [102, ADMIN]})
            gid = group.json()['id']
            root = f'/api/groups/{gid}'
            settings = root + '/settings'

            assert (await client.put(settings, headers=member, json={'color_id': 3})).status_code == 403
            saved = await client.put(settings, headers=owner,
                                     json={'color_id': 3, 'background_emoji_id': 5361600570352256858,
                                           'profile_color_id': 5, 'profile_background_emoji_id': 0})
            assert saved.status_code == 200
            seen = (await client.get(root, headers=member)).json()
            assert (seen['color_id'], seen['background_emoji_id']) == (3, 5361600570352256858)
            assert (seen['profile_color_id'], seen['profile_background_emoji_id']) == (5, 0)

            # Админ с правом change_info тоже может менять цвет.
            assert (await client.post(root + '/admins', headers=owner,
                                      json={'telegram_id': ADMIN, 'rights': 1})).status_code == 201
            assert (await client.put(settings, headers=headers(ADMIN),
                                     json={'profile_color_id': 1})).status_code == 200
            assert (await client.get(root, headers=owner)).json()['profile_color_id'] == 1

            for broken in ({'color_id': 8}, {'color_id': '3'}, {'profile_color_id': -1},
                           {'background_emoji_id': -5}, {'profile_background_emoji_id': 'x'}):
                assert (await client.put(settings, headers=owner, json=broken)).status_code == 400

    run(scenario())


def test_group_emoji_status_and_sticker_sets_round_trip(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            group = await client.post('/api/groups', headers=owner, json={'title': 'Group style'})
            gid = group.json()['id']
            root = f'/api/groups/{gid}'
            settings = root + '/settings'
            applied = await client.put(settings, headers=owner, json={
                'emoji_status_id': 123456789,
                'sticker_set_id': 234567890,
                'sticker_set_access_hash': -345678901,
                'emoji_set_id': 456789012,
                'emoji_set_access_hash': 567890123,
            })
            assert applied.status_code == 200, applied.text
            profile = await client.get(root, headers=owner)
            assert profile.status_code == 200, profile.text
            assert profile.json()['emoji_status_id'] == 123456789
            assert profile.json()['sticker_set_id'] == 234567890
            assert profile.json()['sticker_set_access_hash'] == -345678901
            assert profile.json()['emoji_set_id'] == 456789012
            assert profile.json()['emoji_set_access_hash'] == 567890123
            for broken in ({'emoji_status_id': -1}, {'sticker_set_id': True},
                           {'emoji_set_access_hash': 2**63}):
                assert (await client.put(settings, headers=owner, json=broken)).status_code == 400

    run(scenario())


def test_channel_color_is_still_channel_scoped(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Colored channel', 'username': 'colored_channel',
                                              'is_public': True})
            cid = channel.json()['id']
            root = f'/api/channels/{cid}'
            assert (await client.put(root + '/settings', headers=owner,
                                     json={'color_id': 2, 'profile_color_id': 4})).status_code == 200
            data = (await client.get(root, headers=owner)).json()
            assert (data['color_id'], data['profile_color_id']) == (2, 4)
            # Цвет группы не протекает в канал с тем же серверным смыслом.
            group = await client.post('/api/groups', headers=owner, json={'title': 'Plain group'})
            gid = group.json()['id']
            assert (await client.get(f'/api/groups/{gid}', headers=owner)).json()['color_id'] == 0

    run(scenario())
