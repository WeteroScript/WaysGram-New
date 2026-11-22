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
| Изменение фотографии | ✅ `PUT/DELETE /api/channels/{id}/avatar`, `avatar_media_id` в выдаче | ✅ `channels.editPhoto` |
| Изменение описания | ✅ `PUT /api/channels/{id}` | ✅ `messages.editChatAbout` |
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
| Подписи авторов | ✅ `PUT …/settings` (`signatures_enabled`, `signatures_profiles`) | ✅ `channels.toggleSignatures` |
| Привязка группы обсуждений | ✅ `PUT …/discussion-group` | ✅ `channels.setDiscussionGroup` (+ `linked_chat_id` в полном инфо) |
| Список групп для обсуждения | ✅ `GET /api/groups` | ✅ `channels.getGroupsForDiscussion` |
| Пригласительные ссылки (создать/изменить/удалить/список) | ✅ `…/invites…` | ✅ `messages.exportChatInvite`, `editExportedChatInvite`, `deleteExportedChatInvite`, `getExportedChatInvites` |
| Заявки на вступление | ✅ `…/join-requests…` | ✅ `getChatInviteImporters`, `hideChatJoinRequest`; одобрение/отклонение — ✅ |
| Посты по id (ответы, закреп, альбомы) | ✅ `?message_ids=` | ✅ **добавлено:** `channels.getMessages`, `channels.readMessageContents` |
| Медленный режим канала | ✅ `PUT …/settings` (`slow_mode_seconds`) | ✅ `channels.toggleSlowMode` |
| Скрытие участников / прежней истории | ✅ `PUT …/settings` | ✅ `channels.toggleParticipantsHidden`, `togglePreHistoryHidden` |
| Журнал действий | ✅ `GET …/admin-log` (title/about/username/photo, вход/выход, бан, админы, slow mode, обсуждения) | ✅ `channels.getAdminLog` |
| Поиск по постам | ✅ `GET …/messages/search` + `GET /api/channels/posts/search` | ✅ `channels.searchPosts` |
| Заявки «на вход» (join to send) | ✅ `PUT …/settings` | ✅ `channels.toggleJoinToSend`, `toggleJoinRequest` |
| Цвет/эмодзи-статус канала | ✅ `PUT …/settings` (`color_id`, `background_emoji_id`, `profile_*`, `emoji_status_id`) | ✅ `channels.updateColor`, `updateEmojiStatus` |
| Спонсорские отчёты | — | ✅ закрыто заглушкой (не показывают ошибку) |
| Неактивные каналы | — | ✅ закрыто заглушкой (пустой список) |
| Репорт спама | — | ✅ закрыто заглушкой (true) |

## Группы

| Пункт | Сервер | Клиент |
| --- | --- | --- |
| Создание / удаление | ✅ `POST`, `DELETE /api/groups/{id}` | ✅ `messages.createChat`, `deleteChat` |
| Название / описание | ✅ `PUT /api/groups/{id}` | ✅ `messages.editChatTitle`, `editChatAbout` |
| Фотография | ✅ `PUT/DELETE /api/groups/{id}/avatar`, `avatar_media_id` в выдаче | ✅ `messages.editChatPhoto` |
| Информация о группе | ✅ `GET /api/groups/{id}` | ✅ `messages.getFullChat` |
| Участники и их данные | ✅ | ✅ `getFullChat`, `channels.getParticipants` (для супергрупп) |
| Приглашение / вступление / выход | ✅ `…/members`, `…/invites`, `/api/groups/join` | ✅ `addChatUser`, `importChatInvite`, `deleteChatUser` |
| Администраторы, их права | ✅ `…/admins` | ✅ `messages.editChatAdmin` |
| Блокировка / разблокировка / ограничение | ✅ `…/members/{tg}/mute`, ban, `…/send-permissions` | ✅ `channels.editBanned`, `editBanned`, `messages.editChatDefaultBannedRights` |
| Текст / медиа / альбомы | ✅ | ✅ `sendMessage`, `sendMedia`, `sendMultiMedia` |
| Редактирование / удаление / пересылка | ✅ | ✅ |
| История / закреп / откреп | ✅ | ✅ |
| Медленный режим | ✅ `PUT …/settings` (`slow_mode_seconds`) | 🟡 сервер готов, в UI базовой группы строки нет (Telegram показывает её только для супергрупп) |
| Заявки на вступление + одобрение/отклонение | ✅ `…/join-requests…` | ✅ (invite importers), кнопки — ✅ |
| Скрытие списка участников | ✅ `PUT …/settings` (`members_hidden`) | 🟡 сервер готов, в UI базовой группы строки нет (Telegram показывает её только для супергрупп) |
| Скрытие прежней истории | ✅ `PUT …/settings` (`no_forwards`, `members_hidden`, `slow_mode_seconds`) | 🟡 сервер готов, для базовой группы UI строки не имеет |
| Пригласительные ссылки | ✅ `…/invites`, `…/permanent-invite` | ✅ |

