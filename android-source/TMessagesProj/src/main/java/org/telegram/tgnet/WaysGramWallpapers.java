package org.telegram.tgnet;

import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_account;

import java.io.File;
import java.util.HashMap;

/**
 * Обои-картинки для группы или канала.
 *
 * <p>Каталога обоев у WaysGram нет (`account.getWallPapers` не обслуживался), поэтому обои
 * выбирались только из галереи — и терялись: `account.uploadWallPaper` уходил в обработчик по
 * умолчанию, `TL_account.uploadWallPaper` падал, `messages.setChatWallPaper` не получал
 * `document`, и после переоткрытия чата фон снова был стандартным. Здесь файл загружается
 * тем же путём, что и картинки для остальных медиа, и связывается со слагом обоев
 * (`wg_<media_id>`), чтобы потом отдать серверу `document_id`.</p>
 */
public final class WaysGramWallpapers {
    private WaysGramWallpapers() {
    }

    /** account → slug → media_id. Нужна, чтобы связать входные обои с загруженным файлом. */
    private static final HashMap<Integer, HashMap<String, String>> uploadedBySlug = new HashMap<>();

    private static final String SLUG_PREFIX = "wg_";

    /** Обработчик `account.uploadWallPaper`; null — не наша просьба. */
    public static TLObject handle(int account, TLObject request) throws WaysGramApi.ApiException {
        if (request instanceof TL_account.uploadWallPaper) {
            return upload(account, (TL_account.uploadWallPaper) request);
        }
        return null;
    }

    private static TLObject upload(int account, TL_account.uploadWallPaper req) throws WaysGramApi.ApiException {
        String mediaId = WaysGramMedia.uploadFile(account, req.file, true);
        TLRPC.TL_wallPaper paper = fromMedia(account, mediaId, req.settings);
        if (paper == null) {
            throw new WaysGramApi.ApiException(400, "WALLPAPER_INVALID");
        }
        synchronized (uploadedBySlug) {
            HashMap<String, String> map = uploadedBySlug.get(account);
            if (map == null) {
                map = new HashMap<>();
                uploadedBySlug.put(account, map);
            }
            map.put(paper.slug, mediaId);
        }
        return paper;
    }

    static String slug(String mediaId) {
        return SLUG_PREFIX + mediaId;
    }

    /**
     * media_id по входным обоям из `messages.setChatWallPaper`.
     *
     * <p>Слаг `wg_<media_id>` переживает перезапуск приложения (он лежит в
     * `OverrideWallpaperInfo`), поэтому одного разбора строки достаточно и без карты.</p>
     */
    public static String mediaId(int account, TLRPC.InputWallPaper input) {
        if (input == null) {
            return null;
        }
        if (input instanceof TLRPC.TL_inputWallPaperSlug) {
            String slug = ((TLRPC.TL_inputWallPaperSlug) input).slug;
            if (slug == null) {
                return null;
            }
            synchronized (uploadedBySlug) {
                HashMap<String, String> map = uploadedBySlug.get(account);
                String found = map == null ? null : map.get(slug);
                if (found != null) {
                    return found;
                }
            }
            if (slug.startsWith(SLUG_PREFIX) && slug.length() == SLUG_PREFIX.length() + 32) {
                return slug.substring(SLUG_PREFIX.length());
            }
            return null;
        }
        if (input instanceof TLRPC.TL_inputWallPaper) {
            return WaysGramMedia.mediaIdFor(((TLRPC.TL_inputWallPaper) input).id);
        }
        return null;
    }

    /** Обои-картинка из загруженного медиа; null — файла нет или он не картинка. */
    public static TLRPC.TL_wallPaper fromMedia(int account, String mediaId, TLRPC.WallPaperSettings settings) {
        if (mediaId == null || mediaId.length() != 32) {
            return null;
        }
        try {
            TLRPC.MessageMedia media = WaysGramMedia.buildMedia(account, mediaId, "image/jpeg", "wallpaper.jpg",
                    "document", null, true);
            if (!(media instanceof TLRPC.TL_messageMediaDocument)) {
                return null;
            }
            TLRPC.TL_document document = (TLRPC.TL_document) ((TLRPC.TL_messageMediaDocument) media).document;
            TLRPC.TL_documentAttributeImageSize size = imageSize(account, mediaId);
            if (size != null) {
                document.attributes.add(size);
            }
            TLRPC.TL_wallPaper paper = new TLRPC.TL_wallPaper();
            paper.id = document.id;
            paper.access_hash = document.id;
            paper.document = document;
            paper.slug = slug(mediaId);
            if (settings != null) {
                paper.settings = settings;
                paper.flags |= TLObject.FLAG_2;
            }
            return paper;
        } catch (WaysGramApi.ApiException e) {
            org.telegram.messenger.FileLog.e(e);
            return null;
        }
    }

    private static TLRPC.TL_documentAttributeImageSize imageSize(int account, String mediaId) {
        try {
            File file = WaysGramMedia.ensure(account, mediaId);
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null;
            }
            TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
            size.w = options.outWidth;
            size.h = options.outHeight;
            return size;
        } catch (Exception e) {
            return null;
        }
    }

    /** Обои из ответа сервера: картинка (`document_id`) или градиент (`background_color`). */
    public static TLRPC.WallPaper fromJson(int account, JSONObject data) {
        if (data == null) {
            return null;
        }
        String documentId = data.optString("document_id", "");
        if (documentId.length() == 32) {
            TLRPC.TL_wallPaper paper = fromMedia(account, documentId, settings(data));
            if (paper != null) {
                return paper;
            }
        }
        return null;
    }

    /** Обои из запроса `messages.setChatWallPaper`: картинка, если файл уже загружен. */
    public static TLRPC.WallPaper fromInput(int account, TLRPC.InputWallPaper input,
                                            TLRPC.WallPaperSettings settings) {
        return fromMedia(account, mediaId(account, input), settings);
    }

    static TLRPC.TL_wallPaperSettings settings(JSONObject data) {
        if (data == null) {
            return null;
        }
        TLRPC.TL_wallPaperSettings settings = new TLRPC.TL_wallPaperSettings();
        if (data.has("background_color")) {
            settings.background_color = data.optInt("background_color");
            settings.flags |= TLObject.FLAG_0;
        }
        if (data.optBoolean("blur")) {
            settings.blur = true;
            settings.flags |= TLObject.FLAG_1;
        }
        if (data.optBoolean("motion")) {
            settings.motion = true;
            settings.flags |= TLObject.FLAG_2;
        }
        if (data.has("intensity")) {
            settings.intensity = data.optInt("intensity");
            settings.flags |= TLObject.FLAG_3;
        }
        if (data.has("second_background_color")) {
            settings.second_background_color = data.optInt("second_background_color");
            settings.rotation = data.optInt("rotation");
            settings.flags |= TLObject.FLAG_4;
        }
        if (data.has("third_background_color")) {
            settings.third_background_color = data.optInt("third_background_color");
            settings.flags |= TLObject.FLAG_5;
        }
        if (data.has("fourth_background_color")) {
            settings.fourth_background_color = data.optInt("fourth_background_color");
            settings.flags |= TLObject.FLAG_6;
        }
        if (data.has("emoticon")) {
            settings.emoticon = data.optString("emoticon", "");
            settings.flags |= TLObject.FLAG_7;
        }
        return settings;
    }
}
