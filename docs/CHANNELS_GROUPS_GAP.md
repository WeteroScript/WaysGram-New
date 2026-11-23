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

* Темы канала закрыты (`docs/channel-topics.md`), но без пушей о новых постах в теме и без
  штатного `TopicsFragment`: флаг `ChatObject.isForum` не выставляется, темы открываются своим
  экраном. Форум-режим есть только у каналов; у мегагрупп — групповые темы (`docs/group-topics.md`).
* `channels.toggleAntiSpam` для групп включает серверный антиспам (`send-permissions`), но без
  обучаемых порогов — это простой фильтр.
* Спонсорские отчёты и переводчик (`toggleAutotranslation`) отвечают пустым успехом или
  заглушкой, отдельной функциональности не несут.

## Всплывашки «не поддерживается»: что закрыто

Раньше любой TL-метод без обработчика давал bulletin `WaysGramUnsupportedOperation`
(«Это действие пока не поддерживается WaysGram»). Теперь редкие методы канальных, групповых и
общих экранов обслуживает `WaysGramExtras.handle()`, а сервер получил нужные маршруты.

| TL-метод | Что делает WaysGram |
| --- | --- |
| `messages.getPollVotes` | `GET /api/{channels,groups}/{id}/messages/{mid}/poll-votes`, `GET /api/messages/{mid}/poll-votes?with=` — реальные голоса (`message_card_votes`) с профилями участников, пагинация по `offset`/`option` |
| `messages.addPollAnswer`, `messages.deletePollAnswer` | `POST /api/message-cards/{scope}/{peer}/{mid}` — голос и отзыв голоса |
| `messages.readPollVotes`, `messages.readReactions` | `messages.affectedHistory` без ошибки (счётчики непрочитанного WaysGram не ведёт) |
| `messages.getUnreadPollVotes`, `messages.getUnreadReactions` | пустая выдача `messages.messagesSlice` (раздел «Реакции» открывается пустым, без ошибки) |
| `messages.deleteParticipantReaction(s)` | `DELETE /api/{channels,groups}/{id}/messages/{mid}/reactions?user=` — снятие реакции участника админом (право «удалять сообщения») |
| `messages.translateText` | `POST /api/translate` (нужен `OPENAI_API_KEY` на сервере) |
| `messages.report` | `POST /api/reports` → таблица `message_reports` (scope, сообщение, автор жалобы, причина, текст) |
| `channels.editCreator` | `PUT /api/channels/{id}/owner`, `PUT /api/groups/{id}/owner` — передача владения с полными правами новому владельцу, старое владение остаётся админом |
| `channels.toggleForum` | `forum_enabled` в настройках канала и группы (сами топики у групп есть, у каналов нет) |
| `messages.editChatParticipantRank` | `PUT /api/{channels,groups}/{id}/members/{tg}/rank` — колонка `rank` у участников |
| `help.getCountriesList` | статический список стран в клиенте (номер можно вводить вручную) |
| `messages.getBotCallbackAnswer` | если данные кнопки — URL, он открывается; иначе пустой ответ без ошибки |
| `messages.getInlineBotResults` | пустая выдача «ничего не найдено» |
| `messages.requestUrlAuth`, `acceptUrlAuth`, `checkUrlAuthMatchCode` | ссылка открывается как обычная (авторизацию на стороннем сайте WaysGram не подтверждает) |
| `messages.searchGlobal` | пустая выдача: серверного индекса по всем чатам нет — честный «ничего не найдено» вместо ошибки |

Что осталось честными заглушками (по ним нет серверной функциональности, ошибки больше не
показываются): глобальный поиск, инлайн-боты, кнопки-боты без URL, счётчики непрочитанных
реакций.

## Навигация: `@null`, дубли строк и истории в группах

* `Discussion @null`. `JSONObject.optString(key, "")` возвращает литерал `"null"`, если
  сервер прислал `"username": null` (`JSONObject.NULL.toString()` — это `"null"`). Юзернеймы
  каналов и групп теперь читаются через `WaysGramMapper.text()`, который считает такие
  значения пустыми; при пустом юзернейме строка «Discussion» показывает название связанной
  группы.
* Дубли. Отдельные строки «Группа обсуждений» и «Настройки входящих канала» в карточке
  канала убраны: первая повторяла штатную «Discussion», вторая живёт в меню чата
  (`WaysGramChannelInbox`). Штатная «Discussion» при включённом транспорте открывает
  `WaysGramDiscussions.settings()`, а не нативный `ChatLinkActivity`, который общается
  только через `channels.editLinkedChat`.
