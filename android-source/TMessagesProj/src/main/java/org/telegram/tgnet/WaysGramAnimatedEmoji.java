package org.telegram.tgnet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Анимированные эмодзи WaysGram.
 *
 * <p>Клиент рисует «живой» эмодзи и текстурки реакций только из документа
 * {@code application/x-tgsticker}. В APK лежат два набора TGS (векторный Lottie
 * без растровых слоёв — его проигрывает нативный плеер стикеров), поэтому
 * документы выдаются без сервера: {@code upload.getFile} отдаёт файл из assets.
 *
 * <ul>
 *     <li><b>рисунок</b> — {@code assets/waysgram_emoji/art/*.tgs}: сам эмодзи с
 *     короткой анимацией появления. Идёт в сообщение и в текст. Последний кадр —
 *     спокойный эмодзи.</li>
 *     <li><b>рисунок для реакции</b> — {@code assets/waysgram_emoji/react/*.tgs}:
 *     тот же кадр, но вписанный в центральные 58%. Клиент рисует чип реакции
 *     фильтром {@code 40_40_lastreactframe}, который вырезает центральные 60%
 *     кадра и увеличивает их, — без отступа эмодзи обрезался бы по краям.</li>
 *     <li><b>эффект</b> — {@code assets/waysgram_emoji/*.tgs}: конфетти, искры,
 *     салюты. Проигрывается по нажатию на реакцию и при появлении сообщения с
 *     одиночным эмодзи.</li>
 * </ul>
 *
 * <p>Таблица «эмодзи → файлы» генерируется скриптами
 * {@code scripts/waysgram_emoji_art.py} (рисунки из Twemoji) и
 * {@code scripts/waysgram_emoji_import.py} (таблица {@link WaysGramEmojiTable}).
 *
 * <p>Идентификаторы документов синтетические и стабильные между запусками: по
 * ним клиент кэширует уже скачанные файлы. У рисунка и эффекта разные
 * пространства id — иначе кэш одного набора подставлялся бы вместо другого.
 */
public final class WaysGramAnimatedEmoji {
    /** Рисунок для эмодзи-сообщений (большой анимированный эмодзи в бабле). */
    private static final long EMOJI_ID = 0x6A52000000000000L;
    /** Рисунок для реакций: тот же файл, но свой id, чтобы кэш не смешивался. */
    private static final long REACTION_ID = 0x6A53000000000000L;
    /** Эффект (конфетти, искры): и для сообщений, и для реакций. */
    private static final long EFFECT_ID = 0x6A54000000000000L;

    private static final int COUNT = WaysGramEmojiTable.EMOJI.length;
    private static final HashMap<String, Integer> INDEX = new HashMap<>();
    /** «Короткий» вид эмодзи (без U+FE0F, оттенков и ZWJ-пола) -> индекс. */
    private static final HashMap<String, Integer> ALIAS = new HashMap<>();
    private static final TLRPC.Document[] EMOJI_DOCUMENTS = new TLRPC.Document[COUNT];
    private static final TLRPC.Document[] REACTION_DOCUMENTS = new TLRPC.Document[COUNT];
    private static final TLRPC.Document[] EFFECT_DOCUMENTS = new TLRPC.Document[COUNT];
    /** Копии официальных документов Telegram: id → документ с атрибутом эмодзи. */
    private static final HashMap<Long, TLRPC.Document> TELEGRAM_EMOJI = new HashMap<>();

    static {
        for (int i = 0; i < COUNT; i++) {
            String emoji = WaysGramEmojiTable.EMOJI[i];
            INDEX.put(emoji, i);
            String shortForm = unwrap(emoji);
            if (!shortForm.equals(emoji) && !ALIAS.containsKey(shortForm)) {
                ALIAS.put(shortForm, i);
            }
        }
    }

    private WaysGramAnimatedEmoji() {
    }

    public static int count() {
        return COUNT;
    }

    /** Есть ли локальный рисунок для этого эмодзи (U+FE0F и оттенки кожи не важны). */
    public static boolean has(String emoji) {
        int index = indexOf(emoji);
        return index >= 0 && WaysGramEmojiTable.ART[index] != null;
    }

    /** Есть ли локальный эффект (конфетти, искры) для этого эмодзи. */
    public static boolean hasEffect(String emoji) {
        int index = indexOf(emoji);
        return index >= 0 && WaysGramEmojiTable.EFFECT[index] != null;
    }

    /**
     * Документ-рисунок для эмодзи-сообщения или {@code null}.
     *
     * <p>Отдаём официальный анимированный эмодзи Telegram — тот же набор документов, что и
     * у реакций. Самодельный Twemoji-арт здесь больше не используется: именно из-за него
     * отправленные эмодзи выглядели «старыми», не как в Telegram. Для эмодзи, которых в
     * наборе нет, возвращаем {@code null} — клиент покажет обычный статичный эмодзи, как
     * Telegram без анимированного пака, а не чужой рисунок.</p>
     */
    public static synchronized TLRPC.Document find(String emoji) {
        return telegramEmoji(emoji);
    }

    /** Официальный анимированный эмодзи Telegram (документ из набора реакций) или {@code null}. */
    public static synchronized TLRPC.Document telegramEmoji(String emoji) {
        int index = WaysGramReactionAssets.indexOf(WaysGramReactions.normalize(emoji));
        if (index < 0) {
            return null;
        }
        TLRPC.Document base = WaysGramReactionAssets.document(index, "appear_animation");
        if (base == null) {
            base = WaysGramReactionAssets.document(index, "select_animation");
        }
        if (base == null || !"application/x-tgsticker".equals(base.mime_type)) {
            return null;
        }
        TLRPC.Document cached = TELEGRAM_EMOJI.get(base.id);
        if (cached != null) {
            return cached;
        }
        // Копия документа: клиент отличает анимированный эмодзи от стикера по
        // `TL_documentAttributeCustomEmoji`, а общий документ каталога реакций трогать нельзя.
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = base.id;
        doc.access_hash = base.access_hash;
        doc.dc_id = base.dc_id;
        doc.file_reference = base.file_reference;
        doc.date = base.date;
        doc.mime_type = base.mime_type;
        doc.size = base.size;
        doc.attributes = new java.util.ArrayList<>(base.attributes);
        TLRPC.TL_documentAttributeCustomEmoji attribute = new TLRPC.TL_documentAttributeCustomEmoji();
        attribute.alt = WaysGramReactionAssets.EMOJI[index];
        attribute.free = true;
        attribute.stickerset = new TLRPC.TL_inputStickerSetEmpty();
        doc.attributes.add(attribute);
        TELEGRAM_EMOJI.put(doc.id, doc);
        return doc;
    }

    /** Документ-рисунок для иконки/анимации реакции или {@code null}. */
    public static synchronized TLRPC.Document reaction(String emoji) {
        int index = indexOf(emoji);
        return index < 0 ? null : document(index, REACTION_ID, REACTION_DOCUMENTS, true, false);
    }

    /** Файл рисунка: для реакций — вариант с отступом, если он есть. */
    private static String artName(int index, boolean reaction) {
        if (reaction && WaysGramEmojiTable.REACT[index] != null) {
            return WaysGramEmojiTable.REACT[index];
        }
        return WaysGramEmojiTable.ART[index];
    }

    /** Документ-эффект для реакции (конфетти, искры) или {@code null}. */
    public static synchronized TLRPC.Document reactionEffect(String emoji) {
        int index = indexOf(emoji);
        return index < 0 ? null : document(index, EFFECT_ID, EFFECT_DOCUMENTS, false, false);
    }

    private static TLRPC.Document document(int index, long namespace,
                                           TLRPC.Document[] cache, boolean art, boolean asEmoji) {
        if (index < 0 || index >= COUNT) {
            return null;
        }
        String name = art ? artName(index, cache == REACTION_DOCUMENTS) : WaysGramEmojiTable.EFFECT[index];
        if (name == null) {
            return null;
        }
        TLRPC.Document cached = cache[index];
        if (cached != null) {
            return cached;
        }
        try {
            File file = assetFile(name);
            TLRPC.Document doc = buildDocument(namespace + index, file);
            if (asEmoji) {
                TLRPC.TL_documentAttributeCustomEmoji attribute = new TLRPC.TL_documentAttributeCustomEmoji();
                attribute.alt = WaysGramEmojiTable.EMOJI[index];
                attribute.free = true;
                attribute.stickerset = new TLRPC.TL_inputStickerSetEmpty();
                doc.attributes.add(attribute);
            }
            cache[index] = doc;
            return doc;
        } catch (IOException e) {
            FileLog.e(e);
            return null;
        }
    }

    private static TLRPC.Document buildDocument(long id, File file) {
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = doc.access_hash = id;
        doc.dc_id = 2;
        doc.file_reference = new byte[0];
        doc.date = (int) (System.currentTimeMillis() / 1000);
        doc.mime_type = "application/x-tgsticker";
        doc.size = file.length();
        TLRPC.TL_documentAttributeImageSize dimensions = new TLRPC.TL_documentAttributeImageSize();
        dimensions.w = dimensions.h = 512;
        doc.attributes.add(dimensions);
        doc.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        return doc;
    }

    /**
     * Наполняет карту «эмодзи -> анимации» для {@code EmojiAnimationsOverlay}.
     *
     * <p>Клиент берёт эффекты эмодзи из стикерпака {@code emojicom}, которого у
     * WaysGram нет. Вместо загрузки пака подставляем локальные эффекты из APK:
     * тогда нажатие на большой эмодзи (и его появление в чате) проигрывает
     * конфетти/искры этого эмодзи, а не пустоту.
     */
    public static void fillInteractions(java.util.HashSet<String> supported,
                                        java.util.HashMap<String, java.util.ArrayList<TLRPC.Document>> map) {
        for (int i = 0; i < WaysGramReactionAssets.count(); i++) {
            String emoji = WaysGramReactionAssets.EMOJI[i];
            if (map.containsKey(emoji)) {
                continue;
            }
            TLRPC.Document doc = WaysGramReactionAssets.document(i, "effect_animation");
            if (doc == null) {
                doc = WaysGramReactionAssets.document(i, "around_animation");
            }
            if (doc == null || !"application/x-tgsticker".equals(doc.mime_type)) {
                continue;
            }
            java.util.ArrayList<TLRPC.Document> list = new java.util.ArrayList<>(1);
            list.add(doc);
            map.put(emoji, list);
            supported.add(emoji);
            String alias = unwrap(emoji);
            if (!alias.equals(emoji) && !map.containsKey(alias)) {
                map.put(alias, list);
                supported.add(alias);
            }
        }
        for (int i = 0; i < COUNT; i++) {
            String emoji = WaysGramEmojiTable.EMOJI[i];
            if (WaysGramEmojiTable.EFFECT[i] == null || map.containsKey(emoji)) {
                continue;
            }
            TLRPC.Document doc = reactionEffect(emoji);
            if (doc == null) {
                continue;
            }
            java.util.ArrayList<TLRPC.Document> list = new java.util.ArrayList<>(1);
            list.add(doc);
            map.put(emoji, list);
            supported.add(emoji);
            String shortForm = unwrap(emoji);
            if (!shortForm.equals(emoji) && !map.containsKey(shortForm)) {
                map.put(shortForm, list);
                supported.add(shortForm);
            }
        }
    }

    /**
     * Приведение к «короткому» виду — так же, как {@code EmojiAnimationsOverlay.unwrapEmoji}:
     * без U+FE0F, оттенков кожи и ZWJ-обозначения пола.
     */
    public static String unwrap(String emoji) {
        if (emoji == null) {
            return "";
        }
        String base = stripModifiers(emoji);
        StringBuilder out = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == '\u200D' && i + 1 < base.length()
                    && (base.charAt(i + 1) == '\u2640' || base.charAt(i + 1) == '\u2642')) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Индекс эмодзи в таблице: точное совпадение, затем без оттенка кожи / U+FE0F. */
    private static int indexOf(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return -1;
        }
        Integer exact = INDEX.get(emoji);
        if (exact != null) {
            return exact;
        }
        String stripped = stripModifiers(emoji);
        Integer found = INDEX.get(stripped);
        if (found != null) {
            return found;
        }
        Integer alias = ALIAS.get(unwrap(emoji));
        return alias == null ? -1 : alias;
    }

    private static String stripModifiers(String emoji) {
        StringBuilder out = new StringBuilder(emoji.length());
        for (int i = 0; i < emoji.length(); i++) {
            char c = emoji.charAt(i);
            if (c == '\uFE0F') {
                continue;
            }
            if (Character.isHighSurrogate(c) && i + 1 < emoji.length()) {
                int code = Character.toCodePoint(c, emoji.charAt(i + 1));
                if (code >= 0x1F3FB && code <= 0x1F3FF) {
                    i++;
                    continue;
                }
                out.append(c).append(emoji.charAt(i + 1));
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static synchronized File assetFile(String name) throws IOException {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "waysgram_emoji_v3");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Emoji cache unavailable");
        }
        File file = new File(dir, name.replace('/', '_'));
        if (!file.isFile()) {
            File staging = File.createTempFile("emoji", ".tmp", dir);
            try {
                try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("waysgram_emoji/" + name);
                     FileOutputStream out = new FileOutputStream(staging)) {
                    byte[] bytes = new byte[8192];
                    int n;
                    while ((n = in.read(bytes)) != -1) {
                        out.write(bytes, 0, n);
                    }
                }
                if (!staging.renameTo(file)) {
                    throw new IOException("Emoji cache write failed");
                }
            } finally {
                staging.delete();
            }
        }
        return file;
    }

    /** Имя файла по id документа — для {@code upload.getFile}. */
    private static String assetName(long id) {
        if (id >= EMOJI_ID && id < EMOJI_ID + COUNT) {
            return artName((int) (id - EMOJI_ID), false);
        }
        if (id >= REACTION_ID && id < REACTION_ID + COUNT) {
            return artName((int) (id - REACTION_ID), true);
        }
        if (id >= EFFECT_ID && id < EFFECT_ID + COUNT) {
            return WaysGramEmojiTable.EFFECT[(int) (id - EFFECT_ID)];
        }
        return null;
    }

    static TLObject getFile(TLRPC.TL_upload_getFile request) throws WaysGramApi.ApiException {
        if (!(request.location instanceof TLRPC.TL_inputDocumentFileLocation)) {
            return null;
        }
        String name = assetName(request.location.id);
        if (name == null) {
            return null;
        }
        try {
            return WaysGramGifts.serveFile(assetFile(name), request);
        } catch (IOException e) {
            throw new WaysGramApi.ApiException(500, "FILE_ERROR");
        }
    }
}
