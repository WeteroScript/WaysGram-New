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
        self.assertIn('savedStarGift.can_upgrade && savedStarGift.gift instanceof TL_stars.TL_starGift)', block)
        self.assertIn('new WaysGramAnimatedStarSpan(button)', block)
        self.assertIn('Улучшить за 25 звёзд', block)
        self.assertIn('class WaysGramAnimatedStarSpan extends ReplacementSpan', sheet)
        self.assertIn('canvas.drawPath(star, ink)', sheet)
        self.assertIn('host.isShown()) host.postInvalidateDelayed(33)', sheet)

    def test_upgrade_preview_confirm_reveal_and_durable_retry(self):
        sheet = self.source("ui/Stars/StarGiftSheet.java")
        block = sheet[sheet.index("private void openWaysGramUpgrade("):sheet.index("private ArrayList<TL_stars.StarGiftAttribute> sample_attributes;")]
        for fragment in ('client.previewGiftUpgrade(serial', 'showWaysGramUpgradePreview(serial, preview)',
                         'switchPage(PAGE_UPGRADE, true)', 'waysGramCandidateAttributes(giftId, pool)',
                         'topView.setPreviewingAttributes(attributes)', 'new AlertDialog.Builder',
                         'Это не операция Telegram или TON.', 'doWaysGramUpgrade(serial)',
                         'WaysGramGiftUpgrade.upgrade(getContext(), currentAccount, serial, keep',
                         'result.optJSONObject("roll_pool")', 'roller.preload(actualPool)',
                         'savedStarGift.gift = gift', 'rolling = animate',
                         'activeRoller.skip()', 'waysGramUpgradeActive(serial)'):
            self.assertIn(fragment, block)
        self.assertIn('refreshWaysGramResultArtwork(giftId)', sheet)
        self.assertIn('&& !(roller != null && roller.isRolling())', sheet)
        self.assertIn('WaysGramGiftTraits.remembered(currentAccount, waysGramGiftSerial())', sheet)
        self.assertIn('!isWaysGramGift() && isMine(currentAccount, DialogObject.getPeerDialogId(gift.owner_id))', sheet)
        helper = self.source("messenger/waysgram/WaysGramGiftUpgrade.java")
        for fragment in ('waysgram_pending_upgrades_', 'putBoolean(serial + ":keep", keepOriginal)',
                         'client.upgradeGift(serial, key, keepOriginal,',
                         'pending.getBoolean(serial + ":keep", false) != keepOriginal',
                         'remove(serial).remove(serial + ":keep").commit()'):
            self.assertIn(fragment, helper)
        client = self.source("messenger/waysgram/WaysGramGiftCatalogClient.java")
        self.assertIn('static final ConcurrentHashMap<String, Boolean> inFlightUpgradeSerials', client)
        self.assertIn('sessionProvider.getTelegramAccount() + ":" + giftSerial', client)
        self.assertIn('/upgrade-preview", "GET", null', client)
        self.assertIn('keep_original_details', client)
        controller = self.source("messenger/waysgram/WaysGramProfileGiftsController.java")
        self.assertIn('attributes.optJSONObject("original_details")', controller)
        self.assertIn('WaysGramGiftTraits.remember(currentAccount, serial, traits)', controller)
        attrs = self.source("messenger/waysgram/WaysGramGiftAttributes.java")
        self.assertIn('traits.optBoolean("strict_artwork", false)', attrs)

    def test_nft_profile_transfer_is_internal_and_retains_retry_key(self):
        sheet = self.source("ui/Stars/StarGiftSheet.java")
        start = sheet.index("private boolean canTransferWaysGramGift()")
        end = sheet.index("public void onTransferClick", start)
        block = sheet[start:end]
        self.assertIn('TL_stars.TL_starGiftUnique', block)
        self.assertIn('return myProfile && walletCollectible == null', block)
        self.assertIn('topView.setWaysGramTransferOnly(canTransfer)', sheet)
        self.assertIn('this::openWaysGramTransfer)', sheet)
        self.assertIn('if (isWaysGramGift()) {\n            openWaysGramTransfer();', sheet)
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

    def test_gift_edit_menu_animates_both_directions_and_upgrade_respects_eligibility(self):
        container = self.source("ui/Gifts/ProfileGiftsContainer.java")
        self.assertIn('ItemOptions.makeOptions(parent.fragment, v)', container)
        self.assertIn('ValueAnimator.ofFloat(waysGramCheckProgress, active ? 1f : 0f)', container)
        controller = self.source("messenger/waysgram/WaysGramProfileGiftsController.java")
        self.assertIn('!isCollectible && !isListed && !isAuctioned && optBooleanLike(item, "can_upgrade")', controller)

    def test_waysgram_wear_changes_native_profile_status_via_internal_api(self):
        sheet = self.source("ui/Stars/StarGiftSheet.java")
        self.assertIn('"/api/me/gifts/" + serial + "/wear"', sheet)
        self.assertIn('TL_updateUserEmojiStatus', sheet)
        self.assertIn('R.string.Gift2ActionWearOff', sheet)
        mapper = self.source("tgnet/WaysGramMapper.java")
        self.assertIn('user.emoji_status = wornGiftStatus(json.optJSONObject("worn_gift"))', mapper)
        profile = self.source("ui/ProfileActivity.java")
        self.assertIn('isWaysGramWornGift(status)', profile)
        self.assertIn('R.drawable.filled_crown_on', profile)

    def test_worn_pattern_uses_resolved_artwork_not_an_unloaded_status_id(self):
        profile = self.source("ui/ProfileActivity.java")
        bridge = self.source("tgnet/WaysGramProfile.java")
        self.assertIn("WaysGramProfile.wornPatternDocument(currentAccount, status.collectible_id)", profile)
        self.assertIn("emoji.set(document, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC", profile)
        self.assertIn("ColorUtils.calculateContrast(tint", profile)
        self.assertIn("rememberWornPattern(account, gift.optLong", bridge)

    def test_star_rating_uses_native_profile_badge_and_server_thresholds(self):
        profile = self.source("ui/ProfileActivity.java")
        bridge = self.source("tgnet/WaysGramProfile.java")
        self.assertIn("ratingView.set(userInfo.stars_rating)", profile)
        self.assertIn('full.stars_rating = readStarRating(json.optJSONObject("stars_rating"))', bridge)
        self.assertIn('rating.current_level_stars = value.optLong("current_level_stars")', bridge)
        self.assertIn('full.stars_my_pending_rating_date = json.optInt("stars_my_pending_rating_date")', bridge)

    def test_waysgram_collectible_pin_uses_internal_profile_route(self):
        sheet = self.source("ui/Stars/StarGiftSheet.java")
        self.assertIn('this::toggleWaysGramPin', sheet)
        self.assertIn('"/api/me/gifts/" + serial + "/pin"', sheet)
        controller = self.source("messenger/waysgram/WaysGramProfileGiftsController.java")
        self.assertIn('savedGift.pinned_to_top = isCollectible && optBooleanLike(item, "profile_pinned")', controller)

    def test_waysgram_pins_render_on_native_profile_cover_before_opening_gifts(self):
        profile = self.source("ui/ProfileActivity.java")
        media = self.source("ui/Components/SharedMediaLayout.java")
        controller = self.source("messenger/waysgram/WaysGramProfileGiftsController.java")
        cover = self.source("ui/Stars/ProfileGiftsView.java")
        self.assertIn("sharedMediaLayout.loadWaysGramProfileCover()", profile)
        self.assertIn("ensureWaysGramGifts();\n        waysGramGiftsController.refresh();", media)
        self.assertIn("cover.setWaysGramGifts(nativeGifts)", controller)
        self.assertIn("gift.pinned_to_top && !gift.unsaved", cover)
        self.assertIn("maxCount = waysGram ? 6", cover)
        self.assertIn("if (!waysGram && emojiStatus instanceof TLRPC.TL_emojiStatusCollectible)", cover)
        self.assertIn("findWaysGramGift(gift.id)", profile)
        self.assertIn(".set(saved, null).show()", profile)
        self.assertIn('!gift.slug.startsWith("waysgram://")', cover)

    def test_gift_batch_only_selects_eligible_regular_gifts_and_uses_internal_api(self):
        page = self.source("ui/Gifts/ProfileGiftsContainer.java")
        for fragment in ("GiftBatchMode.UPGRADE", "GiftBatchMode.SELL", "gift.gift instanceof TL_stars.TL_starGift",
                         "WaysGramGiftSell.canSell(gift, currentAccount)", "if (!selectedGiftSerials.remove(serial))",
                         "Общая стоимость:", '"/upgrade" : "/sell"', '"wg-batch-" + java.util.UUID.randomUUID()'):
            self.assertIn(fragment, page)
        cells = self.source("ui/Gifts/GiftSheet.java")
        self.assertIn("setWaysGramBatchMark", cells)
        self.assertIn("0xff34a853", cells)

    def test_batch_buttons_follow_visible_profile_bottom_above_main_tabs(self):
        page = self.source("ui/Gifts/ProfileGiftsContainer.java")
        media = self.source("ui/Components/SharedMediaLayout.java")
        profile = self.source("ui/ProfileActivity.java")
        self.assertIn("giftBatchMode == null ? View.GONE : View.VISIBLE", page)
        self.assertIn("setEnabled(!giftBatchBusy && !selectedGiftSerials.isEmpty())", page)
        self.assertIn("setTranslationY(-hiddenBottom - parent.inventoryBottomInset)", page)
        self.assertIn("parent.inventoryBottomInset + dp(giftBatchMode != null ? 72 : 12)", page)
        self.assertEqual(media.count("waysGramGiftsContainer.setInventoryBottomInset(pagesPaddingBottom)"), 2)
        self.assertIn("setPagesPaddingBottom(navigationBarHeight + additionNavigationBarHeight)", profile)

    def test_profile_nested_scroll_can_reach_gift_grid_and_return_to_header(self):
        profile = self.source("ui/ProfileActivity.java")
        media = self.source("ui/Components/SharedMediaLayout.java")
        self.assertIn("return waysGramGiftsContainer.getCurrentListView()", media)
        self.assertIn("top <= 0 && innerListView != null && dyUnconsumed != 0", profile)
        self.assertIn("innerListView.canScrollVertically(Integer.signum(dyUnconsumed))", profile)
        self.assertIn("innerListView.scrollBy(0, consumed[1])", profile)

    def test_shop_price_sort_is_persistent_and_uses_a_vector_icon(self):
        import xml.etree.ElementTree as ET
        sheet = self.source("ui/Gifts/GiftSheet.java")
        for fragment in ('getBoolean(PRICE_DESCENDING, false)', 'putBoolean(PRICE_DESCENDING, descending).apply()',
                         'topView.addView(sortButton', '.addChecked(priceDescending', '.addChecked(!priceDescending',
                         'items.subList(firstGift, items.size()).sort', 'Long.compare(shopPrice(b), shopPrice(a))',
                         'Long.compare(shopPrice(a), shopPrice(b))', 'return gift.resell_min_stars'):
            self.assertIn(fragment, sheet)
        for key in ('WaysGramGiftSortPrice', 'WaysGramGiftSortAscending', 'WaysGramGiftSortDescending'):
            self.assertIn(f'context.getString(R.string.{key})', sheet)
            for language in ('values', 'values-ru'):
                resources = ET.fromstring(self.archive.read(
                    f'WaysGram-Android/TMessagesProj/src/main/res/{language}/waysgram_gift_sort.xml'))
                self.assertTrue(resources.find(f"string[@name='{key}']").text.strip())
        path = 'WaysGram-Android/TMessagesProj/src/main/res/drawable/waysgram_gift_sort.xml'
        icon = ET.fromstring(self.archive.read(path))
        self.assertEqual(icon.tag, 'vector')
        self.assertIsNotNone(icon.find('path'))

    def test_waysgram_profile_keeps_media_and_gifts_as_separate_tabs(self):
        layout = self.source("ui/Components/SharedMediaLayout.java")
        tabs = layout.split("public void updateTabs(boolean animated)", 1)[1].split("private void startStopVisibleGifs", 1)[0]
        self.assertIn("hasMedia[0] > 0", tabs)
        self.assertIn("hasWaysGramGifts && org.telegram.tgnet.WaysGramTransport.ENABLED", tabs)
        self.assertIn("hasPhotoVideoTab != scrollSlidingTextTabStrip.hasTab(TAB_PHOTOVIDEO)", tabs)
        self.assertIn("if (hasPhotoVideoTab)", tabs)
        self.assertLess(tabs.index("tabs.add(new Pair(TAB_PHOTOVIDEO"),
                        tabs.index("tabs.add(new Pair(TAB_WAYSGRAM_GIFTS"))

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
