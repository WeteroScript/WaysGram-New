#!/usr/bin/env python3
"""Preserve Telegram's startup and gift UI while exposing WaysGram from Settings.

The dedicated WaysGram screens use their own API session. They are opened from a
single Settings entry; Telegram's login flow, main tabs, LaunchActivity and
native GiftSheet remain untouched.
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

WAYS_GRAM_IMPORTS = (
    "import org.telegram.messenger.waysgram.WaysGramHomeFragment;\n"
    "import org.telegram.messenger.waysgram.WaysGramLoginFragment;\n"
    "import org.telegram.messenger.waysgram.WaysGramSessionStore;\n"
)
WAYS_GRAM_SETTINGS_ROW = (
    '        items.add(SettingCell.Factory.of(27, IconBackgroundColors.GREEN.top, '
    'IconBackgroundColors.GREEN.bottom, R.drawable.settings_features, "WaysGram", '
    '"Вход, сообщения, группы и каналы"));'
)
WAYS_GRAM_SETTINGS_HANDLER = (
    "            case 27: {\n"
    "                WaysGramSessionStore waysGramSessionStore = "
    "WaysGramSessionStore.getInstance(getParentActivity());\n"
    "                if (waysGramSessionStore.hasSession()) {\n"
    "                    presentSettingFragment(new WaysGramHomeFragment());\n"
    "                } else {\n"
    "                    presentSettingFragment(new WaysGramLoginFragment());\n"
    "                }\n"
    "                break;\n"
    "            }\n"
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


def patch_settings_source(original: str) -> str:
    """Add one WaysGram entry to Settings without replacing Telegram navigation."""
    required_markers = (
        "import org.telegram.messenger.waysgram.WaysGramHomeFragment;",
        "import org.telegram.messenger.waysgram.WaysGramLoginFragment;",
        "import org.telegram.messenger.waysgram.WaysGramSessionStore;",
        WAYS_GRAM_SETTINGS_ROW,
        "case 27: {",
    )
    present = [marker in original for marker in required_markers]
    if all(present):
        return original
    if any(present):
        raise ValueError("SettingsActivity contains a partial WaysGram entry; restore the pinned source")

    updated = replace_exactly_once(
        original,
        "import org.telegram.messenger.UserObject;\n",
        "import org.telegram.messenger.UserObject;\n" + WAYS_GRAM_IMPORTS,
        "SettingsActivity WaysGram imports",
    )
    updated = replace_exactly_once(
        updated,
        '        items.add(SettingCell.Factory.of(23, IconBackgroundColors.PURPLE.top, '
        'IconBackgroundColors.PURPLE.bottom, R.drawable.settings_features, '
        'getString(R.string.TelegramFeatures)));\n',
        '        items.add(SettingCell.Factory.of(23, IconBackgroundColors.PURPLE.top, '
        'IconBackgroundColors.PURPLE.bottom, R.drawable.settings_features, '
        'getString(R.string.TelegramFeatures)));\n' + WAYS_GRAM_SETTINGS_ROW + "\n",
        "SettingsActivity Help section",
    )

    on_click_start = "    private void onClick(UItem item, View view, int position, float x, float y) {\n"
    on_long_click_start = "    private boolean onLongClick(UItem item, View view, int position, float x, float y) {\n"
    start = updated.find(on_click_start)
    end = updated.find(on_long_click_start, start + len(on_click_start))
    if start < 0 or end < 0:
        raise ValueError("SettingsActivity onClick method boundaries were not found")
    method = updated[start:end]
    method = replace_exactly_once(
        method,
        "            case 20:\n",
        WAYS_GRAM_SETTINGS_HANDLER + "            case 20:\n",
        "SettingsActivity WaysGram click handler",
    )
    updated = updated[:start] + method + updated[end:]

    if not all(marker in updated for marker in required_markers):
        raise ValueError("WaysGram Settings entry was not applied completely")
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
    settings_activity = android_root / "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java"
    for path in (gift_sheet, launch_activity, settings_activity):
        if not path.is_file():
            raise FileNotFoundError(f"Required upstream source file not found: {path}")

    gift_content = gift_sheet.read_text(encoding="utf-8")
    launch_content = launch_activity.read_text(encoding="utf-8")
    settings_content = settings_activity.read_text(encoding="utf-8")

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

    patched_settings = patch_settings_source(settings_content)
    if patched_settings != settings_content:
        settings_activity.write_text(patched_settings, encoding="utf-8")

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

    final_settings = settings_activity.read_text(encoding="utf-8")
    if WAYS_GRAM_SETTINGS_ROW not in final_settings or "presentSettingFragment(new WaysGramHomeFragment())" not in final_settings:
        raise ValueError("WaysGram Settings entry is missing after patching")
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
        "Verified: original Telegram LaunchActivity, LoginActivity routing, main tabs and GiftSheet are preserved; "
        "a dedicated WaysGram entry is available in Settings."
    )


if __name__ == "__main__":
    main()
