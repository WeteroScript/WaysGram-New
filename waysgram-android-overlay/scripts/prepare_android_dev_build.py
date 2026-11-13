#!/usr/bin/env python3
"""Configure Firebase for WaysGram as the default app and retain Telegram FCM separately.

The build uses the registered WaysGram google-services.json. Telegram's original
FCM client is emitted as a named FirebaseApp and used by the Telegram token provider.
WaysGram also keeps its dedicated named FirebaseApp for its own notification token.
"""
from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
from urllib.parse import urlsplit


WAYSGRAM_FIREBASE_PROJECT_ID = "waysgram-59956"
WAYSGRAM_FIREBASE_PACKAGE_NAME = "org.waysgram.messenger"


def replace_one(pattern: str, replacement: str, content: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, content, count=1, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"Expected exactly one {label} setting; found {count}")
    return updated


def replace_string_value(content: str, name: str, value: str) -> tuple[str, bool]:
    pattern = re.compile(
        '(<string[ ]+name="' + re.escape(name) + '">).*?(</string>)',
        flags=re.DOTALL,
    )
    updated, count = pattern.subn(
        lambda match: match.group(1) + value + match.group(2),
        content,
        count=1,
    )
    return updated, count == 1


def patch_string_resources(android_root: Path) -> None:
    values_dirs = android_root / "TMessagesProj/src/main/res"
    files = list(values_dirs.glob("values*/strings.xml"))
    if not files:
        raise FileNotFoundError(f"No strings.xml resources found under {values_dirs}")
    found_app_name = False
    found_beta_name = False
    for path in files:
        content = path.read_text(encoding="utf-8")
        content, found = replace_string_value(content, "AppName", "WaysGram")
        found_app_name = found_app_name or found
        content, found = replace_string_value(content, "AppNameBeta", "WaysGram Dev")
        found_beta_name = found_beta_name or found
        path.write_text(content, encoding="utf-8")
    if not found_app_name or not found_beta_name:
        raise ValueError("AppName/AppNameBeta resources were not found")


def configure_default_api_base_url(android_root: Path) -> None:
    value = os.getenv("WAYSGRAM_API_BASE_URL", "").strip().rstrip("/")
    if not value:
        return

    parsed = urlsplit(value)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.netloc
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
        or parsed.username
        or parsed.password
    ):
        raise ValueError("WAYSGRAM_API_BASE_URL must be an absolute HTTP(S) origin")

    login_sheet = (
        android_root
        / "TMessagesProj/src/main/java/org/telegram/messenger/waysgram/WaysGramLoginSheet.java"
    )
    if not login_sheet.is_file():
        raise FileNotFoundError(f"WaysGram login sheet not found: {login_sheet}")

    content = login_sheet.read_text(encoding="utf-8")
    content = replace_one(
        r'^    private static final String DEFAULT_API_BASE_URL = .*;$',
        f'    private static final String DEFAULT_API_BASE_URL = "{value}";',
        content,
        "WaysGram default API base URL",
    )
    login_sheet.write_text(content, encoding="utf-8")


