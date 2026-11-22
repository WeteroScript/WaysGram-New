package org.telegram.messenger.waysgram;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Official Telegram upgrade attributes — the real model and pattern animations.
 *
 * <p>Telegram publishes the whole upgrade set of a gift as sticker documents: every model is a
 * TGS/WebM animation of the gift with that model, every pattern is an animation drawn over it.
 * The backend serves the set as JSON and the documents themselves; this class downloads them
 * once into the shared gift sticker folder and hands the resulting documents to the native
 * Telegram views, so the catalog, the profile card and the upgrade reveal play the official art
 * instead of a tinted placeholder.</p>
 *
 * <p>Metadata and artwork are loaded off the UI thread with bounded concurrency; only the
 * traits needed by actual gifts are requested. Cached artwork is reused without downloading.</p>
 */
public final class WaysGramGiftAttributes {

    public static final String MODEL = "model";
    public static final String PATTERN = "pattern";

    private static final Object LOCK = new Object();
    private static final Map<String, Map<String, Entry>> METADATA = new HashMap<>();
    private static final Map<String, Long> METADATA_EXPIRES = new HashMap<>();
    // Aligned with the server's one-minute sample TTL, plus a small clock margin.
    private static final long PARTIAL_METADATA_TTL_MS = 62_000L;
    private static final int MAX_PARTIAL_RECHECKS = 2;
    private static final Map<String, Integer> PARTIAL_RECHECKS = new HashMap<>();
    private static final Set<String> PARTIAL_RECHECK_SCHEDULED = new HashSet<>();
    private static final Map<String, TLRPC.TL_document> DOCUMENTS = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>();
    private static final Set<String> DOWNLOADING = new HashSet<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static final Map<String, Integer> RETRIES = new HashMap<>();
    private static final int MAX_AUTOMATIC_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 15_000L;
    private static final Map<String, Set<String>> WANTED = new HashMap<>();
    private static final ArrayDeque<Request> QUEUE = new ArrayDeque<>();
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private static ExecutorService executor;
    private static long generation;
    private static int workersRunning;
    private static final int MAX_DOWNLOAD_WORKERS = 3;
    private static final ExecutorService downloads = Executors.newFixedThreadPool(MAX_DOWNLOAD_WORKERS, runnable -> {
        Thread thread = new Thread(runnable, "WaysGramGiftAttributeDownload");
        thread.setDaemon(true);
        return thread;
    });
    private static Context appContext;
    private static int account = -1;
    private static boolean serverPrefetchStarted;

    private WaysGramGiftAttributes() {
    }

    /**
     * Key of one attribute inside the gift's set.
     *
     * <p>Telegram localises names and spells them with typographic apostrophes
     * ("Eve’s Apple"), while a catalog dump may carry the plain character. Comparing the raw
     * strings would drop the animation of the rolled attribute, so the name is folded first.
     * </p>
     */
    private static String keyFor(String kind, String name) {
        return kind + "\u0000" + normaliseName(name);
    }

