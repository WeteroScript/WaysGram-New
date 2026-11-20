# WaysGram profile gift order

The owner can use the pencil in the separate row at the right, just below the
profile's gift tab, then long-press and drop one card onto another. Dropping
outside a card leaves the order unchanged. The check mark exits editing. This
mode is only for the owner's WaysGram gifts, not Telegram's native gift list.

`PUT /api/me/gifts/order` takes `expected` and `order`, both complete arrays of
owned `user_gifts.id` values. The server compares `expected` against the
current order under a SQLite write transaction, verifies that `order` is a
permutation of it, and returns 409 if the inventory changed. The client blocks
refreshes while editing, saves on each completed swap, and reloads after an
error (including an ambiguous lost response). New or transferred gifts
appear first, in descending ID order, until the next reorder. Transfers reset
the moved gift's order via a database trigger. Both owner and public inventory
endpoints use the same ordering, including paginated reads.

The server migrates existing databases on startup. Roll out the AuthBot API
before distributing an Android version containing this feature. APK build and
device/UI verification were not performed as part of this change.
