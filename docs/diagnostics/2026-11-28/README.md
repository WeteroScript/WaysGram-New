# Пробы к разбору дефектов 2026-11-28

Три сценария, которыми проверялись дефекты 2, 4 и 6 из
[`docs/waysgram-bug-causes-2026-11-28.md`](../../waysgram-bug-causes-2026-11-28.md).
Файлы намеренно названы `probe_*.py`, чтобы обычный `pytest -q` их не собирал: это
диагностические сценарии, а не часть тестового набора.

Запуск (нужен интерпретатор с зависимостями `authbot`, например `.venv` проекта):

```bash
python -m pytest docs/diagnostics/2026-11-28/probe_bug4b.py -q -s
python -m pytest docs/diagnostics/2026-11-28/probe_bug2.py  -q -s
python -m pytest docs/diagnostics/2026-11-28/probe_bug6.py  -q -s
```

Они используют фикстуры `authbot/tests/test_messenger_api.py` (`api_env`, `add_account`,
`add_session`, `client_for`) и печатают фактические ответы API, а не проверяют ожидания.

| Файл | Что показывает |
|---|---|
| `probe_bug4b.py` | `pre_history_hidden=1` + авто-членство в группе обсуждений: `messages?message_ids={root}` отдаёт пустой список, а `threads/{root}/replies` — корень. Это и есть причина сообщения «Этот пост был удалён из группы для обсуждения». |
| `probe_bug2.py` | Сервер платных реакций исправен: `POST .../paid-reactions` → 200, `paid_stars`/`my_paid_stars` есть и в `?message_ids=`, и в истории; 403 `PAID_REACTIONS_UNAVAILABLE` — когда платит владелец канала. Значит исчезновение чипа через ~5 с — клиентская реакция на ошибку/пустой ответ. |
| `probe_bug6.py` | Затравка «backdrop Black + цвета маженты» и печать того, что вернул `GET /api/me/gifts`: API подменяет цвета официальной палитрой Black, если пул подарка найден. |

Ни один сценарий не меняет репозиторий: тесты работают на временной БД фикстуры.
