#!/usr/bin/env python3
"""Истории видят все участники, а не только автор.

С устройства: «истории видны только владельцу». Две причины: сервер не отдавал общий
список историй (`stories.getAllStories`), поэтому клиент не знал ни одного пира с
историями и не рисовал кольца в списке чатов; и клиент считал каналом только id выше
`CHANNEL_OFFSET`, так что истории группы не обслуживались вовсе.
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'


class StoryVisibility(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.stories = cls.source('tgnet/WaysGramStories.java')
        cls.chats = cls.source('tgnet/WaysGramChats.java')
        cls.server = (ROOT / 'authbot' / 'messenger_api.py').read_text(encoding='utf-8')

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    @classmethod
    def source(cls, relative):
        return cls.archive.read(BASE + relative).decode('utf-8')

    @staticmethod
    def body(source, signature):
        start = source.index(signature)
        return source[start:source.index('\n    }', start)]

    def test_groups_are_story_peers_too(self):
        self.assertIn('private static boolean isChat(TLRPC.InputPeer peer)', self.stories)
        path = self.body(self.stories, 'private static String path(long peerId)')
        self.assertIn('"/api/groups/" + peerId + "/stories"', path)
        self.assertIn('"/api/channels/" + WaysGramChats.channelServer(peerId) + "/stories"', path)
        self.assertNotIn('isChannel(', self.stories)

    def test_story_requests_accept_group_peers(self):
        for handler in ('TL_stories_canSendStory', 'TL_stories_sendStory', 'TL_stories_getPeerStories',
                        'TL_stories_getStoriesByID', 'TL_stories_getPinnedStories',
                        'TL_stories_getStoriesArchive', 'TL_stories_deleteStories'):
            start = self.stories.index('instanceof TL_stories.' + handler)
            self.assertIn('isChat(', self.stories[start:start + 220], handler)

    def test_all_stories_come_from_the_server(self):
        self.assertIn('if (req instanceof TL_stories.TL_stories_getAllStories)', self.stories)
        self.assertIn('WaysGramApi.get(account, "/api/stories/all")', self.stories)
        body = self.body(self.stories, 'static TL_stories.TL_stories_allStories all(')
        self.assertIn('peerStories.peer = peer(peerId);', body)
        self.assertIn('out.chats.add(chat(account, scope, serverId, selfId));', body)
        self.assertIn('WaysGramChats.toMegagroup(account, WaysGramApi.get(account, "/api/groups/" + serverId), selfId)',
                      self.stories)

    def test_group_profile_reports_story_state(self):
        self.assertIn('full.stories = WaysGramStories.active(account, chatId, selfId);', self.chats)
        self.assertIn('result["stories_active"] = bool(story_active)', self.server)
        self.assertIn('result["stories_pinned_available"] = bool(story_pinned)', self.server)

    def test_server_lists_stories_of_every_visible_chat(self):
        self.assertIn('@router.get("/api/stories/all")', self.server)
        handler = self.server[self.server.index('async def list_all_stories('):]
        handler = handler[:handler.index('@router.post("/api/channels/{channel_id}/stories/delete")')]
        self.assertIn('STORY_CHANNEL_ACCESS', handler)
        self.assertIn('JOIN group_members gm ON gm.group_id=g.id', handler)
        self.assertIn('s.expires_at>? OR s.pinned=1', handler)


if __name__ == '__main__':
    unittest.main()
