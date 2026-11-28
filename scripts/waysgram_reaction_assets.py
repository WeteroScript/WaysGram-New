#!/usr/bin/env python3
"""Официальные документы реакций Telegram -> ассеты и Java-таблица.

Зачем
-----
Реакция в клиенте Telegram — это не картинка, а набор документов
(`static_icon`, `appear_animation`, `select_animation`, `activate_animation`,
`effect_animation`, `around_animation`, `center_icon`). Они приходят из
`messages.getAvailableReactions`, а файлы лежат на серверах Telegram, поэтому в APK
их нужно вшить один раз.

Как получить архив
------------------
В личке с ботом от админа отправить `/exportreactions` (см. `docs/reaction-emoji-export.md`),
забрать `waysgram_reaction_emoji.zip` (или части `.001`, `.002`, … и склеить
`cat waysgram_reaction_emoji.zip.00* > waysgram_reaction_emoji.zip`).

Что делает скрипт
-----------------
1. Проверяет каждый документ: TGS — gzip+Lottie, 512×512, без растровых слоёв, ≤64 КБ;
   WebP — валидный RIFF, размер берётся из заголовка.
2. Раскладывает файлы в `assets/waysgram_emoji/tg/<id>.tgs|webp`.
3. Генерирует `WaysGramReactionAssets.java`: эмодзи -> документы по слотам.

Использование
-------------
    python3 scripts/waysgram_reaction_assets.py --export /tmp/reaction_emoji.zip
    python3 scripts/waysgram_reaction_assets.py --export /tmp/reaction_emoji.zip --stage /tmp/tg --java /tmp/WaysGramReactionAssets.java
"""

from __future__ import annotations

import argparse
import gzip
import json
import struct
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_STAGE = REPO_ROOT / "android-source/TMessagesProj/src/main/assets/waysgram_emoji/tg"
DEFAULT_JAVA = REPO_ROOT / "android-source/TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramReactionAssets.java"
ASSET_DIR = "tg"
MAX_TGS_BYTES = 64 * 1024
SLOTS = (
    "static_icon",
    "appear_animation",
    "select_animation",
    "activate_animation",
    "effect_animation",
    "around_animation",
    "center_icon",
)

