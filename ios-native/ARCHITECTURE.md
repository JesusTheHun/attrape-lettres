# Attrape-Lettres — native iOS architecture

The document an implementer reads first. Synthesised from the seven subsystem
specs in `spec/`, which remain the detailed reference; where this file and a
spec disagree, **this file wins** — the contradictions have been adjudicated
here and the losing side is named so nobody re-litigates it.

Decisions with a `D` number are recorded in `DECISIONS.md`.

---

## 1. Targets

```
ios-native/
  Sources/
    ALCore        pure Swift. No SwiftUI, no UIKit, no StoreKit, no network.
    ALArt         SwiftUI drawing: the SVG runtime, mascots, exercise icons.
    ALUI          SwiftUI screens: exercises, components, hub, adult screens.
    ALPlatform    the iOS adapters — StoreKit, URLSession, UserDefaults,
                  AVFoundation, CoreHaptics. Imported ONLY by App/.        (D4)
  App/            @main and nothing else.
  Tests/          ALCoreTests, ALArtTests, ALUITests, ALPlatformTests
```

Dependency direction, strictly one-way:

```
App ──▶ ALPlatform ──▶ ALCore
 │                        ▲
 └────▶ ALUI ──▶ ALArt ───┘
```

`ALUI` never imports `ALPlatform`. It reaches the platform through protocols
declared in `ALCore` (`KVStore`, `TimeSource`, `PurchaseStore`, `SyncTransport`,
`TelemetryTransport`, `AudioEngine`, `Haptics`, `AppVersionProvider`), injected
at the root. That is what keeps `swift test` able to run the entire app's logic
on a Mac with no simulator, no store, no network and no signing.

---

## 2. Module graph

### ALCore — pure logic

```
Domain/                         no dependencies
  ExerciseID.swift              ExerciseId (frozen raw values — D11), Difficulty,
                                Mood, Verdict
  ExerciseMeta.swift            ExerciseMeta, AppRoute
  LetterTypes.swift             LetterWord, FirstLetterLevel/Round, LetterMatch*,
                                LetterScript, LetterFace, faceLabel(_:)
  SyllableTypes.swift           SyllableWord, SyllableMode, SyllableTier, tiles,
                                rounds, Spell* types
  SoundTypes.swift              SoundTarget, SoundLevel, BasicSound, FindSound*
  GridTypes.swift               SyllableGridMode, GridSyllable, SyllableGridLevel
  TwinTypes.swift               TwinGraphy, TwinFamily, TwinLevel, TwinTile
  ReadImageTypes.swift          ReadImageLevel, ReadImageRound, ImageKey
  Mascot.swift                  Species, MascotConfig, CustomizationOption,
                                GrowthStage, the mascot catalog types          (D8)
Platform/                       protocols only, no implementations
  KVStore.swift                 synchronous KV — D7. + InMemoryKVStore
  TimeSource.swift              TimeSource, SystemTimeSource, MutableTimeSource (D6)
  AppVersion.swift              AppVersionProvider
  Motion.swift                  ReduceMotionSource                              (D14)
  AudioPort.swift               AudioEngine, Haptics protocols
Support/
  RandomSource.swift            RandomSource, SeededGenerator, orderedUnique(_:) (D9)
  RepeatSession.swift           repeatSessionIndices / repeatSession
  TileID.swift                  TileIDAllocator
Content/                        100% authored literals — invariant 4            (D10)
  Content.swift + Content+LetterWords / SyllableWords / SoundTargets /
  BasicSounds / TwinFamilies / Grid / Banks
Levels/                         the ladders, pools and round builders
  Levels.swift + Levels+FirstLetter / ReadImage / LetterMatch / Syllable /
  SpellSound / FindSound / SyllableGrid / Twins / SpellSyllable
Rewards/
  Rewards.swift                 curve, floor, missCooldown, ledgerKey,
                                sessionReward — the ONLY earner (invariant 8)
Persistence/
  ProfileModels.swift           Counter, Rev, StarCounters, ClearCounters,
                                SpeciesProgress, PersistedProfile, ChildProfile,
                                Roster, ProfileView (not Codable — invariant 9)
  LooseDecoding.swift           all-optional mirrors: v4 + legacy v1/v2/v3
  ProfileStorage.swift          keys, load/save, raw legacy loaders
  Migrations.swift              normalise*, migrateFlat/V1, initialRoster
  DeviceIdentity.swift          deviceId over KVStore, memoised
  ProfileStore.swift            @Observable — the single mutation choke point
Sync/
  Merge.swift                   pure: no clock, no storage, no network
  Wire.swift                    WireChild/WireRoster — no name field at all
  SyncClient.swift              SyncTransport protocol + syncOnce
Licensing/
  Entitlement.swift             the fail-open state machine (invariant 11)
  PurchaseStore.swift           the vendor-free seam + StubPurchaseStore
  LicenseStore.swift            persist.ts, separate from the profile          (D18)
  EntitlementModel.swift        @Observable
Telemetry/
  TelemetryEvent.swift          enum, raw values = wire names
  TelemetryProps.swift          closed property set, no String keys            (D12)
  Telemetry.swift, TelemetryTransport.swift, TelemetryConsent.swift
Updates/
  SemVer.swift                  versionAtLeast
  RemoteContent.swift           the minNative guard. A specified, UNIMPLEMENTED
                                seam — no code download exists natively        (D13)
VO/
  VOKey.swift                   voKey — UTF-16 exact                           (D17)
  Utterances.swift              enumerateUtterances, shop lines
```

