from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import time
import weakref
from collections import OrderedDict
from pathlib import Path
from typing import Any
from io import BytesIO

from PIL import Image, UnidentifiedImageError

import aiosqlite
import gift_names
import gift_model_fallbacks
from telethon import functions, types
from telethon.extensions import BinaryReader


LOGGER = logging.getLogger(__name__)
MAX_STICKER_BYTES = 20 * 1024 * 1024
MAX_STICKER_CACHE_ITEMS = 48
PLUSH_PEPE_GIFT_ID = "5936013938331222567"

# Upgrade attributes (models, patterns, backdrops) come straight from Telegram:
# every model and pattern is a real TGS/WebM sticker document, so the client can play the
# official art instead of tinting the gift picture.
ATTRIBUTE_KINDS = ("model", "pattern", "backdrop")
ATTRIBUTE_RARITIES = ("uncommon", "rare", "epic", "legendary")
GIFT_ATTRIBUTE_TTL_SECONDS = 6 * 60 * 60
# A preview is incomplete; retry sooner so transient Telegram failures don't pin
# every upgrade to the same sample for six hours.
GIFT_ATTRIBUTE_SAMPLE_TTL_SECONDS = 60
# How many model/pattern documents per gift are downloaded ahead of time. ``0`` means
# "everything", which the operator can choose when the host has enough disk space.
PREFETCH_ATTRIBUTES_PER_GIFT = 24


def _env_flag(name: str, default: bool = True) -> bool:
    value = os.getenv(name, "").strip().lower()
    if not value:
        return default
    return value not in {"0", "false", "no", "off"}


def _env_int(name: str, default: int) -> int:
    try:
        return max(0, int(str(os.getenv(name, "") or "").strip()))
    except (TypeError, ValueError):
        return default


PREFETCH_ENABLED = _env_flag("WAYSGRAM_GIFT_PREFETCH", True)


def _value(obj: Any, name: str, default: Any = None) -> Any:
    return getattr(obj, name, default) if obj is not None else default


def _int_value(obj: Any, name: str) -> int | None:
    value = _value(obj, name)
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _flag(obj: Any, name: str) -> int:
    return int(bool(_value(obj, name, False)))


def _tl_json(obj: Any) -> str | None:
    if obj is None:
        return None
    try:
        if hasattr(obj, "to_dict"):
            value = obj.to_dict()
        else:
            value = {"value": str(obj)}
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)
    except Exception:
        return json.dumps({"value": str(obj)}, ensure_ascii=False)


def _sticker_metadata(document: Any) -> dict[str, Any]:
    attributes = list(_value(document, "attributes", []) or [])
    emoji = ""
    set_name = ""
    width = None
    height = None
    animated = False
    video = False
    for attribute in attributes:
        name = type(attribute).__name__
        if name == "DocumentAttributeSticker":
            emoji = str(_value(attribute, "alt", "") or emoji)
            sticker_set = _value(attribute, "stickerset")
            set_name = str(_value(sticker_set, "short_name", "") or set_name)
        if name == "DocumentAttributeImageSize":
            width = _int_value(attribute, "w")
            height = _int_value(attribute, "h")
        if name == "DocumentAttributeAnimated":
            animated = True
        if name == "DocumentAttributeVideo":
            video = True
    mime = str(_value(document, "mime_type", "") or "application/octet-stream")
    animated = animated or mime == "application/x-tgsticker"
    video = video or mime.startswith("video/")
    return {
        "emoji": emoji[:32],
        "set_name": set_name[:128],
        "width": width,
        "height": height,
        "is_animated": animated,
        "is_video": video,
        "mime_type": mime[:128],
        "document_id": _int_value(document, "id"),
        "dc_id": _int_value(document, "dc_id"),
        "size": _int_value(document, "size"),
    }


def _rarity_name(attribute: Any) -> str:
    """Rarity class of an attribute: Telegram reports it as a constructor or a permille."""
    rarity = _value(attribute, "rarity")
    if rarity is None:
        return ""
    name = type(rarity).__name__
    if name == "StarGiftAttributeRarity":
        return ""
    suffix = name.replace("StarGiftAttributeRarity", "").strip().lower()
    return suffix if suffix in ATTRIBUTE_RARITIES else ""


def _rarity_permille(attribute: Any) -> int:
    """Published chance of an attribute in permille, for both Telegram schema revisions."""
    direct = _int_value(attribute, "rarity_permille")
    if direct is not None:
        return max(0, direct)
    rarity = _value(attribute, "rarity")
    if isinstance(rarity, int) and not isinstance(rarity, bool):
        return max(0, rarity)
    value = _int_value(rarity, "permille")
    return max(0, value or 0)


def _attribute_source(rows: list[dict[str, Any]], api: str) -> str:
    """Whether the stored rows describe the whole set or only a sample of every kind."""
    if not rows:
        return "empty"
    models = sum(1 for row in rows if row.get("kind") == "model")
    patterns = sum(1 for row in rows if row.get("kind") == "pattern")
    if api == "attributes" and (models > 1 or patterns > 1):
        return "full"
    return "sample"

def _attribute_payloads(attributes: Any) -> list[dict[str, Any]]:
    """Flatten Telegram's upgrade attribute list into rows the API can store and serve."""
    rows: list[dict[str, Any]] = []
    counters = {kind: 0 for kind in ATTRIBUTE_KINDS}
    for attribute in attributes or []:
        class_name = type(attribute).__name__
        if class_name == "StarGiftAttributeModel":
            # Craft-only models are not obtainable through a normal upgrade.
            if _value(attribute, "crafted", False):
                continue
            kind = "model"
        elif class_name == "StarGiftAttributePattern":
            kind = "pattern"
        elif class_name == "StarGiftAttributeBackdrop":
            kind = "backdrop"
        else:
            continue
        name = str(_value(attribute, "name", "") or "").strip()[:160]
        if not name:
            continue
        row: dict[str, Any] = {
            "kind": kind,
            "name": name,
            "position": counters[kind],
            "rarity": _rarity_name(attribute),
            "permille": _rarity_permille(attribute),
            "document_id": 0,
            "dc_id": None,
            "mime_type": "",
            "size": None,
            "center_color": None,
            "edge_color": None,
            "pattern_color": None,
            "text_color": None,
            "payload_json": _tl_json(attribute) or "{}",
        }
        counters[kind] += 1
        if kind == "backdrop":
            for field in ("center_color", "edge_color", "pattern_color", "text_color"):
                row[field] = _int_value(attribute, field)
        else:
            document = _value(attribute, "document")
            metadata = _sticker_metadata(document)
            # Keep the original TL document (including its file reference) so a restart
            # does not require a fresh complete attribute response for every old gift.
            if isinstance(document, types.Document):
                try:
                    payload = json.loads(row["payload_json"])
                    payload["_waysgram_document_tl"] = base64.b64encode(bytes(document)).decode("ascii")
                    row["payload_json"] = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
                except (TypeError, ValueError):
                    LOGGER.warning("Could not serialize gift attribute document %s", metadata["document_id"])
            row["document_id"] = metadata["document_id"] or 0
            row["dc_id"] = metadata["dc_id"]
            row["mime_type"] = metadata["mime_type"]
            row["size"] = metadata["size"]
        rows.append(row)
    return rows


