"""Рынок коллекционных юзернеймов — наш собственный Fragment.

Официального API у fragment.com нет, поэтому WaysGram повторяет продуктовую
механику на своих правилах:

* владение именем хранится в таблице ``collectibles`` и всегда проверяется на
  сервере, а не по данным клиента;
* продажа идёт по фиксированной цене (``collectible_listings``) или аукционом
  (``collectible_auctions``) со заморозкой звёзд у ставки;
* покупка — одна транзакция ``BEGIN IMMEDIATE``: списание покупателю, выплата
  продавцу минус комиссия, смена владельца и запись в журнал экономики;
* после смены владельца имя заморожено на сутки, чтобы нельзя было сразу
  перепродать купленное (защита от накрутки);
* пополнение звёзд за TON — по адресу и ``memo`` вида ``wg:<telegram_id>``,
  зачисление идёт только по уникальному ``tx_hash`` (повторный перевод с тем же
  хешем второй раз не зачислится).

Модуль не знает про FastAPI-сессии: маршруты регистрирует
:func:`register_routes`, а бизнес-логику можно вызывать напрямую из тестов.
"""

from __future__ import annotations

import hmac
import logging
import os
import re
import secrets
import sqlite3
import time
from typing import Any

import aiosqlite
import db_split
from fastapi import APIRouter, Header, HTTPException, Query, Request

LOGGER = logging.getLogger(__name__)

# Комиссия сервиса с продажи и с аукциона.
COMMISSION_PERCENT = 5
# Сколько нельзя перепродать имя после смены владельца.
TRANSFER_FREEZE_SECONDS = 24 * 3600
# Аукцион: границы длительности и «снайп-защита» (ставка в последние 5 минут
# продлевает аукцион ещё на 5 минут).
AUCTION_MIN_HOURS = 1
AUCTION_MAX_HOURS = 168
AUCTION_SNIPE_WINDOW = 300
# Свободное имя продаёт сам сервис: чем короче, тем дороже.
SYSTEM_PRICE_BY_LENGTH = {1: 50_000, 2: 30_000, 3: 20_000, 4: 12_000,
                          5: 8_000, 6: 5_000, 7: 4_000}
SYSTEM_PRICE_DEFAULT = 3_000
# TON: минимальная сумма вывода и защита от слишком частых заявок.
TON_MIN_WITHDRAW_STARS = 500
TON_MAX_WITHDRAW_STARS = 500_000
TON_DEFAULT_STARS_PER_TON = 500
USERNAME_RE = re.compile(r"^[a-z][a-z0-9_]{2,31}$")
TON_ADDRESS_RE = re.compile(r"^(?:[A-Za-z0-9_-]{48}|-?\d+:[0-9a-fA-F]{64})$")
TON_MEMO_RE = re.compile(r"^wg:(\d{1,20})$")

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS collectibles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL DEFAULT 'username' CHECK(kind IN ('username','phone')),
    name TEXT NOT NULL COLLATE NOCASE UNIQUE,
    owner_telegram_id INTEGER,
    acquired_at INTEGER NOT NULL DEFAULT 0,
    transfer_frozen_until INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL DEFAULT 'system'
        CHECK(source IN ('system','account','sale','auction','claim'))
);
CREATE INDEX IF NOT EXISTS idx_collectibles_owner
    ON collectibles(owner_telegram_id, kind);

