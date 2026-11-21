# Navigation customization

Settings → Customization changes only the main bottom navigation background and the selected tab indicator. Images are clipped into the existing rounded bounds, behind labels and icons; navigation hit targets and other screens retain their standard behavior. Saved configurations and the active selection are local to the user on the device. Disabling customization restores the regular appearance without removing saved configurations.

The Android document picker accepts any filename and MIME type. Images must still be decodable by Android's bitmap decoder; non-image files and unsupported formats are rejected. Images are static and normalized to bounded PNGs (640 × 256 and 128 KiB maximum per layer) before storage or sharing.

Copying a link explicitly publishes the configuration to the WaysGram API. The link `https://t.wg/custom/<token>` is readable by signed-in clients with the token, not an edit credential. The server allows up to 32 unique published configurations per account; re-sharing the same content returns its existing token. Import shows the name and image previews and requires Apply before saving and activating on the recipient's device.

External opening is subject to the `t.wg` DNS/HTTPS and Android App Links deployment described in `waysgram-links.md`. The app intercepts recognized links opened within the client. Device behavior has not been verified.
