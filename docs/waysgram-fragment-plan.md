# Система Fragment в WaysGram: как это можно реализовать

**Статус: варианты A и B реализованы** (фазы 0–3 из раздела 10). В коде:

| Что | Где |
|---|---|
| справочник владения, лоты, аукционы, TON | `authbot/fragment_market.py` |
| миграция и заселение из `accounts.username` | `messenger_api.init_messenger_storage` |
| маршруты `/api/collectibles…`, `/api/me/ton…` | `fragment_market.register_routes` |
| фоновый расчёт аукционов | `process_due_username_auctions` в воркере |
| вотчер TON | `authbot/scripts/ton_watcher.py` |
| карточка профиля (`fragment.getCollectibleInfo`) | `tgnet/WaysGramFragment.java` |
| экран рынка | `ui/WaysGramFragmentMarket.java` (Настройки → Коллекционные юзернеймы) |
| тесты | `authbot/tests/test_fragment_market.py` (14), `scripts/test_android_fragment_market.py` (9) |

Вариант C (NFT-обёртка TEP-62, ончейн-эскроу, фиат/KYC) остаётся планом: это
отдельный проект со смарт-контрактом, аудитом и юридической частью.

Ниже — исходный разбор: что такое Fragment, почему нельзя подключиться к нему
напрямую, оценка вариантов и правила, по которым теперь работает рынок.

## 1. Что такое Fragment по факту

`fragment.com` — официальный маркетплейс Telegram. Через него продаются:

| Что | Как устроено у Telegram |
|---|---|
| Юзернеймы (`@name`) | аукцион на 7 дней или фикс-цена; после покупки — коллекционный юзернейм |
| Коллекционные юзернеймы | NFT на TON (TEP-62), есть адрес смарт-контракта, ончейн-передача |
| Анонимные номера (+888 …) | покупка/продление за TON, вход в аккаунт по номеру |
| Stars | покупка звёзд (карта, TON, крипта), Stars → подарки, Premium |
| Premium | покупка/подарок подписки |
| Реклама (Ad Account) | пополнение TON-баланса рекламного кабинета |

## 2. Почему нельзя «подключиться к Fragment»

Публичного API у Fragment нет. Всё, что есть:

* внутренние HTTP-эндпоинты веб-приложения (`fragment.com/api/...`) — они используют
  сессию Telegram-аккаунта, меняются без предупреждения и прямо запрещены правилами;
* смарт-контракты маркетплейса на TON — они принимают ставки/платежи от имени
  конкретного аккаунта, зарегистрированного в системе Fragment;
* фиатная часть (карты) и KYC — закрытая инфраструктура Telegram.

Итог: **точный Fragment-паритет без договора с Telegram невозможен**. Реалистично —
повторить продуктовую механику внутри WaysGram на своих правилах: свой рынок
юзернеймов/номеров, свои аукционы, оплата звёздами (уже есть) и/или TON.

## 3. Три варианта и оценка

| Вариант | Что входит | Оценка | Риски |
|---|---|---|---|
| **A. Свой маркетплейс на звёздах** | фикс-цена и аукционы юзернеймов, эскроу, комиссия, история | 1–2 недели | нет ончейн-гарантий, всё доверяет серверу WaysGram |
| **B. A + TON-платежи** | депозиты TON с зачислением звёзд, вывод, TON Connect в клиенте | +1–1.5 недели | ключи hot-wallet, курс, AML/KYC при выводе |
| **C. Полный паритет** | NFT-юзернеймы на TON (TEP-62), ончейн-эскроу, карты/фиат | 1.5–2 месяца + юридика | смарт-контракты, аудит, платёжный провайдер, лицензии |

Рекомендация: **A → B**. Вариант C имеет смысл только если появится платёжный партнёр
и готовность вести юридическую часть; без этого это красивый прототип без оборота.

## 4. Что в проекте уже готово (опоры)

