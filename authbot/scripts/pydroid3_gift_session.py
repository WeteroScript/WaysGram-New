"""Create a Telegram user StringSession for WaysGram in Pydroid 3.

Install Telethon in Pydroid's Pip first. Run this script privately: the session
file grants full access to the Telegram account. Never send it to chat or Git.
"""

import asyncio
import os
from pathlib import Path

try:
    from telethon import TelegramClient
    from telethon.sessions import StringSession
except ImportError:
    print("Telethon не установлен. Установите его через Pip в Pydroid 3: telethon>=1.45,<2")
    raise SystemExit(1)


OUTPUT = Path("/storage/emulated/0/Download/waysgram-gift.session")


async def create_session(api_id, api_hash, output=OUTPUT):
    # Reserve the output before login, without overwriting an existing session.
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(output, flags, 0o600)
    saved = False
    client = None
    try:
        client = TelegramClient(StringSession(), api_id, api_hash)
        # Telethon's normal interactive Telegram login takes place on this phone.
        await client.start()
        identity = await client.get_me()
        if identity is None or getattr(identity, "bot", False):
            raise ValueError("Нужен аккаунт пользователя Telegram, а не бот")
        session = client.session.save()
        if not session:
            raise ValueError("Не удалось сохранить сессию")
        with os.fdopen(fd, "w", encoding="utf-8") as file:
            fd = -1
            file.write(session + "\n")
        saved = True
    finally:
        if client is not None:
            try:
                await client.disconnect()
            except Exception:
                pass
        if fd >= 0:
            os.close(fd)
        if not saved:
            output.unlink(missing_ok=True)


def main():
    print("Создание сессии Telegram для загрузки моделей подарков WaysGram")
    print("Используйте отдельный аккаунт. Не отправляйте никому данные из этого окна.")
    print("Нужны те же TELEGRAM_API_ID и TELEGRAM_API_HASH, что настроены на BotHost.")
    print("API hash будет виден при вводе: убедитесь, что экран никто не видит.\n")
    try:
        api_id = int(input("TELEGRAM_API_ID: ").strip())
        api_hash = input("TELEGRAM_API_HASH: ").strip()
        if api_id <= 0 or not api_hash:
            raise ValueError("Не указаны API ID и API hash")
        asyncio.run(create_session(api_id, api_hash))
    except FileExistsError:
        print("\nФайл уже существует и не был перезаписан:", OUTPUT)
        print("Если нужна новая сессия, сначала безопасно удалите или переименуйте старый файл.")
        return
    except PermissionError:
        print("\nНет доступа к папке Download. Разрешите Pydroid 3 доступ к файлам и повторите.")
        return
    except (KeyboardInterrupt, EOFError):
        print("\nВход отменён. Новый файл сессии не сохранён.")
        return
    except Exception as error:
        # Telegram exceptions can contain sensitive details; do not print their messages.
        print("\nНе удалось создать сессию. Тип ошибки:", type(error).__name__)
        print("Проверьте API ID, API hash, подключение к интернету и доступ к папке Download.")
        return

    print("\nГотово. Файл сессии:", OUTPUT)
    print("Откройте файл у себя и скопируйте его содержимое в значение переменной")
    print("WAYSGRAM_GIFT_USER_SESSION на BotHost. Не присылайте его в чат.")
    print("Папка Download — общая: после переноса удалите файл и очистите буфер обмена.")


if __name__ == "__main__":
    main()
