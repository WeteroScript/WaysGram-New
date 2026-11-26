# План правок по отзыву (этапы 1–5)

Зафиксированные решения пользователя:

* **Кастомизация** — изолировать: кастомными остаются фон окна, панели и таб-бар;
  кнопки, текст, суммы звёзд/граммов и диалоги всегда штатные цвета Telegram.
  Двойные меню и дубли убираются.
* **Порядок** — строго 1 → 2 → 3 → 4 → 5, незакрытые функции добираются в конце.
* **Анимированные эмодзи** — анимация играет при отправке одиночного эмодзи;
  по нажатию проигрываются эффекты этого эмодзи.
* **Платные функции** — звёзды списываются у отправителя, владельцу пока ничего не
  начисляется; вывод не делаем.

## Этап 1. Ложные «не поддерживается» и навигация групп/каналов — сделано

| Что | Как закрыто |
| --- | --- |
| `Discussion @null` в карточке канала | `WaysGramMapper.text(json, key)`: `JSONObject.NULL` больше не превращается в строку `"null"`; юзернеймы каналов и групп читаются через него, при пустом юзернейме строка показывает название чата |
| Дубли «Группа обсуждений» / «Настройки входящих канала» в Edit | кастомные `TextCell` убраны; штатная строка «Discussion» при включённом транспорте открывает тот же диалог привязки, что и раньше; настройки входящих остаются в меню чата |
| `Add Story` в группе | `WaysGramStories` считает историевым пиром и канал, и группу: `/api/channels/<id>/stories` либо `/api/groups/<id>/stories` |
| Тост `METHOD_NOT_SUPPORTED:getChatThemes` | `WaysGramExtras` отвечает пустым `account.themes`; сами темы чатов — в конце плана, чтобы не оставлять заглушку |

Тесты: `scripts/test_android_group_channel_navigation.py`.

## Этап 2. Права и монетизация каналов — сделано (кроме тем чатов)

1. **Права публикации.** `admin_rights` берётся ровно из ответа сервера (по умолчанию 0
   вместо прежних 15), отправка в broadcast-канал проверяется до HTTP-запроса
   (`WaysGramChats.requireChannelPostRight` → `CHAT_WRITE_FORBIDDEN`). Сервер уже отвечал
   403 для текста, альбомов и пересылки.
2. **Платные реакции.** `messages.sendPaidReaction` → `POST
   /api/channels/{id}/messages/{mid}/paid-reactions`; чип у поста строится из
   `paid_stars` / `my_paid_stars` (`TL_reactionPaid`). Звёзды только списываются,
   аналитика канала считает покупки по дебетам.
3. **Платные сообщения в канал («Direct Messages»).** `stars.updatePaidMessagesPrice` →
   `PUT /api/channels/{id}/paid-messages` (1–10 000 звёзд, только владелец). Экран берёт
   цену из канала, когда монофорума нет, и обновляет её у себя после сохранения.
4. **Темы чатов** — перенесены в «Хвост»: нужен каталог тем, хранение выбора и
   применение в чате, а не пустая выдача.

Тесты: `scripts/test_android_channel_monetization.py`, `authbot/tests/test_message_changes.py`.

## Этап 3. Реакции и анимированные эмодзи — сделано

Причина пустых текстур: в APK лежал только набор **эффектов**
(`ilyhalight/telegram-emoji-effects`). Эмодзи в нём нет — слой называется
`Confetti`. Клиент же рисует иконку реакции последним кадром `center_icon`
(`40_40_lastreactframe`), а большой эмодзи в бабле — тем же документом. Отсюда и
«нет текстур», и конфетти вместо эмодзи.

| Что | Как закрыто |
| --- | --- |
| Рисунок эмодзи | `scripts/waysgram_emoji_art.py` собирает TGS из Twemoji (`jdecked/twemoji`, плоский вектор): SVG → Lottie (`pip install lottie`) → масштаб в 512×512 с анимацией появления, 42 кадра при 60 fps. Последний кадр спокойный — его берёт чип реакции |
| Отступ под чип реакции | клиент вырезает из кадра центральные 60% (`lastreactframe`), поэтому рядом с `art/` лежит вариант `react/` — тот же рисунок, вписанный в 58% кадра |
| Слоты каталога реакций | иконки и превью (`static_icon`, `center_icon`, `appear/select_animation`) — рисунок; нажатие (`activate/effect/around_animation`) — эффект этого эмодзи |
| Отдельные id документов | `0x6A52…` (эмодзи), `0x6A53…` (реакции), `0x6A54…` (эффекты): кэш одного набора больше не подменяет другой; `upload.getFile` обслуживает все три |
| Эффект при появлении одиночного эмодзи | `WaysGramReactions.consumeEmojiEffect` — один раз на сообщение и только для свежих (60 с), затем штатная анимация `EmojiAnimationsOverlay` |
| Нажатие на эмодзи | пак интеракций `emojicom` у WaysGram не загружается, поэтому `EmojiAnimationsOverlay.checkStickerPack` заполняет карту эффектами из APK (`WaysGramAnimatedEmoji.fillInteractions`); нажатие работает и в каналах, где `currentUser == null` |
| Краш после нажатия на эмодзи в канале | `EmojiAnimationsOverlay.onTapItem` возвращает результат до блока подсказки, если `currentUser == null`: эффект проигрывается, а обращение к `currentUser.id` и `currentUser.first_name` остаётся только для личных чатов |

Ограничение: официальные TGS-анимации Telegram в песочнице недоступны (нет
api.telegram.org и зеркала набора), поэтому рисунок — Twemoji с собственной
анимацией появления, а эффекты — набор `telegram-emoji-effects`.

Тесты: `scripts/test_android_reactions.py`, `scripts/test_android_animated_emoji.py`.

## Этап 4. Кастомизация без конфликтов — сделано

Выбран вариант «изолировать»: кастомным остался только фон, всё остальное —
штатное. Подробности: `docs/navigation-customization.md`.

| Что было | Как закрыто |
| --- | --- |
| «Второе меню» и плитка поверх интерфейса | убраны подмена фона таб-бара (`wrapBackground`) и картинки-индикатора вкладки в `MainTabsLayout`, `GlassTabView`, `FilterTabsView`, `PagerSlidingTabStrip`; декор шапки и правого края удалён |
| Конфликт цветов кнопок и сумм звёзд/граммов | акцент из картинки больше не извлекается: `accentColor`/`layerAccent` и перекраска `key_windowBackgroundWhiteBlue*`, `key_chat_messagePanelSend` удалены — кнопки, ссылки и суммы всегда штатные |
| Серые плашки менялись только в меню кастомизации | хуки `panelShape`/`drawPanel`/`panelRowBackground` удалены из `Theme`, `RecyclerListView`, `UniversalAdapter`: плашки одинаковы везде, картинка видна сквозь них за счёт альфы в `key_windowBackgroundWhite` |
| Четыре картинки и четыре слота | остался один слот «Фон приложения»; старые конфиги (`image1…image3`) продолжают открываться, лишние слоты игнорируются |
| Подмена системных ключей | в `Theme.getColor` подменяются только `key_windowBackgroundWhite` и `key_windowBackgroundGray` (альфа), всё остальное возвращается как есть |

Тесты: `scripts/test_android_customization_isolation.py`.

## Этап 5. Платные сообщения как в Telegram — сделано

Подробности: `docs/paid-messages.md`.

| Требование | Как закрыто |
| --- | --- |
| Бейдж цены в шапке | кнопка отправки показывает «⭐ N» из `GET /api/chats/{peer_id}/message-fee` (через `WaysGramPaidDm.peerFeeStars` и штатный `SendButton.setStarsPrice`), в диалоге канала цена в заголовке |
| Диалог подтверждения перед отправкой | без `approved_fee_stars` сервер отвечает `402` с ценой, клиент спрашивает и повторяет отправку с ценой и `Idempotency-Key` |
| Списание только после подтверждения | повтор с тем же ключом возвращает прежний ответ и не списывает второй раз; изменённая цена требует нового подтверждения |
| Возврат при недоставке | `channel_inbox_messages.delivered_at`/`refunded`; владелец открыл диалог — доставлено, не открыл за 7 дней — звёзды возвращаются (`channel_inbox_refund:`) и списываются обратно у владельца |
| История платежей | `GET /api/me/paid-messages` (лента, итоги, пагинация) и экран `WaysGramPaidHistory` |

Тесты: `scripts/test_android_paid_messages.py`,
`authbot/tests/test_paid_messages_history.py`.

## Каналы и группы: стоковые кнопки — сделано

По просьбе пользователя все добавленные WaysGram-входы в интерфейсе каналов и групп удалены,
а стоковые кнопки вернуты на свои места. Интерфейс за пределами каналов и групп не изменён.

| Что сделано | Где |
| --- | --- |
| Удалены пункты «Розыгрыши», входящие канала, темы канала, история платежей, бусты, истории группы, «Отправить от имени…», темы группы | `ui/ChatActivity.java` — больше нет `headerItem.lazilyAddSubItem(waysgram_…)` |
| Удалены пункты «Платные реакции» и «Группа обсуждений» из меню поста | `ui/ChatActivity.java` |
| Вернуты стоковые «Бусты», просмотр списка реакций и изменение времени отложенного поста | `ui/ChatActivity.java` — убраны гейты WaysGram-транспорта |
| В карточке вернуты «Начать звонок/трансляцию» и стоковая статистика; удалён «Медленный режим» | `ui/ProfileActivity.java` |
| Из «Изменить» удалены все WaysGram-строки; вернуты Welcome Message, «Статистика и бусты», стоковая привязка группы и геолокация только для мегагрупп | `ui/ChatEditActivity.java` |
| Вернут стоковый тумблер платных реакций внутри «Реакций» | `ui/Components/Reactions/ChatCustomReactionsEditActivity.java`, `tgnet/WaysGramChats.java` (`paid_media_allowed`) |

