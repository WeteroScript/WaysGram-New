"""Mini app «Fragment»: страница рынка для встроенного WebView.

Раздел «Настройки → Fragment» в клиенте открывает не нативные списки, а страницу
`/fragment`, свёрстанную как мобильный fragment.com: шапка с логотипом и кнопкой TON,
вкладки «Юзернеймы / Подарки / Инвентарь», таблица аукционов, сетка карточек подарков и
страница лота. Вёрстка лежит в `static/fragment.html` и правится без пересборки APK.

Страница не хранит никаких секретов: токен сессии и тему клиент отдаёт через
JavaScript-мост `WaysGram` (`WaysGram.token()`, `WaysGram.theme()`), поэтому в URL нет ни
токена, ни одноразовых кодов, а сама страница может отдаваться публично.
"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

PAGE_PATH = Path(__file__).resolve().parent / "static" / "fragment.html"


def register_routes(router: APIRouter) -> None:
    """Отдаёт страницу mini app. Данные берутся обычными /api-методами."""

    @router.get("/fragment", include_in_schema=False)
    async def fragment_page() -> FileResponse:
        if not PAGE_PATH.is_file():
            raise HTTPException(status_code=404, detail="Страница Fragment не собрана")
        return FileResponse(
            PAGE_PATH,
            media_type="text/html; charset=utf-8",
            # Правки вёрстки должны приезжать сразу, без ожидания кэша WebView.
            headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"},
        )

    @router.get("/fragment/health", include_in_schema=False)
    async def fragment_health() -> dict[str, object]:
        return {"ok": PAGE_PATH.is_file(), "bytes": PAGE_PATH.stat().st_size if PAGE_PATH.is_file() else 0}
