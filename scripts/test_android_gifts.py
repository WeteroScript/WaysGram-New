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

    def test_catalog_does_not_wait_for_all_artwork(self):
        bridge = self.source("tgnet/WaysGramGifts.java")
        start = bridge.index("public static TL_stars.StarGifts catalog(")
        catalog = bridge[start:bridge.index("private static File placeholderSticker", start)]
        self.assertNotIn("invokeAll", catalog)
        self.assertIn("queueArtwork(account, id)", catalog)
        self.assertIn("placeholderSticker(stickerId)", catalog)
        self.assertIn("controller.giftsLoading", bridge)
        self.assertIn("scheduleCatalogRefresh(account)", bridge)

    def test_attribute_downloads_have_bounded_parallelism(self):
        attributes = self.source("messenger/waysgram/WaysGramGiftAttributes.java")
        self.assertIn("MAX_DOWNLOAD_WORKERS = 3", attributes)
        self.assertIn("newFixedThreadPool(MAX_DOWNLOAD_WORKERS", attributes)
        self.assertIn("WaysGramGiftAttributes::drainDownloads", attributes)
        documents = self.source("messenger/waysgram/WaysGramGiftDocuments.java")
        self.assertIn("synchronized File storeSticker", documents)

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
