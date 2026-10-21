#!/usr/bin/env python3
"""Public-tree credential guard.

Rejects credential containers, private keys, ability-signed binaries and local
evidence archives before they are published to the public repository.

Why this exists: the MFi accessory identity (``identity.pk8`` / ``certificate.p7b``)
used to be tracked in Git and reached the public branch history. Committing a key
does not make it usable later and cannot be undone by deleting it afterwards --
any clone still carries the object. This guard is the barrier that should have
existed in the first place.

Semantics:
  * Only files tracked by Git are inspected (``git ls-files``). Untracked local
    files are irrelevant to what gets published.
  * Both path patterns and file content are checked, because a key can be a raw
    PEM block dropped anywhere in the tree.
  * Exits non-zero with a readable report when anything is rejected, so CI fails
    loudly instead of shipping the problem.

Usage:  python3 scripts/check_public_tree.py
"""

from __future__ import annotations

import re
import subprocess
import sys

# Path patterns that must never enter the public tree.
REJECTED_SUFFIXES = (
    # accessory / code-signing identity containers
    ".pk8", ".p7b", ".p12", ".pfx", ".key", ".keystore", ".jks",
    ".p8", ".der", ".cer", ".pem",
    # installable distribution artifacts (they bundle the identity)
    ".apk", ".aab", ".idsig", ".apks", ".xapk",
)

# Whole directories that are local-only by convention.
REJECTED_PREFIXES = (
    "local-archive/",   # forensics, decompilation output, device logs
    "tmp-analysis/",    # logcat captures, screen recordings, log fires
    "docs-local/",      # private reverse-engineering and planning notes
    "logs/",
    "gitee-logs/",
    "t3-root-debug/",
    "hardware-stage/",
    "backup/",
    ".private/",
    "auth/",            # local staging dir for runtime identity assets
)

REJECTED_BASENAMES = {
    "release-signing.properties",
    "local.properties",   # carries GITEE_LOG_TOKEN
}

# Content patterns searched inside tracked text files.
CONTENT_PATTERNS = (
    # Require a base64 body after the header so that obfuscated string constants
    # (deliberately scrambled markers, which carry no key material) do not trip the
    # check, while a real inlined PEM does.
    ("private key block",
     re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----\s*\r?\n[A-Za-z0-9+/=]{40,}")),
    # Credentials embedded directly into remote URLs, e.g. the Gitee/GitHub remotes
    # that carry a personal access token. Never match a literal secret here -- this
    # script itself ships in the public tree, so it must never contain one.
    ("credentials in remote URL",
     re.compile(rb"https://[^\s/:@]+:[^\s/:@]+@(?:gitee|github)\.com")),
    ("GitHub PAT", re.compile(rb"\bghp_[A-Za-z0-9]{20,}\b")),
    ("hardcoded secret assignment",
     re.compile(rb"(?i)\b(token|password|secret|api_?key)\s*[=:]\s*[\"'][^\"'\s]{8,}[\"']")),
)

MAX_CONTENT_SCAN_BYTES = 2 * 1024 * 1024
TEXT_SAMPLE_BYTES = 8192


def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z"],
        check=True, capture_output=True,
    ).stdout
    return [p.decode("utf-8", "replace")
            for p in out.split(b"\0") if p]


def main() -> int:
    files = tracked_files()
    if not files:
        print("check_public_tree: nothing tracked, nothing to check")
        return 0

    by_path: list[tuple[str, str]] = []
    for path in files:
        lowered = path.lower()
        if lowered.endswith(REJECTED_SUFFIXES):
            by_path.append((path, "credential container or build artifact"))
            continue
        if lowered.startswith(REJECTED_PREFIXES):
            by_path.append((path, "local-only directory"))
            continue
        if lowered.split("/")[-1] in REJECTED_BASENAMES:
            by_path.append((path, "local secrets file"))

    by_content: list[tuple[str, str]] = []
    for path in files:
        if any(path == bad for bad, _ in by_path):
            continue
        try:
            with open(path, "rb") as handle:
                blob = handle.read(MAX_CONTENT_SCAN_BYTES)
        except OSError:
            continue
        if b"\0" in blob[:TEXT_SAMPLE_BYTES]:
            continue  # binary attachments that passed the suffix check are fine
        for label, pattern in CONTENT_PATTERNS:
            if pattern.search(blob):
                by_content.append((path, label))
                break

    if not by_path and not by_content:
        print(f"check_public_tree: OK - {len(files)} tracked files, nothing rejected")
        return 0

    print("check_public_tree: FAILED - refusing to publish these tracked paths",
          file=sys.stderr)
    print("", file=sys.stderr)
    if by_path:
        print("-- rejected by path --", file=sys.stderr)
        for path, reason in by_path:
            print(f"  {path}  ({reason})", file=sys.stderr)
        print("", file=sys.stderr)
    if by_content:
        print("-- rejected by content --", file=sys.stderr)
        for path, label in by_content:
            print(f"  {path}  ({label})", file=sys.stderr)
        print("", file=sys.stderr)
    print("Remove them from tracking (git rm --cached) and add the pattern to",
          file=sys.stderr)
    print(".gitignore. Note: deleting a file does not remove it from Git",
          file=sys.stderr)
    print("history -- rewriting the history is required in that case.",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
