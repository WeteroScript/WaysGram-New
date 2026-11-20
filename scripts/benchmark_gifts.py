#!/usr/bin/env python3
"""Synthetic local benchmark, not a production or device/network measurement.

Run from any directory with the backend test dependencies installed:
    python scripts/benchmark_gifts.py
Creates only a temporary test database. Does not use real credentials or Telegram.
"""
import asyncio
from pathlib import Path
import statistics
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "authbot"))
sys.path.insert(0, str(ROOT / "authbot" / "tests"))

import pytest
import db_split
import test_star_gifts as fixtures


async def benchmark(app):
    async with await fixtures.client_for(app) as client:
        for endpoint in (
            "/api/users/102/gifts?limit=200",
            "/api/telegram/gifts?all=1",
            "/api/messages?with=102",
        ):
            times = []
            for _ in range(6):
                started = time.perf_counter()
                response = await client.get(endpoint, headers=fixtures.headers(101))
                elapsed = (time.perf_counter() - started) * 1000
                if response.status_code != 200:
                    raise RuntimeError(f"{endpoint}: HTTP {response.status_code}")
                times.append(elapsed)
            print(f"{endpoint}: median={statistics.median(times[1:]):.2f} ms "
                  f"(five runs after warm-up: {[round(t, 2) for t in times[1:]]})")


def main():
    with tempfile.TemporaryDirectory(prefix="waysgram-benchmark-") as directory:
        monkeypatch = pytest.MonkeyPatch()
        try:
            path, app = fixtures.env.__wrapped__(Path(directory), monkeypatch)
            with db_split.sqlite_connect(path) as db:
                db.executemany(
                    "INSERT INTO user_gifts(serial_number,gift_name,owner_telegram_id,acquired_at,purchase_price) "
                    "VALUES(?,?,?,?,?)",
                    [(f"WG-{index:012X}", "Подарок", 102, 1800000000, 50) for index in range(200)],
                )
                db.executemany(
                    "INSERT INTO gift_transactions(event_type,serial_number,gift_name,from_telegram_id,to_telegram_id,created_at) "
                    "VALUES('transfer',?,'Подарок',101,102,1800000000)",
                    [(f"WG-{index:012X}",) for index in range(200)],
                )
                db.commit()
            asyncio.run(benchmark(app))
        finally:
            monkeypatch.undo()


if __name__ == "__main__":
    main()
