#!/usr/bin/env python3
"""Static checks for the native WaysGram bridge, not a compiled/device test."""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'


class NativeMessagingSourceChecks(unittest.TestCase):
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

    def test_received_and_cached_plain_messages_are_linkified(self):
        obj = self.source('messenger/MessageObject.java')
        mapper = self.source('tgnet/WaysGramMapper.java')
        self.assertIn('WaysGramMapper.applyTextEntities(messageOwner)', obj)
        self.assertIn('messageOwner.send_state == MESSAGE_SEND_STATE_SENT', obj)
        self.assertIn('!message.entities.isEmpty()', mapper)
        self.assertIn('new TLRPC.TL_messageEntityMention()', mapper)
        self.assertIn('new TLRPC.TL_messageEntityHashtag()', mapper)
        self.assertIn('new TLRPC.TL_messageEntityUrl()', mapper)
        self.assertIn('entity.offset = matcher.start()', mapper)
        self.assertIn('message.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES', mapper)

    def test_group_and_channel_load_old_pages(self):
        chats = self.source('tgnet/WaysGramChats.java')
        self.assertIn('serverGroupId(req.offset_id)', chats)
        self.assertIn('serverGroupId(req.min_id)', chats)
        self.assertIn('"&before_id=" + before', chats)
        self.assertIn('"&after_id=" + after', chats)
        self.assertIn('"/api/groups/" + id + "/messages" + paging', chats)
        self.assertIn('"/api/channels/" + serverId + "/messages" + paging', chats)

    def test_static_location_has_native_send_and_receive_bridge(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        media = self.source('tgnet/WaysGramMedia.java')
        self.assertIn('send.media instanceof TLRPC.TL_inputMediaGeoPoint', transport)
        self.assertIn('body.put("location", location)', transport)
        chats = self.source('tgnet/WaysGramChats.java')
        self.assertIn('body.put("location", location)', chats)
        self.assertIn('TLRPC.TL_inputPeerChat || send.peer instanceof TLRPC.TL_inputPeerChannel', transport)
        self.assertIn('WaysGramApi.postIdempotent(account, "/api/groups/"', chats)
        self.assertIn('Long.toString(randomId).getBytes(java.nio.charset.StandardCharsets.UTF_8)', chats)
        self.assertIn('send.schedule_date != 0', transport)
        self.assertIn('TLRPC.TL_inputPeerSelf', transport)
        self.assertIn('json.optDouble("location_latitude"', media)
        self.assertIn('json.optJSONObject("location")', media)
        self.assertIn('new TLRPC.TL_messageMediaGeo()', media)
        self.assertIn('new TLRPC.TL_messageMediaVenue()', media)

    def test_native_text_edit_uses_supported_put_and_shows_actual_error(self):
        api = self.source('tgnet/WaysGramApi.java')
        bridge = self.source('tgnet/WaysGramMessages.java')
        self.assertIn('return request("PUT", path, body, getToken(account), 30000)', api)
        start = bridge.index('public static TLObject edit(')
        end = bridge.index('// ---------------------------------------------------------------- deleting', start)
        edit = bridge[start:end]
        for route in ('/api/messages/', '/api/groups/', '/api/channels/'):
            self.assertIn('WaysGramApi.put(account, "' + route, edit)
        self.assertNotIn('WaysGramApi.patch(', edit)
        self.assertIn('req.peer instanceof TLRPC.TL_inputPeerSelf', edit)
        helper = self.source('messenger/SendMessagesHelper.java')
        start = helper.index('public int editMessage(MessageObject messageObject, String message,')
        end = helper.index('public int editRichMessage(', start)
        self.assertIn('AlertsCreator.showSimpleAlert(fragment, detail)', helper[start:end])

    def test_native_forward_and_private_delete_use_server(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        messages = self.source('tgnet/WaysGramMessages.java')
        poller = self.source('tgnet/WaysGramPoller.java')
        self.assertIn('request instanceof TLRPC.TL_messages_forwardMessages', transport)
        self.assertIn('WaysGramMessages.forward(', transport)
        self.assertIn('WaysGramApi.postIdempotent(account, "/api/forward"', messages)
        self.assertIn('req.id.size() > 20', messages)
        self.assertIn('req.id.size() != req.random_id.size()', messages)
        self.assertIn('WaysGramChats.serverGroupId(id)', messages)
        self.assertIn('req.drop_author', messages)
        self.assertIn('req.drop_media_captions', messages)
        self.assertIn('req.silent', messages)
        self.assertIn('TL_update.TL_updateNewChannelMessage', messages)
        self.assertIn('TL_update.TL_updateMessageID', messages)
        helper = self.source('messenger/SendMessagesHelper.java')
        self.assertIn('req.from_peer = getMessagesController().getInputPeer(msgObj.getDialogId())', helper)
        self.assertIn('WaysGramTransport.ENABLED ? 20 : 100', helper)
        self.assertIn('req.peer instanceof TLRPC.TL_inputPeerSelf', messages)
        mapper = self.source('tgnet/WaysGramMapper.java')
        self.assertIn('forwarded_from_date', mapper)
        self.assertIn('message.grouped_id = Long.parseLong', mapper)
        self.assertIn('req.revoke ? "?for=all" : "?for=me"', messages)
        self.assertIn('kind.equals("delete_for_me")', poller)
        self.assertIn('forwarded_from_telegram_id', self.source('tgnet/WaysGramMapper.java'))


    def test_single_sends_keep_silent_and_reject_unsupported_schedule(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        chats = self.source('tgnet/WaysGramChats.java')
        text = transport[transport.index('request instanceof TLRPC.TL_messages_sendMessage'):transport.index('request instanceof TLRPC.TL_messages_sendMedia')]
        media = transport[transport.index('request instanceof TLRPC.TL_messages_sendMedia'):transport.index('request instanceof TLRPC.TL_messages_sendMultiMedia')]
        for section in (text, media):
            self.assertIn('send.schedule_date != 0 || send.schedule_repeat_period != 0', section)
            self.assertIn('SCHEDULE_NOT_SUPPORTED', section)
            self.assertIn('send.silent', section)
        self.assertLess(media.index('SCHEDULE_NOT_SUPPORTED'), media.index('WaysGramMedia.uploadFor'))
        direct = transport[transport.index('private static TLObject sendTo('):transport.index('private static TLRPC.TL_error mapError')]
        self.assertIn('nextPts(account), silent, location)', direct)
        self.assertIn('body.put("silent", silent)', direct)
        self.assertGreater(direct.index('body.put("silent", silent)'), direct.index('body.put("location", location)'))
        group = chats[chats.index('public static TLObject send('):chats.index('// ----------------------------------------------------------------', chats.index('public static TLObject send('))]
        self.assertIn('boolean silent', group)
        self.assertIn('body.put("silent", silent)', group)
        album = self.source('tgnet/WaysGramMessages.java')
        self.assertIn('body.put("silent", req.silent)', album)

    def test_private_clear_history_is_bounded_and_events_are_synchronized(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        messages = self.source('tgnet/WaysGramMessages.java')
        poller = self.source('tgnet/WaysGramPoller.java')
        self.assertIn('request instanceof TLRPC.TL_messages_deleteHistory', transport)
        self.assertIn('WaysGramMessages.clearHistory(', transport)
        clear = messages[messages.index('public static TLObject clearHistory('):messages.index('public static TLObject delete(', messages.index('public static TLObject clearHistory('))]
        for value in ('TL_inputPeerUser', 'TL_inputPeerSelf', 'req.max_id <= 0',
                      'req.min_date != 0', 'req.max_date != 0', 'req.revoke',
                      '"/api/conversations/"', '"&max_id=" + req.max_id',
                      'TL_messages_affectedHistory', 'result.offset = 0'):
            self.assertIn(value, clear)
        self.assertIn('kind.equals("delete_for_all_sync")', poller)
        self.assertIn('kind.equals("delete_for_me")', poller)
        self.assertIn('u.messages.add(messageId)', poller)

    def test_native_private_contact_round_trip_and_scope_guards(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        media = self.source('tgnet/WaysGramMedia.java')
        self.assertIn('send.media instanceof TLRPC.TL_inputMediaContact', transport)
        self.assertIn('media.first_name', transport)
        self.assertIn('media.last_name', transport)
        self.assertIn('media.phone_number', transport)
        self.assertIn('media.vcard', transport)
        self.assertIn('CONTACT_NOT_SUPPORTED', transport)
        self.assertIn('body.put("contact", contact)', transport)
        self.assertIn('location != null || contact != null', transport)
        self.assertIn('new TLRPC.TL_messageMediaContact()', media)
        for name in ('contact_name', 'contact_first_name', 'contact_last_name', 'contact_phone', 'contact_vcard'):
            self.assertIn(name, media)
        self.assertIn('media.user_id = 0', media)
        self.assertIn('message.flags |= TLObject.FLAG_9', media)

    def test_native_recordings_and_album_bridges(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        media = self.source('tgnet/WaysGramMedia.java')
        messages = self.source('tgnet/WaysGramMessages.java')
        api = self.source('tgnet/WaysGramApi.java')
        for value in ('TL_messages_uploadMedia', 'TL_messages_sendMultiMedia', 'WaysGramMessages.sendAlbum'):
            self.assertIn(value, transport)
        for value in ('TL_documentAttributeAudio', 'TL_documentAttributeVideo', 'attr.round_message',
                      'attr.voice', 'audio.waveform', 'video.round_message', 'MediaMetadataRetriever',
                      'media_metadata', 'existingMediaId', 'removeParts(media.file.id)'):
            self.assertIn(value, media)
        self.assertIn('X-Media-Kind', api)
        self.assertIn('X-Media-Metadata', api)
        album = messages[messages.index('public static TLObject sendAlbum'):messages.index('// ---------------------------------------------------------------- editing')]
        for value in ('/api/messages/album', '/messages/album', 'postIdempotent', '"captions"',
                      'TL_updateMessageID', 'TL_updateNewMessage', 'TL_updateNewChannelMessage',
                      'clientGroupId', 'markDelivered', 'existingMediaId'):
            self.assertIn(value, album)


if __name__ == '__main__':
    unittest.main()
