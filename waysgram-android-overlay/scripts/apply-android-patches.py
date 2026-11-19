#!/usr/bin/env python3
"""Apply WaysGram integrations while preserving Telegram's native interface.

The profile inventory reuses Telegram ProfileGiftsContainer and GiftCell; WaysGram
provides only the inventory data and sticker bitmaps.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path


FORBIDDEN_GIFT_MARKERS = (
    "WaysGramGiftCatalogSheet",
    "WaysGramLoginSheet",
    "Подарки WaysGram",
)
FORBIDDEN_LAUNCH_MARKERS = (
    "WaysGramLoginFragment",
    "WaysGramHomeFragment",
    "WaysGramSessionStore",
)




def replace_exactly_once(content: str, before: str, after: str, label: str) -> str:
    count = content.count(before)
    if count != 1:
        raise ValueError(f"Expected exactly one {label} anchor; found {count}")
    return content.replace(before, after, 1)


def patch_source(original: str) -> str:
    """Compatibility function: preserve the original GiftSheet byte-for-byte."""
    return original


def patch_launch_source(original: str) -> str:
    """Compatibility function: preserve the original LaunchActivity byte-for-byte."""
    return original





def patch_profile_actions_visibility_source(original: str) -> str:
    """Restore native profile action controls after an interrupted avatar animation."""
    marker = "// WaysGram: restore profile action visibility"
    if marker in original and "WaysGram: keep native profile actions above avatar overlays" in original:
        required = (
            "actionsView.setAlpha(1.0f);",
            "actionsView.setVisibility(View.VISIBLE);",
            "actionsView.bringToFront();",
        )
        if not all(value in original for value in required):
            raise ValueError("ProfileActivity contains an incomplete action visibility patch")
        return original

    if marker in original and "actionsView.setAlpha(1.0f);" not in original:
        raise ValueError("ProfileActivity contains an incomplete action visibility patch")

    before = (
        "        } else {\n"
        "            actionsView.clipHeight = -1;\n"
        "            float bottom = extraHeight + newTop - dp(hasMusic || hasBalance ? 25 : 0);\n"
        "            float height = Math.min(dp(74), bottom - newTop);\n"
        "            actionsView.updatePosition(bottom - height, height);\n"
        "        }\n"
    )
    after = (
        "        } else {\n"
        "            actionsView.clipHeight = -1;\n"
        "            float bottom = extraHeight + newTop - dp(hasMusic || hasBalance ? 25 : 0);\n"
        "            float height = Math.min(dp(74), bottom - newTop);\n"
        "            // WaysGram: restore profile action visibility\n"
        "            actionsView.setAlpha(1.0f);\n"
        "            actionsView.updatePosition(bottom - height, height);\n"
        "        }\n"
    )
    if marker not in original:
        original = replace_exactly_once(original, before, after, "profile action visibility restoration")

    action_position = (
        "            actionsView.updatePosition(bottom - height, height);\n"
    )
    action_position_patched = (
        action_position
        + "            // WaysGram: keep native profile actions above avatar overlays\n"
        + "            if (!openAnimationInProgress) {\n"
        + "                actionsView.setAlpha(1.0f);\n"
        + "                actionsView.setVisibility(View.VISIBLE);\n"
        + "            }\n"
        + "            actionsView.bringToFront();\n"
        + "            actionsView.invalidate();\n"
    )
    if "WaysGram: keep native profile actions above avatar overlays" not in original:
        original = replace_exactly_once(
            original, action_position, action_position_patched,
            "native profile action z-order and visibility restoration"
        )

    pager_indicator_anchor = "        avatarContainer2.addView(avatarsViewPagerIndicatorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));\n"
    pager_indicator_marker = "// WaysGram: place native profile actions above avatar pager indicator"
    if pager_indicator_marker not in original:
        pager_indicator_patched = (
            pager_indicator_anchor
            + "        // WaysGram: place native profile actions above avatar pager indicator\n"
            + "        if (actionsView != null) {\n"
            + "            actionsView.setAlpha(1.0f);\n"
            + "            actionsView.setVisibility(View.VISIBLE);\n"
            + "            actionsView.bringToFront();\n"
            + "        }\n"
        )
        original = replace_exactly_once(
            original, pager_indicator_anchor, pager_indicator_patched,
            "initial native profile action z-order"
        )
    return original



def patch_profile_image_updater_source(original: str) -> str:
    """Initialize Telegram's existing profile action controls after self detection."""
    marker = "// WaysGram: initialize native profile actions after self detection"
    if marker in original:
        required = (
            "if (myProfile && userId != 0 && imageUpdater == null)",
            "imageUpdater = new ImageUpdater(true, ImageUpdater.FOR_TYPE_USER, true);",
            "imageUpdater.setDelegate(this);",
        )
        if not all(value in original for value in required):
            raise ValueError("ProfileActivity contains an incomplete late profile action initializer")
        return original

    before = (
        "        if ((userId == selfId || dialogId == selfId) && !myProfile) {\n"
        "            myProfile = true;\n"
        "            // AndroidUtilities.printStackTrace(\"myProfile flag debug\");\n"
        "        }\n"
    )
    after = before + (
        "        // WaysGram: initialize native profile actions after self detection\n"
        "        if (myProfile && userId != 0 && imageUpdater == null) {\n"
        "            imageUpdater = new ImageUpdater(true, ImageUpdater.FOR_TYPE_USER, true);\n"
        "            imageUpdater.setOpenWithFrontfaceCamera(true);\n"
        "            imageUpdater.parentFragment = this;\n"
        "            imageUpdater.setDelegate(this);\n"
        "        }\n"
    )
    return replace_exactly_once(
        original, before, after,
        "late initialization of native profile action controls"
    )


def patch_profile_phone_null_source(original: str) -> str:
    """Reject placeholder strings such as 'null' in every profile phone display path."""
    marker = "// WaysGram: reject non-numeric phone placeholders"
    if marker in original:
        required = (
            'user.phone.matches("[0-9]{8,15}")',
            'user.phone == null || !user.phone.matches("[0-9]{8,15}")',
            'profilePhone != null && profilePhone.matches("[0-9]{8,15}")',
            'String copyPhone = myProfile ? waysGramStablePhone : user.phone;',
            'copyPhone.matches("[0-9]{8,15}")',
            'format("+" + profilePhone)',
        )
        if not all(value in original for value in required):
            raise ValueError("ProfileActivity contains an incomplete phone placeholder guard")
        return original

    header_before = (
        "                } else if (a == 0 && user.id != getUserConfig().getClientUserId() && !MessagesController.isSupportUser(user) && user.phone != null && user.phone.length() != 0 && getContactsController().contactsDict.get(user.id) == null &&\n"
    )
    header_after = (
        "                } else if (a == 0 && user.id != getUserConfig().getClientUserId() && !MessagesController.isSupportUser(user) && user.phone != null && user.phone.matches(\"[0-9]{8,15}\") && getContactsController().contactsDict.get(user.id) == null &&\n"
    )
    original = replace_exactly_once(
        original, header_before, header_after,
        "reject invalid phone in profile header"
    )

    number_before = (
        "                        if (user != null && user.phone != null && user.phone.length() != 0) {\n"
    )
    number_after = (
        "                        String profilePhone = myProfile ? waysGramStablePhone : (user == null ? \"\" : user.phone);\n"
        "                        if (user != null && profilePhone != null && profilePhone.matches(\"[0-9]{8,15}\")) {\n"
    )
    original = replace_exactly_once(
        original, number_before, number_after,
        "use validated persisted phone in own profile number row"
    )
    number_value_before = (
        "                            value = PhoneFormat.getInstance().format(\"+\" + user.phone);\n"
    )
    number_value_after = (
        "                            value = PhoneFormat.getInstance().format(\"+\" + profilePhone);\n"
    )
    original = replace_exactly_once(
        original, number_value_before, number_value_after,
        "format validated own profile phone"
    )

    click_before = (
        "            if (user == null || user.phone == null || user.phone.length() == 0 || getParentActivity() == null) {\n"
    )
    click_after = (
        "            if (user == null || user.phone == null || !user.phone.matches(\"[0-9]{8,15}\") || getParentActivity() == null) {\n"
    )
    original = replace_exactly_once(
        original, click_before, click_after,
        "reject invalid phone in profile phone actions"
    )

    share_phone_anchor = "if (!TextUtils.isEmpty(user.phone)) {"
    if original.count(share_phone_anchor) != 2:
        raise ValueError("Expected two profile share-contact phone checks")
    original = original.replace(
        share_phone_anchor,
        'if (user.phone != null && user.phone.matches("[0-9]{8,15}")) {'
    )

    copy_phone_before = "            textToCopy = user.phone;\n"
    copy_phone_after = (
        "            String copyPhone = myProfile ? waysGramStablePhone : user.phone;\n"
        "            textToCopy = copyPhone != null && copyPhone.matches(\"[0-9]{8,15}\") ? copyPhone : \"\";\n"
    )
    original = replace_exactly_once(
        original, copy_phone_before, copy_phone_after,
        "copy only a validated profile phone"
    )

    display_anchor = (
        "                    nameTextView[a].setText(PhoneFormat.getInstance().format(\"+\" + user.phone));\n"
    )
    original = replace_exactly_once(
        original, display_anchor,
        display_anchor + "                    // WaysGram: reject non-numeric phone placeholders\n",
        "phone display placeholder guard marker"
    )
    return original


def patch_profile_phone_source(original: str) -> str:
    """Use the persisted self phone instead of a transient cached profile value."""
    marker = "// WaysGram: use persisted self phone while profile refreshes"
    if marker in original:
        required = (
            'myProfile ? waysGramStablePhone',
            'profilePhone.matches("[0-9]{8,15}")',
        )
        if not all(value in original for value in required):
            raise ValueError("ProfileActivity contains an incomplete stable phone patch")
        return original

    original = replace_exactly_once(
        original,
        "    private String vcardPhone;\n",
        "    private String vcardPhone;\n"
        "    // WaysGram: snapshot the signed-in account phone before profile refresh.\n"
        "    private String waysGramStablePhone = \"\";\n",
        "stable signed-in phone field"
    )

    original = replace_exactly_once(
        original,
        "            TLRPC.User user = getMessagesController().getUser(userId);\n"
        "            if (user == null) {\n"
        "                return false;\n"
        "            }\n",
        "            TLRPC.User user = getMessagesController().getUser(userId);\n"
        "            if (user == null) {\n"
        "                return false;\n"
        "            }\n"
        "            if (UserObject.isUserSelf(user)) {\n"
        "                waysGramStablePhone = getUserConfig().getClientPhone();\n"
        "                if (waysGramStablePhone == null || !waysGramStablePhone.matches(\"[0-9]{8,15}\")) {\n"
        "                    waysGramStablePhone = \"\";\n"
        "                }\n"
        "            }\n",
        "stable self phone snapshot before loading full profile"
    )

    old_has_phone = (
        "                boolean hasPhone = user != null && (!TextUtils.isEmpty(user.phone) || !TextUtils.isEmpty(vcardPhone));\n"
    )
    new_has_phone = (
        "                // WaysGram: use persisted self phone while profile refreshes\n"
        "                String profilePhone = myProfile ? waysGramStablePhone : (user == null ? \"\" : user.phone);\n"
        "                boolean hasPhone = user != null && ((profilePhone != null && profilePhone.matches(\"[0-9]{8,15}\")) || (vcardPhone != null && vcardPhone.matches(\"[0-9]{8,15}\")));\n"
    )
    original = replace_exactly_once(
        original, old_has_phone, new_has_phone,
        "stable Telegram profile phone row condition"
    )

    original = replace_exactly_once(
        original,
        "                        if (user != null && !TextUtils.isEmpty(vcardPhone)) {\n",
        "                        String profilePhone = myProfile ? waysGramStablePhone : (user == null ? \"\" : user.phone);\n"
        "                        if (user != null && vcardPhone != null && vcardPhone.matches(\"[0-9]{8,15}\")) {\n",
        "stable profile phone display value"
    )
    original = replace_exactly_once(
        original,
        "                        } else if (user != null && !TextUtils.isEmpty(user.phone)) {\n",
        "                        } else if (user != null && profilePhone != null && profilePhone.matches(\"[0-9]{8,15}\")) {\n",
        "stable Telegram profile phone display"
    )
    original = replace_exactly_once(
        original,
        "                            text = PhoneFormat.getInstance().format(\"+\" + user.phone);\n"
        "                            phoneNumber = user.phone;\n",
        "                            text = PhoneFormat.getInstance().format(\"+\" + profilePhone);\n"
        "                            phoneNumber = profilePhone;\n",
        "persisted profile phone formatting"
    )
    return original

