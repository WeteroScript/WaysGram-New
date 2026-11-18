"""Original Telegram gift-upgrade pools and trait rolls.

Telegram rolls three attributes for every upgraded gift: a model, a pattern and a
backdrop. Each attribute has its own published chance (``rarity_permille``, where
1000 ‰ means "always") and backdrops additionally carry the four colours the
client uses to paint the card.

The pools in :file:`gift_upgrade_pools.json` are copied from the official
Telegram star-gift catalog (``payments.getStarGifts``) together with those
chances, so a WaysGram upgrade yields the same names, the same colours and the
same probability distribution as the original client. Attributes that Telegram
marks with a bare rarity class instead of a permille value (some models) share
the probability mass that is left over, weighted by that class.
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

#: Used when a gift is not part of the official catalog (legacy WaysGram gifts).
FALLBACK_POOL: dict[str, Any] = {
    "title": "",
    "upgrade_stars": 0,
    "models": [["Оригинальная", 1000]],
    "patterns": [["Без узора", 1000]],
    "backdrops": [
        ["Лазурный", 500, 0xD4E4FF, 0x8AB4F8, 0x2C4C7A, 0x0E2A47],
        ["Аметистовый", 300, 0xE8D9FF, 0xA97BF5, 0x4B2A7A, 0x241040],
        ["Золотой", 200, 0xFFF1CE, 0xE8B44A, 0x7A5A18, 0x3A2A08],
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


def _slot_weights(entries: Iterable[list[Any]]) -> list[int]:
    """Published permille values plus the leftover mass for class-only entries."""
    items = [entry for entry in entries if isinstance(entry, list) and entry]
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


def roll_traits(pool: dict[str, Any], now: int) -> dict[str, Any]:
    """Rolls one immutable trait set with the original Telegram chances."""
    models = [entry for entry in pool.get("models") or [] if isinstance(entry, list) and entry]
    patterns = [entry for entry in pool.get("patterns") or [] if isinstance(entry, list) and entry]
    backdrops = [entry for entry in pool.get("backdrops") or [] if isinstance(entry, list) and entry]
    if not models:
        models = FALLBACK_POOL["models"]
    if not patterns:
        patterns = FALLBACK_POOL["patterns"]
    if not backdrops:
        backdrops = FALLBACK_POOL["backdrops"]

    model = _model_traits(_choose(models, _slot_weights(models)))
    pattern = _model_traits(_choose(patterns, _slot_weights(patterns)))
    backdrop = _backdrop_traits(_choose(backdrops, _slot_weights(backdrops)))
    return {
        "version": TRAITS_VERSION,
        "rolled_at": int(now),
        "model": model,
        "pattern": pattern,
        "backdrop": backdrop,
        "rarity": _rarest((model["rarity"], pattern["rarity"], backdrop["rarity"])),
    }


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


def reveal_pool(pool: dict[str, Any] | None, traits: dict[str, Any]) -> dict[str, Any]:
    """Candidate attributes the client scrolls through before it lands on the result."""
    source = pool or FALLBACK_POOL
    models = [entry for entry in source.get("models") or [] if isinstance(entry, list) and entry]
    patterns = [entry for entry in source.get("patterns") or [] if isinstance(entry, list) and entry]
    backdrops = [entry for entry in source.get("backdrops") or [] if isinstance(entry, list) and entry]
    model_name = str((traits.get("model") or {}).get("name") or "")
    pattern_name = str((traits.get("pattern") or {}).get("name") or "")
    backdrop_name = str((traits.get("backdrop") or {}).get("name") or "")
    return {
        "models": _reel(models or FALLBACK_POOL["models"], model_name),
        "patterns": _reel(patterns or FALLBACK_POOL["patterns"], pattern_name),
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
