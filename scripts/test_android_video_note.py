#!/usr/bin/env python3
"""Compile Android video-note upload and mapping methods with Java fakes.

Set WG_JAVA and WG_ECJ_JAR. This does not build Android or test a device codec/UI.
"""
import io
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'

def method(source, signature):
    start = source.rfind('\n', 0, source.index('static ' + signature)) + 1
    pos = source.index('{', start) + 1
    level = 1
    while level:
        if source[pos] == '{': level += 1
        elif source[pos] == '}': level -= 1
        pos += 1
    return source[start:pos]

def main():
    with zipfile.ZipFile(io.BytesIO(b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6)))) as z:
        source = z.read(BASE + 'tgnet/WaysGramMedia.java').decode()
        sender = z.read(BASE + 'messenger/SendMessagesHelper.java').decode()
        camera = z.read(BASE + 'ui/Components/InstantCameraView.java').decode()
        msg = z.read(BASE + 'messenger/MessageObject.java').decode()
    assert 'videoEditedInfo.roundVideo = true;' in camera
    video_send = sender[sender.index('                    } else if (type == 3) {', sender.index('newMsg.ttl = uploadedPhoto.ttl_seconds')):]
    video_send = video_send[:video_send.index('                    } else if (type == 6) {')]
    assert 'uploadedDocument.ttl_seconds = ttl' in video_send
    assert 'ttl != 0 && MessageObject.isRoundVideoDocument(document)' in video_send
    assert 'media.ttl_seconds = ttl;' in video_send and 'media.flags |= 1;' in video_send
    names = ('byte[] assemble', 'boolean isAudioFilename', 'String audioUploadName', 'boolean isMp4Filename',
             'String videoNoteUploadName', 'boolean supportedAudioMime', 'String safeAudioMetadata',
             'void requirePersistentMedia', 'JSONObject uploadFor',
             'TLRPC.MessageMedia buildMedia(int account, String mediaId, String mime, String name,\n')
    methods = '\n'.join(method(source, name) for name in names) + '\n' + method(msg, 'boolean isRoundVideoDocument')
    java = r'''import java.util.*;
import java.io.*;
public class TestVideoNote {
    static class TextUtils { static boolean isEmpty(String s) { return s == null || s.isEmpty(); } }
    static class Base64 { static final int NO_WRAP=2, DEFAULT=0;
        static String encodeToString(byte[] b,int f) { return java.util.Base64.getEncoder().encodeToString(b); }
        static byte[] decode(String b,int f) { return java.util.Base64.getDecoder().decode(b); } }
    static class FileLog { static void e(Exception e) {} }
    static class TLObject { static final int FLAG_0=1, FLAG_1=2, FLAG_2=4; }
    static class BitmapFactory { static class Options { boolean inJustDecodeBounds; int outWidth,outHeight; } static void decodeFile(String s, Options o) {} }
    static class MediaMetadataRetriever {
        static final int METADATA_KEY_DURATION=1, METADATA_KEY_VIDEO_WIDTH=2, METADATA_KEY_VIDEO_HEIGHT=3;
        void setDataSource(String p) {} String extractMetadata(int k) { return k==1 ? "4250" : "360"; } void release() {}
    }
    static class JSONObject {
        Map<String,Object> values=new HashMap<>(); JSONObject() {} JSONObject(String unused) {}
        JSONObject put(String k,Object v) { values.put(k,v); return this; }
        String optString(String k,String d) { Object x=values.get(k); return x == null ? d : x.toString(); }
        double optDouble(String k,double d) { Object x=values.get(k); return x instanceof Number ? ((Number)x).doubleValue() : d; }
        int optInt(String k,int d) { Object x=values.get(k); return x instanceof Number ? ((Number)x).intValue() : d; }
    }
    static class Prefs { Map<String,String> data=new HashMap<>(); String getString(String k,String d) { return data.getOrDefault(k,d); }
        Prefs edit() { return this; } Prefs putString(String k,String v) { data.put(k,v); return this; } void apply() {} }
    static Prefs pref=new Prefs(); static Prefs prefs() { return pref; }
    static class TLRPC {
        static class InputFile { long id; int parts; String name; }
        static class InputMedia { int flags,ttl_seconds; boolean force_file; String mime_type; InputFile file; ArrayList<DocumentAttribute> attributes=new ArrayList<>(); }
        static class TL_inputMediaUploadedDocument extends InputMedia {} static class TL_inputMediaUploadedPhoto extends InputMedia {}
        static class TL_inputMediaDocument extends InputMedia {} static class TL_inputMediaPhoto extends InputMedia {}
        static class DocumentAttribute { String file_name,title,performer; boolean voice,round_message,supports_streaming; double duration; int w,h,flags; byte[] waveform; }
        static class TL_documentAttributeFilename extends DocumentAttribute {} static class TL_documentAttributeAudio extends DocumentAttribute {}
        static class TL_documentAttributeVideo extends DocumentAttribute {}
        static class Document { long id,access_hash,size; int date,dc_id; String mime_type; byte[] file_reference; ArrayList<DocumentAttribute> attributes=new ArrayList<>(); }
        static class TL_document extends Document {} static class MessageMedia { int flags; }
        static class TL_messageMediaDocument extends MessageMedia { Document document; }
        static class TL_messageMediaPhoto extends MessageMedia { TL_photo photo; }
        static class TL_photo { long id,access_hash; byte[] file_reference; int date,dc_id; ArrayList<TL_photoSize> sizes=new ArrayList<>(); }
        static class TL_photoSize { String type; int w,h,size; TL_fileLocationToBeDeprecated location; }
        static class TL_fileLocationToBeDeprecated { long volume_id; int local_id; }
    }
    static class WaysGramApi {
        static int calls; static String filename,kind; static JSONObject info;
        static class ApiException extends Exception { int status; ApiException(int s,String text) { super(text); status=s; } }
        static JSONObject upload(int account,String path,byte[] data,String type,String name,String mediaKind,JSONObject metadata) {
            calls++; filename=name;kind=mediaKind;info=metadata;return new JSONObject().put("id","0123456789abcdef0123456789abcdef");
        }
    }
    static File uploadDir() { return new File(System.getProperty("java.io.tmpdir"),"parts"); }
    static File cacheFile(String id) { return new File(uploadDir(),id); }
    static File ensure(int account,String id) { return cacheFile(id); }
    static long register(String id) { return 123; } static void writeFile(File f,byte[] b) {} static void removeParts(long id) {}
''' + methods + r'''
    static void eq(Object a,Object b) { if (!Objects.equals(a,b)) throw new AssertionError(a+" != "+b); }
    static void part(long id, byte[] bytes) throws Exception { File d=new File(uploadDir(), Long.toHexString(id)); d.mkdirs(); try(FileOutputStream f=new FileOutputStream(new File(d,"0"))) { f.write(bytes); } }
    public static void main(String[] args) throws Exception {
        byte[] data={0,0,0,16,102,116,121,112,105,115,111,109}; part(1,data);
        TLRPC.TL_inputMediaUploadedDocument m=new TLRPC.TL_inputMediaUploadedDocument();
        m.file=new TLRPC.InputFile();m.file.id=1;m.file.parts=1;m.file.name="temporary";m.mime_type="video/mp4";
        TLRPC.TL_documentAttributeVideo a=new TLRPC.TL_documentAttributeVideo();a.round_message=true;a.duration=4.25;a.w=360;a.h=360;m.attributes.add(a);
        uploadFor(0,m);eq(WaysGramApi.filename,"temporary.mp4");eq(WaysGramApi.kind,"video_note");
        eq(WaysGramApi.info.optDouble("duration",0),4.25);eq(WaysGramApi.info.optInt("width",0),360);
        eq(videoNoteUploadName("tmp.m4v","original.MP4","video/mp4"),"original.MP4");
        eq(videoNoteUploadName("tmp.m4v",null,"video/mp4"),"tmp.m4v");
        eq(videoNoteUploadName("tmp",null,"video/quicktime"),"tmp");
        JSONObject info=new JSONObject().put("duration",4.25).put("width",360).put("height",360);
        TLRPC.TL_messageMediaDocument built=(TLRPC.TL_messageMediaDocument)buildMedia(0,"abc","video/mp4","circle.mp4","video_note",info,false);
        eq(isRoundVideoDocument(built.document),true);
        TLRPC.DocumentAttribute video=built.document.attributes.get(1);eq(video.round_message,true);eq(video.w,360);eq(video.h,360);eq(video.duration,4.25);
        built=(TLRPC.TL_messageMediaDocument)buildMedia(0,"abc","video/mp4","round_video_old.mp4","",new JSONObject(),false);
        eq(isRoundVideoDocument(built.document),true);eq(built.document.attributes.get(1).duration,4.25);
        m.ttl_seconds=Integer.MAX_VALUE;try { uploadFor(0,m);throw new AssertionError("TTL accepted"); } catch(WaysGramApi.ApiException e) { eq(e.status,400); } eq(WaysGramApi.calls,1);
        File d=new File(uploadDir(),Long.toHexString(2));d.mkdirs();try(RandomAccessFile f=new RandomAccessFile(new File(d,"0"),"rw")){f.setLength(25L*1024*1024+1);}
        try { assemble(2,1);throw new AssertionError("size accepted"); } catch(WaysGramApi.ApiException e) { eq(e.status,413); }
        try(RandomAccessFile f=new RandomAccessFile(new File(d,"0"),"rw")){f.setLength(25L*1024*1024);}
        eq(assemble(2,1).length,25*1024*1024);
        try { assemble(3,1);throw new AssertionError("missing accepted"); } catch(WaysGramApi.ApiException e) { eq(e.status,400); }
        System.out.println("Video note upload, reconstruction, TTL and size tests passed");
    }
}'''
    with tempfile.TemporaryDirectory() as tmp:
        path=Path(tmp)/'TestVideoNote.java';path.write_text(java,encoding='utf-8')
        subprocess.run([os.environ['WG_JAVA'],'-jar',os.environ['WG_ECJ_JAR'],'-17','-encoding','UTF-8','-d',tmp,str(path)],check=True)
        subprocess.run([os.environ['WG_JAVA'], '-Djava.io.tmpdir='+tmp, '-cp',tmp,'TestVideoNote'],check=True)

if __name__ == '__main__': main()
