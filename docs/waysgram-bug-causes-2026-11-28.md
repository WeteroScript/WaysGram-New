# Разбор шести дефектов WaysGram: причины и точки в коде

Диагностическая часть ниже написана до правок; исправления и их проверка — в разделе
«Что исправлено» (коммит `3bf95d4`).
Все ссылки — `файл:строка` по состоянию ветки `arena/14d0f8a0-waysgram-new` (база `3398397`).
Android-код читался в мирроре `android-source/`; источником правды для сборки остаются
`WaysGram-Android.zip.part1…part5` (см. `docs/android-source-mirror.md`), поэтому правки придётся
вносить через `scripts/repack_android_source.py`.

## Что дало снятие обфускации

Сборка **46139239966** (`diagnostics_only`, коммит `3398397`) завершилась успешно; шаг 13
«Share obfuscated crash class mappings» выложил в комментарий к коммиту строки из
`TMessagesProj_App/build/outputs/mapping/afatRelease/mapping.txt`:

```text
org.telegram.ui.Components.UniversalAdapter -> org.telegram.ui.Components.g71:
org.telegram.ui.Components.BottomSheetWithRecyclerListView -> org.telegram.ui.Components.za:
```

То есть в трейсе из отчёта:

* `za.<init>(:89/:21)` — конструктор `BottomSheetWithRecyclerListView`;
* `vg.s1.x(:22)` — переопределённый `createAdapter()` конкретной шторки подарков;
* `org.telegram.ui.Components.g71.O/N/<init>` — внутренние (анонимные/вложенные) классы
  `UniversalAdapter`, переименованные R8 (в дампе шага 13 попали только два внешних класса,
  поэтому точные имена `$O`/`$N` не восстанавливаются — для механизма это не важно);
* `gh.a.run(:1748)` — вызов `Utilities.Callback2.run(...)`, то есть лямбда `fillItems`.

Этого хватило, чтобы найти конкретную строку, падающую в `NullPointerException`
(`ArrayList.isEmpty()` на `null`), — см. дефект 1.

## Сводка

| № | Симптом | Слой | Виновник | Уверенность |
|---|---|---|---|---|
| 1 | NPE в шторке подарков (R8-трейс) | клиент | `Gifts/GiftSheet.java:1166` + `:188` (поле ещё `null` при вызове из `super(...)`) | высокая, точная строка |
| 2 | Платная реакция исчезает через ~5 с | клиент (+сервер как триггер) | `StarsController.java:1769/1932/1987-1995/2018-2105`, откат `:1984/:2008/:2080`, `MessageObject.java:3952-3980`, `WaysGramMapper.java:645-660` | высокая по механизму, средняя по тому, какая из ветвей срабатывает у пользователя |
| 3 | «Разрешения» не сохраняются | клиент | `WaysGramChats.java:1229-1289`, `ChatUsersActivity.java:286/869/307-311`, `TLRPC.java:255-334` | высокая |
| 4 | «Этот пост был удалён из группы для обсуждения» | сервер | `messenger_api.py:20965-20966` (+ `:5843-5862`, `:21045-21072`) | воспроизведено тестом |
| 5 | Premium-эффект emoji повторяется/дёргается | клиент | `EmojiAnimationsOverlay.java:280-419` (`:413-419`), `:431-450`, `ChatActivity.java:33886-33901` | высокая по механизму |
| 6 | Карточка Fragment: backdrop «Black» рисуется маджентой | фронтенд (`static/fragment.html`) + слабая валидация на сервере | `fragment.html:536-559`, `:726-736`, `:1292-1298`, `:517-531`; `gift_upgrades.py:316-345/236-245` | средняя (нужен JSON конкретного подарка) |

---

## 1. NPE в шторке подарков (R8: `vg.s1.Y(:298)`)

**Симптом.** Падение `NullPointerException: Attempt to invoke virtual method 'boolean
java.util.ArrayList.isEmpty()' on a null object reference` при открытии шторки подарков.

**Цепочка вызовов (снизу вверх по трейсу, сверху вниз по исполнению).**

1. `GiftSheet.<init>` (`android-source/.../ui/Gifts/GiftSheet.java:211-213`) первым делом вызывает
   `super(context, null, false, false, false, null)` — вариант с `hasFixedSize == false`.
2. `BottomSheetWithRecyclerListView.<init>` в ветке `else` вызывает `resetAdapter(context)`
   (`Components/BottomSheetWithRecyclerListView.java:463`), а `resetAdapter` — абстрактный
   `createAdapter(recyclerListView)` (`:588-589`). То есть **переопределённый метод вызывается из
   конструктора суперкласса** — это и есть `za.<init>` → `vg.s1.x(:22)` в трейсе.
