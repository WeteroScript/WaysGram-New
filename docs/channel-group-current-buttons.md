# Текущие кнопки каналов и групп

Список снят с **текущей сборки** из `WaysGram-Android.zip.part1…part5` после возврата к стоковым
элементам. APK не собирался. Подписи приведены по `values/strings.xml`; в `values-ru` часть ключей отсутствует, поэтому на русском экране часть подписей будет английской.

## 1. Экран чата канала/группы

### 1.1 Шапка

| Кнопка | Что делает |
|---|---|
| Аватар/название | открывает карточку канала/группы |
| Поиск (`Search`) | поиск по сообщениям чата |
| «⋮» (`More options`) | открывает меню чата |

### 1.2 Нижняя панель broadcast-канала

| Кнопка | Когда показывается |
|---|---|
| `Join` / `Request to Join` | канал, в который пользователь не вступил |
| `Mute` / `Unmute` | пользователь уже подписан |
| `Search` | для всех каналов и гигагрупп |
| `Send a Gift` | только если сервер отдал `stargifts_available` |
| `Direct Messages` / «Предложить пост» | `broadcast_messages_allowed && linked_monoforum_id != 0` |
| Информация о гигагруппе | только для gigagroup |

### 1.3 Меню «⋮» чата

| Раздел | Кнопка | Канал | Группа |
|---|---|---|---|
| Уведомления | Mute / Notification settings | да | да |
| Навигация | `Direct Messages` | при `linked_monoforum_id` | — |
| Навигация | `Search` | да | да |
| Навигация | `View as topics` | если доступны темы | если доступны темы |
| Продвижение | `Boost Channel` / `Boost Group` | по premium/бустам/админке | по premium/бустам/админке |
| Инструменты | `Translate` | да | да |
| Модерация | `Report` | не админу | не админу |
| Внешний вид | `Set Wallpaper` | да | да |
| Очистка | `Clear history` | да, не в теме | да, не в теме |
| Выход | `Leave channel` / `Leave Group` / `Delete and leave group` | да | да |
| Темы | `All Topics` | в режиме темы | в режиме темы |
| Темы | `Close topic` | в открытой теме, если есть право | в открытой теме, если есть право |

WaysGram-пунктов в этом меню больше нет.

### 1.4 Контекстное меню сообщения/поста

| Раздел | Кнопки |
|---|---|
| Буфер/ответ | `Copy`, `Reply`, `Forward`, `Quote` |
| Редактирование | `Edit`, `Reschedule` (только отложенные посты) |
| Закрепление | `Pin`, `Unpin` |
| Перевод | `Translate` |
| Навигация | `View replies`, `View thread`, `View in topic`, `Copy link` |
| Модерация | `Report`, `Block user`, `Add Fact Check` / `Edit Fact Check` |
| Удаление | `Delete` / `Remove` |
| Медиа | `Save to Gallery`, `Save to Downloads`, `Save to Music`, `Save to GIFs`, `Share` |
| Опросы | `View statistics`, `Unvote`, `Stop poll`, `Stop quiz`, `Edit to-do`, `Add tasks` |
| Рекламные посты | `Hide ad`, `About revenue sharing ads`, `Report ad`, `Remove ads` |
| Стикеры | `Add to Stickers`, `Add to Masks`, `Add to Favorites`, `Delete from Favorites` |

Реакции над сообщением — стоковый чип с существующими эмодзи; нажатие ставит/снимает реакцию,
список поставивших открывается по стоковому правилу.

### 1.5 Композер

Стоковый набор: вложение, текстовое поле, эмодзи, стикеры/GIF, голосовая/видеозапись, отправка,
отложенная отправка, панель ответа/цитаты. WaysGram-кнопок в нижней панели каналов и групп нет.

## 2. Карточка канала/группы (`ProfileActivity`)

### 2.1 Шапка

| Кнопка | Условие/назначение |
|---|---|
| `Search` | поиск по чату |
| Voice chat / Live stream | по `ChatObject.canManageCalls` |
| Карандаш `Edit` | по праву изменения информации |
| «⋮» | меню карточки |

### 2.2 Меню «⋮» — broadcast-канал

