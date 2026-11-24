package org.telegram.tgnet;

import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Original Telegram emoji artwork for reaction chips, extracted from the official
 * EPK3 emoji pack shipped with Telegram. These are static WebP textures (no hand-drawn
 * WaysGram art): the picker and message bubbles draw the same emoji the stock client does.
 */
public final class WaysGramReactionTextures {
    private static final long DOCUMENT_ID = 0x6A55000000000000L;
    private static final String ASSET_DIR = "telegram_reactions/";
    private static final String[] FILES = {
        "1f44d.webp", "2764.webp", "1f525.webp", "1f970.webp", "1f44f.webp", "1f601.webp", "1f914.webp", "1f92f.webp", "1f631.webp", "1f389.webp", "1f929.webp", "1f60d.webp", "1f618.webp", "1f602.webp", "1f622.webp", "1f64f.webp", "1f44c.webp", "1f54a.webp", "1f91d.webp", "1f494.webp", "1f971.webp", "1f974.webp", "1f634.webp", "1f62d.webp", "1f44e.webp", "1f4af.webp", "1f923.webp", "1f60e.webp", "1f921.webp", "1f984.webp", "1f37e.webp", "1f48a.webp", "1f32d.webp", "1f353.webp", "1f383.webp", "1f38a.webp", "1f3c6.webp", "26a1.webp", "2728.webp", "1f4a9.webp", "2764-fe0f-200d-1f525.webp", "1f62e.webp", "1f92c.webp", "1f621.webp", "1f92e.webp", "1f440.webp", "1f64c.webp", "1f973.webp", "1f917.webp", "1fae1.webp", "1f384.webp"
    };
    private static final HashMap<String, Integer> INDEX = new HashMap<>();
    static {
        INDEX.put("👍", 0);
        INDEX.put("❤️", 1);
        INDEX.put("🔥", 2);
        INDEX.put("🥰", 3);
        INDEX.put("👏", 4);
        INDEX.put("😁", 5);
        INDEX.put("🤔", 6);
        INDEX.put("🤯", 7);
        INDEX.put("😱", 8);
        INDEX.put("🎉", 9);
        INDEX.put("🤩", 10);
        INDEX.put("😍", 11);
        INDEX.put("😘", 12);
        INDEX.put("😂", 13);
        INDEX.put("😢", 14);
        INDEX.put("🙏", 15);
        INDEX.put("👌", 16);
        INDEX.put("🕊️", 17);
        INDEX.put("🤝", 18);
        INDEX.put("💔", 19);
        INDEX.put("🥱", 20);
        INDEX.put("🥴", 21);
        INDEX.put("😴", 22);
        INDEX.put("😭", 23);
        INDEX.put("👎", 24);
        INDEX.put("💯", 25);
        INDEX.put("🤣", 26);
        INDEX.put("😎", 27);
        INDEX.put("🤡", 28);
        INDEX.put("🦄", 29);
        INDEX.put("🍾", 30);
        INDEX.put("💊", 31);
        INDEX.put("🌭", 32);
        INDEX.put("🍓", 33);
        INDEX.put("🎃", 34);
        INDEX.put("🎊", 35);
        INDEX.put("🏆", 36);
        INDEX.put("⚡", 37);
        INDEX.put("✨", 38);
        INDEX.put("💩", 39);
        INDEX.put("❤️‍🔥", 40);
        INDEX.put("😮", 41);
        INDEX.put("🤬", 42);
        INDEX.put("😡", 43);
        INDEX.put("🤮", 44);
        INDEX.put("👀", 45);
        INDEX.put("🙌", 46);
        INDEX.put("🥳", 47);
        INDEX.put("🤗", 48);
        INDEX.put("🫡", 49);
        INDEX.put("🎄", 50);
    }

    private WaysGramReactionTextures() {}

    private static String key(String emoji) {
        return WaysGramReactions.normalize(emoji);
    }

    /** Static document for the original Telegram texture; null when unknown. */
    public static synchronized TLRPC.Document get(String emoji) {
        String value = key(emoji);
        Integer boxed = INDEX.get(value);
        if (boxed == null) {
            boxed = INDEX.get(value.replace("\uFE0F", ""));
        }
        if (boxed == null) return null;
        int index = boxed;
        try {
            File file = assetFile(FILES[index]);
            TLRPC.TL_document doc = new TLRPC.TL_document();
            doc.id = doc.access_hash = DOCUMENT_ID + index;
            doc.dc_id = 2;
            doc.file_reference = new byte[0];
            doc.date = (int) (System.currentTimeMillis() / 1000);
            doc.mime_type = "image/webp";
            doc.size = file.length();
            TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
            size.w = size.h = 64;
            doc.attributes.add(size);
            return doc;
        } catch (IOException e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Name of the local asset behind a document id (for upload.getFile). */
    public static String assetName(long id) {
        long offset = id - DOCUMENT_ID;
        return offset >= 0 && offset < FILES.length ? FILES[(int) offset] : null;
    }

    public static TLObject getFile(TLRPC.TL_upload_getFile request) throws WaysGramApi.ApiException {
        if (!(request.location instanceof TLRPC.TL_inputDocumentFileLocation)) return null;
        String name = assetName(request.location.id);
        if (name == null) return null;
        try {
            return WaysGramGifts.serveFile(assetFile(name), request);
        } catch (IOException e) {
            throw new WaysGramApi.ApiException(500, "FILE_ERROR");
        }
    }

    private static synchronized File assetFile(String name) throws IOException {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "telegram_reaction_textures");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Reaction texture cache unavailable");
        File file = new File(dir, name.replace('/', '_'));
        if (!file.isFile()) {
            File staging = File.createTempFile("texture", ".tmp", dir);
            try {
                try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(ASSET_DIR + name);
                     FileOutputStream out = new FileOutputStream(staging)) {
                    byte[] bytes = new byte[8192];
                    int n;
                    while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
                }
                if (!staging.renameTo(file)) throw new IOException("Reaction texture cache write failed");
            } finally {
                staging.delete();
            }
        }
        return file;
    }
}
