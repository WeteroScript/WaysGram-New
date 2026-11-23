"""Геолокация канала и группы (channels.editLocation).

Раньше транспорт отвечал `TL_boolTrue` и ничего не сохранял: точка жила только в памяти
экрана и исчезала после перезагрузки профиля. Теперь точка, подпись и адрес лежат на
сервере и возвращаются в `GET /api/channels/{id}` / `GET /api/groups/{id}`.
"""
from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


def test_channel_location_is_owner_managed_and_visible_to_members(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, member, admin = headers(101), headers(102), headers(ADMIN)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Geo channel', 'username': 'geo_channel', 'is_public': True})
            cid = channel.json()['id']
            root = f'/api/channels/{cid}'
            location = {'latitude': 52.2297, 'longitude': 21.0122, 'label': 'Warsaw office',
                        'address': 'Marszałkowska 1'}

            assert (await client.put(root + '/location', headers=owner, json=location)).json()['location'] == location
            mine = (await client.get(root, headers=owner)).json()
            assert mine['location'] == location and mine['can_set_location'] is True
            assert (await client.get(root, headers=member)).json()['location'] == location
            assert (await client.get(root, headers=member)).json()['can_set_location'] is False

            # Подписчик не может менять геолокацию, админ без права change_info — тоже.
            assert (await client.put(root + '/location', headers=member, json=location)).status_code == 403
            assert (await client.post(root + '/admins', headers=owner,
                                      json={'telegram_id': ADMIN, 'rights': 1})).status_code == 201
            assert (await client.put(root + '/location', headers=admin, json=location)).status_code == 403
            assert (await client.post(root + '/admins', headers=owner,
                                      json={'telegram_id': ADMIN, 'rights': 64})).status_code == 201
            moved = {**location, 'latitude': 50.0647, 'longitude': 19.945, 'label': 'Krakow', 'address': 'Rynek 1'}
            assert (await client.put(root + '/location', headers=admin, json=moved)).json()['location'] == moved
            assert (await client.get(root, headers=owner)).json()['location'] == moved

            assert (await client.delete(root + '/location', headers=owner)).json()['location'] is None
            assert (await client.get(root, headers=owner)).json()['location'] is None

            for broken in ({'latitude': 91, 'longitude': 0}, {'latitude': '52', 'longitude': 21},
                           {'latitude': 52.2, 'longitude': 21.0, 'label': 'x' * 65}):
                assert (await client.put(root + '/location', headers=owner, json=broken)).status_code == 400

    run(scenario())


def test_group_location_requires_change_info_right(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, member = headers(101), headers(102)
            group = await client.post('/api/groups', headers=owner,
                                      json={'title': 'Geo group', 'member_ids': [102, ADMIN]})
            gid = group.json()['id']
            root = f'/api/groups/{gid}'
            location = {'latitude': 41.9028, 'longitude': 12.4964, 'label': 'Rome', 'address': 'Via Roma 1'}

            assert (await client.put(root + '/location', headers=member, json=location)).status_code == 403
            assert (await client.put(root + '/location', headers=owner, json=location)).status_code == 200
            seen = (await client.get(root, headers=member)).json()
            assert seen['location'] == location and seen['can_set_location'] is False
            mine = (await client.get(root, headers=owner)).json()
            assert mine['location'] == location and mine['can_set_location'] is True

            assert (await client.post(root + '/admins', headers=owner,
                                      json={'telegram_id': ADMIN, 'rights': 1})).status_code == 201
            assert (await client.put(root + '/location', headers=headers(ADMIN),
                                     json={**location, 'label': 'Rzym'})).status_code == 200
            assert (await client.get(root, headers=owner)).json()['location']['label'] == 'Rzym'

            assert (await client.delete(root + '/location', headers=owner)).status_code == 200
            assert (await client.get(root, headers=member)).json()['location'] is None

    run(scenario())