* **Юзернеймы.** `accounts.username` (`bot.py:1122`), группы `chat_groups.username`,
  каналы `channels.username`, боты `bots.username`. Проверка занятости и смена —
  `PUT /api/me` (`messenger_api.py:6299`, `COLLATE NOCASE`), клиент:
  `WaysGramProfile.updateUsername` / `checkUsername` ← `TL_account.updateUsername` /
  `checkUsername` (`WaysGramTransport:711`). То есть владение и смена уже есть, не хватает
  «рынка» поверх них.
* **Звёзды.** `accounts.stars`, `economy_transactions(telegram_id, asset, amount,
  balance_after, reason, created_at)`, `chat_stars` + `chat_star_transactions`,
  идемпотентные списания (`charge_dm_fee`, `Idempotency-Key`), клиентские экраны
  `StarsIntroActivity`, `StarsController`, подарки (`WaysGramGifts`), история платежей
  (`/api/me/paid-messages`).
* **Клиентские каркасы Fragment-экранов.** `FragmentUsernameBottomSheet`,
  `ChangeUsernameActivity`, `ProfileActivity` → `TL_fragment.getCollectibleInfo`
  (сейчас запрос не обслуживается, поэтому карточка пустая), `StarsIntroActivity`.
* **Транспорт.** Любой новый серверный метод — это ветка в `WaysGramTransport.handle`,
  REST-вызов в `WaysGramApi`, маппинг в `WaysGramMapper`, и статический тест в `scripts/`.

## 5. Модель данных (вариант A + B)

```sql
-- Что вообще продаётся: юзернейм пользователя, группы, канала, номер
CREATE TABLE IF NOT EXISTS collectibles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL CHECK(kind IN ('username','phone')),
    name TEXT NOT NULL UNIQUE,                -- 'durov', '+88812345678'
    owner_telegram_id INTEGER,                -- NULL = свободно, продаёт система
    owner_scope TEXT NOT NULL DEFAULT 'user', -- user | group | channel (id в owner_chat_id)
    owner_chat_id INTEGER NOT NULL DEFAULT 0,
    acquired_at INTEGER NOT NULL DEFAULT 0,
    transfer_frozen_until INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL DEFAULT 'system'     -- reserved | auction | sale | system
);

CREATE TABLE IF NOT EXISTS listings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collectible_id INTEGER NOT NULL REFERENCES collectibles(id),
    seller_telegram_id INTEGER NOT NULL,
    price_stars INTEGER NOT NULL CHECK(price_stars > 0),
    status TEXT NOT NULL DEFAULT 'active'      -- active | sold | cancelled
        CHECK(status IN ('active','sold','cancelled')),
    created_at INTEGER NOT NULL,
    sold_at INTEGER
);

CREATE TABLE IF NOT EXISTS auctions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collectible_id INTEGER NOT NULL REFERENCES collectibles(id),
    seller_telegram_id INTEGER NOT NULL DEFAULT 0,  -- 0 = аукцион системы
    min_bid_stars INTEGER NOT NULL,
    starts_at INTEGER NOT NULL,
    ends_at INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'active'
        CHECK(status IN ('active','finished','cancelled')),
    winner_telegram_id INTEGER,
    final_price_stars INTEGER
);

CREATE TABLE IF NOT EXISTS bids (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    auction_id INTEGER NOT NULL REFERENCES auctions(id),
    bidder_telegram_id INTEGER NOT NULL,
    amount_stars INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bids_auction ON bids(auction_id, amount_stars DESC);

-- Вариант B: TON
CREATE TABLE IF NOT EXISTS ton_deposits (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    telegram_id INTEGER NOT NULL,
    address TEXT NOT NULL,
    memo TEXT NOT NULL,                        -- комментарий перевода = привязка к аккаунту
    amount_nanoton TEXT NOT NULL,
    tx_hash TEXT NOT NULL UNIQUE,              -- защита от повторного зачисления
    stars_credited INTEGER NOT NULL,
    rate TEXT NOT NULL,                        -- курс на момент зачисления
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ton_payouts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    telegram_id INTEGER NOT NULL,
    amount_nanoton TEXT NOT NULL,
    address TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK(status IN ('pending','sent','failed')),
    tx_hash TEXT,
    created_at INTEGER NOT NULL
);
```

