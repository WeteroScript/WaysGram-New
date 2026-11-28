#!/usr/bin/env python3
"""Вотчер TON-депозитов WaysGram.

Опрашивает toncenter по адресу сервисного кошелька и пересылает найденные
входящие переводы в ``POST /api/internal/ton/deposits``. Состояние не хранится:
повторная обработка безопасна, потому что сервер принимает только новый
``tx_hash`` (``ton_deposits.tx_hash`` уникален).

Пример запуска (отдельным процессом или из cron/systemd timer):

    WAYSGRAM_TON_ADDRESS=UQ... \
    WAYSGRAM_TON_WATCHER_SECRET=... \
    WAYSGRAM_SERVER_URL=https://api.example.com \
    python3 scripts/ton_watcher.py --interval 20

Переменные окружения:

* ``WAYSGRAM_TON_ADDRESS`` — адрес сервисного кошелька (обязательно);
* ``WAYSGRAM_TON_WATCHER_SECRET`` — тот же секрет, что в окружении сервера;
* ``WAYSGRAM_SERVER_URL`` — базовый URL API WaysGram;
* ``TONCENTER_URL`` — по умолчанию ``https://toncenter.com/api/v2``
  (можно подставить свой toncenter с API-ключом в ``TONCENTER_API_KEY``).

Скрипт ничего не подписывает и не выводит деньги: приём TON — это только
пополнение звёзд. Вывод обслуживает отдельная очередь ``ton_payouts``.
"""

from __future__ import annotations

import argparse
import os
import sys
import time

import httpx

MEMO_PREFIX = "wg:"


def incoming_transfers(payload: dict) -> list[dict]:
    """Достаёт из ответа toncenter входящие переводы с нашим memo."""
    result: list[dict] = []
    for tx in payload.get("result") or []:
        in_msg = tx.get("in_msg") or {}
        if not in_msg:
            continue
        value = in_msg.get("value")
        comment = (in_msg.get("message") or "").strip()
        if value is None or not comment.startswith(MEMO_PREFIX):
            continue
        tx_hash = tx.get("transaction_id", {}).get("hash") or in_msg.get("hash")
        if not tx_hash:
            continue
        result.append({
            "memo": comment,
            "tx_hash": str(tx_hash),
            "amount_nanoton": str(int(value)),
        })
    return result


def poll_once(client: httpx.Client, api_url: str, address: str,
              server_url: str, secret: str) -> int:
    response = client.get(
        f"{api_url.rstrip('/')}/getTransactions",
        params={"address": address, "limit": 100},
    )
    response.raise_for_status()
    credited = 0
    for transfer in incoming_transfers(response.json()):
        posted = client.post(
            f"{server_url.rstrip('/')}/api/internal/ton/deposits",
            json=transfer,
            headers={"X-WaysGram-Ton-Secret": secret},
        )
        if posted.status_code == 200:
            credited += 1
            print(f"зачислено {transfer['memo']}: {transfer['amount_nanoton']} наноTON", flush=True)
        elif posted.status_code == 409:
            continue  # уже зачисляли этот перевод
        else:
            print(f"отказ {posted.status_code}: {posted.text[:200]}", flush=True)
    return credited


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interval", type=int, default=20, help="пауза между опросами, секунды")
    parser.add_argument("--once", action="store_true", help="один проход и выход")
    options = parser.parse_args()

    address = os.getenv("WAYSGRAM_TON_ADDRESS", "").strip()
    secret = os.getenv("WAYSGRAM_TON_WATCHER_SECRET", "").strip()
    server_url = os.getenv("WAYSGRAM_SERVER_URL", "").strip()
    api_url = os.getenv("TONCENTER_URL", "https://toncenter.com/api/v2").strip()
    api_key = os.getenv("TONCENTER_API_KEY", "").strip()
    if not address or not secret or not server_url:
        print("нужны WAYSGRAM_TON_ADDRESS, WAYSGRAM_TON_WATCHER_SECRET и WAYSGRAM_SERVER_URL",
              file=sys.stderr)
        return 2

    headers = {"X-API-Key": api_key} if api_key else {}
    with httpx.Client(timeout=httpx.Timeout(20.0), headers=headers) as client:
        while True:
            try:
                poll_once(client, api_url, address, server_url, secret)
            except Exception as error:  # noqa: BLE001 - вотчер не должен падать от сети
                print(f"ошибка опроса: {type(error).__name__}: {error}", file=sys.stderr, flush=True)
            if options.once:
                return 0
            time.sleep(max(5, options.interval))


if __name__ == "__main__":
    raise SystemExit(main())
