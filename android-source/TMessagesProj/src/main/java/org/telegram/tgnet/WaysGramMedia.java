package org.telegram.tgnet;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Photos and documents. Files live on the WaysGram server (/api/media); the app keeps a local cache.
 * The server stores images (JPEG, PNG, WebP up to 10 MB) and documents (PDF, TXT, CSV up to 10 MB).
 */
public class WaysGramMedia {

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("waysgram_media", Context.MODE_PRIVATE);
    }

    private static File cacheDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "waysgram_media");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File uploadDir() {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "waysgram_upload");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    // ---------------------------------------------------------------- ids

    /** The app wants numeric ids; the server uses 32-hex ids. The mapping is remembered on the device. */
    public static long register(String mediaId) {
        long id = Long.parseLong(mediaId.substring(0, 15), 16);
        prefs().edit().putString("m_" + id, mediaId).apply();
        return id;
    }

    public static String mediaIdFor(long id) {
        return prefs().getString("m_" + id, null);
    }

    public static File cacheFile(String mediaId) {
        return new File(cacheDir(), mediaId + ".bin");
    }

    public static File ensure(int account, String mediaId) throws WaysGramApi.ApiException {
        File file = cacheFile(mediaId);
        if (file.exists() && file.length() > 0) {
            return file;
        }
        byte[] bytes = WaysGramApi.download(account, "/api/media/" + mediaId);
        writeFile(file, bytes);
        return file;
    }

    private static void writeFile(File file, byte[] bytes) throws WaysGramApi.ApiException {
        File tmp = null;
        try {
            // Parallel loads of the same photo must not overwrite each other's
            // temporary file or expose partially downloaded bytes to the viewer.
            tmp = File.createTempFile("wg-media-", ".tmp", file.getParentFile());
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(file)) {
                throw new WaysGramApi.ApiException(500, "FILE_ERROR");
            }
        } catch (WaysGramApi.ApiException e) {
            throw e;
        } catch (Exception e) {
            FileLog.e(e);
            throw new WaysGramApi.ApiException(500, "FILE_ERROR");
        } finally {
            if (tmp != null && tmp.exists()) tmp.delete();
        }
    }

    // Generated locally from the original, without modifying the video. Some
    // devices/codecs cannot extract frames; playback still works without one.
    private static File videoThumbnail(String mediaId, File source) {
        File target = new File(cacheDir(), mediaId + ".video-thumb.jpg");
        if (target.exists() && target.length() > 0) return target;
        if (Build.VERSION.SDK_INT < 27) return null; // avoid full-resolution frame allocation
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap bitmap = null;
        try {
            retriever.setDataSource(source.getAbsolutePath());
            int w = 320, h = 320;
            try {
                int sourceW = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int sourceH = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if ("90".equals(rotation) || "270".equals(rotation)) { int swap = sourceW; sourceW = sourceH; sourceH = swap; }
                if (sourceW > 0 && sourceH > 0) {
                    float factor = 320f / Math.max(sourceW, sourceH);
                    w = Math.max(1, Math.round(sourceW * factor));
                    h = Math.max(1, Math.round(sourceH * factor));
                }
            } catch (Exception ignored) { }
            bitmap = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
            if (bitmap == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output) || output.size() == 0) return null;
            writeFile(target, output.toByteArray());
            return target;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    // A custom video cover is a separately uploaded image. Keep a bounded JPEG
    // thumbnail in the video's normal cache location so Telegram's existing
    // document-thumbnail request path can serve it without special UI code.
    private static File videoCoverThumbnail(int account, String coverMediaId, String videoMediaId)
            throws WaysGramApi.ApiException {
        File source = ensure(account, coverMediaId);
        Bitmap decoded = null;
        Bitmap scaled = null;
        File target = new File(cacheDir(), videoMediaId + ".video-thumb.jpg");
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 640 && sample < 64) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            decoded = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            if (decoded == null) return null;
            int max = Math.max(decoded.getWidth(), decoded.getHeight());
            if (max > 320) {
                float factor = 320f / max;
                scaled = Bitmap.createScaledBitmap(decoded,
                        Math.max(1, Math.round(decoded.getWidth() * factor)),
                        Math.max(1, Math.round(decoded.getHeight() * factor)), true);
            } else {
                scaled = decoded;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 84, output) || output.size() == 0) return null;
            if (target.exists() && !target.delete()) return null;
            writeFile(target, output.toByteArray());
            return target;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (scaled != null && scaled != decoded) scaled.recycle();
            if (decoded != null) decoded.recycle();
        }
    }

    // ---------------------------------------------------------------- TL objects

    public static TLRPC.MessageMedia buildMedia(int account, String mediaId, String mime, String name) throws WaysGramApi.ApiException {
        return buildMedia(account, mediaId, mime, name, "", null, false);
    }

    public static TLRPC.MessageMedia buildMedia(int account, String mediaId, String mime, String name,
                                                String kind, JSONObject info, boolean forceDocument) throws WaysGramApi.ApiException {
        File file = ensure(account, mediaId);
        long id = register(mediaId);
        int now = (int) (System.currentTimeMillis() / 1000);
        if (!forceDocument && !"document".equals(kind) && mime != null && mime.startsWith("image/")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
            size.type = "x";
            size.w = options.outWidth > 0 ? options.outWidth : 1280;
            size.h = options.outHeight > 0 ? options.outHeight : 1280;
            int rotation = AndroidUtilities.getImageOrientation(file.getPath()).first;
            if (rotation == 90 || rotation == 270) {
                int width = size.w;
                size.w = size.h;
                size.h = width;
            }
            size.size = (int) file.length();
            TLRPC.TL_fileLocationToBeDeprecated location = new TLRPC.TL_fileLocationToBeDeprecated();
            location.volume_id = -id;
            // Match PhotoSize.TLdeserialize's derived location across restarts.
            location.local_id = 'x';
            size.location = location;

            TLRPC.TL_photo photo = new TLRPC.TL_photo();
            photo.id = id;
            photo.access_hash = id;
            photo.file_reference = new byte[0];
            photo.date = now;
            photo.dc_id = 2;
            photo.sizes.add(size);
            TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
            media.photo = photo;
            media.spoiler = info != null && info.optBoolean("spoiler", false);
            media.flags |= TLObject.FLAG_0;
            return media;
        }
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = id;
        doc.access_hash = id;
        doc.file_reference = new byte[0];
        doc.date = now;
        doc.mime_type = TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
        doc.size = file.length();
        doc.dc_id = 2;
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = TextUtils.isEmpty(name) ? "document" : name;
        doc.attributes.add(filename);
        // Repair older recordings produced by WaysGram before it stored the media kind.
        String lowerName = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (TextUtils.isEmpty(kind) && mime != null && mime.startsWith("video/") && lowerName.startsWith("round_video_")) kind = "video_note";
        if (TextUtils.isEmpty(kind) && mime != null && mime.startsWith("audio/") && lowerName.startsWith("audio_recording_voice_")) kind = "voice";
        if (info == null) info = new JSONObject();
        double duration = info.optDouble("duration", 0);
        int width = info.optInt("width", 0), height = info.optInt("height", 0);
        if ((duration <= 0 || ((kind.equals("video") || kind.equals("video_note") || kind.equals("gif")) && (width <= 0 || height <= 0)))
                && (mime != null && (mime.startsWith("audio/") || mime.startsWith("video/")))) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (duration <= 0 && value != null) duration = Double.parseDouble(value) / 1000.0;
                value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                if (width <= 0 && value != null) width = Integer.parseInt(value);
                value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (height <= 0 && value != null) height = Integer.parseInt(value);
            } catch (Exception ignored) {
                // Some codecs do not expose all fields. Retain what was already available.
            } finally {
                try { retriever.release(); } catch (Exception ignored) { }
            }
        }
        TLRPC.Photo videoCoverPhoto = null;
        if ((kind.equals("voice") || kind.equals("audio")) && mime != null && mime.startsWith("audio/")) {
            TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
            audio.voice = kind.equals("voice");
            audio.duration = Math.max(0, duration);
            String title = info.optString("title", ""), performer = info.optString("performer", "");
            if (!TextUtils.isEmpty(title)) { audio.title = title; audio.flags |= TLObject.FLAG_0; }
            if (!TextUtils.isEmpty(performer)) { audio.performer = performer; audio.flags |= TLObject.FLAG_1; }
            String waveform = info.optString("waveform", "");
            if (!TextUtils.isEmpty(waveform)) {
                try {
                    audio.waveform = Base64.decode(waveform, Base64.DEFAULT);
                    if (audio.waveform.length > 0 && audio.waveform.length <= 768) audio.flags |= TLObject.FLAG_2;
                } catch (IllegalArgumentException ignored) { }
            }
            doc.attributes.add(audio);
        } else if ((kind.equals("video") || kind.equals("video_note") || kind.equals("gif")) && mime != null && mime.startsWith("video/")) {
            if (kind.equals("gif")) doc.attributes.add(new TLRPC.TL_documentAttributeAnimated());
            TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
            video.round_message = kind.equals("video_note");
            video.supports_streaming = true;
            video.duration = Math.max(0, duration);
            video.w = width > 0 ? width : 384;
            video.h = height > 0 ? height : 384;
            doc.attributes.add(video);
            String coverMediaId = info.optString("video_cover_media_id", "");
if (!TextUtils.isEmpty(coverMediaId) && kind.equals("video")) {
    try {
        TLRPC.MessageMedia cover = buildMedia(account, coverMediaId, "image/jpeg",
                "video_cover.jpg", "", new JSONObject(), false);
        if (cover instanceof TLRPC.TL_messageMediaPhoto) {
            File customThumb = videoCoverThumbnail(account, coverMediaId, mediaId);
            if (customThumb != null) {
                videoCoverPhoto = ((TLRPC.TL_messageMediaPhoto) cover).photo;
            }
        }
    } catch (WaysGramApi.ApiException coverError) {
        FileLog.e(coverError);
    }
}
            File thumb = videoThumbnail(mediaId, file);
            if (thumb != null) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(thumb.getPath(), bounds);
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
                    size.type = "m";
                    size.w = bounds.outWidth;
                    size.h = bounds.outHeight;
                    size.size = (int) thumb.length();
                    size.location = new TLRPC.TL_fileLocationToBeDeprecated();
                    size.location.volume_id = -id;
                    size.location.local_id = 1000 + 'm';
                    doc.thumbs.add(size);
                    doc.flags |= TLObject.FLAG_0;
                }
            }
        }
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = doc;
        media.spoiler = info.optBoolean("spoiler", false);
        if (videoCoverPhoto != null) {
            media.video_cover = videoCoverPhoto;
            media.flags |= TLObject.FLAG_9;
        }
        media.flags |= TLObject.FLAG_0;
        return media;
    }

    /**
     * Adds the attachment (if any) to a message built from server JSON. The server fills the text with a stock
     * phrase when no caption was given; the app shows the attachment alone in that case.
     */
    public static TLRPC.Message attach(int account, TLRPC.Message message, JSONObject json) {
        WaysGramMapper.applyExtras(message, json,
                message.peer_id instanceof TLRPC.TL_peerChat ? WaysGramChats.GROUP_MESSAGE_OFFSET : 0);
        if (WaysGramCards.attach(message, json.optJSONObject("card"), json.optInt("id"))) return message;
        JSONObject contact = json.optJSONObject("contact");
        String contactName = contact != null ? contact.optString("name", "") : json.optString("contact_name", "");
        String contactPhone = contact != null ? contact.optString("phone", "") : json.optString("contact_phone", "");
        if (!TextUtils.isEmpty(contactName) && !"null".equals(contactName)
                && !TextUtils.isEmpty(contactPhone) && !"null".equals(contactPhone)) {
            String first = contact != null ? contact.optString("first_name", "") : json.optString("contact_first_name", "");
            String last = contact != null ? contact.optString("last_name", "") : json.optString("contact_last_name", "");
            String vcard = contact != null ? contact.optString("vcard", "") : json.optString("contact_vcard", "");
            if ("null".equals(first)) first = "";
            if ("null".equals(last)) last = "";
            if ("null".equals(vcard)) vcard = "";
            TLRPC.TL_messageMediaContact media = new TLRPC.TL_messageMediaContact();
            media.first_name = TextUtils.isEmpty(first) && TextUtils.isEmpty(last) ? contactName : first;
            media.last_name = last;
            media.phone_number = contactPhone;
            media.vcard = vcard;
            // The server stores a contact card, not an authenticated WaysGram identity.
            media.user_id = 0;
            message.media = media;
            message.flags |= TLObject.FLAG_9;
            if (("Контакт: " + contactName).equals(message.message)) message.message = "";
            return message;
        }
        JSONObject location = json.optJSONObject("location");
        boolean flatLocation = !json.isNull("location_latitude") && !json.isNull("location_longitude");
        if (location != null || flatLocation) {
            double latitude = location != null ? location.optDouble("latitude", Double.NaN) : json.optDouble("location_latitude", Double.NaN);
            double longitude = location != null ? location.optDouble("longitude", Double.NaN) : json.optDouble("location_longitude", Double.NaN);
            if (!Double.isNaN(latitude) && !Double.isInfinite(latitude) && latitude >= -90 && latitude <= 90
                    && !Double.isNaN(longitude) && !Double.isInfinite(longitude) && longitude >= -180 && longitude <= 180) {
                TLRPC.TL_geoPoint point = new TLRPC.TL_geoPoint();
                point.lat = latitude;
                point._long = longitude;
                String label = location != null ? location.optString("label", "") : json.optString("location_label", "");
                if (!TextUtils.isEmpty(label) && !"null".equals(label)) {
                    TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
                    venue.geo = point;
                    venue.title = label;
                    JSONObject details = location != null ? location.optJSONObject("venue") : null;
                    if (details == null) {
                        String raw = json.optString("location_venue_json", "");
                        if (!TextUtils.isEmpty(raw) && !"null".equals(raw)) {
                            try { details = new JSONObject(raw); } catch (Exception ignored) { }
                        }
                    }
                    venue.address = details != null ? details.optString("address", "") : "";
                    venue.provider = details != null ? details.optString("provider", "") : "waysgram";
                    venue.venue_id = details != null ? details.optString("venue_id", "") : "";
                    venue.venue_type = details != null ? details.optString("venue_type", "") : "";
                    message.media = venue;
                } else {
                    TLRPC.TL_messageMediaGeo geo = new TLRPC.TL_messageMediaGeo();
                    geo.geo = point;
                    message.media = geo;
                }
                message.flags |= TLObject.FLAG_9;
                if ("Местоположение".equals(message.message) || label.equals(message.message)) message.message = "";
                return message;
            }
        }
        String mediaId = json.optString("media_id", "");
        if (TextUtils.isEmpty(mediaId) || "null".equals(mediaId)) {
            return message;
        }
        try {
            String mime = json.optString("media_type", "");
            String name = json.optString("media_name", "");
            String kind = json.optString("media_kind", "");
            if ("null".equals(kind)) kind = "";
            JSONObject metadata = json.optJSONObject("media_metadata");
            if (metadata == null) {
                try { metadata = new JSONObject(json.optString("media_metadata", "{}")); }
                catch (Exception ignored) { metadata = new JSONObject(); }
            }
            message.media = buildMedia(account, mediaId, mime, name, kind, metadata, false);
            if (message.media != null) message.flags |= TLObject.FLAG_9;
            String body = message.message == null ? "" : message.message;
            if ((json.optJSONArray("entities") == null || json.optJSONArray("entities").length() == 0)
                    && (body.equals("Изображение") || body.equals("Документ: " + name)
                    || (body.equals("Медиаальбом") && json.optInt("media_album_count", 0) > 1))) {
                message.message = "";
            }
        } catch (WaysGramApi.ApiException e) {
            FileLog.e(e);
        }
        return message;
    }

    // ---------------------------------------------------------------- download side

    public static TLObject getFile(int account, TLRPC.TL_upload_getFile req) throws WaysGramApi.ApiException {
        long id;
        if (req.location instanceof TLRPC.TL_inputPhotoFileLocation) {
            id = req.location.id;
        } else if (req.location instanceof TLRPC.TL_inputDocumentFileLocation) {
            id = req.location.id;
        } else if (req.location instanceof TLRPC.TL_inputPeerPhotoFileLocation) {
            id = ((TLRPC.TL_inputPeerPhotoFileLocation) req.location).photo_id;
        } else {
            return null;
        }
        String mediaId = mediaIdFor(id);
        if (mediaId == null) {
            return null;
        }
        File original = ensure(account, mediaId);
        if (req.location instanceof TLRPC.TL_inputDocumentFileLocation
                && !TextUtils.isEmpty(req.location.thumb_size)) {
            if (!"m".equals(req.location.thumb_size))
                throw new WaysGramApi.ApiException(404, "MEDIA_THUMB_NOT_FOUND");
            File thumb = videoThumbnail(mediaId, original);
            if (thumb == null) throw new WaysGramApi.ApiException(404, "MEDIA_THUMB_NOT_FOUND");
            return WaysGramGifts.serveFile(thumb, req);
        }
        return WaysGramGifts.serveFile(original, req);
    }

    // ---------------------------------------------------------------- upload side

    private static File partFile(long fileId, int part) {
        File dir = new File(uploadDir(), Long.toHexString(fileId));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, String.valueOf(part));
    }

    public static TLObject savePart(long fileId, int part, NativeByteBuffer buffer) throws WaysGramApi.ApiException {
        try {
            int length = buffer.limit();
            byte[] bytes = new byte[length];
            buffer.position(0);
            buffer.readBytes(bytes, false);
            try (FileOutputStream out = new FileOutputStream(partFile(fileId, part))) {
                out.write(bytes);
            }
            return new TLRPC.TL_boolTrue();
        } catch (Exception e) {
            FileLog.e(e);
            throw new WaysGramApi.ApiException(500, "FILE_PART_INVALID");
        }
    }

    private static byte[] assemble(long fileId, int parts) throws WaysGramApi.ApiException {
        File dir = new File(uploadDir(), Long.toHexString(fileId));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int i = 0; i < parts; i++) {
                File part = new File(dir, String.valueOf(i));
                if (!part.exists()) {
                    throw new WaysGramApi.ApiException(400, "FILE_PART_MISSING");
                }
                try (InputStream in = new FileInputStream(part)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        // Match the server document cap before allocating an unbounded byte array.
                        if (out.size() > 25 * 1024 * 1024 - n) {
                            throw new WaysGramApi.ApiException(413, "Размер вложения превышает 25 МБ");
                        }
                        out.write(buf, 0, n);
                    }
                }
            }
            return out.toByteArray();
        } catch (WaysGramApi.ApiException e) {
            throw e;
        } catch (Exception e) {
            FileLog.e(e);
            throw new WaysGramApi.ApiException(500, "FILE_ERROR");
        }
    }

    private static void removeParts(long fileId) {
        File dir = new File(uploadDir(), Long.toHexString(fileId));
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    // Android may upload an audio file from a temporary path without its real
    // extension. The server validates by filename and content, so prefer the
    // original document filename and only infer an extension from audio MIME
    // when neither available filename has a supported one.
    private static boolean isAudioFilename(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".ogg")
                || lower.endsWith(".opus") || lower.endsWith(".flac") || lower.endsWith(".wav");
    }

    static String audioUploadName(String uploaded, String original, String mime) {
        if (isAudioFilename(original)) return original;
        if (isAudioFilename(uploaded)) return uploaded;
        String name = !TextUtils.isEmpty(original) ? original : !TextUtils.isEmpty(uploaded) ? uploaded : "audio";
        if (mime == null) return name;
        switch (mime.toLowerCase(java.util.Locale.ROOT)) {
            case "audio/mpeg": case "audio/mp3": return name + ".mp3";
            case "audio/mp4": case "audio/m4a": case "audio/x-m4a": return name + ".m4a";
            case "audio/ogg": return name + ".ogg";
            case "audio/opus": return name + ".opus";
            case "audio/flac": case "audio/x-flac": return name + ".flac";
            case "audio/wav": case "audio/x-wav": case "audio/wave": return name + ".wav";
            default: return name;
        }
    }

    // Round videos can be uploaded from converted temporary paths without a suffix.
    private static String videoNoteUploadName(String uploaded, String original, String mime) {
        if (isMp4Filename(original)) return original;
        if (isMp4Filename(uploaded)) return uploaded;
        String name = !TextUtils.isEmpty(original) ? original : !TextUtils.isEmpty(uploaded) ? uploaded : "video_note";
        return "video/mp4".equalsIgnoreCase(mime) ? name + ".mp4" : name;
    }

    private static boolean isMp4Filename(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".m4v");
    }

    private static boolean isSupportedDocumentFilename(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String ext : new String[] {".jpg", ".jpeg", ".png", ".webp", ".pdf", ".txt", ".csv",
                ".zip", ".rar", ".7z", ".tar", ".gz", ".tgz", ".bz2", ".tbz2", ".xz", ".txz", ".mp4", ".m4v", ".mov",
                ".webm", ".mkv", ".mp3", ".m4a", ".ogg", ".opus", ".flac", ".wav"}) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean supportedAudioMime(String mime) {
        if (mime == null) return false;
        switch (mime.toLowerCase(java.util.Locale.ROOT)) {
            case "audio/mpeg": case "audio/mp3": case "audio/mp4": case "audio/m4a": case "audio/x-m4a":
            case "audio/ogg": case "audio/opus": case "audio/flac": case "audio/x-flac":
            case "audio/wav": case "audio/x-wav": case "audio/wave": return true;
            default: return false;
        }
    }

    private static String safeAudioMetadata(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0, count = 0; i < value.length() && count < 160;) {
            int code = value.codePointAt(i);
            i += Character.charCount(code);
            if (code < 32 || code >= 0xD800 && code <= 0xDFFF) continue;
            out.appendCodePoint(code);
            count++;
        }
        return out.toString();
    }

    // Never silently turn a self-destructing attachment into a permanent one.
    static void requirePersistentMedia(TLRPC.InputMedia media) throws WaysGramApi.ApiException {
        if (media == null) return;
        boolean uploaded = media instanceof TLRPC.TL_inputMediaUploadedDocument || media instanceof TLRPC.TL_inputMediaUploadedPhoto;
        boolean existing = media instanceof TLRPC.TL_inputMediaDocument || media instanceof TLRPC.TL_inputMediaPhoto;
        if (media.ttl_seconds != 0 || (uploaded && (media.flags & 2) != 0) || (existing && (media.flags & 1) != 0)) {
            throw new WaysGramApi.ApiException(400, "Самоуничтожающиеся медиа пока не поддерживаются WaysGram");
        }
    }

    // Covers are stored as a separate image asset and linked from video metadata.
    // Refuse an unmapped photo ID rather than silently sending video without its cover.
    static void requireSupportedVideoCover(TLRPC.InputMedia media) throws WaysGramApi.ApiException {
        if (media == null || media.video_cover == null) return;
        if (media.video_cover.id <= 0 || TextUtils.isEmpty(mediaIdFor(media.video_cover.id))) {
            throw new WaysGramApi.ApiException(400, "VIDEO_COVER_NOT_UPLOADED");
        }
    }

    static JSONObject metadataForInput(TLRPC.InputMedia media) throws WaysGramApi.ApiException {
        JSONObject info = new JSONObject();
        if (media == null) return info;
        try {
            info.put("spoiler", media.spoiler);
            if (media.video_cover != null) {
                requireSupportedVideoCover(media);
                info.put("video_cover_media_id", mediaIdFor(media.video_cover.id));
            }
            return info;
        } catch (WaysGramApi.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "MEDIA_METADATA_INVALID");
        }
    }

    /** Uploads a native attachment and persists its actual type and playback metadata. */
    public static JSONObject uploadFor(int account, TLRPC.InputMedia media) throws WaysGramApi.ApiException {
        requirePersistentMedia(media);
        requireSupportedVideoCover(media);
        boolean photo = media instanceof TLRPC.TL_inputMediaUploadedPhoto;
        boolean document = media instanceof TLRPC.TL_inputMediaUploadedDocument;
        if (!photo && !document) return null;
        if (media.file == null) throw new WaysGramApi.ApiException(400, "FILE_PARTS_INVALID");
        boolean original = document && media.force_file;
        String coverMediaId = media.video_cover == null ? "" : mediaIdFor(media.video_cover.id);
        if (media.video_cover != null && TextUtils.isEmpty(coverMediaId)) {
            throw new WaysGramApi.ApiException(400, "VIDEO_COVER_NOT_UPLOADED");
        }
        String uploadKey = "uploaded_" + account + "_" + media.file.id + "_" + media.file.parts
                + (original ? "_original" : "") + "_s" + (media.spoiler ? "1" : "0")
                + "_c" + (TextUtils.isEmpty(coverMediaId) ? "none" : coverMediaId);
        String previous = prefs().getString(uploadKey, null);
        if (previous != null) {
            try { return new JSONObject(previous); } catch (Exception ignored) { }
        }
        byte[] data = assemble(media.file.id, Math.max(media.file.parts, 1));
        String kind = "";
        String originalName = null;
        JSONObject info = new JSONObject();
        try {
            info.put("spoiler", media.spoiler);
            if (!TextUtils.isEmpty(coverMediaId)) info.put("video_cover_media_id", coverMediaId);
        } catch (Exception e) {
            throw new WaysGramApi.ApiException(400, "MEDIA_METADATA_INVALID");
        }
        boolean animated = false;
        boolean sticker = false;
        boolean roundVideo = false;
        boolean audioAttribute = false;
        if (document) {
            try {
                for (TLRPC.DocumentAttribute attr : media.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeAnimated) animated = true;
                    if (attr instanceof TLRPC.TL_documentAttributeSticker || attr instanceof TLRPC.TL_documentAttributeCustomEmoji) sticker = true;
                    if (attr instanceof TLRPC.TL_documentAttributeFilename && !TextUtils.isEmpty(attr.file_name)) {
                        originalName = attr.file_name;
                    } else if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                        audioAttribute = true;
                        kind = attr.voice ? "voice" : "audio";
                        info.put("duration", Math.max(0, Math.min(86400, attr.duration)));
                        String title = safeAudioMetadata(attr.title), performer = safeAudioMetadata(attr.performer);
                        if (!TextUtils.isEmpty(title)) info.put("title", title);
                        if (!TextUtils.isEmpty(performer)) info.put("performer", performer);
                        if (attr.waveform != null && attr.waveform.length <= 768) info.put("waveform", Base64.encodeToString(attr.waveform, Base64.NO_WRAP));
                    } else if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                        roundVideo |= attr.round_message;
                        kind = attr.round_message ? "video_note" : "video";
                        info.put("duration", Math.max(0, Math.min(86400, attr.duration)));
                        info.put("width", Math.max(0, Math.min(8192, attr.w)));
                        info.put("height", Math.max(0, Math.min(8192, attr.h)));
                    }
                }
            } catch (Exception e) { throw new WaysGramApi.ApiException(400, "MEDIA_METADATA_INVALID"); }
        }
        if (media.video_cover != null && (!document || original || !"video".equals(kind))) {
            throw new WaysGramApi.ApiException(400, "VIDEO_COVER_NOT_SUPPORTED_FOR_MEDIA_TYPE");
        }
        if (document && animated && !original) {
            // Do not silently convert stickers, non-MP4 GIFs or round videos into another media type.
            if (sticker || roundVideo || audioAttribute || !"video/mp4".equalsIgnoreCase(media.mime_type))
                throw new WaysGramApi.ApiException(400, "ANIMATION_NOT_SUPPORTED");
            if (info.optInt("width", 0) > 1280 || info.optInt("height", 0) > 1280)
                throw new WaysGramApi.ApiException(400, "ANIMATION_DIMENSIONS_NOT_SUPPORTED");
            kind = "gif";
        }
        if (document && TextUtils.isEmpty(kind) && !media.force_file && supportedAudioMime(media.mime_type)) kind = "audio";
        String uploadName = media.file.name;
        if (original) {
            if (!TextUtils.isEmpty(originalName)) uploadName = originalName;
        } else if ("gif".equals(kind)) {
            // Temporary paths and inline GIF names often end in .gif despite containing MP4.
            uploadName = "animation.mp4";
        } else if (document && ("audio".equals(kind) || "voice".equals(kind) || (TextUtils.isEmpty(kind) && supportedAudioMime(media.mime_type)))) {
            uploadName = audioUploadName(uploadName, originalName, media.mime_type);
        } else if (document && "video_note".equals(kind)) {
            uploadName = videoNoteUploadName(uploadName, originalName, media.mime_type);
        } else if (document && isSupportedDocumentFilename(originalName)) {
            // The temporary upload path may not carry the original extension.
            uploadName = originalName;
        }
        String uploadPath = photo ? "/api/media/images" : original ? "/api/media/documents/original" : "/api/media/documents";
        JSONObject result = WaysGramApi.upload(account, uploadPath,
                data, "application/octet-stream", photo ? null : uploadName, original ? null : kind, original ? null : info);
        String mediaId = result.optString("id", "");
        if (mediaId.isEmpty()) throw new WaysGramApi.ApiException(502, "BAD_RESPONSE");
        writeFile(cacheFile(mediaId), data);
        register(mediaId);
        prefs().edit().putString(uploadKey, result.toString()).apply();
        removeParts(media.file.id);
        return result;
    }

    public static String existingMediaId(TLRPC.InputMedia media) {
        long id = 0;
        if (media instanceof TLRPC.TL_inputMediaPhoto && ((TLRPC.TL_inputMediaPhoto) media).id != null) {
            id = ((TLRPC.TL_inputMediaPhoto) media).id.id;
        } else if (media instanceof TLRPC.TL_inputMediaDocument && ((TLRPC.TL_inputMediaDocument) media).id != null) {
            id = ((TLRPC.TL_inputMediaDocument) media).id.id;
        }
        return id == 0 ? null : mediaIdFor(id);
    }

    public static TLRPC.MessageMedia uploadMedia(int account, TLRPC.InputMedia input) throws WaysGramApi.ApiException {
        JSONObject uploaded = uploadFor(account, input);
        if (uploaded == null) throw new WaysGramApi.ApiException(400, "MEDIA_NOT_SUPPORTED");
        JSONObject info;
        try { info = new JSONObject(uploaded.optString("media_metadata", "{}")); }
        catch (Exception ignored) { info = new JSONObject(); }
        return buildMedia(account, uploaded.optString("id"), uploaded.optString("mime_type", ""),
                uploaded.optString("name", ""), uploaded.optString("media_kind", ""), info,
                input instanceof TLRPC.TL_inputMediaUploadedDocument);
    }

    /** Compatibility helper for profile/avatar uploads. */
    public static String uploadFile(int account, TLRPC.InputFile file, boolean image) throws WaysGramApi.ApiException {
        if (file == null) throw new WaysGramApi.ApiException(400, "FILE_PARTS_INVALID");
        byte[] data = assemble(file.id, Math.max(file.parts, 1));
        JSONObject result = WaysGramApi.upload(account, image ? "/api/media/images" : "/api/media/documents",
                data, "application/octet-stream", image ? null : file.name);
        String id = result.optString("id", "");
        if (id.isEmpty()) throw new WaysGramApi.ApiException(502, "BAD_RESPONSE");
        writeFile(cacheFile(id), data);
        register(id);
        return id;
    }
}