def patch_profile_gifts_source(original: str) -> str:
    """Add a native Telegram gifts container for the WaysGram-owned inventory tab."""
    tab_marker = "    public static final int TAB_POLL = 15;\n"
    tab_added = "    public static final int TAB_WAYSGRAM_GIFTS = 16;\n"
    field_marker = "    public ProfileGiftsContainer giftsContainer;\n"
    field_added = (
        field_marker
        + "    private ProfileGiftsContainer waysGramGiftsContainer;\n"
        + "    private org.telegram.messenger.waysgram.WaysGramProfileGiftsController waysGramGiftsController;\n"
    )
    import_marker = "import org.telegram.messenger.AndroidUtilities;\n"
    import_added = (
        import_marker
        + "import org.telegram.messenger.waysgram.WaysGramProfileGiftsController;\n"
    )

    markers_present = (
        "TAB_WAYSGRAM_GIFTS",
        "waysGramGiftsContainer",
        "WaysGramProfileGiftsController",
    )
    initial_padding_marker = "waysGramGiftsContainer.setPaddingTop("
    animated_padding_marker = (
        "waysGramGiftsContainer.setPaddingTop(dp(48) + "
        "(int) topPanelLayout.getAnimatedHeightWithPadding(dp(7)))"
    )
    if all(marker in original for marker in markers_present):
        updated = original
        if initial_padding_marker not in updated:
            listener_anchor = (
                "                    waysGramGiftsContainer.setWaysGramGiftUpdatedListener(waysGramGiftsController::refresh);\n"
            )
            updated = replace_exactly_once(
                updated, listener_anchor,
                listener_anchor
                + "                    waysGramGiftsContainer.setPaddingTop(\n"
                + "                            dp(48) + (topPanelLayout != null ? ((int) topPanelLayout.getAnimatedHeightWithPadding(0)) : 0));\n",
                "existing WaysGram profile gifts initial top padding",
            )
        if animated_padding_marker not in updated:
            native_padding = (
                "                if (giftsContainer != null) {\n"
                "                    giftsContainer.setPaddingTop(dp(48) + (int) topPanelLayout.getAnimatedHeightWithPadding(dp(7)));\n"
                "                }\n"
            )
            updated = replace_exactly_once(
                updated, native_padding,
                native_padding
                + "                if (waysGramGiftsContainer != null) {\n"
                + "                    waysGramGiftsContainer.setPaddingTop(dp(48) + (int) topPanelLayout.getAnimatedHeightWithPadding(dp(7)));\n"
                + "                }\n",
                "existing WaysGram profile gifts animated top padding",
            )
        return updated
    if any(marker in original for marker in markers_present):
        raise ValueError("SharedMediaLayout contains a partial WaysGram profile gifts tab patch")

    updated = replace_exactly_once(original, import_marker, import_added, "profile gifts controller import")
    updated = replace_exactly_once(updated, tab_marker, tab_marker + tab_added, "profile gifts tab ID")
    updated = replace_exactly_once(updated, field_marker, field_added, "profile gifts container field")

    gifts_changed = (
        "        if (hasGifts != scrollSlidingTextTabStrip.hasTab(TAB_GIFTS)) {\n"
        "            changed++;\n"
        "        } else if (giftsContainer != null && hasGifts && giftsLastHash != giftsContainer.getLastEmojisHash()) {\n"
        "            changed++;\n"
        "        }\n"
    )
    gifts_changed_patched = (
        "        if (isStoriesView() != scrollSlidingTextTabStrip.hasTab(TAB_WAYSGRAM_GIFTS)) {\n"
        "            changed++;\n"
        "        }\n" + gifts_changed
    )
    updated = replace_exactly_once(updated, gifts_changed, gifts_changed_patched, "profile gifts tab change detection")

    native_gifts_tabs = (
        "            if (hasGifts) {\n"
        "                tabs.add(new Pair(TAB_GIFTS, TextUtils.concat(getString(R.string.ProfileGifts), giftsContainer.getLastEmojis(null))));\n"
        "                giftsLastHash = giftsContainer.getLastEmojisHash();\n"
        "            }\n"
    )
    waysgram_tab = (
        "            if (isStoriesView()) {\n"
        "                tabs.add(new Pair(TAB_WAYSGRAM_GIFTS, \"Мои подарки\"));\n"
        "            }\n"
    )
    updated = replace_exactly_once(
        updated, native_gifts_tabs, waysgram_tab + native_gifts_tabs,
        "profile gifts tab placement beside story archive"
    )

    native_page_branch = (
        "            } else if (mediaPages[a].selectedType == TAB_GIFTS) {\n"
        "                if (currentAdapter != null) {\n"
        "                    recycleAdapter(currentAdapter);\n"
        "                    mediaPages[a].listView.setAdapter(null);\n"
        "                }\n"
        "                if (giftsContainer != null && giftsContainer.getParent() != mediaPages[a]) {\n"
        "                    AndroidUtilities.removeFromParent(giftsContainer);\n"
        "                    mediaPages[a].addView(giftsContainer);\n"
        "                    mediaPages[a].emptyView.setVisibility(View.INVISIBLE);\n"
        "                }\n"
        "            }\n"
    )
    custom_page_branch = (
        "            } else if (mediaPages[a].selectedType == TAB_WAYSGRAM_GIFTS) {\n"
        "                if (currentAdapter != null) {\n"
        "                    recycleAdapter(currentAdapter);\n"
        "                    mediaPages[a].listView.setAdapter(null);\n"
        "                }\n"
        "                if (waysGramGiftsContainer == null) {\n"
        "                    waysGramGiftsContainer = new ProfileGiftsContainer(profileActivity, getContext(),\n"
        "                            profileActivity.getCurrentAccount(), ((ProfileActivity) profileActivity).getDialogId(),\n"
        "                            resourcesProvider, true);\n"
        "                    waysGramGiftsController = new WaysGramProfileGiftsController(\n"
        "                            getContext(), profileActivity, waysGramGiftsContainer);\n"
        "                    waysGramGiftsContainer.setWaysGramGiftUpdatedListener(waysGramGiftsController::refresh);\n"
        "                    waysGramGiftsContainer.setPaddingTop(\n"
        "                            dp(48) + (topPanelLayout != null ? ((int) topPanelLayout.getAnimatedHeightWithPadding(0)) : 0));\n"
        "                }\n"
        "                if (waysGramGiftsContainer.getParent() != mediaPages[a]) {\n"
        "                    AndroidUtilities.removeFromParent(waysGramGiftsContainer);\n"
        "                    mediaPages[a].addView(waysGramGiftsContainer, new FrameLayout.LayoutParams(\n"
        "                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));\n"
        "                    mediaPages[a].emptyView.setVisibility(View.INVISIBLE);\n"
        "                }\n"
        "                waysGramGiftsController.refresh();\n"
        "            }\n"
    )
    updated = replace_exactly_once(
        updated, native_page_branch,
        native_page_branch[:-len("            }\n")] + custom_page_branch,
        "native Telegram profile gifts container"
    )

    native_padding = (
        "                if (giftsContainer != null) {\n"
        "                    giftsContainer.setPaddingTop(dp(48) + (int) topPanelLayout.getAnimatedHeightWithPadding(dp(7)));\n"
        "                }\n"
    )
    updated = replace_exactly_once(
        updated, native_padding,
        native_padding
        + "                if (waysGramGiftsContainer != null) {\n"
        + "                    waysGramGiftsContainer.setPaddingTop(dp(48) + (int) topPanelLayout.getAnimatedHeightWithPadding(dp(7)));\n"
        + "                }\n",
        "synchronize WaysGram top padding with Telegram animated profile header",
    )

    native_cleanup = (
        "            if (giftsContainer != null && mediaPages[a].selectedType != TAB_GIFTS && giftsContainer.getParent() == mediaPages[a]) {\n"
        "                mediaPages[a].removeView(giftsContainer);\n"
        "            }\n"
    )
    custom_cleanup = (
        "            if (waysGramGiftsContainer != null && mediaPages[a].selectedType != TAB_WAYSGRAM_GIFTS && waysGramGiftsContainer.getParent() == mediaPages[a]) {\n"
        "                mediaPages[a].removeView(waysGramGiftsContainer);\n"
        "            }\n"
    )
    updated = replace_exactly_once(updated, native_cleanup, native_cleanup + custom_cleanup, "profile gifts inventory cleanup")

    search_condition = (
        "mediaPages[a].selectedType == TAB_RECOMMENDED_CHANNELS || mediaPages[a].selectedType == TAB_BOT_PREVIEWS || mediaPages[a].selectedType == TAB_GIFTS) {"
    )
    search_patched = (
        "mediaPages[a].selectedType == TAB_RECOMMENDED_CHANNELS || mediaPages[a].selectedType == TAB_BOT_PREVIEWS || mediaPages[a].selectedType == TAB_GIFTS || mediaPages[a].selectedType == TAB_WAYSGRAM_GIFTS) {"
    )
    updated = replace_exactly_once(updated, search_condition, search_patched, "profile gifts search visibility")

    load_more_condition = (
        "        } else if (mediaPage.selectedType != TAB_RECOMMENDED_CHANNELS && mediaPage.selectedType != TAB_SAVED_MESSAGES && mediaPage.selectedType != TAB_BOT_PREVIEWS && mediaPage.selectedType != TAB_GIFTS) {"
    )
    load_more_patched = (
        "        } else if (mediaPage.selectedType != TAB_RECOMMENDED_CHANNELS && mediaPage.selectedType != TAB_SAVED_MESSAGES && mediaPage.selectedType != TAB_BOT_PREVIEWS && mediaPage.selectedType != TAB_GIFTS && mediaPage.selectedType != TAB_WAYSGRAM_GIFTS) {"
    )
    updated = replace_exactly_once(updated, load_more_condition, load_more_patched, "profile gifts load-more exclusion")

    media_loading_branch = (
        "            } else if (mediaPages[a].selectedType == TAB_GIFTS) {\n"
        "\n"
        "            } else {\n"
        "                int type = mediaPages[a].selectedType;\n"
    )
    media_loading_branch_patched = (
        "            } else if (mediaPages[a].selectedType == TAB_GIFTS) {\n"
        "\n"
        "            } else if (mediaPages[a].selectedType == TAB_WAYSGRAM_GIFTS) {\n"
        "\n"
        "            } else {\n"
        "                int type = mediaPages[a].selectedType;\n"
    )
    updated = replace_exactly_once(
        updated, media_loading_branch, media_loading_branch_patched,
        "profile gifts initial media loading exclusion"
    )

    search_method_marker = "    public boolean isSearchItemVisible(int type) {\n"
    search_method_guard = "        if (type == TAB_WAYSGRAM_GIFTS) {\n            return false;\n        }\n"
    if search_method_guard not in updated:
        updated = replace_exactly_once(
            updated, search_method_marker, search_method_marker + search_method_guard,
            "profile gifts search exclusion"
        )

    return updated