* Истории. `WaysGramStories` считал каналом только `channel_id > CHANNEL_OFFSET`, поэтому в
  группе `stories.canSendStory`/`sendStory` проваливались в `METHOD_NOT_SUPPORTED` и кнопка
  «Add Story» показывала тост. Теперь путь строится по типу чата:
  `/api/channels/<id>/stories` или `/api/groups/<id>/stories` (сервер принимал оба).
* `account.getChatThemes` (экран «Appearance» в чате) отвечает пустым `account.themes`
  вместо ошибки с тостом; сами темы чатов — отдельная задача в хвосте плана
  (`docs/waysgram-fix-plan.md`).

## Платные реакции и платные сообщения в канал

* `messages.sendPaidReaction` → `POST /api/channels/{id}/messages/{mid}/paid-reactions`.
  Клиент получает свежие реакции поста и обновляет чип; в выдаче сообщений суммы лежат
  полями `paid_stars` и `my_paid_stars`, клиент превращает их в `TL_reactionPaid`.
* Звёзды платной реакции только списываются у отправителя (решение пользователя:
  начисления владельцу и вывод пока не делаются). Аналитика канала считает покупки по
  дебетам `economy_transactions`, а не по прежним начислениям.
* `messages.getPaidReactionPrivacy` / `togglePaidReactionPrivacy` отвечают нейтрально:
  анонимность платных реакций WaysGram не разделяет.
* «Direct Messages» (платные сообщения подписчиков): `stars.updatePaidMessagesPrice` →
  `PUT /api/channels/{id}/paid-messages` (`paid_messages_enabled`, `paid_messages_stars`).
  Экран `PostSuggestionsEditActivity` без монофорума читает и пишет цену в самом канале.
* Права администратора больше не додумываются: `admin_rights` по умолчанию 0, и отправка
  в broadcast-канал проверяется до запроса (`CHAT_WRITE_FORBIDDEN`).

## Комментарии к постам — штатный экран Telegram

Раньше комментарии открывались собственным диалогом из меню поста («Комментарии» →
«Комментариев пока нет»). Этот пункт меню убран: комментарии теперь штатные.

* Сервер заводит корневое сообщение треда в связанной группе обсуждений:
  `channel_post_discussions(channel_id, post_id, group_id, root_message_id)`,
  `GET /api/channels/{id}/messages/{post}/discussion`.
* Комментарий первого уровня — ответ на корень (`reply_to_id = root`), поэтому тред собирается
  штатно; ответ на корень тоже разрешён.
* `GET /api/groups/{gid}/threads/{root}/replies` отдаёт id сообщений треда, полные сообщения
  клиент берёт штатным `/api/groups/{gid}/messages?message_ids=`.
* Выдача постов канала содержит `comments_count`, `last_comment_id`, `discussion_group_id` —
  из них клиент строит `message.replies` (`comments = true`, `channel_id` группы, `max_id`),
  и под постом появляется кнопка комментариев со счётчиком.
* TL-методы `messages.getDiscussionMessage`, `messages.getReplies`, `messages.readDiscussion`
  обслуживает `WaysGramThreads.handle()`; счётчик непрочитанных комментариев не ведётся
  (`unread_count = 0`, `read_max_id = max_id`).

## Анимированные эмодзи и реакции: почему не работало и что сделано

Симптомы с устройства: одиночный эмодзи в личной переписке не анимировался; у половины
реакций не было текстуры и ни у одной не было анимации; в каналах реакции не открывались
вообще (ни обычные, ни платные).

Причина одна и в клиенте, а не в сервере:

* Клиент узнаёт, какие реакции существуют, из `messages.getAvailableReactions`. Метод никто
  не обрабатывал, поэтому `MediaDataController.reactionsList` был пуст: выбор реакции
  (`ReactionsContainerLayout`) строился только из локальной палитры, а чип реакции рисуется
  по `center_icon` из `getReactionsMap()` — без записи каталога рисовать нечего.
* Анимированный эмодзи рисуется только из документа `application/x-tgsticker`
  (`MediaDataController.getEmojiAnimatedSticker` → `WaysGramAnimatedEmoji.find`). В APK лежало
  три файла (`heart`, `fire`, `star`), остальным эмодзи документа не существовало вовсе.
* Платные реакции (`paid_reactions_enabled`) уже приезжают в `channelFull`, но вкладка ⭐ в
  шторке реакций появляется только когда есть каталог реакций — его и не было.

Что сделано:

* `WaysGramEmojiTable` (генерируется `scripts/waysgram_emoji_import.py`) — таблица
  «эмодзи → файл анимации» на **125** эмодзи; сами TGS лежат в
  `TMessagesProj/src/main/assets/waysgram_emoji/*.tgs` (векторный Lottie без растровых слоёв,
  его проигрывает нативный плеер стикеров).
