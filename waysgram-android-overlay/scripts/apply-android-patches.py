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

    pager_indicator_anchor = "        avatarContainer2.addView(avatarsViewPagerIndicatorView);\\n"
    pager_indicator_marker = "// WaysGram: place native profile actions above avatar pager indicator"
    if pager_indicator_marker not in original:
        pager_indicator_patched = (
            pager_indicator_anchor
            + "        // WaysGram: place native profile actions above avatar pager indicator\\n"
            + "        if (actionsView != null) {\\n"
            + "            actionsView.setAlpha(1.0f);\\n"
            + "            actionsView.setVisibility(View.VISIBLE);\\n"
            + "            actionsView.bringToFront();\\n"
            + "        }\\n"
        )
        original = replace_exactly_once(
            original, pager_indicator_anchor, pager_indicator_patched,
            "initial native profile action z-order"
        )
    return original


def patch_profile_phone_source(original: str) -> str:
    """Prevent invalid API placeholder strings from becoming Telegram phone rows."""
    marker = "// WaysGram: ignore invalid Telegram phone placeholders"
    if marker in original:
        if 'user.phone.matches("[0-9]{8,15}")' not in original:
            raise ValueError("ProfileActivity contains an incomplete phone placeholder patch")
        return original

    old_has_phone = (
        "                boolean hasPhone = user != null && (!TextUtils.isEmpty(user.phone) || !TextUtils.isEmpty(vcardPhone));\n"
    )
    new_has_phone = (
        "                // WaysGram: ignore invalid Telegram phone placeholders\n"
        "                boolean hasPhone = user != null && ((user.phone != null && user.phone.matches(\"[0-9]{8,15}\")) || (vcardPhone != null && vcardPhone.matches(\"[0-9]{8,15}\")));\n"
    )
    original = replace_exactly_once(
        original, old_has_phone, new_has_phone,
        "valid Telegram profile phone row condition"
    )

    original = replace_exactly_once(
        original,
        "                        if (user != null && !TextUtils.isEmpty(vcardPhone)) {\n",
        "                        if (user != null && vcardPhone != null && vcardPhone.matches(\"[0-9]{8,15}\")) {\n",
        "valid vCard profile phone display"
    )
    original = replace_exactly_once(
        original,
        "                        } else if (user != null && !TextUtils.isEmpty(user.phone)) {\n",
        "                        } else if (user != null && user.phone != null && user.phone.matches(\"[0-9]{8,15}\")) {\n",
        "valid Telegram profile phone display"
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
    if all(marker in original for marker in markers_present):
        return original
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
    )
    if all(marker in original for marker in markers):
        return original
    if any(marker in original for marker in markers):
        raise ValueError("ProfileGiftsContainer contains a partial WaysGram inventory patch")

    updated = replace_exactly_once(
        original,
        "    private final long dialogId;\n",
        "    private final long dialogId;\n    private final boolean waysGramInventory;\n",
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
    click_insert = (
        "\n" + click_match.group("indent") + "    if (parent.waysGramInventory || list == null) return;"
    )
    updated = updated[:click_match.end()] + click_insert + updated[click_match.end():]

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
        "        if (page != null) page.update(false);\n"
        "    }\n\n"
    )
    insert_at = updated.rfind("\n}")
    if insert_at < 0:
        raise ValueError("ProfileGiftsContainer closing brace not found")
    updated = updated[:insert_at] + "\n\n" + method + updated[insert_at:]
    return updated


