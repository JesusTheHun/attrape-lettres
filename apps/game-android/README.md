# apps/game-android — not built yet

**Route decided: native Kotlin + Compose.** Nothing is scaffolded.

There used to be a second option. The repo carried a Capacitor shell — the same
web bundle in a WebView, `cap add android` away — and it was removed rather than
finished, because the decision is to go fully native on both phones. So this app
mirrors `apps/game-ios/`: a real port, not a wrapper.

## What a port has to reproduce

`apps/game-ios/` is the worked example, and its `ARCHITECTURE.md` and
`DECISIONS.md` are the map. The short version of what is actually hard:

- **The invariants in the root `CLAUDE.md`.** Feedback fires on pointer-down
  before the UI commits; animation never goes through the render path; there is
  no fail state; every level is unlocked. These are why the game feels alive to a
  child, and they are the things a rewrite loses silently.
- **The economy, exactly.** `sessionReward` is the only function that returns
  points. The reward curve, the `ledgerKey` string format and the per-device
  counters are a persistence contract shared with the web app and the sync wire —
  `"<exercise>:<level>"`, not a struct key.
- **The v4 storage schema and its migrations.** Stars and clears are per-device
  counters precisely so a child playing offline on two phones merges instead of
  losing stars. Android Auto Backup is the analogue of what iOS gets from
  `UserDefaults`.
- **The sync wire.** `GET`/`PUT /household/{id}` with ETag optimistic
  concurrency, merged on the device. See `services/api/README.md`.
- **Money, and the platforms not being symmetric.** Play has no price-0 IAP, so
  the trial is a local stamp rather than a signed receipt, and Play Family
  Library does **not** share in-app purchases — Android restores per Google
  account only. Copy promising "toute la famille" must not appear here.

## What it gets for free

The baked voice-over. 855 clips live once in `apps/game-web/src/vo/clips/`,
named by a hash of the utterance they speak. `apps/game-ios/scripts/stage-vo.sh`
hard-links them into the iOS bundle at build time instead of committing a second
copy; do the same rather than adding 13.7 MiB to the repo again.