HEADER = """package org.telegram.tgnet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Документы реакций Telegram: рисунок и анимации по слотам.
 *
 * <p>Сгенерировано {@code scripts/waysgram_reaction_assets.py} из выгрузки
 * {@code /exportreactions} — не редактировать вручную. Идентификаторы документов настоящие
 * (телеграмные), поэтому штатный {@code AnimatedEmojiDrawable}/{@code RLottieDrawable} играет
 * официальные анимации: {@code appear/select/activate} — появление эмодзи, {@code effect} —
 * вспышка вокруг сообщения, {@code around} — частицы в чипе.</p>
 *
 * <p>Файлы лежат в {@code assets/waysgram_emoji/tg/} и раздаются транспортом через
 * {@code upload.getFile}: клиент качает их как обычный документ.</p>
 */
final class WaysGramReactionAssets {
    private static final int MAX_TGS_BYTES = @@MAX_TGS@@;

    /** Слоты в том порядке, в котором они лежат в {@code SLOT_DOC}. */
    static final String[] SLOTS = {@@SLOTS@@};

    static final String[] EMOJI = {
@@EMOJI@@
    };

    static final String[] TITLE = {
@@TITLE@@
    };

    /** Для каждой реакции — индексы документов по слотам, -1 если слота нет. */
    private static final int[][] SLOT_DOC = {
@@SLOT_DOC@@
    };

    private static final long[] DOC_ID = {
@@DOC_ID@@
    };

    private static final int[] DOC_DC = {
@@DOC_DC@@
    };

    private static final String[] DOC_MIME = {
@@DOC_MIME@@
    };

    private static final int[] DOC_SIZE = {
@@DOC_SIZE@@
    };

    private static final int[] DOC_WIDTH = {
@@DOC_WIDTH@@
    };

    private static final int[] DOC_HEIGHT = {
@@DOC_HEIGHT@@
    };

    private static final String[] DOC_FILE = {
@@DOC_FILE@@
    };

    private static final HashMap<String, Integer> INDEX = new HashMap<>();
    private static final HashMap<Long, String> FILES = new HashMap<>();
    private static final HashMap<Long, TLRPC.Document> CACHE = new HashMap<>();

    static {
        for (int i = 0; i < EMOJI.length; i++) {
            INDEX.put(WaysGramReactions.normalize(EMOJI[i]), i);
        }
        for (int i = 0; i < DOC_ID.length; i++) {
            FILES.put(DOC_ID[i], DOC_FILE[i]);
        }
    }

    private WaysGramReactionAssets() {
    }

    static int count() {
        return EMOJI.length;
    }

    /** Индекс реакции по эмодзи (U+FE0F и оттенки кожи не важны), -1 если нет. */
    static int indexOf(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return -1;
        }
        Integer found = INDEX.get(WaysGramReactions.normalize(emoji));
        return found == null ? -1 : found;
    }

    static int slotIndex(String slot) {
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i].equals(slot)) {
                return i;
            }
        }
        return -1;
    }

    /** Документ реакции по слоту или {@code null}, если Telegram его не отдал. */
    static synchronized TLRPC.Document document(int reaction, String slot) {
        if (reaction < 0 || reaction >= SLOT_DOC.length) {
            return null;
        }
        int slotIndex = slotIndex(slot);
        if (slotIndex < 0) {
            return null;
        }
        int document = SLOT_DOC[reaction][slotIndex];
        return document < 0 ? null : document(document);
    }

    /** Документ по своему индексу. */
    static synchronized TLRPC.Document document(int index) {
        if (index < 0 || index >= DOC_ID.length) {
            return null;
        }
        TLRPC.Document cached = CACHE.get(DOC_ID[index]);
        if (cached != null) {
            return cached;
        }
        try {
            TLRPC.TL_document doc = new TLRPC.TL_document();
            doc.id = doc.access_hash = DOC_ID[index];
            doc.dc_id = DOC_DC[index];
            doc.file_reference = new byte[0];
            doc.date = (int) (System.currentTimeMillis() / 1000);
            doc.mime_type = DOC_MIME[index];
            doc.size = DOC_SIZE[index];
            TLRPC.TL_documentAttributeImageSize dimensions = new TLRPC.TL_documentAttributeImageSize();
            dimensions.w = DOC_WIDTH[index];
            dimensions.h = DOC_HEIGHT[index];
            doc.attributes.add(dimensions);
            if ("application/x-tgsticker".equals(DOC_MIME[index])) {
                doc.attributes.add(new TLRPC.TL_documentAttributeAnimated());
            }
            CACHE.put(doc.id, doc);
            return doc;
        } catch (RuntimeException e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Имя ассета по id документа — для {@code upload.getFile}. */
    static String assetName(long id) {
        return FILES.get(id);
    }

    /** Файл ассета на диске (копируется из assets при первом обращении). */
    static synchronized File assetFile(String name) throws IOException {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "waysgram_reactions_v1");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Reaction cache unavailable");
        }
        File file = new File(dir, name.replace('/', '_'));
        if (!file.isFile()) {
            File staging = File.createTempFile("reaction", ".tmp", dir);
            try {
                try (InputStream in = ApplicationLoader.applicationContext.getAssets()
                        .open("waysgram_emoji/" + name);
                     FileOutputStream out = new FileOutputStream(staging)) {
                    byte[] bytes = new byte[8192];
                    int read;
                    while ((read = in.read(bytes)) != -1) {
                        out.write(bytes, 0, read);
                    }
                }
                if (!staging.renameTo(file)) {
                    throw new IOException("Reaction cache write failed");
                }
            } finally {
                staging.delete();
            }
        }
        return file;
    }

    static TLObject getFile(TLRPC.TL_upload_getFile request) throws WaysGramApi.ApiException {
        if (!(request.location instanceof TLRPC.TL_inputDocumentFileLocation)) {
            return null;
        }
        String name = assetName(request.location.id);
        if (name == null) {
            return null;
        }
        try {
            return WaysGramGifts.serveFile(assetFile(name), request);
        } catch (IOException e) {
            throw new WaysGramApi.ApiException(500, "FILE_ERROR");
        }
    }
}
"""


def webp_size(raw: bytes) -> tuple[int, int]:
    if raw[:4] != b"RIFF" or raw[8:12] != b"WEBP":
        raise ValueError("not a WebP file")
    fourcc = raw[12:16]
    if fourcc == b"VP8 ":
        width, height = struct.unpack("<HH", raw[26:30])
        return width & 0x3FFF, height & 0x3FFF
    if fourcc == b"VP8L":
        bits = int.from_bytes(raw[21:25], "little")
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    if fourcc == b"VP8X":
        return (int.from_bytes(raw[24:27], "little") + 1,
                int.from_bytes(raw[27:30], "little") + 1)
    raise ValueError(f"unsupported WebP chunk {fourcc!r}")


def inspect(raw: bytes, mime_type: str, name: str) -> tuple[int, int]:
    """Проверяет документ и возвращает его размеры в пикселях."""
    if mime_type == "application/x-tgsticker":
        if len(raw) > MAX_TGS_BYTES:
            raise ValueError(f"{name}: TGS больше 64 КБ ({len(raw)} Б)")
        try:
            document = json.loads(gzip.decompress(raw))
        except Exception as exc:  # noqa: BLE001 - битый файл должен остановить сборку
            raise ValueError(f"{name}: не TGS ({exc})") from exc
        if document.get("tgs") != 1:
            raise ValueError(f"{name}: нет флага tgs")
        if [a for a in document.get("assets", []) if isinstance(a, dict) and a.get("p")]:
            raise ValueError(f"{name}: растровые слои в TGS недопустимы")
        return int(document.get("w", 512)), int(document.get("h", 512))
    if mime_type == "image/webp":
        return webp_size(raw)
    raise ValueError(f"{name}: неожиданный тип {mime_type!r}")


