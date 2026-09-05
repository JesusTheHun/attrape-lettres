# Money — port specification

Scope: `src/licensing/**`, `src/telemetry.ts`, `src/updates.ts`, their tests, and the
native surface described by `capacitor.config.ts` + `package.json`.

Owner of **invariant 11** (money never fails closed) and **invariant 10** (nothing
identifying leaves the device).

Behaviour is frozen. Everything below is a translation, not a redesign. Where the
translation forced a choice React did not have to make, it is marked
**[DEVIATION]** and repeated in § 7 Risks. Where the PWA does something that looks
wrong, it is ported anyway and recorded in § 7.

French user-facing copy is byte-identical to the TypeScript. There are exactly
three strings in this scope: two in `trialNotice`, and they are quoted verbatim
below.

---

## 1. Inventory

### In scope

| File | Lines | What it actually does |
|---|---:|---|
| `src/licensing/entitlement.ts` | 120 | **Pure.** Constants (`TRIAL_DAYS` 7, `DAY_MS`, `TRIAL_MS`, `UNLOCK_PRICE_EUR` 11.99, `EARLY_PRICE_EUR` 2.99, `PRODUCT_TRIAL`, `PRODUCT_UNLOCK`, `PRODUCT_UNLOCK_EARLY`, `OFFLINE_GRACE_MS` = 14 days). `LicenseState` (6 fields). `BLANK_LICENSE`. `Entitlement` discriminated union (4 cases). `effectiveNow` (monotonic clock). `entitlementOf` (the state machine). `canPlay`. `withClock`. `trialNotice` (the only French in the module). No storage, no store, no clock — `now` is always a parameter. |
| `src/licensing/entitlement.test.ts` | 137 | 13 tests, all with a fixed `T0 = 1_700_000_000_000`. Covers the trial countdown, the expiry boundary, clock rewind, the French copy, the paid-beats-trial rule, the offline grace window, "never expires a paid family that was never verified", and 6 `applySnapshot` cases. **This file is the executable half of invariant 11 and must survive the port test-for-test.** |
| `src/licensing/store.ts` | 150 | The IAP seam. `StoreSnapshot { paid, trialStartedAt, reachable }`, the `UNREACHABLE` constant, the `PurchaseStore` interface (6 members), a `webStore` that reports `paid: true` unconditionally, a `nativeStore` **stub** that reports `UNREACHABLE` for everything, a `Capacitor.isNativePlatform()` selector wrapped in try/catch, and a test override setter. No payment vendor anywhere — the only names in the file are Apple and Google. |
| `src/licensing/useEntitlement.tsx` | 173 | The React provider. Exports the **pure** `applySnapshot(state, snapshot, now)` (which the entitlement tests import), `EntitlementAPI`, `EntitlementProvider`, `useEntitlement`. Owns: initial load from `persist`, a `checkedAt` timestamp that the entitlement is computed against, refresh-on-mount, refresh-on-resume via `@capacitor/app` `appStateChange`, `beginTrial` (local stamp first, authoritative date second), `purchase`, `restore`, and the price label fetch. |
| `src/licensing/persist.ts` | 45 | Two keys in `kv`: `attrape-lettres:license:v1` (JSON blob) and `attrape-lettres:onboarded:v1` (`"1"`). Lenient field-by-field decode; any throw ⇒ `BLANK_LICENSE`. Write failures swallowed. |
| `src/telemetry.ts` | 198 | First-party analytics + JS error reporting. Closed `EVENTS` tuple (11 names). `TelemetryProps` interface (8 optional fields: 1 enum + 7 numbers). `NUMERIC_KEYS` array driving `sanitize`. Consent in `attrape-lettres:consent:v1` (`"1"`/`"0"`/unset). A module-level `queue`, flush at 20 or on demand, `fetch` with `keepalive: true` and `credentials: "omit"`. `reportError` — **not** consent-gated, truncates `where`/`message`/`stack` to 60/300/2000. `installErrorReporting` wires `window.onerror`, `unhandledrejection`, and flush-on-hidden. |
| `src/telemetry.test.ts` | 156 | 12 tests over a `fetch` mock. Notably: consent unanswered ≠ refusal; withdrawal drains the queue; **"silently drops any property not on the allowlist"** with a literal `childName: "Léa"` and `deviceId: "dad-phone"` cast through `as unknown as TelemetryProps`; non-finite numbers dropped; batching; `credentials: "omit"`; error reports sent without consent; truncation; inert with no endpoint. |
| `src/updates.ts` | 125 | Self-hosted OTA. `versionAtLeast` (pure, numeric semver-ish compare). `compatible` (the `minNative` guard). `checkOnce` — GET the manifest, compare to the running bundle version, check `minNative` against `CapApp.getInfo().version`, `CapacitorUpdater.download`, then `CapacitorUpdater.next` so the swap lands on the **next launch**. `installLiveUpdates` — native-only, `notifyAppReady()` then check on boot and on each resume, every failure swallowed into `reportError`. `bundleVersion = __APP_VERSION__`. |
| `src/updates.test.ts` | 35 | 5 tests, all `versionAtLeast`. Explicitly the only part testable without a device. |
| `capacitor.config.ts` | 54 | appId `fr.dappit.attrapelettres`, appName `Attrape-Lettres`, `webDir: dist`, `loggingBehavior: "none"`, `backgroundColor: "#FFE7C9"`. iOS: `contentInset: "never"`, `scrollEnabled: false`. Android: debugging off. Plugins: SplashScreen (`launchAutoHide: false`), CapacitorUpdater (`autoUpdate: false`, `appReadyTimeout: 10_000`, `responseTimeout: 20`, `resetWhenUpdate: true`). |
| `package.json` | 57 | `version: "0.1.0"` (the source of `__APP_VERSION__`). Native deps: `@capacitor/app`, `core`, `preferences`, `splash-screen`, `status-bar`, `@capgo/capacitor-updater`. |

### Read for context, owned by other agents

`src/kv.ts` (91) — the key/value primitive I persist through. `src/device.ts` (56) —
`deviceId()`, which telemetry must **never** touch. `src/main.tsx` (68) — boot order.
`src/components/Onboarding.tsx` (117), `Paywall.tsx` (189), `ParentalGate.tsx` (116) —
the only consumers of my API; their contract is pinned in § 2.5.

### Call-site census (grepped, not inferred)

Only six symbols of mine are consumed outside my scope:

```
main.tsx:            EntitlementProvider, installErrorReporting, installLiveUpdates
Onboarding.tsx:      useEntitlement().{beginTrial, storeAvailable, priceLabel},
                     TRIAL_DAYS, UNLOCK_PRICE_EUR, setConsent, track("trial_started")
Paywall.tsx:         useEntitlement().{purchase, restore, priceLabel, storeAvailable},
                     UNLOCK_PRICE_EUR, hasConsent, setConsent,
                     track("purchase_completed"|"purchase_failed"|"purchase_restored"|"paywall_shown")
```

Three consequences the implementer must know:

1. **`canPlay` has no call site.** It is exported, tested, and never used — `App.tsx`
   does not gate rounds on it and `Paywall` is not mounted from anywhere. The
   entitlement machinery is fully built and not yet wired to gameplay. Port it as-is;
   do not "finish" the wiring. (§ 7 R9)
2. **`trialNotice` has no call site either.** Same treatment; its two French strings
   are still copied verbatim.
3. **6 of the 11 telemetry events are never emitted** (`exercise_started`,
   `session_completed`, `shop_opened`, `item_bought`, `mascot_grown`,
   `trial_expired`). The list is aspirational. Port all 11. (§ 7 R10)

---

## 2. Swift module plan

Everything in this scope is pure logic or a protocol. Nothing needs SwiftUI except the
observable model, which is `@Observable` (Observation, not SwiftUI) and therefore also
lives in **ALCore**.

