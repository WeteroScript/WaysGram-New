"""Платные ЛС и приватность подарков должны соблюдаться сервером на всех путях."""

from messenger_api import init_messenger_storage
from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)

import bot as bot_module


def test_paid_dm_blocks_scheduled_live_location_and_when_online(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            fee = await client.put("/api/me/message-fee", headers=headers(102), json={"stars": 5, "exempt_users": []})
            assert fee.status_code == 200 and fee.json()["stars"] == 5

            # Текст: нужна оплата.
            text = await client.post("/api/messages", headers=headers(101), json={"recipient_id": 102, "body": "hi"})
            assert text.status_code == 402 and text.json()["detail"]["code"] == "MESSAGE_FEE_REQUIRED"

            # Отложенное сообщение нельзя создать в обход платы.
            scheduled = await client.post(
                "/api/messages/schedule", headers=headers(101),
                json={"recipient_id": 102, "body": "later", "scheduled_at": int(__import__("time").time()) + 3600},
            )
            assert scheduled.status_code == 402 and scheduled.json()["detail"]["code"] == "MESSAGE_FEE_UNSUPPORTED_FOR_SEND_TYPE"
            pending = (await client.get("/api/messages/scheduled", headers=headers(101))).json()
            assert not pending.get("messages")

            # «Когда появится онлайн» — тот же платный диалог.
            online = await client.post(
                "/api/messages/when-online", headers=headers(101),
                json={"recipient_id": 102, "body": "when online"},
            )
            assert online.status_code == 402

            # Живая геолокация тоже не бесплатна.
            live = await client.post(
                "/api/messages/live-location", headers=headers(101),
                json={"recipient_id": 102, "latitude": 52.2, "longitude": 21.0, "period": 3600},
            )
            assert live.status_code == 402

            # После добавления в контакты получателя и его ответа переписка свободна.
            assert (await client.post("/api/contacts", headers=headers(102), json={"user_id": 101})).status_code in (200, 201)
            allowed = await client.post("/api/messages", headers=headers(101), json={"recipient_id": 102, "body": "hi"})
            assert allowed.status_code == 201

    run(scenario())


def test_gift_privacy_contacts_blocks_strangers(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            await bot_module.set_telegram_gift_values("g1", price=40, supply=10)

            # По умолчанию подарки может присылать кто угодно.
            default = (await client.get("/api/me/privacy/gifts", headers=headers(102))).json()
            assert default == {"mode": "everyone", "allow": [], "deny": []}

            # Экран «Подарки» раньше писал только ключ отображения: миграция при старте переносит
            # уже сделанный выбор в ключ, который действительно запрещает подарки.
            assert (await client.put("/api/me/privacy/gift-autosave", headers=headers(102), json={"mode": "contacts", "allow": [], "deny": []})).status_code == 200
            await init_messenger_storage(path)
            migrated = (await client.get("/api/me/privacy/gifts", headers=headers(102))).json()
            assert migrated["mode"] == "contacts"
            legacy = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={"recipient_id": 102})
            assert legacy.status_code == 403

            # «Только контакты».
            saved = await client.put("/api/me/privacy/gifts", headers=headers(102), json={"mode": "contacts", "allow": [], "deny": []})
            assert saved.status_code == 200 and saved.json()["mode"] == "contacts"
            stranger = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={"recipient_id": 102})
            assert stranger.status_code == 403 and stranger.json()["detail"] == "GIFT_PRIVACY_FORBIDDEN"

            # Свой подарок тоже нельзя перекинуть постороннему.
            own = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})
            assert own.status_code == 201
            serial = own.json()["gift"]["serial_number"]
            transfer = await client.post("/api/gifts/transfer", headers=headers(101), json={"serial_number": serial, "recipient_id": 102})
            assert transfer.status_code == 403

            # Контакт получателя подарок отправить может.
            assert (await client.post("/api/contacts", headers=headers(102), json={"user_id": 101})).status_code in (200, 201)
            ok = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={"recipient_id": 102})
            assert ok.status_code == 201

            # «Никто» — не пропускает даже контакты.
            assert (await client.put("/api/me/privacy/gifts", headers=headers(102), json={"mode": "nobody", "allow": [], "deny": []})).status_code == 200
            none = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={"recipient_id": 102})
            assert none.status_code == 403

            # Явное исключение в deny перебивает контакт.
            assert (await client.put(
                "/api/me/privacy/gifts", headers=headers(102),
                json={"mode": "contacts", "allow": [102], "deny": [101]},
            )).status_code == 200
            denied = await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={"recipient_id": 102})
            assert denied.status_code == 403

            # Подарок самому себе приватностью не блокируется.
            assert (await client.put("/api/me/privacy/gifts", headers=headers(101), json={"mode": "nobody", "allow": [], "deny": []})).status_code == 200
            assert (await client.post("/api/telegram/gifts/g1/purchase", headers=headers(101), json={})).status_code == 201

    run(scenario())
