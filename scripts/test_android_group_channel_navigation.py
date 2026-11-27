#!/usr/bin/env python3
"""Статические проверки навигации по группам и каналам в собранном архиве.

Тест читает `WaysGram-Android.zip.part1…5` — тот же источник, из которого Gradle
собирает APK, — поэтому он не расходится с реальным кодом, в отличие от
`android-source/`.

Что закреплено:

* «Discussion» в карточке канала не показывает `@null`;
* дубли строк «Группа обсуждений» и «Настройки входящих канала» убраны, а штатная
  строка «Discussion» открывает тот же диалог привязки, что и раньше;
* истории работают и в группах, а не только в каналах;
* `account.getChatThemes` отвечает пустой выдачей, а не `METHOD_NOT_SUPPORTED`.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class GroupChannelNavigationChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        assert len(cls.archive.namelist()) == len(set(cls.archive.namelist()))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_json_null_never_becomes_a_username(self):
        mapper = self.source("tgnet/WaysGramMapper.java")
        chats = self.source("tgnet/WaysGramChats.java")
        self.assertIn("static String text(JSONObject json, String key)", mapper)
        self.assertIn('"null".equalsIgnoreCase(value) || "undefined".equalsIgnoreCase(value) ? "" : value', mapper)
        self.assertIn('String username = WaysGramMapper.text(c, "username");', chats)
        self.assertIn('String username = WaysGramMapper.text(g, "username");', chats)
        self.assertNotIn('String username = c.optString("username", "");', chats)
        self.assertNotIn('String username = g.optString("username", "");', chats)

    def test_linked_chat_row_navigates_from_both_sides(self):
        # Канал открывает группу обсуждений, а группа показывает/открывает свой канал.
        edit = self.source("ui/ChatEditActivity.java")
        self.assertNotIn("TextCell discussionCell = new TextCell(context);", edit)
        self.assertNotIn("TextCell inboxCell = new TextCell(context);", edit)
        self.assertIn("WaysGramDiscussions.openGroup(this, currentAccount, chatId, info.linked_chat_id)", edit)
        self.assertIn("WaysGramDiscussions.openChannel(this, info.linked_chat_id)", edit)
        self.assertIn("WaysGramDiscussions.groupSettings(this, currentAccount, chatId);", edit)
        self.assertIn("!isChannel && info.linked_chat_id == 0 && !currentChat.creator", edit)
        self.assertIn("if (isChannel) {", edit)
        self.assertIn("info != null && info.linked_chat_id != 0", edit)
        # Управление привязкой канала осталось по долгому нажатию; группа не передаёт
        # свой id в channel discussion endpoint.
        self.assertIn("linkedCell.setOnLongClickListener", edit)
        self.assertIn("WaysGramTransport.ENABLED && isChannel", edit)
        self.assertIn("WaysGramDiscussions.settings(this, currentAccount, chatId);", edit)
        self.assertIn("ChatLinkActivity fragment = new ChatLinkActivity(chatId);", edit)

    def test_group_full_profile_exposes_its_linked_channel_and_group_type(self):
        chats = self.source("tgnet/WaysGramChats.java")
        full = chats.split("public static TLRPC.TL_messages_chatFull fullChat(", 1)[1].split(
            "private static void addMember", 1)[0]
        self.assertIn("full.can_set_username = ownerId == selfId;", full)
        self.assertIn("full.linked_chat_id = channelTg(linkedChannelId);", full)
        self.assertIn("out.chats.add(toChannel(account, linkedChannel", full)
        discussions = self.source("ui/WaysGramDiscussions.java")
        self.assertIn("static void openChannel(BaseFragment fragment, long channel)", discussions)

    def test_linked_group_candidates_follow_server_member_permission(self):
        chats = self.source("tgnet/WaysGramChats.java")
        candidates = chats.split("public static TLRPC.TL_messages_chats discussionGroups(", 1)[1].split(
            "public static TLObject setDiscussionGroup(", 1)[0]
        self.assertIn('(group.optInt("default_send_restrictions") & 32768) == 0', candidates)
        self.assertIn('(group.optInt("admin_rights") & 1) != 0', candidates)

    def test_username_value_falls_back_to_chat_title(self):
        edit = self.source("ui/ChatEditActivity.java")
        self.assertIn('TextUtils.isEmpty(username = ChatObject.getPublicUsername(chat))', edit)
        self.assertIn('chat.title, R.drawable.msg_discuss', edit)

    def test_stories_work_for_groups_and_channels(self):
        stories = self.source("tgnet/WaysGramStories.java")
        self.assertIn("private static boolean isChat(TLRPC.InputPeer peer) {", stories)
        self.assertIn("peer.channel_id > 0;", stories)
        self.assertIn('return "/api/groups/" + peerId + "/stories";', stories)
        self.assertIn('return "/api/channels/" + WaysGramChats.channelServer(peerId) + "/stories";', stories)
        self.assertNotIn("isChannel(", stories)

    def test_chat_themes_answer_is_empty_not_an_error(self):
        extras = self.source("tgnet/WaysGramExtras.java")
        self.assertIn("request instanceof TL_account.getChatThemes", extras)
        self.assertIn("themes.hash = 0;", extras)
        self.assertIn("import org.telegram.tgnet.tl.TL_account;", extras)


if __name__ == "__main__":
    unittest.main()