### ALArt — drawing

```
SVGPath.swift          the d-string runtime + SVGShape          [DONE, D2]
Canvas/
  SVGCanvas.swift      the GraphicsContext substrate, CTM stack  (D15)
  Paint.swift          gradients incl. objectBoundingBox stretch
Mascot/
  Rig.swift            species x stage x wardrobe -> draw list
  Parts.swift, Dragon.swift, Rabbit.swift, Fox.swift, Unicorn.swift, Cat.swift
  Motion.swift         idle / cheer / pop, transform-only
Icons/
  ExerciseIconCatalog.swift   switch over ExerciseId, NO default  (invariant 7)
Images/
  WordImages.swift     jupe / pyjama / macaron / igloo, same parser
```

### ALUI — screens

```
Interaction/
  TouchDown.swift      the ONE touch-down primitive               (D5)
  Anim.swift           Core Animation press / shake / pop / pulse
  LayerHost.swift      the hosted CALayer
Design/
  Fluid.swift          fluid(min:vw:max:) — the CSS clamp() analogue (D16)
  Palette.swift, Typography.swift, Copy.swift  (French copy, byte-exact)
Components/
  Tile.swift, GameFrame.swift, ConfettiOverlay.swift, ChildCard.swift, …
Engines/
  AssembleView.swift, SyllableGridView.swift, FirstLetterView.swift, …
Screens/
  HubView.swift, RootView.swift, Onboarding.swift, Paywall.swift,
  ParentalGate.swift, WhoIsPlaying.swift, Dashboard.swift
Harness/
  RenderHarness.swift  launch-argument-driven render of any mascot or screen,
                       for the D3 pixel diff                        (D19)
```

---

## 3. Invariant ownership

Every invariant has exactly one owning file and one proving test. A blank cell
would be a bug in this table.

