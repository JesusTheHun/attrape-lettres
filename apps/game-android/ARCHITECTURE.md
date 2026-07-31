# Attrape-Lettres — native Android architecture

The document an implementer reads first. It is deliberately the same document as
`apps/game-ios/ARCHITECTURE.md` with the platform words changed: the two apps are
independent implementations of one game, and they agree because each was written
against `apps/game-web/` line by line and its tests say so.

Where this file and the iOS one differ, the difference is a *platform* fact and
is called out as one. Decisions with an `A` number are recorded in
`DECISIONS.md`; `D` numbers are the iOS app's, `R` numbers the repo's.

---

## 1. Modules

```
apps/game-android/
  core/       plain Kotlin/JVM. No Android plugin, so `android.*` will not
              even resolve. No Compose, no billing, no network.        (A1)
  art/        Compose drawing: the SVG runtime, mascots, exercise icons.
  ui/         Compose screens: exercises, components, hub, adult screens.
  platform/   the Android adapters — Play Billing, HTTP, SharedPreferences,
              SoundPool/AudioTrack, vibrator. Depended on by :app ONLY.
  app/        MainActivity and nothing else.
  scripts/    stage-vo.sh
```

Dependency direction, strictly one-way, enforced by the Gradle graph:

```
app ──▶ platform ──▶ core
 │                     ▲
 └────▶ ui ──▶ art ────┘
```

`:ui` never depends on `:platform`. It reaches the device through interfaces
declared in `:core` (`KVStore`, `TimeSource`, `PurchaseStore`, `SyncTransport`,
`TelemetryTransport`, `AudioEngine`, `Haptics`, `ReduceMotionSource`,
`AppVersionProvider`), injected at the root. That is what keeps
`./gradlew :core:test` able to run the entire app's logic on a laptop with no
emulator, no store, no network and no signing.

## 2. `:core` layout

Package root `fr.dappit.attrapelettres.core`. One directory per subsystem, the
same seven the iOS app uses, so a file has the same name in three languages.

```
domain/         no dependencies
  ExerciseId.kt         ExerciseId (FROZEN wire values — A2), Difficulty,
                        Mood, Verdict
  ExerciseMeta.kt       ExerciseMeta, AppRoute
  LetterTypes.kt        LetterWord, FirstLetterLevel/Round, LetterMatch*,
                        LetterScript, LetterFace, faceLabel()
  SyllableTypes.kt      SyllableWord, SyllableMode, SyllableTier, tiles,
                        rounds, Spell* types
  SoundTypes.kt         SoundTarget, SoundLevel, BasicSound, FindSound*
  GridTypes.kt          SyllableGridMode, GridSyllable, SyllableGridLevel
  TwinTypes.kt          TwinGraphy, TwinFamily, TwinLevel, TwinTile
  ReadImageTypes.kt     ReadImageLevel, ReadImageRound, ImageKey
  Mascot.kt             Species, MascotConfig, CustomizationOption, GrowthStage
platform/       interfaces only, no implementations
  KVStore.kt            SYNCHRONOUS key/value + InMemoryKVStore            (A3)
  TimeSource.kt         TimeSource, SystemTimeSource, MutableTimeSource
  AppVersion.kt         AppVersionProvider
  Motion.kt             ReduceMotionSource
  AudioPort.kt          AudioEngine, Haptics
support/
  RandomSource.kt       RandomSource, SeededGenerator, orderedUnique()
  RepeatSession.kt      repeatSessionIndices / repeatSession
  TileId.kt             TileIdAllocator
content/        100% authored literals — invariant 4
  Content.kt + Content_LetterWords / SyllableWords / SoundTargets /
  BasicSounds / TwinFamilies / Grid / Banks
levels/         the ladders, pools and round builders
  Levels.kt + Levels_FirstLetter / ReadImage / LetterMatch / Syllable /
  SpellSound / FindSound / SyllableGrid / Twins / SpellSyllable
rewards/
  Rewards.kt            curve, floor, missCooldown, ledgerKey, sessionReward —
                        the ONLY earner (invariant 8)                  [DONE]
persistence/
  ProfileModels.kt      Counter, Rev, StarCounters, ClearCounters,
                        SpeciesProgress, PersistedProfile, ChildProfile,
                        Roster, ProfileView (ProfileView NOT serializable —
                        invariant 9)
  LooseDecoding.kt      all-optional mirrors: v4 + legacy v1/v2/v3
  ProfileStorage.kt     keys, load/save, raw legacy loaders
  Migrations.kt         normalise*, migrateFlat/V1, initialRoster
  DeviceIdentity.kt     deviceId over KVStore, memoised
  ProfileStore.kt       the single mutation choke point
sync/
  Merge.kt              pure: no clock, no storage, no network
  Wire.kt               WireChild/WireRoster — no name field at all
  SyncClient.kt         SyncTransport interface + syncOnce + a stub    (A7)
licensing/
  Entitlement.kt        the fail-open state machine (invariant 11)
  PurchaseStore.kt      the vendor-free seam + StubPurchaseStore
  LicenseStore.kt       persist.ts, separate from the profile
  EntitlementModel.kt   the observable wrapper
telemetry/
  TelemetryEvent.kt     enum, wire names as the enum's `wire` value
  TelemetryProps.kt     closed property set, no String keys
  Telemetry.kt, TelemetryTransport.kt, TelemetryConsent.kt
vo/
  VoKey.kt              voKey — must hash the SAME bytes as the web and iOS
  Utterances.kt         enumerateUtterances, shop lines
```

