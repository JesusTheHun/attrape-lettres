# DECISIONS.md — apps/game-android

Decisions taken inside the Android app. Repo-level decisions are `R` numbers in
the root `DECISIONS.md`; the iOS app's are `D` numbers in
`apps/game-ios/DECISIONS.md`. Nothing here is repeated from there.

---

## A1 — `:core` is a plain JVM module, not an Android library

`core/build.gradle.kts` applies `org.jetbrains.kotlin.jvm`, not
`com.android.library`. The consequence is the point: `android.*` does not
resolve in that module at all, so "just read `SharedPreferences` here" is a
compile error rather than a code-review note.

What it buys is the loop. `./gradlew :core:test` runs the whole game's logic —
content, ladders, round builders, the economy, migrations, the merge — as
ordinary JUnit in a couple of seconds, with no emulator, no SDK on the test
path, no Robolectric and no signing. That is the Kotlin analogue of `swift test`
running 1454 host tests on a Mac with no simulator, and it is why the port can
be written test-first against the web app's own suites.

The cost is one line of ceremony: the module compiles *by* JDK 21 and *for* JVM
17 bytecode (`jvmToolchain(21)` + `jvmTarget = JVM_17`), because `:platform`,
`:ui` and `:app` consume it as an ordinary Android dependency and Android's
floor is Java 17.

## A2 — `ExerciseId` is an enum with a frozen `wire` string

Kotlin enum constants are `SCREAMING_SNAKE`, the stored identifiers are
`kebab-case`, and the two must not be the same thing. `ExerciseId.FIRST_LETTER`
carries `wire = "first-letter"`.

The `wire` value is half of `ledgerKey` — `"<exercise>:<level>"` — which is a
key inside every child's clear counters. Those counters are persisted, and they
travel to the family's other phones over the sync wire, where the web app and
the iOS app write the same strings. Renaming a constant is free; renaming a
`wire` value orphans a level's history on Android only, silently, and the child
who cleared it sees the 10-star jackpot come back.

`fromWire` returns null rather than throwing: a stored key written by a newer
build is data to skip, not a crash.

## A3 — `KVStore` is synchronous, and that is invariant 1 expressed as a type

The interface in `core/platform/KVStore.kt` has no `suspend` on any method, and
neither does `ProfileStore`. This is not a style preference. Invariant 1 says
feedback fires inside the pointer-down handler before the UI commits, and the
award/spend path runs in that handler — there is nowhere to suspend. An async
adapter simply cannot implement the interface, so the shape that would break the
invariant does not compile.

`SharedPreferences` is synchronous for reads and `commit()`s on write, so the
Android adapter is a direct fit; this is the platform detail that made the web
app's `kv.ts` collapse to `localStorage` when Capacitor went away (R2).

## A4 — `applicationId` is `fr.dappit.attrapelettres`, without the hyphen

The iOS bundle id is `fr.dappit.attrape-lettres`. A hyphen is illegal in an
Android package segment, so the ids cannot match. Rather than invent a third
name, this matches the StoreKit product prefix that already exists —
`fr.dappit.attrapelettres.trial14` / `.unlock` — so the two stores' product ids
line up even though the app ids do not.

## A5 — Auto Backup carries everything except the device id

Play has no price-0 in-app product, so unlike iOS there is no signed StoreKit
`purchaseDate` to date the trial from; the stamp is a local value, and if it did
not survive a reinstall the 14-day trial would be infinitely re-rollable. Auto
Backup is what carries it, so `allowBackup="true"` and `shared_prefs` is
included.

The device id is excluded, in both `backup_rules.xml` (API 30-) and
`data_extraction_rules.xml` (API 31+), including the device-transfer section.

That exclusion protects invariant 9. Stars and clears are per-device grow-only
counters merged with `max()`, which is lossless only while one counter key
belongs to exactly one live device. Restore a backup onto a new phone while the
old phone is still in the house, and a backed-up device id would put two live
devices on one key: `max()` would then read two devices' independent progress as
one stale one, and a child would lose stars. A restored device mints a fresh id
instead, and nothing is lost by that — its historical counts travel inside the
roster blob under the OLD id and still sum correctly; only new earnings land on
the new key.

## A6 — AGP 9 and the toolchain it forces

Pinned: AGP 9.3.1, Kotlin 2.4.10, Gradle 9.6.1 (wrapper), Compose BOM
2026.06.01, JDK 21, `compileSdk = 37` / `compileSdkMinor = 1`, `minSdk = 26`,
`targetSdk = 36`.

Two of those are not free choices:

- **No `org.jetbrains.kotlin.android` plugin.** AGP 9 has built-in Kotlin
  support and *rejects the build* if the plugin is also applied. `:core` still
  applies `org.jetbrains.kotlin.jvm` — that one is a different plugin for a
  non-Android module and stays.
- **`compileSdk` 37.1 is forced by AndroidX, not chosen.** `lifecycle` 2.11 and
  `activity-compose` 1.13 refuse to be consumed by a project compiling against
  36. `targetSdk` stays at 36 deliberately: raising `compileSdk` only allows
  newer APIs, while raising `targetSdk` opts the app in to new runtime
  behaviour, and there is nothing to gain from opting into behaviour changes
  before there is an app to observe them in.

`minSdk = 26` is the floor that makes adaptive launcher icons and
`AudioAttributes`-configured `SoundPool` unconditional.

## A7 — The sync client is a declared seam with a stub behind it

`services/api`'s contract is described as frozen — `GET`/`PUT /household/{id}`
with ETag optimistic concurrency, `POST /events`, `POST /errors` — but it is
being reworked, and the household document shape is what a client binds to
hardest.

So `:core` ships `SyncTransport` as an interface and a stub implementation, and
no HTTP exists in this app yet. `Merge.kt` is unaffected by that: it is pure and
takes no transport, so the expensive, invariant-9-critical half of sync can be
ported and property-tested now, and only the last few hundred bytes of wire
plumbing wait for the contract. `Wire.kt` gets the same treatment as iOS —
`WireChild` has no `name` field at all, so invariant 10 is a property of the
type rather than of a serialiser configuration.

## A8 — `kotlinx.serialization` for the stored blobs

The persisted roster is JSON in `SharedPreferences`, the same v4 schema the web
app and iOS read, and the migration rule (R2, storage.ts's header) requires
reading three older shapes forward. That means all-optional mirror types and
tolerance for unknown keys — `ignoreUnknownKeys = true`, `explicitNulls = false`
— which is exactly what `LooseDecoding` does on iOS.

`org.json` was the alternative: it is in the platform, adds nothing to the APK,
and would have kept `:core` dependency-free. It was rejected because it lives in
`android.*`, which A1 makes unavailable in `:core` on purpose, and because
hand-written readers for four schema versions are precisely the code that
silently drops a field.
