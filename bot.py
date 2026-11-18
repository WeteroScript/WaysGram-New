"""Entry point that runs the AuthBot stored in `authbot/` of this repository.

WaysGram-New is the source of truth for the bot code, so this file needs no network access: it
puts `authbot/` on `sys.path` and executes `authbot/bot.py` as `__main__`.

Two ways to deploy:

* BotHost points at **WaysGram-AuthBot** (recommended, the panel keeps a small repository):
  that repository runs the launcher from `deploy/authbot-bridge/bot.py`, which pulls `authbot/`
  from here and starts it;
* BotHost points at **this** repository: the panel starts this file and the bot runs straight
  from the committed `authbot/` folder.

Diagnostics: `python bot.py --check` prints what would be executed and starts nothing.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
AUTHBOT_DIR = os.path.join(ROOT, "authbot")
AUTHBOT_ENTRY = os.path.join(AUTHBOT_DIR, "bot.py")


def main() -> int:
    parser = argparse.ArgumentParser(description="WaysGram AuthBot")
    parser.add_argument("--check", action="store_true",
                        help="print the entry point without starting the bot")
    args = parser.parse_args()

    if not os.path.isfile(AUTHBOT_ENTRY):
        print(f"AuthBot sources are missing: {AUTHBOT_ENTRY}", file=sys.stderr)
        return 2

    if args.check:
        print(json.dumps({
            "source": AUTHBOT_DIR,
            "entry": AUTHBOT_ENTRY,
        }, ensure_ascii=False, indent=2))
        return 0

    if AUTHBOT_DIR not in sys.path:
        sys.path.insert(0, AUTHBOT_DIR)

    import runpy

    runpy.run_path(AUTHBOT_ENTRY, run_name="__main__")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
