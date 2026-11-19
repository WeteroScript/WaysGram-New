# Аудит подарков: почему у улучшенных NFT нет модели и что ещё расходится с оригиналом

Дата: 2026-11-18. База: `WaysGram-New` @ `63042d6` (Telegram Android 13.1.1 + WaysGram-слой).
Сверка: официальный `TelegramMessenger/Telegram-Android` master (13.2.2), `Nekogram/Nekogram` main,
Telethon 1.46 (схема TL-метода `payments.getStarGiftUpgradeAttributes`).
Сборка APK `44518184202` — success, APK `WaysGram-New-APK-261` (96 421 304 B).

---

## 1. Карта файлов (что за что отвечает)

### Клиент — рендер (нативный Telegram, не наш код)

| Файл | Роль |
|---|---|
| `ui/Stars/StarGiftSheet.java` (11 848) | карточка подарка. Строки Model/Pattern/Backdrop — `addAttributeRow(findAttribute(gift.attributes, …))` 4641–4663 (13.2.2) / 4596–4618 (13.1.1). Quantity — `Gift2QuantityIssued1/2` из `gift.availability_issued/availability_total`. Арт шапки — `gift.getDocument()`. |
| `tgnet/tl/TL_stars.java` | `starGiftUnique.getDocument()`: `sticker`, иначе документ `starGiftAttributeModel` (стр. 128). |
| `ui/Gifts/GiftSheet.java` (3 267) | каталог «Buy a Gift»; берёт `StarsController.sortedGifts`. |
| `ui/Gifts/ProfileGiftsContainer.java` (2 269) | сетка «Мои подарки»; ячейка = `GiftSheet.GiftCell` с `sticker` подарка и `uniqueGift.getDocument()`. |
| `ui/Stars/StarsController.java` (4 471) | кэш каталога и preview; `getStarGiftPreview()` → `getStarGiftUpgradePreview` (стр. 4007 в master). |

### Клиент — наш слой

| Файл | Роль |
|---|---|
| `tgnet/WaysGramGifts.java` (513) | транспорт: `getStarGifts` → наш каталог, `getSavedStarGifts`, `getPaymentForm`, `sendStarsForm`, `upload.getFile` → файлы из `filesDir/waysgram_gifts/*.bin`, `ensureSticker`/`placeholderSticker`. |
| `tgnet/WaysGramTransport.java` | маршрутизация TL-запросов в `WaysGram*` (стр. 168–183). |
| `messenger/waysgram/WaysGramProfileGiftsController.java` (774) | `/api/me/gifts` → `TL_starGift` / `starGiftUnique`; создаёт backdrop и (при наличии документа) model/pattern. |
| `messenger/waysgram/WaysGramGiftAttributes.java` (556) | список `model/pattern` подарка (`/api/telegram/gifts/{id}/attributes`), скачивание документов, кэш, `traitDocument`, `wantRolled`, `retryFailed`. |
| `messenger/waysgram/WaysGramGiftDocuments.java` (231) | `sniffMime`, `buildDocument`, файлы `{documentId}.bin`. |
| `messenger/waysgram/WaysGramGiftMediaStore.java` (171) | кэш арта обычных подарков (`getAnimationDocument`). |
| `messenger/waysgram/WaysGramGiftTraits.java` (88) | чтение `traits` из JSON (`name`, `rarity`, `permille`, `document_id`). |
| `messenger/waysgram/WaysGramGiftUpgrade.java` (94) / `WaysGramGiftSell.java` (149) | апгрейд 25 ⭐ / продажа (50 %, 7 дней). |
| `messenger/waysgram/WaysGramGiftCatalogSheet.java` (1 164), `WaysGramGiftInventorySheet.java` (352) | старые кастомные экраны; вызываются только из `WaysGramHomeFragment` (под удаление по п.9). |
| `messenger/waysgram/WaysGramGiftArtwork.java` (157) | **мёртвый код**, 0 ссылок (остаток отклонённого «рисованного арта»). |

### Бэкенд

