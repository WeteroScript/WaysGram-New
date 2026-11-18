package org.telegram.messenger.waysgram;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.BackupImageView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Plays the WaysGram upgrade reveal.
 *
 * <p>Three reels — model, pattern and backdrop — scroll continuously through the official
 * Telegram candidates, decelerate and click into place one after another while the card
 * gradient and the artwork tint travel with them. The card is wrapped in a frame that turns
 * into the colour of the rolled rarity.</p>
 *
 * <p>While the reveal runs the only control is «Пропустить»; when it settles the buttons
 * «Следующий подарок» and «Готово» fade in. No toast confirms the upgrade: the reveal does.</p>
 */
public final class WaysGramGiftUpgradeReveal {

    /** Called when the user leaves the reveal. */
    public interface Listener {
        /** The user is done — close everything and reload the inventory. */
        void onDone();

        /** The user wants the next gift that can still be upgraded. */
        void onNextGift();
    }

    private static final long MODEL_DURATION = 1300L;
    private static final long PATTERN_DURATION = 1550L;
    private static final long BACKDROP_DURATION = 1800L;
    private static final long PATTERN_DELAY = 110L;
    private static final long BACKDROP_DELAY = 220L;

    private WaysGramGiftUpgradeReveal() {
    }

    public static void show(
            Context context, String serial, JSONObject result, Listener listener
    ) {
        if (context == null || result == null) {
            if (listener != null) {
                listener.onDone();
            }
            return;
        }
        try {
            new Panel(context, serial, result, listener).show();
        } catch (Exception exception) {
            FileLog.e(exception);
            if (listener != null) {
                listener.onDone();
            }
        }
    }

    private static final class Panel {

        private final Context context;
        private final String serial;
        private final Listener listener;
        private final Dialog dialog;

        private final LinearLayout card;
        private final GradientDrawable frameBackground;
        private final GradientDrawable cardBackground;
        private final BackupImageView artworkView;
        private final ColorDrawable tintDrawable;
        private final int[] gradientColors = new int[2];
        private final View tint;
        private final TextView titleView;
        private final TextView numberView;
        private final TextView rarityView;
        private final TextView skipButton;
        private final TextView nextButton;
        private final TextView doneButton;
        private final LinearLayout actions;

        private final Reel modelReel;
        private final Reel patternReel;
        private final Reel backdropReel;

        private final String[] models;
        private final String[] patterns;
        private final String[] backdrops;
        private final int[] backdropCenter;
        private final int[] backdropEdge;

        private final String finalModel;
        private final String finalPattern;
        private final String finalBackdrop;
        private final String giftName;
        private final String rarityText;
        private final int finalCenterColor;
        private final int finalEdgeColor;
        private final int accentColor;
        private final int collectionNumber;

        private final ArgbEvaluator argb = new ArgbEvaluator();
        private final ArrayList<ValueAnimator> animators = new ArrayList<>();
        private float lastGradientPosition = -1f;
        private float lastTintPosition = -1f;

        private boolean finished;
        private boolean closed;

        Panel(Context context, String serial, JSONObject result, Listener listener) {
            this.context = context;
            this.serial = serial;
            this.listener = listener;

            JSONObject traits = result.optJSONObject("traits");
            if (traits == null) {
                JSONObject attributes = result.optJSONObject("attributes");
                traits = attributes == null
                        ? null : attributes.optJSONObject("collectible_traits");
            }
            JSONObject model = traitObject(traits, "model");
            JSONObject pattern = traitObject(traits, "pattern");
            JSONObject backdrop = traitObject(traits, "backdrop");
            finalModel = traitText(model, traits, "model", "Модель");
            finalPattern = traitText(pattern, traits, "pattern", "Узор");
            finalBackdrop = traitText(backdrop, traits, "backdrop", "Фон");
            finalCenterColor = color(backdrop, "center_color", 0x00202A38);
            finalEdgeColor = color(backdrop, "edge_color", 0x00111A24);
            giftName = result.optString("gift_name", "").trim();
            collectionNumber = Math.max(0, result.optInt("unique_num", 0));
            String rarityKey = traits == null ? "" : traits.optString("rarity", "");
            rarityText = rarityLabel(rarityKey);
            accentColor = accentFor(rarityKey);

            JSONObject pool = result.optJSONObject("roll_pool");
            models = names(pool, "models", finalModel);
            patterns = names(pool, "patterns", finalPattern);
            backdrops = names(pool, "backdrops", finalBackdrop);
            JSONArray candidates = pool == null ? null : pool.optJSONArray("backdrops");
            int size = candidates == null ? 0 : candidates.length();
            backdropCenter = new int[Math.max(1, size)];
            backdropEdge = new int[Math.max(1, size)];
            for (int i = 0; i < size; i++) {
                JSONArray entry = candidates.optJSONArray(i);
                backdropCenter[i] = entry == null
                        ? finalCenterColor : entry.optInt(2, 0) | 0xFF000000;
                backdropEdge[i] = entry == null
                        ? finalEdgeColor : entry.optInt(3, 0) | 0xFF000000;
            }

            dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);

