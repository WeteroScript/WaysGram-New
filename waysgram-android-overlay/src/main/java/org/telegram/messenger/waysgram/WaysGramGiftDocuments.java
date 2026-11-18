package org.telegram.messenger.waysgram;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Builds Telegram sticker documents for WaysGram gift artwork.
 *
 * <p>Gift artwork is stored once as {@code <documentId>.bin} in the shared
 * WaysGram gift sticker folder. The same file is served to Telegram's own
 * media stack through the WaysGram transport, so gift cells and gift message
 * cards can play animated (TGS/WebM) artwork natively instead of showing only
 * a still frame.</p>
 */
public final class WaysGramGiftDocuments {
    public static final String MIME_LOTTIE = "application/x-tgsticker";
    public static final String MIME_WEBM = "video/webm";
    public static final String MIME_WEBP = "image/webp";
    public static final String MIME_PNG = "image/png";
    public static final String MIME_JPEG = "image/jpeg";

    private static final int DEFAULT_SIZE = 512;

    private WaysGramGiftDocuments() {
    }

    /** Folder shared with the WaysGram transport's {@code upload.getFile} handler. */
    public static File stickerFolder() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        File folder = new File(context.getFilesDir(), "waysgram_gifts");
        if (!folder.exists() && !folder.mkdirs()) {
            return null;
        }
        return folder;
    }

    /** Stable document id used both for the on-disk file name and for Telegram. */
    public static long documentId(String telegramGiftId) {
        String value = telegramGiftId == null ? "" : telegramGiftId.trim();
        if (value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return Integer.toUnsignedLong(value.hashCode());
        }
    }

    public static File stickerFile(long documentId) {
        File folder = stickerFolder();
        return folder == null ? null : new File(folder, documentId + ".bin");
    }

    /**
     * Writes raw sticker bytes to the shared sticker folder.
     *
     * @return the written file, or {@code null} when the bytes are unusable.
     */
    public static File storeSticker(long documentId, byte[] bytes) {
        if (documentId == 0L || bytes == null || bytes.length < 16) {
            return null;
        }
        String mimeType = sniffMime(bytes);
        if (mimeType == null) {
            return null;
        }
        File target = stickerFile(documentId);
        if (target == null) {
            return null;
        }
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.flush();
        } catch (Exception exception) {
            FileLog.e(exception);
            temporary.delete();
            return null;
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            return null;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            return null;
        }
        return target;
    }

    /** Builds the sticker document Telegram's media stack expects for gift artwork. */
    public static TLRPC.TL_document buildDocument(long documentId, File file, String emoji) {
        if (documentId == 0L || file == null || !file.isFile() || file.length() == 0) {
            return null;
        }
        byte[] head = readHead(file, 32);
        String mimeType = sniffMime(head);
        if (mimeType == null) {
            return null;
        }
        boolean lottie = MIME_LOTTIE.equals(mimeType);
        boolean webm = MIME_WEBM.equals(mimeType);
        int size = (int) Math.min(Integer.MAX_VALUE, file.length());

        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = documentId;
        document.access_hash = documentId;
        document.file_reference = new byte[0];
        document.date = (int) (System.currentTimeMillis() / 1000L);
        document.mime_type = mimeType;
        document.size = size;
        document.dc_id = 2;

        TLRPC.TL_documentAttributeImageSize imageSize = new TLRPC.TL_documentAttributeImageSize();
        imageSize.w = DEFAULT_SIZE;
        imageSize.h = DEFAULT_SIZE;
        document.attributes.add(imageSize);

        TLRPC.TL_documentAttributeSticker sticker = new TLRPC.TL_documentAttributeSticker();
        sticker.alt = emoji == null ? "" : emoji;
        sticker.stickerset = new TLRPC.TL_inputStickerSetEmpty();
        document.attributes.add(sticker);

        if (webm) {
            TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
            video.w = DEFAULT_SIZE;
            video.h = DEFAULT_SIZE;
            video.duration = 1.0;
            video.nosound = true;
            video.supports_streaming = true;
            document.attributes.add(video);
        }
        if (lottie || webm) {
            document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        }
        // No preview sizes on purpose: the artwork file itself is served by the
        // WaysGram transport, so Telegram decodes it directly and animates it.
        return document;
    }

    /** Returns the artwork mime type from the file signature, or {@code null}. */
    public static String sniffMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        if ((bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return MIME_PNG;
        }
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return MIME_JPEG;
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return MIME_WEBP;
        }
        if ((bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            return MIME_LOTTIE;
        }
        if ((bytes[0] & 0xff) == 0x1a && (bytes[1] & 0xff) == 0x45
                && (bytes[2] & 0xff) == 0xdf && (bytes[3] & 0xff) == 0xa3) {
            return MIME_WEBM;
        }
        return null;
    }

    public static boolean isAnimatedMime(String mimeType) {
        return MIME_LOTTIE.equals(mimeType) || MIME_WEBM.equals(mimeType);
    }

    private static byte[] readHead(File file, int count) {
        byte[] buffer = new byte[count];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(buffer);
            if (read <= 0) {
                return null;
            }
            if (read == buffer.length) {
                return buffer;
            }
            byte[] shorter = new byte[read];
            System.arraycopy(buffer, 0, shorter, 0, read);
            return shorter;
        } catch (Exception exception) {
            FileLog.e(exception);
            return null;
        }
    }
}
