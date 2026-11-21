# WaysGram profile rating

The server derives the public rating from settled Stars activity, not the wallet balance. Android uses the existing Telegram `StarRatingView` and detail sheet, populated from `/api/me` and `/api/users/{id}`. Level 0 is public too, including before the first points mature. An upcoming increase and its application date are visible only to the owner.

| Operation | Rating effect |
| --- | --- |
| Store gift purchase or settled store auction in Stars | +100% |
| Stars resale purchase, paid message or suggested post | +20% |
| Gift converted/sold back for Stars | −85% of the returned Stars |
| Confirmed Stars purchase refund | −10× |

The 20% and refund rules recognize explicit Stars ledger reasons; this change does not add payment flows that emit them. Gram transactions, admin credits, free grants, bid holds and gift upgrades do not count. Increases mature after 21 days; deductions apply immediately. Fractional points are summed before rounding to the nearest whole point. The full ladder is defined in `authbot/star_rating.py`: the thresholds beyond the observed early levels are WaysGram policy, not a claim about Telegram's private backend.