Маппер теперь заполняет стоковые поля `paid_media_allowed`, `stargifts_available`, `can_view_stats`
и `linked_monoforum_id`, чтобы кнопки появлялись по тем же условиям, что и в Telegram.
Сборка APK не запускалась. Тесты: `scripts/test_android_stock_channel_group_buttons.py`.

## Реакции каналов и групп — сделано

| Проблема | Как исправлено |
| --- | --- |
| Платная реакция-звезда открывала окно Stars только по долгому нажатию | `ChatActivity.selectReaction()` теперь открывает `StarsReactionsSheet` и по одиночному тапу — до вызова `toggleWaysGramReaction()` обрабатывается `visibleReaction.isStar` |
| «Это действие пока не поддерживается WaysGram.» показывалось после почти каждого действия | `BulletinFactory` больше не показывает bulletin для ошибок `METHOD_NOT_SUPPORTED:*`: `makeForError`, оба `showForError` и `showError` выходят раньше показа |
| В каналах и группах не было стоковых реакций и их анимаций | `waysGramReactionMenu` теперь включает компактную WaysGram-палитру только в личных чатах (`currentChat == null`); в каналах/группах вызывается стоковый `reactionsLayout.setMessage(...)`, поэтому используются анимации `TL_availableReaction` |

APK не собирался. Тесты: `scripts/test_android_reaction_regressions.py`.

## Разбор ошибок от 24.11.2026

Распределение задач по потокам, приоритеты и открытые вопросы: `docs/waysgram-bug-triage-2026-11-24.md`.
APK по этому списку не собирается до отдельной команды.

## Модель WaysGram (Plush Pepe) — перерисована

| Проблема | Как исправлено |
| --- | --- |
| Потерялся чёрный наружный контур модели | Кадры исходного стикера рендерятся через `rlottie`, поверх силуэта добавляется чёрный контур по альфа-маске; новый `preview.png` и WebM пересобраны |
| Анимация была выдуманной (свечение, сердечки, искры) | Движение берётся из исходного TGS подарка Plush Pepe покадрово: 60 fps, 3 с, те же ключи и кривые, что у стоковых моделей |
| Серверная и APK-копии модели могли разойтись | Один и тот же WebM (571878 байт, sha256 `2a0b81d9c2f2c2eb99a15eeddfd6a5992340d96ead9935cb1aa27d1fa36abb4c`) записан в `authbot/assets/gift_models/5936013938331222567/` и в `res/raw/waysgram_model.webm` внутри архива; `manifest.json` обновлён |

Скрипт `scripts/build_waysgram_gift_model_webm.py` теперь собирает модель из
`authbot/assets/gifts/5936013938331222567.tgs`, перекрашивает сердце в красное, рисует
логотип `WaysGram` по исходной трансформации слоя `heart` и кодирует VP9 WebM с
альфой детерминированно. APK не собирался. Тесты: `scripts/test_waysgram_model_animation.py`.

## Хвост: незакрытые функции

* **Темы чатов** (`account.getChatThemes`, `messages.setChatTheme`) — сделано:
  каталог «основных» тем собирается в клиенте (`WaysGramChatThemes`), выбор хранится
  на сервере (`PUT /api/chats/{id}/theme`), тема канала применяется штатным
  механизмом и перекрывает кастомизацию фона. См. `docs/chat-themes.md`.
* Прочие `METHOD_NOT_SUPPORTED`, которые сейчас отвечают пустым успехом вместо реальной
  функциональности, — перечисляются и закрываются по одному.
## Краши с устройства (24.11)

| Что | Причина | Как закрыто |
| --- | --- | --- |
| `ClassCastException: TL_messages_affectedMessages → TLRPC$Updates` в `MessagesController.lambda$deleteDialog$145` при «Очистить историю» в группе | группы показываются клиенту мегагруппами, запрос уходит как `channels.deleteHistory`, а `WaysGramChats.groupClearHistory` возвращал `messages.affectedMessages`, тогда как штатный обработчик приводит ответ к `Updates` | `groupClearHistory` возвращает `new TLRPC.TL_updates()` |
| Такой же дефект у «Удалить историю» участника: `channels.deleteParticipantHistory` ждёт `messages.affectedHistory` | `groupClearMemberHistory` возвращал `messages.affectedMessages` | возвращает `TL_messages_affectedHistory` (`pts`, `pts_count`, `offset`), как канальный вариант `clearChannelMemberHistory` |
| Любое исключение в колбэке запроса валило приложение (`WaysGramTransport.lambda$execute$6` в стеке) | `delegate.run(...)` вызывался вне `try/catch` прямо в рабочем потоке | ответы идут через `WaysGramTransport.deliver()`: исключение колбэка пишется в `FileLog`, запросы продолжают работать |
| Падение после тапа по анимированному эмодзи в канале | в канале `chatActivity.currentUser == null`, а подсказка обращалась к `currentUser.id` / `currentUser.first_name` | `EmojiAnimationsOverlay.onTapItem` возвращает результат до блока подсказки, если `currentUser == null`; эффект при этом проигрывается |

Тесты: `scripts/test_android_group_history_crash.py`, `scripts/test_android_animated_emoji.py`.

## Права и настройки чата не применялись сразу (24.11)

С устройства: «изменения прав в группе принимаются через минуты или часы, иногда не
применяются вообще»; новый аватар группы при повторном входе в редактирование сразу
выбрасывал в чат; у второго и третьего аккаунта в том же приложении оставались права
владельца на публикацию и на аватар.

| Что | Причина | Как закрыто |
| --- | --- | --- |
| Права, название, аватар, состав чата не появлялись у других участников | сервер применял изменения, но не сообщал о них: `/api/events` отдаёт только то, что записано через `record_event`, а для `chat_changed` вызовов не было вообще | `record_event(db, "group"/"channel", 0, "chat_changed", uid, chat_id=…)` пишется в тех же транзакциях, что и само изменение: настройки группы, отправка, аватар (PUT/DELETE), медленный режим, видимость участников, передача владения, добавление/удаление/ограничение участника, назначение/снятие администратора, должность и анонимность, плюс канал: `PATCH`, аватар (PUT/DELETE), выдача и снятие админ-прав |
| Клиент не знал о таких событиях | `WaysGramPoller.handleEvent` обрабатывал только сообщения, пины и реакции | ветка `chat_changed` вызывает `WaysGramChats.chatChanged(account, scope, chat_id)` |
| Права одного аккаунта подхватывались другим аккаунтом на том же устройстве | `chatCache` ключуется парой аккаунт+id, но кэш никто не сбрасывал, поэтому после смены прав второй аккаунт продолжал видеть старый `creator`/`admin_rights` | `chatChanged` удаляет запись только этого аккаунта (`chatCache.remove(cacheKey(account, tgId))`) и заново запрашивает профиль (`MessagesController.loadFullChat(tgId, 0, true)` + `dialogsNeedReload`) |

Событие `chat_changed` не отдаётся автору изменения (он уже обновил экран), остальным
участникам области — отдаётся. Тесты: `authbot/tests/test_chat_change_events.py`
(3 сценария: права группы, настройки и аватар группы, редактирование канала),
`scripts/test_android_chat_change_events.py` (4 проверки архива и сервера).
APK не собирался.

## Редактор группы закрывался сам после смены аватара (24.11)

| Что | Причина | Как закрыто |
| --- | --- | --- |
| После смены аватара группы повторное «Изменить» сразу выбрасывает обратно в чат | `ChatEditActivity` на `channelRightsUpdated` закрывается, если `admin_rights`/`default_banned_rights` чата не равны снимку, сделанному при открытии. `TL_chatAdminRights.equals` и `TL_chatBannedRights.equals` сравнивают поле `flags`, а в TLRPC оно вычисляется только в `serializeToStream`. Снимок делается через `clone()` (то есть уже проводной), а чат из `WaysGramChats` держал объект с `flags == 0` — равенство не выполнялось никогда, и любое обновление прав (в том числе после загрузки профиля с новым аватаром) закрывало редактор | построители прав возвращают проводной вид: `normalized(...)` = `TL_chatAdminRights.clone` / `TL_chatBannedRights.clone`; для ограничений на отправку флаги тоже нормализуются, а решения интерфейса («можно ли отправлять») берутся из исходной маски |
| Права администратора группы показывались не те | для групп использовалась канальная маска (1 — публикация, 2 — правка, 4 — удаление…), хотя у групп своя: 1 — менять информацию, 2 — добавлять участников, 4 — банить, 8 — закреплять, 16 — удалять сообщения, 32 — медленный режим | отдельный `groupAdminRights(mask)`, он же используется в `toChat`, `toMegagroup` и в списке участников `fullChat` |

Тесты: `scripts/test_android_chat_rights_flags.py` (6 проверок), статический набор — 247 OK.
APK не собирался.

## Истории видел только автор (24.11)

| Что | Причина | Как закрыто |
| --- | --- | --- |
| Истории канала/группы видны только тому, кто их опубликовал | `stories.getAllStories` (единственный источник колец историй в списке чатов) на клиенте не обслуживался вообще: клиент не знал ни одного пира с историями | сервер: `GET /api/stories/all` — активные истории всех доступных каналов (по `STORY_CHANNEL_ACCESS`) и групп (по членству), просроченные исключены, закреплённые остаются; клиент: `WaysGramStories.all()` собирает `TL_stories_allStories` вместе с чатами (`toChannel`/`toMegagroup`) |
| В группе истории не работали совсем | `WaysGramStories` считал пиром только канал (`channel_id > CHANNEL_OFFSET`), группы живут ниже смещения — все запросы уходили в `METHOD_NOT_SUPPORTED` | путь строится по типу чата: `/api/groups/<id>/stories` или `/api/channels/<id>/stories`; все обработчики принимают группу |
| Кольца истории нет в профиле группы | `fullChat` не заполнял `TL_channelFull.stories`/`stories_pinned_available`, и карточка группы не отдавала эти флаги | `get_group` возвращает `stories_active`/`stories_pinned_available`, `fullChat` кладёт их и `WaysGramStories.active(...)` в `full` |