def patch_profile_gifts_container_source(original: str) -> str:
    """Let the original Telegram profile gifts UI render a separate WaysGram inventory."""
    markers = (
        "private final boolean waysGramInventory;",
        "setWaysGramInventory(ArrayList<TL_stars.SavedStarGift> gifts)",
        "setWaysGramGiftUpdatedListener(Runnable listener)",
    )
    if all(marker in original for marker in markers):
        return original
    if any(marker in original for marker in markers):
        raise ValueError("ProfileGiftsContainer contains a partial WaysGram inventory patch")

    updated = replace_exactly_once(
        original,
        "    private final long dialogId;\n",
        "    private final long dialogId;\n    private final boolean waysGramInventory;\n    private Runnable waysGramGiftUpdatedListener;\n",
        "native gifts container inventory mode field",
    )

    constructor_match = re.search(
        r"(?m)^[ \t]*public ProfileGiftsContainer\s*\((?P<params>.*?)\)\s*\{\s*super\(context\);",
        updated,
        re.DOTALL,
    )
    if constructor_match is None:
        raise ValueError("Could not locate native ProfileGiftsContainer constructor in archived Telegram source")
    parameter_parts = [part.strip() for part in constructor_match.group("params").split(",") if part.strip()]
    parameter_names = [part.split()[-1] for part in parameter_parts]
    constructor_start = constructor_match.start()
    open_brace = updated.find("{", constructor_start)
    super_call = "super(context);"
    super_position = updated.find(super_call, open_brace)
    if open_brace < 0 or super_position < 0 or not parameter_names:
        raise ValueError("Native ProfileGiftsContainer constructor signature is not supported")
    original_header = updated[constructor_start:open_brace + 1]
    overload_header = (
        original_header[:original_header.rfind(")")]
        + ",\n        boolean waysGramInventory\n    ) {"
    )
    wrapper = (
        original_header
        + "\n        this(" + ", ".join(parameter_names + ["false"]) + ");\n"
        + "    }\n\n"
        + overload_header
        + "\n        super(context);"
    )
    super_end = super_position + len(super_call)
    updated = updated[:constructor_start] + wrapper + updated[super_end:]
    updated = replace_exactly_once(
        updated,
        "        this.fragment = fragment;\n\n        this.currentAccount = currentAccount;\n",
        "        this.fragment = fragment;\n        this.waysGramInventory = waysGramInventory;\n\n        this.currentAccount = currentAccount;\n",
        "native gifts container inventory mode initialization",
    )

    native_store = (
        "        StarsController.getInstance(currentAccount).invalidateProfileGifts(dialogId);\n"
        "        this.list = StarsController.getInstance(currentAccount).getProfileGiftsList(dialogId);\n"
        "        this.collections = StarsController.getInstance(currentAccount).getProfileGiftCollectionsList(dialogId, true);\n"
        "        this.collections.all = list;\n"
        "        this.list.shown = true;\n"
        "        if (fragment instanceof ProfileActivity && ((ProfileActivity) fragment).openGiftsUpgradable) {\n"
        "            this.list.setFilters(StarsController.GiftsList.INCLUDE_TYPE_UPGRADABLE_FLAG);\n"
        "        } else {\n"
        "            this.list.resetFilters();\n"
        "        }\n"
        "        this.list.load();\n"
    )
    external_store = (
        "        if (waysGramInventory) {\n"
        "            this.list = new StarsController.GiftsList(currentAccount, dialogId, false);\n"
        "            this.collections = new StarsController.GiftsCollections(currentAccount, dialogId, false);\n"
        "            this.list.endReached = true;\n"
        "            this.collections.loaded = true;\n"
        "        } else {\n"
        "            StarsController.getInstance(currentAccount).invalidateProfileGifts(dialogId);\n"
        "            this.list = StarsController.getInstance(currentAccount).getProfileGiftsList(dialogId);\n"
        "            this.collections = StarsController.getInstance(currentAccount).getProfileGiftCollectionsList(dialogId, true);\n"
        "        }\n"
        "        this.collections.all = list;\n"
        "        this.list.shown = !waysGramInventory;\n"
        "        if (!waysGramInventory) {\n"
        "            if (fragment instanceof ProfileActivity && ((ProfileActivity) fragment).openGiftsUpgradable) {\n"
        "                this.list.setFilters(StarsController.GiftsList.INCLUDE_TYPE_UPGRADABLE_FLAG);\n"
        "            } else {\n"
        "                this.list.resetFilters();\n"
        "            }\n"
        "            this.list.load();\n"
        "        }\n"
    )
    updated = replace_exactly_once(updated, native_store, external_store, "native versus WaysGram gifts data source")

    scroll_load = (
        "                    if (isAttachedToWindow() && (!listView.canScrollVertically(1) || isLoadingVisible())) {\n"
        "                        list.load();\n"
        "                    }\n"
    )
    updated = replace_exactly_once(
        updated, scroll_load,
        "                    if (!parent.waysGramInventory && isAttachedToWindow() && (!listView.canScrollVertically(1) || isLoadingVisible())) {\n"
        "                        list.load();\n"
        "                    }\n",
        "disable Telegram pagination for WaysGram gifts",
    )
    bind_load = (
        "            if (list != null) {\n"
        "                list.load();\n"
        "            }\n"
        "            update(false);\n"
    )
    updated = replace_exactly_once(
        updated, bind_load,
        "            if (list != null && !parent.waysGramInventory) {\n"
        "                list.load();\n"
        "            }\n"
        "            update(false);\n",
        "disable Telegram initial page load for WaysGram gifts",
    )
    scroll_load_2 = (
        "                if (list != null && isAttachedToWindow() && (!listView.canScrollVertically(1) || isLoadingVisible())) {\n"
        "                    list.load();\n"
        "                }\n"
    )
    updated = replace_exactly_once(
        updated, scroll_load_2,
        "                if (!parent.waysGramInventory && list != null && isAttachedToWindow() && (!listView.canScrollVertically(1) || isLoadingVisible())) {\n"
        "                    list.load();\n"
        "                }\n",
        "disable Telegram scroll pagination for WaysGram gifts",
    )

    if "waysGramInventoryBottomSpace" not in updated:
        fill_anchor = (
            "            if (parent.list == list) {\n"
            "                items.add(UItem.asSpace(dp(20)));\n"
        )
        fill_replacement = (
            "            if (parent.waysGramInventory) {\n"
            "                items.add(UItem.asSpace(dp(120))); // waysGramInventoryBottomSpace\n"
            "            }\n"
            + fill_anchor
        )
        updated = replace_exactly_once(
            updated, fill_anchor, fill_replacement,
            "WaysGram inventory bottom scroll space"
        )

    click_match = re.search(
        r"(?m)^(?P<indent>[ \t]*)(?:(?:public|private|protected)\s+)?void\s+onItemClick\s*\([^)]*\)\s*\{",
        updated,
    )
    if click_match is None:
        raise ValueError("Could not locate native gift item click handler")
    gift_update_callback = (
        "                        .setOnGiftUpdatedListener(() -> {\n"
        "                            update(false);\n"
        "                        })\n"
    )
    gift_update_callback_patched = (
        "                        .setOnGiftUpdatedListener(() -> {\n"
        "                            update(false);\n"
        "                            if (parent.waysGramInventory && parent.waysGramGiftUpdatedListener != null) {\n"
        "                                parent.waysGramGiftUpdatedListener.run();\n"
        "                            }\n"
        "                        })\n"
    )
    if "parent.waysGramGiftUpdatedListener.run();" not in updated:
        updated = replace_exactly_once(
            updated, gift_update_callback, gift_update_callback_patched,
            "refresh WaysGram inventory after native detail sheet update"
        )

    # Keep the original click handler: WaysGram cards open the native Telegram detail sheet.

    long_press_match = re.search(
        r"(?m)^(?P<indent>[ \t]*)(?:(?:public|private|protected)\s+)?boolean\s+onItemLongPress\s*\([^)]*\)\s*\{",
        updated,
    )
    if long_press_match is None:
        raise ValueError("Could not locate native gift item long-press handler")
    long_press_insert = (
        "\n" + long_press_match.group("indent") + "    if (parent.waysGramInventory || list == null) return false;"
    )
    updated = updated[:long_press_match.end()] + long_press_insert + updated[long_press_match.end():]

    updated = replace_exactly_once(
        updated,
        "    public boolean canAdd() {\n        if (!collections.isMine()) return false;\n",
        "    public boolean canAdd() {\n        if (waysGramInventory || !collections.isMine()) return false;\n",
        "disable Telegram collections for WaysGram gifts",
    )
    updated = replace_exactly_once(
        updated,
        "    public void updateButton() {\n        if (viewPager == null) return;\n",
        "    public void updateButton() {\n"
        "        if (waysGramInventory) {\n"
        "            if (buttonContainer != null) buttonContainer.setVisibility(View.GONE);\n"
        "            if (bulletinContainer != null) bulletinContainer.setVisibility(View.GONE);\n"
        "            if (checkboxLayout != null) checkboxLayout.setVisibility(View.GONE);\n"
        "            return;\n"
        "        }\n"
        "        if (viewPager == null) return;\n",
        "hide native Telegram gift purchase button for WaysGram inventory",
    )
    updated = replace_exactly_once(
        updated,
        "    public boolean canFilter() {\n        return true;\n",
        "    public boolean canFilter() {\n        return !waysGramInventory;\n",
        "disable unsupported Telegram gift filters for WaysGram",
    )
    updated = replace_exactly_once(
        updated,
        "    public boolean canFilterHidden() {\n        if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) return true;\n",
        "    public boolean canFilterHidden() {\n        if (waysGramInventory) return false;\n"
        "        if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) return true;\n",
        "disable hidden gift filter for WaysGram inventory",
    )
    updated = replace_exactly_once(
        updated,
        "    public boolean canReorder() {\n        if (dialogId >= 0) return dialogId == 0 || dialogId == UserConfig.getInstance(currentAccount).getClientUserId();\n",
        "    public boolean canReorder() {\n        if (waysGramInventory) return false;\n"
        "        if (dialogId >= 0) return dialogId == 0 || dialogId == UserConfig.getInstance(currentAccount).getClientUserId();\n",
        "disable Telegram gift reordering for WaysGram inventory",
    )

    attach_list = (
        "        if (list != null) {\n"
        "            list.shown = true;\n"
        "            list.load();\n"
        "        }\n"
        "        if (collections != null) {\n"
        "            collections.shown = true;\n"
        "            collections.load();\n"
        "        }\n"
    )
    updated = replace_exactly_once(
        updated, attach_list,
        "        if (list != null) {\n"
        "            list.shown = !waysGramInventory;\n"
        "            if (!waysGramInventory) list.load();\n"
        "        }\n"
        "        if (collections != null) {\n"
        "            collections.shown = !waysGramInventory;\n"
        "            if (!waysGramInventory) collections.load();\n"
        "        }\n",
        "disable native Telegram network fetches on WaysGram tab attach",
    )

    factory_pos = updated.find("buttonGlassFactory", constructor_start)
    if factory_pos >= 0:
        background_pos = updated.find(".setBackground(", factory_pos)
        if background_pos < 0:
            raise ValueError("ProfileGiftsContainer uses buttonGlassFactory but no matching background binding was found")
        block_start = updated.rfind("\n", 0, factory_pos) + 1
        background_line_end = updated.find("\n", background_pos)
        block_end = len(updated) if background_line_end < 0 else background_line_end + 1
        block = updated[block_start:block_end]
        indent = block[:len(block) - len(block.lstrip(" \t"))]
        wrapped = indent + "if (!waysGramInventory) {\n"
        for line in block.splitlines(True):
            wrapped += (indent + "    " + line[len(indent):]) if line.strip() else line
        wrapped += indent + "}\n"
        updated = updated[:block_start] + wrapped + updated[block_end:]

    method = (
        "    public void setWaysGramGiftUpdatedListener(Runnable listener) {\n"
        "        this.waysGramGiftUpdatedListener = listener;\n"
        "    }\n\n"
        "    public void setWaysGramInventory(ArrayList<TL_stars.SavedStarGift> gifts) {\n"
        "        if (!waysGramInventory) return;\n"
        "        list.gifts.clear();\n"
        "        if (gifts != null) list.gifts.addAll(gifts);\n"
        "        list.totalCount = list.gifts.size();\n"
        "        list.endReached = true;\n"
        "        list.loading = false;\n"
        "        list.lastOffset = null;\n"
        "        list.notifyUpdate();\n"
        "        Page page = getCurrentPage();\n"
        "        if (page != null) page.update(false);\n"
        "        updateButton();\n"
        "    }\n\n"
        "    public void replaceWaysGramInventory(ArrayList<TL_stars.SavedStarGift> gifts) {\n"
        "        if (!waysGramInventory || gifts == null) return;\n"
        "        Page page = getCurrentPage();\n"
        "        int waysGramFirst = -1;\n"
        "        int waysGramOffset = 0;\n"
        "        if (page != null && page.listView.getLayoutManager()\n"
        "                instanceof androidx.recyclerview.widget.LinearLayoutManager) {\n"
        "            androidx.recyclerview.widget.LinearLayoutManager manager =\n"
        "                    (androidx.recyclerview.widget.LinearLayoutManager) page.listView.getLayoutManager();\n"
        "            waysGramFirst = manager.findFirstVisibleItemPosition();\n"
        "            View firstChild = manager.findViewByPosition(waysGramFirst);\n"
        "            waysGramOffset = firstChild == null ? 0 : firstChild.getTop();\n"
        "        }\n"
        "        list.gifts.clear();\n"
        "        list.gifts.addAll(gifts);\n"
        "        list.totalCount = list.gifts.size();\n"
        "        list.notifyUpdate();\n"
        "        if (page != null) {\n"
        "            page.update(false);\n"
        "            if (waysGramFirst > 0 && page.listView.getLayoutManager()\n"
        "                    instanceof androidx.recyclerview.widget.LinearLayoutManager) {\n"
        "                ((androidx.recyclerview.widget.LinearLayoutManager) page.listView.getLayoutManager())\n"
        "                        .scrollToPositionWithOffset(waysGramFirst, waysGramOffset);\n"
        "            }\n"
        "        }\n"
        "        updateButton();\n"
        "    }\n"
        "\n"
    )
    insert_at = updated.rfind("\n}")
    if insert_at < 0:
        raise ValueError("ProfileGiftsContainer closing brace not found")
    updated = updated[:insert_at] + "\n\n" + method + updated[insert_at:]
    return updated




def patch_star_gift_sheet_display_source(original: str) -> str:
    """Let the native saved-gift card draw a WaysGram gift exactly like a Telegram one.

    The card keeps Telegram's own artwork, backdrop and attribute rendering: the inventory gives
    the gift a real sticker document, so nothing here has to paint over it. Only the WaysGram
    owned actions (a fixed-price upgrade and the sell row) are added.
    """
    marker = "private boolean isWaysGramGift()"
    if marker in original:
        required = (
            "private String waysGramGiftSerial()",
            "topView.buttonsLayout.setVisibility(View.GONE);",
            "if (isWaysGramGift()) return false;",
            "if (isWaysGramGift()) return null;",
        )
        if not all(value in original for value in required):
            raise ValueError("StarGiftSheet contains an incomplete WaysGram display patch")
        return original

    methods = (
        "    private String waysGramGiftSerial() {\n"
        "        if (savedStarGift == null || savedStarGift.gift == null || TextUtils.isEmpty(savedStarGift.gift.slug)) {\n"
        "            return null;\n"
        "        }\n"
        "        final String prefix = \"waysgram://\";\n"
        "        if (!savedStarGift.gift.slug.startsWith(prefix)) {\n"
        "            return null;\n"
        "        }\n"
        "        String serial = savedStarGift.gift.slug.substring(prefix.length()).trim();\n"
        "        return serial.isEmpty() ? null : serial;\n"
        "    }\n\n"
        "    private boolean isWaysGramGift() {\n"
        "        return waysGramGiftSerial() != null;\n"
        "    }\n\n"
    )
    original = replace_exactly_once(
        original,
        "    private boolean canDelete() {\n",
        methods + "    private boolean canDelete() {\n",
        "WaysGram native detail identity helpers"
    )

    waysgram_detail_ui = (
        "        if (isWaysGramGift()) {\n"
        "            // Telegram hides the actions it would take on its own server; the artwork, the\n"
        "            // backdrop and the attributes stay the native ones.\n"
        "            topView.setShowOptions(false);\n"
        "            topView.buttonsLayout.setVisibility(View.GONE);\n"
        "            String waysGramSerial = waysGramGiftSerial();\n"
        "            // WaysGram upgrade: a fixed 25 Stars through the WaysGram backend,\n"
        "            // never through the native Telegram Stars payment flow.\n"
        "            if (myProfile && button != null\n"
        "                    && (savedStarGift.gift instanceof TL_stars.TL_starGift\n"
        "                        || savedStarGift.gift instanceof TL_stars.TL_starGiftUnique)) {\n"
        "                button.setFilled(true);\n"
        "                button.setText(\"Улучшить за \" + org.telegram.messenger.waysgram.WaysGramGiftUpgrade.DEFAULT_PRICE_STARS + \" \\u2b50\", !firstSet);\n"
        "                button.setSubText(null, !firstSet);\n"
        "                button.setOnClickListener(v -> org.telegram.messenger.waysgram.WaysGramGiftUpgrade.upgrade(\n"
        "                        getContext(), currentAccount, waysGramSerial,\n"
        "                        StarGiftSheet.this::onBackPressed));\n"
        "            }\n"
        "        }\n\n"
    )
    original = replace_exactly_once(
        original,
        "        if (savedStarGift.refunded) {\n",
        waysgram_detail_ui + "        if (savedStarGift.refunded) {\n",
        "native WaysGram detail action isolation"
    )
    original = replace_exactly_once(
        original,
        "    private boolean canDelete() {\n        if (walletCollectible != null || getInputStarGift() == null) return false;\n",
        "    private boolean canDelete() {\n        if (isWaysGramGift()) return false;\n"
        "        if (walletCollectible != null || getInputStarGift() == null) return false;\n",
        "prevent Telegram-native deletion of WaysGram gifts"
    )
    original = replace_exactly_once(
        original,
        "    public boolean canTransfer() {\n        if (getInputStarGift() == null) return false;\n",
        "    public boolean canTransfer() {\n        if (isWaysGramGift()) return false;\n"
        "        if (getInputStarGift() == null) return false;\n",
        "prevent Telegram-native transfer of WaysGram gifts"
    )
    original = replace_exactly_once(
        original,
        "    private String getLink() {\n        final TL_stars.StarGift starGift = getGift();\n",
        "    private String getLink() {\n        if (isWaysGramGift()) return null;\n"
        "        final TL_stars.StarGift starGift = getGift();\n",
        "prevent invalid Telegram NFT links for WaysGram gifts"
    )
    return original



