package org.telegram.ui.Stars;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.tgnet.tl.TL_stars;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Номер эксклюзивной модели WaysGram.
 *
 * <p>Обычный уникальный подарок Telegram подписан «#913». У модели WaysGram вместо номера
 * стоит «#???» — три знака вопроса, залитые градиентом от тёмно-синего к тёмно-чёрному,
 * который медленно переливается. Номер в базе при этом остаётся настоящим: он просто не
 * показывается, чтобы подарок нельзя было перепутать с обычным.
 */
public final class WaysGramGiftNumber {

    /** Имя модели в traits подарка (совпадает с серверным пулом Plush Pepe). */
    public static final String MODEL_NAME = "WaysGram";

    /** Подпись вместо номера: ровно три знака вопроса. */
    public static final String LABEL = "#???";

    private static final int COLOR_NEAR_BLACK = 0xFF05070B;
    private static final int COLOR_DEEP_BLUE = 0xFF16233F;
    private static final int COLOR_SHIMMER = 0xFF24406B;

    /** 0..1 — сдвиг градиента; общий для всех карточек, чтобы перелив шёл синхронно. */
    private static volatile float phase;
    private static boolean ticking;
    private static final ArrayList<WeakReference<View>> targets = new ArrayList<>();

    private WaysGramGiftNumber() {
    }

    /** True, если уникальный подарок несёт модель WaysGram. */
    public static boolean isWaysGramModel(@Nullable TL_stars.StarGift gift) {
        if (!(gift instanceof TL_stars.TL_starGiftUnique)) {
            return false;
        }
        return isWaysGramModel((TL_stars.TL_starGiftUnique) gift);
    }

    public static boolean isWaysGramModel(@Nullable TL_stars.TL_starGiftUnique gift) {
        if (gift == null || gift.attributes == null) {
            return false;
        }
        for (TL_stars.StarGiftAttribute attribute : gift.attributes) {
            if (!(attribute instanceof TL_stars.starGiftAttributeModel)) {
                continue;
            }
            if (attribute.name != null && MODEL_NAME.equalsIgnoreCase(attribute.name.trim())) {
                return true;
            }
            // Имя модели Telegram может локализовать, а документ модель несёт всегда:
            // встроенная анимация WaysGram однозначно выдаёт нашу модель.
            if (attribute.document != null
                    && attribute.document.id == org.telegram.messenger.waysgram.WaysGramBundledArtwork.WAYSGRAM_MODEL_DOCUMENT_ID) {
                return true;
            }
        }
        return false;
    }

    /** Номер подарка: «#???» для модели WaysGram, иначе «#913». */
    public static String numberOrLabel(@Nullable TL_stars.TL_starGiftUnique gift) {
        if (gift != null && isWaysGramModel(gift)) {
            return LABEL;
        }
        return gift == null ? "" : "#" + org.telegram.messenger.LocaleController.formatNumber(gift.num, ',');
    }

    /** Подпись подарка: «Название #???» вместо «Название #913». */
    public static String titled(@Nullable TL_stars.TL_starGiftUnique gift, String fallback) {
        if (gift != null && isWaysGramModel(gift)) {
            return gift.title + " " + LABEL;
        }
        return fallback;
    }

    /** То же для проверки по базовому типу подарка. */
    public static String titled(@Nullable TL_stars.StarGift gift, String fallback) {
        if (gift instanceof TL_stars.TL_starGiftUnique) {
            return titled((TL_stars.TL_starGiftUnique) gift, fallback);
        }
        return fallback;
    }

