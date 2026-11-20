"""Pinned, exact-name artwork fallback for old Heart Locket upgrades.

Synthetic negative document IDs are local to WaysGram; they are never persisted as
Telegram IDs. Do not guess a model from its chance or position in a partial pool.
"""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

HEART_LOCKET_ID = "5868455043362980631"
_ROOT = Path(__file__).with_name("assets") / "gift_models" / HEART_LOCKET_ID


@lru_cache(maxsize=1)
def _models() -> tuple[dict[str, dict[str, Any]], dict[int, dict[str, Any]], dict[int, dict[str, Any]]]:
    try:
        manifest = json.loads((_ROOT / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}, {}, {}
    if manifest.get("gift_id") != HEART_LOCKET_ID or not isinstance(manifest.get("models"), list):
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
        if not name or not filename.endswith(".tgs") or Path(filename).name != filename or doc_id >= 0:
            continue
        path = _ROOT / filename
        if not path.is_file():
            continue
        entry = {"name": name, "document_id": doc_id, "path": path}
        names[name.casefold()] = entry
        synthetic[doc_id] = entry
        if telegram_id > 0:
            telegram[telegram_id] = entry
    return names, synthetic, telegram


def model_for_name(gift_id: str, name: Any) -> dict[str, Any] | None:
    if gift_id != HEART_LOCKET_ID or not isinstance(name, str):
        return None
    return _models()[0].get(name.strip().casefold())


def model_for_document(gift_id: str, document_id: int, *, synthetic: bool) -> dict[str, Any] | None:
    if gift_id != HEART_LOCKET_ID:
        return None
    return _models()[1 if synthetic else 2].get(document_id)


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
