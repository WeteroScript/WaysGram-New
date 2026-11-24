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

    /** Края перелива: синий, читаемый на чёрной карточке. */
    private static final int COLOR_EDGE = 0xFF3A5FA8;
    /** Середина перелива. */
    private static final int COLOR_MID = 0xFF7FA3E8;
    /** Самая светлая точка, которая проходит по «#???». */
    private static final int COLOR_PEAK = 0xFFC9DBFF;

    /**
     * Палитра симметрична: крайние цвета одинаковые, поэтому «шов» тайла незаметен.
     *
     * <p>Раньше здесь были 0xFF05070B/0xFF16233F/0xFF24406B. На тёмной карточке
     * подарка (Theme.key_dialogGiftsBackground) такой номер сливался с фоном: на
     * скриншоте «#???» читался как чёрное пятно. Теперь перелив идёт по синим
     * тонам с достаточной яркостью, но остаётся приглушённым.
     */
    private static final int[] SHIMMER_COLORS = {
            COLOR_EDGE, COLOR_MID, COLOR_PEAK, COLOR_MID, COLOR_EDGE
    };
    private static final float[] SHIMMER_POSITIONS = {0f, 0.25f, 0.5f, 0.75f, 1f};

    /** Один цикл перелива: за него градиент проходит ровно один период зеркального тайла. */
    private static final long CYCLE_MS = 3600L;

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
            final TL_stars.starGiftAttributeModel model = (TL_stars.starGiftAttributeModel) attribute;
            if (model.name != null && MODEL_NAME.equalsIgnoreCase(model.name.trim())) {
                return true;
            }
            // Имя модели Telegram может локализовать, а документ модель несёт всегда:
            // встроенная анимация WaysGram однозначно выдаёт нашу модель.
            if (model.document != null
                    && model.document.id == org.telegram.messenger.waysgram.WaysGramBundledArtwork.WAYSGRAM_MODEL_DOCUMENT_ID) {
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
                phase = (frameTimeNanos / 1_000_000L % CYCLE_MS) / (float) CYCLE_MS;
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
    public static void applyShimmer(@Nullable Paint paint, float x, float width) {
        if (paint == null || width <= 0) {
            return;
        }
        paint.setShader(shimmerShader(x, width));
    }

    /**
     * Градиент перелива.
     *
     * <p>Раньше сдвиг шёл по «пиле» от -0.8 ширины к +0.8 ширины с {@code TileMode.CLAMP}:
     * в конце цикла градиент резко возвращался в начало, и «#???» дёргался. Теперь цвета
     * симметричны, тайл зеркальный, а за цикл сдвиг проходит ровно его период — картинка
     * непрерывна, стыка не видно. Градиент почти горизонтальный (1 px по вертикали, чтобы
     * шейдер не вырождался), поэтому по высоте текста ничего не отражается.
     */
    private static Shader shimmerShader(float x, float width) {
        final float length = Math.max(width * 1.4f, 1f);
        final float shift = phase * 2f * length;
        // Вертикальный размер в 1 пиксель: градиент остаётся горизонтальным, но не вырождается.
        return new LinearGradient(x - length + shift, 0f, x + shift, 1f,
                SHIMMER_COLORS, SHIMMER_POSITIONS, Shader.TileMode.MIRROR);
    }

    /** Снимает градиент перелива с кисти. */
    public static void clearShimmer(@Nullable Paint paint) {
        if (paint != null) {
            paint.setShader(null);
        }
    }

    /** «#???» синим с переливом от края к светлой середине. */
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
            stroke.setShader(shimmerShader(x, width));
            canvas.drawText(text, start, end, x, y, stroke);
        }
    }
}
