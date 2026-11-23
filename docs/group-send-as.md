# Отправка от имени группы и канала (`channels.getSendAs`)

Кнопка «Отправитель» в поле ввода группы появляется только после ответа на
`channels.getSendAs`. Раньше метода не было в транспорте: `MessagesController.getSendAsPeers`
получал `METHOD_NOT_SUPPORTED`, `SenderSelectPopup` не открывался, а если отправитель всё же
выбирался, поле `send_as` в `messages.sendMessage`/`sendMedia` просто игнорировалось — сообщение
уходило от аккаунта.

## Что теперь

**Сервер** (всё уже было, добавлено только ослабление проверки):

* `GET /api/groups/{id}/send-identities` — список отправителей для вызывающего:
  `anonymous_allowed` (владелец или админ с включённой анонимностью), `title` группы,
  `channels` — публичные каналы, которыми владеет пользователь (до 100, без забаненных),
  `admins` — для владельца группы.
* `POST /api/groups/{id}/identity-messages` с `send_as_id`:
  `0` — от имени группы, `> 0` — от имени своего публичного канала. Теперь разрешено и с
  вложением (`media_id`, карточка, контакт, геометка): `send_as_id` лежит в той же строке
  `group_messages`, что и медиа. Запрещены только треды комментариев и топики — там отправитель
  фиксирован, `send_as_id` в них даёт `400 INVALID_GROUP_SEND_IDENTITY`.

**Клиент**:

* `WaysGramChats.sendAsPeers(account, groupTgId, selfId)` собирает `TL_channels_sendAsPeers`:
  `TL_sendAsPeer` + `TL_peerChat` для самой группы и `TL_sendAsPeer` + `TL_peerChannel` для
  каждого канала; `chats` заполняется `toMegagroup`/`toChannel`, чтобы `SenderSelectPopup`
  показывал аватар и название без дополнительного запроса.
* `WaysGramTransport` отвечает на `TL_channels_getSendAs`; `for_live_stories` и каналы
  (не группы) получают пустой список — в историях и постах канала отправитель один.
* `MessagesController.getSendAsPeers` и `ChatActivity.loadSendAsPeers` больше не блокируют
  приватную WaysGram-группу: гейт `ChatObject.canSendAsPeers` требует публичности/ссылки,
  а сервер сам решает, кому можно писать от имени группы.
* Выбранный отправитель доходит до отправки: `sendAsIdentity(send.send_as, send.peer)`
  превращает `TL_inputPeerChat` группы в `0`, а канал — в `channelServer(channel_id)`.
  Значение протаскивается через перегрузки `sendTo` в `WaysGramChats.send`, который при
  `identity >= 0` уходит на `/identity-messages` с `send_as_id` и прежним `Idempotency-Key`.
* Геолокация и опросы по-прежнему требуют обычного отправителя
  (`usesDefaultLocationSender`): их бридж не умеет альтернативного автора, и это поведение
  зафиксировано статическим тестом.

## Проверки

* `authbot/tests/test_group_send_as.py` — 3 сценария: список отправителей (только свои публичные
  каналы, анонимность админа), отправка текста и фото от имени канала + отказ чужому каналу и
  обычному участнику, запрет `send_as_id` в треде комментариев.
* `scripts/test_android_group_send_as.py` — 5 статических проверок исходников (диспетчер
  `channels.getSendAs`, сборка `TL_channels_sendAsPeers`, проброс `identity`, гейт приватной
  группы, сохранение ограничения для геолокации/опросов).

APK не собирался, на устройстве не проверялось.