Проводки — только через `economy_transactions` с понятными `reason`:
`listing:<id>`, `auction_hold:<id>`, `auction_win:<id>`, `auction_refund:<id>`,
`ton_deposit:<tx_hash>`, `commission:<id>`. Это даёт бесплатную историю и аудит, а
`/api/me/paid-messages` можно расширить новыми `kind`.

## 6. API сервера

```
GET    /api/collectibles/{name}            карточка: владелец, цена, аукцион, история
GET    /api/collectibles?kind=username&query=du
GET    /api/collectibles/{name}/bids        ставки (для аукциона)
PUT    /api/collectibles/{name}/listing     выставить на продажу (только владелец)
DELETE /api/collectibles/{name}/listing     снять с продажи
POST   /api/collectibles/{name}/buy         купить по фикс-цене (списание + передача владения)
POST   /api/collectibles/{name}/bid         ставка (холд звёзд)
PUT    /api/me/ton-address                  адрес и memo для пополнения
POST   /api/me/ton-withdraw                 заявка на вывод
```

Правила, без которых рынок ломается:

1. **Владение проверяется на сервере**, а не клиентом: продавец — текущий владелец в
   `collectibles`, а не тот, кто прислал запрос.
2. **Эскроу.** Покупка — одна `BEGIN IMMEDIATE`-транзакция: списать у покупателя,
   записать продавцу минус комиссия, сменить владельца, закрыть `listing`, положить
   запись в `economy_transactions`, затем отдать `TL_updates` с обновлёнными профилями
   обеих сторон.
3. **Заморозка.** `transfer_frozen_until = now + 24h` после смены владельца: нельзя
   сразу перепродать (защита от накрутки и от «продал-забрал»).
4. **Аукцион.** Ставка холдит звёзды (`auction_hold`), перебитая — возвращается.
   Снайп-защита: ставка в последние 5 минут продлевает аукцион на 5 минут. Расчёт —
   фоновая задача `asyncio` в authbot плюс «ленивый» расчёт при чтении карточки, чтобы
   рестарт сервера не терял закрытие аукциона.
5. **Идемпотентность.** Все мутации — с `Idempotency-Key` (как `POST /api/messages`),
   иначе двойной тап «купить» спишет дважды.
6. **Комиссия** — процент от продажи (например 5 %), настраивается админом, проводка
   `commission:<id>`.

## 7. Клиент

* `TL_account.checkUsername` уже есть — расширить ответ: если имя продаётся, вернуть
  цену/аукцион, чтобы в `ChangeUsernameActivity` рядом с «занято» появилось «продаётся
  за ⭐5 000».
* `fragment.getCollectibleInfo` (`WaysGramTransport`): отдавать `TL_fragment.TL_collectibleInfo`
  из карточки `/api/collectibles/{name}` — тогда штатный `FragmentUsernameBottomSheet` и
  `ProfileActivity` начнут показывать дату покупки, адрес NFT (можно отдавать пустой) и
  цену. Это самый дешёвый способ «включить Fragment» визуально.
* Новый экран `WaysGramFragmentActivity` (по образцу `WaysGramChannelInbox` /
  `WaysGramPaidHistory`): вкладки «На продажу», «Аукционы», «Мои», карточка с ставкой и
  кнопкой «Купить». Экран открывается из `ChangeUsernameActivity` и из профиля.
* Покупка — только через сервер: клиент не считает баланс и не решает, кому что
  принадлежит; после ответа обновляем профиль через `MessagesController.putUser`.
