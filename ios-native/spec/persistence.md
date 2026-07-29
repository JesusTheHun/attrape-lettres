# Persistence & Sync — Swift port specification

Scope: `src/storage.ts`, `src/kv.ts`, `src/device.ts`, `src/hooks/useProfile.tsx`
(+ test), `src/sync/merge.ts`, `src/sync/client.ts` (+ tests), `src/main.tsx`.

Behaviour is frozen (D0). The PWA is the specification; everything below is a
description of what the TypeScript **does**, restated as a Swift design. Where a
JS semantic detail matters for byte- or value-compatibility it is quoted.

---

## 1. Inventory

| File | Lines | What it actually does |
|---|---|---|
| `src/kv.ts` | 91 | The one key/value primitive. Web: `localStorage` directly. Native: an in-memory `Map<string,string>` hydrated once at boot from `@capacitor/preferences` (`hydrateKv()` awaits `Preferences.keys()` then gets each key), so **every read is synchronous forever after**. Writes go cache-first, then fire-and-forget to Preferences. All failures swallowed. |
| `src/device.ts` | 56 | This device's id: `crypto.randomUUID()` (fallback `d_<ts36>_<rand36>`), stored under `attrape-lettres:device:v1`, resolved **lazily** on first call (must be after hydration), memoised in a module variable. It is the key every Counter is indexed by. It is *not* a person identifier and is never sent to analytics; it **does** appear inside Counter keys uploaded to the household record — that is by design. A failed write degrades to a per-launch id, which still merges losslessly (just an extra counter key). `__resetDeviceId` exists for tests. |
| `src/storage.ts` | 120 | The ONLY module that reads/writes profiles. Synchronous and total: value-or-default, never a throw, never a promise. Keys: `attrape-lettres:roster:v4` (current), `:roster:v3`, `:profile:v2`, `:profile:v1` (kept for migration + rollback), `attrape-lettres:shop-seen:v1` (childId → last-seen balance; purely cosmetic, its own key so no schema bump). `loadV3Roster/loadV2Profile/loadV1Profile` return raw `unknown` — useProfile owns the reshape. |
| `src/hooks/useProfile.tsx` | 586 | The roster provider: single source of truth for players, points, mascots, progress. Owns: default/blank shapes, the loose-normalisation of any loaded blob, the v1/v2/v3→v4 migrations, `initialRoster()` (v4→v3→v2→v1→empty), the flattening `expose()` (PersistedProfile → runtime Profile with `balance`/`ledger`/`config`/`owned`), and every mutation: `award/preview/spend/buy/setConfig/chooseSpecies/createChild/selectChild/renameChild/deleteChild/switchChild`. Also owns the sync trigger: pull→merge→push **on mount and on app-resume**, never on a write. Mutations read the freshest roster through a ref (synchronous, pointerdown-safe). |
| `src/hooks/useProfile.test.tsx` | 333 | Behavioural tests: roster gate, award curve + accuracy bonus, difficulty-0 pays nothing but ledgers, spend/buy, non-destructive species switch, sibling isolation, v3→v4 and v1→v4 migrations (exact star reproduction, curve position preserved, v3 blob left in place), delete tombstones, persistence round-trip. |
| `src/sync/merge.ts` | 208 | **Pure** cross-device merge. No storage, no network, no clock (time only via `now` args). Counters (per-device grow-only, merged by per-key `max`), PN-counter stars (`balance = max(0, Σearned − Σspent)`), clears summed across devices, LWW `Rev` stamps with deterministic tie-break, grow-only `owned` union, `chosen` OR, per-species doc merge, roster merge with tombstones (`removed`), the `tombstone > touchedAt` delete rule, and `activeId` never merged. |
| `src/sync/merge.test.ts` | 285 | Commutativity, associativity, idempotence; overdraw floors at 0 and both items kept; clears sum so farming stays unprofitable; LWW ties agree from both sides; delete propagation, delete refusal after later play, tombstone transitivity to a third device. |
| `src/sync/client.ts` | 159 | The transport around merge. Household = uuid + join code, no accounts. Keys `attrape-lettres:household:v1`, `attrape-lettres:household-etag:v1`. `WireChild = Omit<ChildProfile,"name"|"nameRev">`; `toWire` strips names + stamps + `activeId`; `fromWire` re-attaches names this device already knows (unknown child ⇒ `"Enfant"` + zero stamp so the local name always wins). `SyncTransport` protocol (pull/push with ETag optimistic concurrency, push resolves `"conflict"` on 412). `syncOnce`: up to 3 attempts of pull→merge→push; on conflict, merge on top and retry — never force. Endpoint from `VITE_SYNC_URL`, read lazily. `syncEnabled() = endpoint && householdId`. |
| `src/sync/client.test.ts` | 194 | The wire never contains a name, a `nameRev`, or `activeId` (asserted on the serialised JSON); names re-attach locally; placeholder `"Enfant"` with zero stamp; syncOnce no-ops without a household; two phones converge to 13 stars while the server JSON never contains "Léa"; conflict retry keeps all three devices' stars; idempotent re-sync. |
| `src/main.tsx` | 68 | Boot order: `installErrorReporting()` → `await hydrateKv()` → mount React (roster must be in memory before `useState(initialRoster)`) → hide splash → `installLiveUpdates()`. SW registration is web-only. |

Cross-scope inputs this scope consumes (owned elsewhere, signatures needed):
`rewards.ts` — `ledgerKey(exercise, level) → "\(exercise):\(level)"`,
`rewardFor`, `sessionReward(difficulty, priorClears, perfectRounds, totalRounds)`,
`previewReward(ledger, exercise, level, difficulty)`; `levels.ts` —
`exerciseDifficulty(exercise)`; `types.ts` — `Species`, `MascotConfig`,
`CustomizationOption`, `ExerciseId`.

---

## 2. Swift module plan

Everything in this scope is **ALCore** (pure Swift, host-testable), except the
two platform adapters injected from the app layer.

```
Sources/ALCore/Persistence/
  KVStore.swift          protocol KVStore + InMemoryKVStore (tests/previews)
  ProfileModels.swift    Counter, Rev, StarCounters, ClearCounters,
                         SpeciesProgress, SpeciesMap, PersistedProfile,
                         ChildProfile, Roster, ProfileView (runtime flatten)
  LooseDecoding.swift    all-optional Decodable mirrors of every on-disk shape
                         (v4 loose + legacy v1/v2/v3) — the JSON tolerance layer
  ProfileStorage.swift   port of storage.ts: keys, load/save roster, shop-seen,
                         raw legacy loaders
  Migrations.swift       normalizeProfile/normalizeSpecies/normalizeRoster,
                         migrateFlatProfile, migrateV1Profile, initialRoster
  DeviceIdentity.swift   deviceId over KVStore, lazy + memoised, test reset
  ProfileStore.swift     @MainActor final class ProfileStore: ObservableObject —
                         the useProfile port; every mutation lives here
Sources/ALCore/Sync/
  Merge.swift            the pure merge — port of merge.ts, function for function
  Wire.swift             WireChild / WireRoster (NO name field — see invariant 10),
                         toWire / fromWire
  SyncClient.swift       SyncTransport protocol, household identity, syncOnce
App/ (app layer, injected)
  UserDefaultsKVStore.swift   KVStore over UserDefaults.standard, Capacitor-
                              compatible key prefix (see §8)
  URLSessionSyncTransport.swift  the HTTP transport (fetch → URLSession)
Tests/ALCoreTests/
  MergeTests.swift, MergePropertyTests.swift, WireTests.swift,
  SyncClientTests.swift, ProfileStoreTests.swift, MigrationTests.swift,
  KVStoreTests.swift, CapacitorInteropTests.swift
```

