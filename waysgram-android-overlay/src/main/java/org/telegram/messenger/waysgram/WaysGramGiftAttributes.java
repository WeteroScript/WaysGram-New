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
 * <p>Downloads run one at a time on a single background thread: a gift can have hundreds of
 * models and patterns, and decoding many sticker animations at once is what made the gift
 * screens stutter.</p>
 */
public final class WaysGramGiftAttributes {

    public static final String MODEL = "model";
    public static final String PATTERN = "pattern";

    private static final Object LOCK = new Object();
    private static final Map<String, Map<String, Entry>> METADATA = new HashMap<>();
    private static final Map<String, TLRPC.TL_document> DOCUMENTS = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>();
    private static final Set<String> DOWNLOADING = new HashSet<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static final Map<String, Set<String>> WANTED = new HashMap<>();
    private static final ArrayDeque<Request> QUEUE = new ArrayDeque<>();
    private static final Set<Runnable> LISTENERS = new CopyOnWriteArraySet<>();

    private static ExecutorService executor;
    private static boolean workerRunning;
    private static Context appContext;
    private static int account = -1;
    private static boolean serverPrefetchStarted;

    private WaysGramGiftAttributes() {
    }

    /** One model or pattern of the gift's upgrade set. */
    /** Attribute names are compared case-insensitively: Telegram localises their case. */
    private static String keyFor(String kind, String name) {
        return kind + "\u0000" + name.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Entry {
        final String kind;
        final String name;
        final long documentId;
        final String mimeType;
        final String url;

        Entry(String kind, String name, long documentId, String mimeType, String url) {
            this.kind = kind;
            this.name = name;
            this.documentId = documentId;
            this.mimeType = mimeType;
            this.url = url;
        }

        String key() {
            return keyFor(kind, name);
        }
    }

    private static final class Request {
        final String giftId;
        final Entry entry;

        Request(String giftId, Entry entry) {
            this.giftId = giftId;
            this.entry = entry;
        }

        String key() {
            return giftId + "|" + entry.key();
        }
    }

    /** Called on the UI thread whenever a document becomes available. */
    public interface Listener {
        void onGiftAttributesChanged(String giftId);
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
            executor = Executors.newSingleThreadExecutor(runnable -> {
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
        synchronized (LOCK) {
            if (METADATA.containsKey(giftId) || LOADING.contains(giftId)
                    || FAILED.contains("load|" + giftId)) {
                return;
            }
            LOADING.add(giftId);
        }
        executor().execute(() -> {
            try {
                JSONObject response = org.telegram.tgnet.WaysGramApi.get(
                        account(),
                        "/api/telegram/gifts/" + Uri.encode(giftId) + "/attributes"
                );
                Map<String, Entry> entries = parse(giftId, response);
                synchronized (LOCK) {
                    METADATA.put(giftId, entries);
                }
                queueWanted(giftId);
                notifyListeners(giftId);
            } catch (Exception exception) {
                FileLog.e(exception);
                synchronized (LOCK) {
                    FAILED.add("load|" + giftId);
                }
            } finally {
                synchronized (LOCK) {
                    LOADING.remove(giftId);
                }
            }
        });
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
    }

    /** Asks for the model and the pattern that the server actually rolled for this gift. */
    public static void wantRolled(String giftId, JSONObject traits) {
        if (TextUtils.isEmpty(giftId) || traits == null) {
            return;
        }
        want(giftId, MODEL, WaysGramGiftTraits.name(traits, "model"));
        want(giftId, PATTERN, WaysGramGiftTraits.name(traits, "pattern"));
    }

    /**
     * The animated document of one attribute, or {@code null} while it is still downloading.
     * Requesting a missing document is enough: it lands in the cache and the listeners fire.
     */
    public static TLRPC.TL_document document(String giftId, String kind, String name) {
        if (TextUtils.isEmpty(giftId) || TextUtils.isEmpty(kind) || TextUtils.isEmpty(name)) {
            return null;
        }
        String key = keyFor(kind, name);
        boolean queued = false;
        synchronized (LOCK) {
            TLRPC.TL_document document = DOCUMENTS.get(giftId + "|" + key);
            if (document != null) {
                return document;
            }
            Map<String, Entry> entries = METADATA.get(giftId);
            if (entries == null) {
                return null;
            }
            Entry entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            File file = WaysGramGiftDocuments.stickerFile(entry.documentId);
            if (file != null && file.isFile() && file.length() > 0) {
                TLRPC.TL_document built = WaysGramGiftDocuments.buildDocument(
                        entry.documentId, file, ""
                );
                if (built != null) {
                    DOCUMENTS.put(giftId + "|" + key, built);
                    return built;
                }
            }
            queued = !DOWNLOADING.contains(giftId + "|" + key) && !FAILED.contains(giftId + "|" + key);
            enqueueLocked(giftId, entry);
        }
        if (queued) {
            // Reading a document is enough to request it: the queue is drained right after
            // the lock is released so the caller never waits for the network.
            pumpDownloads();
        }
        return null;
    }

    /** True when the attribute animation of this gift is already on the device. */
    public static boolean hasDocument(String giftId, String kind, String name) {
        if (TextUtils.isEmpty(giftId) || TextUtils.isEmpty(kind) || TextUtils.isEmpty(name)) {
            return false;
        }
        synchronized (LOCK) {
            if (DOCUMENTS.get(giftId + "|" + keyFor(kind, name)) != null) {
                return true;
            }
        }
        return document(giftId, kind, name) != null;
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
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
        executor().execute(() -> {
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

    /** Drops every cached document and attribute list. */
    public static synchronized void reset() {
        synchronized (LOCK) {
            METADATA.clear();
            DOCUMENTS.clear();
            LOADING.clear();
            DOWNLOADING.clear();
            FAILED.clear();
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
            Entry entry = new Entry(kind, name, documentId, item.optString("mime_type", ""), url);
            entries.put(entry.key(), entry);
        }
    }

    private static void queueWanted(String giftId) {
        synchronized (LOCK) {
            Set<String> keys = WANTED.get(giftId);
            Map<String, Entry> entries = METADATA.get(giftId);
            if (keys == null || entries == null) {
                return;
            }
            List<String> snapshot = new ArrayList<>(keys);
            for (int i = 0; i < snapshot.size(); i++) {
                Entry entry = entries.get(snapshot.get(i));
                if (entry != null) {
                    enqueueLocked(giftId, entry);
                }
            }
        }
        pumpDownloads();
    }

    private static void enqueueLocked(String giftId, Entry entry) {
        String key = giftId + "|" + entry.key();
        if (DOCUMENTS.containsKey(key) || DOWNLOADING.contains(key) || FAILED.contains(key)) {
            return;
        }
        DOWNLOADING.add(key);
        QUEUE.add(new Request(giftId, entry));
    }

    private static void pumpDownloads() {
        synchronized (LOCK) {
            if (workerRunning || QUEUE.isEmpty()) {
                return;
            }
            workerRunning = true;
        }
        executor().execute(() -> {
            while (true) {
                Request request;
                synchronized (LOCK) {
                    request = QUEUE.poll();
                    if (request == null) {
                        workerRunning = false;
                        return;
                    }
                }
                String key = request.key();
                try {
                    byte[] bytes = org.telegram.tgnet.WaysGramApi.download(
                            account(), request.entry.url
                    );
                    File stored = WaysGramGiftDocuments.storeSticker(
                            request.entry.documentId, bytes
                    );
                    if (stored == null) {
                        throw new IllegalStateException("attribute artwork could not be stored");
                    }
                    synchronized (LOCK) {
                        DOWNLOADING.remove(key);
                    }
                    notifyListeners(request.giftId);
                } catch (Exception exception) {
                    FileLog.e(exception);
                    synchronized (LOCK) {
                        DOWNLOADING.remove(key);
                        FAILED.add(key);
                    }
                }
            }
        });
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
