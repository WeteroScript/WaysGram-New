package org.telegram.tgnet;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.tl.TL_stars;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Gift shop. The gifts, their names and artwork come from the Telegram catalog served by the WaysGram server
 * (/api/telegram/gifts). Prices and stock are set by the administrator and are paid in Stars on the WaysGram server
 * (/api/telegram/gifts/{id}/purchase). Until the administrator sets a price and a stock, a gift is not shown.
 */
public class WaysGramGifts {

    private static final long CATALOG_TTL_MS = 20_000;

    private static JSONArray catalogCache;
    private static long cacheAt;

    /** Catalog snapshots per Telegram account: gifts must never leak between accounts. */
    private static final HashMap<Integer, CatalogSnapshot> snapshots = new HashMap<>();

    private static final class CatalogSnapshot {
        JSONArray gifts;
        long at;
        final HashMap<Long, String> originalIds = new HashMap<>();
    }

    private static void install(CatalogSnapshot snapshot) {
        catalogCache = snapshot.gifts;
        cacheAt = snapshot.at;
        originalIds.clear();
        originalIds.putAll(snapshot.originalIds);
    }
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> failedStickers =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> retryingArtwork =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.Set<Integer> pendingCatalogRefresh =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.concurrent.ScheduledExecutorService artworkRetry =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "WaysGramGiftArtworkRetry");
                thread.setDaemon(true);
                return thread;
            });
    private static final ExecutorService artworkDownloads = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "WaysGramGiftArtwork");
        thread.setDaemon(true);
        return thread;
    });
    private static final java.util.Set<String> queuedArtwork =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    // A failed artwork download is remembered briefly only: the shop must retry on the
    // next open instead of painting a placeholder for the whole session.
    private static final long RETRY_DELAY_MS = 45 * 1000L;
    private static final HashMap<Long, String> originalIds = new HashMap<>();

    public static synchronized void invalidate() {
        snapshots.clear();
        catalogCache = null;
        cacheAt = 0;
        originalIds.clear();
        failedStickers.clear();
    }

    /** The server's id of a gift (the app only works with numbers). */
    public static synchronized String originalId(long id) {
        String original = originalIds.get(id);
        return original != null ? original : String.valueOf(id);
    }

    private static synchronized void loadCatalog(int account) throws WaysGramApi.ApiException {
        long now = System.currentTimeMillis();
        CatalogSnapshot snapshot = snapshots.get(account);
        if (snapshot != null && now - snapshot.at < CATALOG_TTL_MS) {
            install(snapshot);
            return;
        }
        JSONObject tg = WaysGramApi.get(account, "/api/telegram/gifts?all=1");
        JSONArray gifts = tg.optJSONArray("gifts");
        CatalogSnapshot fresh = new CatalogSnapshot();
        fresh.gifts = gifts == null ? new JSONArray() : gifts;
        fresh.at = now;
        for (int i = 0; i < fresh.gifts.length(); i++) {
            JSONObject gift = fresh.gifts.optJSONObject(i);
            if (gift != null) {
                String original = gift.optString("id");
                fresh.originalIds.put(parseId(original), original);
            }
        }
        snapshots.put(account, fresh);
        install(fresh);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    static long parseId(String id) {
        try {
            return Long.parseLong(id.trim());
        } catch (Exception e) {
            return id.hashCode() & 0xffffffffL;
        }
    }

    // ---------------------------------------------------------------- stickers

    private static File stickerDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "waysgram_gifts");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File ensureSticker(int account, String giftId) {
        long stickerId = org.telegram.messenger.waysgram.WaysGramGiftDocuments.documentId(giftId);
        File file = new File(stickerDir(), stickerId + ".bin");
        if (isValidSticker(file)) {
            return file;
        }
        if (file.exists() && !file.delete()) {
            // A stale or truncated file must never hide the real artwork.
            return placeholderSticker(stickerId);
        }
        Long failedAt = failedStickers.get(stickerId);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_DELAY_MS) {
            return placeholderSticker(stickerId);
        }
        byte[] bytes = downloadStickerBytes(account, giftId, true);
        if (bytes == null) {
            bytes = downloadStickerBytes(account, giftId, false);
        }
        if (bytes == null) {
            // One more try: the first call often fails right after the app started, while the
            // session or the network is still warming up.
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            bytes = downloadStickerBytes(account, giftId, true);
            if (bytes == null) {
                bytes = downloadStickerBytes(account, giftId, false);
            }
        }
        if (bytes != null && writeStickerFile(file, bytes)) {
            failedStickers.remove(stickerId);
            return file;
        }
        // The cell will show a placeholder; the log names the gift, so a server-side gap in
        // /api/telegram/gifts/{id}/sticker can be found without guessing.
        FileLog.e("WaysGramGifts: no artwork for gift " + giftId);
        failedStickers.put(stickerId, System.currentTimeMillis());
        retryArtwork(account, giftId, stickerId);
        return placeholderSticker(stickerId);
    }

    /** Retry a transient failure without leaving the catalog's placeholder cached forever. */
    private static void retryArtwork(int account, String giftId, long stickerId) {
        String key = account + "|" + giftId;
        if (!retryingArtwork.add(key)) return;
        artworkRetry.schedule(() -> {
            try {
                File file = new File(stickerDir(), stickerId + ".bin");
                if (!isValidSticker(file)) {
                    byte[] bytes = downloadStickerBytes(account, giftId, true);
                    if (bytes == null) bytes = downloadStickerBytes(account, giftId, false);
                    if (bytes == null || org.telegram.messenger.waysgram.WaysGramGiftDocuments.sniffMime(bytes) == null
                            || !writeStickerFile(file, bytes)) return;
                }
                failedStickers.remove(stickerId);
                scheduleCatalogRefresh(account);
            } finally {
                retryingArtwork.remove(key);
            }
        }, RETRY_DELAY_MS + 1000L, TimeUnit.MILLISECONDS);
    }

    private static void scheduleCatalogRefresh(int account) {
        if (!pendingCatalogRefresh.add(account)) return;
        Runnable refresh = new Runnable() {
            @Override public void run() {
                org.telegram.ui.Stars.StarsController controller =
                        org.telegram.ui.Stars.StarsController.getInstance(account);
                if (controller.giftsLoading) {
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(this, 500L);
                    return;
                }
                pendingCatalogRefresh.remove(account);
                controller.invalidateStarGifts();
            }
        };
        org.telegram.messenger.AndroidUtilities.runOnUIThread(refresh, 1500L);
    }

    private static void queueArtwork(int account, String giftId) {
        String key = account + "|" + giftId;
        Long failedAt = failedStickers.get(org.telegram.messenger.waysgram.WaysGramGiftDocuments.documentId(giftId));
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_DELAY_MS) return;
        if (!queuedArtwork.add(key)) return;
        artworkDownloads.execute(() -> {
            try {
                File result = ensureSticker(account, giftId);
                if (result != null && stickerDocumentId(result) == org.telegram.messenger.waysgram.WaysGramGiftDocuments.documentId(giftId) && isValidSticker(result)) {
                    scheduleCatalogRefresh(account);
                }
            } finally {
                queuedArtwork.remove(key);
            }
        });
    }

    private static byte[] downloadStickerBytes(int account, String giftId, boolean animated) {
        try {
            String path = "/api/telegram/gifts/" + android.net.Uri.encode(giftId) + "/sticker"
                    + (animated ? "?animated=1" : "");
            byte[] bytes = WaysGramApi.download(account, path);
            return bytes == null || bytes.length < 16
                    || org.telegram.messenger.waysgram.WaysGramGiftDocuments.sniffMime(bytes) == null
                    ? null : bytes;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static boolean isValidSticker(File file) {
        if (file == null || !file.isFile() || file.length() < 16) {
            return false;
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[16];
            int read = in.read(head);
            return read >= 4
                    && org.telegram.messenger.waysgram.WaysGramGiftDocuments.sniffMime(head) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean writeStickerFile(File file, byte[] bytes) {
        long id = stickerDocumentId(file);
        return org.telegram.messenger.waysgram.WaysGramGiftDocuments.storeSticker(id, bytes) != null;
    }

    /** Document id of the file that is actually served for this gift artwork. */
    static long stickerDocumentId(File file) {
        if (file == null) {
            return 0;
        }
        String name = file.getName();
        int dot = name.indexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        try {
            return Long.parseLong(name);
        } catch (Exception e) {
            return parseId(name);
        }
    }

    private static String mimeFor(JSONObject sticker) {
        String mime = sticker == null ? "" : sticker.optString("mime_type", "");
        if (!TextUtils.isEmpty(mime)) {
            return mime;
        }
        if (sticker != null && sticker.optBoolean("is_video")) {
            return "video/webm";
        }
        if (sticker != null && sticker.optBoolean("is_animated")) {
            return "application/x-tgsticker";
        }
        return "image/webp";
    }

    // ---------------------------------------------------------------- catalog

    public static TL_stars.StarGifts catalog(int account) throws WaysGramApi.ApiException {
        final JSONArray catalog;
        final long catalogAt;
        synchronized (WaysGramGifts.class) {
            loadCatalog(account);
            CatalogSnapshot snapshot = snapshots.get(account);
            catalog = snapshot.gifts;
            catalogAt = snapshot.at;
        }
        WaysGramApi.ensureAdminKnown(account);
        final boolean admin = WaysGramApi.isAdmin(account);

        // The shop shows only gifts that are for sale. The administrator sees every gift to be able to price it.
        TL_stars.TL_starGifts result = new TL_stars.TL_starGifts();
        result.hash = (int) (catalogAt / 1000);
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject gift = catalog.optJSONObject(i);
            if (gift == null || TextUtils.isEmpty(gift.optString("id"))
                    || (!admin && !gift.optBoolean("for_sale"))) continue;
            String id = gift.optString("id");
            long stickerId = org.telegram.messenger.waysgram.WaysGramGiftDocuments.documentId(id);
            File file = new File(stickerDir(), stickerId + ".bin");
            if (!isValidSticker(file)) {
                // Do not wait for all network downloads before displaying the catalog.
                queueArtwork(account, id);
                file = placeholderSticker(stickerId);
            }
            if (file != null) result.gifts.add(toStarGift(gift, file));
        }
        return result;
    }

    /** A plain picture for a gift whose artwork could not be downloaded, so the gift is still listed. */
    private static File placeholderSticker(long id) {
        try {
            // Keep the placeholder on its own document id: a failed download
            // must not block the real artwork forever.
            long placeholderId = (id * 1000003L + 7L) & 0x7FFFFFFFFFFFFFFFL;
            if (placeholderId == 0) {
                placeholderId = id + 1;
            }
            File file = new File(stickerDir(), placeholderId + ".bin");
            if (file.exists() && file.length() > 0) {
                return file;
            }
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0x33888888);
            canvas.drawRoundRect(24, 24, 232, 232, 40, 40, paint);
            paint.setColor(0xFF888888);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setTextSize(120);
            canvas.drawText("\uD83C\uDF81", 128, 160, paint);
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
            return file;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static TL_stars.TL_starGift toStarGift(JSONObject g, File file) {
        JSONObject sticker = g.optJSONObject("sticker");
        long id = parseId(g.optString("id"));

        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = id;
        doc.access_hash = id;
        doc.file_reference = new byte[0];
        doc.date = (int) (System.currentTimeMillis() / 1000);
        doc.mime_type = isPng(file) ? "image/png" : mimeFor(sticker);
        doc.size = file.length();
        doc.dc_id = 2;
        TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
        size.w = sticker != null && sticker.optInt("width") > 0 ? sticker.optInt("width") : 512;
        size.h = sticker != null && sticker.optInt("height") > 0 ? sticker.optInt("height") : 512;
        doc.attributes.add(size);
        TLRPC.TL_documentAttributeSticker st = new TLRPC.TL_documentAttributeSticker();
        st.alt = g.optString("emoji", "");
        st.stickerset = new TLRPC.TL_inputStickerSetEmpty();
        doc.attributes.add(st);
        TLRPC.TL_document sniffedDocument =
                org.telegram.messenger.waysgram.WaysGramGiftDocuments.buildDocument(
                        stickerDocumentId(file), file, g.optString("emoji", ""));
        if (sniffedDocument != null) {
            doc = sniffedDocument;
        }

        TL_stars.TL_starGift gift = new TL_stars.TL_starGift();
        gift.id = id;
        gift.gift_id = id;
        gift.sticker = doc;
        gift.title = g.optString("title", "");
        gift.flags |= TLObject.FLAG_5;
        gift.stars = g.optLong("price_stars");
        gift.convert_stars = 0;
        // Unlimited WaysGram supply must not render as a limited-edition counter.
        boolean waysGramUnlimitedSupply = g.optBoolean("unlimited_supply", false);
        gift.limited = !waysGramUnlimitedSupply;
        gift.availability_remains = g.optInt("remaining");
        gift.availability_total = Math.max(g.optInt("supply"), gift.availability_remains);
        gift.birthday = g.optBoolean("birthday");
        gift.require_premium = false;
        return gift;
    }

    private static boolean isPng(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[4];
            return in.read(head) == 4 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- my gifts

    /**
     * "My gifts" of the profile. The server keeps only the owner's own inventory (/api/me/gifts), so other people's
     * profiles show no gifts. Each owned gift is drawn with the artwork of the Telegram gift of the same name.
     */
    public static TL_stars.TL_payments_savedStarGifts savedGifts(int account, TL_stars.getSavedStarGifts req, long selfId) throws WaysGramApi.ApiException {
        TL_stars.TL_payments_savedStarGifts out = new TL_stars.TL_payments_savedStarGifts();
        boolean mine = req.peer instanceof TLRPC.TL_inputPeerSelf
                || (req.peer instanceof TLRPC.TL_inputPeerUser && req.peer.user_id == selfId);
        // Подарки канала лежат не в личном инвентаре, а у самого канала (см. /api/channels/{id}/gifts).
        long channelTgId = req.peer instanceof TLRPC.TL_inputPeerChannel
                ? ((TLRPC.TL_inputPeerChannel) req.peer).channel_id : 0;
        boolean channelPeer = channelTgId > 0 && !WaysGramChats.isGroupChannel(channelTgId)
                && WaysGramChats.channelServer(channelTgId) > 0;
        if ((!mine && !channelPeer) || !TextUtils.isEmpty(req.offset) || req.collection_id != 0) {
            return out;
        }
        loadCatalog(account);
        HashMap<String, JSONObject> telegramByTitle = new HashMap<>();
        HashMap<String, JSONObject> telegramById = new HashMap<>();
        for (int i = 0; i < catalogCache.length(); i++) {
            JSONObject g = catalogCache.optJSONObject(i);
            if (g != null) {
                telegramByTitle.put(normalize(g.optString("title")), g);
                telegramById.put(g.optString("id"), g);
            }
        }
        if (channelPeer) {
            JSONObject json = WaysGramApi.get(account, "/api/channels/"
                    + WaysGramChats.channelServer(channelTgId) + "/gifts");
            JSONArray rows = json.optJSONArray("gifts");
            if (rows == null) {
                return out;
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                JSONObject tg = telegramById.get(row.optString("telegram_gift_id"));
                if (tg == null) {
                    tg = telegramByTitle.get(normalize(row.optString("gift_name")));
                }
                if (tg == null) {
                    continue;
                }
                File file = ensureSticker(account, tg.optString("id"));
                if (file == null) {
                    file = placeholderSticker(parseId(tg.optString("id")));
                }
                if (file == null) {
                    continue;
                }
                TL_stars.TL_starGift gift = toStarGift(tg, file);
                gift.limited = false; // подарок канала не товар магазина: без счётчика остатка
                TL_stars.TL_savedStarGift saved = new TL_stars.TL_savedStarGift();
                saved.date = row.optInt("created_at");
                saved.gift = gift;
                saved.saved_id = row.optLong("id");
                saved.flags |= TLObject.FLAG_11;
                out.gifts.add(saved);
            }
            out.count = out.gifts.size();
            return out;
        }
        JSONObject json = WaysGramApi.get(account, "/api/me/gifts");
        JSONArray owned = json.optJSONArray("gifts");
        if (owned == null) {
            return out;
        }
        for (int i = 0; i < owned.length(); i++) {
            JSONObject row = owned.optJSONObject(i);
            if (row == null) {
                continue;
            }
            JSONObject tg = telegramByTitle.get(normalize(row.optString("gift_name")));
            if (tg == null) {
                continue; // no Telegram artwork for this gift
            }
            File file = ensureSticker(account, tg.optString("id"));
            if (file == null) {
                file = placeholderSticker(parseId(tg.optString("id")));
            }
            if (file == null) {
                continue;
            }
            TL_stars.TL_starGift gift = toStarGift(tg, file);
            gift.limited = false; // an owned gift is not a shop item: no "x of y left" badge
            TL_stars.TL_savedStarGift saved = new TL_stars.TL_savedStarGift();
            saved.date = row.optInt("acquired_at");
            saved.gift = gift;
            saved.saved_id = row.optLong("id");
            saved.flags |= TLObject.FLAG_11;
            out.gifts.add(saved);
        }
        out.count = out.gifts.size();
        return out;
    }

    // ---------------------------------------------------------------- files

    public static TLObject getFile(int account, TLRPC.TL_upload_getFile req) throws WaysGramApi.ApiException {
        if (!(req.location instanceof TLRPC.TL_inputDocumentFileLocation)) {
            return null;
        }
        long id = ((TLRPC.TL_inputDocumentFileLocation) req.location).id;
        File file = new File(stickerDir(), id + ".bin");
        if (!file.exists()) {
            // The cell asked for artwork that the catalog build did not manage to download:
            // fetch exactly this file now, so the visible cell fills itself in.
            file = fetchStickerNow(account, id);
            if (file == null) {
                return null;
            }
        }
        return serveFile(file, req);
    }

    /**
     * Downloads the artwork of one gift by the document id the view is asking for.
     *
     * <p>Only a real artwork file is returned: the placeholder lives under its own document id,
     * so it can never be served in place of the requested one.</p>
     */
    private static File fetchStickerNow(int account, long documentId) {
        String original = null;
        synchronized (WaysGramGifts.class) {
            for (java.util.Map.Entry<Long, String> entry : originalIds.entrySet()) {
                if (entry.getKey() != null && org.telegram.messenger.waysgram.WaysGramGiftDocuments.documentId(entry.getValue()) == documentId) {
                    original = entry.getValue();
                    break;
                }
            }
        }
        if (TextUtils.isEmpty(original)) {
            return null;
        }
        File file = ensureSticker(account, original);
        if (file == null || stickerDocumentId(file) != documentId) {
            return null;
        }
        return file;
    }

    /** Answers upload.getFile with the requested slice of a local file. */
    public static TLObject serveFile(File file, TLRPC.TL_upload_getFile req) throws WaysGramApi.ApiException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            int offset = (int) Math.min(req.offset, length);
            int count = (int) Math.min(Math.max(req.limit, 0), length - offset);
            byte[] chunk = new byte[count];
            raf.seek(offset);
            raf.readFully(chunk);
            TLRPC.TL_upload_file out = new TLRPC.TL_upload_file();
            out.type = new TLRPC.TL_storage_fileUnknown();
            out.mtime = (int) (file.lastModified() / 1000);
            NativeByteBuffer buffer = new NativeByteBuffer(Math.max(count, 1));
            buffer.writeBytes(chunk);
            buffer.rewind();
            if (count == 0) {
                buffer.limit(0);
            }
            out.bytes = buffer;
            return out;
        } catch (Exception e) {
            FileLog.e(e);
            throw new WaysGramApi.ApiException(500, "FILE_ERROR");
        }
    }

    // ---------------------------------------------------------------- purchase

    private static JSONObject forSale(int account, long giftId) throws WaysGramApi.ApiException {
        loadCatalog(account);
        for (int i = 0; i < catalogCache.length(); i++) {
            JSONObject g = catalogCache.optJSONObject(i);
            if (g != null && parseId(g.optString("id")) == giftId) {
                if (!g.optBoolean("for_sale")) {
                    throw new WaysGramApi.ApiException(409, "Подарок недоступен для покупки");
                }
                return g;
            }
        }
        throw new WaysGramApi.ApiException(404, "Подарок не найден");
    }

    public static TLObject paymentForm(int account, TLRPC.TL_inputInvoiceStarGift invoice) throws WaysGramApi.ApiException {
        JSONObject gift = forSale(account, invoice.gift_id);
        TLRPC.TL_payments_paymentFormStarGift form = new TLRPC.TL_payments_paymentFormStarGift();
        form.form_id = invoice.gift_id;
        TLRPC.TL_invoice inv = new TLRPC.TL_invoice();
        inv.currency = "XTR";
        TLRPC.TL_labeledPrice price = new TLRPC.TL_labeledPrice();
        price.label = gift.optString("title");
        price.amount = gift.optLong("price_stars");
        inv.prices.add(price);
        form.invoice = inv;
        return form;
    }

    public static TLObject sendForm(int account, TL_stars.TL_payments_sendStarsForm req) throws WaysGramApi.ApiException {
        if (!(req.invoice instanceof TLRPC.TL_inputInvoiceStarGift)) {
            return null;
        }
        TLRPC.TL_inputInvoiceStarGift invoice = (TLRPC.TL_inputInvoiceStarGift) req.invoice;
        forSale(account, invoice.gift_id);
        if (invoice.peer instanceof TLRPC.TL_inputPeerChannel) {
            long channelTgId = ((TLRPC.TL_inputPeerChannel) invoice.peer).channel_id;
            if (WaysGramChats.isGroupChannel(channelTgId) || WaysGramChats.channelServer(channelTgId) <= 0) {
                throw new WaysGramApi.ApiException(400, "CHANNEL_GIFT_PEER_INVALID");
            }
            long channelId = WaysGramChats.channelServer(channelTgId);
            // Подарок каналу, как в Telegram: он остаётся у канала и виден в его профиле.
            // Ошибки сервера (мало Stars, подарок закончился) обязаны дойти до интерфейса.
            try {
                WaysGramApi.post(account, "/api/channels/" + channelId + "/gifts",
                        channelGiftBody(invoice));
            } catch (WaysGramApi.ApiException e) {
                if (e.status == 409 && e.detail != null && e.detail.contains("Stars")) {
                    throw new WaysGramApi.ApiException(400, "BALANCE_TOO_LOW");
                }
                throw e;
            }
            invalidate(); // the stock changed
            TLRPC.TL_payments_paymentResult channelResult = new TLRPC.TL_payments_paymentResult();
            channelResult.updates = new TLRPC.TL_updates();
            channelResult.updates.date = (int) (System.currentTimeMillis() / 1000);
            return channelResult;
        }
        JSONObject body = new JSONObject();
        try {
            if (invoice.peer instanceof TLRPC.TL_inputPeerUser) {
                body.put("recipient_id", invoice.peer.user_id);
            }
            body.put("hide_name", invoice.hide_name);
            if (invoice.message != null && invoice.message.text != null) {
                body.put("message", invoice.message.text);
            }
        } catch (Exception ignore) {
        }
        try {
            WaysGramApi.post(account, "/api/telegram/gifts/" + android.net.Uri.encode(originalId(invoice.gift_id)) + "/purchase", body);
        } catch (WaysGramApi.ApiException e) {
            if (e.status == 409 && e.detail != null && e.detail.contains("Stars")) {
                throw new WaysGramApi.ApiException(400, "BALANCE_TOO_LOW");
            }
            throw e;
        }
        invalidate(); // the stock changed
        TLRPC.TL_payments_paymentResult result = new TLRPC.TL_payments_paymentResult();
        result.updates = new TLRPC.TL_updates();
        result.updates.date = (int) (System.currentTimeMillis() / 1000);
        return result;
    }

    private static JSONObject channelGiftBody(TLRPC.TL_inputInvoiceStarGift invoice) {
        JSONObject body = new JSONObject();
        try {
            body.put("telegram_gift_id", originalId(invoice.gift_id));
            body.put("hide_name", invoice.hide_name);
            if (invoice.message != null && invoice.message.text != null) {
                body.put("message", invoice.message.text);
            }
        } catch (Exception ignore) {
        }
        return body;
    }

    private static String newKey() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder("wg-gift-");
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
