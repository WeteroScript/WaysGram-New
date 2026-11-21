# Channel paid reactions

Server implementation; Android is not yet wired. Uses internal WaysGram Stars, not Telegram Stars or payments.

- `GET /api/channels/{id}` includes `paid_reactions_enabled` (off by default).
- Owner: `PUT /api/channels/{id}/paid-reactions` with `{"enabled":true}`.
- Reader: `GET /api/channels/{id}/messages/{post}/paid-reactions` returns `total_stars` and `my_stars` without exposing other contributors' identities.
- Reader: `POST` to that URL with `{"stars":5}` and a unique `Idempotency-Key` (16–128 ASCII letters, digits, underscores or hyphens). Send only after explicitly confirming the amount. Reuse the same key and amount on ambiguous failures; generate a new key only for a new confirmed purchase. Replays return the original receipt, not the current wallet balance.

A purchase credits the channel owner's wallet and debits the buyer, with both ledger entries and the receipt in one transaction. Disabling reactions prevents new purchases; it does not refund past contributions. Self-tips are rejected. Receipts survive post deletion to prevent a delayed retry from creating another charge.
