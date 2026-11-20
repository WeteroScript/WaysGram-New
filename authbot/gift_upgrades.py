"""Original Telegram gift-upgrade pools and trait rolls.

Telegram rolls three attributes for every upgraded gift: a model, a pattern and a
backdrop. Each attribute has its own published chance (``rarity_permille``, where
1000 ‰ means "always") and backdrops additionally carry the four colours the
client uses to paint the card.

The pools in :file:`gift_upgrade_pools.json` are copied from the official
Telegram star-gift catalog (``payments.getStarGifts``) together with those
chances. Complete pools totaling 1000 permille retain their published distribution.
Incomplete pools are normalized over the available entries; attributes with only a
rarity class share the remaining mass by class weights. These are estimates, not
verified Telegram odds. The effective chance is rounded to integer permille for UI.
"""

from __future__ import annotations

import json
import secrets
from pathlib import Path
from typing import Any, Iterable

TRAITS_VERSION = 3

#: Every published chance is expressed in permille and a slot adds up to 1000.
PERMILLE_TOTAL = 1000

#: Relative weights for attributes that only carry a rarity class.
CLASS_WEIGHTS = {"uncommon": 10, "rare": 5, "epic": 2, "legendary": 1}

#: From least to most rare; used to pick the rarity badge of the whole card.
RARITY_ORDER = ("common", "uncommon", "rare", "epic", "legendary")

#: A slot is rolled from Telegram's own attribute rows only when that set offers a real
#: choice. ``payments.getStarGiftUpgradePreview`` answers with a single sample of every kind,
#: which would make every upgrade of the same gift look identical.
MIN_LIVE_ROWS = 2

#: Used when a gift is not part of the official catalog (legacy WaysGram gifts).
FALLBACK_POOL: dict[str, Any] = {
    "title": "",
    "upgrade_stars": 0,
    "models": [["Оригинальная", 1000]],
    "patterns": [["Без узора", 1000]],
    "backdrops": [
        ["Лазурный", 200, 0xD4E4FF, 0x8AB4F8, 0x2C4C7A, 0x0E2A47],
        ["Аметистовый", 160, 0xE8D9FF, 0xA97BF5, 0x4B2A7A, 0x241040],
        ["Золотой", 140, 0xFFF1CE, 0xE8B44A, 0x7A5A18, 0x3A2A08],
        ["Изумрудный", 120, 0xD6F5DC, 0x63C98A, 0x1E5A38, 0x0B2C1B],
        ["Коралловый", 100, 0xFFDFD6, 0xF2866B, 0x7A2F22, 0x3A130D],
        ["Стальной", 90, 0xE1E6EE, 0x93A2B8, 0x334155, 0x141C26],
        ["Розовый", 80, 0xFFD9EC, 0xF077B6, 0x7A2450, 0x3A0F26],
        ["Графитовый", 60, 0xD5D7DB, 0x6E7480, 0x24272E, 0x0D0F13],
        ["Медный", 50, 0xF7DFC8, 0xC98A54, 0x6B3A17, 0x301809],
    ],
}

POOLS_FILE = Path(__file__).with_name("gift_upgrade_pools.json")

_pools: dict[str, Any] | None = None
_by_title: dict[str, str] | None = None