def patch_star_gift_sheet_sell_row(original: str) -> str:
    """Offer a sell row in the saved-gift card of a WaysGram gift.

    Telegram's own conversion row stays empty for WaysGram gifts, so the row sits between the
    acquisition date and the value. Only a gift that was never upgraded is sold.
    """
    marker = "WaysGram: sell row of a saved gift"
    if marker in original:
        return original
    match = re.search(
        r"(?m)^[ \t]*tableView\.addRow\(getString\(R\.string\.Gift2Value\), "
        r"replaceStarsWithPlain\(TextUtils\.concat\(\"\u2b50\ufe0f \" \+ "
        r"LocaleController\.formatNumber\(savedStarGift\.gift\.stars",
        original,
    )
    if match is None:
        raise ValueError("Could not locate the saved gift value row of the native gift sheet")
    indent = re.match(r"[ \t]*", original[match.start():]).group(0)
    row = (
        indent + "// " + marker + ".\n"
        + indent + "if (isWaysGramGift() && savedStarGift.gift instanceof TL_stars.TL_starGift) {\n"
        + indent + "    tableView.addRow(\"Продать подарок\", \"Вернём половину цены в Stars\", \"Продать\",\n"
        + indent + "            () -> org.telegram.messenger.waysgram.WaysGramGiftSell.confirm(\n"
        + indent + "                    getContext(), currentAccount, waysGramGiftSerial(),\n"
        + indent + "                    StarGiftSheet.this::onBackPressed));\n"
        + indent + "}\n"
    )
    return original[:match.start()] + row + original[match.start():]


def patch_waysgram_mapper_source(original: str) -> str:
    """Render WaysGram gift messages as Telegram's native gift cards."""
    marker = "WaysGramGiftMessages.toServiceMessage"
    if marker in original:
        required = (
            "public static TLRPC.Message toMessage(JSONObject m, long selfId, int account)",
            marker,
        )
        if not all(value in original for value in required):
            raise ValueError("WaysGramMapper contains an incomplete WaysGram card patch")
        return original

    anchor = (
        "    public static TLRPC.TL_message toMessage(JSONObject m, long selfId) {\n"
        "        return toMessage(\n"
        "                m.optLong(\"id\"),\n"
        "                m.optLong(\"sender_id\"),\n"
        "                m.optLong(\"recipient_id\"),\n"
        "                m.optString(\"body\", \"\"),\n"
        "                m.optInt(\"created_at\"),\n"
        "                selfId);\n"
        "    }\n"
    )
    replacement = (
        "    public static TLRPC.Message toMessage(JSONObject m, long selfId) {\n"
        "        return toMessage(m, selfId, org.telegram.messenger.UserConfig.selectedAccount);\n"
        "    }\n"
        "\n"
        "    /**\n"
        "     * Gift purchases, transfers and received gifts are drawn with Telegram's\n"
        "     * native gift card instead of a plain text bubble.\n"
        "     */\n"
        "    public static TLRPC.Message toMessage(JSONObject m, long selfId, int account) {\n"
        "        final TLRPC.Message waysGramGiftMessage =\n"
        "                org.telegram.messenger.waysgram.WaysGramGiftMessages.toServiceMessage(account, m, selfId);\n"
        "        if (waysGramGiftMessage != null) {\n"
        "            return waysGramGiftMessage;\n"
        "        }\n"
        "        return toMessage(\n"
        "                m.optLong(\"id\"),\n"
        "                m.optLong(\"sender_id\"),\n"
        "                m.optLong(\"recipient_id\"),\n"
        "                m.optString(\"body\", \"\"),\n"
        "                m.optInt(\"created_at\"),\n"
        "                selfId);\n"
        "    }\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram gift card message mapping")


def patch_waysgram_gift_message_callers(original: str) -> str:
    """Pass the account slot into the gift-aware message mapper."""
    updated = original
    plain = "WaysGramMapper.toMessage(m, selfId)"
    with_account = "WaysGramMapper.toMessage(m, selfId, account)"
    if plain in updated:
        updated = updated.replace(plain, with_account)
    if with_account not in updated:
        raise ValueError("WaysGram message mapper call site is missing")
    return updated


def patch_waysgram_gifts_source(original: str) -> str:
    """Serve gift artwork reliably: valid mime, retries and safe placeholders."""
    ensure_marker = "isValidSticker(file)"
    placeholder_marker = "stickerDocumentId(file)"
    if ensure_marker in original and placeholder_marker in original:
        required = (
            "downloadStickerBytes(account, giftId, true)",
            "?animated=1",
        )
        if not all(value in original for value in required):
            raise ValueError("WaysGramGifts contains an incomplete artwork download patch")
        return original

    start = original.index("    private static File ensureSticker(int account, String giftId) {")
    end = original.index("    private static String mimeFor(JSONObject sticker) {")
    replacement = (
        "    private static File ensureSticker(int account, String giftId) {\n"
        "        long stickerId = parseId(giftId);\n"
        "        File file = new File(stickerDir(), stickerId + \".bin\");\n"
        "        if (isValidSticker(file)) {\n"
        "            return file;\n"
        "        }\n"
        "        if (file.exists() && !file.delete()) {\n"
        "            // A stale or truncated file must never hide the real artwork.\n"
        "            return placeholderSticker(stickerId);\n"
        "        }\n"
        "        Long failedAt = failedStickers.get(stickerId);\n"
        "        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_DELAY_MS) {\n"
        "            return placeholderSticker(stickerId);\n"
        "        }\n"
        "        byte[] bytes = downloadStickerBytes(account, giftId, true);\n"
        "        if (bytes == null) {\n"
        "            bytes = downloadStickerBytes(account, giftId, false);\n"
        "        }\n"
        "        if (bytes != null && writeStickerFile(file, bytes)) {\n"
        "            failedStickers.remove(stickerId);\n"
        "            return file;\n"
        "        }\n"
        "        failedStickers.put(stickerId, System.currentTimeMillis());\n"
        "        return placeholderSticker(stickerId);\n"
        "    }\n"
        "\n"
        "    private static byte[] downloadStickerBytes(int account, String giftId, boolean animated) {\n"
        "        try {\n"
        "            String path = \"/api/telegram/gifts/\" + android.net.Uri.encode(giftId) + \"/sticker\"\n"
        "                    + (animated ? \"?animated=1\" : \"\");\n"
        "            byte[] bytes = WaysGramApi.download(account, path);\n"
        "            return bytes == null || bytes.length == 0 ? null : bytes;\n"
        "        } catch (Exception e) {\n"
        "            FileLog.e(e);\n"
        "            return null;\n"
        "        }\n"
        "    }\n"
        "\n"
        "    private static boolean isValidSticker(File file) {\n"
        "        if (file == null || !file.isFile() || file.length() < 16) {\n"
        "            return false;\n"
        "        }\n"
        "        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {\n"
        "            byte[] head = new byte[16];\n"
        "            int read = in.read(head);\n"
        "            return read >= 4\n"
        "                    && org.telegram.messenger.waysgram.WaysGramGiftDocuments.sniffMime(head) != null;\n"
        "        } catch (Exception e) {\n"
        "            return false;\n"
        "        }\n"
        "    }\n"
        "\n"
        "    private static boolean writeStickerFile(File file, byte[] bytes) {\n"
        "        try {\n"
        "            File tmp = new File(file.getPath() + \".tmp\");\n"
        "            try (FileOutputStream out = new FileOutputStream(tmp)) {\n"
        "                out.write(bytes);\n"
        "            }\n"
        "            if (file.exists() && !file.delete()) {\n"
        "                tmp.delete();\n"
        "                return false;\n"
        "            }\n"
        "            return tmp.renameTo(file);\n"
        "        } catch (Exception e) {\n"
        "            FileLog.e(e);\n"
        "            return false;\n"
        "        }\n"
        "    }\n"
        "\n"
        "    /** Document id of the file that is actually served for this gift artwork. */\n"
        "    static long stickerDocumentId(File file) {\n"
        "        if (file == null) {\n"
        "            return 0;\n"
        "        }\n"
        "        String name = file.getName();\n"
        "        int dot = name.indexOf('.');\n"
        "        if (dot > 0) {\n"
        "            name = name.substring(0, dot);\n"
        "        }\n"
        "        try {\n"
        "            return Long.parseLong(name);\n"
        "        } catch (Exception e) {\n"
        "            return parseId(name);\n"
        "        }\n"
        "    }\n"
        "\n"
    )
    updated = original[:start] + replacement + original[end:]

    # A placeholder must never occupy the real artwork file name.
    placeholder_anchor = (
        "        try {\n"
        "            File file = new File(stickerDir(), id + \".bin\");\n"
        "            if (file.exists() && file.length() > 0) {\n"
        "                return file;\n"
        "            }\n"
    )
    placeholder_replacement = (
        "        try {\n"
        "            // Keep the placeholder on its own document id: a failed download\n"
        "            // must not block the real artwork forever.\n"
        "            long placeholderId = (id * 1000003L + 7L) & 0x7FFFFFFFFFFFFFFFL;\n"
        "            if (placeholderId == 0) {\n"
        "                placeholderId = id + 1;\n"
        "            }\n"
        "            File file = new File(stickerDir(), placeholderId + \".bin\");\n"
        "            if (file.exists() && file.length() > 0) {\n"
        "                return file;\n"
        "            }\n"
    )
    updated = replace_exactly_once(
        updated, placeholder_anchor, placeholder_replacement,
        "WaysGram placeholder sticker separation"
    )

    # Serve the artwork with its real mime type and animation attributes.
    doc_anchor = "        doc.attributes.add(st);\n"
    doc_replacement = (
        "        doc.attributes.add(st);\n"
        "        TLRPC.TL_document sniffedDocument =\n"
        "                org.telegram.messenger.waysgram.WaysGramGiftDocuments.buildDocument(\n"
        "                        stickerDocumentId(file), file, g.optString(\"emoji\", \"\"));\n"
        "        if (sniffedDocument != null) {\n"
        "            doc = sniffedDocument;\n"
        "        }\n"
    )
    updated = replace_exactly_once(updated, doc_anchor, doc_replacement, "WaysGram gift artwork mime type")

    cache_fields_anchor = "    private static long cacheAt;\n"
    cache_fields_replacement = (
        cache_fields_anchor
        + "    private static final HashMap<Long, Long> failedStickers = new HashMap<>();\n"
        + "    private static final long RETRY_DELAY_MS = 5 * 60 * 1000L;\n"
    )
    updated = replace_exactly_once(
        updated, cache_fields_anchor, cache_fields_replacement,
        "WaysGram gift artwork retry cache"
    )
    return updated


def patch_push_service_source(original: str) -> str:
    """Route WaysGram FCM messages separately and refresh its secondary token."""
    message_marker = "WaysGramPushNotifications.show(this, message);"
    token_marker = "WaysGramPushManager.sync(this);"
    if message_marker in original and token_marker in original:
        return original
    if message_marker in original or token_marker in original:
        raise ValueError("GcmPushListenerService contains a partial WaysGram push patch")

    updated = replace_exactly_once(
        original,
        "package org.telegram.messenger;\n",
        "package org.telegram.messenger;\n\n"
        "import org.telegram.messenger.waysgram.WaysGramPushManager;\n"
        "import org.telegram.messenger.waysgram.WaysGramPushNotifications;\n",
        "FCM service WaysGram imports",
    )
    updated = replace_exactly_once(
        updated,
        "        PushListenerController.processRemoteMessage("
        "PushListenerController.PUSH_TYPE_FIREBASE, data.get(\"p\"), time);\n",
        "        if (\"1\".equals(data.get(\"waysgram\"))) {\n"
        "            WaysGramPushNotifications.show(this, message);\n"
        "            return;\n"
        "        }\n"
        "        PushListenerController.processRemoteMessage("
        "PushListenerController.PUSH_TYPE_FIREBASE, data.get(\"p\"), time);\n",
        "FCM message routing",
    )
    updated = replace_exactly_once(
        updated,
        "            PushListenerController.sendRegistrationToServer("
        "PushListenerController.PUSH_TYPE_FIREBASE, token);\n",
        "            PushListenerController.sendRegistrationToServer("
        "PushListenerController.PUSH_TYPE_FIREBASE, token);\n"
        "            WaysGramPushManager.sync(this);\n",
        "FCM secondary token refresh",
    )
    return updated