3. `GiftSheet.createAdapter` (`GiftSheet.java:1106-1110`) создаёт
   `new UniversalAdapter(..., this::fillItems, ...)`.
4. Конструктор `UniversalAdapter` заканчивается вызовом `update(false)`
   (`Components/UniversalAdapter.java:164`) → `updateInternal(false)` (`:303-318`), где сразу
   выполняется `fillItems.run(items, this)` — это кадр `gh.a.run(:1748)`.
5. Вызывается `GiftSheet.fillItems` (`GiftSheet.java:1112`), и на строке **1166**:

   ```java
   final boolean hasWaysGramMyGifts = waysGramMyGiftsLoading || !waysGramMyGifts.isEmpty();
   ```

   Поле объявлено как

   ```java
   private final ArrayList<TL_stars.SavedStarGift> waysGramMyGifts = new ArrayList<>(); // :188
   ```

**Почему `null`.** Инициализаторы полей подкласса выполняются **после** возврата из
`super(...)`. В момент, когда суперкласс через `createAdapter()` вызывает `fillItems`, поля
`waysGramMyGifts`, `premiumTiers`, `tabs`, `myGifts`, `topView`, `starsHeaderView` и т.д. ещё не
присвоены. Остальные обращения в `fillItems` защищены (`premiumTiers != null` — `:1121`,
`myGifts != null` — `:1155`, `tabs` — локальная переменная), а строка 1166 — единственная
без проверки, и как раз она добавлена фичей WaysGram («мои подарки» в шторке подарков).
Это ровно `java.util.ArrayList.isEmpty()` на `null`: тип поля — `ArrayList`.

**Почему это регрессия.** Поле и его использование — WaysGram-код (`waysGramMyGifts`,
`waysGramMyGiftsLoading`, `waysGramMyGiftsRequestStarted`), добавленный поверх штатного
`GiftSheet`. Падение детерминированное: `fillItems` доходит до 1166 при каждом открытии шторки.

**Затронутые/незатронутые шторки.** Тот же hazard (работа с состоянием до инициализации
подкласса) есть у всех наследников `BottomSheetWithRecyclerListView`:
`SendGiftSheet.java:944-945`, `AcquiredGiftsSheet.java:87-88` (там `fillItems` начинается с
`if (gifts == null) return;` — поэтому не падает), `StarGiftSheet.java:910-912` (собственный
`Adapter`, `UniversalAdapter` не используется). Фактически падает именно `GiftSheet`.

**Направление правки (без изменений в этой ветке).**
* минимально — `(waysGramMyGifts != null && !waysGramMyGifts.isEmpty())` в `:1166`;
* системно — не строить адаптер из конструктора суперкласса: `BottomSheetWithRecyclerListView`
  должен вызывать `createAdapter()`/`createDefaultAdapter()` после конструктора (например, из
  `onViewCreated`/`show`), либо наследники должны переживать вызов `fillItems` до собственной
  инициализации (guard-ы на все поля, читаемые в `fillItems`).

---

### 1а. Вторая ступень того же падения: `subtitleStarsView.animate()` на null

Первый NPE (`waysGramMyGifts.isEmpty()`) закрыл дорогу дальше, и после правки трейс сместился на
следующую строку того же конструкторского вызова:
`NullPointerException: Attempt to invoke virtual method 'android.view.ViewPropertyAnimator
android.view.View.animate()' on a null object reference` в кадре `vg.s1.Y`.

Источник — `fillItems` вызывает `setShowCollectiblesInfo(...)` (`GiftSheet.java:1229`), а тот анимирует
`subtitleStarsView`/`subtitleCollectiblesStarsView`, создаваемые в **теле** конструктора
(`:356`, `:368`), то есть позже `super(...)`. Худший случай совпадает: при вызове из конструктора
`self == false`, `dialogId == 0`, `selectedTab` уже равен индексу вкладки «Коллекционные», поэтому
`show == true`, ранний выход `if (show == shownCollectiblesInfo)` не срабатывает и выполняется
`subtitleStarsView.animate()` на `null`.

Правка: `setShowCollectiblesInfo` выходит, пока вьюхи не созданы, и **не** запоминает значение —
конец конструктора вызывает `adapter.update(false)`, и метод отрабатывает второй раз уже с вьюхами:

```java
if (subtitleStarsView == null || subtitleCollectiblesStarsView == null) {
    return;
}
```

Заодно список `waysGramMyGifts` в `fillItems` заменён на локальный `waysGramGifts`
(`waysGramMyGifts != null ? waysGramMyGifts : new ArrayList<>()`), поэтому цикл по нему тоже
не может упасть до инициализации поля.