-- Preserve the account/group/channel that supplied a username so an unlisted name
-- can be restored to the same entity, but only while that entity still belongs to
-- the seller. A purchased name has no binding and appears as an additional username.
CREATE TABLE IF NOT EXISTS collectible_entity_bindings (
    collectible_id INTEGER PRIMARY KEY,
    entity_type TEXT NOT NULL CHECK(entity_type IN ('account','group','channel')),
    entity_id INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS collectible_listings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collectible_id INTEGER NOT NULL,
    seller_telegram_id INTEGER NOT NULL,
    price_stars INTEGER NOT NULL DEFAULT 0 CHECK(price_stars >= 0),
    price_grams INTEGER NOT NULL DEFAULT 0 CHECK(price_grams >= 0),
    currency TEXT NOT NULL DEFAULT 'stars' CHECK(currency IN ('stars','grams')),
    status TEXT NOT NULL DEFAULT 'active'
        CHECK(status IN ('active','sold','cancelled')),
    created_at INTEGER NOT NULL,
    sold_at INTEGER,
    CHECK((currency='stars' AND price_stars>0 AND price_grams=0)
       OR (currency='grams' AND price_grams>0 AND price_stars=0))
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_collectible_listings_active
    ON collectible_listings(collectible_id) WHERE status='active';

CREATE TABLE IF NOT EXISTS collectible_auctions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collectible_id INTEGER NOT NULL,
    seller_telegram_id INTEGER NOT NULL DEFAULT 0,
    min_bid_stars INTEGER NOT NULL CHECK(min_bid_stars > 0),
    current_bid_stars INTEGER NOT NULL DEFAULT 0,
    highest_bidder_telegram_id INTEGER,
    bids_count INTEGER NOT NULL DEFAULT 0,
    starts_at INTEGER NOT NULL,
    ends_at INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'active'
        CHECK(status IN ('active','sold','ended','cancelled')),
    winner_telegram_id INTEGER,
    final_price_stars INTEGER,
    settled_at INTEGER
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_collectible_auctions_active
    ON collectible_auctions(collectible_id) WHERE status='active';
CREATE INDEX IF NOT EXISTS idx_collectible_auctions_due
    ON collectible_auctions(status, ends_at);

CREATE TABLE IF NOT EXISTS collectible_auction_bids (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    auction_id INTEGER NOT NULL,
    bidder_telegram_id INTEGER NOT NULL,
    amount_stars INTEGER NOT NULL CHECK(amount_stars > 0),
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_collectible_auction_bids_auction
    ON collectible_auction_bids(auction_id, id DESC);

CREATE TABLE IF NOT EXISTS collectible_transfers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collectible_id INTEGER NOT NULL,
    from_telegram_id INTEGER,
    to_telegram_id INTEGER,
    price_stars INTEGER NOT NULL DEFAULT 0,
    commission_stars INTEGER NOT NULL DEFAULT 0,
    price_grams INTEGER NOT NULL DEFAULT 0,
    commission_grams INTEGER NOT NULL DEFAULT 0,
    currency TEXT NOT NULL DEFAULT 'stars' CHECK(currency IN ('stars','grams')),
    kind TEXT NOT NULL DEFAULT 'sale'
        CHECK(kind IN ('sale','auction','claim','system')),
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_collectible_transfers_item
    ON collectible_transfers(collectible_id, id DESC);

CREATE TABLE IF NOT EXISTS ton_deposits (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    telegram_id INTEGER NOT NULL,
    memo TEXT NOT NULL,
    tx_hash TEXT NOT NULL UNIQUE,
    amount_nanoton TEXT NOT NULL,
    stars_credited INTEGER NOT NULL,
    rate TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ton_deposits_user
    ON ton_deposits(telegram_id, id DESC);

CREATE TABLE IF NOT EXISTS ton_payouts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    telegram_id INTEGER NOT NULL,
    address TEXT NOT NULL,
    amount_nanoton TEXT NOT NULL,
    stars_spent INTEGER NOT NULL,
    rate TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK(status IN ('pending','sent','failed','cancelled')),
    tx_hash TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ton_payouts_status
    ON ton_payouts(status, id);
"""


class MarketError(Exception):
    """Ошибка рынка: HTTP-статус, машинный код и текст для клиента."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def _activate(db: Any) -> None:
    db.row_factory = aiosqlite.Row


def canonical_name(value: Any) -> str:
    """Приводит имя к канону и отбрасывает всё, что не похоже на юзернейм."""
    name = str(value or "").strip().lstrip("@").lower()
    if not USERNAME_RE.fullmatch(name):
        raise MarketError(
            400, "USERNAME_INVALID",
            "Username: 3–32 символа, латиница, цифры и _, первый символ — буква",
        )
    return name


def system_price(name: str) -> int:
    """Цена свободного имени: короче — дороже."""
    return SYSTEM_PRICE_BY_LENGTH.get(len(name), SYSTEM_PRICE_DEFAULT)


async def ensure_storage(db: Any) -> None:
    await db.executescript(db_split.qualify_ddl(SCHEMA_SQL))
    cur = await db.execute(f"PRAGMA {db_split.SCHEMA}.table_info(collectible_listings)")
    columns = {str(row[1]) for row in await cur.fetchall()}
    if "currency" not in columns:
        # Rebuild the old Stars-only table: the old CHECK(price_stars > 0) cannot
        # represent Gram listings without storing a misleading Stars value.
        await db.execute(f"DROP INDEX IF EXISTS {db_split.SCHEMA}.idx_collectible_listings_active")
        await db.execute(
            f"ALTER TABLE {db_split.SCHEMA}.collectible_listings "
            "RENAME TO collectible_listings_legacy"
        )
        await db.execute(f"""CREATE TABLE {db_split.SCHEMA}.collectible_listings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            collectible_id INTEGER NOT NULL,
            seller_telegram_id INTEGER NOT NULL,
            price_stars INTEGER NOT NULL DEFAULT 0 CHECK(price_stars >= 0),
            price_grams INTEGER NOT NULL DEFAULT 0 CHECK(price_grams >= 0),
            currency TEXT NOT NULL DEFAULT 'stars' CHECK(currency IN ('stars','grams')),
            status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','sold','cancelled')),
            created_at INTEGER NOT NULL,
            sold_at INTEGER,
            CHECK((currency='stars' AND price_stars>0 AND price_grams=0)
               OR (currency='grams' AND price_grams>0 AND price_stars=0))
        )""")
        await db.execute(
            f"""INSERT INTO {db_split.SCHEMA}.collectible_listings
               (id,collectible_id,seller_telegram_id,price_stars,price_grams,currency,status,created_at,sold_at)
               SELECT id,collectible_id,seller_telegram_id,price_stars,0,'stars',status,created_at,sold_at
               FROM {db_split.SCHEMA}.collectible_listings_legacy"""
        )
        await db.execute(f"DROP TABLE {db_split.SCHEMA}.collectible_listings_legacy")
    else:
        cur = await db.execute(f"PRAGMA {db_split.SCHEMA}.table_info(collectible_listings)")
        columns = {str(row[1]) for row in await cur.fetchall()}
        if "price_grams" not in columns:
            await db.execute(
                f"ALTER TABLE {db_split.SCHEMA}.collectible_listings "
                "ADD COLUMN price_grams INTEGER NOT NULL DEFAULT 0"
            )
    await db.execute(
        f"CREATE UNIQUE INDEX IF NOT EXISTS {db_split.SCHEMA}.idx_collectible_listings_active "
        "ON collectible_listings(collectible_id) WHERE status='active'"
    )
    cur = await db.execute(f"PRAGMA {db_split.SCHEMA}.table_info(collectible_transfers)")
    transfer_columns = {str(row[1]) for row in await cur.fetchall()}
    for column, definition in (
        ("price_grams", "INTEGER NOT NULL DEFAULT 0"),
        ("commission_grams", "INTEGER NOT NULL DEFAULT 0"),
        ("currency", "TEXT NOT NULL DEFAULT 'stars'"),
    ):
        if column not in transfer_columns:
            await db.execute(
                f"ALTER TABLE {db_split.SCHEMA}.collectible_transfers "
                f"ADD COLUMN {column} {definition}"
            )
    await db.commit()


async def seed_from_accounts(db: Any, now: int | None = None) -> int:
    """Заселяет справочник именами, которые уже используются аккаунтами.

    Имя, которое сейчас стоит на аккаунте, принадлежит этому аккаунту: иначе
    после первого запуска все существующие юзернеймы выглядели бы свободными и
    их можно было бы «купить» у самих себя.
    """
    _activate(db)
    stamp = int(now if now is not None else time.time())
    cur = await db.execute(
        "SELECT telegram_id,username,created_at FROM accounts WHERE LENGTH(username)>0"
    )
    rows = await cur.fetchall()
    seeded = 0
    for row in rows:
        name = str(row["username"] or "").strip().lstrip("@").lower()
        if not USERNAME_RE.fullmatch(name):
            continue
        await db.execute(
            "INSERT OR IGNORE INTO collectibles(kind,name,owner_telegram_id,acquired_at,source)"
            " VALUES('username',?,?,?,'account')",
            (name, int(row["telegram_id"]), int(row["created_at"] or stamp)),
        )
        # Строка могла появиться раньше как «свободная» — отдаём её владельцу.
        await db.execute(
            "UPDATE collectibles SET owner_telegram_id=?"
            " WHERE name=? COLLATE NOCASE AND owner_telegram_id IS NULL",
            (int(row["telegram_id"]), name),
        )
        await _bind_entity(db, name, "account", int(row["telegram_id"]))
        seeded += 1

    # Public group/channel usernames are also collectible assets. Seed them to the
    # owning account so the same secure listing flow can authorize the owner.
    entity_queries = (
        ("group", "SELECT id,username,owner_telegram_id,created_at FROM chat_groups"
         " WHERE COALESCE(username,'')<>''"),
        ("channel", "SELECT id,username,owner_telegram_id,created_at FROM channels"
         " WHERE COALESCE(is_public,1)=1 AND COALESCE(username,'')<>''"),
    )
    for entity_type, sql in entity_queries:
        try:
            cur = await db.execute(sql)
            entities = await cur.fetchall()
        except Exception:  # noqa: BLE001 - minimal test schemas may omit chat tables
            continue
        for entity in entities:
            name = str(entity["username"] or "").strip().lstrip("@").lower()
            owner_id = int(entity["owner_telegram_id"] or 0)
            if not owner_id or not USERNAME_RE.fullmatch(name):
                continue
            await db.execute(
                "INSERT OR IGNORE INTO collectibles(kind,name,owner_telegram_id,acquired_at,source)"
                " VALUES('username',?,?,?,'account')",
                (name, owner_id, int(entity["created_at"] or stamp)),
            )
            await db.execute(
                "UPDATE collectibles SET owner_telegram_id=?"
                " WHERE name=? COLLATE NOCASE AND owner_telegram_id IS NULL",
                (owner_id, name),
            )
            await _bind_entity(db, name, entity_type, int(entity["id"]))
            seeded += 1

    await db.commit()
    return seeded


async def _collectible(db: Any, name: str) -> aiosqlite.Row | None:
    cur = await db.execute(
        "SELECT id,name,kind,owner_telegram_id,acquired_at,transfer_frozen_until,source"
        " FROM collectibles WHERE name=? COLLATE NOCASE",
        (name,),
    )
    return await cur.fetchone()


async def _bind_entity(db: Any, name: str, entity_type: str, entity_id: int) -> None:
    collectible = await _collectible(db, name)
    if collectible is None or collectible["owner_telegram_id"] is None:
        return
    owner_id = int(collectible["owner_telegram_id"])
    if entity_type == "account":
        cur = await db.execute(
            "SELECT 1 FROM accounts WHERE telegram_id=? AND username=? COLLATE NOCASE",
            (owner_id, name),
        )
    elif entity_type == "group":
        cur = await db.execute(
            "SELECT 1 FROM chat_groups WHERE id=? AND owner_telegram_id=?"
            " AND username=? COLLATE NOCASE",
            (int(entity_id), owner_id, name),
        )
    else:
        cur = await db.execute(
            "SELECT 1 FROM channels WHERE id=? AND owner_telegram_id=?"
            " AND username=? COLLATE NOCASE AND COALESCE(is_public,1)=1",
            (int(entity_id), owner_id, name),
        )
    if await cur.fetchone() is None:
        return
    await db.execute(
        """INSERT INTO collectible_entity_bindings(collectible_id,entity_type,entity_id)
           VALUES(?,?,?)
           ON CONFLICT(collectible_id) DO UPDATE SET
               entity_type=excluded.entity_type,entity_id=excluded.entity_id""",
        (int(collectible["id"]), entity_type, int(entity_id)),
    )


async def _entity_binding(db: Any, collectible_id: int, owner_id: int) -> dict[str, Any] | None:
    cur = await db.execute(
        "SELECT entity_type,entity_id FROM collectible_entity_bindings WHERE collectible_id=?",
        (int(collectible_id),),
    )
    binding = await cur.fetchone()
    if binding is None:
        return None
    cur = await db.execute("SELECT name FROM collectibles WHERE id=?", (int(collectible_id),))
    collectible = await cur.fetchone()
    if collectible is None:
        return None
    name = str(collectible["name"] or "").strip().lstrip("@").casefold()
    entity_type, entity_id = str(binding["entity_type"]), int(binding["entity_id"])
    if entity_type == "account":
        cur = await db.execute(
            "SELECT display_name,username FROM accounts WHERE telegram_id=?",
            (entity_id,),
        )
        row = await cur.fetchone()
        current_name = str(row["username"] or "").strip().lstrip("@").casefold() if row else ""
        if row is None or entity_id != int(owner_id) or current_name != name:
            return None
        return {"type": "account", "id": entity_id,
                "title": str(row["display_name"] or row["username"] or "")}
    if entity_type == "group":
        cur = await db.execute(
            "SELECT title,owner_telegram_id,username FROM chat_groups WHERE id=?", (entity_id,)
        )
    else:
        cur = await db.execute(
            "SELECT title,owner_telegram_id,username,is_public FROM channels WHERE id=?", (entity_id,)
        )
    row = await cur.fetchone()
    current_name = str(row["username"] or "").strip().lstrip("@").casefold() if row else ""
    if (row is None or int(row["owner_telegram_id"] or 0) != int(owner_id)
            or current_name != name
            or (entity_type == "channel" and not bool(row["is_public"]))):
        return None
    return {"type": entity_type, "id": entity_id, "title": str(row["title"] or "")}


async def _username_entity(db: Any, name: str) -> dict[str, Any] | None:
    """The account, group or public channel that currently uses this username."""
    cur = await db.execute(
        "SELECT id,title,owner_telegram_id FROM chat_groups"
        " WHERE username=? COLLATE NOCASE LIMIT 1",
        (name,),
    )
    row = await cur.fetchone()
    if row is not None:
        return {
            "type": "group",
            "id": int(row["id"]),
            "title": str(row["title"] or ""),
            "owner_telegram_id": int(row["owner_telegram_id"] or 0),
        }
    cur = await db.execute(
        "SELECT id,title,owner_telegram_id FROM channels"
        " WHERE username=? COLLATE NOCASE AND COALESCE(is_public,1)=1 LIMIT 1",
        (name,),
    )
    row = await cur.fetchone()
    if row is not None:
        return {
            "type": "channel",
            "id": int(row["id"]),
            "title": str(row["title"] or ""),
            "owner_telegram_id": int(row["owner_telegram_id"] or 0),
        }
    cur = await db.execute(
        "SELECT telegram_id,display_name,username FROM accounts"
        " WHERE username=? COLLATE NOCASE LIMIT 1",
        (name,),
    )
    row = await cur.fetchone()
    if row is not None:
        return {
            "type": "account",
            "id": int(row["telegram_id"]),
            "title": str(row["display_name"] or row["username"] or ""),
            "owner_telegram_id": int(row["telegram_id"]),
        }
    return None


async def _ensure_collectible(db: Any, name: str, now: int) -> aiosqlite.Row:
    row = await _collectible(db, name)
    if row is not None:
        if row["owner_telegram_id"] is not None:
            entity = await _username_entity(db, name)
            if entity is not None and int(entity["owner_telegram_id"]) == int(row["owner_telegram_id"]):
                await _bind_entity(db, name, entity["type"], int(entity["id"]))
        return row
    cur = await db.execute(
        "SELECT telegram_id,created_at FROM accounts WHERE username=? COLLATE NOCASE",
        (name,),
    )
    account = await cur.fetchone()
    entity = None if account is not None else await _username_entity(db, name)
    owner = (int(account["telegram_id"]) if account is not None
             else (int(entity["owner_telegram_id"]) if entity else None))
    acquired = (int(account["created_at"] or now) if account is not None
                else (int(now) if entity else 0))
    await db.execute(
        "INSERT OR IGNORE INTO collectibles(kind,name,owner_telegram_id,acquired_at,source)"
        " VALUES('username',?,?,?,'account')",
        (name, owner, acquired),
    )
    row = await _collectible(db, name)
    if row is None:  # pragma: no cover - INSERT OR IGNORE с тем же именем
        raise MarketError(409, "COLLECTIBLE_CONFLICT", "Не удалось завести имя")
    if account is not None:
        await _bind_entity(db, name, "account", int(account["telegram_id"]))
    elif entity is not None:
        await _bind_entity(db, name, entity["type"], int(entity["id"]))
    return row


async def _public_account(db: Any, telegram_id: int | None) -> dict[str, Any] | None:
    if not telegram_id:
        return None
    cur = await db.execute(
        "SELECT telegram_id,display_name,username,avatar_media_id FROM accounts WHERE telegram_id=?",
        (int(telegram_id),),
    )
    row = await cur.fetchone()
    if row is None:
        return {"telegram_id": int(telegram_id), "display_name": "", "username": "",
                "avatar_media_id": ""}
    return {
        "telegram_id": int(row["telegram_id"]),
        "display_name": str(row["display_name"] or ""),
        "username": str(row["username"] or ""),
        "avatar_media_id": str(row["avatar_media_id"] or ""),
    }


async def _active_listing(db: Any, collectible_id: int) -> aiosqlite.Row | None:
    cur = await db.execute(
        "SELECT id,collectible_id,seller_telegram_id,price_stars,price_grams,currency,created_at"
        " FROM collectible_listings WHERE collectible_id=? AND status='active'",
        (int(collectible_id),),
    )
    return await cur.fetchone()


async def _active_auction(db: Any, collectible_id: int) -> aiosqlite.Row | None:
    cur = await db.execute(
        "SELECT id,collectible_id,seller_telegram_id,min_bid_stars,current_bid_stars,"
        "highest_bidder_telegram_id,bids_count,starts_at,ends_at"
        " FROM collectible_auctions WHERE collectible_id=? AND status='active'"
        " ORDER BY id DESC LIMIT 1",
        (int(collectible_id),),
    )
    return await cur.fetchone()


async def _last_auction(db: Any, collectible_id: int) -> aiosqlite.Row | None:
    cur = await db.execute(
        "SELECT id,status,final_price_stars,winner_telegram_id,ends_at,settled_at,"
        "current_bid_stars,min_bid_stars,bids_count"
        " FROM collectible_auctions WHERE collectible_id=? AND status<>'active'"
        " ORDER BY id DESC LIMIT 1",
        (int(collectible_id),),
    )
    return await cur.fetchone()


async def _history(db: Any, collectible_id: int, limit: int = 10) -> list[dict[str, Any]]:
    cur = await db.execute(
        "SELECT from_telegram_id,to_telegram_id,price_stars,commission_stars,"
        "price_grams,commission_grams,currency,kind,created_at"
        " FROM collectible_transfers WHERE collectible_id=? ORDER BY id DESC LIMIT ?",
        (int(collectible_id), int(limit)),
    )
    return [dict(row) for row in await cur.fetchall()]


async def _latest_collectible_purchase(
    db: Any, collectible_id: int, owner_id: int,
) -> aiosqlite.Row | None:
    """Return the last paid transfer that made this account the current owner."""
    cur = await db.execute(
        "SELECT price_stars,price_grams,currency,kind,created_at"
        " FROM collectible_transfers WHERE collectible_id=? AND to_telegram_id=?"
        " AND kind IN ('sale','auction','claim') ORDER BY id DESC LIMIT 1",
        (int(collectible_id), int(owner_id)),
    )
    return await cur.fetchone()


async def _balance(db: Any, telegram_id: int) -> int:
    cur = await db.execute("SELECT stars FROM accounts WHERE telegram_id=?", (int(telegram_id),))
    row = await cur.fetchone()
    if row is None:
        raise MarketError(404, "ACCOUNT_NOT_FOUND", "Аккаунт не найден")
    return int(row["stars"])


async def _grams_balance(db: Any, telegram_id: int) -> int:
    cur = await db.execute("SELECT grams FROM accounts WHERE telegram_id=?", (int(telegram_id),))
    row = await cur.fetchone()
    if row is None:
        raise MarketError(404, "ACCOUNT_NOT_FOUND", "Аккаунт не найден")
    return int(row["grams"])


async def _add_stars(db: Any, telegram_id: int, delta: int, reason: str, now: int) -> int:
    """Меняет баланс и пишет проводку в журнал экономики.

    Отрицательный баланс невозможен: ``UPDATE`` с условием ``stars+delta>=0``
    либо списывает, либо не трогает строку, и тогда это ошибка «недостаточно
    звёзд» — гонка двух одновременных покупок не уводит счёт в минус.
    """
    cur = await db.execute(
        "UPDATE accounts SET stars=stars+?,updated_at=? WHERE telegram_id=? AND stars>=?",
        (int(delta), int(now), int(telegram_id), -int(delta)),
    )
    if cur.rowcount != 1:
        if await _account_exists(db, telegram_id):
            raise MarketError(409, "NOT_ENOUGH_STARS", "Недостаточно звёзд")
        raise MarketError(404, "ACCOUNT_NOT_FOUND", "Аккаунт не найден")
    balance = await _balance(db, telegram_id)
    await db.execute(
        "INSERT INTO economy_transactions(telegram_id,asset,amount,balance_after,reason,"
        "admin_id,created_at) VALUES(?,'stars',?,?,?,NULL,?)",
        (int(telegram_id), int(delta), balance, reason, int(now)),
    )
    return balance


async def _add_grams(db: Any, telegram_id: int, delta: int, reason: str, now: int) -> int:
    """Изменяет Gram-баланс и записывает экономическую проводку атомарно."""
    cur = await db.execute(
        "UPDATE accounts SET grams=grams+?,updated_at=? WHERE telegram_id=? AND grams>=?",
        (int(delta), int(now), int(telegram_id), -int(delta)),
    )
    if cur.rowcount != 1:
        if await _account_exists(db, telegram_id):
            raise MarketError(409, "NOT_ENOUGH_GRAMS", "Недостаточно Gram")
        raise MarketError(404, "ACCOUNT_NOT_FOUND", "Аккаунт не найден")
    balance = await _grams_balance(db, telegram_id)
    await db.execute(
        "INSERT INTO economy_transactions(telegram_id,asset,amount,balance_after,reason,"
        "admin_id,created_at) VALUES(?,'grams',?,?,?,NULL,?)",
        (int(telegram_id), int(delta), balance, reason, int(now)),
    )
    return balance


async def _account_exists(db: Any, telegram_id: int) -> bool:
    cur = await db.execute("SELECT 1 FROM accounts WHERE telegram_id=?", (int(telegram_id),))
    return await cur.fetchone() is not None


def commission_percent() -> int:
    """Комиссия сервиса: процент по умолчанию, переопределяется окружением."""
    try:
        value = int(str(os.getenv("WAYSGRAM_FRAGMENT_COMMISSION_PERCENT", "")).strip())
    except ValueError:
        value = COMMISSION_PERCENT
    return max(0, min(50, value))


def _commission(price: int) -> int:
    return max(0, int(price) * commission_percent() // 100)


async def _transfer_ownership(
    db: Any, name: str, seller_id: int | None, buyer_id: int, now: int, *, source: str
) -> None:
    """Передаёт именно актив Fragment, не подменяя основной username аккаунта."""
    collectible = await _collectible(db, name)
    if collectible is None:
        raise MarketError(409, "COLLECTIBLE_CONFLICT", "Имя не найдено")
    # После продажи старый аккаунт/канал/группа больше не может автоматически
    # получить имя обратно при снятии лота. У покупателя это будет дополнительный ник.
    await db.execute(
        "DELETE FROM collectible_entity_bindings WHERE collectible_id=?",
        (int(collectible["id"]),),
    )
    await db.execute(
        "UPDATE collectibles SET owner_telegram_id=?,acquired_at=?,transfer_frozen_until=?,"
        "source=? WHERE name=? COLLATE NOCASE",
        (int(buyer_id), int(now), int(now) + TRANSFER_FREEZE_SECONDS, source, name),
    )


async def _record_transfer(
    db: Any, collectible_id: int, seller_id: int | None, buyer_id: int,
    price: int, commission: int, kind: str, now: int, currency: str = "stars",
) -> None:
    asset = "grams" if str(currency).lower() == "grams" else "stars"
    await db.execute(
        "INSERT INTO collectible_transfers(collectible_id,from_telegram_id,to_telegram_id,"
        "price_stars,commission_stars,price_grams,commission_grams,currency,kind,created_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?)",
        (int(collectible_id), seller_id, int(buyer_id),
         int(price) if asset == "stars" else 0,
         int(commission) if asset == "stars" else 0,
         int(price) if asset == "grams" else 0,
         int(commission) if asset == "grams" else 0,
         asset, kind, int(now)),
    )


async def card(db: Any, name: str, viewer_id: int | None = None,
               now: int | None = None, share_url: str = "",
               notify_sale: Any = None) -> dict[str, Any]:
    """Карточка имени: владелец, цена, аукцион, заморозка и история."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    await settle_due(db, stamp, notify_sale)
    row = await _ensure_collectible(db, key, stamp)
    collectible_id = int(row["id"])
    owner_id = row["owner_telegram_id"]
    owner = await _public_account(db, int(owner_id) if owner_id else None)
    listing = await _active_listing(db, collectible_id)
    auction = await _active_auction(db, collectible_id)
    last = await _last_auction(db, collectible_id)
    purchase = (await _latest_collectible_purchase(db, collectible_id, int(owner_id))
                if owner_id is not None else None)
    frozen_until = int(row["transfer_frozen_until"] or 0)
    entity = None
    if owner_id is not None:
        entity = await _entity_binding(db, collectible_id, int(owner_id))
        if entity is None:
            current_entity = await _username_entity(db, key)
            if current_entity is not None and int(current_entity["owner_telegram_id"]) == int(owner_id):
                entity = current_entity
                await _bind_entity(db, key, entity["type"], int(entity["id"]))
    # Имя не из рынка остаётся занятым, в том числе если его использует аккаунт.
    reserved = owner_id is None and await _username_used_outside_market(db, key)
    result: dict[str, Any] = {
        "name": key,
        "kind": str(row["kind"]),
        "owner": owner,
        "entity_type": entity["type"] if entity else "",
        "entity_id": int(entity["id"]) if entity else 0,
        "entity_title": str(entity["title"]) if entity else "",
        "mine": bool(owner_id and viewer_id and int(owner_id) == int(viewer_id)),
        "free": owner_id is None and not reserved,
        "reserved": reserved,
        "system_price_stars": system_price(key),
        # Keep the active asking price separate from what the current owner paid.
        # Fragment's native collectible sheet uses purchase_* to describe acquisition.
        "purchase_price_stars": int(purchase["price_stars"] or 0) if purchase is not None else 0,
        "purchase_price_grams": int(purchase["price_grams"] or 0) if purchase is not None else 0,
        "purchase_currency": str(purchase["currency"] or "") if purchase is not None else "",
        "price_stars": (int(listing["price_stars"] or 0)
                        if listing is not None and str(listing["currency"]) == "stars"
                        else (0 if owner_id is not None or reserved else system_price(key))),
        "price_grams": (int(listing["price_grams"] or 0)
                        if listing is not None and str(listing["currency"]) == "grams" else 0),
        "currency": str(listing["currency"]) if listing is not None else "",
        "price": (int(listing["price_grams"] or 0) if listing is not None
                  and str(listing["currency"]) == "grams" else
                  (int(listing["price_stars"] or 0) if listing is not None else 0)),
        "for_sale": listing is not None,
        "listing_id": int(listing["id"]) if listing is not None else 0,
        "auction": None,
        "last_auction": None,
        "frozen_until": frozen_until,
        "frozen": frozen_until > stamp,
        "transferable": bool(owner_id and viewer_id and int(owner_id) == int(viewer_id)
                             and frozen_until <= stamp and auction is None),
        "purchase_date": int(row["acquired_at"] or 0),
        "source": str(row["source"]),
        "share_url": share_url or "",
        "commission_percent": commission_percent(),
        "history": await _history(db, collectible_id),
    }
    if auction is not None:
        minimum = max(int(auction["min_bid_stars"]), int(auction["current_bid_stars"]) + 1)
        result["auction"] = {
            "id": int(auction["id"]),
            "seller_telegram_id": int(auction["seller_telegram_id"]),
            "min_bid_stars": int(auction["min_bid_stars"]),
            "current_bid_stars": int(auction["current_bid_stars"]),
            "minimum_next_bid_stars": minimum,
            "highest_bidder_telegram_id": auction["highest_bidder_telegram_id"],
            "bids_count": int(auction["bids_count"]),
            "ends_at": int(auction["ends_at"]),
            "snipe_window_seconds": AUCTION_SNIPE_WINDOW,
        }
    if last is not None:
        result["last_auction"] = {
            "id": int(last["id"]),
            "status": str(last["status"]),
            "final_price_stars": int(last["final_price_stars"] or 0),
            "winner_telegram_id": last["winner_telegram_id"],
            "settled_at": int(last["settled_at"] or 0),
        }
    return result


async def search(
    db: Any, viewer_id: int, *, section: str = "market", query: str = "",
    limit: int = 50, now: int | None = None, notify_sale: Any = None,
) -> dict[str, Any]:
    """Списки рынка: что продаётся, что на аукционе, что уже моё."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    await settle_due(db, stamp, notify_sale)
    await seed_from_accounts(db, stamp)
    prefix = str(query or "").strip().lstrip("@").lower()
    items: list[dict[str, Any]] = []
    if section in ("market", "for_sale"):
        sql = (
            "SELECT c.id,c.name,c.owner_telegram_id,l.price_stars,l.price_grams,l.currency,"
            "l.id AS listing_id,a.telegram_id AS account_id,a.display_name,a.username,a.avatar_media_id"
            " FROM collectible_listings l"
            " JOIN collectibles c ON c.id=l.collectible_id"
            " LEFT JOIN accounts a ON a.telegram_id=c.owner_telegram_id"
            " WHERE l.status='active'"
        )
        params: list[Any] = []
        if prefix:
            sql += " AND c.name LIKE ?"
            params.append(prefix + "%")
        sql += " ORDER BY l.currency,CASE WHEN l.currency='stars' THEN l.price_stars ELSE l.price_grams END,c.name LIMIT ?"
        params.append(int(limit))
        cur = await db.execute(sql, params)
        for row in await cur.fetchall():
            owner_id = int(row["owner_telegram_id"] or 0)
            entity = (await _entity_binding(db, int(row["id"]), owner_id)
                      if owner_id else None)
            items.append({
                "name": str(row["name"]),
                "kind": "sale",
                "price_stars": int(row["price_stars"] or 0),
                "price_grams": int(row["price_grams"] or 0),
                "currency": str(row["currency"] or "stars"),
                "price": int(row["price_grams"] or 0) if str(row["currency"] or "stars") == "grams"
                         else int(row["price_stars"] or 0),
                "listing_id": int(row["listing_id"] or 0),
                "owner": await _public_account(db, row["owner_telegram_id"]),
                "mine": bool(owner_id and owner_id == int(viewer_id)),
                "entity_type": entity["type"] if entity else "",
                "entity_id": int(entity["id"]) if entity else 0,
                "entity_title": str(entity["title"]) if entity else "",
                "auction": None,
            })
    if section in ("market", "auctions"):
        sql = (
            "SELECT c.name,a2.id AS auction_id,a2.min_bid_stars,a2.current_bid_stars,"
            " a2.bids_count,a2.ends_at,a2.seller_telegram_id"
            " FROM collectible_auctions a2"
            " JOIN collectibles c ON c.id=a2.collectible_id"
            " WHERE a2.status='active'"
        )
        params = []
        if prefix:
            sql += " AND c.name LIKE ?"
            params.append(prefix + "%")
        sql += " ORDER BY a2.ends_at LIMIT ?"
        params.append(int(limit))
        cur = await db.execute(sql, params)
        for row in await cur.fetchall():
            items.append({
                "name": str(row["name"]),
                "kind": "auction",
                "price_stars": max(int(row["min_bid_stars"]), int(row["current_bid_stars"])),
                "owner": await _public_account(db, row["seller_telegram_id"]),
                "mine": int(row["seller_telegram_id"]) == int(viewer_id),
                "auction": {
                    "id": int(row["auction_id"]),
                    "min_bid_stars": int(row["min_bid_stars"]),
                    "current_bid_stars": int(row["current_bid_stars"]),
                    "minimum_next_bid_stars": max(int(row["min_bid_stars"]),
                                                  int(row["current_bid_stars"]) + 1),
                    "bids_count": int(row["bids_count"]),
                    "ends_at": int(row["ends_at"]),
                },
            })
    if section == "mine":
        sql = (
            "SELECT c.id,c.name,c.acquired_at,c.transfer_frozen_until,"
            " l.price_stars,l.price_grams,l.currency,l.id AS listing_id,"
            " a2.id AS auction_id,a2.current_bid_stars,a2.min_bid_stars,a2.ends_at"
            " FROM collectibles c"
            " LEFT JOIN collectible_listings l ON l.collectible_id=c.id AND l.status='active'"
            " LEFT JOIN collectible_auctions a2 ON a2.collectible_id=c.id AND a2.status='active'"
            " WHERE c.owner_telegram_id=?"
        )
        params = [int(viewer_id)]
        if prefix:
            sql += " AND c.name LIKE ?"
            params.append(prefix + "%")
        sql += " ORDER BY c.acquired_at DESC, c.name LIMIT ?"
        params.append(int(limit))
        cur = await db.execute(sql, params)
        for row in await cur.fetchall():
            auction = None
            if row["auction_id"] is not None:
                auction = {
                    "id": int(row["auction_id"]),
                    "min_bid_stars": int(row["min_bid_stars"] or 0),
                    "current_bid_stars": int(row["current_bid_stars"] or 0),
                    "minimum_next_bid_stars": int(row["min_bid_stars"] or 0),
                    "bids_count": 0,
                    "ends_at": int(row["ends_at"] or 0),
                }
            name = str(row["name"])
            entity = await _entity_binding(db, int(row["id"]), int(viewer_id))
            if entity is None:
                current_entity = await _username_entity(db, name)
                if current_entity is not None and int(current_entity["owner_telegram_id"]) == int(viewer_id):
                    entity = current_entity
                    await _bind_entity(db, name, entity["type"], int(entity["id"]))
            items.append({
                "name": name,
                "kind": "auction" if auction else ("sale" if row["listing_id"] else "owned"),
                "price_stars": int(row["price_stars"] or 0) if row["listing_id"] else 0,
                "price_grams": int(row["price_grams"] or 0) if row["listing_id"] else 0,
                "currency": str(row["currency"] or "stars") if row["listing_id"] else "",
                "price": (int(row["price_grams"] or 0) if row["listing_id"]
                          and str(row["currency"] or "stars") == "grams" else
                          (int(row["price_stars"] or 0) if row["listing_id"] else 0)),
                "listing_id": int(row["listing_id"] or 0),
                "owner": await _public_account(db, viewer_id),
                "mine": True,
                "auction": auction,
                "entity_type": entity["type"] if entity else "",
                "entity_id": int(entity["id"]) if entity else 0,
                "entity_title": str(entity["title"]) if entity else "",
                "frozen_until": int(row["transfer_frozen_until"] or 0),
                "frozen": int(row["transfer_frozen_until"] or 0) > stamp,
            })
    return {
        "section": section,
        "query": prefix,
        "items": items,
        "stars_balance": await _balance(db, viewer_id),
        "commission_percent": commission_percent(),
        "freeze_seconds": TRANSFER_FREEZE_SECONDS,
    }


async def _username_occupied_by_other(
    db: Any, name: str, owner_id: int, entity_type: str = "", entity_id: int = 0,
) -> bool:
    for table in ("accounts", "chat_groups", "channels", "bots"):
        try:
            if table == "accounts":
                cur = await db.execute(
                    "SELECT 1 FROM accounts WHERE username=? COLLATE NOCASE"
                    " AND telegram_id<>? LIMIT 1",
                    (name, int(owner_id)),
                )
            elif table == "chat_groups":
                cur = await db.execute(
                    "SELECT 1 FROM chat_groups WHERE username=? COLLATE NOCASE"
                    " AND id<>? LIMIT 1",
                    (name, int(entity_id) if entity_type == "group" else -1),
                )
            elif table == "channels":
                cur = await db.execute(
                    "SELECT 1 FROM channels WHERE username=? COLLATE NOCASE"
                    " AND id<>? AND COALESCE(is_public,1)=1 LIMIT 1",
                    (name, int(entity_id) if entity_type == "channel" else -1),
                )
            else:
                cur = await db.execute(
                    "SELECT 1 FROM bots WHERE username=? COLLATE NOCASE LIMIT 1", (name,)
                )
        except Exception:  # noqa: BLE001 - optional entity tables vary in small test schemas
            continue
        if await cur.fetchone() is not None:
            return True
    return False


async def _detach_entity_username(
    db: Any, collectible_id: int, name: str, owner_id: int, now: int,
) -> None:
    binding = await _entity_binding(db, collectible_id, owner_id)
    if binding is None:
        return
    entity_type = str(binding["type"])
    entity_id = int(binding["id"])
    if entity_type == "account":
        await db.execute(
            "UPDATE accounts SET username='',updated_at=?"
            " WHERE telegram_id=? AND username=? COLLATE NOCASE",
            (int(now), entity_id, name),
        )
    elif entity_type == "group":
        await db.execute(
            "UPDATE chat_groups SET username=NULL WHERE id=? AND owner_telegram_id=?"
            " AND username=? COLLATE NOCASE",
            (entity_id, int(owner_id), name),
        )
    elif entity_type == "channel":
        # channels.username is NOT NULL UNIQUE; private channels use a unique private_* slug.
        await db.execute(
            "UPDATE channels SET username=?,is_public=0 WHERE id=? AND owner_telegram_id=?"
            " AND username=? COLLATE NOCASE AND COALESCE(is_public,1)=1",
            ("private_" + secrets.token_hex(16), entity_id, int(owner_id), name),
        )


async def _restore_entity_username(
    db: Any, collectible_id: int, name: str, owner_id: int, now: int,
) -> None:
    cur = await db.execute(
        "SELECT entity_type,entity_id FROM collectible_entity_bindings WHERE collectible_id=?",
        (int(collectible_id),),
    )
    binding = await cur.fetchone()
    if binding is None:
        return
    entity_type, entity_id = str(binding["entity_type"]), int(binding["entity_id"])
    if await _username_occupied_by_other(db, name, owner_id, entity_type, entity_id):
        return
    if entity_type == "account":
        await db.execute(
            "UPDATE accounts SET username=?,updated_at=? WHERE telegram_id=? AND username=''",
            (name, int(now), entity_id),
        )
    elif entity_type == "group":
        await db.execute(
            "UPDATE chat_groups SET username=? WHERE id=? AND owner_telegram_id=?"
            " AND (username IS NULL OR username='')",
            (name, entity_id, int(owner_id)),
        )
    elif entity_type == "channel":
        await db.execute(
            "UPDATE channels SET username=?,is_public=1 WHERE id=? AND owner_telegram_id=?"
            " AND is_public=0 AND username LIKE 'private_%'",
            (name, entity_id, int(owner_id)),
        )


async def put_listing(db: Any, telegram_id: int, name: str, price: int,
                      now: int | None = None, notify_sale: Any = None,
                      currency: str = "stars") -> dict[str, Any]:
    """Выставляет имя на продажу. Продавец — только текущий владелец из базы."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    asset = str(currency or "stars").strip().lower()
    if asset not in {"stars", "grams", "gram"}:
        raise MarketError(400, "CURRENCY_INVALID", "Поддерживаются только Stars и Gram")
    if asset == "gram":
        asset = "grams"
    if int(price) <= 0:
        raise MarketError(400, "PRICE_INVALID", "Цена должна быть больше нуля")
    if int(price) > 10_000_000:
        raise MarketError(400, "PRICE_INVALID", "Слишком большая цена")
    row = await _ensure_collectible(db, key, stamp)
    owner_id = row["owner_telegram_id"]
    if not owner_id:
        raise MarketError(403, "NOT_OWNER", "Имя вам не принадлежит")
    if int(owner_id) != int(telegram_id):
        raise MarketError(403, "NOT_OWNER", "Имя вам не принадлежит")
    if int(row["transfer_frozen_until"] or 0) > stamp:
        raise MarketError(423, "TRANSFER_FROZEN", "Имя заморожено после смены владельца")
    if await _active_auction(db, int(row["id"])) is not None:
        raise MarketError(409, "AUCTION_ACTIVE", "Сначала закончите аукцион")
    listing = await _active_listing(db, int(row["id"]))
    stars_price = int(price) if asset == "stars" else 0
    grams_price = int(price) if asset == "grams" else 0
    if listing is not None:
        await db.execute(
            "UPDATE collectible_listings SET price_stars=?,price_grams=?,currency=? WHERE id=?",
            (stars_price, grams_price, asset, int(listing["id"])),
        )
    else:
        await db.execute(
            "INSERT INTO collectible_listings(collectible_id,seller_telegram_id,price_stars,"
            "price_grams,currency,status,created_at) VALUES(?,?,?,?,?,'active',?)",
            (int(row["id"]), int(telegram_id), stars_price, grams_price, asset, stamp),
        )
    await _detach_entity_username(db, int(row["id"]), key, int(telegram_id), stamp)
    await db.commit()
    return await card(db, key, telegram_id, stamp, notify_sale=notify_sale)


async def unlist(db: Any, telegram_id: int, name: str, now: int | None = None,
                 notify_sale: Any = None) -> dict[str, Any]:
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    row = await _ensure_collectible(db, key, stamp)
    if not row["owner_telegram_id"] or int(row["owner_telegram_id"]) != int(telegram_id):
        raise MarketError(403, "NOT_OWNER", "Имя вам не принадлежит")
    cur = await db.execute(
        "UPDATE collectible_listings SET status='cancelled' WHERE collectible_id=? AND status='active'",
        (int(row["id"]),),
    )
    if cur.rowcount == 0:
        raise MarketError(404, "LISTING_NOT_FOUND", "Имя не выставлено на продажу")
    await _restore_entity_username(db, int(row["id"]), key, int(telegram_id), stamp)
    await db.commit()
    return await card(db, key, telegram_id, stamp, notify_sale=notify_sale)


async def buy(
    db: Any, telegram_id: int, name: str, now: int | None = None,
    idempotent_reason: str = "", *, commit: bool = True, notify_sale: Any = None,
) -> dict[str, Any]:
    """Покупка по фикс-цене: списание, выплата продавцу минус комиссия, передача."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    await settle_due(db, stamp, notify_sale)
    row = await _ensure_collectible(db, key, stamp)
    listing = await _active_listing(db, int(row["id"]))
    if listing is None:
        raise MarketError(409, "NOT_FOR_SALE", "Имя не продаётся")
    seller_id = int(listing["seller_telegram_id"])
    if seller_id == int(telegram_id):
        raise MarketError(409, "OWN_LISTING", "Это ваше имя")
    currency = str(listing["currency"] or "stars")
    price = int(listing["price_grams"] or 0) if currency == "grams" else int(listing["price_stars"] or 0)
    commission = _commission(price)
    payout = price - commission
    add_balance = _add_grams if currency == "grams" else _add_stars
    await add_balance(db, int(telegram_id), -price,
                      f"username_purchase:{int(listing['id'])}", stamp)
    await add_balance(db, seller_id, payout,
                      f"username_sale:{int(listing['id'])}", stamp)
    await _record_transfer(db, int(row["id"]), seller_id, int(telegram_id),
                           price, commission, "sale", stamp, currency=currency)
    await db.execute(
        "UPDATE collectible_listings SET status='sold',sold_at=? WHERE id=? AND status='active'",
        (stamp, int(listing["id"])),
    )
    await _transfer_ownership(db, key, seller_id, int(telegram_id), stamp, source="sale")
    result = await card(db, key, telegram_id, stamp, notify_sale=notify_sale)
    result["seller_id"] = seller_id
    result["buyer_id"] = int(telegram_id)
    result["sale_price_stars"] = price if currency == "stars" else 0
    result["payout_stars"] = payout if currency == "stars" else 0
    result["sale_price_grams"] = price if currency == "grams" else 0
    result["payout_grams"] = payout if currency == "grams" else 0
    result["sale_currency"] = currency
    if notify_sale is not None and currency == "stars":
        await notify_sale(db, seller_id, key, payout, int(telegram_id), stamp)
    if commit:
        await db.commit()
    return result


async def claim(db: Any, telegram_id: int, name: str, now: int | None = None,
                notify_sale: Any = None) -> dict[str, Any]:
    """Покупает свободное имя у сервиса по цене из таблицы длин."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    row = await _ensure_collectible(db, key, stamp)
    if row["owner_telegram_id"]:
        raise MarketError(409, "USERNAME_TAKEN", "Юзернейм уже занят")
    cur = await db.execute(
        "SELECT telegram_id FROM accounts WHERE username=? COLLATE NOCASE", (key,)
    )
    holder = await cur.fetchone()
    if holder is not None:
        raise MarketError(409, "USERNAME_TAKEN", "Юзернейм уже занят")
    for table in ("chat_groups", "channels", "bots"):
        try:
            cur = await db.execute(
                f"SELECT 1 FROM {table} WHERE username=? COLLATE NOCASE LIMIT 1", (key,)
            )
        except Exception:  # noqa: BLE001 - в урезанных схемах этих таблиц может не быть
            continue
        if await cur.fetchone() is not None:
            raise MarketError(409, "USERNAME_TAKEN", "Юзернейм уже занят")
    price = system_price(key)
    await _add_stars(db, int(telegram_id), -price, f"username_claim:{int(row['id'])}", stamp)
    await _record_transfer(db, int(row["id"]), None, int(telegram_id), price,
                           price, "claim", stamp)
    await _transfer_ownership(db, key, None, int(telegram_id), stamp, source="claim")
    await db.commit()
    return await card(db, key, telegram_id, stamp, notify_sale=notify_sale)


async def start_auction(db: Any, telegram_id: int, name: str, min_bid: int,
                        hours: int, now: int | None = None,
                        notify_sale: Any = None) -> dict[str, Any]:
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    if int(min_bid) <= 0:
        raise MarketError(400, "PRICE_INVALID", "Минимальная ставка должна быть больше нуля")
    duration = max(AUCTION_MIN_HOURS, min(AUCTION_MAX_HOURS, int(hours or 0)))
    row = await _ensure_collectible(db, key, stamp)
    if not row["owner_telegram_id"] or int(row["owner_telegram_id"]) != int(telegram_id):
        raise MarketError(403, "NOT_OWNER", "Имя вам не принадлежит")
    if int(row["transfer_frozen_until"] or 0) > stamp:
        raise MarketError(423, "TRANSFER_FROZEN", "Имя заморожено после смены владельца")
    if await _active_listing(db, int(row["id"])) is not None:
        raise MarketError(409, "LISTING_ACTIVE", "Сначала снимите имя с продажи")
    if await _active_auction(db, int(row["id"])) is not None:
        raise MarketError(409, "AUCTION_ACTIVE", "Аукцион уже идёт")
    ends_at = stamp + duration * 3600
    await db.execute(
        "INSERT INTO collectible_auctions(collectible_id,seller_telegram_id,min_bid_stars,"
        "current_bid_stars,bids_count,starts_at,ends_at,status)"
        " VALUES(?,?,?,0,0,?,?,'active')",
        (int(row["id"]), int(telegram_id), int(min_bid), stamp, ends_at),
    )
    await db.commit()
    return await card(db, key, telegram_id, stamp, notify_sale=notify_sale)


async def cancel_auction(db: Any, telegram_id: int, name: str,
                         now: int | None = None, notify_sale: Any = None) -> dict[str, Any]:
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    row = await _ensure_collectible(db, key, stamp)
    auction = await _active_auction(db, int(row["id"]))
    if auction is None:
        raise MarketError(404, "AUCTION_NOT_FOUND", "Аукцион не найден")
    if int(auction["seller_telegram_id"]) != int(telegram_id):
        raise MarketError(403, "NOT_OWNER", "Это не ваш аукцион")
    bidder = auction["highest_bidder_telegram_id"]
    held = int(auction["current_bid_stars"])
    if bidder and held > 0:
        await _add_stars(db, int(bidder), held,
                         f"username_auction_refund:{int(auction['id'])}", stamp)
    await db.execute(
        "UPDATE collectible_auctions SET status='cancelled',settled_at=? WHERE id=? AND status='active'",
        (stamp, int(auction["id"])),
    )
    await db.commit()
    return await card(db, key, telegram_id, stamp, notify_sale=notify_sale)


async def bids(db: Any, name: str, limit: int = 30) -> list[dict[str, Any]]:
    _activate(db)
    key = canonical_name(name)
    row = await _ensure_collectible(db, key, int(time.time()))
    cur = await db.execute(
        "SELECT b.amount_stars,b.created_at,b.bidder_telegram_id,a.display_name,a.username"
        " FROM collectible_auction_bids b"
        " JOIN collectible_auctions ac ON ac.id=b.auction_id AND ac.collectible_id=?"
        " LEFT JOIN accounts a ON a.telegram_id=b.bidder_telegram_id"
        " ORDER BY b.id DESC LIMIT ?",
        (int(row["id"]), int(limit)),
    )
    return [
        {
            "amount_stars": int(row["amount_stars"]),
            "created_at": int(row["created_at"]),
            "bidder": await _public_account(db, row["bidder_telegram_id"]),
        }
        for row in await cur.fetchall()
    ]


async def place_bid(db: Any, telegram_id: int, name: str, amount: int,
                    now: int | None = None, notify_sale: Any = None) -> dict[str, Any]:
    """Ставка холдит звёзды до конца аукциона; перебитая ставка возвращается."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    await settle_due(db, stamp, notify_sale)
    row = await _ensure_collectible(db, key, stamp)
    auction = await _active_auction(db, int(row["id"]))
    if auction is None:
        raise MarketError(404, "AUCTION_NOT_FOUND", "Аукцион не найден")
    auction_id = int(auction["id"])
    if int(auction["ends_at"]) <= stamp:
        raise MarketError(409, "AUCTION_ENDED", "Аукцион уже закончился")
    if int(auction["seller_telegram_id"]) == int(telegram_id):
        raise MarketError(409, "OWN_AUCTION", "Своя же ставка не нужна")
    top = int(auction["current_bid_stars"])
    minimum = max(int(auction["min_bid_stars"]), top + 1)
    if int(amount) < minimum:
        raise MarketError(409, "BID_TOO_LOW", f"Минимальная ставка — ⭐{minimum}")
    previous = auction["highest_bidder_telegram_id"]
    if previous is not None and top > 0:
        await _add_stars(db, int(previous), top,
                         f"username_auction_outbid:{auction_id}", stamp)
    await _add_stars(db, int(telegram_id), -int(amount),
                     f"username_auction_hold:{auction_id}", stamp)
    await db.execute(
        "INSERT INTO collectible_auction_bids(auction_id,bidder_telegram_id,amount_stars,created_at)"
        " VALUES(?,?,?,?)",
        (auction_id, int(telegram_id), int(amount), stamp),
    )
    ends_at = int(auction["ends_at"])
    if ends_at - stamp <= AUCTION_SNIPE_WINDOW:
        # Снайп-защита: поздняя ставка не обрывает торги, а продлевает их.
        ends_at = stamp + AUCTION_SNIPE_WINDOW
    await db.execute(
        "UPDATE collectible_auctions SET current_bid_stars=?,highest_bidder_telegram_id=?,"
        "bids_count=bids_count+1,ends_at=? WHERE id=? AND status='active'",
        (int(amount), int(telegram_id), ends_at, auction_id),
    )
    await db.commit()
    return {
        "status": "accepted",
        "name": key,
        "auction_id": auction_id,
        "bid_stars": int(amount),
        "minimum_next_bid_stars": int(amount) + 1,
        "ends_at": ends_at,
        "stars_balance": await _balance(db, telegram_id),
    }


async def settle_due(db: Any, now: int | None = None,
                     notify_sale: Any = None) -> list[dict[str, Any]]:
    """Закрывает истёкшие аукционы. Вызывается и воркером, и лениво — при чтении.

    Расчёт живёт в базе, а не в памяти: рестарт процесса ничего не теряет.
    If supplied, notify_sale records the seller's post-commission Stars transfer
    in the same transaction as the auction settlement.
    """
    _activate(db)
    stamp = int(now if now is not None else time.time())
    cur = await db.execute(
        "SELECT id,collectible_id,seller_telegram_id,current_bid_stars,"
        "highest_bidder_telegram_id,ends_at FROM collectible_auctions"
        " WHERE status='active' AND ends_at<=? LIMIT 100",
        (stamp,),
    )
    due = await cur.fetchall()
    sold: list[dict[str, Any]] = []
    for auction in due:
        auction_id = int(auction["id"])
        collectible_id = int(auction["collectible_id"])
        seller_id = int(auction["seller_telegram_id"])
        bid = int(auction["current_bid_stars"])
        bidder = auction["highest_bidder_telegram_id"]
        cur = await db.execute(
            "SELECT name,owner_telegram_id FROM collectibles WHERE id=?", (collectible_id,)
        )
        collectible = await cur.fetchone()
        if collectible is None:
            await db.execute(
                "UPDATE collectible_auctions SET status='cancelled',settled_at=?"
                " WHERE id=? AND status='active'", (stamp, auction_id))
            continue
        name = str(collectible["name"])
        owner_ok = collectible["owner_telegram_id"] is not None \
            and int(collectible["owner_telegram_id"]) == seller_id
        if bidder is None or bid <= 0 or not owner_ok:
            # Аукцион без ставок или имя уже не у продавца — деньги возвращаем.
            if bidder is not None and bid > 0:
                await _add_stars(db, int(bidder), bid,
                                 f"username_auction_refund:{auction_id}", stamp)
            await db.execute(
                "UPDATE collectible_auctions SET status=?,settled_at=?"
                " WHERE id=? AND status='active'",
                ("ended" if bidder is None or bid <= 0 else "cancelled", stamp, auction_id),
            )
            continue
        commission = _commission(bid)
        payout = bid - commission
        await _add_stars(db, seller_id, payout,
                         f"username_auction_sale:{auction_id}", stamp)
        await _record_transfer(db, collectible_id, seller_id, int(bidder), bid,
                               commission, "auction", stamp)
        await _transfer_ownership(db, name, seller_id, int(bidder), stamp, source="auction")
        await db.execute(
            "UPDATE collectible_auctions SET status='sold',winner_telegram_id=?,"
            "final_price_stars=?,settled_at=? WHERE id=? AND status='active'",
            (int(bidder), bid, stamp, auction_id),
        )
        if notify_sale is not None:
            await notify_sale(db, seller_id, name, payout, int(bidder), stamp)
        sold.append({
            "auction_id": auction_id,
            "name": name,
            "winner_telegram_id": int(bidder),
            "seller_telegram_id": seller_id,
            "price_stars": bid,
            "commission_stars": commission,
            "payout_stars": payout,
        })
    return sold


async def on_username_changed(db: Any, telegram_id: int, old_name: str, new_name: str,
                              now: int | None = None) -> None:
    """Синхронизирует основной username, сохраняя купленные дополнительные имена."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    owner_id = int(telegram_id)
    old = str(old_name or "").strip().lstrip("@").lower()
    new = str(new_name or "").strip().lstrip("@").lower()
    if old and old != new:
        cur = await db.execute(
            "SELECT id,source FROM collectibles WHERE name=? COLLATE NOCASE"
            " AND owner_telegram_id=?",
            (old, owner_id),
        )
        previous = await cur.fetchone()
        # Купленный/заявленный ник остаётся активом аккаунта, даже если его
        # временно поставили основным и затем заменили другим.
        if previous is not None:
            if str(previous["source"]) == "account":
                await db.execute(
                    "UPDATE collectibles SET owner_telegram_id=NULL,source='system',"
                    "transfer_frozen_until=0 WHERE id=?",
                    (int(previous["id"]),),
                )
            # A purchased name remains owned, but no longer belongs to this primary
            # account address once the user changes it.
            await db.execute(
                "DELETE FROM collectible_entity_bindings WHERE collectible_id=?"
                " AND entity_type='account' AND entity_id=?",
                (int(previous["id"]), owner_id),
            )
    if USERNAME_RE.fullmatch(new):
        await db.execute(
            "INSERT OR IGNORE INTO collectibles(kind,name,owner_telegram_id,acquired_at,source)"
            " VALUES('username',?,?,?,'account')",
            (new, owner_id, stamp),
        )
        cur = await db.execute(
            "SELECT id,owner_telegram_id FROM collectibles WHERE name=? COLLATE NOCASE",
            (new,),
        )
        collectible = await cur.fetchone()
        if collectible is not None and collectible["owner_telegram_id"] is None:
            await db.execute(
                "UPDATE collectibles SET owner_telegram_id=?,acquired_at=?,"
                "transfer_frozen_until=?,source='account' WHERE id=?",
                (owner_id, stamp, stamp + TRANSFER_FREEZE_SECONDS, int(collectible["id"])),
            )
        await _bind_entity(db, new, "account", owner_id)
    await db.commit()


async def on_entity_username_changed(
    db: Any, telegram_id: int, entity_type: str, entity_id: int,
    old_name: str, new_name: str, now: int | None = None,
) -> None:
    """Keeps account-owned username assets in sync with a group/channel address."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    owner_id = int(telegram_id)
    old = str(old_name or "").strip().lstrip("@").lower()
    new = str(new_name or "").strip().lstrip("@").lower()
    if old and old != new:
        cur = await db.execute(
            "SELECT id,source FROM collectibles WHERE name=? COLLATE NOCASE"
            " AND owner_telegram_id=?",
            (old, owner_id),
        )
        previous = await cur.fetchone()
        if previous is not None:
            if str(previous["source"]) == "account":
                await db.execute(
                    "UPDATE collectibles SET owner_telegram_id=NULL,source='system',"
                    "transfer_frozen_until=0 WHERE id=?",
                    (int(previous["id"]),),
                )
            # A purchased username keeps its owner when detached from an entity.
            await db.execute(
                "DELETE FROM collectible_entity_bindings WHERE collectible_id=?"
                " AND entity_type=? AND entity_id=?",
                (int(previous["id"]), str(entity_type), int(entity_id)),
            )
    if USERNAME_RE.fullmatch(new):
        await db.execute(
            "INSERT OR IGNORE INTO collectibles(kind,name,owner_telegram_id,acquired_at,source)"
            " VALUES('username',?,?,?,'account')",
            (new, owner_id, stamp),
        )
        cur = await db.execute(
            "SELECT id,owner_telegram_id FROM collectibles WHERE name=? COLLATE NOCASE",
            (new,),
        )
        collectible = await cur.fetchone()
        if collectible is not None and collectible["owner_telegram_id"] is None:
            await db.execute(
                "UPDATE collectibles SET owner_telegram_id=?,acquired_at=?,"
                "transfer_frozen_until=?,source='account' WHERE id=?",
                (owner_id, stamp, stamp + TRANSFER_FREEZE_SECONDS, int(collectible["id"])),
            )
        await _bind_entity(db, new, str(entity_type), int(entity_id))


async def owned_usernames(
    db: Any, telegram_id: int, primary_username: str = "",
) -> list[dict[str, Any]]:
    """Public additional active usernames owned by one account."""
    _activate(db)
    primary = str(primary_username or "").strip().lstrip("@").lower()
    try:
        cur = await db.execute(
            "SELECT c.name,EXISTS(SELECT 1 FROM collectible_listings l"
            " WHERE l.collectible_id=c.id AND l.status='active') AS for_sale"
            " FROM collectibles c WHERE c.owner_telegram_id=? AND c.kind='username'"
            " AND NOT EXISTS(SELECT 1 FROM collectible_listings l"
            "               WHERE l.collectible_id=c.id AND l.status='active')"
            " AND NOT EXISTS(SELECT 1 FROM collectible_entity_bindings b"
            "               WHERE b.collectible_id=c.id AND b.entity_type IN ('group','channel'))"
            " ORDER BY c.acquired_at DESC,c.name",
            (int(telegram_id),),
        )
        rows = await cur.fetchall()
    except Exception:  # noqa: BLE001 - profile responses also work during a partial migration
        return []
    result = []
    seen = {primary} if primary else set()
    for row in rows:
        name = str(row["name"] or "").strip().lstrip("@").lower()
        if not name or name in seen:
            continue
        seen.add(name)
        result.append({"username": name, "active": True,
                       "for_sale": bool(row["for_sale"])})
    return result


async def _username_used_outside_market(db: Any, name: str) -> bool:
    """Занято ли имя аккаунтом, группой, каналом или ботом вне рынка."""
    for table in ("accounts", "chat_groups", "channels", "bots"):
        try:
            sql = f"SELECT 1 FROM {table} WHERE username=? COLLATE NOCASE"
            if table == "channels":
                sql += " AND COALESCE(is_public,1)=1"
            cur = await db.execute(sql + " LIMIT 1", (name,))
        except Exception:  # noqa: BLE001 - в урезанных схемах этих таблиц может не быть
            continue
        if await cur.fetchone() is not None:
            return True
    return False


async def username_used_outside_market(db: Any, name: str) -> bool:
    """Whether an account, public entity, or bot currently uses the canonical name."""
    key = str(name or "").strip().lstrip("@").lower()
    if not USERNAME_RE.fullmatch(key):
        return False
    return await _username_used_outside_market(db, key)


async def username_taken_elsewhere(db: Any, telegram_id: int, name: str) -> bool:
    """Is a market-owned username unavailable to this account/entity?"""
    _activate(db)
    key = str(name or "").strip().lstrip("@").lower()
    if not USERNAME_RE.fullmatch(key):
        return False
    row = await _collectible(db, key)
    if row is None or row["owner_telegram_id"] is None:
        return False
    if int(row["owner_telegram_id"]) != int(telegram_id):
        return True
    return await _active_listing(db, int(row["id"])) is not None


# ---------------------------------------------------------------- TON


def ton_deposit_address() -> str:
    return os.getenv("WAYSGRAM_TON_ADDRESS", "").strip()


def ton_stars_per_ton() -> int:
    try:
        value = int(str(os.getenv("WAYSGRAM_STARS_PER_TON", "")).strip() or TON_DEFAULT_STARS_PER_TON)
    except ValueError:
        value = TON_DEFAULT_STARS_PER_TON
    return max(1, value)


def ton_memo_for(telegram_id: int) -> str:
    return f"wg:{int(telegram_id)}"


async def ton_state(db: Any, telegram_id: int) -> dict[str, Any]:
    _activate(db)
    rate = ton_stars_per_ton()
    cur = await db.execute(
        "SELECT tx_hash,amount_nanoton,stars_credited,rate,created_at FROM ton_deposits"
        " WHERE telegram_id=? ORDER BY id DESC LIMIT 20",
        (int(telegram_id),),
    )
    deposits = [dict(row) for row in await cur.fetchall()]
    cur = await db.execute(
        "SELECT id,address,amount_nanoton,stars_spent,status,tx_hash,created_at"
        " FROM ton_payouts WHERE telegram_id=? ORDER BY id DESC LIMIT 20",
        (int(telegram_id),),
    )
    payouts = [dict(row) for row in await cur.fetchall()]
    return {
        "address": ton_deposit_address(),
        "memo": ton_memo_for(telegram_id),
        "stars_per_ton": rate,
        "min_withdraw_stars": TON_MIN_WITHDRAW_STARS,
        "withdraw_enabled": str(os.getenv("WAYSGRAM_TON_WITHDRAWALS", "")).strip() == "1",
        "deposits": deposits,
        "payouts": payouts,
        "stars_balance": await _balance(db, telegram_id),
    }


async def credit_deposit(db: Any, memo: str, tx_hash: str, amount_nanoton: str,
                         now: int | None = None) -> dict[str, Any]:
    """Зачисляет TON-перевод в звёзды. Повторный ``tx_hash`` — отказ."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    match = TON_MEMO_RE.fullmatch(str(memo or "").strip())
    if match is None:
        raise MarketError(400, "TON_MEMO_INVALID", "Комментарий перевода не похож на wg:<id>")
    telegram_id = int(match.group(1))
    if not await _account_exists(db, telegram_id):
        raise MarketError(404, "ACCOUNT_NOT_FOUND", "Аккаунт не найден")
    digest = str(tx_hash or "").strip()
    if len(digest) < 16 or len(digest) > 128 or not re.fullmatch(r"[A-Za-z0-9_:-]+", digest):
        raise MarketError(400, "TON_TX_INVALID", "Некорректный хеш перевода")
    try:
        nanoton = int(str(amount_nanoton).strip())
    except (TypeError, ValueError) as exc:
        raise MarketError(400, "TON_AMOUNT_INVALID", "Некорректная сумма перевода") from exc
    if nanoton <= 0:
        raise MarketError(400, "TON_AMOUNT_INVALID", "Некорректная сумма перевода")
    rate = ton_stars_per_ton()
    stars = nanoton * rate // 1_000_000_000
    if stars <= 0:
        raise MarketError(400, "TON_AMOUNT_TOO_SMALL", "Сумма слишком мала для зачисления")
    try:
        await db.execute(
            "INSERT INTO ton_deposits(telegram_id,memo,tx_hash,amount_nanoton,stars_credited,"
            "rate,created_at) VALUES(?,?,?,?,?,?,?)",
            (telegram_id, str(memo).strip(), digest, str(nanoton), stars, str(rate), stamp),
        )
    except sqlite3.IntegrityError as exc:
        raise MarketError(409, "TON_DEPOSIT_DUPLICATE", "Этот перевод уже зачислен") from exc
    balance = await _add_stars(db, telegram_id, stars, f"ton_deposit:{digest}", stamp)
    await db.commit()
    return {
        "telegram_id": telegram_id,
        "stars_credited": stars,
        "stars_balance": balance,
        "rate": rate,
        "tx_hash": digest,
    }


async def request_payout(db: Any, telegram_id: int, stars: int, address: str,
                         now: int | None = None) -> dict[str, Any]:
    """Заявка на вывод: звёзды списываются сразу, выплата ждёт подписанта."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    if str(os.getenv("WAYSGRAM_TON_WITHDRAWALS", "")).strip() != "1":
        raise MarketError(403, "TON_WITHDRAW_DISABLED",
                          "Вывод в TON временно отключён")
    amount = int(stars or 0)
    if amount < TON_MIN_WITHDRAW_STARS:
        raise MarketError(400, "TON_WITHDRAW_TOO_SMALL",
                          f"Минимум для вывода — ⭐{TON_MIN_WITHDRAW_STARS}")
    if amount > TON_MAX_WITHDRAW_STARS:
        raise MarketError(400, "TON_WITHDRAW_TOO_LARGE",
                          f"Максимум за одну заявку — ⭐{TON_MAX_WITHDRAW_STARS}")
    destination = str(address or "").strip()
    if not TON_ADDRESS_RE.fullmatch(destination):
        raise MarketError(400, "TON_ADDRESS_INVALID", "Некорректный адрес TON")
    rate = ton_stars_per_ton()
    nanoton = int(amount) * 1_000_000_000 // rate
    if nanoton <= 0:
        raise MarketError(400, "TON_WITHDRAW_TOO_SMALL", "Слишком маленькая сумма")
    balance = await _add_stars(db, int(telegram_id), -amount,
                               f"ton_withdraw_pending:{secrets.token_hex(6)}", stamp)
    cur = await db.execute(
        "INSERT INTO ton_payouts(telegram_id,address,amount_nanoton,stars_spent,rate,status,"
        "created_at,updated_at) VALUES(?,?,?,?,?,'pending',?,?)",
        (int(telegram_id), destination, str(nanoton), amount, str(rate), stamp, stamp),
    )
    payout_id = int(cur.lastrowid)
    await db.commit()
    return {
        "status": "pending",
        "payout_id": payout_id,
        "amount_nanoton": str(nanoton),
        "stars_spent": amount,
        "stars_balance": balance,
        "rate": rate,
    }


# ---------------------------------------------------------------- маршруты


def _raise(error: MarketError) -> None:
    raise HTTPException(
        status_code=error.status,
        detail={"code": error.code, "message": error.message},
    )


def register_routes(
    router: APIRouter,
    *,
    db_path: Any,
    current_user: Any,
    idempotency_descriptor: Any = None,
    idempotency_lookup: Any = None,
    idempotency_store: Any = None,
    sale_notification: Any = None,
    notification_wakeup: Any = None,
) -> None:
    """Регистрирует REST-методы рынка на уже собранном роутере."""

    def sale_notifier():
        state = {"push_queued": False}

        async def notify(db: Any, seller_id: int, item_name: str, stars: int,
                         buyer_id: int, stamp: int) -> bool:
            if sale_notification is None:
                return False
            queued = bool(await sale_notification(
                db, int(seller_id), "username", str(item_name), int(stars),
                int(buyer_id), int(stamp),
            ))
            state["push_queued"] = state["push_queued"] or queued
            return queued

        return notify, state

    def wake_sale_push(state: dict[str, bool]) -> None:
        if state.get("push_queued") and notification_wakeup is not None:
            notification_wakeup.set()

    async def viewer(authorization: str | None) -> dict[str, Any]:
        return await current_user(authorization)

    @router.get("/api/collectibles")
    async def list_collectibles(
        section: str = Query(default="market"),
        query: str = Query(default=""),
        authorization: str | None = Header(default=None),
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        if section not in ("market", "for_sale", "auctions", "mine"):
            raise HTTPException(400, detail={"code": "SECTION_INVALID", "message": "Неизвестный раздел"})
        notify_sale, notification_state = sale_notifier()
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await search(
                    db, int(user["telegram_id"]), section=section, query=query,
                    notify_sale=notify_sale,
                )
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
        wake_sale_push(notification_state)
        return result

    @router.get("/api/me/collectibles")
    async def my_collectibles(authorization: str | None = Header(default=None)) -> dict[str, Any]:
        return await list_collectibles(section="mine", query="", authorization=authorization)

    @router.get("/api/collectibles/{name}")
    async def collectible_card(
        name: str, authorization: str | None = Header(default=None)
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        notify_sale, notification_state = sale_notifier()
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await card(
                    db, name, int(user["telegram_id"]), notify_sale=notify_sale,
                )
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
        wake_sale_push(notification_state)
        return result

    @router.get("/api/collectibles/{name}/bids")
    async def collectible_bids(
        name: str, authorization: str | None = Header(default=None)
    ) -> dict[str, Any]:
        await viewer(authorization)
        async with aiosqlite.connect(db_path()) as db:
            try:
                rows = await bids(db, name)
            except MarketError as error:
                _raise(error)
                raise
        return {"bids": rows}

    @router.put("/api/collectibles/{name}/listing")
    async def set_listing(
        name: str, payload: dict[str, Any],
        authorization: str | None = Header(default=None),
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        currency = str(payload.get("currency") or "stars").strip().lower()
        if currency == "gram":
            currency = "grams"
        price_key = "price_grams" if currency == "grams" else "price_stars"
        price = payload.get("price", payload.get(price_key))
        if type(price) is not int:
            raise HTTPException(400, detail={"code": "PRICE_INVALID", "message": "Цена должна быть целым числом"})
        notify_sale, notification_state = sale_notifier()
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await put_listing(
                    db, int(user["telegram_id"]), name, price, notify_sale=notify_sale,
                    currency=currency,
                )
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
        wake_sale_push(notification_state)
        return result

    @router.delete("/api/collectibles/{name}/listing")
    async def drop_listing(
        name: str, authorization: str | None = Header(default=None)
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        notify_sale, notification_state = sale_notifier()
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await unlist(
                    db, int(user["telegram_id"]), name, notify_sale=notify_sale,
                )
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
        wake_sale_push(notification_state)
        return result

    async def _idempotent_mutation(
        scope: str, path: str, payload: dict[str, Any], idempotency_key: str | None,
        action: Any, telegram_id: int,
    ) -> dict[str, Any]:
        """Общая обёртка мутаций: `Idempotency-Key` защищает от двойного тапа."""
        now = int(time.time())
        descriptor = (idempotency_descriptor(path, payload, idempotency_key)
                      if idempotency_descriptor else None)
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            if idempotency_lookup is not None:
                previous = await idempotency_lookup(db, telegram_id, scope, descriptor, now)
                if previous is not None:
                    await db.rollback()
                    return previous
            try:
                result = await action(db, telegram_id, now)
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            if idempotency_store is not None:
                await idempotency_store(db, telegram_id, scope, descriptor, result, now)
            await db.commit()
        return result

    @router.post("/api/collectibles/{name}/buy")
    async def buy_collectible(
        name: str,
        payload: dict[str, Any] | None = None,
        authorization: str | None = Header(default=None),
        idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        uid = int(user["telegram_id"])
        notify_sale, notification_state = sale_notifier()

        async def purchase_action(db: Any, telegram_id: int, now: int) -> dict[str, Any]:
            return await buy(
                db, telegram_id, name, now, commit=False,
                notify_sale=notify_sale,
            )

        result = await _idempotent_mutation(
            "collectible_buy", f"/api/collectibles/{name}/buy", payload or {},
            idempotency_key, purchase_action, uid,
        )
        wake_sale_push(notification_state)
        return result

    @router.post("/api/collectibles/{name}/claim")
    async def claim_collectible(
        name: str,
        payload: dict[str, Any] | None = None,
        authorization: str | None = Header(default=None),
        idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        uid = int(user["telegram_id"])
        notify_sale, notification_state = sale_notifier()
        result = await _idempotent_mutation(
            "collectible_claim", f"/api/collectibles/{name}/claim", payload or {},
            idempotency_key,
            lambda db, telegram_id, now: claim(
                db, telegram_id, name, now, notify_sale=notify_sale,
            ), uid,
        )
        wake_sale_push(notification_state)
        return result

    @router.post("/api/collectibles/{name}/auction")
    async def create_auction(
        name: str, payload: dict[str, Any],
        authorization: str | None = Header(default=None),
        idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        uid = int(user["telegram_id"])
        min_bid = payload.get("min_bid_stars")
        hours = payload.get("duration_hours", 24)
        if type(min_bid) is not int or type(hours) is not int:
            raise HTTPException(400, detail={"code": "PRICE_INVALID", "message": "Ставка и срок — целые числа"})
        notify_sale, notification_state = sale_notifier()
        result = await _idempotent_mutation(
            "collectible_auction_start", f"/api/collectibles/{name}/auction", payload,
            idempotency_key,
            lambda db, telegram_id, now: start_auction(
                db, telegram_id, name, min_bid, hours, now, notify_sale=notify_sale), uid,
        )
        wake_sale_push(notification_state)
        return result

    @router.delete("/api/collectibles/{name}/auction")
    async def drop_auction(
        name: str, authorization: str | None = Header(default=None)
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        notify_sale, notification_state = sale_notifier()
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await cancel_auction(
                    db, int(user["telegram_id"]), name, notify_sale=notify_sale,
                )
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
        wake_sale_push(notification_state)
        return result

    @router.post("/api/collectibles/{name}/bid")
    async def place_collectible_bid(
        name: str, payload: dict[str, Any],
        authorization: str | None = Header(default=None),
        idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        uid = int(user["telegram_id"])
        amount = payload.get("amount_stars")
        if type(amount) is not int:
            raise HTTPException(400, detail={"code": "PRICE_INVALID", "message": "Ставка — целое число звёзд"})
        notify_sale, notification_state = sale_notifier()
        result = await _idempotent_mutation(
            "collectible_bid", f"/api/collectibles/{name}/bid", payload, idempotency_key,
            lambda db, telegram_id, now: place_bid(
                db, telegram_id, name, amount, now, notify_sale=notify_sale,
            ), uid,
        )
        wake_sale_push(notification_state)
        return result

    @router.get("/api/me/ton")
    async def read_ton(authorization: str | None = Header(default=None)) -> dict[str, Any]:
        user = await viewer(authorization)
        async with aiosqlite.connect(db_path()) as db:
            return await ton_state(db, int(user["telegram_id"]))

    @router.post("/api/me/ton-withdraw")
    async def withdraw_ton(
        payload: dict[str, Any],
        authorization: str | None = Header(default=None),
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        uid = int(user["telegram_id"])
        amount = payload.get("stars")
        address = payload.get("address")
        if type(amount) is not int or not isinstance(address, str):
            raise HTTPException(400, detail={"code": "TON_WITHDRAW_INVALID", "message": "Нужны звёзды и адрес"})
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await request_payout(db, uid, amount, address)
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
        return result

    @router.post("/api/internal/ton/deposits")
    async def ton_deposit_webhook(
        payload: dict[str, Any], request: Request
    ) -> dict[str, Any]:
        """Приём переводов от вотчера. Без секрета в окружении endpoint закрыт."""
        secret = os.getenv("WAYSGRAM_TON_WATCHER_SECRET", "").strip()
        if not secret:
            raise HTTPException(503, detail={"code": "TON_WATCHER_DISABLED",
                                             "message": "Приём TON не настроен"})
        provided = request.headers.get("X-WaysGram-Ton-Secret", "")
        if not hmac.compare_digest(provided, secret):
            raise HTTPException(403, detail={"code": "TON_SECRET_INVALID",
                                             "message": "Неверный секрет вотчера"})
        memo = payload.get("memo")
        tx_hash = payload.get("tx_hash")
        amount = payload.get("amount_nanoton")
        if not isinstance(memo, str) or not isinstance(tx_hash, str) or amount is None:
            raise HTTPException(400, detail={"code": "TON_PAYLOAD_INVALID",
                                             "message": "Нужны memo, tx_hash и amount_nanoton"})
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await credit_deposit(db, memo, tx_hash, str(amount))
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
        LOGGER.info("TON deposit credited: %s", result["tx_hash"])
        return result