```
Sources/ALCore/
  Platform/
    TimeSource.swift            protocol TimeSource + SystemTimeSource + MutableTimeSource
    AppVersion.swift            protocol AppVersionProvider (replaces __APP_VERSION__)
  Licensing/
    Entitlement.swift           LicenseState, Entitlement, the pure state machine
    PurchaseStore.swift         StoreSnapshot, PurchaseStore protocol, .unreachable,
                                PreviewPurchaseStore, StubPurchaseStore
    LicenseStore.swift          persist.ts — load/save over KeyValueStore
    EntitlementModel.swift      applySnapshot (pure) + @Observable EntitlementModel
  Telemetry/
    TelemetryEvent.swift        enum TelemetryEvent (11 cases, raw values = wire names)
    TelemetryProps.swift        struct TelemetryProps + its closed wire encoding
    TelemetryConsent.swift      consent read/write over KeyValueStore
    Telemetry.swift             @MainActor final class Telemetry (queue, flush, track,
                                reportError)
    TelemetryTransport.swift    protocol TelemetryTransport + RecordingTransport (tests)
  Updates/
    SemVer.swift                versionAtLeast — pure
    RemoteContent.swift         manifest model + the minNative guard + the signed-payload
                                seam. NO code download. See § 4.4.

Sources/ALPlatform/             [NEW TARGET — see § 8 decision A]
  StoreKitPurchaseStore.swift   the real StoreKit 2 adapter
  URLSessionTelemetryTransport.swift
  BundleAppVersionProvider.swift

Tests/ALCoreTests/
  EntitlementTests.swift        1:1 port of entitlement.test.ts (13 cases)
  EntitlementPropertyTests.swift  the fail-open properties (§ 6.2)
  LicenseStoreTests.swift       persistence round-trip + corruption
  EntitlementModelTests.swift   applySnapshot, refresh serialisation, beginTrial ordering
  TelemetryTests.swift          1:1 port of telemetry.test.ts (12 cases)
  TelemetryClosureTests.swift   the allowlist-is-closed tests (§ 6.3)
  SemVerTests.swift             1:1 port of updates.test.ts (5 cases)
  RemoteContentTests.swift      minNative guard, signature, schema rejection
```

**Dependency direction.** `ALCore/Licensing` depends on `ALCore/Platform` (TimeSource,
KeyValueStore) and on nothing else. `ALCore/Telemetry` depends on
`ALCore/Platform` (KeyValueStore, AppVersionProvider) and on `ExerciseId` from the
domain types — and on **nothing under `Device`**. `ALCore/Updates` depends on
`AppVersionProvider` only. `ALPlatform` depends on `ALCore` and imports StoreKit /
Foundation. `ALUI` (screens) depends on `ALCore`, never on `ALPlatform`.

**`ALCore` must not import StoreKit.** The whole point of the seam is that
`swift test` runs the entitlement machine on a Mac with no store, no network and no
signing.

### 2.1 `TimeSource` — the injectable clock

`entitlementOf` already takes `now` as a parameter, so the pure layer is fine. The
untestable part is `EntitlementModel`, which calls `Date.now()` in five places
(`refresh`, `beginTrial` ×2, and the `checkedAt` initialiser). Those become
`time.nowMillis`.

```swift
/// Epoch milliseconds. NOT named `Clock` — that collides with the stdlib protocol.
public protocol TimeSource: Sendable {
    var nowMillis: Int64 { get }
}
public struct SystemTimeSource: TimeSource {
    public init() {}
    public var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
/// Tests only. Settable, so a spec can walk a fortnight in four lines.
public final class MutableTimeSource: TimeSource, @unchecked Sendable {
    public var nowMillis: Int64
    public init(_ ms: Int64) { self.nowMillis = ms }
    public func advance(days: Double) { nowMillis += Int64(days * 86_400_000) }
}
```

**A port that reads `Date()` inside `EntitlementModel` is a bug.** There must be no
`Date()` literal anywhere under `Sources/ALCore/Licensing/`. Enforce it with the
source-scan test in § 6.5.

### 2.2 Time representation

Epoch **milliseconds as `Int64`**, everywhere in ALCore. Not `Date`, not
`TimeInterval`.

Reasons: the arithmetic (`TRIAL_MS`, `DAY_MS`, `Math.ceil(diff / DAY_MS)`) is then
byte-identical to the TypeScript; the persisted JSON stays interchangeable with the
PWA's blob; and `Int64` cannot accumulate the float drift a `TimeInterval` would.

`Date` appears only at the two platform boundaries: `SystemTimeSource`, and the
StoreKit adapter converting `Transaction.purchaseDate`:

```swift
Int64(transaction.purchaseDate.timeIntervalSince1970 * 1000)
```

### 2.3 What replaces the React provider

