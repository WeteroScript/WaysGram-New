# WaysGram share links

The native client uses `https://t.wg` for WaysGram profile and public community links, channel-post links, and typed invitation links. Private links retain `/api/groups/invites/<token>`, `/api/channels/invites/<token>` or `/api/channel-subscriptions/<token>`. The client opens these routes through the existing invite handlers against its configured API server, not against `t.wg`. Legacy inbound links and third-party Telegram URLs are not migrated in message content.

Server invite responses retain the API origin by default for older clients. Set `WAYSGRAM_BRANDED_INVITES=1` only after the domain and supported client rollout are ready; this changes the returned invitation origin without changing API endpoints or tokens.

Public opening requires a usable domain, HTTPS, and a web fallback. Verified Android App Links also require Digital Asset Links with the production package and signing certificate, plus a verified manifest association. The manifest URL filter alone does not guarantee that Android will open the app from another app or browser. DNS, public hosting, and on-device opening have not been verified.