## Что сделано в этом заходе

1. `channels.getMessages` — посты канала по id одним запросом (ответы, закреп, альбомы).
   Раньше любой ответ на пост и открытие закрепа давали «функция не поддерживается».
2. `channels.readMessageContents`, `messages.readMessageContents` — подтверждение без ошибки.
3. `messages.getMessages` (список id без пира, служебные запросы кэша файлов) — пустой ответ
   вместо ошибки: тост больше не появляется.
4. `channels.getInactiveChannels` — пустой список; `channels.reportSpam` — `true`.
5. Фото канала и группы: `channels.editPhoto` / `messages.editChatPhoto` (и удаление через
   `inputChatPhotoEmpty`) сохраняют `avatar_media_id`, клиент отдаёт `chatPhoto`/`photo` и
   подгружает байты через `upload.getFile`.
6. Убраны наши «Платные реакции» и «Платное медиа»: пункты меню поста канала и тумблер
   платных реакций на экране реакций канала удалены. Реакции теперь штатные (бесплатные,
   серверный роут `POST/PUT /api/channels/{id}/messages/{mid}/reactions*`), как в эталоне.

7. Флаги канала: `toggleSignatures`, `toggleSlowMode`, `toggleParticipantsHidden`,
   `togglePreHistoryHidden`, `toggleJoinToSend`, `toggleJoinRequest`, `toggleAntiSpam`,
   `toggleUsername`/`deactivateAllUsernames`, `messages.toggleNoForwards` — всё через
   `PUT /api/channels/{id}/settings` (и `/api/groups/{id}/settings` для групп).
8. Обсуждения: `channels.getGroupsForDiscussion` (группы, где я админ) + `channels.setDiscussionGroup`;
   привязанная группа приходит в `linked_chat_id` полного инфо.
9. Цвет и эмодзи-статус канала: `channels.updateColor` (цвет + эмодзи-фон, отдельно для профиля) и
   `channels.updateEmojiStatus`.
10. Журнал действий: `channels.getAdminLog` — таблица `channel_admin_log`, события о названии,
    описании, username, фото, входе/выходе, бане/разбане, назначении/снятии админов, slow mode,
    привязке обсуждений; читают только владелец и админы.
11. Ограничения прав в группах: `messages.editChatDefaultBannedRights` ↔ `PUT /api/groups/{id}/send-permissions`
    (маска «что нельзя отправлять»: текст, фото, видео, голосовые, стикеры, файлы), текущая маска
    отдаётся в `default_banned_rights` группы.
12. Поиск по постам: `channels.searchPosts` — `GET /api/channels/posts/search` по публичным каналам,
    плюс `channels.checkSearchPostsFlood` (поиск всегда бесплатный) и `channels.deleteHistory` /
    `channels.deleteParticipantHistory` (очистка истории канала и истории участника).

13. Группы переведены в супергруппы. Сервер отдаёт группу как `TL_channel` с `megagroup=true`
    (`WaysGramChats.toMegagroup`), id группы остаётся прежним и лежит ниже `CHANNEL_OFFSET`, поэтому
    клиент сам отличает группу от канала (`WaysGramChats.isGroupChannel`). Все сообщения групп
    (`messages.*` → `channels.*`) идут через `WaysGramMegagroups` и `channels.*`-методы:
    `getFullChannel`, `getParticipants`, `getParticipant`, `editAdmin`, `editBanned`,
    `inviteToChannel`, `joinChannel`, `leaveChannel`, `deleteChannel`, `editTitle`, `editPhoto`,
    `readHistory`, `deleteMessages`, `deleteHistory`, `deleteParticipantHistory`, `getMessages`,
    `checkUsername`/`updateUsername`/`toggleUsername`, `getAdminLog`, `exportMessageLink`.
    Диалоги, история, отправка, черновики, закреп, реакции, «печатает», медиа, пересылка,
    приглашения и события — на `TL_peerChannel`/`TL_inputPeerChannel`.
14. Три «супергрупповых» пункта теперь штатные в UI: медленный режим
    (`channels.toggleSlowMode` → `PUT /api/groups/{id}/settings`, лестница 0/5/10/15/30/60/120/300),
    скрытие списка участников (`toggleParticipantsHidden` → `members_hidden` + `can_view_participants`
    в полном инфо) и скрытие истории (`togglePreHistoryHidden` → `pre_history_hidden`: новому
    участнику ставится метка чтения на момент входа, а `GET /api/groups/{id}/messages` не отдаёт
    сообщения старше его вступления).
