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
        "    public void refreshWaysGramGiftViews() {\n"
        "        if (!waysGramInventory) return;\n"
        "        Page page = getCurrentPage();\n"
        "        if (page != null) {\n"
        "            page.update(false);\n"
        "            for (int i = 0; i < page.listView.getChildCount(); i++) {\n"
        "                android.view.View child = page.listView.getChildAt(i);\n"
        "                if (!(child instanceof GiftSheet.GiftCell)) continue;\n"
        "                GiftSheet.GiftCell cell = (GiftSheet.GiftCell) child;\n"
        "                TL_stars.SavedStarGift savedGift = cell.getSavedGift();\n"
        "                if (savedGift == null || savedGift.gift == null || savedGift.gift.slug == null || !savedGift.gift.slug.startsWith(\"waysgram://\")) continue;\n"
        "                String serial = savedGift.gift.slug.substring(\"waysgram://\".length()).trim();\n"
        "                android.graphics.Bitmap bitmap = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.get(serial);\n"
        "                if (bitmap != null && !bitmap.isRecycled()) {\n"
        "                    cell.imageView.getImageReceiver().setImageBitmap(bitmap);\n"
        "                    cell.imageView.invalidate();\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "    }\n\n"
    )
    insert_at = updated.rfind("\n}")
    if insert_at < 0:
        raise ValueError("ProfileGiftsContainer closing brace not found")
    updated = updated[:insert_at] + "\n\n" + method + updated[insert_at:]
    return updated




def patch_gift_sheet_profile_art_source(original: str) -> str:
    """Bind static and animated WaysGram artwork to the SavedStarGift profile cells."""
    marker = "// WaysGram saved profile gift artwork binding"
    if marker in original:
        required = (
            'userGift.gift.slug.startsWith("waysgram://")',
            "WaysGramGiftMediaStore.getAnimationFile(waysGramSerial)",
            "setImageBitmap(waysGramAnimation)",
            "WaysGramGiftMediaStore.get(waysGramSerial)",
        )
        if not all(value in original for value in required):
            raise ValueError("GiftSheet contains an incomplete SavedStarGift artwork binding")
        return original

    needle = "            setSticker(userGift.gift.getDocument(), userGift);\n"
    replacement = (
        needle
        + "            " + marker + "\n"
        + "            if (userGift.gift != null && userGift.gift.slug != null && userGift.gift.slug.startsWith(\"waysgram://\")) {\n"
        + "                String waysGramSerial = userGift.gift.slug.substring(\"waysgram://\".length()).trim();\n"
        + "                java.io.File waysGramAnimationFile = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.getAnimationFile(waysGramSerial);\n"
        + "                if (waysGramAnimationFile != null && waysGramAnimationFile.isFile()) {\n"
        + "                    org.telegram.ui.Components.AnimatedFileDrawable waysGramAnimation = new org.telegram.ui.Components.AnimatedFileDrawable(waysGramAnimationFile, true, 0, 0, null, null, null, 0, org.telegram.messenger.UserConfig.selectedAccount, true, 64, 64, null);\n"
        + "                    imageView.getImageReceiver().setImageBitmap(waysGramAnimation);\n"
        + "                    imageView.invalidate();\n"
        + "                } else {\n"
        + "                    android.graphics.Bitmap waysGramBitmap = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.get(waysGramSerial);\n"
        + "                    if (waysGramBitmap != null && !waysGramBitmap.isRecycled()) {\n"
        + "                        imageView.getImageReceiver().setImageBitmap(waysGramBitmap);\n"
        + "                        imageView.invalidate();\n"
        + "                    }\n"
        + "                }\n"
        + "            }\n"
    )
    return replace_exactly_once(
        original,
        needle,
        replacement,
        "native SavedStarGift profile media binding"
    )



