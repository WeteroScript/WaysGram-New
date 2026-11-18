# AuthBot: один репозиторий на хостинге, код тянется из другого

В панели Bothost указан **один** репозиторий — `WaysGram-New`. Код бота при старте
подтягивается из репозитория `WaysGram-AuthBot` и запускается. Тянуть ничего вручную не нужно:
изменил бота в его репозитории — перезапустил контейнер — работает новая версия.

```
BotHost ──clone──► WaysGram-New
                      │  python bot.py
                      ▼
              [authbot-bootstrap]
                      │  1) GitHub: WaysGram-AuthBot@main → commit SHA
                      │  2) скачивает tar.gz, распаковывает в кэш /data/authbot/<sha>
                      ▼
              runpy → <кэш>/bot.py  (uvicorn + aiogram)
```

## Файлы, которые за это отвечают

| Файл | Роль |
| --- | --- |
| `bot.py` | бутстрап: находит нужный коммит, скачивает, кэширует и запускает `bot.py` бота |
| `requirements.txt` | `-r authbot/requirements.txt` — зависимости ставятся на этапе сборки |
| `Procfile` | `web: python bot.py` |
| `runtime.txt` | `python-3.12.8` |
| `.gitignore` | игнорирует `.authbot-cache/`, `.env`, `data/` |
| `authbot/` | резервная копия кода бота (fallback, см. ниже) |

Порядок поиска кода при старте:

1. **remote** (по умолчанию) — берётся коммит из `WaysGram-AuthBot`; если этот коммит уже лежит
   в кэше, сеть вообще не используется;
2. **кэш** — если GitHub недоступен, запускается последний успешно скачанный коммит;
3. **`authbot/`** — если и кэша нет (первый запуск без сети), берётся копия из этого репозитория.
   В логе будет явное предупреждение, что она может отставать.

## Настройки проекта в Bothost

| Параметр | Значение |
| --- | --- |
| Репозиторий | `WeteroScript/WaysGram-New` |
| Ветка | `main` (после мержа PR; можно временно указать рабочую ветку) |
| Язык / версия | Python 3.12 |
| Главный файл | `bot.py` |
| Файл зависимостей | `requirements.txt` |
| Команда запуска (если есть) | `python bot.py` |
| Постоянный каталог | `/data` |

## Переменные окружения

Бутстрап:

- `AUTHBOT_REPO` — репозиторий бота, по умолчанию `WeteroScript/WaysGram-AuthBot`;
- `AUTHBOT_REF` — ветка или тег, по умолчанию `main`;
- `AUTHBOT_COMMIT` — прибить точный коммит (тогда проверка обновлений не выполняется,
  старт мгновенный);
- `AUTHBOT_HOME` — каталог кэша, по умолчанию `/data/authbot` (если `/data` доступен на запись)
  иначе `./.authbot-cache`;
- `AUTHBOT_SOURCE` — `auto` (по умолчанию), `remote` (только сеть, без fallback) или `local`
  (только копия в `authbot/`);
- `AUTHBOT_GITHUB_TOKEN` — если репозиторий бота станет приватным или упрётесь в лимит GitHub API;
- `AUTHBOT_SYNC_DEPS=1` — доустанавливать `requirements.txt` из скачанного коммита при старте.

Сам бот (значения переносятся без изменений из `authbot/.env.example`):

- `BOT_TOKEN`, `TELEGRAM_API_ID`, `TELEGRAM_API_HASH`, `ADMIN_ID`, `OPENAI_API_KEY`;
- `INTERNAL_API_TOKEN`, `TELESRV_OTP_WEBHOOK_SECRET`, `OTP_FERNET_KEY`;
- `DATABASE_PATH=/data/waysgram_authbot.sqlite3`, `MESSENGER_DATABASE_PATH`;
- `INTERNAL_API_HOST=0.0.0.0`, `INTERNAL_API_PORT`, `PORT`;
- `PUBLIC_API_BASE_URL` — публичный HTTPS-адрес API; должен совпадать с секретом
  `WAYSGRAM_API_BASE_URL` в Android-сборке;
- `LOG_LEVEL`.

## Зависимости и код: важная разница

Код тянется из `WaysGram-AuthBot`, а пакеты ставятся из `requirements.txt` **этого** репозитория
во время сборки образа. То есть:

- поменялся только код бота → достаточно перезапустить деплой;
- в боте появились новые пакеты → обновите корневой `requirements.txt`
  (`git subtree pull` обновит и `authbot/requirements.txt`), либо включите `AUTHBOT_SYNC_DEPS=1`
  (медленнее старт, зато код и зависимости всегда совпадают).

## Диагностика

```bash
python bot.py --check     # скачать и распаковать, ничего не запускать; печатает путь и коммит
python bot.py --check --repo WeteroScript/WaysGram-AuthBot --ref main
AUTHBOT_SOURCE=local python bot.py --check   # проверить резервную копию
```

В логах старта ищите строки с префиксом `[authbot-bootstrap]`: там видно, какой коммит взят,
был ли скачан архив и включился ли fallback.

## Обновление резервной копии `authbot/`

Копия обновляется из исходного репозитория через `git subtree`:

```bash
git subtree pull --prefix authbot https://github.com/WeteroScript/WaysGram-AuthBot.git main --squash
git push origin main
```

Она не участвует в обычном запуске, но именно она спасает первый деплой в сети, где GitHub
недоступен.

## Ограничения подхода

- На старте нужен исходящий доступ к `api.github.com` / `codeload.github.com`; без него работает
  кэш, а на самом первом запуске — копия в `authbot/`.
- Репозиторий весит ~200 МБ из-за частей Android-архива, поэтому первый клон на хостинге
  не мгновенный. Если начнёт мешать, части архива можно вынести в GitHub Releases.
- Пока контейнер работает, код не обновляется сам — нужен перезапуск/редеплой (это же и защита
  от неожиданных обновлений в момент, когда бот обслуживает пользователей).