def _sniff_media_type(content: bytes) -> str:
    """Mime type of downloaded artwork, detected from its signature."""
    if not content:
        return "application/octet-stream"
    if content.startswith(bytes.fromhex("89504E470D0A1A0A")):
        return "image/png"
    if content.startswith(bytes.fromhex("FFD8FF")):
        return "image/jpeg"
    if content.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if len(content) >= 12 and content[:4] == b"RIFF" and content[8:12] == b"WEBP":
        return "image/webp"
    if content.startswith(b"\x1f\x8b"):
        return "application/x-tgsticker"
    if content.startswith(bytes.fromhex("1A45DFA3")):
        return "video/webm"
    return "application/octet-stream"


def _gift_display_name(title: Any, sticker_set_name: Any, gift_id: Any = "") -> str:
    return gift_names.resolve_gift_name(title, sticker_set_name, gift_id)[0]


def _gift_payload(gift: Any, position: int, synced_at: int) -> dict[str, Any]:
    document = _value(gift, "sticker")
    sticker = _sticker_metadata(document)
    gift_id = _int_value(gift, "id")
    emoji = sticker["emoji"]
    title = _gift_display_name(_value(gift, "title", ""), sticker["set_name"], gift_id)
    return {
        "gift_id": str(gift_id),
        "title": title[:160],
        "sticker_emoji": emoji,
        "sticker_set_name": sticker["set_name"],
        "sticker_width": sticker["width"],
        "sticker_height": sticker["height"],
        "sticker_is_animated": int(sticker["is_animated"]),
        "sticker_is_video": int(sticker["is_video"]),
        "sticker_mime_type": sticker["mime_type"],
        "sticker_document_id": sticker["document_id"],
        "sticker_dc_id": sticker["dc_id"],
        "sticker_size": sticker["size"],
        "stars": max(0, _int_value(gift, "stars") or 0),
        "limited": _flag(gift, "limited"),
        "sold_out": _flag(gift, "sold_out"),
        "birthday": _flag(gift, "birthday"),
        "require_premium": _flag(gift, "require_premium"),
        "limited_per_user": _flag(gift, "limited_per_user"),
        "peer_color_available": _flag(gift, "peer_color_available"),
        "auction": _flag(gift, "auction"),
        "availability_remains": _int_value(gift, "availability_remains"),
        "availability_total": _int_value(gift, "availability_total"),
        "availability_resale": _int_value(gift, "availability_resale"),
        "convert_stars": _int_value(gift, "convert_stars"),
        "first_sale_date": _int_value(gift, "first_sale_date"),
        "last_sale_date": _int_value(gift, "last_sale_date"),
        "upgrade_stars": _int_value(gift, "upgrade_stars"),
        "resell_min_stars": _int_value(gift, "resell_min_stars"),
        "released_by_json": _tl_json(_value(gift, "released_by")),
        "per_user_total": _int_value(gift, "per_user_total"),
        "per_user_remains": _int_value(gift, "per_user_remains"),
        "locked_until_date": _int_value(gift, "locked_until_date"),
        "auction_slug": str(_value(gift, "auction_slug", "") or "")[:256],
        "gifts_per_round": _int_value(gift, "gifts_per_round"),
        "auction_start_date": _int_value(gift, "auction_start_date"),
        "upgrade_variants": _int_value(gift, "upgrade_variants"),
        "background_json": _tl_json(_value(gift, "background")),
        "source_payload": json.dumps({
            "id": gift_id,
            "title": title,
            "sticker": sticker,
            "stars": max(0, _int_value(gift, "stars") or 0),
            "limited": bool(_value(gift, "limited", False)),
            "sold_out": bool(_value(gift, "sold_out", False)),
            "birthday": bool(_value(gift, "birthday", False)),
            "require_premium": bool(_value(gift, "require_premium", False)),
            "limited_per_user": bool(_value(gift, "limited_per_user", False)),
            "peer_color_available": bool(_value(gift, "peer_color_available", False)),
            "auction": bool(_value(gift, "auction", False)),
            "availability_remains": _int_value(gift, "availability_remains"),
            "availability_total": _int_value(gift, "availability_total"),
            "availability_resale": _int_value(gift, "availability_resale"),
            "convert_stars": _int_value(gift, "convert_stars"),
            "first_sale_date": _int_value(gift, "first_sale_date"),
            "last_sale_date": _int_value(gift, "last_sale_date"),
            "upgrade_stars": _int_value(gift, "upgrade_stars"),
            "resell_min_stars": _int_value(gift, "resell_min_stars"),
            "released_by": json.loads(_tl_json(_value(gift, "released_by")) or "null"),
            "per_user_total": _int_value(gift, "per_user_total"),
            "per_user_remains": _int_value(gift, "per_user_remains"),
            "locked_until_date": _int_value(gift, "locked_until_date"),
            "auction_slug": str(_value(gift, "auction_slug", "") or ""),
            "gifts_per_round": _int_value(gift, "gifts_per_round"),
            "auction_start_date": _int_value(gift, "auction_start_date"),
            "upgrade_variants": _int_value(gift, "upgrade_variants"),
            "background": json.loads(_tl_json(_value(gift, "background")) or "null"),
        }, ensure_ascii=False, separators=(",", ":"), default=str),
        "position": position,
        "synced_at": synced_at,
    }