def patch_star_gift_sheet_display_source(original: str) -> str:
    """Render WaysGram-owned gifts in Telegram's native detail sheet without Telegram ownership actions."""
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
        "            topView.setShowOptions(false);\n"
        "            topView.buttonsLayout.setVisibility(View.GONE);\n"
        "            String waysGramSerial = waysGramGiftSerial();\n"
        "            org.telegram.messenger.waysgram.WaysGramGiftMediaStore.GiftMetadata waysGramMetadata = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.getMetadata(waysGramSerial);\n"
        "            android.graphics.Bitmap waysGramBitmap = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.get(waysGramSerial);\n"
        "            View waysGramEmojiExisting = topView.imageLayout.findViewWithTag(\"waysgramGiftDetailEmoji\");\n"
        "            android.widget.TextView waysGramEmojiView;\n"
        "            if (waysGramEmojiExisting instanceof android.widget.TextView) {\n"
        "                waysGramEmojiView = (android.widget.TextView) waysGramEmojiExisting;\n"
        "            } else {\n"
        "                waysGramEmojiView = new android.widget.TextView(getContext());\n"
        "                waysGramEmojiView.setTag(\"waysgramGiftDetailEmoji\");\n"
        "                waysGramEmojiView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 52);\n"
        "                waysGramEmojiView.setGravity(Gravity.CENTER);\n"
        "                topView.imageLayout.addView(waysGramEmojiView, new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER));\n"
        "            }\n"
        "            waysGramEmojiView.setText(waysGramMetadata == null || TextUtils.isEmpty(waysGramMetadata.emoji) ? \"🎁\" : waysGramMetadata.emoji);\n"
        "            if (waysGramBitmap != null && topView.imageView[0] != null) {\n"
        "                topView.imageView[0].getImageReceiver().setImageBitmap(waysGramBitmap);\n"
        "                waysGramEmojiView.setVisibility(View.GONE);\n"
        "            } else {\n"
        "                waysGramEmojiView.setVisibility(View.VISIBLE);\n"
        "            }\n"
        "        }\n\n"
    )
    original = replace_exactly_once(
        original,
        "        if (savedStarGift.refunded) {\n",
        waysgram_detail_ui + "        if (savedStarGift.refunded) {\n",
        "native WaysGram detail artwork and action isolation"
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

def patch_gift_sheet_source(original: str) -> str:
    """Bind WaysGram profile gift metadata and artwork without emoji fallbacks."""
    updated = original
    marker = "WaysGramGiftMediaStore.getMetadata("
    if marker not in updated:
        metadata_target = (
            "            cardBackground.setStrokeColors(null);\n"
            "            titleView.setVisibility(View.GONE);\n"
            "            subtitleView.setVisibility(View.GONE);\n"
        )
        metadata_replacement = (
            metadata_target
            + "            if (userGift.gift.slug != null && userGift.gift.slug.startsWith(\"waysgram://\")) {\n"
            + "                String waysGramSerial = userGift.gift.slug.substring(\"waysgram://\".length());\n"
            + "                org.telegram.messenger.waysgram.WaysGramGiftMediaStore.GiftMetadata waysGramMetadata = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.getMetadata(waysGramSerial);\n"
            + "                if (waysGramMetadata != null) {\n"
            + "                    titleView.setText(waysGramMetadata.title);\n"
            + "                    titleView.setMaxLines(1);\n"
            + "                    titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);\n"
            + "                    titleView.setVisibility(View.VISIBLE);\n"
            + "                    subtitleView.setText(waysGramMetadata.subtitle);\n"
            + "                    subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f);\n"
            + "                    subtitleView.setMaxLines(2);\n"
            + "                    subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);\n"
            + "                    subtitleView.setVisibility(waysGramMetadata.subtitle == null || waysGramMetadata.subtitle.isEmpty() ? View.GONE : View.VISIBLE);\n"
            + "                    priceLayout.setVisibility(View.GONE);\n"
            + "                    View waysGramEmojiExisting = card.findViewWithTag(\"waysgramGiftEmoji\");\n"
            + "                    if (waysGramEmojiExisting != null) waysGramEmojiExisting.setVisibility(View.GONE);\n"
            + "                    android.graphics.Bitmap waysGramGiftBitmap = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.get(waysGramSerial);\n"
            + "                    if (waysGramGiftBitmap != null && !waysGramGiftBitmap.isRecycled()) {\n"
            + "                        imageView.setImageBitmap(waysGramGiftBitmap);\n"
            + "                    }\n"
            + "                }\n"
            + "            }\n"
        )
        updated = replace_exactly_once(
            updated, metadata_target, metadata_replacement,
            "native GiftCell WaysGram metadata and non-emoji artwork binding"
        )
    return updated


def patch_chat_action_cell_waysgram_receipts_source(original: str) -> str:
    """Render WaysGram-owned service receipts with their own title and cached artwork."""
    marker = "// WaysGram: render local purchase receipt title and media"
    if marker in original:
        required = (
            'action.gift.slug.startsWith("waysgram://")',
            "WaysGramGiftMediaStore.getAnimationFile(waysGramSerial)",
            "Gift2ActionSelfInfoNoConvert",
        )
        if not all(value in original for value in required):
            raise ValueError("ChatActionCell contains an incomplete WaysGram gift receipt patch")
        return original

    title_before = (
        "                        if (action.gift_num > 0 && action.gift != null && action.gift.title != null) {\n"
        "                            sb.append(action.gift.title).append(\" #\").append(formatNumber(action.gift_num, ','));\n"
        "                        } else {\n"
        "                            sb.append(getString(R.string.Gift2ActionSelfTitle));\n"
        "                        }"
    )
    title_after = (
        "                        // WaysGram: render local purchase receipt title and media\n"
        "                        if (action.gift != null && action.gift.slug != null && action.gift.slug.startsWith(\"waysgram://\") && !TextUtils.isEmpty(action.gift.title)) {\n"
        "                            sb.append(action.gift.title);\n"
        "                        } else if (action.gift_num > 0 && action.gift != null && action.gift.title != null) {\n"
        "                            sb.append(action.gift.title).append(\" #\").append(formatNumber(action.gift_num, ','));\n"
        "                        } else {\n"
        "                            sb.append(getString(R.string.Gift2ActionSelfTitle));\n"
        "                        }"
    )
    updated = replace_exactly_once(
        original, title_before, title_after,
        "native WaysGram purchase receipt header title"
    )

    subtitle_before = (
        "                    } else if (self) {\n"
        "                        if (action.converted && stars > 0) {\n"
        "                            title = formatPluralStringComma(\"Gift2ActionConvertedInfo\", (int) stars);\n"
        "                        } else if (action.can_upgrade) {\n"
        "                            title = AndroidUtilities.replaceTags(getString(R.string.Gift2ActionSelfInfoUpgrade));\n"
        "                        } else {\n"
        "                            title = AndroidUtilities.replaceTags(getString(R.string.Gift2ActionSelfInfoNoConvert));\n"
        "                        }\n"
    )
    subtitle_after = (
        "                    } else if (self) {\n"
        "                        if (action.gift != null && action.gift.slug != null && action.gift.slug.startsWith(\"waysgram://\")) {\n"
        "                            title = \"Подарок приобретён в WaysGram\";\n"
        "                        } else if (action.converted && stars > 0) {\n"
        "                            title = formatPluralStringComma(\"Gift2ActionConvertedInfo\", (int) stars);\n"
        "                        } else if (action.can_upgrade) {\n"
        "                            title = AndroidUtilities.replaceTags(getString(R.string.Gift2ActionSelfInfoUpgrade));\n"
        "                        } else {\n"
        "                            title = AndroidUtilities.replaceTags(getString(R.string.Gift2ActionSelfInfoNoConvert));\n"
        "                        }\n"
    )
    updated = replace_exactly_once(
        updated, subtitle_before, subtitle_after,
        "native WaysGram purchase receipt subtitle"
    )

    media_anchor = "                forceWasUnread = messageObject.wasUnread;\n                giftSticker = document;\n"
    insert_position = updated.find(media_anchor)
    if insert_position < 0:
        raise ValueError("Could not locate the native gift sticker preparation block")
    next_block = "                if (botInlineButtons != null) {\n"
    block_position = updated.find(next_block, insert_position)
    if block_position < 0:
        raise ValueError("Could not locate the native gift message action block")
    media_binding = (
        "                if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift) {\n"
        "                    TL_stars.StarGift waysGramGift = ((TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action).gift;\n"
        "                    if (waysGramGift != null && waysGramGift.slug != null && waysGramGift.slug.startsWith(\"waysgram://\")) {\n"
        "                        String waysGramSerial = waysGramGift.slug.substring(\"waysgram://\".length()).trim();\n"
        "                        java.io.File waysGramAnimationFile = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.getAnimationFile(waysGramSerial);\n"
        "                        if (waysGramAnimationFile != null && waysGramAnimationFile.isFile()) {\n"
        "                            org.telegram.ui.Components.AnimatedFileDrawable waysGramAnimation = new org.telegram.ui.Components.AnimatedFileDrawable(waysGramAnimationFile, true, 0, 0, null, null, null, 0, currentAccount, true, 160, 160, null);\n"
        "                            imageReceiver.setAllowStartLottieAnimation(true);\n"
        "                            imageReceiver.setDelegate(giftStickerDelegate);\n"
        "                            imageReceiver.setImageBitmap(waysGramAnimation);\n"
        "                        } else {\n"
        "                            android.graphics.Bitmap waysGramBitmap = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.get(waysGramSerial);\n"
        "                            if (waysGramBitmap != null && !waysGramBitmap.isRecycled()) {\n"
        "                                imageReceiver.setAllowStartLottieAnimation(true);\n"
        "                                imageReceiver.setImageBitmap(waysGramBitmap);\n"
        "                            }\n"
        "                        }\n"
        "                    }\n"
        "                }\n"
    )
    updated = updated[:block_position] + media_binding + updated[block_position:]
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



def patch_chat_activity_native_gift_receipts_source(original: str) -> str:
    """Render WaysGram purchase receipts through Telegram's native StarGift action cell."""
    marker = "// WaysGram: hydrate legacy native gift receipts"
    if marker in original:
        required = (
            "WaysGramNativeGiftReceiptAdapter.hydrateLegacyReceipts",
            "chatMode == MODE_DEFAULT",
        )
        if not all(value in original for value in required):
            raise ValueError("ChatActivity contains an incomplete WaysGram receipt patch")
        return original

    anchor = "        checkGroupMessagesOrder();\n"
    replacement = (
        anchor
        + "        // WaysGram: hydrate legacy native gift receipts\n"
        + "        if (chatMode == MODE_DEFAULT && chatAdapter != null) {\n"
        + "            org.telegram.messenger.waysgram.WaysGramNativeGiftReceiptAdapter.hydrateLegacyReceipts(\n"
        + "                    currentAccount, dialog_id, messages);\n"
        + "        }\n"
    )
    return replace_exactly_once(
        original,
        anchor,
        replacement,
        "native Saved Messages and DM gift receipt hydration"
    )


def verify_source(android_root: Path) -> None:
    gift_sheet = android_root / "TMessagesProj/src/main/java/org/telegram/ui/Gifts/GiftSheet.java"
    launch_activity = android_root / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
    for path in (gift_sheet, launch_activity):
        if not path.is_file():
            raise FileNotFoundError(f"Required upstream source file not found: {path}")

    gift_sheet_source = gift_sheet.read_text(encoding="utf-8")
    patched_gift_sheet_source = patch_gift_sheet_profile_art_source(gift_sheet_source)
    if patched_gift_sheet_source != gift_sheet_source:
        gift_sheet.write_text(patched_gift_sheet_source, encoding="utf-8")
    final_gift_sheet_source = gift_sheet.read_text(encoding="utf-8")
    for marker in (
        "// WaysGram saved profile gift artwork binding",
        'userGift.gift.slug.startsWith("waysgram://")',
        "WaysGramGiftMediaStore.get(waysGramSerial)",
        "imageView.getImageReceiver().setImageBitmap(waysGramBitmap);",
    ):
        if marker not in final_gift_sheet_source:
            raise ValueError("Native GiftCell WaysGram bitmap binding was not applied completely")

    chat_activity = android_root / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
    if not chat_activity.is_file():
        raise FileNotFoundError(f"Required native ChatActivity source not found: {chat_activity}")
    chat_activity_source = chat_activity.read_text(encoding="utf-8")
    patched_chat_activity = patch_chat_activity_native_gift_receipts_source(chat_activity_source)
    if patched_chat_activity != chat_activity_source:
        chat_activity.write_text(patched_chat_activity, encoding="utf-8")
    final_chat_activity = chat_activity.read_text(encoding="utf-8")
    if (
        "// WaysGram: hydrate legacy native gift receipts" not in final_chat_activity
        or "WaysGramNativeGiftReceiptAdapter.hydrateLegacyReceipts" not in final_chat_activity
    ):
        raise ValueError("Native Saved Messages gift receipt integration was not applied")

    chat_action_cell = android_root / "TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatActionCell.java"
    if not chat_action_cell.is_file():
        raise FileNotFoundError(f"Required native ChatActionCell source not found: {chat_action_cell}")
    chat_action_source = chat_action_cell.read_text(encoding="utf-8")
    patched_chat_action = patch_chat_action_cell_waysgram_receipts_source(chat_action_source)
    if patched_chat_action != chat_action_source:
        chat_action_cell.write_text(patched_chat_action, encoding="utf-8")
    final_chat_action = chat_action_cell.read_text(encoding="utf-8")
    for marker in (
        "// WaysGram: render local purchase receipt title and media",
        'action.gift.slug.startsWith("waysgram://")',
        "WaysGramGiftMediaStore.getAnimationFile(waysGramSerial)",
        'title = "Подарок приобретён в WaysGram";',
    ):
        if marker not in final_chat_action:
            raise ValueError("Native Saved Messages WaysGram gift card rendering is incomplete")

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
        or "page.listView.getChildCount()" not in final_gifts_container
        or "cell.imageView.getImageReceiver().setImageBitmap(bitmap);" not in final_gifts_container
    ):
        raise ValueError("WaysGram profile gift cards must open native details and refresh inventory")

    star_gift_sheet = android_root / "TMessagesProj/src/main/java/org/telegram/ui/Stars/StarGiftSheet.java"
    if not star_gift_sheet.is_file():
        raise FileNotFoundError(f"Required native StarGiftSheet source not found: {star_gift_sheet}")
    star_gift_source = star_gift_sheet.read_text(encoding="utf-8")
    patched_star_gift_source = patch_star_gift_sheet_display_source(star_gift_source)
    if patched_star_gift_source != star_gift_source:
        star_gift_sheet.write_text(patched_star_gift_source, encoding="utf-8")
    final_star_gift_source = star_gift_sheet.read_text(encoding="utf-8")
    for marker in (
        "private String waysGramGiftSerial()",
        "topView.buttonsLayout.setVisibility(View.GONE);",
        "if (isWaysGramGift()) return false;",
        "if (isWaysGramGift()) return null;",
    ):
        if marker not in final_star_gift_source:
            raise ValueError("WaysGram native card detail rendering is incomplete")
    if "performWaysGramGiftUpgrade" in final_star_gift_source:
        raise ValueError("WaysGram profile detail must not route to paid randomized upgrades")

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

    gift_content = gift_sheet.read_text(encoding="utf-8")
    patched_gift_content = patch_gift_sheet_source(gift_content)
    if patched_gift_content != gift_content:
        gift_sheet.write_text(patched_gift_content, encoding="utf-8")
        gift_content = patched_gift_content

    gift_content = gift_sheet.read_text(encoding="utf-8")
    launch_content = launch_activity.read_text(encoding="utf-8")

    for marker in (
        "WaysGramGiftMediaStore.get(",
        "WaysGramGiftMediaStore.getMetadata(",
        "waysGramEmojiExisting.setVisibility(View.GONE)",
    ):
        if marker not in gift_content:
            raise ValueError("WaysGram native GiftCell artwork and metadata binding is missing")
    if "waysGramEmojiView.setText" in gift_content:
        raise ValueError("Emoji placeholders must remain hidden on WaysGram gift cards")

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
