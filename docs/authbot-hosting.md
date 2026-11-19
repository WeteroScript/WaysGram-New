# AuthBot: BotHost → WaysGram-AuthBot → код из WaysGram-New

В панели BotHost указывается репозиторий **WaysGram-AuthBot**. В нём лежит маленький лаунчер
(`bot.py`), который при каждом старте подтягивает папку `authbot/` из **WaysGram-New** и запускает
её. Весь код бота живёт в `WaysGram-New`, а хостинг смотрит на стабильный маленький репозиторий.

```
BotHost ──clone──► WaysGram-AuthBot            (репозиторий в панели)
                      │  python bot.py         ← deploy/authbot-bridge/bot.py
                      ▼
              [launcher] GitHub API → WaysGram-New@main
                      │  скачивает только authbot/** (~25 файлов, ~1.2 МБ)
                      ▼
              /data/waysgram-new/<commit>/authbot/bot.py
                      │  runpy
                      ▼
              uvicorn + aiogram (код из WaysGram-New)
```

## Что где лежит

| Репозиторий | Файл | Роль |
| --- | --- | --- |
| `WaysGram-AuthBot` | `bot.py` | лаунчер, копия [`deploy/authbot-bridge/bot.py`](../deploy/authbot-bridge/bot.py) |
| `WaysGram-AuthBot` | `bot_standalone.py` | старая точка входа, резерв на случай первого старта без сети |
| `WaysGram-AuthBot` | `requirements.txt` | зависимости (ставятся при сборке образа) |
| `WaysGram-New` | `authbot/` | **весь код бота** — единственный источник правды |
| `WaysGram-New` | `deploy/authbot-bridge/bot.py` | исходник лаунчера |
| `WaysGram-New` | `bot.py`, `requirements.txt`, `Procfile`, `runtime.txt` | альтернативный деплой: панель смотрит прямо на этот репозиторий |
| `WaysGram-New` | `.github/workflows/authbot.yml` | тесты бота + проверка лаунчера и точки входа |

Пошаговая инструкция для `WaysGram-AuthBot` — в
[`deploy/authbot-bridge/README.md`](../deploy/authbot-bridge/README.md).

## Установка (один раз)

В репозитории `WaysGram-AuthBot`:

```bash
git mv bot.py bot_standalone.py          # сохранить старый запуск как страховку
curl -fsSL -o bot.py \
  https://raw.githubusercontent.com/WeteroScript/WaysGram-New/main/deploy/authbot-bridge/bot.py
git add bot.py bot_standalone.py && git commit -m "Run the bot from WaysGram-New/authbot"
git push origin main
```

В BotHost ничего не меняется: репозиторий `WeteroScript/WaysGram-AuthBot`, Python 3.12,
главный файл `bot.py`, зависимости `requirements.txt`, команда `python bot.py`, постоянный
каталог `/data`.

## Переменные окружения

Лаунчер (все необязательные):

| Переменная | По умолчанию | Зачем |
| --- | --- | --- |
| `WAYSGRAM_REPO` | `WeteroScript/WaysGram-New` | откуда брать код бота |
| `WAYSGRAM_REF` | `main` | ветка или тег |
| `WAYSGRAM_COMMIT` | — | прибить коммит (мгновенный старт, без проверки обновлений) |
| `WAYSGRAM_SUBDIR` | `authbot` | папка с кодом |
| `WAYSGRAM_HOME` | `/data/waysgram-new` | кэш скачанных коммитов |
| `WAYSGRAM_MODE` | `auto` | `auto` / `api` / `archive` / `local` |
| `WAYSGRAM_LOCAL_ENTRY` | `bot_standalone.py` | резервная копия бота в репозитории лаунчера |
| `AUTHBOT_GITHUB_TOKEN` | — | приватный репозиторий или повышенный лимит GitHub API |
| `AUTHBOT_SYNC_DEPS` | `0` | `1` — доустанавливать `requirements.txt` из скачанного коммита |

Сам бот (значения переносятся без изменений из `authbot/.env.example`):

- `BOT_TOKEN`, `TELEGRAM_API_ID`, `TELEGRAM_API_HASH`, `ADMIN_ID`, `OPENAI_API_KEY`;
- `INTERNAL_API_TOKEN`, `TELESRV_OTP_WEBHOOK_SECRET`, `OTP_FERNET_KEY`;
- `DATABASE_PATH=/data/waysgram_authbot.sqlite3`, `MESSENGER_DATABASE_PATH`;
- `INTERNAL_API_HOST=0.0.0.0`, `INTERNAL_API_PORT`, `PORT`;
- `PUBLIC_API_BASE_URL` — публичный HTTPS-адрес API; должен совпадать с секретом
  `WAYSGRAM_API_BASE_URL` в Android-сборке;
- `LOG_LEVEL`.

## Порядок загрузки кода

1. **api** (по умолчанию) — резолв коммита `WaysGram-New@main`, дерево файлов, затем ~25 блобов
   только из `authbot/**` (~1.2 МБ). Остальные ~200 МБ Android-репозитория не скачиваются.
2. **archive** — если API упёрся в лимит: скачивается архив репозитория и из него распаковывается
   только `authbot/`.
3. **кэш** — GitHub недоступен: запускается последний удачно скачанный коммит
   (`/data/waysgram-new/<commit>/authbot`). Повторный старт с тем же коммитом сети не требует.
