#!/usr/bin/env python3
"""Compile the actual Android upload method against small Java fakes (not an APK/device test).

Set WG_JAVA to a Java 17+ runtime and WG_ECJ_JAR to an Eclipse compiler jar.
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
    opening = source.index('{', start)
    level, pos = 1, opening + 1
    while level:
        if source[pos] == '{': level += 1
        elif source[pos] == '}': level -= 1
        pos += 1
    return source[start:pos]

def main():
    with zipfile.ZipFile(io.BytesIO(b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6)))) as archive:
        source = archive.read(BASE + 'tgnet/WaysGramMedia.java').decode()
        transport = archive.read(BASE + 'tgnet/WaysGramTransport.java').decode()
        send = archive.read(BASE + 'messenger/SendMessagesHelper.java').decode()
        recorder = archive.read(BASE + 'messenger/MediaController.java').decode()
    assert 'WaysGramMedia.requirePersistentMedia(send.media);' in transport
    assert 'attributeAudio.voice = true;' in recorder
    voice_send = send[send.index('                    } else if (type == 8) {', send.index('newMsg.ttl = uploadedPhoto.ttl_seconds')):]
    voice_send = voice_send[:voice_send.index('                    } else if (type == 10) {')]
    assert 'uploadedDocument.ttl_seconds = ttl' in voice_send
    assert 'media.ttl_seconds = ttl;' in voice_send and 'media.flags |= 1;' in voice_send
    methods = '\n'.join(method(source, name) for name in ('boolean isAudioFilename', 'String audioUploadName',
        'boolean supportedAudioMime', 'String safeAudioMetadata', 'void requirePersistentMedia', 'JSONObject uploadFor'))
    java = r'''import java.util.*;
import java.io.*;
public class TestVoice {
    static class TextUtils { static boolean isEmpty(String s) { return s == null || s.isEmpty(); } }
    static class Base64 { static final int NO_WRAP = 2; static String encodeToString(byte[] b, int f) { return java.util.Base64.getEncoder().encodeToString(b); } }
    static class JSONObject {
        Map<String,Object> values = new HashMap<>();
        JSONObject() {} JSONObject(String unused) {}
        JSONObject put(String k, Object v) { values.put(k,v); return this; }
        String optString(String k, String d) { Object v = values.get(k); return v == null ? d : v.toString(); }
    }
    static class Prefs { Map<String,String> data = new HashMap<>();
        String getString(String k, String d) { return data.getOrDefault(k,d); }
        Prefs edit() { return this; } Prefs putString(String k, String v) { data.put(k,v); return this; } void apply() {} }
    static Prefs p = new Prefs(); static Prefs prefs() { return p; }
    static class TLRPC {
        static class InputFile { long id; int parts; String name; }
        static class InputMedia { int flags, ttl_seconds; boolean force_file; String mime_type; InputFile file; ArrayList<DocumentAttribute> attributes = new ArrayList<>(); }
        static class TL_inputMediaUploadedDocument extends InputMedia {} static class TL_inputMediaUploadedPhoto extends InputMedia {}
        static class TL_inputMediaDocument extends InputMedia {} static class TL_inputMediaPhoto extends InputMedia {}
        static class DocumentAttribute { String file_name, title, performer; boolean voice, round_message; double duration; int w,h; byte[] waveform; }
        static class TL_documentAttributeFilename extends DocumentAttribute {} static class TL_documentAttributeAudio extends DocumentAttribute {}
        static class TL_documentAttributeVideo extends DocumentAttribute {}
    }
    static class WaysGramApi {
        static int calls; static String filename, kind; static JSONObject info;
        static class ApiException extends Exception { int status; ApiException(int status, String text) { super(text); this.status=status; } }
        static JSONObject upload(int account, String path, byte[] data, String type, String name, String mediaKind, JSONObject metadata) {
            calls++; filename=name; kind=mediaKind; info=metadata; return new JSONObject().put("id", "0123456789abcdef0123456789abcdef");
        }
    }
    static byte[] assemble(long id, int parts) { return new byte[] {79,103,103,83}; }
    static File cacheFile(String id) { return new File(id); }
    static void writeFile(File file, byte[] b) {} static void register(String id) {} static void removeParts(long id) {}
''' + methods + r'''
    static void eq(Object a, Object b) { if (!Objects.equals(a,b)) throw new AssertionError(a + " != " + b); }
    static void reject(TLRPC.InputMedia m) throws Exception {
        int before=WaysGramApi.calls;
        try { uploadFor(0,m); throw new AssertionError("TTL accepted"); } catch (WaysGramApi.ApiException e) { eq(e.status,400); }
        eq(WaysGramApi.calls,before);
    }
    public static void main(String[] args) throws Exception {
        TLRPC.TL_inputMediaUploadedDocument m = new TLRPC.TL_inputMediaUploadedDocument();
        m.file = new TLRPC.InputFile(); m.file.id=1; m.file.parts=1; m.file.name="tempfile"; m.mime_type="audio/ogg";
        TLRPC.TL_documentAttributeAudio a = new TLRPC.TL_documentAttributeAudio();
        a.voice=true; a.duration=2.75; a.waveform=new byte[] {1,2,3}; m.attributes.add(a);
        uploadFor(0,m); eq(WaysGramApi.filename,"tempfile.ogg"); eq(WaysGramApi.kind,"voice");
        eq(WaysGramApi.info.values.get("duration"),2.75); eq(WaysGramApi.info.values.get("waveform"),"AQID");
        m.ttl_seconds=Integer.MAX_VALUE; reject(m); // reject before cached upload can be reused
        m.ttl_seconds=0; m.flags=2; reject(m);
        TLRPC.TL_inputMediaDocument existing = new TLRPC.TL_inputMediaDocument(); existing.flags=1;
        try { requirePersistentMedia(existing); throw new AssertionError("existing TTL accepted"); } catch (WaysGramApi.ApiException e) { eq(e.status,400); }
        existing.flags=2; requirePersistentMedia(existing); // query flag, not TTL
        TLRPC.TL_inputMediaUploadedPhoto photo = new TLRPC.TL_inputMediaUploadedPhoto(); photo.flags=2; reject(photo);
        m.flags=0; m.file.id=2; m.file.name="recording.opus"; a.waveform=new byte[769];
        uploadFor(0,m); eq(WaysGramApi.filename,"recording.opus"); eq(WaysGramApi.info.values.containsKey("waveform"),false);
        System.out.println("Voice upload, waveform, filename, and TTL guard tests passed");
    }
}'''
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / 'TestVoice.java'
        path.write_text(java, encoding='utf-8')
        subprocess.run([os.environ['WG_JAVA'], '-jar', os.environ['WG_ECJ_JAR'], '-17', '-encoding', 'UTF-8', '-d', tmp, str(path)], check=True)
        subprocess.run([os.environ['WG_JAVA'], '-cp', tmp, 'TestVoice'], check=True)

if __name__ == '__main__': main()