---

## 2. Платная реакция исчезает примерно через 5 секунд

**Симптом.** После отправки платной реакции чип со звёздами виден, затем пропадает примерно
через 5 секунд.

**Ключевая константа.** `StarsController.REACTIONS_TIMEOUT = 5_000` (`ui/Stars/StarsController.java:1769`)
— это ровно наблюдаемая задержка. Схема жизненного цикла:

1. Тап по ⭐ в шторке → `StarsReactionsSheet:551-559` →
   `sendPaidReaction(msg, act, total, false, true, peer)`; `PendingPaidReactions.add(..., affect=false)`
   только накапливает `not_added`, оптимистичного добавления нет.
2. `animate3dIcon` (`StarsReactionsSheet:832-1010`) вызывает `pending.apply()` при `t > 0.95`
   (≈800 мс) и повторно в `onAnimationEnd` (под флагом `applied`). `apply()`
   (`StarsController.java:1953-1976`) делает `messageObject.addPaidReactions(+not_added, true, peer)`
   и рассылает `didUpdateReactions` — «мои звёзды» рисуются локально.
3. `apply()` также запускает таймер: `AndroidUtilities.runOnUIThread(closeRunnable, REACTIONS_TIMEOUT)`
   (`:1932`, повторно `:1967`).
4. Через 5 с `close()` (`:1987-2000`) вызывает `commit()` (`:2018-2105`), который отправляет
   `TL_messages_sendPaidReaction`. **Важно:** `currentPendingReactions = null` выставляется в
   `close()` (`:1993-1995`) до ответа сервера.
5. `WaysGramMessages.sendPaidReaction` (`tgnet/WaysGramMessages.java:246-300`) переводит это в
   `POST /api/channels/{id}/messages/{mid}/paid-reactions`, затем делает
   `GET .../messages?message_ids={mid}` и собирает `TL_updateMessageReactions` из **этого**
   ответа (`WaysGramMapper.readReactions`).

**Почему чип пропадает.** Есть две ветви, обе приводят к откату ровно на 5-й секунде:

* **Ошибка коммита.** Любая ошибка (`PAID_REACTIONS_UNAVAILABLE` при
  `!(req.peer instanceof TL_inputPeerChannel) || isGroupChannel(peer.channel_id)`,
  `PAID_REACTION_INVALID`, 403 от сервера при `owner == sender` или выключенных платных
  реакциях, `BALANCE_TOO_LOW`) попадает в `commit()`-обработчик и выполняет
  `messageObject.addPaidReactions(-amount, wasChosen, ...)` (`StarsController.java:2080-2086`,
  для баланса — `:2026-2032`). Оптимистичные `+N` отменяются, чип обнуляется/исчезает.
* **Пустой/неполный ответ GET.** Если `messages` в ответе пуст, `WaysGramMessages:283-296`
  оставляет `new TLRPC.TL_messageReactions()` — то есть «успешный» апдейт с **нулевым** набором
  реакций. `MessagesController:20729-20736` подмешивает pending только если
  `getPendingPaidReactions(...) != 0`, а он уже `0` (`:1993-1995`, `:2150-2154`), после чего
  `ChatActivity:23559` → `MessageObject.updateReactions` **заменяет `message.reactions` целиком**
  (`messenger/MessageObject.java:3952-3980`) на пустой набор.

**Дополнительный дефект (даже при успешном ответе).** `WaysGramMapper.addPaidReaction`
(`tgnet/WaysGramMapper.java:645-660`) кладёт в `results` только `TL_reactionCount` с
`reaction = TL_reactionPaid` и `count = paid_stars`, `chosen = my_paid_stars > 0`. Он **не
создаёт `MessageReactor`** в `top_reactors`. В результате:

* `MessageObject.getMyPaidReactionPeer()` (`:12313+`) после апдейта возвращает `null` — состояние
  «я платил» в интерфейсе теряется (чип остаётся, но «мой» статус и аватар реактора — нет);
* `ReactionsLayoutInBubble.equalsTLReaction` (`Components/Reactions/ReactionsLayoutInBubble.java:155-163`)
  знает только `TL_reactionEmoji`/`TL_reactionCustomEmoji` и для `TL_reactionPaid` всегда
  возвращает `false`, поэтому `updateReactions` не переносит `chosen`/`lastDrawnPosition` со
  старого объекта — чип пересоздаётся и «прыгает».