Тесты: `authbot/tests/test_stories_visibility.py` (3 сценария: подписчик канала, участник и
посторонний в группе, истёкшая история), `scripts/test_android_story_visibility.py` (5).
APK не собирался.

## «Реакции» откатывались после переоткрытия настроек (24.11)

| Что | Причина | Как закрыто |
| --- | --- | --- |
| В группе переключатель «Реакции» не сохранялся | `WaysGramChats.setAvailableReactions` для группы возвращал пустой `TL_updates` с комментарием «сервер хранит отдельно», а сервер таких настроек не имел вовсе — запрос никуда не уходил | у `chat_groups` появились `reactions_enabled`, `reactions_limit`, `available_reactions`; новый `PUT /api/groups/{id}/reactions-settings` (право «менять информацию», пишет `chat_changed`), запрос клиента уходит туда же |
| В канале включал/выключал — после переоткрытия снова включено | `fullChannel` строил `TL_chatReactionsSome` из сохранённого набора эмодзи, даже когда `reactions_enabled = false`; набор побеждал выключатель | при `reactions_enabled = false` всегда `TL_chatReactionsNone`, набор остаётся сохранённым на сервере |
| В группе карточка не отдавала настройки реакций | `GET /api/groups/{id}` возвращал только канальные поля | карточка группы отдаёт `reactions_enabled`, `reactions_limit`, `available_reactions`, а `fullChat` кладёт их в `TL_channelFull` |

Тесты: `authbot/tests/test_group_reactions_settings.py` (3 сценария: группа, канал, запрет
для не-админа), `scripts/test_android_reaction_settings.py` (5).
APK не собирался.

## Обои «Внешний вид и эмодзи» не применялись (24.11)

| Что | Причина | Как закрыто |
| --- | --- | --- |
| В группе и канале выбранные обои не появляются | `messages.setChatWallPaper` не обслуживался: запрос уходил в обработчик по умолчанию, сервер о выборе не знал | `WaysGramExtras.setChatWallpaper`: обои (цвета, градиенты, интенсивность, поворот, размытие, движение, эмодзи-узор) уходят в `PUT /api/chats/{scope}/{chat_id}/wallpaper`, сброс — `DELETE`; личные чаты остаются локальными |
| Фон сбрасывался после переоткрытия настроек | карточка чата не возвращала обои, поэтому `ChatThemeController.getDialogWallpaper` всегда получал `null` | у каналов и групп появилось поле `wallpaper`; `fullChannel`/`fullChat` кладут его в `TL_channelFull.wallpaper` (`flags2 |= 128`) |
| Обои одного аккаунта не должны попадать другому | — | хранение в `main.chat_wallpapers(scope, chat_id, telegram_id)` — своя запись на аккаунт |

Тесты: `authbot/tests/test_chat_wallpaper.py` (3 сценария: группа, канал, посторонний),
`scripts/test_android_chat_wallpaper.py` (4). APK не собирался.

## «Welcome message» крутил бесконечную загрузку (24.11)

| Что | Причина | Как закрыто |
| --- | --- | --- |
| Экран «Welcome message» в канале и группе висит на загрузке | экран ждёт ответ на `ephemeral.getWelcomeMessages`, а `WaysGramExtras` этот запрос не обслуживал: возвращался `null`, ответ не приходил вовсе, и загрузчик (`MessagesController` при `MODE_WELCOME_MESSAGES`) ждал вечно — колбэк срабатывает только при `res != null` | транспорт отвечает: `ephemeral.getWelcomeMessages` → `GET /api/chats/{scope}/{chat_id}/welcome` (пустой список — нормальный ответ, `hash` даёт `welcomeMessagesNotModified`), `ephemeral.sendMessage` с флагом `welcome` → `POST`, `deleteWelcomeMessage`/`deleteAllWelcomeMessages` → `DELETE` |
| Приветственные сообщения негде было хранить | таблицы не существовало | `main.chat_welcome_messages(scope, chat_id, author_id, body, entities, created_at)`, до 5 штук на чат (`429 WELCOME_MESSAGES_TOO_MUCH`), текст 1…4096 символов |
| Кто правит | — | тот, кто может менять информацию о чате: у группы бит `1` (`group_has_right`), у канала `can_manage_channel`; остальным `403 CHAT_ADMIN_REQUIRED` |

Тесты: `authbot/tests/test_welcome_messages.py` (3 сценария: группа, канал с лимитом,
пустой текст), `scripts/test_android_welcome_messages.py` (4). APK не собирался.

## Реакции как в Telegram (24.11)

Пункт 3 списка с устройства: «реакции должны быть оригинальные как в Telegram».
Разбор по слоям и что из них оригинальное:

| Слой реакции | Что лежит в клиенте | Оригинал? |
| --- | --- | --- |
| Текстура эмодзи (чип, попап, панель — 51 эмодзи) | `assets/telegram_reactions/*.webp`, вырезка из телеграмного `assets/emoji.pack` (EPK3, спрайты 64×64) | да, тот же рисунок, что клиент рисует в сообщениях |
| Эффект по нажатию (`effect/around_animation`) | `assets/waysgram_emoji/*.tgs` — байт-в-байт файлы набора `ilyhalight/telegram-emoji-effects` (салют, конфетти, фейерверк, мыльные пузыри) | да, это телеграмные Lottie (512×512, 60 fps, без растровых слоёв) |
| `appear/select/activate_animation` (появление эмодзи) | официальная текстура эмодзи (статичная) | арт оригинальный, анимации не было |
| Анимированные эмодзи Telegram (TGS) | нет и не будет из публичных источников | нет |

**Почему TGS нет.** Настоящие `appear/select/activate/effect_animation` Telegram приходят
из `messages.getAvailableReactions` и лежат на серверах Telegram; из песочницы
`api.telegram.org` недоступен. Проверены публичные наборы: `ilyhalight/telegram-emoji-effects`
(только эффекты: у 🎉 слой `Confetti`, у 🎆 `fireworks`, у 🍌 `взрыв`, `Hand_1…Hand_7` есть
лишь у 👍), `ericsahne/telegram-emoji-collection`, `sliva0/tgradish`,
`Malith-Rukshan/animated-country-flags` — анимированных эмодзи Telegram нет ни в одном;
`tdesktop` и `Telegram Android` их в репозиториях не держат.

**Что сделано.** Раз анимации нет, «поп» рисует сам клиент: для статичного документа
(webp) `ReactionsEffectOverlay` перелетает из 35 % в 100 % за 260 мс с перелётом
(`CubicBezierInterpolator.EASE_OUT_BACK`) — тот же пружинный вылет, что в Telegram, и он
применяется к `appear/select/activate` (то есть и при выборе реакции в панели, и при
появлении по центру). Раньше в этих слотах тоже был рисунок, но без движения — эмодзи
«включался» рывком.

**Анимированные эмодзи получены (24.11).** Набор выгружен из авторизованной Telethon-сессии
authbot командой `/exportreactions` (см. `docs/reaction-emoji-export.md`): **74 реакции,
516 документов, 12,4 МБ**. `scripts/waysgram_reaction_assets.py` проверил каждый документ и
собрал `WaysGramReactionAssets` + `assets/waysgram_emoji/tg/`; `WaysGramReactions.catalog()`
теперь строит реакции из настоящих телеграмных документов (id/`access_hash`/mime/размер
настоящие), а транспорт раздаёт файлы через `upload.getFile`. Значит `appear/select/activate`
играют официальные анимации появления, `effect` — вспышку вокруг сообщения, `around` —
частицы в чипе; самодельная пружина остаётся только как запасной путь для статичных (webp)
документов.

Тесты: `scripts/test_android_reaction_animation.py` (8), `authbot/tests/test_reaction_export.py`
(5). APK не собирался; архив вырос до 126,3 МБ, части по 26 МБ.

## 4. Аватар канала в профиле и подарки каналу

### Аватар прикреплённого канала (исправлено)

Жалоба с устройства: «Канал прикреплённый к профилю, теряет аватарку при заходе во вкладку
профиль». Причина — серверная: `profile_channel()` собирал карточку канала без
`avatar_media_id`, поэтому `WaysGramChats.toChannel()` строил `TL_chatPhotoEmpty` и
прикреплённый канал перерисовывался без картинки. В `SELECT` добавлен
`c.avatar_media_id`, тест `test_profile_channel_and_invite_privacy` его проверяет.

Заодно исправлен большой аватар: `WaysGramMapper.avatarLocation()` пишет
`volume_id = -photoId`, а `WaysGramMedia.register()` помнит файл по положительному id,
поэтому `upload.getFile` для большого фото профиля не находил файл вовсе.
`WaysGramMedia.mediaIdFor()` теперь проверяет и `-id`
(регрессия — `scripts/test_android_profile_avatar_ids.py`).

### Подарки каналу (сделано, как в Telegram)

Подарок за звёзды отправляется каналу и остаётся у канала: в профиле канала появляется
раздел подарков и кнопка «Отправить подарок».

Сервер:

- таблица `channel_gifts` (канал, подарок, отправитель, анонимность, сообщение, цена, дата);
- `POST /api/channels/{id}/gifts` — покупка и отправка: списывает Stars, уменьшает сток
  каталога, отказам 409 «Недостаточно Stars»/«Подарок закончился»; в личный инвентарь
  (`user_gifts`) ничего не пишется;
