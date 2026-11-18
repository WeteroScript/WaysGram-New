# Промпт для агента, работающего в репозитории `WaysGram-AuthBot`

Скопируй всё, что ниже разделителя, в задачу другому агенту (у него должен быть доступ к
репозиторию `WeteroScript/WaysGram-AuthBot`).

---

Ты работаешь в репозитории `https://github.com/WeteroScript/WaysGram-AuthBot` (Python 3.12,
aiogram 3 + FastAPI + Telethon, точка входа `bot.py`).

Задача: превратить этот репозиторий в тонкий лаунчер. Хостинг BotHost указывает именно на него,
а **весь код бота теперь живёт в репозитории `WeteroScript/WaysGram-New`, папка `authbot/`**.
Лаунчер при каждом старте скачивает эту папку и запускает её.

Ничего не выдумывай: лаунчер уже написан и протестирован, его нужно только перенести.

## 1. Сохрани текущую точку входа как резервную

```bash
git mv bot.py bot_standalone.py
```

Файл `bot_standalone.py` менять нельзя: это аварийный запуск старого кода, если на самом первом
старте у контейнера не будет доступа к GitHub. Его соседние модули (`messenger_api.py`,
`db_split.py`, `otp_webhook.py`, `telegram_mtproto.py`) остаются на месте.

## 2. Положи лаунчер как новый `bot.py`

Скопируй файл `deploy/authbot-bridge/bot.py` из репозитория `WeteroScript/WaysGram-New`.
Сначала пробуй ветку `main`, а если файла там ещё нет — ветку `arena/00b4c974-waysgram-new`
(она содержит лаунчер; после мержа PR файл появится и в `main`).

```bash
# вариант 1: raw
curl -fsSL -o bot.py \
  https://raw.githubusercontent.com/WeteroScript/WaysGram-New/arena/00b4c974-waysgram-new/deploy/authbot-bridge/bot.py

# вариант 2: если raw недоступен, через API (файл приходит в base64)
curl -fsSL -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/WeteroScript/WaysGram-New/contents/deploy/authbot-bridge/bot.py?ref=arena/00b4c974-waysgram-new" \
  | python -c "import base64,json,sys; open('bot.py','wb').write(base64.b64decode(json.load(sys.stdin)['content']))"
```

Требования к результату:

- `bot.py` в корне репозитория — это лаунчер (в начале файла строка вида
  `"""Launcher that runs the WaysGram bot code stored in the WaysGram-New repository.`),
  а не старая реализация бота;
- лаунчер использует только стандартную библиотеку Python — новых зависимостей не появляется;
- никакого копирования `authbot/` внутрь этого репозитория делать не нужно.

## 3. Почини импорты в тестах

Девять тестов импортируют старую точку входа напрямую (`import bot as bot_module`), после замены
они сломаются. Переключи их на сохранённую копию:

```bash
grep -rl "import bot as bot_module" tests/ | xargs sed -i 's/import bot as bot_module/import bot_standalone as bot_module/'
grep -rn "import bot as bot_module" tests/ || echo "imports fixed"
```

Ничего другого в тестах не меняй. Канонические тесты бота теперь гоняются в репозитории
`WaysGram-New` (workflow `authbot.yml`), здесь достаточно, чтобы прежний набор оставался зелёным.

## 4. Не трогай то, что уже настроено

- `requirements.txt` — зависимости устанавливаются при сборке образа; не удаляй и не сокращай
  список. Если в `WaysGram-New/authbot/requirements.txt` появились новые пакеты, добавь их сюда
  (это единственная причина править файл);
- `runtime.txt`, `Procfile` (`web: python bot.py`), `Dockerfile` (`CMD ["python", "bot.py"]`),
  `HEALTHCHECK` — работают без изменений, лаунчер подхватывается автоматически;
- `docker-compose.yml`, `.dockerignore`, `.gitignore` — без изменений;
- `.env` в репозиторий не добавлять; секреты не коммитить.

