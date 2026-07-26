#!/bin/bash
# Screenshot an HTML page with headless Chrome (no deps).
# usage: screenshot.sh page.html out.png [WIDTHxHEIGHT]   (default 1400x1000)
set -euo pipefail
HTML="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
OUT="$2"
SIZE="${3:-1400x1000}"
SIZE="${SIZE/x/,}"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
if [ ! -x "$CHROME" ]; then
  # Fallback: newest Playwright headless shell cache, if any.
  CHROME="$(ls -d "$HOME"/Library/Caches/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-mac-*/chrome-headless-shell 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "${CHROME:-}" ] || [ ! -x "$CHROME" ]; then
  echo "no headless Chrome found" >&2
  exit 1
fi

"$CHROME" --headless --disable-gpu --hide-scrollbars \
  --screenshot="$OUT" --window-size="$SIZE" "file://$HTML" 2>/dev/null
echo "$OUT"