* `WaysGramAnimatedEmoji` выдаёт по таблице документы двух видов (эмодзи-сообщение и реакция),
  с синтетическими стабильными `id`; файлы отдаёт `upload.getFile` прямо из assets
  (кэш в `cacheDir/waysgram_emoji_v2`). Большие одиночные эмодзи анимируются в бабле у обоих
  участников личной переписки.
* `WaysGramReactions` отвечает на `messages.getAvailableReactions`, `getTopReactions`,
  `getRecentReactions`, `getDefaultTagReactions`: 50 реакций с документами во всех слотах
  (`static_icon`, `appear/select/activate/effect_animation`, `center_icon`, `around_animation`).
  Один документ используется и как иконка, и как анимация — чип рисует последний кадр, нажатие
  проигрывает анимацию.
* Для каждой реакции дополнительно кладётся неактивный дубль без `U+FE0F`: сервер может вернуть
  эмодзи в любой форме, а чип ищется по точному совпадению строки.
* `WaysGramReactions.contains()` пропускает в отрисовку любую реакцию, для которой есть локальная
  анимация (125 эмодзи), а не только 39 из старой палитры — поэтому реакции, поставленные из
  официального клиента, тоже получают текстуру.
* Вкладка платных реакций (⭐) в каналах включается штатно через `channelFull.paid_reactions_available`.

Источник ассетов и честные ограничения:

* TGS взяты из открытого набора `ilyhalight/telegram-emoji-effects` (анимации эмодзи Telegram,
  имена файлов по Unicode). Набор воспроизводится скриптом:
  `python3 scripts/waysgram_emoji_import.py --source <распакованный репозиторий>`.
* Полный официальный набор анимированных эмодзи (~1000 файлов, ~60 МБ) в APK не влезает;
  в таблице 125 самых используемых. Эмодзи вне таблицы остаются статичными.
* Анимируется одиночный эмодзи в сообщении (как в Telegram); эмодзи в середине длинного текста
  Telegram анимирует через `custom_emoji`-entities, которых WaysGram-сервер пока не отдаёт.
* Платная реакция как **оплата звёздами** требует серверного списания Stars
  (`messages.sendPaidReaction`); сейчас доступен выбор и отображение, но не списание.
* APK вырос примерно на 6.8 МБ; архив остаётся в пяти частях (~112.7 МБ).

## Следующие заходы (если понадобятся)

1. ~~Форум-топики канала (`channels.toggleForum`)~~ — сделано: таблицы `channel_topics` /
   `channel_topic_followers`, `topic_id` у постов, `/api/channels/{id}/topics…`, общий экран тем
   и маппинг `TL_forum`-методов, см. `docs/channel-topics.md`.
2. ~~Отправка от имени канала в группах (`channels.getSendAs`, `sendAsPeers`)~~ — сделано,
   см. `docs/group-send-as.md`.
3. ~~Геолокация канала (`channels.editLocation`)~~ — сделано, см. `docs/chat-location.md`.
   ~~Цвет профиля группы (`channels.updateColor`)~~ — сделано, см. `docs/group-profile-color.md`.
   ~~Передача владения каналом (`channels.editCreator`)~~ — сделано своим диалогом вместо SRP,
   см. `docs/channel-transfer.md`.
4. Проверка на устройстве: ветка теперь компилируется и собирается (workflow «Build Android APK»,
   run 310, commit 8496dba; попутно нашлись и исправлены две ошибки компиляции — импорт
   `TL_account` в `WaysGramChatThemes` и `linked_chat_id` у `Chat` вместо `ChatFull` в меню поста).
   Остался прогон на телефоне и проверка миграции БД на живых данных.

## Профиль чата: закрыто в последнем заходе

`messages.getOnlines`, `getCommonChats`, `getPeerSettings`, `reportSpam` и
`getAdminsWithInvites` больше не дают «не поддерживается»: у них есть серверные ответы и
разбор в транспорте. Подробности и пути эндпоинтов — `docs/chat-profile-endpoints.md`.

Остаток по каналам и группам, который измерялся статическими проверками, закрыт:
`scripts/test_android_messaging.py` и `scripts/test_android_gifts.py` больше не падают,
полный прогон `pytest scripts/ -q` — 168 passed. Что именно добавлено (строки аналитики канала
и группы, «Входящие» в настройках канала, пункт «Группа обсуждений» в меню поста, редактор
платных сообщений, скрытие бустов и звонков Telegram, палитра реакций, набор в группах,
секретные номера) — см. `docs/chat-profile-endpoints.md`, раздел «Статические проверки закрыты».