def patch_push_listener_controller_source(original: str) -> str:
    """Keep Telegram's push token registered with its original named Firebase app."""
    before = (
        "                    FirebaseApp.initializeApp(ApplicationLoader.applicationContext);\n"
        "                    FirebaseMessaging.getInstance().getToken()"
    )
    marker = "TelegramFirebaseConfig.getMessaging(ApplicationLoader.applicationContext)"
    if marker in original:
        if "telegramMessaging.getToken()" not in original:
            raise ValueError("PushListenerController contains a partial Telegram Firebase isolation patch")
        return original

    after = (
        "                    FirebaseMessaging telegramMessaging = "
        "org.telegram.messenger.waysgram.TelegramFirebaseConfig.getMessaging("
        "ApplicationLoader.applicationContext);\n"
        "                    if (telegramMessaging == null) {\n"
        "                        throw new IllegalStateException(\"Telegram Firebase messaging is not configured\");\n"
        "                    }\n"
        "                    telegramMessaging.getToken()"
    )
    return replace_exactly_once(
        original,
        before,
        after,
        "Telegram Firebase named-app token provider",
    )


def patch_notification_permissions(android_root: Path) -> None:
    """Declare Android 13+ notification permission in both app manifests."""
    anchor = '    <uses-permission android:name="com.google.android.c2dm.permission.RECEIVE" />\n'
    permission = '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n'
    for variant in ("debug", "release"):
        manifest = android_root / f"TMessagesProj/config/{variant}/AndroidManifest.xml"
        if not manifest.is_file():
            raise FileNotFoundError(f"Required {variant} manifest not found: {manifest}")
        content = manifest.read_text(encoding="utf-8")
        if permission in content:
            continue
        updated = replace_exactly_once(
            content,
            anchor,
            anchor + permission,
            f"{variant} notification permission",
        )
        manifest.write_text(updated, encoding="utf-8")


def patch_location_permissions(android_root: Path) -> None:
    """Declare runtime and foreground-service location permissions in both app manifests."""
    anchor = '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n'
    permissions = (
        '    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />\n'
        '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n'
        '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n'
        '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />\n'
    )
    for variant in ("debug", "release"):
        manifest = android_root / f"TMessagesProj/config/{variant}/AndroidManifest.xml"
        if not manifest.is_file():
            raise FileNotFoundError(f"Required {variant} manifest not found: {manifest}")
        content = manifest.read_text(encoding="utf-8")
        missing = "".join(
            permission for permission in permissions.splitlines(keepends=True)
            if permission not in content
        )
        if not missing:
            continue
        if anchor not in content:
            raise ValueError(
                f"Cannot add foreground location permissions to {variant}: "
                "notification permission anchor is missing"
            )
        updated = content.replace(anchor, anchor + missing, 1)
        manifest.write_text(updated, encoding="utf-8")


def patch_live_location_service(android_root: Path) -> None:
    """Register the WaysGram foreground location service in both app manifests."""
    declaration = (
        '        <service\n'
        '            android:name="org.telegram.messenger.waysgram.WaysGramLiveLocationService"\n'
        '            android:exported="false"\n'
        '            android:stopWithTask="false"\n'
        '            android:foregroundServiceType="location" />\n'
    )
    for variant in ("debug", "release"):
        manifest = android_root / f"TMessagesProj/config/{variant}/AndroidManifest.xml"
        if not manifest.is_file():
            raise FileNotFoundError(f"Required {variant} manifest not found: {manifest}")
        content = manifest.read_text(encoding="utf-8")
        if declaration in content:
            continue
        anchor = "    </application>\n"
        if content.count(anchor) != 1:
            raise ValueError(f"Cannot register WaysGram live location service in {variant} manifest")
        manifest.write_text(content.replace(anchor, declaration + anchor, 1), encoding="utf-8")


def patch_translation_dependencies(android_root: Path) -> None:
    """Add on-device translation and language-identification libraries to WaysGram."""
    gradle_file = android_root / "TMessagesProj/build.gradle"
    if not gradle_file.is_file():
        raise FileNotFoundError(f"Telegram Android module Gradle file not found: {gradle_file}")
    content = gradle_file.read_text(encoding="utf-8")
    dependencies = (
        "implementation 'com.google.mlkit:language-id:17.0.6'",
        "implementation 'com.google.mlkit:translate:17.0.3'",
    )
    present = [dependency in content for dependency in dependencies]
    if all(present):
        return
    if any(present):
        raise ValueError("TMessagesProj/build.gradle contains a partial ML Kit translation dependency patch")

    addition = (
        "\n\n// WaysGram on-device message translation.\n"
        "repositories {\n"
        "    google()\n"
        "}\n"
        "dependencies {\n"
        "    implementation 'com.google.mlkit:language-id:17.0.6'\n"
        "    implementation 'com.google.mlkit:translate:17.0.3'\n"
        "}\n"
    )
    gradle_file.write_text(content.rstrip() + addition, encoding="utf-8")


def patch_waysgram_gift_integration(android_root: Path) -> None:
    """Apply gift card mapping and reliable shop artwork to the WaysGram transport."""
    mapper = android_root / "TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramMapper.java"
    if not mapper.is_file():
        raise FileNotFoundError(f"Required WaysGram message mapper not found: {mapper}")
    mapper_source = mapper.read_text(encoding="utf-8")
    patched_mapper = patch_waysgram_mapper_source(mapper_source)
    if patched_mapper != mapper_source:
        mapper.write_text(patched_mapper, encoding="utf-8")

    for relative in (
        "TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramPoller.java",
        "TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramMessages.java",
        "TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramTransport.java",
    ):
        path = android_root / relative
        if not path.is_file():
            raise FileNotFoundError(f"Required WaysGram message source not found: {path}")
        source = path.read_text(encoding="utf-8")
        patched = patch_waysgram_gift_message_callers(source)
        if patched != source:
            path.write_text(patched, encoding="utf-8")

    gifts = android_root / "TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramGifts.java"
    if not gifts.is_file():
        raise FileNotFoundError(f"Required WaysGram gift source not found: {gifts}")
    gifts_source = gifts.read_text(encoding="utf-8")
    patched_gifts = patch_waysgram_gifts_source(gifts_source)
    patched_gifts = patch_waysgram_gift_account_isolation(patched_gifts)
    patched_gifts = patch_waysgram_gift_unlimited_stock(patched_gifts)
    if patched_gifts != gifts_source:
        gifts.write_text(patched_gifts, encoding="utf-8")

    avatar_container = (
        android_root
        / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java"
    )
    if not avatar_container.is_file():
        raise FileNotFoundError(f"Required chat avatar source not found: {avatar_container}")
    avatar_source = avatar_container.read_text(encoding="utf-8")
    patched_avatar = patch_chat_avatar_title_badges(avatar_source)
    patched_avatar = patch_chat_avatar_self_skip(patched_avatar)
    if patched_avatar != avatar_source:
        avatar_container.write_text(patched_avatar, encoding="utf-8")
    if "WaysGramPeerBadges.decorate(" not in avatar_container.read_text(encoding="utf-8"):
        raise ValueError("WaysGram marks next to the chat name were not applied")

    final_mapper = mapper.read_text(encoding="utf-8")
    if "WaysGramGiftMessages.toServiceMessage" not in final_mapper:
        raise ValueError("WaysGram gift cards are not mapped into native Telegram gift messages")
    final_gifts = gifts.read_text(encoding="utf-8")
    for marker in (
        "isValidSticker(file)",
        "?animated=1",
        "stickerDocumentId(file)",
        "WaysGramGiftDocuments.buildDocument(",
        "CatalogSnapshot",
        "install(snapshot);",
        "waysGramUnlimitedSupply",
    ):
        if marker not in final_gifts:
            raise ValueError("WaysGram shop gift artwork patch is incomplete")


def patch_waysgram_gift_account_isolation(original: str) -> str:
    """Keep the WaysGram gift catalog per Telegram account instead of process-wide."""
    if "CatalogSnapshot" in original:
        for required in ("snapshots.clear();", "install(snapshot);"):
            if required not in original:
                raise ValueError("WaysGramGifts contains an incomplete per-account catalog patch")
        return original

    fields = (
        "    private static JSONArray catalogCache;\n"
        "    private static long cacheAt;\n"
    )
    fields_replacement = (
        fields
        + "\n"
        + "    /** Catalog snapshots per Telegram account: gifts must never leak between accounts. */\n"
        + "    private static final HashMap<Integer, CatalogSnapshot> snapshots = new HashMap<>();\n"
        + "\n"
        + "    private static final class CatalogSnapshot {\n"
        + "        JSONArray gifts;\n"
        + "        long at;\n"
        + "        final HashMap<Long, String> originalIds = new HashMap<>();\n"
        + "    }\n"
        + "\n"
        + "    private static void install(CatalogSnapshot snapshot) {\n"
        + "        catalogCache = snapshot.gifts;\n"
        + "        cacheAt = snapshot.at;\n"
        + "        originalIds.clear();\n"
        + "        originalIds.putAll(snapshot.originalIds);\n"
        + "    }\n"
    )
    updated = replace_exactly_once(original, fields, fields_replacement, "WaysGram per-account catalog fields")

    invalidate_anchor = (
        "    public static synchronized void invalidate() {\n"
        "        cacheAt = 0;\n"
        "    }\n"
    )
    invalidate_replacement = (
        "    public static synchronized void invalidate() {\n"
        "        snapshots.clear();\n"
        "        catalogCache = null;\n"
        "        cacheAt = 0;\n"
        "        originalIds.clear();\n"
        "        failedStickers.clear();\n"
        "    }\n"
    )
    updated = replace_exactly_once(updated, invalidate_anchor, invalidate_replacement, "WaysGram per-account catalog invalidation")

    start = updated.index("    private static synchronized void loadCatalog(int account) throws WaysGramApi.ApiException {")
    end = updated.index("    private static String normalize(String s) {")
    replacement = (
        "    private static synchronized void loadCatalog(int account) throws WaysGramApi.ApiException {\n"
        "        long now = System.currentTimeMillis();\n"
        "        CatalogSnapshot snapshot = snapshots.get(account);\n"
        "        if (snapshot != null && now - snapshot.at < CATALOG_TTL_MS) {\n"
        "            install(snapshot);\n"
        "            return;\n"
        "        }\n"
        "        JSONObject tg = WaysGramApi.get(account, \"/api/telegram/gifts?all=1\");\n"
        "        JSONArray gifts = tg.optJSONArray(\"gifts\");\n"
        "        CatalogSnapshot fresh = new CatalogSnapshot();\n"
        "        fresh.gifts = gifts == null ? new JSONArray() : gifts;\n"
        "        fresh.at = now;\n"
        "        for (int i = 0; i < fresh.gifts.length(); i++) {\n"
        "            JSONObject gift = fresh.gifts.optJSONObject(i);\n"
        "            if (gift != null) {\n"
        "                String original = gift.optString(\"id\");\n"
        "                fresh.originalIds.put(parseId(original), original);\n"
        "            }\n"
        "        }\n"
        "        snapshots.put(account, fresh);\n"
        "        install(fresh);\n"
        "    }\n"
        "\n"
    )
    return updated[:start] + replacement + updated[end:]


def patch_chat_avatar_title_badges(original: str) -> str:
    """Draw WaysGram marks next to the chat title in the native action bar."""
    marker = "WaysGramPeerBadges.decorate("
    if marker in original:
        return original

    anchor = (
        "        if (value != null) {\n"
        "            value = Emoji.replaceEmoji(value, titleTextView.getPaint().getFontMetricsInt(), false);\n"
        "        }\n"
    )
    replacement = (
        anchor
        + "        // WaysGram: draw WaysGram marks right next to the peer name.\n"
        + "        value = org.telegram.messenger.waysgram.WaysGramPeerBadges.decorate(\n"
        + "                currentAccount,\n"
        + "                parentFragment == null ? 0 : parentFragment.getDialogId(),\n"
        + "                value,\n"
        + "                () -> {\n"
        + "                    if (parentFragment != null) {\n"
        + "                        parentFragment.updateTitle(true);\n"
        + "                    }\n"
        + "                });\n"
    )
    return replace_exactly_once(original, anchor, replacement, "native chat title WaysGram marks")


def patch_waysgram_gift_unlimited_stock(original: str) -> str:
    """Do not draw an unlimited WaysGram stock as a limited-edition counter."""
    if "unlimited_supply" in original:
        return original
    anchor = (
        "        // The stock is always limited: the administrator sets how many can be sold.\n"
        "        gift.limited = true;\n"
    )
    replacement = (
        "        // Unlimited WaysGram supply must not render as a limited-edition counter.\n"
        "        boolean waysGramUnlimitedSupply = g.optBoolean(\"unlimited_supply\", false);\n"
        "        gift.limited = !waysGramUnlimitedSupply;\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram unlimited gift supply")


