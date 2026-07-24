#!/usr/bin/env bash
#
# Remove macOS AppleDouble / resource-fork junk (._* and .DS_Store) from the
# whole repository, INCLUDING the .git directory.
#
# Why this exists: this project lives on a FAT/exFAT volume, and macOS writes a
# "._<name>" sidecar next to every file to hold extended attributes. Those files
# are already git-ignored (see .gitignore), but they keep reappearing on disk and
# can even land inside .git/objects, where git chokes with
# "non-monotonic index ._pack-*.idx". Run this whenever that happens.
#
# Usage:  bash scripts/clean-appledouble.sh
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "Cleaning AppleDouble files under: $ROOT_DIR"

# Prefer Apple's dot_clean (merges the ._ sidecar back into the real file) when
# available; fall back to a plain delete otherwise.
if command -v dot_clean >/dev/null 2>&1; then
  dot_clean -m . 2>/dev/null || true
fi

before="$(find . -name '._*' -o -name '.DS_Store' | wc -l | tr -d ' ')"
find . \( -name '._*' -o -name '.DS_Store' \) -type f -delete 2>/dev/null || true
after="$(find . -name '._*' -o -name '.DS_Store' | wc -l | tr -d ' ')"

echo "Removed $((before - after)) file(s); $after remaining."
