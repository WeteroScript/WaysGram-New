"""Рынок коллекционных юзернеймов: карточки, продажа, аукционы, TON.

Проверки повторяют правила, изложенные в docs/waysgram-fragment-plan.md:
владение берётся только из базы, покупка — одна транзакция с комиссией,
имя заморожено сутки после смены владельца, ставка холдит звёзды, а перевод
TON с уже зачисленным хешем второй раз не проходит.
"""

import time

import aiosqlite
import pytest

import fragment_market
from messenger_api import init_messenger_storage
from test_messenger_api import client_for, run
from test_star_gifts import ADMIN, env, headers  # noqa: F401  (env is a pytest fixture)


async def balances(path, *ids) -> dict[int, int]:
    """Балансы внутри уже запущенного сценария (соединение видит оба файла)."""
    async with aiosqlite.connect(path) as db:
        cur = await db.execute(
            "SELECT telegram_id,stars FROM accounts WHERE telegram_id IN (%s)"
            % ",".join("?" * len(ids)),
            ids,
        )
        return {int(row[0]): int(row[1]) for row in await cur.fetchall()}


def give_stars(path, telegram_id: int, stars: int) -> None:
    async def scenario():
        async with aiosqlite.connect(path) as db:
            await db.execute("UPDATE accounts SET stars=? WHERE telegram_id=?", (stars, telegram_id))
            await db.commit()

    run(scenario())


async def usernames(path) -> dict[int, str]:
    async with aiosqlite.connect(path) as db:
        cur = await db.execute("SELECT telegram_id,username FROM accounts")
        return {int(row[0]): str(row[1]) for row in await cur.fetchall()}


def test_existing_usernames_belong_to_their_accounts(env) -> None:
    """После миграции имя владельца не выглядит свободным: иначе его «купил» бы кто угодно."""
    path, app = env
    async def scenario() -> None:
        # В фикстуре у 101 есть username buyer, у 102 — friend.
        assert (await usernames(path)).get(101) == "buyer"
        async with await client_for(app) as client:
            card = (await client.get("/api/collectibles/buyer", headers=headers(102))).json()
            assert card["owner"]["telegram_id"] == 101
            assert card["free"] is False
            assert card["for_sale"] is False
            assert card["mine"] is False

    run(scenario())


def test_sale_transfers_ownership_and_pays_the_seller(env) -> None:
    path, app = env
    give_stars(path, 102, 1_000)
    give_stars(path, 101, 0)

    async def scenario() -> None:
        async with await client_for(app) as client:
            listed = await client.put(
                "/api/collectibles/buyer/listing", headers=headers(101),
                json={"price_stars": 100},
            )
            assert listed.status_code == 200, listed.text
            assert listed.json()["for_sale"] is True

            bought = await client.post(
                "/api/collectibles/buyer/buy", headers={**headers(102), "Idempotency-Key": "buyer-sale-mutation-0001"},
                json={},
            )
            assert bought.status_code == 200, bought.text
            card = bought.json()
            assert card["owner"]["telegram_id"] == 102
            assert card["mine"] is True
            assert card["frozen"] is True

            # Комиссия 5 %: покупатель отдал 100, продавец получил 95.
            assert await balances(path, 102, 101) == {102: 900, 101: 95}
            # Имя реально переехало на аккаунт покупателя.
            assert (await usernames(path))[102] == "buyer"
            assert (await usernames(path))[101] == ""

    run(scenario())


def test_a_name_cannot_be_sold_by_a_stranger_or_bought_twice(env) -> None:
    path, app = env
    give_stars(path, 101, 500)
    give_stars(path, 102, 500)

    async def scenario() -> None:
        async with await client_for(app) as client:
            assert (await client.put(
                "/api/collectibles/buyer/listing", headers=headers(102),
                json={"price_stars": 100},
            )).status_code == 403
            assert (await client.put(
                "/api/collectibles/buyer/listing", headers=headers(101),
                json={"price_stars": 100},
            )).status_code == 200

            # Владелец не покупает у себя.
            own = await client.post("/api/collectibles/buyer/buy", headers=headers(101), json={})
            assert own.status_code == 409 and own.json()["detail"]["code"] == "OWN_LISTING"

            first = await client.post(
                "/api/collectibles/buyer/buy",
                headers={**headers(102), "Idempotency-Key": "buyer-once-mutation-0001"}, json={},
            )
            assert first.status_code == 200
            # Повтор того же запроса возвращает тот же результат и не списывает второй раз.
            again = await client.post(
                "/api/collectibles/buyer/buy",
                headers={**headers(102), "Idempotency-Key": "buyer-once-mutation-0001"}, json={},
            )
            assert again.status_code == 200 and again.json()["owner"]["telegram_id"] == 102
            assert await balances(path, 102) == {102: 400}
            # А без ключа идемпотентности лот уже продан.
            third = await client.post("/api/collectibles/buyer/buy", headers=headers(102), json={})
            assert third.status_code == 409 and third.json()["detail"]["code"] == "NOT_FOR_SALE"

    run(scenario())


