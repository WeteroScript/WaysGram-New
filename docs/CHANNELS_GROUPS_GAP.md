# Каналы и группы: что есть, чего нет

Источник правды — два слоя:

* **сервер** `authbot/messenger_api.py` (REST, 71 эндпоинт `/api/channels…`, 60+ `/api/groups…`);
* **клиент** `WaysGramTransport.handle()` — диспетчер MTProto → REST. Если метод туда не добавлен,
  приложение получает `METHOD_NOT_SUPPORTED` и показывает «функция не поддерживается WaysGram».

Telegram-клиент уже умеет все эти экраны; менять UI не нужно. Не хватало **серверного API**:
ответов на те TL-методы, которые клиент отправляет. Ниже — карта по пунктам задания.

Обозначения: ✅ работает, 🟡 частично (работает, но не всё), ❌ клиент отправляет метод, диспетчера нет.

## Каналы

| Пункт | Сервер | Клиент |
| --- | --- | --- |
| Создание канала | ✅ `POST /api/channels` | ✅ `channels.createChannel` |
| Удаление канала | ✅ `DELETE /api/channels/{id}` | ✅ `channels.deleteChannel` |
| Изменение названия | ✅ `PUT /api/channels/{id}` | ✅ `channels.editTitle` |
| Изменение фотографии | 🟡 `PUT /api/channels/{id}` (нет поля фото) | ❌ `channels.editPhoto` |
| Изменение описания | ✅ `PUT /api/channels/{id}` | ❌ `messages.editChatAbout`/`channels.editAbout` не подключены |
| Установка username | ✅ `PUT /api/channels/{id}` | ✅ `channels.updateUsername` |
| Проверка username | ✅ | ✅ `channels.checkUsername` |
| Просмотр информации | ✅ `GET /api/channels/{id}` | ✅ `channels.getFullChannel` |
| Просмотр подписчиков | ✅ `GET …/members` | ✅ `channels.getParticipants` |
| Информация об участнике | ✅ `GET …/members/{tg}` | ✅ `channels.getParticipant` |
| Приглашение пользователей | ✅ `POST …/members` | ✅ `channels.inviteToChannel` |
| Вступление в канал | ✅ `POST /api/channels/join`, `…/membership` | ✅ `channels.joinChannel` |
| Выход из канала | ✅ `PUT …/membership` | ✅ `channels.leaveChannel` |
| Назначение администраторов | ✅ `POST …/admins` | ✅ `channels.editAdmin` |
| Снятие администраторов | ✅ `DELETE …/admins/{tg}` | ✅ `channels.editAdmin` |
| Права администраторов | ✅ | ✅ `channels.editAdmin` |
| Блокировка / разблокировка | ✅ `PUT …/members/{tg}/ban` | ✅ `channels.editBanned` |
| Публикация текста | ✅ `POST …/messages` | ✅ `messages.sendMessage` |
| Публикация медиа | ✅ `POST …/messages`, `…/album` | ✅ `messages.sendMedia`, `sendMultiMedia` |
| Редактирование публикаций | ✅ `PUT …/messages/{id}` | ✅ `messages.editMessage` |
| Удаление публикаций | ✅ `DELETE …/messages/{id}` | ✅ `channels.deleteMessages` |
| Пересылка публикаций | ✅ | ✅ `messages.forwardMessages` |
| Закрепление / открепление | ✅ | ✅ `messages.updatePinnedMessage`, `unpinAllMessages` |
| История публикаций | ✅ `GET …/messages` | ✅ `messages.getHistory` |
| Отметка прочитанным | ✅ `read_markers` | ✅ `channels.readHistory` |
| Подписи авторов | 🟡 только `post`-флаг | ❌ `channels.toggleSignatures` |
| Привязка группы обсуждений | ✅ `PUT …/discussion-group` | 🟡 своя строка в настройках, нативный `channels.setDiscussionGroup` — ❌ |
| Список групп для обсуждения | ✅ `GET /api/groups` | ❌ `channels.getGroupsForDiscussion` |
| Пригласительные ссылки (создать/изменить/удалить/список) | ✅ `…/invites…` | ✅ `messages.exportChatInvite`, `editExportedChatInvite`, `deleteExportedChatInvite`, `getExportedChatInvites` |
| Заявки на вступление | ✅ `…/join-requests…` | ✅ `getChatInviteImporters`, `hideChatJoinRequest`; одобрение/отклонение — ✅ |
| Посты по id (ответы, закреп, альбомы) | ✅ `?message_ids=` | ✅ **добавлено:** `channels.getMessages`, `channels.readMessageContents` |
| Медленный режим канала | ❌ нет поля | ❌ `channels.toggleSlowMode` |
| Скрытие участников / прежней истории | ❌ нет полей | ❌ `channels.toggleParticipantsHidden`, `togglePreHistoryHidden` |
| Журнал действий | 🟡 `GET …/analytics` | ❌ `channels.getAdminLog` |
| Поиск по постам | ✅ `GET …/messages/search` | ❌ `channels.searchPosts` (обычный `messages.search` работает) |
| Заявки «на вход» (join to send) | ❌ | ❌ `channels.toggleJoinToSend`, `toggleJoinRequest` |
| Цвет/эмодзи-статус канала | ❌ | ❌ `channels.updateColor`, `updateEmojiStatus` |
| Спонсорские отчёты | — | ✅ закрыто заглушкой (не показывают ошибку) |
| Неактивные каналы | — | ✅ закрыто заглушкой (пустой список) |
| Репорт спама | — | ✅ закрыто заглушкой (true) |

