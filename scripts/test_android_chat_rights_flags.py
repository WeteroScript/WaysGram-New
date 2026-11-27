#!/usr/bin/env python3
"""Права чата сравниваются по проводному виду, иначе редактор группы закрывается сам.

С устройства: после смены аватара группы повторное открытие «Изменить» сразу выбрасывало
обратно в чат. `ChatEditActivity` на `channelRightsUpdated` закрывается, если `admin_rights`
или `default_banned_rights` чата перестали быть равны снимку, сделанному при открытии.
`TL_chatAdminRights.equals`/`TL_chatBannedRights.equals` сравнивают поле `flags`, а оно в
TLRPC вычисляется только при сериализации. Снимок делается через `clone()` (то есть уже
проводной), а чат из `WaysGramChats` держал объект с `flags == 0` — равенство не выполнялось
никогда, и первое же обновление прав закрывало редактор.
"""
import io
import re
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'WaysGram-Android/TMessagesProj/src/main/java/org/telegram/'


class ChatRightsFlags(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b''.join((ROOT / f'WaysGram-Android.zip.part{i}').read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.chats = cls.source('tgnet/WaysGramChats.java')
        cls.tlrpc = cls.source('tgnet/TLRPC.java')
        cls.edit = cls.source('ui/ChatEditActivity.java')

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    @classmethod
    def source(cls, relative):
        return cls.archive.read(BASE + relative).decode('utf-8')

    @staticmethod
    def method_body(source, signature):
        start = source.index(signature)
        return source[start:source.index('\n    }', start)]

    def test_equality_of_rights_is_based_on_flags(self):
        self.assertIn('return this.flags == ((TL_chatAdminRights) obj).flags;', self.tlrpc)
        self.assertIn('return this.flags == ((TL_chatBannedRights) obj).flags;', self.tlrpc)

    def test_edit_screen_still_closes_when_rights_really_changed(self):
        body = self.method_body(self.edit, '} else if (id == NotificationCenter.channelRightsUpdated) {')
        self.assertIn('finishFragment();', body)
        self.assertIn('chatAdminRights.equals(chat.admin_rights)', body)
        self.assertIn('chatDefaultBannedRights.equals(chat.default_banned_rights)', body)

    def test_builders_return_wire_normalised_rights(self):
        self.assertIn('return TLRPC.TL_chatAdminRights.clone(rights);',
                      self.method_body(self.chats, 'private static TLRPC.TL_chatAdminRights normalized('))
        self.assertIn('return TLRPC.TL_chatBannedRights.clone(rights);',
                      self.method_body(self.chats, 'private static TLRPC.TL_chatBannedRights normalized('))
        for signature in ('private static TLRPC.TL_chatAdminRights channelRights(int mask)',
                          'private static TLRPC.TL_chatAdminRights groupAdminRights(int mask)'):
            self.assertIn('normalized(rights)', self.method_body(self.chats, signature), signature)

    def test_ban_restrictions_keep_both_flags_and_send_decisions(self):
        body = self.method_body(self.chats, 'static TLRPC.TL_chatBannedRights bannedRightsFor(int mask, boolean changeInfoRestricted)')
        self.assertIn('TLRPC.TL_chatBannedRights stable = normalized(rights);', body)
        self.assertIn('stable.send_messages |= (mask & 1) != 0;', body)
        self.assertIn('stable.send_media |= (mask & 2) != 0;', body)
        self.assertIn('return stable;', body)

    def test_group_admin_rights_use_the_group_mask(self):
        body = self.method_body(self.chats, 'private static TLRPC.TL_chatAdminRights groupAdminRights(int mask)')
        expected = {
            'change_info': 1,
            'invite_users': 2,
            'ban_users': 4,
            'pin_messages': 8,
            'delete_messages': 16,
            'manage_call': 32,
        }
        for field, bit in expected.items():
            self.assertIn(f'rights.{field} = (mask & {bit}) != 0;', body, field)

    def test_default_change_info_is_mapped_and_saved_separately(self):
        self.assertIn("bannedRightsFor(int mask, boolean changeInfoRestricted)", self.chats)
        self.assertIn("rights.change_info = changeInfoRestricted;", self.chats)
        for signature in (
            "public static TLRPC.TL_chat toChat(int account, JSONObject g, long selfId)",
            "public static TLRPC.TL_channel toMegagroup(int account, JSONObject g, long selfId)",
        ):
            body = self.method_body(self.chats, signature)
            self.assertIn('g.optBoolean("default_change_info_restricted")', body, signature)
            self.assertIn("bannedRightsFor(g.optInt(\"default_send_restrictions\")", body, signature)
        setter = self.method_body(
            self.chats,
            "public static TLObject setGroupDefaultRights(int account, long chatId, TLRPC.TL_chatBannedRights rights)",
        )
        self.assertIn('"change_info_restricted", rights != null && rights.change_info', setter)
        self.assertIn('"/send-permissions"', setter)

    def test_participant_rights_are_never_invented(self):
        """Участник без назначения не должен получать маску прав из воздуха.

        Сервер отдавал `admin_rights: 63` обычному участнику (DEFAULT колонки), а клиент
        подставлял 63 и 15 там, где поля не было вовсе. Из-за этого в приложении
        появлялись «Редактировать» и админ-действия у людей, которых никто не назначал.
        """
        self.assertNotIn('optInt("admin_rights", 63)', self.chats)
        self.assertNotIn('optInt("admin_rights", 15)', self.chats)
        self.assertIn('int groupMask = g.optInt("admin_rights", 0);', self.chats)
        self.assertIn('("admin".equals(role) && groupMask > 0)', self.chats)
        self.assertIn('row.optInt("admin_rights", 0)', self.chats)

    def test_groups_no_longer_borrow_the_channel_mask(self):
        body = self.method_body(self.chats, 'public static TLRPC.TL_channel toMegagroup(int account, JSONObject g, long selfId)')
        self.assertIn('chat.admin_rights = groupAdminRights(', body)
        self.assertNotIn('channelRights(', body)
        full = self.method_body(self.chats, 'public static TLRPC.TL_messages_chatFull fullChat(int account, long chatId, long selfId)')
        self.assertIn('participant.waysGramAdminRights = groupAdminRights(', full)


if __name__ == '__main__':
    unittest.main()
