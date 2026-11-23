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

## Статические проверки закрыты

Раньше здесь висел список 12 падающих проверок `scripts/test_android_messaging.py` и
`scripts/test_android_gifts.py`. В этом заходе закрыты все; полный прогон
`pytest scripts/ -q` даёт **168 passed**. Что появилось в исходниках:

* **Аналитика канала и группы.** В `ChatEditActivity` у владельца канала появились строки
  «Платные реакции», «Статистика канала» и «Входящие канала», у владельца группы —
  «Статистика группы». Канал вызывает `WaysGramPaidReactions.showAnalytics` (7/30/90 дней,
  выручка по реакциям, платному медиа, подпискам и входящим), группа —
  `showGroupAnalytics` (`GET /api/groups/{id}/analytics`: `messages_remaining`,
  `unique_authors`).
* **Входящие канала** открываются отдельной строкой настроек (`WaysGramChannelInbox.settings`),
  сам экран — по-прежнему из меню чата.
* **Обсуждения.** В контекстном меню поста канала появился пункт «Группа обсуждений»:
  `WaysGramDiscussions.open(this, currentAccount, currentChat.id, post.getId())` открывает тред
  комментариев. Также в меню появился пункт «Платные реакции» —
  `WaysGramPaidReactions.showPost(...)`.
* **Платные сообщения.** «Сообщения» в приватности при включённом транспорте открывают
  редактор `WaysGramPaidDm.settings` (0–10000 Stars, список исключений), сохранение идёт через
  `PUT /api/me/message-fee`.
* **Бусты и звонки Telegram** скрыты, пока работает WaysGram-транспорт: гейты
  `!WaysGramTransport.ENABLED && ChatObject.isBoostSupported(...)` в шапке чата и
  `!WaysGramTransport.ENABLED && ChatObject.isChannelAndNotMegaGroup(...)` для строки
  «Статистика и бусты» в настройках, `!waysGramBroadcast && ChatObject.canManageCalls(...)` в
  профиле канала и группы.
* **Реакции.** Локальное меню реакций доступно и без серверного списка
  (`isReactionsAvailable = waysGramReactionMenu`), нативный просмотрщик реакций скрыт
  (`isReactionsViewAvailable = !WaysGramTransport.ENABLED`), в палитру добавлена 😢.
* **Индикатор набора** поддерживает обычные группы (`TL_inputPeerChat`), сервер уже принимает
  `scope=group`.
* **Секретные номера** проверяются `WaysGramMapper.isSecretNumberDigits(text)` на вводе логина.

Заодно исправлен серверный баг, из-за которого корневое сообщение треда (копия поста канала в
группе обсуждений) попадало в обычную историю группы: теперь `GET /api/groups/{id}/messages`
его пропускает (`NOT EXISTS` по `channel_post_discussions`), а тред комментариев продолжает
работать. Тест `test_channel_discussion_comments_use_group_permissions_and_moderation` зелёный.

APK не собирался, на устройстве ничего не проверялось — только статические проверки исходников
и серверные pytest.