`EntitlementProvider` + `useEntitlement()` becomes one `@MainActor @Observable final
class EntitlementModel`, injected with `.environment(model)` and read with
`@Environment(EntitlementModel.self)`. iOS 17 / macOS 14 (the package's floor) has
Observation, so no `ObservableObject`/`@Published` boilerplate.

`@MainActor` and not an `actor`: every consumer is a view, `track()` is called from
tap handlers, and invariant 1 forbids putting anything on the feedback path behind a
hop. Single-actor is also what the React code was.

### 2.4 What replaces Capacitor

| Capacitor | Swift |
|---|---|
| `Capacitor.isNativePlatform()` | gone — there is no web build. `PurchaseStore.available` stays (§ 3.3). |
| `CapApp.addListener("appStateChange", isActive)` | `@Environment(\.scenePhase)`, `.onChange(of: scenePhase)`, refresh on `.active` **only** (not `.inactive`). |
| `CapApp.getInfo().version` | `AppVersionProvider` → `CFBundleShortVersionString`. |
| `@capacitor/preferences` + `kv.ts` cache | `KeyValueStore` protocol backed by `UserDefaults` — **synchronous**, so the whole `hydrateKv()`-before-mount dance disappears. |
| `@capgo/capacitor-updater` | **removed.** See § 4.4. |
| `fetch(..., keepalive: true)` | `URLSession` + `UIApplication.beginBackgroundTask` around the flush. |

### 2.5 The API contract the screens agent codes against

Frozen. If this changes, `Onboarding.swift` and `Paywall.swift` change with it.

```swift
@MainActor @Observable public final class EntitlementModel {
    public private(set) var entitlement: Entitlement
    public private(set) var onboarded: Bool
    public private(set) var priceLabel: String?      // nil until the store answers
    public let storeAvailable: Bool
    public func beginTrial()                         // fire-and-forget, returns immediately
    public func purchase() async -> Bool
    public func restore() async -> Bool
    public func refresh() async                      // called on boot and on scenePhase == .active
}
```

Plus the free functions/constants the screens import directly:
`TRIAL_DAYS`, `UNLOCK_PRICE_EUR`, `canPlay(_:)`, `trialNotice(_:)`,
`Telemetry.shared.track(_:_:)`, `TelemetryConsent.has` / `.set(_:)`.

---

## 3. Type mapping

### 3.1 `LicenseState`

```ts
interface LicenseState {
  paid: boolean;
  verifiedAt: number | null;
  trialStartedAt: number | null;
  clockHighWater: number;
}
```

```swift
public struct LicenseState: Equatable, Codable, Sendable {
    public var paid: Bool
    public var verifiedAt: Int64?          // epoch ms; nil == JSON null
    public var trialStartedAt: Int64?
    public var clockHighWater: Int64

    public static let blank = LicenseState(
        paid: false, verifiedAt: nil, trialStartedAt: nil, clockHighWater: 0)
}
```

`number | null` → `Int64?`. Two Codable requirements, both load-bearing:

- **Encoding must emit explicit `null`, not omit the key.** Synthesised Codable uses
  `encodeIfPresent` for optionals and drops the key. `JSON.stringify` emits
  `"verifiedAt":null`. Write `encode(to:)` by hand with
  `try c.encode(verifiedAt, forKey: .verifiedAt)` (the `Encodable?` overload writes
  null). Cheap, and it keeps the blob identical to the PWA's.
- **Decoding must be total and lenient**, exactly mirroring `loadLicense`:

  ```swift
  paid           = (try? c.decodeIfPresent(Bool.self,  forKey: .paid))  ?? nil ?? false
  verifiedAt     = (try? c.decodeIfPresent(Int64.self, forKey: .verifiedAt)) ?? nil
  trialStartedAt = (try? c.decodeIfPresent(Int64.self, forKey: .trialStartedAt)) ?? nil
  clockHighWater = (try? c.decodeIfPresent(Int64.self, forKey: .clockHighWater)) ?? nil ?? 0
  ```

  A wrong-typed field degrades to its default rather than failing the whole decode,
  because `LicenseState.blank` means *full trial*, i.e. the child plays. Fail open all
  the way down.

### 3.2 `Entitlement` — the discriminated union

```ts
type Entitlement =
  | { status: "unknown" }
  | { status: "trial"; daysLeft: number; endsAt: number }
  | { status: "expired" }
  | { status: "paid" };
```

```swift
public enum Entitlement: Equatable, Sendable {
    case unknown
    case trial(daysLeft: Int, endsAt: Int64)
    case expired
    case paid
}
```

The canonical discriminated-union mapping: `status` becomes the case, the extra fields
become associated values, and the compiler now forbids reading `daysLeft` off a `.paid`
— which `trialNotice`'s `if (e.status !== "trial") return null` had to do by hand.

`daysLeft` is `Int` (`Math.ceil` always yields an integer). `endsAt` is `Int64` ms.

`.unknown` is never produced by `entitlementOf`. Keep the case: it is the documented
"store has not answered" value, `canPlay` is specified against it, and the doc comment
("Never render a paywall on this") is part of invariant 11's contract.

### 3.3 `PurchaseStore` and `StoreSnapshot`

```swift
public struct StoreSnapshot: Equatable, Sendable {
    public var paid: Bool
    public var trialStartedAt: Int64?
    public var reachable: Bool
    public static let unreachable = StoreSnapshot(
        paid: false, trialStartedAt: nil, reachable: false)
}

public protocol PurchaseStore: Sendable {
    /// Is there a purchase path on this build at all?
    var available: Bool { get }
    func refresh() async -> StoreSnapshot     // NOT throwing — see below
    func beginTrial() async -> Int64?         // epoch ms, or nil
    func purchase() async -> Bool
    func restore() async -> Bool
    func priceLabel() async -> String?
}
```

**[DEVIATION — and it is the good kind] None of these throw.** In TypeScript every one
of the six is a `Promise` and every call site wraps it in `.catch(() => <fail-open
value>)`. That is invariant 11 enforced by six separate hand-written catch clauses; miss
one and a paying child sees a paywall because StoreKit blinked. In Swift, declaring the
protocol non-throwing means the *adapter* is obliged to map every error to the fail-open
value, and no caller can forget. The fail-open mapping is fixed and normative:

| failure | must return |
|---|---|
| `refresh()` — any error, any timeout | `.unreachable` |
| `beginTrial()` — any error, cancel | `nil` (the local stamp stands) |
| `purchase()` — any error, cancel, `.pending`, `.unverified` | `false` |
| `restore()` — any error | `false` (the model still refreshes; see § 4.2) |
| `priceLabel(_:)` — any error | `nil` (the screen falls back to the tier's own constant → `"11,99 €"` / `"2,99 €"`) |

Three conformances ship:

- `StoreKitPurchaseStore` (ALPlatform) — the real thing, § 5.
- `StubPurchaseStore` — `available: true`, `refresh()` → `.unreachable`, everything else
  the fail-open value. This is a literal port of today's `nativeStore` and is the
  correct default until StoreKit is wired: a native build today behaves exactly like the
  web one plus a running trial clock, nothing charged, nothing locked.
- `PreviewPurchaseStore(available:paid:)` — for `#Preview` and tests. **Must be
  `#if DEBUG`.** Today's `webStore` returns `paid: true` unconditionally *and the
  provider persists that `true` to storage*; an `available:false, paid:true` store
  reaching a release build would silently unlock everyone. (§ 7 R7)

### 3.4 Telemetry — closed by construction

This is the heart of invariant 10 in the port. **There is no `String` key path
anywhere.** Not in the API, not in the queue, not in the encoder.

```swift
public enum TelemetryEvent: String, CaseIterable, Sendable {
    case exerciseStarted   = "exercise_started"
    case sessionCompleted  = "session_completed"
    case shopOpened        = "shop_opened"
    case itemBought        = "item_bought"
    case mascotGrown       = "mascot_grown"
    case trialStarted      = "trial_started"
    case trialExpired      = "trial_expired"
    case paywallShown      = "paywall_shown"
    case purchaseCompleted = "purchase_completed"
    case purchaseFailed    = "purchase_failed"
    case purchaseRestored  = "purchase_restored"
}
```

The TS `EVENTS.includes(event)` runtime check and its dev `console.warn` **disappear**:
an unknown event is not representable. Do not add a `case custom(String)`. Do not make
this `RawRepresentable` from an arbitrary string in any public initialiser.

```swift
public struct TelemetryProps: Equatable, Sendable {
    public var exercise: ExerciseId?   // closed enum, ported by the types agent
    public var level: Int?
    public var rounds: Int?
    public var perfect: Int?
    public var points: Int?
    public var cost: Int?
    public var stage: Int?
    public var daysLeft: Int?
    public init(exercise: ExerciseId? = nil, level: Int? = nil, rounds: Int? = nil,
                perfect: Int? = nil, points: Int? = nil, cost: Int? = nil,
                stage: Int? = nil, daysLeft: Int? = nil)
}
```

`number` → `Int`, not `Double`. Verified against every producer: `daysLeft` comes from
`Math.ceil`, `points`/`cost` from `Math.floor` in `rewards.ts`, the rest are counts.
This makes the TS "drops non-finite numbers rather than sending null" rule
**unrepresentable** rather than merely enforced — there is no `Int` NaN. (§ 7 R11)

Wire encoding is hand-written, one `if let` per field, in the TS insertion order so the
JSON is byte-comparable:

```swift
// key order: level, rounds, perfect, points, cost, stage, daysLeft, exercise
```

(`NUMERIC_KEYS` order first, then `exercise` — that is exactly what `sanitize` produces
and `JSON.stringify` preserves.) Use a `JSONEncoder` with
`.outputFormatting = []` (no `.sortedKeys`, which would reorder) and a manual
`encode(to:)`, or build the object with `JSONSerialization` from an ordered array. Do
**not** add `Codable` synthesis to `TelemetryProps` — synthesis would emit optionals by
`encodeIfPresent` in declaration order, which happens to match, but would also silently
start emitting any field a future edit adds. Hand-written is the point: adding a field
without touching the encoder produces a field that is never sent.

Payload envelope, matching `post("/events", { v, events })`:

```swift
struct EventEnvelope: Encodable { let v: String; let events: [WireEvent] }
struct WireEvent: Encodable { let event: String; let props: [ordered pairs] }
```

`ExerciseId` is already a closed union in `types.ts` and will be a Swift enum. Its raw
values (`"read-image"` etc.) go on the wire unchanged.

### 3.5 Index signatures / `Record<string, …>`

`sanitize` returns `Record<string, number | string>` and `Payload.props` is that type.
**Nothing in the Swift port is a dictionary keyed by `String`.** The whole reason the TS
needs `sanitize` — copying a caller-supplied bag into a filtered bag — evaporates when
the bag is a struct with eight named optional fields. `sanitize` therefore has no Swift
counterpart, and the TS test "silently drops any property not on the allowlist"
(which passes `childName: "Léa"` through an `as unknown as` cast) has no Swift
counterpart either, because there is no cast that would make it compile. It is replaced
by the reflection test in § 6.3.

### 3.6 Updates

```swift
public struct UpdateManifest: Decodable, Equatable, Sendable {
    public let version: String
    public let url: URL
    public let checksum: String
    public let minNative: String?
}
public func versionAtLeast(_ have: String, _ need: String) -> Bool
```

`have.split(".").map(Number)` with `?? 0` for missing segments. Swift:
`have.split(separator: ".").map { Int($0) ?? 0 }`, pad the shorter with 0, compare
element-wise, `return true` on full equality. `Int($0) ?? 0` reproduces the TS `Number()`
→ `NaN` case badly on purpose: TS `NaN !== 0` is true and `NaN > 0` is false, so
`versionAtLeast("1.x.0", "1.0.0")` is `false` in TS. `Int("x") ?? 0` gives `0`, and
`0 !== 0` is false so it *continues* to the next segment. **Reproduce the TS
behaviour**: map a non-numeric segment to a sentinel that compares as "not equal, not
greater". Concretely, model segments as `Int?` and, on the first index where the two
differ (treating `nil` as differing from everything including another `nil`), return
`(a ?? -1) > (b ?? -1)` with `nil` losing. There is no test for it and no real manifest
will contain one; specify it so the implementer does not silently pick the other
branch. (§ 7 R12)

---

## 4. Behaviour notes

### 4.1 The entitlement state machine — normative

The single most important function in this scope. Quoted whole, because paraphrase is
how ports go wrong:

```ts
export function entitlementOf(state: LicenseState, now: number): Entitlement {
  const t = effectiveNow(state, now);

  if (state.paid) {
    const stale =
      state.verifiedAt !== null && t - state.verifiedAt > OFFLINE_GRACE_MS;
    if (!stale) return { status: "paid" };
    // Grace exhausted: fall through and let the trial clock decide, rather than
    // hard-locking.
  }

  if (state.trialStartedAt === null) {
    return { status: "trial", daysLeft: TRIAL_DAYS, endsAt: t + TRIAL_MS };
  }

  const endsAt = state.trialStartedAt + TRIAL_MS;
  const daysLeft = Math.ceil((endsAt - t) / DAY_MS);
  return daysLeft > 0 ? { status: "trial", daysLeft, endsAt } : { status: "expired" };
}
```

Step by step, with every fail-open edge named:

**S0 — monotonic clock.** `t = max(now, state.clockHighWater)`. A device clock wound
backwards is ignored. This is the only *fail-closed* thing in the file and it is
deliberate: winding the date back is the free trial-extension trick.
`withClock(state, now)` raises the high-water mark and is called on every touch.

**S1 — paid, fresh.** `paid == true` and (`verifiedAt == nil` **or**
`t - verifiedAt <= OFFLINE_GRACE_MS`) ⇒ `.paid`. Note the boundary is `>`, so exactly
14 days is still fresh.

**S1a — paid, never verified ⇒ paid forever.** `verifiedAt == nil` makes `stale` false
regardless of `t`. The test asserts this holds ten years out. This is intentional: we
have a `paid` flag and nothing to age it against, so we believe it.

**S1b — paid, grace exhausted ⇒ fall through, do not lock.** The comment is the spec:
"rather than hard-locking. Worst case the family sees the paywall and taps
'Restaurer'." Control drops to S2/S3, so a family whose trial had not started still
gets `.trial`.

**S2 — trial not started ⇒ full trial.** `trialStartedAt == nil` ⇒
`.trial(daysLeft: 14, endsAt: t + TRIAL_MS)`. Pre-onboarding, nothing is ever gated
before the parent has seen the terms. Note `endsAt` is recomputed from `t` on every
call, so it slides forward — it is not a stable date. (§ 7 R13)

**S3 — the countdown.** `endsAt = trialStartedAt + TRIAL_MS`;
`daysLeft = ceil((endsAt - t) / DAY_MS)`; `.trial` while `daysLeft > 0`, else
`.expired`. The boundary is exact: at `T0 + TRIAL_MS - 1` it is still trial, at
`T0 + TRIAL_MS` it is expired.

Swift `ceil`, matching `Math.ceil` including negatives:

```swift
let daysLeft = Int((Double(endsAt - t) / Double(DAY_MS)).rounded(.up))
```

`.rounded(.up)` is `ceil` (toward +∞), not `.toNearestOrAwayFromZero`. Epoch ms fit
exactly in a `Double` (< 2^53), so this is lossless.

**S4 — `canPlay`.**

```swift
public func canPlay(_ e: Entitlement) -> Bool { e != .expired }
```

`.unknown`, `.trial`, `.paid` all play. Never invert this into a whitelist
(`e == .paid || e == .trial`) — that is exactly the refactor that breaks invariant 11,
because a future fifth case would default to locked.

### 4.2 `applySnapshot` — what the store is allowed to change

```ts
export function applySnapshot(s, snap, now) {
  if (!snap.reachable) return withClock(s, now);
  const trialStartedAt =
    snap.trialStartedAt !== null
      ? Math.min(snap.trialStartedAt, s.trialStartedAt ?? snap.trialStartedAt)
      : s.trialStartedAt;
  return withClock({ ...s, paid: snap.paid, verifiedAt: now, trialStartedAt }, now);
}
```

- **Unreachable ⇒ change nothing but the clock.** Not `paid`, not `verifiedAt`. The
  test explicitly asserts `verifiedAt` is *not* re-stamped: the grace window measures
  time since the last **confirmation**, so an unreachable check must not refresh it.
- **Reachable ⇒ `paid` is taken verbatim, including `false`.** That is how a refund
  lands. A negative from a reachable store is authoritative; a negative from an
  unreachable one is not. This asymmetry *is* invariant 11.
- **Earliest trial start wins.** `min(authoritative, local ?? authoritative)`. A
  reinstall cannot buy a fresh fortnight. If the platform cannot prove a start
  (`snap.trialStartedAt == nil`) the local stamp is kept untouched.
- `verifiedAt = now` on every reachable answer, paid or not.

Swift signature — free function, pure, no `self`:

```swift
public func applySnapshot(_ s: LicenseState, _ snap: StoreSnapshot, _ now: Int64) -> LicenseState
```

### 4.3 `EntitlementModel` — the provider's behaviour, precisely

Fields mirrored from `useState`: `license`, `onboarded`, `priceLabel`, `checkedAt`.

**`checkedAt` is not a live clock.** `entitlement` is computed as
`entitlementOf(license, checkedAt)` where `checkedAt` is set only inside `refresh()`.
The comment says why: "The trial can lapse mid-session. We recheck on resume rather
than ticking a timer: a child mid-exercise when the fortnight runs out gets to finish."
Do **not** replace this with a `Timer`, a `TimelineView`, or `Date.now` in a computed
property. Port the staleness.

**`init`.** `license = LicenseStore.load()`, `onboarded = LicenseStore.loadOnboarded()`,
`priceLabel = nil`, `checkedAt = time.nowMillis`. Storage is synchronous
(`UserDefaults`), so unlike the web there is no hydration race.

**`refresh()`** — port of:

```ts
const now = Date.now();
setCheckedAt(now);
try {
  const snap = await store.refresh();
  commit(applySnapshot(ref.current, snap, now));
} catch {
  commit(withClock(ref.current, now));
}
```

Swift, with the protocol non-throwing, collapses to:

```swift
let now = time.nowMillis
checkedAt = now
let snap = await store.refresh()          // cannot throw; returns .unreachable on error
license = applySnapshot(license, snap, now)
LicenseStore.save(license)
```

The `catch` arm is not lost — it is relocated into the adapter's obligation to return
`.unreachable`. Note `now` is sampled **before** the await and reused after, exactly as
in the TS.

**[DEVIATION] Refresh must be serialised.** React's `ref.current` read-modify-write was
safe because JS is single-threaded and the two triggers (mount, resume) could not
interleave a `commit`. Two overlapping Swift `Task`s could each read `license`, await,
and write back — losing one update. Hold a single `Task<Void, Never>?`; a second
`refresh()` while one is in flight awaits the existing task rather than starting a new
one. `@MainActor` guarantees the read-modify-write around the single `await` is
otherwise atomic. (§ 7 R1)

**Refresh triggers.** Boot (`.task` on the root view) and `scenePhase == .active`.
Comment to preserve: "that is when a purchase made in the store UI, a Family Sharing
grant, or a refund shows up." SwiftUI fires `.active` at first appearance too, so boot
double-refreshes; the TS does the same (mount effect + `appStateChange`), and with
serialisation the second call is a coalesced no-op. Do **not** refresh on `.inactive`
(Control Centre, notification shade) — Capacitor's `isActive` is false there and the TS
only acts on `true`.

**`priceLabel`** is fetched once at init, in parallel with the first refresh, and never
again. `nil` on failure.

**`beginTrial()`** — order is load-bearing:

1. `LicenseStore.saveOnboarded()`; `onboarded = true`. Synchronous, first, so the
   onboarding screen dismisses even if everything after fails.
2. `license = withClock({...license, trialStartedAt: license.trialStartedAt ?? now}, now)`
   and save. **Local stamp first**, so the clock starts with no network. `?? now` means
   an existing stamp is never overwritten.
3. `Task { if let auth = await store.beginTrial() { license.trialStartedAt = min(auth, license.trialStartedAt ?? auth); license = withClock(license, time.nowMillis); save } }`
   — the authoritative (earlier) StoreKit date, if the platform can mint one. `nil` ⇒
   the local stamp stands, silently.

Note step 3 re-reads `license` *after* the await (TS reads `ref.current`), so it must
compose with whatever a concurrent `refresh()` wrote. Route it through the same
serialisation as `refresh()`.

**`purchase()`** — `let ok = await store.purchase(); if ok { await refresh() }; return ok`.
Refresh only on success.

**`restore()`** — `let ok = await store.restore(); await refresh(); return ok`.
Refresh **unconditionally**. The asymmetry with `purchase()` is in the TS and is
correct: `AppStore.sync()` can materialise entitlements even when the call reports
nothing restored. Port as-is.

**Telemetry is not emitted by the model.** `track("purchase_completed")` etc. live in
`Paywall.tsx`, after the await. Keep them in the view layer; do not "tidy" them into
the model, or the screens agent's port will double-fire them.

### 4.4 Updates — what survives the port, and what does not

**This is the one place the port genuinely removes a capability. Read this section
before writing any code.**

#### Dead: the live JS bundle

`@capgo/capacitor-updater` downloads a zip of `dist/` and swaps the WKWebView's document
root. The DPLA §3.3.1(B) carve-out that permits it is narrow and explicit: *downloaded
interpreted code executed by Apple's WebKit or JavaScriptCore*. A native SwiftUI app has
no WebKit in the loop and its UI is compiled machine code. There is no legal, and no
technical, way to hot-swap it. `CapacitorUpdater.download` / `.next` /
`.notifyAppReady`, the rollback window (`appReadyTimeout: 10_000`), `resetWhenUpdate`,
and `checkOnce`'s download half all **delete**.

What this costs, concretely, stated plainly for the user:

| Today (PWA/Capacitor) | After the port |
|---|---|
| Fix a typo in French copy | same-day OTA | **store release** (~24h review) |
| Fix a layout bug | same-day OTA | **store release** |
| Fix a wrong exercise icon | same-day OTA | **store release** |
| Add a word to `LETTER_WORDS` | same-day OTA | OTA, *if* the content channel below is built; otherwise store release |
| Re-bake a VO clip | same-day OTA | OTA, same condition |
| Retune `SYLLABLE_TIERS` / `REWARD_CURVE` | same-day OTA | OTA, same condition |
| Add an exercise | store release (needed a native cap or not, 2.3.1 applies) | store release |
| Change the price or paywall | **never** (explicitly forbidden today) | **never** |

Every row that was already forbidden stays forbidden. The rows that get worse are
copy/layout/icon fixes — genuine UI, which is exactly what "interpreted code" bought us
and compiled code cannot.

#### Survives: the version guard

`versionAtLeast` ports verbatim to `ALCore/Updates/SemVer.swift`, pure, with its 5 tests
running on the host. Its consumer changes (see below) but the function does not. It is
still the guard against "a payload that needs a shell the installed binary lacks", which
is still a white screen on a child's tablet with no way back.

#### Recommended replacement: signed remote **content**, not code

This is a recommendation, not a port — see § 8 decision G on whether to build it now.

The distinction that matters to 2.5.2 is *code that "introduces or changes features or
functionality"* versus *data interpreted by features the reviewed binary already
contains*. A JSON list of French words rendered by an exercise engine App Review
already saw is data, in the same sense a downloaded level pack or a server-driven
word-of-the-day is data. It is not "interpreted code" in the prohibited sense, and it
does not need the §3.3.1(B) carve-out at all, because it is not code.

The line, and it must be held hard:

**Allowed over the air** — new entries in `LETTER_WORDS` / `SYLLABLE_WORDS` /
`SYLLABLE_GRID_ROWS`, new VO clips, retuned `SYLLABLE_TIERS` / `FIRST_LETTER_LEVELS` /
`SOUND_LEVELS` / `REWARD_CURVE` numbers, corrected French strings **that already exist
as keys in the shipped binary**.

**Never over the air** — a new `ExerciseId`, a new `SyllableMode`, a new screen, a new
string key with no shipped fallback, anything under `Licensing/`, the price, a feature
dormant at submission. Each of those is 2.3.1 or 2.5.2 territory and each is a store
release.

Mechanism:

```swift
public struct ContentManifest: Decodable, Sendable {
    public let schema: Int          // the app rejects anything it does not know
    public let version: String
    public let url: URL
    public let checksum: String     // sha256 of the payload
    public let minNative: String?   // versionAtLeast guard, unchanged
    public let signature: Data      // Ed25519 over (schema|version|checksum)
}
```

- **Signed, first-party.** `CryptoKit.Curve25519.Signing.PublicKey.isValidSignature`
  with the public key compiled into the binary. No vendor, no SDK — same reason
  `store.ts` has no vendor in it (Kids Category 1.3 bans third-party PII/device
  information, and the cheapest way to comply is to have no third party).
- **Whole-payload rejection.** Unknown `schema`, bad signature, checksum mismatch, or
  `!versionAtLeast(appVersion, minNative)` ⇒ discard entirely. Never partially apply:
  half a word list is a corrupt game.
- **Applied at next launch only**, mirroring `CapacitorUpdater.next({id})`. "Swapping
  the bundle under a child who is halfway through a round is the worst possible
  moment" applies unchanged to swapping the word list.
- **Last-known-good.** Keep the previously applied payload and the store-shipped
  baseline; if a launch with a new payload does not reach first paint, fall back. This
  is the `notifyAppReady` / `appReadyTimeout: 10_000` idea, ported.
- **Failures are silent.** "a failed update check must be indistinguishable from a
  normal launch to the family using the app." Every error swallowed, at most a
  `Telemetry.reportError(_, "remote-content")`.
- **Trigger points** unchanged: boot, and each `scenePhase == .active`.

The manifest fetch itself is `credentials: "omit"` → a `URLRequest` with
`httpShouldHandleCookies = false` on an ephemeral session.

### 4.5 Telemetry — behaviour

**Consent (`attrape-lettres:consent:v1`).** Three states, and the distinction is
tested: unset (never asked), `"0"` (refused), `"1"` (given). `hasConsent()` is
`value == "1"`; `consentAnswered()` is `value != nil`. `setConsent(false)` **drains the
queue immediately** (`queue.length = 0`) — withdrawal is retroactive for anything not
yet sent. Device-scoped, never synced: consent belongs to the adult holding this phone.
France sets the GDPR Art. 8 age at 15, so the toggle only ever appears on parent-facing
screens.

**`track`.** `if !hasConsent() || endpoint == nil { return }`. Enqueue. Flush when
`queue.count >= 20`. Never awaited, never blocks a tap, never throws. In Swift:
`@MainActor func track(_ e: TelemetryEvent, _ p: TelemetryProps = .init())`, synchronous
in and out, the network hop inside a detached `Task`.

**`flushTelemetry`.** No-op if already flushing, if the queue is empty, or if no
endpoint. Otherwise splice the whole queue and POST `{ v: appVersion, events: [...] }`
to `<endpoint>/events`. The `flushing` re-entrancy guard is vestigial in JS but real in
Swift once a `Task` is involved — keep it.

**`reportError`.** Deliberately **not** consent-gated: "The payload carries no
identifier of any kind, so it is not personal data and needs no consent. That split is
the point: we still hear about the bug that breaks the game for the ~60% of parents who
decline analytics." POSTs `{ v, where, message, stack }` to `<endpoint>/errors`,
truncated to 60 / 300 / 2000. Accepts a non-Error throw (`String(describing:)`).
**Nothing from app state is ever attached** — the roster holds children's first names.

**[DEVIATION] Truncation unit.** JS `slice(0, 300)` counts UTF-16 code units; Swift
`String(s.prefix(300))` counts grapheme clusters. Identical for ASCII (the tested case,
`"x".repeat(5000)`), and Swift's is strictly safer — it cannot split a surrogate pair or
a combining sequence. Use `prefix`. (§ 7 R5)

**[DEVIATION] `installErrorReporting` does not survive intact.** `window.onerror` and
`unhandledrejection` exist because "A Capacitor app's failures are JS exceptions, not
native crashes — App Store Connect and Play Console vitals never see them, so without
this we are blind to our own bugs." **In a native app that premise is false**: App Store
Connect *does* receive native crash reports, and Swift's typed `throws` means there is
no global unhandled-rejection channel to hook. Swift runtime traps (force-unwrap,
array bounds, `precondition`) are signals, not catchable exceptions.

What ports:
- `Telemetry.reportError(_:where:)` — the API, called from the same explicit catch sites
  (`installLiveUpdates` → `reportError(e, "live-update")` becomes
  `reportError(e, "remote-content")`).
- `document.visibilitychange → hidden ⇒ flushTelemetry()` becomes
  `scenePhase == .background ⇒ flush()`, wrapped in
  `UIApplication.shared.beginBackgroundTask` to reproduce `keepalive: true`.

What does not port: any global handler. `NSSetUncaughtExceptionHandler` catches only
ObjC exceptions, which a pure SwiftUI app essentially never raises. Do **not** add a
third-party crash SDK — Kids Category 1.3. MetricKit (`MXMetricManagerSubscriber`) is
the first-party option and is a *new capability*; behaviour is frozen, so it is a
follow-up, not part of the port. (§ 7 R6)

**Transport.** `credentials: "omit"` ⇒ `URLSessionConfiguration.ephemeral` with
`httpCookieStorage = nil`, `urlCredentialStorage = nil`,
`httpCookieAcceptPolicy = .never`, and `request.httpShouldHandleCookies = false`. No
`Authorization`, no custom headers beyond `content-type: application/json`. Responses
are ignored; errors are swallowed ("telemetry must never surface to a child").

**Endpoint.** `import.meta.env.VITE_TELEMETRY_URL`, read **lazily** so tests and builds
can vary it. Swift: an injected `URL?` on the `Telemetry` initialiser, sourced at the
app layer from an `xcconfig`-driven Info.plist key. `nil` ⇒ the whole module is inert,
which is the tested "a dev build posts nowhere" case.

### 4.6 The native surface, mapped

`capacitor.config.ts`:

| Key | Value | Swift/Xcode home |
|---|---|---|
| `appId` | `fr.dappit.attrapelettres` | `PRODUCT_BUNDLE_IDENTIFIER`. **Must not change** — the StoreKit product ids are namespaced under it and an existing purchase is keyed to it. |
| `appName` | `Attrape-Lettres` | `CFBundleDisplayName` |
| `backgroundColor` | `#FFE7C9` | launch-screen background + window background |
| `loggingBehavior` | `"none"` | no `print`/`os_log` of user content in release |
| `ios.contentInset` | `"never"` | `.ignoresSafeArea()` on the stage gradient; insets handled in layout |
| `ios.scrollEnabled` | `false` | no `ScrollView` on game surfaces; `.scrollBounceBehavior(.basedOnSize)` where one is unavoidable |
| `SplashScreen.launchAutoHide` | `false` | a SwiftUI launch screen; the hydration gate it existed for is unnecessary (UserDefaults is synchronous) |
| `CapacitorUpdater.*` | — | deleted; `appReadyTimeout` / `resetWhenUpdate` reappear as the content channel's last-known-good rules (§ 4.4) |
| `android.*` | — | out of scope |

`package.json`:

| Dep | Fate |
|---|---|
| `@capacitor/core` | gone |
| `@capacitor/app` | `scenePhase` + `Bundle.main` |
| `@capacitor/preferences` | `UserDefaults` behind `KeyValueStore` |
| `@capacitor/splash-screen` | launch screen storyboard/SwiftUI |
| `@capacitor/status-bar` | `.statusBarHidden` / `preferredColorScheme` |
| `@capgo/capacitor-updater` | **gone**, § 4.4 |
| `version: "0.1.0"` | `MARKETING_VERSION`. `__APP_VERSION__` becomes `AppVersionProvider` reading `CFBundleShortVersionString`. |

---

## 5. StoreKit 2 adapter (ALPlatform)

Products, per App Review 3.1.1 and Kids Category 1.3:

| id | type | price | note |
|---|---|---|---|
| `fr.dappit.attrapelettres.trial7` | non-consumable | **0** | must be **named "7-day Trial"** in App Store Connect. Its StoreKit `purchaseDate` is the clock, because it is signed by Apple and survives a reinstall. iOS only — Play has no price-0 IAP. |
| `fr.dappit.attrapelettres.unlock` | non-consumable | €11.99 | **Family Sharing ON** in App Store Connect: six people, free, native. |
| `fr.dappit.attrapelettres.unlock.early` | non-consumable | €2.99 | Same content, early-adopter price. **Family Sharing ON** too. Offered only to a family holding `LicenseState.discountGrantedAt`, i.e. one that redeemed a `discount` code. StoreKit has no coupon for a one-time purchase — offer codes are subscriptions only — so the discount has to BE a product. Both ids count as paid in `scan()`; reading only the first locks out every early adopter. |

### 5.1 `refresh()` — and the sharpest hazard in this port

Naive implementation:

```swift
for await result in Transaction.currentEntitlements {
    guard case .verified(let t) = result else { continue }
    if t.productID == PRODUCT_UNLOCK, t.revocationDate == nil { paid = true }
    if t.productID == PRODUCT_TRIAL { trialStartedAt = ms(t.purchaseDate) }
}
return StoreSnapshot(paid: paid, trialStartedAt: trialStartedAt, reachable: true)
```

**This violates invariant 11.** `Transaction.currentEntitlements` reads a *local signed
cache*. On a fresh install with no network the cache is empty and the loop completes
normally — so the naive version reports `paid: false, reachable: true`, which
`applySnapshot` treats as authoritative and writes `paid = false`. A paying family that
reinstalls on a plane gets locked out. The TS stub never had this problem because it
always reported `UNREACHABLE`.

**Normative rule.** `reachable` means *"the store answered out loud"*, and a negative
may only downgrade when it did.

1. Iterate `Transaction.currentEntitlements`, collecting `paid` and `trialStartedAt`
   from `.verified` results only. `.unverified` is discarded (never trust an unsigned
   claim), and a non-nil `revocationDate` on the unlock is not paid.
2. If `paid == true` ⇒ return `reachable: true`. A signed positive is always
   trustworthy, online or not. **A paid family is never downgraded by this path.**
3. If `paid == false` ⇒ we cannot tell "genuinely not purchased" from "cache not yet
   populated". Probe: `try await Product.products(for: [PRODUCT_UNLOCK])`.
   - throws, or returns empty ⇒ return `.unreachable`.
   - succeeds ⇒ the device has reached the App Store this session, so an empty
     entitlement set is a real negative ⇒ return `reachable: true, paid: false`.

Step 3 is what makes the TS test *"honours a refund once the store says so out loud"*
still true, and the test *"refuses to downgrade anything when the store is unreachable"*
still true, on real StoreKit.

Wrap the whole thing in a timeout (`withThrowingTaskGroup` + `Task.sleep`, ~10s) whose
expiry returns `.unreachable`. Any thrown error anywhere ⇒ `.unreachable`.

### 5.2 `Transaction.updates` listener

StoreKit 2 requires a listener for the app's whole lifetime; interrupted purchases,
Ask-to-Buy approvals, Family Sharing grants and revocations all arrive there and nowhere
else. Start it at launch:

```swift
Task.detached {
    for await result in Transaction.updates {
        if case .verified(let t) = result { await t.finish() }
        await entitlementModel.refresh()
    }
}
```

This is **not** in the TypeScript (Capacitor's stub had no such concept) and is
mandatory on StoreKit — without it an Ask-to-Buy approval never lands. Record it as a
necessary addition, not a feature.

### 5.3 `purchase()`

`Product.products(for: [PRODUCT_UNLOCK]).first` → `product.purchase()`.

| result | return | then |
|---|---|---|
| `.success(.verified(t))` | `true` | `await t.finish()` |
| `.success(.unverified)` | `false` | — |
| `.userCancelled` | `false` | — |
| `.pending` | `false` | the `Transaction.updates` listener picks it up on approval |
| throws | `false` | — |

`.pending` is **Ask to Buy**, which in a six-year-old's app is not an edge case. It maps
to `false`, so `Paywall.tsx` shows "L'achat n'a pas abouti. Rien n'a été débité." —
literally true (nothing was debited) but misleading (it may yet succeed). Behaviour is
frozen: port as-is, flagged in § 7 R3.

### 5.4 `restore()`

`try? await AppStore.sync()` (this is the control Apple requires to exist), then re-run
the `refresh()` entitlement scan; return `true` iff a verified, unrevoked
`PRODUCT_UNLOCK` entitlement is now present. Errors ⇒ `false`. The model refreshes
afterwards regardless (§ 4.3).

### 5.5 `beginTrial()`

Purchase `PRODUCT_TRIAL` (price 0 — this still presents the purchase sheet; that is the
mechanism guideline 3.1.1 prescribes, not a bug). On `.success(.verified(t))` return
`ms(t.purchaseDate)`. Everything else, including a cancel, returns `nil` and the local
stamp stands.

### 5.6 `priceLabel()`

`Product.products(for: [PRODUCT_UNLOCK]).first?.displayPrice` — already localised and
already formatted `"11,99 €"` in a French storefront. `nil` on any failure; the screens
fall back to the tier's own constant → `"11,99 €"`, or `"2,99 €"` for `.early`.

### 5.7 What must **not** appear in this file

No RevenueCat, no Adapty, no Superwall, no analytics SDK, no HTTP call to anything but
Apple. The only parties in the payment path are the family and Apple. This is both a
product decision and the cheapest possible compliance story for Kids Category 1.3
("may not send personally identifiable information or device information to third
parties") — there is no third party to send it to.

---

## 6. Test plan

Everything below runs on the **host** with `swift test` — no simulator, no signing, no
network — except where marked ⚠️.

### 6.1 `EntitlementTests` — 1:1 port of `entitlement.test.ts`

Same `T0 = 1_700_000_000_000`, same 13 cases, same names (translated), same
assertions:

- full fortnight before the parent has accepted
- counts down from the start date (14 / 11 at +3d / 1 at +13.5d)
- expires at exactly `T0 + TRIAL_MS`, not `-1`
- `canPlay` true in trial and paid, false expired
- ignores a clock wound back a year (`effectiveNow` and `daysLeft: 4`)
- the two French strings, byte for byte:
  `"Essai gratuit — 11 jours restants"` (note the em dash and the non-ASCII spaces are
  ordinary spaces around `—`) and `"Dernier jour d'essai"`; `trialNotice(.paid) == nil`
- paid beats an exhausted trial
- stays paid at `+OFFLINE_GRACE_MS - DAY_MS`
- expires at `+OFFLINE_GRACE_MS + DAY_MS`
- never expires a paid family with `verifiedAt == nil`, even ten years out
- the 6 `applySnapshot` cases (unreachable no-downgrade + `verifiedAt` unchanged; grant;
  refund; earliest trial start; keep local stamp when unprovable; high-water advances)

If any of these need editing to pass, the port is wrong, not the test.

### 6.2 `EntitlementPropertyTests` — invariant 11 as properties

Deterministic pseudo-random sweeps (seeded `SystemRandomNumberGenerator` replacement, so
failures reproduce), ~10 000 iterations each, all with `MutableTimeSource`:

1. **Unreachable never downgrades.** ∀ `state`, ∀ `now`:
   `rank(entitlementOf(applySnapshot(state, .unreachable, now), now)) >= rank(entitlementOf(state, now))`
   where `rank(paid) = 3 > trial = 2 > unknown = 1 > expired = 0`.
2. **A confirmed paid family plays for a fortnight offline.** ∀ `v`, ∀ `d ∈ [0, 14 days]`:
   `entitlementOf(LicenseState(paid: true, verifiedAt: v, …), v + d) == .paid`.
3. **Never-verified paid is permanent.** ∀ `now`: `paid && verifiedAt == nil ⇒ .paid`.
4. **Clock rewind is inert.** ∀ `now <= state.clockHighWater`:
   `entitlementOf(state, now) == entitlementOf(state, state.clockHighWater)`.
5. **`canPlay` is total.** ∀ case of `Entitlement`: `canPlay` iff not `.expired`. Written
   over `CaseIterable`-ish exhaustive construction so a future fifth case fails to
   compile rather than silently defaulting to locked.
6. **Chaos store.** A `ChaosPurchaseStore` returning `.unreachable` / throwing-mapped
   values / occasional real answers at random, driven through 1000 `refresh()` calls
   with a randomly walking clock. Assert: once the license has ever held
   `paid == true, verifiedAt = v` from a **reachable** answer, no sequence of
   unreachable answers produces `.expired` before `v + OFFLINE_GRACE_MS`.
7. **Monotone countdown.** For a fixed started trial, `daysLeft` is non-increasing in
   `t`, and hits `.expired` exactly once.

### 6.3 `TelemetryClosureTests` — invariant 10 as structure

1. **Reflection allowlist.** `Mirror(reflecting: TelemetryProps())` child labels, as a
   `Set<String>`, equals the literal
   `["exercise","level","rounds","perfect","points","cost","stage","daysLeft"]`. Adding
   a field without touching this test and the encoder fails the build's test run. This
   is the Swift replacement for the TS `childName: "Léa"` test, which cannot be written
   because there is no cast that would compile.
2. **Encoded key set is a subset of the allowlist.** For every `TelemetryEvent` × a
   fully populated `TelemetryProps`, decode the produced JSON back to
   `[String: JSONValue]` and assert `Set(props.keys) ⊆ allowlist` and the envelope keys
   are exactly `["v","events"]`, event keys exactly `["event","props"]`.
3. **No device id.** Assert the encoded payload of every event contains no occurrence of
   a `deviceId()`-shaped value: run with a `KeyValueStore` pre-seeded with a device id
   of `"dad-phone"` and assert the string never appears. Structurally guaranteed
   (Telemetry does not import Device) — the test documents the guarantee.
4. **No free text on the event path.** `reportError` is the only producer of free-form
   strings; assert `track`'s output for every event is pure numbers plus one
   `ExerciseId` raw value.

### 6.4 `TelemetryTests` — 1:1 port of `telemetry.test.ts`

Against a `RecordingTransport` (an in-memory `TelemetryTransport`) and an in-memory
`KeyValueStore`:

- unanswered is not consent; refusal is distinct from never-asked
- nothing sent without consent
- withdrawal drains the queue
- a tracked event contains event name + allowlisted numbers + `exercise` + `v`, in that
  key order, and nothing else; URL is `<endpoint>/events`
- batching: two `track`s ⇒ one POST with two events; a second flush is a no-op
- no cookies/credentials (assert the request's `httpShouldHandleCookies == false` and
  the session config's `httpCookieStorage == nil`)
- error reports sent **without** consent, to `<endpoint>/errors`, with `message` and
  `where`
- a 5000-char message truncates to 300
- a non-Error throw does not crash
- with `endpoint == nil`, nothing is posted at all

Dropped, as unrepresentable: "silently drops any property not on the allowlist"
(§ 3.5) and "drops non-finite numbers" (§ 3.4). Both are replaced by § 6.3.1.

### 6.5 `SemVerTests` — 1:1 port of `updates.test.ts`

Exact match, newer shell, older shell, `1.10.0 > 1.9.0` (the lexical-compare bug),
missing segments as zero. Plus the non-numeric-segment case specified in § 3.6.

### 6.6 `LicenseStoreTests`

- round-trip: `save` then `load` is the identity for a populated state
- JSON shape: encoded blob contains explicit `"verifiedAt":null` for a blank license
- corrupt bytes ⇒ `.blank`; wrong-typed field ⇒ that field's default, others preserved
- **`.blank` plays**: `canPlay(entitlementOf(.blank, anyNow)) == true`
- a write failure (a `KeyValueStore` that throws) is swallowed and does not mutate state
- key names are exactly `attrape-lettres:license:v1` and `attrape-lettres:onboarded:v1`

### 6.7 `EntitlementModelTests`

With `MutableTimeSource` + `PreviewPurchaseStore`/`ChaosPurchaseStore`:

- init reads persisted state; `checkedAt` == injected now
- `entitlement` does **not** change when the time source advances past expiry until
  `refresh()` is called (the frozen-`checkedAt` behaviour)
- `beginTrial` sets `onboarded` synchronously, stamps `trialStartedAt` before any await,
  and does not overwrite an existing stamp
- `beginTrial` with an authoritative earlier date takes the min
- `purchase()` refreshes only on success; `restore()` refreshes always
- **two concurrent `refresh()` calls coalesce** and neither loses a write
- `priceLabel` is `nil` when the store fails and never retried

### 6.8 ⚠️ Not host-testable — simulator / device only

- `StoreKitPurchaseStore` against an Xcode `.storekit` configuration file: purchase,
  cancel, Ask-to-Buy pending → approve, refund, Family Sharing grant, revocation. Use
  StoreKit Test's transaction manager; assert the resulting `StoreSnapshot`, not the UI.
- The § 5.1 reachability rule with the network off: install fresh with a paid Apple
  Account, kill the network, launch ⇒ must **not** report `paid: false, reachable: true`.
  This is the single most important manual test in the port.
- `scenePhase` refresh wiring, background-task flush.
- The `Transaction.updates` listener.

### 6.9 A source-scan test

A `Tools/` script (or an XCTest that reads its own source tree) asserting:

- no `Date()` literal under `Sources/ALCore/Licensing/`
- `Sources/ALCore/Telemetry/` contains no `import` of a Device module and no
  `deviceId` identifier
- `Sources/ALCore/` contains no `import StoreKit`
- `Sources/ALCore/Telemetry/` contains no `[String: ` dictionary type

Cheap, and it turns four review-time rules into build-time ones.

---

## 7. Risks

**R1 — React's single-threaded read-modify-write has no SwiftUI equivalent.**
`useEntitlement` reads `ref.current`, awaits, and writes back, in three places. Two
overlapping Swift tasks would lose an update. Mitigation: `@MainActor` plus a single
coalescing in-flight `Task`. This is a deliberate deviation forced by the concurrency
model, not a behaviour change — but it is the one place where "port it verbatim" would
be wrong.

**R2 — `reachable` has no StoreKit signal.** `Transaction.currentEntitlements` succeeds
offline, so the TS's central fail-open concept does not map directly. The probe rule in
§ 5.1 is a design decision I had to invent, and it is the highest-risk item in this
spec. It should be reviewed before implementation and manually tested per § 6.8. Get it
wrong in the other direction and a reinstalling paying family is locked out — the exact
failure invariant 11 exists to prevent.

**R3 — `.pending` (Ask to Buy) shows the wrong copy.** In a Kids Category app,
Ask to Buy is the *normal* path for a family with Screen Time set up. It maps to
`false`, which shows "L'achat n'a pas abouti. Rien n'a été débité." The purchase may
still complete minutes later via `Transaction.updates`. Behaviour frozen ⇒ ported as-is.
Recommend a follow-up: a third `PurchaseOutcome.pending` case and a French line saying
the request was sent to a parent.

**R4 — the live JS bundle is gone and cannot come back.** § 4.4. Copy, layout and icon
fixes now cost a store release. This is the one capability the port removes and the user
must accept it consciously.

**R5 — string truncation unit differs** (UTF-16 code units vs grapheme clusters).
Harmless, strictly safer, tested case is ASCII. Noted so nobody "fixes" it back.

**R6 — `installErrorReporting` cannot be ported faithfully.** No global handler for
Swift traps without a crash reporter, and third-party SDKs are barred by Kids Category
1.3. Partially obviated: App Store Connect does see native crashes, which was the stated
reason the hook existed. Net observability probably improves; error *context* worsens.

**R7 — `webStore` returns `paid: true` unconditionally and the provider persists it.**
On the web that is a product decision (no payment rail, no gating). In Swift there is no
web build, so this must not survive as a default. `PreviewPurchaseStore` with
`paid: true` must be `#if DEBUG` and must never be the release default; the release
default until StoreKit lands is `StubPurchaseStore` (always `.unreachable`, which is
fail-open via the trial clock, not via a fake purchase).

**R8 — `Entitlement.unknown` is unreachable from `entitlementOf`.** It exists only as a
literal and only `canPlay` reads it. Kept deliberately (§ 3.2), but an implementer will
be tempted to delete it as dead code. Do not: it is the documented "store has not
answered" value and deleting it would let a future refactor make "no answer" mean
"locked".

**R9 — the entitlement system is not wired to gameplay.** `canPlay` and `trialNotice`
have zero call sites and `Paywall` is mounted from nowhere. The port must reproduce
that state, not complete it. If the user wants it wired, that is a separate change with
its own review.

**R10 — 6 of 11 telemetry events are never emitted.** Aspirational list; ported whole.

**R11 — `Int` vs `number` makes one TS test vacuous.** "drops non-finite numbers rather
than sending null" cannot fail in Swift. Verified safe: every producer
(`Math.floor`/`Math.ceil`/counts) yields integers. If a future call site wants a
fraction, switch that field to `Double` **and** restore an `.isFinite` guard in the
encoder in the same change.

**R12 — `versionAtLeast` on a non-numeric segment** behaves differently between
`Number("x") → NaN` and `Int("x") ?? 0`. No test, no real manifest. Specified in § 3.6;
implement the specified behaviour rather than whichever is convenient.

**R13 — `endsAt` slides** in the pre-onboarding branch (`t + TRIAL_MS`, recomputed every
call). Any UI that renders an end *date* from it would show a date that moves. Nothing
does today. Ported as-is.

**R14 — `LicenseState` is not a `Counter`.** Invariant 9 (never persist a bare running
total) does not apply here — nothing in the license accumulates, and `persist.ts` is
explicitly separate from `storage.ts` for that reason ("Losing a profile is a tragedy,
losing this is one `restore()` tap"). The license must **not** be added to
`sync/merge.ts`. If household-level entitlement is ever wanted (the Android
Family-Sharing gap), it belongs on the sync backend keyed by `familyId`, not in the
merged profile blob.

---

## 8. Decisions needed above this scope

**A — a fourth target, `Sources/ALPlatform`.** D1 says platform implementations are
"injected at the app layer", but `App/` is also meant to hold "almost no code". The
StoreKit adapter, the URLSession transport, the UserDefaults KV, haptics and the audio
session all need a home, and putting them in the Xcode target means they are outside
`swift build` and inside the `.pbxproj`. **Recommend** adding
`.target(name: "ALPlatform", dependencies: ["ALCore"])` to `Package.swift`, building for
iOS+macOS, imported only by `App/`. Fallback if rejected: `App/Platform/*.swift`, and
accept that the adapters are not compiled by CI.

**B — who owns `KeyValueStore`.** Licensing (2 keys), telemetry consent (1 key),
`device.ts`, `storage.ts` and the sync client all read `kv.ts`. There must be exactly
one protocol, one `UserDefaults` suite, one key namespace (`attrape-lettres:*` preserved
verbatim so a future PWA→native import is possible). The storage agent should declare
it; I consume it. My spec assumes `protocol KeyValueStore { func string(_ key: String) -> String?; func set(_ value: String, for key: String); func remove(_ key: String) }`, synchronous.

**C — `TimeSource` naming and sharing.** Must not be called `Clock` (stdlib collision).
Licensing, `sync/merge` LWW stamps and the reward curve all need injectable time; one
protocol, one instance injected at the root.

**D — the app version.** `__APP_VERSION__` is `package.json`'s `0.1.0`. The native app
needs a `MARKETING_VERSION` and it is not obvious the PWA's line should continue.
Also: `minNative` in the old manifest compared the *native shell* version against a
*bundle* requirement — after the port there is only one version, so `minNative` becomes
"the app version required by this content payload". Someone must set the initial
numbering.

**E — one lifecycle fan-out.** Licensing refresh, sync pull/push, and the content check
all want "on resume". Three independent `.onChange(of: scenePhase)` handlers will fire
in undefined order and triple-hammer the network on every foreground. Recommend one
app-level `AppLifecycle` that publishes `didBecomeActive` / `didEnterBackground` and
that these three subscribe to.

**F — configuration mechanism.** `VITE_TELEMETRY_URL` and `VITE_UPDATE_URL` are
build-time env vars. Their Swift equivalent (xcconfig → Info.plist → typed accessor)
should be decided once, for all agents, not three times.

**G — is the remote-content channel in scope for this port at all?**
**Recommend: no.** Port the version guard and leave `RemoteContent.swift` as a
specified, unimplemented seam. Shipping an OTA content channel is a new capability with
its own App Review exposure and its own signing-key operational burden, and behaviour is
frozen. Build it as a deliberate follow-up, with § 4.4's allowed/forbidden line written
into `DECISIONS.md` first.

**H — Family Sharing on Android.** Out of scope for an iOS port, but the asymmetry
documented in `CLAUDE.md` (Play Family Library never shares IAPs) means the
platform-conditional French copy in `Onboarding.tsx` (`ios ? "pour toute la famille" :
"sur vos appareils"`) becomes a constant in an iOS-only build. The screens agent should
keep `"pour toute la famille"` and delete the branch, or keep the branch dead. Flagging
so both agents make the same choice.