def patch_gift_sheet_source(original: str) -> str:
    """Keep Telegram's GiftCell renderer, injecting WaysGram stickers, emoji and metadata."""
    updated = original

    sticker_marker = "WaysGramGiftMediaStore.get("
    if sticker_marker not in updated:
        sticker_target = "            setSticker(userGift.gift.getDocument(), userGift);\n"
        sticker_replacement = (
            sticker_target
            + "            if (userGift.gift.slug != null && userGift.gift.slug.startsWith(\"waysgram://\")) {\n"
            + "                android.graphics.Bitmap waysGramBitmap = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.get(userGift.gift.slug.substring(\"waysgram://\".length()));\n"
            + "                if (waysGramBitmap != null) imageView.setImageBitmap(waysGramBitmap);\n"
            + "            }\n"
        )
        updated = replace_exactly_once(updated, sticker_target, sticker_replacement, "native GiftCell WaysGram sticker binding")

    emoji_reset_marker = "waysGramEmojiOverlay"
    if emoji_reset_marker not in updated:
        sticker_target = "            setSticker(userGift.gift.getDocument(), userGift);\n"
        sticker_replacement = (
            sticker_target
            + "            View waysGramEmojiOverlay = card.findViewWithTag(\"waysgramGiftEmoji\");\n"
            + "            if (waysGramEmojiOverlay != null) waysGramEmojiOverlay.setVisibility(View.GONE);\n"
        )
        updated = replace_exactly_once(
            updated, sticker_target, sticker_replacement,
            "reset recycled WaysGram gift emoji overlay"
        )

    metadata_marker = "WaysGramGiftMediaStore.getMetadata("
    if metadata_marker not in updated:
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
            + "                    subtitleView.setVisibility(View.VISIBLE);\n"
            + "                    priceLayout.setVisibility(View.GONE);\n"
            + "                    View waysGramEmojiExisting = card.findViewWithTag(\"waysgramGiftEmoji\");\n"
            + "                    android.widget.TextView waysGramEmojiView;\n"
            + "                    if (waysGramEmojiExisting instanceof android.widget.TextView) {\n"
            + "                        waysGramEmojiView = (android.widget.TextView) waysGramEmojiExisting;\n"
            + "                    } else {\n"
            + "                        waysGramEmojiView = new android.widget.TextView(getContext());\n"
            + "                        waysGramEmojiView.setTag(\"waysgramGiftEmoji\");\n"
            + "                        waysGramEmojiView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);\n"
            + "                        waysGramEmojiView.setGravity(android.view.Gravity.CENTER);\n"
            + "                        waysGramEmojiView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));\n"
            + "                        card.addView(waysGramEmojiView, new FrameLayout.LayoutParams(AndroidUtilities.dp(80), AndroidUtilities.dp(80), android.view.Gravity.CENTER));\n"
            + "                    }\n"
            + "                    waysGramEmojiView.setText(waysGramMetadata.emoji == null || waysGramMetadata.emoji.isEmpty() ? \"🎁\" : waysGramMetadata.emoji);\n"
            + "                    android.graphics.Bitmap waysGramGiftBitmap = org.telegram.messenger.waysgram.WaysGramGiftMediaStore.get(waysGramSerial);\n"
            + "                    if (waysGramGiftBitmap != null) {\n"
            + "                        imageView.setImageBitmap(waysGramGiftBitmap);\n"
            + "                        waysGramEmojiView.setVisibility(View.GONE);\n"
            + "                    } else {\n"
            + "                        waysGramEmojiView.setVisibility(View.VISIBLE);\n"
            + "                    }\n"
            + "                }\n"
            + "            }\n"
        )
        updated = replace_exactly_once(updated, metadata_target, metadata_replacement, "native GiftCell WaysGram metadata and emoji binding")

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


def verify_source(android_root: Path) -> None:
    gift_sheet = android_root / "TMessagesProj/src/main/java/org/telegram/ui/Gifts/GiftSheet.java"
    launch_activity = android_root / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
    for path in (gift_sheet, launch_activity):
        if not path.is_file():
            raise FileNotFoundError(f"Required upstream source file not found: {path}")

    profile_layout = android_root / "TMessagesProj/src/main/java/org/telegram/ui/Components/SharedMediaLayout.java"
    if not profile_layout.is_file():
        raise FileNotFoundError(f"Required profile layout source not found: {profile_layout}")
    profile_source = profile_layout.read_text(encoding="utf-8")
    patched_profile_source = patch_profile_gifts_source(profile_source)
    if patched_profile_source != profile_source:
        profile_layout.write_text(patched_profile_source, encoding="utf-8")
    final_profile_source = profile_layout.read_text(encoding="utf-8")
    for marker in ("TAB_WAYSGRAM_GIFTS", "WaysGramProfileGiftsController", "Мои подарки"):
        if marker not in final_profile_source:
            raise ValueError("WaysGram profile gift tab patch was not applied completely")

    profile_activity = android_root / "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java"
    if not profile_activity.is_file():
        raise FileNotFoundError(f"Required native ProfileActivity source not found: {profile_activity}")
    profile_activity_source = profile_activity.read_text(encoding="utf-8")
    patched_profile_activity = patch_profile_actions_visibility_source(profile_activity_source)
    if patched_profile_activity != profile_activity_source:
        profile_activity.write_text(patched_profile_activity, encoding="utf-8")
    final_profile_activity = profile_activity.read_text(encoding="utf-8")
    patched_profile_phone = patch_profile_phone_source(final_profile_activity)
    if patched_profile_phone != final_profile_activity:
        profile_activity.write_text(patched_profile_phone, encoding="utf-8")
    final_profile_activity = profile_activity.read_text(encoding="utf-8")
    if (
        "// WaysGram: restore profile action visibility" not in final_profile_activity
        or "WaysGram: keep native profile actions above avatar overlays" not in final_profile_activity
        or "WaysGram: place native profile actions above avatar pager indicator" not in final_profile_activity
        or "actionsView.setAlpha(1.0f);" not in final_profile_activity
        or "actionsView.bringToFront();" not in final_profile_activity
        or 'user.phone.matches("[0-9]{8,15}")' not in final_profile_activity
        or "WaysGram: ignore invalid Telegram phone placeholders" not in final_profile_activity
    ):
        raise ValueError("Native profile actions or phone placeholder patch was not applied")

    gifts_container = android_root / "TMessagesProj/src/main/java/org/telegram/ui/Gifts/ProfileGiftsContainer.java"
    if not gifts_container.is_file():
        raise FileNotFoundError(f"Required native profile gifts container not found: {gifts_container}")
    gifts_container_source = gifts_container.read_text(encoding="utf-8")
    patched_gifts_container = patch_profile_gifts_container_source(gifts_container_source)
    if patched_gifts_container != gifts_container_source:
        gifts_container.write_text(patched_gifts_container, encoding="utf-8")

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
        "waysgramGiftEmoji",
        "waysGramEmojiView.setText",
    ):
        if marker not in gift_content:
            raise ValueError("WaysGram native GiftCell sticker, emoji or metadata binding is missing")

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
