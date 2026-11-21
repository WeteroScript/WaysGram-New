# Group send identities

**Send as…** in the group menu opens a text composer with an explicit sender choice. Group owners can post as the group and grant or revoke that right per administrator. Any member may post as a public WaysGram channel they own, if they are otherwise allowed to send in the group. Ordinary sends continue to use the personal profile. Existing moderation and rate limits still apply to the actual account.

The server retains the real actor internally to enforce edits, deletions and limits. For non-personal sends, public history, search, reply metadata and the new/edit/delete events never include that actor's account ID. The author only receives an `is_mine` flag. Forwarding these messages is disabled. This does not hide admin membership or make the ownership of a public channel private.

`GET /api/groups/{id}/send-identities` lists available identities. `PUT /api/groups/{id}/admins/{user}/anonymous` with `{"enabled":true}` grants the right. `POST /api/groups/{id}/identity-messages` takes `send_as_id: 0` for the group or a positive public channel ID. This version covers text in the main group, not attachments, forum topics, channel comments or scheduled sends.