4. **`bot_standalone.py`** — самая последняя страховка: старый код, лежащий в репозитории лаунчера.

## Зависимости и код: важная разница

Код тянется из `WaysGram-New/authbot`, а пакеты ставятся из `requirements.txt` репозитория
`WaysGram-AuthBot` во время сборки образа:

- поменялся только код бота → достаточно рестарта/редеплоя;
- в `authbot/requirements.txt` появились новые пакеты → перенеси их в `requirements.txt`
  лаунчера (или включи `AUTHBOT_SYNC_DEPS=1`, тогда бот доставит их сам при старте).

## Диагностика

```bash
python bot.py --check                       # коммит и путь, ничего не запуская
WAYSGRAM_REF=arena/00b4c974-waysgram-new python bot.py --check
WAYSGRAM_MODE=archive python bot.py --check # проверить резервный путь через архив
```

В логах ищите строки с префиксом `[waysgram-launcher]`.

## Альтернатива: панель смотрит прямо на WaysGram-New

Если удобнее указывать в BotHost именно `WaysGram-New`, там уже есть корневой `bot.py`: он без
сети запускает `authbot/` из этого же репозитория. Тогда панель: репозиторий `WaysGram-New`,
ветка `main`, главный файл `bot.py`, зависимости `requirements.txt`.

## Кэш анимаций подарков (TGS-модели и узоры)

Подарки Telegram — это настоящие анимации: у самого подарка это TGS/WebM стикер, а у улучшения
(модель, узор, фон) — отдельные стикер-документы Telegram. Telegram отклоняет запросы набора
атрибутов от бота (`BotMethodInvalidError`); для них оператор может настроить отдельную
пользовательскую сессию `WAYSGRAM_GIFT_USER_SESSION`. Бот-сессия по-прежнему используется для
основного каталога. О настройке и риске полного доступа к аккаунту см. `authbot/README.md`.
Кэш файлов находится рядом с базой, в `gift_assets/`.

- после каждого синхронизированного каталога бот в фоне скачивает арт всех подарков магазина
  (включая снятые с продажи вроде Plush Pepe) и официальный набор моделей/узоров;
- прогресс подготовки виден в `GET /api/telegram/gifts/prefetch`, повторный запуск —
  `POST /api/telegram/gifts/prefetch`;
- анимации отдаются приложению по `GET /api/telegram/gifts/{gift_id}/attributes`,
  `GET /api/telegram/gifts/{gift_id}/attributes/{document_id}`.

Переменные окружения:

| Переменная | По умолчанию | Смысл |
| --- | --- | --- |
| `WAYSGRAM_GIFT_PREFETCH` | `1` | скачивать арт каталога заранее; `0` — только по запросу приложения |
| `WAYSGRAM_GIFT_PREFETCH_ATTRIBUTES` | `24` | сколько моделей/узоров на подарок скачивать заранее; `0` — все |
| `WAYSGRAM_GIFT_ASSET_DIR` | рядом с БД | каталог кэша анимаций |

Кэш — обычные файлы `<document_id>.bin`: их можно без опаски удалить, бот скачает нужное снова.
`WAYSGRAM_GIFT_PREFETCH_ATTRIBUTES=0` загружает весь набор официальных моделей и узоров; на 115
подарках это несколько сотен мегабайт, поэтому по умолчанию ограничено.

## Метки профилей и чатов

Метка — это шаблон (`текст` + `цвет`), который выдаётся аккаунту, каналу или группе. Приложение
рисует её пилюлей после значка премиум: в списке чатов, в шапке чата и в профиле.

**Метки редактируются только в боте.** В API приложения они доступны лишь на чтение:

- `GET /api/labels?targets=account:42,channel:3,group:17` — пакетный запрос для списка чатов,
  до 100 целей, ответ `{"labels": {"account:42": [{"text","color"}], ...}}`;
- метки уже входят в `GET /api/me`, `GET /api/users/{id}`, `GET /api/channels/{id}`,
  `GET /api/groups/{id}` и в списки пользователей/каналов/групп.

Создание, выдача и снятие — в панели `wgadm` (тот же `ADMIN_ID`):

1. **🏷 Метки** → «➕ Создать новую метку»: текст 1–32 символа, цвет `#RRGGBB`;
2. карточка метки → «Выдать метку» → «👤 Аккаунту» / «📢 Каналу» / «👥 Группе», затем
   Telegram ID или `@username` получателя;
3. карточка метки → «Кому выдана (N)»: список получателей, у каждого кнопка «❌ Снять»;
4. «Удалить шаблон» — снимает метку со всех аккаунтов, каналов и групп и удаляет шаблон.

Ограничения: рядом с именем помещается **3** метки, четвёртую бот не выдаст — сначала снимите
лишнюю. Все действия (выдача, снятие, удаление) пишутся в журнал `admin_logs`
(`label_assign`, `label_unassign`, `label_delete`). Повторная выдача идемпотентна: бот ответит
«Эта метка уже назначена».

## Ограничения

- На старте нужен исходящий доступ к `api.github.com` / `codeload.github.com`; без него работают
  кэш и `bot_standalone.py`.
- Пока контейнер работает, код не обновляется сам — нужен рестарт/редеплой.
- Первый клон репозитория в панели не мгновенный (~200 МБ из-за частей Android-архива). Если это
  станет мешать, части архива можно вынести в GitHub Releases.
