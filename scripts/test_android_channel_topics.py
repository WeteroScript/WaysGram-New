#!/usr/bin/env python3
"""Статические проверки тем канала (форум-режим).

У группы темы уже были (`WaysGramForumActivity` + `/api/groups/{id}/topics`). У канала не было
ни таблицы, ни эндпоинтов, ни экрана. Теперь тот же экран работает с канальным корнем,
`channels.toggleForum` включает режим, а штатные `TL_forum`-методы отвечают реальными темами,
а не «не поддерживается».
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'
RES = 'WaysGram-Android/TMessagesProj/src/main/res/'
SERVER = (ROOT / 'authbot' / 'messenger_api.py').read_text(encoding='utf-8')


class ChannelTopicsServerChecks(unittest.TestCase):
    def test_server_exposes_channel_topic_routes(self):
        for route in (
            '@router.get("/api/channels/{channel_id}/topics")',
            '@router.post("/api/channels/{channel_id}/topics", status_code=201)',
            '@router.patch("/api/channels/{channel_id}/topics/{topic_id}")',
            '@router.delete("/api/channels/{channel_id}/topics/{topic_id}")',
            '@router.put("/api/channels/{channel_id}/topics/{topic_id}/notifications")',
            '@router.put("/api/channels/{channel_id}/topics/{topic_id}/pin")',
            '@router.get("/api/channels/{channel_id}/topics/{topic_id}/messages")',
            '@router.post("/api/channels/{channel_id}/topics/{topic_id}/messages", status_code=201)',
        ):
            self.assertIn(route, SERVER)

    def test_channel_topics_are_stored_separately_from_the_feed(self):
        self.assertIn('CREATE TABLE IF NOT EXISTS channel_topics', SERVER)
        self.assertIn('CREATE TABLE IF NOT EXISTS channel_topic_followers', SERVER)
        self.assertIn('ADD COLUMN topic_id INTEGER', SERVER)
        self.assertIn('idx_channel_topic_messages', SERVER)
        # Пост темы не должен попадать в общую ленту канала.
        self.assertIn('WHERE channel_id=? AND topic_id IS NULL AND id>?', SERVER)
        self.assertIn('WHERE channel_id=? AND topic_id IS NULL AND (?=0 OR id<?)', SERVER)

    def test_rights_and_errors_are_explicit(self):
        for code in (
            'CHANNEL_TOPIC_MANAGE_REQUIRED',
            'CHANNEL_TOPIC_PIN_REQUIRED',
            'CHANNEL_TOPIC_POST_REQUIRED',
            'CHANNEL_TOPIC_NOT_FOUND',
            'CHANNEL_TOPIC_TITLE_EXISTS',
            'CHANNEL_TOPIC_LIMIT',
            'CHANNEL_TOPIC_CLOSED',
            'CHANNEL_FORUM_DISABLED',
            'CHANNEL_TOPIC_MESSAGE_REQUIRED',
        ):
            self.assertIn(code, SERVER)
        self.assertIn('channel["can_manage_topics"] = channel["can_set_location"]', SERVER)


class ChannelTopicsAndroidChecks(unittest.TestCase):
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

    def resource(self, name):
        return self.archive.read(RES + name + '/strings.xml').decode('utf-8')

    def test_forum_screen_is_reused_for_channels(self):
        forum = self.source('ui/WaysGramForumActivity.java')
        self.assertIn('"/api/groups/" + groupId + "/topics"', forum)
        self.assertIn('WaysGramForumActivity(int account, long groupId, long topicId, String root, boolean channel)', forum)
        self.assertIn('final boolean channel;', forum)
        self.assertIn('return channel ? R.string.WaysGramChannelTopics : R.string.WaysGramTopics;', forum)
        # Композер скрыт, если сервер не разрешает публикацию в тему канала.
        self.assertIn('channel && !data.optBoolean("can_post")', forum)
        self.assertIn('data.optBoolean("forum_enabled", true)', forum)

    def test_topics_helper_opens_channels_and_toggles_the_forum(self):
        topics = self.source('ui/WaysGramGroupTopics.java')
        self.assertIn('import org.telegram.tgnet.WaysGramChats;', topics)
        self.assertIn('static void openChannel(BaseFragment fragment, int account, long channel)', topics)
        self.assertIn('WaysGramChats.channelServer(channel)', topics)
        self.assertIn('"/api/channels/" + server + "/topics", true)', topics)
        self.assertIn('new JSONObject().put("forum_enabled", enabled)', topics)
        self.assertIn('root.substring(0, root.length() - "/topics".length())', topics)
        self.assertIn('forum.channel', topics)  # открытие вложения пишет peer канала, а не группы

    def test_entry_points_are_wired(self):
        chat = self.source('ui/ChatActivity.java')
        self.assertIn('private static final int waysgram_channel_topics = 82;', chat)
        self.assertIn('id == waysgram_channel_topics', chat)
        self.assertIn('WaysGramGroupTopics.openChannel(ChatActivity.this, currentAccount, currentChat.id)', chat)
        self.assertIn('headerItem.lazilyAddSubItem(waysgram_channel_topics, R.drawable.msg_topics', chat)
        edit = self.source('ui/ChatEditActivity.java')
        self.assertIn('channelTopicsCell.setOnClickListener(v -> WaysGramGroupTopics.openChannel(this, currentAccount, chatId))', edit)

    def test_native_forum_rpc_is_mapped(self):
        mapper = self.source('tgnet/WaysGramChannelTopics.java')
        for method in ('TL_messages_getForumTopics', 'TL_messages_getForumTopicsByID',
                       'TL_messages_createForumTopic', 'TL_messages_editForumTopic',
                       'TL_messages_deleteTopicHistory'):
            self.assertIn(method, mapper)
        self.assertIn('"/api/groups/" + channel', mapper)
        self.assertIn('WaysGramChats.channelServer(channel)', mapper)
        self.assertIn('new TLRPC.TL_peerChannel()', mapper)
        transport = self.source('tgnet/WaysGramTransport.java')
        self.assertIn('WaysGramChannelTopics.handle(account, request, expectedToken)', transport)

    def test_labels_are_localized(self):
        for name in ('values', 'values-ru'):
            strings = self.resource(name)
            self.assertIn('name="WaysGramChannelTopics"', strings)
            self.assertIn('name="WaysGramTopicsEnable"', strings)
            self.assertIn('name="WaysGramTopicsDisable"', strings)
            self.assertIn('name="WaysGramTopicsDisabled"', strings)
        self.assertIn('Темы канала', self.resource('values-ru'))
        self.assertIn('Включить темы', self.resource('values-ru'))


if __name__ == '__main__':
    unittest.main()
