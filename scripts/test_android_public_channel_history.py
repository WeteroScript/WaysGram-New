#!/usr/bin/env python3
"""Regression checks for opening public-channel history without Telegram channel pts.

WaysGram exposes channel history through its own HTTP endpoint, but it does not provide
Telegram's updates.getChannelDifference state. A non-member opening a public channel must
therefore skip the native short-poll gate and start firstLoadMessages immediately.
"""

import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class PublicChannelHistoryChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join(
            (ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6)
        )
        cls.archive = zipfile.ZipFile(io.BytesIO(data))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_public_nonmember_does_not_wait_for_telegram_difference(self):
        chat = self.source("ui/ChatActivity.java")
        start = chat.index("if (ChatObject.isNotInChat(currentChat)")
        end = chat.index("} else if (userId != 0)", start)
        channel_gate = chat[start:end]
        marker = (
            "if (org.telegram.tgnet.WaysGramTransport.ENABLED "
            "&& !TextUtils.isEmpty(currentChat.username))"
        )
        self.assertIn(marker, channel_gate)
        waysgram = channel_gate.split(marker, 1)[1].split("} else {", 1)[0]
        self.assertIn("waitingForGetDifference = false;", waysgram)
        self.assertNotIn("startShortPoll", waysgram)

        load_start = chat.index("        loadInfo = false;", end)
        load_end = chat.index("        if (chatMode == 0)", load_start)
        initial_load = chat[load_start:load_end]
        self.assertIn(
            "if (!waitingForGetDifference) {\n                firstLoadMessages();\n            }",
            initial_load,
        )

    def test_chat_history_uses_waysgram_http_endpoint_for_channels(self):
        chats = self.source("tgnet/WaysGramChats.java")
        start = chats.index("public static TLRPC.TL_messages_messages history(")
        end = chats.index("// ---------------------------------------------------------------- send", start)
        history = chats[start:end]
        self.assertIn('"/api/channels/" + serverId + "/messages" + paging', history)
        self.assertIn("channelMessage(m, tg, selfId)", history)
        self.assertIn("WaysGramMedia.attach(account, channelMessage", history)


if __name__ == "__main__":
    unittest.main()
