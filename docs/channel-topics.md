# Темы канала (форум-режим)

У группы темы были с прошлого этапа: `group_topics`, `/api/groups/{id}/topics` и экран
`WaysGramForumActivity`. У канала не было ничего: `channels.toggleForum` менял только флаг
`channels.forum_enabled` (он уже был в `CHANNEL_BOOL_SETTINGS`), а самих тем, `top_msg_id` у
постов и экрана не существовало. Пост жил только в общей ленте, поэтому включение форума
ничего не меняло.

## Что теперь

**Сервер**

* Миграция: `channel_messages.topic_id` (+ индекс `idx_channel_topic_messages`), новые таблицы
  `channel_topics` (`title`, `closed`, `pinned_message_id`, `deleted_at`) и
  `channel_topic_followers`. Уникальность названия — по `(channel_id, title)` среди неудалённых.
* `GET /api/channels/{id}/topics` — список тем с `forum_enabled`, `can_manage`, `can_post`.
  В каждой теме `id`, `title`, `closed`, `created_at`, `message_count`, `last_message_id`,
  `last_message_at`.
* `POST /api/channels/{id}/topics` (`{"title": …}`) — только владелец или админ с правом
  `change_info` (64): `403 CHANNEL_TOPIC_MANAGE_REQUIRED`. Пока форум выключен —
  `409 CHANNEL_FORUM_DISABLED`; больше 100 тем — `409 CHANNEL_TOPIC_LIMIT`; повтор названия —
  `409 CHANNEL_TOPIC_TITLE_EXISTS`; пустое или длиннее 80 символов — `400 INVALID_TOPIC_TITLE`.
* `PATCH`/`DELETE /api/channels/{id}/topics/{topic}` — переименование, закрытие/открытие
  (`closed`), удаление (`deleted_at`, подписки снимаются). Закрытие обратимо, история остаётся.
* `PUT …/topics/{topic}/pin` — `{"message_id": id}`; `0` снимает закрепление. Нужно право
  `pin_messages` (8): `403 CHANNEL_TOPIC_PIN_REQUIRED`. Чужой пост или пост другой темы —
  `404 CHANNEL_TOPIC_MESSAGE_NOT_FOUND`.
* `PUT …/topics/{topic}/notifications` — `{"enabled": bool}`, подписка зрителя на тему.
* `GET …/topics/{topic}/messages` — история темы с `before_id` и `limit`, отдаёт `topic`,
  `can_manage`, `can_pin`, `can_post`, `notifications_enabled`, `next_before_id`. Автор поста —
  канал (`sender_name`), `sender_id` — админ, который опубликовал.
* `POST …/topics/{topic}/messages` (`{"body": …}` или `{"media_id": …}`) — публикация в тему.
  Нужно право публикации (1): `403 CHANNEL_TOPIC_POST_REQUIRED`; в закрытую тему —
  `409 CHANNEL_TOPIC_CLOSED`; пустой запрос — `400 CHANNEL_TOPIC_MESSAGE_REQUIRED`; лимит
  `CHANNEL_POSTS_PER_MINUTE` общий с лентой (`429 CHANNEL_POST_RATE_LIMIT`); медиа проверяется
  как обычно (`require_owned_media`), `Idempotency-Key` поддерживается.
* Посты тем **не попадают в общую ленту**: `GET /api/channels/{id}/messages` фильтрует
  `topic_id IS NULL` — как у групп. Реакции, просмотры и остальная механика ленты для постов тем
  не считается: тема — отдельная ветка.
* `GET /api/channels/{id}` отдаёт `can_manage_topics` и `can_post`, чтобы экран не гадал о правах.

**Клиент**

* `WaysGramForumActivity` стал общим: конструктор
  `(account, chatId, topic, root, channel)`; прежний трёхаргументный оставлен для групп.
  Заголовок, композер и `peer` вложения зависят от `channel`.
* `WaysGramGroupTopics.openChannel(...)` строит корень `/api/channels/{server}/topics`
  (`channelServer = tgId − 1 000 000 000`) и `setForum(...)` шлёт
  `PUT /api/channels/{id}/settings {"forum_enabled": …}` — тот же запрос, что транспорт делает
  для `channels.toggleForum`.
* Если форум выключен, экран показывает это состояние и кнопку «Включить темы» (для владельца и
  админа с `change_info`), а не пустой список.
* Входы: меню канала «Темы канала» (`waysgram_channel_topics`, иконка `msg_topics`) и строка
  «Темы канала» в настройках канала у владельца. В группе остался прежний пункт «Темы».
* Композер темы канала скрыт, если сервер не вернул `can_post` (подписчик читает, но не пишет).
* Новый `tgnet/WaysGramChannelTopics` закрывает штатные `TL_forum`-методы вместо
  «не поддерживается»: `messages.getForumTopics`, `getForumTopicsByID`,
  `createForumTopic`, `editForumTopic`, `channels.deleteTopicHistory`. Для групп те же методы
  ходят в `/api/groups/{id}/topics`. `messages.updatePinnedForumTopic` и
  `reorderPinnedForumTopics` отвечают `TL_boolTrue`: порядок и пин темы — состояние сервера
  Telegram, в REST-слое их нет.
* Локализация: «Темы канала», «Включить темы», «Выключить темы», «Темы выключены…» в `values`
  и `values-ru`.

## Ограничения

* Пуш-уведомления о новых постах в теме не отправляются: подписка (`notifications`) хранится,
  но рассылка требует отдельного прохода по `channel_topic_followers`.
* Форум-режим и темы — только для каналов (`isChannelAndNotMegaGroup`); у мегагрупп остались
  групповые темы.
* Глобальный флаг `ChatObject.isForum` по-прежнему не выставляется: штатный `TopicsFragment`
  опирается на серверные `TL_forumTopic`-апдейты и порядок тем, которых у WaysGram нет, поэтому
  навигация канала остаётся прежней, а темы открываются своим экраном.
Сборка: ветка собирается workflow-ом «Build Android APK» (run 310, commit 8496dba; артефакт `WaysGram-New-APK-310`, sha256 APK лежит рядом с ним). На устройстве не проверялось.