    private static String normaliseName(String name) {
        if (name == null) {
            return "";
        }
        String folded = name
                .replace('\u2019', '\'').replace('\u2018', '\'')
                .replace('\u02bc', '\'').replace('\u00b4', '\'')
                .replace('\u2010', '-').replace('\u2011', '-')
                .replace('\u2012', '-').replace('\u2013', '-')
                .replace('\u2014', '-').replace('\u2015', '-')
                .replace('\u2212', '-').replace('\u00a0', ' ');
        return folded.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static final class Entry {
        final String kind;
        final String name;
        final long documentId;
        final String mimeType;
        final String url;
        /** Slot of the attribute inside Telegram's published set, -1 when unknown. */
        final int position;
        /** Published chance of the attribute in permille, 0 when Telegram did not publish one. */
        final int permille;

        Entry(String kind, String name, long documentId, String mimeType, String url) {
            this(kind, name, documentId, mimeType, url, -1, 0);
        }

        Entry(String kind, String name, long documentId, String mimeType, String url,
              int position, int permille) {
            this.kind = kind;
            this.name = name;
            this.documentId = documentId;
            this.mimeType = mimeType;
            this.url = url;
            this.position = position;
            this.permille = permille;
        }

        String key() {
            return keyFor(kind, name);
        }
    }

    private static final class Request {
        final String giftId;
        final Entry entry;
        final long requestGeneration;

        Request(String giftId, Entry entry) {
            this.giftId = giftId;
            this.entry = entry;
            this.requestGeneration = generation;
        }

        String key() {
            return documentCacheKey(giftId, entry.documentId);
        }
    }

    private static String documentCacheKey(String giftId, long documentId) {
        return giftId + "|document|" + documentId;
    }

    /** Called on the UI thread whenever a document becomes available. */
    public interface Listener {
        void onGiftAttributesChanged(String giftId);

        default void onGiftMetadataLoaded(String giftId) { }
    }

    /**
     * Binds the helper to the application context and the account whose WaysGram session is
     * used for the store-and-forward requests.
     */
    public static void attach(Context context, int accountNumber) {
        if (context != null) {
            appContext = context.getApplicationContext() == null
                    ? context : context.getApplicationContext();
        }
        if (accountNumber >= 0) {
            account = accountNumber;
        }
    }

    private static int account() {
        if (account >= 0) {
            return account;
        }
        return UserConfig.selectedAccount;
    }

    private static synchronized ExecutorService executor() {
        if (executor == null) {
            // Slow/uncached collections must not block metadata for every other gift.
            executor = Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "WaysGramGiftAttributes");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    /** True when the gift's model and pattern list is already known. */
    public static boolean isLoaded(String giftId) {
        if (TextUtils.isEmpty(giftId)) {
            return false;
        }
        synchronized (LOCK) {
            return METADATA.containsKey(giftId);
        }
    }

    /** Loads the official attribute list of the gift once per process. */
    public static void ensure(final String giftId) {
        if (TextUtils.isEmpty(giftId)) {
            return;
        }
        if (appContext == null) {
            appContext = ApplicationLoader.applicationContext;
        }
        if (appContext == null) {
            return;
        }
        final long requestGeneration;
        synchronized (LOCK) {
            Long expires = METADATA_EXPIRES.get(giftId);
            if (METADATA.containsKey(giftId) && (expires == null || System.currentTimeMillis() < expires)) return;
            if (LOADING.contains(giftId) || FAILED.contains("load|" + giftId)) return;
            LOADING.add(giftId);
            requestGeneration = generation;
        }
        executor().execute(() -> {
            try {
                JSONObject response = org.telegram.tgnet.WaysGramApi.get(
                        account(),
                        "/api/telegram/gifts/" + Uri.encode(giftId) + "/attributes"
                );
                Map<String, Entry> entries = parse(giftId, response);
                JSONObject sync = response.optJSONObject("sync");
                boolean complete = sync != null && "full".equals(sync.optString("source"));
                synchronized (LOCK) {
                    if (generation != requestGeneration) return;
                    METADATA.put(giftId, entries);
                    if (complete) {
                        METADATA_EXPIRES.remove(giftId);
                        PARTIAL_RECHECKS.remove(giftId);
                    } else {
                        METADATA_EXPIRES.put(giftId, System.currentTimeMillis() + PARTIAL_METADATA_TTL_MS);
                    }
                }
                queueWanted(giftId);
                if (!complete) schedulePartialRecheck(giftId);
                notifyListeners(giftId);
                AndroidUtilities.runOnUIThread(() -> {
                    for (Listener listener : LISTENERS) {
                        listener.onGiftMetadataLoaded(giftId);
                    }
                });
            } catch (Exception exception) {
                FileLog.e(exception);
                synchronized (LOCK) {
                    if (generation != requestGeneration) return;
                    FAILED.add("load|" + giftId);
                    scheduleRetry("load|" + giftId, () -> ensure(giftId));
                }
            } finally {
                synchronized (LOCK) {
                    if (generation == requestGeneration) LOADING.remove(giftId);
                }
            }
        });
    }

    // Partial responses can become complete after Telegram reconnects. Retry only when
    // a visible inventory needs a missing name, at most twice without user interaction.
    private static void schedulePartialRecheck(String giftId) {
        final long expiry;
        final long recheckGeneration;
        synchronized (LOCK) {
            Long at = METADATA_EXPIRES.get(giftId);
            Set<String> wanted = WANTED.get(giftId);
            Map<String, Entry> entries = METADATA.get(giftId);
            if (at == null || wanted == null || entries == null || LISTENERS.isEmpty()
                    || PARTIAL_RECHECK_SCHEDULED.contains(giftId)) return;
            boolean missing = false;
            for (String key : wanted) {
                if (!entries.containsKey(key)) { missing = true; break; }
            }
            int attempts = PARTIAL_RECHECKS.containsKey(giftId) ? PARTIAL_RECHECKS.get(giftId) : 0;
            if (!missing || attempts >= MAX_PARTIAL_RECHECKS) return;
            PARTIAL_RECHECK_SCHEDULED.add(giftId);
            expiry = at;
            recheckGeneration = generation;
        }
        AndroidUtilities.runOnUIThread(() -> {
            synchronized (LOCK) {
                if (generation != recheckGeneration) return;
                PARTIAL_RECHECK_SCHEDULED.remove(giftId);
                Long current = METADATA_EXPIRES.get(giftId);
                if (current == null || current.longValue() != expiry || LISTENERS.isEmpty()) return;
                int attempts = PARTIAL_RECHECKS.containsKey(giftId) ? PARTIAL_RECHECKS.get(giftId) : 0;
                if (attempts >= MAX_PARTIAL_RECHECKS) return;
                PARTIAL_RECHECKS.put(giftId, attempts + 1);
            }
            ensure(giftId);
        }, Math.max(1L, expiry - System.currentTimeMillis() + 250L));
    }

    /**
     * Asks for the animation of one attribute. Returns immediately; the document arrives through
     * {@link #addListener(Listener)}.
     */
    public static void want(String giftId, String kind, String name) {
        if (TextUtils.isEmpty(giftId) || TextUtils.isEmpty(kind) || TextUtils.isEmpty(name)) {
            return;
        }
        String key = keyFor(kind, name);
        synchronized (LOCK) {
            Set<String> keys = WANTED.get(giftId);
            if (keys == null) {
                keys = new HashSet<>();
                WANTED.put(giftId, keys);
            }
            keys.add(key);
        }
        ensure(giftId);
        queueWanted(giftId);
        schedulePartialRecheck(giftId);
    }

    /**
     * Asks for the model and the pattern that the server actually rolled for this gift.
     *
     * <p>The server stores the Telegram document id inside the rolled traits, so the exact
     * animation is downloaded even when Telegram localises the attribute names. Only gifts
     * upgraded by an older backend fall back to the name lookup.</p>
     */
    public static void wantRolled(String giftId, JSONObject traits) {
        if (TextUtils.isEmpty(giftId) || traits == null) {
            return;
        }
        wantTrait(giftId, MODEL, traits, "model");
        wantTrait(giftId, PATTERN, traits, "pattern");
    }

    private static void wantTrait(
            String giftId, String kind, JSONObject traits, String key
    ) {
        Entry entry = resolveEntry(giftId, kind, traits, key);
        if (entry != null) {
            // Check device storage before queueing any network work.
            documentFor(giftId, entry);
            return;
        }
        // The set is not known yet: remember the name, so it is queued as soon as the
        // attribute list of the gift arrives.
        want(giftId, kind, WaysGramGiftTraits.name(traits, key));
    }

    /**
     * The animated document of one rolled trait.
     *
     * <p>The stored Telegram document id is the exact answer. Gifts upgraded before ids were
     * stored fall back to the slot the attribute occupies in Telegram's published set, then to
     * its published chance, and only then to its name — names are the weakest key, because
     * Telegram localises them and rewrites their typography.</p>
     */
    public static TLRPC.TL_document traitDocument(
            String giftId, String kind, JSONObject traits, String key
    ) {
        Entry entry = resolveEntry(giftId, kind, traits, key);
        return entry == null ? null : documentFor(giftId, entry);
    }

    /**
     * Finds the attribute of the gift's set behind one rolled trait.
     *
     * <p>Returns {@code null} while the set is still being loaded; the request is started here
     * so a later rebuild of the inventory finds the document.</p>
     */
    private static Entry resolveEntry(
            String giftId, String kind, JSONObject traits, String key
    ) {
        if (TextUtils.isEmpty(giftId) || traits == null) {
            return null;
        }
        JSONObject trait = WaysGramGiftTraits.entry(traits, key);
        if (trait == null) {
            return null;
        }
        long documentId = trait.optLong("document_id", 0L);
        if (documentId != 0L) {
            return directEntry(giftId, documentId);
        }
        // Встроенные модели WaysGram известны локально: старые черты могли быть записаны
        // без document_id, но анимация у приложения уже есть.
        if (WaysGramBundledArtwork.isBundledName(WaysGramGiftTraits.name(traits, key))) {
            WaysGramBundledArtwork.install();
            return directEntry(giftId, WaysGramBundledArtwork.WAYSGRAM_MODEL_DOCUMENT_ID);
        }
        // New rolls only use a server-verified document ID. A later inventory refresh
        // can add one after exact-name matching on the server; no local guess is safe.
        if (traits.optBoolean("strict_artwork", false)) {
            ensure(giftId);
            return null;
        }
        // Revalidate partial/preview responses; they may become complete after the
        // backend reconnects to Telegram. Continue using the old entries meanwhile.
        ensure(giftId);
        Map<String, Entry> entries;
        synchronized (LOCK) {
            entries = METADATA.get(giftId);
        }
        if (entries == null) {
            ensure(giftId);
            return null;
        }
        // A preview can contain just one unrelated model with the same chance. Exact
        // normalized names win; never infer a different model from a partial sample.
        Entry byName = entries.get(keyFor(kind, WaysGramGiftTraits.name(traits, key)));
        if (byName != null) return byName;
        synchronized (LOCK) {
            if (METADATA_EXPIRES.containsKey(giftId)) return null;
        }
        List<Entry> ofKind = entriesOfKind(entries, kind);
        int position = trait.optInt("position", -1);
        int poolSize = trait.optInt("pool_size", 0);
        if (position >= 0 && poolSize > 0 && poolSize == ofKind.size()) {
            Entry byPosition = byPosition(ofKind, position);
            if (byPosition != null) {
                return byPosition;
            }
        }
        int permille = trait.optInt("permille", 0);
        if (permille > 0) {
            Entry byChance = byUniquePermille(ofKind, permille);
            if (byChance != null) {
                return byChance;
            }
        }
        return null;
    }

    private static Entry directEntry(String giftId, long documentId) {
        return new Entry(
                "document",
                Long.toString(documentId),
                documentId,
                "",
                "/api/telegram/gifts/" + Uri.encode(giftId) + "/attributes/" + documentId
        );
    }

    private static List<Entry> entriesOfKind(Map<String, Entry> entries, String kind) {
        List<Entry> ofKind = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (kind.equals(entry.kind)) {
                ofKind.add(entry);
            }
        }
        java.util.Collections.sort(ofKind, (first, second) ->
                Integer.compare(first.position, second.position));
        return ofKind;
    }

    private static Entry byPosition(List<Entry> entries, int position) {
        for (Entry entry : entries) {
            if (entry.position == position) {
                return entry;
            }
        }
        return null;
    }

    private static Entry byUniquePermille(List<Entry> entries, int permille) {
        Entry found = null;
        for (Entry entry : entries) {
            if (entry.permille != permille) {
                continue;
            }
            if (found != null) {
                return null; // the chance is not unique, so it cannot identify the attribute
            }
            found = entry;
        }
        return found;
    }

    /**
     * Downloads one attribute document by its Telegram id. No attribute list is needed: the
     * id is served by the same backend route, so the reveal can play exactly the candidate the
     * server rolled regardless of the attribute names.
     */
    public static void wantDocument(String giftId, long documentId) {
        documentById(giftId, documentId);
    }

    /** The animation of one attribute known by its Telegram document id. */
    public static TLRPC.TL_document documentById(String giftId, long documentId) {
        if (TextUtils.isEmpty(giftId) || documentId == 0L) {
            return null;
        }
        return documentFor(giftId, directEntry(giftId, documentId));
    }

    /** The animation of one attribute, downloaded on demand and cached per gift. */
    private static TLRPC.TL_document documentFor(String giftId, Entry entry) {
        if (TextUtils.isEmpty(giftId) || entry == null) {
            return null;
        }
        String cacheKey = documentCacheKey(giftId, entry.documentId);
        boolean queued = false;
        TLRPC.TL_document built = null;
        synchronized (LOCK) {
            TLRPC.TL_document cached = DOCUMENTS.get(cacheKey);
            if (cached != null) {
                built = cached;
            } else {
                File file = WaysGramGiftDocuments.stickerFile(entry.documentId);
                if (file != null && file.isFile() && file.length() > 0) {
                    TLRPC.TL_document fromDisk = WaysGramGiftDocuments.buildDocument(
                            entry.documentId, file, ""
                    );
                    if (fromDisk != null) {
                        DOCUMENTS.put(cacheKey, fromDisk);
                        built = fromDisk;
                    }
                }
                if (built == null) {
                    // A download that failed once must get another chance: the set may have
                    // been requested before the server had the artwork.
                    queued = !FAILED.contains(cacheKey) && !DOWNLOADING.contains(cacheKey);
                    enqueueLocked(giftId, entry);
                }
            }
        }
        if (queued) {
            pumpDownloads();
        }
        if (built != null) {
            pushToFileLoader(built);
        }
        return built;
    }

    /**
     * Hands a ready document to {@code FileLoader}.
     *
     * <p>The bytes are already on the device and served by the WaysGram transport, but a
     * download that failed earlier leaves {@code FileLoader} believing the file is broken, and
     * the view keeps drawing nothing until the process restarts. Asking again clears that
     * state, so a card that was opened too early fills itself in as soon as the animation
     * lands.</p>
     */
    private static void pushToFileLoader(TLRPC.TL_document document) {
        try {
            int accountNumber = account();
            if (accountNumber < 0 || document == null) {
                return;
            }
            org.telegram.messenger.FileLoader.getInstance(accountNumber).loadFile(
                    document, null, org.telegram.messenger.FileLoader.PRIORITY_NORMAL, 1
            );
        } catch (Exception exception) {
            FileLog.e(exception);
        }
    }

    /**
     * The animated document of one attribute, or {@code null} while it is still downloading.
     * Requesting a missing document is enough: it lands in the cache and the listeners fire.
     */
    public static TLRPC.TL_document document(String giftId, String kind, String name) {
        if (TextUtils.isEmpty(giftId) || TextUtils.isEmpty(kind) || TextUtils.isEmpty(name)) {
            return null;
        }
        Entry entry;
        synchronized (LOCK) {
            Map<String, Entry> entries = METADATA.get(giftId);
            if (entries == null) {
                ensure(giftId);
                return null;
            }
            entry = entries.get(keyFor(kind, name));
        }
        // Reading a document is enough to request it: the queue is drained inside, so the
        // caller never waits for the network.
        return entry == null ? null : documentFor(giftId, entry);
    }

    /** True when the attribute animation of this gift is already on the device. */
    public static boolean hasDocument(String giftId, String kind, String name) {
        if (TextUtils.isEmpty(giftId) || TextUtils.isEmpty(kind) || TextUtils.isEmpty(name)) {
            return false;
        }
        return document(giftId, kind, name) != null;
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
            List<String> giftIds;
            synchronized (LOCK) { giftIds = new ArrayList<>(WANTED.keySet()); }
            for (String giftId : giftIds) schedulePartialRecheck(giftId);
        }
    }

