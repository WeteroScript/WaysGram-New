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
        String id = p.getBoolean("enabled", false) ? p.getString("active", "") : "";
        String key = UserConfig.getInstance(account).getClientUserId() + ":" + id;
        if (renderedAccount == account && key.equals(renderedKey)) return;
        renderedAccount = account;
        renderedKey = key;
        JSONObject config = object(p.getString("config_" + id, ""));
        for (int i = 0; i < 2; i++) layers[i] = bitmap(config.optString("image" + i));
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

    @Override public View createView(Context context) {
        actionBar.setTitle(getString(R.string.WgCustomization));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
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

    private void toggle(LinearLayout parent, String label, boolean value, Runnable action) {
        TextCheckCell cell = new TextCheckCell(getContext());
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        cell.setTextAndCheck(label, value, false);
        cell.setOnClickListener(v -> action.run());
        parent.addView(cell);
    }

    private Button button(String text, Runnable click) {
        Button b = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void rebuild() {
        if (content == null || getContext() == null) return;
        content.removeAllViews();
        SharedPreferences p = prefs(currentAccount);
        boolean enabled = p.getBoolean("enabled", false);
        toggle(content, getString(R.string.WgCustomization), enabled, () -> {
            p.edit().putBoolean("enabled", !enabled).apply();
            changed();
        });
        for (int i = 0; i < 2; i++) {
            final int layer = i;
            TextView label = new TextView(getContext());
            label.setText(text(i == 0 ? R.string.WgCustomBackground : R.string.WgCustomIndicator));
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            label.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), 0, 0);
            LinearLayout section = new LinearLayout(getContext());
            section.setOrientation(LinearLayout.VERTICAL);
            section.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(-1, -2);
            sectionParams.topMargin = AndroidUtilities.dp(8);
            content.addView(section, sectionParams);
            section.addView(label);
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView preview = new ImageView(getContext());
            preview.setContentDescription(label.getText());
            preview.setScaleType(ImageView.ScaleType.FIT_XY);
            Bitmap image = bitmap(draft[i]);
            if (image == null) preview.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(22), Theme.getColor(i == 0 ? Theme.key_windowBackgroundGray : Theme.key_windowBackgroundWhiteBlueText)));
            else preview.setImageBitmap(image);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(AndroidUtilities.dp(i == 0 ? 155 : 70), AndroidUtilities.dp(44));
            lp.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            row.addView(preview, lp);
            row.addView(button(text(R.string.WgCustomUpload), () -> pick(layer)));
            section.addView(row);
        }
        content.addView(button(text(R.string.WgCustomSave), this::nameConfig));
        java.util.TreeMap<String, ?> sorted = new java.util.TreeMap<>(p.getAll());
        for (String key : sorted.keySet()) {
            if (!key.startsWith("config_")) continue;
            String id = key.substring(7);
            JSONObject config = object(p.getString(key, ""));
            boolean active = id.equals(p.getString("active", ""));
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            TextView title = new TextView(getContext());
            title.setText(config.optString("name"));
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setTextSize(16);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPaddingRelative(AndroidUtilities.dp(16), 0, 0, 0);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton copy = new ImageButton(getContext());
            copy.setImageResource(R.drawable.msg_copy);
            copy.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            copy.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            copy.setContentDescription(getString(R.string.CopyLink));
            copy.setOnClickListener(v -> share(config));
            row.addView(copy, new LinearLayout.LayoutParams(AndroidUtilities.dp(48), AndroidUtilities.dp(48)));
            org.telegram.ui.Components.Switch select = new org.telegram.ui.Components.Switch(getContext());
            select.setChecked(active, false);
            select.setContentDescription(config.optString("name"));
            select.setOnClickListener(v -> {
                p.edit().putString("active", active ? "" : id).apply();
                changed();
            });
            row.addView(select, new LinearLayout.LayoutParams(AndroidUtilities.dp(56), AndroidUtilities.dp(48)));
            content.addView(row);
        }
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
