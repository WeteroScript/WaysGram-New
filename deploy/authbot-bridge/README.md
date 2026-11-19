# Мост: BotHost → WaysGram-AuthBot → код из WaysGram-New/authbot

Схема, которая тебе нужна:

```
BotHost (репозиторий = WaysGram-AuthBot)
   │  python bot.py            ← файл из этой папки
   ▼
[launcher] тянет только папку authbot/ из WeteroScript/WaysGram-New
   ▼
runpy → authbot/bot.py  (uvicorn + aiogram, код из WaysGram-New)
```

То есть в панели BotHost остаётся прежний репозиторий `WeteroScript/WaysGram-AuthBot`, а весь код
бота ты правишь в `WaysGram-New/authbot/` — лаунчер подхватит изменения при следующем рестарте.

## Что сделать один раз в репозитории WaysGram-AuthBot

1. Сохранить старую точку входа как резервную (на случай первого старта без доступа к GitHub):

   ```bash
   git clone https://github.com/WeteroScript/WaysGram-AuthBot.git
   cd WaysGram-AuthBot
   git mv bot.py bot_standalone.py
   ```

2. Положить лаунчер как новый `bot.py`:

   ```bash
   curl -fsSL -o bot.py \
     https://raw.githubusercontent.com/WeteroScript/WaysGram-New/main/deploy/authbot-bridge/bot.py
   git add bot.py bot_standalone.py
   git commit -m "Run the bot from WaysGram-New/authbot"
   git push origin main
   ```

   Если ветка `main` в `WaysGram-New` ещё не содержит `deploy/authbot-bridge/`, возьми файл из
   рабочей ветки или скопируй его вручную.

3. В BotHost ничего не меняется: репозиторий `WeteroScript/WaysGram-AuthBot`, Python 3.12,
   главный файл `bot.py`, зависимости `requirements.txt`, команда `python bot.py`.
   Переменные окружения тоже остаются прежними (см. `authbot/.env.example`).

## Как это работает

Порядок поиска кода при старте:

1. **api** (по умолчанию) — один запрос резолвит коммит `WaysGram-New@main`, второй отдаёт дерево
   файлов, остальные (~25 штук, ~1.2 МБ) скачивают только `authbot/**`. Остальные ~200 МБ
   Android-репозитория не скачиваются вообще.
2. **archive** — если GitHub API упёрся в лимит (60 запросов/час на IP без токена), скачивается
   архив репозитория, но распаковывается из него только `authbot/`.
3. **кэш** — если GitHub недоступен, берётся последний удачно скачанный коммит из
   `/data/waysgram-new/<commit>/authbot`.
4. **`bot_standalone.py`** — самая последняя страховка: старый код, лежащий в этом репозитории.

Логи старта помечены префиксом `[waysgram-launcher]`, в них видно коммит и источник.

## Переменные окружения (все необязательные)

| Переменная | Значение по умолчанию | Зачем |
| --- | --- | --- |
| `WAYSGRAM_REPO` | `WeteroScript/WaysGram-New` | откуда брать код |
| `WAYSGRAM_REF` | `main` | ветка или тег |
| `WAYSGRAM_COMMIT` | — | прибить коммит, старт без проверки обновлений |
| `WAYSGRAM_SUBDIR` | `authbot` | папка с кодом бота |
| `WAYSGRAM_HOME` | `/data/waysgram-new` | кэш коммитов |
| `WAYSGRAM_MODE` | `auto` | `auto` / `api` / `archive` / `local` |
| `WAYSGRAM_LOCAL_ENTRY` | `bot_standalone.py` | резервная копия старого бота |
| `AUTHBOT_GITHUB_TOKEN` | — | приватный репозиторий или лимит API |
| `AUTHBOT_SYNC_DEPS` | `0` | `1` — доустанавливать `requirements.txt` из скачанного коммита |

Плюс обычные переменные бота: `BOT_TOKEN`, `TELEGRAM_API_ID`, `TELEGRAM_API_HASH`, `ADMIN_ID`,
`OPENAI_API_KEY`, `INTERNAL_API_TOKEN`, `TELESRV_OTP_WEBHOOK_SECRET`, `OTP_FERNET_KEY`,
`DATABASE_PATH=/data/waysgram_authbot.sqlite3`, `MESSENGER_DATABASE_PATH`, `INTERNAL_API_HOST`,
`INTERNAL_API_PORT`, `PORT`, `PUBLIC_API_BASE_URL`, `LOG_LEVEL`.

## Диагностика

```bash
python bot.py --check                          # покажет коммит и путь, ничего не запуская
WAYSGRAM_REF=arena/00b4c974-waysgram-new python bot.py --check
WAYSGRAM_MODE=archive python bot.py --check    # проверить резервный путь через архив
python bot.py --check --repo WeteroScript/WaysGram-New --subdir authbot
```

## Что важно помнить

- **Зависимости** ставятся из `requirements.txt` репозитория `WaysGram-AuthBot` на этапе сборки,
  а код приходит из `WaysGram-New`. Если в `authbot/requirements.txt` появились новые пакеты,
  перенеси их в `requirements.txt` лаунчера или включи `AUTHBOT_SYNC_DEPS=1`.
- Пока контейнер работает, код не обновляется: обновление происходит при рестарте/редеплое.
- Без доступа к GitHub работает кэш `/data/waysgram-new`, а на самом первом старте —
  `bot_standalone.py`.
