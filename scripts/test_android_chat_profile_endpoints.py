#!/usr/bin/env python3
"""Статические проверки экрана профиля группы/канала в Android-исходниках.

Проверяются методы, которые до этого не разбирались транспортом и давали
«не поддерживается»: присутствие участников, общие группы, настройки собеседника,
жалоба на чат и список приглашений администраторов.
"""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'


class ChatProfileSourceChecks(unittest.TestCase):
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

    def test_presence_uses_group_membership_endpoint(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        chats = self.source('tgnet/WaysGramChats.java')
        self.assertIn('request instanceof TLRPC.TL_messages_getOnlines', transport)
        self.assertIn('WaysGramChats.onlines(account, onlinesChat)', transport)
        self.assertIn('"/api/groups/" + chatId + "/onlines"', chats)
        self.assertIn('TLRPC.TL_chatOnlines', chats)

    def test_common_chats_are_mapped_from_server(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        chats = self.source('tgnet/WaysGramChats.java')
        self.assertIn('request instanceof TLRPC.TL_messages_getCommonChats', transport)
        self.assertIn('WaysGramChats.commonChats(', transport)
        self.assertIn('"/api/users/" + targetId + "/common-chats"', chats)
        self.assertIn('out.chats.add(toMegagroup(chat, selfId));', chats)

    def test_peer_settings_do_not_fail(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        chats = self.source('tgnet/WaysGramChats.java')
        self.assertIn('request instanceof TLRPC.TL_messages_getPeerSettings', transport)
        self.assertIn('WaysGramChats.peerSettings()', transport)
        self.assertIn('static TLRPC.TL_messages_peerSettings peerSettings()', chats)

    def test_report_spam_posts_to_reports_endpoint(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        chats = self.source('tgnet/WaysGramChats.java')
        self.assertIn('request instanceof TLRPC.TL_messages_reportSpam', transport)
        self.assertIn('WaysGramChats.reportPeer(', transport)
        self.assertIn('"/api/reports"', chats)
        self.assertIn('body.put("option", "spam");', chats)

    def test_admins_with_invites_uses_dedicated_endpoint(self):
        transport = self.source('tgnet/WaysGramTransport.java')
        invites = self.source('tgnet/WaysGramInvites.java')
        self.assertIn('request instanceof TLRPC.TL_messages_getAdminsWithInvites', transport)
        self.assertIn('WaysGramInvites.adminsWithInvites(', transport)
        self.assertIn('"/admins-with-invites"', invites)
        self.assertIn('TLRPC.TL_chatAdminWithInvites', invites)
        self.assertIn('out.users.add(WaysGramMapper.toUser(row));', invites)


if __name__ == '__main__':
    unittest.main()