    public static void removeListener(Listener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    /**
     * Asks the server to download the artwork of the whole catalog ahead of time. The call is
     * idempotent: a running preparation job is reported instead of being started twice.
     */
    public static void startServerPrefetch() {
        if (appContext == null) {
            return;
        }
        synchronized (LOCK) {
            if (serverPrefetchStarted) {
                return;
            }
            serverPrefetchStarted = true;
        }
        // A slow prefetch request must not occupy a metadata worker.
        org.telegram.tgnet.WaysGramApi.NETWORK.execute(() -> {
            try {
                org.telegram.tgnet.WaysGramApi.post(
                        account(), "/api/telegram/gifts/prefetch", new JSONObject()
                );
            } catch (Exception exception) {
                FileLog.e(exception);
                synchronized (LOCK) {
                    // A failed call must not block a later attempt.
                    serverPrefetchStarted = false;
                }
            }
        });
    }

    /**
     * Forgets the downloads that failed, so a transient network error cannot hide the model
     * and pattern a gift was rolled with. Callers re-request the attributes right after, and
     * every refresh of the inventory gives a failed download another chance.
     */
    public static void retryFailed() {
        boolean changed;
        synchronized (LOCK) {
            changed = !FAILED.isEmpty();
            FAILED.clear();
            RETRIES.clear();
        }
        if (changed) {
            pumpDownloads();
        }
    }

    /** Drops every cached document and attribute list. */
    public static void reset() {
        synchronized (LOCK) {
            generation++;
            METADATA.clear();
            METADATA_EXPIRES.clear();
            PARTIAL_RECHECKS.clear();
            PARTIAL_RECHECK_SCHEDULED.clear();
            DOCUMENTS.clear();
            LOADING.clear();
            DOWNLOADING.clear();
            FAILED.clear();
            RETRIES.clear();
            WANTED.clear();
            QUEUE.clear();
            serverPrefetchStarted = false;
        }
    }

    private static Map<String, Entry> parse(String giftId, JSONObject response) {
        Map<String, Entry> entries = new HashMap<>();
        addEntries(giftId, entries, response.optJSONArray("models"), MODEL, "models");
        addEntries(giftId, entries, response.optJSONArray("patterns"), PATTERN, "patterns");
        return entries;
    }

    private static void addEntries(
            String giftId, Map<String, Entry> entries, JSONArray array,
            String kind, String fallbackPath
    ) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.optString("name", "").trim();
            long documentId = item.optLong("document_id", 0L);
            if (name.isEmpty() || documentId == 0L) {
                continue;
            }
            String url = item.optString("url", "");
            if (!url.startsWith("/")) {
                // Only relative API paths are allowed: a catalog response must never be able
                // to redirect an authenticated download to another host.
                url = "/api/telegram/gifts/" + Uri.encode(giftId) + "/attributes/" + documentId;
            }
            // The slot inside Telegram's set and the published chance travel with the entry:
            // they identify the attribute even when the name is localised.
            Entry entry = new Entry(
                    kind,
                    name,
                    documentId,
                    item.optString("mime_type", ""),
                    url,
                    item.optInt("position", -1),
                    item.optInt("permille", 0)
            );
            entries.put(entry.key(), entry);
        }
    }

    private static void queueWanted(String giftId) {
        List<Entry> wanted = new ArrayList<>();
        synchronized (LOCK) {
            Set<String> keys = WANTED.get(giftId);
            Map<String, Entry> entries = METADATA.get(giftId);
            if (keys == null || entries == null) return;
            for (String key : keys) {
                Entry entry = entries.get(key);
                if (entry != null) wanted.add(entry);
            }
        }
        // This also consults the disk cache; a name and an ID for the same
        // document share one in-flight download and one cached TL document.
        for (Entry entry : wanted) documentFor(giftId, entry);
    }

    private static void enqueueLocked(String giftId, Entry entry) {
        String key = documentCacheKey(giftId, entry.documentId);
        if (DOCUMENTS.containsKey(key) || DOWNLOADING.contains(key) || FAILED.contains(key)) {
            return;
        }
        DOWNLOADING.add(key);
        QUEUE.add(new Request(giftId, entry));
    }

    private static void pumpDownloads() {
        synchronized (LOCK) {
            int available = Math.min(MAX_DOWNLOAD_WORKERS - workersRunning, QUEUE.size());
            for (int i = 0; i < available; i++) {
                workersRunning++;
                downloads.execute(WaysGramGiftAttributes::drainDownloads);
            }
        }
    }

    private static void drainDownloads() {
        try {
            while (true) {
                Request request;
                synchronized (LOCK) {
                    request = QUEUE.poll();
                    if (request == null) return;
                }
                String key = request.key();
                try {
                    byte[] bytes = org.telegram.tgnet.WaysGramApi.download(account(), request.entry.url);
                    File stored = WaysGramGiftDocuments.storeSticker(request.entry.documentId, bytes);
                    if (stored == null) throw new IllegalStateException("attribute artwork could not be stored");
                    synchronized (LOCK) {
                        if (generation != request.requestGeneration) continue;
                        DOWNLOADING.remove(key);
                    }
                    notifyListeners(request.giftId);
                } catch (Exception exception) {
                    FileLog.e(exception);
                    synchronized (LOCK) {
                        if (generation != request.requestGeneration) continue;
                        DOWNLOADING.remove(key);
                        FAILED.add(key);
                        scheduleRetry(key, () -> documentFor(request.giftId, request.entry));
                    }
                }
            }
        } finally {
            synchronized (LOCK) { workersRunning--; }
            pumpDownloads();
        }
    }

    private static void scheduleRetry(String key, Runnable retry) {
        final long retryGeneration;
        synchronized (LOCK) {
            int attempts = RETRIES.containsKey(key) ? RETRIES.get(key) : 0;
            if (attempts >= MAX_AUTOMATIC_RETRIES) return;
            RETRIES.put(key, attempts + 1);
            retryGeneration = generation;
        }
        AndroidUtilities.runOnUIThread(() -> {
            synchronized (LOCK) {
                if (generation != retryGeneration || !FAILED.remove(key)) return;
            }
            retry.run();
        }, RETRY_DELAY_MS);
    }

    private static void notifyListeners(final String giftId) {
        if (LISTENERS.isEmpty()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener listener : LISTENERS) {
                try {
                    listener.onGiftAttributesChanged(giftId);
                } catch (Exception exception) {
                    FileLog.e(exception);
                }
            }
        });
    }
}
