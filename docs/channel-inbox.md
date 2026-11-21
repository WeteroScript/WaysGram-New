# Channel inbox (server API)

Channel inboxes are off by default. The owner (not regular channel admins) can configure the inbox with `GET` / `PUT /api/channels/{id}/inbox/settings`. `PUT` takes `{"enabled":true,"fee_stars":0}`; prices from 1 to 10000 use internal WaysGram Stars.

- Reader: `GET` / `POST /api/channels/{id}/inbox/me` operates on the authenticated reader's own thread. The read response includes `can_send` and the current `fee_stars`. It requires channel access. Turning off the inbox stops new incoming messages but does not erase threads.
- Owner: `GET /api/channels/{id}/inbox` lists threads. `GET` / `POST /api/channels/{id}/inbox/threads/{reader_id}` reads or replies to an existing thread. Replies are free and appear as channel replies without a personal sender ID. List endpoints support `before_id`, `limit`, and `next_before_id`.
- Send with `{"body":"…","approved_fee_stars":6}` and an `Idempotency-Key` of 16–128 ASCII letters, digits, underscores or hyphens. Confirm the exact current fee before sending; a changed fee returns HTTP 402 and is never automatically accepted. Zero can be omitted for free sends. Retry with the same key, body and fee on an ambiguous outcome. A completed operation returns its original message and never charges twice.

In Android, owners configure the inbox in the channel editor and read/reply via **Channel messages** in the chat menu. Readers use **Message channel** in the same menu. Text only; no push notifications, proposed posts, or delegated inbox admin permissions yet.
