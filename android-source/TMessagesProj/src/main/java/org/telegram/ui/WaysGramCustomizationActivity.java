package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.net.Uri;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.WaysGramApi;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.TextCheckCell;
import java.io.*;
import java.util.UUID;

public class WaysGramCustomizationActivity extends BaseFragment {
    private static final int MAX_IMAGE = 131072, PICK = 8710;
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Bitmap[] layers = new Bitmap[2];
    private static int renderedAccount = -1;
    private static String renderedKey;
    /** 0 — кастомизация выключена; иначе альфа фонов окна (сквозь них виден фон). */
    private static volatile int backdropAlpha;
    /** Акцентный цвет, выведенный из картинки подложки: им красятся основные кнопки. */
    private static volatile int accentColor;
    private static final java.util.WeakHashMap<View, Integer> navViews = new java.util.WeakHashMap<>();
    private static final Path clip = new Path();

    public static android.graphics.drawable.Drawable wrapBackground(int account, View view,
            android.graphics.drawable.Drawable ordinary, int inset) {
        navViews.put(view, account);
        return new Background(account, ordinary, inset);
    }

    private static class Background extends android.graphics.drawable.Drawable implements android.graphics.drawable.Drawable.Callback {
        final int account, inset;
        final android.graphics.drawable.Drawable ordinary;
        final RectF box = new RectF();
        Background(int account, android.graphics.drawable.Drawable ordinary, int inset) {
            this.account = account; this.ordinary = ordinary; this.inset = inset;
            ordinary.setCallback(this);
        }
        @Override public void draw(Canvas canvas) {
            refreshLayers(account);
            box.set(getBounds());
            box.inset(inset, inset);
            if (!drawLayer(canvas, 0, box, box.height() / 2f, 1f)) ordinary.draw(canvas);
        }
        @Override protected void onBoundsChange(Rect bounds) { ordinary.setBounds(bounds); }
        @Override public void setAlpha(int alpha) { ordinary.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { ordinary.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void invalidateDrawable(android.graphics.drawable.Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(android.graphics.drawable.Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(android.graphics.drawable.Drawable who, Runnable what) { unscheduleSelf(what); }
    }

    private static void invalidateNavigation(int account) {
        for (java.util.Map.Entry<View, Integer> entry : navViews.entrySet()) {
            if (entry.getValue() != account) continue;
            View view = entry.getKey();
            if (view == null) continue;
            view.invalidate();
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).invalidate();
            }
        }
    }

    private final String[] draft = {"", ""};
    private LinearLayout content;
    private boolean busy;

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("wg_customization_" + UserConfig.getInstance(account).getClientUserId(), Context.MODE_PRIVATE);
    }

    private static JSONObject object(String json) {
        try { return new JSONObject(json); } catch (Exception e) { return new JSONObject(); }
    }

    private static Bitmap bitmap(String data) {
        if (data.isEmpty() || data.length() > 175000) return null;
        try {
            byte[] b = Base64.decode(data, Base64.NO_WRAP);
            if (b.length > MAX_IMAGE) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (o.outWidth < 1 || o.outHeight < 1 || o.outWidth > 640 || o.outHeight > 256) return null;
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Exception e) { return null; }
    }

    public static void refreshLayers(int account) {
        SharedPreferences p = prefs(account);
        boolean enabled = p.getBoolean("enabled", false);
        String id = enabled ? p.getString("active", "") : "";
        String key = UserConfig.getInstance(account).getClientUserId() + ":" + id;
        if (renderedAccount == account && key.equals(renderedKey)) return;
        renderedAccount = account;
        renderedKey = key;
        JSONObject config = object(p.getString("config_" + id, ""));
        for (int i = 0; i < 2; i++) layers[i] = bitmap(config.optString("image" + i));
        backdropAlpha = enabled && layers[0] != null ? 214 : 0;
        accentColor = backdropAlpha == 0 ? 0 : layerAccent(layers[0]);
    }

