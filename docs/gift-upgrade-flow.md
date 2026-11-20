# WaysGram collectible upgrades

Upgrades are internal to WaysGram, not Telegram or TON operations. The backend keeps
charging the fixed WaysGram price atomically and stores a single server-selected result.
A private, read-only preview endpoint returns candidate pools and the current price; it
is a sample, not a reservation, because catalog data may change before confirmation.
The reveal uses candidates returned with the successful roll, and its final attributes
and collection number come from that response. New rolls match artwork by document ID
or exact name, rather than guessing from a chance or position. Older gifts retain the
existing compatibility repair. If exact animation files are unavailable, the UI shows
actual trait names and chances without pretending another artwork is the winner.

The user may choose to preserve the latest acquisition's sender, recipient, date and
message in the collectible's card, which may be visible to other users. Anonymous senders
remain anonymous, including when their receipt is missing or corrupt. The idempotency
request includes the preservation choice. Android stores the key and choice before
sending, reuses them after errors or restart, and refuses a conflicting retry. The key is
scoped to the account slot and serial. Concurrent requests for the same serial are blocked.

Android reuses Telegram's upgrade page and roller when matching artwork is available;
otherwise it falls back to a non-animated card. A timeout prevents an unloaded animation
from spinning indefinitely. Late artwork can refresh the open result card. The client
never starts Telegram's payment flow for a WaysGram gift. Telegram-only prepayment,
dynamic prices, blockchain actions and the next-gift shortcut are not implemented.

Backend changes must be deployed before an APK containing this flow is used. Source
checks are not a substitute for compiling the Android app or testing on a device, and
visual or behavioral identity with Telegram has not been verified on a device.
