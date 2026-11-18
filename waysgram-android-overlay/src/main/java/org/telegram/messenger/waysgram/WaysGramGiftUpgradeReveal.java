package org.telegram.messenger.waysgram;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.BackupImageView;

import java.util.ArrayList;
import java.util.Random;

/**
 * Plays the upgrade reveal: the model, the pattern and the backdrop reels scroll through the
 * official Telegram candidates and stop one after another on the attributes the server rolled.
 *
 * <p>Telegram's own client rolls the three attributes on the server and animates the result; the
 * reels here use exactly the same candidate names, the same published chances (the server already
 * rolled with them) and the official backdrop colours, so the animation lands on the same kind of
 * card the original sheet shows — including the collection number, "Plush Pepe #1".</p>
 *
 * <p>No toast is shown on success: the reveal itself is the confirmation.</p>
 */
public final class WaysGramGiftUpgradeReveal {

    private static final long MODEL_SETTLES_AT = 640L;
    private static final long PATTERN_SETTLES_AT = 960L;
    private static final long BACKDROP_SETTLES_AT = 1280L;
    private static final long TICK = 45L;
    private static final long HOLD_AFTER_SETTLE = 1400L;

    private WaysGramGiftUpgradeReveal() {
    }

    public static void show(
            Context context, String serial, JSONObject result, Runnable onDone
    ) {
        if (context == null || result == null) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        try {
            new Panel(context, serial, result, onDone).start();
        } catch (Exception exception) {
            FileLog.e(exception);
            if (onDone != null) {
                onDone.run();
            }
        }
    }

    private static final class Panel implements Runnable {

        private final Context context;
        private final String serial;
        private final Runnable onDone;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Random random = new Random();

        private final TextView titleView;
        private final TextView numberView;
        private final TextView rarityView;
        private final TextView modelValue;
        private final TextView patternValue;
        private final TextView backdropValue;
        private final View artworkTint;
        private final GradientDrawable cardBackground;

        private final String finalModel;
        private final String finalPattern;
        private final String finalBackdrop;
        private final int finalFocusColor;
        private final int finalCenterColor;
        private final int finalEdgeColor;
        private final String giftName;
        private final int collectionNumber;
        private final String rarityText;

        private final String[] models;
        private final String[] patterns;
        private final String[] backdrops;
        private final int[] backdropCenter;
        private final int[] backdropEdge;

        private final Dialog dialog;
        private final long startedAt = SystemClock.uptimeMillis();
        private boolean settled;
        private boolean done;

        Panel(Context context, String serial, JSONObject result, Runnable onDone) {
            this.context = context;
            this.serial = serial;
            this.onDone = onDone;

            JSONObject traits = result.optJSONObject("traits");
            if (traits == null) {
                JSONObject attributes = result.optJSONObject("attributes");
                traits = attributes == null ? null : attributes.optJSONObject("collectible_traits");
            }
            JSONObject model = traitObject(traits, "model");
            JSONObject pattern = traitObject(traits, "pattern");
            JSONObject backdrop = traitObject(traits, "backdrop");
            this.finalModel = traitText(model, traits, "model", "Модель");
            this.finalPattern = traitText(pattern, traits, "pattern", "Узор");
            this.finalBackdrop = traitText(backdrop, traits, "backdrop", "Фон");
            this.finalCenterColor = backdrop == null
                    ? 0x00202A38 : backdrop.optInt("center_color", 0) | 0xFF000000;
            this.finalEdgeColor = backdrop == null
                    ? 0x00111A24 : backdrop.optInt("edge_color", 0) | 0xFF000000;
            this.finalFocusColor = backdrop == null
                    ? 0x007A6BFF : backdrop.optInt("pattern_color", 0) | 0xFF000000;
            this.giftName = result.optString("gift_name", "").trim();
            this.collectionNumber = Math.max(0, result.optInt("unique_num", 0));
            this.rarityText = rarityLabel(
                    traits == null ? "" : traits.optString("rarity", "")
            );

            JSONObject pool = result.optJSONObject("roll_pool");
            this.models = names(pool, "models", finalModel);
            this.patterns = names(pool, "patterns", finalPattern);
            this.backdrops = names(pool, "backdrops", finalBackdrop);
            JSONArray candidates = pool == null ? null : pool.optJSONArray("backdrops");
            int size = candidates == null ? 0 : candidates.length();
            this.backdropCenter = new int[Math.max(1, size)];
            this.backdropEdge = new int[Math.max(1, size)];
            for (int i = 0; i < size; i++) {
                JSONArray entry = candidates.optJSONArray(i);
                backdropCenter[i] = entry == null ? finalCenterColor : entry.optInt(2, 0) | 0xFF000000;
                backdropEdge[i] = entry == null ? finalEdgeColor : entry.optInt(3, 0) | 0xFF000000;
            }

            dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(true);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            int padding = dp(20);
            root.setPadding(padding, padding, padding, padding);
            GradientDrawable card = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{finalCenterColor, finalEdgeColor}
            );
            card.setCornerRadius(dp(20));
            cardBackground = card;
            root.setBackground(card);

