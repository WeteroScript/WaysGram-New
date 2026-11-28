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
        # Fragment UI keeps the gift marketplace, collectible artwork and collection filters.
        for marker in ("FRAGMENT", "Купить и продать юзернеймы", "Подарки", "Мои лоты",
                       "Коллекции", "Collection", "Model", "Backdrop", "Symbol",
                       "Фильтры", "currency: currency", "price: value"):
            self.assertIn(marker, self.page, marker)
        for removed in ("Подключить TON", "Минимальная ставка", "Аукцион", "Серийный номер"):
            self.assertNotIn(removed, self.page, removed)
        self.assertIn("grid-template-columns: repeat(3, 1fr)", self.page)
        self.assertIn("collectible_traits", self.page)
        # Цвета фона берутся по имени трейта из официальной таблицы палитр.
        self.assertIn('backdropColor(backdrop, "center_color")', self.page)
        self.assertIn('backdropColor(backdrop, "edge_color")', self.page)
        self.assertIn("/api/gift-backdrops", self.page)
        self.assertIn('class="inventory-withdraw"', self.page)
        self.assertIn("/api/me/fragment/gifts/", self.page)
        self.assertIn("/api/telegram/gifts/", self.page)
        self.assertIn("if (interval >= 10000)", self.page)

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
                     "/api/gifts/"):
            self.assertIn(path, self.page, path)
        self.assertIn("/api/gift-market?mine=true&limit=100", self.page)
        self.assertIn("/api/gift-market/catalog", self.page)
        self.assertNotIn("/api/me/ton", self.page)
        self.assertIn("requestModelArtwork", self.page)
        self.assertIn("model_document_id", self.page)
        self.assertIn("lottie.loadAnimation", self.page)
        # Никаких запросов к сторонним доменам: рендерер модели поставляется локально.
        self.assertNotIn("http://", self.page)
        self.assertNotIn("https://", self.page)

    def test_pattern_is_not_overlaid_on_the_gift_model(self):
        # Telegram uses the pattern as a tinted background texture, not as a foreground
        # sticker. The page must keep its trait label without painting a black mask over art.
        self.assertIn("render one vector frame as a tinted mask", self.page)
        self.assertIn('trait(traits, "symbol") || trait(traits, "pattern")', self.page)
        self.assertIn("node.style.backgroundColor = color;", self.page)
        self.assertIn("node.style.maskRepeat = \"repeat\";", self.page)
        self.assertIn(".pattern-texture {", self.page)
        self.assertIn("z-index: 0; pointer-events: none;", self.page)
        for marker in ("pattern-art", "requestPatternArtwork", "renderGiftPattern"):
            self.assertNotIn(marker, self.page)

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
        self.assertIn('card.optString("purchase_currency", "")', fragment)
        self.assertIn('info.amount = card.optLong("purchase_price_stars")', fragment)
        self.assertIn('info.crypto_amount = card.optLong("purchase_price_grams")', fragment)
        self.assertIn('info.crypto_currency = "GRAM";', fragment)

    def test_collectible_username_card_formats_stars_without_a_learn_more_button(self):
        fragment = self.source("tgnet/WaysGramFragment.java")
        self.assertIn('info.currency = "XTR";', fragment)
        self.assertNotIn('info.currency = "STARS";', fragment)
        self.assertIn('info.crypto_currency = "";', fragment)

        billing = self.source("messenger/BillingController.java")
        self.assertIn('if ("XTR".equalsIgnoreCase(currency))', billing)

        bottom_sheet = self.source("ui/FragmentUsernameBottomSheet.java")
        self.assertIn('TextUtils.isEmpty(info.currency) ? "" : BillingController', bottom_sheet)
        self.assertIn('TextUtils.isEmpty(info.crypto_currency) ? "" : BillingController', bottom_sheet)
        self.assertIn('StarsIntroActivity.replaceStarsWithPlain(messageSpanned, .8f)', bottom_sheet)
        self.assertRegex(bottom_sheet, r'(?s)if \(type == TYPE_PHONE\)\s*\{\s*ButtonWithCounterView button = .*?FragmentUsernameOpen')
        self.assertIn('R.string.FragmentUsernameCopy', bottom_sheet)
        self.assertIn('if (copy != null)', bottom_sheet)

        profile = self.source("ui/ProfileActivity.java")
        self.assertIn('TextUtils.isEmpty(info.crypto_currency) ? "" :', profile)
        self.assertIn('StarsIntroActivity.replaceStarsWithPlain(message, .8f)', profile)

    def test_profile_maps_purchased_usernames_as_additional_names(self):
        mapper_path = ROOT / "android-source" / "TMessagesProj" / "src" / "main" / "java" \
            / "org" / "telegram" / "tgnet" / "WaysGramMapper.java"
        profile_path = ROOT / "android-source" / "TMessagesProj" / "src" / "main" / "java" \
            / "org" / "telegram" / "ui" / "ProfileActivity.java"
        mapper = mapper_path.read_text(encoding="utf-8")
        profile = profile_path.read_text(encoding="utf-8")
        api = (ROOT / "authbot" / "fragment_market.py").read_text(encoding="utf-8")
        self.assertIn('json.optJSONArray("usernames")', mapper)
        self.assertIn("user.usernames.add(nativeUsername)", mapper)
        self.assertIn("alsoUsernamesString(username, usernames", profile)
        self.assertIn('"username": name', api)

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
                      '@router.post("/api/me/fragment/gifts/{serial_number}/withdraw"',
                      '@router.post("/api/gifts/{serial_number}/list"',
                      '@router.get("/api/telegram/gifts/{gift_id}/sticker"'):
            self.assertIn(route, api, route)
        self.assertIn("message_fragment_star_transfers", api)
        self.assertIn("fragment_star_transfer_stars", api)

    def test_fragment_transfers_keep_native_stars_gift_card(self):
        mapper = self.source("tgnet/WaysGramMapper.java")
        message_object = self.source("messenger/MessageObject.java")
        self.assertIn('json.optLong("fragment_star_transfer_stars", 0L)', mapper)
        self.assertIn("new TLRPC.TL_messageActionGiftStars()", mapper)
        self.assertIn("action.amount = 0;", mapper)
        self.assertIn("action.stars = stars;", mapper)
        self.assertIn("private static boolean isFragmentStarsTransfer(", message_object)
        self.assertIn("action.amount == 0 && action.stars > 0", message_object)
        self.assertIn("isFragmentStarsTransfer(giftStarsAction)", message_object)
        self.assertNotIn("isFragmentStarsTransfer((TLRPC.TL_messageActionGiftStars) messageOwner.action)", message_object)
        gift_branch = message_object.index(
            "} else if (messageOwner.action instanceof TLRPC.TL_messageActionGiftStars ||"
        )
        next_branch = message_object.index("} else if", gift_branch + 1)
        self.assertIn("type = TYPE_GIFT_STARS;", message_object[gift_branch:next_branch])
        self.assertIn("ActionFragmentStarsTransferInbound", message_object)
        chat_action_cell = self.source("ui/Cells/ChatActionCell.java")
        self.assertIn("formatPluralStringComma(\"ActionGiftStarsTitle\", (int) stars)", chat_action_cell)
        self.assertIn("getString(R.string.ActionGiftStarsView)", chat_action_cell)
        for resource in ("values/strings.xml", "values-ru/strings.xml"):
            xml = self.resource(resource)
            self.assertIn('name="ActionFragmentStarsTransferInbound"', xml)
            self.assertIn('name="FragmentStarsCount_one"', xml)
        gifts = self.source("messenger/waysgram/WaysGramGiftMessages.java")
        self.assertIn("toServiceMessage(account, m, selfId)", mapper)
        self.assertIn("TL_messageActionStarGiftUnique", gifts)

    def test_report_submission_is_safe_when_message_ids_are_missing(self):
        report = self.source("ui/ReportBottomSheet.java")
        self.assertIn("private static boolean hasMessageIds(ArrayList<Integer> ids)", report)
        self.assertIn("return ids != null && !ids.isEmpty();", report)
        self.assertEqual(report.count("hasMessageIds(messageIds)"), 3)
        self.assertNotIn("!messageIds.isEmpty()", report)

    def test_gift_serial_is_not_rendered_in_fragment(self):
        # Serial numbers stay internal for API operations; no raw number is shown to users.
        self.assertNotIn("Серийный номер", self.page)
        self.assertNotIn("serialOf(", self.page)
        self.assertNotIn('kv("Номер"', self.page)

    def test_rules_that_keep_the_market_honest(self):
        server = (ROOT / "authbot" / "fragment_market.py").read_text(encoding="utf-8")
        self.assertIn("COMMISSION_PERCENT = 5", server)
        self.assertIn("TRANSFER_FREEZE_SECONDS = 24 * 3600", server)
        self.assertIn("AUCTION_SNIPE_WINDOW = 300", server)
        self.assertIn('"NOT_OWNER"', server)
        self.assertIn("tx_hash TEXT NOT NULL UNIQUE", server)
        self.assertIn('"TON_DEPOSIT_DUPLICATE"', server)

    def test_unclaimed_names_cannot_be_taken_from_public_entities(self):
        server = (ROOT / "authbot" / "fragment_market.py").read_text(encoding="utf-8")
        self.assertIn("async def username_used_outside_market", server)
        self.assertIn('for table in ("accounts", "chat_groups", "channels", "bots")', server)
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
