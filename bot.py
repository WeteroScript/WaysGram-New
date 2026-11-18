"""BotHost entry point: run the AuthBot that lives in its own repository.

The hosting panel knows exactly one repository (``WaysGram-New``). The bot code itself is not
kept in sync by hand: this file pulls it from ``WaysGram-AuthBot`` at startup, caches it by
commit, and then executes it.

Lookup order:

1. ``AUTHBOT_SOURCE=remote`` (default) - fetch the requested commit from GitHub, cached in
   ``AUTHBOT_HOME``; a restart with an already cached commit does not touch the network.
2. the cache of a previous start (used when GitHub is unreachable);
3. the copy committed in ``authbot/`` of this repository (last-resort fallback, logged loudly).

Environment variables:

    AUTHBOT_REPO          owner/name of the bot repository   (default WeteroScript/WaysGram-AuthBot)
    AUTHBOT_REF           branch or tag to follow            (default main)
    AUTHBOT_COMMIT        pin an exact commit; disables update checks
    AUTHBOT_HOME          cache directory                    (default /data/authbot, else ./.authbot-cache)
    AUTHBOT_GITHUB_TOKEN  token for a private repository or a higher API rate limit
    AUTHBOT_SOURCE        auto | remote | local             (default auto)
    AUTHBOT_SYNC_DEPS     install the pulled requirements.txt before start (0/1, default 0)

Diagnostics: ``python bot.py --check`` resolves and downloads the bot without starting it.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
VENDORED_DIR = os.path.join(ROOT, "authbot")

DEFAULT_REPO = "WeteroScript/WaysGram-AuthBot"
DEFAULT_REF = "main"
ENTRY_NAME = "bot.py"
HTTP_TIMEOUT = 20


def log(message: str) -> None:
    sys.stderr.write(f"[authbot-bootstrap] {message}\n")
    sys.stderr.flush()


def _env(name: str, default: str = "") -> str:
    value = os.getenv(name)
    return default if value is None or value == "" else value


def cache_home() -> str:
    configured = _env("AUTHBOT_HOME")
    if configured:
        return os.path.abspath(configured)
    if os.path.isdir("/data") and os.access("/data", os.W_OK):
        return "/data/authbot"
    return os.path.join(ROOT, ".authbot-cache")


def _request(url: str, token: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "waysgram-authbot-bootstrap"})
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
        return response.read()


def resolve_commit(repo: str, ref: str, token: str) -> str:
    """Returns the commit hash the ref points at, or an empty string if GitHub is unreachable."""
    url = f"https://api.github.com/repos/{repo}/commits/{ref}"
    try:
        payload = json.loads(_request(url, token).decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError) as error:
        log(f"cannot resolve {repo}@{ref} through the API ({type(error).__name__}); "
            "falling back to the archive hash")
        return ""
    sha = str(payload.get("sha", "")).strip()
    return sha


def download_archive(repo: str, ref: str, token: str) -> bytes:
    url = f"https://codeload.github.com/{repo}/tar.gz/refs/heads/{ref}"
    try:
        return _request(url, token)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    # A tag or a commit hash is not a branch, so ask for the generic archive endpoint.
    archive = _request(f"https://codeload.github.com/{repo}/tar.gz/{ref}", token)
    return archive


def extract_archive(payload: bytes, target_dir: str) -> int:
    """Extracts the archive without the leading repository directory; returns the file count."""
    extracted = 0
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as archive:
        for member in archive.getmembers():
            parts = [part for part in member.name.split("/") if part not in ("", ".")]
            if not parts:
                continue
            parts = parts[1:]  # drop the "repo-<ref>/" prefix
            if not parts or ".." in parts:
                continue
            destination = os.path.join(target_dir, *parts)
            if member.isdir():
                os.makedirs(destination, exist_ok=True)
                continue
            if not member.isfile():
                # Symlinks and devices are never needed by the bot and are skipped on purpose.
                continue
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            source = archive.extractfile(member)
            if source is None:
                continue
            with open(destination, "wb") as handle:
                shutil.copyfileobj(source, handle)
            extracted += 1
    return extracted


def fetch_source(repo: str, ref: str, pinned_commit: str, token: str, home: str) -> str:
    """Ensures a cached copy of the requested revision exists and returns its directory."""
    os.makedirs(home, exist_ok=True)
    sha = pinned_commit or resolve_commit(repo, ref, token)
    if sha:
        cached = os.path.join(home, sha)
        if os.path.isfile(os.path.join(cached, ENTRY_NAME)):
            log(f"using cached revision {sha[:12]}")
            return cached

    log(f"downloading {repo}@{sha or ref}")
    payload = download_archive(repo, sha or ref, token)
    if not sha:
        sha = hashlib.sha256(payload).hexdigest()

    cached = os.path.join(home, sha)
    if os.path.isfile(os.path.join(cached, ENTRY_NAME)):
        log(f"using cached revision {sha[:12]}")
        return cached

    # Extract into a temporary directory first so a failed download never leaves a partial
    # revision that a later start would happily execute.
    staging = tempfile.mkdtemp(prefix=f".staging-{sha[:12]}-", dir=home)
    try:
        files = extract_archive(payload, staging)
        if not os.path.isfile(os.path.join(staging, ENTRY_NAME)):
            raise RuntimeError(
                f"{repo}@{sha or ref} does not contain {ENTRY_NAME}; check AUTHBOT_REPO/AUTHBOT_REF"
            )
        os.replace(staging, cached)
        log(f"unpacked {files} files into {cached}")
    finally:
        if os.path.isdir(staging):
            shutil.rmtree(staging, ignore_errors=True)
    return cached


def latest_cached(home: str) -> str:
    if not os.path.isdir(home):
        return ""
    candidates = [
        name for name in os.listdir(home)
        if not name.startswith(".") and os.path.isfile(os.path.join(home, name, ENTRY_NAME))
    ]
    if not candidates:
        return ""
    candidates.sort(key=lambda name: os.path.getmtime(os.path.join(home, name)), reverse=True)
    return os.path.join(home, candidates[0])


def resolve_source(mode: str, repo: str, ref: str, pinned: str, token: str, home: str) -> str:
    if mode == "local":
        log("AUTHBOT_SOURCE=local: running the copy committed in authbot/")
        return VENDORED_DIR

    if mode != "remote":
        mode = "auto"

    try:
        return fetch_source(repo, ref, pinned, token, home)
    except Exception as error:  # noqa: BLE001 - any network/archive problem needs a fallback
        log(f"remote pull failed ({type(error).__name__}: {error})")
        if mode == "remote":
            raise

    cached = latest_cached(home)
    if cached:
        log(f"falling back to the cached revision in {cached}")
        return cached

    if os.path.isfile(os.path.join(VENDORED_DIR, ENTRY_NAME)):
        log("falling back to the copy committed in authbot/; it may lag behind the bot repository")
        return VENDORED_DIR

    raise RuntimeError(
        "AuthBot sources are unavailable: the pull failed, the cache is empty and authbot/ is "
        "missing in this repository"
    )


def sync_dependencies(source_dir: str) -> None:
    requirements = os.path.join(source_dir, "requirements.txt")
    if not os.path.isfile(requirements):
        return
    log("AUTHBOT_SYNC_DEPS=1: installing the pulled requirements")
    subprocess.run(
        [sys.executable, "-m", "pip", "install", "--no-cache-dir", "-r", requirements],
        check=True,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="WaysGram AuthBot bootstrap")
    parser.add_argument("--check", action="store_true",
                        help="pull the bot and print where it landed, without starting it")
    parser.add_argument("--repo", default=_env("AUTHBOT_REPO", DEFAULT_REPO))
    parser.add_argument("--ref", default=_env("AUTHBOT_REF", DEFAULT_REF))
    parser.add_argument("--commit", default=_env("AUTHBOT_COMMIT"))
    parser.add_argument("--source", default=_env("AUTHBOT_SOURCE", "auto"),
                        choices=("auto", "remote", "local"))
    args = parser.parse_args()

    token = _env("AUTHBOT_GITHUB_TOKEN")
    home = cache_home()
    try:
        source_dir = resolve_source(args.source, args.repo, args.ref, args.commit, token, home)
    except Exception as error:  # noqa: BLE001 - the host needs one readable line, not a traceback
        log(f"cannot obtain the AuthBot sources: {type(error).__name__}: {error}")
        log("check AUTHBOT_REPO/AUTHBOT_REF, the network access of the host and AUTHBOT_SOURCE")
        return 2

    if not os.path.isfile(os.path.join(source_dir, ENTRY_NAME)):
        log(f"{ENTRY_NAME} is missing in {source_dir}")
        return 2

    if args.check:
        print(json.dumps({
            "source": source_dir,
            "entry": os.path.join(source_dir, ENTRY_NAME),
            "repo": args.repo,
            "ref": args.ref,
            "commit": os.path.basename(source_dir) if os.path.abspath(source_dir) != VENDORED_DIR else "",
            "cache_home": home,
        }, ensure_ascii=False, indent=2))
        return 0

    if _env("AUTHBOT_SYNC_DEPS", "0") == "1":
        sync_dependencies(source_dir)

    if source_dir not in sys.path:
        sys.path.insert(0, source_dir)

    import runpy

    runpy.run_path(os.path.join(source_dir, ENTRY_NAME), run_name="__main__")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