            titleView = text(dp(17), 0xFFFFFFFF, true);
            titleView.setText(TextUtils.isEmpty(giftName)
                    ? "Улучшение подарка" : giftName);
            titleView.setGravity(Gravity.CENTER);
            root.addView(titleView, params(-1, -2));

            FrameLayout artwork = new FrameLayout(context);
            artwork.setClipToOutline(false);
            LinearLayout.LayoutParams artworkParams = new LinearLayout.LayoutParams(dp(148), dp(148));
            artworkParams.topMargin = dp(14);
            root.addView(artwork, artworkParams);

            BackupImageView imageView = new BackupImageView(context);
            artwork.addView(imageView, new FrameLayout.LayoutParams(-1, -1));
            WaysGramGiftArtwork.apply(imageView, serial);

            artworkTint = new View(context);
            artworkTint.setBackgroundColor(finalFocusColor);
            artworkTint.setAlpha(0f);
            FrameLayout.LayoutParams tintParams = new FrameLayout.LayoutParams(-1, dp(96));
            tintParams.gravity = Gravity.BOTTOM;
            artwork.addView(artworkTint, tintParams);

            numberView = text(dp(15), 0xFFFFFFFF, true);
            numberView.setGravity(Gravity.CENTER);
            numberView.setText("");
            LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(-2, -2);
            numberParams.topMargin = dp(6);
            root.addView(numberView, numberParams);

            modelValue = reelRow(root, "Модель", finalModel);
            patternValue = reelRow(root, "Узор", finalPattern);
            backdropValue = reelRow(root, "Фон", finalBackdrop);

            rarityView = text(dp(13), 0xCCFFFFFF, false);
            rarityView.setGravity(Gravity.CENTER);
            rarityView.setText("");
            LinearLayout.LayoutParams rarityParams = new LinearLayout.LayoutParams(-2, -2);
            rarityParams.topMargin = dp(8);
            root.addView(rarityView, rarityParams);

            TextView hint = text(dp(11), 0x99FFFFFF, false);
            hint.setText("Нажмите, чтобы пропустить");
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-2, -2);
            hintParams.topMargin = dp(10);
            root.addView(hint, hintParams);

