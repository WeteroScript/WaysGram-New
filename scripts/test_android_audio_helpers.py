#!/usr/bin/env python3
"""Compile and exercise the real audio helpers extracted from the Android source archive.

Requires WG_JAVA and WG_ECJ_JAR. This is not a full Android build or device test.
"""
import io
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NAME = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramMedia.java'

def method(source, signature):
    start = source.rfind('\n', 0, source.index('static ' + signature)) + 1
    brace = source.index('{', start)
    depth = 1
    pos = brace + 1
    while depth:
        if source[pos] == '{': depth += 1
        if source[pos] == '}': depth -= 1
        pos += 1
    return source[start:pos]

def main():
    archive = io.BytesIO(b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6)))
    with zipfile.ZipFile(archive) as z:
        source = z.read(NAME).decode()
    assert 'supportedAudioMime(media.mime_type)) kind = "audio"' in source
    assert 'audioUploadName(uploadName, originalName, media.mime_type)' in source
    assert 'safeAudioMetadata(attr.title)' in source
    methods = '\n'.join(method(source, name) for name in (
        'boolean isAudioFilename', 'String audioUploadName', 'boolean supportedAudioMime', 'String safeAudioMetadata'))
    java = '''public class TestAudio {
    static class TextUtils { static boolean isEmpty(String s) { return s == null || s.isEmpty(); } }
''' + methods + '''
    static void eq(Object got, Object expected) { if (!expected.equals(got)) throw new AssertionError(got + " != " + expected); }
    public static void main(String[] args) {
        eq(audioUploadName("temp", "Песня.MP3", "audio/mpeg"), "Песня.MP3");
        eq(audioUploadName("cache.opus", "noext", "audio/opus"), "cache.opus");
        eq(audioUploadName("temp", "noext", "audio/m4a"), "noext.m4a");
        eq(audioUploadName(null, null, "audio/x-wav"), "audio.wav");
        eq(audioUploadName("temp", null, "audio/unknown"), "temp");
        eq(isAudioFilename("track.FLAC"), true);
        eq(isAudioFilename("track.mp3.exe"), false);
        eq(supportedAudioMime("audio/M4A"), true);
        eq(supportedAudioMime("video/mp4"), false);
        eq(safeAudioMetadata("Hi\\n🎵"), "Hi🎵");
        eq(safeAudioMetadata("🎵".repeat(161)).codePointCount(0, safeAudioMetadata("🎵".repeat(161)).length()), 160);
        System.out.println("Audio filename and metadata helper tests passed");
    }
}'''
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / 'TestAudio.java'
        path.write_text(java, encoding='utf-8')
        subprocess.run([os.environ['WG_JAVA'], '-jar', os.environ['WG_ECJ_JAR'], '-17', '-encoding', 'UTF-8', '-d', tmp, str(path)], check=True)
        subprocess.run([os.environ['WG_JAVA'], '-cp', tmp, 'TestAudio'], check=True)

if __name__ == '__main__': main()
