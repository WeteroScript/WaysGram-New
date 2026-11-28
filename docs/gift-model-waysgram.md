# Gift model «WaysGram» (Plush Pepe)

An exclusive upgraded model added on top of Telegram's official Plush Pepe set. It does not
replace any published model: the ordinary 50 models keep their odds, and the WaysGram model is
drawn in addition to them.

| Property | Value |
| --- | --- |
| Gift | Plush Pepe (`5936013938331222567`) |
| Upgrade price | 25 Stars (same as every WaysGram upgrade) |
| Model name | `WaysGram` |
| Drop chance | **0.05 %** — one upgrade in 2000 (`one_in: 2000`) |
| Artwork | `authbot/assets/gift_models/5936013938331222567/waysgram.webm` (rendered from the official Plush Pepe TGS) |
| Document id | `-5936013938331222567` (synthetic, local to WaysGram only) |
| Number in UI | `#???` instead of the real `#913` |

## How the chance is drawn

`gift_upgrades.SPECIAL_MODELS` holds the exclusive entries. `roll_special_model()` makes one
independent `secrets.randbelow(2000)` draw per upgrade, so the published chance is exact and the
Telegram pool is untouched — its weights are not rescaled by the new model. When the draw hits,
the model slot is replaced by the WaysGram trait and the traits carry `"special_model": true`
plus `"chance_percent": 0.05`. Permille cannot express 0.05 %, so the UI must read
`chance_percent` when a trait has it (`bot._trait_chance`).

The roll is used by both the WaysGram upgrade endpoint and the lazy repair of old gifts, so a
gift upgraded before this model existed cannot retroactively become a WaysGram model.

## Artwork and animation

The motion is not invented — it is the motion of the **original Plush Pepe gift sticker**
(`tgs/by_name/plush_pepe.tgs` from Telegram's catalog):

| What is reused | How |
| --- | --- |
| Frames and loop | 60 fps, `ip 60` → `op 240` (3 s) — identical to the original |
| Body sway | layer `body`, `ks.r`: 9 keyframes `0 → −3° → +5° → −5° → +5° → −5° → +5° → −3° → 0` |
| Breathing | layer `body`, `ks.s`: 11 keyframes `100 % → 103/97 % → …` |
| Pivot | below the frame (ground pivot), like the original body layer |
| Easing | the original `i`/`o` bezier curves, verbatim |

Only the artwork is ours: the heart is repainted WaysGram red, the `WaysGram` wordmark is
painted onto it and follows the original heart layer, and a real black outer contour is added
around the silhouette (the earlier model had lost it). The frames are rendered from the real
TGS with `rlottie`, so no glow, hearts, sparkles or other invented animation effects remain.
Because the art is raster, the result is WebM with alpha, not TGS: the app's Lottie renderer
draws vector frames only.

Reproduce it with `scripts/build_waysgram_gift_model_webm.py`:

```bash
python3 scripts/build_waysgram_gift_model_webm.py \
    --raw /path/to/WaysGram-Android/TMessagesProj/src/main/res/raw/waysgram_model.webm
```

The file is served by `GET /api/telegram/gifts/{gift_id}/attributes/{document_id}` from the
pinned pack, exactly like the Heart Locket fallbacks. `gift_model_fallbacks` loads every
`assets/gift_models/<gift_id>/` directory instead of only the Heart Locket one, so a new pack
needs just a `manifest.json` and its sticker file (`preview.png` is the poster, not the sticker).

## Artwork bundled in the app

The model document id is synthetic, so it never exists in Telegram's catalog: the client can
only draw it after the artwork has been downloaded into the shared sticker folder
(`files/waysgram_gifts/<documentId>.bin`), which the WaysGram transport serves through
`upload.getFile`. Until that first download landed (or after the app data was cleared) the gift
cell stayed empty — the model had nothing to draw.

The same animation is therefore shipped inside the APK as `res/raw/waysgram_model.webm` and
`WaysGramBundledArtwork.install()` copies it into the sticker folder once per launch (it is
called from `WaysGramGiftDocuments.stickerFile(...)`, the single accessor used by attributes,
gift cards and the transport). The model then plays through the ordinary media stack, offline,
in the profile grid, the saved-gift card and chat service messages. When the rolled traits of an
older gift carry no `document_id`, `WaysGramGiftAttributes.resolveEntry` still resolves the
bundled id by the model name, and `WaysGramProfileGiftsController` falls back to the bundled
document when the network copy is not there yet.

## Number «#???»

The database still assigns a real collection number, so uniqueness and the market keep working.
The client hides it for this model only: `org.telegram.ui.Stars.WaysGramGiftNumber` detects the
`WaysGram` model in the gift attributes (by name or by the bundled document id) and draws `#???`
— three question marks in a gradient that shimmers from dark blue to near-black
(`Choreographer`-driven, 3.6 s cycle). Applied to the unique-gift card title, the rotated ribbon of
the gift grid (`Gifts.GiftSheet.Ribbon`, drawn with the same gradient), `getGiftName(...)`, the
wear page, offer sheets, service messages and resale bulletins.

The shimmer loops without a jump. The first version moved the gradient from −0.8 to +0.8 of the
text width with `TileMode.CLAMP`: at the end of the cycle the highlight snapped back to the start
and `#???` twitched. Now the palette is symmetric (`near-black → deep blue → shimmer → deep blue →
near-black`), the tile is `Shader.TileMode.MIRROR`, and one cycle shifts the gradient by exactly
one mirrored period (`phase * 2 * length`). The frame at `phase = 1` is pixel-identical to the
frame at `phase = 0`, so the loop is seamless while still moving in one direction. The gradient is
built horizontally (1 px of vertical extent so the shader is not degenerate), so nothing is
mirrored along the text height.

The cycle never stops while a card or a grid cell is registered: views are invalidated with
`postInvalidateOnAnimation()` for as long as they live, so the colour keeps moving while the card
is open instead of freezing until it is reopened.

## Issuing from the admin bot

`admin_nft_options` appends the exclusive models to the model slot of a supported gift, so
Управление аккаунтами → выдать NFT-подарок → Plush Pepe → модель `WaysGram · 0.05%` mints the
gift with exactly this artwork. The grant writes the same traits the roll produces, including the
synthetic document id, so the client plays the animation immediately.

## Checks

```bash
cd authbot && python3 - <<'PY'
import gift_model_fallbacks, gift_upgrades, time
PEPE = "5936013938331222567"
print(gift_model_fallbacks.model_for_name(PEPE, "waysgram"))
_, pool = gift_upgrades.pool_for_gift(PEPE)
real = gift_upgrades.secrets.randbelow
gift_upgrades.secrets.randbelow = lambda n: 0 if n == 2000 else real(n)
print(gift_upgrades.roll_traits(pool, int(time.time()), gift_id=PEPE)["model"])
gift_upgrades.secrets.randbelow = real
PY
```
