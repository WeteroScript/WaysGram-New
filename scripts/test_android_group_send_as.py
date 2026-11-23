#!/usr/bin/env python3
"""Статические проверки отправки от имени группы и публичных каналов.

Клиент штатно просит `channels.getSendAs` перед показом кнопки «Отправитель».
Раньше ответа не было, поэтому `SenderSelectPopup` не открывался, а выбранный
канал игнорировался при отправке. Здесь проверяется, что:
* транспорт отвечает на `channels.getSendAs` списком из `/send-identities`;
* выбранный отправитель доходит до `WaysGramChats.send` и уходит на
  `/identity-messages` с `send_as_id`;
* приватная WaysGram-группа не блокирует список (гейт `canSendAsPeers`).
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'


class GroupSendAsSourceChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        assert len(cls.archive.namelist()) == len(set(cls.archive.namelist()))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, path):
        return self.archive.read(BASE + path).decode('utf-8')

    def test_transport_answers_channels_get_send_as(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        self.assertIn('request instanceof TLRPC.TL_channels_getSendAs', transport)
        self.assertIn('return WaysGramChats.sendAsPeers(account, group, selfId);', transport)
        self.assertIn('req.for_live_stories || group <= 0 || !WaysGramChats.isGroup(req.peer)', transport)
        self.assertIn('return new TLRPC.TL_channels_sendAsPeers();', transport)

    def test_send_as_peers_use_group_send_identities(self):
        chats = self.source('tgnet/WaysGramChats.java')
        self.assertIn('public static TLRPC.TL_channels_sendAsPeers sendAsPeers(int account, long groupTgId, long selfId)',
                      chats)
        self.assertIn('"/api/groups/" + groupTgId + "/send-identities"', chats)
        self.assertIn('data.optBoolean("anonymous_allowed")', chats)
        self.assertIn('TLRPC.TL_sendAsPeer', chats)
        self.assertIn('TLRPC.TL_peerChat', chats)
        self.assertIn('TLRPC.TL_peerChannel', chats)
        self.assertIn('toChannel(item, selfId, true)', chats)

    def test_selected_identity_reaches_group_send(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        chats = self.source('tgnet/WaysGramChats.java')
        helper = transport.split('private static long sendAsIdentity', 1)[1].split('// The native composer fills', 1)[0]
        for value in ('sendAs instanceof TLRPC.TL_inputPeerChat', 'sendAs instanceof TLRPC.TL_inputPeerChannel',
                      'WaysGramChats.channelServer(sendAs.channel_id)', 'return 0;'):
            self.assertIn(value, helper)
        self.assertIn('sendAsIdentity(send.send_as, send.peer)', transport)
        self.assertIn('sendAsIdentity(send.send_as, send.peer)', transport)
        self.assertIn('JSONObject card, String expectedToken, Integer approvedFee, long identity', transport)
        self.assertIn('public static TLObject send(int account, TLRPC.InputPeer peer, String text, long randomId, '
                      'String mediaId, int replyTo, long selfId, int globalPts, boolean silent, JSONObject location, '
                      'JSONObject contact, JSONArray entities, JSONObject quote, JSONObject card, long identity)',
                      chats)
        self.assertIn('"/identity-messages"', chats)
        self.assertIn('body.put("send_as_id", identity)', chats)

    def test_private_waysgram_group_still_lists_senders(self):
        controller = self.source('messenger/MessagesController.java')
        self.assertIn('final boolean waysGramGroup = org.telegram.tgnet.WaysGramTransport.ENABLED', controller)
        self.assertIn('if (chat == null || !(waysGramGroup || ChatObject.canSendAsPeers(chat))) {', controller)
        chat = self.source('ui/ChatActivity.java')
        self.assertIn('|| org.telegram.tgnet.WaysGramTransport.ENABLED && ChatObject.isMegagroup(currentChat))', chat)

    def test_locations_and_polls_still_require_default_sender(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        self.assertIn('!usesDefaultLocationSender(send.send_as, send.peer, selfId)', transport)
        gate = transport.split('if (send.media instanceof TLRPC.TL_inputMediaGeoPoint', 1)[1].split('JSONArray captionEntities', 1)[0]
        self.assertNotIn('sendAsIdentity', gate)


if __name__ == '__main__':
    unittest.main()
