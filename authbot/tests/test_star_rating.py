import time

import db_split
from star_rating import PENDING_SECONDS, rating_value
from test_messenger_api import client_for, run
from test_star_gifts import env, headers  # noqa: F401


def test_rating_thresholds_and_cap():
    assert rating_value(-9)['level'] == -1
    assert rating_value(4999)['level'] == 1
    assert rating_value(5000)['level'] == 2
    assert rating_value(12000)['level'] == 3
    assert 'next_level_stars' not in rating_value(1_000_000)


def test_rating_from_ledger_and_pending_privacy(env):
    path, app = env
    now = int(time.time())
    old = now - PENDING_SECONDS - 100
    pending = now - PENDING_SECONDS + 3600
    with db_split.sqlite_connect(path) as db:
        db.executemany('''INSERT INTO economy_transactions
            (telegram_id,asset,amount,balance_after,reason,admin_id,created_at)
            VALUES(101,?,?,0,?,?,?)''', [
            ('stars', -5000, 'telegram_gift:1', None, old),
            ('stars', -100, 'stars_paid_message:1', None, old),
            ('stars', -100, 'gift_purchase:2', None, pending),
            ('stars', 100, 'gift_sell:1', None, now),
            ('stars', 5, 'stars_purchase_refund:1', None, now),
            ('grams', -9000, 'gift_purchase:1', None, old),
            ('stars', -9000, 'gift_purchase:1', 1, old),
            ('stars', -9000, 'auction_bid:1', None, old),
        ])
        db.commit()

    async def scenario():
        async with await client_for(app) as client:
            me = await client.get('/api/me', headers=headers(101))
            assert me.status_code == 200, me.text
            data = me.json()
            assert data['stars_rating']['stars'] == 4885
            assert data['stars_my_pending_rating']['stars'] == 4985
            assert data['stars_my_pending_rating_date'] == pending + PENDING_SECONDS
            for viewer in (101, 102):
                response = await client.get('/api/users/101', headers=headers(viewer))
                assert response.status_code == 200, response.text
                profile = response.json()
                assert profile['stars_rating'] == data['stars_rating']
                assert ('stars_my_pending_rating' in profile) == (viewer == 101)

    run(scenario())


def test_other_users_see_level_zero_before_earnings_mature(env):
    path, app = env
    with db_split.sqlite_connect(path) as db:
        db.execute("""INSERT INTO economy_transactions
            (telegram_id,asset,amount,balance_after,reason,created_at)
            VALUES(101,'stars',-50,0,'telegram_gift:1',?)""", (int(time.time()),))
        db.commit()

    async def scenario():
        async with await client_for(app) as client:
            other = await client.get('/api/users/101', headers=headers(102))
            assert other.status_code == 200, other.text
            assert other.json()['stars_rating'] == rating_value(0)
            assert 'stars_my_pending_rating' not in other.json()
            me = (await client.get('/api/me', headers=headers(101))).json()
            assert me['stars_rating'] == rating_value(0)
            assert me['stars_my_pending_rating']['stars'] == 50

    run(scenario())
