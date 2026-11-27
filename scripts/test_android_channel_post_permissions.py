#!/usr/bin/env python3
"""Broadcast-channel posting is denied in both the UI and native request paths."""
from __future__ import annotations

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class ChannelPostPermissions(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        archive_data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{index}").read_bytes()
            for index in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(archive_data))
        cls.chat_activity = cls.read("ui/ChatActivity.java")
        cls.chat_object = cls.read("messenger/ChatObject.java")
        cls.chats = cls.read("tgnet/WaysGramChats.java")
        cls.messages = cls.read("tgnet/WaysGramMessages.java")

    @classmethod
    def tearDownClass(cls) -> None:
        cls.archive.close()

    @classmethod
    def read(cls, relative_path: str) -> str:
        return cls.archive.read(BASE + relative_path).decode("utf-8")

    @classmethod
    def method(cls, source: str, signature: str, next_signature: str) -> str:
        start = source.index(signature)
        end = source.index(next_signature, start)
        return source[start:end]

    def test_no_post_right_hides_the_composer_even_after_other_overlay_branches(self) -> None:
        body = self.method(
            self.chat_activity,
            "public void updateBottomOverlay(boolean animated)",
            "private boolean shouldDisplaySwipeToLeftToReplyInForum()",
        )
        self.assertIn("boolean channelPostForbidden = currentChat != null", body)
        self.assertIn("ChatObject.isChannel(currentChat)", body)
        self.assertIn("&& !currentChat.megagroup", body)
        self.assertIn("&& !ChatObject.canPost(currentChat)", body)
        self.assertIn("!(ChatObject.isMonoForum(currentChat) && isSubscriberSuggestions)", body)
        self.assertIn("!chatActivityEnterView.isEditingMessage()", body)
        self.assertIn("chatActivityEnterView.setVisibility(View.INVISIBLE);", body)
        self.assertIn("chatActivityEnterView.closeKeyboard();", body)

    def test_post_permission_uses_the_dedicated_post_messages_right(self) -> None:
        self.assertIn("public static boolean canPost(TLRPC.Chat chat)", self.chat_object)
        self.assertIn("return canUserDoAction(chat, ACTION_POST);", self.chat_object)
        self.assertIn("value = admin_rights.post_messages;", self.chat_object)
        channel = self.method(
            self.chats,
            "public static TLRPC.TL_channel toChannel(",
            "public static TLRPC.TL_message groupMessage(",
        )
        self.assertIn("channel.broadcast = true;", channel)
        self.assertIn("rights.post_messages = (mask & 1) != 0;", self.chats)
        self.assertIn('int mask = c.optInt("admin_rights", 0);', channel)

    def test_native_text_and_album_send_paths_check_rights_before_request(self) -> None:
        required = self.method(
            self.chats,
            "public static void requireChannelPostRight(",
            "public static TLObject send(",
        )
        self.assertIn("chat.creator", required)
        self.assertIn("chat.admin_rights.post_messages", required)
        self.assertIn('"CHAT_WRITE_FORBIDDEN"', required)
        send = self.method(
            self.chats,
            "public static TLObject send(int account, TLRPC.InputPeer peer, String text, long randomId, String mediaId, int replyTo",
            "// ---------------------------------------------------------------- group management",
        )
        self.assertIn("requireChannelPostRight(account, tg);", send)
        album = self.method(
            self.messages,
            "public static TLObject sendAlbum(",
            "// ---------------------------------------------------------------- editing",
        )
        self.assertIn("requireChannelPostRight(account, WaysGramChats.channelTg(peerId));", album)


if __name__ == "__main__":
    unittest.main()