def patch_waysgram_api_helpers(original: str) -> str:
    """Extend the REST client with session refresh and a remembered account phone."""
    if "refreshTokenIfNeeded" in original:
        for value in ("SESSION_REFRESH_GRACE_SECONDS", "getRememberedPhone", "rememberPhone"):
            if value not in original:
                raise ValueError("WaysGramApi contains an incomplete session refresh patch")
        return original

    expires_anchor = (
        "    public static long getTokenExpiresAt(int account) {\n"
        "        return prefs().getLong(\"expires_\" + account, 0);\n"
        "    }\n"
    )
    helpers = expires_anchor + (
        "\n"
        "    /** The server extends a session while it is still valid; the token itself never changes. */\n"
        "    private static final long SESSION_REFRESH_GRACE_SECONDS = 7 * 24 * 60 * 60;\n"
        "    private static final java.util.concurrent.atomic.AtomicLong LAST_REFRESH =\n"
        "            new java.util.concurrent.atomic.AtomicLong(0);\n"
        "\n"
        "    public static void refreshTokenIfNeeded(int account) {\n"
        "        String token = getToken(account);\n"
        "        if (TextUtils.isEmpty(token)) {\n"
        "            return;\n"
        "        }\n"
        "        long now = System.currentTimeMillis() / 1000;\n"
        "        long expires = getTokenExpiresAt(account);\n"
        "        if (expires > 0 && expires - now > SESSION_REFRESH_GRACE_SECONDS) {\n"
        "            return;\n"
        "        }\n"
        "        long last = LAST_REFRESH.get();\n"
        "        if (now - last < 120 || !LAST_REFRESH.compareAndSet(last, now)) {\n"
        "            return;\n"
        "        }\n"
        "        try {\n"
        "            JSONObject response = request(\"POST\", \"/api/auth/refresh\", new JSONObject(), token, 20000);\n"
        "            long renewed = response.optLong(\"expires_at\", 0);\n"
        "            if (renewed > 0) {\n"
        "                saveToken(account, token, renewed);\n"
        "            }\n"
        "        } catch (ApiException e) {\n"
        "            // An unusable session is reported by the next normal request as 401.\n"
        "            FileLog.d(\"waysgram: session refresh failed (\" + e.status + \")\");\n"
        "        }\n"
        "    }\n"
        "\n"
        "    /** The phone the server confirmed, so the UI shows the number before the profile object loads. */\n"
        "    public static void rememberPhone(int account, String phone) {\n"
        "        String digits = phone == null ? \"\" : phone.replaceAll(\"[^0-9]\", \"\");\n"
        "        if (digits.length() < 8 || digits.length() > 15) {\n"
        "            return;\n"
        "        }\n"
        "        prefs().edit().putString(\"phone_\" + account, digits).apply();\n"
        "    }\n"
        "\n"
        "    public static String getRememberedPhone(int account) {\n"
        "        return prefs().getString(\"phone_\" + account, \"\");\n"
        "    }\n"
    )
    original = replace_exactly_once(original, expires_anchor, helpers, "WaysGram session refresh helpers")

    me_anchor = (
        "    public static JSONObject me(int account) throws ApiException {\n"
        "        JSONObject json = get(account, \"/api/me\");\n"
        "        if (json.has(\"is_admin\")) {\n"
        "            setAdmin(account, json.optBoolean(\"is_admin\"));\n"
        "        }\n"
        "        return json;\n"
        "    }\n"
    )
    me_replacement = (
        "    public static JSONObject me(int account) throws ApiException {\n"
        "        JSONObject json = get(account, \"/api/me\");\n"
        "        if (json.has(\"is_admin\")) {\n"
        "            setAdmin(account, json.optBoolean(\"is_admin\"));\n"
        "        }\n"
        "        rememberPhone(account, json.optString(\"phone\", \"\"));\n"
        "        return json;\n"
        "    }\n"
    )
    return replace_exactly_once(original, me_anchor, me_replacement, "WaysGram account phone caching")


def patch_waysgram_mapper_phone(original: str) -> str:
    """Keep the account phone on the mapped user so the UI can render a real number."""
    if "waysGramPhoneDigits" in original:
        return original

    to_user_anchor = "    public static TLRPC.TL_user toUser(JSONObject json) {\n"
    helper = (
        "    /** Digits of the account phone; the API sends it as \"+<number>\" or not at all. */\n"
        "    private static String waysGramPhoneDigits(JSONObject json) {\n"
        "        if (json == null) {\n"
        "            return \"\";\n"
        "        }\n"
        "        for (String key : new String[]{\"phone\", \"phone_number\", \"virtual_number\", \"number\"}) {\n"
        "            String digits = json.optString(key, \"\").replaceAll(\"[^0-9]\", \"\");\n"
        "            if (digits.matches(\"[0-9]{8,15}\")) {\n"
        "                return digits;\n"
        "            }\n"
        "        }\n"
        "        return \"\";\n"
        "    }\n"
        "\n"
        + to_user_anchor
    )
    original = replace_exactly_once(original, to_user_anchor, helper, "WaysGram phone helper")

    self_anchor = (
        "        String digits = phoneWithPlus == null ? \"\" : phoneWithPlus.replaceAll(\"[^0-9]\", \"\");\n"
        "        if (json != null && json.has(\"phone\") && !json.isNull(\"phone\")) {\n"
        "            digits = json.optString(\"phone\", \"\").replaceAll(\"[^0-9]\", \"\");\n"
        "        }\n"
        "        if (!TextUtils.isEmpty(digits)) {\n"
        "            user.phone = digits;\n"
        "            user.flags |= TLObject.FLAG_4;\n"
        "        }\n"
    )
    self_replacement = (
        "        String digits = waysGramPhoneDigits(json);\n"
        "        if (digits.isEmpty() && phoneWithPlus != null) {\n"
        "            digits = phoneWithPlus.replaceAll(\"[^0-9]\", \"\");\n"
        "        }\n"
        "        if (digits.matches(\"[0-9]{8,15}\")) {\n"
        "            user.phone = digits;\n"
        "            user.flags |= TLObject.FLAG_4;\n"
        "        }\n"
    )
    original = replace_exactly_once(original, self_anchor, self_replacement, "WaysGram self phone digits")

    premium_anchor = (
        "        user.premium = true; // every WaysGram account has Premium, permanently\n"
    )
    premium_replacement = premium_anchor + (
        "        String waysGramPhone = waysGramPhoneDigits(json);\n"
        "        if (!waysGramPhone.isEmpty()) {\n"
        "            user.phone = waysGramPhone;\n"
        "            user.flags |= TLObject.FLAG_4;\n"
        "        }\n"
    )
    return replace_exactly_once(original, premium_anchor, premium_replacement, "WaysGram peer phone field")


def patch_waysgram_self_chat(original: str) -> str:
    """Let Saved Messages work: the account may read from and write to itself."""
    if "WaysGram saved messages" in original:
        for value in ("TL_inputPeerSelf ? selfId", "recipient_id\", targetId"):
            if value not in original:
                raise ValueError("WaysGram transport contains an incomplete Saved Messages patch")
        return original

    session_anchor = (
        "        // Everything below requires a session.\n"
        "        if (TextUtils.isEmpty(WaysGramApi.getToken(account))) {\n"
        "            throw new WaysGramApi.ApiException(401, \"AUTH_KEY_UNREGISTERED\");\n"
        "        }\n"
    )
    session_replacement = session_anchor + (
        "        WaysGramApi.refreshTokenIfNeeded(account);\n"
    )
    original = replace_exactly_once(original, session_anchor, session_replacement, "WaysGram session refresh call")

    history_anchor = (
        "        if (!(req.peer instanceof TLRPC.TL_inputPeerUser)) {\n"
        "            return out;\n"
        "        }\n"
        "        long peerId = req.peer.user_id;\n"
    )
    history_replacement = (
        "        // WaysGram saved messages: the chat with yourself is a normal user peer.\n"
        "        if (!(req.peer instanceof TLRPC.TL_inputPeerUser) && !(req.peer instanceof TLRPC.TL_inputPeerSelf)) {\n"
        "            return out;\n"
        "        }\n"
        "        long peerId = req.peer instanceof TLRPC.TL_inputPeerSelf ? selfId : req.peer.user_id;\n"
    )
    original = replace_exactly_once(original, history_anchor, history_replacement, "WaysGram saved messages history")

    send_anchor = (
        "        if (!(peer instanceof TLRPC.TL_inputPeerUser)) {\n"
        "            throw new WaysGramApi.ApiException(400, \"METHOD_NOT_SUPPORTED\");\n"
        "        }\n"
        "        if (WaysGramBotFather.is(peer.user_id)) {\n"
        "            return WaysGramBotFather.send(account, text, selfId, nextPts(account));\n"
        "        }\n"
    )
    send_replacement = (
        "        // WaysGram saved messages: the recipient is the account itself.\n"
        "        if (!(peer instanceof TLRPC.TL_inputPeerUser) && !(peer instanceof TLRPC.TL_inputPeerSelf)) {\n"
        "            throw new WaysGramApi.ApiException(400, \"METHOD_NOT_SUPPORTED\");\n"
        "        }\n"
        "        final long targetId = peer instanceof TLRPC.TL_inputPeerSelf ? selfId : peer.user_id;\n"
        "        if (peer instanceof TLRPC.TL_inputPeerUser && WaysGramBotFather.is(peer.user_id)) {\n"
        "            return WaysGramBotFather.send(account, text, selfId, nextPts(account));\n"
        "        }\n"
    )
    original = replace_exactly_once(original, send_anchor, send_replacement, "WaysGram saved messages recipient")
    original = replace_exactly_once(
        original,
        "            body.put(\"recipient_id\", peer.user_id);\n",
        "            body.put(\"recipient_id\", targetId);\n",
        "WaysGram saved messages body",
    )
    original = replace_exactly_once(
        original,
        "        WaysGramPoller.markDelivered(account, peer.user_id, result.optInt(\"id\"));\n",
        "        WaysGramPoller.markDelivered(account, targetId, result.optInt(\"id\"));\n",
        "WaysGram saved messages delivery marker",
    )

    wanted_anchor = (
        "                if (inputPeer instanceof TLRPC.TL_inputPeerUser) {\n"
        "                    wanted.add(inputPeer.user_id);\n"
    )
    wanted_replacement = (
        "                if (inputPeer instanceof TLRPC.TL_inputPeerSelf) {\n"
        "                    wanted.add(selfId);\n"
        "                } else if (inputPeer instanceof TLRPC.TL_inputPeerUser) {\n"
        "                    wanted.add(inputPeer.user_id);\n"
    )
    original = replace_exactly_once(original, wanted_anchor, wanted_replacement, "WaysGram saved messages peer dialog")

    dialogs_anchor = (
        "        for (int i = 0; i < c.dialogs.size(); i++) {\n"
        "            TLRPC.Dialog d = c.dialogs.get(i);\n"
        "            if (wanted.contains(d.peer.user_id)) {\n"
        "                out.dialogs.add(d);\n"
        "                out.messages.add(c.messages.get(i));\n"
        "                out.users.add(c.users.get(i));\n"
        "            }\n"
        "        }\n"
        "        out.users.add(selfUser(account));\n"
        "        out.state = state(account);\n"
    )
    dialogs_replacement = (
        "        for (int i = 0; i < c.dialogs.size(); i++) {\n"
        "            TLRPC.Dialog d = c.dialogs.get(i);\n"
        "            if (wanted.contains(d.peer.user_id)) {\n"
        "                out.dialogs.add(d);\n"
        "                out.messages.add(c.messages.get(i));\n"
        "                out.users.add(c.users.get(i));\n"
        "            }\n"
        "        }\n"
        "        // A chat without messages has no server-side conversation yet; the app still needs a\n"
        "        // dialog for it, otherwise a freshly opened chat stays in its loading state.\n"
        "        for (long wantedPeer : wanted) {\n"
        "            boolean present = false;\n"
        "            for (int i = 0; i < out.dialogs.size(); i++) {\n"
        "                TLRPC.Dialog existing = out.dialogs.get(i);\n"
        "                if (existing.peer instanceof TLRPC.TL_peerUser && existing.peer.user_id == wantedPeer) {\n"
        "                    present = true;\n"
        "                    break;\n"
        "                }\n"
        "            }\n"
        "            if (present) {\n"
        "                continue;\n"
        "            }\n"
        "            TLRPC.User wantedUser = wantedPeer == selfId ? selfUser(account) : null;\n"
        "            if (wantedUser == null) {\n"
        "                try {\n"
        "                    wantedUser = WaysGramMapper.toUser(WaysGramApi.get(account, \"/api/users/\" + wantedPeer));\n"
        "                } catch (WaysGramApi.ApiException e) {\n"
        "                    FileLog.e(e);\n"
        "                    continue;\n"
        "                }\n"
        "            }\n"
        "            out.dialogs.add(WaysGramMapper.toDialog(wantedPeer, 0));\n"
        "            out.users.add(wantedUser);\n"
        "        }\n"
        "        out.users.add(selfUser(account));\n"
        "        out.state = state(account);\n"
    )
    return replace_exactly_once(original, dialogs_anchor, dialogs_replacement, "WaysGram empty chat dialog")


def patch_settings_phone_source(original: str) -> str:
    """Show the account number in Settings and never render a \"+null\" placeholder."""
    if "WaysGram: render only a real number" in original:
        return original
    anchor = (
        "        final StringBuilder sb = new StringBuilder();\n"
        "        if (user != null) {\n"
        "            sb.append(PhoneFormat.getInstance().format(\"+\" + user.phone));\n"
        "        }\n"
    )
    replacement = (
        "        final StringBuilder sb = new StringBuilder();\n"
        "        // WaysGram: render only a real number, never a \"+null\" placeholder.\n"
        "        String waysGramSettingsPhone = user == null || user.phone == null\n"
        "                ? \"\" : user.phone.replaceAll(\"[^0-9]\", \"\");\n"
        "        if (waysGramSettingsPhone.isEmpty()) {\n"
        "            waysGramSettingsPhone = org.telegram.tgnet.WaysGramApi.getRememberedPhone(currentAccount);\n"
        "        }\n"
        "        if (waysGramSettingsPhone.matches(\"[0-9]{8,15}\")) {\n"
        "            sb.append(PhoneFormat.getInstance().format(\"+\" + waysGramSettingsPhone));\n"
        "        }\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram settings phone")


