# Анимированные эмодзи реакций: как их получить

Настоящие документы реакций (`static_icon`, `appear/select/activate/effect/around_animation`,
`center_icon`) Telegram выдаёт через `messages.getAvailableReactions`, а сами файлы лежат на
серверах Telegram. В публичных репозиториях их нет: `ilyhalight/telegram-emoji-effects` —
только эффекты (салют/конфетти), `tdesktop` и Telegram Android анимированные эмодзи не хранят.
Поэтому набор выгружается один раз из сессии, которая уже подключена в authbot.

## Выгрузка (одна команда в боте)

1. Бот должен быть запущен с MTProto-доступом: `TELEGRAM_API_ID`, `TELEGRAM_API_HASH` и
   `WAYSGRAM_GIFT_USER_SESSION` (та же сессия, что качает модели подарков).
2. В личке с ботом от админа отправить:

   ```
   /exportreactions
   ```

3. Бот ответит «Собираю документы реакций…», затем пришлёт
   `waysgram_reaction_emoji.zip` (≈15–25 МБ): `manifest.json` + `files/<id>.tgs|webp`.

Права: команду видит только админ в приватном чате (`is_authbot_admin` + `is_private_admin_chat`),
как и `/admin`. При ошибке бот покажет текст, а не «что-то пошло не так».

## Что внутри архива

`manifest.json`:

```json
{
  "exported_at": 1735000000,
  "count": 51,
  "slots": ["static_icon", "appear_animation", "select_animation", "activate_animation",
            "effect_animation", "around_animation", "center_icon"],
  "reactions": [
    {"emoji": "🎉", "title": "Party Popper", "inactive": false,
     "documents": {"appear_animation": {"id": 5046509860389126442, "dc_id": 2,
                    "mime_type": "application/x-tgsticker", "size": 47557,
                    "file": "files/5046509860389126442.tgs"}}}
  ],
  "files": 240,
  "bytes": 17123456
}
```

Один документ может стоять в нескольких слотах — файл выгружается один раз (`files`).

## Куда это попадает

* в APK: `assets/waysgram_emoji/tg/<id>.tgs` + таблица `WaysGramEmojiTable`, документы
  раздаёт `WaysGramAnimatedEmoji` через `upload.getFile`;
* либо на сервер WaysGram (вариант «без пересборки»): authbot сохраняет файлы, клиент тянет
  каталог и сами документы по требованию.

Тесты выгрузки: `authbot/tests/test_reaction_export.py` (3).
