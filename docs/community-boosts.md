# Community boosts

WaysGram accounts have one boost slot shared between channels and groups. Members can assign it from the chat menu under **Boosts**. Moving it removes the old boost and starts a seven-day cooldown. There is no Stars charge. An assignment only counts while the booster is an active member; leaving does not reset the move cooldown. Thresholds for levels 1–5 are 1, 3, 5, 10 and 25 active boosts.

A group owner or an administrator who can manage slow mode can optionally exempt members currently boosting the group from the slow-mode timer. Boosts do not bypass mutes, send restrictions, or anti-spam. Channel and group appearance rewards and additional Telegram Premium-style slots are not part of this implementation.

API: `GET` / `PUT /api/community-boosts/{group|channel}/{chat_id}` for status and assignment; `PUT /api/groups/{id}/boost-settings` with `{"slow_mode_bypass":true}` for the group perk.