**Проверка сервера.** Скриптом `docs/diagnostics/2026-11-28/probe_bug2.py` (создание канала, включение платных
реакций, `POST .../paid-reactions` с `Idempotency-Key`) получено: `POST 200
{stars:5, balance_after:995, total_stars:5}`, а `GET ...?message_ids=` и список истории содержат
`paid_stars`/`my_paid_stars`. 403 `PAID_REACTIONS_UNAVAILABLE` — когда платит владелец канала или
`paid_reactions_enabled = 0`. То есть данные сервера в порядке: пропажа — клиентская реакция на
ошибку или на пустой ответ.

**Дополнение.** `ChatActivity:24960` на `starBalanceUpdated` обновляет только верхнюю панель,
оверлей и «закреплённый» блок, но не инвалидирует ячейку сообщения, поэтому после отката
перерисовка может приходить с задержкой/не приходить вовсе.

**Направление правки.**
1. Не обнулять pending до подтверждения: `currentPendingReactions` должен жить до ответа сервера
   (или до `MessageObject.updateReactions` с непустым `paid_stars`).
2. В `WaysGramMessages.sendPaidReaction` пустой `messages`/отсутствие нужного id считать ошибкой
   (`502 REACTION_RESPONSE_INVALID`), а не отдавать пустые реакции.
3. В `WaysGramMapper.addPaidReaction` дополнительно заполнять `top_reactors` (`MessageReactor`
   c `my = true`, `peer_id`, `count`/`stars`), чтобы `getMyPaidReactionPeer()` продолжал работать.
4. Научить `equalsTLReaction` сравнивать `TL_reactionPaid` (`reaction1 instanceof TL_reactionPaid`).
5. По `starBalanceUpdated` инвалидировать видимые ячейки с платными реакциями.

**Что осталось неподтверждённым.** Какая именно ветвь (ошибка коммита или пустой GET) сработала
у пользователя — зависит от состояния канала (владелец ли он, включены ли платные реакции,
не попадает ли сообщение под фильтр видимости в `messages?message_ids=`). Различить можно по
логу `WaysGramTransport`/`WaysGramApi` на устройстве: `403/400` — ветвь ошибки, `200` с пустым
`messages` — ветвь пустого набора.

---

## 3. Экран «Разрешения» не сохраняет часть настроек

**Симптом.** В «Права участников»/«Разрешения» группы часть переключателей после сохранения и
повторного открытия экрана возвращается в прежнее состояние.

**Механизм.** Маска сервера (`chat_groups.default_send_restrictions`) ↔ флаги `TL_chatBannedRights`
переводятся в обе стороны, но бит 2 (фото) имеет **два имени** на клиенте: `send_media` и
`send_photos`.

* Чтение: `WaysGramChats.bannedRightsFor` (`tgnet/WaysGramChats.java:1229-1262`)
  `rights.send_media = (mask & 2) != 0;` **и** `rights.send_photos = (mask & 2) != 0;`.
  После `normalized()` (`:230-232` → `TLRPC.TL_chatBannedRights.clone`, `tgnet/TLRPC.java:242-248`,
  это serialize→deserialize) восстанавливаются только биты 0 и 1 (`:1259-1261`).
* Запись: `WaysGramChats.sendRestrictionMask` (`:1265-1289`)
  `if (rights.send_media || rights.send_photos) mask |= 2;` — то есть бит 2 включается от
  **любого** из двух флагов.
* UI: `ChatUsersActivity` копирует все флаги из `currentChat.default_banned_rights`
  (`:281-306`), чекбокс «Фото» меняет **только** `send_photos` (`:869`); `send_media` меняет
  лишь мастер-переключатель «Медиа» `setSendMediaEnabled` (`:3882-3900`).

Итог: если в маске был бит 2, то после чтения `send_media == true`. Администратор снимает запрет
«Фото» → `send_photos = false`, но `send_media` остаётся `true` → `sendRestrictionMask` снова
ставит бит 2 → на сервер уходит та же маска. После перезапуска/переоткрытия запрет «Фото» снова
включён. Обратный эффект у мастера «Медиа»: он выключает заодно `embed_links`, `send_polls`,
`send_reactions` (`:3883-3898`), которые в «медиа» не входят.

**Второй источник расхождений — проводная нормализация TL.** `TL_chatBannedRights.readParams`
(`tgnet/TLRPC.java:255-283`) при `send_media == true` разворачивает его в шесть флагов
(`send_photos`, `send_videos`, `send_roundvideos`, `send_audios`, `send_voices`, `send_docs`), а
`serializeToStream` (`:285-334`) сворачивает обратно:
`send_media = все шесть медиа-флагов`, `send_messages = send_plain && send_media && send_stickers`.
Любой кругооборот через `clone()` (а `normalized()` используется и в `bannedRightsFor`) теряет
частичные наборы: «запрещены только фото» после сериализации выглядит как «медиа разрешены,
текст запрещён». Отсюда и обходной костыль в `ChatUsersActivity:307-311`, который обнуляет все
шесть медиа-флагов, если агрегат `send_media == false`, а все шесть включены.

