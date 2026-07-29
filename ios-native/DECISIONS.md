# Native iOS port — decision log

Running record of the technical calls made while porting Attrape-Lettres from
Vite/React/TypeScript to native Swift/SwiftUI. Behaviour target: **identical to
the PWA**. Where the two could differ, the PWA wins and the difference is
recorded here.

Read top to bottom; entries are appended in the order they were decided.

---

## D0 — Scope and ground rules

**Behaviour is frozen.** The PWA is the specification. No feature is added,
removed or "improved" during the port. The 11 invariants in the repo's
`CLAUDE.md` are carried over verbatim and each one is assigned an owner module
below.

**Source of truth for reading is the working tree of `main`, not `HEAD`.** At the
time of the port a large amount of the app was uncommitted or untracked
(`licensing/`, `sync/`, `kv.ts`, `device.ts`, `telemetry.ts`, `updates.ts`,
`SyllableGridExercise.tsx`, `Onboarding/Paywall/ParentalGate`). A git worktree
checks out committed state only, so every agent reading TypeScript reads from
the original repository path. The worktree holds only new Swift output.

**The port is additive.** Nothing under `src/` is modified. The web app keeps
shipping while this exists in parallel.

---

## D1 — Layering: one SwiftPM package, three targets, host-testable

```
ios-native/
  Package.swift
  Sources/ALCore    pure logic  — no SwiftUI, no UIKit
  Sources/ALArt     SwiftUI     — SVG path runtime, mascots, exercise icons
  Sources/ALUI      SwiftUI     — exercises, components, screens
  App/              thin Xcode wrapper around the package
```

The package declares `platforms: [.iOS(.v17), .macOS(.v14)]`. That is the whole
point: **SwiftUI is cross-platform, so `swift test` runs the geometry and the
logic on the host with no simulator and no Xcode project**. Path parsing,
level building, the reward curve, the sync merge, the entitlement clock — all
verified in seconds on the command line.

Anything genuinely iOS-only (haptics, audio session category, StoreKit) sits
behind a protocol declared in `ALCore` with a platform implementation injected
at the app layer, so the pure targets never import UIKit.

The Xcode project is deliberately thin — an app shell that depends on the local
package. Almost no code lives in it, so almost nothing is trapped behind a
`.pbxproj` merge conflict.

**Swift language mode 5, not 6.** Tools version is 6.0, but every target sets
`swiftLanguageMode(.v5)`. This is a port, not a greenfield concurrency exercise;
strict `Sendable` checking on 15k lines of freshly translated code buys noise,
not safety, because the app is a single-actor UI with one small networking path.
`@MainActor` is applied where it is genuinely true. Revisit once the port is
green — flipping a target to `.v6` is a one-line change.

---

## D2 — SVG: parse the `d` string at runtime, do not re-author the geometry

The census that drove this:

| | count |
|---|---|
| `<path>` | 218 |
| …of which use `d={` template interpolation `}` | **127** |
| `<circle>` / `<ellipse>` / `<rect>` / `<line>` | 117 / 66 / 32 / 4 |
| `<linearGradient>` / `<radialGradient>` / `<stop>` | 14 / 2 / 42 |
| `<clipPath>` / `<mask>` / `<use>` / `<defs>` | 4 / 1 / 2 / 13 |
| `<filter>` / `<pattern>` / `<animate>` / `foreignObject` | **0 / 0 / 0 / 0** |
| arc commands (`a` + `A`) | 25 |
| transforms | 5, all `rotate` |
| standalone `.svg` files | 5 |

Two facts decide this:

1. **127 of 218 paths are computed at runtime from props.** They are not assets.
   No static asset format — PDF, asset-catalog SVG, anything — can hold them, on
   any platform. So "does iOS support SVG files" is the wrong question; the
   answer would not have helped.
2. **The vocabulary that is actually used maps 1:1 onto SwiftUI.** Zero filters,
   zero patterns, zero SMIL. Everything present has a direct `Path` / `Shape` /
   `LinearGradient` / `.clipShape` / `.mask` / `.rotationEffect` equivalent.

Therefore: **ship a small, exhaustively tested `d`-string parser in `ALArt`** and
port the drawing code by copying the `d` template verbatim into a Swift string
interpolation.

```
tsx:    <path d={`M ${x} ${y} q ${w/2} ${-h} ${w} 0 Z`} />
swift:  Path(svg: "M \(x) \(y) q \(w/2) \(-h) \(w) 0 Z")
```

