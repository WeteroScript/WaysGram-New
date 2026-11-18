package org.telegram.messenger.waysgram;

import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.WaysGramApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows WaysGram marks right next to a chat or channel name.
 *
 * <p>Only ways that the user actually owns are drawn: the labels come from the
 * WaysGram API for the peer that is open and are cached per Telegram account.
 * Rendering is a plain {@link CharSequence} with colour spans, so the native
 * action bar title renderer draws it without any layout changes.</p>
 */
public final class WaysGramPeerBadges {

    private static final long RETRY_DELAY_MS = 5 * 60 * 1000L;
    private static final int MAX_LABELS = 3;

    private static final ConcurrentHashMap<Long, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Set<Long> REQUESTED =
            Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private WaysGramPeerBadges() {
    }

    /**
     * @return the title with WaysGram marks appended, or the title itself while
     * the marks are still loading.
     */
    public static CharSequence decorate(
            int account, long peerId, CharSequence title, Runnable invalidate
    ) {
        if (title == null || peerId == 0) {
            return title;
        }
        long key = key(account, peerId);
        Entry entry = CACHE.get(key);
        if (entry == null) {
            request(account, peerId, key, invalidate);
            return title;
        }
        long now = System.currentTimeMillis();
        if (!entry.loaded && now - entry.at > RETRY_DELAY_MS) {
            request(account, peerId, key, invalidate);
        }
        if (entry.labels.isEmpty()) {
            return title;
        }
        return withBadges(title, entry.labels);
    }

    /** Drops cached marks, e.g. after the session of an account changed. */
    public static void clear() {
        CACHE.clear();
        REQUESTED.clear();
    }

    private static CharSequence withBadges(CharSequence title, List<Label> labels) {
        SpannableStringBuilder builder = new SpannableStringBuilder(title);
        for (Label label : labels) {
            int start = builder.length();
            builder.append(' ').append(label.text);
            int end = builder.length();
            builder.setSpan(new BackgroundColorSpan(label.background),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new ForegroundColorSpan(label.foreground),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new RelativeSizeSpan(0.8f),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }

    private static void request(
            final int account, final long peerId, final long key, final Runnable invalidate
    ) {
        if (!REQUESTED.add(key)) {
            return;
        }
        EXECUTOR.execute(() -> {
            Entry entry = new Entry(new ArrayList<>(), System.currentTimeMillis(), false);
            for (String path : badgePaths(account, peerId)) {
                try {
                    List<Label> labels = labels(WaysGramApi.get(account, path));
                    entry = new Entry(labels, System.currentTimeMillis(), true);
                    break;
                } catch (Exception exception) {
                    FileLog.e(exception);
                }
            }
            CACHE.put(key, entry);
            REQUESTED.remove(key);
            if (!entry.labels.isEmpty() && invalidate != null) {
                AndroidUtilities.runOnUIThread(invalidate);
            }
        });
    }

    private static List<Label> labels(JSONObject response) {
        JSONArray array = response == null ? null : response.optJSONArray("labels");
        if (array == null && response != null) {
            JSONObject peer = response.optJSONObject("user");
            if (peer == null) {
                peer = response.optJSONObject("peer");
            }
            if (peer != null) {
                array = peer.optJSONArray("labels");
            }
        }
        List<Label> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length() && result.size() < MAX_LABELS; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String text = item.optString("text", "").trim();
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            result.add(new Label(text, color(item.optString("color", ""))));
        }
        return result;
    }

    private static int color(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return 0xFF2F80ED;
        }
        if (!text.startsWith("#")) {
            text = "#" + text;
        }
        try {
            return Color.parseColor(text);
        } catch (Exception ignored) {
            return 0xFF2F80ED;
        }
    }

    /**
     * WaysGram keeps marks only for accounts and channels; a chat dialog id is
     * negative, so channels are looked up by their positive id. The signed-in
     * account carries its own marks on {@code /api/me}, which is what the profile
     * of the owner shows.
     */
    private static String[] badgePaths(int account, long peerId) {
        if (peerId < 0) {
            long id = -peerId;
            return new String[]{"/api/channels/" + id, "/api/groups/" + id};
        }
        if (peerId == UserConfig.getInstance(account).getClientUserId()) {
            return new String[]{"/api/me", "/api/users/" + peerId};
        }
        return new String[]{"/api/users/" + peerId};
    }

    private static long key(int account, long peerId) {
        return ((long) account << 48) ^ peerId;
    }

    private static final class Label {
        final String text;
        final int background;
        final int foreground;

        Label(String text, int background) {
            this.text = text;
            this.background = background;
            this.foreground = isDark(background) ? Color.WHITE : Color.BLACK;
        }

        private static boolean isDark(int color) {
            double luminance = (0.299 * Color.red(color)
                    + 0.587 * Color.green(color)
                    + 0.114 * Color.blue(color)) / 255d;
            return luminance < 0.62d;
        }
    }

    private static final class Entry {
        final List<Label> labels;
        final long at;
        final boolean loaded;

        Entry(List<Label> labels, long at, boolean loaded) {
            this.labels = labels;
            this.at = at;
            this.loaded = loaded;
        }
    }
}
