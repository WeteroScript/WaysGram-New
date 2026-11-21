# WaysGram group topics

Open **Topics** from a group’s chat menu. The owner and administrators with change-info rights can create, rename, close, reopen or delete topics. Members can read topic history and send text, photos, or MP4 in open topics, subject to the group's restrictions and slow mode. Closing is reversible and preserves history. Deleting removes the topic from normal access without moving its messages into the main room.

API: authenticated `GET` and `POST /api/groups/{id}/topics`, `PATCH` and `DELETE /api/groups/{id}/topics/{topic}`, and `GET` / `POST` on the topic's `/messages` endpoint. Topic message sends support the normal `Idempotency-Key`. History has its own `before_id` cursor. Topic messages are excluded from general history and its unread count.

Topic pinning uses `PUT /api/groups/{id}/topics/{topic}/pin` with `message_id`; `0` unpins. The group pin permission is required. One message can be pinned per topic, and a message from another topic or the main room cannot be pinned here. The media endpoint checks current group membership and an active topic before granting access through a topic attachment.

Android still uses a dedicated dialog rather than a native forum screen. It refreshes the latest page every five seconds while that dialog is visible. Photos up to 10 MiB and MP4 videos up to 25 MiB can be sent and opened from topic actions. Per-topic push notifications are not implemented.