Why this and not build-time codegen into `Path` builder calls:

- **Geometry cannot drift.** The control points are never retyped, so a port bug
  cannot silently move a dragon's eye three points left. The only thing that can
  be wrong is the parser, and the parser is one file with a golden test per
  distinct `d` string in the repo.
- **The parametric case stays parametric.** Codegen would have to reimplement the
  interpolation logic anyway; that is the part with the bugs in it.
- **Cost is a non-issue.** `Shape.path(in:)` runs on layout, not per frame.
  Animation is `.rotationEffect` / transform, which never re-parses. Parsed
  results are memoised by string for the static cases.

The parser must cover: `M m L l H h V v C c S s Q q T t A a Z z`, implicit repeat
of the previous command, comma-or-whitespace separation, negative-number eliding
(`10-5`), and the endpoint→centre arc conversion from SVG spec appendix F.6.5
(the 25 arcs are the only fiddly part; out-of-range radii must be scaled up per
F.6.6, not clamped).

The 5 standalone `.svg` files go through the same parser, converted once to Swift
source at port time.

---

## D3 — Verification: pixel-diff the two apps, not eyeball them

The port has a mechanical acceptance test:

1. Render each mascot × stage × wardrobe combination in the PWA, screenshot it.
2. Render the same combination in the Swift build, `simctl io screenshot`.
3. Diff.

Both sides produce PNGs. This is what makes "behaviour is identical" a claim that
can be checked rather than asserted, and it covers exactly the surface where a
hand port is most likely to be silently wrong.

---

<!-- Entries below are appended by the port phases. -->

## D4 — A fourth target, `ALPlatform`, not "code in the app"

D1 said the platform implementations get "injected at the app layer". Two specs
pushed back for the same reason: the StoreKit adapter, the URLSession transport,
the UserDefaults store, the audio engine and the haptics all need a home, and if
that home is the Xcode target then they sit outside `swift build`, inside the
`.pbxproj`, and CI never compiles them.

So: `Sources/ALPlatform`, depending on ALCore, imported only by `App/`. `App/`
keeps `@main` and nothing else. **`ALCore` must never import StoreKit** — the
whole point of the seam is that `swift test` runs the entitlement machine on a
Mac with no store, no network and no signing.

## D5 — Touch-down is one primitive, app-wide: a zero-duration long-press recogniser

Invariant 1 is the hardest thing in the port. SwiftUI's `Button` fires on touch
**up**; `.onTapGesture` is a recogniser with arbitration delay. Neither is
acceptable — the sound and the press animation have to happen at the instant the
finger lands, synchronously, before any state update.

Two candidates came back. `engines.md` proposed a `UIControl` hosting a
`UIHostingController` (`touchDown` is exactly the right event). `shell.md`
proposed `UILongPressGestureRecognizer(minimumPressDuration: 0)` and acting on
`.began`.

**Chosen: the recogniser**, in one file, `ALUI/Interaction/TouchDown.swift`, used
by every interactive gameplay element — tiles, listen buttons, slot-remove
buttons, child cards. It reaches touch-down at the same instant with no child
view-controller plumbing, no hosting-view sizing, and no accessibility
passthrough problem. The `UIControl` design stays documented as the fallback if
arbitration inside a scroll view ever misbehaves.

The rule that matters more than the choice: **one primitive, decided once**. Two
different touch paths in the same app is how one of them silently regresses.

## D6 — Injectable time, and it is not called `Clock`

The trial clock, the LWW stamps in `sync/merge`, and the reward curve all need
the current time. Any of them reading `Date()` directly is untestable, and for
licensing that is not a style opinion — a 14-day offline grace whose clock cannot
be advanced in a test is a 14-day offline grace nobody has ever verified.

One protocol, `TimeSource`, in `ALCore/Platform`, injected at the root.
`SystemTimeSource` in production, `MutableTimeSource` in tests. Deliberately not
named `Clock`: the stdlib already has one.

## D7 — One `KVStore`, synchronous, keeping the Capacitor key namespace

Five call sites read `kv.ts` today: licensing, telemetry consent, `device.ts`,
`storage.ts` and the sync client. They get exactly one protocol, one
`UserDefaults` suite, one namespace.

**Synchronous by signature.** `func string(_ key: String) -> String?`, not
`async`. This is invariant 1 expressed in the type system: an adapter that wants
to await simply cannot conform. It is the same reason `kv.ts` hydrates once at
boot and keeps every read synchronous thereafter.

