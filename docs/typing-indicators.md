# Typing indicators

The native Android bridge supports the ordinary text typing/cancel action in
one-to-one conversations and basic groups, not Saved Messages, channels,
threads or recording/upload activity. It ignores unsupported activity actions
rather than presenting them as text typing. User input is not transmitted.

`PUT /api/typing` remains the source of ephemeral activity, expiring eight
seconds after the last update. The server currently aggregates activity by
account and chat, not session: if the same account types on two devices, a
cancel from one device can clear the other device's indicator until it refreshes.
For that reason the feature remains partial. One account's Android writes are ordered; queued
activity older than eight seconds is discarded and session changes invalidate
queued requests. The native client's independent per-account poller fetches
`GET /api/typing/active` roughly every 2.5 seconds. It emits native TL typing and
cancel updates and lets the UI expire indicators when the network fails. Its
monotonic deadlines prevent delayed responses from reviving expired indicators.
Polling only runs while the WaysGram poller is active. It does not use push or
persist typing events in message history.

The snapshot is authenticated and excludes the caller, expired/banned senders,
blocked direct conversations, and groups where either party is no longer a
member. Group blocking follows the existing per-group typing API policy. It
returns no phone numbers. Cursor parameters (`after_scope`, `after_peer_id`,
`after_user_id`, `limit` 1–500) form a keyset; every page rechecks access and
expiry. The Android poller performs at most 40 pages per pass and resumes an
unfinished scan on the next pass; it never treats omitted pages as cancellations.
Pages are not a single transactional snapshot, so rapidly changing activity
can appear or disappear with polling latency. Very large active lists can take
longer than the eight-second lifetime to traverse.

Deploy the server with this endpoint before the Android client. On an older
server or transient error, message polling continues and existing indicators
expire naturally. Android static checks and backend tests do not replace an
Android build or device/network test; neither was performed for this change.
