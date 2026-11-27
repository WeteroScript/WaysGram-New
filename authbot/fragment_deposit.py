"""Пополнение инвентаря Fragment: подарок, отправленный на @fragment.

Рынок подарков в WaysGram работает на таблице ``user_gifts``: подарок принадлежит
одному аккаунту, а продажа — это объявление в ``gift_market_listings``. Чтобы
инвентарь нельзя было накрутить пересылкой между своими же аккаунтами, пополнение
идёт только через служебный аккаунт ``@fragment``:

* аккаунт ``@fragment`` создаётся при инициализации базы (``ensure_account``);
* когда пользователь отправляет ему подарок, ``user_gifts.owner_telegram_id``
  переходит на служебный аккаунт ``@fragment``; запись в ``fragment_gift_deposits``
  сохраняет отправителя, который может выставить лот от своего имени;
* повторная отправка того же серийного номера не создаёт вторую запись
  (уникальный индекс ``telegram_id + serial_number``).

Так инвентарь пополняется осознанным действием в клиенте, а не прямым вызовом
API: без подарка, отправленного на ``@fragment``, объявление на рынке не создать.
"""

from __future__ import annotations

import logging
import time
from typing import Any

import aiosqlite
import db_split

LOGGER = logging.getLogger(__name__)

# Имя служебного аккаунта и его фиксированный идентификатор: он не пересекается
# ни с телеграмными id, ни с id групп (0..1e9) и каналов (server_id + 1e9).
FRAGMENT_USERNAME = "fragment"
FRAGMENT_TELEGRAM_ID = 777_000_777
FRAGMENT_DISPLAY_NAME = "Fragment"
FRAGMENT_BIO = (
    "Служебный аккаунт рынка Fragment в WaysGram. Отправьте сюда NFT-подарок — "
    "он перейдёт во владение @fragment и появится в вашем разделе Fragment для продажи."
)

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS fragment_gift_deposits (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    serial_number TEXT NOT NULL,
    telegram_id INTEGER NOT NULL,
    gift_name TEXT NOT NULL DEFAULT '',
    deposited_at INTEGER NOT NULL,
    UNIQUE(telegram_id, serial_number)
);
CREATE INDEX IF NOT EXISTS idx_fragment_deposits_user
    ON fragment_gift_deposits(telegram_id, deposited_at DESC);
"""


def _activate(db: Any) -> None:
    """amalgama-шимам (тесты, импорт) нужен row factory как у aiosqlite."""
    if getattr(db, "row_factory", None) is None:
        db.row_factory = aiosqlite.Row


async def ensure_storage(db: Any) -> None:
    await db.executescript(db_split.qualify_ddl(SCHEMA_SQL))
    await db.commit()


async def ensure_account(db: Any, now: int | None = None) -> bool:
    """Создаёт служебный аккаунт ``@fragment``. Возвращает True, если он появился."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    cur = await db.execute(
        "SELECT telegram_id FROM accounts WHERE LOWER(username)=? LIMIT 1",
        (FRAGMENT_USERNAME,),
    )
    existing = await cur.fetchone()
    if existing is not None:
        # Имя уже занято вручную — не подменяем владельца, просто используем его.
        return False
    await db.execute(
        """INSERT INTO accounts
               (telegram_id,username,display_name,avatar_media_id,bio,stars,grams,
                is_banned,created_at,updated_at)
           VALUES(?,?,?,'',?,0,0,0,?,?)
           ON CONFLICT(telegram_id) DO UPDATE SET
               username=excluded.username,
               display_name=excluded.display_name,
               bio=excluded.bio,
               updated_at=excluded.updated_at""",
        (FRAGMENT_TELEGRAM_ID, FRAGMENT_USERNAME, FRAGMENT_DISPLAY_NAME, FRAGMENT_BIO,
         stamp, stamp),
    )
    await db.commit()
    return True


async def deposit_targets(db: Any) -> set[int]:
    """Идентификаторы аккаунтов, которые принимают подарки в инвентарь."""
    _activate(db)
    try:
        cur = await db.execute(
            "SELECT telegram_id FROM accounts WHERE LOWER(username)=?", (FRAGMENT_USERNAME,)
        )
        rows = await cur.fetchall()
    except Exception:  # noqa: BLE001 - урезанные схемы тестов могут не иметь accounts
        LOGGER.warning("fragment deposit targets unavailable", exc_info=True)
        return {FRAGMENT_TELEGRAM_ID}
    targets = {int(row[0]) for row in rows}
    if not targets:
        targets = {FRAGMENT_TELEGRAM_ID}
    return targets


async def is_deposit_target(db: Any, telegram_id: int) -> bool:
    return int(telegram_id) in await deposit_targets(db)


async def record(db: Any, telegram_id: int, serial_number: str, gift_name: str,
                 now: int | None = None) -> bool:
    """Отмечает подарок как принятый Fragment. False — уже был принят раньше."""
    _activate(db)
    stamp = int(now if now is not None else time.time())
    cur = await db.execute(
        """INSERT OR IGNORE INTO fragment_gift_deposits
               (serial_number,telegram_id,gift_name,deposited_at)
           VALUES(?,?,?,?)""",
        (serial_number.strip().upper(), int(telegram_id), gift_name[:120], stamp),
    )
    return cur.rowcount > 0


async def deposited_count(db: Any, telegram_id: int) -> int:
    _activate(db)
    cur = await db.execute(
        "SELECT COUNT(*) FROM fragment_gift_deposits WHERE telegram_id=?",
        (int(telegram_id),),
    )
    row = await cur.fetchone()
    return int(row[0] or 0) if row is not None else 0


async def deposited_serials(db: Any, telegram_id: int) -> set[str]:
    _activate(db)
    cur = await db.execute(
        "SELECT serial_number FROM fragment_gift_deposits WHERE telegram_id=?",
        (int(telegram_id),),
    )
    return {str(row[0]) for row in await cur.fetchall()}
