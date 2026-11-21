# WaysGram paid direct messages

The server stores an optional fee in internal Stars (1–10,000) per recipient. The default is off. Self-messages, the recipient's contacts, users on their exception list, and users the recipient has already messaged are exempt. Existing block rules still take precedence.

- `GET` / `PUT /api/me/message-fee`: read or save `{"stars": 3, "exempt_users": [123]}`. Sending `stars: 0` disables the fee.
- `GET /api/chats/{peer_id}/message-fee`: fetch the fee for the authenticated sender before confirming a send. Do not treat a quote as authorization.
- `POST /api/messages`: send an explicit `approved_fee_stars` equal to the current fee and a stable `Idempotency-Key` for this send. HTTP 402 `MESSAGE_FEE_REQUIRED` includes the current price and requires fresh confirmation. `MESSAGE_FEE_INSUFFICIENT_STARS` indicates insufficient balance. Never retry a changed price automatically.

A paid send creates the message, debits the sender, credits the recipient, records both ledger entries, and persists the idempotent response in one transaction. Batches, forwards, gift messages, and scheduled messages that would require a fee are rejected rather than charging without consent. Store receipts are not paid chat sends.

Android source exposes the fee and exceptions under Privacy → Messages. Direct text and text replies prompt in the active chat using the exact server price. Ambiguous paid retries reuse the previous approval and request key, and are refused locally after 24 hours. A changed price, including zero, requires new confirmation. Media and other send types do not have a paid confirmation UI. Older APKs cannot approve a fee.