def test_sold_name_is_frozen_before_it_can_be_resold(env) -> None:
    path, app = env
    give_stars(path, 102, 1_000)

    async def scenario() -> None:
        async with await client_for(app) as client:
            await client.put("/api/collectibles/buyer/listing", headers=headers(101),
                             json={"price_stars": 100})
            assert (await client.post("/api/collectibles/buyer/buy", headers=headers(102),
                                      json={})).status_code == 200
            frozen = await client.put("/api/collectibles/buyer/listing", headers=headers(102),
                                      json={"price_stars": 500})
            assert frozen.status_code == 423
            assert frozen.json()["detail"]["code"] == "TRANSFER_FROZEN"

    run(scenario())


def test_free_name_is_bought_from_the_service_and_old_name_returns_to_the_pool(env) -> None:
    path, app = env
    give_stars(path, 101, 20_000)

    async def scenario() -> None:
        async with await client_for(app) as client:
            free = (await client.get("/api/collectibles/zhenya", headers=headers(101))).json()
            assert free["free"] is True
            assert free["price_stars"] == fragment_market.system_price("zhenya")

            claimed = await client.post(
                "/api/collectibles/zhenya/claim",
                headers={**headers(101), "Idempotency-Key": "claim-zhenya-mutation-0001"}, json={},
            )
            assert claimed.status_code == 200, claimed.text
            assert claimed.json()["owner"]["telegram_id"] == 101
            assert await balances(path, 101) == {101: 20_000 - fragment_market.system_price("zhenya")}
            names = await usernames(path)
            assert names[101] == "zhenya"
            # Прежнее имя снова продаётся сервисом.
            old = (await client.get("/api/collectibles/buyer", headers=headers(101))).json()
            assert old["free"] is True

    run(scenario())


def test_auction_holds_the_bid_refunds_the_outbid_and_transfers_the_name(env) -> None:
    path, app = env
    give_stars(path, 101, 0)
    give_stars(path, 102, 500)
    give_stars(path, ADMIN, 500)

    async def scenario() -> None:
        async with await client_for(app) as client:
            started = await client.post(
                "/api/collectibles/buyer/auction", headers=headers(101),
                json={"min_bid_stars": 100, "duration_hours": 24},
            )
            assert started.status_code == 200, started.text
            assert started.json()["auction"]["min_bid_stars"] == 100

            low = await client.post("/api/collectibles/buyer/bid", headers=headers(102),
                                    json={"amount_stars": 50})
            assert low.status_code == 409 and low.json()["detail"]["code"] == "BID_TOO_LOW"

            first = await client.post(
                "/api/collectibles/buyer/bid",
                headers={**headers(102), "Idempotency-Key": "bid-first-mutation-0001"},
                json={"amount_stars": 150},
            )
            assert first.status_code == 200, first.text
            assert await balances(path, 102) == {102: 350}
            # Перебитая ставка возвращается сразу.
            second = await client.post(
                "/api/collectibles/buyer/bid",
                headers={**headers(ADMIN), "Idempotency-Key": "bid-second-mutation-0001"},
                json={"amount_stars": 200},
            )
            assert second.status_code == 200, second.text
            assert await balances(path, 102, ADMIN) == {102: 500, ADMIN: 300}

            card = (await client.get("/api/collectibles/buyer", headers=headers(ADMIN))).json()
            assert card["auction"]["current_bid_stars"] == 200
            assert card["auction"]["minimum_next_bid_stars"] == 201

            # Время вышло: расчёт закрывает аукцион и передаёт имя победителю.
            async with aiosqlite.connect(path) as db:
                await db.execute(
                    "UPDATE collectible_auctions SET ends_at=? WHERE status='active'",
                    (int(time.time()) - 1,),
                )
                await db.commit()
            settled = (await client.get("/api/collectibles/buyer", headers=headers(ADMIN))).json()
            assert settled["owner"]["telegram_id"] == ADMIN
            assert settled["last_auction"]["status"] == "sold"
            assert await balances(path, 101) == {101: 190}  # 200 минус 5 % комиссии
            assert (await usernames(path))[ADMIN] == "buyer"

    run(scenario())


