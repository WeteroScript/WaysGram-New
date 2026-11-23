# Передача владения каналом

Штатный путь Telegram (`channels.editCreator`) требует SRP-подтверждения: `ChatRightsEditActivity`
и `SelectChatUserSheet` отправляют запрос с `TL_inputCheckPasswordEmpty`, ждут
`PASSWORD_HASH_INVALID`, открывают `TwoStepVerificationActivity` и считают SRP по
`account.getPassword`. В WaysGram SRP нет — облачный пароль проверяется на сервере
(PBKDF2, `/api/me/cloud-password`), поэтому нативный экран передачу выполнить не мог.

## Что теперь

**Сервер**

* `GET /api/channels/{id}/transfer` — только владельцу (`403 CHANNEL_OWNER_REQUIRED`).
  Возвращает `password_required` (включён ли облачный пароль) и `candidates` — действующие
  администраторы канала (кроме владельца, без забаненных), с `telegram_id`, `username`,
  `display_name`, `avatar_media_id`.
* `POST /api/channels/{id}/transfer` с `{"new_owner_id": …, "password": "…"}`:
  * `new_owner_id` ≠ текущий владелец, аккаунт существует и не забанен;
  * новый владелец обязан быть администратором (`400 NEW_OWNER_MUST_BE_ADMIN`) — как в Telegram;
  * если у владельца включён облачный пароль: без пароля `401 PASSWORD_MISSING`, неверный —
    `403 PASSWORD_HASH_INVALID`, верный — PBKDF2-сверка;
  * в одной транзакции: `channels.owner_telegram_id` меняется, новый владелец удаляется из
    `channel_admins`, прежний остаётся админом с маской 127, в `channel_admin_log` пишется
    `transfer_owner` с `{from, to}`.

**Клиент**

* Новый помощник `WaysGramChannelTransfer`: `GET` списка кандидатов → диалог выбора →
  подтверждение (при необходимости с полем облачного пароля) → `POST`. После успеха
  показывается «Владение каналом передано» и вызывается `loadFullChat`, чтобы обновился профиль.
* В настройках канала у владельца появилась строка «Передать канал» (иконка `msg_admins`),
  рядом с «Входящими канала». Строки локализованы в `values` и `values-ru`
  (`WaysGramTransferChannel`, `WaysGramTransferChoose`, `WaysGramTransferConfirm`,
  `WaysGramTransferPassword`, `WaysGramTransferDone`, `WaysGramTransferError`,
  `WaysGramTransferNoAdmins`).
* `channels.editCreator` в транспорте больше не «не поддерживается»: возвращается понятная
  ошибка «Передача канала доступна в настройках канала WaysGram», чтобы пользователь не искал
  нерабочий нативный экран.

## Ограничения

* Передать канал может только действующий администратор — как в Telegram; если админов нет,
  диалог просит сначала добавить администратора.
* Второй фактор — облачный пароль WaysGram. Если он не включён, передача подтверждается
  только диалогом (в Telegram в этом случае тоже достаточно подтверждения).
* APK не собирался, на устройстве не проверялось.

## Проверки

* `authbot/tests/test_channel_transfer.py` — 3 сценария: список кандидатов и смена владельца
  (включая отказы: не владелец, не админ, передача самому себе), требование облачного пароля
  (нет пароля / неверный / верный), запись `transfer_owner` в журнал канала.
* `scripts/test_android_channel_transfer.py` — 4 статические проверки: понятная ошибка вместо
  заглушки, REST-вызовы помощника, строка в настройках, локализация строк.
