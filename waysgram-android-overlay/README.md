# WaysGram Android overlay

Mirrored from `WeteroScript/WaysGram-source` at revision `ce8c1ce2db4832c6d1a29947ad64d9320927d53d`.

The APK workflow copies `src/main/java/` into the unpacked Android project, then runs:
- `scripts/apply-android-patches.py` — Adds the WaysGram entry point in Settings and keeps WaysGram and Telegram push notifications isolated.
- `scripts/prepare_android_dev_build.py` — Configures the registered package, app label, API origin, Firebase client configuration and Telegram's separately named Firebase app.

The Android overlay contains the WaysGram login/session screens, home/directory/profile/chat/timeline UI, gift catalogue, inventory and marketplace flows. Gift operations are internal to WaysGram: the Telegram Gift ID supplies catalogue metadata; ownership, transfers, listings and deterministic upgrades stay in the WaysGram backend.
