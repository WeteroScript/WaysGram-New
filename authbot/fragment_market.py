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

CREATE TABLE IF NOT EXISTS collectible_listings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collectible_id INTEGER NOT NULL,
    seller_telegram_id INTEGER NOT NULL,
    price_stars INTEGER NOT NULL CHECK(price_stars > 0),
    status TEXT NOT NULL DEFAULT 'active'
        CHECK(status IN ('active','sold','cancelled')),
    created_at INTEGER NOT NULL,
    sold_at INTEGER
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


async def _ensure_collectible(db: Any, name: str, now: int) -> aiosqlite.Row:
    row = await _collectible(db, name)
    if row is not None:
        return row
    cur = await db.execute(
        "SELECT telegram_id,created_at FROM accounts WHERE username=? COLLATE NOCASE",
        (name,),
    )
    account = await cur.fetchone()
    owner = int(account["telegram_id"]) if account is not None else None
    acquired = int(account["created_at"] or now) if account is not None else 0
    await db.execute(
        "INSERT OR IGNORE INTO collectibles(kind,name,owner_telegram_id,acquired_at,source)"
        " VALUES('username',?,?,?,'account')",
        (name, owner, acquired),
    )
    row = await _collectible(db, name)
    if row is None:  # pragma: no cover - INSERT OR IGNORE с тем же именем
        raise MarketError(409, "COLLECTIBLE_CONFLICT", "Не удалось завести имя")
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
        "SELECT id,collectible_id,seller_telegram_id,price_stars,created_at"
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
        "SELECT from_telegram_id,to_telegram_id,price_stars,commission_stars,kind,created_at"
        " FROM collectible_transfers WHERE collectible_id=? ORDER BY id DESC LIMIT ?",
        (int(collectible_id), int(limit)),
    )
    return [dict(row) for row in await cur.fetchall()]


async def _balance(db: Any, telegram_id: int) -> int:
    cur = await db.execute("SELECT stars FROM accounts WHERE telegram_id=?", (int(telegram_id),))
    row = await cur.fetchone()
    if row is None:
        raise MarketError(404, "ACCOUNT_NOT_FOUND", "Аккаунт не найден")
    return int(row["stars"])


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


async def _account_exists(db: Any, telegram_id: int) -> bool:
    cur = await db.execute("SELECT 1 FROM accounts WHERE telegram_id=?", (int(telegram_id),))
    return await cur.fetchone() is not None