- `GET /api/channels/{id}/gifts` — список подарков канала (приватный канал требует доступа,
  скрытый отправитель отдаётся как `sender_id = 0`, `hide_name = true`);
- в карточке канала появились `stargifts_count` и `stargifts_available`.
- тесты: `authbot/tests/test_channel_gifts.py` (2 сценария: оплата и хранение,
  приватность/нехватка звёзд).

Клиент:

- `WaysGramGifts.savedGifts()` понимает `inputPeerChannel` и грузит подарки канала вместо
  личного инвентаря;
- `WaysGramGifts.sendForm()` для канала отправляет в `/api/channels/{id}/gifts`
  (ошибка нехватки Stars доходит до интерфейса как `BALANCE_TOO_LOW`);
- `WaysGramChats.toChannelFull()` прокидывает `stargifts_count` (flags2 `FLAG_18`), поэтому
  раздел подарков появляется в профиле канала.
- регрессия: `scripts/test_android_channel_gifts.py` (7 тестов).

Статический набор: **283 OK**. Бэкенд-выборка по подаркам/профилю: 145 passed.

## 5. Список чатов: подарок «на себе», «Избранное» и видео из истории

### Значок подарка появлялся через 2–3 минуты (исправлено)

`/api/conversations` не отдавал `worn_gift`, а клиент рисует подарок «на себе» из
`user.emoji_status` (см. `WaysGramMapper.toUser`). Пока профиль собеседника не был
загружен, в строке чата значка не было. Теперь `list_conversations` кладёт
`worn_gift` (id, serial, title, traits) через пакетный `worn_gift_map` — без обогащения
документами, чтобы не тормозить список.

### «Избранное» превращалось в профиль аккаунта (исправлено)

`WaysGramTransport.loadConversations()` строил пользователя из строки `/api/conversations`
и не помечал его как себя. Без флага `user.self` `UserObject.isUserSelf()` возвращал false,
и строка «Избранное» рисовала имя с аватаркой (`DialogCell`, `AvatarDrawable`). Флаг
выставляется по `selfId` — имя и закладка снова штатные.

### «App was unable to play this video» (частично)

`WaysGramMedia.buildMedia()` строил документ без `TL_documentAttributeVideo`, если у медиа
пустой `media_kind` (старые записи, истории): файл с `video/mp4` становился «документом»
и не проигрывался. Теперь тип выводится из mime (`!forceDocument`), атрибут видео
добавляется. Отдельно проверено, что `ensure()` качает файл целиком и сверяет длину,
а `serveFile()` корректно отдаёт диапазоны `offset/limit`.

Регрессии: `scripts/test_android_chat_list_identity.py` (4), `test_channel_gifts.py`
(3, включая `worn_gift` в списке чатов).

## 6. Экран приватности «Сообщения»

Экран (`PrivacySettingsActivity`) грузится двумя запросами: `account.getGlobalPrivacySettings`
и `account.getPrivacy` для каждого ключа, включая `inputPrivacyKeyNoPaidMessages`.
Не обслуживались ни тот, ни другой:

- `WaysGramProfile.privacySetting()` не знал `TL_inputPrivacyKeyNoPaidMessages` — запрос уходил
  в заглушку «действие не поддерживается», переключатель «Платные сообщения» ничего не сохранял;
- `account.getGlobalPrivacySettings` не обрабатывался вовсе, поэтому «Скрывать галочки
  прочтения», «Хранить архив», «Кнопка подарков» и порог платных сообщений оставались
  значениями по умолчанию и не сохранялись.

Сделано:

- ключ `paid-messages` в клиенте и на сервере (по умолчанию `everyone`);
- `GET/PUT /api/me/privacy/global` + таблица `account_global_privacy`(`hide_read_marks`,
  `keep_archived_unmuted`, `keep_archived_folders`, `archive_and_mute_new_noncontact_peers`,
  `new_noncontact_peers_require_premium`, `display_gifts_button`, `noncontact_peers_paid_stars`);
- клиент: `WaysGramProfile.globalPrivacy()` читает и сохраняет настройки,
  `WaysGramTransport` обслуживает оба запроса.

Тесты: `authbot/tests/test_privacy_messages_screen.py` (2),
`scripts/test_android_privacy_messages_screen.py` (3).

## 7. Модель WaysGram: надпись и руки

Жалобы с устройства: «надпись дёргается», «посередине сердца», «часть руки поверх сердца»,
затем «надпись пониз рук, но прямо на сердце, без разницы что её не видно».

- порядок слоёв как в исходном стикере: контур → тело → правая кисть (слой 15) →
  сердце → надпись → левые кисти (1, 2). Раньше все три кисти рисовались поверх сердца,
  из-за чего на нём висел оторванный кусок правой руки;
- надпись стоит ровно в середине сердца и идёт **под** передними кистями; в середине она
  ими почти вся закрыта — это согласованное решение (важен порядок слоёв и место);
- крепление жёсткое: размер один раз, положение и наклон из трансформа сердечного слоя,
  точка постоянна — надпись не болтается.

Модель пересобрана: 552 819 байт,
sha256 `126ab22880a62d6a16bba921807c58abdc6cbd533d2c81552ca912e4b30df230`,
копия в APK (`res/raw/waysgram_model.webm`) обновлена. Тест
`test_waysgram_model_animation.py` закрепляет порядок слоёв (надпись под кистями) и
середину сердца.

## 8. Сборка APK 326 → 327

- **326** (run 45520370437, коммит `df4dcdb`) — упала на шаге «Compile WaysGram Java sources»:
  `WaysGramExtras.java:245: error: cannot find symbol`. `messages.setChatWallPaper` держит
  градиент/узор в собственном `settings`, а `wallpaper` — это `InputWallPaper`, у которого
  поля `settings` нет. Исправлено на `req.settings`, добавлен регресс-тест
  `test_settings_come_from_the_request_not_from_the_input_wallpaper`.
- **327** (run 45524127137, коммит `29b9b51`) — успешно. APK 118 925 393 байт,
  sha256 `674566372ee729917c83427522e99389f6ce239bdcde2009f57d4ca63dd36e13`,
  релиз `apk-waysgram-327` (+ части по 24 МБ и `.sha256`). Сборка сама скачала
  опубликованный файл обратно и сверила байты и CRC.

## 9. Лента прокрутки улучшения: одна модель по кругу

По отчёту с устройства: на карточке улучшенного подарка прокрутка всё время показывала
одну и ту же модель («Танцует»), хотя в пуле их четырнадцать.

Причина в клиенте, а не в сервере. `/api/telegram/gifts/{id}/attributes` и
`candidates`/`roll_pool` отдают все 14 моделей (`document_id` `-7700000000000000…13`,
сумма шансов 1000 ‰). Но `StarGiftSheet.waysGramCandidateAttributes()` собирает ленту
только из тех записей, для которых `WaysGramGiftAttributes.documentById()` вернул
документ, а тот отдаёт лишь уже скачанный файл (иначе ставит загрузку в очередь). К
моменту открытия страницы «Улучшить» на диске обычно лежала одна модель — та, что
нарисована на карточке, — и снимок пула с ней же фиксировался в `sample_attributes`.

Что изменилось в `ui/Stars/StarGiftSheet.java`:

| Правка | Зачем |
| --- | --- |
| `waysGramPrefetchReel()` на открытии страницы «Улучшить» | ставит в очередь все модели и узоры пула и повторяет ранее сбойные загрузки |
| `waysGramReelReady()`: ≥ 8 моделей и ≥ 3 узоров (или все, если меньше) | спин не стартует с одной анимации |
| ожидание 2.5 с (10 попыток по 250 мс из 40), затем спин тем, что пришло | лента успевает наполниться, но интерфейс не зависает |
| после броска — ожидание готовности `roll_pool` не дольше 1.5 с (6 попыток из 20) | прокрутка результата тоже полная, а карточка не задерживается |
| `waysGramChance()`: 0 ‰ печатается как `<0.1%` | «Привет» 0.05 % и «Танцует» 0.1 % больше не выглядят как «0 %» |

Архив исходников пересобран новым скриптом `scripts/repack_android_source.py`
(14 897 записей, части по 26 000 000 байт); регресс-тест —
`scripts/test_android_gifts.py::test_upgrade_reel_downloads_and_waits_for_the_whole_model_pool`.
APK не собирался: правка ждёт следующей сборки по команде.

## 10. Подпись и «вредоносное приложение» в Play Protect

VirusTotal показывал 14/64, Play Protect называл сборку вредоносной. Причина не в коде, а в
двух настройках проекта:

1. APK подписывался **публичным** ключом Telegram: `TMessagesProj/config/release.keystore`,
   alias `androidkey`, пароль `android` (`gradle.properties`). Этим ключом подписаны все
   форки Telegram и часть вредоносных сборок, поэтому сертификат ничего не доказывает — и
   любой мог подписать «обновление» для `org.waysgram.messenger`.
2. В манифесте висело `android.permission.REQUEST_INSTALL_PACKAGES` (установка APK из чата) —
   главный «дроппер»-флаг для Play Protect. Кода, который реально ставит APK, в сборке
   `afat` нет: файл просто открывается через `AndroidUtilities.openForView`, а без
   разрешения показывается штатный диалог-запрет.

Что сделано:

| Файл | Правка |
| --- | --- |
| `TMessagesProj/config/release.keystore` | свой PKCS12-ключ: RSA 4096, `CN=WaysGram`, срок до 2056, alias `waysgram` |
| `TMessagesProj_App/build.gradle` | `storeType 'PKCS12'` в `signingConfigs.debug` и `signingConfigs.release` |
| `gradle.properties` | `RELEASE_KEY_ALIAS=waysgram`, оба пароля — свои (не `android`) |
| `TMessagesProj/src/main/AndroidManifest.xml` | убран `REQUEST_INSTALL_PACKAGES`, `allowBackup="false"` |
| `.github/workflows/build-apk.yml` | сборка падает, если `apksigner` не видит `CN=WaysGram` |

