package org.telegram.messenger.waysgram;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Local MP4 trim editor. Audio and video samples are copied into a new MP4
 * container without re-encoding; the source file remains unchanged.
 */
public final class WaysGramVideoEditor {
    public interface Callback {
        void onEdited(Uri editedVideo);
        void onError(String message);
    }

    private static final long MIN_CLIP_MS = 1000L;
    private static final long MAX_OUTPUT_BYTES = 25L * 1024L * 1024L;
    private static final int DEFAULT_BUFFER_BYTES = 8 * 1024 * 1024;
    private static final int MAX_BUFFER_BYTES = 16 * 1024 * 1024;

    private WaysGramVideoEditor() {
    }

    public static void show(Context context, ContentResolver resolver, Uri source, Callback callback) {
        if (context == null || resolver == null || source == null || callback == null) {
            if (callback != null) callback.onError("Не удалось открыть видео для редактирования.");
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            long duration;
            try {
                duration = readDuration(context, source);
            } catch (Exception exception) {
                main.post(() -> callback.onError("Не удалось прочитать длительность видео: "
                        + safeMessage(exception)));
                return;
            }
            main.post(() -> showTrimDialog(context, resolver, source, duration, callback));
        }, "WaysGramVideoMetadata");
        worker.setDaemon(true);
        worker.start();
    }

    private static long readDuration(Context context, Uri source) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, source);
            String raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration = raw == null ? 0 : Long.parseLong(raw);
            if (duration < MIN_CLIP_MS) {
                throw new IllegalArgumentException("Видео короче одной секунды");
            }
            return duration;
        } finally {
            retriever.release();
        }
    }

    private static void showTrimDialog(
            Context context,
            ContentResolver resolver,
            Uri source,
            long durationMs,
            Callback callback
    ) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 14);
        root.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(context);
        hint.setText("Укажите начало и конец фрагмента. Исходное видео не изменяется; дорожки копируются без перекодирования.");
        hint.setTextSize(13);
        root.addView(hint, matchWrap());

        TextView durationLabel = new TextView(context);
        durationLabel.setText("Длительность исходного видео: " + formatTime(durationMs));
        durationLabel.setTextSize(12);
        durationLabel.setPadding(0, dp(context, 8), 0, dp(context, 8));
        root.addView(durationLabel, matchWrap());

        TextView startLabel = new TextView(context);
        startLabel.setText("Начало: 00:00.0");
        root.addView(startLabel, matchWrap());
        SeekBar startBar = new SeekBar(context);
        startBar.setMax(1000);
        startBar.setProgress(0);
        root.addView(startBar, matchWrap());

        TextView endLabel = new TextView(context);
        endLabel.setText("Конец: " + formatTime(durationMs));
        endLabel.setPadding(0, dp(context, 8), 0, 0);
        root.addView(endLabel, matchWrap());
        SeekBar endBar = new SeekBar(context);
        endBar.setMax(1000);
        endBar.setProgress(1000);
        root.addView(endBar, matchWrap());

        TextView selectedLabel = new TextView(context);
        selectedLabel.setTextSize(13);
        selectedLabel.setTextColor(Color.DKGRAY);
        selectedLabel.setPadding(0, dp(context, 8), 0, 0);
        root.addView(selectedLabel, matchWrap());

        final int minimumGap = (int) Math.max(1L,
                Math.min(1000L, (MIN_CLIP_MS * 1000L + durationMs - 1L) / durationMs));
        Runnable refresh = () -> {
            long start = progressToMs(startBar.getProgress(), durationMs);
            long end = progressToMs(endBar.getProgress(), durationMs);
            startLabel.setText("Начало: " + formatTime(start));
            endLabel.setText("Конец: " + formatTime(end));
            selectedLabel.setText("Фрагмент: " + formatTime(Math.max(0, end - start))
                    + " · без потери качества при поддерживаемых кодеках");
        };
        startBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress > endBar.getProgress() - minimumGap) {
                    seekBar.setProgress(Math.max(0, endBar.getProgress() - minimumGap));
                }
                refresh.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        endBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < startBar.getProgress() + minimumGap) {
                    seekBar.setProgress(Math.min(1000, startBar.getProgress() + minimumGap));
                }
                refresh.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        refresh.run();

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Обрезать видео")
                .setView(root)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Обрезать и отправить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    long start = progressToMs(startBar.getProgress(), durationMs);
                    long end = progressToMs(endBar.getProgress(), durationMs);
                    if (end - start < MIN_CLIP_MS) {
                        selectedLabel.setText("Выберите фрагмент длиной не менее одной секунды.");
                        return;
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                    selectedLabel.setText("Подготавливаю новый MP4…");
                    Thread worker = new Thread(() -> {
                        File output = null;
                        try {
                            output = trim(context, resolver, source, start * 1000L, end * 1000L);
                            Uri result = Uri.fromFile(output);
                            mainHandler().post(() -> {
                                dialog.dismiss();
                                callback.onEdited(result);
                            });
                        } catch (Exception exception) {
                            if (output != null) output.delete();
                            String error = "Не удалось обрезать видео: " + safeMessage(exception);
                            mainHandler().post(() -> {
                                if (!dialog.isShowing()) return;
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                                selectedLabel.setText(error);
                            });
                        }
                    }, "WaysGramVideoTrim");
                    worker.setDaemon(true);
                    worker.start();
                }));
        dialog.show();
    }

    private static File trim(
            Context context,
            ContentResolver resolver,
            Uri source,
            long startUs,
            long endUs
    ) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        File output = new File(
                new File(context.getCacheDir(), "waysgram_video_edits"),
                "trim_" + UUID.randomUUID().toString().replace("-", "") + ".mp4"
        );
        File directory = output.getParentFile();
        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
            throw new IllegalStateException("Не удалось создать временное хранилище видео");
        }

        Map<Integer, Integer> trackMap = new HashMap<>();
        Map<Integer, String> trackMime = new HashMap<>();
        try {
            extractor.setDataSource(context, source, null);
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrackCount = 0;
            int audioTrackCount = 0;
            int bufferBytes = DEFAULT_BUFFER_BYTES;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.containsKey(MediaFormat.KEY_MIME)
                        ? format.getString(MediaFormat.KEY_MIME) : null;
                if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    continue;
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    int max = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    if (max > 0) bufferBytes = Math.max(bufferBytes, Math.min(MAX_BUFFER_BYTES, max));
                }
                int muxerTrack = muxer.addTrack(format);
                trackMap.put(i, muxerTrack);
                trackMime.put(i, mime);
                if (mime.startsWith("video/")) videoTrackCount++;
                else audioTrackCount++;
                extractor.selectTrack(i);
            }

            if (videoTrackCount == 0 || trackMap.isEmpty()) {
                throw new IllegalArgumentException("В файле нет поддерживаемой видеодорожки");
            }
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(context, source);
                String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rotation != null) {
                    int degrees = Integer.parseInt(rotation);
                    if (degrees == 90 || degrees == 180 || degrees == 270) muxer.setOrientationHint(degrees);
                }
                retriever.release();
            } catch (Exception ignored) {
                // Rotation metadata is optional; the media tracks remain valid without it.
            }

            muxer.start();
            muxerStarted = true;
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_NEXT_SYNC);
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferBytes);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long baseTimeUs = -1L;
            boolean videoStarted = false;
            int writtenSamples = 0;

            while (true) {
                int sourceTrack = extractor.getSampleTrackIndex();
                if (sourceTrack < 0) break;
                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs < 0 || sampleTimeUs > endUs) break;
                if (!trackMap.containsKey(sourceTrack)) {
                    if (!extractor.advance()) break;
                    continue;
                }
                String mime = trackMime.get(sourceTrack);
                boolean isVideo = mime != null && mime.startsWith("video/");
                int sampleFlags = extractor.getSampleFlags();
                if (sampleTimeUs < startUs) {
                    if (!extractor.advance()) break;
                    continue;
                }
                if (isVideo && !videoStarted) {
                    if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                        if (!extractor.advance()) break;
                        continue;
                    }
                    videoStarted = true;
                }
                if (!isVideo && videoTrackCount > 0 && !videoStarted) {
                    if (!extractor.advance()) break;
                    continue;
                }

                buffer.clear();
                int sampleSize;
                try {
                    sampleSize = extractor.readSampleData(buffer, 0);
                } catch (IllegalStateException exception) {
                    throw new IllegalStateException("Не удалось прочитать видеокадр; попробуйте другой файл", exception);
                }
                if (sampleSize < 0) break;
                if (baseTimeUs < 0) baseTimeUs = sampleTimeUs;
                info.offset = 0;
                info.size = sampleSize;
                info.presentationTimeUs = Math.max(0, sampleTimeUs - baseTimeUs);
                info.flags = sampleFlags;
                muxer.writeSampleData(trackMap.get(sourceTrack), buffer, info);
                writtenSamples++;
                if (!extractor.advance()) break;
            }

            if (!videoStarted || writtenSamples == 0) {
                throw new IllegalArgumentException("Не найден подходящий видеокадр в выбранном диапазоне");
            }
            muxer.stop();
            muxerStarted = false;
            muxer.release();
            muxer = null;
            if (!output.isFile() || output.length() < 1024) {
                throw new IllegalStateException("Не удалось создать обрезанное видео");
            }
            if (output.length() > MAX_OUTPUT_BYTES) {
                throw new IllegalStateException("Обрезанное видео превышает лимит отправки 25 МБ");
            }
            return output;
        } catch (Exception exception) {
            output.delete();
            throw exception;
        } finally {
            extractor.release();
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                try { muxer.release(); } catch (Exception ignored) { }
            }
        }
    }

    private static long progressToMs(int progress, long durationMs) {
        return durationMs * Math.max(0, Math.min(1000, progress)) / 1000L;
    }

    private static String formatTime(long milliseconds) {
        long totalTenths = Math.max(0L, milliseconds) / 100L;
        long minutes = totalTenths / 600L;
        long seconds = (totalTenths / 10L) % 60L;
        long tenths = totalTenths % 10L;
        return String.format(Locale.ROOT, "%02d:%02d.%d", minutes, seconds, tenths);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "ошибка обработки видео" : message;
    }
}