def patch_user_info_phone_source(original: str) -> str:
    """Show the account number in the edit-account screen without placeholders."""
    if "WaysGram: the edit-account number" in original:
        return original
    anchor = (
        "            items.add(SettingsActivity.SettingCell.Factory.of(INFO_PHONE, IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, R.drawable.settings_calls, PhoneFormat.getInstance().format(\"+\" + user.phone), getString(R.string.TapToChangePhone)));\n"
    )
    replacement = (
        "            // WaysGram: the edit-account number, never a \"+null\" placeholder.\n"
        "            String waysGramUserInfoPhone = user.phone == null ? \"\" : user.phone.replaceAll(\"[^0-9]\", \"\");\n"
        "            if (waysGramUserInfoPhone.isEmpty()) {\n"
        "                waysGramUserInfoPhone = org.telegram.tgnet.WaysGramApi.getRememberedPhone(currentAccount);\n"
        "            }\n"
        "            String waysGramUserInfoPhoneText = waysGramUserInfoPhone.matches(\"[0-9]{8,15}\")\n"
        "                    ? PhoneFormat.getInstance().format(\"+\" + waysGramUserInfoPhone) : \"\";\n"
        "            items.add(SettingsActivity.SettingCell.Factory.of(INFO_PHONE, IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, R.drawable.settings_calls, waysGramUserInfoPhoneText, getString(R.string.TapToChangePhone)));\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram edit-account phone")


def patch_user_config_phone_source(original: str) -> str:
    """Fall back to the session phone while the profile object has none."""
    if "WaysGram: the session knows the number" in original:
        return original
    anchor = (
        "    public String getClientPhone() {\n"
        "        synchronized (sync) {\n"
        "            return currentUser != null && currentUser.phone != null ? currentUser.phone : \"\";\n"
        "        }\n"
        "    }\n"
    )
    replacement = (
        "    public String getClientPhone() {\n"
        "        synchronized (sync) {\n"
        "            String phone = currentUser != null && currentUser.phone != null ? currentUser.phone : \"\";\n"
        "            if (phone.isEmpty()) {\n"
        "                // WaysGram: the session knows the number even before the profile object catches up.\n"
        "                phone = org.telegram.tgnet.WaysGramApi.getRememberedPhone(currentAccount);\n"
        "            }\n"
        "            return phone;\n"
        "        }\n"
        "    }\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram client phone fallback")


def patch_profile_own_marks_source(original: str) -> str:
    """Draw the WaysGram marks of the account in its profile, exactly as in the chat title.

    The marks belong to the account, not to the viewer, so a profile shows them to everybody:
    the owner sees their own marks and so does everyone who opens the profile.
    """
    marker = "WaysGram: account marks in the profile title"
    if marker in original:
        return original
    anchor = "                    nameTextView[a].setText(newString);\n"
    replacement = (
        "                    // " + marker + ".\n"
        "                    nameTextView[a].setText(a == 0\n"
        "                            ? org.telegram.messenger.waysgram.WaysGramPeerBadges.decorate(\n"
        "                                    currentAccount, dialogId, newString,\n"
        "                                    () -> {\n"
        "                                        if (getParentActivity() != null) {\n"
        "                                            updateProfileData(false);\n"
        "                                        }\n"
        "                                    })\n"
        "                            : newString);\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram profile marks")


def patch_chat_avatar_self_skip(original: str) -> str:
    """Saved Messages is a chat with yourself and never carries peer marks."""
    if "waysGramBadgeDialog" in original:
        return original
    anchor = (
        "        value = org.telegram.messenger.waysgram.WaysGramPeerBadges.decorate(\n"
        "                currentAccount,\n"
        "                parentFragment == null ? 0 : parentFragment.getDialogId(),\n"
    )
    replacement = (
        "        long waysGramBadgeDialog = parentFragment == null ? 0 : parentFragment.getDialogId();\n"
        "        if (waysGramBadgeDialog == org.telegram.messenger.UserConfig.getInstance(currentAccount).getClientUserId()) {\n"
        "            // Saved Messages is a chat with yourself: it shows no peer marks.\n"
        "            waysGramBadgeDialog = 0;\n"
        "        }\n"
        "        value = org.telegram.messenger.waysgram.WaysGramPeerBadges.decorate(\n"
        "                currentAccount,\n"
        "                waysGramBadgeDialog,\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram saved messages mark exclusion")


def patch_locale_default_russian(original: str) -> str:
    """Start the interface in Russian; the user can still switch languages in Settings."""
    if "WaysGram ships a Russian interface" in original:
        return original
    anchor = (
        "            String lang = preferences.getString(\"language\", null);\n"
        "            if (lang != null) {\n"
    )
    replacement = (
        "            String lang = preferences.getString(\"language\", null);\n"
        "            if (lang == null) {\n"
        "                // WaysGram ships a Russian interface and starts in it until another one is chosen.\n"
        "                lang = \"ru\";\n"
        "                preferences.edit().putString(\"language\", lang).apply();\n"
        "            }\n"
        "            if (lang != null) {\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram default Russian locale")

def patch_chat_list_marks_field(original: str) -> str:
    """Room reserved for the WaysGram marks of the chat list row."""
    if "private int waysGramMarksWidth;" in original:
        return original
    anchor = "    private boolean nameIsEllipsized;\n"
    replacement = (
        anchor
        + "    private int waysGramMarksWidth;\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram chat list marks field")


def patch_chat_list_marks_reserve(original: str) -> str:
    """Ellipsize a long name instead of pushing the WaysGram marks out of the row."""
    if "WaysGram: keep room for the marks" in original:
        return original
    anchor = (
        "        } else if (drawScam != 0) {\n"
        "            int w = dp(6) + (drawScam == 1 ? Theme.dialogs_scamDrawable : Theme.dialogs_fakeDrawable).getIntrinsicWidth();\n"
        "            nameWidth -= w;\n"
        "            nameAdditionalsForChannelSubscriber += w;\n"
        "            if (LocaleController.isRTL) {\n"
        "                nameLeft += w;\n"
        "            }\n"
        "        }\n"
    )
    replacement = (
        anchor
        + "        // WaysGram: keep room for the marks drawn next to the badge.\n"
        + "        waysGramMarksWidth = org.telegram.messenger.waysgram.WaysGramPeerBadges.reservedWidth(\n"
        + "                currentAccount, getDialogId(), Theme.dialogs_namePaint[paintIndex].getTextSize(),\n"
        + "                () -> {\n"
        + "                    // buildLayout() is what measures the marks, and it only runs\n"
        + "                    // when the row is rebuilt: without the flag the pills of an\n"
        + "                    // already measured row would never appear.\n"
        + "                    updateLayout = true;\n"
        + "                    requestLayout();\n"
        + "                    invalidate();\n"
        + "                });\n"
        + "        if (waysGramMarksWidth > 0) {\n"
        + "            waysGramMarksWidth += dp(drawPremium ? 24 : 18);\n"
        + "            if (dialogsType != 2 && (dialogMuted || drawUnmute || isHiddenInCommunity)\n"
        + "                    && !drawVerified && drawScam == 0) {\n"
        + "                waysGramMarksWidth += dp(10) + Theme.dialogs_muteDrawable.getIntrinsicWidth();\n"
        + "            }\n"
        + "            if (waysGramMarksWidth > nameWidth / 2) {\n"
        + "                // The marks never take more than half of the row: the name wins.\n"
        + "                waysGramMarksWidth = 0;\n"
        + "            }\n"
        + "        }\n"
        + "        if (LocaleController.isRTL) {\n"
        + "            nameLeft += waysGramMarksWidth;\n"
        + "        }\n"
        + "        nameWidth -= waysGramMarksWidth;\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram chat list marks room")


def patch_chat_list_marks(original: str) -> str:
    """Draw the WaysGram marks of the row next to its premium or verified badge."""
    if "WaysGramPeerBadges.drawMarks(" in original:
        return original
    anchor = (
        "            } else if (drawScam != 0) {\n"
        "                int y = dp(useForceThreeLines || SharedConfig.useThreeLinesLayout ? 12 : 15);\n"
        "                if ((!(useForceThreeLines || SharedConfig.useThreeLinesLayout) || isForumCell()) && hasTags()) {\n"
        "                    y -= dp(9);\n"
        "                }\n"
        "                setDrawableBounds((drawScam == 1 ? Theme.dialogs_scamDrawable : Theme.dialogs_fakeDrawable), nameMuteLeft, y);\n"
        "                (drawScam == 1 ? Theme.dialogs_scamDrawable : Theme.dialogs_fakeDrawable).draw(canvas);\n"
        "            }\n"
    )
    replacement = (
        anchor
        + "            // WaysGram: the marks follow the badge, exactly as in the chat title.\n"
        + "            if (currentDialogFolderId == 0 && nameLayout != null && waysGramMarksWidth > 0\n"
        + "                    && nameMuteLeft != 0) {\n"
        + "                try {\n"
        + "                    boolean waysGramMirrored = LocaleController.isRTL;\n"
        + "                    boolean waysGramMutedIcon = dialogsType != 2\n"
        + "                            && (dialogMuted || drawUnmute || isHiddenInCommunity)\n"
        + "                            && !drawVerified && drawScam == 0;\n"
        + "                    float waysGramMarksAnchor = nameMuteLeft;\n"
        + "                    float waysGramMarksLimit;\n"
        + "                    if (waysGramMirrored) {\n"
        + "                        waysGramMarksAnchor -= dp(drawVerified || drawPremium || drawScam != 0\n"
        + "                                ? (drawPremium ? 24 : 18) : 4);\n"
        + "                        if (waysGramMutedIcon && nameMutedIconLeft < nameMuteLeft) {\n"
        + "                            waysGramMarksAnchor = nameMutedIconLeft - dp(4);\n"
        + "                        }\n"
        + "                        waysGramMarksLimit = nameLeft - waysGramMarksWidth;\n"
        + "                    } else {\n"
        + "                        if (drawVerified || drawPremium || drawScam != 0) {\n"
        + "                            waysGramMarksAnchor += dp(drawPremium ? 24 : 18);\n"
        + "                        }\n"
        + "                        if (waysGramMutedIcon) {\n"
        + "                            waysGramMarksAnchor = (nameMutedIconLeft > nameMuteLeft\n"
        + "                                    ? nameMutedIconLeft : nameMuteLeft)\n"
        + "                                    + Theme.dialogs_muteDrawable.getIntrinsicWidth() + dp(4);\n"
        + "                        }\n"
        + "                        waysGramMarksLimit = nameLeft + nameWidth + waysGramMarksWidth;\n"
        + "                    }\n"
        + "                    org.telegram.messenger.waysgram.WaysGramPeerBadges.drawMarks(\n"
        + "                            canvas,\n"
        + "                            Theme.dialogs_namePaint[paintIndex],\n"
        + "                            waysGramMarksAnchor,\n"
        + "                            nameTop,\n"
        + "                            waysGramMarksLimit,\n"
        + "                            currentAccount,\n"
        + "                            getDialogId(),\n"
        + "                            waysGramMirrored,\n"
        + "                            () -> {\n"
        + "                                requestLayout();\n"
        + "                                invalidate();\n"
        + "                            });\n"
        + "                } catch (Exception e) {\n"
        + "                    FileLog.e(e);\n"
        + "                }\n"
        + "            }\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram chat list marks")


def patch_waysgram_chat_fixes(android_root: Path) -> None:
    """Saved Messages, empty chats, account phone, session refresh and own-profile marks."""
    api = android_root / "TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramApi.java"
    mapper = android_root / "TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramMapper.java"
    transport = android_root / "TMessagesProj/src/main/java/org/telegram/tgnet/WaysGramTransport.java"
    settings = android_root / "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java"
    user_info = android_root / "TMessagesProj/src/main/java/org/telegram/ui/UserInfoActivity.java"
    user_config = android_root / "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java"
    profile = android_root / "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java"
    locale = android_root / "TMessagesProj/src/main/java/org/telegram/messenger/LocaleController.java"
    dialog_cell = (
        android_root / "TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java"
    )

    jobs = (
        (api, patch_waysgram_api_helpers),
        (mapper, patch_waysgram_mapper_phone),
        (transport, patch_waysgram_self_chat),
        (settings, patch_settings_phone_source),
        (user_info, patch_user_info_phone_source),
        (user_config, patch_user_config_phone_source),
        (profile, patch_profile_own_marks_source),
        (dialog_cell, patch_chat_list_marks_field),
        (dialog_cell, patch_chat_list_marks_reserve),
        (dialog_cell, patch_chat_list_marks),
        (locale, patch_locale_default_russian),
    )
    for path, patcher in jobs:
        if not path.is_file():
            raise FileNotFoundError(f"Required WaysGram source not found: {path}")
        source = path.read_text(encoding="utf-8")
        patched = patcher(source)
        if patched != source:
            path.write_text(patched, encoding="utf-8")

    for path, marker in (
        (api, "refreshTokenIfNeeded"),
        (mapper, "waysGramPhoneDigits"),
        (transport, "WaysGram saved messages"),
        (settings, "WaysGram: render only a real number"),
        (user_info, "WaysGram: the edit-account number"),
        (user_config, "WaysGram: the session knows the number"),
        (profile, "WaysGram: account marks in the profile title"),
        (dialog_cell, "WaysGramPeerBadges.drawMarks("),
        (locale, "WaysGram ships a Russian interface"),
    ):
        if marker not in path.read_text(encoding="utf-8"):
            raise ValueError(f"WaysGram chat fix is missing in {path.name}")