15. Заявки на вступление: `channels.toggleJoinRequest` → `join_request`; публичное вступление
    (`POST /api/groups/{id}/join`) при включённом флаге создаёт заявку вместо вступления.
    Очистка истории: `DELETE /api/groups/{id}/messages` (все или список id) и
    `DELETE /api/groups/{id}/members/{tg}/messages`.
16. Username группы (публичная ссылка) — `PUT /api/groups/{id}` с проверкой занятости среди групп,
    каналов и аккаунтов.

## Что осталось за рамками паритета

* У групп нет форума-топиков, передачи владения (`channels.editCreator`), `getSendAs` и
  геолокации. Важно: `channels.toggleForum` и `channels.editCreator` **не заглушены** — их нет в
  `WaysGramTransport.handle()`, поэтому экран «Форум» и «Передать канал» показывают
  «Это действие пока не поддерживается WaysGram». Остальные из этого абзаца закрыты пустым успехом.
* `channels.toggleAntiSpam` для групп включает серверный антиспам (`send-permissions`), но без
  обучаемых порогов — это простой фильтр.
* Цвет профиля группы пока не хранится: `channels.updateColor` для группы вернёт ошибку канала.
* Форум-топики, спонсорские отчёты, геолокация канала, переводчик (`toggleAutotranslation`) —
  отвечают пустым успехом или заглушкой, отдельной функциональности не несут.

## Всплывашки «не поддерживается» в каналах (проверено по коду клиента)

Таблица выше описывает пункты задания. Кроме них официальный клиент в канальных экранах
отправляет ещё ряд TL-методов, которых в диспетчере нет — каждый даёт bulletin
`WaysGramUnsupportedOperation` («Это действие пока не поддерживается WaysGram»).
Проверено поиском по `tgnet/WaysGram*.java`: метода нет ни в `instanceof`, ни в заглушках.

| TL-метод | Откуда вызывается |
| --- | --- |
| `messages.getPollVotes`, `messages.readPollVotes`, `messages.getUnreadPollVotes` | результаты опроса, «непрочитанные голоса» |
| `messages.getUnreadReactions`, `messages.readReactions` | раздел «Реакции» в профиле канала (дёргает `MessagesController` при открытии) |
| `messages.translateText` | «Перевести» публикацию (`TranslateAlert2/3`, `TranslateController`) |
| `messages.report` | «Пожаловаться» (`ReportBottomSheet`) |
| `messages.editChatParticipantRank` | «Должность» администратора (`ChatRightsEditActivity`) |
| `channels.editCreator` | «Передать канал» (`ChatRightsEditActivity`, `SelectChatUserSheet`) |
| `channels.toggleForum` | тумблер «Форум» (`MessagesController`) |
| `channels.reorderProfileTabs`, `account.reorderProfileTabs` | перестановка вкладок профиля (`SharedMediaLayout`) |
| `help.getCountriesList` | выбор страны (`LoginActivity`, бусты) |
| `messages.getBotCallbackAnswer` | кнопки-боты под постами |
| `messages.getInlineBotResults`, `messages.searchGlobal`, `messages.searchStickers` | поиск и инлайн-боты из канала |
| `messages.acceptUrlAuth`, `messages.requestUrlAuth`, `messages.checkUrlAuthMatchCode` | ссылки с авторизацией |
| `messages.sendBotRequestedPeer`, `messages.reportReadMetrics` | мелкие вызовы из `ChatActivity*` |
| `messages.deleteParticipantReaction(s)`, `messages.addPollAnswer`, `messages.deletePollAnswer` | реакции/опросы участников |

Тихого фолбэка нет: `BulletinFactory.errorDescription()` показывает строку для любого
`METHOD_NOT_SUPPORTED:`. Чтобы убрать всплывашки, нужны либо серверные обработчики,
либо явные заглушки в диспетчере.

## Следующие заходы (если понадобятся)

1. Форум-топики канала (`channels.toggleForum`) — сейчас заглушка, нужен серверный слой топиков.
2. Отправка от имени канала в группах (`channels.getSendAs`, `sendAsPeers`).
3. Геолокация канала (`channels.editLocation`), передача владения (`channels.editCreator`) и цвет
   профиля группы (`channels.updateColor`).
4. Проверка миграции на устройстве: ветка собрана без компилятора, нужен прогон сборки APK.
