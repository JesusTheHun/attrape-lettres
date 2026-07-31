#!/usr/bin/env bash
#
# stage-vo.sh — put the baked voice-over where AGP can bundle it.
#
# The Android twin of apps/game-ios/scripts/stage-vo.sh, and deliberately the
# same script with two paths changed. The 855 baked clips live ONCE in this
# repo, in the web app at `apps/game-web/src/vo/clips/`. They are the shared
# source of truth: the generator (`pnpm vo:build`, a web-app job) writes them
# there and all three apps read them from there. Committing a third 13.7 MiB
# copy under `apps/game-android/` would triple the git weight and hand us a
# drift bug for free, so `platform/src/main/assets/vo/` is STAGED, never
# committed.
#
# What IS committed is `platform/src/main/assets/vo-manifest.txt` — one
# `<voKey>.<ext>` per line. That is what makes the coverage test ("every
# utterance the app can speak has a clip") run on a machine that has never
# staged the audio, which is the whole point: CI catches a new word shipping
# without a voice.
#
# Usage:
#   scripts/stage-vo.sh                # hard-link (default; instant, 0 bytes)
#   scripts/stage-vo.sh --copy         # real copies (for archiving a bundle)
#   scripts/stage-vo.sh --check        # verify staged == manifest, stage nothing
#   scripts/stage-vo.sh --write-manifest   # re-derive the manifest from the bank
#   scripts/stage-vo.sh --clean        # remove the staged clips
#
# Hard links are the default because the source and the destination are on the
# same volume by construction (both inside this checkout) and because a hard
# link cannot go stale the way a copy can: re-baking a clip in place is picked
# up with no re-stage. `--copy` exists for the case where they are not (a
# worktree on a different volume falls back to copying automatically).

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pkg="$(cd "$here/.." && pwd)"          # apps/game-android/
dest="$pkg/platform/src/main/assets/vo"
manifest="$pkg/platform/src/main/assets/vo-manifest.txt"

# The bank lives in the web app — `apps/game-web/src/vo/clips`, our sibling —
# and it has exactly ONE home: the repository's MAIN working tree. That matters
# because an app may be developed in a linked git worktree, which carries its
# own copy of the bank at whatever HEAD it was created from; staging from there
# silently bakes a stale bank into the app. So: ask git for the main working
# tree first, and only then fall back to the obvious relative guesses (for a
# plain, worktree-free checkout).
bank_rel="apps/game-web/src/vo/clips"

find_bank() {
  if [ -n "${VO_CLIPS_DIR:-}" ]; then
    printf '%s\n' "$VO_CLIPS_DIR"
    return 0
  fi
  local main
  main="$(git -C "$pkg" worktree list --porcelain 2>/dev/null | awk '/^worktree /{print $2; exit}')" || true
  if [ -n "$main" ] && [ -d "$main/$bank_rel" ]; then
    printf '%s\n' "$main/$bank_rel"
    return 0
  fi
  local candidates=(
    "$pkg/../game-web/src/vo/clips"              # the sibling app, same checkout
    "$pkg/../../$bank_rel"                       # equivalently, from the repo root
  )
  local c
  for c in "${candidates[@]}"; do
    if [ -d "$c" ]; then
      (cd "$c" && pwd)
      return 0
    fi
  done
  return 1
}

mode="link"
case "${1:-}" in
  --copy)  mode="copy" ;;
  --clean) mode="clean" ;;
  --check) mode="check" ;;
  --write-manifest) mode="manifest" ;;
  "")      ;;
  *) echo "stage-vo.sh: unknown option '$1'" >&2; exit 2 ;;
esac

if [ "$mode" = "clean" ]; then
  rm -rf "$dest"
  echo "stage-vo: removed $dest"
  exit 0
fi

if [ "$mode" = "check" ]; then
  missing=0
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    [ -e "$dest/$name" ] || { missing=$((missing + 1)); }
  done < "$manifest"
  total="$(grep -c . "$manifest")"
  if [ "$missing" -eq 0 ]; then
    echo "stage-vo: OK — $total/$total clips staged in $dest"
    exit 0
  fi
  echo "stage-vo: $missing of $total manifest clips are NOT staged (run scripts/stage-vo.sh)" >&2
  exit 1
fi

bank="$(find_bank)" || {
  echo "stage-vo: cannot find $bank_rel — set VO_CLIPS_DIR=/path/to/clips" >&2
  exit 1
}

mkdir -p "$(dirname "$manifest")"

if [ "$mode" = "manifest" ]; then
  # Sorted with LC_ALL=C so the committed file is byte-stable across machines.
  ( cd "$bank" && ls | grep -E '\.(m4a|mp3|wav)$' | LC_ALL=C sort ) > "$manifest"
  echo "stage-vo: wrote $(grep -c . "$manifest") entries to $manifest"
  exit 0
fi

mkdir -p "$dest"

# Drop anything staged that the bank no longer has, so a deleted clip does not
# linger in the bundle and quietly keep a stale utterance alive.
for f in "$dest"/*.m4a "$dest"/*.mp3 "$dest"/*.wav; do
  [ -e "$f" ] || continue
  [ -e "$bank/$(basename "$f")" ] || rm -f "$f"
done

linked=0
copied=0
for src in "$bank"/*.m4a "$bank"/*.mp3 "$bank"/*.wav; do
  [ -e "$src" ] || continue
  name="$(basename "$src")"
  out="$dest/$name"
  # Already the same inode (a live hard link) — nothing to do, and re-baking the
  # clip in place is picked up for free.
  if [ -e "$out" ] && [ "$out" -ef "$src" ]; then
    continue
  fi
  rm -f "$out"
  if [ "$mode" = "link" ] && ln "$src" "$out" 2>/dev/null; then
    linked=$((linked + 1))
  else
    cp -p "$src" "$out"
    copied=$((copied + 1))
  fi
done

staged="$(ls "$dest" | grep -cE '\.(m4a|mp3|wav)$' || true)"
echo "stage-vo: $staged clips in $dest (${linked} linked, ${copied} copied) from $bank"

# Warn — do not fail — when the bank and the committed manifest disagree. The
# manifest is what the tests assert against; a mismatch means somebody baked a
# clip and forgot `--write-manifest`, or deleted one and forgot to re-bake.
expected="$(grep -c . "$manifest")"
if [ "$staged" != "$expected" ]; then
  echo "stage-vo: WARNING — staged $staged but the manifest lists $expected." >&2
  echo "stage-vo:           re-run with --write-manifest if the bank is the newer one." >&2
fi
