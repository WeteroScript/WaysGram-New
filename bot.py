"""Entry point for the WaysGram AuthBot inside the WaysGram-New monorepo.

BotHost (and similar hosts) expect a single start file in the repository root, while the bot
itself lives in ``authbot/``. This shim puts that directory on ``sys.path`` so the bot's flat
imports (``messenger_api``, ``db_split``, ...) keep working, then executes ``authbot/bot.py``
exactly as if it were started directly.
"""

import os
import runpy
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
AUTHBOT_DIR = os.path.join(ROOT, "authbot")
AUTHBOT_ENTRY = os.path.join(AUTHBOT_DIR, "bot.py")

if AUTHBOT_DIR not in sys.path:
    sys.path.insert(0, AUTHBOT_DIR)

if __name__ == "__main__":
    if not os.path.isfile(AUTHBOT_ENTRY):
        raise SystemExit(f"AuthBot sources are missing: {AUTHBOT_ENTRY}")
    runpy.run_path(AUTHBOT_ENTRY, run_name="__main__")