| Файл | Роль |
|---|---|
| `authbot/messenger_api.py` | `/api/me/gifts`; `/api/telegram/gifts`; `/api/telegram/gifts/{id}/sticker`; `/api/telegram/gifts/{id}/attributes`; `/attributes/{document_id}`; `upgrade_gift`; `_sync_gift_attribute_documents`, `_enrich_traits_documents`, `_gift_attribute_documents`. |
| `authbot/telegram_mtproto.py` | `TelegramMTProtoGiftCatalog`: `_request_upgrade_attributes` (570), `_attribute_payloads` (143), `sync_upgrade_attributes` (600), `download_attribute` (710), `prefetch_state`/`start_prefetch`. |
| `authbot/gift_upgrades.py` + `gift_upgrade_pools.json` | ролл трейтов: `TRAITS_VERSION = 3`, `FALLBACK_POOL`, `roll_traits(pool, now, previous)`. Пул на подарок: 50–157 моделей, 136–300 узоров, 60–80 фонов (115 подарков), имена **английские** («Flowey», «Sunflower», «Strawberry»).<br>`FALLBACK_POOL` (для подарков вне таблицы) — 1 модель «Оригинальная», 1 узор «Без узора», 9 фонов. |

---

## 2. Как это устроено в оригинальном Telegram

```
клиент                                сервер Telegram
  │  payments.getStarGiftUpgradePreview(gift_id)
  │────────────────────────────────────►  starGiftUpgradePreview{sample_attributes}
  │  (только для катушки-превью, документы «примерные»)
  │
  │  payments.upgradeStarGift(...)
  │────────────────────────────────────►  updateStarGiftAuction… + starGiftUnique
  │                                        {num, slug, title, attributes[
  │                                          starGiftAttributeModel{document},
  │                                          starGiftAttributePattern{document},
  │                                          starGiftAttributeBackdrop{colors}]}
  ◄────────────────────────────────────
  │  карточка: addAttributeRow(model), addAttributeRow(pattern), addAttributeRow(backdrop)
  │  арт: gift.getDocument() → sticker ?? model.document
  │       ImageLocation.getForDocument(doc) → FileLoader → upload.getFile
```

Ключевой вывод: **официальный клиент никогда не ищет документ модели сам**. Сервер отдаёт
`starGiftUnique` вместе с документами всех трёх атрибутов — клиент только рисует.
`getStarGiftUpgradePreview` возвращает `sample_attributes` — по одному образцу каждого вида,
этого достаточно только для анимации катушки.

Проверка «других репозиториев» (запрос пользователя):

* `Nekogram/Nekogram` (main) `StarGiftSheet.java` — **побайтово совпадает** с официальным
  (`md5 d1913dd090b0ee818d72d4c449b51404`, 609 020 B). Форки этот код не меняют.
* `Cherrygram/Cherrygram`, `Nagram`, `NagramX`, `ForkGram`, `Telegram-FOSS` — правят тему/анимации;
  `StarGiftSheet`, `ProfileGiftsContainer`, `GiftSheet` берутся из upstream без изменений пути
  атрибутов.
* Сравнение 13.1.1 → 13.2.2 по gift-файлам: только тема и «стеклянные» стили, новых подарков и
  механик улучшения нет.
* Вывод: наши расхождения — не «старая версия», а наш собственный слой (ролл на бэкенде,
  синтез `starGiftUnique` в клиенте). Чинить нужно его, а не форк.

## 3. Как это устроено у нас (и где рвётся)

```
Telegram ──(Telethon)──► telegram_mtproto_gift_attributes (модели/узоры/фоны + document_id)
                              │
   gift_upgrades.roll_traits ─┤ (ролл локальный: имя + rarity + permille)
                              ▼
        БД подарка: traits_json {model:{name,rarity,permille[,document_id]}, …}
                              │
   /api/me/gifts ─────────────┴──► WaysGramProfileGiftsController → starGiftUnique
                                        │  backdrop   — всегда
                                        │  model/pattern — только если документ уже на диске
                                        ▼
                              WaysGramGiftAttributes.traitDocument(...)
                                        │  document_id != 0 → documentById()  ← быстрый путь
                                        │  иначе → document(kind, name)       ← поиск по имени
                                        ▼
                              /api/telegram/gifts/{id}/attributes  (список с document_id)
                                        ▼
                              скачивание → filesDir/waysgram_gifts/{docId}.bin
                                        ▼
                              upload.getFile → WaysGramGifts.getFile (стр. 408)
```

Ролл локальный, значит документ конкретной модели нам никто не присылает — мы обязаны вывести
его сами. В этом и лежит корень бага.

---

## 4. Найденные дефекты

### B1 (критично, «нету моделей»). Документ модели/узора не находится

`WaysGramGiftAttributes.traitDocument`:

```java
long documentId = entry.optLong("document_id", 0L);
if (documentId != 0L) return documentById(giftId, documentId);   // путь A
return document(giftId, kind, WaysGramGiftTraits.name(traits, key)); // путь B
```

