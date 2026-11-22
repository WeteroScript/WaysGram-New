"""Pinned, exact-name artwork fallback for WaysGram-exclusive models.

Two packs are bundled with the backend:

* Heart Locket — the official Telegram model set, kept as a last resort when a live
  file reference expires;
* Plush Pepe — the WaysGram-exclusive model, a WebM animation with an alpha channel.
  The app ships the same file, so the model is visible offline; this copy answers the
  ``attributes/{document_id}`` request when the client has not installed it yet.

Synthetic negative document IDs are local to WaysGram; they are never persisted as
Telegram IDs. Do not guess a model from its chance or position in a partial pool.
"""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

HEART_LOCKET_ID = "5868455043362980631"

#: Telegram id of Plush Pepe; the pinned pack adds the WaysGram-exclusive upgraded model.
PLUSH_PEPE_ID = "5936013938331222567"

_ROOT = Path(__file__).with_name("assets") / "gift_models"

#: Artwork formats a pack may carry. Telegram stickers are vector TGS; the WaysGram
#: model is WebM with alpha, because the app's Lottie renderer draws vector frames only.
_SUFFIX_MIME = {".tgs": "application/x-tgsticker", ".webm": "video/webm"}


@lru_cache(maxsize=16)
def _models(gift_id: str = HEART_LOCKET_ID) -> tuple[dict[str, dict[str, Any]], dict[int, dict[str, Any]], dict[int, dict[str, Any]]]:
    root = _ROOT / gift_id
    try:
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}, {}, {}
    if manifest.get("gift_id") != gift_id or not isinstance(manifest.get("models"), list):
        return {}, {}, {}
    names, synthetic, telegram = {}, {}, {}
    for row in manifest["models"]:
        if not isinstance(row, dict):
            continue
        name, filename = str(row.get("name") or "").strip(), str(row.get("file") or "")
        try:
            doc_id, telegram_id = int(row.get("document_id") or 0), int(row.get("telegram_document_id") or 0)
        except (TypeError, ValueError):
            continue
        suffix = Path(filename).suffix.lower()
        if not name or suffix not in _SUFFIX_MIME or Path(filename).name != filename or doc_id >= 0:
            continue
        path = root / filename
        if not path.is_file():
            continue
        entry = {"name": name, "document_id": doc_id, "path": path, "mime": _SUFFIX_MIME[suffix]}
        names[name.casefold()] = entry
        synthetic[doc_id] = entry
        if telegram_id > 0:
            telegram[telegram_id] = entry
    return names, synthetic, telegram


def model_for_name(gift_id: str, name: Any) -> dict[str, Any] | None:
    """Pinned artwork of one model name inside a gift's local pack (any gift with a pack)."""
    if not isinstance(name, str):
        return None
    key = str(gift_id or "").strip()
    if not key:
        return None
    return _models(key)[0].get(name.strip().casefold())


def model_for_document(gift_id: str, document_id: int, *, synthetic: bool) -> dict[str, Any] | None:
    key = str(gift_id or "").strip()
    if not key:
        return None
    return _models(key)[1 if synthetic else 2].get(document_id)


def names(gift_id: str) -> list[str]:
    """Model names available offline for a gift (empty when no pack is bundled)."""
    key = str(gift_id or "").strip()
    if not key:
        return []
    _, synthetic, telegram = _models(key)
    seen: dict[str, None] = {}
    for entry in list(synthetic.values()) + list(telegram.values()):
        seen.setdefault(str(entry["name"]), None)
    return list(seen)


# Both independently published catalogs identify Shield of Sun with the same
# document ID. No artwork is bundled for it: Telegram still has to return the
# actual document and file. This mapping is returned transiently, not stored.
_HEART_KNOWN_REMOTE_MODELS = {"shield of sun": 5226650538359158786}


def remote_document_for_name(gift_id: str, name: Any) -> int:
    if gift_id != HEART_LOCKET_ID or not isinstance(name, str):
        return 0
    return _HEART_KNOWN_REMOTE_MODELS.get(name.strip().casefold(), 0)


def is_known_remote_document(gift_id: str, document_id: int) -> bool:
    return gift_id == HEART_LOCKET_ID and document_id in _HEART_KNOWN_REMOTE_MODELS.values()
