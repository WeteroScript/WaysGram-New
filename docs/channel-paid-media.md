# Paid channel media (server API)

Uses internal WaysGram Stars. In Android, open **Paid media** from the context menu of a published channel post. The owner can attach a photo (up to 10 MB) or an MP4 (up to 25 MB) to a text post. Readers confirm the price before checking out and use the same menu to reopen a purchased file.

1. The channel owner uploads a fresh photo or video using the media API and publishes a text-only channel post. Do not attach the upload as ordinary media or share it elsewhere.
2. Owner calls `PUT /api/channels/{channel_id}/messages/{post_id}/paid-media` with `{"media_id":"…","price_stars":5}`. One item per post; the price and asset cannot be changed. Valid prices are 1–10000 Stars.
3. `GET` on the same path returns `price_stars` and `unlocked`; `media_id` is only sent to the owner or a buyer. After explicit price confirmation, buy with `POST` and `{"approved_price_stars":5}`. One purchase per buyer per post: concurrent calls or retries cannot charge twice. A mismatching price returns HTTP 402 and the current price; do not retry with a different price without user approval.
4. Download the returned file through authenticated `GET /api/media/{media_id}`. Knowing the asset ID is not sufficient. Buyers must still have channel access and the post must remain available. Deleting a post or losing channel access does not refund the purchase; clients must disclose this before checkout. This is access control, not DRM, and can't revoke copies already saved by buyers.
