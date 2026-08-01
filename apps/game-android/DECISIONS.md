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

## A13 — The object graph hangs off `Application`, not off `MainActivity`

`AttrapeLettresApplication.onCreate` builds `AppGraph` and nothing else builds
one. The activity reads it. Two facts force that, and neither is style:

- **Telemetry's scope must outlive an activity.** `PlatformEnvironment`'s own
  contract asks for a scope that lives as long as the PROCESS, because a
  rotation must not cancel an in-flight send. An activity-scoped
  `CoroutineScope` cannot promise it.
- **The audio engine owns native handles.** Static `AudioTrack`s, a reused
  `MediaPlayer` and a `TextToSpeech` service binding. Rebuilding it on every
  activity recreation would leak them and would re-pay the prewarm, which is
  exactly what invariant 1 forbids paying twice. `android:configChanges` covers
  the common recreations, but "don't keep activities", a density change and a
  font-scale change still recreate the activity, and none of them may cost a
  child their sound.

The corollary is that `SfxPlayer` is constructed in `AppGraph` and passed to
`LiveAudioEngine.live(sfx = …)` rather than left to the engine's default. That
constructor IS the prewarm — it renders the PCM and primes the tracks — so
building it during process start-up is what makes `pop()` on the pointer-down
path three JNI calls with nothing to load. Nothing in the graph is `by lazy`,
for the same reason: lazy moves the cost to whoever touches it first, and for
the audio half that is a finger.

Nothing is released on `onDestroy`. A process-scoped engine has one end, and it
is the process ending.

## A14 — `Dispatchers.Main` is a declared dependency, not an inherited one

The telemetry scope runs on `Dispatchers.Main.immediate`, because `Telemetry`
keeps its queue and its pending-job list in unlocked `ArrayList`s and is
documented main-thread bound; only the socket leaves the main thread, inside
`HttpTelemetryTransport`'s own `withContext(Dispatchers.IO)`.

`Dispatchers.Main` resolves through a `ServiceLoader`, so it is a RUNTIME
dependency that no compiler checks: without `kotlinx-coroutines-android` on the
classpath it throws on first access. AndroidX supplies it transitively today
(`lifecycle-runtime-android` lists it in both its API and runtime variants),
which is precisely the kind of accident a dependency tidy-up removes — and the
first access is in `Application.onCreate`, so the failure mode is "the app does
not start". `:app` therefore names the artifact itself. Same family and version
as the `kotlinx-coroutines-core` entry already in the catalog.

## A15 — System back leaves the app, and that is a recorded gap

