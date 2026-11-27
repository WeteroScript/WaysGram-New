#!/usr/bin/env python3
"""Fragment: mini app вместо нативных экранов.

Раздел «Настройки → Fragment» открывает страницу `/fragment`, свёрстанную как
мобильный fragment.com. Проверяем три вещи:

* клиент действительно показывает mini app — это экран с `WebView`, токен и тема
  передаются странице JavaScript-мостом, а не через URL;
* самой нативной вёрстки (списки, карточки лота) больше нет ни в зеркале, ни в архиве;
* сервер отдаёт страницу и все `/api`-методы, которые она вызывает.

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
PAGE = ROOT / "authbot" / "static" / "fragment.html"
SERVER = ROOT / "authbot" / "fragment_webapp.py"


class FragmentMiniAppChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        cls.names = set(cls.archive.namelist())
        cls.page = PAGE.read_text(encoding="utf-8")
        cls.screen = cls.archive.read(BASE + "ui/WaysGramFragmentActivity.java").decode("utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def resource(self, name):
        return self.archive.read(RES + name).decode("utf-8")

    # ------------------------------------------------------------- страница

    def test_page_is_shipped_with_the_server(self):
        self.assertTrue(SERVER.is_file())
        self.assertTrue(PAGE.is_file(), "страница mini app лежит в authbot/static")
        server = SERVER.read_text(encoding="utf-8")
        self.assertIn('@router.get("/fragment"', server)
        self.assertIn("fragment.html", server)
        api = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")
        self.assertIn("import fragment_webapp", api)
        self.assertIn("fragment_webapp.register_routes(router)", api)

    def test_page_has_the_three_tabs_of_fragment(self):
        for tab in ("Юзернеймы", "Подарки", "Инвентарь"):
            self.assertIn(tab, self.page, tab)
        # Ни номеров, ни звёзд, ни Premium, ни рекламы — как и просили.
        for removed in ("Numbers", "Stars", "Premium", "Ads"):
            self.assertNotIn(f'data-tab="{removed.lower()}"', self.page)

    def test_page_looks_like_the_site(self):
        # Скриншоты fragment.com: брендовая строка, кнопка TON, таблица с колонками,
        # сетка карточек с номером и ценой, страница лота с атрибутами.
        for marker in ("FRAGMENT", "Подключить TON", "Минимальная ставка",
                       "Купить и продать юзернеймы", "Топ подарков", "Коллекции",
                       "Серийный номер", "Модель", "Узор", "Фон"):
            self.assertIn(marker, self.page, marker)
        self.assertIn("grid-template-columns: repeat(3, 1fr)", self.page)
        self.assertIn("collectible_traits", self.page)
        self.assertIn("/api/telegram/gifts/", self.page)

    def test_page_never_carries_the_token_in_the_url(self):
        # Токен приходит мостом: страница сама его не выпрашивает и в ссылке его нет.
        self.assertIn("window.WaysGram", self.page)
        self.assertIn("bridge.token()", self.page)
        self.assertIn("bridge.theme()", self.page)
        self.assertNotIn("?token=", self.page)
        self.assertNotIn("?code=", self.page)
        self.assertNotIn("localStorage", self.page)

    def test_page_uses_only_our_api(self):
        for path in ("/api/collectibles", "/api/gift-market", "/api/me/fragment/gifts",
                     "/api/me/ton", "/api/gifts/"):
            self.assertIn(path, self.page, path)
        self.assertIn("requestModelArtwork", self.page)
        self.assertIn("model_document_id", self.page)
        self.assertIn("lottie.loadAnimation", self.page)
        # Никаких запросов к сторонним доменам: рендерер модели поставляется локально.
        self.assertNotIn("http://", self.page)
        self.assertNotIn("https://", self.page)

    # -------------------------------------------------------------- клиент

    def test_client_shows_the_mini_app(self):
        screen = self.screen
        self.assertIn("extends BaseFragment", screen)
        self.assertIn("actionBar.setBackButtonImage(R.drawable.ic_ab_back)", screen)
        self.assertIn("actionBar.setTitle(", screen)
        self.assertIn("finishFragment()", screen)
        self.assertIn("import android.webkit.WebView;", screen)
        self.assertIn("setJavaScriptEnabled(true)", screen)
        self.assertIn("addJavascriptInterface(new Bridge(), \"WaysGram\")", screen)
        self.assertIn("public String token()", screen)
        self.assertIn("public String theme()", screen)
        # URL собирается из базового адреса API, без токена в строке запроса.
        self.assertIn('+ "/fragment"', screen)
        self.assertNotIn("?token=", screen)
        self.assertNotIn("AlertDialog", screen)
        self.assertNotIn("showDialog(", screen)

    def test_client_handles_offline(self):
        screen = self.screen
        self.assertIn("onReceivedError", screen)
        self.assertIn("R.string.WaysGramFragmentOffline", screen)
        self.assertIn("R.string.WaysGramFragmentRetry", screen)
        for name in ("WaysGramFragmentOffline", "WaysGramFragmentRetry"):
            for resource in ("values/strings.xml", "values-ru/strings.xml"):
                self.assertIn(f'name="{name}"', self.resource(resource), f"{name} @ {resource}")

    def test_native_fragment_screens_are_gone(self):
        for name in ("ui/WaysGramFragmentGiftActivity.java", "ui/WaysGramCollectibleActivity.java"):
            self.assertNotIn(BASE + name, self.names, name)
            self.assertFalse((ROOT / "android-source" / (BASE + name)).exists(), name)
        settings = self.source("ui/SettingsActivity.java")
        self.assertIn("presentSettingFragment(new WaysGramFragmentActivity(currentAccount))", settings)
        self.assertIn("R.string.WaysGramFragmentMenu", settings)

    def test_profile_card_still_gets_an_answer(self):
        # Карточка юзернейма в профиле Telegram ходит в TL_fragment.getCollectibleInfo.
        transport = self.source("tgnet/WaysGramTransport.java")
        self.assertIn("WaysGramFragment.handle(account, request)", transport)
        fragment = self.source("tgnet/WaysGramFragment.java")
        self.assertIn("TL_fragment.TL_getCollectibleInfo", fragment)
        self.assertIn("new TL_fragment.TL_collectibleInfo()", fragment)
        self.assertIn('/api/collectibles/" + path(name)', fragment)
        self.assertIn('info.purchase_date = card.optInt("purchase_date")', fragment)
        self.assertIn('info.amount = card.optLong("price_stars")', fragment)

    def test_money_operations_are_idempotent(self):
        # Мини-апп повторяет те же ключи, что и нативный клиент.
        fragment = self.source("tgnet/WaysGramFragment.java")
        for call in ("/buy", "/claim", "/bid", "/auction"):
            self.assertIn(f'postIdempotent(account, "/api/collectibles/" + path(name) + "{call}"',
                          fragment)
        self.assertIn('return "wg-" + action + "-" + clean + "-" + System.currentTimeMillis();',
                      fragment)
        # Страница строит ключ по тому же шаблону.
        self.assertIn('"wg-" + action + "-" + clean + "-" + Date.now()', self.page)
        self.assertIn('"Idempotency-Key"', self.page)

    # -------------------------------------------------------------- сервер

    def test_server_exposes_the_endpoints_the_page_calls(self):
        server = (ROOT / "authbot" / "fragment_market.py").read_text(encoding="utf-8")
        for route in ('"/api/collectibles"', '"/api/collectibles/{name}"',
                      '"/api/collectibles/{name}/listing"', '"/api/collectibles/{name}/buy"',
                      '"/api/collectibles/{name}/claim"', '"/api/collectibles/{name}/auction"',
                      '"/api/collectibles/{name}/bid"', '"/api/me/ton"',
                      '"/api/me/ton-withdraw"', '"/api/internal/ton/deposits"'):
            self.assertIn(route, server, route)
        api = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")
        self.assertIn("fragment_market.register_routes(", api)
        self.assertIn("fragment_market.ensure_storage(db)", api)
        self.assertIn("process_due_username_auctions()", api)
        for route in ('@router.get("/api/gift-market"', '@router.get("/api/me/gifts")',
                      '@router.get("/api/me/fragment/gifts")',
                      '@router.post("/api/gifts/{serial_number}/list"',
                      '@router.get("/api/telegram/gifts/{gift_id}/sticker"'):
            self.assertIn(route, api, route)

    def test_market_listings_carry_the_card_number(self):
        # На карточке подарка, как на сайте, стоит номер («#1821»), а не серийник.
        api = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")
        self.assertIn("(SELECT n.unique_num FROM gift_unique_numbers n", api)
        self.assertIn("WHERE n.serial_number=l.serial_number) AS unique_num", api)
        self.assertIn("Number(item.unique_num || 0) > 0", self.page)

    def test_rules_that_keep_the_market_honest(self):
        server = (ROOT / "authbot" / "fragment_market.py").read_text(encoding="utf-8")
        self.assertIn("COMMISSION_PERCENT = 5", server)
        self.assertIn("TRANSFER_FREEZE_SECONDS = 24 * 3600", server)
        self.assertIn("AUCTION_SNIPE_WINDOW = 300", server)
        self.assertIn('"NOT_OWNER"', server)
        self.assertIn("tx_hash TEXT NOT NULL UNIQUE", server)
        self.assertIn('"TON_DEPOSIT_DUPLICATE"', server)

    def test_market_never_sells_a_chat_username(self):
        server = (ROOT / "authbot" / "fragment_market.py").read_text(encoding="utf-8")
        self.assertIn('for table in ("chat_groups", "channels", "bots")', server)
        self.assertIn('"reserved": reserved,', server)

    def test_ton_watcher_is_present(self):
        watcher = (ROOT / "authbot" / "scripts" / "ton_watcher.py").read_text(encoding="utf-8")
        self.assertIn("/api/internal/ton/deposits", watcher)
        self.assertIn("X-WaysGram-Ton-Secret", watcher)
        self.assertIn('MEMO_PREFIX = "wg:"', watcher)

    def test_inventory_is_topped_up_only_by_the_deposit_account(self):
        server = (ROOT / "authbot" / "fragment_deposit.py").read_text(encoding="utf-8")
        self.assertIn('FRAGMENT_USERNAME = "fragment"', server)
        self.assertIn("fragment_gift_deposits", server)
        self.assertIn("UNIQUE(telegram_id, serial_number)", server)
        api = (ROOT / "authbot" / "messenger_api.py").read_text(encoding="utf-8")
        self.assertIn("fragment_deposit.is_deposit_target(db, recipient_id)", api)
        self.assertIn("await fragment_deposit.record(db, uid, serial", api)
        self.assertIn('"fragment_deposit" if deposit else "chat_send"', api)
        self.assertIn("await fragment_deposit.ensure_account(db)", api)


if __name__ == "__main__":
    unittest.main()
