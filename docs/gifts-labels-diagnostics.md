# Диагностика: подарки, метки, админ-панель

Разбор по жалобам со скриншотов (Buy a Gift · Algorithm Cup #9 · Algorithm Cup #6).
Для каждого пункта: симптом → корневая причина (файл:строка) → что правим → уверенность.

Файлы WaysGram: `org/telegram/messenger/waysgram/` внутри архива `WaysGram-Android.zip.part*`
Правки нативных классов Telegram: там же, в архиве (накладываются при правке частей, без патч-скрипта)
Сервер: `authbot/messenger_api.py`, `authbot/gift_upgrades.py`, `authbot/bot.py`

---

## A. Подарки

### 1. «Мои подарки»: при >18 подарков список не листается

**Причина.** Список — нативный `ProfileGiftsContainer` (RecyclerView, пагинация Telegram для
WaysGram отключена), поэтому сама прокрутка не сломана. Ломает её волна перерисовок:

* `WaysGramProfileGiftsController.refresh()` на каждое открытие вкладки делает
  `WaysGramGiftMediaStore.clearStillImages()` (строка 81) — все картинки пропадают;
* `downloadGiftStickers()` на **каждый** пришедший стикер вызывает
  `container.refreshWaysGramGiftViews()` (строка 592) **без дебаунса**;
* `refreshWaysGramGiftViews()` (патч, `ProfileGiftsContainer`) делает `page.update(false)`,
  то есть полный `notifyDataSetChanged()` **всех** ячеек;
* плюс `downloadGiftAnimations()` → `scheduleGiftViewRefresh()` (350 мс) — ещё одна волна
  полных перерисовок.

При ≤18 подарках все картинки уже в кеше, волны нет. При большом инвентаре список
перестраивается десятки раз в секунду: жесты прокрутки теряются, анимации стартуют заново.

**Правка.** Убрать `refreshWaysGramGiftViews()` из колбэка стикера; добавить в
`ProfileGiftsContainer` точечные методы `updateWaysGramGiftArtwork(serial)` и
`updateWaysGramGift(serial, SavedStarGift)` — они находят ячейку в `listView` и обновляют
только её (`notifyItemChanged`, без `page.update`). `clearStillImages()` заменить на
`retain(Set<String> serials)` — чистить только стиллы подарков, которых больше нет в инвентаре.

**Уверенность:** высокая (следствие проверяется по коду, симптом совпадает с «чем больше
подарков, тем хуже»).

### 2. Анимация подарка в профиле лагает и быстро моргает

**Причина.** Та же волна полных перерисовок: `page.update(false)` пересоздаёт ячейки, а
`WaysGramGiftArtwork.apply()` при пересоздании заново ставит документ и вызывает
`startAnimation(true)` — анимация стартует с нуля (визуально «моргает» по 1–2 кадра).
Дополнительно `clearStillImages()` сбрасывает кадр-заглушку, пока документ ещё не пришёл.

**Правка.** Пункт 1 (нет полных перерисовок) + в `WaysGramGiftArtwork.apply()` сохранять
позицию проигрывания (`ImageReceiver.getCurrentAnimationProgress()` → `setAnimationProgress`)
при повторной привязке того же документа. `clearStillImages` → `retain(...)`.

**Уверенность:** высокая.

### 3. При улучшении меняются подпись/цвета, но сам подарок остаётся стоковым

**Причина.** `WaysGramProfileGiftsController.toNativeGifts()` (строки 235–249):

```java
TLRPC.TL_document modelDocument = WaysGramGiftAttributes.traitDocument(...);
TLRPC.TL_document patternDocument = WaysGramGiftAttributes.traitDocument(...);
if (modelDocument == null || patternDocument == null) {
    TLRPC.TL_document fallbackDocument = traitDocument(serial, telegramGiftIdText, giftEmoji);
    if (modelDocument == null)  modelDocument  = fallbackDocument;   // стикер самого подарка
    if (patternDocument == null) patternDocument = fallbackDocument;  // в слот «узор»
}
```

`traitDocument()` возвращает документ **только если он уже скачан**; в момент сборки карточки
его ещё нет → в атрибуты `model`/`pattern` нативного `TL_starGiftUnique` попадает **стикер
самого подарка** (стоковая картинка). Когда настоящий документ докачивается,
`refreshWaysGramGiftViews()` перерисовывает только `imageView` ячейки и **не пересобирает
атрибуты** — подмена остаётся навсегда (до повторного открытия вкладки, и там цикл
повторяется, если загрузка ещё идёт).

**Правка.** (а) не подставлять стикер подарка в `model`/`pattern` — при отсутствии документа
добавлять атрибут с `document = null` (Telegram рисует пусто/эмодзи) либо не добавлять вовсе;
(б) при приходе документа (слушатель `WaysGramGiftAttributes`) пересобирать
`TL_savedStarGift` **только этого подарка** и заменять элемент в `list.gifts` через
`updateWaysGramGift(...)` (см. п. 1).

**Уверенность:** высокая (код читается однозначно).

### 4. Подарок без модели в Telegram нельзя улучшить

**Причина.** Сервер: `POST /api/gifts/{serial}/upgrade`, `messenger_api.py:6218`

```python
if not str(gift["telegram_gift_id"] or ""):
    raise HTTPException(status_code=409, detail="Этот подарок нельзя улучшить")
```

Подарки, которых нет в каталоге Telegram (только в WaysGram), отбиваются, хотя цена
фиксированная (25 ⭐) и пул характеристик уже умеет падать в `gift_upgrades.FALLBACK_POOL`
(`_collectible_trait_pool()`).

**Правка.** Убрать guard, оставить проверку непустого пула (`gift_upgrades.pool_for_gift`
всегда возвращает непустой благодаря fallback). Ключ уникального номера —
`gift_name`/`serial`, он в коде уже есть.

**Уверенность:** высокая. Нужно решение по продукту: включаем улучшение (так задумано в
комментарии к цене) или, наоборот, честно скрываем кнопку «Улучшить» для таких подарков.

### 5. Пропала иконка Plush Pepe в «Мои подарки»

**Причина.** Арт ячейки берётся из `WaysGramGiftMediaStore` (стилл) и
`getAnimationDocument()` (анимация). Оба заполняются только если в `/api/me/gifts` есть
`sticker_available == true` **и** `telegram_gift_id`
(`downloadGiftStickers`, `downloadGiftAnimations`). Plush Pepe (id `5936013938331222567`)
распродан, поэтому в каталоге `telegram_mtproto_gift_catalog` у него `is_available = 0`
и в ответе инвентаря нет `telegram_gift` → качать нечего; сверху `clearStillImages()`
стирает кеш. В итоге ячейка остаётся с эмодзи-оверлеем/пустой.

**Правка (сервер).** В `/api/me/gifts` отдавать `telegram_gift_id` и `sticker_available`
из каталога по `user_gifts.telegram_gift_id` независимо от `is_available`/`sold_out`
(распроданный подарок по-прежнему имеет стикер-сет), а `gift_name` для него — «Plush Pepe»
(`telegram_gift_display_name` уже знает этот id).

**Правка (клиент).** Не стирать стиллы (п. 1), показывать эмодзи как заглушку только когда
арта реально нет (`WaysGramGiftArtwork.hasArtwork`).

**Проверить на данных:**
`SELECT serial_number, telegram_gift_id, sticker_available FROM user_gifts WHERE gift_name LIKE '%Plush%'`
и `GET /api/me/gifts` — есть ли `telegram_gift_id` у этой записи.

**Уверенность:** средняя — нужен ответ API (в репозитории данных нет).

### 6. Нет кнопки «Продать подарок» в карточке Saved Gift и нет картинки подарка

**Причина.** Кнопки продажи в нативной карточке нет вообще: патчи Telegram-овские действия
только **отключают** (`canConvert()/canTransfer()/canDelete()/getLink()` → `false/null` для
WaysGram), а своя продажа живёт в `WaysGramGiftCatalogSheet` (маркет). Строки карточки — это
`tableView.addRow(...)` в `StarGiftSheet`: дата — `StarsTransactionDate` (строка 5081),
стоимость — `Gift2Value` (5082), между ними сейчас пусто.

Картинки в карточке нет потому, что detail-патч ставит только
`topView.imageView[0].getImageReceiver().setImageBitmap(WaysGramGiftMediaStore.get(serial))`
(стилл), а стилл пуст (п. 5), и `WaysGramGiftArtwork.apply()` (анимация) там не вызывается.

**Правка.**
* detail-патч: `WaysGramGiftArtwork.apply(topView.imageView[0], serial)` вместо `setImageBitmap`,
  эмодзи-оверлей скрывать при наличии арта;
* новая строка между датой и стоимостью для WaysGram-подарков **не улучшенных**
  (`upgrade_level == 0`): «Продать подарок» → подтверждение → `WaysGramGiftCatalogClient.sellGift`
  (эндпоинт `/api/gifts/{serial}/sell` уже есть, идемпотентный). Цена — как в маркете
  (`sell_refund`, 50 %).

**Уверенность:** высокая (кнопки действительно нет).

### 7. Квадратик (placeholder) за картинкой подарка

**Причина.** Два источника, оба в сборке нативной карточки:
1. `patternDocument` = стикер самого подарка (п. 3) — Telegram рисует «узор» отдельным
   квадратным слоем за моделью;
2. `createNativeBackdrop()` подставляет `pattern_color = contrastColor(center)`, когда
   Telegram не публикует `pattern_color` — это ровный квадрат фона под моделью.

**Правка.** Убрать подмену `model`/`pattern` стикером (п. 3); `pattern_color` заполнять
только из `backdrop.pattern_color` каталога, иначе `0` (без слоя), `edge_color` — из каталога
или затемнённый центр.

**Уверенность:** высокая для (1), средняя для (2) — проверяется визуально после сборки.

---

## B. Админ-панель

### 8. Страница 14 («подарки»): названия вида «Подарок телеграмм»

**Причина.** `authbot/bot.py`:
* `telegram_gift_rows()` (1817–1830) для строк, которых нет в каталоге, жёстко подставляет
  `'Подарок Telegram' AS title`;
* `telegram_gift_display_name()` (1847–1872) возвращает `"Подарок Telegram"` всякий раз, когда
  `title` пуст/«Telegram Gift N» и из `sticker_set_name` не получается читаемого имени.

Отсюда на странице десятки одинаковых безымянных позиций.

**Правка.** Читаемое имя всегда: `title` → разбор `sticker_set_name` → `Gift · <последние 4
цифры gift_id>` (уникально); литерал из SQL убрать; в подпись добавить `sticker_emoji`.

**Уверенность:** высокая.

---

## C. Метки

### 9. Метки не отображаются вообще

Две независимые причины, обе надо закрыть.

**(а) Клиент: чат-лист не перерисовывает строку после загрузки меток.** `DialogCell`
(`patch_chat_list_marks_reserve/_marks`): ширина под пилюли `waysGramMarksWidth` вычисляется
**только** в `buildLayout()`, а рисование в `drawMarks` требует `waysGramMarksWidth > 0`.
Метки приходят асинхронно; колбэк делает только `requestLayout()`, а `onLayout()` вызывает
`buildLayout()` лишь при изменении размера или при `updateLayout == true`
(`DialogCell.java:1071`). Размер не меняется → `buildLayout` не запускается →
`waysGramMarksWidth` навсегда `0` → **пилюли не рисуются никогда**.
Правка: в колбэке `updateLayout = true; requestLayout(); invalidate();` (и такой же форс в
`reservedWidth`-пути).

**(б) Доставка меток.** `WaysGramPeerBadges`:
* `CALLBACKS` — по одной лямбде на пира (`put` перезаписывает): если метку запросил чат-лист,
  а потом заголовок чата — разбудится только последний. Заменить на список колбэков;
* для аккаунта запасной путь `/api/me` читает `labels` верхнего уровня — если его там нет,
  метки аккаунта не появятся нигде (ни в профиле, ни в Saved Messages). Проверить ответ
  `GET /api/labels?targets=account:<id>` на сервере (эндпоинт добавлен только в этой ветке —
  на работающем сервере он может отсутствовать → 404 → пустой список).

**Проверка на живом сервере:**
`curl -H 'Authorization: Bearer <token>' 'https://<host>/api/labels?targets=account:<tg_id>'`
— ожидается `{"labels": {"account:<id>": [{"text": "...", "color": "#RRGGBB"}]}}`.

**Уверенность:** (а) высокая, (б) средняя (нужен ответ сервера).

---

## Порядок правок

1. Подарки: точечные обновления ячеек + отказ от `clearStillImages` (п. 1, 2, 5а).
2. Подарки: не подменять модель/узор стикером, пересборка атрибутов по приходу документа (п. 3, 7).
3. Подарки: detail-карточка — арт + строка «Продать подарок» (п. 6).
4. Сервер: улучшение без `telegram_gift_id` (п. 4) — после решения по продукту.
5. Сервер: `sticker_available`/`telegram_gift_id` для распроданных (п. 5).
6. Метки: форс `buildLayout` + список колбэков (п. 9а, 9б-клиент).
7. Админ-панель: имена подарков (п. 8).
8. Сборка APK, проверка, коммит.
