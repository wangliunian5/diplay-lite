#!/usr/bin/env python3
"""Public-tree comment stripper for diplay-lite/app/build.gradle.kts.

The private branch keeps a per-vc decision log as long ``//`` comment runs in
this file (historically ~350 lines).  That log is internal engineering
knowledge and must not reach the public repository, so the sync flow runs this
script after ``git restore`` and before the credential check / commit.

Policy:
  * Remove *runs* of >= ``MIN_RUN`` consecutive ``//`` comment lines (the
    per-vc decision log).  Short isolated comments (1-9 lines, e.g. the
    signing-config and ciRelease notes) are kept -- they are genuine
    configuration documentation.
  * Blank lines that only separated a removed run from the next statement are
    collapsed so no double blank gaps remain.
  * The file must still be valid Kotlin after stripping: only full-line
    comments are removed, never code, and ``/* */`` blocks are left untouched.

Usage:
  python scripts/strip_public_comments.py [--check]

  --check  exit 1 (and print the offending line ranges) if the file still
           contains a removable run -- lets the sync flow assert cleanliness.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

TARGET = Path("diplay-lite/app/build.gradle.kts")
MIN_RUN = 10  # >= this many consecutive // lines counts as the decision log

LINE_COMMENT = re.compile(r"^\s*//")


def _runs(lines: list[str]) -> list[tuple[int, int]]:
    """Half-open [start, end) line ranges of consecutive // runs (0-based)."""
    runs: list[tuple[int, int]] = []
    i, n = 0, len(lines)
    while i < n:
        if LINE_COMMENT.match(lines[i]):
            j = i
            while j < n and LINE_COMMENT.match(lines[j]):
                j += 1
            runs.append((i, j))
            i = j
        else:
            i += 1
    return runs


def strip(text: str) -> str:
    lines = text.split("\n")
    kill = {idx for a, b in _runs(lines) if b - a >= MIN_RUN for idx in range(a, b)}
    if not kill:
        return text
    kept = [l for i, l in enumerate(lines) if i not in kill]
    # collapse runs of >1 blank line created by removals
    out: list[str] = []
    for l in kept:
        if l.strip() == "" and out and out[-1].strip() == "":
            continue
        out.append(l)
    return "\n".join(out)


def main() -> int:
    if not TARGET.exists():
        print(f"strip_public_comments: {TARGET} not found (run from repo root)", file=sys.stderr)
        return 2
    text = TARGET.read_text(encoding="utf-8")
    removable = [(a, b) for a, b in _runs(text.split("\n")) if b - a >= MIN_RUN]
    if "--check" in sys.argv:
        if removable:
            print(f"strip_public_comments: {TARGET} still has decision-log runs:", file=sys.stderr)
            for a, b in removable:
                print(f"  lines {a + 1}-{b} ({b - a} comment lines)", file=sys.stderr)
            return 1
        return 0
    stripped = strip(text)
    if stripped == text:
        print(f"strip_public_comments: {TARGET} already clean")
        return 0
    TARGET.write_text(stripped, encoding="utf-8")
    removed = len(text.split("\n")) - len(stripped.split("\n"))
    print(f"strip_public_comments: removed {removed} decision-log lines from {TARGET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
