# Профиль группы и канала: присутствие, общие чаты, жалоба, приглашения админов

Закрытые дыры, из-за которых экран профиля и список ссылок показывали ошибку
«функция не поддерживается WaysGram»: транспорт не разбирал методы, а сервер не имел
соответствующих ответов. Правки — в архиве `WaysGram-Android.zip.part1…part5`
(он источник правды для сборки) и в `authbot/messenger_api.py`.

## Что добавлено

| Метод Telegram | Сервер | Клиент |
| --- | --- | --- |
| `messages.getOnlines` | `GET /api/groups/{id}/onlines` | `WaysGramChats.onlines` → `TL_chatOnlines` |
| `messages.getCommonChats` | `GET /api/users/{id}/common-chats` | `WaysGramChats.commonChats` → `TL_messages_chats` |
| `messages.getPeerSettings` | не нужен | `WaysGramChats.peerSettings` → пустой `TL_peerSettings` |
| `messages.reportSpam` | `POST /api/reports` | `WaysGramChats.reportPeer` → `boolTrue` |
| `messages.getAdminsWithInvites` | `GET /api/{channels,groups}/{id}/admins-with-invites` | `WaysGramInvites.adminsWithInvites` |

### Присутствие

Сервер считает участников группы, которые обращались к API за последние пять минут
(`api_user_presence.last_seen_at`). Счётчик доступен только участникам группы: посторонний
получает `403`. Для каналов метод не используется — счётчик живых подписчиков Telegram
не показывает.

### Общие группы

`GET /api/users/{target_id}/common-chats` отдаёт группы, где состоят оба пользователя, с
полями `id`, `title`, `member_count`, `my_role`, `admin_rights`. Клиент превращает их в
`TL_channel` с `megagroup = true` через существующий `WaysGramChats.toMegagroup`, поэтому
экран «Общие группы» открывается тем же списком, что и остальные группы.

### Настройки собеседника

`messages.getPeerSettings` раньше давал ошибку при открытии профиля. Теперь клиент отвечает
пустым набором флагов: WaysGram не предлагает «Добавить в контакты» или «Пожаловаться»
автоматически, а экран получает корректный ответ.

### Жалоба на чат или пользователя

`messages.reportSpam` отправляет запись в `POST /api/reports` (`scope` по типу пира,
`option = "spam"`, `peer_id`). Сервер теперь принимает жалобу без списка сообщений —
раньше такой запрос отвергался `400`, потому что жалоба всегда привязывалась к сообщениям.
Пустой `message_ids` разрешён только при непустом `option`; жалоба с конкретными сообщениями
работает как раньше.

### Приглашения администраторов

`GET /api/channels/{id}/admins-with-invites` и `GET /api/groups/{id}/admins-with-invites`
возвращают владельца и администраторов с числом активных и отозванных ссылок. Каналы:
доступ только администраторам канала. Группы: нужно право «приглашать участников» (бит 2),
иначе `403`. Клиент собирает `TL_messages_chatAdminsWithInvites` и список пользователей через
`WaysGramMapper.toUser`.

## Тесты

* `authbot/tests/test_chat_profile_endpoints.py` — 4 теста: общие группы, присутствие,
  статистика ссылок администраторов (канал и группа), жалоба без id сообщений и с ними.
* `scripts/test_android_chat_profile_endpoints.py` — 5 статических проверок исходников:
  транспорт разбирает методы, пути эндпоинтов совпадают, ответы маппятся.

APK не собирался, проверка на устройстве не выполнялась.

## Что осталось из статических проверок

`scripts/test_android_messaging.py` и `scripts/test_android_gifts.py` содержат 12 проверок,
которые падают и на исходном архиве ветки (то есть архив отстаёт от тестов). Это и есть
следующий список работ по каналам и группам:

1. `test_channel_analytics_is_owner_only_and_reads_real_revenue` — аналитика канала только
   владельцу и с реальной выручкой.
2. `test_channel_creation_and_subscriber_requests_use_waysgram_routes` — создание канала и
   заявки подписчиков через роуты WaysGram.
3. `test_channel_discussions_use_owned_groups_and_real_comment_threads` — обсуждения через
   собственные группы и настоящие треды комментариев.
4. `test_channel_inbox_uses_distinct_owner_and_reader_flows_and_confirms_fee` — «Входящие»
   канала: отдельные сценарии владельца и читателя, подтверждение цены.
5. `test_group_analytics_uses_group_endpoint_and_activity_metrics` — аналитика группы.
6. `test_local_reaction_picker_and_bubble_fallback`,
   `test_paid_channel_reactions_require_confirmation_and_reuse_pending_key`,
   `test_paid_direct_text_confirms_exact_fee_and_preserves_retry` — реакции и платные действия.
7. `test_secret_numbers_are_grouped_and_valid_at_login` — формат секретных номеров.
8. `test_typing_bridge_is_scoped_serial_and_ephemeral` — индикатор набора.
9. `test_waysgram_chat_refresh_does_not_request_telegram_boosts` — обновление чата без
   запросов бустов Telegram.
10. `test_profile_uses_actual_owner_and_supports_nested_scrolling` (подарки) — профиль и
    вложенный скролл.