def configure_waysgram_firebase(android_root: Path) -> bool:
    """Generate WaysGram default Google Services config and separate Telegram FCM options."""
    raw_config = (
        os.getenv("FIREBASE_CONFIG", "").strip()
        or os.getenv("WAYSGRAM_FIREBASE_CONFIG_JSON", "").strip()
    )
    required = os.getenv("WAYSGRAM_REQUIRE_FIREBASE_CONFIG", "").strip().lower() in {
        "1", "true", "yes"
    }
    if not raw_config:
        if required:
            raise ValueError("FIREBASE_CONFIG is required for an Android APK build")
        return False

    try:
        config = json.loads(raw_config)
    except json.JSONDecodeError as exc:
        raise ValueError("FIREBASE_CONFIG must contain valid Firebase JSON") from exc

    project_info = config.get("project_info")
    clients = config.get("client")
    if not isinstance(project_info, dict) or not isinstance(clients, list):
        raise ValueError("Firebase JSON is missing project_info or client")

    project_id = str(project_info.get("project_id") or "").strip()
    sender_id = str(project_info.get("project_number") or "").strip()
    if project_id != WAYSGRAM_FIREBASE_PROJECT_ID or not sender_id.isdigit():
        raise ValueError("Firebase JSON must belong to the WaysGram project waysgram-59956")

    matching = []
    for client in clients:
        client_info = client.get("client_info", {}) if isinstance(client, dict) else {}
        android_info = client_info.get("android_client_info", {}) if isinstance(client_info, dict) else {}
        if android_info.get("package_name") == WAYSGRAM_FIREBASE_PACKAGE_NAME:
            matching.append(client)

    if len(matching) != 1:
        raise ValueError(
            "Firebase JSON must contain exactly one Android client for "
            + WAYSGRAM_FIREBASE_PACKAGE_NAME
        )

    client_info = matching[0].get("client_info", {})
    app_id = str(client_info.get("mobilesdk_app_id") or "").strip()
    api_keys = matching[0].get("api_key", [])
    api_key = str(api_keys[0].get("current_key") or "").strip() if api_keys else ""
    if not app_id.startswith(f"1:{sender_id}:android:") or not api_key:
        raise ValueError("Firebase Android client is missing mobilesdk_app_id or current_key")

    telegram_json = android_root / "TMessagesProj_App/google-services.json"
    if not telegram_json.is_file():
        raise FileNotFoundError(f"Pinned Telegram Firebase config not found: {telegram_json}")
    try:
        telegram_config = json.loads(telegram_json.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError("Pinned Telegram google-services.json is invalid") from exc

    telegram_project = telegram_config.get("project_info", {})
    telegram_clients = telegram_config.get("client", [])
    if not isinstance(telegram_project, dict) or not isinstance(telegram_clients, list):
        raise ValueError("Pinned Telegram Firebase config is missing project_info or client")
    telegram_sender_id = str(telegram_project.get("project_number") or "").strip()
    telegram_project_id = str(telegram_project.get("project_id") or "").strip()
    telegram_matches = []
    for client in telegram_clients:
        client_info = client.get("client_info", {}) if isinstance(client, dict) else {}
        android_info = client_info.get("android_client_info", {}) if isinstance(client_info, dict) else {}
        if android_info.get("package_name") == "org.telegram.messenger":
            telegram_matches.append(client)
    if len(telegram_matches) != 1 or not telegram_sender_id.isdigit() or not telegram_project_id:
        raise ValueError("Pinned Telegram Firebase config has no unique org.telegram.messenger client")

    telegram_client = telegram_matches[0]
    telegram_app_id = str(telegram_client.get("client_info", {}).get("mobilesdk_app_id") or "").strip()
    telegram_api_keys = telegram_client.get("api_key", [])
    telegram_api_key = (
        str(telegram_api_keys[0].get("current_key") or "").strip()
        if telegram_api_keys else ""
    )
    if (
        not telegram_app_id.startswith(f"1:{telegram_sender_id}:android:")
        or not telegram_api_key
    ):
        raise ValueError("Pinned Telegram Firebase client is missing app ID or API key")

    def java_string(value: str) -> str:
        return json.dumps(value, ensure_ascii=True)

    telegram_java = f'''package org.telegram.messenger.waysgram;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.List;

/** Build-generated Telegram FCM credentials, isolated from WaysGram's default Firebase app. */
public final class TelegramFirebaseConfig {{
    private static final String APP_NAME = "telegram-push";
    private static final Object LOCK = new Object();
    private static final String APP_ID = {java_string(telegram_app_id)};
    private static final String API_KEY = {java_string(telegram_api_key)};
    private static final String PROJECT_ID = {java_string(telegram_project_id)};
    private static final String SENDER_ID = {java_string(telegram_sender_id)};

    private TelegramFirebaseConfig() {{
    }}

    public static FirebaseMessaging getMessaging(Context context) {{
        if (context == null) {{
            return null;
        }}
        synchronized (LOCK) {{
            FirebaseApp app = null;
            List<FirebaseApp> apps = FirebaseApp.getApps(context);
            for (FirebaseApp candidate : apps) {{
                if (APP_NAME.equals(candidate.getName())) {{
                    app = candidate;
                    break;
                }}
            }}
            if (app == null) {{
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setApplicationId(APP_ID)
                        .setApiKey(API_KEY)
                        .setProjectId(PROJECT_ID)
                        .setGcmSenderId(SENDER_ID)
                        .build();
                app = FirebaseApp.initializeApp(context, options, APP_NAME);
            }}
            return app == null ? null : app.get(FirebaseMessaging.class);
        }}
    }}
}}
'''

    telegram_output = (
        android_root
        / "TMessagesProj/src/main/java/org/telegram/messenger/waysgram/TelegramFirebaseConfig.java"
    )
    telegram_output.parent.mkdir(parents=True, exist_ok=True)
    telegram_output.write_text(telegram_java, encoding="utf-8")

    waysgram_java = f'''package org.telegram.messenger.waysgram;

/**
 * Build-generated public Firebase client options for the WaysGram secondary app.
 * The default Google Services app is also configured for WaysGram.
 */
public final class WaysGramFirebaseConfig {{
    public static final String APP_ID = {java_string(app_id)};
    public static final String API_KEY = {java_string(api_key)};
    public static final String PROJECT_ID = {java_string(project_id)};
    public static final String SENDER_ID = {java_string(sender_id)};

    private WaysGramFirebaseConfig() {{
    }}

    public static boolean isConfigured() {{
        return APP_ID.length() > 0 && API_KEY.length() > 0
                && PROJECT_ID.length() > 0 && SENDER_ID.length() > 0;
    }}
}}
'''
    waysgram_output = (
        android_root
        / "TMessagesProj/src/main/java/org/telegram/messenger/waysgram/WaysGramFirebaseConfig.java"
    )
    waysgram_output.parent.mkdir(parents=True, exist_ok=True)
    waysgram_output.write_text(waysgram_java, encoding="utf-8")

    # Google Services requires a client matching the resolved Android application ID.
    # Telegram's original FCM app has already been preserved above as a named FirebaseApp.
    google_services_output = android_root / "TMessagesProj_App/google-services.json"
    google_services_output.write_text(
        json.dumps(config, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return True


def configured_android_package() -> str:
    """Read the Android package from the existing Firebase config secret."""
    raw_config = (os.getenv("FIREBASE_CONFIG", "").strip() or os.getenv("WAYSGRAM_FIREBASE_CONFIG_JSON", "").strip())
    if not raw_config:
        return WAYSGRAM_FIREBASE_PACKAGE_NAME
    try:
        config = json.loads(raw_config)
    except json.JSONDecodeError as exc:
        raise ValueError("FIREBASE_CONFIG must contain valid Firebase JSON") from exc
    project_info = config.get("project_info")
    clients = config.get("client")
    if not isinstance(project_info, dict) or not isinstance(clients, list):
        raise ValueError("FIREBASE_CONFIG is missing project_info or client")
    packages = []
    for client in clients:
        client_info = client.get("client_info", {}) if isinstance(client, dict) else {}
        android_info = client_info.get("android_client_info", {}) if isinstance(client_info, dict) else {}
        package_name = str(android_info.get("package_name") or "").strip()
        if package_name:
            packages.append(package_name)
    if WAYSGRAM_FIREBASE_PACKAGE_NAME not in packages:
        raise ValueError(
            "FIREBASE_CONFIG must include the registered Android package "
            + WAYSGRAM_FIREBASE_PACKAGE_NAME
        )
    return WAYSGRAM_FIREBASE_PACKAGE_NAME


def configure_debug_application_id_suffix(android_root: Path) -> None:
    """Use the registered WaysGram package for AfatDebug, without Telegram's .beta suffix."""
    build_file = android_root / "TMessagesProj_App/build.gradle"
    if not build_file.is_file():
        raise FileNotFoundError(f"Android app Gradle file not found: {build_file}")

    content = build_file.read_text(encoding="utf-8")
    beta_pattern = re.compile(r'^([ \t]*)applicationIdSuffix "\.beta"$', re.MULTILINE)
    empty_pattern = re.compile(r'^[ \t]*applicationIdSuffix ""$', re.MULTILINE)
    beta_matches = beta_pattern.findall(content)
    empty_matches = empty_pattern.findall(content)

    if len(beta_matches) == 1 and len(empty_matches) == 0:
        content = beta_pattern.sub(r'\1applicationIdSuffix ""', content, count=1)
    elif len(beta_matches) == 0 and len(empty_matches) == 1:
        pass
    else:
        raise ValueError(
            "Expected exactly one debug .beta suffix or its already-configured empty value"
        )

    build_file.write_text(content, encoding="utf-8")


def configure_mlkit_translation_dependencies(android_root: Path) -> None:
    """Add ML Kit language detection and translation to the module compiling WaysGram chat."""
    build_file = android_root / "TMessagesProj/build.gradle"
    if not build_file.is_file():
        raise FileNotFoundError(f"Telegram project Gradle file not found: {build_file}")

    content = build_file.read_text(encoding="utf-8")
    dependency_lines = (
        "    implementation 'com.google.mlkit:language-id:17.0.6'",
        "    implementation 'com.google.mlkit:translate:17.0.3'",
    )
    if all(line in content for line in dependency_lines):
        return

    block = re.search(r"(?m)^dependencies\s*\{", content)
    if block is None:
        raise ValueError("TMessagesProj/build.gradle has no dependencies block")

    opening = content.find("{", block.start())
    depth = 0
    closing = -1
    for index in range(opening, len(content)):
        character = content[index]
        if character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                closing = index
                break
    if closing < 0:
        raise ValueError("TMessagesProj/build.gradle dependencies block is unbalanced")

    dependencies_block = content[opening:closing + 1]
    missing = [line for line in dependency_lines if line.split(":")[1] not in dependencies_block]
    if missing:
        insertion = "\n" + "\n".join(missing) + "\n"
        content = content[:closing] + insertion + content[closing:]
        build_file.write_text(content, encoding="utf-8")

    updated = build_file.read_text(encoding="utf-8")
    for line in dependency_lines:
        if line not in updated:
            raise ValueError(f"Failed to add ML Kit dependency: {line}")


def configure_android_root(android_root: Path) -> None:
    if not (android_root / "gradlew").is_file():
        raise FileNotFoundError(f"Android Gradle wrapper not found in {android_root}")

    gradle_properties = android_root / "gradle.properties"
    content = gradle_properties.read_text(encoding="utf-8")
    content = replace_one(
        r"^APP_PACKAGE=.*$",
        f"APP_PACKAGE={configured_android_package()}",
        content,
        "Android application ID",
    )
    gradle_properties.write_text(content, encoding="utf-8")
    configure_debug_application_id_suffix(android_root)
    configure_mlkit_translation_dependencies(android_root)
    configure_default_api_base_url(android_root)
    firebase_configured = configure_waysgram_firebase(android_root)
    patch_string_resources(android_root)

    print(
        "Configured WaysGram as the default Firebase app and preserved Telegram FCM "
        "in a named Firebase app; Telegram API settings, permissions, and manifests remain intact. "
        + ("WaysGram Firebase configured." if firebase_configured else "Firebase secrets were not supplied.")
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--android-root", required=True, type=Path)
    args = parser.parse_args()
    configure_android_root(args.android_root)


if __name__ == "__main__":
    main()
