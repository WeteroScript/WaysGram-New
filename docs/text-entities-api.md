# Text formatting: API and Android bridge

The Android bridge maps supported entities for ordinary text and single-media
captions on send, edit, history, live updates and forwards. The implementation
is still incomplete: formatted album sends, drafts and scheduled/online delivery
are not supported; unknown TL entity types are rejected on send/edit. Android
source checks are not a build or a device test. Deploy a compatible server
before distributing this client: older servers may ignore entities and lose
formatting. Both client and server changes are required.

The existing message POST endpoints for direct messages, groups and
channels (text and single-media captions), and their PUT/PATCH edit endpoints, accept an optional `entities`
array alongside `body`. When the field is present, the exact body is stored,
including leading/trailing whitespace (the body must still contain non-whitespace
and be at most 4000 Python Unicode code points). The array may be empty and is
limited to 100 entries. Each entry has `type`, `offset` and `length`: offsets
and lengths count UTF-16 code units in the exact body, and both endpoints of the
range must be Unicode code-point boundaries. Length must be positive. This avoids
splitting surrogate pairs such as emoji; offsets are not grapheme-cluster indices.
Nested/overlapping ranges are allowed; exact duplicate entries are rejected.

Supported types are `bold`, `italic`, `underline`, `strike`, `spoiler`, `code`,
`pre` (optional string `language`, up to 64 characters, default empty), and
`text_url` (required HTTP(S) `url`, up to 2048 characters, without credentials),
and the plain-text detection entities `url`, `mention`, `hashtag`.
Unknown fields and unsupported types are rejected with HTTP 400 rather than
silently dropped. No URL is fetched or verified by the server.

Example: `{"body":" A🌷 ","entities":[{"type":"bold","offset":2,"length":2}]}`.
Existing messages migrate to an empty entity list. POST responses, history and
search return nonempty entities as `entities`; empty lists are omitted for
compatibility. Batch cross-chat forwards and legacy direct-message forwards
copy the formatting as a snapshot; dropping a media caption also drops its
entities. Idempotency keys include the original request payload.

Old clients omitting `entities` keep their previous whitespace behavior for
unformatted text. An edit that omits `entities` on a formatted message returns
HTTP 409 without changing it: the server cannot safely infer new offsets.
Clients that intentionally remove formatting must send an empty array. Contact,
location and gift messages cannot carry nonempty entities. Album and scheduled
or when-online requests reject the field; Android rejects formatted album sends
rather than dropping styling. Existing unformatted captions can be edited with
an empty array. Device behavior and Android build have not been validated.