* **Путь A молчит у уже улучшенных подарков.** `document_id` в `traits_json` заполняют только
  `_sync_gift_attribute_documents` + `_enrich_traits_documents` — код коммита `63042d6`.
  Подарки, улучшенные до него (и до развёртывания этой версии на сервере), `document_id` не
  имеют вообще, бэкфилла для них нет — значит остаётся путь B. Проверяется одним запросом
  (см. §7).
* **Путь B зависит от совпадения имени.** `document()` ищет `keyFor(kind, name)` =
  `kind + "\0" + name.toLowerCase()` в списке `/attributes`. Список приходит из
  `telegram_mtproto_gift_attributes`, т.е. из того, что вернул Telegram.
  Если список пуст (провайдер не авторизован / 404 / таймаут) или имя отличается —
  результат `null`, и **повторной попытки нет**: `FAILED.add("load|"+giftId)` живёт до
  `retryFailed()`, который вызывается только при ручном обновлении профиля.
* **Список может быть неполным.** `_request_upgrade_attributes` сначала пробует
  `payments.getStarGiftUpgradeAttributes` (полный набор, Telethon 1.46 — есть), а при любой
  ошибке молча падает на `payments.getStarGiftUpgradePreview` → `sample_attributes`,
  то есть **1 модель, 1 узор, 1 фон**. Тогда:
  * `_match_attribute_document(..., allow_position=len(entries)==len(rows))` — позиции
    запрещены (50 ≠ 1), совпадение только по имени или permille → почти всегда мимо;
  * клиентский поиск по имени тоже попадает в 1 случай из 50.
  Ровно это и наблюдается: у одного подарка модель есть, у остальных нет.

Итог: даже когда «механика» исправна, вероятность увидеть модель ≈ (число совпавших атрибутов)/(50).

### B2. `Quantity 0/0 issued` в карточке

`StarGiftSheet` (unique-ветка) печатает `availability_issued` / `availability_total`.
`WaysGramProfileGiftsController` эти поля у `starGiftUnique` **не заполняет вообще** — они
остаются 0. В оригинале там данные коллекции, которые приходят с сервера.
У нас они есть в `telegram_gift`-метаданных (`total_count`, `remaining_count`) — просто не
прокинуты.

### B3. Строка «This gift is visible. Hide from profile» — мёртвая кнопка

`toggleShow()` (стр. 6083) отправляет `TL_stars.saveStarGift` в Telegram. Наш транспорт этот
запрос не перехватывает (`WaysGramTransport` → обычный путь к серверу Telegram), а аккаунт
работает без Telegram-сессии → ошибка/тишина. Плюс сама надпись обещает действие, которого нет:
скрытие подарка с профиля у нас не реализовано. То же с пунктом меню «Сделать видимым/скрытым».

### B4. Отсутствие строк Model/Pattern в карточке — следствие B1

`addAttributeRow` вызывается только для найденных атрибутов; нет документа → контроллер не
добавляет `starGiftAttributeModel/Pattern` в `uniqueGift.attributes` → строк нет. Отдельного
бага нет: починим B1 — появятся строки (они уже сверены с upstream: имя + редкость + превью).

### B5. Пустые/серые ячейки в «Buy a Gift» (каталог)

Каталог в этом окне — **нативный** `ui/Gifts/GiftSheet`, он получает данные от нашего
транспорта: `WaysGramTransport` → `TL_stars.getStarGifts` → `WaysGramGifts.catalog(account)`.
Пустая ячейка = серый нарисованный бокс с 🎁 (`placeholderSticker`, стр. 265), который
подставляется, если `ensureSticker()` (стр. 114) не смог скачать `/api/telegram/gifts/{id}/sticker`
(404 / таймаут / битый файл). Повтор — только через 5 минут (`RETRY_DELAY_MS`) и только при
следующей перезагрузке каталога; в открытом шите ячейка так и остаётся серой.
То есть это не «нет данных», а «не докачался арт, и нет ни ретрая, ни инвалидации ячейки».

### B6. `WaysGramGifts.getFile` отдаёт только стикеры каталога

`upload.getFile` (стр. 177 транспорта) → `WaysGramMedia.getFile` → `WaysGramGifts.getFile`,
который читает единственный каталог `filesDir/waysgram_gifts/{id}.bin`. Документы атрибутов
пишутся туда же (`WaysGramGiftDocuments.stickerFile`), поэтому путь рабочий — но он же является
единственным источником байтов для `FileLoader`. Если файл не скачан к моменту первого
`ImageLocation`, `FileLoader` помечает файл как «не найден» и повторно тянет только по
`loadFile(..., force=true)`; наш `WaysGramGiftAttributes` дергает ребилд инвентаря, а не
`FileLoader`. Симптом: документ уже на диске, а картинка не появляется до перезапуска.
Нужен явный «дотолкнуть» файл в `FileLoader` при появлении документа.