## 5. Дополни `.env.example` описанием переменных лаунчера

Добавь в конец файла блок с комментариями (значения пустые, все переменные необязательные):

```
# --- Лаунчер: откуда брать код бота (все необязательные) ---
# Репозиторий с кодом бота
WAYSGRAM_REPO=WeteroScript/WaysGram-New
# Ветка или тег; пока PR не смержен, укажи arena/00b4c974-waysgram-new
WAYSGRAM_REF=main
# Прибить конкретный коммит (тогда проверка обновлений не выполняется)
# WAYSGRAM_COMMIT=
# Папка с кодом внутри репозитория
WAYSGRAM_SUBDIR=authbot
# Куда кэшировать скачанные коммиты (по умолчанию /data/waysgram-new)
# WAYSGRAM_HOME=/data/waysgram-new
# auto | api | archive | local
# WAYSGRAM_MODE=auto
# 1 = доустанавливать requirements.txt из скачанного коммита при старте
# AUTHBOT_SYNC_DEPS=0
# Токен GitHub, если репозиторий станет приватным или упрётся в лимит API
# AUTHBOT_GITHUB_TOKEN=
```

## 6. Обнови README

В разделе про деплой напиши коротко и по делу:

1. BotHost указывает на этот репозиторий; главный файл — `bot.py`, зависимости — `requirements.txt`,
   команда запуска — `python bot.py`.
2. Код бота больше не лежит здесь: он в `WeteroScript/WaysGram-New/authbot` и подтягивается при
   старте (GitHub API, только файлы `authbot/**`, ~1.2 МБ; кэш в `/data/waysgram-new/<commit>`).
3. Порядок источников: API → архив при лимите API → кэш → `bot_standalone.py`.
4. Правки бота делаются в `WaysGram-New`, после рестарта/редеплоя подхватываются автоматически.
5. Диагностика: `python bot.py --check`; строки лога с префиксом `[waysgram-launcher]`.
6. Зависимости ставятся из `requirements.txt` этого репозитория — при новых пакетах в боте
   синхронизируй файл или включи `AUTHBOT_SYNC_DEPS=1`.

## 7. Проверь себя перед коммитом

```bash
python -m compileall -q bot.py bot_standalone.py
python bot.py --check                     # JSON: source .../authbot, entry .../authbot/bot.py
python bot.py --check                     # второй запуск: в логе "using cached commit"
python -m pytest -q                       # весь прежний набор зелёный
```

Ожидаемый вывод `--check`: JSON с полями `source`, `entry`, `repo`, `ref`, `subdir` (`authbot`),
`commit`, `cache_home`. Если вместо этого ошибка, проверь доступ к `api.github.com` и
`codeload.github.com` и правильность `WAYSGRAM_REF`.

## 8. Коммит и пуш

```bash
git add bot.py bot_standalone.py .env.example README.md tests/
git commit -m "Run the bot from WaysGram-New/authbot"
git push origin main
```

В настройках BotHost ничего не меняй: репозиторий остаётся `WeteroScript/WaysGram-AuthBot`,
Python 3.12, главный файл `bot.py`, зависимости `requirements.txt`, команда `python bot.py`,
постоянный каталог `/data`. Пока `authbot/` не появился в `main` репозитория `WaysGram-New`,
в панели нужна переменная `WAYSGRAM_REF=arena/00b4c974-waysgram-new`; после мержа её можно убрать.

## Границы задачи

- не переписывай сам код бота: `messenger_api.py`, `db_split.py`, `otp_webhook.py`,
  `telegram_mtproto.py`, `tests/` (кроме строк импорта из п.3) остаются как есть;
- не добавляй новые зависимости и не меняй версии пакетов;
- не настраивай GitHub Actions заново и не удаляй существующие workflow;
- если какой-то шаг невозможен (нет доступа к GitHub, файл лаунчера не найден) — остановись и
  напиши, какой именно шаг и почему не выполнен, вместо того чтобы писать лаунчер с нуля.
