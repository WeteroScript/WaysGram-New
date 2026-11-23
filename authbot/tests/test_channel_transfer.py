"""Передача владения каналом (channels.editCreator без SRP).

Штатный экран Telegram требует SRP-подтверждение облачного пароля, которого в WaysGram нет.
Сервер отдаёт список администраторов-кандидатов и меняет владельца; при включённом облачном
пароле подтверждение идёт паролем WaysGram (PBKDF2), а не SRP.
"""
import hashlib
import secrets
import sqlite3
import time

from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


def _set_cloud_password(path, telegram_id: int, password: str) -> None:
    salt = secrets.token_bytes(16)
    verifier = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 600_000)
    with sqlite3.connect(path) as db:
        db.execute("""INSERT INTO api_cloud_passwords(telegram_id,salt,verifier) VALUES(?,?,?)
            ON CONFLICT(telegram_id) DO UPDATE SET salt=excluded.salt,verifier=excluded.verifier""",
                   (telegram_id, salt, verifier))
        db.commit()


def test_transfer_requires_admin_candidate_and_moves_ownership(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, admin, outsider = headers(101), headers(102), headers(ADMIN)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Transfer me', 'username': 'transfer_me', 'is_public': True})
            cid = channel.json()['id']
            root = f'/api/channels/{cid}'
            options = root + '/transfer'

            assert (await client.post(root + '/admins', headers=owner,
                                      json={'telegram_id': 102, 'rights': 15})).status_code == 201
            assert (await client.get(options, headers=admin)).status_code == 403
            listed = (await client.get(options, headers=owner)).json()
            assert listed['password_required'] is False
            assert [c['telegram_id'] for c in listed['candidates']] == [102]

            assert (await client.post(options, headers=owner,
                                      json={'new_owner_id': 101})).status_code == 400
            assert (await client.post(options, headers=owner,
                                      json={'new_owner_id': ADMIN})).status_code == 400  # не админ
            assert (await client.post(options, headers=admin,
                                      json={'new_owner_id': ADMIN})).status_code == 403

            moved = await client.post(options, headers=owner, json={'new_owner_id': 102})
            assert moved.status_code == 200 and moved.json()['owner_telegram_id'] == 102
            after = (await client.get(root, headers=owner)).json()
            assert after['owner_telegram_id'] == 102
            admins = (await client.get(root + '/admins', headers=owner)).json()
            assert admins['owner_telegram_id'] == 102
            assert 102 not in [a['telegram_id'] for a in admins['admins']]
            assert 101 in [a['telegram_id'] for a in admins['admins']]  # прежний владелец — админ

            # Новый владелец управляет каналом, передавать его дальше может только он.
            assert (await client.put(root + '/settings', headers=admin,
                                     json={'signatures_enabled': True})).status_code == 200
            assert (await client.get(options, headers=owner)).status_code == 403

    run(scenario())


def test_transfer_requires_cloud_password_when_enabled(env) -> None:
    path, app = env
    _set_cloud_password(path, 101, 'waysgram-pass-1')

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner, admin = headers(101), headers(102)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Guarded', 'username': 'transfer_guarded', 'is_public': True})
            cid = channel.json()['id']
            root = f'/api/channels/{cid}'
            options = root + '/transfer'
            assert (await client.post(root + '/admins', headers=owner,
                                      json={'telegram_id': 102, 'rights': 15})).status_code == 201
            assert (await client.get(options, headers=owner)).json()['password_required'] is True

            assert (await client.post(options, headers=owner,
                                      json={'new_owner_id': 102})).status_code == 401
            wrong = await client.post(options, headers=owner,
                                      json={'new_owner_id': 102, 'password': 'wrong-password'})
            assert wrong.status_code == 403 and wrong.json()['detail'] == 'PASSWORD_HASH_INVALID'
            good = await client.post(options, headers=owner,
                                     json={'new_owner_id': 102, 'password': 'waysgram-pass-1'})
            assert good.status_code == 200
            assert (await client.get(root, headers=admin)).json()['owner_telegram_id'] == 102

    run(scenario())


def test_transfer_is_logged_for_the_admin_log(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            owner = headers(101)
            channel = await client.post('/api/channels', headers=owner,
                                        json={'title': 'Logged', 'username': 'transfer_logged', 'is_public': True})
            cid = channel.json()['id']
            root = f'/api/channels/{cid}'
            assert (await client.post(root + '/admins', headers=owner,
                                      json={'telegram_id': 102, 'rights': 15})).status_code == 201
            assert (await client.post(root + '/transfer', headers=owner,
                                      json={'new_owner_id': 102})).status_code == 200
            with sqlite3.connect(path) as db:
                rows = db.execute("""SELECT actor_id,action FROM channel_admin_log
                    WHERE channel_id=? AND action='transfer_owner'""", (cid,)).fetchall()
            assert rows == [(101, "transfer_owner")]
            assert int(time.time()) > 0

    run(scenario())
