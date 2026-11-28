"""WaysGram's own star gifts: bundled model packs with a Telegram-shaped upgrade.

A WaysGram gift is not a Telegram gift.  Its upgraded models are WebM animations with an
alpha channel, bundled in :file:`assets/gift_models/<gift_id>/` (see
:mod:`gift_model_fallbacks`), and its price, stock and place in the shop belong to us.
Everything else works exactly like Telegram's own gifts, because the gift lives in the
same tables and goes through the same code paths:

* the storefront row is a ``telegram_mtproto_gift_catalog`` row, marked ``is_waysgram`` so
  that the Telegram catalogue sync never retires it and the administrator can hide it;
* the price, stock and sold counter are the row of ``telegram_gift_prices``, so the
  existing AuthBot screen (price / supply / hide) edits them without a special case;
* upgrade traits are rolled by :mod:`gift_upgrades` from the pool entry of
  ``gift_upgrade_pools.json`` plus the exact independent draws of ``SPECIAL_MODELS``;
* the client plays the models through ``/api/telegram/gifts/<id>/attributes/<document>``,
  which already serves synthetic negative document ids from a bundled pack.

Patterns and backdrops are reused from a real Telegram gift instead of being redrawn:
Telegram publishes them as small animations and colour sets, and a hand-drawn copy would
look worse than the original.  The donor gift is stated explicitly and only its pattern
and backdrop rows are borrowed - its models are never used for a WaysGram gift.
"""

from __future__ import annotations

import json
import time
from functools import lru_cache
from pathlib import Path
from typing import Any

import gift_model_fallbacks
import gift_upgrades

#: Telegram does not know this id, so it can never collide with a real gift.
GIFT_ID = "7390000000000000001"

#: ``telegram_mtproto_gift_catalog`` column that marks our own catalogue entries.  The
#: catalogue sync skips them, and the storefront hides them when the administrator unsets
#: ``is_available``, which is not how Telegram's own unlisted gifts behave.
MARKER_COLUMN = "is_waysgram"

#: Catalog position of a WaysGram gift.  Telegram sorts its own gifts by this positional
#: value; ours is placed after all of them, so the Telegram catalogue keeps its order.
POSITION = 1_000_000

#: Telegram gift whose patterns and backdrops an upgraded card of ours reuses.  The list is
#: a preference order: the first gift that already has both sets in the local catalogue is
#: used, so a server that has synced only part of Telegram still shows real patterns.
DONOR_GIFTS: tuple[str, ...] = (
    "5898012527257715797",  # Vice Cream: 300 patterns, 80 backdrops
    "5999277561060787166",  # Chill Flame
    "5913517067138499193",  # Perfume Bottle
    "5868503709637411929",  # Diamond Ring
)

_PACKS = Path(__file__).with_name("assets") / "gift_models"

#: ``gift_id -> settings`` of every WaysGram-exclusive gift.  A pack without an entry here
#: is still a valid artwork fallback for a Telegram gift (Plush Pepe), it is simply not a
#: shop item of its own.
GIFTS: dict[str, dict[str, Any]] = {
    GIFT_ID: {
        "title": "Mellstroy",
        "emoji": "🎁",
        "price_stars": 5000,
        "upgrade_stars": 25,
        "supply": 1000,
        "position": POSITION,
    },
}

#: Telegram's own gift ids that must never be treated as WaysGram entries.
_TELEGRAM_ONLY = frozenset({"5868455043362980631", gift_model_fallbacks.PLUSH_PEPE_ID})


def is_waysgram_gift(gift_id: Any) -> bool:
    """True only for a gift this module owns; Telegram's ids always answer ``False``."""
    key = str(gift_id or "").strip()
    return key in GIFTS and key not in _TELEGRAM_ONLY


def settings(gift_id: Any) -> dict[str, Any] | None:
    key = str(gift_id or "").strip()
    return GIFTS.get(key) if is_waysgram_gift(key) else None


def _suffix_mime(name: str) -> str:
    suffix = Path(name).suffix.lower()
    return {".webm": "video/webm", ".tgs": "application/x-tgsticker"}.get(suffix, "")


