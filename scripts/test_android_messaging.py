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

    def test_local_reaction_picker_and_bubble_fallback(self):
        import xml.etree.ElementTree as ET
        chat = self.source('ui/ChatActivity.java')
        bubble = self.source('ui/Components/Reactions/ReactionsLayoutInBubble.java')
        start = chat.index('    private static final String[] WAYSGRAM_REACTIONS')
        end = chat.index('    public void selectReaction(', start)
        picker = chat[start:end]
        for value in ('👍', '❤️', '😂', '😮', '😢', '🎉', '🙏'):
            self.assertIn('"' + value + '"', picker)
        for guard in ('WaysGramTransport.ENABLED', 'chatMode == MODE_DEFAULT', '!isThreadChat()',
                      '!isSecretChat()', '!message.messageOwner.isThreadMessage',
                      'message.getId() > 0', 'message.isReactionsAvailable()',
                      '!message.isSecretMedia()', 'getMessagesController().isFrozen()',
                      'ChatObject.ACTION_SEND_REACTIONS', 'chosen.size() >= 3',
                      'reaction.documentId != 0', 'waysGramReactionRequests.contains(key)',
                      'getSendMessagesHelper().sendReaction(message, chosen',
                      'waysGramReactionRequests.remove(key)'):
            self.assertIn(guard, picker)
        self.assertIn('options.isEmpty() && optionsView == null && !waysGramReactionMenu', chat)
        self.assertIn('showWaysGramReactionPicker(primaryMessage)', chat)
        self.assertIn('toggleWaysGramReaction(primaryMessage, visibleReaction.emojicon)', chat)
        self.assertIn('isReactionsViewAvailable = !org.telegram.tgnet.WaysGramTransport.ENABLED', chat)
        self.assertIn('org.telegram.tgnet.WaysGramTransport.ENABLED || suggestEdit || isEphemeral', chat)
        self.assertIn('imageReceiver.setImageBitmap(Emoji.getEmojiDrawable(visibleReaction.emojicon))', bubble)
        keys = {'WaysGramReactionSelected', 'WaysGramReactionNotSelected', 'WaysGramReactionPending',
                'WaysGramReactionUnsupported', 'WaysGramReactionLimit'}
        for language in ('values', 'values-ru'):
            path = f'WaysGram-Android/TMessagesProj/src/main/res/{language}/waysgram_reactions.xml'
            xml = ET.fromstring(self.archive.read(path))
            self.assertEqual({entry.attrib['name'] for entry in xml}, keys)
            self.assertTrue(all(entry.text for entry in xml))

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

    def test_format_entities_bridge_single_messages(self):
        mapper = self.source('tgnet/WaysGramMapper.java')
        transport = self.source('tgnet/WaysGramTransport.java')
        chats = self.source('tgnet/WaysGramChats.java')
        messages = self.source('tgnet/WaysGramMessages.java')
        media = self.source('tgnet/WaysGramMedia.java')
        helper = self.source('messenger/SendMessagesHelper.java')
        for entity, kind in (('Bold', 'bold'), ('Italic', 'italic'), ('Underline', 'underline'),
                             ('Strike', 'strike'), ('Spoiler', 'spoiler'), ('Code', 'code'),
                             ('Pre', 'pre'), ('TextUrl', 'text_url'), ('Url', 'url'),
                             ('Mention', 'mention'), ('Hashtag', 'hashtag')):
            self.assertIn('instanceof TLRPC.TL_messageEntity' + entity, mapper)
            self.assertIn('case "' + kind + '": entity = new TLRPC.TL_messageEntity' + entity, mapper)
        for value in ('FORMATTING_NOT_SUPPORTED', 'entity.offset', 'entity.length',
                      'item.put("language"', 'item.put("url"', 'Character.isLowSurrogate',
                      'length > message.message.length() - offset', 'readTextEntities(message, json)',
                      'existing instanceof TLRPC.TL_messageEntityCode', 'if (blocked) continue;'):
            self.assertIn(value, mapper)
        self.assertIn('WaysGramMapper.writeTextEntities(send.entities)', transport)
        self.assertLess(transport.index('JSONArray captionEntities = WaysGramMapper.writeTextEntities'),
                        transport.index('WaysGramMedia.uploadFor(account, send.media)'))
        self.assertIn('WaysGramMapper.readTextEntities(formatted, result)', transport)
        self.assertIn('sent.entities = formatted.entities', transport)
        self.assertIn('sent.flags |= TLObject.FLAG_7', transport)
        for section in (transport, chats):
            self.assertIn('body.put("entities", entities)', section)
        self.assertIn('body.put("entities", WaysGramMapper.writeTextEntities(req.entities))', messages)
        self.assertIn('WaysGramMapper.hasFormatting(item.entities)', messages)
        self.assertIn('request.message = message[0].toString()', helper)
        self.assertIn('json.optJSONArray("entities").length() == 0', media)

    def test_typing_bridge_is_scoped_serial_and_ephemeral(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        poller = self.source('tgnet/WaysGramPoller.java')
        typing = self.source('tgnet/WaysGramTyping.java')
        controller = self.source('messenger/MessagesController.java')
        self.assertIn('request instanceof TLRPC.TL_messages_setTyping', transport)
        self.assertIn('typingRequest ? typingQueue(account)', transport)
        self.assertIn('newSingleThreadExecutor', transport)
        self.assertIn('SystemClock.elapsedRealtime() - queuedAt > 8000', transport)
        self.assertIn('!TextUtils.equals(reactionToken, WaysGramApi.getToken(account))', transport)
        self.assertIn('WaysGramTyping.start(account)', poller)
        self.assertIn('WaysGramTyping.stop(account)', poller)
        self.assertIn('!org.telegram.tgnet.WaysGramTransport.ENABLED && user.status != null', controller)
        for value in ('TL_inputPeerUser', 'TL_inputPeerChat', 'req.top_msg_id != 0',
                      'TL_sendMessageTypingAction', 'TL_sendMessageCancelAction', 'WaysGramApi.request("PUT", "/api/typing", body, token, 30000)',
                      '/api/typing/active?limit=500', 'MAX_PAGES = 40', 'next_cursor', 'after_scope',
                      'generation[account] != expectedGeneration', 'TextUtils.equals(token, WaysGramApi.getToken(account))',
                      'TL_updateUserTyping', 'TL_updateChatUserTyping', 'remaining > 8',
                      'item.deadline <= SystemClock.elapsedRealtime()', 'if (complete)',
                      'if (controller.getUser(item.user) == null)', 'newSingleThreadScheduledExecutor'):
            self.assertIn(value, typing)
        self.assertNotIn('TL_inputPeerChannel)', typing)

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

    def test_waysgram_address_search_uses_device_geocoder_without_bot(self):
        adapter = self.source('ui/Adapters/BaseLocationAdapter.java')
        search = adapter[adapter.index('    private void searchWaysGramAddresses('):
                         adapter.index('    protected void update(boolean animated)')]
        self.assertIn('Geocoder.isPresent()', search)
        self.assertIn('Utilities.globalQueue.postRunnable', search)
        self.assertIn('geocoder.getFromLocationName(query, 5)', search)
        self.assertIn('generation == waysGramSearchGeneration', search)
        self.assertIn('generation != waysGramSearchGeneration', search)
        self.assertIn('AndroidUtilities.runOnUIThread', search)
        self.assertIn('locations.addAll(found)', search)
        self.assertIn('venue.provider = "android-geocoder"', search)
        self.assertIn('venue.icon = "pin"', search)
        self.assertNotIn('TL_messages_getInlineBotResults', search)
        start = adapter.index('    public void searchPlacesWithQuery(final String query, final Location coordinate, boolean searchUser, boolean animated)')
        end = adapter.index('        TLObject object =', start)
        entry = adapter[start:end]
        self.assertIn('WaysGramTransport.ENABLED && !stories && !biz', entry)
        self.assertLess(entry.index('searchWaysGramAddresses('), entry.index('return;'))
        self.assertIn('Utilities.searchQueue.cancelRunnable(searchRunnable)', adapter)
        self.assertIn('codePointCount', search)

    def test_address_search_handles_permission_and_provider_failures(self):
        adapter = self.source('ui/Adapters/BaseLocationAdapter.java')
        results = self.source('ui/Adapters/LocationActivitySearchAdapter.java')
        attach = self.source('ui/Components/ChatAttachAlertLocationLayout.java')
        activity = self.source('ui/LocationActivity.java')
        delayed = adapter[adapter.index('    public void searchDelayed('):adapter.index('    private void searchBotUser(')]
        self.assertIn('places.clear()', delayed)
        self.assertIn('locations.clear()', delayed)
        self.assertLess(delayed.index('locations.clear()'), delayed.index('searchWaysGramAddresses(query.trim(), generation)'))
        self.assertIn('query.codePointCount(0, query.length()) > 200', adapter)
        self.assertIn('WG_SEARCH_UNAVAILABLE', adapter)
        self.assertIn('WG_SEARCH_TOO_LONG', adapter)
        self.assertIn('generation != waysGramSearchGeneration) return', adapter)
        self.assertIn('!myLocationDenied || org.telegram.tgnet.WaysGramTransport.ENABLED && !stories && !biz && isSearching()', results)
        self.assertEqual(attach.count('locationDenied && !parentAlert.isStoryLocationPicker && !org.telegram.tgnet.WaysGramTransport.ENABLED'), 2)
        for screen in (attach, activity):
            self.assertIn('searchAdapter.getWaysGramSearchError()', screen)
            self.assertIn('R.string.WaysGramAddressSearchUnavailable', screen)
            self.assertIn('R.string.WaysGramAddressSearchTooLong', screen)
        import xml.etree.ElementTree as ET
        keys = {'WaysGramAddressSearchError', 'WaysGramAddressSearchUnavailable', 'WaysGramAddressSearchTooLong'}
        for language in ('values', 'values-ru'):
            path = f'WaysGram-Android/TMessagesProj/src/main/res/{language}/waysgram_location.xml'
            xml = ET.fromstring(self.archive.read(path))
            self.assertEqual({entry.attrib['name'] for entry in xml}, keys)
            self.assertTrue(all(entry.text for entry in xml))

    def test_venue_send_receive_and_metadata(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        media = self.source('tgnet/WaysGramMedia.java')
        utils = self.source('messenger/utils/tlutils/TlUtils.java')
        for value in ('send.media instanceof TLRPC.TL_inputMediaVenue', 'location.put("label", place.title',
                      'venue.put("address", place.address', 'venue.put("provider", place.provider',
                      'venue.put("venue_id", place.venue_id', 'venue.put("venue_type", place.venue_type',
                      'location.put("venue", venue)'):
            self.assertIn(value, transport)
        for value in ('location.optJSONObject("venue")', 'json.optString("location_venue_json"',
                      'details.optString("address"', 'details.optString("provider"',
                      'details.optString("venue_id"', 'details.optString("venue_type"'):
            self.assertIn(value, media)
        self.assertIn('WaysGramTransport.ENABLED && location.venue_type != null ? location.venue_type', utils)
        self.assertIn('TlUtils.toInputMediaGeo(location)', self.source('messenger/SendMessagesHelper.java'))

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
        self.assertIn('nextPts(account), silent, location, contact, entities)', direct)
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

    def test_native_contact_round_trip_and_scope_guards(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        media = self.source('tgnet/WaysGramMedia.java')
        self.assertIn('send.media instanceof TLRPC.TL_inputMediaContact', transport)
        self.assertIn('media.first_name', transport)
        self.assertIn('media.last_name', transport)
        self.assertIn('media.phone_number', transport)
        self.assertIn('media.vcard', transport)
        self.assertIn('CONTACT_NOT_SUPPORTED', transport)
        self.assertIn('body.put("contact", contact)', transport)
        chats = self.source('tgnet/WaysGramChats.java')
        self.assertIn('body.put("contact", contact)', chats)
        self.assertIn('location != null || contact != null', transport)
        self.assertIn('new TLRPC.TL_messageMediaContact()', media)
        for name in ('contact_name', 'contact_first_name', 'contact_last_name', 'contact_phone', 'contact_vcard'):
            self.assertIn(name, media)
        self.assertIn('media.user_id = 0', media)
        self.assertIn('message.flags |= TLObject.FLAG_9', media)

    def test_existing_reactions_map_to_history_and_updates(self):
        mapper = self.source('tgnet/WaysGramMapper.java')
        media = self.source('tgnet/WaysGramMedia.java')
        poller = self.source('tgnet/WaysGramPoller.java')
        messages = self.source('tgnet/WaysGramMessages.java')
        for value in ('json.optString("reaction_summary", "")', 'json.optJSONArray("my_reactions")',
                      'TLRPC.TL_reactionEmoji', 'TLRPC.TL_reactionCount', 'result.chosen = true',
                      'result.chosen_order = i', 'message.flags |= TLObject.FLAG_20'):
            self.assertIn(value, mapper)
        self.assertIn('WaysGramMapper.applyExtras(message, json', media)
        self.assertEqual(poller.count('kind.equals("reaction")'), 4)
        for value in ('e.optLong("user_a")', 'e.optLong("user_b")',
                      'WaysGramMapper.peerUser(other)', 'fetchPrivate(account, other, messageId, selfId)',
                      'fetchGroup(account, groupId, messageId, selfId)',
                      'fetchChannel(account, serverId, messageId, selfId)',
                      'TL_update.TL_updateMessageReactions', 'new TLRPC.TL_messageReactions()',
                      'update.updateUnreadState = false', 'update.msg_id = message.id'):
            self.assertIn(value, poller)
        self.assertIn('"&before_id=" + ((long) id + 1)', messages)
        self.assertIn('"/messages?limit=1&before_id=" + ((long) serverId + 1)', messages)

    def test_reaction_write_replaces_set_and_restores_after_error(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        bridge = self.source('tgnet/WaysGramMessages.java')
        mapper = self.source('tgnet/WaysGramMapper.java')
        helper = self.source('messenger/SendMessagesHelper.java')
        for value in ('TL_messages_sendReaction', 'TL_messages_getMessagesReactions',
                      'WaysGramMessages.setReactions', 'WaysGramMessages.refreshReactions',
                      'newSingleThreadExecutor', 'reactionToken', 'TextUtils.equals(reactionToken'):
            self.assertIn(value, transport)
        for value in ('WaysGramChats.serverGroupId(id)', 'WaysGramChats.channelServer(req.peer.channel_id)',
                      'body.put("peer_id", peer.user_id)', 'body.put("reactions", selected)',
                      'WaysGramApi.put(account, path, body)', 'REACTION_NOT_SUPPORTED',
                      'new TLRPC.TL_messageReactions()', 'REACTIONS_BATCH_NOT_SUPPORTED'):
            self.assertIn(value, bridge)
        self.assertIn('json.optJSONArray("reactions")', mapper)
        start = helper.index('    public void sendReaction(')
        end = helper.index('    public void requestUrlAuth(', start)
        for value in ('TL_messages_getMessagesReactions', 'refresh.id.add(req.msg_id)',
                      'getMessagesController().processUpdates((TLRPC.Updates) state, false)',
                      'AlertsCreator.showSimpleAlert(parentFragment', 'callback.run()'):
            self.assertIn(value, helper[start:end])

    def test_uncompressed_image_documents_stay_documents(self):
        media = self.source('tgnet/WaysGramMedia.java')
        self.assertIn('!forceDocument && !"document".equals(kind)', media)
        self.assertIn('json.optString("media_kind", "")', media)
        self.assertIn('buildMedia(account, mediaId, mime, name, kind, metadata, false)', media)
        self.assertIn('input instanceof TLRPC.TL_inputMediaUploadedDocument', media)

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
