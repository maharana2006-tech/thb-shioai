#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

find "$ROOT_DIR/src" "$ROOT_DIR/target" \
  -type f \
  \( -name '._*' -o -name '.DS_Store' \) \
  -delete 2>/dev/null || true
