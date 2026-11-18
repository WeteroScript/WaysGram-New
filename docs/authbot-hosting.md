# AuthBot на хостинге из монорепозитория WaysGram-New

AuthBot (`authbot/`) теперь лежит в этом же репозитории, что и Android-клиент. В настройках
Bothost (или любого похожего хостинга) указывается **один** репозиторий — `WaysGram-New` — и он же
запускает бота из корня. Отдельный репозиторий `WaysGram-AuthBot` больше не нужен для деплоя.

## Раскладка

```
WaysGram-New/
├── bot.py              # точка входа для хостинга: подключает authbot/ и запускает authbot/bot.py
├── requirements.txt    # "-r authbot/requirements.txt" — хостинг ставит зависимости из корня
├── Procfile            # web: python bot.py
├── runtime.txt         # python-3.12.8
├── authbot/            # код бота целиком (bot.py, messenger_api.py, db_split.py, tests/, scripts/)
└── .github/workflows/  # build-apk.yml (Android) и authbot.yml (тесты бота)
```

`bot.py` в корне — маленький шим: он добавляет `authbot/` в `sys.path`, поэтому плоские импорты
внутри бота (`import messenger_api`, `import db_split`, `import telegram_mtproto`) продолжают
работать, и выполняет `authbot/bot.py` как `__main__`. Ничего в самом коде бота менять не нужно.

## Настройки проекта в Bothost

| Параметр | Значение |
| --- | --- |
| Репозиторий | `WeteroScript/WaysGram-New` |
| Ветка | `main` (после мержа PR; до этого — рабочая ветка) |
| Язык | Python |
| Версия Python | 3.12 (см. `runtime.txt`) |
| Главный файл / точка входа | `bot.py` |
| Файл зависимостей | `requirements.txt` |
| Команда запуска (если поле есть) | `python bot.py` |
| Постоянное хранилище | каталог `/data` (SQLite и Telethon-сессии) |

Если хостинг позволяет указать путь внутри репозитория, можно вместо шима поставить точку входа
`authbot/bot.py` и файл зависимостей `authbot/requirements.txt` — результат тот же.

## Переменные окружения

Значения берутся из `authbot/.env.example`; сам `.env` в Git не попадает и на хостинге не нужен —
все значения задаются в панели:

- `BOT_TOKEN` — токен бота от @BotFather;
- `TELEGRAM_API_ID`, `TELEGRAM_API_HASH` — доступ к MTProto;
- `ADMIN_ID` — Telegram ID администратора;
- `OPENAI_API_KEY` — если используются AI-функции;
- `INTERNAL_API_TOKEN` — общий секрет между Android-клиентом и API бота;
- `TELESRV_OTP_WEBHOOK_SECRET` — секрет вебхука OTP;
- `OTP_FERNET_KEY` — ключ шифрования OTP;
- `DATABASE_PATH=/data/waysgram_authbot.sqlite3` — база на постоянном томе;
- `MESSENGER_DATABASE_PATH` — база мессенджера, если используется отдельно;
- `INTERNAL_API_HOST=0.0.0.0`, `INTERNAL_API_PORT` и `PORT` — адрес и порт HTTP API;
- `PUBLIC_API_BASE_URL=https://bot-1793554244-6434-weterochina.bothost.tech` — публичный HTTPS-адрес
  API. Он должен совпадать с секретом `WAYSGRAM_API_BASE_URL` в GitHub Actions Android-сборки,
  иначе клиент не достучится до бэкенда;
- `LOG_LEVEL` — уровень логирования.

## Деплой и обновления

1. В панели BotHost сменить репозиторий проекта на `WeteroScript/WaysGram-New`, ветку — на `main`,
   точку входа — на `bot.py`, зависимости — на `requirements.txt`.
2. Перенести переменные окружения (значения не меняются).
3. Убедиться, что постоянный каталог `/data` сохранён, и что после первого деплоя в нём лежит
   прежняя `waysgram_authbot.sqlite3` — иначе пользователи и подарки «обнулятся».
4. Нажать «Деплой» и проверить `GET https://<домен>/` и `GET https://<домен>/api/telegram/gifts`.

Дальше любая правка в `authbot/` и `push` в `main` пересобирает бота автоматически. Правки в
Android-части запускают только сборку APK: у workflow `build-apk.yml` стоит `paths-ignore` на
`authbot/**`, а у `authbot.yml` — фильтр на `authbot/**`, поэтому чужие сборки не тратятся.

## Синхронизация с историческим репозиторием

`authbot/` импортирован через `git subtree`, поэтому историю можно тянуть и пушить обратно:

```bash
# забрать свежие изменения из старого репозитория
git subtree pull --prefix authbot https://github.com/WeteroScript/WaysGram-AuthBot.git main --squash

# отправить изменения из монорепозитория обратно
git subtree push --prefix authbot https://github.com/WeteroScript/WaysGram-AuthBot.git main
```

Если старый репозиторий больше не нужен, его можно архивировать (read-only) — деплой от него не
зависит.

## Почему не «репозиторий, который тянет код из другого репозитория»

Такой вариант технически возможен: хостинг клонирует `WaysGram-New`, а код в рантайме клонирует
`WaysGram-AuthBot`. Но это лишний сетевой запрос на каждом холодном старте, отдельные токены доступа
для приватного репозитория и расхождение версий клиента и API. Монорепозиторий с `git subtree`
даёт то же удобство без этих проблем.

## Известное ограничение

Репозиторий весит около 100 МБ из-за пяти частей Android-архива, поэтому первый клон на хостинге
занимает больше времени, чем у обычного бота. Если это станет мешать, части архива можно вынести
в GitHub Releases и скачивать их в workflow — код бота от этого не зависит.
