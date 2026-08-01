# apps/game-android

**Route: native Kotlin + Compose.** Scaffolded; the port has started.

There used to be a second option. The repo carried a Capacitor shell — the same
web bundle in a WebView, `cap add android` away — and it was removed rather than
finished, because the decision is to go fully native on both phones. So this app
mirrors `apps/game-ios/`: a real port, not a wrapper. `ARCHITECTURE.md` is the
map; `DECISIONS.md` records what was chosen here and why.

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

./gradlew :core:test        # the whole game's logic, as JUnit, no emulator
./gradlew test              # every module's host tests
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
scripts/stage-vo.sh         # hard-link the 845 baked clips into :platform
```

`:core` is a plain JVM module and does not apply the Android plugin, so
`./gradlew :core:test` is the Kotlin analogue of `swift test`: the entire game's
logic runs as ordinary JUnit in milliseconds, with no emulator, no store, no
network and no signing. That is the loop the port is written in.

`local.properties` is not committed; it holds `sdk.dir` for this machine.

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
  `UserDefaults` — see `app/src/main/res/xml/backup_rules.xml`, which excludes
  the device id on purpose and says why.
- **The sync wire.** `GET`/`PUT /household/{id}` with ETag optimistic
  concurrency, merged on the device. See `services/api/README.md`. That contract
  is being reworked, so `:core` declares the transport as an interface and ships
  a stub; no HTTP exists in this app yet (A7).
- **Money, and the platforms not being symmetric.** Play has no price-0 IAP, so
  the trial is a local stamp rather than a signed receipt, and Play Family
  Library does **not** share in-app purchases — Android restores per Google
  account only. Copy promising "toute la famille" must not appear here.

## What it gets for free

The baked voice-over. 845 clips live once in `apps/game-web/src/vo/clips/`,
named by a hash of the utterance they speak. `scripts/stage-vo.sh` hard-links
them into `platform/src/main/assets/vo/` instead of committing a third copy.
What *is* committed is `platform/src/main/assets/vo-manifest.txt`, byte-identical
to the iOS one, so the coverage test ("every utterance the app can speak has a
clip") runs on a machine that has never staged the audio.

## Status

| Layer | State |
|-------|-------|
| Toolchain, Gradle module graph, debug APK | done — builds |
| `:core` — domain, content, levels, rewards, persistence, sync, licensing, telemetry, VO | done — **551 tests**, 83 classes |
| `:art` — SVG runtime, 17 exercise icons, 4 word images, 5 mascots + their rig | done — **185 tests**, 33 classes |
| `:platform` — storage, clip bank, SFX, haptics, reduce-motion, transports | done — **70 tests**, 11 classes |
| `:ui` — the Compose screens, and wiring the adapters into `MainActivity` | next |

806 host tests, none skipped. `:core` is complete except for the sync client's
ETag/412 retry loop and household identity, which wait on the API contract (A7).
Everything the game computes — every ladder, every round builder, the economy, the
migrations, the merge, the entitlement state machine — is ported and tested on the
host, as is everything it draws.

Two things a green build here does not prove, and neither is proven yet: **no
pixel has ever been produced** (`SvgCanvas` records a draw list that is heavily
tested; `SvgRender` replays it into Compose and is not), and **nothing has run on
a device or an emulator** — audio latency in particular is unmeasurable from the
host. Billing is absent by decision: no Play Billing dependency is taken and
`:app` wires the stub, so nothing can be bought.
