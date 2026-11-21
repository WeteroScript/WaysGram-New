# WaysGram share links

The native client uses `https://t.wg` for WaysGram profile and public community links, channel-post links, and typed invitation links. Private links retain `/api/groups/invites/<token>`, `/api/channels/invites/<token>` or `/api/channel-subscriptions/<token>`. The client opens these routes through the existing invite handlers against its configured API server, not against `t.wg`. Legacy inbound links and third-party Telegram URLs are not migrated in message content.

Server invite responses retain the API origin by default for older clients. Set `WAYSGRAM_BRANDED_INVITES=1` only after the supported client rollout is ready; this changes the returned invitation origin without changing API endpoints or tokens.

`t.wg` is an in-app link namespace. Open or paste the link in WaysGram; Android does not register the host as an external App Link. Public DNS, a website, and browser redirects are not required or provided.