Dependency direction (all within ALCore, no cycles):

```
KVStore ← DeviceIdentity ← ProfileStore
KVStore ← ProfileStorage ← Migrations ← ProfileStore
ProfileModels ← {everything}
Merge ← ProfileStore (bump/balanceOf/ledgerOf/newRev on the write path)
Merge ← Wire ← SyncClient ← ProfileStore (sync trigger)
Rewards/Levels (other scope) ← ProfileStore
```

`ProfileStore` is the single choke point for every profile mutation, exactly as
`useProfile` is today. ALUI reads `store.profile` (a `ProfileView`) and calls
the API; it never touches `KVStore`, `Merge`, or `ProfileStorage` directly.

---

## 3. Type mapping

JSON compatibility is a hard requirement twice over: (a) the wire format must
interoperate with web/Android Capacitor devices in the same household, and
(b) an in-place iOS update must read the previous Capacitor build's blobs.
Field names below are therefore byte-identical to the TS property names.

| TS | Swift | Notes |
|---|---|---|
| `Counter = Record<string, number>` | `typealias Counter = [String: Int]` | Keys are device ids (opaque strings). Values are integer star/clear counts. `bump` may write `+0` (award of 0 points still creates/keeps the key) — preserve. |
| `StarCounters { earned; spent }` | `struct StarCounters: Codable, Equatable { var earned: Counter; var spent: Counter }` | PN-counter. Never add a stored total. |
| `ClearCounters = Record<string, Counter>` | `typealias ClearCounters = [String: Counter]` | Key = `ledgerKey(exercise, level)` = `"\(exerciseId):\(level)"`. |
| `CompletionLedger = Record<string, number>` | `typealias CompletionLedger = [String: Int]` | Derived only (fold of ClearCounters). Never persisted. |
| `Rev { at: number; by: string }` | `struct Rev: Codable, Equatable { var at: Millis; var by: String }` | `typealias Millis = Int64`. `at` is `Date.now()` (ms since epoch, always an integer in JS). `ZERO_REV = Rev(at: 0, by: "")`. |
| `Species` union | `enum Species: String, Codable, CaseIterable` (owned by types scope) | Cases `unicorn, cat, fox, rabbit, dragon`, raw values identical. |
| `Record<Species, SpeciesProgress>` | `struct SpeciesMap: Codable, Equatable` with **five stored properties** (`unicorn, cat, fox, rabbit, dragon: SpeciesProgress`) and `subscript(_: Species) -> SpeciesProgress { get set }` | Deliberately a fixed struct, not `[Species: SpeciesProgress]`: (1) totality is compile-time — `normalizeSpecies` in TS exists to guarantee all five keys are present, the struct guarantees it for free; (2) avoids the `Dictionary` Codable pitfall (enum-keyed dicts need `CodingKeyRepresentable` to encode as an object, not an array); (3) `mergeProfile` iterates `Object.keys(a.species)` — with the struct it iterates `Species.allCases`, which is what the normalised data always is. Custom `init(from:)` supplies `blankProgress(s)` for a missing key and **ignores unknown keys** (matching `normalizeSpecies` dropping them). `encode(to:)` always writes all five, matching normalised JS output. |
| `MascotConfig` | struct owned by types scope: `species: Species, stage: Int, colors: [String: String], styles: [String: String], accessories: [String]` | Index signatures land as `[String: String]`. |
| `SpeciesProgress { config; owned; rev }` | `struct SpeciesProgress: Codable, Equatable { var config: MascotConfig; var owned: [String]; var rev: Rev }` | `owned` is a grow-only **ordered array**, not a `Set` — merge preserves a-side order (see §4.5). |
| `PersistedProfile` | `struct PersistedProfile: Codable, Equatable { var chosen: Bool; var current: Species; var currentRev: Rev; var species: SpeciesMap; var stars: StarCounters; var clears: ClearCounters }` | No `balance`, no `ledger` — structurally impossible to persist a total (invariant 9). |
| `Profile extends PersistedProfile` | `struct ProfileView` — the persisted fields **plus** `config: MascotConfig, owned: [String], balance: Int, ledger: CompletionLedger` | **Deliberately NOT `Codable`.** It is the runtime flatten (`expose()` in TS) and must never be persisted or sent; making it non-Codable makes that a compile error, not a code-review catch. Built by `ProfileView(of: PersistedProfile)`. |
| `ChildProfile` | `struct ChildProfile: Codable, Equatable { var id: String; var name: String; var nameRev: Rev; var touchedAt: Millis; var profile: PersistedProfile }` | `name` is a six-year-old's first name — device-local, see invariant 10. `id` stays `String` (JS uuids are lowercase; ids are minted once and copied, never re-derived, so no case normalisation). |
| `Roster` | `struct Roster: Codable, Equatable { var children: [ChildProfile]; var activeId: String?; var removed: [String: Millis] }` | `activeId: null` ⇄ `nil`. When encoding, emit `"activeId": null` explicitly (encode the optional, don't omit) to match JS `JSON.stringify` output — harmless either way for JS readers but keeps fixtures byte-comparable. |
| `WireChild = Omit<ChildProfile, "name"\|"nameRev">` | `struct WireChild: Codable, Equatable { var id: String; var touchedAt: Millis; var profile: PersistedProfile }` | **There is no `name` property to forget to strip.** See invariant 10. |
| `WireRoster` | `struct WireRoster: Codable, Equatable { var children: [WireChild]; var removed: [String: Millis] }` | No `activeId` field — its absence on the wire is also compile-time. |
| `LegacyV1Profile`, `LegacyFlatProfile`, `LegacyV3Roster`, `LooseProgress`, `LooseProfile` | all-optional `Decodable` structs in `LooseDecoding.swift` | Every field `var x: T?`; nested species map decodes as `[String: LooseProgress]` keyed by raw string (unknown species dropped during normalise). These are decode-only (no `Encodable`). |
| `SyncTransport` interface | `protocol SyncTransport { func pull(household: String) async throws -> (roster: WireRoster, etag: String)?; func push(household: String, roster: WireRoster, etag: String?) async throws -> PushResult }` with `enum PushResult: Equatable { case ok(etag: String); case conflict }` | The TS `"conflict"` string union member becomes an enum case. `pull` returning `null` (404) becomes `nil`. |
| `ProfileAPI` | the public surface of `ProfileStore` (methods, not closures) | See §4.6. |
| kv (`getItem/setItem/removeItem`) | `protocol KVStore: AnyObject { func get(_ key: String) -> String?; func set(_ key: String, _ value: String); func remove(_ key: String) }` | **Synchronous by protocol shape** — there is no async requirement to accidentally await (invariant 1). |

JSON coding rules (one shared `JSONCoding.swift` helper or per-type):
plain `JSONEncoder()/JSONDecoder()` with default strategies — all keys are
already exact-match, dates are raw `Int64` ms (never `Date`), no key
conversion, no date strategy. Numbers: counts and `Millis` are integers in
every blob JS has ever written (`Date.now()`, integer stars); decode as
`Int`/`Int64`. Do **not** use `.sortedKeys` output on the wire — key order is
irrelevant to every reader, but fixtures in tests may use it locally for
comparison.

---

## 4. Behaviour notes

### 4.1 KVStore — the synchronous primitive (kv.ts)

**Recommendation: `UserDefaults.standard`.** Rationale, against the
alternatives:

- `UserDefaults` **is** the Swift-native version of what kv.ts hand-builds:
  an in-memory cache (maintained by `cfprefsd`) with synchronous reads and
  asynchronous write-behind persistence. The entire `hydrateKv()` machinery
  — the boot await, the splash-screen hold, the "reads must come after
  hydration" ordering constraint on `deviceId()` — **disappears**; there is
  nothing to hydrate. Invariant 1 is satisfied by construction: `get` is a
  synchronous dictionary read, `set` is cache-first with the daemon flushing
  behind, exactly the semantics kv.ts implements manually.
- `UserDefaults` is what `@capacitor/preferences` writes to on iOS, which
  makes in-place upgrade a read of the same store (§8) instead of a data
  migration.
- It is backed up (iCloud device backup / encrypted local backup) and is not
  evictable under disk pressure — the two properties the kv.ts header names
  as the reason Preferences was chosen over WebView localStorage.
- **Not Keychain**: Keychain survives app uninstall. `device.ts` documents
  "a reinstall mints a fresh one" as deliberate; Keychain would silently
  break that promise and turn the device id into a longer-lived identifier —
  the opposite of the privacy posture. Also Keychain reads can block on
  device-lock state.
- **Not a bespoke file**: we would be re-implementing atomicity, write
  coalescing and crash-safety that `cfprefsd` already provides, to gain
  nothing.

What hydrates at launch: **nothing needs to**. `UserDefaults.standard`
self-primes on first access; the first `get` may fault in the plist (fast,
synchronous). There is no async boot step, so the D1 app layer does not need
a splash-hold equivalent of `await hydrateKv()`. `deviceId()` keeps its lazy
memoised shape anyway (it is correct and free), but the ordering constraint
that motivated laziness in TS no longer exists.

Failure semantics: `UserDefaults` writes do not throw; the TS try/catch
around quota/private-mode has no equivalent failure on iOS. `KVStore`'s API
is non-throwing, matching storage.ts's "total, never a throw" contract.

`InMemoryKVStore` (a `[String: String]` behind the protocol) is the test and
preview double — it is what lets every ProfileStore/Migration test run on the
host with `swift test`, no simulator.

### 4.2 DeviceIdentity (device.ts)

```
read():  saved = kv.get(KEY); if saved { return saved }
         fresh = UUID().uuidString.lowercased()   // see note
         kv.set(KEY, fresh); return fresh
deviceId(): memoised read()
```

Key: `attrape-lettres:device:v1` (through the Capacitor-compatible prefix,
§8 — so an in-place update **keeps the same device id**, and existing
counters keep accruing under the same key; this matters more than it looks:
a fresh id would still merge losslessly, but keeping it avoids a dangling
counter key per updated device).

Note on case: JS `crypto.randomUUID()` emits lowercase; Swift
`UUID().uuidString` emits uppercase. Ids are opaque and only ever compared
for equality against themselves, so either works — lowercase is recommended
purely so mixed-fleet households produce homogeneous-looking documents.
The `d_<ts36>_<rand36>` fallback path (crypto unavailable) has no Swift
equivalent failure mode; omit it, `UUID()` cannot fail.

Provide `_reset(id: String?)` mirroring `__resetDeviceId` — the merge and
store tests need to act as different devices.

### 4.3 ProfileStorage (storage.ts)

Constants, verbatim:

```
KEY          = "attrape-lettres:roster:v4"
V3_KEY       = "attrape-lettres:roster:v3"
V2_KEY       = "attrape-lettres:profile:v2"
V1_KEY       = "attrape-lettres:profile:v1"
SHOP_SEEN_KEY= "attrape-lettres:shop-seen:v1"
```

- `loadRoster() -> LooseRoster?` — get KEY, decode with the loose decoder,
  `nil` on absent **or any decode failure** (total function; TS catches
  parse errors and returns null).
- `saveRoster(Roster)` — encode, `kv.set`. Non-throwing.
- `loadShopSeen() -> [String: Int]` / `saveShopSeen(_)` — default `[:]` on
  failure. Cosmetic; the shop meter animation reads it (ALUI scope), the
  storage functions live here.
- `loadV3Roster() -> LegacyV3Roster?`, `loadV2Profile() -> LegacyFlatProfile?`,
  `loadV1Profile() -> LegacyV1Profile?` — decode-or-nil.
- **The old keys and readers are never deleted** — the schema-history rule in
  the storage.ts header carries over verbatim: a format change is a forward,
  additive migration; bump to `:vN`, add a `loadV(N-1)`, keep everything.
  (On iOS the "rolled-back launch" scenario is rarer than on the PWA, but the
  cross-platform household makes the rule load-bearing anyway: an Android
  Capacitor device in the same family may still be on v4-writing code.)

Difference from TS: `loadRoster` in TS returns the blob cast to `Roster` and
`normalizeRoster` runs later in `initialRoster`. In Swift the cast is
impossible; `loadRoster` returns the loose shape and `Migrations.swift`
normalises. Same observable behaviour, one honest type.

### 4.4 Migrations & normalisation (useProfile.tsx lines 57–263)

All pure functions in `Migrations.swift`, all host-testable. Inputs: loose
shapes + `device: String` + `now: Millis` (passed in — no clock reads inside,
same discipline as merge.ts).

Blanks:

```
blankConfig(s)   = MascotConfig(species: s, stage: 0, colors: [:], styles: [:], accessories: [])
blankProgress(s) = SpeciesProgress(config: blankConfig(s), owned: [], rev: ZERO_REV)
DEFAULT_PROFILE  = PersistedProfile(chosen: false, current: .unicorn,
                   currentRev: ZERO_REV, species: all-blank map,
                   stars: StarCounters(earned: [:], spent: [:]), clears: [:])
```

`ZERO_REV` is `{at: 0, by: ""}` — "the stamp every never-written LWW field
starts at — always loses a merge".

`normalizeSpecies(loose)`: start from the all-blank map; for each known
species present in the loose dict, overlay:
`config = blankConfig(s) ⊕ loose.config` field-by-field (each present field
replaces the default) **with `species` force-set to the slot key** —
TS: `{ ...blankConfig(s), ...src.config, species: s }`; `owned = src.owned ?? []`;
`rev = src.rev ?? ZERO_REV`. Unknown species keys are dropped.

`normalizeProfile(loose)`: every field defaulted
(`chosen ?? false`, `current ?? .unicorn`, `currentRev ?? ZERO_REV`,
species via normalizeSpecies, `stars = {earned: loose.stars?.earned ?? [:],
spent: loose.stars?.spent ?? [:]}`, `clears ?? [:]`). If `current` decodes
to an unknown species string, treat as absent (→ `.unicorn`) — JS would have
kept the garbage string; Swift's enum cannot, and `.unicorn` is the only
behaviour-preserving choice for data that only a corrupted blob can contain.
**List this in Risks; do not silently widen `current` to String.**

`migrateFlatProfile(legacy, device)` — v2/v3 → v4. The doc comment is the
spec: the flat totals become "everything earned on THIS device", the only
honest seeding since pre-v4 there was no sync; two phones migrating their own
blobs and meeting later SUM, which is correct.

```
clears: for (key, n) in legacy.ledger where n > 0 → clears[key] = [device: n]
stars:  earned = legacy.balance > 0 ? [device: balance] : [:]; spent = [:]
```

Note the guards: `n > 0` and `balance > 0` — a zero or negative legacy value
produces **no key**, not a zero key. `balance` was already net of spending,
so seeding `earned` alone leaves the spendable total unchanged.

`migrateV1Profile(legacy, device)` — v1 → v4: `current = legacy.config?.species
?? .unicorn`; that one mascot fills its species slot
(`species = [current: (config: legacy.config, owned: legacy.owned ?? [])]`),
`chosen` defaults **true** for v1 (TS: `l.chosen ?? true` — v1 users had
necessarily chosen), then delegates to `migrateFlatProfile`.

`normalizeRoster(loose)`: per child —
`id = (id?.isEmpty == false ? id! : newId())` — **TS uses `||`, so an empty
string id is replaced too**; `name ?? "Joueur"`; `nameRev ?? ZERO_REV`;
`touchedAt ?? 0`; profile via normalizeProfile. `activeId` kept only if a
child with that id survived, else `nil`. `removed ?? [:]`.

`initialRoster(kv:, device:, now:) -> Roster` — priority order, exactly:

1. v4 present → `normalizeRoster(v4)`.
2. else v3 present **and `children` non-empty** (TS: `v3?.children?.length`)
   → map each child: `id = c.id || newId()`, `name = c.name ?? "Joueur"`,
   `nameRev = ZERO_REV`, **`touchedAt = now`** (comment is normative: "Fresh
   migration: nothing can have tombstoned these yet, and marking them touched
   keeps a future stale tombstone from erasing them"),
   `profile = migrateFlatProfile(c.profile ?? {}, device)`.
   `activeId` kept if it matches a migrated child, else `nil`. `removed = [:]`.
3. else v2 present → one child `"Joueur 1"` via `child()` helper
   (`id = newId()`, `nameRev = newRev(device, now)`, `touchedAt = now`),
   activeId = that child.
4. else v1 present → same wrapper around `migrateV1Profile`.
5. else empty roster `(children: [], activeId: nil, removed: [:])`.

`child(name:profile:device:now:)`: `name.trim() || "Joueur"` — note it does
**not** clamp to 14 characters (only `renameChild` does). Port the asymmetry.

The migration **does not delete or rewrite the old key** — the v3/v2/v1 blobs
stay on disk untouched (asserted by the TS test "leaves the v3 blob in place").
The migrated result is only persisted when the first `commit` happens (first
mutation), not eagerly at load — same as `useState(initialRoster)` which
saves nothing until a write. Preserve that: `ProfileStore.init` computes the
roster but does not call `saveRoster`.

`newId()`: `UUID().uuidString.lowercased()` (TS fallback shape
`c_<ts36>_<rand36>` unneeded).

### 4.5 Merge (sync/merge.ts) — pure, no clock, no storage, no network

Port function-for-function into `Merge.swift` as free functions (or a
caseless `enum Merge` namespace). Every function takes values and returns
values; the only time that ever enters is through `newRev(device:now:)`'s
parameters. That is what keeps the whole file property-testable on the host.

```
mergeCounter(a, b)   per-key max: out = a; for (d, n) in b { out[d] = max(out[d] ?? 0, n) }
sumCounter(c)        Σ values
bump(c, device, by)  out = c; out[device] = (out[device] ?? 0) + by   // the ONLY legal counter write
emptyStars()         (earned: [:], spent: [:])
mergeStars(a, b)     field-wise mergeCounter
balanceOf(stars)     max(0, sum(earned) − sum(spent))                 // floor is normative, see below
mergeClears(a, b)    union of keys, mergeCounter per key ([:] default)
ledgerOf(clears)     per-key sumCounter — clears SUM across devices
newRev(device, now)  Rev(at: now, by: device)
laterRev(a, b)       a.at != b.at ? (a.at > b.at ? a : b) : (a.by >= b.by ? a : b)
revWins(a, b)        implement as a predicate:
                     a.at != b.at ? a.at > b.at : a.by >= b.by
mergeOwned(a, b)     out = a; for id in b where !out.contains(id) { out.append(id) }   // ORDER: a first
mergeSpecies(a, b)   winner = revWins(a.rev, b.rev) ? a : b
                     → (config: winner.config, rev: winner.rev, owned: mergeOwned(a.owned, b.owned))
mergeProfile(a, b)   chosen: a || b (grow-only)
                     currentWinner = revWins(a.currentRev, b.currentRev) ? a : b
                     current/currentRev from winner
                     species: for s in Species.allCases → mergeSpecies(a[s], b[s])
                     stars: mergeStars; clears: mergeClears
mergeChild(a, b)     id: a.id; name/nameRev from revWins(a.nameRev, b.nameRev) winner
                     touchedAt: max; profile: mergeProfile
mergeRoster(l, r)    removed: per-id max of tombstone times
                     children: index l by id, fold r in (mergeChild when both)
                     drop any child where removed[id] != nil && removed[id]! > touchedAt
                     activeId: keep l.activeId iff still present, else nil
```

Non-obvious points an implementer must not lose:

- **`revWins` in TS is `laterRev(a,b) === a` — reference identity.** Swift
  structs have no identity; implement the predicate directly as above. It is
  equivalent: when the stamps are fully equal both orderings pick the `a`-side
  by `>=`, and equal stamps carry equal payloads in practice.
- **Tie-break is string `>=` on `by`.** JS compares UTF-16 code units; Swift
  `String >=` compares Unicode scalars. Device ids are ASCII (uuid hex or
  `d_…36`), where the two orders coincide. Do not "fix" this with a locale
  or lexicographic-by-grapheme comparison; plain `>=` on ASCII is exact.
- **`balanceOf` floors at 0** and the comment travels with it: two offline
  devices both spend the same 10 stars → Σearned=10, Σspent=16 → child keeps
  both items and sees 0. "We never claw a purchase back from a six-year-old
  to satisfy arithmetic."
- **`ledgerOf` SUMS across devices** — two devices each clearing level 1 once
  is two clears; the reward curve decays accordingly. Max-ing here would
  re-open the 10-star jackpot on the other phone (invariant 8's economy).
- **`mergeOwned` is order-preserving union**, local order first. Do not use
  `Set` — the array order is observable (shop/inventory ordering).
- **`mergeRoster` never merges `activeId`** — "who is holding this tablet
  says nothing about who is holding the other one."
- **The delete rule is deliberately not delete-always-wins**: a tombstone is
  honoured only when `tombstone > touchedAt`. A resurrected child is two
  taps; a vanished child is unrecoverable. Tombstones themselves merge by
  max and are **kept forever** in `removed` so a third device also honours
  the delete (transitivity test exists).
- Every merge is commutative, associative, idempotent — that is not emergent,
  it is the contract sync relies on ("safe to fire as often as we like").
  The property tests in §6 pin it.

### 4.6 ProfileStore (useProfile.tsx)

`@MainActor final class ProfileStore: ObservableObject` (or `@Observable`;
pick once app-wide — see §9). Constructor-injected: `kv: KVStore`,
`device: () -> String` (defaults to `DeviceIdentity`), `now: () -> Millis`
(defaults to wall clock; injectable for tests), `sync: SyncClient?`.

State: `@Published private(set) var roster: Roster`, initialised with
`Migrations.initialRoster(...)`. React's ref-mirror trick
(`ref.current = roster` so pointerdown-path reads are fresh) is unnecessary:
a `@MainActor` class property **is** the single, synchronously-readable,
always-fresh value. `award`/`spend`/`buy` read `self.roster` directly —
invariant 1's "nowhere to await" is preserved because every method below is
a plain synchronous function.

Private helpers, straight ports:

- `commit(_ next: Roster)` — set `roster`, `ProfileStorage.saveRoster(next)`.
  Every mutation persists immediately (TS commits on every write).
- `updateActive(_ fn: (PersistedProfile) -> PersistedProfile)` — no-op when
  `activeId == nil`; otherwise rewrite the active child with
  `touchedAt = now()` **stamped on every write** (the delete rule reads it),
  and `profile = fn(child.profile)`.
- `activeProfile` — the active child's profile or `DEFAULT_PROFILE`.
- `expose` → `var profile: ProfileView` computed:
  `config = species[current].config`, `owned = species[current].owned`,
  `balance = balanceOf(stars)`, `ledger = ledgerOf(clears)`. Everything
  outside ProfileStore/ProfileStorage/Merge sees plain numbers.

Public API (returns and edge cases are normative):

- `award(exercise: ExerciseId, level: Int, perfectRounds: Int, totalRounds: Int) -> Int`
  1. `key = ledgerKey(exercise, level)`
  2. `prior = ledgerOf(activeProfile.clears)[key] ?? 0` — read **before** the
     update, from the freshest state.
  3. `points = sessionReward(exerciseDifficulty(exercise), prior, perfectRounds, totalRounds)`
  4. `updateActive`: `stars.earned = bump(earned, device, points)` (yes, even
     when `points == 0` — the TS bump writes `+0`, creating the key; keep it),
     `clears[key] = bump(clears[key] ?? [:], device, 1)`.
  5. return `points`. Difficulty-0 exercises award 0 but still ledger the
     clear (tested). If no child is active the update no-ops but the computed
     `points` is still returned (TS behaviour — `updateActive` guards, `award`
     doesn't).
- `preview(exercise:level:) -> Int` =
  `previewReward(ledgerOf(activeProfile.clears), exercise, level, exerciseDifficulty(exercise))`.
- `spend(cost: Int) -> Bool` — `balanceOf(activeProfile.stars) < cost` →
  false; else bump `spent` by cost, return true. **The affordability check
  reads the live store synchronously** — this runs inside pointerdown-path
  code.
- `buy(_ option: CustomizationOption) -> Bool` — read active profile; let
  `owned = current-species owned.contains(option.id)`; if `!owned &&
  balance < option.cost` → false. Then inside `updateActive`, re-derive
  `already` from the passed profile (TS does; keeps the closure
  self-consistent): debit `spent` by `option.cost` only when `!already`;
  species slot becomes `(config: applyOption(config, option),
  owned: already ? owned : owned + [option.id], rev: newRev(device, now))`.
  Returns true (re-equip of an owned item is a free success). `applyOption`
  is the pure config-transformer (accessory: append-if-absent; color/style:
  set `colors[slot]`/`styles[slot]`) — it is also used by the shop try-on
  preview, so it lives as a free function next to the models, `public`, pure.
- `setConfig(_ next: MascotConfig)` and `setConfig(_ f: (MascotConfig) -> MascotConfig)`
  — TS's value-or-function union becomes two overloads. Stamps a fresh
  `rev = newRev(device, now)` on the species slot; the rev is computed
  **once, outside** the update closure (TS does — one timestamp even if the
  closure re-ran; in Swift the closure runs once anyway).
- `chooseSpecies(_ s: Species)` — `chosen = true`, `current = s`,
  `currentRev = newRev(...)`. Non-destructive: species map untouched.
- `createChild(name: String)` — append `child(name, DEFAULT_PROFILE, device, now)`,
  make it active. (Name trimmed, `"Joueur"` fallback, **no 14-char clamp**.)
- `selectChild(id: String)` — set activeId. (TS does not validate the id;
  port as-is.)
- `renameChild(id: String, name: String)` — trimmed; empty → no-op; stored
  as `trimmed.prefix(14)`; fresh `nameRev`; `touchedAt = now` on that child.
- `deleteChild(id: String)` — remove from children, clear activeId if it was
  active, and **tombstone**: `removed[id] = now()`. The comment is normative:
  without it the family's other device hands the child straight back.
- `switchChild()` — `activeId = nil` (welcome screen "Qui joue ?").

Sync trigger (port of the `useEffect`): a method
`func syncNow()` — guard `sync?.enabled == true`; `Task { }` around
`let merged = try await sync.syncOnce(local: roster)`; on success, back on
the MainActor, `if merged != roster { commit(merged) }` (value equality
replaces the TS reference check — same observable result: an unchanged
roster is not re-saved); on any error, swallow — "the device keeps playing
alone". Called from the **app layer** on launch and on `scenePhase == .active`
(the Swift equivalent of mount + `appStateChange.isActive`).
**Never called from a write path** — gameplay stays offline-first, and a
child mid-round must never wait on the network. The store itself exposes
`syncNow()`; the wiring to ScenePhase lives in App/ so ALCore stays
platform-free.

Concurrency note: `syncOnce` runs off-actor (network); the roster it merges
against is the value captured at call time, and the commit compares against
the *current* value. If a local write lands mid-sync, `merged != roster` may
overwrite the newer local write with the merge of the older snapshot —
**this race exists identically in the TS code** (`ref.current` is read at
call start, commit compares against the current ref). The TS code tolerates
it because the very next sync re-merges and counters are lossless; port the
same tolerance, do not "fix" it with locking. If desired, re-merge instead of
overwrite at commit time (`commit(mergeRoster(roster, merged))`) is a strictly
safe strengthening — note it in DECISIONS.md if taken; default is the
faithful port.

### 4.7 Wire + SyncClient (sync/client.ts)

`Wire.swift`:

- `toWire(_ r: Roster) -> WireRoster` — build `WireChild(id:touchedAt:profile:)`
  memberwise. Because `WireChild` has no name/nameRev/activeId properties,
  "stripping" is not an operation that can be forgotten — the type cannot
  represent the secret. (`activeId` additionally never leaves: "it is about
  this tablet, not the family".)
- `fromWire(_ w: WireRoster, local: Roster) -> Roster` — for each wire child,
  re-attach `name`/`nameRev` from the local roster's child with the same id;
  unknown child → `name: "Enfant"`, `nameRev: ZERO_REV` ("the moment this
  parent names them, that name wins forever" — zero stamp always loses).
  `activeId: nil` — never adopted from the wire. `removed` passes through.

`SyncClient.swift`:

- Keys: `attrape-lettres:household:v1`, `attrape-lettres:household-etag:v1`.
- `householdId() -> String?` = kv read. `joinHousehold(id)` = set household,
  set etag to `""`. `createHousehold() -> String` = mint uuid, join, return.
  No accounts, no email — the uuid + join code **is** the identity model, and
  why the server holds only opaque ids and integers.
- `enabled: Bool` = endpoint configured **and** household joined. Endpoint
  comes from injected configuration (the port of lazily-read
  `import.meta.env.VITE_SYNC_URL` — see §9 for the app-level decision).
- `syncOnce(local: Roster) async throws -> Roster` — exact algorithm:

  ```
  guard household && endpoint else { return local }
  var local = local
  for attempt in 0..<3 {
    let remote = try await transport.pull(household)
    let merged = remote != nil
        ? mergeRoster(local, fromWire(remote!.roster, local: local))
        : local
    switch try await transport.push(household, toWire(merged), remote?.etag) {
    case .ok(let etag): kv.set(ETAG_KEY, etag); return merged
    case .conflict:     local = merged        // pull their write, merge on top, retry
    }
  }
  return local   // three collisions: keep merged local state, try next resume
  ```

  Idempotent by construction (merge.ts guarantees repeats change nothing).
  On 412/conflict, **never force** — "the other phone's write is somebody's
  stars." Oddity, port as-is: the stored ETag is written but never read back
  by `syncOnce` (each attempt uses the etag from its own pull); it exists as
  state, keep it.

- `URLSessionSyncTransport` (app layer): `GET {endpoint}/household/{id}` —
  404 → `nil`; non-2xx → throw; else decode `WireRoster`, etag from the
  `ETag` response header (`?? ""`). `PUT` with `Content-Type:
  application/json` and `If-Match: <etag>` when etag non-nil; 412 →
  `.conflict`; non-2xx → throw; else `.ok(etag:)`. `credentials: "omit"` ⇒
  an ephemeral `URLSession` configuration with no cookies/credentials.
  Note: iOS ATS requires the endpooint be HTTPS — it already is in production.

### 4.8 Boot (main.tsx)

The Swift boot collapses: no `hydrateKv()` (UserDefaults is synchronous), no
splash-hold for data (the launch screen covers process start only), no
service worker. Remaining order for the App layer: install error reporting
(telemetry scope) → construct `ProfileStore` (synchronously builds the
roster, migrating if needed) → present UI → `store.syncNow()` on appear and
on `scenePhase == .active`. Live updates (updates.ts) do not exist in the
native port — code ships through the store (whole-app fact, out of scope
here, flagged in §9).

---

## 5. Invariant ownership

**Invariant 1 — feedback fires synchronously (this scope's share: nothing on
the read/write path may await).** Enforced by: `KVStore` protocol methods are
synchronous (no `async` in the signature — an adapter *cannot* be async
without failing to conform); `ProfileStorage` functions are synchronous and
total; `deviceId()` is a synchronous memoised read; every `ProfileStore`
mutation (`award`, `spend`, `buy`, …) is a plain synchronous `@MainActor`
method. What would break it: making `KVStore` async "because the backing
store is", or routing award/spend through a `Task`. The compile-level guard
is the protocol shape; the test-level guard is that ProfileStoreTests call
award/spend synchronously and assert the returned value and the resulting
balance in the same expression.

**Invariant 9 — never persist a bare running total.** This is the load-bearing
invariant of the scope. Enforced structurally, at four layers:

1. `PersistedProfile` (the only Codable profile shape) **has no `balance` or
   `ledger` property** — a stored total cannot be expressed in the persisted
   type. `StarCounters`/`ClearCounters` are the only star/clear storage.
2. `ProfileView` (which *does* carry `balance`/`ledger` for the UI) is
   **deliberately not Codable** — accidentally persisting or wiring the
   flattened view is a compile error.
3. `bump` is the only counter mutator, and it only writes the caller's own
   device slot; `mergeCounter` is per-key max, so a device's slot is only
   ever advanced by that device. Nothing in the API takes "set balance to N".
4. `mergeProfile`/`mergeChild`/`mergeRoster` are pure value functions with no
   clock (time enters only through `newRev(device:now:)` arguments), no
   storage and no network — which is exactly what makes the
   commutative/associative/idempotent property tests in §6 runnable on the
   host and the invariant checkable rather than asserted.

The cosmetic exceptions are exactly the TS ones: `config` + `current` +
`name` are LWW via `Rev` (losing one costs nothing), `owned` is a grow-only
union, `chosen` a grow-only OR. **The rule for any new persisted field** goes
in the `ProfileModels.swift` header verbatim from CLAUDE.md: accumulates on
two devices ⇒ `Counter`; cosmetic ⇒ value + `Rev`; a set ⇒ grow-only array;
and adding it means extending `mergeProfile`/`mergeChild` *in the same
change* with a two-offline-devices test.

What would break it: adding a convenience `var balance: Int` stored property
"for performance", making ProfileView Codable, or persisting `ledgerOf`
output. The spec makes all three either impossible or a loud diff.

**Invariant 10 — nothing identifying leaves the device.** Enforced
structurally: `WireChild` **has no `name` or `nameRev` field**, so stripping
is a compile-time property of the type, not a runtime `Omit`. `WireRoster`
has no `activeId`. `toWire` is a memberwise projection — there is no spread
operator to accidentally carry an extra field. `fromWire` proves the reverse
direction: names come only from the local roster.
The Swift equivalent of "a name cannot appear in a payload" lives in
`Tests/ALCoreTests/WireTests.swift`: encode a `WireRoster` built from a
roster containing `"Léa"`/`"Tom"`, assert the UTF-8 JSON contains neither
name, nor the substring `"nameRev"`, nor `"activeId"` — the same
serialised-bytes assertion as client.test.ts, kept even though the type
already guarantees it (it also guards the Codable conformance against a
future custom encoder regression). `deviceId()` is never referenced by the
telemetry module (telemetry is another scope; its allowlist test asserts it),
and the only network path in *this* scope is `SyncClient`, whose payload type
is `WireRoster`. What would break it: adding a name-bearing field to
`WireChild` (a loud, reviewable diff by design), or bypassing `toWire` by
encoding `Roster` itself — greppable, and preventable by making `Roster`'s
`Encodable` conformance internal-only if the app layer never needs it
(recommended: keep `Roster: Codable` — storage needs it — but the transport
protocol accepts only `WireRoster`, so the type system already refuses a raw
`Roster` push).

**Invariant 8 (shared — economy side).** All points still flow through
`sessionReward` (rewards scope): `award` is the only earner and it calls
`sessionReward`; `ledgerOf` **sums** clears across devices so cross-device
replay keeps decaying the curve. Owned here: the summing fold and the "prior
clears read before bump" ordering.

**Invariant 11 (adjacent).** Sync failure semantics uphold the same
philosophy: any thrown transport error is swallowed by `syncNow()` and the
device keeps playing alone; sync is never on a write path.

---

## 6. Test plan — all host-runnable (`swift test`, no simulator)

Everything in this scope is pure logic + a protocol seam; 100% of it runs on
macOS. Port every existing TS test case, plus property tests the TS suite
approximates by example.

**MergeTests.swift** (from merge.test.ts, case-for-case — the Léa/Dad/Mum/iPad
scenarios keep their names and comments):
- counter: fresher-per-device (10,13→13 not 23); both devices kept (10+3=13);
  commutative; idempotent; associative across three devices (=20);
  `bump` touches only its own slot.
- stars: cross-device spend (10−4+6=12); **overdraw floors at 0** (Σe=10,
  Σs=16 → 0) and both items survive the overdraw.
- clears: sum across devices (1+1=2) and `rewardFor(2) < rewardFor(0)`
  (rewards scope dependency — links the economy); independent keys untouched.
- LWW: later config wins from both sides; loser's `owned` still unioned;
  exact-timestamp tie agrees from both sides; `chosen` never un-picks;
  `mergeOwned(["a","b"],["b","c"]) == ["a","b","c"]` (order pinned).
- roster: sibling adoption; same-child merge (13); activeId never adopted;
  activeId cleared when its child tombstoned elsewhere; delete propagates;
  **delete refused when `touchedAt` later** (t=200 play survives t=50
  tombstone, 40 stars intact); tombstone transitivity to a third device;
  idempotence + membership agreement both directions.

**MergePropertyTests.swift** (new — the reason merge is pure):
seeded-RNG generators for `Counter`, `StarCounters`, `ClearCounters`, `Rev`,
`SpeciesProgress`, `PersistedProfile`, `Roster`; assert for ~1k random triples:
`merge(a,b) == merge(b,a)`, `merge(merge(a,b),c) == merge(a,merge(b,c))`,
`merge(merge(a,b),b) == merge(a,b)`, `balanceOf ≥ 0`, per-device counter
values never decrease under merge, `owned` never loses an element, tombstoned
ids never resurrect when `tombstone > touchedAt`. (mergeRoster associativity
holds because every leaf is; asserting it end-to-end is the point.)

**WireTests.swift** (from client.test.ts §wire): serialised `WireRoster`
contains no `"Léa"`, `"Tom"`, `"nameRev"`, `"activeId"`; ids and integers do
travel; `fromWire(toWire(local), local)` re-attaches name + stamp; never-seen
child gets `"Enfant"` + `ZERO_REV`.

**SyncClientTests.swift** (from client.test.ts §syncOnce): a `FakeServer`
actor with ETag semantics and an `interleave()` hook, behind `SyncTransport`;
InMemoryKVStore for household keys. Cases: no-op before joining a household
(and the server stays empty); first upload; two phones converge to 13 and
the server document's JSON never contains "Léa"; sibling arrives named
"Enfant" and `activeId` stays local; **conflict retry** (interleaved write →
412 → re-pull → all three devices' stars = 17); idempotent re-sync (still 10).
These are `async` tests; they still run on the host.

**ProfileStoreTests.swift** (from useProfile.test.tsx, against
`ProfileStore(kv: InMemoryKVStore(), device: fixed, now: controllable)`):
roster gate (empty start, safe default profile, balance 0); createChild
activates with species unchosen; switch/select round-trip; rename trims,
clamps to 14, ignores empty; award pays `REWARD_CURVE[0]` then decays;
full-perfect adds difficulty bonus; difficulty-0 pays 0 but ledgers the
clear and previews 0; spend fails then succeeds; buy debits once and
re-equips free; non-destructive species switch (fox is fresh, unicorn intact
with stage/colour/owned, stars global); sibling isolation; delete writes a
`removed[id] > 0` tombstone into the **saved v4 JSON** (assert via the kv
double); persistence round-trip through a fresh store on the same kv.

**MigrationTests.swift**: seed the kv double with the exact V3/V1 JSON
fixtures from useProfile.test.tsx (byte-for-byte, including `"Léa"`,
`balance: 17`, `ledger: {"read-image:1": 2}`); assert stars=17 exactly,
ledger position preserved (`preview == rewardFor(2)`, next award pays
`rewardFor(2)`), look/items intact, **v3 blob left in place**, migrated
stars merge additively with another device's migration (17+6=23); v1 promotes
the single cat into its slot with its 5 stars; v2 path; empty-children v3
falls through; `id: ""` gets a fresh id (`||` semantics); unknown species key
dropped; unknown `current` string → `.unicorn` (documented deviation, §7).

**CapacitorInteropTests.swift**: fixtures of real blobs as the Capacitor
build writes them (v4 roster, device id, household id, shop-seen) decode
through the loose layer; a roster encoded by Swift decodes through the same
loose layer (self-interop); key-prefix resolution (`CapacitorStorage.` — §8)
reads and writes the expected UserDefaults keys — testable on the host
against `UserDefaults(suiteName:)` or the InMemory double.

**KVStoreTests / DeviceIdentityTests**: get/set/remove round-trip; deviceId
mints once, memoises, `_reset` re-reads; a preset id is honoured.

Simulator-only (not this scope's gate, listed for completeness): nothing.
This entire scope tests on the host; the only device-facing code is the
UserDefaults adapter and URLSession transport, both thin enough that their
logic (prefixing, status-code mapping) is host-tested behind the protocols.

---

## 7. Risks

1. **`Species` enum vs corrupted blobs.** JS tolerates any string in
   `current`/species keys (normalise drops unknown species keys but *keeps*
   an unknown `current`). Swift's enum cannot represent garbage. Decision
   taken here: unknown `current` → `.unicorn` during normalisation; unknown
   species keys dropped (same as JS). Only reachable from a hand-corrupted
   blob; behaviourally invisible for all data the app has ever written. If
   the whole-app types scope chooses raw-String species instead, this note
   dissolves.
2. **Rev tie-break string ordering.** JS `>=` compares UTF-16 code units,
   Swift compares Unicode scalars. Identical over ASCII, and device ids are
   ASCII by construction — but a mixed household where one side ever ran a
   port that changed id shape could theoretically disagree on a tie. Keep id
   minting ASCII-only forever.
3. **The mid-sync local-write race** (§4.6) exists in the TS code and is
   ported as-is; counters make it self-healing, but an implementer seeing it
   may be tempted to "fix" it with locking or to skip the equality check —
   either changes observable timing. Port faithfully; the optional
   `commit(mergeRoster(roster, merged))` strengthening needs a DECISIONS.md
   entry if taken.
4. **React StrictMode double-invocation is gone.** `initialRoster` runs once
   in Swift where React may run it twice; it is pure (apart from possibly
   minting ids for damaged blobs), so no behaviour difference — but tests
   that counted invocations in TS have no analogue.
5. **`useEffect` cleanup / listener lifecycle** → ScenePhase in the App
   layer. Risk: forgetting the resume trigger entirely (there is no
   compile-time forcing function). Mitigation: name it in the App-layer
   checklist; ProfileStoreTests assert `syncNow()` semantics so only the
   wiring can be missed.
6. **JSON number width.** All persisted numbers are integers today. If any
   future JS writer ever emitted a float (it does not), strict `Int64`
   decoding would fail the whole blob and read as "no roster". The loose
   layer should decode `Millis` via `Int64` with a `Double` fallback
   (truncating) to keep `loadRoster` total in the same spirit as the TS
   try/catch.
7. **Fire-and-forget write durability.** kv.ts tolerates a lost write
   (private mode). UserDefaults is *more* durable (cfprefsd flushes even on
   kill), so the port is strictly safer; noted so nobody adds
   `synchronize()` calls chasing a phantom.
8. **Oddities ported as-is** (do not improve): `createChild` does not clamp
   the name to 14 chars but `renameChild` does; `award` bumps `earned` by 0
   creating a zero-valued key; the stored ETag is write-only; `selectChild`
   does not validate the id; `award` returns computed points even with no
   active child (while writing nothing).
9. **`VITE_SYNC_URL` has no direct Swift analogue** — see §9; wiring it
   wrong (hard-coding, or shipping it absent) silently disables sync, which
   is designed to fail quiet. The `enabled` flag should surface in the
   parent-facing settings UI scope so a misconfiguration is visible.

---

## 8. Reading the existing Capacitor build's data — in-place update

**Achievable: yes, losslessly, with zero migration code beyond a key prefix.**

On-disk format today (iOS Capacitor build): `@capacitor/preferences` with no
`group` configured (checked `capacitor.config.ts` — the plugin block sets
only SplashScreen/CapacitorUpdater) uses its default group
**`CapacitorStorage`** and stores every pair in **`UserDefaults.standard`**
under the key `CapacitorStorage.<key>` — i.e. the app's own preferences
plist, `Library/Preferences/fr.dappit.attrapelettres.plist`. Values are the
raw JSON strings kv.ts wrote. Concretely, after an in-place update the new
binary can synchronously read:

```
CapacitorStorage.attrape-lettres:roster:v4        the roster JSON
CapacitorStorage.attrape-lettres:roster:v3        (older installs)
CapacitorStorage.attrape-lettres:profile:v2 / v1  (older still)
CapacitorStorage.attrape-lettres:shop-seen:v1
CapacitorStorage.attrape-lettres:device:v1        ← keeps counters accruing under the same device id
CapacitorStorage.attrape-lettres:household:v1     ← keeps the family joined
CapacitorStorage.attrape-lettres:household-etag:v1
(+ licensing/telemetry keys, other scopes, same prefix)
```

Preconditions: same bundle id (`fr.dappit.attrapelettres`) — an App Store
update in place satisfies it; UserDefaults is not entitlement-gated.

**Migration path — recommendation: adopt the Capacitor prefix permanently.**
`UserDefaultsKVStore` maps every logical key `k` to physical key
`"CapacitorStorage." + k`, reads and writes. Consequences:
- Zero migration step, zero dual-read window, nothing to get wrong.
- **Bidirectional**: it is the storage.ts forward-migration philosophy applied
  across the platform boundary — if the rollout ever had to fall back to a
  Capacitor build, that build still finds its data exactly where it left it.
- The device id, household membership, and every star survive the update
  with no ceremony; the roster then flows through the ordinary
  v4→(v3→v2→v1) loader untouched.

Rejected alternative: one-time copy from prefixed to bare keys. It adds a
migration flag, a dual-read fallback, and breaks the rollback story, for the
cosmetic benefit of prettier key names nobody sees.

One follow-up for the App layer: on first native launch there is nothing to
await (no `hydrateKv`), so the migration cost is a handful of synchronous
UserDefaults reads during `ProfileStore.init` — microseconds; no splash
logic needed.

---

## 9. Decisions needed at whole-app level (not decidable inside this scope)

1. **Ownership of shared model types.** `Species`, `MascotConfig`,
   `CustomizationOption`, `ExerciseId` are used by content/levels/UI scopes
   and by persistence. Proposal: one `ALCore/Models.swift` owned by the
   types/content agent; this spec's `ProfileModels.swift` owns only the
   sync-safe types (Counter, Rev, StarCounters, ClearCounters,
   SpeciesProgress, SpeciesMap, PersistedProfile, ChildProfile, Roster,
   ProfileView). Needs a cross-agent agreement so the two files are written
   once, not twice.
2. **Observation framework**: `ObservableObject`/`@Published` vs Swift 5.9
   `@Observable`. Affects every store in the app (entitlement provider too);
   must be uniform. This spec is agnostic; the surface is identical.
3. **Sync endpoint configuration** (the `VITE_SYNC_URL` analogue): Info.plist
   key vs build-setting-injected constant vs xcconfig per configuration.
   Whatever is chosen must keep "endpoint absent ⇒ sync disabled, silently"
   and be test-stubbable (the client takes it injected either way).
4. **KVStore key-namespace policy** — §8's "keep the `CapacitorStorage.`
   prefix" affects licensing (`persist.ts`), telemetry and updates scopes,
   which share kv.ts today. Must be decided once, in DECISIONS.md, and the
   `UserDefaultsKVStore` shared by all scopes.
5. **Live updates do not exist natively** (updates.ts / CapacitorUpdater is
   web-tech-only under DPLA §3.3.1(B)). Someone must record in DECISIONS.md
   that the native port ships content/VO changes through store releases —
   this changes the release cadence assumption baked into the OTA recipes in
   CLAUDE.md.
6. **Android/back-compat of the wire format.** If Android stays Capacitor
   while iOS goes native, the household document is written by both TS and
   Swift. This spec keeps field names and semantics byte-compatible, but the
   rule "wire format changes require a cross-platform migration plan" should
   be stated once, app-level, before anyone adds a field to `WireRoster`.