def java_string(value: str) -> str:
    out = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{out}"'


def emit_java(path: Path, reactions: list[dict]) -> None:
    emoji, titles, slot_doc = [], [], []
    documents: list[dict] = []
    index_of: dict[tuple[int, str], int] = {}
    for reaction in reactions:
        row = []
        for slot in SLOTS:
            document = reaction["documents"].get(slot)
            if document is None:
                row.append(-1)
                continue
            key = (id(reaction), slot)
            index = index_of.get(key)
            if index is None:
                index = len(documents)
                index_of[key] = index
                documents.append(document)
            row.append(index)
        emoji.append(reaction["emoji"])
        titles.append(reaction.get("title") or reaction["emoji"])
        slot_doc.append(row)

    # Одинаковые документы в разных слотах/реакциях храним одной записью.
    unique: dict[int, int] = {}
    remap: list[int] = []
    for document in documents:
        known = unique.get(document["id"])
        if known is None:
            known = len(unique)
            unique[document["id"]] = known
            remap.append(document["id"])
        elif remap[known] != document["id"]:
            raise ValueError("document id collision")
    order = {document_id: position for position, document_id in enumerate(remap)}
    doc_index = {unique[document_id]: order[document_id] for document_id in unique}
    slot_doc = [[doc_index.get(value, -1) if value >= 0 else -1 for value in row] for row in slot_doc]
    docs = [documents[0]] * 0
    by_id = {}
    for document in documents:
        by_id.setdefault(document["id"], document)
    docs = [by_id[document_id] for document_id in remap]

    def block(values, per_line: int) -> str:
        lines = []
        for start in range(0, len(values), per_line):
            lines.append("        " + ", ".join(values[start:start + per_line]) + ",")
        return "\n".join(lines)

    text = HEADER
    text = text.replace("@@MAX_TGS@@", str(MAX_TGS_BYTES))
    text = text.replace("@@SLOTS@@", ", ".join(java_string(slot) for slot in SLOTS))
    text = text.replace("@@EMOJI@@", block([java_string(value) for value in emoji], 6))
    text = text.replace("@@TITLE@@", block([java_string(value) for value in titles], 3))
    text = text.replace("@@SLOT_DOC@@", block(
        ["{" + ", ".join(str(value) for value in row) + "}" for row in slot_doc], 2))
    text = text.replace("@@DOC_ID@@", block([f"{document['id']}L" for document in docs], 4))
    text = text.replace("@@DOC_DC@@", block([str(document.get("dc_id") or 2) for document in docs], 12))
    text = text.replace("@@DOC_MIME@@", block([java_string(document["mime_type"]) for document in docs], 3))
    text = text.replace("@@DOC_SIZE@@", block([str(document["size"]) for document in docs], 10))
    text = text.replace("@@DOC_WIDTH@@", block([str(document["width"]) for document in docs], 12))
    text = text.replace("@@DOC_HEIGHT@@", block([str(document["height"]) for document in docs], 12))
    text = text.replace("@@DOC_FILE@@", block(
        [java_string(document["file"]) for document in docs], 3))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--export", type=Path, required=True, help="waysgram_reaction_emoji.zip")
    parser.add_argument("--stage", type=Path, default=DEFAULT_STAGE, help="куда положить tg/*.tgs")
    parser.add_argument("--java", type=Path, default=DEFAULT_JAVA, help="WaysGramReactionAssets.java")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    if not args.export.is_file():
        parser.error(f"нет файла выгрузки: {args.export}")
    with zipfile.ZipFile(args.export) as archive:
        bad = archive.testzip()
        if bad is not None:
            parser.error(f"архив повреждён: {bad}")
        manifest = json.loads(archive.read("manifest.json"))
        reactions = manifest.get("reactions") or []
        if not reactions:
            parser.error("в манифесте нет реакций")
        args.stage.mkdir(parents=True, exist_ok=True)
        total = 0
        for reaction in reactions:
            for slot, document in reaction["documents"].items():
                if slot not in SLOTS:
                    raise SystemExit(f"неизвестный слот {slot}")
                raw = archive.read(document["file"])
                if len(raw) != int(document["size"]):
                    raise SystemExit(f"{document['file']}: размер не совпал с манифестом")
                width, height = inspect(raw, document["mime_type"], document["file"])
                document["width"], document["height"] = width, height
                target = args.stage / Path(document["file"]).name
                target.write_bytes(raw)
                document["file"] = f"{ASSET_DIR}/{target.name}"
                total += len(raw)
    emit_java(args.java, reactions)
    if not args.quiet:
        print(f"реакций: {len(reactions)}, файлов: {sum(len(r['documents']) for r in reactions)}, байт: {total}")
        print(f"ассеты: {args.stage}")
        print(f"таблица: {args.java}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