def _commission(price: int) -> int:
    return max(0, int(price) * COMMISSION_PERCENT // 100)


async def _transfer_ownership(
    db: Any, name: str, seller_id: int | None, buyer_id: int, now: int, *, source: str
) -> None:
    """Передаёт имя покупателю и возвращает его прежнее имя в общий фонд."""
    cur = await db.execute("SELECT username FROM accounts WHERE telegram_id=?", (int(buyer_id),))
    row = await cur.fetchone()
    previous = str(row["username"] or "").strip().lstrip("@").lower() if row else ""
    if previous and previous != name:
        # Куплено новое имя — старое освобождается и снова продаётся сервисом.
        await db.execute(
            "INSERT OR IGNORE INTO collectibles(kind,name,owner_telegram_id,acquired_at,source)"
            " VALUES('username',?,NULL,?,'system')",
            (previous, int(now)),
        )
        await db.execute(
            "UPDATE collectibles SET owner_telegram_id=NULL,source='system',"
            "transfer_frozen_until=0 WHERE name=? COLLATE NOCASE",
            (previous,),
        )
    if seller_id:
        await db.execute(
            "UPDATE accounts SET username='',updated_at=? WHERE telegram_id=?"
            " AND username=? COLLATE NOCASE",
            (int(now), int(seller_id), name),
        )
    # На всякий случай снимаем имя с любого другого аккаунта: два владельца
    # одного имени — это дыра, а не состояние.
    await db.execute(
        "UPDATE accounts SET username='',updated_at=? WHERE username=? COLLATE NOCASE"
        " AND telegram_id<>?",
        (int(now), name, int(buyer_id)),
    )
    await db.execute(
        "UPDATE accounts SET username=?,updated_at=? WHERE telegram_id=?",
        (name, int(now), int(buyer_id)),
    )
    await db.execute(
        "UPDATE collectibles SET owner_telegram_id=?,acquired_at=?,transfer_frozen_until=?,"
        "source=? WHERE name=? COLLATE NOCASE",
        (int(buyer_id), int(now), int(now) + TRANSFER_FREEZE_SECONDS, source, name),
    )


async def _record_transfer(
    db: Any, collectible_id: int, seller_id: int | None, buyer_id: int,
    price: int, commission: int, kind: str, now: int,
) -> None:
    await db.execute(
        "INSERT INTO collectible_transfers(collectible_id,from_telegram_id,to_telegram_id,"
        "price_stars,commission_stars,kind,created_at) VALUES(?,?,?,?,?,?,?)",
        (int(collectible_id), seller_id, int(buyer_id), int(price), int(commission), kind, int(now)),
    )


async def card(db: Any, name: str, viewer_id: int | None = None,
               now: int | None = None, share_url: str = "") -> dict[str, Any]:
    """Карточка имени: владелец, цена, аукцион, заморозка и история."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    await settle_due(db, stamp)
    row = await _ensure_collectible(db, key, stamp)
    collectible_id = int(row["id"])
    owner_id = row["owner_telegram_id"]
    owner = await _public_account(db, int(owner_id) if owner_id else None)
    listing = await _active_listing(db, collectible_id)
    auction = await _active_auction(db, collectible_id)
    last = await _last_auction(db, collectible_id)
    frozen_until = int(row["transfer_frozen_until"] or 0)
    # Имя группы, канала или бота принадлежит не рынку: его нельзя ни купить, ни занять.
    reserved = owner_id is None and await _username_used_outside_market(db, key)
    result: dict[str, Any] = {
        "name": key,
        "kind": str(row["kind"]),
        "owner": owner,
        "mine": bool(owner_id and viewer_id and int(owner_id) == int(viewer_id)),
        "free": owner_id is None and not reserved,
        "reserved": reserved,
        "system_price_stars": system_price(key),
        "price_stars": int(listing["price_stars"]) if listing is not None else (
            0 if owner_id is not None or reserved else system_price(key)),
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
        "commission_percent": COMMISSION_PERCENT,
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
    limit: int = 50, now: int | None = None,
) -> dict[str, Any]:
    """Списки рынка: что продаётся, что на аукционе, что уже моё."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    await settle_due(db, stamp)
    await seed_from_accounts(db, stamp)
    prefix = str(query or "").strip().lstrip("@").lower()
    items: list[dict[str, Any]] = []
    if section in ("market", "for_sale"):
        sql = (
            "SELECT c.name,c.owner_telegram_id,l.price_stars,l.id AS listing_id,"
            " a.telegram_id AS account_id,a.display_name,a.username,a.avatar_media_id"
            " FROM collectible_listings l"
            " JOIN collectibles c ON c.id=l.collectible_id"
            " LEFT JOIN accounts a ON a.telegram_id=c.owner_telegram_id"
            " WHERE l.status='active'"
        )
        params: list[Any] = []
        if prefix:
            sql += " AND c.name LIKE ?"
            params.append(prefix + "%")
        sql += " ORDER BY l.price_stars, c.name LIMIT ?"
        params.append(int(limit))
        cur = await db.execute(sql, params)
        for row in await cur.fetchall():
            items.append({
                "name": str(row["name"]),
                "kind": "sale",
                "price_stars": int(row["price_stars"] or 0),
                "listing_id": int(row["listing_id"] or 0),
                "owner": await _public_account(db, row["owner_telegram_id"]),
                "mine": bool(row["owner_telegram_id"] and int(row["owner_telegram_id"]) == int(viewer_id)),
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
            " l.price_stars,l.id AS listing_id,"
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
            items.append({
                "name": str(row["name"]),
                "kind": "auction" if auction else ("sale" if row["listing_id"] else "owned"),
                "price_stars": int(row["price_stars"] or 0) if row["listing_id"] else 0,
                "listing_id": int(row["listing_id"] or 0),
                "owner": await _public_account(db, viewer_id),
                "mine": True,
                "auction": auction,
                "frozen_until": int(row["transfer_frozen_until"] or 0),
                "frozen": int(row["transfer_frozen_until"] or 0) > stamp,
            })
    return {
        "section": section,
        "query": prefix,
        "items": items,
        "stars_balance": await _balance(db, viewer_id),
        "commission_percent": COMMISSION_PERCENT,
        "freeze_seconds": TRANSFER_FREEZE_SECONDS,
    }


async def put_listing(db: Any, telegram_id: int, name: str, price: int,
                      now: int | None = None) -> dict[str, Any]:
    """Выставляет имя на продажу. Продавец — только текущий владелец из базы."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
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
    if listing is not None:
        await db.execute(
            "UPDATE collectible_listings SET price_stars=? WHERE id=?",
            (int(price), int(listing["id"])),
        )
    else:
        await db.execute(
            "INSERT INTO collectible_listings(collectible_id,seller_telegram_id,price_stars,"
            "status,created_at) VALUES(?,?,?,'active',?)",
            (int(row["id"]), int(telegram_id), int(price), stamp),
        )
    await db.commit()
    return await card(db, key, telegram_id, stamp)


async def unlist(db: Any, telegram_id: int, name: str, now: int | None = None) -> dict[str, Any]:
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
    await db.commit()
    return await card(db, key, telegram_id, stamp)


async def buy(db: Any, telegram_id: int, name: str, now: int | None = None,
              idempotent_reason: str = "") -> dict[str, Any]:
    """Покупка по фикс-цене: списание, выплата продавцу минус комиссия, передача."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    await settle_due(db, stamp)
    row = await _ensure_collectible(db, key, stamp)
    listing = await _active_listing(db, int(row["id"]))
    if listing is None:
        raise MarketError(409, "NOT_FOR_SALE", "Имя не продаётся")
    seller_id = int(listing["seller_telegram_id"])
    if seller_id == int(telegram_id):
        raise MarketError(409, "OWN_LISTING", "Это ваше имя")
    price = int(listing["price_stars"])
    commission = _commission(price)
    payout = price - commission
    await _add_stars(db, int(telegram_id), -price,
                     f"username_purchase:{int(listing['id'])}", stamp)
    await _add_stars(db, seller_id, payout,
                     f"username_sale:{int(listing['id'])}", stamp)
    await _record_transfer(db, int(row["id"]), seller_id, int(telegram_id),
                           price, commission, "sale", stamp)
    await db.execute(
        "UPDATE collectible_listings SET status='sold',sold_at=? WHERE id=? AND status='active'",
        (stamp, int(listing["id"])),
    )
    await _transfer_ownership(db, key, seller_id, int(telegram_id), stamp, source="sale")
    await db.commit()
    return await card(db, key, telegram_id, stamp)


async def claim(db: Any, telegram_id: int, name: str, now: int | None = None) -> dict[str, Any]:
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
    return await card(db, key, telegram_id, stamp)


async def start_auction(db: Any, telegram_id: int, name: str, min_bid: int,
                        hours: int, now: int | None = None) -> dict[str, Any]:
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
    return await card(db, key, telegram_id, stamp)


async def cancel_auction(db: Any, telegram_id: int, name: str,
                         now: int | None = None) -> dict[str, Any]:
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
    return await card(db, key, telegram_id, stamp)


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
                    now: int | None = None) -> dict[str, Any]:
    """Ставка холдит звёзды до конца аукциона; перебитая ставка возвращается."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    key = canonical_name(name)
    await settle_due(db, stamp)
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


async def settle_due(db: Any, now: int | None = None) -> list[dict[str, Any]]:
    """Закрывает истёкшие аукционы. Вызывается и воркером, и лениво — при чтении.

    Расчёт живёт в базе, а не в памяти: рестарт процесса ничего не теряет.
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
        sold.append({
            "auction_id": auction_id,
            "name": name,
            "winner_telegram_id": int(bidder),
            "seller_telegram_id": seller_id,
            "price_stars": bid,
            "commission_stars": commission,
        })
    return sold


async def on_username_changed(db: Any, telegram_id: int, old_name: str, new_name: str,
                              now: int | None = None) -> None:
    """Синхронизирует справочник после ручной смены юзернейма в профиле."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    old = str(old_name or "").strip().lstrip("@").lower()
    new = str(new_name or "").strip().lstrip("@").lower()
    if old and old != new:
        await db.execute(
            "UPDATE collectibles SET owner_telegram_id=NULL,source='system',"
            "transfer_frozen_until=0 WHERE name=? COLLATE NOCASE AND owner_telegram_id=?",
            (old, int(telegram_id)),
        )
    if USERNAME_RE.fullmatch(new):
        await db.execute(
            "INSERT OR IGNORE INTO collectibles(kind,name,owner_telegram_id,acquired_at,source)"
            " VALUES('username',?,?,?,'account')",
            (new, int(telegram_id), stamp),
        )
        await db.execute(
            "UPDATE collectibles SET owner_telegram_id=?,acquired_at=?,"
            "transfer_frozen_until=? WHERE name=? COLLATE NOCASE",
            (int(telegram_id), stamp, stamp + TRANSFER_FREEZE_SECONDS, new),
        )
    await db.commit()


async def _username_used_outside_market(db: Any, name: str) -> bool:
    """Занято ли имя группой, каналом или ботом: такие имена рынок не продаёт."""
    for table in ("chat_groups", "channels", "bots"):
        try:
            cur = await db.execute(
                f"SELECT 1 FROM {table} WHERE username=? COLLATE NOCASE LIMIT 1", (name,)
            )
        except Exception:  # noqa: BLE001 - в урезанных схемах этих таблиц может не быть
            continue
        if await cur.fetchone() is not None:
            return True
    return False


async def username_taken_elsewhere(db: Any, telegram_id: int, name: str) -> bool:
    """Занят ли юзернейм чужим лотом: купленное имя нельзя занять в обход рынка."""
    _activate(db)
    key = str(name or "").strip().lstrip("@").lower()
    if not USERNAME_RE.fullmatch(key):
        return False
    row = await _collectible(db, key)
    if row is None or row["owner_telegram_id"] is None:
        return False
    return int(row["owner_telegram_id"]) != int(telegram_id)


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
) -> None:
    """Регистрирует REST-методы рынка на уже собранном роутере."""

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
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await search(db, int(user["telegram_id"]), section=section, query=query)
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
        return result

    @router.get("/api/me/collectibles")
    async def my_collectibles(authorization: str | None = Header(default=None)) -> dict[str, Any]:
        return await list_collectibles(section="mine", query="", authorization=authorization)

    @router.get("/api/collectibles/{name}")
    async def collectible_card(
        name: str, authorization: str | None = Header(default=None)
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await card(db, name, int(user["telegram_id"]))
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
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
        price = payload.get("price_stars")
        if type(price) is not int:
            raise HTTPException(400, detail={"code": "PRICE_INVALID", "message": "Цена в звёздах — целое число"})
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await put_listing(db, int(user["telegram_id"]), name, price)
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
        return result

    @router.delete("/api/collectibles/{name}/listing")
    async def drop_listing(
        name: str, authorization: str | None = Header(default=None)
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await unlist(db, int(user["telegram_id"]), name)
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
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
        return await _idempotent_mutation(
            "collectible_buy", f"/api/collectibles/{name}/buy", payload or {},
            idempotency_key,
            lambda db, telegram_id, now: buy(db, telegram_id, name, now), uid,
        )

    @router.post("/api/collectibles/{name}/claim")
    async def claim_collectible(
        name: str,
        payload: dict[str, Any] | None = None,
        authorization: str | None = Header(default=None),
        idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        uid = int(user["telegram_id"])
        return await _idempotent_mutation(
            "collectible_claim", f"/api/collectibles/{name}/claim", payload or {},
            idempotency_key,
            lambda db, telegram_id, now: claim(db, telegram_id, name, now), uid,
        )

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
        return await _idempotent_mutation(
            "collectible_auction_start", f"/api/collectibles/{name}/auction", payload,
            idempotency_key,
            lambda db, telegram_id, now: start_auction(
                db, telegram_id, name, min_bid, hours, now), uid,
        )

    @router.delete("/api/collectibles/{name}/auction")
    async def drop_auction(
        name: str, authorization: str | None = Header(default=None)
    ) -> dict[str, Any]:
        user = await viewer(authorization)
        async with aiosqlite.connect(db_path()) as db:
            await db.execute("BEGIN IMMEDIATE")
            try:
                result = await cancel_auction(db, int(user["telegram_id"]), name)
            except MarketError as error:
                await db.rollback()
                _raise(error)
                raise
            await db.commit()
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
        return await _idempotent_mutation(
            "collectible_bid", f"/api/collectibles/{name}/bid", payload, idempotency_key,
            lambda db, telegram_id, now: place_bid(db, telegram_id, name, amount, now), uid,
        )

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