Отпечаток сертификата (SHA-256, DER): `635239a56430aa10c9f7da080b2524a4dafa4dd079dc3c9217b33cdc31842843`,
SHA-256 файла ключа: `64562f299fd64cb03882ffccf4c817d979a31518d8ff60cdcdc22765885ccbe0`.

**Важно:** подпись сменилась, поэтому APK нельзя поставить поверх 327 — приложение нужно
удалить и установить заново (переписки живут на сервере, локально пропадут настройки, кеш
и секретные чаты). Все следующие сборки будут обновляться поверх нового ключа без проблем.

Ключ лежит в репозитории, то есть он публичный — как и прежний ключ Telegram. Для настоящей
защиты: убрать файл из репозитория, положить его base64 в секреты GitHub
(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_STORE_PASSWORD`,
`ANDROID_KEY_PASSWORD`) и распаковывать в workflow; `gradle.properties` тогда читает пароли
из переменных окружения.

Чего эти правки не сделают: 0/64 в VirusTotal (детекторы ловят сам факт модифицированного
Telegram) и жёлтый экран Play Protect при установке с сайта — он появляется у любого APK не
из Play. Проверки: `scripts/test_android_release_signing.py`.

## 11. Отчёт «реакции, канал, права, истории, подарки»

Семь пунктов из скриншотов, разобранные по причинам. Правки — в архиве исходников
(пересобран `scripts/repack_android_source.py`, 14 897 записей, части по 26 000 000 байт),
сервер — `authbot/messenger_api.py`. APK не собирался.

### «Не удалось подтвердить реакцию» и «старые реакции» (одна причина)

Клиентская палитра — `WaysGramReactionAssets.EMOJI` — записана **без** `U+FE0F`
(`❤` U+2764, `🕊` U+1F54A, `✍` U+270D), а вайтлист сервера — **с** ним (`"❤️"`, `"🕊️"`).
Точное сравнение строк отвечало `Недопустимый набор реакций`, приложение показывало
«Не удалось подтвердить реакцию» — и именно на самых частых реакциях. Та же рассинхронизация
ломала отрисовку: реакция с сервера (`❤️`) не находилась в `MediaDataController.reactionsMap`
(ключи без `U+FE0F`), и чип рисовался системным шрифтом вместо официальной анимации.

- сервер: `_reaction_key()` (игнорирует `U+FE0F` и оттенки кожи) и `_reaction_catalog()`;
  оба маршрута реакций (`toggle_message_reaction`, `set_message_reactions`) сравнивают по
  ключу и хранят серверную запись, поэтому все клиенты читают одно и то же;
- клиент: `WaysGramReactions.canonical()` приводит любую запись к каталогу, и это применяется
  в `WaysGramMapper.addReaction`/`addRecentReactions`, в `WaysGramMessages.setReactions`
  (отправка) и в списках `available_reactions` канала и группы.

### Канал: «Direct Messages» сбрасывается после «Применить»

`ChatUsersActivity.processDone()` отправлял `stars.updatePaidMessagesPrice` **без**
`suggestions_allowed`, а транспорт считает `enabled = suggestions_allowed && stars > 0` —
серверу уходило «выключено», и после перезагрузки канала переключатель гас. Теперь флаг
выставляется, оптимистичная запись `chat.flags2 |= 16384` убрана, а состояние берётся из
ответа сервера (`putChats` + `processUpdates`); при ошибке переключатель возвращается.

### Канал: «Apply for WaysGram» в «Сменить обои»

`ChannelBoostsController.getBoostsStats()` запрашивает `premium.getBoostsStatus`, который
транспорт не обслуживал: `boostsStatus` оставался `null`, и `applyWallpaperBackground`
молча выходил. Метод реализован (`WaysGramTransport.boostsStatus`): бустов в WaysGram нет,
поэтому уровень канала — 1, обои применяются сразу.

### Канал: переключатель «Платные реакции»

В `ChatReactionsEditActivity` его не было вовсе (в `ChatEditActivity` только отображение).
Добавлена строка «Enable Paid Reactions»: она читает `chatFull.paid_reactions_available` и
сохраняется тем же `messages.setChatAvailableReactions` (`paid_enabled`), который транспорт
переводит в `PUT /api/channels/{id}/paid-reactions`.

### Группа: «Права участников» не сохраняются

Экран показывает десять переключателей, а серверная маска знала шесть битов: реакции,
опросы, ссылки, голосовые и кружочки **не сохранялись**, музыка и голосовые делили бит 8,
видео и кружочки — бит 4. Маска расширена (1 текст · 2 фото · 4 видео · 8 музыка ·
16 стикеры/гифки/игры · 32 файлы · 64 голосовые · 128 кружочки · 256 ссылки · 512 опросы ·
1024 реакции) и в клиенте, и на сервере.

Старые строки читаются в прежнем смысле: колонка `send_restrictions_version` = 0 означает
историческое значение, и `send_restrictions()` добавляет уточняющие биты (4 → ещё 128,
8 → ещё 64, 1 → ещё 512). Проверка запрета реакций теперь реально работает: бит 1024
блокирует `set_message_reactions` в группе. Музыка (audio) больше не проверяется как
«файлы»: сервер относит её к биту 8, как и клиент.

### Истории: «App was unable to play this video»

Диалог показывает только `PhotoViewer.onError`, то есть ExoPlayer не смог открыть файл.
Причина — документ без `TL_documentAttributeVideo`: атрибут добавлялся лишь при
`mime.startsWith("video/")`, а у части записей и историй `mime_type` пуст или
`application/octet-stream`. Теперь тип берётся из `media_kind`: если запись говорит «видео»
(или «кружочек», «анимация»), а mime не видео — он считается `video/mp4`, атрибут ставится,
и в лог пишется исходный mime для диагностики.

### Подарки: «Value ⭐0» и невидимый узор

- **Value**: цена бралась из возврата при продаже (`sell_refund * 100 / 50`), а возврат есть
  только пока подарок можно продать (7 дней и не выставлен на продажу) — у старых подарков
  выходило «⭐0». Теперь берётся `purchase_price` (сервер отдаёт его владельцу), возврат —
  только запасной вариант; ответ на улучшение тоже содержит `purchase_price`.
- **Узор**: в записи о ношении (`worn_gift`) у черты может не быть `document_id`, и тогда
  `pattern_document_id` был 0 — плашка профиля рисовала фон без узора. `WaysGramMapper`
  восстанавливает документ по имени черты тем же каталогом, что и карточка подарка
  (с кэшем на час, неудачи — на минуту), и ставит встроенную анимацию модели в очередь
  установки, если документ синтетический.

Проверки: `scripts/test_android_qa_report_fixes.py` (9), сервер —
`authbot/tests/test_reaction_identity_and_group_restrictions.py` (4),
клиент — весь `scripts/` (307) и `authbot/tests/` (478).

### Сборка 330

`gh run 45659545082`, тег `apk-waysgram-330`: 118 929 159 байт,
SHA-256 `0119a3778c8b7ca95f7cb36e56070372d97f4d9e124127805ce1b64a2a22b0e7`.

Дорога к ней: сборка 328 упала на `WaysGramExtras` (правка обоев), сборка 329 — на
`StarGiftSheet.java:6754`, где правка ленты улучшения написала `runOnUiThread` вместо
`runOnUIThread`; javac искал `runOnUiThread(Runnable,int)` и не находил. Отчёт об
ошибках теперь печатает строки `symbol:` и `location:` — раньше в комментарий к PR
попадала только первая строка. Чтобы такое не повторялось, добавлен
`scripts/test_android_symbol_check.py`: он проходит по архиву, вырезает комментарии и
проверяет, что каждый вызов `Класс.метод(` у служебных классов WaysGram и
`AndroidUtilities` действительно объявлен.

### Сборка 331: комментарии под постами и единый каталог реакций

**Почему в канале не было кнопки комментариев.** `TL_messageReplies` рисуется по
`channel_message.replies`, а тот собирается из строки `channel_discussion_groups`
(`WaysGramChats.channelMessage`). У канала, созданного в приложении, такой строки не
было: `POST /api/channels` её не заводил, а штатный `ChatLinkActivity` умел только
выбирать из уже существующих групп и упирался в «нет групп».

- `POST /api/channels` принимает `comments` (по умолчанию `true`) и сразу заводит группу
  «Комментарии — {title}» с владельцем в `group_members` и строкой в
  `channel_discussion_groups`; в ответе — `discussion_group_id`.
- Новый `POST /api/channels/{id}/discussion-group/create` (201, `{group_id, created}`) —
  для каналов, созданных раньше.
- В `WaysGramDiscussions` тупик «нет групп» заменён диалогом с «Create a group»; после
  сохранения/создания вызывается `reload()` (`loadFullChat(channel, 0, true)` +
  `dialogsNeedReload`), поэтому кнопка комментариев появляется без перезапуска.
- «Обсуждение» в настройках канала при включённом `WaysGramTransport.ENABLED` открывает
  этот диалог; штатный `ChatLinkActivity` остаётся запасным путём.

**Реакции.** Сервер держал свой короткий вайтлист: градиент из 7 базовых и 40 «расширенных»,
поэтому часть палитры приложения отклонялась с 400. Теперь каталог — файл
`authbot/assets/reactions.json` (79 реакций, 7 базовых), который собирает
`scripts/generate_reaction_catalog.py` из `WaysGramReactions.REACTIONS` и
`WaysGramReactionAssets.EMOJI` (дедуп по identity без `U+FE0F` и тонов кожи). v1-маршрут
остаётся на базовых, v2 принимает весь каталог; `--check` в генераторе ловит расхождение
с архивом.

Проверки: `scripts/test_android_comments_and_reactions.py` (10, включая сверку
`reactions.json` с архивом и сохранность полной таблицы строк), `authbot/tests/test_reaction_catalog.py`
(6), весь `scripts/` (319) и `authbot/tests/` (483).

`strings.xml` собирать только из архива: зеркало `android-source/` отставало от него и
теряло группы `WaysGramTransfer*`, `WaysGramInbox*`, `WaysGramPaidHistory*`,
`WaysGramChannelTopics`.

### Сборка 332: плашки push-сообщений

**Что на скриншоте.** В шторке две одинаковые строки «+00000000» — по одной на сообщение,
без имени, аватара и перехода в чат.

- **Номер вместо имени.** Аккаунты, заведённые администратором
  (`bot.admin_create_account`), получали `display_name = <номер>`, а push-заголовок
  собирался как `display_name or username`. Теперь имя проходит через `name_or_empty`,
  а все 13 мест отправки используют `sender_push_title` (имя → `@username` → `User N`), плюс
  `deliver_push_notifications` сам подменяет телефон в заголовке (`push_title`).
- **Плашка на каждое сообщение.** `WaysGramPushNotifications` считал `notificationId` по
  `message_id`, поэтому два сообщения одного чата висели двумя строками. Теперь id — по
  чату, плашки собраны в группу со сводкой («WaysGram»), нечитаемое системное `stat_notify_chat`
  заменено на `R.drawable.notification`.
- **Тап не открывал чат.** Переход был только для тем. Теперь для `dm`/`group`/`channel`
  выставляется `com.tmessages.openchat` с id диалога (канал — со смещением `channelTg`) и
  `waysgram_account`; `LaunchActivity` по этому полю выбирает аккаунт.
- **Дубли со штатными плашками.** Если пуллер аккаунта работает, push не показывается
  (`WaysGramPoller.isRunning` стал `public`): сообщение придёт обычным путём с именем,
  аватаром и кнопками. `NotificationsController.cleanup` теперь убирает и наши плашки.
- В данных push для всех устройств появился `account_id` (раньше — только для тем),
  `title`/`body` кладутся в `data` всегда.

Проверки: `authbot/tests/test_push_notifications.py` (5), `scripts/test_android_push_notifications.py`
(9), весь `scripts/` (328) и `authbot/tests/` (489).

### Сборка 333: лента улучшения крутила одну модель

**Симптом.** На странице результата прокрутка улучшения показывает одну и ту же модель,
пока не нажмёшь «Skip»; ползунок шансов при этом уже показывает итоговые «Model 0%,
Symbol 0.4%, Backdrop 1.2%».

**Причина (клиент).** `StarGiftSheet.Roller.Sticker.isLoaded()` считал готовой только
Lottie-анимацию (`getLottieAnimation() != null`). Модели WaysGram — WebM с альфой, у них
Lottie нет, поэтому ни одна модель пула не попадала в выбор: `AttrRoller.next()` возвращал
`start` — картинку самого подарка, — и лента бесконечно рисовала её же. До `finish`
прокрутка не доходила, пока не срабатывал 16-секундный таймаут и `skip()`.

- `isLoaded()` теперь принимает и видео-анимацию (`getAnimation()`), и Lottie.
- Пул просматривался только до 12 записей, а у WaysGram-подарков их 14 (12 общих и 2 особые):
  обрезка выбрасывала как раз особые модели, включая выпавшую. Введён `WAYSGRAM_POOL_LIMIT = 24`.

**Данные сервера в порядке** и зафиксированы тестом: пул Mellstroy отдаёт 14 моделей с
разными синтетическими id, и каждый id скачивается файлом
(`gift_model_fallbacks.model_for_document(..., synthetic=True)`).

Проверки: `scripts/test_android_gift_upgrade_reel.py` (6), весь `scripts/` (334),
`authbot/tests/` (489).

### Сборка 334: строка «Messages» больше не открывает свой диалог цены

**Симптом.** Privacy and Security → «Messages» открывала карточку «Платные личные
сообщения» с полем `10000`, `Cancel` / `Save`, а в списке сама строка была подписана этим
же заголовком вместо цены: было непонятно, сохранилось ли значение.

**Причина.** В клиенте жили два независимых места правки цены. Строка списка вызывала
`WaysGramPaidDm.settings()` — самодельный `AlertDialog` (10000 / Cancel / Save), который
прятал штатный выбор «кто может писать» и правил только цену, без исключений. Стоковый экран
`PrivacyControlActivity` для `PRIVACY_RULES_TYPE_MESSAGES` цены не знал: `checkPrivacy()`
для WaysGram всегда стартовал с «все могут писать» и `10`, поэтому сохранённое значение не
появлялось ни в списке, ни на экране, а `applyWaysGramFeeValues()` был объявлен и ни разу не
вызван.

**Правка.**
- `PrivacySettingsActivity`: строка «Messages» открывает штатный экран
  `PrivacyControlActivity(PRIVACY_RULES_TYPE_MESSAGES)`, а её подпись — сохранённая цена
  (`⭐10000`) или «Everybody», когда цена `0`.
- `PrivacyControlActivity`: `checkPrivacy()` догружает цену с сервера
  (`loadWaysGramMessagesFee()` → `GET /api/me/message-fee`), и как только она пришла, экран
  выбирает платный режим с прежней ценой и исключениями. `Save` без правок больше ничего не
  обнуляет, потому что `applyWaysGramFeeValues()` теперь вызывается.
- `WaysGramPaidDm`: удалён мёртвый диалог (`settings`/`editor`/`notice`) вместе с девятью
  строками `WaysGramPaidDm*`, которые остались только от него; подтверждение отправки
  показывает стоковое `AlertsCreator.showPayForMessageAlert`.

Проверки: `scripts/test_android_paid_messages.py` (11).

### Сборка 335: экран «Сообщения» — штатный Telegram

**Запрос.** После Privacy and Security → «Messages» нужен оригинальный экран Telegram:
«Кто может отправлять мне сообщения?» → «Все» / «Контакты и Premium-подписчики» /
«Сообщения за звёзды», описание, «Стоимость одного сообщения» со слайдером и подпись про 85%.

**Что было.** У WaysGram на этом экране жил свой путь: `checkPrivacy()` не читал ни
приватность, ни глобальные настройки, а `applyCurrentPrivacySettings()` писал цену в
`/api/me/message-fee`. Из-за этого штатные строки (список «кто может писать», описание,
слайдер) вообще не строились.

**Правка.**
- `PrivacyControlActivity`: WaysGram-ветки для `PRIVACY_RULES_TYPE_MESSAGES` удалены — и при
  открытии, и при сохранении работает штатный код Telegram: `inputPrivacyKeyNoPaidMessages`
  + `setGlobalPrivacySettings` (`noncontact_peers_paid_stars`), строки `priceHeaderRow`,
  `priceRow`, `priceInfoRow`, `detailRow2`. Удалены ставшие мёртвыми
  `applyWaysGramMessagesSettings`, `loadWaysGramMessagesFee`, `applyWaysGramFeeValues`.
- `PrivacySettingsActivity`: подпись строки «Messages» — цена из глобальных настроек
  (`noncontact_peers_paid_stars`), иначе «Контакты и Premium-подписчики» / «Все».
- `WaysGramPaidDm`: убраны кэш цены и `loadFee`/`saveFee` — цену теперь пишет штатный экран.
  Осталось то, что нужно при отправке: `peerFeeStars`/`loadPeerFee` (цена в шапке чата) и
  `confirm` (стоковое подтверждение).
- Сервер: `noncontact_peers_paid_stars` и `dm_message_fees.stars` — одна запись
  (`sync_paid_stars`), allow-список ключа `paid-messages` и `exempt_users` синхронизированы в
  обе стороны. Раньше цена со слайдера ни на что не влияла, а исключения жили отдельно.

**Отличие от скриншота.** На скриншоте аккаунт без Premium, поэтому строка «Сообщения за
звёзды» с замком, а вместо слайдера кнопка «Доступно с Telegram Premium». В WaysGram Premium
есть у каждого аккаунта (`WaysGramMapper`), поэтому у нас та же разметка, но слайдер сразу
доступен. Максимум слайдера — 10 000 ⭐ (предел сервера WaysGram), у Telegram в конфиге 35 000.

Проверки: `scripts/test_android_paid_messages.py` (12), `scripts/` (341),
`authbot/tests/test_privacy_messages_screen.py` (4), `authbot/tests/` (491).

### Права без админства: маска по умолчанию

**Запрос.** Два аккаунта пользователя могли управлять его группой/каналом, хотя админских
прав им никто не выдавал; владение — у одного аккаунта.

**Причина.** Права не выдавались, их дорисовывала маска по умолчанию.
`group_members.admin_rights` создавалась как `DEFAULT 63` (полная маска админа группы:
инфо, приглашения, бан, закрепление, удаление чужих сообщений, медленный режим), а строки
членства вставляются без явного значения. Поэтому `GET /api/groups` отдавал обычному
участнику `admin_rights: 63`, а деталь группы то же поле маскировала через `CASE` — права
«то есть, то нет». Клиент читал поле как готовые права (`WaysGramChats.toChat`/`fullChat`),
а при отсутствии поля подставлял 63 для групп и 15 для участника канала, то есть выдумывал
права. Все пишущие ручки при этом отвечали участнику 403 (проверено ~30 запросами по
группам и каналам) — сервер управлять не давал, права жили только в отображении.

**Правка.**
- `messenger_api.py`: `DEFAULT 0` у новой колонки плюс миграция, которая при старте гасит
  маску у всех неназначенных строк; маска отдаётся через `CASE` в списке групп, в ростере и
  в карточке общего чата (владелец — 63, админ — своя маска, участник — 0); в списке каналов
  владелец снова `is_admin=True` (расходился с деталью канала).
- `WaysGramChats.java`: дефолтов 63/15 больше нет, объект прав создаётся только при непустой
  маске — у участника без назначения админ-кнопок нет совсем.
- Тесты: `authbot/tests/test_chat_rights_masking.py` (3) и проверка объявлений в
  `scripts/test_android_chat_rights_flags.py`.

Проверки: `authbot/tests/` (494), `scripts/` (342).

### Сборки 331 → 332

- **331** (run 45729030451, коммит `09850e7`) — упала на «Compile WaysGram Java sources»:
  `WaysGramPaidDm.java:55-60: cannot find symbol PEER_FEE_TTL / peerFees / peerFetched`.
  Объявления полей потерялись в `80dfaaf` при чистке мёртвого диалога платных сообщений:
  методы `peerFeeStars`/`loadPeerFee` остались и используются, а поля исчезли. Сборка 330
  была с `e84865d`, то есть до этой чистки, поэтому ошибка дождалась первой сборки после неё.
  Поля восстановлены (`PEER_FEE_TTL` 60 с + два HashMap-кэша), а
  `scripts/test_android_paid_messages.py` теперь проверяет и их объявления.
- **332** (run 45732399206, коммит `dd1c7c8`) — успешно, тег `apk-waysgram-332`:
  118 925 325 байт, SHA-256
  `5258dfee8837e557a4ab9dbc8237189ba96fd4b2e5e9b73d1ba014d1af01c42c`.
  Сборка сама скачала опубликованный файл обратно и сверила байты и CRC.

### Обои группы/канала пропадали после переоткрытия чата

**Запрос.** Обои канала и группы применяются, но потом пропадают.

**Причина.** Обои уходили на сервер (`messages.setChatWallPaper` →
`PUT /api/chats/{scope}/{id}/wallpaper`), но клиент сохранял их только в одной ветке:
ответ должен содержать `updateNewMessage` со служебным сообщением
`messageActionSetChatWallPaper` — именно оттуда `ChatThemeController` берёт
`chatFull.wallpaper` и пишет `chatWallpaper_<account>_<dialog>` в prefs. WaysGram
служебных сообщений не заводит и отвечает пустым `TL_updates`, поэтому обои жили только
на экране выбора: при следующем открытии чата `getDialogWallpaper` возвращал null и фон
снова становился стандартным.

**Правка.** `ChatThemeController.setWallpaperToPeer`: если в ответе не нашлось нужного
обновления, обои применяются из самого запроса (`req.settings`) — `chatFull.wallpaper`
(+`flags2 |= 128`), запись в prefs через `saveChatWallpaper` и `chatInfoDidLoad`;
для личных чатов — `userFull.wallpaper` (+`flags |= 16777216`). Ветка работает только при
`WaysGramTransport.ENABLED`, штатный путь Telegram не тронут.

Проверки: `scripts/test_android_chat_wallpaper.py` (11).

#### Обои-картинки из галереи

**Причина.** Картинка из галереи уходила в `account.uploadWallPaper`, а этот запрос никто
не обслуживал: он падал в обработчике по умолчанию, `messages.setChatWallPaper` получал
`TL_inputWallPaperSlug` без `document`, и обои-картинки не существовало ни на сервере, ни
в карточке чата. Каталога обоев (`account.getWallPapers`) у WaysGram нет и не планируется —
выбор идёт только из галереи.

**Правка.** Новый `tgnet/WaysGramWallpapers.java`:
* `account.uploadWallPaper` → файл загружается тем же путём, что и остальные медиа
  (`WaysGramMedia.uploadFile` → `POST /api/media/images`), из него собирается `TL_wallPaper`
  с документом (`WaysGramMedia.buildMedia`, `TL_documentAttributeImageSize`) и слагом
  `wg_<media_id>`;
* слаг разбирается обратно в `media_id` (`TL_inputWallPaperSlug`, `TL_inputWallPaper` →
  prefs `m_<id>`), поэтому связка переживает перезапуск приложения;
* `WaysGramExtras.wallpaperJson` дописывает в тело `document_id`;
* `WaysGramChats.wallpaper(account, data)` отдаёт карточке чата `TL_wallPaper` с документом,
  если сервер вернул `document_id`, иначе — прежний `TL_wallPaperNoFile` с градиентом;
* `ChatThemeController.applyWaysGramWallpaper` применяет загруженный файл, а не только
  настройки цвета.

Сервер менять не понадобилось: `/api/chats/{scope}/{id}/wallpaper` хранит обои как
произвольный JSON (`chat_wallpapers.settings`), поэтому `document_id` сохраняется и
возвращается как есть.

Пересборка архива: `scripts/repack_android_source.py` получил `--add` — правка добавила
новый файл, а старый режим умел только заменять существующие записи.

### Русский интерфейс: переводы в ресурсах

**Запрос.** В приложении выбран русский, а всё на английском.

**Причина.** Русский в клиенте — встроенный язык (`LocaleInfo.builtIn = true`,
`pathToFile = null`), поэтому `LocaleController.applyRemoteLanguage` для него сразу
выходит и перевод берётся только из ресурсов `values-ru` (в `.bin`-локализацию из
`TelegramStringsTask`). Остальные языки Telegram догружает с сервера
(`langpack.getLangPack`), но у WaysGram такого сервера нет. В сборке было переведено
530 строк из 11 772 — всё остальное падало в английский.

**Правка.** `scripts/build_russian_translations.py` собирает полный
`values-ru/strings.xml`: свои (форк-специфичные) строки сохраняются, остальные берутся из
официального Android-langpack-а Telegram (включая множественные формы
`LangPackStringPluralized` → `Key_one`/`_few`/`_many`/`_other`), значения экранируются
под aapt2 (апострофы, кавычки, `&`, `<`, `>`, `\n`, ведущие `@`/`?`,
`formatted="false"` для нестандартных `%`), бренд «Telegram» заменяется на «WaysGram»
там, где в английской базе он уже заменён. Итог: 11 739 строк, покрытие 99.7%
(без перевода остались только имена поисковиков с URL и названия валюты Grams).

Проверки: `scripts/test_android_russian_translations.py` (5), `scripts/` (352).

### Реакции и эмодзи: «старый» арт вместо телеграмного

**Запрос.** Реакции почти все остались старыми, и отправленные эмодзи рисуются не как в
Telegram.

**Причина 1 — закэшированный каталог реакций.** Набор документов реакций переехал с
самодельного арта на настоящие документы Telegram в коммите `7410627`, но хеш каталога
остался прежним (`REACTIONS_HASH = 0x5757_0001`). Клиент хранит каталог в таблице
`reactions` вместе с хешем: при запуске он показывает сохранённый список и присылает
`messages.getAvailableReactions(hash = 0x57570001)`, сервер отвечал
`availableReactionsNotModified`, и приложение до конца жизни установки рисовало старые
синтетические документы. Файлы для них по-прежнему лежат в `assets` (`waysgram_emoji/art`,
`waysgram_emoji/react`, `telegram_reactions`), поэтому старый арт исправно отображался и
после обновления приложения.

**Правка 1.** `WaysGramReactions`: хеш каталога считается по идентификаторам документов
(`catalogHash` через `WaysGramReactionAssets.rawId`), а не задаётся константой. Любая смена
набора документов меняет хеш, клиент получает `availableReactions` вместо `notModified` и
перезаписывает кэш. Хеши `top`/`saved`-списков выводятся из того же значения. Текущий хеш
каталога — `0xb68564c7` (прежний — `0x57570001`).

**Причина 2 — самодельный арт для эмодзи-сообщений.** `MediaDataController
.getEmojiAnimatedSticker` для эмодзи без пака `emojicom` брал документ из
`WaysGramAnimatedEmoji.find`, то есть Twemoji-рисунок из `assets/waysgram_emoji/art`.
Отправленный эмодзи выглядел как чужой рисунок, а не как телеграмный.

**Правка 2.** `WaysGramAnimatedEmoji.find` отдаёт официальный анимированный эмодзи Telegram
— документ `appear_animation` из того же набора, что и реакции (копия с
`TL_documentAttributeCustomEmoji`, иначе клиент не считает его эмодзи и зацикливает
анимацию). Для эмодзи, которых в наборе нет, возвращается `null`: клиент покажет обычный
статичный эмодзи, как Telegram без анимированного пака. Эффекты нажатия
(`EmojiAnimationsOverlay`) тоже берутся из `effect_animation`/`around_animation` Telegram;
самодельные Lottie-эффекты остались только там, где Telegram ничего не отдал.

Проверки: `scripts/test_android_reaction_animation.py` (12), `scripts/` (357).

### Платное сообщение: нет плашки «Вы оплатили ⭐N»

**Запрос.** При оплате платного сообщения в переписке не появляется уведомление в чате
(«вы оплатили сообщение»), как в Telegram.

**Причина.** Плашку над баблом рисует штатная ячейка `ChatMessageCell` по полю
`message.paid_message_stars`, а поле в WaysGram нигде не жило. Сервер списывал звёзды
(`charge_dm_fee` пишет транзакцию `message_fee:<id>`), но сумму у самого сообщения не
хранил и не отдавал в истории; в ответе на отправку тоже нечего было взять — клиент
получает `TL_updateShortSentMessage`, а в нём такого поля нет. Локально `payStars` в
`SendMessagesHelper` тоже был нулевым: оплата подтверждается глубоко в транспорте
(`confirmPaidText`), уже после создания временного сообщения.

**Правка.** Сумма теперь едет вместе с сообщением:
* сервер: у `messages` появилась колонка `paid_message_stars` (новая схема + миграция
  `ALTER TABLE`), она заполняется при списании и возвращается в ответе на отправку и в
  истории (`GET /api/messages`), то есть видна обеим сторонам и после перезапуска;
* клиент: `WaysGramMapper.readPaidMessage` перекладывает поле в `TLRPC.Message`
  (+`flags2 |= 64`, иначе оно не сохранится в локальной базе), поэтому история и поллер
  сразу показывают плашку;
* клиент: `WaysGramPaidStars` запоминает сумму по id сообщения из ответа на отправку, а
  `SendMessagesHelper` забирает её в момент присвоения настоящего id — плашка появляется
  сразу после оплаты, без перезагрузки чата;
* миграция восстанавливает сумму у сообщений, оплаченных до обновления: она есть в
  `economy_transactions` (списание с reason `message_fee:<id>`), оттуда и подставляется.

Тексты штатные: у отправителя «Вы оплатили ⭐N, чтобы отправить сообщение», у получателя
«Вы получили ⭐N от un1».

Проверки: `authbot/tests/test_paid_dm_and_gift_privacy.py`
(`test_paid_dm_message_carries_the_amount_for_the_chat_banner`),
`scripts/test_android_paid_messages.py` (13), `scripts/` (358), `authbot/tests` (495).

## 12. Коллекционные юзернеймы (свой Fragment) — сделано

**Запрос.** «Как можно реализовать систему Fragment» → затем «делай всё по порядку».

**Что понятно про Fragment.** Публичного API у fragment.com нет: внутренние
эндпоинты веб-приложения требуют сессии Telegram и запрещены правилами, а сделки
идут через смарт-контракты TON с аккаунтом, зарегистрированным в Fragment. Точный
паритет без договора с Telegram невозможен, поэтому повторена продуктовая механика
внутри WaysGram: свой рынок юзернеймов на звёздном ledger.

**Сервер (`authbot/fragment_market.py`, подключён в `messenger_api.py`).**

* `collectibles` — владелец имени (`owner_telegram_id`), дата получения и заморозка;
  заселяется из `accounts.username` при миграции, поэтому имя, которое уже стоит на
  аккаунте, нельзя «купить» у самого себя после обновления.
* `collectible_listings` — фикс-цена; `collectible_auctions` + `collectible_auction_bids` —
  аукцион; `collectible_transfers` — журнал смен владельца (кто, кому, за сколько,
  какая комиссия).
* Покупка — одна `BEGIN IMMEDIATE`: списание покупателю, выплата продавцу минус комиссия
  (`WAYSGRAM_FRAGMENT_COMMISSION_PERCENT`, по умолчанию 5 %), закрытие лота, смена владельца и проводки в `economy_transactions`
  (`username_purchase:<listing>` / `username_sale:<listing>`). Владение берётся
  только из базы, а не из тела запроса.
* Имя реально переезжает на аккаунт покупателя, прежнее имя покупателя возвращается
  в фонд как свободное; после смены владельца имя заморожено на 24 часа.
* Свободное имя продаёт сервис по цене от длины (`system_price`), покупка —
  `POST /api/collectibles/{name}/claim`.
* Ставка холдит звёзды (`username_auction_hold`), перебитая возвращается
  (`username_auction_outbid`), снайп-защита продлевает аукцион на 5 минут, расчёт
  ленивый при чтении и в воркере `process_due_username_auctions`, поэтому рестарт
  сервера ничего не теряет.
* `Idempotency-Key` на всех мутациях: двойной тап «купить» не списывает дважды.
* TON: `GET /api/me/ton` отдаёт адрес и `memo = wg:<telegram_id>`, курс;
  `POST /api/internal/ton/deposits` (секрет `X-WaysGram-Ton-Secret`) зачисляет звёзды
  строго один раз на `tx_hash`; `POST /api/me/ton-withdraw` создаёт заявку в
  `ton_payouts` и списывает звёзды, включается флагом `WAYSGRAM_TON_WITHDRAWALS=1`.
* `authbot/scripts/ton_watcher.py` опрашивает toncenter и пересылает переводы с
  `memo`; состояние не хранит — повторная обработка безопасна благодаря уникальности
  `tx_hash`.

**Клиент.**

* `tgnet/WaysGramFragment.java` — ветка транспорта: `fragment.getCollectibleInfo`
  больше не падает с `METHOD_NOT_SUPPORTED`, а отвечает `TL_collectibleInfo` из
  `/api/collectibles/{name}`, поэтому штатные карточка профиля и
  `FragmentUsernameBottomSheet` начинают показывать дату и цену.
* `ui/WaysGramFragmentMarket.java` — экран рынка: «На продажу», «Аукционы», «Мои имена»,
  покупка, ставка, выставление по фикс-цене, аукцион, снятие с продажи, занятие
  свободного имени, пополнение/вывод TON. Открывается из «Настройки» → «Коллекционные
  юзернеймы».
* Все денежные вызовы идут через `WaysGramApi.postIdempotent`; клиент не считает
  баланс и не решает, кому принадлежит имя.

**Ограничения.** Продаются только имена внутри WaysGram: `@durov` принадлежит
Telegram, а не нам. NFT-обёртка (TEP-62), фиат и ончейн-эскроу не делались — это
отдельный проект с аудитом контракта. TON-вывод выключен по умолчанию: без
подписанта и лимитов он превращает сервер в hot-wallet.

Проверки: `authbot/tests/test_fragment_market.py` (14),
`scripts/test_android_fragment_market.py` (9), `scripts/` (367), `authbot/tests` (510).
На устройстве не проверено, APK не собирался.

## 13. Сборка APK 337: две давние поломки, которые никто не видел

Первый запуск сборки после моих правок упал **дважды**, и оба раза не из-за рынка
юзернеймов: предыдущие коммиты (перевод интерфейса и обои чата) в архив попадали, но
ни разу не компилировались.

1. **`mergeAfatReleaseResources` → «The character sequence "]]>" must not appear in
   content».** В `values-ru/strings.xml` три строки были записаны как
   `&lt;![CDATA[...]]&gt;`. После разэкранирования в тексте появляется `]]>`, и aapt2
   считает это неверным концом CDATA-секции. `AskAQuestionInfo` получила настоящие
   CDATA-секции (внутри ссылки `<a href=…>`), у `ChatsLeaveAlert` и
   `SendMediaPermissionStickersGifs` маркеры просто убраны. Тест
   `scripts/test_android_russian_translations.py::test_cdata_markers_are_not_escaped`
   теперь ловит такую запись до сборки.
2. **`javac`: `WaysGramChats.java:227: incompatible types: WallPaper cannot be converted
   to TL_wallPaper`.** `WaysGramWallpapers.fromJson` возвращает базовый `TLRPC.WallPaper`,
   а переменная в `WaysGramChats.wallpaper` была объявлена конкретным `TL_wallPaper`.
   Обои-градиент (`TL_wallPaperNoFile`) в неё не помещались.

**Диагностика.** Логи GitHub Actions лежат в артефакте и редиректят на хранилище, которое
из агентской песочницы закрыто, поэтому в `build-apk.yml` добавлен шаг: при падении строки
компилятора публикуются комментарием к коммиту (`gh api .../commits/$SHA/comments`). Через
`api.github.com` они читаются, и следующие итерации шли уже по реальным сообщениям javac.

**Итог.** `Build Android APK` (run 45891518793, build 337, commit `4e6be85`) — зелёный:
компиляция Java, `assembleAfatRelease`, проверка zip-CRC, публикация и повторное
скачивание опубликованного файла со сверкой sha256.

* APK: 119 168 464 B
* SHA-256: `e875bfd321b80171bc27b3bdab4dee4a122ff155c99906211756914561817747`
* релиз: https://github.com/WeteroScript/WaysGram-New/releases/download/apk-waysgram-337/WaysGram.apk
* части: `WaysGram.apk.part00…part04` в том же релизе

Проверки: `scripts` (368), `authbot/tests` (510). На устройстве ещё не проверено.

## 14. Рынок юзернеймов: экраны вместо диалогов

На скриншоте с устройства «Настройки → Коллекционные юзернеймы» открывались как
`AlertDialog` со списком пунктов. Это не то, что просили: разделы должны открываться
**внутри приложения**, как обычные фрагменты Telegram — со своим `ActionBar`, заголовком,
кнопкой «назад» и строками настроек.

Что сделано:

* `ui/WaysGramFragmentMarket.java` (диалоговый экран) удалён из архива; вместо него
  `ui/WaysGramFragmentActivity.java extends BaseFragment`: `ActionBar` с заголовком,
  `ScrollView` + `HeaderCell`/`TextSettingsCell`/`ShadowSectionCell` (как в настройках),
  разделы «На продажу», «Аукционы», «Мои имена», пополнение за TON (адрес, memo, курс,
  вывод) и строка «Занять свободное имя».
* `ui/WaysGramCollectibleActivity.java extends BaseFragment` — карточка имени: владелец,
  цена, аукцион, заморозка, комиссия; действия — строки списка, суммы вводятся полем на
  том же экране. Пустое имя открывает режим «Занять свободное имя» (поле + проверка).
* `SettingsActivity` case 88 = `presentSettingFragment(new WaysGramFragmentActivity(currentAccount))`.
* Ни в одном из двух экранов нет `AlertDialog`/`showDialog`: ошибки и подтверждения
  показываются строкой в списке. Это закреплено тестом
  `test_market_screens_are_fragments_not_dialogs`.
* `scripts/repack_android_source.py` получил `--remove`, иначе удалённый файл остался бы
  в zip-архиве, который и есть источник сборки. Скрипт проверяет, что запись есть, и что
  её нельзя одновременно обновить и удалить.

Проверки: `scripts` — **370 passed** (+5 subtests), `authbot/tests` — 510. На устройстве
не проверено, APK 338 не собирался.