### B7. Мёртвый код и остатки отклонённых подходов

* `WaysGramGiftArtwork.java` — 0 ссылок.
* `WaysGramGiftCatalogSheet` + `WaysGramGiftInventorySheet` — старые кастомные экраны
  (п.9: кастомные интерфейсы после «следующий подарок» убрать).
  Их наличие не влияет на скриншоты (они не открываются), но это риск случайно вернуть
  «свои» экраны и лишние 1 500 строк.

### B8. Диагностики нет

Ни в `wgadm`, ни в API нет способа увидеть: сколько строк атрибутов у подарка, полный это набор
или `sample`, есть ли `document_id` в трейтах, дошёл ли файл до диска. Без этого любая правка
проверяется только «на глаз» через пересборку APK — что мы и делаем уже который раз.

### B9. Сравнение по имени — хрупкое (типографика и юникод)

`keyFor()` делает только `trim().toLowerCase()`. В наших пулах есть имена с типографским
апострофом — например модель **`Eve’s Apple`** (U+2019) у Astral Shard. Если Telegram вернёт
`Eve's Apple` (U+0027), `NFD`-форма или неразрывный пробел, ключ не совпадёт → документа нет, и
это никак не диагностируется. Плюс Telegram переименовывает атрибуты, а наш JSON снят один раз
(`captured: 2026-11-18`) — любое переименование навсегда ломает поиск по имени.

Отдельный случай: подарки, которых нет в таблице, получают `FALLBACK_POOL` с моделью
**«Оригинальная»** и узором «Без узора» — у них документа не существует в принципе, а русское
имя гарантирует промах поиска. Для таких подарков модель не появится никогда, пока мы не
перейдём на ролл по живому набору (Этап 1 п.2) или не зададим явное поведение.

---

## 5. Разбор скриншотов

| Скриншот | Что на нём | Диагноз |
|---|---|---|
| Карточка «Astral Shard #12» | Owner есть, Backdrop «Aquamarine <0.1%», Quantity «0/0 issued», Model/Pattern отсутствуют, арт-шапка пустая (только градиент) | B1 (нет документов модели/узора), B2 (quantity 0/0), B3 (мёртвая ссылка «Hide from profile»), B4 |
| «Buy a Gift» | нативный каталог с ценами, часть ячеек без арта | B5 (placeholder вместо арта) |
| «Мои подарки» | 2–3 плитки с моделью, остальные — только градиент | B1 (у остальных документ не найден), B6 (не дотолкнули в `FileLoader`) |

Ожидаемое поведение (как в оригинале): у каждого улучшенного подарка в шапке карточки и в
ячейке сетки играет модель, ниже идут строки Model/Pattern/Backdrop с редкостями, Quantity
показывает реальные числа коллекции, а «Hide from profile» отсутствует (или работает через
наш бэкенд).

---

## 6. План правок (по этапам, каждый этап — отдельная сборка/проверка)

### Этап 1 — гарантировать документ модели и узора (B1, B6)

Бэкенд:
1. `_request_upgrade_attributes`: не «молча падать» на sample. Пробовать по порядку
   `getStarGiftUpgradeAttributes` → `getStarGiftUpgradePreview`; записывать в
   `telegram_mtproto_gift_attribute_sync` фактический источник (`full` / `sample`) — нужна
   колонка `source` (`ALTER TABLE … ADD COLUMN`, как остальные миграции), чтобы отличать
   деградацию от пустого ответа.
2. При апгрейде: если полный набор доступен — **роллить по живому набору** (имя + permille +
   document_id берутся из строки), а не по статическому JSON. Тогда документ есть по
   построению. Статический пул остаётся запасным (нет набора / 1 sample).
3. Писать в `traits_json` для model/pattern: `document_id`, `position`, `pool_size`
   (и `colors` для backdrop — точные цвета Telegram вместо наших приближений).
4. `_repair_traits_documents` в `/api/me/gifts`: если у подарка `document_id` нет — попытаться
   по `position` (при `pool_size == len(rows)`), затем по permille, затем по имени; если строк
   нет вообще — поставить в очередь фоновый `sync_upgrade_attributes`.

Клиент:
5. `WaysGramGiftAttributes`: `Entry` получает `position`/`permille`; нормализация имён
   (`’`→`'`, `‑`→`-`, схлопывание пробелов, `NFKC`); `traitDocument`/`wantTrait`
   добавляют резервный путь `position → document_id` (когда `pool_size` совпадает с размером
   списка) и `permille`. Так документ находится даже при локализованных/переименованных именах.
