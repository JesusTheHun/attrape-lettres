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
`:platform`'s `HttpSyncTransport` is a labelled sketch: the addressing, the
ETag/412 re-pull-re-merge-retry loop and the wire-to-wire fold are real and
written to the contract as described, while the document codec throws
`SyncContractNotLanded` and household identity is not invented at all. Nothing
constructs it — `Adapters.kt` wires `ProfileStore(sync = null)` — and if
something one day does before the codec lands, what escapes is an `IOException`,
which `syncNow` already swallows, so the device just keeps playing alone.
(Telemetry is the opposite case and is fully wired: `HttpTelemetryTransport` is
real, because `POST /events` binds to a closed property allowlist rather than to
the household shape.) When the contract lands, the codec belongs in `:core` next
to `Wire.kt`, where a test can assert on the serialised bytes.

`Merge.kt` is unaffected by any of that: it is pure and
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

## A9 — Android modules name `kotlin-test-junit` themselves

`kotlin.test.Test` is an `expect` typealias with no `actual` until a runner
binding is on the test classpath. `:core` gets one implicitly: `useJUnitPlatform()`
tells the Kotlin plugin which variant of `kotlin-test` to resolve. An Android
library variant has no equivalent switch, so nothing supplies it, and every
`import kotlin.test.Test` in the module is unresolved while `assertEquals` from
the same package resolves fine — a confusing failure that looks like a broken
import and is not.

`:art` and `:platform` therefore add `testImplementation(libs.kotlin.test.junit)`
next to `libs.kotlin.test`. Adding `junit:junit` alone does **not** fix it: the
problem is variant selection on `kotlin-test`, not the absence of JUnit. Same
artifact family and version as the dependency already there, `testImplementation`
only, so nothing reaches the APK.

`:ui` and `:app` will need the identical line the day they get their first test;
they have no `src/test` today, so their existing `kotlin-test` line is inert.

## A10 — `:art` is tested as a pure draw list, not as pixels

Compose's `Path`, `Color` and `DrawScope` cannot be instantiated on a bare JVM,
so a test that touches them needs an emulator or Robolectric, and the port loses
the loop A1 exists to protect. `:art` is split instead: `SvgCanvas` parses path
data and records a neutral `List<SvgDrawOp>` in plain Kotlin, which is what the
185 tests assert on, and `SvgRender` is a thin replay of that list into a
`DrawScope`.

The honest cost is stated once here rather than implied: **the replay layer has
no test and no pixel has been produced.** What the suite proves is that the
right shapes, in the right order, with the right paint, would be drawn. The
mascots and icons are additionally pinned against the web by string: all 30
exercise-icon `d` strings and all 46 word-image `d` strings are byte-identical
to `ExerciseIcon.tsx` and `src/img/*.svg` after whitespace normalisation, and the
SVG corpus test parses all 189 path strings read straight from
`apps/game-ios/Tests/ALArtTests/Resources/svg-corpus.json` — the iOS file, not a
copy, so the two ports cannot drift apart silently.

The twelve dragon parts the port skips (`CloudWings`, `DomeFin`, `Bolt`,
`RainCloud`, `Gem`, `AngularWings`, `FrillBand`, `DustPuffs`, `KnightHelmet`,
`Shield`, `ChestArmor`, `EggShield`) have zero references anywhere in
`apps/game-web/src` outside `dragonParts.tsx`: they are unreachable design-run
candidates, and iOS skipped the same list.

## A11 — `:ui` is tested as data, because a host test cannot compose

Measured, not assumed. On the unit-test JVM these all work and `:ui` uses them
freely in both main code and tests: `Color` (a value class over `ULong`, so
`lerp` and `compositeOver` are pure arithmetic), `Dp`, `TextUnit`, `TextStyle`,
`FontWeight`, `Offset`, `Size`, `Brush.linearGradient`, `Modifier` chains —
`Modifier.size(92.dp)` constructs and is inspectable — `mutableStateOf`,
`mutableStateListOf` and `Animatable`. These throw `Method X in android.Y not
mocked`: any `Path` operation (Compose's `Path` is `AndroidPath`, wrapping
`android.graphics.Path`) and `android.graphics.Color.parseColor`.

So the split is not the one `:art` needed. `:art` had to mirror geometry into a
neutral draw list because `Path` is unusable; `:ui` keeps idiomatic Compose types
and instead factors the *logic* out of the `@Composable` — the press state
machine, the keyframe values, the fluid sizing math, the confetti physics, the
copy — into plain classes and functions a test calls directly. No Robolectric, no
`compose-ui-test`, and `testOptions.unitTests.returnDefaultValues` stays off: it
would turn a wrong-layer test into a silently-passing one.

The honest cost, stated once: **no composition ever runs.** The 227 tests prove
what the animation *specs* say, not that a frame was drawn, and the central
invariant-1 claim — that `awaitFirstDown(pass = PointerEventPass.Initial)`
resumes synchronously inside pointer dispatch, before recomposition — is a
property of Compose's internals that now compiles but has not been observed. That
needs an instrumented test or a device trace, and until then it rests on the API
contract.

## A12 — The pointer-down rule binds gameplay, not navigation

`Modifier.clickable` is absent from `:ui` and a source-scan test fails the build
if it returns: it fires on UP, behind the ripple, after a commit, which is
invariant 1 inverted. Every game surface goes through the one `touchDown`
primitive in `ui/interaction/TouchDown.kt`, the only `pointerInput` in the module.

Two things that look like exceptions and are not. The four `onClick` calls in
`:ui` are `androidx.compose.ui.semantics.onClick` — accessibility actions
registered in the semantics tree so a TalkBack double-tap reaches the same
handler; they are not a touch path. And `GameFrame`'s « ← Menu » deliberately
acts on the *lift*, through `touchDown`'s `onUp` with an empty down handler,
because `GameFrame.tsx` uses `onClick` there too: leaving an exercise by accident
costs a child their place, which is the one case where waiting for the lift is
the kinder behaviour.

Compose has more than one deferred phase, and the invariant is about the phase,
not the API. `Anim.kt`'s six `Animatable`s are read inside `graphicsLayer`
lambdas; `Tile`'s highlight ring is read inside `drawBehind`, because a painted
colour band is not expressible as a layer transform. Both are draw-phase reads
costing zero recompositions — which is what invariant 2 actually asks for.
