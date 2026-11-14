#!/usr/bin/env python3
"""Apply WaysGram integrations while preserving Telegram's original interface.

Telegram Settings, login flow, main tabs, LaunchActivity and native GiftSheet
remain untouched.
"""
from __future__ import annotations

import argparse
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





def patch_profile_gifts_source(original: str) -> str:
    """Add a WaysGram-only inventory tab beside Archived Stories in the current user's profile."""
    tab_marker = "    public static final int TAB_POLL = 15;\n"
    tab_added = "    public static final int TAB_WAYSGRAM_GIFTS = 16;\n"
    field_marker = "    public ProfileGiftsContainer giftsContainer;\n"
    field_added = field_marker + "    private org.telegram.messenger.waysgram.WaysGramProfileGiftsView waysGramGiftsContainer;\n"
    import_marker = "import org.telegram.messenger.AndroidUtilities;\n"
    import_added = import_marker + "import org.telegram.messenger.waysgram.WaysGramProfileGiftsView;\n"

    markers_present = (
        "TAB_WAYSGRAM_GIFTS",
        "waysGramGiftsContainer",
        "WaysGramProfileGiftsView",
    )
    if all(marker in original for marker in markers_present):
        return original
    if any(marker in original for marker in markers_present):
        raise ValueError("SharedMediaLayout contains a partial WaysGram profile gifts tab patch")

    updated = replace_exactly_once(original, import_marker, import_added, "profile gifts view import")
    updated = replace_exactly_once(updated, tab_marker, tab_marker + tab_added, "profile gifts tab ID")
    updated = replace_exactly_once(updated, field_marker, field_added, "profile gifts view field")

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
        "                    waysGramGiftsContainer = new WaysGramProfileGiftsView(getContext());\n"
        "                }\n"
        "                if (waysGramGiftsContainer.getParent() != mediaPages[a]) {\n"
        "                    AndroidUtilities.removeFromParent(waysGramGiftsContainer);\n"
        "                    mediaPages[a].addView(waysGramGiftsContainer, new FrameLayout.LayoutParams(\n"
        "                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));\n"
        "                    mediaPages[a].emptyView.setVisibility(View.INVISIBLE);\n"
        "                }\n"
        "                waysGramGiftsContainer.refresh();\n"
        "            }\n"
    )
    updated = replace_exactly_once(
        updated, native_page_branch,
        native_page_branch[:-len("            }\n")] + custom_page_branch,
        "profile gifts inventory page"
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

    searchable_condition = "            type != TAB_GIFTS &&\\n"
    searchable_patched = "            type != TAB_GIFTS &&\\n            type != TAB_WAYSGRAM_GIFTS &&\\n"
    updated = replace_exactly_once(updated, searchable_condition, searchable_patched, "profile gifts search exclusion")

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
    for marker in ("TAB_WAYSGRAM_GIFTS", "WaysGramProfileGiftsView", "Мои подарки"):
        if marker not in final_profile_source:
            raise ValueError("WaysGram profile gift tab patch was not applied completely")

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
        "Verified: Telegram Settings, LaunchActivity, LoginActivity routing and native GiftSheet are preserved; WaysGram inventory is added as a separate tab in the current user profile."
    )


if __name__ == "__main__":
    main()