**Keys keep the `CapacitorStorage.` prefix and the `attrape-lettres:*` names,
byte for byte.** `@capacitor/preferences` on iOS writes to `UserDefaults` with
that prefix, so a family who updates in place from the Capacitor build to the
native one keeps their roster, their stars and their mascot. Losing that data
would be indistinguishable, to a six-year-old, from the app deleting their
progress. Verified by `CapacitorInteropTests`, which seeds a real Capacitor-shaped
blob and reads it through the Swift loader.

## D8 — Shared model types have exactly one owner

`Species`, `MascotConfig`, `CustomizationOption`, `Mood`, the growth stages and
`ExerciseId` are needed by content, levels, persistence, the mascot art and the
shop. Written twice they will drift.

One home: `ALCore/Domain/`. `Mascot.swift` owns the mascot vocabulary,
`ExerciseID.swift` owns `ExerciseId`. `ALCore/Persistence/ProfileModels.swift`
owns only the sync-safe storage types (`Counter`, `Rev`, `PersistedProfile`, …)
and imports the rest.

## D9 — Randomness is injected and seedable, and sessions are never built in a view initialiser

`Math.random()` is all over the round builders — shuffles, the fill-blank gap,
the mixed-form choice, the letter-match direction. Ported as an injected
`RandomSource` (default `SystemRandomNumberGenerator`), because a round builder
that cannot be replayed cannot be tested, and these builders carry the whole
difficulty curve.

There is **no shared global RNG instance**, and that is deliberate. In React the
session is built once, in a lazy `useState` initialiser. In SwiftUI an expression
in a `@State` default is evaluated *every time the view struct is initialised*
even though only the first value is kept — so building a session there silently
builds and discards one on every parent re-render. With a system RNG that is
invisible; with a seeded one it destroys reproducibility. **Rule: never call a
`build…Session` from a view's `init` or a `@State` default.** Build it in
`.task {}` or in an `@Observable` model created once.

Related trap, same class: Swift's `Set` is unordered and per-process randomised,
while JS `Set` is insertion-ordered. Three sites depend on it (the syllable
bank's fallback element, the distractor distribution, the mixed-intruder trap
order). They go through an `orderedUnique(_:)` helper. Miss one and the result
is nondeterministic *under a fixed seed*, which reads as a flaky test rather than
a port bug.

## D10 — Content stays authored Swift literals, not a bundled JSON resource

The alternative was `resources: [.process("Content")]` and a decode at launch.
Rejected:

- **The recipe survives.** CLAUDE.md says "append to `LETTER_WORDS` in
  `content.ts` — that's it; pools derive automatically." With literals that
  becomes "append to `Content.letterWords`" — the same single edit, and the
  compiler still checks the shape, the image key and the enum cases. With JSON
  the recipe grows a second step and a typo in an `img` key turns from a build
  error into a runtime `nil`.
- **Invariant 4 says content is authored.** Making it decodable makes it
  *loadable*, and loadable things fail at runtime.
- **ALCore stays resource-free**, which is what makes it instant and pure.
- **The comments are the point.** The most valuable lines in `content.ts` are the
  French notes explaining *why* — the MAI-SON/POIS-SON `/zɔ̃/` vs `/sɔ̃/`
  collision, why PAPI-LLON had to go, why « auto » was a bad anchor. JSON cannot
  hold them. **Every one of those comments is copied across.**

Compile time is the only real argument against, and it is mitigated: annotate
every array's type explicitly, give every struct an explicit memberwise `init`,
one table per file. If a file ever gets slow the fix is splitting the array into
`static let` parts, not moving to a resource.

## D11 — `ExerciseId` raw values are a persistence contract, frozen

`Rewards.ledgerKey(exercise:level:)` produces `"\(rawValue):\(level)"`, and that
string is the key of the per-device clear counters that survive on disk and cross
the sync wire. Renaming an `ExerciseId` case's raw value orphans every existing
profile's history. The raw values are frozen; the Swift case names may be
idiomatic, the raw values may not change.

## D12 — Telemetry is closed by construction, not by review

Invariant 10 says the allowlist has no string escape hatch. In TypeScript that is
maintained by discipline plus a test. In Swift it becomes structural: an `enum
TelemetryEvent` whose raw values are the wire names, and a `TelemetryProps`
struct with typed fields and **no `String`-keyed dictionary anywhere in the
type**. There is no API that accepts an arbitrary key, so no reviewer has to
notice one being added.