    /** Spannable-подпись «#???» с переливающимся градиентом. */
    public static CharSequence label() {
        SpannableStringBuilder builder = new SpannableStringBuilder(LABEL);
        builder.setSpan(new NumberSpan(), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    /** Дописывает « #???» в конец подписи и возвращает её же. */
    public static CharSequence appendTo(SpannableStringBuilder builder) {
        if (builder.length() > 0) {
            builder.append(' ');
        }
        int from = builder.length();
        builder.append(LABEL);
        builder.setSpan(new NumberSpan(), from, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    /**
     * Подключает перерисовку заголовка, в котором стоит {@link NumberSpan}: span сам
     * инвалидировать TextView не может, поэтому карточка регистрируется здесь.
     */
    public static void bindShimmer(@Nullable View view) {
        if (view == null) {
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).get() == view) {
                startTicker();
                return;
            }
        }
        targets.add(new WeakReference<>(view));
        startTicker();
    }

    private static void startTicker() {
        if (ticking) {
            return;
        }
        ticking = true;
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                // Полный цикл перелива — 2 секунды.
                phase = (frameTimeNanos / 1_000_000L % 2000L) / 2000f;
                boolean pending = false;
                for (Iterator<WeakReference<View>> iterator = targets.iterator(); iterator.hasNext(); ) {
                    View view = iterator.next().get();
                    if (view == null) {
                        iterator.remove();
                        continue;
                    }
                    // Вид может быть ещё не прикреплён в момент bind: цикл не должен
                    // останавливаться, иначе перелив замирает до повторного открытия карточки.
                    pending = true;
                    if (view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE) {
                        view.postInvalidateOnAnimation();
                    }
                }
                if (pending) {
                    Choreographer.getInstance().postFrameCallback(this);
                } else {
                    ticking = false;
                }
            }
        });
    }

    /** Текущая фаза перелива: общая для карточек, заголовков и лент подарков. */
    public static float phase() {
        return phase;
    }

    /**
     * Градиент перелива для произвольной кисти: используется там, где номер рисуется
     * не span-ом, а вручную (лента «#???» в ячейке подарка).
     */
    public static void applyShimmer(@Nullable Paint paint, float width, float textSize) {
        if (paint == null || width <= 0) {
            return;
        }
        final float shift = (phase - 0.5f) * width * 1.6f;
        paint.setShader(new LinearGradient(
                shift, 0f, shift + width * 1.4f, Math.max(1f, textSize),
                new int[]{COLOR_NEAR_BLACK, COLOR_DEEP_BLUE, COLOR_SHIMMER, COLOR_NEAR_BLACK},
                new float[]{0f, 0.38f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
    }

    /** Снимает градиент перелива с кисти. */
    public static void clearShimmer(@Nullable Paint paint) {
        if (paint != null) {
            paint.setShader(null);
        }
    }

    /** «#???» тёмно-синим с переливом в тёмно-чёрный. */
    public static class NumberSpan extends ReplacementSpan {

        private static final float SIZE_FACTOR = 0.85f;

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                           @Nullable Paint.FontMetricsInt fm) {
            TextPaint stroke = new TextPaint(paint);
            stroke.setTextSize(paint.getTextSize() * SIZE_FACTOR);
            if (fm != null) {
                Paint.FontMetricsInt metrics = stroke.getFontMetricsInt();
                fm.ascent = metrics.ascent;
                fm.descent = metrics.descent;
                fm.top = metrics.top;
                fm.bottom = metrics.bottom;
                fm.leading = metrics.leading;
            }
            return (int) Math.ceil(stroke.measureText(text, start, end));
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, @NonNull Paint paint) {
            TextPaint stroke = new TextPaint(paint);
            stroke.setTextSize(paint.getTextSize() * SIZE_FACTOR);
            final float width = stroke.measureText(text, start, end);
            if (width <= 0) {
                return;
            }
            final float shift = (phase - 0.5f) * width * 1.6f;
            stroke.setShader(new LinearGradient(
                    x + shift, y - stroke.getTextSize(), x + shift + width * 1.4f, y,
                    new int[]{COLOR_NEAR_BLACK, COLOR_DEEP_BLUE, COLOR_SHIMMER, COLOR_NEAR_BLACK},
                    new float[]{0f, 0.38f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawText(text, start, end, x, y, stroke);
        }
    }
}
