package org.telegram.tgnet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/** Original WaysGram vector artwork rendered by the native animated sticker player. */
public final class WaysGramAnimatedEmoji {
    private static final long FIRST_ID = 0x6A52000000000000L;
    private static final String[] NAMES = {"heart", "fire", "star"};
    private static final String[] EMOJI = {"❤", "🔥", "⭐"};
    private static final TLRPC.Document[] DOCUMENTS = new TLRPC.Document[NAMES.length];

    public static synchronized TLRPC.Document find(String emoji) {
        for (int i = 0; i < EMOJI.length; i++) {
            if (!EMOJI[i].equals(emoji)) continue;
            if (DOCUMENTS[i] != null) return DOCUMENTS[i];
            try {
                File file = assetFile(i);
                TLRPC.TL_document doc = new TLRPC.TL_document();
                doc.id = doc.access_hash = FIRST_ID + i;
                doc.dc_id = 2;
                doc.file_reference = new byte[0];
                doc.mime_type = "application/x-tgsticker";
                doc.size = file.length();
                TLRPC.TL_documentAttributeImageSize dimensions = new TLRPC.TL_documentAttributeImageSize();
                dimensions.w = dimensions.h = 512;
                doc.attributes.add(dimensions);
                TLRPC.TL_documentAttributeCustomEmoji attribute = new TLRPC.TL_documentAttributeCustomEmoji();
                attribute.alt = EMOJI[i];
                attribute.free = true;
                attribute.stickerset = new TLRPC.TL_inputStickerSetEmpty();
                doc.attributes.add(attribute);
                DOCUMENTS[i] = doc;
                return doc;
            } catch (IOException e) {
                FileLog.e(e);
                return null;
            }
        }
        return null;
    }

    private static synchronized File assetFile(int index) throws IOException {
        String name = NAMES[index] + ".tgs";
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "waysgram_emoji_v1");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Emoji cache unavailable");
        File file = new File(dir, name);
        if (!file.isFile()) {
            File staging = File.createTempFile("emoji", ".tmp", dir);
            try {
                try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("waysgram_emoji/" + name);
                     FileOutputStream out = new FileOutputStream(staging)) {
                    byte[] bytes = new byte[4096];
                    int n;
                    while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
                }
                if (!staging.renameTo(file)) throw new IOException("Emoji cache write failed");
            } finally {
                staging.delete();
            }
        }
        return file;
    }

    static TLObject getFile(TLRPC.TL_upload_getFile request) throws WaysGramApi.ApiException {
        if (!(request.location instanceof TLRPC.TL_inputDocumentFileLocation)) return null;
        long index = request.location.id - FIRST_ID;
        if (index < 0 || index >= NAMES.length) return null;
        try {
            return WaysGramGifts.serveFile(assetFile((int) index), request);
        } catch (IOException e) {
            throw new WaysGramApi.ApiException(500, "FILE_ERROR");
        }
    }
}
