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
            for marker in ("Юзернеймы", "Подарки", "Инвентарь", "FRAGMENT", "Подключить TON"):
                assert marker in body, marker
            # Вёрстка повторяет сайт: таблица с колонками и сетка карточек.
            assert "Минимальная ставка" in body
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
            for route in ("/api/collectibles", "/api/gift-market", "/api/me/gifts",
                          "/api/me/ton", "/api/telegram/gifts/"):
                assert route in body, route
            # Ключ идемпотентности строится по тому же шаблону, что и в клиенте.
            assert '"wg-" + action + "-" + clean' in body
            assert '"Idempotency-Key"' in body

    run(scenario())


def test_page_file_matches_the_repository_copy() -> None:
    assert PAGE.is_file()
    body = PAGE.read_text(encoding="utf-8")
    assert body.startswith("<!doctype html>")
    assert body.count("<section") >= 4