`RootView` deliberately has no back stack (`App.tsx` replaces the whole screen;
a nav library would also put a gesture detector above the exercise tiles, which
is invariant 1's territory), and it keeps the route private. `:app` therefore
installs no `BackHandler`: from `:app` the only choice available would be
"exit or trap", and trapping a user is worse than leaving.

The consequence is real and is not pretended away: a system back gesture leaves
the app from anywhere, including mid-round, where every screen's own affordance
(« ← Menu ») would have returned to the hub. Fixing it means hoisting the route
out of `RootView` so the activity can map back onto "go to hub, and exit only
from the hub" — a `:ui` change, not one to smuggle into the composition root.

## A16 — Edge-to-edge is switched on, because the shell already assumes it

`MainActivity.onCreate` calls `enableEdgeToEdge()` and the activity declares
`windowSoftInputMode="adjustResize"`. Not decoration: `RootView`'s `ShellFrame`
computes its gutter from `WindowInsets.safeDrawing`, and those insets are only
reported once the window stops fitting system windows. `targetSdk = 36` forces
edge-to-edge from API 35 anyway, so without this the shell would behave
differently on the two halves of the supported range — including for the IME
inset that keeps « Ton prénom » and the parental gate's answer box above the
keyboard.

## A17 — Zero permissions, and a test that keeps it that way

The manifest declares no `<uses-permission>` at all, INTERNET included. Sync is
not wired (A7) and telemetry is inert without an endpoint, which no build
declares — so the app genuinely opens no socket, and the Play Data safety form
and the Kids Category review have nothing to explain.

The hazard is the day that changes, in both directions, and `ManifestContractTest`
pins both. An endpoint declared without INTERNET does not fail loudly:
`HttpURLConnection` raises `SecurityException`, `Telemetry.post` swallows it by
design ("telemetry must never surface to a child"), and the result is an app
that looks instrumented and reports nothing forever. A permission declared with
no endpoint is the mirror image — invisible in testing, expensive exactly once,
in review.

## A18 — The growth price is clamped, and Android is the only app that clamps it

`GrowthCard` is the one place a price reaching `spend()` is **computed** rather
than read from the authored `CATALOG`: `growthPrice(stage) = 30 * (stage + 1)`.
The stage it multiplies comes off disk through `LooseDecoding`, as
`obj["stage"].looseDouble()?.toInt()`, with no range check — the same latitude the
web gets from `JSON.parse(localStorage…)`.

A stored `stage: -4` therefore prices a growth at −90. `affordable` is trivially
true, `atMax` is false, and `ProfileStore.spend(-90)` does not refuse: its only
guard is `balanceOf(...) < cost`, and `0 < -90` is false. It then bumps the
`spent` counter by −90, which *lowers* spending, which *raises* the folded
balance. That is a mint, outside `sessionReward`, from a value the app never
writes but will happily read — invariant 8 defeated without a single line of
arithmetic in `:ui`.

Reachability is low: it needs a hand-edited prefs file, so root or `adb` on a
debuggable build, and `sync = null` means no remote party can write one. It is
also **inherited, not introduced** — `apps/game-web/src/shop/GrowthCard.tsx:17`
and `apps/game-ios/Sources/ALUI/Shop/GrowthCard.swift:18` compute the identical
unclamped price, and `useProfile.tsx:425` has the identical unguarded `spend`.

`growthCardSurface` clamps with `stage.coerceIn(0, GROWTH_STAGES - 1)`, and
`performGrow` re-derives through the same clamp rather than incrementing the raw
value. The clamp is at the top rather than inside the purchase because a surface
that cannot legitimately be bought must not be *rendered* either — an unclamped
one would draw « Grandir · ⭐ -90 » and a pip row of −3. `:core` already defends
the same input the same way in `stageScale`'s `coerceIn(0, 9)`.

**This is a deliberate divergence from the source of truth, and the real fix is
elsewhere.** The clamp closes the one caller Android has; it does not close
`spend()`. Guarding `spend()` against a non-positive cost would close the class
for all three apps at once, and that is a decision to take across web, iOS and
Android together rather than unilaterally in the newest port.

## A19 — A graphics layer crops what it records, so a tile buys headroom

Found on an emulator, not on the host. The tile's green highlight ring
(`0 0 0 6px #66BB6A`) rendered as four green slivers in the corners and nothing
else, and the hard lip (`0 8px 0 rgba(0,0,0,.12)`) under both the tile and the
« Écouter » pill never rendered at all.

The cause is a piece of Compose that reads the opposite way round to CSS.
`Modifier.graphicsLayer` — and therefore `Modifier.alpha`, `Modifier.shadow`,
and every animated scale — records its subtree into a layer the size of its
node. Content painted outside those bounds is not clipped so much as never
recorded. **`clip` does not govern this**: `clip` chooses whether the layer's
OUTLINE crops the content, while the recording bounds always do. So
`clip = false` next to a `drawBehind` that paints 6 dp outward reads as correct
and is not, which is exactly how it survived review.

CSS is the other way: a `box-shadow` takes part in no layout and nothing crops
it. Every shadow in this app is a ported `box-shadow`, so the mismatch was
systemic rather than local.

Measured, because the geometry and the crop produce similar-looking wrongness.
With the tile at `[232,991][416,1175]`, green pixels existed at `dy=2` and
`dy=182` and NOWHERE on the centre line — the signature of a crop, not of a bad
radius. A magenta disc of radius 30 dp drawn at the tile's own origin survived
only as a corner sliver. Moving the `drawBehind` out from under
`Modifier.shadow` changed nothing, which ruled the shadow out and left the
press-animation and opacity layers.

Two things came out of it.

`design/Opacity.kt` — `Modifier.opacity`, the port's only fade. Disassembled
from ui-android 1.11.4, `Modifier.alpha` installs `graphicsLayer(clip = true)`
for any alpha below 1, so it crops on the outline as well. CSS `opacity` never
crops. `OpacitySourceScanTest` bans `Modifier.alpha` from `:ui` outright.

`design/Overdraw.kt` — `Modifier.overdraw(all)`, which measures its subtree
larger on every side, reports the original size, and places the subtree back at
the negative offset. Layers to its right are bigger and can paint into the
margin; everything to its left, including the pointer-input node that owns
invariant 1, sees the tile's true size and an unchanged hit rect. Tile bounds
are byte-identical before and after: `[227,1084][519,1376]`, 292 px = 111.2 dp,
which is `clamp(92px, 27vw, 150px)` at a 411 dp viewport.

The general rule for this port: **anything that paints outside its border box
needs `overdraw` before the first layer, or it is not on the screen.** No host
test can see this one — `:ui` is asserted as data and rasterises nothing.
