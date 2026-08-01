#!/usr/bin/env bash
#
# emulator.sh — boot the phone this app is developed against.
#
# `./gradlew test` is the loop the port is written in and it needs no device.
# This script is for the other half: the facts a host JUnit run structurally
# cannot see. `:ui` is asserted as data and rasterises nothing, so whether a
# shadow is cropped, whether `FontFamily.Cursive` resolves to a joined face,
# whether the keyboard covers « Ton prénom » — none of them fail a green build.
# A19 in DECISIONS.md is one that got all the way to a commit before an
# emulator caught it.
#
# Usage:
#   pnpm android:emulator                # boot, wait, leave it running
#   pnpm android:emulator -- --cold      # ignore the saved snapshot
#   pnpm android:emulator -- --install   # fetch the SDK packages first (~4.3 GB)
#   pnpm android:emulator -- --recreate  # rebuild the AVD from scratch
#   AVD_NAME=foo pnpm android:emulator   # a different AVD
#
# Anything after a `--` that this script does not recognise is handed to the
# emulator binary, so `-- -no-audio` and friends work.
#
# The SDK packages are NOT downloaded without being asked: 4.3 GB is not
# something a `pnpm` script should start on its own. Missing ones are reported
# with the exact command, or fetched with `--install`.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

AVD="${AVD_NAME:-al-pixel7}"
# API 36 is `targetSdk`. `google_apis` rather than `google_apis_playstore`
# because the Play images refuse `adb root`, and root is how the v4 schema, the
# per-device counters and the backup_rules.xml exclusions get inspected on
# disk. No store is wired anyway — :app ships StubPurchaseStore (A7).
IMAGE="${AVD_IMAGE:-system-images;android-36;google_apis;arm64-v8a}"
DEVICE="${AVD_DEVICE:-pixel_7}"

cold=0
install_missing=0
recreate=0
passthrough=()
for arg in "$@"; do
  case "$arg" in
    # pnpm forwards the separator itself, so `pnpm android:emulator -- --cold`
    # arrives here as a literal `--`. Dropping it keeps it out of the emulator's
    # argv, where it is not valid.
    --) ;;
    --cold) cold=1 ;;
    --install) install_missing=1 ;;
    --recreate) recreate=1 ;;
    *) passthrough+=("$arg") ;;
  esac
done

die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
note() { printf '\033[2m%s\033[0m\n' "$*" >&2; }

# --- The SDK -----------------------------------------------------------------
#
# Order matters: an explicit environment variable wins, then whatever Gradle is
# actually building against (local.properties is not committed and is the one
# per-machine truth), then the Homebrew cask, then Google's own default.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f "$here/local.properties" ]]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' "$here/local.properties" | head -1)"
fi
for candidate in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk"; do
  [[ -n "$sdk" ]] && break
  [[ -d "$candidate" ]] && sdk="$candidate"
done
[[ -n "$sdk" && -d "$sdk" ]] || die "No Android SDK. Set ANDROID_HOME, or put sdk.dir in $here/local.properties."

export ANDROID_HOME="$sdk"
export ANDROID_SDK_ROOT="$sdk"
PATH="$sdk/platform-tools:$sdk/emulator:$sdk/cmdline-tools/latest/bin:$PATH"
export PATH

# avdmanager and sdkmanager are Java programs; the emulator itself is not.
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
    [[ -d "$candidate" ]] && export JAVA_HOME="$candidate"
  done
fi

command -v adb >/dev/null || die "No adb in $sdk/platform-tools."

# --- The packages ------------------------------------------------------------
missing=()
[[ -x "$sdk/emulator/emulator" ]] || missing+=("emulator")
image_dir="$sdk/${IMAGE//;//}"
[[ -d "$image_dir" ]] || missing+=("$IMAGE")

