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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final long RETRY_DELAY_MS = 5 * 60 * 1000L;
    /** A mark handed out by the administrator appears without restarting the app. */
    private static final long LABEL_TTL_MS = 30 * 60 * 1000L;
    /** A name never carries more marks than that: more would read as noise. */
    public static final int MAX_LABELS = 3;
    private static final int DEFAULT_COLOR = 0xFF2F80ED;

    /** Label text is drawn a bit smaller than the name it follows. */
    private static final float TEXT_SCALE = 0.72f;
    private static final float DIVIDER = 1.15f;

    private static final long FLUSH_DELAY_MS = 120;

    private static final ConcurrentHashMap<Long, Entry> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, long[]> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Runnable> CALLBACKS = new ConcurrentHashMap<>();
    private static final Set<Long> REQUESTED =
            Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
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
            float width = pillWidth(paint, label.text);
            if (mirror) {
                if (x - width < limit) {
                    break;
                }
                drawPill(canvas, paint, label.text, label.color, x - width, centerY);
                x -= width + AndroidUtilities.dp(3);
            } else {
                if (x + width > limit) {
                    break;
                }
                drawPill(canvas, paint, label.text, label.color, x, centerY);
                x += width + AndroidUtilities.dp(3);
            }
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
        CACHE.clear();
        PENDING.clear();
        CALLBACKS.clear();
        REQUESTED.clear();
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

    private static List<Label> labels(int account, long peerId, Runnable invalidate) {
        long key = key(account, peerId);
        Entry entry = CACHE.get(key);
        if (entry == null) {
            request(account, peerId, key, invalidate);
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        long ttl = entry.loaded ? LABEL_TTL_MS : RETRY_DELAY_MS;
        if (now - entry.at > ttl) {
            // Refresh in the background: the known marks stay on screen until the answer is in.
            request(account, peerId, key, invalidate);
        }
        return entry.labels;
    }

    private static void request(
            int account, long peerId, long key, Runnable invalidate
    ) {
        if (invalidate != null) {
            CALLBACKS.put(key, invalidate);
        }
        if (!REQUESTED.add(key)) {
            return;
        }
        PENDING.put(key, new long[]{account, peerId});
        scheduleFlush();
    }

    /**
     * Marks are resolved in batches: opening the chat list asks for every visible row with a
     * single request instead of one request per row.
     */
    private static void scheduleFlush() {
        if (!FLUSH_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
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
        if (PENDING.isEmpty()) {
            return;
        }
        Map<Long, long[]> batch = new HashMap<>(PENDING);
        PENDING.clear();
        Map<Integer, List<Long>> byAccount = new LinkedHashMap<>();
        for (Map.Entry<Long, long[]> entry : batch.entrySet()) {
            List<Long> keys = byAccount.get((int) entry.getValue()[0]);
            if (keys == null) {
                keys = new ArrayList<>();
                byAccount.put((int) entry.getValue()[0], keys);
            }
            keys.add(entry.getKey());
        }
        for (Map.Entry<Integer, List<Long>> group : byAccount.entrySet()) {
            int account = group.getKey();
            List<Long> keys = group.getValue();
            StringBuilder targets = new StringBuilder();
            for (Long key : keys) {
                long[] peer = batch.get(key);
                if (peer == null) {
                    continue;
                }
                if (targets.length() > 0) {
                    targets.append(',');
                }
                targets.append(target(peer[1]));
            }
            JSONObject response = null;
            if (targets.length() > 0) {
                try {
                    response = WaysGramApi.get(account, "/api/labels?targets=" + targets);
                } catch (Exception exception) {
                    FileLog.e(exception);
                }
            }
            JSONObject answered = response == null ? null : response.optJSONObject("labels");
            for (Long key : keys) {
                long[] peer = batch.get(key);
                if (peer == null) {
                    continue;
                }
                List<Label> labels = null;
                if (answered != null) {
                    labels = labels(answered.optJSONArray(target(peer[1])));
                }
                if (labels == null) {
                    // An older WaysGram build has no batch endpoint: fall back to the peer routes.
                    labels = loadOne(account, peer[1]);
                }
                finish(key, new Entry(labels, System.currentTimeMillis(), labels != null));
            }
        }
    }

    /** Marks of one peer from its own route; {@code null} when even that route failed. */
    private static List<Label> loadOne(int account, long peerId) {
        for (String path : badgePaths(account, peerId)) {
            try {
                return labels(WaysGramApi.get(account, path));
            } catch (Exception exception) {
                FileLog.e(exception);
            }
        }
        return null;
    }

    private static void finish(long key, Entry entry) {
        if (entry.labels == null) {
            entry = new Entry(new ArrayList<>(), System.currentTimeMillis(), false);
        }
        CACHE.put(key, entry);
        REQUESTED.remove(key);
        Runnable invalidate = CALLBACKS.remove(key);
        if (invalidate != null && !entry.labels.isEmpty()) {
            AndroidUtilities.runOnUIThread(invalidate);
        }
    }

    private static List<Label> labels(JSONObject response) {
        return labels(response == null ? null : response.optJSONArray("labels"));
    }

    private static List<Label> labels(JSONArray array) {
        if (array == null) {
            return Collections.emptyList();
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

    private static long key(int account, long peerId) {
        return ((long) account << 48) ^ peerId;
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

    private static final class Label {
        final String text;
        final int color;

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