## 3. Invariant ownership

Every invariant has exactly one owning file and one proving test. A blank cell
would be a bug in this table. Rows 1, 2 and 6 are where Android differs from
iOS mechanically while landing in the same place.

| # | Invariant | Owner | Proof |
|---|---|---|---|
| 1 | Feedback on pointer-down, before commit | `ui/interaction/TouchDown.kt` — `Modifier.pointerInput` + `awaitFirstDown(requireUnconsumed = false)`, which runs in the pointer handler synchronously, before any recomposition. **Never `Modifier.clickable`**: it fires on UP and behind the ripple. Audio must be a pre-warmed `SoundPool` — a `MediaPlayer` decodes on the tap path. **No scrollable parent over an exercise tile grid** (the iOS `delaysContentTouches` rule has a Compose twin: a scrollable ancestor competes for the same down event) | `TouchDownTest`; `AudioLatencyTest` (no decode on the tap path); every `KVStore`/`ProfileStore` method is non-`suspend`, so an async adapter cannot implement the interface |
| 2 | Animation off the render path | `ui/interaction/Anim.kt` — `Animatable` read INSIDE a `Modifier.graphicsLayer { }` lambda (a deferred read: it re-layers, it does not recompose). Confetti is a `Canvas` leaf driven by `withFrameNanos` over a plain array, never `mutableStateOf` | `AnimationSurfaceTest`: no animation target is hoisted state; the particle buffer is not a snapshot object |
| 3 | No fail state | `ui/components/Tile.kt` treats `REJECT` as shake-only; `say()` returns `Boolean` and cannot throw; no round builder has a terminal state | `NoFailStateTest`: a miss changes no lock, no lives, no route |
| 4 | Content authored, not computed | `core/content/*` — 100% literals; `:core` exposes no `(String) -> List<String>` splitter | `ContentIntegrityTest`: `syllables.joinToString("") == word` as a *shape* check, never a generator |
| 5 | All levels unlocked | the hub renders `1..levelCount` unconditionally; `levels/` does not import `persistence/` | `HubCatalogTest`; the missing import is structural |
| 6 | Accessibility floor | `Tile` 92.dp floor, `Modifier.semantics { contentDescription = … }` from `Copy.kt`, and `ReduceMotionSource` — on Android that is `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`, not an accessibility flag, because Android has no `prefers-reduced-motion`. It gates the mascot and the confetti and **deliberately NOT** press/shake, which the web leaves ungated (D29) | `AccessibilityTest`; `AnimTest.reducedMotionGatesOnlyWhatTheWebGates` |
| 7 | Every exercise has a drawn icon | `art/icons/ExerciseIconCatalog.kt` — `when (id)` over the enum with **no `else`** branch, which Kotlin checks for exhaustiveness | Compile error on a new case; `IconCatalogTest` asserts distinct tints over `entries` |
| 8 | Farming never pays | `core/rewards/Rewards.kt` is the only earner; `MISS_COOLDOWN_MS` is the swallow window; the `GameFrame` star strip greys at pointer-down | `RewardsTest` (spam earns the bare curve) [DONE]; `StarStripTest` |
| 9 | Never persist a bare total | `core/persistence/ProfileModels.kt` — `PersistedProfile` has no `balance`/`ledger` property; the folded `ProfileView` is deliberately not `@Serializable` | `MergePropertyTest`: commutative, associative, idempotent; two offline devices lose nothing. Plus `backup_rules.xml` excluding the device id, so a restore cannot put two live phones on one counter key (A5) |
| 10 | Nothing identifying leaves the device | `core/sync/Wire.kt` — `WireChild` has no `name` field, so stripping is a *type* property; `TelemetryProps` has no String key | `WireTest` asserts the serialised bytes contain neither "Léa" nor "nameRev"; `TelemetryClosureTest` asserts the allowlist is closed |
| 11 | Money never fails closed | `core/licensing/Entitlement.kt` — 14-day offline grace, `canPlay(UNKNOWN) == true` | `EntitlementPropertyTest`: no store state and no clock value produces a lockout for a paid family |

