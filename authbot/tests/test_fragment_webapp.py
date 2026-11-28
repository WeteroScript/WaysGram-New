"""Mini app «Fragment»: сервер отдаёт страницу рынка.

Раздел открывается в WebView, поэтому сервер обязан отдать готовую HTML-страницу,
которая сама ходит в наши `/api`-методы. Токен в URL не передаётся: его отдаёт
JavaScript-мост клиента, поэтому страница публичная и не содержит секретов.
"""
import re
from pathlib import Path

from test_messenger_api import client_for, run
from test_star_gifts import env, headers  # noqa: F401

PAGE = Path(__file__).resolve().parents[1] / "static" / "fragment.html"


def test_page_is_served_without_auth(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            response = await client.get("/fragment")
            assert response.status_code == 200, response.text
            assert response.headers["content-type"].startswith("text/html")
            assert "no-store" in response.headers.get("cache-control", "")
            body = response.text
            for marker in ("Юзернеймы", "Подарки", "Инвентарь", "FRAGMENT", "Мои лоты"):
                assert marker in body, marker
            # Fragment UI is a fixed-price marketplace: TON, auctions and serials are absent.
            for removed in ("Подключить TON", "Пополнить за TON", "Минимальная ставка",
                            "Аукцион", "Серийный номер", "/api/me/ton"):
                assert removed not in body, removed
            assert "grid-template-columns: repeat(3, 1fr)" in body
            assert "collectible_traits" in body

    run(scenario())


def test_page_has_no_secrets_and_no_tokens_in_url(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            body = (await client.get("/fragment")).text
            # Токен приходит мостом, а не из HTML: страница публичная.
            assert "window.WaysGram" in body
            assert "bridge.token()" in body
            assert "?token=" not in body
            assert "?code=" not in body
            assert "localStorage" not in body
            assert not re.search(r"Bearer [A-Za-z0-9_\-]{16,}", body)
            # Никаких сторонних домов.
            assert "http://" not in body and "https://" not in body

    run(scenario())


def test_health_reports_the_page(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            response = await client.get("/fragment/health")
            assert response.status_code == 200, response.text
            data = response.json()
            assert data["ok"] is True
            assert data["bytes"] > 10_000

    run(scenario())


def test_page_calls_only_our_api(env) -> None:
    path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            body = (await client.get("/fragment")).text
            for route in ("/api/collectibles", "/api/gift-market", "/api/me/fragment/gifts",
                          "/api/telegram/gifts/"):
                assert route in body, route
            assert "/api/gift-market?mine=true&limit=100" in body
            assert "/api/gift-market/catalog" in body
            assert "requestModelArtwork" in body
            assert "lottie.loadAnimation" in body
            assert "model_document_id" in body
            # Ключ идемпотентности строится по тому же шаблону, что и в клиенте.
            assert '"wg-" + action + "-" + clean' in body
            assert '"Idempotency-Key"' in body

    run(scenario())


def test_pattern_is_not_rendered_as_a_foreground_sticker() -> None:
    body = PAGE.read_text(encoding="utf-8")
    assert "tinted, tiled backdrop texture" in body
    assert "pattern-art" not in body
    assert "requestPatternArtwork" not in body
    assert "renderGiftPattern" not in body
    # Keep the pattern's name visible as a trait; only the broken image overlay is removed.
    assert 'trait(traits, "symbol") || trait(traits, "pattern")' in body


def test_gift_filters_own_listings_and_refresh_interval(env) -> None:
    body = PAGE.read_text(encoding="utf-8")
    for label in ("Collection", "Model", "Backdrop", "Symbol", "Коллекции",
                  "Выбрать все", "Цена по возрастанию", "Цена по убыванию",
                  "Мои лоты", "Снять с продажи", "selected-filters"):
        assert label in body, label
    assert "mine=true" in body
    assert "if (interval > 0 && interval < 10000) { interval = 10000; }" in body
    assert "if (interval >= 10000)" in body
    assert 'kv("Серийный номер"' not in body
    assert "serialOf(" not in body


def test_fragment_inventory_supports_withdrawal_price_and_trait_backdrops() -> None:
    body = PAGE.read_text(encoding="utf-8")
    assert 'class="inventory-withdraw"' in body
    assert 'id="withdraw-gift"' in body
    assert "/api/me/fragment/gifts/" in body and "/withdraw" in body
    assert "item.price_grams" in body
    assert "backdrop.center_color" in body
    assert "backdrop.edge_color" in body
    assert "fragment_inventory" in body


def test_model_renderer_assets_are_served_locally(env) -> None:
    _path, app = env

    async def scenario() -> None:
        async with await client_for(app) as client:
            for name in ("pako_inflate.min.js", "lottie_light.min.js"):
                response = await client.get("/fragment/assets/" + name)
                assert response.status_code == 200, response.text
                assert response.headers["content-type"].startswith("application/javascript")
                assert len(response.content) > 1_000
            missing = await client.get("/fragment/assets/unknown.js")
            assert missing.status_code == 404

    run(scenario())


def test_page_file_matches_the_repository_copy() -> None:
    assert PAGE.is_file()
    body = PAGE.read_text(encoding="utf-8")
    assert body.startswith("<!doctype html>")
    assert body.count("<section") >= 4