def patch_star_gift_sheet_unfold_source(original: str) -> str:
    """Unfold collectible gift cards when the native sheet binds a unique gift."""
    if "WaysGramGiftUnfold.playOnce(" in original:
        return original
    anchor = (
        "            if (gift instanceof TL_stars.TL_starGiftUnique) {\n"
        "                backdrop[page] = findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class);\n"
    )
    replacement = (
        anchor
        + "                // WaysGram: unfold the collectible card once per gift.\n"
        + "                org.telegram.messenger.waysgram.WaysGramGiftUnfold.playOnce(\n"
        + "                        \"wg-unique:\" + gift.slug + \":\" + gift.id, this);\n"
    )
    return replace_exactly_once(original, anchor, replacement, "WaysGram collectible gift unfold")



def verify_source(android_root: Path) -> None:
    gift_sheet = android_root / "TMessagesProj/src/main/java/org/telegram/ui/Gifts/GiftSheet.java"
    launch_activity = android_root / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
    for path in (gift_sheet, launch_activity):
        if not path.is_file():
            raise FileNotFoundError(f"Required upstream source file not found: {path}")

    # GiftSheet keeps Telegram's own sticker binding: each WaysGram gift carries a real
    # TL_document, so the native cell decodes and animates the artwork itself.

    profile_layout = android_root / "TMessagesProj/src/main/java/org/telegram/ui/Components/SharedMediaLayout.java"
    if not profile_layout.is_file():
        raise FileNotFoundError(f"Required profile layout source not found: {profile_layout}")
    profile_source = profile_layout.read_text(encoding="utf-8")
    patched_profile_source = patch_profile_gifts_source(profile_source)
    if patched_profile_source != profile_source:
        profile_layout.write_text(patched_profile_source, encoding="utf-8")
    final_profile_source = profile_layout.read_text(encoding="utf-8")
    for marker in (
        "TAB_WAYSGRAM_GIFTS",
        "WaysGramProfileGiftsController",
        "Мои подарки",
        "waysGramGiftsContainer.setPaddingTop(",
        "waysGramGiftsContainer.setPaddingTop(dp(48) + (int) topPanelLayout.getAnimatedHeightWithPadding(dp(7)))",
    ):
        if marker not in final_profile_source:
            raise ValueError("WaysGram profile gift tab or native top spacing patch was not applied completely")

    profile_activity = android_root / "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java"
    if not profile_activity.is_file():
        raise FileNotFoundError(f"Required native ProfileActivity source not found: {profile_activity}")
    profile_activity_source = profile_activity.read_text(encoding="utf-8")
    patched_profile_activity = patch_profile_actions_visibility_source(profile_activity_source)
    if patched_profile_activity != profile_activity_source:
        profile_activity.write_text(patched_profile_activity, encoding="utf-8")
    final_profile_activity = profile_activity.read_text(encoding="utf-8")
    patched_image_updater = patch_profile_image_updater_source(final_profile_activity)
    if patched_image_updater != final_profile_activity:
        profile_activity.write_text(patched_image_updater, encoding="utf-8")
    final_profile_activity = profile_activity.read_text(encoding="utf-8")
    patched_profile_phone = patch_profile_phone_source(final_profile_activity)
    if patched_profile_phone != final_profile_activity:
        profile_activity.write_text(patched_profile_phone, encoding="utf-8")
    final_profile_activity = profile_activity.read_text(encoding="utf-8")
    patched_phone_placeholders = patch_profile_phone_null_source(final_profile_activity)
    if patched_phone_placeholders != final_profile_activity:
        profile_activity.write_text(patched_phone_placeholders, encoding="utf-8")
    final_profile_activity = profile_activity.read_text(encoding="utf-8")
    if (
        "// WaysGram: restore profile action visibility" not in final_profile_activity
        or "WaysGram: keep native profile actions above avatar overlays" not in final_profile_activity
        or "WaysGram: place native profile actions above avatar pager indicator" not in final_profile_activity
        or "actionsView.setAlpha(1.0f);" not in final_profile_activity
        or "actionsView.bringToFront();" not in final_profile_activity
        or 'profilePhone.matches("[0-9]{8,15}")' not in final_profile_activity
        or "WaysGram: use persisted self phone while profile refreshes" not in final_profile_activity
        or "waysGramStablePhone = getUserConfig().getClientPhone();" not in final_profile_activity
        or "WaysGram: initialize native profile actions after self detection" not in final_profile_activity
        or "if (myProfile && userId != 0 && imageUpdater == null)" not in final_profile_activity
        or "WaysGram: reject non-numeric phone placeholders" not in final_profile_activity
        or 'user.phone.matches("[0-9]{8,15}")' not in final_profile_activity
        or "String copyPhone = myProfile ? waysGramStablePhone : user.phone;" not in final_profile_activity
        or 'copyPhone != null && copyPhone.matches("[0-9]{8,15}")' not in final_profile_activity
        or 'if (user.phone != null && user.phone.matches("[0-9]{8,15}"))' not in final_profile_activity
    ):
        raise ValueError("Native profile actions or phone placeholder patch was not applied")

    gifts_container = android_root / "TMessagesProj/src/main/java/org/telegram/ui/Gifts/ProfileGiftsContainer.java"
    if not gifts_container.is_file():
        raise FileNotFoundError(f"Required native profile gifts container not found: {gifts_container}")
    gifts_container_source = gifts_container.read_text(encoding="utf-8")
    patched_gifts_container = patch_profile_gifts_container_source(gifts_container_source)
    if patched_gifts_container != gifts_container_source:
        gifts_container.write_text(patched_gifts_container, encoding="utf-8")
    final_gifts_container = gifts_container.read_text(encoding="utf-8")
    if (
        "setWaysGramGiftUpdatedListener(Runnable listener)" not in final_gifts_container
        or "parent.waysGramGiftUpdatedListener.run();" not in final_gifts_container
        or "if (parent.waysGramInventory || list == null) return;" in final_gifts_container
        or "replaceWaysGramInventory(ArrayList<TL_stars.SavedStarGift> gifts)" not in final_gifts_container
        or "scrollToPositionWithOffset(waysGramFirst, waysGramOffset)" not in final_gifts_container
        or "waysGramInventoryBottomSpace" not in final_gifts_container
    ):
        raise ValueError("WaysGram profile gift cards must open native details and refresh inventory")

    star_gift_sheet = android_root / "TMessagesProj/src/main/java/org/telegram/ui/Stars/StarGiftSheet.java"
    if not star_gift_sheet.is_file():
        raise FileNotFoundError(f"Required native StarGiftSheet source not found: {star_gift_sheet}")
    star_gift_source = star_gift_sheet.read_text(encoding="utf-8")
    patched_star_gift_source = patch_star_gift_sheet_display_source(star_gift_source)
    patched_star_gift_source = patch_star_gift_sheet_unfold_source(patched_star_gift_source)
    patched_star_gift_source = patch_star_gift_sheet_sell_row(patched_star_gift_source)
    if patched_star_gift_source != star_gift_source:
        star_gift_sheet.write_text(patched_star_gift_source, encoding="utf-8")
    final_star_gift_source = star_gift_sheet.read_text(encoding="utf-8")
    for marker in (
        "private String waysGramGiftSerial()",
        "topView.buttonsLayout.setVisibility(View.GONE);",
        "if (isWaysGramGift()) return false;",
        "if (isWaysGramGift()) return null;",
        "WaysGramGiftUnfold.playOnce(",
        "WaysGramGiftUpgrade.upgrade(",
        "WaysGram: sell row of a saved gift",
        "WaysGramGiftSell.confirm(",
    ):
        if marker not in final_star_gift_source:
            raise ValueError("WaysGram native card detail rendering is incomplete")
    if "performWaysGramGiftUpgrade" in final_star_gift_source:
        raise ValueError("WaysGram profile detail must not route to paid randomized upgrades")
    # The upgrade button replaces the native bottom action, so it has to be applied after the
    # native button setup and inside the WaysGram block that follows it.
    upgrade_index = final_star_gift_source.find("WaysGram upgrade: a fixed 25 Stars")
    native_button_index = final_star_gift_source.find(
        "button.setText(getString(R.string.OK), !firstSet)"
    )
    if upgrade_index < 0 or native_button_index < 0 or upgrade_index < native_button_index:
        raise ValueError(
            "the WaysGram upgrade button must be applied after the native bottom button setup"
        )

    gift_controller = android_root / "TMessagesProj/src/main/java/org/telegram/messenger/waysgram/WaysGramProfileGiftsController.java"
    if not gift_controller.is_file():
        raise FileNotFoundError(f"Required WaysGram profile gift controller not found: {gift_controller}")
    controller_source = gift_controller.read_text(encoding="utf-8")
    for marker in (
        "TL_stars.TL_starGift regularGift = new TL_stars.TL_starGift();",
        "if (isCollectible)",
        "savedGift.flags |= 2;",
        "uniqueGift.flags |= 1;",
        "boolean canUpgrade = false;",
    ):
        if marker not in controller_source:
            raise ValueError("WaysGram gift inventory native model mapping is incomplete")

    gift_inventory_sheet = android_root / "TMessagesProj/src/main/java/org/telegram/messenger/waysgram/WaysGramGiftInventorySheet.java"
    if not gift_inventory_sheet.is_file():
        raise FileNotFoundError(f"Required WaysGram gift inventory sheet not found: {gift_inventory_sheet}")
    inventory_sheet_source = gift_inventory_sheet.read_text(encoding="utf-8")
    if "String slug = savedGift.gift.slug;" not in inventory_sheet_source:
        raise ValueError("WaysGram ordinary gift cards are not selectable")

    # GiftSheet.GiftCell is left exactly as Telegram wrote it: a WaysGram gift now carries a real
    # sticker document, so the native cell draws the artwork, the backdrop and the pattern.
    gift_content = gift_sheet.read_text(encoding="utf-8")
    launch_content = launch_activity.read_text(encoding="utf-8")

    gift_hits = [marker for marker in FORBIDDEN_GIFT_MARKERS if marker in gift_content]
    launch_hits = [marker for marker in FORBIDDEN_LAUNCH_MARKERS if marker in launch_content]
    if gift_hits or launch_hits:
        details = []
        if gift_hits:
            details.append("GiftSheet: " + ", ".join(gift_hits))
        if launch_hits:
            details.append("LaunchActivity: " + ", ".join(launch_hits))
        raise ValueError(
            "WaysGram UI injection detected in protected Telegram sources; "
            "restore the pinned upstream files before building. " + "; ".join(details)
        )

    live_service = android_root / "TMessagesProj/src/main/java/org/telegram/messenger/waysgram/WaysGramLiveLocationService.java"
    if not live_service.is_file():
        raise FileNotFoundError(f"WaysGram live location service missing: {live_service}")

    push_service = android_root / "TMessagesProj/src/main/java/org/telegram/messenger/GcmPushListenerService.java"
    if not push_service.is_file():
        raise FileNotFoundError(f"Required Firebase messaging service not found: {push_service}")
    push_source = push_service.read_text(encoding="utf-8")
    patched_push_source = patch_push_service_source(push_source)
    if patched_push_source != push_source:
        push_service.write_text(patched_push_source, encoding="utf-8")
    push_listener = android_root / "TMessagesProj/src/main/java/org/telegram/messenger/PushListenerController.java"
    if not push_listener.is_file():
        raise FileNotFoundError(f"Required Telegram push token provider not found: {push_listener}")
    push_listener_source = push_listener.read_text(encoding="utf-8")
    patched_push_listener = patch_push_listener_controller_source(push_listener_source)
    if patched_push_listener != push_listener_source:
        push_listener.write_text(patched_push_listener, encoding="utf-8")
    patch_notification_permissions(android_root)
    patch_location_permissions(android_root)
    patch_live_location_service(android_root)
    patch_translation_dependencies(android_root)
    patch_waysgram_chat_fixes(android_root)
    patch_waysgram_gift_integration(android_root)

    translation_gradle = (android_root / "TMessagesProj/build.gradle").read_text(encoding="utf-8")
    if (
        "com.google.mlkit:language-id:17.0.6" not in translation_gradle
        or "com.google.mlkit:translate:17.0.3" not in translation_gradle
    ):
        raise ValueError("WaysGram on-device translation dependencies are missing")

    final_push_source = push_service.read_text(encoding="utf-8")
    if (
        "WaysGramPushNotifications.show(this, message);" not in final_push_source
        or "WaysGramPushManager.sync(this);" not in final_push_source
    ):
        raise ValueError("WaysGram FCM routing is missing after patching")

    final_push_listener = push_listener.read_text(encoding="utf-8")
    if (
        "TelegramFirebaseConfig.getMessaging(ApplicationLoader.applicationContext)" not in final_push_listener
        or "telegramMessaging.getToken()" not in final_push_listener
    ):
        raise ValueError("Telegram FCM token provider is not isolated on its original Firebase app")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--android-root", required=True, type=Path)
    args = parser.parse_args()
    verify_source(args.android_root)
    print(
        "Verified: Telegram Settings, LaunchActivity and LoginActivity routing are preserved; WaysGram inventory uses Telegram native ProfileGiftsContainer and GiftCell."
    )


if __name__ == "__main__":
    main()