@lru_cache(maxsize=8)
def _manifest(gift_id: str) -> dict[str, Any]:
    try:
        data = json.loads((_PACKS / gift_id / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    if str(data.get("gift_id") or "") != gift_id or not isinstance(data.get("models"), list):
        return {}
    return data


def _pack_file(gift_id: str, filename: str) -> Path | None:
    """``None`` unless the name is a plain file inside this gift's own pack."""
    if not filename or Path(filename).name != filename:
        return None
    path = _PACKS / gift_id / filename
    return path if path.is_file() else None


def sticker(gift_id: Any, animated: bool) -> tuple[Path, str] | None:
    """Artwork of the base (non-upgraded) gift: its own animation or its first frame.

    The animation is the ``OSNOVNOI`` clip of the pack - the same WebM the client plays in
    the shop - and the still is the transparent PNG poster, so a storefront that cannot
    animate still shows the gift instead of a placeholder.
    """
    key = str(gift_id or "").strip()
    if not is_waysgram_gift(key):
        return None
    filename, mime = ("main.webm", "video/webm") if animated else ("main.png", "image/png")
    path = _pack_file(key, filename)
    return (path, mime) if path is not None else None


def models(gift_id: Any) -> list[dict[str, Any]]:
    """Upgraded models of the gift: name, synthetic document id, file, mime and size."""
    key = str(gift_id or "").strip()
    result: list[dict[str, Any]] = []
    for row in _manifest(key).get("models") or []:
        if not isinstance(row, dict) or str(row.get("kind") or "") != "model":
            continue
        name = str(row.get("name") or "").strip()
        try:
            document_id = int(row.get("document_id") or 0)
        except (TypeError, ValueError):
            continue
        path = _pack_file(key, str(row.get("file") or ""))
        mime = _suffix_mime(str(row.get("file") or ""))
        if not name or document_id >= 0 or path is None or not mime:
            continue
        result.append({
            "name": name,
            "document_id": document_id,
            "path": path,
            "mime_type": mime,
            "size": int(row.get("bytes") or path.stat().st_size),
        })
    return result


def _rarity_of(permille: int) -> str:
    # Display-only badge: chances themselves travel separately.
    if permille >= 100:
        return "common"
    if permille >= 60:
        return "uncommon"
    if permille >= 30:
        return "rare"
    return "epic"


def model_chances(gift_id: Any) -> list[dict[str, Any]]:
    """Every upgrade model in pool order, with its published chance.

    The pool of :file:`gift_upgrade_pools.json` carries the weighted draw; the models of
    :data:`gift_upgrades.SPECIAL_MODELS` are drawn independently, before the pool, and are
    listed with ``permille`` 0 because 0.05 % is not expressible in permille.
    """
    key = str(gift_id or "").strip()
    _, pool = gift_upgrades.pool_for_gift(key)
    chance: dict[str, dict[str, Any]] = {}
    for entry in (pool or {}).get("models") or []:
        if not isinstance(entry, list) or not entry:
            continue
        permille = int(entry[1]) if len(entry) > 1 else 0
        chance[str(entry[0])] = {"permille": permille, "rarity": _rarity_of(permille)}
    rows: list[dict[str, Any]] = []
    for model in models(key):
        meta = chance.get(model["name"], {"permille": 0, "rarity": "epic"})
        rows.append({**model, **meta})
    known = {row["name"] for row in rows}
    for special in gift_upgrades.special_models(key):
        name = str(special.get("name") or "")
        if name in known:
            continue
        model = next((item for item in models(key) if item["name"] == name), None)
        if model is None:
            continue
        rows.append({
            **model,
            "permille": 0,
            "rarity": str(special.get("rarity") or "legendary"),
            "special": True,
            "chance_percent": float(special.get("chance_percent") or 0.0),
        })
        known.add(name)
    return rows


async def donor_gift_id(db: Any) -> str:
    """The donor whose patterns and backdrops are borrowed, or ``""`` when none is synced."""
    for candidate in DONOR_GIFTS:
        try:
            cur = await db.execute(
                """SELECT kind,COUNT(*) FROM telegram_mtproto_gift_attributes
                   WHERE gift_id=? AND is_available=1 AND kind IN ('pattern','backdrop')
                   GROUP BY kind""",
                (candidate,),
            )
            counts = {str(row[0]): int(row[1]) for row in await cur.fetchall()}
        except Exception:  # noqa: BLE001 - trimmed-down schemas have no attribute table
            return ""
        if counts.get("pattern", 0) >= gift_upgrades.MIN_LIVE_ROWS and \
                counts.get("backdrop", 0) >= gift_upgrades.MIN_LIVE_ROWS:
            return candidate
    return ""


async def attribute_rows(db: Any, gift_id: Any) -> dict[str, list[dict[str, Any]]]:
    """Attribute rows of a WaysGram gift, shaped exactly like Telegram's own rows.

    The models are ours; the patterns and backdrops are the donor's rows, copied with their
    documents and colours.  ``source_gift_id`` names the gift that must serve a borrowed
    document, because a download is always requested under the upgraded gift's id.
    """
    key = str(gift_id or "").strip()
    if not is_waysgram_gift(key):
        return {}
    grouped: dict[str, list[dict[str, Any]]] = {"model": [], "pattern": [], "backdrop": []}
    for position, row in enumerate(model_chances(key)):
        grouped["model"].append({
            "kind": "model",
            "position": position,
            "name": row["name"],
            "rarity": row["rarity"],
            "permille": int(row["permille"]),
            "document_id": int(row["document_id"]),
            "mime_type": row["mime_type"],
            "size": int(row["size"]),
            "center_color": 0,
            "edge_color": 0,
            "pattern_color": 0,
            "text_color": 0,
        })
    donor = await donor_gift_id(db)
    if not donor:
        return grouped
    cur = await db.execute(
        """SELECT kind,position,name,rarity,permille,document_id,mime_type,size,
                  center_color,edge_color,pattern_color,text_color
           FROM telegram_mtproto_gift_attributes
           WHERE gift_id=? AND is_available=1 AND kind IN ('pattern','backdrop')
           ORDER BY kind,position""",
        (donor,),
    )
    for row in await cur.fetchall():
        kind = str(row[0] or "")
        if kind not in grouped:
            continue
        grouped[kind].append({
            "kind": kind,
            "position": int(row[1] or 0),
            "name": str(row[2] or ""),
            "rarity": str(row[3] or ""),
            "permille": int(row[4] or 0),
            "document_id": int(row[5] or 0),
            "mime_type": str(row[6] or ""),
            "size": int(row[7] or 0),
            "center_color": int(row[8] or 0),
            "edge_color": int(row[9] or 0),
            "pattern_color": int(row[10] or 0),
            "text_color": int(row[11] or 0),
            "source_gift_id": donor,
        })
    return grouped


async def document_gift_id(db: Any, gift_id: Any, document_id: Any) -> str:
    """Gift that owns one attribute document of ours, for the download endpoint.

    A negative id belongs to the bundled pack and is served locally, so it is returned
    unchanged.  A positive id is a Telegram document of the donor gift.
    """
    key = str(gift_id or "").strip()
    if not is_waysgram_gift(key):
        return key
    try:
        numeric = int(document_id)
    except (TypeError, ValueError):
        return key
    if numeric <= 0:
        return key
    donor = await donor_gift_id(db)
    if not donor:
        return key
    cur = await db.execute(
        """SELECT 1 FROM telegram_mtproto_gift_attributes
           WHERE gift_id=? AND document_id=? LIMIT 1""",
        (donor, numeric),
    )
    return donor if await cur.fetchone() else key


async def attributes_payload(db: Any, gift_id: Any) -> dict[str, Any]:
    """``/attributes`` answer of a WaysGram gift, built without asking Telegram."""
    key = str(gift_id or "").strip()
    rows = await attribute_rows(db, key)
    models_json: list[dict[str, Any]] = []
    patterns: list[dict[str, Any]] = []
    backdrops: list[dict[str, Any]] = []
    for kind, slot in (("model", models_json), ("pattern", patterns), ("backdrop", backdrops)):
        for row in rows.get(kind) or []:
            item = {
                "kind": kind,
                "name": row["name"],
                "position": row["position"],
                "rarity": row["rarity"],
                "permille": row["permille"],
            }
            if kind == "backdrop":
                for field in ("center_color", "edge_color", "pattern_color", "text_color"):
                    item[field] = row[field]
            else:
                if not row.get("document_id"):
                    continue
                owner = str(row.get("source_gift_id") or key)
                item.update({
                    "document_id": row["document_id"],
                    "mime_type": row.get("mime_type") or "",
                    "size": row.get("size") or None,
                    "url": f"/api/telegram/gifts/{owner}/attributes/{row['document_id']}",
                    "cached": False,
                })
            slot.append(item)
    return {
        "gift_id": key,
        "models": models_json,
        "patterns": patterns,
        "backdrops": backdrops,
        "count": len(models_json) + len(patterns) + len(backdrops),
        "sync": {"source": "waysgram", "donor_gift_id": await donor_gift_id(db)},
    }


def catalog_row(gift_id: Any, now: int) -> dict[str, Any] | None:
    """The storefront row of a WaysGram gift, in ``telegram_mtproto_gift_catalog`` shape."""
    key = str(gift_id or "").strip()
    entry = settings(key)
    if entry is None:
        return None
    animated = sticker(key, True)
    artwork = animated or sticker(key, False)
    mime = animated[1] if animated else "image/png"
    size = 0
    if artwork is not None:
        try:
            size = int(artwork[0].stat().st_size)
        except OSError:
            size = 0
    return {
        "gift_id": key,
        "title": str(entry["title"]),
        "sticker_emoji": str(entry.get("emoji") or ""),
        "sticker_set_name": "",
        "sticker_width": 512,
        "sticker_height": 512,
        "sticker_is_animated": 0,
        "sticker_is_video": 1 if animated else 0,
        "sticker_mime_type": mime,
        "sticker_document_id": int(key),
        "sticker_dc_id": None,
        "sticker_size": size,
        "stars": int(entry["price_stars"]),
        "limited": 0,
        "sold_out": 0,
        "birthday": 0,
        "require_premium": 0,
        "limited_per_user": 0,
        "peer_color_available": 0,
        "auction": 0,
        "availability_remains": int(entry["supply"]),
        "availability_total": int(entry["supply"]),
        "availability_resale": None,
        "convert_stars": None,
        "first_sale_date": None,
        "last_sale_date": None,
        "upgrade_stars": int(entry["upgrade_stars"]),
        "resell_min_stars": None,
        "released_by_json": None,
        "per_user_total": None,
        "per_user_remains": None,
        "locked_until_date": None,
        "auction_slug": "",
        "gifts_per_round": None,
        "auction_start_date": None,
        "upgrade_variants": len(models(key)) or None,
        "background_json": None,
        "source_payload": "{}",
        "position": int(entry.get("position") or 0),
        "is_available": 1,
        "synced_at": int(now),
        MARKER_COLUMN: 1,
    }


async def ensure_catalog(db: Any) -> None:
    """Publishes every WaysGram gift that is not in the catalogue yet.

    ``INSERT OR IGNORE`` on purpose: an administrator's price, stock and "in the shop"
    decision must survive a restart, so an existing row is never overwritten.
    """
    now = int(time.time())
    for gift_id in GIFTS:
        if not is_waysgram_gift(gift_id):
            continue
        row = catalog_row(gift_id, now)
        if row is None:
            continue
        columns = list(row)
        placeholders = ",".join("?" for _ in columns)
        await db.execute(
            f"""INSERT OR IGNORE INTO telegram_mtproto_gift_catalog
                ({",".join(columns)}) VALUES({placeholders})""",
            [row[column] for column in columns],
        )
        entry = settings(gift_id) or {}
        await db.execute(
            """INSERT OR IGNORE INTO telegram_gift_prices
               (gift_id,price_stars,supply,sold,updated_at) VALUES(?,?,?,0,?)""",
            (gift_id, int(entry.get("price_stars") or 0), int(entry.get("supply") or 0), now),
        )


async def set_listed(db: Any, gift_id: Any, listed: bool) -> bool:
    """Adds or removes a WaysGram gift from the shop; returns the stored state."""
    key = str(gift_id or "").strip()
    if not is_waysgram_gift(key):
        raise ValueError("not a WaysGram gift")
    await db.execute(
        f"UPDATE telegram_mtproto_gift_catalog SET {MARKER_COLUMN}=1,is_available=? WHERE gift_id=?",
        (1 if listed else 0, key),
    )
    return listed


async def is_listed(db: Any, gift_id: Any) -> bool:
    key = str(gift_id or "").strip()
    cur = await db.execute(
        "SELECT is_available FROM telegram_mtproto_gift_catalog WHERE gift_id=?", (key,)
    )
    row = await cur.fetchone()
    return bool(row and int(row[0] or 0))
