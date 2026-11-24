"""Экран приватности «Сообщения»: платные сообщения и глобальные настройки.

Клиент запрашивает `account.getGlobalPrivacySettings` и ключ `inputPrivacyKeyNoPaidMessages`.
Без серверной части экран оставался пустым, а переключатели не сохранялись.
"""

from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


def test_paid_messages_privacy_key_round_trip(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            route = "/api/me/privacy/paid-messages"
            default = await client.get(route, headers=headers(101))
            assert default.status_code == 200
            assert default.json()["mode"] == "everyone"

            saved = await client.put(route, headers=headers(101), json={"mode": "contacts", "allow": [102], "deny": []})
            assert saved.status_code == 200
            reread = (await client.get(route, headers=headers(101))).json()
            assert reread["mode"] == "contacts" and reread["allow"] == [102]

            bad = await client.put(route, headers=headers(101), json={"mode": "friends"})
            assert bad.status_code == 400

    run(scenario())


def test_global_privacy_settings_round_trip(env) -> None:
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            route = "/api/me/privacy/global"
            defaults = (await client.get(route, headers=headers(101))).json()
            assert defaults["hide_read_marks"] is False
            assert defaults["display_gifts_button"] is True
            assert defaults["noncontact_peers_paid_stars"] == 0

            saved = await client.put(
                route,
                headers=headers(101),
                json={
                    "hide_read_marks": True,
                    "keep_archived_unmuted": True,
                    "display_gifts_button": False,
                    "noncontact_peers_paid_stars": 25,
                },
            )
            assert saved.status_code == 200
            reread = (await client.get(route, headers=headers(101))).json()
            assert reread["hide_read_marks"] is True
            assert reread["keep_archived_unmuted"] is True
            assert reread["display_gifts_button"] is False
            assert reread["noncontact_peers_paid_stars"] == 25
            # Остальные значения не сбрасываются.
            assert reread["keep_archived_folders"] is False

            # Чужие настройки не меняются.
            other = (await client.get(route, headers=headers(102))).json()
            assert other["hide_read_marks"] is False

            for bad in ({"hide_read_marks": "yes"}, {"noncontact_peers_paid_stars": -5}, {"noncontact_peers_paid_stars": True}):
                assert (await client.put(route, headers=headers(102), json=bad)).status_code == 400

    run(scenario())