def _load() -> tuple[dict[str, Any], dict[str, str]]:
    """Reads the pool table once and keeps it in memory."""
    global _pools, _by_title
    if _pools is None:
        try:
            data = json.loads(POOLS_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        gifts = data.get("gifts") if isinstance(data, dict) else None
        _pools = gifts if isinstance(gifts, dict) else {}
        _by_title = {}
        for key, pool in _pools.items():
            title = str(pool.get("title") or "").strip().lower()
            if title and title not in _by_title:
                _by_title[title] = key
    return _pools, _by_title or {}


def pool_for_gift(gift_id: Any, title: Any = None) -> tuple[str | None, dict[str, Any] | None]:
    """Finds the official pool of a gift by its Telegram id, then by its title."""
    pools, by_title = _load()
    value = str(gift_id or "").strip()
    if value and value in pools:
        return value, pools[value]
    name = str(title or "").strip().lower()
    if name and name in by_title:
        key = by_title[name]
        return key, pools[key]
    return None, None


def _slot_weights(entries: Iterable[list[Any]], colored: bool = False) -> list[int]:
    """Published permille values plus the leftover mass for class-only entries.

    Backdrops are published as ``[name, chance, centre, edge, pattern, text]``: their chance
    is a plain permille and must not be read as a rarity class, otherwise every backdrop of
    the gift would be equally likely instead of following Telegram's own distribution.
    """
    items = [entry for entry in entries if isinstance(entry, list) and entry]
    if colored:
        return [max(0, int(entry[1])) if len(entry) > 1 else 0 for entry in items]
    published = sum(int(entry[1]) for entry in items if len(entry) == 2)
    remaining = max(0, PERMILLE_TOTAL - published)
    flagged = [entry for entry in items if len(entry) > 2]
    flagged_total = sum(CLASS_WEIGHTS.get(str(entry[2]), 1) for entry in flagged)
    weights: list[int] = []
    for entry in items:
        if len(entry) == 2:
            weights.append(max(0, int(entry[1])))
            continue
        if flagged_total <= 0 or remaining <= 0:
            weights.append(0)
            continue
        share = CLASS_WEIGHTS.get(str(entry[2]), 1)
        weights.append(max(0, remaining * share // flagged_total))
    return weights


def _choose(entries: list[list[Any]], weights: list[int]) -> list[Any]:
    total = sum(weights)
    if total <= 0:
        return entries[0]
    roll = secrets.randbelow(total)
    seen = 0
    for entry, weight in zip(entries, weights):
        if weight <= 0:
            continue
        seen += weight
        if roll < seen:
            return entry
    return entries[-1]


def _kind(entry: list[Any]) -> str:
    """Rarity class of an attribute: explicit class, otherwise common."""
    if len(entry) > 2:
        return str(entry[2])
    return "common"


def _model_traits(entry: list[Any]) -> dict[str, Any]:
    return {
        "name": str(entry[0]),
        "permille": int(entry[1]) if len(entry) > 1 else 0,
        "rarity": _kind(entry),
    }


def _backdrop_traits(entry: list[Any]) -> dict[str, Any]:
    return {
        "name": str(entry[0]),
        "permille": int(entry[1]) if len(entry) > 1 else 0,
        "center_color": int(entry[2]) if len(entry) > 2 else 0,
        "edge_color": int(entry[3]) if len(entry) > 3 else 0,
        "pattern_color": int(entry[4]) if len(entry) > 4 else 0,
        "text_color": int(entry[5]) if len(entry) > 5 else 0,
        "rarity": "common",
    }


def _rarest(kinds: Iterable[str]) -> str:
    best = 0
    for kind in kinds:
        try:
            best = max(best, RARITY_ORDER.index(kind))
        except ValueError:
            continue
    return RARITY_ORDER[best]


def _pick(entries: list[list[Any]], weights: list[int]) -> list[Any]:
    # Each upgrade is an independent draw. Excluding the old value changes every
    # advertised probability and makes it impossible for an attribute to repeat.
    return _choose(entries, weights)


def _effective_permille(weight: int, weights: list[int]) -> int:
    # UI protocol has integer permille; return the nearest representable chance.
    total = sum(weights)
    return (weight * PERMILLE_TOTAL + total // 2) // total if total > 0 else 0


def _live_rows(live: Any, kind: str) -> list[dict[str, Any]]:
    """Telegram's own rows of one slot, with a name and something to play."""
    rows: list[dict[str, Any]] = []
    source = live.get(kind) if isinstance(live, dict) else None
    for row in source or []:
        if not isinstance(row, dict):
            continue
        name = str(row.get("name") or "").strip()
        if not name:
            continue
        # A model or a pattern without its animation cannot be drawn, so it is not a
        # candidate; a backdrop only needs its colours.
        if kind in ("model", "pattern") and not int(row.get("document_id") or 0):
            continue
        rows.append(row)
    return rows


def _live_weights(rows: list[dict[str, Any]]) -> list[int]:
    """Published chances of Telegram's rows, with the leftover mass for class-only rows."""
    published = sum(
        int(row.get("permille") or 0) for row in rows if int(row.get("permille") or 0) > 0
    )
    remaining = max(0, PERMILLE_TOTAL - published)
    flagged = [row for row in rows if int(row.get("permille") or 0) <= 0]
    flagged_total = sum(CLASS_WEIGHTS.get(str(row.get("rarity") or ""), 1) for row in flagged)
    weights: list[int] = []
    for row in rows:
        chance = int(row.get("permille") or 0)
        if chance > 0:
            weights.append(chance)
        elif flagged_total > 0 and remaining > 0:
            share = CLASS_WEIGHTS.get(str(row.get("rarity") or ""), 1)
            weights.append(max(0, remaining * share // flagged_total))
        else:
            weights.append(0)
    return weights


def _pick_row(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Picks one of Telegram's rows with an independent weighted draw."""
    entries = [[str(row.get("name") or ""), int(row.get("permille") or 0)] for row in rows]
    chosen = _pick(entries, _live_weights(rows))
    return rows[entries.index(chosen)]


def _live_traits(row: dict[str, Any], kind: str, pool_size: int) -> dict[str, Any]:
    """Traits of one attribute taken from Telegram, including its animation."""
    traits: dict[str, Any] = {
        "name": str(row.get("name") or "").strip(),
        "permille": int(row.get("permille") or 0),
        "rarity": "common" if kind == "backdrop" else str(row.get("rarity") or "common"),
    }
    if kind == "backdrop":
        for key in ("center_color", "edge_color", "pattern_color", "text_color"):
            traits[key] = int(row.get(key) or 0)
    document_id = int(row.get("document_id") or 0)
    if document_id:
        traits["document_id"] = document_id
    position = row.get("position")
    if position is not None:
        traits["position"] = int(position)
    traits["pool_size"] = int(pool_size)
    return traits


def _live_slot(live: Any, kind: str) -> dict[str, Any] | None:
    """Rolls one slot from Telegram's own set, or ``None`` when that set is too small."""
    rows = _live_rows(live, kind)
    if len(rows) < MIN_LIVE_ROWS:
        return None
    weights = _live_weights(rows)
    selected = _pick_row(rows)
    result = _live_traits(selected, kind, len(rows))
    result["permille"] = _effective_permille(weights[rows.index(selected)], weights)
    return result


def roll_traits(
        pool: dict[str, Any],
        now: int,
        previous: dict[str, Any] | None = None,
        live: dict[str, list[dict[str, Any]]] | None = None,
) -> dict[str, Any]:
    """Rolls one trait set using available weighted chances.

    Each roll is independent, including repeat upgrades; an attribute may repeat.
    Displayed permille is the effective weighted chance, rounded to the nearest permille
    when a source pool is incomplete or contains class-only entries.

    ``live`` are Telegram's own attribute rows of the gift. When a slot has a real set there,
    the roll happens inside it and the chosen row brings its animation document with it, so
    the card can play the official model instead of depending on a name lookup later. The
    static pool stays the source for gifts Telegram publishes only a sample for.
    """
    models = [entry for entry in pool.get("models") or [] if isinstance(entry, list) and entry]
    patterns = [entry for entry in pool.get("patterns") or [] if isinstance(entry, list) and entry]
    backdrops = [entry for entry in pool.get("backdrops") or [] if isinstance(entry, list) and entry]
    if not models:
        models = FALLBACK_POOL["models"]
    if not patterns:
        patterns = FALLBACK_POOL["patterns"]
    if not backdrops:
        backdrops = FALLBACK_POOL["backdrops"]

    live_hits = {
        "model": _live_slot(live, "model"),
        "pattern": _live_slot(live, "pattern"),
        "backdrop": _live_slot(live, "backdrop"),
    }
    def pool_slot(entries: list[list[Any]], *, colored: bool = False) -> dict[str, Any]:
        weights = _slot_weights(entries, colored=colored)
        selected = _pick(entries, weights)
        result = _backdrop_traits(selected) if colored else _model_traits(selected)
        result["permille"] = _effective_permille(weights[entries.index(selected)], weights)
        return result

    model = live_hits["model"] or pool_slot(models)
    pattern = live_hits["pattern"] or pool_slot(patterns)
    backdrop = live_hits["backdrop"] or pool_slot(backdrops, colored=True)
    used = sum(1 for hit in live_hits.values() if hit is not None)
    return {
        "version": TRAITS_VERSION,
        "rolled_at": int(now),
        "roll_source": "telegram" if used == 3 else ("mixed" if used else "pool"),
        "model": model,
        "pattern": pattern,
        "backdrop": backdrop,
        "rarity": _rarest((model["rarity"], pattern["rarity"], backdrop["rarity"])),
    }


def pool_from_rows(live: Any) -> dict[str, Any]:
    """The pool shape used by the reveal, built from Telegram's own rows.

    The reels must show the same names the roll produced: when the roll came from Telegram
    the static JSON pool of the gift is not the set the winner belongs to.
    """
    def slot(kind: str, colored: bool) -> list[list[Any]]:
        entries: list[list[Any]] = []
        for row in _live_rows(live, kind):
            entry: list[Any] = [str(row.get("name") or ""), int(row.get("permille") or 0)]
            if colored:
                entry += [
                    int(row.get("center_color") or 0),
                    int(row.get("edge_color") or 0),
                    int(row.get("pattern_color") or 0),
                    int(row.get("text_color") or 0),
                ]
            entries.append(entry)
        return entries

    result = {
        "models": slot("model", False),
        "patterns": slot("pattern", False),
        "backdrops": slot("backdrop", True),
    }
    return {key: value for key, value in result.items() if value}


def effective_reveal_pool(base: dict[str, Any], live: Any) -> dict[str, Any]:
    """Mirror the per-slot live/fallback selection used by roll_traits."""
    result = dict(base)
    live_pool = pool_from_rows(live)
    for kind, slot in (("model", "models"), ("pattern", "patterns"), ("backdrop", "backdrops")):
        if len(_live_rows(live, kind)) >= MIN_LIVE_ROWS:
            result[slot] = live_pool[slot]
    return result


def _reel(entries: list[list[Any]], winner: str, limit: int = 40) -> list[list[Any]]:
    """Thins a slot down for the reveal animation, always keeping the winner."""
    if len(entries) <= limit:
        return [list(entry) for entry in entries]
    step = max(1, len(entries) // limit)
    picked = entries[::step][:limit]
    if not any(str(entry[0]) == winner for entry in picked):
        picked = picked[:-1] + [
            next((entry for entry in entries if str(entry[0]) == winner), entries[0])
        ]
    return [list(entry) for entry in picked]


def _with_documents(
        entries: list[list[Any]], documents: dict[int, int] | None
) -> list[list[Any]]:
    """Normalises a reel to ``[name, permille, rarity, document_id]``.

    The document id is the Telegram sticker document of that attribute, so the client can
    play the official model and pattern animation. It is 0 when Telegram did not provide it.
    """
    mapping = documents or {}
    result: list[list[Any]] = []
    for position, entry in enumerate(entries):
        rarity = str(entry[2]) if len(entry) > 2 and isinstance(entry[2], str) else ""
        result.append([
            str(entry[0]),
            int(entry[1]) if len(entry) > 1 else 0,
            rarity,
            int(mapping.get(position, 0)),
        ])
    return result


def reveal_pool(
        pool: dict[str, Any] | None,
        traits: dict[str, Any],
        documents: dict[str, dict[int, int]] | None = None,
) -> dict[str, Any]:
    """Candidate attributes the client scrolls through before it lands on the result."""
    source = pool or FALLBACK_POOL
    models = [entry for entry in source.get("models") or [] if isinstance(entry, list) and entry]
    patterns = [entry for entry in source.get("patterns") or [] if isinstance(entry, list) and entry]
    backdrops = [entry for entry in source.get("backdrops") or [] if isinstance(entry, list) and entry]
    model_name = str((traits.get("model") or {}).get("name") or "")
    pattern_name = str((traits.get("pattern") or {}).get("name") or "")
    backdrop_name = str((traits.get("backdrop") or {}).get("name") or "")
    mapping = documents or {}
    models = _with_documents(models or FALLBACK_POOL["models"], mapping.get("model"))
    patterns = _with_documents(patterns or FALLBACK_POOL["patterns"], mapping.get("pattern"))
    return {
        "models": _reel(models, model_name),
        "patterns": _reel(patterns, pattern_name),
        "backdrops": _reel(backdrops or FALLBACK_POOL["backdrops"], backdrop_name),
    }


def traits_of(attributes: Any) -> dict[str, Any] | None:
    """Returns the collectible traits stored in a gift's attributes, if any."""
    if not isinstance(attributes, dict):
        return None
    traits = attributes.get("collectible_traits")
    return traits if isinstance(traits, dict) else None


def is_current_traits(traits: Any) -> bool:
    """True when the traits were rolled from the official pools."""
    if not isinstance(traits, dict):
        return False
    if traits.get("version") != TRAITS_VERSION:
        return False
    for key in ("model", "pattern", "backdrop"):
        entry = traits.get(key)
        if not isinstance(entry, dict) or not str(entry.get("name") or "").strip():
            return False
    return True