class TelegramMTProtoGiftCatalog:
    """MTProto reader; the optional user client is used only for upgrade attributes."""

    def __init__(self, client: Any, database_path: Path | str | None = None,
                 attribute_client: Any | None = None):
        self.client = client
        self.attribute_client = attribute_client if attribute_client is not None else client
        self._sync_lock = asyncio.Lock()
        self._media_load_lock = asyncio.Lock()
        self._gifts: dict[str, Any] = {}
        self._media_cache: OrderedDict[str, tuple[bytes, str]] = OrderedDict()
        # Telegram documents of the upgrade attributes, needed for their download
        # (id and access_hash do not survive a serialisation round trip).
        self._attribute_documents: dict[str, dict[int, Any]] = {}
        self._attribute_locks: dict[str, asyncio.Lock] = {}
        self._document_locks: weakref.WeakValueDictionary[int, asyncio.Lock] = weakref.WeakValueDictionary()
        self._prefetch_task: asyncio.Task | None = None
        self._prefetch_state: dict[str, Any] = {
            "enabled": PREFETCH_ENABLED,
            "running": False,
            "total": 0,
            "done": 0,
            "failed": 0,
            "attributes": 0,
            "started_at": 0,
            "finished_at": 0,
            "asset_dir": "",
        }
        self._database_path: Path | None = Path(database_path) if database_path else None

    async def sync(self, database_path: Path) -> dict[str, Any]:
        self._database_path = Path(database_path)
        async with self._sync_lock:
            response = await self.client(
                functions.payments.GetStarGiftsRequest(hash=0)
            )
            if type(response).__name__ == "StarGiftsNotModified":
                now = int(time.time())
                async with aiosqlite.connect(database_path) as db:
                    cur = await db.execute(
                        "SELECT COUNT(*) FROM telegram_mtproto_gift_catalog WHERE is_available=1"
                    )
                    count = int((await cur.fetchone())[0])
                    await db.execute(
                        """INSERT INTO telegram_gift_sync_state(id,last_synced_at,gift_count,source)
                           VALUES(1,?,?,'telegram_mtproto')
                           ON CONFLICT(id) DO UPDATE SET
                               last_synced_at=excluded.last_synced_at,
                               gift_count=excluded.gift_count,
                               source=excluded.source""",
                        (now, count),
                    )
                    await db.execute(
                        """INSERT OR IGNORE INTO telegram_auction_gift_registry
                           (gift_id,title,auction_slug,auction_start_date,gifts_per_round,
                            discovered_at,source_payload)
                           SELECT gift_id,title,auction_slug,auction_start_date,gifts_per_round,
                                  synced_at,source_payload
                           FROM telegram_mtproto_gift_catalog
                           WHERE auction=1
                             AND TRIM(COALESCE(auction_slug,''))<>''
                             AND auction_start_date IS NOT NULL
                              AND auction_start_date<=?
                             AND COALESCE(gifts_per_round,0)>0""",
                        (now,),
                    )
                    await db.commit()
                return {
                    "last_synced_at": now,
                    "gift_count": count,
                    "source": "telegram_mtproto",
                }

            gifts = _value(response, "gifts")
            if not isinstance(gifts, (list, tuple)):
                raise RuntimeError("MTProto payments.getStarGifts returned an unexpected result")

            now = int(time.time())
            payloads = [
                _gift_payload(gift, position, now)
                for position, gift in enumerate(gifts)
                if _int_value(gift, "id") is not None and _value(gift, "sticker") is not None
            ]
            if gifts and not payloads:
                raise RuntimeError("MTProto gift list contained no valid StarGift objects")

            columns = [
                "gift_id", "title", "sticker_emoji", "sticker_set_name",
                "sticker_width", "sticker_height", "sticker_is_animated",
                "sticker_is_video", "sticker_mime_type", "sticker_document_id",
                "sticker_dc_id", "sticker_size", "stars", "limited", "sold_out",
                "birthday", "require_premium", "limited_per_user", "peer_color_available",
                "auction", "availability_remains", "availability_total", "availability_resale",
                "convert_stars", "first_sale_date", "last_sale_date", "upgrade_stars",
                "resell_min_stars", "released_by_json", "per_user_total", "per_user_remains",
                "locked_until_date", "auction_slug", "gifts_per_round", "auction_start_date",
                "upgrade_variants", "background_json", "source_payload", "position", "synced_at",
            ]
            placeholders = ",".join("?" for _ in columns)
            updates = ",".join(
                f"{column}=excluded.{column}" for column in columns if column != "gift_id"
            )
            values = [tuple(item[column] for column in columns) for item in payloads]

            async with aiosqlite.connect(database_path) as db:
                await db.execute("BEGIN IMMEDIATE")
                if values:
                    await db.executemany(
                        f"""INSERT INTO telegram_mtproto_gift_catalog
                            ({",".join(columns)},is_available)
                            VALUES({placeholders},1)
                            ON CONFLICT(gift_id) DO UPDATE SET {updates},is_available=1""",
                        values,
                    )
                    gift_ids = [item["gift_id"] for item in payloads]
                    placeholders_ids = ",".join("?" for _ in gift_ids)
                    await db.execute(
                        f"""UPDATE telegram_mtproto_gift_catalog
                            SET is_available=0 WHERE gift_id NOT IN ({placeholders_ids})""",
                        gift_ids,
                    )
                else:
                    await db.execute(
                        "UPDATE telegram_mtproto_gift_catalog SET is_available=0"
                    )
                await db.execute(
                    """INSERT INTO telegram_gift_sync_state(id,last_synced_at,gift_count,source)
                       VALUES(1,?,?,'telegram_mtproto')
                       ON CONFLICT(id) DO UPDATE SET
                           last_synced_at=excluded.last_synced_at,
                           gift_count=excluded.gift_count,
                           source=excluded.source""",
                    (now, len(values)),
                )
                await db.execute(
                    """INSERT OR IGNORE INTO telegram_auction_gift_registry
                       (gift_id,title,auction_slug,auction_start_date,gifts_per_round,
                        discovered_at,source_payload)
                       SELECT gift_id,title,auction_slug,auction_start_date,gifts_per_round,
                              synced_at,source_payload
                       FROM telegram_mtproto_gift_catalog
                       WHERE auction=1
                         AND TRIM(COALESCE(auction_slug,''))<>''
                         AND auction_start_date IS NOT NULL
                              AND auction_start_date<=?
                         AND COALESCE(gifts_per_round,0)>0""",
                    (now,),
                )
                await db.commit()

            self._gifts = {
                str(_value(gift, "id")): gift
                for gift in gifts
                if _int_value(gift, "id") is not None and _value(gift, "sticker") is not None
            }
            self._media_cache.clear()
            return {
                "last_synced_at": now,
                "gift_count": len(values),
                "source": "telegram_mtproto",
            }

    # ------------------------------------------------------------ asset cache

    def set_database_path(self, database_path: Path | str | None) -> None:
        if database_path is not None:
            self._database_path = Path(database_path)

    def _asset_directory(self, database_path: Path | str | None = None) -> Path | None:
        """Folder with the artwork downloaded ahead of time, next to the database."""
        override = os.getenv("WAYSGRAM_GIFT_ASSET_DIR", "").strip()
        if override:
            directory = Path(override)
        else:
            root = Path(database_path) if database_path else self._database_path
            if root is None:
                return None
            directory = root.parent / "gift_assets"
        try:
            directory.mkdir(parents=True, exist_ok=True)
        except OSError:
            return None
        return directory

    def _asset_file(
            self, document_id: int, database_path: Path | str | None = None,
            suffix: str = ".bin"
    ) -> Path | None:
        if not document_id:
            return None
        directory = self._asset_directory(database_path)
        if directory is None:
            return None
        return directory / f"{int(document_id)}{suffix}"

    def asset_path(
            self, document_id: int, database_path: Path | str | None = None,
            suffix: str = ".bin"
    ) -> Path | None:
        return self._asset_file(document_id, database_path, suffix)

    def _read_asset(
            self, document_id: int, database_path: Path | str | None = None,
            suffix: str = ".bin"
    ) -> bytes | None:
        path = self._asset_file(document_id, database_path, suffix)
        if path is None or not path.is_file():
            return None
        try:
            content = path.read_bytes()
        except OSError:
            return None
        return content or None

    def _write_asset(
            self, document_id: int, content: bytes,
            database_path: Path | str | None = None, suffix: str = ".bin"
    ) -> None:
        path = self._asset_file(document_id, database_path, suffix)
        if path is None or not content:
            return
        temporary = path.with_name(path.name + ".tmp")
        try:
            temporary.write_bytes(content)
            if path.exists():
                path.unlink()
            temporary.rename(path)
        except OSError:
            try:
                temporary.unlink()
            except OSError:
                pass

    async def _download_document(self, document: Any, document_id: int) -> tuple[bytes, str]:
        """Download one Telegram document once and keep it in the on-disk cache."""
        size = _int_value(document, "size")
        if size is not None and size > MAX_STICKER_BYTES:
            raise ValueError("Telegram sticker file exceeds the supported size")
        payload = await self.attribute_client.download_media(document, file=bytes)
        if hasattr(payload, "getvalue"):
            payload = payload.getvalue()
        if not isinstance(payload, (bytes, bytearray)) or not payload:
            raise RuntimeError("MTProto did not return sticker bytes")
        content = bytes(payload)
        if len(content) > MAX_STICKER_BYTES:
            raise ValueError("Telegram sticker file exceeds the supported size")
        mime_type = str(_value(document, "mime_type", "") or "") or _sniff_media_type(content)
        self._write_asset(document_id, content)
        return content, mime_type

    # ------------------------------------------------------- upgrade attributes

    async def _request_upgrade_attributes(self, gift_id: int) -> tuple[list[Any], str]:
        """Official upgrade attributes of one gift, with their real TGS/WebM documents.

        The second value names the call that answered: ``attributes`` is the complete set,
        ``preview`` is Telegram's sample of every kind (one model, one pattern, one
        backdrop) and ``none`` means nothing came back.
        """
        getter = getattr(functions.payments, "GetStarGiftUpgradeAttributesRequest", None)
        if getter is not None:
            try:
                response = await self.attribute_client(getter(gift_id=gift_id))
                attributes = _value(response, "attributes")
                if attributes:
                    return list(attributes), "attributes"
            except Exception as error:
                # A preview is useful as a last resort, but it is not a complete set.
                LOGGER.warning("Gift %s full attribute request failed: %s", gift_id, type(error).__name__)
        try:
            response = await self.attribute_client(
                functions.payments.GetStarGiftUpgradePreviewRequest(gift_id=gift_id)
            )
        except Exception as error:
            LOGGER.warning("Gift %s attribute preview failed: %s", gift_id, type(error).__name__)
            return [], "none"
        return list(_value(response, "sample_attributes") or []), "preview"


    def _keep_attribute_documents(self, gift_id: str, attributes: Any) -> None:
        documents: dict[int, Any] = {}
        for attribute in attributes or []:
            document = _value(attribute, "document")
            if document is None:
                continue
            document_id = _int_value(document, "id") or 0
            if document_id:
                documents[document_id] = document
        if documents:
            # A later partial response must not erase documents already in use by gifts.
            self._attribute_documents.setdefault(gift_id, {}).update(documents)

    async def sync_upgrade_attributes(
            self, database_path: Path | str, gift_id: str
    ) -> list[dict[str, Any]]:
        """Read the gift's official model/pattern/backdrop set from Telegram and store it."""
        key = str(gift_id or "").strip()
        try:
            numeric_id = int(key)
        except (TypeError, ValueError):
            raise ValueError("Telegram gift id must be numeric") from None
        attributes, api = await self._request_upgrade_attributes(numeric_id)
        rows = _attribute_payloads(attributes)
        source = _attribute_source(rows, api)
        now = int(time.time())
        columns = (
            "gift_id", "kind", "position", "name", "rarity", "permille", "document_id",
            "dc_id", "mime_type", "size", "center_color", "edge_color", "pattern_color",
            "text_color", "payload_json", "fetched_at",
        )
        values = [
            (
                key, row["kind"], row["position"], row["name"], row["rarity"],
                row["permille"], row["document_id"], row["dc_id"], row["mime_type"],
                row["size"], row["center_color"], row["edge_color"], row["pattern_color"],
                row["text_color"], row["payload_json"], now,
            )
            for row in rows
        ]
        async with aiosqlite.connect(database_path) as db:
            await db.execute("BEGIN IMMEDIATE")
            cursor = await db.execute(
                "SELECT source FROM telegram_mtproto_gift_attribute_sync WHERE gift_id=?", (key,)
            )
            existing = await cursor.fetchone()
            # Temporary API errors, or a preview response, must not destroy a full set
            # that is already referenced by owned gifts. Don't extend its TTL either.
            if not rows or (existing and existing[0] == "full" and source != "full"):
                await db.rollback()
                self._keep_attribute_documents(key, attributes)
                previous, _ = await self._read_attributes(database_path, key)
                return previous
            await db.execute(
                "DELETE FROM telegram_mtproto_gift_attributes WHERE gift_id=?", (key,)
            )
            if values:
                await db.executemany(
                    f"""INSERT INTO telegram_mtproto_gift_attributes
                        ({",".join(columns)},is_available)
                        VALUES({",".join("?" for _ in columns)},1)""",
                    values,
                )
            await db.execute(
                """INSERT INTO telegram_mtproto_gift_attribute_sync
                   (gift_id,attribute_count,source,fetched_at) VALUES(?,?,?,?)
                   ON CONFLICT(gift_id) DO UPDATE SET
                       attribute_count=excluded.attribute_count,
                       source=excluded.source,
                       fetched_at=excluded.fetched_at""",
                (key, len(values), source, now),
            )
            await db.commit()
        self._keep_attribute_documents(key, attributes)
        return rows

    async def _read_attributes(
            self, database_path: Path | str, gift_id: str
    ) -> tuple[list[dict[str, Any]], int]:
        async with aiosqlite.connect(database_path) as db:
            db.row_factory = aiosqlite.Row
            cur = await db.execute(
                """SELECT kind,position,name,rarity,permille,document_id,dc_id,mime_type,
                          size,center_color,edge_color,pattern_color,text_color,payload_json
                   FROM telegram_mtproto_gift_attributes
                   WHERE gift_id=? AND is_available=1
                   ORDER BY kind,position""",
                (gift_id,),
            )
            rows = [dict(row) for row in await cur.fetchall()]
            cur = await db.execute(
                "SELECT fetched_at,source FROM telegram_mtproto_gift_attribute_sync WHERE gift_id=?",
                (gift_id,),
            )
            sync_row = await cur.fetchone()
        fetched_at = int(sync_row["fetched_at"] or 0) if sync_row is not None else 0
        if fetched_at and sync_row["source"] != "full":
            # Express the shorter sample TTL using the same freshness calculation below.
            fetched_at -= GIFT_ATTRIBUTE_TTL_SECONDS - GIFT_ATTRIBUTE_SAMPLE_TTL_SECONDS
        return rows, fetched_at

    async def attribute_sync_state(
            self, database_path: Path | str | None, gift_id: str
    ) -> dict[str, Any]:
        """Diagnostics: how many attribute rows are stored and where they came from.

        ``source`` is ``full`` only when Telegram published the whole model/pattern set;
        ``sample`` means the catalog answered with one sample of every kind, which is the
        reason behind a card that cannot show a model.
        """
        key = str(gift_id or "").strip()
        database_path = database_path if database_path is not None else self._database_path
        if not key or database_path is None:
            return {}
        try:
            async with aiosqlite.connect(database_path) as db:
                db.row_factory = aiosqlite.Row
                cur = await db.execute(
                    """SELECT attribute_count,source,fetched_at
                       FROM telegram_mtproto_gift_attribute_sync WHERE gift_id=?""",
                    (key,),
                )
                sync_row = await cur.fetchone()
                cur = await db.execute(
                    """SELECT kind,COUNT(*) AS total,
                              SUM(CASE WHEN document_id>0 THEN 1 ELSE 0 END) AS with_document
                       FROM telegram_mtproto_gift_attributes
                       WHERE gift_id=? AND is_available=1 GROUP BY kind""",
                    (key,),
                )
                kinds = {str(item["kind"]): dict(item) for item in await cur.fetchall()}
        except Exception:  # noqa: BLE001 - diagnostics must never break a request
            return {}
        return {
            "gift_id": key,
            "source": str(sync_row["source"] or "") if sync_row is not None else "",
            "attribute_count": int(sync_row["attribute_count"] or 0) if sync_row is not None else 0,
            "fetched_at": int(sync_row["fetched_at"] or 0) if sync_row is not None else 0,
            "kinds": kinds,
        }

    async def attributes(
            self, database_path: Path | str | None, gift_id: str, refresh: bool = False
    ) -> list[dict[str, Any]]:
        """Cached official attribute set; fetched from Telegram when missing or stale."""
        key = str(gift_id or "").strip()
        database_path = database_path if database_path is not None else self._database_path
        if not key or database_path is None:
            return []
        rows, fetched_at = await self._read_attributes(database_path, key)
        stale = int(time.time()) - fetched_at > GIFT_ATTRIBUTE_TTL_SECONDS
        if not refresh and rows and not stale:
            return rows
        lock = self._attribute_locks.setdefault(key, asyncio.Lock())
        async with lock:
            if not refresh:
                rows, fetched_at = await self._read_attributes(database_path, key)
                if rows and int(time.time()) - fetched_at <= GIFT_ATTRIBUTE_TTL_SECONDS:
                    return rows
            try:
                return await self.sync_upgrade_attributes(database_path, key)
            except Exception:
                # Telegram can be temporarily unavailable: stale rows still describe
                # the gift correctly, and the artwork cache keeps working offline.
                return rows

    async def _attribute_document(
            self, database_path: Path | str, gift_id: str, document_id: int
    ) -> Any:
        key = str(gift_id or "").strip()
        documents = self._attribute_documents.get(key) or {}
        document = documents.get(int(document_id))
        if document is not None:
            return document
        # Try the persisted document first. File references can expire, in which case
        # download_attribute will refresh the set and retry with a fresh document.
        async with aiosqlite.connect(database_path) as db:
            cur = await db.execute(
                """SELECT payload_json FROM telegram_mtproto_gift_attributes
                   WHERE gift_id=? AND document_id=? AND is_available=1 LIMIT 1""",
                (key, int(document_id)),
            )
            row = await cur.fetchone()
        if row:
            try:
                encoded = json.loads(row[0] or "{}").get("_waysgram_document_tl", "")
                if encoded:
                    with BinaryReader(base64.b64decode(encoded, validate=True)) as reader:
                        document = reader.tgread_object()
                    if isinstance(document, types.Document) and document.id == int(document_id):
                        self._attribute_documents.setdefault(key, {})[int(document_id)] = document
                        return document
            except Exception as error:  # noqa: BLE001 - old/corrupt TL data must fall back to Telegram
                LOGGER.warning("Stored gift document %s could not be restored: %s", document_id, type(error).__name__)
        # A small, gift-scoped allowlist has an independently identified exact
        # document. Resolve that first instead of waiting for the full collection
        # refresh, which can be slow or return only a preview.
        document = await self._known_remote_model(key, int(document_id))
        if document is not None:
            return document
        await self.attributes(database_path, key, refresh=True)
        documents = self._attribute_documents.get(key) or {}
        return documents.get(int(document_id))

    async def _known_remote_model(self, gift_id: str, document_id: int) -> Any:
        # An explicitly identified model may be resolvable by document ID even when
        # the full gift-attribute method only returns a sample. Telegram can still
        # decline or return no document; never substitute a different model.
        if not gift_model_fallbacks.is_known_remote_document(gift_id, document_id):
            return None
        try:
            result = await self.attribute_client(
                functions.messages.GetCustomEmojiDocumentsRequest(document_id=[document_id])
            )
            for document in result or []:
                if isinstance(document, types.Document) and document.id == document_id:
                    self._attribute_documents.setdefault(gift_id, {})[document_id] = document
                    return document
        except Exception as error:
            LOGGER.warning("Known gift model %s could not be resolved: %s", document_id, type(error).__name__)
        return None

    async def download_attribute(
            self, database_path: Path | str, gift_id: str, document_id: int
    ) -> tuple[bytes, str]:
        """Bytes of one model or pattern animation, served from the disk cache."""
        try:
            numeric_id = int(document_id)
        except (TypeError, ValueError):
            raise ValueError("document id must be numeric") from None
        cached = self._read_asset(numeric_id, database_path)
        if cached:
            return cached, _sniff_media_type(cached)
        # Prefetch and many clients can request the same document concurrently. Share one
        # Telegram download and one cache write; independent documents stay parallel.
        lock = self._document_locks.setdefault(numeric_id, asyncio.Lock())
        async with lock:
            cached = self._read_asset(numeric_id, database_path)
            if cached:
                return cached, _sniff_media_type(cached)
            document = await self._attribute_document(database_path, gift_id, numeric_id)
            if document is None:
                raise LookupError("Telegram attribute document is unavailable")
            try:
                return await self._download_document(document, numeric_id)
            except Exception:
                # A stored Telegram file reference can expire; refresh it once before failing.
                await self.attributes(database_path, str(gift_id), refresh=True)
                fresh = (self._attribute_documents.get(str(gift_id)) or {}).get(numeric_id)
                if fresh is None or fresh is document:
                    fresh = await self._known_remote_model(str(gift_id), numeric_id)
                if fresh is None or fresh is document:
                    raise
                return await self._download_document(fresh, numeric_id)

    # -------------------------------------------------------------- prefetching

    def prefetch_state(self) -> dict[str, Any]:
        directory = self._asset_directory()
        state = dict(self._prefetch_state)
        state["enabled"] = PREFETCH_ENABLED
        state["running"] = bool(self._prefetch_task and not self._prefetch_task.done())
        state["asset_dir"] = str(directory) if directory else ""
        return state

    async def start_prefetch(
            self, database_path: Path | str, gift_ids: list[str] | None = None
    ) -> dict[str, Any]:
        """Download the artwork of the whole catalog in the background, one gift at a time."""
        self.set_database_path(database_path)
        if not PREFETCH_ENABLED:
            return self.prefetch_state()
        if self._prefetch_task is not None and not self._prefetch_task.done():
            return self.prefetch_state()
        self._prefetch_task = asyncio.create_task(
            self._prefetch_worker(Path(database_path), gift_ids)
        )
        return self.prefetch_state()

    async def _catalog_artwork_ids(self, database_path: Path) -> list[str]:
        """Every gift the shop can show: available MTProto gifts plus WaysGram-priced ones.

        Manually added or sold-out gifts live only in the Bot API tables, and the shop still
        sells them, so their artwork has to be downloaded as well.
        """
        queries = (
            "SELECT gift_id FROM telegram_mtproto_gift_catalog WHERE is_available=1 ORDER BY position",
            "SELECT gift_id FROM telegram_gift_prices WHERE price_stars>0",
            "SELECT gift_id FROM telegram_gift_catalog ORDER BY position",
        )
        seen: list[str] = []
        known: set[str] = set()
        async with aiosqlite.connect(database_path) as db:
            for query in queries:
                try:
                    cur = await db.execute(query)
                    rows = await cur.fetchall()
                except Exception:
                    # Trimmed-down test schemas may not have every catalog table.
                    continue
                for row in rows:
                    gift_id = str(row[0] or "").strip()
                    if gift_id and gift_id not in known:
                        known.add(gift_id)
                        seen.append(gift_id)
        return seen

    async def _prefetch_worker(
            self, database_path: Path, gift_ids: list[str] | None
    ) -> None:
        state = self._prefetch_state
        state.update({
            "running": True,
            "total": 0,
            "done": 0,
            "failed": 0,
            "attributes": 0,
            "started_at": int(time.time()),
            "finished_at": 0,
        })
        limit = _env_int("WAYSGRAM_GIFT_PREFETCH_ATTRIBUTES", PREFETCH_ATTRIBUTES_PER_GIFT)
        try:
            ids = [str(value) for value in (gift_ids or []) if str(value).strip()]
            if not ids:
                ids = await self._catalog_artwork_ids(database_path)
            state["total"] = len(ids)
            for gift_id in ids:
                try:
                    await self.download_sticker(gift_id, database_path)
                except Exception:
                    # Sold-out gifts are gone from payments.getStarGifts; their upgrade
                    # attributes still hold the real artwork, so try that instead.
                    try:
                        await self.sold_out_artwork(database_path, gift_id)
                    except Exception:
                        state["failed"] = int(state["failed"]) + 1
                try:
                    rows = await self.attributes(database_path, gift_id)
                except Exception:
                    rows = []
                downloaded = 0
                for row in rows:
                    if row["kind"] not in ("model", "pattern"):
                        continue
                    if not row["document_id"]:
                        continue
                    if limit and downloaded >= limit:
                        break
                    try:
                        await self.download_attribute(
                            database_path, gift_id, int(row["document_id"])
                        )
                        downloaded += 1
                        state["attributes"] = int(state["attributes"]) + 1
                    except Exception:
                        continue
                state["done"] = int(state["done"]) + 1
                await asyncio.sleep(0)
        except Exception:
            state["failed"] = int(state["failed"]) + 1
        finally:
            state["running"] = False
            state["finished_at"] = int(time.time())

    async def sold_out_artwork(
            self, database_path: Path | str, gift_id: str
    ) -> tuple[bytes, str] | None:
        """The gift's own animation taken from its model attribute.

        Every Telegram model document is the whole gift rendered with that model, so a gift
        that disappeared from ``payments.getStarGifts`` (Plush Pepe and other sold-out
        collections) can still be shown and animated with its official artwork.
        """
        key = str(gift_id or "").strip()
        database_path = database_path if database_path is not None else self._database_path
        try:
            attributes = await self.attributes(database_path, key)
        except Exception:
            return None
        for row in attributes:
            if row["kind"] != "model":
                continue
            document_id = int(row["document_id"] or 0)
            if not document_id:
                continue
            cached = self._read_asset(document_id, database_path)
            if cached:
                return cached, _sniff_media_type(cached)
            try:
                return await self.download_attribute(database_path, key, document_id)
            except Exception:
                continue
        return None

    async def _ensure_gift_documents(self) -> None:
        """Rehydrate process-local Telegram sticker documents after a restart."""
        if self._gifts:
            return
        async with self._media_load_lock:
            if self._gifts:
                return
            response = await self.client(
                functions.payments.GetStarGiftsRequest(hash=-1)
            )
            if type(response).__name__ == "StarGiftsNotModified":
                raise LookupError("Telegram returned no gift documents for a cold cache")
            gifts = _value(response, "gifts")
            if not isinstance(gifts, (list, tuple)):
                raise RuntimeError("MTProto did not return a gift document list")
            loaded = {
                str(_value(gift, "id")): gift
                for gift in gifts
                if _int_value(gift, "id") is not None and _value(gift, "sticker") is not None
            }
            if not loaded:
                raise LookupError("Telegram gift documents are unavailable")
            self._gifts = loaded

    async def download_sticker_preview(
            self, gift_id: str, database_path: Path | str | None = None
    ) -> tuple[bytes, str]:
        """Try available still thumbnails from largest to smallest."""
        key = str(gift_id)
        cache_key = "preview:" + key
        cached = self._media_cache.get(cache_key)
        if cached is not None:
            self._media_cache.move_to_end(cache_key)
            return cached

        document_id = _int_value(
            _value(self._gifts.get(key), "sticker"), "id"
        ) or (int(key) if key.isdigit() else 0)
        stored = self._read_asset(document_id, database_path)
        if stored:
            # Only the original animation is cached; an animated asset is still the best
            # preview Android can show (it decodes the first frame).
            return stored, _sniff_media_type(stored)

        try:
            await self._ensure_gift_documents()
        except Exception:
            # The main catalog can be unavailable even while the sold-out gift's
            # model is already cached (or its own attribute request still works).
            fallback = await self.sold_out_artwork(database_path, key)
            if fallback is not None:
                return fallback
            raise
        gift = self._gifts.get(key)
        if gift is None:
            fallback = await self.sold_out_artwork(database_path, key)
            if fallback is not None:
                return fallback
            raise LookupError("Telegram gift is not present in the current MTProto cache")
        document = _value(gift, "sticker")
        metadata = _sticker_metadata(document)
        thumbnails = list(_value(document, "thumbs", []) or [])
        candidates = [
            thumb for thumb in thumbnails
            if "VideoSize" not in type(thumb).__name__
            and "PhotoPathSize" not in type(thumb).__name__
        ]
        candidates.sort(
            key=lambda value: (
                (_int_value(value, "w") or 0) * (_int_value(value, "h") or 0)
            ),
            reverse=True,
        )
        content = b""
        mime_type = ""
        for thumb in candidates:
            try:
                payload = await self.client.download_media(document, file=bytes, thumb=thumb)
            except Exception:
                continue
            if hasattr(payload, "getvalue"):
                payload = payload.getvalue()
            if not isinstance(payload, (bytes, bytearray)) or not payload:
                continue
            candidate = bytes(payload)
            if len(candidate) > MAX_STICKER_BYTES:
                continue
            try:
                with Image.open(BytesIO(candidate)) as image:
                    image.verify()
            except (UnidentifiedImageError, OSError, ValueError):
                continue
            if candidate.startswith(bytes.fromhex("89504E470D0A1A0A")):
                content, mime_type = candidate, "image/png"
                break
            if candidate.startswith(bytes.fromhex("FFD8FF")):
                content, mime_type = candidate, "image/jpeg"
                break
            if candidate.startswith((b"GIF87a", b"GIF89a")):
                content, mime_type = candidate, "image/gif"
                break
            if len(candidate) >= 12 and candidate[:4] == b"RIFF" and candidate[8:12] == b"WEBP":
                content, mime_type = candidate, "image/webp"
                break

        if not content:
            # Android can decode the original TGS/WebM file with AnimatedFileDrawable
            # and use its first frame when Telegram supplies no usable still thumbnail.
            return await self.download_sticker(key, database_path)

        self._write_asset(document_id, content, database_path, suffix=".preview")
        result = (content, mime_type)
        self._media_cache[cache_key] = result
        while len(self._media_cache) > MAX_STICKER_CACHE_ITEMS:
            self._media_cache.popitem(last=False)
        return result

    async def download_sticker(
            self, gift_id: str, database_path: Path | str | None = None
    ) -> tuple[bytes, str]:
        key = str(gift_id)
        cached = self._media_cache.get(key)
        if cached is not None:
            self._media_cache.move_to_end(key)
            return cached

        document_id = int(key) if key.isdigit() else 0
        stored = self._read_asset(document_id, database_path)
        if stored:
            result = (stored, _sniff_media_type(stored))
            self._media_cache[key] = result
            return result

        try:
            await self._ensure_gift_documents()
        except Exception:
            fallback = await self.sold_out_artwork(database_path, key)
            if fallback is not None:
                self._media_cache[key] = fallback
                return fallback
            raise
        gift = self._gifts.get(key)
        if gift is None:
            fallback = await self.sold_out_artwork(database_path, key)
            if fallback is not None:
                self._media_cache[key] = fallback
                return fallback
            raise LookupError("Telegram gift is not present in the current MTProto cache")
        document = _value(gift, "sticker")
        size = _int_value(document, "size")
        if size is not None and size > MAX_STICKER_BYTES:
            raise ValueError("Telegram sticker file exceeds the supported size")

        try:
            payload = await self.client.download_media(document, file=bytes)
            if hasattr(payload, "getvalue"):
                payload = payload.getvalue()
            if not isinstance(payload, (bytes, bytearray)) or not payload:
                raise RuntimeError("MTProto did not return sticker bytes")
        except Exception:
            # A sold-out item can still be present in the catalogue with an expired or
            # inaccessible document. A usable model from the user attribute session is
            # preferable to leaving both the shop and the inventory blank.
            fallback = await self.sold_out_artwork(database_path, key)
            if fallback is not None:
                self._media_cache[key] = fallback
                return fallback
            raise
        content = bytes(payload)
        if len(content) > MAX_STICKER_BYTES:
            raise ValueError("Telegram sticker file exceeds the supported size")
        mime_type = str(_value(document, "mime_type", "") or "application/octet-stream")
        self._write_asset(_int_value(document, "id") or document_id, content, database_path)
        result = (content, mime_type)
        self._media_cache[key] = result
        while len(self._media_cache) > MAX_STICKER_CACHE_ITEMS:
            self._media_cache.popitem(last=False)
        return result


