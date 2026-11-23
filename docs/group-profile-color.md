# Цвет профиля группы (`channels.updateColor` для мегагруппы)

В Telegram цвет аватара и профиля группы меняется тем же `channels.updateColor`, что и у
канала. Транспорт отправлял его в `WaysGramChats.updateChannelSettings`, то есть на
`/api/channels/{id}`. У мегагруппы такого канала нет (`id < CHANNEL_OFFSET`, `channelServer`
даёт отрицательное число) — запрос получал 404, цвет не сохранялся, а поля цвета в настройках
группы сервер вообще не принимал.

## Что теперь

**Сервер**

* Миграция `chat_groups`: `color_id`, `background_emoji_id`, `profile_color_id`,
  `profile_background_emoji_id` (по аналогии с `channels`).
* `PUT /api/groups/{id}/settings` принимает эти четыре поля с теми же проверками, что и канал:
  цвет 0…7, id эмодзи ≥ 0, иначе `400 INVALID_PEER_COLOR`. Право — 1 (`change_info`).
* `GET /api/groups/{id}` возвращает их всем участникам, поэтому цвет видят и те, кто не админ.

**Клиент**

* `WaysGramTransport` разбирает `TL_channels_updateColor` и при `isGroupChannel(id)` вызывает
  `updateGroupSettings` вместо `updateChannelSettings`; флаг `for_profile` по-прежнему
  переключает `color_id` ↔ `profile_color_id`.
* Отдельный маппинг не понадобился: `toMegagroup` уже вызывает `applyChannelFlags`, а тот
  собирает `TL_peerColor` из `color_id`/`background_emoji_id` и `profile_color_id`/
  `profile_background_emoji_id` (`TLObject.FLAG_7` и `FLAG_8`). Раньше сервер просто не отдавал
  эти поля для групп.

## Проверки

* `authbot/tests/test_group_profile_color.py` — 2 сценария: сохранение и чтение цвета
  участником, смена админом с правом 1, отказ обычному участнику, валидация (8, `"3"`, −1, −5,
  `"x"`) и отсутствие смешения цвета канала и группы.
* `scripts/test_android_group_profile_color.py` — 2 статические проверки: маршрутизация по
  диапазону id и маппинг `TL_peerColor` в `applyChannelFlags`.

Сборка: ветка собирается workflow-ом «Build Android APK» (run 310, commit 8496dba; артефакт `WaysGram-New-APK-310`, sha256 APK лежит рядом с ним). На устройстве не проверялось.
