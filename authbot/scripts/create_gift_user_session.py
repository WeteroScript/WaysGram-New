#!/usr/bin/env python3
"""Create a dedicated Telegram user session locally; never send it to chat or Git."""
from __future__ import annotations

import argparse
import asyncio
import os
from pathlib import Path

from telethon import TelegramClient
from telethon.sessions import StringSession


async def create(output: Path, api_id: int, api_hash: str) -> None:
    # Fail before login if the output exists; never overwrite an active session.
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    saved = False
    try:
        client = TelegramClient(StringSession(), api_id, api_hash)
        try:
            # Telegram's interactive login runs only in the operator's own terminal.
            await client.start()
            identity = await client.get_me()
            if identity is None or getattr(identity, "bot", False):
                raise RuntimeError("A user account is required, not a bot")
            session = client.session.save()
            with os.fdopen(fd, "w", encoding="utf-8") as file:
                fd = -1
                file.write(session)
                file.write("\n")
            saved = True
        finally:
            await client.disconnect()
    finally:
        if fd >= 0:
            os.close(fd)
        if not saved:
            output.unlink(missing_ok=True)
    print(f"Session saved securely to {output}. Treat it as full access to the Telegram account.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="new private .session file")
    args = parser.parse_args()
    try:
        api_id = int(os.environ.get("TELEGRAM_API_ID", "0"))
    except ValueError:
        api_id = 0
    api_hash = os.environ.get("TELEGRAM_API_HASH", "").strip()
    if api_id <= 0 or not api_hash:
        parser.error("set TELEGRAM_API_ID and TELEGRAM_API_HASH in your local environment")
    if args.output.suffix != ".session":
        parser.error("output must end in .session so Git ignores it")
    asyncio.run(create(args.output, api_id, api_hash))


if __name__ == "__main__":
    main()
