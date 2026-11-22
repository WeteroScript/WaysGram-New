package org.telegram.messenger.waysgram;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Lightweight WaysGram photo editor; the selected source file remains untouched. */
public final class WaysGramPhotoEditor {
    public interface Callback {
        void onEdited(Uri editedImage);
        void onError(String message);
    }

    private static final int MAX_DECODE_EDGE = 2048;
    private static final int[] BRUSH_COLORS = {
            Color.RED, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.WHITE
    };

    private WaysGramPhotoEditor() { }

    public static void show(Context context, ContentResolver resolver, Uri source, Callback callback) {
        if (context == null || resolver == null || source == null || callback == null) {
            if (callback != null) callback.onError("Не удалось открыть фото для редактирования.");
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            Bitmap bitmap;
            try {
                bitmap = decode(resolver, source);
            } catch (Exception exception) {
                main.post(() -> callback.onError("Не удалось открыть изображение: " + safeMessage(exception)));
                return;
            }
            main.post(() -> showEditor(context, bitmap, callback));
        }, "WaysGramPhotoEditorDecode");
        worker.setDaemon(true);
        worker.start();
    }

    private static Bitmap decode(ContentResolver resolver, Uri source) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) throw new IllegalStateException("Файл изображения недоступен");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("Файл не является поддерживаемым изображением");
        }
        int sample = 1;
        while (bounds.outWidth / sample > MAX_DECODE_EDGE
                || bounds.outHeight / sample > MAX_DECODE_EDGE) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) throw new IllegalStateException("Файл изображения недоступен");
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) throw new IllegalArgumentException("Не удалось декодировать изображение");
            return bitmap;
        }
    }

    private static void showEditor(Context context, Bitmap bitmap, Callback callback) {
        EditorView editor = new EditorView(context, bitmap);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 8);
        root.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(context);
        hint.setText("Поверните фото, добавьте текст или стикер либо включите рисование.");
        hint.setTextSize(13);
        root.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        ScrollView previewScroll = new ScrollView(context);
        previewScroll.setFillViewport(true);
        previewScroll.addView(editor, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 300)
        ));
        root.addView(previewScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 300)
        ));

        LinearLayout tools = new LinearLayout(context);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(0, dp(context, 6), 0, 0);
        Button rotate = toolButton(context, "Повернуть");
        Button draw = toolButton(context, "Рисовать");
        Button color = toolButton(context, "Цвет");
        Button addText = toolButton(context, "Текст");
        Button sticker = toolButton(context, "Стикер");
        tools.addView(rotate);
        tools.addView(draw);
        tools.addView(color);
        tools.addView(addText);
        tools.addView(sticker);
        root.addView(tools);

        rotate.setOnClickListener(view -> editor.rotateClockwise());
        draw.setOnClickListener(view -> {
            boolean enabled = !editor.isDrawingEnabled();
            editor.setDrawingEnabled(enabled);
            draw.setText(enabled ? "Готово" : "Рисовать");
        });
        color.setOnClickListener(view -> color.setText(brushName(editor.cycleBrushColor())));
        addText.setOnClickListener(view -> showTextEntry(context, editor));
        sticker.setOnClickListener(view -> showStickerPicker(context, editor));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Редактирование фото")
                .setView(root)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить и отправить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        Uri result = saveEditedImage(context, editor.render());
                        dialog.dismiss();
                        callback.onEdited(result);
                    } catch (Exception exception) {
                        callback.onError("Не удалось сохранить изменённое фото: " + safeMessage(exception));
                    }
                }));
        dialog.show();
    }

    private static void showTextEntry(Context context, EditorView editor) {
        EditText input = new EditText(context);
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setHint("Текст на фотографии");
        new AlertDialog.Builder(context)
                .setTitle("Добавить текст")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!value.isEmpty()) editor.addLabel(value, false);
                })
                .show();
    }

    private static void showStickerPicker(Context context, EditorView editor) {
        String[] stickers = {"❤️", "⭐", "🔥", "😂", "🎉", "👍", "🌈", "🎁"};
        new AlertDialog.Builder(context)
                .setTitle("Выберите стикер")
                .setItems(stickers, (dialog, which) -> editor.addLabel(stickers[which], true))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static Uri saveEditedImage(Context context, Bitmap bitmap) throws Exception {
        File directory = new File(context.getCacheDir(), "waysgram_edited");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Не удалось создать временную папку");
        }
        File file = new File(directory,
                "photo_" + UUID.randomUUID().toString().replace("-", "") + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("Не удалось записать изображение");
            }
            output.flush();
        } finally {
            bitmap.recycle();
        }
        return Uri.fromFile(file);
    }

    private static Button toolButton(Context context, String text) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(11);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1
        );
        params.rightMargin = dp(context, 2);
        button.setLayoutParams(params);
        return button;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String brushName(int color) {
        if (color == Color.RED) return "Красный";
        if (color == Color.YELLOW) return "Жёлтый";
        if (color == Color.CYAN) return "Голубой";
        if (color == Color.GREEN) return "Зелёный";
        if (color == Color.MAGENTA) return "Розовый";
        return "Белый";
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "ошибка чтения файла" : message;
    }

    private static final class Stroke {
        final Path path;
        final Paint paint;
        Stroke(Path path, Paint paint) { this.path = path; this.paint = paint; }
    }

    private static final class Label {
        final String text;
        final float x;
        final float y;
        final boolean sticker;
        Label(String text, float x, float y, boolean sticker) {
            this.text = text; this.x = x; this.y = y; this.sticker = sticker;
        }
    }

    private static final class EditorView extends View {
        private Bitmap bitmap;
        private final List<Stroke> strokes = new ArrayList<>();
        private final List<Label> labels = new ArrayList<>();
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Path activePath;
        private boolean drawingEnabled;
        private int brushColor = Color.RED;
        private float scale = 1f;
        private float offsetX;
        private float offsetY;

        EditorView(Context context, Bitmap bitmap) {
            super(context);
            this.bitmap = bitmap;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            activeStrokePaint.setColor(brushColor);
            activeStrokePaint.setStyle(Paint.Style.STROKE);
            activeStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            activeStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        }

        boolean isDrawingEnabled() { return drawingEnabled; }

        void setDrawingEnabled(boolean enabled) {
            drawingEnabled = enabled;
            invalidate();
        }

        int cycleBrushColor() {
            for (int i = 0; i < BRUSH_COLORS.length; i++) {
                if (BRUSH_COLORS[i] == brushColor) {
                    brushColor = BRUSH_COLORS[(i + 1) % BRUSH_COLORS.length];
                    invalidate();
                    return brushColor;
                }
            }
            brushColor = BRUSH_COLORS[0];
            return brushColor;
        }

        void rotateClockwise() {
            if (bitmap == null) return;
            Matrix matrix = new Matrix();
            matrix.setRotate(90f);
            RectF bounds = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
            matrix.mapRect(bounds);
            matrix.postTranslate(-bounds.left, -bounds.top);
            Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
            );
            for (Stroke stroke : strokes) stroke.path.transform(matrix);
            for (int i = 0; i < labels.size(); i++) {
                Label label = labels.get(i);
                float[] point = {label.x, label.y};
                matrix.mapPoints(point);
                labels.set(i, new Label(label.text, point[0], point[1], label.sticker));
            }
            bitmap.recycle();
            bitmap = rotated;
            invalidate();
        }

        void addLabel(String text, boolean sticker) {
            if (bitmap == null || text == null || text.trim().isEmpty()) return;
            labels.add(new Label(text.trim(), bitmap.getWidth() / 2f, bitmap.getHeight() / 2f, sticker));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) return;
            scale = Math.min(getWidth() / (float) bitmap.getWidth(),
                    getHeight() / (float) bitmap.getHeight());
            if (scale <= 0) return;
            offsetX = (getWidth() - bitmap.getWidth() * scale) / 2f;
            offsetY = (getHeight() - bitmap.getHeight() * scale) / 2f;
            canvas.save();
            canvas.translate(offsetX, offsetY);
            canvas.scale(scale, scale);
            canvas.drawBitmap(bitmap, 0, 0, bitmapPaint);
            for (Stroke stroke : strokes) canvas.drawPath(stroke.path, stroke.paint);
            if (activePath != null) canvas.drawPath(activePath, activeStrokePaint);
            float textSize = Math.max(26f, bitmap.getWidth() * 0.055f);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            labelPaint.setColor(Color.WHITE);
            labelPaint.setShadowLayer(Math.max(2f, textSize * 0.08f), 1f, 2f, Color.BLACK);
            for (Label label : labels) {
                labelPaint.setTextSize(label.sticker ? textSize * 1.7f : textSize);
                canvas.drawText(label.text, label.x, label.y, labelPaint);
            }
            canvas.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!drawingEnabled || bitmap == null || scale <= 0) return false;
            float x = (event.getX() - offsetX) / scale;
            float y = (event.getY() - offsetY) / scale;
            if (x < 0 || y < 0 || x > bitmap.getWidth() || y > bitmap.getHeight()) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    activePath = new Path();
                    activePath.moveTo(x, y);
                    activeStrokePaint.setColor(brushColor);
                    activeStrokePaint.setStrokeWidth(Math.max(8f, bitmap.getWidth() * 0.006f));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (activePath != null) { activePath.lineTo(x, y); invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (activePath != null) {
                        strokes.add(new Stroke(activePath, new Paint(activeStrokePaint)));
                        activePath = null;
                        invalidate();
                    }
                    return true;
                default:
                    return true;
            }
        }

        Bitmap render() {
            if (bitmap == null) throw new IllegalStateException("Изображение уже освобождено");
            Bitmap output = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            if (output == null) throw new IllegalStateException("Не удалось подготовить изображение");
            Canvas canvas = new Canvas(output);
            for (Stroke stroke : strokes) canvas.drawPath(stroke.path, stroke.paint);
            float textSize = Math.max(26f, bitmap.getWidth() * 0.055f);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            labelPaint.setColor(Color.WHITE);
            labelPaint.setShadowLayer(Math.max(2f, textSize * 0.08f), 1f, 2f, Color.BLACK);
            for (Label label : labels) {
                labelPaint.setTextSize(label.sticker ? textSize * 1.7f : textSize);
                canvas.drawText(label.text, label.x, label.y, labelPaint);
            }
            return output;
        }
    }
}