if (( ${#missing[@]} )); then
  if (( install_missing )); then
    note "Fetching: ${missing[*]}  (~4.3 GB, once)"
    command -v sdkmanager >/dev/null || die "No sdkmanager. Install the cmdline-tools package."
    yes | sdkmanager --licenses >/dev/null 2>&1 || true
    sdkmanager "${missing[@]}"
  else
    quoted=""
    for pkg in "${missing[@]}"; do quoted+=" '$pkg'"; done
    die "Missing SDK packages: ${missing[*]}
Run once, or pass --install:
  sdkmanager$quoted"
  fi
fi

# --- Already up? -------------------------------------------------------------
#
# Booting a second copy of the same AVD fails with a lock file rather than
# anything readable, so check first.
if adb devices | grep -q '^emulator-[0-9]*[[:space:]]*device$'; then
  note "An emulator is already running:"
  adb devices | grep '^emulator-' >&2
  exit 0
fi

# --- The AVD -----------------------------------------------------------------
avd_home="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
config="$avd_home/$AVD.avd/config.ini"

if (( recreate )) && [[ -d "$avd_home/$AVD.avd" ]]; then
  note "Removing AVD $AVD"
  avdmanager delete avd -n "$AVD" >/dev/null 2>&1 || rm -rf "$avd_home/$AVD.avd" "$avd_home/$AVD.ini"
fi

if [[ ! -f "$config" ]]; then
  note "Creating AVD $AVD ($DEVICE, $IMAGE)"
  command -v avdmanager >/dev/null || die "No avdmanager. Install the cmdline-tools package."
  echo no | avdmanager create avd -n "$AVD" -k "$IMAGE" -d "$DEVICE" --force >/dev/null 2>&1 || true
  [[ -f "$config" ]] || die "avdmanager did not create $config."

  # Three edits to what avdmanager writes.
  #
  #   hw.gpu.enabled  — it defaults to `no`, which is software rendering, and
  #                     this app is a Compose app that animates every tap.
  #   hw.ramSize      — 2 GB default; 4 GB keeps the confetti honest.
  #   hw.keyboard=no  — DELIBERATELY LEFT ALONE. With a hardware keyboard the
  #                     soft IME never appears, and « Ton prénom » being covered
  #                     by the IME is one of the things only a device can tell
  #                     us (the iOS port hit exactly that).
  sed -i '' \
    -e 's/^hw\.gpu\.enabled=no/hw.gpu.enabled=yes/' \
    -e 's/^hw\.gpu\.mode=auto/hw.gpu.mode=host/' \
    -e 's/^hw\.ramSize=2G/hw.ramSize=4096/' \
    "$config" 2>/dev/null || \
  sed -i \
    -e 's/^hw\.gpu\.enabled=no/hw.gpu.enabled=yes/' \
    -e 's/^hw\.gpu\.mode=auto/hw.gpu.mode=host/' \
    -e 's/^hw\.ramSize=2G/hw.ramSize=4096/' \
    "$config"
fi

# --- Boot --------------------------------------------------------------------
boot_args=(-avd "$AVD" -gpu host -no-boot-anim)
(( cold )) && boot_args+=(-no-snapshot-load)
(( ${#passthrough[@]} )) && boot_args+=("${passthrough[@]}")

note "Booting $AVD"
"$sdk/emulator/emulator" "${boot_args[@]}" >/dev/null 2>&1 &
emulator_pid=$!

# `adb wait-for-device` returns as soon as adbd answers, which is minutes before
# the launcher exists. sys.boot_completed is the real signal.
adb wait-for-device
for _ in $(seq 1 120); do
  kill -0 "$emulator_pid" 2>/dev/null || die "The emulator exited during boot."
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 2
done
[[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] || die "Timed out waiting for boot."

note "Ready: $(adb shell getprop ro.product.model | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
cat >&2 <<'NEXT'

  cd apps/game-android && ./gradlew installDebug
  adb shell am start -n fr.dappit.attrapelettres/.MainActivity

NEXT