| # | Invariant | Owner | Proof |
|---|---|---|---|
| 1 | Feedback on pointerdown, before commit | `ALUI/Interaction/TouchDown.swift` + pre-warmed `AudioEngine`; **no `ScrollView` may enclose a `LayerHost`** — `delaysContentTouches` holds the touch for pan recognition, so `KeyboardScroll` is gated per branch (D42) | `TouchDownTests` (recogniser fires on `.began`); `AudioLatencyTests` (no decode on the tap path); `KeyboardScrollTests` (`enabled: false` is the pixel-exact identity); every `KVStore`/`ProfileStore` method is synchronous — an async adapter cannot conform |
| 2 | Animation off the render path | `ALUI/Interaction/Anim.swift` (Core Animation), `ConfettiOverlay` (TimelineView+Canvas leaf), `ALArt/Mascot/Motion.swift` (transform-only) | `AnimationSurfaceTests`: no `withAnimation` on shared state; particle buffer is a reference type, not `@State` |
| 3 | No fail state | `Tile` treats `.reject` as shake-only; `say()` returns `Bool` and cannot throw or hang; no round builder has a terminal state | `NoFailStateTests`: a miss changes no lock, no lives, no route |
| 4 | Content authored, not computed | `ALCore/Content/*` — 100% literals; ALCore exposes no `(String) -> [String]` splitter | `ContentIntegrityTests`: `syllables.joined() == word` as a *shape* check, never a generator |
| 5 | All levels unlocked | `HubView` renders `1...levelCount` unconditionally; `Levels` does not import the profile module | `HubCatalogTests`; the missing import is structural |
| 6 | Accessibility floor | `Tile` 92pt floor, `.accessibilityLabel` from `Copy.swift`, `ReduceMotionSource` gating `Anim.pop`, `Anim.pulse`, `MascotMotion` and `ConfettiSystem.fire()` — and **deliberately NOT** `Anim.press`/`Anim.shake`, which the web leaves ungated (D29) | `AccessibilityTests`; `AnimTests.reducedMotionGatesOnlyWhatTheWebGates`; `faceLabel` unit-tested in ALCore |
| 7 | Every exercise has a drawn icon | `ALArt/Icons/ExerciseIconCatalog.swift` — `switch ExerciseId` with **no `default:`** | Compile error on a new case; `IconCatalogTests` asserts distinct tints over `allCases` |
| 8 | Farming never pays | `ALCore/Rewards/Rewards.swift` is the only earner; `Rewards.missCooldownMs` is the swallow window; `GameFrame` greys the live round's star at pointerdown | `RewardsTests` (spam earns the bare curve); `StarStripTests` |
| 9 | Never persist a bare total | `ALCore/Persistence/ProfileModels.swift` — `PersistedProfile` has no `balance`/`ledger` property; `ProfileView` is deliberately not Codable | `MergePropertyTests`: commutative, associative, idempotent; two offline devices lose nothing |
| 10 | Nothing identifying leaves the device | `ALCore/Sync/Wire.swift` — `WireChild` has no `name` field, so stripping is a *type* property; `TelemetryProps` has no String key | `WireTests` asserts the serialised bytes contain neither "Léa" nor "nameRev"; `TelemetryClosureTests` asserts the allowlist is closed |
| 11 | Money never fails closed | `ALCore/Licensing/Entitlement.swift` — 14-day offline grace, `canPlay(.unknown) == true` | `EntitlementPropertyTests`: no store state and no clock value produces a lockout for a paid family |

---

## 4. Build order

Dependency-sorted work packages. **P** = parallel-safe (disjoint files).
Everything except W12–W14 is verifiable with `swift test` on the host.

| # | Package | Target | Depends on | P | Host-testable |
|---|---------|--------|-----------|---|---------------|
| W0 | SVG path runtime | ALArt | — | — | ✅ **done** |
| W1 | Domain types + Platform protocols | ALCore | — | — | ✅ compiles |
| W2 | `RandomSource`, `repeatSession`, `TileIDAllocator` | ALCore | W1 | — | ✅ seeded determinism |
| W3 | Content tables (7 files, literals + French comments) | ALCore | W1 | P | ✅ integrity |
| W4 | Rewards | ALCore | W1 | P | ✅ curve, spam-vs-careful |
| W5 | Levels: ladders, pools, 9 round builders | ALCore | W1–W4 | — | ✅ 1:1 with `levels.test.ts` |
| W6 | Persistence: models, loose decoding, migrations, storage | ALCore | W1 | P | ✅ v1/v2/v3→v4 |
| W7 | Sync: merge, wire, client | ALCore | W6 | — | ✅ property tests |
| W8 | `ProfileStore` | ALCore | W6, W7, W4 | — | ✅ every mutation |
| W9 | Licensing + Telemetry + Updates | ALCore | W1 | P | ✅ 1:1 with the three TS suites |
| W10 | `SVGCanvas`, paint, CTM | ALArt | W0 | — | ✅ geometry |
| W11 | Mascot rig + 5 species + motion | ALArt | W10, W1 | — | ⚠️ geometry only; pixels need D3 |
| W12 | Exercise icons | ALArt | W10, W1 | P | ✅ exhaustiveness |
| W13 | Interaction + design tokens + components | ALUI | W1 | — | ⚠️ partly |
| W14 | Engine views (3 families) | ALUI | W13, W5, W8 | — | ⚠️ models yes, views no |
| W15 | Hub, router, adult screens | ALUI | W13, W12, W9 | — | ⚠️ |
| W16 | Audio: clip bank, engine, haptics | ALPlatform | W1 | P | ⚠️ latency needs a device |
| W17 | StoreKit, URLSession, UserDefaults adapters | ALPlatform | W9, W6 | P | ⚠️ |
| W18 | Render harness + pixel diff | ALUI + scripts | W11, W15 | — | simulator |