    /**
     * Доминирующий насыщенный цвет подложки: акцент для кнопок и ссылок.
     * Если на картинке нет выраженного цвета (серый/тёмный снимок), акцент не задаётся —
     * иначе из средней яркости получался случайный оттенок (например зелёный).
     */
    private static int layerAccent(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() < 2 || bitmap.getHeight() < 2) return 0;
        int stepX = Math.max(1, bitmap.getWidth() / 24), stepY = Math.max(1, bitmap.getHeight() / 24);
        final float[] hsv = new float[3];
        final double[] weights = new double[12], reds = new double[12], greens = new double[12], blues = new double[12];
        double total = 0, bestWeight = 0;
        float maxSaturation = 0;
        for (int y = stepY / 2; y < bitmap.getHeight(); y += stepY) {
            for (int x = stepX / 2; x < bitmap.getWidth(); x += stepX) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 32) continue;
                Color.colorToHSV(pixel, hsv);
                if (hsv[2] < .12f) continue;
                maxSaturation = Math.max(maxSaturation, hsv[1]);
                if (hsv[1] < .25f) continue;
                int sector = Math.min(11, (int) (hsv[0] / 30f));
                double weight = hsv[1] * hsv[1] * (.35f + hsv[2]);
                weights[sector] += weight;
                reds[sector] += Color.red(pixel) * weight;
                greens[sector] += Color.green(pixel) * weight;
                blues[sector] += Color.blue(pixel) * weight;
                total += weight;
            }
        }
        int best = -1;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > bestWeight) {
                bestWeight = weights[i];
                best = i;
            }
        }
        if (best < 0 || total <= 0 || maxSaturation < .4f || bestWeight / total < .3) return 0;
        int pixel = Color.rgb((int) (reds[best] / bestWeight), (int) (greens[best] / bestWeight), (int) (blues[best] / bestWeight));
        Color.colorToHSV(pixel, hsv);
        hsv[2] = Math.max(.5f, Math.min(.68f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    /**
     * Фон окна и основные кнопки под кастомизацию. Вызывается из Theme.getColor,
     * поэтому обязано быть дешёвым и без обращений к prefs.
     */
    public static int windowColor(int key, int color) {
        final int accent = accentColor;
        // key_featuredStickers_addButton намеренно не подменяем: это кнопки покупок
        // и подтверждений («Buy More Stars», «OK», «Add a post») — они остаются синими Telegram.
        if (accent != 0 && (key == Theme.key_windowBackgroundWhiteBlueButton
                || key == Theme.key_windowBackgroundWhiteBlueIcon
                || key == Theme.key_windowBackgroundWhiteBlueText
                || key == Theme.key_windowBackgroundWhiteBlueText2
                || key == Theme.key_chat_messagePanelSend)) {
            return accent;
        }
        final int alpha = backdropAlpha;
        if (alpha == 0) return color;
        if (key == Theme.key_windowBackgroundWhite) return (color & 0x00ffffff) | (alpha << 24);
        if (key == Theme.key_windowBackgroundGray) return (color & 0x00ffffff) | (Math.min(255, alpha - 14) << 24);
        return color;
    }

    /** Картинка индикатора для любых вкладок; false — рисуйте штатный индикатор. */
    public static boolean drawIndicatorImage(Canvas canvas, RectF box, float radius, float opacity) {
        refreshLayers(UserConfig.selectedAccount);
        return drawLayer(canvas, 1, box, radius, opacity);
    }

    /** Фон переписки: подложка кастомизации вместо обоев, если она включена. */
    public static android.graphics.drawable.Drawable chatWallpaper(int account) {
        SharedPreferences p = prefs(account);
        if (!p.getBoolean("enabled", false) || p.getString("active", "").isEmpty()) return null;
        return new ImageBackdropDrawable(account, 0x2b000000);
    }

    public static boolean drawIndicator(View tabLayout, Canvas canvas, RectF box, float radius, float opacity) {
        Integer account = navViews.get(tabLayout);
        if (account == null) return false;
        refreshLayers(account);
        return drawLayer(canvas, 1, box, radius, opacity);
    }

    private static boolean drawLayer(Canvas canvas, int layer, RectF box, float radius, float opacity) {
        Bitmap b = layers[layer];
        if (b == null || box.isEmpty()) return false;
        clip.rewind();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip);
        PAINT.setAlpha(Math.max(0, Math.min(255, Math.round(opacity * 255))));
        canvas.drawBitmap(b, null, box, PAINT);
        canvas.restoreToCount(save);
        return true;
    }

    private static void cover(RectF dst, Rect bounds, Bitmap image) {
        float scale = Math.max(bounds.width() / (float) image.getWidth(), bounds.height() / (float) image.getHeight());
        float width = image.getWidth() * scale, height = image.getHeight() * scale;
        float centerX = bounds.exactCenterX(), centerY = bounds.exactCenterY();
        dst.set(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f);
    }

    /** Подложка, растянутая по площади с сохранением пропорций, плюс затемнение под текст. */
    public static class ImageBackdropDrawable extends android.graphics.drawable.Drawable {
        private final int account, scrimColor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint scrimPaint = new Paint();
        private final RectF dst = new RectF();
        private final Rect bounds = new Rect();

        public ImageBackdropDrawable(int account, int scrimColor) {
            this.account = account;
            this.scrimColor = scrimColor;
        }

        @Override public void draw(Canvas canvas) {
            refreshLayers(account);
            Bitmap image = layers[0];
            Rect area = getBounds();
            if (image == null || area.isEmpty()) return;
            cover(dst, area, image);
            canvas.drawBitmap(image, null, dst, paint);
            if (Color.alpha(scrimColor) != 0) {
                scrimPaint.setColor(scrimColor);
                canvas.drawRect(area, scrimPaint);
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Фон всего приложения: живёт под интерфейсом в LaunchActivity. */
    public static class BackdropView extends View {
        private final ImageBackdropDrawable drawable;
        private final Rect area = new Rect();

        public BackdropView(Context context, int account) {
            super(context);
            drawable = new ImageBackdropDrawable(account, 0x33000000);
            navViews.put(this, account);
        }

        @Override protected void onDraw(Canvas canvas) {
            area.set(0, 0, getWidth(), getHeight());
            drawable.setBounds(area);
            drawable.draw(canvas);
        }
    }

    @Override public View createView(Context context) {
        actionBar.setTitle(getString(R.string.WgCustomization));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(20));
        scroll.addView(content);
        fragmentView = scroll;
        rebuild();
        return scroll;
    }

    private String getString(int id) { return text(id); }

    // Текст берём с защитой от Resources$NotFoundException: в релизной сборке (shrinkResources +
    // R8 full mode) id ресурса может уже не указывать на строку, а голый TextView.setText(int)
    // на таком id падает и роняет экран.
    private String text(int id) {
        Context context = getContext();
        if (context != null) {
            try {
                return context.getString(id);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        String value = LocaleController.getString(id);
        return value == null || value.startsWith("LOC_ERR") ? "" : value;
    }

    /* ─────────────────────────────  Интерфейс  ───────────────────────────── */

    private static final long REVEAL_DELAY = 26L, REVEAL_DURATION = 220L;

    private final java.util.ArrayList<View> revealViews = new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> revealOrder = new java.util.ArrayList<>();

    /** Запоминает блок, который проявится после сборки экрана. */
    private void reveal(View view, int index) {
        view.setAlpha(0f);
        view.setTranslationY(AndroidUtilities.dp(12));
        revealViews.add(view);
        revealOrder.add(index);
    }

    /** Мягкий сдвиг вверх и проявление, с нарастающей задержкой по порядку блоков. */
    private void runReveal() {
        if (getContext() == null) return;
        for (int i = 0; i < revealViews.size(); i++) {
            View view = revealViews.get(i);
            int order = revealOrder.get(i);
            view.animate().alpha(1f).translationY(0f)
                    .setStartDelay(Math.min(order, 8) * REVEAL_DELAY)
                    .setDuration(REVEAL_DURATION)
                    .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT)
                    .start();
        }
        revealViews.clear();
        revealOrder.clear();
    }

    /** Картинка слоя: сначала несохранённый черновик, затем активный конфиг. */
    private Bitmap draftBitmap(int layer) {
        if (!draft[layer].isEmpty()) {
            Bitmap image = bitmap(draft[layer]);
            if (image != null) return image;
        }
        refreshLayers(currentAccount);
        return layers[layer];
    }

    private LinearLayout card(int index) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14), Theme.getColor(Theme.key_windowBackgroundWhite)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.leftMargin = AndroidUtilities.dp(12);
        params.rightMargin = AndroidUtilities.dp(12);
        card.setLayoutParams(params);
        reveal(card, index);
        return card;
    }

    private TextView sectionHeader(int titleRes, int topMargin, int index) {
        TextView view = new TextView(getContext());
        view.setText(text(titleRes));
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        view.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(topMargin), AndroidUtilities.dp(22), AndroidUtilities.dp(6));
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        reveal(view, index);
        return view;
    }

    private View divider() {
        View divider = new View(getContext());
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, AndroidUtilities.dp(0.5f)));
        params.leftMargin = AndroidUtilities.dp(16);
        divider.setLayoutParams(params);
        return divider;
    }

    private View toggleRow(int labelRes, boolean value, Runnable action, int index) {
        TextCheckCell cell = new TextCheckCell(getContext());
        cell.setBackground(null);
        cell.setTextAndCheck(text(labelRes), value, false);
        cell.setOnClickListener(v -> action.run());
        reveal(cell, index);
        return cell;
    }

    private View layerRow(int layer, int index) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14),
                Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_listSelector)));
        row.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10));

        LayerChip chip = new LayerChip(context, draftBitmap(layer), layer == 0);
        row.addView(chip, new LinearLayout.LayoutParams(AndroidUtilities.dp(layer == 0 ? 62 : 40), AndroidUtilities.dp(30)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1f);
        textsParams.leftMargin = AndroidUtilities.dp(14);
        textsParams.rightMargin = AndroidUtilities.dp(8);
        TextView title = new TextView(context);
        title.setText(text(layer == 0 ? R.string.WgCustomBackground : R.string.WgCustomIndicator));
        title.setTextSize(16);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView state = new TextView(context);
        state.setText(text(draft[layer].isEmpty() ? R.string.WgCustomLayerEmpty : R.string.WgCustomLayerSet));
        state.setTextSize(13);
        state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        texts.addView(title);
        texts.addView(state);
        row.addView(texts, textsParams);

        ImageView action = new ImageView(context);
        action.setImageResource(R.drawable.msg_gallery);
        action.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        action.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        action.setBackground(Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(40), 0, Theme.getColor(Theme.key_listSelector)));
        action.setContentDescription(text(R.string.WgCustomUpload));
        action.setOnClickListener(v -> pick(layer));
        row.addView(action, new LinearLayout.LayoutParams(AndroidUtilities.dp(40), AndroidUtilities.dp(40)));

        row.setOnClickListener(v -> pick(layer));
        reveal(row, index);
        return row;
    }

    private View primaryButton(int labelRes, boolean clickable, Runnable click, int index) {
        TextView button = new TextView(getContext());
        button.setText(text(labelRes));
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12),
                Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton), Theme.getColor(Theme.key_listSelector)));
        button.setAlpha(clickable ? 1f : .45f);
        button.setEnabled(clickable);
        if (clickable) {
            button.setOnClickListener(v -> click.run());
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(46));
        params.leftMargin = AndroidUtilities.dp(12);
        params.rightMargin = AndroidUtilities.dp(12);
        params.topMargin = AndroidUtilities.dp(14);
        button.setLayoutParams(params);
        reveal(button, index);
        return button;
    }

    private View configRow(String id, JSONObject config, boolean active, int index) {
        Context context = getContext();
        SharedPreferences p = prefs(currentAccount);
        String name = config.optString("name");
        if (name == null || name.isEmpty()) name = text(R.string.WgCustomUntitled);
        final String title = name;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14),
                Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_listSelector)));
        row.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(8));

        View dot = new View(context);
        dot.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4),
                Theme.getColor(active ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText3)));
        row.addView(dot, new LinearLayout.LayoutParams(AndroidUtilities.dp(8), AndroidUtilities.dp(8)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1f);
        textsParams.leftMargin = AndroidUtilities.dp(12);
        TextView nameView = new TextView(context);
        nameView.setText(name);
        nameView.setTextSize(15);
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(nameView);
        if (active) {
            TextView badge = new TextView(context);
            badge.setText(text(R.string.WgCustomActive));
            badge.setTextSize(12);
            badge.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            texts.addView(badge);
        }
        row.addView(texts, textsParams);

        ImageView copy = new ImageView(context);
        copy.setImageResource(R.drawable.msg_copy);
        copy.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        copy.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        copy.setBackground(Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(38), 0, Theme.getColor(Theme.key_listSelector)));
        copy.setContentDescription(getString(R.string.CopyLink));
        copy.setOnClickListener(v -> share(config));
        row.addView(copy, new LinearLayout.LayoutParams(AndroidUtilities.dp(38), AndroidUtilities.dp(38)));

        ImageView delete = new ImageView(context);
        delete.setImageResource(R.drawable.msg_delete);
        delete.setColorFilter(Theme.getColor(Theme.key_text_RedRegular));
        delete.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        delete.setBackground(Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(38), 0, Theme.getColor(Theme.key_listSelector)));
        delete.setContentDescription(text(R.string.WgCustomDelete));
        delete.setOnClickListener(v -> confirmDelete(id, title, row));
        row.addView(delete, new LinearLayout.LayoutParams(AndroidUtilities.dp(38), AndroidUtilities.dp(38)));

        org.telegram.ui.Components.Switch select = new org.telegram.ui.Components.Switch(context);
        select.setChecked(active, false);
        select.setContentDescription(name);
        select.setOnClickListener(v -> {
            p.edit().putString("active", active ? "" : id).apply();
            changed();
        });
        row.addView(select, new LinearLayout.LayoutParams(AndroidUtilities.dp(46), AndroidUtilities.dp(40)));

        reveal(row, index);
        return row;
    }

    private View emptyState(int index) {
        TextView view = new TextView(getContext());
        view.setText(text(R.string.WgCustomEmpty));
        view.setTextSize(14);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        view.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(18), AndroidUtilities.dp(22), AndroidUtilities.dp(8));
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        reveal(view, index);
        return view;
    }

    private void confirmDelete(String id, String name, View row) {
        if (getContext() == null) return;
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(name)
                .setMessage(text(R.string.WgCustomDeleteConfirm))
                .setPositiveButton(text(R.string.WgCustomDelete), (d, w) -> deleteConfig(id, row))
                .setNegativeButton(text(R.string.Cancel), null)
                .create();
        showDialog(dialog);
    }

    private void deleteConfig(String id, View row) {
        SharedPreferences p = prefs(currentAccount);
        boolean wasActive = id.equals(p.getString("active", ""));
        android.content.SharedPreferences.Editor editor = p.edit().remove("config_" + id);
        if (wasActive) editor.putString("active", "");
        editor.apply();
        refreshLayers(currentAccount);
        invalidateNavigation(currentAccount);
        if (row != null) {
            row.animate().alpha(0f).translationX(AndroidUtilities.dp(32))
                    .setDuration(160)
                    .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT)
                    .withEndAction(this::rebuild)
                    .start();
        } else {
            rebuild();
        }
    }

    /** Мини-превью панели навигации: подложка + «пилюля» активной вкладки. */
    private static class LayersPreview extends View {
        private final Bitmap background, indicator;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), pill = new RectF();
        private final Path path = new Path();

        LayersPreview(Context context, Bitmap background, Bitmap indicator) {
            super(context);
            this.background = background;
            this.indicator = indicator;
        }

        @Override protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            box.set(0, 0, getWidth(), getHeight());
            path.rewind();
            path.addRoundRect(box, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(path);
            shapePaint.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            canvas.drawRect(box, shapePaint);
            if (background != null) canvas.drawBitmap(background, null, box, bitmapPaint);

            float pillWidth = box.width() * .3f, pillHeight = box.height() * .66f;
            float left = box.left + box.width() * .38f - pillWidth / 2f;
            pill.set(left, box.centerY() - pillHeight / 2f, left + pillWidth, box.centerY() + pillHeight / 2f);
            if (indicator != null) {
                canvas.drawBitmap(indicator, null, pill, bitmapPaint);
            } else {
                shapePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                canvas.drawRoundRect(pill, pillHeight / 2f, pillHeight / 2f, shapePaint);
            }
            canvas.restoreToCount(save);

            shapePaint.setStyle(Paint.Style.STROKE);
            shapePaint.setStrokeWidth(AndroidUtilities.dp(1));
            shapePaint.setColor(Theme.getColor(Theme.key_divider));
            canvas.drawRoundRect(box, radius, radius, shapePaint);
            shapePaint.setStyle(Paint.Style.FILL);
        }
    }

    /** Маленькая скруглённая плашка со своей картинкой слоя. */
    private static class LayerChip extends View {
        private final Bitmap image;
        private final boolean accent;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Path path = new Path();

        LayerChip(Context context, Bitmap image, boolean accent) {
            super(context);
            this.image = image;
            this.accent = accent;
        }

        @Override protected void onDraw(Canvas canvas) {
            float radius = AndroidUtilities.dp(8);
            box.set(0, 0, getWidth(), getHeight());
            path.rewind();
            path.addRoundRect(box, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(path);
            shapePaint.setColor(Theme.getColor(accent ? Theme.key_windowBackgroundGray : Theme.key_windowBackgroundWhiteBlueText));
            canvas.drawRect(box, shapePaint);
            if (image != null) canvas.drawBitmap(image, null, box, bitmapPaint);
            canvas.restoreToCount(save);
        }
    }

    private void rebuild() {
        if (content == null || getContext() == null) return;
        content.removeAllViews();
        revealViews.clear();
        revealOrder.clear();
        int index = 0;
        SharedPreferences p = prefs(currentAccount);
        boolean enabled = p.getBoolean("enabled", false);

        LinearLayout hero = card(index++);
        LayersPreview preview = new LayersPreview(getContext(), draftBitmap(0), draftBitmap(1));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(58));
        previewParams.setMargins(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), 0);
        hero.addView(preview, previewParams);
        hero.addView(toggleRow(R.string.WgCustomization, enabled, () -> {
            p.edit().putBoolean("enabled", !enabled).apply();
            changed();
        }, index++));
        content.addView(hero);

        LinearLayout layersCard = card(index++);
        layersCard.addView(layerRow(0, index++));
        layersCard.addView(divider());
        layersCard.addView(layerRow(1, index++));
        content.addView(layersCard);

        content.addView(primaryButton(R.string.WgCustomSave, !draft[0].isEmpty() || !draft[1].isEmpty(), this::nameConfig, index++));

        java.util.TreeMap<String, ?> sorted = new java.util.TreeMap<>(p.getAll());
        String activeId = p.getString("active", "");
        LinearLayout configs = null;
        for (String key : sorted.keySet()) {
            if (!key.startsWith("config_")) continue;
            String id = key.substring(7);
            JSONObject config = object(p.getString(key, ""));
            if (configs == null) {
                content.addView(sectionHeader(R.string.WgCustomConfigs, 18, index++));
                configs = card(index++);
            } else {
                configs.addView(divider());
            }
            configs.addView(configRow(id, config, id.equals(activeId), index++));
        }
        if (configs != null) content.addView(configs);
        else content.addView(emptyState(index));

        content.post(this::runReveal);
    }

    private void changed() {
        refreshLayers(currentAccount);
        invalidateNavigation(currentAccount);
        rebuild();
    }

    private void pick(int layer) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK + layer);
    }

    @Override public void onActivityResultFragment(int request, int result, Intent data) {
        if ((request != PICK && request != PICK + 1) || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Context context = ApplicationLoader.applicationContext;
        Uri uri = data.getData();
        Utilities.globalQueue.postRunnable(() -> {
            String encoded = null;
            try { encoded = normalize(context, uri); } catch (Exception e) { FileLog.e(e); }
            final String image = encoded;
            AndroidUtilities.runOnUIThread(() -> {
                if (isFinished || getContext() == null) return;
                if (image == null) error();
                else { draft[request - PICK] = image; rebuild(); }
            });
        });
    }

    private static String normalize(Context context, Uri uri) throws Exception {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(stream, null, opts); }
        if (opts.outWidth < 1 || opts.outHeight < 1 || opts.outWidth > 100000 || opts.outHeight > 100000) return null;
        opts.inSampleSize = 1;
        while (opts.outWidth / opts.inSampleSize > 1280 || opts.outHeight / opts.inSampleSize > 512) opts.inSampleSize *= 2;
        opts.inJustDecodeBounds = false;
        Bitmap source;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) { source = BitmapFactory.decodeStream(stream, null, opts); }
        if (source == null) return null;
        float scale = Math.min(1f, Math.min(640f / source.getWidth(), 256f / source.getHeight()));
        while (scale > .01f) {
            Bitmap small = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)), Math.max(1, Math.round(source.getHeight() * scale)), true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            if (small != source) small.recycle();
            if (bytes.size() <= MAX_IMAGE) { source.recycle(); return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP); }
            scale *= .75f;
        }
        source.recycle();
        return null;
    }

    private void nameConfig() {
        if (draft[0].isEmpty() && draft[1].isEmpty()) { error(); return; }
        EditText name = new EditText(getContext());
        name.setSingleLine();
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(48)});
        name.setHint(text(R.string.WgCustomName));
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle(LocaleController.getString(R.string.WgCustomName))
                .setView(name).setNegativeButton(LocaleController.getString(R.string.Cancel), null).setPositiveButton(LocaleController.getString(R.string.WgCustomSave), null).create();
        showDialog(dialog);
        View saveButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (saveButton == null) return;
        saveButton.setOnClickListener(v -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) { name.setError(getString(R.string.WgCustomName)); return; }
            try {
                JSONObject config = new JSONObject().put("name", title).put("image0", draft[0]).put("image1", draft[1]);
                save(currentAccount, config);
                dialog.dismiss();
                changed();
            } catch (JSONException e) { error(); }
        });
    }

    private static void save(int account, JSONObject config) {
        String id = UUID.randomUUID().toString();
        prefs(account).edit().putString("config_" + id, config.toString()).putString("active", id).putBoolean("enabled", true).apply();
        refreshLayers(account);
        invalidateNavigation(account);
    }

    private void error() {
        if (getContext() != null && !isFinished) showDialog(new AlertDialog.Builder(getContext()).setMessage(LocaleController.getString(R.string.WgCustomError)).setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    private void share(JSONObject config) {
        if (busy) return;
        busy = true;
        int account = currentAccount;
        Utilities.globalQueue.postRunnable(() -> {
            String token = "";
            try { token = WaysGramApi.post(account, "/api/customizations", config).optString("token"); } catch (Exception e) { FileLog.e(e); }
            final String result = token;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (isFinished) return;
                if (!result.matches("[A-Za-z0-9_-]{32}")) error();
                else AndroidUtilities.addToClipboard("https://t.wg/custom/" + result);
            });
        });
    }

    public static boolean isLink(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && "t.wg".equalsIgnoreCase(uri.getHost())
                && uri.getPath() != null && uri.getPath().matches("/custom/[A-Za-z0-9_-]{32}");
    }

    public static void open(LaunchActivity activity, int account, Uri uri) {
        final long userId = UserConfig.getInstance(account).getClientUserId();
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject config = null;
            try { config = WaysGramApi.get(account, "/api/customizations/" + uri.getLastPathSegment()); } catch (Exception e) { FileLog.e(e); }
            final JSONObject loaded = config;
            AndroidUtilities.runOnUIThread(() -> {
                if (activity.isFinishing() || UserConfig.selectedAccount != account || UserConfig.getInstance(account).getClientUserId() != userId) return;
                if (loaded == null || (bitmap(loaded.optString("image0")) == null && bitmap(loaded.optString("image1")) == null)) {
                    new AlertDialog.Builder(activity).setMessage(LocaleController.getString(R.string.WgCustomError)).setPositiveButton(LocaleController.getString(R.string.OK), null).show();
                    return;
                }
                LinearLayout preview = new LinearLayout(activity);
                preview.setOrientation(LinearLayout.VERTICAL);
                preview.setGravity(Gravity.CENTER_HORIZONTAL);
                for (int layer = 0; layer < 2; layer++) {
                    Bitmap image = bitmap(loaded.optString("image" + layer));
                    if (image == null) continue;
                    ImageView thumbnail = new ImageView(activity);
                    thumbnail.setImageBitmap(image);
                    thumbnail.setScaleType(ImageView.ScaleType.FIT_XY);
                    thumbnail.setContentDescription(LocaleController.getString(layer == 0 ? R.string.WgCustomBackground : R.string.WgCustomIndicator));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(AndroidUtilities.dp(layer == 0 ? 180 : 72), AndroidUtilities.dp(44));
                    lp.bottomMargin = AndroidUtilities.dp(8);
                    preview.addView(thumbnail, lp);
                }
                new AlertDialog.Builder(activity).setTitle(loaded.optString("name")).setView(preview)
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null).setPositiveButton(LocaleController.getString(R.string.WgCustomApply), (d, w) -> {
                            if (UserConfig.selectedAccount == account && UserConfig.getInstance(account).getClientUserId() == userId) { save(account, loaded); activity.getWindow().getDecorView().invalidate(); }
                        }).show();
            });
        });
    }
}
