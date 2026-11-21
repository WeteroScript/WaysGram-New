# Paid channel subscriptions (server API)

Uses internal WaysGram Stars. Android integration is not yet available. This is a **30-day pass with explicit manual renewal**, not recurring billing.

- Only a private channel's owner can read or create its offer with `GET` / `PUT /api/channels/{id}/subscription-offer`. `PUT` takes `{"price_stars":6}` (1–10000). Each change rotates the token; `0` revokes the offer without cancelling existing passes. Making the channel public revokes the offer.
- `GET /api/channel-subscriptions/{token}` quotes the price and indicates whether the current account already has access. Use authenticated requests. Ordinary invites are separate and remain free.
- After the buyer confirms, `POST` to that path with `{"approved_price_stars":6}` and an `Idempotency-Key` (16–128 ASCII letters, digits, underscores or hyphens). Use the same key, token and amount when resolving ambiguous payments. A new confirmed purchase after expiry uses a new key. Already-authorized viewers are not charged, and a different price is never accepted without a new confirmation.
- Paid membership is checked against the expiration timestamp on the server; it does not silently convert to permanent membership. Leaving or getting banned ends the pass. Existing independent access (e.g. admin role or ordinary invite membership) follows the normal rules. No automatic debit or refund.