6. При появлении документа — `FileLoader.loadFile(doc, force=true)`, чтобы ячейка/шапка
   перерисовались без перезапуска (B6).
7. `retryFailed()` вызывать не только при refresh профиля, но и при открытии карточки подарка.

### Этап 2 — карточка (B2, B3)

8. `WaysGramProfileGiftsController`: `availability_total = telegram_gift.total_count`,
   `availability_issued = total_count - remaining_count` (минимум 0). В upstream строка Quantity
   добавляется **безусловно** (см. 13.2.2, строки 4657–4664 — ни флага, ни проверки на 0),
   поэтому нули убрать можно только данными. Если каталог тираж не знает (`total_count` пуст) —
   минимальная правка `StarGiftSheet`: для WaysGram-подарков с `availability_total == 0` строку
   не добавлять (2 строки, с комментарием), чтобы не показывать «0/0 issued».
9. Скрыть `Gift2ProfileVisible/Invisible` подсказку и пункт меню для WaysGram-подарков
   (или реализовать скрытие через наш API — отдельное решение, спрошу).

### Этап 3 — каталог (B5)

10. `ensureSticker`: при неудаче — не отдавать серый placeholder навсегда: короткий ретрай
    (2 попытки с backoff) и разрешить повтор при следующем открытии шита; кэш неудач — по
    времени, а не «навсегда».
11. `catalog()`: не подменять арт заглушкой молча — логировать `gift_id` причины отказа, чтобы
    на сервере было видно, у каких подарков `/sticker` не отдаёт байты.

### Этап 4 — уборка (B7)

12. Удалить `WaysGramGiftArtwork.java`, `WaysGramGiftCatalogSheet.java`,
    `WaysGramGiftInventorySheet.java` и их вызовы из `WaysGramHomeFragment` (п.9).

### Этап 5 — диагностика (B8)

13. `wgadm` → «Подарки» → строка состояния: для выбранного подарка — `sync.source`
    (full/sample), число строк по видам, сколько трейтов с `document_id`, размер файлов на
    диске. Эндпоинт `GET /api/admin/telegram/gifts/{id}/attributes/status` (только админ).

---

## 7. Проверка

* `pytest authbot/tests` (сейчас 198 passed) + новые тесты: ролл по живому набору, репаир по
  position/permille, `availability_*` в `/api/me/gifts`.
* `javalang`-парсинг изменённых Java-файлов (компилятора в песочнице нет).
* Сборка APK, затем на устройстве:
  1. открыть «Astral Shard #12» → в шапке модель, строки Model/Pattern есть, Quantity не 0/0;
  2. «Мои подарки» → у всех улучшенных подарков играет модель;
  3. «Buy a Gift» → нет серых ячеек;
  4. улучшить любой подарок повторно → меняются модель, узор и фон.
* Конкретные проверки на сервере (до правок — чтобы увидеть факт, после — чтобы увидеть эффект):

  ```bash
  # 1. есть ли document_id в трейтах и сколько строк атрибутов у подарка
  curl -s -H "Authorization: Bearer <token>" https://<host>/api/me/gifts \
    | jq '.gifts[] | select(.upgrade_level > 0)
          | {serial, level: .upgrade_level, gift: .telegram_gift_id,
             model: (.traits.model | {name, document_id, position, pool_size}),
             pattern: (.traits.pattern | {name, document_id})}'

  # 2. полный набор атрибутов или sample (ожидаем столько же строк, сколько в пуле:
  #    например для Astral Shard 50 моделей / 198 узоров / 60 фонов)
  curl -s -H "Authorization: Bearer <token>" \
    "https://<host>/api/telegram/gifts/<telegram_gift_id>/attributes?refresh=1" \
    | jq '{models: (.models | length), patterns: (.patterns | length),
           backdrops: (.backdrops | length),
           models_with_doc: ([.models[] | select((.document_id // 0) > 0)] | length)}'
  ```

  Если `models` = 1, сервер получает только `sample_attributes` — тогда Этап 1 п.2 обязателен
  (роллить по живому набору), иначе документов для остальных моделей пула не существует.

* Один прогон диагностики из п.13 на сервере — до и после деплоя, чтобы увидеть, полный ли
  набор атрибутов отдаёт Telegram для наших подарков. Это единственный факт, который нельзя
  установить из кода: если Telegram отдаёт только `sample`, полный набор придётся дособирать
  иначе (кэш по всем подаркам каталога при первом обращении).