**Сервер.** `PUT /api/groups/{id}/send-permissions` (`authbot/messenger_api.py:19949-19975`)
проверяет только диапазон (`0 <= mask <= MAX_SEND_RESTRICTIONS`) и пишет маску **как есть**;
`send_restrictions` (`:5696-5709`) лишь дополняет маски версии 0. Значит сохранение маски на
сервере корректно, дефект целиком на клиенте.

**Направление правки.** Сделать `send_media`/`send_messages` строго производными и не
участвующими в обратном преобразовании: в `bannedRightsFor` не выставлять `send_media`, в
`sendRestrictionMask` не учитывать `send_media`, в `ChatUsersActivity` не копировать агрегат в
рабочее состояние (или явно пересчитывать его после каждого изменения). Мастер «Медиа» должен
менять только шесть медиа-флагов.

---

## 4. «Этот пост был удалён из группы для обсуждения» (воспроизведено)

**Воспроизведение** (`docs/diagnostics/2026-11-28/probe_bug4b.py`): группа + `PUT /api/groups/{gid}/settings
{"pre_history_hidden": true}` → `GET /api/channels/{cid}/discussion?...` возвращает
`root_message_id = 1` → `GET /api/groups/{gid}/messages?message_ids=1` → `{"messages": [],
"next_after_id": 0}`, при этом `GET /api/groups/{gid}/threads/1/replies?include_root=true`
возвращает `ids: [1]`.

**Механизм.**

1. Авто-членство в группе обсуждений ставит `joined_at = now`:
   `join_discussion_group_for_channel` (`authbot/messenger_api.py:21045-21072`, `INSERT ...
   VALUES(?,?,'member',int(time.time()))`, `:21066-21070`).
2. `group_history_floor` (`:5843-5862`) для `role = 'member'` при `pre_history_hidden = 1`
   возвращает `MAX(id)` сообщений с `created_at < joined_at` — то есть все сообщения, созданные
   до входа в группу (включая корневой пост обсуждения, который создан раньше).
3. `list_group_messages` (`:20890-20976`) применяет floor **даже в ветке явных id**:
   `... AND id>?` с `history_floor` в параметрах (`:20965-20966`), хотя фильтр, скрывающий
   корневые посты, для явных id уже отключён (`:20941-20944`). Поэтому запрос по `message_ids=1`
   возвращает пусто, а `/threads/{root}/replies` (другой запрос) — возвращает.
4. Клиент: `processLoadedDiscussionMessage` (`ui/ChatActivity.java:36513` и далее) при пустой
   истории сбрасывает `history`/`discussionMessage`, и на `:36622` показывает
   `R.string.ChannelPostDeleted` — «Этот пост был удалён из группы для обсуждения».

**Почему только у части пользователей.** Нужны одновременно `pre_history_hidden = 1`, роль
`member` и корневой пост, созданный до `joined_at` — то есть обычный подписчик, впервые
открывающий комментарии к старому посту.

**Направление правки (предпочтительное).** В explicit-ids ветке не применять floor к строкам,
у которых есть запись в `channel_post_discussions` (корень обсуждения — это «ссылка», а не
история группы): добавить в условие `AND (id > ? OR EXISTS (... d.root_message_id =
group_messages.id))` либо вычислять floor как `min(history_floor, root_id − 1)` для таких id.
Альтернатива — не стампить `joined_at = now` при авто-джойне в группу обсуждений (брать
`created_at` группы/канала), но это меняет семантику «до вступления» для всей группы, поэтому
точечное исключение корней предпочтительнее.

---

## 5. Premium-эффект emoji повторяется и «дёргается»

**Симптом.** Анимация премиум-эффекта emoji проигрывается повторно/с рывками (в том числе после
завершения), вместо одного проигрывания.

**Механизм — самоперезапуск в `draw()`.** `EmojiAnimationsOverlay.draw(Canvas)`
(`ui/EmojiAnimationsOverlay.java:280`) для каждого объекта в `drawingObjects`:

```java
} else if (drawingObject.imageReceiver.getLottieAnimation() != null && !....isRunning()) {
    drawingObject.imageReceiver.getLottieAnimation().setCurrentFrame(0, true);   // :416
    drawingObject.imageReceiver.getLottieAnimation().start();                    // :417
}
```