            // Outer frame: two device pixels of accent colour, the card itself sits inside.
            frameBackground = rounded(0x00000000, 0xC7FFFFFF, dp(2), dp(24));
            FrameLayout frameView = new FrameLayout(context);
            frameView.setBackground(frameBackground);
            frameView.setPadding(dp(3), dp(3), dp(3), dp(3));

            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(18), dp(18), dp(18), dp(16));
            cardBackground = rounded(finalCenterColor, 0, 0, dp(21));
            cardBackground.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            card.setBackground(cardBackground);
            frameView.addView(card, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            titleView = text(17, 0xFFFFFFFF, true);
            titleView.setGravity(Gravity.CENTER);
            titleView.setText(TextUtils.isEmpty(giftName) ? "Улучшение подарка" : giftName);
            card.addView(titleView, matchWrap(0));

            FrameLayout artworkHolder = new FrameLayout(context);
            LinearLayout.LayoutParams artworkParams = new LinearLayout.LayoutParams(dp(150), dp(150));
            artworkParams.topMargin = dp(12);
            artworkParams.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(artworkHolder, artworkParams);

            artworkView = new BackupImageView(context);
            artworkHolder.addView(artworkView, new FrameLayout.LayoutParams(-1, -1));
            // Only the still frame is drawn while the reels spin: decoding an animated sticker on
            // the UI thread at the same time is what made the reveal stutter on real devices.
            if (WaysGramGiftArtwork.hasArtwork(serial)) {
                WaysGramGiftArtwork.applyStill(artworkView, serial);
            } else {
                TextView emojiView = text(56, 0xFFFFFFFF, false);
                emojiView.setGravity(Gravity.CENTER);
                emojiView.setText(result.optString("gift_emoji", "🎁"));
                artworkHolder.addView(emojiView, new FrameLayout.LayoutParams(-1, -1));
            }

            tintDrawable = new ColorDrawable(modelColor(models[0]));
            tint = new View(context);
            tint.setBackground(tintDrawable);
            tint.setAlpha(0.32f);
            FrameLayout.LayoutParams tintParams = new FrameLayout.LayoutParams(-1, dp(96));
            tintParams.gravity = Gravity.BOTTOM;
            artworkHolder.addView(tint, tintParams);

            numberView = text(15, 0xFFFFFFFF, true);
            numberView.setGravity(Gravity.CENTER);
            numberView.setText("");
            numberView.setAlpha(0f);
            card.addView(numberView, matchWrap(2));

            LinearLayout reels = new LinearLayout(context);
            reels.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams reelsParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            reelsParams.topMargin = dp(10);
            card.addView(reels, reelsParams);

            modelReel = addReel(reels, "Модель", models);
            patternReel = addReel(reels, "Узор", patterns);
            backdropReel = addReel(reels, "Фон", backdrops);

            rarityView = text(12, 0xCCFFFFFF, false);
            rarityView.setGravity(Gravity.CENTER);
            rarityView.setText(rarityText);
            rarityView.setAlpha(0f);
            card.addView(rarityView, matchWrap(8));

            actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            actionsParams.topMargin = dp(14);
            card.addView(actions, actionsParams);

            skipButton = button("Пропустить", 0x33FFFFFF, 0xFFFFFFFF);
            skipButton.setOnClickListener(v -> skip());
            actions.addView(skipButton, weightWrap());

            nextButton = button("Следующий подарок", accentColor, 0xFF10131A);
            nextButton.setVisibility(View.GONE);
            nextButton.setAlpha(0f);
            nextButton.setOnClickListener(v -> finishWithNext());
            actions.addView(nextButton, weightWrap());

            doneButton = button("Готово", 0x33FFFFFF, 0xFFFFFFFF);
            doneButton.setVisibility(View.GONE);
            doneButton.setAlpha(0f);
            doneButton.setOnClickListener(v -> finishWithDone());
            LinearLayout.LayoutParams doneParams = weightWrap();
            doneParams.leftMargin = dp(8);
            actions.addView(doneButton, doneParams);

            dialog.setContentView(frameView);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.dimAmount = 0.62f;
                int width = Math.min(dp(330), screenWidth() - dp(40));
                attributes.width = width;
                attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(attributes);
            }
            dialog.setOnDismissListener(d -> close());
        }

        void show() {
            dialog.show();
            applyBackdrop(0f);
            // Animated text and background on a hardware layer keep the frames off the software
            // rasterizer; the layer is released as soon as the reels settle.
            card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            card.setAlpha(0f);
            card.setScaleX(0.94f);
            card.setScaleY(0.94f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
            startReels();
        }

        private void startReels() {
            launch(modelReel, models, finalModel, MODEL_DURATION, 0L);
            launch(patternReel, patterns, finalPattern, PATTERN_DURATION, PATTERN_DELAY);
            ValueAnimator backdropAnimator = launch(
                    backdropReel, backdrops, finalBackdrop, BACKDROP_DURATION, BACKDROP_DELAY
            );
            ValueAnimator modelTintAnimator = trail(
                    models.length, finalModel, MODEL_DURATION, 0L,
                    value -> applyModelTint(value)
            );
            // The gradient travels with the backdrop reel, so colour and name always agree.
            backdropAnimator.addUpdateListener(
                    animation -> applyBackdrop((float) animation.getAnimatedValue())
            );
            backdropAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!finished) {
                        settle();
                    }
                }
            });
            if (modelTintAnimator != null) {
                animators.add(modelTintAnimator);
            }
        }

        private ValueAnimator launch(
                Reel reel, String[] values, String target, long duration, long delay
        ) {
            int index = indexOf(values, target);
            float end = index + Math.max(3, 2 + values.length / 6);
            ValueAnimator animator = ValueAnimator.ofFloat(0f, Math.max(1f, end));
            animator.setDuration(duration);
            animator.setStartDelay(delay);
            animator.setInterpolator(new DecelerateInterpolator(1.75f));
            animator.addUpdateListener(
                    animation -> reel.setPosition((float) animation.getAnimatedValue())
            );
            animator.start();
            animators.add(animator);
            return animator;
        }

        /** Same motion as the model reel, used for the artwork tint. */
        private ValueAnimator trail(
                int count, String target, long duration, long delay, FloatSink sink
        ) {
            if (count <= 1) {
                return null;
            }
            float end = Math.max(1f, Math.max(3, 2 + count / 6));
            ValueAnimator animator = ValueAnimator.ofFloat(0f, end);
            animator.setDuration(duration);
            animator.setStartDelay(delay);
            animator.setInterpolator(new DecelerateInterpolator(1.75f));
            animator.addUpdateListener(animation -> sink.accept((float) animation.getAnimatedValue()));
            animator.start();
            return animator;
        }

        private void applyModelTint(float position) {
            int count = models.length;
            if (count == 0 || Math.abs(position - lastTintPosition) < 0.002f) {
                return;
            }
            lastTintPosition = position;
            int index = (int) Math.floor(position);
            float fraction = position - index;
            int from = modelColor(models[circular(index, count)]);
            int to = modelColor(models[circular(index + 1, count)]);
            tintDrawable.setColor(blend(from, to, fraction));
        }

        private void applyBackdrop(float position) {
            if (backdropCenter.length == 0) {
                setGradient(finalCenterColor, finalEdgeColor);
                return;
            }
            if (Math.abs(position - lastGradientPosition) < 0.002f) {
                return;
            }
            lastGradientPosition = position;
            int count = backdropCenter.length;
            int index = (int) Math.floor(position);
            float fraction = position - index;
            int centerFrom = backdropCenter[circular(index, count)];
            int centerTo = backdropCenter[circular(index + 1, count)];
            int edgeFrom = backdropEdge[circular(index, count)];
            int edgeTo = backdropEdge[circular(index + 1, count)];
            setGradient(
                    blend(centerFrom, centerTo, fraction),
                    blend(edgeFrom, edgeTo, fraction)
            );
        }

        /** Reuses one colour array so the animation allocates nothing per frame. */
        private void setGradient(int center, int edge) {
            if (gradientColors[0] == center && gradientColors[1] == edge) {
                return;
            }
            gradientColors[0] = center;
            gradientColors[1] = edge;
            cardBackground.setColors(gradientColors);
        }

        private void settle() {
            if (finished) {
                return;
            }
            finished = true;
            modelReel.setPosition(indexOf(models, finalModel));
            patternReel.setPosition(indexOf(patterns, finalPattern));
            backdropReel.setPosition(indexOf(backdrops, finalBackdrop));
            setGradient(finalCenterColor, finalEdgeColor);
            tintDrawable.setColor(modelColor(finalModel));
            tint.setAlpha(0.24f);
            card.setLayerType(View.LAYER_TYPE_NONE, null);
            // From here the gift's own animation loops for as long as the panel is open.
            if (WaysGramGiftArtwork.hasArtwork(serial)) {
                WaysGramGiftArtwork.apply(artworkView, serial);
                WaysGramGiftArtwork.play(artworkView);
            }

            String number = collectionNumber > 0
                    ? (TextUtils.isEmpty(giftName) ? "Подарок" : giftName) + " #" + collectionNumber
                    : (TextUtils.isEmpty(giftName) ? "Подарок" : giftName);
            titleView.setText(number);
            numberView.setText(collectionNumber > 0
                    ? "Номер в коллекции: #" + collectionNumber : "");

            animateColor(frameBackground, 0xC7FFFFFF, accentColor, 420L);
            fadeIn(rarityView, 260L, 120L);
            numberView.setScaleX(0.72f);
            numberView.setScaleY(0.72f);
            numberView.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(90L)
                    .setDuration(300L)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .start();

            skipButton.animate().alpha(0f).setDuration(160L)
                    .withEndAction(() -> skipButton.setVisibility(View.GONE))
                    .start();
            revealActions();
        }

        private void revealActions() {
            nextButton.setVisibility(View.VISIBLE);
            doneButton.setVisibility(View.VISIBLE);
            fadeIn(nextButton, 240L, 200L);
            fadeIn(doneButton, 240L, 300L);
        }

        private void skip() {
            if (finished) {
                return;
            }
            for (int i = 0; i < animators.size(); i++) {
                animators.get(i).cancel();
            }
            settle();
        }

        private void finishWithDone() {
            dialog.dismiss();
        }

        private void finishWithNext() {
            if (listener != null && !closed) {
                closed = true;
                listener.onNextGift();
            }
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            WaysGramGiftArtwork.stop(artworkView);
            for (int i = 0; i < animators.size(); i++) {
                animators.get(i).cancel();
            }
            animators.clear();
            if (listener != null) {
                listener.onDone();
            }
        }

        private Reel addReel(LinearLayout parent, String caption, String[] values) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rowParams.topMargin = dp(7);
            parent.addView(row, rowParams);

            TextView label = text(12, 0xAAFFFFFF, false);
            label.setText(caption);
            row.addView(label, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

            Reel reel = new Reel(context);
            reel.setValues(values);
            reel.setTextSizePx(sp(13.5f));
            reel.setTextColor(0xFFFFFFFF);
            row.addView(reel, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ));
            return reel;
        }

        private TextView button(String label, int background, int foreground) {
            TextView view = new TextView(context);
            view.setText(label);
            view.setGravity(Gravity.CENTER);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            view.setTextColor(foreground);
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            view.setPadding(dp(12), dp(10), dp(12), dp(10));
            view.setBackground(rounded(background, 0, 0, dp(14)));
            return view;
        }

        private void fadeIn(View view, long duration, long delay) {
            view.setAlpha(0f);
            view.animate().alpha(1f).setStartDelay(delay).setDuration(duration).start();
        }

        private void animateColor(
                final GradientDrawable drawable, int from, int to, long duration
        ) {
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            animator.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                int color = (Integer) argb.evaluate(fraction, from, to);
                drawable.setStroke(dp(2), color);
            });
            animator.start();
        }

        private TextView text(float sizeSp, int color, boolean bold) {
            TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            view.setTextColor(color);
            if (bold) {
                view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            }
            return view;
        }

        private LinearLayout.LayoutParams matchWrap(int topMarginDp) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (topMarginDp != 0) {
                params.topMargin = dp(topMarginDp);
            }
            return params;
        }

        private LinearLayout.LayoutParams weightWrap() {
            return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        }

        private int dp(int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        private float sp(float value) {
            return value * context.getResources().getDisplayMetrics().scaledDensity;
        }

        private int screenWidth() {
            return context.getResources().getDisplayMetrics().widthPixels;
        }

        private int circular(int index, int count) {
            return Math.floorMod(index, Math.max(1, count));
        }

        private int blend(int from, int to, float fraction) {
            return (Integer) argb.evaluate(Math.max(0f, Math.min(1f, fraction)), from, to);
        }
    }

    /** One continuously scrolling reel; it draws the current and the incoming value. */
    private static final class Reel extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String[] values = new String[]{"—"};
        private float position;

        Reel(Context context) {
            super(context);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setColor(0xFFFFFFFF);
        }

        void setValues(String[] values) {
            this.values = values == null || values.length == 0
                    ? new String[]{"—"} : values;
            invalidate();
        }

        void setTextSizePx(float size) {
            paint.setTextSize(size);
            requestLayout();
        }

        void setTextColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void setPosition(float position) {
            this.position = position;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = Math.round(paint.getTextSize() * 1.85f);
            setMeasuredDimension(width, Math.max(height, 1));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int count = values.length;
            if (count == 0) {
                return;
            }
            int index = (int) Math.floor(position);
            float fraction = position - index;
            String current = values[Math.floorMod(index, count)];
            String incoming = values[Math.floorMod(index + 1, count)];
            float lineHeight = paint.getTextSize() * 1.85f;
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;
            float shift = fraction * lineHeight;
            int baseColor = paint.getColor();

            paint.setAlpha(Math.round(255f * Math.max(0f, 1f - fraction * 0.55f)));
            canvas.drawText(current, 0f, baseline - shift, paint);

            paint.setAlpha(Math.round(255f * Math.min(1f, 0.35f + fraction * 0.65f)));
            canvas.drawText(incoming, 0f, baseline + lineHeight - shift, paint);

            paint.setAlpha(Color.alpha(baseColor));
        }
    }

    private interface FloatSink {
        void accept(float value);
    }

    private static JSONObject traitObject(JSONObject traits, String key) {
        return traits == null ? null : traits.optJSONObject(key);
    }

    private static String traitText(
            JSONObject entry, JSONObject traits, String key, String fallback
    ) {
        if (entry != null) {
            String name = entry.optString("name", "").trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        if (traits != null) {
            String legacy = traits.optString(key, "").trim();
            if (!legacy.isEmpty() && !"null".equalsIgnoreCase(legacy)) {
                return legacy;
            }
        }
        return fallback;
    }

    private static int color(JSONObject entry, String key, int fallback) {
        if (entry == null) {
            return fallback;
        }
        int value = entry.optInt(key, 0);
        return value == 0 ? fallback : value | 0xFF000000;
    }

    private static String[] names(JSONObject pool, String key, String fallback) {
        JSONArray array = pool == null ? null : pool.optJSONArray(key);
        ArrayList<String> names = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONArray entry = array.optJSONArray(i);
                String name = entry == null ? "" : entry.optString(0, "").trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        if (names.isEmpty()) {
            names.add(fallback);
        }
        return names.toArray(new String[0]);
    }

    private static int indexOf(String[] values, String target) {
        if (values == null || values.length == 0) {
            return 0;
        }
        for (int i = 0; i < values.length; i++) {
            if (target != null && target.equals(values[i])) {
                return i;
            }
        }
        return 0;
    }

    /** Telegram paints every model with its own colourway; the reel uses it as a tint. */
    private static int modelColor(String model) {
        float hue = Math.floorMod(model == null ? 0 : model.hashCode(), 360);
        return Color.HSVToColor(new float[]{hue, 0.42f, 1f});
    }

    private static int accentFor(String rarityKey) {
        String rarity = rarityKey == null ? "" : rarityKey.trim().toLowerCase(Locale.ROOT);
        switch (rarity) {
            case "uncommon":
                return 0xFF4ADE80;
            case "rare":
                return 0xFF60A5FA;
            case "epic":
                return 0xFFC084FC;
            case "legendary":
                return 0xFFFBBF24;
            default:
                return 0xFF8AB4F8;
        }
    }

    private static String rarityLabel(String rarityKey) {
        String rarity = rarityKey == null ? "" : rarityKey.trim().toLowerCase(Locale.ROOT);
        switch (rarity) {
            case "uncommon":
                return "Необычная редкость";
            case "rare":
                return "Редкая редкость";
            case "epic":
                return "Эпическая редкость";
            case "legendary":
                return "Легендарная редкость";
            default:
                return "Обычная редкость";
        }
    }

    /** Rounded drawable; radii and stroke widths are already in pixels. */
    private static GradientDrawable rounded(
            int fill, int stroke, int strokeWidthPx, int radiusPx
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(radiusPx);
        if (strokeWidthPx > 0 && stroke != 0) {
            drawable.setStroke(strokeWidthPx, stroke);
        }
        return drawable;
    }
}
