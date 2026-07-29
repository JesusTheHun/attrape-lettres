#!/bin/bash
# Build, install, launch and screenshot the app on a simulator.
#
# The whole reason this exists: an agent can read the PNG it produces. That
# closes the loop from "I changed a Path" to "here is what a child would see",
# with no human in it. See DECISIONS.md D3.
#
#   scripts/sim.sh                 build + install + launch + screenshot
#   scripts/sim.sh shot out.png    screenshot the running app only
#   scripts/sim.sh log             stream the app's log output
set -euo pipefail

export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVICE=${SIM_DEVICE:-iPhone 17 Pro}
BUNDLE=fr.dappit.attrape-lettres
DD=${DERIVED_DATA:-$HERE/.build/xcode}

udid() {
  xcrun simctl list devices available \
    | grep "$DEVICE (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/'
}

case "${1:-run}" in
  shot)
    xcrun simctl io "$(udid)" screenshot "${2:-$HERE/.build/shot.png}"
    ;;
  log)
    xcrun simctl spawn "$(udid)" log stream --style compact \
      --predicate "subsystem CONTAINS 'attrape' OR processImagePath CONTAINS 'Attrape'"
    ;;
  run)
    xcodebuild -project "$HERE/App/AttrapeLettres.xcodeproj" -scheme AttrapeLettres \
      -destination "platform=iOS Simulator,name=$DEVICE" \
      -derivedDataPath "$DD" CODE_SIGNING_ALLOWED=NO build \
      2>&1 | grep -E 'error:|warning:|BUILD' || true

    UDID=$(udid)
    xcrun simctl boot "$UDID" 2>/dev/null || true
    xcrun simctl bootstatus "$UDID" -b >/dev/null
    xcrun simctl install "$UDID" "$DD/Build/Products/Debug-iphonesimulator/Attrape-Lettres.app"
    xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
    xcrun simctl launch "$UDID" "$BUNDLE"
    ;;
  *)
    echo "usage: sim.sh [run|shot <path>|log]" >&2
    exit 2
    ;;
esac
