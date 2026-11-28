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


def test_stock_screen_price_is_the_one_that_charges(env) -> None:
    """Цена из штатного экрана Telegram — это плата за ЛС.

    Клиент шлёт `account.setGlobalPrivacySettings` (`noncontact_peers_paid_stars`),
    а списание при отправке смотрит в `dm_message_fees`. Раньше это были две
    независимые записи: цена со слайдера ни на что не влияла.
    """
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            route = "/api/me/privacy/global"
            quote = "/api/chats/101/message-fee"

            assert (await client.put(route, headers=headers(101), json={"noncontact_peers_paid_stars": 25})).status_code == 200
            assert (await client.get(quote, headers=headers(102))).json() == {"stars": 25}
            # Та же цена видна и в своём /message-fee, чтобы оба пути не расходились.
            assert (await client.get("/api/me/message-fee", headers=headers(101))).json()["stars"] == 25
            assert (await client.get(route, headers=headers(101))).json()["noncontact_peers_paid_stars"] == 25

            body = {"recipient_id": 101, "body": "price from the slider"}
            denied = await client.post("/api/messages", headers=headers(102), json=body)
            assert denied.status_code == 402 and denied.json()["detail"]["stars"] == 25
            paid = await client.post(
                "/api/messages",
                headers={**headers(102), "Idempotency-Key": "privacy-screen-price-0001"},
                json={**body, "approved_fee_stars": 25},
            )
            assert paid.status_code == 201

            # Цена, выставленная через /message-fee, тоже видна штатному экрану.
            assert (await client.put("/api/me/message-fee", headers=headers(101), json={"stars": 7, "exempt_users": []})).status_code == 200
            assert (await client.get(route, headers=headers(101))).json()["noncontact_peers_paid_stars"] == 7

            # 0 на слайдере — платных сообщений нет.
            assert (await client.put(route, headers=headers(101), json={"noncontact_peers_paid_stars": 0})).status_code == 200
            assert (await client.get("/api/me/message-fee", headers=headers(101))).json()["stars"] == 0
            assert (await client.get(quote, headers=headers(102))).json() == {"stars": 0}

    run(scenario())


def test_paid_messages_exceptions_are_the_allow_list(env) -> None:
    """«Кому платить не надо» на штатном экране — это exempt_users в списании."""
    _, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            assert (await client.put("/api/me/privacy/global", headers=headers(101),
                                      json={"noncontact_peers_paid_stars": 5})).status_code == 200
            assert (await client.put("/api/me/privacy/paid-messages", headers=headers(101),
                                     json={"mode": "contacts", "allow": [102], "deny": []})).status_code == 200
            # 102 в списке исключений — платы нет, остальным цена осталась.
            assert (await client.get("/api/chats/101/message-fee", headers=headers(102))).json() == {"stars": 0}
            assert (await client.get("/api/chats/101/message-fee", headers=headers(ADMIN))).json() == {"stars": 5}
            # Обратная сторона: исключение, добавленное через /message-fee, видно экрану.
            assert (await client.put("/api/me/message-fee", headers=headers(101),
                                     json={"stars": 5, "exempt_users": [ADMIN]})).status_code == 200
            # Экран видит оба источника исключений: allow-список ключа и exempt_users.
            rules = (await client.get("/api/me/privacy/paid-messages", headers=headers(101))).json()
            assert sorted(rules["allow"]) == [102, ADMIN]
            assert (await client.get("/api/chats/101/message-fee", headers=headers(ADMIN))).json() == {"stars": 0}

    run(scenario())
