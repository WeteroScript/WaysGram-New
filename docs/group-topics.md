# WaysGram group topics

Open **Topics** from a group’s chat menu. The owner and administrators with change-info rights can create, rename, close, reopen or delete topics. Members can read topic history and send text in open topics, subject to the group's restrictions and slow mode. Closing is reversible and preserves history. Deleting removes the topic from normal access without moving its messages into the main room.

API: authenticated `GET` and `POST /api/groups/{id}/topics`, `PATCH` and `DELETE /api/groups/{id}/topics/{topic}`, and `GET` / `POST` on the topic's `/messages` endpoint. Topic message sends support the normal `Idempotency-Key`. History has its own `before_id` cursor. Topic messages are excluded from general history and its unread count.

This first version uses a dedicated dialog, not the Telegram forum screen. It has text messages and manual history refresh; no per-topic notifications, media, or pinned messages yet.