def test_bid_in_the_last_five_minutes_extends_the_auction(env) -> None:
    path, app = env
    give_stars(path, 102, 500)

    async def scenario() -> None:
        async with await client_for(app) as client:
            await client.post("/api/collectibles/buyer/auction", headers=headers(101),
                              json={"min_bid_stars": 10, "duration_hours": 1})
            async with aiosqlite.connect(path) as db:
                ends_at = int(time.time()) + 60
                await db.execute("UPDATE collectible_auctions SET ends_at=? WHERE status='active'",
                                 (ends_at,))
                await db.commit()
            bid = await client.post("/api/collectibles/buyer/bid", headers=headers(102),
                                    json={"amount_stars": 20})
            assert bid.status_code == 200, bid.text
            assert bid.json()["ends_at"] == pytest.approx(int(time.time()) + 300, abs=5)
            assert bid.json()["ends_at"] > ends_at

    run(scenario())


def test_ton_deposit_is_credited_once_per_transaction_hash(env, monkeypatch: pytest.MonkeyPatch) -> None:
    path, app = env
    monkeypatch.setenv("WAYSGRAM_TON_ADDRESS", "EQ" + "A" * 46)
    monkeypatch.setenv("WAYSGRAM_STARS_PER_TON", "100")
    monkeypatch.setenv("WAYSGRAM_TON_WATCHER_SECRET", "watcher-secret-value")
    give_stars(path, 101, 0)

    async def scenario() -> None:
        async with await client_for(app) as client:
            state = (await client.get("/api/me/ton", headers=headers(101))).json()
            assert state["address"].startswith("EQ")
            assert state["memo"] == "wg:101"
            assert state["stars_per_ton"] == 100

            body = {"memo": "wg:101", "tx_hash": "a" * 32, "amount_nanoton": "2000000000"}
            # Без секрета вотчера endpoint закрыт.
            denied = await client.post("/api/internal/ton/deposits", json=body)
            assert denied.status_code == 403
            ok = await client.post("/api/internal/ton/deposits",
                                   headers={"X-WaysGram-Ton-Secret": "watcher-secret-value"},
                                   json=body)
            assert ok.status_code == 200, ok.text
            assert ok.json()["stars_credited"] == 200
            assert await balances(path, 101) == {101: 200}
            # Тот же перевод второй раз не зачисляется.
            again = await client.post("/api/internal/ton/deposits",
                                      headers={"X-WaysGram-Ton-Secret": "watcher-secret-value"},
                                      json=body)
            assert again.status_code == 409
            assert again.json()["detail"]["code"] == "TON_DEPOSIT_DUPLICATE"
            assert await balances(path, 101) == {101: 200}

            # Перевод без memo не зачисляется автоматически.
            bad = await client.post("/api/internal/ton/deposits",
                                    headers={"X-WaysGram-Ton-Secret": "watcher-secret-value"},
                                    json={"memo": "hello", "tx_hash": "b" * 32,
                                          "amount_nanoton": "1000000000"})
            assert bad.status_code == 400

    run(scenario())