* Сервисное сообщение в чат владельцу: «@name продан за ⭐N» (механика `messageAction…`
  уже отработана на платных сообщениях и подарках).

## 8. TON-часть (вариант B)

1. **Приём.** У сервиса один hot-wallet (или пул адресов). Пользователю показываем адрес
   и `memo` = `wg:<telegram_id>:<nonce>`; перевод без `memo` не зачисляем автоматически
   (попадает в ручной разбор).
2. **Watcher.** Фоновая задача раз в 15–30 с дергает `toncenter`/`tonapi`
   (`/api/v2/getTransactions?address=…`) по last-known lt, ищет `in_msg` с нашим `memo`,
   пишет `ton_deposits` с `tx_hash UNIQUE` и начисляет звёзды по курсу из конфига
   (`stars_per_ton`), курс фиксируется в записи. Повторный `tx_hash` — отказ.
3. **Вывод.** Заявка → проверка AML-порогов и лимитов → очередь `ton_payouts` →
   подпись и отправка (`toncenter` + приватный ключ на сервере, в идеале — отдельный
   сервис-подписант) → `tx_hash`. Ключи не в репозитории, только в секретах окружения.
4. **Клиент.** TON Connect (или простой deep-link на `tonkeeper`/`tonhub` с адресом и
   суммой) — для оплаты из кошелька; для пополнения звёзд хватает адреса + memo,
   кошелёк в клиент встраивать не обязательно.
5. **Юридика.** Продажа цифровых активов за крипту и вывод — это KYC/AML, налоги и,
   возможно, лицензия в зависимости от юрисдикции; без этого лучше ограничиться
   «покупкой звёзд» (вариант A) или принимать TON только как способ пополнения без
   обратного вывода.

## 9. Риски

* **Фишинг владения.** Самая частая атака — убедить сервер, что продавец это владелец.
  Решение: владение только из БД, проверка `telegram_id` из сессии, заморозка 24 ч,
  журнал всех смен владельца.
* **Накрутка аукционов** своими аккаунтами. Решение: холд ставки реальными звёздами,
  лимит ставок на аккаунт, заморозка звёзд до конца аукциона.
* **Рестарт сервера** во время аукциона. Решение: расчёт по времени в БД (а не в
  памяти), ленивое закрытие при чтении + фоновая задача.
* **Двойное списание** при повторе запроса. Решение: `Idempotency-Key` + уникальные
  проводки.
* **Чужие юзернеймы Telegram.** Продавать можно только имена внутри WaysGram; `@durov`
  в Telegram нам не принадлежит — такие имена должны быть зарезервированы как занятые
  (или продаваться как «wildcard» только если сервис реально владеет именем).

## 10. План по фазам

1. **Фаза 0 (0.5 дня).** Справочник `collectibles`: миграция, заселение текущих
   юзернеймов (`accounts.username` и т. д.), `/api/collectibles/{name}`, отдача
   `fragment.getCollectibleInfo`. Уже после этого карточка в профиле перестаёт быть пустой.
2. **Фаза 1 (2–3 дня).** Фикс-цена: `listings`, `POST …/buy`, эскроу, комиссия,
   заморозка, сервисное сообщение, экран в клиенте, тесты на двойную покупку и на
   «продаёт не владелец».
3. **Фаза 2 (2–3 дня).** Аукционы: таблицы, холд/возврат ставок, снайп-защита, расчёт,
   карточка аукциона в клиенте.
4. **Фаза 3 (3–4 дня).** TON-депозиты: адрес+memo, watcher, курс, зачисление, история.
5. **Фаза 4 (по желанию).** Вывод TON, NFT-обёртка юзернейма (TEP-62) — отдельный
   проект с аудитом контракта.

Каждая фаза — с тестами в стиле проекта: `authbot/tests` (поведение API, миграции,
идемпотентность) и `scripts/test_android_*.py` (наличие обработчика, экрана, текстов).
