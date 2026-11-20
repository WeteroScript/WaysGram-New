#!/usr/bin/env python3
"""Static regression checks in the Android split archive; not an Android build or UI test."""
import io
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "WaysGram-Android/TMessagesProj/src/main/java/org/telegram/"


class AndroidGiftSourceChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = b"".join((ROOT / f"WaysGram-Android.zip.part{i}").read_bytes() for i in range(1, 6))
        cls.archive = zipfile.ZipFile(io.BytesIO(data))
        assert len(cls.archive.namelist()) == len(set(cls.archive.namelist()))

    @classmethod
    def tearDownClass(cls):
        cls.archive.close()

    def source(self, name):
        return self.archive.read(BASE + name).decode("utf-8")

    def test_profile_uses_actual_owner_and_supports_nested_scrolling(self):
        layout = self.source("ui/Components/SharedMediaLayout.java")
        self.assertIn('getArguments().getLong("user_id", 0)', layout)
        self.assertIn('waysGramProfileOwner == profileActivity.getUserConfig().getClientUserId() ? "Мои подарки" : "Подарки"', layout)
        self.assertIn('waysGramGiftsContainer.getCurrentListView()', layout)
        self.assertIn('waysGramGiftsContainer.setVisibleHeight(height)', layout)
        self.assertIn('waysGramGiftsContainer.canScroll(forward)', layout)
        self.assertIn('userId != 0 || hasMedia', self.source("ui/ProfileActivity.java"))

    def test_inventory_sender_message_and_real_rarity(self):
        controller = self.source("messenger/waysgram/WaysGramProfileGiftsController.java")
        for fragment in ('client.loadGifts(ownerId', 'item.optLong("owner_id"',
                         'item.optLong("sender_id"', 'optText(item, "gift_message")',
                         'trait.optInt("permille", 0)', 'container.replaceWaysGramInventory(nativeGifts)',
                         'downloadTelegramGiftStickerBytes(telegramGiftId, false'):
            self.assertIn(fragment, controller)
        client = self.source("messenger/waysgram/WaysGramGiftCatalogClient.java")
        self.assertIn('"/api/users/" + ownerId + "/gifts"', client)
        self.assertIn('response.optInt("next_offset"', client)

    def test_waysgram_order_mode_swaps_and_persists_only_owner_inventory(self):
        view = self.source("ui/Gifts/ProfileGiftsContainer.java")
        controller = self.source("messenger/waysgram/WaysGramProfileGiftsController.java")
        self.assertIn("parent.dialogId == UserConfig.getInstance(currentAccount).getClientUserId()", view)
        self.assertIn("makeWaysGramEditRow(context)", view)
        self.assertIn('Зажмите подарок и перетащите его', view)
        self.assertIn(').show(true)', view)
        self.assertIn('java.util.Collections.swap(list.gifts, from, to)', view)
        self.assertIn('View targetView = giftUnder(dragX, dragY)', view)
        self.assertIn('if (!parent.waysGramInventory) reorder.attachToRecyclerView(listView)', view)
        self.assertIn('event.findPointerIndex(dragPointer)', view)
        self.assertIn('event.findPointerIndex(scrollPointer)', view)
        self.assertIn('listView.scrollBy(0, Math.round(scrollY - y))', view)
        self.assertIn('finishWaysGramDrag(action == MotionEvent.ACTION_UP)', view)
        self.assertIn('finishWaysGramDrag(false)', view)
        self.assertIn('listView.adapter.update(false)', view)
        self.assertIn('ViewConfiguration.getLongPressTimeout()', view)
        self.assertIn('MotionEvent.ACTION_CANCEL, dragX, dragY', view)
        self.assertIn('parent.waysGramOrderBusy', view)
        self.assertIn('if (parent.waysGramInventory) return;', view)
        self.assertIn('client.put("/api/me/gifts/order", payload', controller)
        self.assertIn('payload.put("expected", expected)', controller)
        self.assertIn('if (editingOrder || savingOrder)', controller)
        self.assertIn('lastInventory = null;', controller)

    def test_pepe_cache_version_and_partial_model_match_are_safe(self):
        documents = self.source("messenger/waysgram/WaysGramGiftDocuments.java")
        bridge = self.source("tgnet/WaysGramGifts.java")
        attributes = self.source("messenger/waysgram/WaysGramGiftAttributes.java")
        self.assertIn('"5936013938331222567".equals(value)', documents)
        self.assertIn('return 8999000000000000123L', documents)
        self.assertIn('WaysGramGiftDocuments.documentId(giftId)', bridge)
        self.assertIn('WaysGramGiftDocuments.documentId(id)', bridge)
        self.assertIn('WaysGramGiftDocuments.documentId(entry.getValue()) == documentId', bridge)
        start = attributes.index('private static Entry resolveEntry(')
        end = attributes.index('private static Entry directEntry(', start)
        lookup = attributes[start:end]
        self.assertLess(lookup.index('Entry byName'), lookup.index('byUniquePermille('))
        self.assertLess(lookup.index('METADATA_EXPIRES.containsKey(giftId)'), lookup.index('byUniquePermille('))

    def test_collectible_transfer_message_uses_exact_unique_card_and_refreshes_art(self):
        messages = self.source("messenger/waysgram/WaysGramGiftMessages.java")
        self.assertIn('json.optInt("gift_upgrade_level", 0) > 0', messages)
        self.assertIn('traits != null && traits.length() > 0', messages)
        self.assertIn('senderId != recipientId', messages)
        self.assertIn('new TLRPC.TL_messageActionStarGiftUnique()', messages)
        self.assertIn('WaysGramProfileGiftsController.toNativeGifts(inventory, account)', messages)
        self.assertIn('gift.num = Math.max(0, item.optInt("unique_num", 0))', messages)
        self.assertIn('item.put("unique_num", Math.max(0, json.optInt("gift_unique_num", 0)))', messages)
        self.assertIn('item.put("owner_id", recipientId)', messages)
        self.assertIn('action.transferred = true', messages)
        self.assertIn('size() > 512', messages)
        self.assertIn('changedGiftId.equals(item.optString("telegram_gift_id", ""))', messages)
        cells = self.source("ui/Cells/ChatActionCell.java")
        self.assertIn('refreshCollectibleArtwork(currentAccount, currentMessageObject.messageOwner, giftId)', cells)
        self.assertIn('WaysGramGiftAttributes.addListener(waysGramGiftListener)', cells)
        self.assertIn('WaysGramGiftAttributes.removeListener(waysGramGiftListener)', cells)
        layout = self.source("ui/Stars/StarGiftUniqueActionLayout.java")
        self.assertIn('imageReceiver.clearImage()', layout)
        self.assertIn('gift.num > 0 ? gift.title', layout)
        sheet = self.source("ui/Stars/StarGiftSheet.java")
        serial_start = sheet.index('private String waysGramGiftSerial()')
        self.assertIn('final TL_stars.StarGift gift = getGift()', sheet[serial_start:serial_start + 450])
        self.assertIn('if (isWaysGramGift() || userStarGiftRepolling', sheet)
        self.assertIn('walletTransaction == null && !isWaysGramGift()', sheet)
        self.assertIn('if (messageObject != null && !isWaysGramGift() && showQuantity)', sheet)
        self.assertIn('if (!isWaysGramGift() && attr.rarity instanceof', sheet)
        self.assertIn('if (permille < 0) return ""', sheet)
        profile = self.source("messenger/waysgram/WaysGramProfileGiftsController.java")
        self.assertIn('unknown.permille = -1', profile)
        self.assertIn('WaysGramGiftAttributes.traitDocument(', profile)

    def test_waysgram_upgrade_button_uses_drawn_animated_star_and_not_on_nfts(self):
        sheet = self.source("ui/Stars/StarGiftSheet.java")
        start = sheet.index("// WaysGram upgrade: a fixed 25 Stars")
        end = sheet.index("if (savedStarGift.refunded)", start)
        block = sheet[start:end]
        self.assertIn('savedStarGift.gift instanceof TL_stars.TL_starGift)', block)
        self.assertIn('savedStarGift.gift instanceof TL_stars.TL_starGiftUnique', block)
        self.assertIn('new WaysGramAnimatedStarSpan(button)', block)
        self.assertIn('button.setOnClickListener(v -> openWaysGramTransfer())', block)
        self.assertIn('Улучшить за 25 звёзд', block)
        self.assertIn('class WaysGramAnimatedStarSpan extends ReplacementSpan', sheet)
        self.assertIn('canvas.drawPath(star, ink)', sheet)
        self.assertIn('host.isShown()) host.postInvalidateDelayed(33)', sheet)

    def test_nft_profile_transfer_is_internal_and_retains_retry_key(self):
        sheet = self.source("ui/Stars/StarGiftSheet.java")
        start = sheet.index("private void openWaysGramTransfer()")
        end = sheet.index("public void onTransferClick", start)
        block = sheet[start:end]
        self.assertIn('TL_stars.TL_starGiftUnique', block)
        self.assertIn('!myProfile', block)
        self.assertIn('UserSelectorBottomSheet.TYPE_TRANSFER', block)
        self.assertNotIn('addTONOption', block)
        self.assertIn('recipient <= 0', block)
        self.assertIn('"/api/messages/gift", body, key', block)
        self.assertIn('pending.edit().putString(prefix + "key", key)', block)
        self.assertIn('pending.getString(prefix + "key", "")', block)
        self.assertIn('6L * 24 * 60 * 60 * 1000', block)
        self.assertIn('WaysGramGiftUpgrade.notifyDataChanged()', block)
        self.assertIn('не транзакция в блокчейне', block)

    def test_catalog_does_not_wait_for_all_artwork(self):
        bridge = self.source("tgnet/WaysGramGifts.java")
        start = bridge.index("public static TL_stars.StarGifts catalog(")
        catalog = bridge[start:bridge.index("private static File placeholderSticker", start)]
        self.assertNotIn("invokeAll", catalog)
        self.assertIn("queueArtwork(account, id)", catalog)
        self.assertIn("placeholderSticker(stickerId)", catalog)
        self.assertIn("controller.giftsLoading", bridge)
        self.assertIn("scheduleCatalogRefresh(account)", bridge)

    def test_open_purchase_sheet_refreshes_artwork_after_catalog_load(self):
        sheet = self.source("ui/Gifts/SendGiftSheet.java")
        self.assertIn("private void refreshGiftArtwork()", sheet)
        self.assertIn("getStarGift(starGift.id)", sheet)
        self.assertIn("starGift.sticker = newDocument", sheet)
        self.assertIn("actionCell.setMessageObject(messageObject, true)", sheet)
        self.assertIn("addObserver(this, NotificationCenter.starGiftsLoaded)", sheet)
        self.assertIn("removeObserver(this, NotificationCenter.starGiftsLoaded)", sheet)
        self.assertIn("refreshGiftArtwork(); // Also handles a refresh that completed before attachment.", sheet)

    def test_attribute_downloads_have_bounded_parallelism(self):
        attributes = self.source("messenger/waysgram/WaysGramGiftAttributes.java")
        self.assertIn("MAX_DOWNLOAD_WORKERS = 3", attributes)
        self.assertIn("newFixedThreadPool(MAX_DOWNLOAD_WORKERS", attributes)
        self.assertIn("WaysGramGiftAttributes::drainDownloads", attributes)
        documents = self.source("messenger/waysgram/WaysGramGiftDocuments.java")
        self.assertIn("synchronized File storeSticker", documents)

    def test_model_loading_reuses_disk_deduplicates_and_retries_partial_metadata(self):
        attributes = self.source("messenger/waysgram/WaysGramGiftAttributes.java")
        self.assertIn('newFixedThreadPool(3', attributes)
        self.assertIn('PARTIAL_METADATA_TTL_MS = 62_000L', attributes)
        self.assertIn('MAX_PARTIAL_RECHECKS = 2', attributes)
        self.assertIn('if (!complete) schedulePartialRecheck(giftId)', attributes)
        self.assertIn('PARTIAL_RECHECK_SCHEDULED.contains(giftId)', attributes)
        self.assertIn('current.longValue() != expiry || LISTENERS.isEmpty()', attributes)
        self.assertIn('generation != recheckGeneration', attributes)
        self.assertIn('generation != request.requestGeneration', attributes)
        self.assertIn('generation != requestGeneration', attributes)
        self.assertIn('for (String giftId : giftIds) schedulePartialRecheck(giftId)', attributes)
        self.assertIn('return giftId + "|document|" + documentId', attributes)
        self.assertIn('documentCacheKey(giftId, entry.documentId)', attributes)
        self.assertIn('documentCacheKey(giftId, entry.documentId);', attributes)
        want_start = attributes.index('private static void wantTrait(')
        want_end = attributes.index('public static TLRPC.TL_document traitDocument(', want_start)
        self.assertIn('documentFor(giftId, entry)', attributes[want_start:want_end])
        document_start = attributes.index('private static TLRPC.TL_document documentFor(')
        document_end = attributes.index('private static void pushToFileLoader(', document_start)
        body = attributes[document_start:document_end]
        self.assertLess(body.index('WaysGramGiftDocuments.stickerFile'), body.index('enqueueLocked'))
        wanted_start = attributes.index('private static void queueWanted(')
        wanted_end = attributes.index('private static void enqueueLocked(', wanted_start)
        self.assertIn('documentFor(giftId, entry)', attributes[wanted_start:wanted_end])
        prefetch_start = attributes.index('public static void startServerPrefetch()')
        prefetch_end = attributes.index('public static void retryFailed()', prefetch_start)
        self.assertIn('WaysGramApi.NETWORK.execute', attributes[prefetch_start:prefetch_end])

    def test_profile_labels_refresh_does_not_reload_gifts(self):
        profile = self.source("messenger/waysgram/WaysGramUserProfileFragment.java")
        start = profile.index("private final Runnable waysGramLabelsTick")
        end = profile.index("@Override public void onResume()", start)
        tick = profile[start:end]
        self.assertIn('/api/labels?targets=', tick)
        self.assertIn('statusCode != 404 && statusCode != 405', tick)

    def test_purchase_text_and_owner_only_actions(self):
        bridge = self.source("tgnet/WaysGramGifts.java")
        self.assertIn('body.put("message", invoice.message.text)', bridge)
        self.assertIn('body.put("hide_name", invoice.hide_name)', bridge)
        sheet = self.source("ui/Stars/StarGiftSheet.java")
        self.assertIn('return myProfile && isMine(currentAccount, dialogId)', sheet)
        self.assertIn('selfId != fromId || isForChannel || !myProfile', sheet)
        messages = self.source("messenger/waysgram/WaysGramGiftMessages.java")
        self.assertIn('action.flags |= TLObject.FLAG_1', messages)


if __name__ == "__main__":
    unittest.main()