## Группы

| Пункт | Сервер | Клиент |
| --- | --- | --- |
| Создание / удаление | ✅ `POST`, `DELETE /api/groups/{id}` | ✅ `messages.createChat`, `deleteChat` |
| Название / описание | ✅ `PUT /api/groups/{id}` | ✅ `messages.editChatTitle`, `editChatAbout` |
| Фотография | 🟡 тот же `PUT` | ❌ `messages.editChatPhoto` не подключён |
| Информация о группе | ✅ `GET /api/groups/{id}` | ✅ `messages.getFullChat` |
| Участники и их данные | ✅ | ✅ `getFullChat`, `channels.getParticipants` (для супергрупп) |
| Приглашение / вступление / выход | ✅ `…/members`, `…/invites`, `/api/groups/join` | ✅ `addChatUser`, `importChatInvite`, `deleteChatUser` |
| Администраторы, их права | ✅ `…/admins` | ✅ `messages.editChatAdmin` |
| Блокировка / разблокировка / ограничение | ✅ `…/members/{tg}/mute`, ban | ✅ `channels.editBanned`, `editBanned` |
| Текст / медиа / альбомы | ✅ | ✅ `sendMessage`, `sendMedia`, `sendMultiMedia` |
| Редактирование / удаление / пересылка | ✅ | ✅ |
| История / закреп / откреп | ✅ | ✅ |
| Медленный режим | ✅ `PUT …/slow-mode` | ❌ `channels.toggleSlowMode`, `messages.setChat…` |
| Заявки на вступление + одобрение/отклонение | ✅ `…/join-requests…` | ✅ (invite importers), кнопки — ✅ |
| Скрытие списка участников | ✅ `PUT …/member-visibility` | ❌ `channels.toggleParticipantsHidden` |
| Скрытие прежней истории | ❌ нет поля | ❌ `channels.togglePreHistoryHidden` |
| Пригласительные ссылки | ✅ `…/invites`, `…/permanent-invite` | ✅ |

## Что сделано в этом заходе

1. `channels.getMessages` — посты канала по id одним запросом (ответы, закреп, альбомы).
   Раньше любой ответ на пост и открытие закрепа давали «функция не поддерживается».
2. `channels.readMessageContents`, `messages.readMessageContents` — подтверждение без ошибки.
3. `messages.getMessages` (список id без пира, служебные запросы кэша файлов) — пустой ответ
   вместо ошибки: тост больше не появляется.
4. `channels.getInactiveChannels` — пустой список; `channels.reportSpam` — `true`.

## Следующие заходы (по одному пункту за раз)

1. Фото канала/группы: `channels.editPhoto`, `messages.editChatPhoto` + `avatar_media_id` в `PUT`.
2. Флаги канала: `toggleSignatures`, `toggleSlowMode`, `toggleParticipantsHidden`,
   `togglePreHistoryHidden` + поля в `PUT /api/channels/{id}`.
3. Обсуждения: `channels.getGroupsForDiscussion`, `channels.setDiscussionGroup` нативно.
4. `channels.getAdminLog`, `channels.searchPosts`, `channels.updateColor`, `updateEmojiStatus`.
5. Заявки на вход: `toggleJoinToSend`, `toggleJoinRequest` + поля на сервере.
6. `messages.toggleNoForwards`, `messages.setChatTheme` и прочие «мелочи» групп.