# ---------------------------------------------------------------- экспорт реакций

# Слоты `availableReaction`: рисунок и анимации. Анимированные эмодзи Telegram
# (`application/x-tgsticker`) есть только на серверах Telegram, поэтому их нужно один раз
# выгрузить из авторизованной сессии и положить в APK.
REACTION_SLOTS = (
    "static_icon",
    "appear_animation",
    "select_animation",
    "activate_animation",
    "effect_animation",
    "around_animation",
    "center_icon",
)


def _document_file_name(document: Any) -> str:
    """Имя файла документа: официальный id Telegram + расширение по типу."""
    document_id = _int_value(document, "id") or 0
    mime_type = str(_value(document, "mime_type", "") or "")
    if mime_type == "application/x-tgsticker":
        extension = "tgs"
    elif mime_type == "image/webp":
        extension = "webp"
    elif mime_type == "video/webm":
        extension = "webm"
    else:
        extension = "bin"
    return f"files/{document_id}.{extension}"


async def export_reaction_emoji(client: Any, concurrency: int = 4) -> tuple[dict[str, Any], dict[str, bytes]]:
    """Выгружает официальные документы реакций Telegram.

    Возвращает манифест (`emoji -> документы по слотам`) и словарь `имя файла -> байты`.
    Один и тот же документ может стоять в нескольких слотах, поэтому файлы дедуплицируются
    по id. Скачивание идёт пачками, чтобы большой набор не растягивался на минуты.
    """
    response = await client(functions.messages.GetAvailableReactionsRequest(hash=0))
    reactions = _value(response, "reactions") or []
    manifest: dict[str, Any] = {
        "exported_at": int(time.time()),
        "count": len(reactions),
        "slots": list(REACTION_SLOTS),
        "reactions": [],
    }
    jobs: dict[str, Any] = {}
    for reaction in reactions:
        entry: dict[str, Any] = {
            "emoji": str(_value(reaction, "reaction", "") or ""),
            "title": str(_value(reaction, "title", "") or ""),
            "inactive": bool(_value(reaction, "inactive", False)),
            "documents": {},
        }
        for slot in REACTION_SLOTS:
            document = _value(reaction, slot)
            if document is None:
                continue
            file_name = _document_file_name(document)
            entry["documents"][slot] = {
                "id": _int_value(document, "id"),
                "dc_id": _int_value(document, "dc_id"),
                "mime_type": str(_value(document, "mime_type", "") or ""),
                "size": _int_value(document, "size"),
                "file": file_name,
            }
            jobs.setdefault(file_name, document)
        if entry["documents"]:
            manifest["reactions"].append(entry)

    files: dict[str, bytes] = {}
    semaphore = asyncio.Semaphore(max(1, int(concurrency)))

    async def fetch(file_name: str, document: Any) -> None:
        async with semaphore:
            payload = await client.download_media(document, file=bytes)
            if hasattr(payload, "getvalue"):
                payload = payload.getvalue()
            if not isinstance(payload, (bytes, bytearray)) or not payload:
                raise RuntimeError(f"MTProto returned no bytes for {file_name}")
            if len(payload) > MAX_STICKER_BYTES:
                raise ValueError(f"Telegram document exceeds the supported size: {file_name}")
            files[file_name] = bytes(payload)

    await asyncio.gather(*(fetch(name, document) for name, document in jobs.items()))
    manifest["files"] = len(files)
    manifest["bytes"] = sum(len(value) for value in files.values())
    return manifest, files


# Загрузка на GitHub через браузер ограничена 25 МБ на файл, поэтому большой архив
# отправляется частями: waysgram_reaction_emoji.zip.001, .002, …
REACTION_ARCHIVE_PART_BYTES = 20 * 1024 * 1024


def split_archive(data: bytes, limit: int = REACTION_ARCHIVE_PART_BYTES) -> list[bytes]:
    """Режет архив на части не больше `limit` байт (пустой вход — пустой список)."""
    if not data:
        return []
    size = max(1, int(limit))
    return [data[index:index + size] for index in range(0, len(data), size)]


def archive_part_name(index: int) -> str:
    return f"waysgram_reaction_emoji.zip.{index:03d}"
