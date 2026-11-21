# Channel Stars giveaways

The channel menu offers **Giveaways**. The owner can create one open giveaway per channel. The owner chooses the deadline, a prize of 1–10,000 Stars per winner, and up to 50 winners; the maximum total is 100,000 Stars. Active members can enter once for free. The owner cannot enter. Participants who are no longer eligible at draw time are excluded.

The background worker selects eligible winners uniformly without replacement. It debits the owner's balance and credits all winners in the same transaction as the final result. Funds are checked at creation but are **not reserved**. Insufficient balance at draw time cancels the giveaway and no Stars move. If fewer eligible members entered than requested, only those members win. The list shows the 20 most recent giveaways, the number of winners, and whether the authenticated account won; it does not reveal participant or winner identities to others.

API: `GET`/`POST /api/channels/{id}/giveaways` and `PUT /api/channels/{id}/giveaways/{giveaway_id}/entry`. Creation accepts `ends_at` (Unix seconds), `prize_stars`, and `winner_count`.