def test_ton_withdrawal_is_a_queued_request_behind_the_env_flag(env, monkeypatch: pytest.MonkeyPatch) -> None:
    path, app = env
    give_stars(path, 101, 1_000)
    monkeypatch.setenv("WAYSGRAM_STARS_PER_TON", "100")
    monkeypatch.delenv("WAYSGRAM_TON_WITHDRAWALS", raising=False)

    address = "-1:" + "c" * 64

    async def scenario() -> None:
        async with await client_for(app) as client:
            disabled = await client.post("/api/me/ton-withdraw", headers=headers(101),
                                         json={"stars": 600, "address": address})
            assert disabled.status_code == 403

    run(scenario())
    monkeypatch.setenv("WAYSGRAM_TON_WITHDRAWALS", "1")

    async def scenario_enabled() -> None:
        async with await client_for(app) as client:
            created = await client.post("/api/me/ton-withdraw", headers=headers(101),
                                        json={"stars": 600, "address": address})
            assert created.status_code == 200, created.text
            assert created.json()["status"] == "pending"
            assert created.json()["amount_nanoton"] == str(600 * 10**9 // 100)
            assert await balances(path, 101) == {101: 400}
            small = await client.post("/api/me/ton-withdraw", headers=headers(101),
                                      json={"stars": 10, "address": address})
            assert small.status_code == 400
            bad = await client.post("/api/me/ton-withdraw", headers=headers(101),
                                    json={"stars": 500, "address": "not-an-address"})
            assert bad.status_code == 400

    run(scenario_enabled())


def test_commission_can_be_changed_by_the_operator(env, monkeypatch: pytest.MonkeyPatch) -> None:
    """Комиссия по умолчанию 5 %, но администратор задаёт её окружением."""
    path, app = env
    monkeypatch.setenv("WAYSGRAM_FRAGMENT_COMMISSION_PERCENT", "10")
    give_stars(path, 101, 0)
    give_stars(path, 102, 1_000)

    async def scenario() -> None:
        async with await client_for(app) as client:
            listed = await client.put("/api/collectibles/buyer/listing", headers=headers(101),
                                      json={"price_stars": 100})
            assert listed.json()["commission_percent"] == 10
            bought = await client.post("/api/collectibles/buyer/buy", headers=headers(102), json={})
            assert bought.status_code == 200, bought.text
            assert await balances(path, 101) == {101: 90}

    run(scenario())


def test_group_username_is_not_sold_by_the_market(env) -> None:
    """Имя группы принадлежит чату, а не рынку: его нельзя ни занять, ни купить."""
    path, app = env

    async def scenario() -> None:
        async with aiosqlite.connect(path) as db:
            await db.execute(
                "INSERT INTO chat_groups(title,owner_telegram_id,username,created_at)"
                " VALUES('Клуб',101,'clubhouse',1)"
            )
            await db.commit()
            await db.execute("UPDATE accounts SET stars=50000 WHERE telegram_id=101")
            await db.commit()
        async with await client_for(app) as client:
            card = (await client.get("/api/collectibles/clubhouse", headers=headers(101))).json()
            assert card["free"] is False and card["reserved"] is True
            assert card["price_stars"] == 0
            claim = await client.post("/api/collectibles/clubhouse/claim", headers=headers(101),
                                      json={})
            assert claim.status_code == 409
            assert claim.json()["detail"]["code"] == "USERNAME_TAKEN"

    run(scenario())


def test_market_listing_shows_only_what_is_for_sale(env) -> None:
    path, app = env
    give_stars(path, 101, 0)

    async def scenario() -> None:
        async with await client_for(app) as client:
            assert (await client.get("/api/collectibles?section=market",
                                     headers=headers(102))).json()["items"] == []
            await client.put("/api/collectibles/buyer/listing", headers=headers(101),
                             json={"price_stars": 77})
            market = (await client.get("/api/collectibles?section=market",
                                       headers=headers(102))).json()
            assert [item["name"] for item in market["items"]] == ["buyer"]
            assert market["items"][0]["price_stars"] == 77
            mine = (await client.get("/api/me/collectibles", headers=headers(101))).json()
            assert [item["name"] for item in mine["items"]] == ["buyer"]
            assert mine["items"][0]["listing_id"] > 0

            removed = await client.delete("/api/collectibles/buyer/listing", headers=headers(101))
            assert removed.status_code == 200 and removed.json()["for_sale"] is False

    run(scenario())


def test_username_change_keeps_the_catalogue_in_sync(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            renamed = await client.put("/api/me", headers=headers(101),
                                       json={"username": "buyer_two"})
            assert renamed.status_code == 200, renamed.text
            old = (await client.get("/api/collectibles/buyer", headers=headers(102))).json()
            assert old["free"] is True
            new = (await client.get("/api/collectibles/buyer_two", headers=headers(102))).json()
            assert new["owner"]["telegram_id"] == 101
            # Купленное имя нельзя занять мимо рынка: владелец больше не отдаёт его.
            async with aiosqlite.connect(path) as db:
                await db.execute("UPDATE collectibles SET transfer_frozen_until=0")
                await db.commit()
            busy = await client.put("/api/me", headers=headers(102), json={"username": "buyer_two"})
            assert busy.status_code == 409

    run(scenario())


def test_market_is_created_by_the_storage_migration(env) -> None:
    """Схема рынка появляется тем же init_messenger_storage, что и остальные таблицы."""
    path, app = env

    async def scenario() -> None:
        async with aiosqlite.connect(path) as db:
            cur = await db.execute(
                "SELECT name FROM messenger.sqlite_master WHERE type='table' AND name LIKE 'collectible%'"
            )
            names = {str(row[0]) for row in await cur.fetchall()}
        assert {"collectibles", "collectible_listings", "collectible_auctions",
                "collectible_auction_bids", "collectible_transfers"} <= names
        # Повторная инициализация не падает и не дублирует владельцев.
        await init_messenger_storage(path)
        async with aiosqlite.connect(path) as db:
            cur = await db.execute("SELECT COUNT(*) FROM collectibles WHERE name='buyer'")
            assert int((await cur.fetchone())[0]) == 1

    run(scenario())