`deviceId()` is not referenced anywhere under `Telemetry/`, and the byte-level
test survives the port: encode a payload built from a roster containing "Léa" and
"Tom", assert the JSON contains neither, nor `"nameRev"`, nor `"activeId"`.

## D13 — Live updates do not survive the port, and that is a real loss

`updates.ts` ships a JS bundle over the air. The DPLA §3.3.1(B) carve-out permits
that precisely because it is *interpreted code run by WebKit*. A native Swift app
has no such carve-out and no such mechanism.

What survives: the version guard (`minNative`, now meaning "the app version this
content payload requires") and `versionAtLeast`.

What does not: same-day shipping of a copy fix, a layout fix, a new level, or a
VO re-bake. **After the port those cost a store release.** This is the one
capability the port removes, and it changes the release-cadence assumption baked
into CLAUDE.md's OTA recipes.

`RemoteContent.swift` is written as a specified but **unimplemented** seam. A
signed JSON payload carrying word lists, levels and VO would be legitimate — it
is data, not code — but shipping it is a new capability with its own App Review
exposure and its own signing-key operational burden, and behaviour is frozen for
this port. Build it deliberately, later, with the allowed/forbidden line written
down first.

## D14 — One source of truth for reduced motion

Invariant 6 requires `prefers-reduced-motion` to be honoured by the mascot, the
confetti and the sheen. Three independent `@Environment(\.accessibilityReduceMotion)`
reads are three chances to forget one. A single `ReduceMotionSource` protocol in
ALCore, injected, so the render harness and the tests can force it on.

## D15 — ALArt draws through one `GraphicsContext` canvas with an explicit CTM stack

The alternative was composing SwiftUI views per SVG element. The canvas wins, and
the reasons are all bugs it prevents rather than elegance:

- **`rotate(a cx cy)`.** SVG's rotate takes an optional anchor and 4+ of the
  app's 19 rotations use one. As a `.rotationEffect` that anchor becomes a
  hand-computed `UnitPoint`, and getting it wrong moves a horn a few points with
  no error anywhere. Concatenating the CTM is the same arithmetic SVG does.
- **Gradients in `objectBoundingBox` units.** The ground glow's radial gradient
  is anisotropically stretched by the ellipse's bounding box; a naïve
  `RadialGradient` draws a circle. The scaled-CTM trick reproduces it.
- **Stroke scaling.** `scaleEffect` blurs raster and scales strokes wrongly; a
  scaled CTM scales geometry and stroke width together, as SVG does.
- **Draw order.** SVG is painter's order, encoded as JSX statement order
  including mid-list conditionals. A canvas transcribes statement by statement;
  any "tidying" of a `ZStack` reorders pixels.

Same substrate for the exercise icons, so there is one drawing model in the app.

Two things the canvas costs, both handled: `Canvas` clips to its bounds and the
top-stage wings and halo deliberately overflow the 100-unit box, so the container
must not clip; and the mascot's motion must stay transform-only on a static rig,
never a per-frame redraw, or invariant 2 is gone.

## D16 — One `fluid(min:vw:max:)`, viewport = screen width

The PWA sizes nearly everything with CSS `clamp(min, vw, max)`. Reimplemented per
view it will drift. One utility in `ALUI/Design/Fluid.swift`, with "viewport
width" defined once as the screen width (not the container width), matching the
PWA's `vw` on both iPhone and iPad. The D3 pixel diff catches any divergence.

## D17 — `voKey` hashes UTF-16 code units, exactly

The clip bank is keyed by a hash of the utterance. Iterating `unicodeScalars` or
`Character`s instead of `utf16` yields a plausible-looking hash that misses every
emoji-bearing clip — and the app then degrades silently to text-to-speech. No
crash, no log, just a different voice. Golden vectors lock it.

Same class, same file: the French copy uses **both** apostrophes — typographic
`’` in the hub names and mode hints, ASCII `'` in "Écoute le son et trouve
comment il s'écrit". Copied byte for byte. Normalising them would silently
re-key those clips.

## D18 — The licence is not a counter and never enters the merge

Invariant 9 turns anything a child accumulates into a per-device `Counter`. The
licence accumulates nothing, and `persist.ts` is deliberately separate from
`storage.ts` for exactly that reason: losing a profile is a tragedy, losing the
licence is one `restore()` tap. It must **not** be added to `sync/merge.ts`.

If household-level entitlement is ever wanted — and the Android Family Library
gap is a real reason to want it — it belongs on the sync backend keyed by
`familyId`, not in the merged profile blob.

## D19 — The render harness is a shipping-disabled entry point, not a test target

D3's pixel diff needs to render an arbitrary `(species, stage, wardrobe, mood,
reduced-motion, size)` combination on demand. That is driven by launch arguments
through `ALUI/Harness/RenderHarness.swift`, which is also what `Mascot.stories.tsx`
was for — Storybook has no Swift equivalent and the harness plus Xcode Previews
replaces it.

## D20 — `@Observable`, uniformly

iOS 17 is the deployment floor, so `@Observable` is available and there is no
reason to carry `ObservableObject`. The surface is identical either way; what
matters is that `ProfileStore`, `EntitlementModel` and `Telemetry` all use the
same one. All three are also `@MainActor`.

## D21 — Correction to D2's census, and what it changes (nothing)

D2 quoted "transforms: 5, all `rotate`". That count predated the dragon. Measured
again against the current working tree:

| | count |
|---|---|
| `transform` attributes | **32** |
| …`rotate` (several with an explicit `cx cy` anchor) | ~19 |
| …`translate` | ~12 |
| `matrix` / `skewX` / `skewY` | **0** |
| `fill-rule="evenodd"` | **0** — every fill is nonzero, already SwiftUI's default |
| `stroke-width` / `linecap` / `linejoin` / `dasharray` | 130 / 39 / 29 / 3 |
| path strings captured for the golden corpus | **189** (94 static, 95 template) |

D2's conclusion is unchanged — still no filters, no patterns, no SMIL — but the
transform surface is larger than the log implied, which is the direct reason D15
mandates an explicit CTM stack rather than per-element view modifiers. The
anchored rotations are the specific silent bug being designed out.

One thing the parser needed that D2 did not anticipate: Swift prints doubles as
the shortest round-trip form, which can be exponent notation (`1e-05`). Since the
`d` strings are built by interpolating computed numbers, the parser accepts
exponents. It already does, and `SVGPathGrammarTests` covers it.

## D22 — ALCore is complete and green; what the port proved and what it did not

509 tests in 80 suites, on the host, in 0.43 seconds. Every TypeScript suite is
ported at or above parity:

| TS suite | tests | Swift |
|---|---|---|
| `levels.test.ts` | 80 | 99 |
| `merge.test.ts` | 24 | 35 |
| `useProfile.test.tsx` | 18 | 26 |
| `entitlement.test.ts` | 16 | 17 |
| `telemetry.test.ts` | 13 | 21 |
| `rewards.test.ts` | 12 | 16 |
| `client.test.ts` | 10 | 15 |
| `updates.test.ts` | 5 | 16 |

**Mechanically verified, each behind a test proved non-vacuous by mutation:**
`ledgerKey` matches the string `ClearCounters` is keyed by, end to end through
award → counter → persisted bytes → sync wire, for all 17 exercises (D11);
exactly one line in ALCore assigns `stars.earned` and it is fed by
`sessionReward` (invariant 8); nothing under `Telemetry/` references
`deviceId()`; `WireChild` carries only `id`, `touchedAt`, `profile` (invariant
10); `PersistedProfile` has no balance and no ledger (invariant 9); and ALCore's
57 files import only `Foundation` and `Observation` — nothing else, ever.

That last check found a real hole: the scan an agent had written banned StoreKit
and UIKit but not SwiftUI or Network. It bans them now.

**Content is byte-perfect.** Every string literal in `content.ts` was diffed as
a multiset against the Swift tables: zero divergences. The only TS-only strings
were the import and the four `img/*.svg` URLs, now the `ImageKey` enum. The
mixed `’`/`'` apostrophes survived, which matters because they are VO lookup
keys (D17).

**Not proved here, and honestly so:** invariants 1, 2, 6 and 7 are owned by
files that do not exist yet. ALCore's *contribution* to them is in place and is
structural rather than conventional — `KVStore` and every `ProfileStore`
mutation are synchronous by signature, so an adapter that wants to await cannot
conform (invariant 1); `AudioEngine.say` returns `Bool` and can neither throw
nor hang (invariant 3); `ReduceMotionSource` exists to be injected (invariant 6).

**Two gaps carried forward into the art phase.** `src/mascot/catalog.ts` — the
growth maths, the anchors, the ids and the catalog data — has no Swift home yet,
and `VO/Utterances.swift` depends on its costs. Per D8 that data belongs in
`ALCore/Mascot/`, not in ALArt, because the shop and the profile read it too.
The integration agent declined to invent a shape the mascot port would then have
to contradict, which was the right call.