| Раздел | Кнопка | Условие |
|---|---|---|
| Связь | `Start Live stream` | владелец/админ с правом звонков |
| Управление | `Auto-Delete` | по праву изменения информации |
| Статистика | `Statistics` | `can_view_stats` / `can_view_revenue` / `can_view_stars_revenue` / истории |
| Подарки | `Send a Gift` | `stargifts_available` |
| Обсуждение | `View discussion` | `linked_chat_id != 0` |
| Публичность | `Share` | публичный канал |
| быстрый доступ | `Add to Home screen` | не тема |
| Разрушающие | `Leave Channel` / `Delete Channel` | создатель или участник |

### 2.3 Меню «⋮» — группа/супергруппа

| Раздел | Кнопка | Условие |
|---|---|---|
| Связь | `Start Voice chat` | `ChatObject.canManageCalls` |
| Управление | `Auto-Delete` | по праву изменения информации |
| Статистика | `Statistics` | те же стоковые флаги |
| Истории | `Open Channel Archive Stories` | `canPostStories` / `canEditStories` |
| Участники | `Search members` | участники видимы или админ |
| Темы | `Delete topic` | режим темы + право удаления |
| быстрый доступ | `Add to Home screen` | не тема |
| Разрушающие | `Leave Group` / `Delete Group` / `Delete and exit group` | создатель или участник |

`Send a Gift` и `View discussion` относятся только к не-мегагрупповому каналу.

## 3. Экран «Изменить» (`ChatEditActivity`)

### 3.1 Шапка

| Кнопка | Назначение |
|---|---|
| Аватар | `Set Photo or Video` / `Set New Photo` |
| `Done` / `Save` в шапке | сохранение изменений |

### 3.2 Раздел «Тип и ссылки» (`typeEditContainer`)

| Кнопка | Канал | Группа |
|---|---|---|
| `Location` | — | только мегагруппа |
| `Channel Type` / `Group Type` | да | да |
| `Discussion` / `Linked Channel` | да, по праву изменения/закрепления | да, для мегагруппы с темами |
| `Direct Messages` (post suggestions) | broadcast-канал с правом изменения | — |
| Изменение цвета имени | broadcast-канал | супергруппа админу |
| `Auto-Translate Messages` | broadcast-канал, не community | — |
| `Chat history for new members` | по праву блокировки | да |
| `Topics` | — | создатель группы |

### 3.3 Раздел «Информация и права» (`infoContainer`)

Порядок для **канала**: `Reactions`, `Welcome Message`, `Administrators`, `Subscribers`,
`Invite Links`, `Removed Users`, `Statistics and Boosts`, `Recent Actions`, `Affiliate Programs`.

Порядок для **группы**: `Reactions`, `Welcome Message`, `Permissions`, `Invite Links`,
`Administrators`, `Members`, `Member Requests`, `Removed Users`, `Recent Actions`,
`Group Stickers`.

| Кнопка | Условие |
|---|---|
| `Reactions` | всегда у админа |
| `Welcome Message` | право `MANAGE_WELCOME` |
| `Administrators` | админ |
| `Subscribers` / `Members` | админ; для канала показывается счётчик |
| `Member Requests` | если `requests_pending > 0` |
| `Invite Links` | админ |
| `Permissions` / `Removed Users` | по правам и типу чата |
| `Statistics and Boosts` | broadcast-канал, не community |
| `Recent Actions` | канал/мегагруппа |
| `Affiliate Programs` | создаётся скрытой, показывается по флагу Premium |
| `Group Stickers` | группа, по наличию набора |

### 3.4 Разрушающий раздел

| Кнопка | Где |
|---|---|
| `Delete Channel` | канал |
| `Delete and Leave Group` | группа |

## 4. Что именно исчезло по сравнению с прошлым состоянием

Эти кнопки WaysGram больше **не отображаются** в каналах и группах:

* `Розыгрыши`, `Сообщения канала`, `Написать каналу`, `Темы канала`, `Payment history`, `Бусты`;
* `Истории группы`, `Отправить от имени…`, `Темы`, `Бусты` в меню группы;
* `Платные реакции`, `Группа обсуждений` в меню поста;
* `Медленный режим` в меню карточки группы;
* `Платные реакции`, `Статистика канала`, `Настройки входящих канала`, `Передать канал`,
  `Темы канала`, `Платная подписка` на экране «Изменить» канала;
* `Ограничения отправки`, `Список участников`, `Статистика группы` на экране «Изменить» группы.

Вместо них работают стоковые кнопки из раздела 1–3. Служебные диалоги WaysGram остались в коде,
но в интерфейсе каналов и групп у них больше нет кнопок-входов.