## 4. Build order

Dependency-sorted work packages. **P** = parallel-safe (disjoint files).
Everything except W12–W14 is verifiable with `./gradlew test` on the host.

| # | Package | Module | Depends on | P | Host-testable |
|---|---------|--------|-----------|---|---------------|
| W1 | Domain types + platform interfaces | `:core` | — | — | ✅ **done** for `ExerciseId`/`Difficulty` |
| W2 | `RandomSource`, `repeatSession`, `TileIdAllocator` | `:core` | W1 | — | ✅ seeded determinism |
| W3 | Content tables (7 files, literals + French comments) | `:core` | W1 | P | ✅ integrity |
| W4 | Rewards | `:core` | W1 | P | ✅ **done** — 14 tests |
| W5 | Levels: ladders, pools, 9 round builders | `:core` | W1–W4 | — | ✅ 1:1 with `levels.test.ts` |
| W6 | Persistence: models, loose decoding, migrations, storage | `:core` | W1 | P | ✅ v1/v2/v3→v4 |
| W7 | Sync: merge, wire, stub client | `:core` | W6 | — | ✅ property tests |
| W8 | `ProfileStore` | `:core` | W4, W6, W7 | — | ✅ every mutation |
| W9 | Licensing + telemetry | `:core` | W1 | P | ✅ 1:1 with the three TS suites |
| W10 | SVG path runtime + Compose canvas | `:art` | W1 | — | ✅ geometry |
| W11 | Mascot rig + 5 species + motion | `:art` | W10 | — | ⚠️ geometry only |
| W12 | Exercise icons | `:art` | W10 | P | ✅ exhaustiveness |
| W13 | Interaction + design tokens + components | `:ui` | W1 | — | ⚠️ partly |
| W14 | Engine screens (3 families) | `:ui` | W5, W8, W13 | — | ⚠️ models yes, pixels no |
| W15 | Hub, router, adult screens | `:ui` | W9, W12, W13 | — | ⚠️ |
| W16 | Audio: clip bank, SoundPool graph, haptics | `:platform` | W1 | P | ⚠️ latency needs a device |
| W17 | Play Billing, HTTP, SharedPreferences adapters | `:platform` | W6, W9 | P | ⚠️ |

Serialisation points: W5 gates every engine; W8 gates anything that awards;
W10 gates all drawing; W13 gates every screen.

## 5. Cross-module API surface

The contracts modules are implemented *against*, so nobody discovers them by
compiling. Note what is **not** `suspend`: that is invariant 1 expressed as a
type, and it is why an async storage adapter cannot be wired in by accident.

```kotlin
interface KVStore {                       // synchronous — invariant 1
    fun string(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

interface TimeSource { val nowMillis: Long }

interface RandomSource { fun next(upperBound: Int): Int }   // injected, never global

interface PurchaseStore {                 // no vendor name appears in :core
    suspend fun snapshot(): StoreSnapshot
    suspend fun purchase(): PurchaseOutcome
    suspend fun restore(): StoreSnapshot
}

interface SyncTransport { suspend fun exchange(payload: WireRoster): WireRoster }
interface TelemetryTransport { suspend fun send(body: ByteArray) }
interface AudioEngine { suspend fun say(key: VoKey): Boolean; fun pop(); fun nudge() }
interface Haptics { fun light(); fun soft() }
interface ReduceMotionSource { val isReduced: Boolean }
interface AppVersionProvider { val marketing: String }
```