            root.setOnClickListener(v -> skip());

            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.dimAmount = 0.6f;
                attributes.width = Math.min(dp(320), context.getResources().getDisplayMetrics().widthPixels - dp(48));
                window.setAttributes(attributes);
            }
            dialog.setOnDismissListener(d -> finishOnce());
        }

        void start() {
            dialog.show();
            handler.postDelayed(this, TICK);
        }

        @Override
        public void run() {
            long elapsed = SystemClock.uptimeMillis() - startedAt;
            if (!settled) {
                if (elapsed < MODEL_SETTLES_AT) {
                    modelValue.setText(pick(models, finalModel));
                } else if (modelValue.getTag() == null) {
                    modelValue.setTag(Boolean.TRUE);
                    modelValue.setText(finalModel);
                }
                if (elapsed < PATTERN_SETTLES_AT) {
                    patternValue.setText(pick(patterns, finalPattern));
                } else if (patternValue.getTag() == null) {
                    patternValue.setTag(Boolean.TRUE);
                    patternValue.setText(finalPattern);
                }
                if (elapsed < BACKDROP_SETTLES_AT) {
                    backdropValue.setText(pick(backdrops, finalBackdrop));
                    paintRandomBackdrop();
                } else if (backdropValue.getTag() == null) {
                    backdropValue.setTag(Boolean.TRUE);
                    backdropValue.setText(finalBackdrop);
                }
                boolean allSettled = modelValue.getTag() != null
                        && patternValue.getTag() != null
                        && backdropValue.getTag() != null;
                if (!allSettled) {
                    handler.postDelayed(this, TICK);
                    return;
                }
                applyResult(true);
                return;
            }
        }

        private void applyResult(boolean animate) {
            settled = true;
            cardBackground.setColors(new int[]{finalCenterColor, finalEdgeColor});
            modelValue.setText(finalModel);
            patternValue.setText(finalPattern);
            backdropValue.setText(finalBackdrop);
            artworkTint.setBackgroundColor(modelColor(finalModel));
            artworkTint.animate().alpha(0.22f).setDuration(220L).start();
            rarityView.setText(rarityText);
            String number = collectionNumber > 0 ? " #" + collectionNumber : "";
            titleView.setText((TextUtils.isEmpty(giftName) ? "Подарок" : giftName) + number);
            numberView.setText(collectionNumber > 0
                    ? "Новый номер коллекции: #" + collectionNumber : "");
            if (animate) {
                numberView.setScaleX(0.6f);
                numberView.setScaleY(0.6f);
                numberView.setAlpha(0f);
                numberView.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(260L).start();
            } else {
                numberView.setAlpha(1f);
            }
            handler.postDelayed(this::dismiss, HOLD_AFTER_SETTLE);
        }

        private void skip() {
            if (settled) {
                return;
            }
            handler.removeCallbacks(this);
            applyResult(false);
        }

        private void dismiss() {
            if (dialog.isShowing()) {
                dialog.dismiss();
            } else {
                finishOnce();
            }
        }

        private void finishOnce() {
            if (done) {
                return;
            }
            done = true;
            handler.removeCallbacksAndMessages(null);
            if (onDone != null) {
                onDone.run();
            }
        }

        private void paintRandomBackdrop() {
            if (backdropCenter.length == 0) {
                return;
            }
            int index = random.nextInt(backdropCenter.length);
            cardBackground.setColors(new int[]{backdropCenter[index], backdropEdge[index]});
            artworkTint.setBackgroundColor(modelColor(pick(models, finalModel)));
        }

        private TextView reelRow(LinearLayout parent, String label, String value) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.topMargin = dp(8);
            parent.addView(row, rowParams);

            TextView caption = text(dp(12), 0xAAFFFFFF, false);
            caption.setText(label);
            row.addView(caption, new LinearLayout.LayoutParams(dp(64), -2));

            TextView field = text(dp(13), 0xFFFFFFFF, true);
            field.setText(value);
            field.setSingleLine(true);
            field.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
            return field;
        }

        private TextView text(int size, int color, boolean bold) {
            TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            view.setTextColor(color);
            if (bold) {
                view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
            }
            return view;
        }

        private LinearLayout.LayoutParams params(int width, int height) {
            return new LinearLayout.LayoutParams(width, height);
        }

        private int dp(int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        private String pick(String[] pool, String fallback) {
            if (pool == null || pool.length == 0) {
                return fallback;
            }
            return pool[random.nextInt(pool.length)];
        }
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
            if (!legacy.isEmpty()) {
                return legacy;
            }
        }
        return fallback;
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

    /** Telegram paints every model with its own colourway; the reveal uses it as a tint. */
    private static int modelColor(String model) {
        float hue = Math.floorMod(model == null ? 0 : model.hashCode(), 360);
        return Color.HSVToColor(new float[]{hue, 0.45f, 1f});
    }

    private static String rarityLabel(String rarityKey) {
        String rarity = rarityKey == null ? "" : rarityKey.trim().toLowerCase(java.util.Locale.ROOT);
        if (rarity.equals("uncommon")) {
            return "Необычная редкость";
        }
        if (rarity.equals("rare")) {
            return "Редкая редкость";
        }
        if (rarity.equals("epic")) {
            return "Эпическая редкость";
        }
        if (rarity.equals("legendary")) {
            return "Легендарная редкость";
        }
        return "Обычная редкость";
    }
}