(`drawingObject.wasPlayed = true` — `:414`.) То есть как только lottie доиграл и остановился, но
объект ещё не удалён из списка, кадр сбрасывается в 0 и анимация стартует снова. Удаление
происходит только когда объект помечен `removing` (`:333-347`), а `removing` ставится по условиям
позиции/скролла и по `isPremiumSticker`-границам — то есть не по факту завершения. В результате
`setAutoRepeat(0)` (`:541`, `:785`) обходится ручным перезапуском, и эффект крутится по кругу,
а на границах списка даёт рывки (объект то помечается `removing`, то нет).

**Второй источник — несколько объектов на одно сообщение.** `createDrawingObject`
(`:628-...`) считает совпадения по `messageId` (`:634-643`), но прерывает создание только когда
у существующего объекта нет lottie либо он ещё генерирует кэш; для premium-стикера при
`sendTap = false` второй объект допускается. Плюс отдельный путь `showAnimationForCell`
(`:495-515`) возвращает `false` лишь при тех же условиях. Поэтому повторный триггер (тап по
сообщению, повторный показ ячейки) может дать два наложенных эффекта — визуально «джиттер».

**Третий источник — рассинхрон с флагом «уже проигран».** Гейт в `ChatActivity.java:16373`:
`... && !messageObject.messageOwner.premiumEffectWasPlayed && emojiAnimationsOverlay.isIdle() ...`,
где `isIdle()` — это `drawingObjects.isEmpty()` (`EmojiAnimationsOverlay.java:944-945`). Флаг
`premiumEffectWasPlayed = true` ставится в `onTapItem` (`:447-450`), но `ChatActivity.restartSticker`
(`ui/ChatActivity.java:33886-33901`) перезапускает **только** lottie стикера
(`drawable.restart()`), не трогая ни флаг, ни оверлей: стикер начинает заново, оверлей считает
эффект проигранным (или наоборот), отсюда рассинхрон и повторный проигрыш.

**Направление правки.** Удалять объект из `drawingObjects` по завершении анимации (по
`onAnimationEnd`/`framesCount - 2`, как это сделано для других типов), убрать ветку
`setCurrentFrame(0)/start()` из `draw()`, дедуплицировать объекты строго по `messageId` для всех
типов (включая premium-стикеры и реакции), а в `restartSticker` согласованно сбрасывать
`premiumEffectWasPlayed`/скрывать оверлей для этого сообщения.

---

## 6. Карточка подарка в Fragment: backdrop «Black» рисуется маджентой

**Симптом.** У Plush Pepe #317 указан backdrop «Black», модель Gucci Leap, символ Diamonds, а
карточка залита маджентой.

**Что проверено на сервере.**

* Трейты роллятся из живых строк Telegram: `gift_upgrades.roll_traits` (`:348-404`) →
  `_live_slot` (`:336-345`) → `_live_traits` (`:316-334`), где для backdrop копируются
  `center_color`, `edge_color`, `pattern_color`, `text_color` как `int(row.get(key) or 0)`
  **без проверки, что четыре цвета действительно принадлежат записи с этим именем**, и без
  защиты от нулей; `_backdrop_traits` (`:236-245`) при отсутствии полей подставляет `0`.
* При чтении API пытается это исправить: `messenger_api._enrich_collectible_backdrop_colors`
  (`:572-621`) находит backdrop по имени в официальном пуле подарка (`pool_for_gift`) и
  перезаписывает цвета; вызывается из `gift_inventory` (`:7061`) и из листингов маркета (`:9890`).
  Проба (`docs/diagnostics/2026-11-28/probe_bug6.py`, seed `user_gifts.attributes_json` с «Black» и цветами
  маженты) подтвердила: `GET /api/me/gifts` вернул именно палитру Black
  (`0x363738 / 0x0E0F0F / 0x6C6868 / 0x8C8F91`), то есть на этом пути сервер цвета исправляет.
  Значит «маджента» попадает на экран, если `pool_for_gift` не нашёл пул/имя (старый
  `telegram_gift_id`, отсутствующий пул) — тогда в ответ уходят чужие или нулевые цвета.

**Что происходит во фронтенде (главная причина того, что видно на скриншоте).** Детальная
карточка собирается в `authbot/static/fragment.html:1292-1298`: `.big-art` с CSS-фоном
`colorFor(item)` и **двумя** независимыми слоями картинки внутри:

* `colorFor` (`:517-531`) строит `radial-gradient(circle at 50% 40%, center_color 0%, edge_color 100%)`;
  при `.big-art { height: 200px }` (`:196-201`) и ширине ~350 px весь видимый прямоугольник
  попадает внутрь «центрального» стопа — карточка почти однотонная, направление градиента
  произвольное (не как в Fragment);
