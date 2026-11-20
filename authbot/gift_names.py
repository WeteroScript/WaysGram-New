"""Display names for Telegram gifts, without treating technical set names as authoritative."""
from __future__ import annotations

import re
from typing import Any

import gift_upgrades

_GENERIC = {
    "gift", "gifts", "star gift", "telegram", "telegram gift", "telegram gifts",
    "подарок", "подарок telegram", "подарок телеграм", "подарок телеграмм",
    "подарок в telegram", "подарок в телеграм",
}


def _generic_title(title: str) -> bool:
    folded = " ".join(title.casefold().split())
    return (
        not folded or len(folded) < 2 or folded in _GENERIC
        or folded.startswith(("telegram gift", "подарок telegram", "подарок телеграм"))
        or not any(char.isalnum() for char in folded)
    )


def _readable_set_name(value: Any) -> str:
    raw = str(value or "").strip()
    compact = "".join(c for c in raw.casefold() if c.isalnum())
    # A shared Telegram sticker set is not a collection name.
    if not compact or compact in {"telegramgifts", "telegramgift", "gifts", "gift"}:
        return ""
    readable = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", raw)
    readable = re.sub(r"([A-Z])([A-Z][a-z])", r"\1 \2", readable)
    readable = re.sub(r"[_-]+", " ", readable)
    readable = re.sub(r"\s+", " ", readable).strip()
    # Strip a prefix only at a word boundary, not the 'Gift' part of 'Gifts'.
    readable = re.sub(r"^(?:telegram\s+)?gift\s+", "", readable, flags=re.IGNORECASE).strip()
    return "" if _generic_title(readable) else readable[:160]


def resolve_gift_name(title: Any, sticker_set_name: Any, gift_id: Any = "", *,
                      emoji: Any = "", disambiguate: bool = False) -> tuple[str, int]:
    """Return (name, quality): explicit title > known collection > guessed set > placeholder.

    Quality lets callers prefer a real title from another catalogue over a placeholder.
    The checked-in ID table describes collectible collections and is a fallback, not a
    claim that every ordinary gift has an official Telegram name.
    """
    key = str(gift_id or "").strip()
    raw = str(title or "").strip()
    slug = _readable_set_name(sticker_set_name)
    compact = "".join(c for c in str(sticker_set_name or "").casefold() if c.isalnum())
    if key == "5936013938331222567" or compact.startswith("plushpepe"):
        return "Plush Pepe", 2

    # A sync may previously have saved a guessed sticker-set name as a title.
    inferred_title = bool(slug and raw.casefold() == slug.casefold())
    if not _generic_title(raw) and not inferred_title:
        return raw[:160], 3
    _, pool = gift_upgrades.pool_for_gift(key)
    known = str(pool.get("title") or "").strip() if pool else ""
    if known and not _generic_title(known):
        return known[:160], 2
    if slug:
        return slug, 1
    if not disambiguate:
        return "Подарок Telegram", 0
    symbol = str(emoji or "").strip()
    digits = "".join(c for c in key if c.isdigit())
    suffix = f" №{digits[-6:]}" if digits else (f" · {key[:16]}" if key else "")
    return f"Подарок{(' ' + symbol) if symbol else ''}{suffix}", 0
