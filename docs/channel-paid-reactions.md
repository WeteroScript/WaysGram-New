# Channel paid reactions

Android: the owner can enable paid reactions in channel settings. The context menu on a published channel post shows totals and lets readers choose and confirm a contribution. The pending key is kept locally so an ambiguous payment can be retried without a second debit. Uses internal WaysGram Stars, not Telegram Stars or payments.

- `GET /api/channels/{id}` includes `paid_reactions_enabled` (off by default).
- Owner: `PUT /api/channels/{id}/paid-reactions` with `{"enabled":true}`.
- Reader: `GET /api/channels/{id}/messages/{post}/paid-reactions` returns `total_stars` and `my_stars` without exposing other contributors' identities.
- Reader: `POST` to that URL with `{"stars":5}` and a unique `Idempotency-Key` (16–128 ASCII letters, digits, underscores or hyphens). Send only after explicitly confirming the amount. Reuse the same key and amount on ambiguous failures; generate a new key only for a new confirmed purchase. Replays return the original receipt, not the current wallet balance.

A purchase credits the channel owner's wallet and debits the buyer, with both ledger entries and the receipt in one transaction. Disabling reactions prevents new purchases; it does not refund past contributions. Self-tips are rejected. Receipts survive post deletion to prevent a delayed retry from creating another charge.

## Owner statistics

`GET /api/channels/{id}/analytics?days=7|30|90` (default 30) is owner-only. The Android channel editor exposes it under Channel statistics. It reports the current member count, members still subscribed who joined in the selected period, posts published in that period that are still available (one per album), settled paid-reaction purchases, and a Stars revenue breakdown for reactions, paid media, subscriptions, and paid inbox messages. Revenue comes from credited ledger entries, so removing a post does not remove its revenue. It does not measure churn, impressions, or unique viewers.
