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

Жалобы с устройства: надпись «дёргается», затем «посередине сердца»; «часть руки поверх
сердца».

- порядок слоёв как в исходном стикере: контур → тело → правая кисть (слой 15) →
  сердце → левые кисти (1, 2) → надпись. Раньше все три кисти рисовались поверх сердца,
  из-за чего на нём висел оторванный кусок правой руки;
- надпись стоит ровно в середине сердца (0.50 по высоте, центр по горизонтали) и идёт
  последним слоем: под кистями в середине сердца она закрывается на 64…100 % и «мигает»
  при анимации (см. `docs/assets/waysgram-wordmark-middle.png`);
- крепление жёсткое: размер один раз, положение и наклон из трансформа сердечного слоя,
  локальная точка постоянна — надпись не болтается.

Модель пересобрана: 570 420 байт,
sha256 `f2fb50da27c96bdf87184d0e236df0d756d614b603a3f1be1a267b43043cb0ab`,
копия в APK (`res/raw/waysgram_model.webm`) обновлена. Тест
`test_waysgram_model_animation.py` закрепляет порядок слоёв, середину сердца и
отсутствие покадрового измерения.