* поверх градиента — `<img>` (`:201`, `z-index: 1`) из `artwork()` (`:536-559`), который тянет
  `/api/telegram/gifts/{id}/sticker` и **безусловно** ставит `img.hidden = false` (`:542`, `:555`);
  параллельно `requestModelArtwork` → `renderGiftModel` (`:726-736`) по завершении загрузки модели
  делает `img.hidden = true`. Кто ответил позже, тот и определяет вид: если «стикер» пришёл
  после модели, поверх градиента остаётся непрозрачная каталожная картинка подарка с запечённым
  в неё цветом (у Plush Pepe — маджентовым), а `.model-art` с `z-index: 2` (`:166-173`) рисуется
  всего лишь «поверх» неё. Backdrop «Black» при этом не виден вообще;
* `.pattern-texture` (`:159-167`, `:815-836`) тинтуется `pattern_color` того же (возможно,
  неверного) backdrop — узор тоже уезжает по цвету.

Пиксельная выборка из присланного скриншота (1080×2400): углы `#311a2c` / `#802a59`,
середина слева `#792153`, узор `#711e4c` — насыщенная маджента, тогда как палитра Black —
`#363738`/`#0e0f0f`. Ни один из цветов `COLORS` (`:407`) на это не похож, то есть это не
fallback-палитра `colorFor`, а именно непрозрачная картинка поверх градиента.

**Направление правки.**
1. Для коллекционных подарков (есть `collectible_traits`) не показывать каталожный `sticker`:
   либо не вызывать `artwork()`, либо в `artwork()` уважать уже показанную модель
   (`if (!img.hidden) return;` и не включать `img`, если `model-art` активен) — сейчас это гонка
   двух `fetch`.
2. Валидировать цвета на сервере: `_live_traits` должен отбрасывать/чинить набор, не совпадающий
   с палитрой записи с тем же именем, и не отдавать нули (`_backdrop_traits`).
3. Сделать градиент пропорциональным (например `radial-gradient(ellipse at 50% 30%, ...)` или
   линейный сверху вниз), чтобы центр-стоп не занимал всю карточку.
4. Добавить регрессионный тест на трейты/палитру: сейчас `grep collectible_traits
   authbot/tests/test_messenger_api.py` не находит ничего, покрытие есть только в
   `test_star_gifts.py:690-711` (enrich) — нужен тест «roll_traits → GET карточки → цвета
   совпадают с пулом, а фронтенд получает непрозрачную подложку только при отсутствии модели».

