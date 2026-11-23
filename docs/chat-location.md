# Геолокация канала и группы (`channels.editLocation`)

`TL_channels_editLocation` входил в список заглушек транспорта: запрос молча получал
`TL_boolTrue`. `LocationActivity` показывал «Геолокация: <адрес>», потому что экран правил
`chatInfo.location` в памяти, но после перезагрузки профиля точка исчезала — сервер её не хранил.

## Что теперь

**Сервер**

* Миграция: у `channels` и `chat_groups` появились `location_latitude`, `location_longitude`,
  `location_label`, `location_address`.
* `PUT /api/channels/{id}/location` и `PUT /api/groups/{id}/location` принимают
  `{"latitude": …, "longitude": …, "label": "…", "address": "…"}`; `DELETE` по тому же пути
  очищает точку. Широта проверяется в диапазоне −90…90, долгота −180…180, подпись ≤ 64,
  адрес ≤ 128 символов, иначе `400 LOCATION_INVALID`.
* Права: канал — владелец или админ с правом `change_info` (бит 64), иначе
  `403 CHANNEL_ADMIN_REQUIRED`; группа — право 1 (`группы`), иначе `403 GROUP_ADMIN_REQUIRED`.
* `GET /api/channels/{id}` и `GET /api/groups/{id}` возвращают `location` (или `null`) и
  `can_set_location` для вызывающего. Точка видна всем, кто видит чат.

**Клиент**

* `WaysGramTransport` разбирает `TL_channels_editLocation` и вызывает
  `WaysGramChats.updateLocation`: путь выбирается по диапазону id — группа (`id < CHANNEL_OFFSET`)
  уходит на `/api/groups/{id}/location`, канал — на `/api/channels/{id - CHANNEL_OFFSET}/location`.
* `WaysGramChats.chatLocation(JSONObject)` превращает `location` в `TL_channelLocation`
  (`TL_geoPoint` + подпись, при её отсутствии — адрес). Маппинг добавлен и в
  `fullChannel` (каналы), и в `fullGroup` (мегагруппы), вместе с `can_set_location`
  (`TLObject.FLAG_16`).
* `ChatEditActivity` показывает строку геолокации не только мегагруппе, но и каналу при
  включённом WaysGram-транспорте: `LocationActivity` для канала отправляет тот же
  `channels.editLocation`, поэтому отдельного экрана не потребовалось. Просмотр на карте и
  адрес в профиле уже работали через `chatInfo.location`.

## Проверки

* `authbot/tests/test_chat_location.py` — 2 сценария: канал (владелец, подписчик, админ с
  правом и без, изменение, очистка, невалидные координаты) и группа (право 1, видимость для
  участника, очистка).
* `scripts/test_android_channel_location.py` — 4 статические проверки исходников (заглушка
  убрана, путь по типу чата, маппинг в `TL_channelFull`, строка в настройках канала).

APK не собирался, на устройстве не проверялось.
