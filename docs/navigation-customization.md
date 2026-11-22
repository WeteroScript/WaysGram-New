# Navigation customization

Settings → Customization changes only the main bottom navigation background and the selected tab indicator. Images are clipped into the existing rounded bounds, behind labels and icons; navigation hit targets and other screens retain their standard behavior. Saved configurations and the active selection are local to the user on the device. Disabling customization restores the regular appearance without removing saved configurations.

The screen is built from rounded cards: a live preview of the navigation bar (background layer plus the active tab pill), a master switch, one compact row per layer with a thumbnail and upload action, a primary "Save config" button, and the list of saved configurations. Collected blocks fade and slide in with a short stagger, rows show a press ripple, and a deleted configuration animates out before the list is rebuilt. Each saved configuration row carries the active switch, a copy-link action and a delete action; deletion asks for confirmation, removes the stored configuration and, if it was active, clears the selection.

Settings → Customization changes only the main bottom navigation background and the selected tab indicator. Images are clipped into the existing rounded bounds, behind labels and icons; navigation hit targets and other screens retain their standard behavior. Saved configurations and the active selection are local to the user on the device. Disabling customization restores the regular appearance without removing saved configurations.

The Android document picker accepts any filename and MIME type. Images must still be decodable by Android's bitmap decoder; non-image files and unsupported formats are rejected. Images are static and normalized to bounded PNGs (640 × 256 and 128 KiB maximum per layer) before storage or sharing.

Copying a link explicitly publishes the configuration to the WaysGram API. The link `https://t.wg/custom/<token>` is readable by signed-in clients with the token, not an edit credential. The server allows up to 32 unique published configurations per account; re-sharing the same content returns its existing token. Import shows the name and image previews and requires Apply before saving and activating on the recipient's device.

Open these links within WaysGram. The app does not associate `t.wg` with external browsers or other apps. Device behavior has not been verified.
