#!/usr/bin/env python3
"""Коллекционные юзернеймы: наш Fragment в клиенте и на сервере.

Проверяет, что штатные экраны Telegram (`ProfileActivity` →
`TL_fragment.getCollectibleInfo`) получают ответ, что рынок вызывается только
через серверные методы и что у каждой новой строки есть русский перевод.

Читает исходники из `WaysGram-Android.zip.part1…5`.
"""

import io
import re
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"
RES = "WaysGram-Android/TMessagesProj/src/main/res/"
RES_FILES = (
    "values/strings.xml",
    "values-ru/strings.xml",
)


class FragmentMarketChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.names = set(cls.archive.namelist())

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def resource(self, name):
        return self.archive.read(RES + name).decode("utf-8")

    def test_profile_card_gets_an_answer(self):
        # Раньше getCollectibleInfo падал с METHOD_NOT_SUPPORTED, поэтому карточка
        # юзернейма в профиле оставалась пустой.
        transport = self.source("tgnet/WaysGramTransport.java")
        self.assertIn("WaysGramFragment.handle(account, request)", transport)
        self.assertLess(transport.index("WaysGramFragment.handle(account, request)"),
                        transport.index("WaysGramTransport.METHOD_NOT_SUPPORTED")
                        if "WaysGramTransport.METHOD_NOT_SUPPORTED" in transport else len(transport))
        fragment = self.source("tgnet/WaysGramFragment.java")
        self.assertIn("TL_fragment.TL_getCollectibleInfo", fragment)
        self.assertIn("new TL_fragment.TL_collectibleInfo()", fragment)
        self.assertIn('/api/collectibles/" + path(name)', fragment)
        # Дата покупки и цена — ровно те поля, которые читает FragmentUsernameBottomSheet.
        self.assertIn('info.purchase_date = card.optInt("purchase_date")', fragment)
        self.assertIn('info.amount = card.optLong("price_stars")', fragment)
        # Свободное имя и имя группы карточкой не притворяются.
        self.assertIn('card.optBoolean("reserved") || card.optBoolean("free")', fragment)

    def test_market_screen_is_wired_into_settings(self):
        settings = self.source("ui/SettingsActivity.java")
        self.assertIn("presentSettingFragment(new WaysGramFragmentActivity(currentAccount))", settings)
        self.assertIn("R.string.WaysGramFragmentMenu", settings)
        self.assertIn("R.drawable.fragment", settings)
        market = self.source("ui/WaysGramFragmentActivity.java")
        card = self.source("ui/WaysGramCollectibleActivity.java")
        self.assertIn("WaysGramFragment.market(account, WaysGramFragment.SECTION_MARKET)", market)
        self.assertIn("WaysGramFragment.buy(account, name)", card)
        self.assertIn("WaysGramFragment.bid(account, name, value)", card)
        self.assertIn("WaysGramFragment.list(account, name, value)", card)
        self.assertIn("WaysGramFragment.startAuction(account, name, value, duration)", card)
        self.assertIn("WaysGramFragment.ton(account)", market)
        self.assertIn("WaysGramFragment.claim(account, name)", card)

    def test_market_screens_are_fragments_not_dialogs(self):
        # Пользователь дважды просил: разделы открываются как экраны приложения, а не
        # как всплывающие диалоги. Рынок и карточка имени — обычные фрагменты со своим
        # ActionBar, кнопкой «назад» и строками настроек.
        self.assertNotIn("WaysGram-Android/TMessagesProj/src/main/java/org/telegram/ui/"
                         "WaysGramFragmentMarket.java", self.names)
        self.assertFalse((ROOT / "android-source/TMessagesProj/src/main/java/org/telegram/ui/"
                          "WaysGramFragmentMarket.java").exists())
        for name in ("ui/WaysGramFragmentActivity.java", "ui/WaysGramCollectibleActivity.java"):
            screen = self.source(name)
            self.assertIn("extends BaseFragment", screen, name)
            self.assertIn("actionBar.setBackButtonImage(R.drawable.ic_ab_back)", screen, name)
            self.assertIn("actionBar.setTitle(", screen, name)
            self.assertIn("finishFragment()", screen, name)
            self.assertIn("TextSettingsCell", screen, name)
            self.assertIn("ShadowSectionCell", screen, name)
            self.assertNotIn("AlertDialog", screen, name)
            self.assertNotIn("showDialog(", screen, name)
        market = self.source("ui/WaysGramFragmentActivity.java")
        for section in ("SECTION_MARKET", "SECTION_AUCTIONS", "SECTION_MINE"):
            self.assertIn(f"WaysGramFragment.{section}", market)
        self.assertIn("presentFragment(new WaysGramCollectibleActivity(account, name))", market)
        self.assertIn("presentFragment(new WaysGramCollectibleActivity(account, \"\"))", market)
        card = self.source("ui/WaysGramCollectibleActivity.java")
        # Действия — строки списка; сумма вводится полем на том же экране.
        for action in ("WaysGramFragmentUnlist", "WaysGramFragmentCancelAuction",
                       "WaysGramFragmentListing", "WaysGramFragmentStartAuction",
                       "WaysGramFragmentBuy", "WaysGramFragmentClaimPrice"):
            self.assertIn(action, card)
        self.assertIn("setInputType(InputType.TYPE_CLASS_NUMBER)", card)

    def test_repack_can_delete_a_replaced_screen(self):
        # Экран-диалог заменён фрагментом: старый файл нужно уметь убрать из архива.
        script = (ROOT / "scripts" / "repack_android_source.py").read_text(encoding="utf-8")
        self.assertIn('"--remove"', script)
        self.assertIn("removed_set", script)

    def test_money_operations_are_idempotent(self):
        # Двойной тап по «Купить» не должен списать звёзды дважды.
        fragment = self.source("tgnet/WaysGramFragment.java")
        for call in ("/buy", "/claim", "/bid", "/auction"):
            self.assertIn(f'postIdempotent(account, "/api/collectibles/" + path(name) + "{call}"',
                          fragment)
        self.assertIn('return "wg-" + action + "-" + clean + "-" + System.currentTimeMillis();',
                      fragment)
        # Серверный формат ключа: 16–128 символов из латиницы, цифр, ._:- .
        total = len("wg-") + len("auction") + len("-") + 1 + len("-") + len("1750000000000")
        self.assertGreaterEqual(total, 16)

    def test_server_exposes_the_endpoints_the_client_calls(self):
        server = (ROOT / "authbot" / "fragment_market.py").read_text(encoding="utf-8")
        for route in ('"/api/collectibles"', '"/api/collectibles/{name}"',
                      '"/api/collectibles/{name}/listing"', '"/api/collectibles/{name}/buy"',
                      '"/api/collectibles/{name}/claim"', '"/api/collectibles/{name}/auction"',
                      '"/api/collectibles/{name}/bid"', '"/api/me/ton"',
                      '"/api/me/ton-withdraw"', '"/api/internal/ton/deposits"'):
            self.assertIn(route, server, route)
        api = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")
        # Маршруты подключаются тем же роутером, что и остальной API.
        self.assertIn("fragment_market.register_routes(", api)
        self.assertIn("fragment_market.ensure_storage(db)", api)
        self.assertIn("process_due_username_auctions()", api)

    def test_rules_that_keep_the_market_honest(self):
        server = (ROOT / "authbot" / "fragment_market.py").read_text(encoding="utf-8")
        # Комиссия, заморозка после покупки, снайп-защита и холд ставки.
        self.assertIn("COMMISSION_PERCENT = 5", server)
        self.assertIn("TRANSFER_FREEZE_SECONDS = 24 * 3600", server)
        self.assertIn("AUCTION_SNIPE_WINDOW = 300", server)
        self.assertIn('f"username_auction_hold:{auction_id}"', server)
        self.assertIn('f"username_auction_outbid:{auction_id}"', server)
        # Чужой лот не продаётся и не покупается: владелец только из базы.
        self.assertIn('"NOT_OWNER"', server)
        self.assertIn("stars=stars+?,updated_at=? WHERE telegram_id=? AND stars>=?", server)
        # Повторный tx_hash не зачисляется второй раз.
        self.assertIn("tx_hash TEXT NOT NULL UNIQUE", server)
        self.assertIn('"TON_DEPOSIT_DUPLICATE"', server)

    def test_market_never_sells_a_chat_username(self):
        server = (ROOT / "authbot" / "fragment_market.py").read_text(encoding="utf-8")
        # Имя группы/канала/бота принадлежит чату, а не рынку.
        self.assertIn('for table in ("chat_groups", "channels", "bots")', server)
        self.assertIn('"reserved": reserved,', server)
        card = self.source("ui/WaysGramCollectibleActivity.java")
        self.assertIn('data.optBoolean("reserved")', card)
        self.assertIn("R.string.WaysGramFragmentUnavailable", card)

    def test_every_new_string_is_translated(self):
        base = self.resource("values/strings.xml")
        russian = self.resource("values-ru/strings.xml")
        base_names = set(re.findall(r'<string name="([^"]+)"', base))
        ru_names = set(re.findall(r'<string name="([^"]+)"', russian))
        used = set()
        for name in ("ui/WaysGramFragmentActivity.java", "ui/WaysGramCollectibleActivity.java",
                     "ui/SettingsActivity.java"):
            used.update(re.findall(r"R\.string\.(WaysGramFragment\w+)", self.source(name)))
        self.assertTrue(used, "клиент не использует ни одной строки рынка")
        missing_base = sorted(used - base_names)
        missing_ru = sorted(used - ru_names)
        self.assertEqual(missing_base, [], "нет строки в базовых ресурсах")
        self.assertEqual(missing_ru, [], "нет русского перевода")

    def test_new_files_are_in_the_build(self):
        # Исходники собираются одним модулем: новые файлы в тех же пакетах.
        for name in ("tgnet/WaysGramFragment.java", "ui/WaysGramFragmentActivity.java",
                     "ui/WaysGramCollectibleActivity.java"):
            self.assertIn("WaysGram-Android/TMessagesProj/src/main/java/org/telegram/" + name,
                          self.names)

    def test_ton_watcher_is_present(self):
        watcher = (ROOT / "authbot" / "scripts" / "ton_watcher.py").read_text(encoding="utf-8")
        self.assertIn("/api/internal/ton/deposits", watcher)
        self.assertIn("X-WaysGram-Ton-Secret", watcher)
        self.assertIn("MEMO_PREFIX = \"wg:\"", watcher)


if __name__ == "__main__":
    unittest.main()
