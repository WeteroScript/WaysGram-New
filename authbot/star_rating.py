"""WaysGram's server-side Stars rating policy, independent of wallet balance."""
from bisect import bisect_right

PENDING_SECONDS = 21 * 24 * 60 * 60
# The client receives thresholds from the server. Beyond the observed early
# Telegram levels, these are WaysGram policy, not Telegram's private ladder.
LEVEL_THRESHOLDS = (0, 5_000, 12_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000)


def rating_value(points: int) -> dict[str, int]:
    if points < 0:
        return {"level": -1, "stars": points, "current_level_stars": points, "next_level_stars": 0}
    if points == 0:
        return {"level": 0, "stars": 0, "current_level_stars": 0, "next_level_stars": 1}
    level = bisect_right(LEVEL_THRESHOLDS, points)
    result = {"level": level, "stars": points, "current_level_stars": max(1, LEVEL_THRESHOLDS[level - 1])}
    if level < len(LEVEL_THRESHOLDS):
        result["next_level_stars"] = LEVEL_THRESHOLDS[level]
    return result


def _whole_points(hundredths: int) -> int:
    return (-1 if hundredths < 0 else 1) * ((abs(hundredths) + 50) // 100)


async def profile_rating(db, uid: int, *, now: int, include_pending: bool) -> dict:
    # Only allowlisted, committed Stars operations affect the rating. A free gift,
    # Gram trade, bid hold or admin credit never counts as completed Stars spending.
    cursor = await db.execute(
        """SELECT amount, reason, created_at FROM economy_transactions
           WHERE telegram_id=? AND asset='stars' AND admin_id IS NULL AND (
             reason GLOB 'telegram_gift:*' OR reason GLOB 'gift_purchase:*' OR
             reason GLOB 'gift_sell:*' OR reason GLOB 'stars_purchase_refund:*' OR
             reason GLOB 'stars_resale_purchase:*' OR reason GLOB 'stars_paid_message:*' OR
             reason GLOB 'stars_suggested_post:*')
           UNION ALL
           SELECT -current_bid, 'gift_purchase:auction', settled_at FROM gift_auctions
           WHERE current_bidder_id=? AND status='ended' AND settled_at IS NOT NULL AND current_bid>0""",
        (uid, uid),
    )
    current = 0
    future: dict[int, int] = {}
    for amount, reason, created in await cursor.fetchall():
        kind = reason.split(':', 1)[0]
        if amount > 0 and kind in ('gift_sell', 'stars_purchase_refund'):
            current -= amount * (85 if kind == 'gift_sell' else 1000)
        elif amount < 0 and kind in ('telegram_gift', 'gift_purchase', 'stars_resale_purchase', 'stars_paid_message', 'stars_suggested_post'):
            points = -amount * (100 if kind in ('telegram_gift', 'gift_purchase') else 20)
            applies = created + PENDING_SECONDS
            if applies <= now:
                current += points
            elif include_pending:
                future[applies] = future.get(applies, 0) + points
    points = _whole_points(current)
    result = {"stars_rating": rating_value(points)}
    if future:
        for applies, earned in sorted(future.items()):
            current += earned
            next_points = _whole_points(current)
            if next_points != points:
                result["stars_my_pending_rating"] = rating_value(next_points)
                result["stars_my_pending_rating_date"] = applies
                break
    return result