Serialisation points: W5 gates every engine; W8 gates anything that awards;
W10 gates all drawing; W13 gates every screen.

---

## 5. Cross-target API surface

The contracts packages are implemented *against*, so nobody discovers them by
compiling:

```swift
public protocol KVStore {                       // synchronous — invariant 1
    func string(_ key: String) -> String?
    func set(_ value: String, for key: String)
    func remove(_ key: String)
}

public protocol TimeSource { var nowMillis: Int64 { get } }

public protocol RandomSource {                  // injected, never global
    mutating func next(upperBound: Int) -> Int
}

public protocol PurchaseStore {                 // no vendor names in ALCore
    func snapshot() async -> StoreSnapshot
    func purchase() async -> PurchaseOutcome
    func restore() async -> StoreSnapshot
}

public protocol SyncTransport { func exchange(_ payload: WireRoster) async throws -> WireRoster }
public protocol TelemetryTransport { func send(_ body: Data) async throws }
public protocol AudioEngine { @discardableResult func say(_ key: VOKey) async -> Bool
                              func pop(); func nudge() }
public protocol Haptics { func light(); func soft() }
public protocol ReduceMotionSource { var isReduced: Bool { get } }
public protocol AppVersionProvider { var marketing: String { get } }
```

`ProfileStore`, `EntitlementModel` and `Telemetry` are `@Observable` and
`@MainActor`. ALUI holds them in the environment; it never constructs them.

---

## 6. Adjudicated contradictions

Recorded so they are not reopened:

1. **Touch-down primitive.** `engines.md` proposed `TilePressControl` (a
   `UIControl` hosting a `UIHostingController`); `shell.md` proposed a
   `UILongPressGestureRecognizer(minimumPressDuration: 0)`. **shell.md wins**
   (D5) — same touch-down instant, no child view-controller plumbing, no
   accessibility passthrough problem. `TilePressControl` stays the documented
   fallback if scroll-view arbitration ever misbehaves.
2. **Observation framework.** `persistence.md` wrote `ObservableObject`;
   `money.md` and `engines.md` assumed `@Observable`. **`@Observable` wins**
   (D20) — iOS 17 is the floor, and it must be uniform across every store.
3. **Where the platform adapters live.** D1 said "the app layer"; `money.md`
   and `persistence.md` both wanted a real target. **A fourth target,
   `ALPlatform`** (D4) — otherwise the StoreKit and URLSession code sits in the
   `.pbxproj`, outside `swift build`, and CI never compiles it.
4. **`AppView` / `AppRoute`.** `data-core.md` put it in ALCore because it lives
   in `types.ts`; it is pure navigation. **Stays in ALCore** (`Domain/ExerciseMeta.swift`)
   so the router is host-testable.
5. **`buildSession` for FirstLetter.** Currently inside the TSX. **Moves to
   `Levels.buildFirstLetterSession`** — pure, and it is the one session shape a
   host test cannot otherwise reach. Behaviour unchanged, 2 distractors kept.
6. **The transform census in D2 was stale.** Re-measured: 32 transform
   attributes (~19 `rotate`, several with `cx cy` anchors, ~12 `translate`),
   no `matrix`, no `skew`, and **zero `fill-rule="evenodd"`** — every fill is
   nonzero, which is already SwiftUI's default. D2's conclusion is unchanged;
   the correction is in D21.
