#!/usr/bin/env python3
"""Static checks for unsupported live-location safety, not an Android/device test."""
import io
from pathlib import Path
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'

class LiveLocationGuardChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.z = zipfile.ZipFile(io.BytesIO(b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1,6))))
    @classmethod
    def tearDownClass(cls): cls.z.close()
    def source(self, path): return self.z.read(BASE + path).decode()
    def test_send_and_edit_reject_live_before_network(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        send = transport[transport.index('request instanceof TLRPC.TL_messages_sendMedia'):]
        send = send[:send.index('request instanceof TLRPC.TL_messages_uploadMedia')]
        self.assertLess(send.index('send.media instanceof TLRPC.TL_inputMediaGeoLive'), send.index('WaysGramMedia.uploadFor'))
        edit = self.source('tgnet/WaysGramMessages.java').split('public static TLObject edit(',1)[1].split('// ---------------------------------------------------------------- deleting',1)[0]
        self.assertLess(edit.index('req.media instanceof TLRPC.TL_inputMediaGeoLive'), edit.index('WaysGramApi.put('))
        self.assertIn('Трансляция геолокации пока не поддерживается', send)
    def test_failed_recent_locations_can_retry(self):
        controller = self.source('messenger/LocationController.java')
        load = controller.split('public void loadLiveLocations(long did)',1)[1].split('public void markLiveLoactionsAsRead',1)[0]
        error = load.split('if (error != null)',1)[1].split('return;',1)[0]
        self.assertIn('AndroidUtilities.runOnUIThread(() -> cacheRequests.delete(did))', error)

if __name__ == '__main__': unittest.main()
