package org.telegram.messenger.waysgram;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.tgnet.WaysGramChats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows WaysGram marks next to a name — in chat titles, in the chat list and on profiles.
 *
 * <p>A mark is drawn as a pill: a translucent fill of the mark colour, a thin border of the
 * same colour and the text in a brightened tone of it, so it reads on the light and on the
 * dark theme. Every mark is one {@link LabelSpan}, which means the native title renderers draw
 * it exactly where the name ends. In the chat list the pills are painted by
 * {@link #drawMarks} right after the premium or verified badge.</p>
 *
 * <p>Marks are cached per Telegram account and peer. A missing entry is requested in the
 * background and the caller is told to redraw once it arrives.</p>
 */
public final class WaysGramPeerBadges {

    private static final long RETRY_DELAY_MS = 15 * 1000L;
    /** A mark handed out by the administrator appears without restarting the app. */
    private static final long LABEL_TTL_MS = 15 * 1000L;
    public static final long REFRESH_INTERVAL_MS = 15 * 1000L;
    private static final int MAX_BATCH_SIZE = 100;
    /** A name never carries more marks than that: more would read as noise. */
    public static final int MAX_LABELS = 3;
    private static final int DEFAULT_COLOR = 0xFF2F80ED;

    /** Label text is drawn a bit smaller than the name it follows. */
    private static final float TEXT_SCALE = 0.72f;
    private static final float DIVIDER = 1.15f;

    private static final long FLUSH_DELAY_MS = 120;

    // A single lock makes draining the queue and invalidating a session atomic.
    private static final Object LOCK = new Object();
    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>();
    private static final Map<String, Pending> REQUESTED = new HashMap<>();
    private static final Map<String, List<Runnable>> CALLBACKS = new HashMap<>();
    private static final int[] GENERATIONS = new int[UserConfig.MAX_ACCOUNT_COUNT];

    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    private static final Paint FILL_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint BORDER_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final TextPaint LABEL_PAINT = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF PILL = new RectF();

    private WaysGramPeerBadges() {
    }

    /**
     * @return the title with WaysGram marks appended, or the title itself while the marks are
     * still loading.
     */
    public static CharSequence decorate(
            int account, long peerId, CharSequence title, Runnable invalidate
    ) {
        if (title == null || peerId == 0) {
            return title;
        }
        List<Label> labels = labels(account, peerId, invalidate);
        if (labels.isEmpty()) {
            return title;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(title);
        for (Label label : labels) {
            int start = builder.length();
            builder.append(' ');
            builder.append(label.text);
            builder.setSpan(
                    new LabelSpan(label.text, label.color),
                    start,
                    builder.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        return builder;
    }

    /**
     * Draws the marks of a peer next to its badge, e.g. after the premium star of the chat list.
     * Nothing is drawn while the marks are loading; the request invalidates the caller once
     * they arrive.
     *
     * @param anchor left edge of the first pill, or its right edge when {@code mirror} is set
     * @param top    top of the text layout the marks belong to
     * @param limit  rightmost allowed x, or the leftmost one when {@code mirror} is set
     * @return the x coordinate after the last drawn pill, or {@code anchor} when none is drawn
     */
    public static float drawMarks(
            Canvas canvas,
            Paint paint,
            float anchor,
            float top,
            float limit,
            int account,
            long peerId,
            boolean mirror,
            Runnable invalidate
    ) {
        if (canvas == null || paint == null || peerId == 0) {
            return anchor;
        }
        List<Label> labels = labels(account, peerId, invalidate);
        if (labels.isEmpty()) {
            return anchor;
        }
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        float centerY = top + (metrics.descent - metrics.ascent) * 0.5f;
        float x = anchor;
        for (Label label : labels) {
            String text = label.text;
            float room = mirror ? x - limit : limit - x;
            float width = pillWidth(paint, text);
            if (width > room) {
                if (x != anchor || room <= AndroidUtilities.dp(10)) break;
                text = android.text.TextUtils.ellipsize(text, LABEL_PAINT,
                        room - AndroidUtilities.dp(10), android.text.TextUtils.TruncateAt.END).toString();
                width = pillWidth(paint, text);
                if (text.isEmpty() || width > room) break;
            }
            drawPill(canvas, paint, text, label.color, mirror ? x - width : x, centerY);
            x += (mirror ? -1 : 1) * (width + AndroidUtilities.dp(3));
        }
        return x;
    }

    /**
     * Room the marks of a peer need next to a name of the given text size, so a list can
     * ellipsize the name instead of hiding the marks. Zero while the marks are loading — the
     * caller is redrawn once they arrive.
     */
    public static int reservedWidth(
            int account, long peerId, float textSize, Runnable invalidate
    ) {
        if (peerId == 0) {
            return 0;
        }
        List<Label> labels = labels(account, peerId, invalidate);
        if (labels.isEmpty()) {
            return 0;
        }
        LABEL_PAINT.setTextSize(Math.max(1f, textSize * TEXT_SCALE));
        int width = 0;
        for (Label label : labels) {
            width += Math.round(LABEL_PAINT.measureText(label.text) + AndroidUtilities.dp(10));
        }
        return width + Math.max(1, labels.size()) * AndroidUtilities.dp(3) + AndroidUtilities.dp(4);
    }

    /** Restyles a plain text view as a mark pill (used by the profile screen). */
    public static void styleChip(TextView chip, int color) {
        if (chip == null) {
            return;
        }
        int accent = readableAccent(color);
        chip.setTextColor(accent);
        chip.setTextSize(12);
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        background.setColor(withAlpha(color, 0x30));
        background.setCornerRadius(AndroidUtilities.dp(9));
        background.setStroke(Math.max(1, AndroidUtilities.dp(1)), withAlpha(accent, 0x60));
        chip.setBackground(background);
        chip.setPadding(
                AndroidUtilities.dp(8), AndroidUtilities.dp(3),
                AndroidUtilities.dp(8), AndroidUtilities.dp(3)
        );
    }

    /** Parses a {@code #RRGGBB} colour, falling back to the WaysGram blue. */
    public static int parseColor(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return DEFAULT_COLOR;
        }
        if (!text.startsWith("#")) {
            text = "#" + text;
        }
        try {
            return Color.parseColor(text);
        } catch (Exception ignored) {
            return DEFAULT_COLOR;
        }
    }

    /** Drops cached marks, e.g. after the session of an account changed. */
    public static void clear() {
        synchronized (LOCK) {
            for (int account = 0; account < GENERATIONS.length; account++) GENERATIONS[account]++;
            CACHE.clear();
            PENDING.clear();
            CALLBACKS.clear();
            REQUESTED.clear();
        }
    }

    /** Width of one pill for the given base paint. */
    private static float pillWidth(Paint paint, String text) {
        LABEL_PAINT.setTextSize(Math.max(1f, paint.getTextSize() * TEXT_SCALE));
        return LABEL_PAINT.measureText(text) + AndroidUtilities.dp(10);
    }

    /** Draws one pill; the caller guarantees it fits between {@code left} and the max width. */
    private static void drawPill(
            Canvas canvas, Paint basePaint, String text, int color, float left, float centerY
    ) {
        float textSize = Math.max(1f, basePaint.getTextSize() * TEXT_SCALE);
        LABEL_PAINT.setTextSize(textSize);
        LABEL_PAINT.setColor(readableAccent(color));
        LABEL_PAINT.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL
        ));
        float width = LABEL_PAINT.measureText(text) + AndroidUtilities.dp(10);
        Paint.FontMetricsInt labelMetrics = LABEL_PAINT.getFontMetricsInt();
        float textHeight = labelMetrics.descent - labelMetrics.ascent;
        float height = textHeight + AndroidUtilities.dp(4);

        PILL.set(left, centerY - height * 0.5f, left + width, centerY + height * 0.5f);
        float radius = height * 0.5f;
        FILL_PAINT.setColor(withAlpha(color, 0x33));
        FILL_PAINT.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(PILL, radius, radius, FILL_PAINT);
        BORDER_PAINT.setColor(withAlpha(readableAccent(color), 0x70));
        BORDER_PAINT.setStyle(Paint.Style.STROKE);
        BORDER_PAINT.setStrokeWidth(Math.max(1f, AndroidUtilities.dp(1f) * DIVIDER));
        canvas.drawRoundRect(PILL, radius, radius, BORDER_PAINT);

        float baseline = centerY - (labelMetrics.ascent + labelMetrics.descent) * 0.5f;
        canvas.drawText(text, left + AndroidUtilities.dp(5), baseline, LABEL_PAINT);
    }

    /**
     * One mark as a span: a rounded pill drawn inline with the name, so the native title
     * renderers place it exactly after the text.
     */
    public static final class LabelSpan extends ReplacementSpan {

        private final String text;
        private final int color;

        public LabelSpan(String text, int color) {
            this.text = text == null ? "" : text;
            this.color = color;
        }

        @Override
        public int getSize(
                Paint paint,
                CharSequence source,
                int start,
                int end,
                Paint.FontMetricsInt fm
        ) {
            float width = pillWidth(paint, text) + AndroidUtilities.dp(4);
            if (fm != null) {
                // The pill is slightly taller than the text; a tiny ascent keeps the line
                // from clipping it without moving the whole row.
                Paint.FontMetricsInt base = paint.getFontMetricsInt();
                fm.ascent = base.ascent;
                fm.descent = base.descent;
                fm.top = base.top;
                fm.bottom = base.bottom;
                fm.leading = base.leading;
            }
            return Math.round(width);
        }

        @Override
        public void draw(
                Canvas canvas,
                CharSequence source,
                int start,
                int end,
                float x,
                int top,
                int baseline,
                int bottom,
                Paint paint
        ) {
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            float centerY = baseline + (metrics.ascent + metrics.descent) * 0.5f;
            drawPill(canvas, paint, text, color, x + AndroidUtilities.dp(2), centerY);
        }
    }

    /** Call from a visible surface periodically; no network request is made for fresh entries. */
    public static List<Label> labels(int account, long peerId, Runnable invalidate) {
        if (peerId == 0 || account < 0 || account >= GENERATIONS.length) {
            return Collections.emptyList();
        }
        String key = key(account, peerId);
        synchronized (LOCK) {
            Entry entry = CACHE.get(key);
            long ttl = entry != null && entry.loaded ? LABEL_TTL_MS : RETRY_DELAY_MS;
            if (entry == null || android.os.SystemClock.elapsedRealtime() - entry.at >= ttl) {
                request(account, peerId, key, invalidate);
            }
            return entry == null ? Collections.emptyList() : entry.labels;
        }
    }

    // Called after a WaysGram login/logout: an old in-flight response must not repopulate the cache.
    public static void clearAccount(int account) {
        if (account < 0 || account >= GENERATIONS.length) return;
        String prefix = account + ":";
        synchronized (LOCK) {
            GENERATIONS[account]++;
            CACHE.keySet().removeIf(key -> key.startsWith(prefix));
            PENDING.keySet().removeIf(key -> key.startsWith(prefix));
            REQUESTED.keySet().removeIf(key -> key.startsWith(prefix));
            CALLBACKS.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    // Caller holds LOCK. Each view supplies a stable callback to avoid unbounded duplicate waiters.
    private static void request(int account, long peerId, String key, Runnable invalidate) {
        if (invalidate != null) {
            List<Runnable> waiting = CALLBACKS.get(key);
            if (waiting == null) {
                waiting = new ArrayList<>();
                CALLBACKS.put(key, waiting);
            }
            if (!waiting.contains(invalidate)) waiting.add(invalidate);
        }
        if (REQUESTED.containsKey(key)) return;
        Pending pending = new Pending(account, peerId, GENERATIONS[account]);
        REQUESTED.put(key, pending);
        PENDING.put(key, pending);
        scheduleFlush();
    }

    private static void scheduleFlush() {
        if (!FLUSH_SCHEDULED.compareAndSet(false, true)) return;
        SCHEDULER.schedule(() -> {
            FLUSH_SCHEDULED.set(false);
            try {
                flush();
            } catch (Exception exception) {
                FileLog.e(exception);
            }
        }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void flush() {
        Map<String, Pending> batch;
        synchronized (LOCK) {
            batch = new LinkedHashMap<>(PENDING);
            PENDING.clear();
        }
        Map<Integer, List<Map.Entry<String, Pending>>> byAccount = new LinkedHashMap<>();
        for (Map.Entry<String, Pending> item : batch.entrySet()) {
            List<Map.Entry<String, Pending>> group = byAccount.get(item.getValue().account);
            if (group == null) {
                group = new ArrayList<>();
                byAccount.put(item.getValue().account, group);
            }
            group.add(item);
        }
        for (Map.Entry<Integer, List<Map.Entry<String, Pending>>> group : byAccount.entrySet()) {
            List<Map.Entry<String, Pending>> peers = group.getValue();
            for (int offset = 0; offset < peers.size(); offset += MAX_BATCH_SIZE) {
                List<Map.Entry<String, Pending>> chunk = peers.subList(offset, Math.min(offset + MAX_BATCH_SIZE, peers.size()));
                StringBuilder targets = new StringBuilder();
                for (Map.Entry<String, Pending> item : chunk) {
                    if (targets.length() > 0) targets.append(',');
                    targets.append(target(item.getValue().peerId));
                }
                JSONObject response = null;
                boolean fallback = false;
                try {
                    response = WaysGramApi.get(group.getKey(), "/api/labels?targets=" + targets);
                    fallback = true; // Missing individual entries can use the older peer route.
                } catch (WaysGramApi.ApiException exception) {
                    fallback = exception.status == 404 || exception.status == 405;
                    FileLog.e(exception);
                } catch (Exception exception) {
                    FileLog.e(exception);
                }
                JSONObject answered = response == null ? null : response.optJSONObject("labels");
                for (Map.Entry<String, Pending> item : chunk) {
                    Pending pending = item.getValue();
                    JSONArray array = answered == null ? null : answered.optJSONArray(target(pending.peerId));
                    List<Label> result = array == null ? (fallback ? loadOne(pending.account, pending.peerId) : null) : labels(array);
                    finish(item.getKey(), pending, result);
                }
            }
        }
    }

    /** Marks of one peer from its own route; null when the response is missing or failed. */
    private static List<Label> loadOne(int account, long peerId) {
        for (String path : badgePaths(account, peerId)) {
            try {
                List<Label> result = labels(WaysGramApi.get(account, path));
                if (result != null) return result;
            } catch (Exception exception) {
                FileLog.e(exception);
            }
        }
        return null;
    }

    private static void finish(String key, Pending pending, List<Label> result) {
        List<Runnable> waiting;
        boolean changed;
        synchronized (LOCK) {
            if (pending.generation != GENERATIONS[pending.account] || REQUESTED.get(key) != pending) return;
            Entry previous = CACHE.get(key);
            // A temporary failure must not erase known marks; retry soon instead.
            changed = result != null && (previous == null || !sameLabels(previous.labels, result));
            // Keep the same snapshot object when unchanged so other visible surfaces can cheaply
            // detect a result fetched by a different screen or list row.
            List<Label> value = result == null || !changed
                    ? previous == null ? Collections.emptyList() : previous.labels : result;
            CACHE.put(key, new Entry(value, android.os.SystemClock.elapsedRealtime(), result != null));
            REQUESTED.remove(key);
            waiting = CALLBACKS.remove(key);
        }
        if (changed && waiting != null) {
            for (Runnable invalidate : waiting) AndroidUtilities.runOnUIThread(invalidate);
        }
    }

    private static boolean sameLabels(List<Label> left, List<Label> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (left.get(i).color != right.get(i).color || !left.get(i).text.equals(right.get(i).text)) return false;
        }
        return true;
    }

    private static final class Pending {
        final int account;
        final long peerId;
        final int generation;
        Pending(int account, long peerId, int generation) {
            this.account = account;
            this.peerId = peerId;
            this.generation = generation;
        }
    }

    private static List<Label> labels(JSONObject response) {
        return labels(response == null ? null : response.optJSONArray("labels"));
    }

    private static List<Label> labels(JSONArray array) {
        if (array == null) {
            return null;
        }

        List<Label> result = new ArrayList<>();
        for (int i = 0; i < array.length() && result.size() < MAX_LABELS; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String text = item.optString("text", "").trim();
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            result.add(new Label(text, parseColor(item.optString("color", ""))));
        }
        return result;
    }

    /**
     * The target of a peer in the WaysGram label API. A dialog id is negative; groups keep the
     * chat id and channels carry {@link WaysGramChats#CHANNEL_OFFSET}, so a group and a channel
     * are never confused even when the server gave them the same number.
     */
    private static String target(long peerId) {
        if (peerId < 0) {
            long chatId = -peerId;
            if (chatId >= WaysGramChats.CHANNEL_OFFSET) {
                return "channel:" + WaysGramChats.channelServer(chatId);
            }
            return "group:" + chatId;
        }
        return "account:" + peerId;
    }

    /** Routes used when the batch endpoint is not available yet. */
    private static String[] badgePaths(int account, long peerId) {
        if (peerId == UserConfig.getInstance(account).getClientUserId()) {
            return new String[]{"/api/me"};
        }
        if (peerId < 0) {
            long chatId = -peerId;
            if (chatId >= WaysGramChats.CHANNEL_OFFSET) {
                return new String[]{"/api/channels/" + WaysGramChats.channelServer(chatId)};
            }
            return new String[]{"/api/groups/" + chatId};
        }
        return new String[]{"/api/users/" + peerId};
    }

    private static String key(int account, long peerId) {
        return account + ":" + peerId;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(0xFF, alpha)) << 24);
    }

    /** Brightens a dark mark colour so the pill text stays readable on any theme. */
    private static int readableAccent(int color) {
        double luminance = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255d;
        if (luminance >= 0.62d) {
            return color;
        }
        float mix = (float) ((0.62d - luminance) * 0.9d);
        mix = Math.max(0.15f, Math.min(0.55f, mix));
        return blend(color, 0xFFFFFFFF, mix);
    }

    private static int blend(int from, int to, float fraction) {
        float value = Math.max(0f, Math.min(1f, fraction));
        int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * value);
        int green = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * value);
        int blue = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * value);
        return Color.rgb(red, green, blue);
    }

    public static final class Label {
        public final String text;
        public final int color;

        Label(String text, int color) {
            this.text = text;
            this.color = color;
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