**Что осталось неподтверждённым.** Точный JSON `attributes.collectible_traits` конкретного
подарка (#317) и первый кадр ассета
`/api/telegram/gifts/{telegram_gift_id}/attributes/{document_id}`: если в JSON цвета уже
палитры Black, а на экране маджента — виновата гонка слоёв (пункт 1); если цвета в JSON чужие —
виноват `_enrich_collectible_backdrop_colors`, не нашедший пул (пункт 2).

---

## Что исправлено (коммит `3bf95d4`)

| № | Правка | Точки |
|---|---|---|
| 1 | `fillItems` не трогает `waysGramMyGifts`, пока список не загружен (адаптер создаётся внутри `BottomSheetWithRecyclerListView.<init>`) | `GiftSheet.java:1164-1172` |
| 2 | владельцу канала разрешена платная реакция на свой пост; клиент больше не откатывает оптимистичную звезду на пустом ответе | `messenger_api.py:12285-12290`, `WaysGramMessages.java` (`sendPaidReaction`: `REACTION_RESPONSE_INVALID` вместо NPE), `ReactionsLayoutInBubble.equalsTLReaction` (paid == paid) |
| 3 | маска прав: `1=текст`, `2=фото`, `4=видео`, `8=аудио`, `16=стикеры/gif/игры/inline`, `32=документы`, `64=голосовые`, `128=кружки`; `send_media` — производное «все шесть медиа запрещены», а не алиас одного бита | `WaysGramChats.java` (`bannedRightsFor`/`sendRestrictionMask`), `ChatUsersActivity.java` (`updateSendMediaAggregate`, синхронизация `send_messages`/`send_plain`), `ChatObject.java` (`ACTION_SEND_MEDIA`) |
| 4 | явно запрошенный `message_ids` больше не режется `group_history_floor`, если это корень `channel_post_discussions` | `messenger_api.py:20946-20960` |
| 5 | однократный emoji-эффект не перезапускается сам: вместо `setCurrentFrame(0,true)+start()` объект уходит в `removing`; второй эффект не запускается поверх играющего (`isIdle()` + `checkPosition()` перед `consumeEmojiEffect`, иначе каждый проход отрисовки добавлял новый объект) | `EmojiAnimationsOverlay.java:412-430`, `ChatActivity.java:16376-16389` |
| 6 | палитра backdrop выравнивается по пулу при ролле, а каталог-стикер не закрашивает уже показанную 3D-модель; градиент карточки — эллипс, а не круг | `gift_upgrades.py:348-372` (+ вызов в `roll_traits`), `fragment.html` (`reveal`/`fallback`/`artwork`, `colorFor`) |

Тесты, закрепляющие правки (все зелёные):

* `tests/test_star_gifts.py::test_rolled_backdrop_colours_follow_the_pool_palette`
* `tests/test_message_changes.py` — расширенный набор «Разрешений»
* `tests/test_channel_discussions.py` — корень обсуждения в `message_ids`
* `tests/test_fragment_webapp.py`, `tests/test_fragment_deposit.py`
* `scripts/test_android_waysgram_client_regressions.py` — читает **собранные** `WaysGram-Android.zip.part*`,
  проверяет NPE-гард, таблицу битов и round-trip 15 масок, синхронизацию `send_media`,
  пустую страницу платной реакции, идентичность paid-чипа и отсутствие `setCurrentFrame(0,true)` в `draw()`.
  Запускается в CI шагом «Run Android client regression checks» (`authbot.yml`).

Android-правки лежат и в мирроре `android-source/`, и в архиве: после каждой правки нужен
`python3 scripts/repack_android_source.py <пути…>`, иначе сборка возьмёт старое.

---

## Приложение: чем проверялось

| Проверка | Результат |
|---|---|
| `gh api .../actions/runs/46139239966` | `completed / success`, шаг 13 выложил mapping (см. выше) |
| `docs/diagnostics/2026-11-28/probe_bug4b.py` | дефект 4 воспроизведён: `messages?message_ids=1` пусто, `threads/1/replies` содержит `1` |
| `docs/diagnostics/2026-11-28/probe_bug2.py` | сервер платных реакций исправен: POST 200, `paid_stars`/`my_paid_stars` в GET; 403 при `owner == sender` |
| `docs/diagnostics/2026-11-28/probe_bug6.py` | API перезаписывает чужие цвета палитрой именованного backdrop |
| `pytest` (paid_reaction/discussion/permission наборы) | 5 тестов проходят — существующее покрытие дефекты 2–4 не ловит |
| PIL по скриншоту карточки | углы `#311a2c`/`#802a59`, узор `#711e4c` — не палитра Black |
| `python -m pytest docs/diagnostics/2026-11-28/probe_*.py -q` | три пробы проходят (они печатают ответы API, а не проверяют ожидания); см. `docs/diagnostics/2026-11-28/README.md` |

| `pytest` после правок | `test_star_gifts.py`, `test_message_changes.py`, `test_channel_discussions.py`, `test_fragment_webapp.py`, `test_fragment_deposit.py`, `test_channel_multi_session_rights.py` — зелёные |
| `pytest -q -k backdrop` | 2 passed (палитра backdrop) |
| `python -m pytest scripts/test_android_waysgram_client_regressions.py -q` | 7 passed |
| `gh workflow run build-apk.yml -f diagnostics_only=true` | сборка APK из ветки — проверка компиляции Java-правок |

### Известные красные тесты (были красными до правок)

В базе красные три теста, все — один корень: в репозитории сознательно принято, что участник группы
**может** менять информацию, пока владелец не включит «Изменять информацию» в «Разрешениях»
(`chat_groups.default_change_info_restricted` по умолчанию `0`; это прямо закреплено
`tests/test_message_changes.py::test_group_default_change_info_is_persisted_and_applied`).
Старые тесты ждут обратного — телеграмного дефолта «участникам запрещено»:

| Тест | Что ждёт | Что получает |
|---|---|---|
| `test_chat_rights_masking.py::test_member_without_appointment_has_no_group_rights` | `PUT /api/groups/{id}` → 403 | 200 (`group_can_change_info` пускает участника при флаге `0`) |
| `test_chat_location.py::test_group_location_requires_change_info_right` | `PUT /api/groups/{id}/location` → 403 | 200 (то же) |
| `test_messenger_api.py::test_channel_and_group_avatar_can_be_set_only_by_managers` | `PUT /api/groups/{id}/avatar` → 403 | 404 `Изображение не найдено` — участник прошёл проверку прав и упал на владельце картинки |

Проверено прогоном базового коммита `7a26f0d` без наших изменений: те же три падения. Дефекты 1–6
их не касаются. Развязка — либо перевести тесты на новый контракт (владелец запрещает флагом), либо
сделать `default_change_info_restricted` по умолчанию `1`, как в Telegram, и переписать
`test_group_default_change_info_is_persisted_and_applied`. Это продуктовое решение, поэтому код не тронут.
