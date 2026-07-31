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
apps/game-ios/
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

## D23 — Rasterisation is testable on the host too, so the pixel loop is not simulator-only

D3 assumed the only way to see pixels was `simctl io screenshot`. It is not:
`ImageRenderer` runs on macOS, so a `Canvas` replaying a draw list can be
rasterised and read back inside `swift test`, in milliseconds, with the rest of
the suite.

This matters because the draw-list assertions have a blind spot they cannot see
past. `SVGCanvas` composes its own CTM and hands the matrix to
`GraphicsContext.concatenate`; if those two composed in opposite directions,
every mascot in the app would be wrong and every draw-list test would still
pass, because the draw list would be identical. Eight rasterisation tests close
that gap: translation direction, composition order against an SVG transform
list, an anchored rotation landing on its anchor, a bounding-box gradient
staying elliptical, group opacity compositing once rather than double-darkening
an overlap, clipping, alpha masking, and stroke width scaling with the CTM.

Each was mutation-tested. Dropping the transform in `fill` fails three of them;
collapsing the bounding-box gradient to a circle fails the gradient one with
`alpha 0 at x=16` — which is precisely the silent bug D15 was written to
prevent, reproduced on demand.

The simulator screenshot loop still earns its place for whole screens, fonts and
the real device pipeline. But geometry no longer has to wait for it.

## D24 — Gradients: every one in the app is `objectBoundingBox`, because none says otherwise

Measured while writing the paint layer: the app declares **no `gradientUnits`
attribute anywhere**. SVG's default is `objectBoundingBox`, so all 16 gradients
resolve their coordinates as fractions of the filled element's bounding box and
are stretched by that box's aspect ratio.

That turns the case D15 flagged as a risk into the only case there is. A radial
gradient on the ground glow's 4:1 ellipse is an ellipse in SVG and a circle in a
naïve `RadialGradient` port. So bounding-box units are the **default** in
`SVGPaint`, and `.userSpaceOnUse` is the opt-out rather than the other way
round.

Also measured, and simplifying: the two radial gradients declare no `cx`/`cy`/
`r` (so SVG's 50%/50%/50% applies) and there are **no `fx`/`fy` focal offsets
anywhere**. `GraphicsContext` cannot express a focal offset, so rather than
accept one and silently ignore it, the focus is absent from the type.

## D25 — D2 is now checked, not just asserted: 174/174 path skeletons matched

`scripts/d2-skeleton-diff.mjs` extracts every path-shaped string literal from
`Sources/ALArt`, replaces each `\(…)` interpolation with a placeholder, and
looks for the same skeleton in the TypeScript.

```
Swift path literals             : 174
  matched in the PAIRED TS file : 174
  matched only in another file  : 0
  no counterpart anywhere       : 0
```

**Zero retyped geometry.** Two things about how that number was reached matter
more than the number:

**Set-membership matching was not good enough.** The first version of the check
also reported 174/174 — and still passed when the trailing `Z` was deleted from
the cat's ear, because only 143 *distinct* skeletons exist across the whole app
and a mangled path lands on somebody else's shape by coincidence. The check now
requires the match to be in the *paired* file (`Cat.swift` ↔ `Cat.tsx`) and
names the coincidence when it is not. It was then verified to fail on a retyped
coordinate (`20.5`→`20.6`), a changed command (`Q`→`C`), and the dropped `Z`.

**It has a blind spot, stated in the script header rather than hidden.**
Whatever is inside `\(...)` is invisible to it. Changing `headR * 0.4` to
`headR * 0.42` moves a real ear and this check will not see it. That is what the
D3 pixel diff is for, and it is now the only thing that can catch that class.

Twelve TypeScript paths have no Swift counterpart. Each is individually
explained and all are unreachable: `dragonParts.tsx` exports 32 components and
`Dragon.tsx` imports 20; the other 12 are referenced exactly once in all of
`src/`, by their own `export function` line. Also unreachable are `SpadeTail`'s
`tip === "bolt"` arm (the caller casts `tailTip` to `"spade" | "club" | "flame"`)
and the `gem`/`gems` props, which are never passed. They are an **annotated
allowlist**, not a silent skip, so a thirteenth gap becomes a finding instead of
disappearing into a familiar list.

## D26 — Two real defects the art phase surfaced, both worth remembering

**The corpus generator had silently lost its emit block.** `extract-svg-corpus.mjs`
was writing `{statics, templates, indirect}` while `SVGPathCorpusTests` decodes
`{count, paths:[…]}`. Running the regeneration command in the header comment —
which the port instructions told an agent to do — would have broken the golden
test that guards every path in the app. Restored, and the reconstruction was
verified byte-identical to the committed corpus before overwriting it.

**`CapacitorInteropTests` was flaky across processes, not within one.** It failed
once, then passed 73 runs. The cause: three *fixed* `UserDefaults` suite names
plus `removePersistentDomain` in `init`. `UserDefaults` is host state, not
process state, so two concurrent `swift test` runs wipe each other's fixtures.
Reproduced standalone — two processes lose ~6% of reads (25/400, 22/400), one
process loses 0/400. Fixed by making the suite name per-PID; no assertion
changed. Worth remembering because the port runs several agents building the
same package at once, and this failure mode looks exactly like a real bug.

## D27 — Mascot node counts are frozen

Ink-laying leaf counts per species, default look, growth stages 0→9:

```
unicorn  30  34  41  49  50  55  58  81  94 108
cat      31  35  39  43  47  65  67  68  71  97
fox      37  41  45  49  61  73  79 108 112 144
rabbit   37  41  45  48  50  51  54  57  60  72
dragon   28  32  33  41  47  52  57  74  84  91
```

Now a snapshot test, so a refactor that silently drops a part fails loudly.
Before this only the dragon had any rig test at all.

The measured envelope over all 50 combinations is x ∈ [−55.04, 155.04],
y ∈ [−80.27, 112.98]. The low y is the unicorn's stage-9 halo — legitimate
overflow, which is why D15 forbids the canvas from clipping at the viewBox.

## D28 — The baked voice-over is staged, not committed twice

845 `.m4a` clips, 17 MB, already live once in this repo under `src/vo/clips/`,
keyed by `voKey(text)`. Copying them into `apps/game-ios/Sources/ALPlatform/Resources/`
would put a second 17 MB in git that is byte-identical to the first and would
drift the moment `pnpm vo:build` re-bakes a clip.

So the audio resource is **staged**: `apps/game-ios/scripts/stage-vo.sh` links the
clips into `Sources/ALPlatform/Resources/vo/` before a build, and that folder is
gitignored. What *is* committed is the clip **manifest** — the list of keys.

The distinction that makes this safe rather than merely tidy: the test that
matters is "every utterance the app can speak has a clip", and that test reads
the manifest, not the audio. It therefore runs on a fresh checkout, in CI, and on
a machine that has never staged a single byte of audio. The clips are needed to
*hear* the app; they are not needed to *prove* it is complete.

`Package.swift` declares `resources: [.process("Resources")]` on `ALPlatform`
with a committed `.gitkeep`, so the manifest ships and an unstaged build still
compiles — it just falls back to `AVSpeechSynthesizer`, exactly as the web falls
back to `speechSynthesis` when a clip is missing. Invariant 3 all the way down:
absent audio degrades, it never blocks.

## D29 — Reduced motion is gated where the WEB gates it, and nowhere else

The port initially gated all four interaction animations on `ReduceMotionSource`.
Two agents flagged it as a deviation and both were right: my own instructions and
`ARCHITECTURE.md` §3's invariant-6 row asked for "every `Anim.*` entry point",
which is not what the app does. Measured, per file:

| animation | web | gated? |
|---|---|---|
| `pop` | `usePopFlourish.ts:29` reads the media query | **yes** |
| `pulse` | `GameFrame.tsx` writes `motion-safe:animate-pulse` — Tailwind's own gate | **yes** |
| confetti | `useConfetti.ts:22` | **yes** |
| mascot idle/cheer | `Mascot.tsx:60` | **yes** |
| dashboard sweep, sheen, shop press/pop | `Dashboard.tsx:55`, `index.css:88`, `shop/anim.ts` | **yes** |
| **tile press** (130 ms squish) | `Tile.tsx:59` — a bare `el.animate(press, …)` | **NO** |
| **tile shake** (300 ms wobble) | `Tile.tsx:61` — likewise | **NO** |

`Tile.tsx` contains no `matchMedia` call at all. CLAUDE.md's invariant 6 scopes
itself the same way — "mascot + confetti" — and the distinction is a real one: a
squish and a wobble are *how a tap feels*, not ornament. Suppressing them would
leave a child who needs Reduce Motion tapping a dead slab, which is the opposite
of an accessibility win.

`Anim.press` and `Anim.shake` therefore take **no `ReduceMotionSource` parameter
at all**. The absence of the parameter is the guard: the gate cannot be
reintroduced by reflex, only by changing a signature and every call site.
Mutation-checked — re-suppressing `press` fails four assertions across three
tests.

The general lesson, and the reason this entry exists rather than a silent fix:
**a spec that overstates an invariant is more dangerous than one that omits it**,
because agents implement it faithfully and the result looks principled.

## D30 — An empty SwiftPM resource bundle breaks the iOS build, and only the iOS build

`ALUI` carried `resources: [.process("Resources")]` from the scaffold over a
folder containing nothing but `.gitkeep`. SwiftPM emitted a bundle with an
`Info.plist` and one hidden file, and `codesign` refused it:

```
AttrapeLettres_ALUI.bundle: bundle format unrecognized, invalid, or unsuitable
Command CodeSign failed with a nonzero exit code
```

`swift build` and `swift test` never saw it — they do not sign. The whole
host-testing strategy (D1) rests on the host tier catching almost everything, and
this is a clean example of what it structurally *cannot* catch. The declaration is
removed until ALUI has a real resource; the comment in `Package.swift` says to
re-add it *with* one, never ahead of one.

Corollary for the build order: an iOS `xcodebuild` pass belongs in every phase
gate, not only at the end. It is the only tier that signs.

## D31 — The audio session is `.playback` + `.duckOthers`: the game is now audible on silent

**Flagged for the user, not settled by me.** This is a deliberate behaviour change
from the PWA and the only one in phase 4.

The Capacitor WebView followed the ring/silent switch, so a muted iPad meant a
silent game. Native, `AVAudioSession` category `.playback` ignores the switch and
`.duckOthers` lowers other apps' audio rather than stopping it.

Why: the game is voice-led end to end — every prompt, every letter, every syllable
is spoken. On silent, the PWA is not a quieter game, it is an unplayable one, and
a six-year-old cannot diagnose a hardware switch. Every voice-led kids' app makes
the same call.

Why it is nonetheless flagged: a parent who silenced a tablet deliberately did not
expect it to start talking, and "behaviour identical to the PWA" was the standing
constraint. The change is confined to
`Sources/ALPlatform/Audio/AudioSession.swift` — one category constant. Reverting
to `.ambient` restores the web's behaviour exactly and breaks nothing else.

## D32 — There are two `ReduceMotionSource` implementations, and the layering forces it

D14 asked for one. There are two, and neither is redundant:

- `ALPlatform.SystemReduceMotion` observes
  `UIAccessibility.reduceMotionStatusDidChangeNotification`, so it notices a
  mid-session change. The app root injects this one.
- `ALUI.SystemDefaultReduceMotion` reads the switch on every access but has no
  notification. It is the `EnvironmentKey` default.

ALUI may not import ALPlatform (ARCHITECTURE §1), so it cannot use the observing
one as its default — and the alternative default, "assume motion is fine", would
silently animate for a child who asked the OS not to. A non-observing honest read
beats an observing unreachable one.

They were originally named `SystemReduceMotion` and `PlatformReduceMotion`, which
is exactly the kind of near-identical pair someone injects the wrong half of.
Renamed so the fallback says it is a fallback.

## D33 — Phase 4 inventory, and what is proved versus merely written

```
ALCore       63 files   7715 lines   (unchanged)
ALArt        16 files   5673 lines   (unchanged)
ALUI         18 files   4120 lines   interaction, design tokens, components
ALPlatform   17 files   2622 lines   audio, adapters, composition root
tests        79 files  16221 lines   969 tests, 161 suites, 1.1 s on the host
```

Proved on the host: the touch-down ordering contract; every `Anim` keyframe
against the TSX numbers; `fluid`/`clamp` at the boundaries and three device
widths; the French copy scalar-exact, including the mixed `’`/`'`; the
miss-cooldown swallow window; the star strip greying on the *first* wrong tap of a
round; the confetti particle system stepped deterministically under a seeded
`RandomSource`; `say()`'s supersession and watchdog contracts; the 845↔845 clip
bijection (which also proves the ten clips still at HEAD are deleted orphans, not
a gap); the `CapacitorStorage.` key strings; every StoreKit failure path resolving
playable; and — asserted on the transmitted bytes — that a roster containing "Léa"
travels with no name and no device id.

NOT proved, and honestly outstanding:

- **No SwiftUI view body is exercised.** Every component's rules were extracted
  into plain types (`TilePress`, `MissCooldown`, `StarStrip`, `FitLineFit`,
  `ConfettiSystem`) and those are tested; the `body`s themselves are not. That was
  the right trade — the rules are where the invariants live — but it leaves
  layout, the rendered accessibility tree, and everything CSS-approximate (shadow
  blur ≈ radius/2, `line-height` → `lineSpacing`) to the D3 pixel diff.
- **The real UIKit recogniser** is proven by configuration assertions plus the
  core-logic tests, not by driving actual touch delivery; recogniser state is not
  externally settable. Scroll-view arbitration is reasoned from documented UIKit
  behaviour, not observed.
- **Everything the device owns**: tap→sound latency (the ~2 ms budget the whole
  audio design exists to protect), the silent switch, ducking, call interruption,
  StoreKit's `Transaction.updates`, and the reduce-motion notification name.
- **Nothing is wired into `App/` yet.** `LiveAudioEngine`, `PlatformEnvironment`,
  the `scenePhase` fan-out (`refresh` on active, `flush` on background) and the
  `ALSyncURL` / `ALTelemetryURL` Info.plist keys all exist and are all
  unreferenced. Sync and telemetry are therefore inert in a build made today —
  fail-silent by design, which is precisely why it needs writing down rather than
  trusting a later test to notice.

## D34 — The confetti drew on top of the game. It should draw behind it.

Two engine agents, working on different files, independently reported the same
defect in `GameFrame.swift`. Its header comment said:

> the children carry no z-index. So confetti draws OVER the game content

The children do carry a z-index. Every one of the nine exercise roots is
`relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2`, and
`Finished` is `relative z-[41]` too, against the canvas's `zIndex: 40`. So on the
web the burst passes **behind** the tiles, the mascot and the word picture.

That is not cosmetic. Confetti falling behind the game keeps a six-year-old's eye
on the word they just read; confetti splattering over it takes the eye off the
one thing the reward is meant to reinforce. Wrong feeling, at the exact moment
the app is trying to land something.

**Why it survived a whole phase, and what to do about it.** Z-order is invisible
to every assertion that does not actually draw. The draw list, the view type, the
modifier chain and all 1115 other tests were equally happy either way. So the fix
ships with `GameFrameZOrderTests`: two `ImageRenderer` rasterisations that put an
opaque red overlay and an opaque green content block in the same rectangle and
read back which colour won — one asserting content beats confetti, one asserting
confetti still beats the background, because burying the overlay too deep would
hide the burst entirely and the first test alone would not notice. Mutation-checked:
restoring the old ordering fails with `px.g → 56 > px.r → 255`.

This is the same lesson as D23, now paid for twice: **`ImageRenderer` on the host
is the cheapest tier that can see paint order, and any decision about what covers
what needs a test in that tier.**

A footnote on the fix: the header and the children are both `z-[41]` on the web,
tied, with the tie broken by DOM order in the children's favour. SwiftUI has no
tie, so the numbers are 40 / 41 / 42 and the 42 encodes the tie-break rather than
any web value. It only shows where a centred `Finished` on a short screen reaches
up into the header's band.

## D35 — `EngineHost`: the nine views take one parameter set, and it holds nothing a caller cannot supply

Four agents wrote the nine views in parallel and produced two shapes: three took
`audio` / `time` / `award` loose, six took a whole `EngineDeps` and then did this:

```swift
var wired = deps
wired.fireConfetti = { system.fire() }   // whatever the caller passed is dropped
```

Both work. Neither is acceptable at the hub, which has to dispatch over all nine —
and the second is the worse of the two, because a parameter that is accepted and
silently ignored reads at the call site as if passing it did something.

The asymmetry is real, not an accident: `fireConfetti` must be *this run's*
`ConfettiSystem.fire` (the TSX calls `useConfetti()` once per exercise) and the
view owns that system because the view also has to place the canvas. So the fix
splits the type by who can actually supply what. `EngineHost` carries the four
things a caller has — audio, clock, `award`, the announce sleep — and
`host.deps(fireConfetti:)` completes the set inside `.task`, where the system
finally exists. All nine views now take `host: EngineHost`.

The test that checked "the caller's fireConfetti must not win" was deleted as a
question, because `EngineHost` has no such member for a caller to pass — the
guarantee moved from an assertion into the type. What replaced it is the
direction that still can go wrong: that wiring the confetti in does not quietly
drop or substitute one of the four things the caller *did* pass.

## D36 — Two web quirks ported faithfully rather than fixed

Both were found by reading the TSX closely, both are flagged rather than
corrected, because behaviour is frozen and neither is mine to decide.

**The 350 ms announce timer is not cancelled by a pick.** Every engine speaks a
prompt 350 ms after a round becomes current. A *correct* tap inside that window
lets the prompt supersede the success line; the success line settles `false`, the
ok-gate fails, and the round does not advance. A fast child can stall the game and
has no idea why. Reproduced in the model, documented in `RoundRunner.swift`'s
header, and identical to the PWA. Fixing it is a two-line change — cancel the
announce task in `pick` — but it is a behaviour change.

**The syllable-grid vowel gap tests truthiness, not equality.** The TSX writes
`flash ? round.target.vowel : ""`, rather than comparing with the target.

> **CORRECTION (D45).** The sentence that stood here — "so *any* flash, including
> a wrong pick's, fills the gap with the target's vowel" — was **wrong**, and it
> was wrong in the direction that invents a defect rather than hides one. Both
> `SyllableGridExercise.tsx` and `SinglePickModel.pick` assign `flash` only
> AFTER the wrong-answer early return, so a miss never sets it and the two forms
> agree on every reachable input. Nothing was ever revealed to a child on a wrong
> tap. The port now compares anyway, for the reason given in D45.

## D37 — Phase 5 inventory

```
ALUI         31 files   8526 lines   + interaction, tokens, components, 3 models, 9 views
ALPlatform   17 files   2622 lines   unchanged this phase
tests        89 files  19158 lines   1117 tests, 183 suites, 0.8 s on the host
```

Nine exercises, three state machines, one view each, no forks: `AssembleView` is
one view for three `SyllableMode`s, `SyllableGridView` one for both combinatoire
drills, `SpellSyllableView` one for five hub rows. Per-engine differences are
descriptor data — judge key, prompt and success lines, headline, listen text,
preview-lock asymmetry, seeded and locked slots — so the one-engine-per-family
rule cannot be broken by a later edit without deleting a factory.

Not proved, and unchanged from D33's list: no SwiftUI `body` is exercised, because
`swift test` has no renderer. Every rule was extracted into a plain type and
tested there — `TilePress`, `MissCooldown`, `StarStrip`, `FitLineFit`,
`ConfettiSystem`, the three models, and per-engine `…Metrics`/`…Stage` values — but
layout, vertical rhythm, dashed-border rhythm, the CSS-blur-to-shadow-radius
approximation and the wrap points of every tile row remain for the D3 pixel diff.
Two of the four agents also flagged a one-frame `runner == nil` placeholder: D9
forbids seeding in a `@State` default, so the session is built in `.task` and the
first frame renders an empty stage. Nothing is interactive or audible in it, but
whether it is perceptible on device is a simulator question, not a host one.

## D38 — Phase 6 stopped at the weekly account limit, mid-flight

All four phase-6 agents died on the same error:

```
You've hit your weekly limit · resets Aug 2 at 2pm (Europe/Paris)
```

Five of the eleven planned files had already been written and are committed:
`Onboarding.swift`, `ParentalGate.swift`, `WhoIsPlaying.swift`, `Shop/Meter.swift`,
`Shop/Picker.swift` — 2058 lines. They compile, the package is green at 1117
tests, and the iOS build still succeeds, because nothing constructs them yet:
there is no router. They are inert additions.

**They have ZERO tests, and that is the thing to know before touching them.**
Every other line in this port has a host test behind it. These five files do not,
because the agents that wrote them were killed before the test-writing step.
The code is well-shaped for it — the rules are already extracted into pure types
(`GateChallenge`, `ParentalGateModel`, `RosterAction`, the meter arithmetic, the
picker's two variants) precisely so they could be tested — but nothing yet proves
any of them right. Spot-checked by hand, not by machine:

- `ParentalGate` is fail-open by construction, which is the property invariant 11
  needs: the challenge is generated, not loaded, so there is no read that can fail
  and no state that can lock. A wrong answer costs one retry.
- `WhoIsPlaying` emits no telemetry at all and touches no transport, so the child's
  first name has no path off the device from this screen (invariant 10).

Both readings come from the code and its comments; neither is asserted anywhere.

Not started: `Dashboard`, `Paywall`, the shop itself (`ShopView`, `ShopItem`,
`GrowthCard`, `ItemPreview`, `ShopAnim`), `HubView`, `Router`, `RootView` (still
the placeholder star), and the App composition root. The consequence of that last
one is unchanged from D33: **sync and telemetry are inert in a build made today**,
the audio engine is never constructed, and nothing wires `scenePhase`.

Resuming needs no archaeology — the phase-6 workflow script is on disk and its
four agent prompts are unchanged and re-runnable:

```
.claude/…/workflows/scripts/al-ios-phase6-screens-wf_1665df0b-ea3.js
```

## D39 — Three flaky tests, and what each one actually was

The suite passed 12 of 15 runs when phase 6 landed. Intermittent failures are
usually dismissed as "test flake"; these were three different things, and only
one of them was a test problem.

**1. A data race in `TileIDAllocator` — a real bug in shipped code.**
`LevelsSpellSyllableTests.givesEveryTrayTileAUniqueId` handed out a duplicate id
about one run in six. Its fixture is fully seeded and deterministic, so the
duplicate could not have come from the generator — it came from
`TileIDAllocator.shared`, a process-wide mutable counter doing an unsynchronised
`counter += 1` while Swift Testing built rounds from several threads at once.

In the shipped app every builder is reached from a `@MainActor` model, so the
race is currently unreachable there. That is not a property anything enforces,
and the consequence if it were reached is not cosmetic: tile ids are SwiftUI
identities, so a collision reuses a view and carries press/shake layer state onto
the wrong tile (invariants 1 and 2), and the undo logic matches a slot back to
its tray tile BY ID. Now lock-protected and `@unchecked Sendable`. One
uncontended lock per tile, of which a round allocates a handful.

**2. `EntitlementModelTests` — untestable production code, flaky since phase 4.**
`beginTrialTakesEarliest` failed about one run in eight. The test waited with
`for _ in 0..<20 { await Task.yield() }`. `Task.yield()` offers the CURRENT
executor a chance to run something else and promises nothing about a task on
another thread, so "enough yields" is not a quantity that exists; it only started
failing when the suite passed a thousand tests and the parallel load grew.

Polling for the effect would have fixed three of the four call sites and not the
fourth, because three of them assert that something did NOT change — and no
condition becomes true when nothing happens. The real cause was that
`beginTrial()` fired an unowned `Task` with no handle, so its completion was
unobservable. It now keeps that task in `trialTask` and the tests await it.
**A test cannot be made honest against work it has no way to wait for**; when
that happens, the seam belongs in the production code, not in a cleverer poll.

**3. `RosterPrivacyTests` — a genuine test bug, in the most important test.**
Same fixed-yield pattern, now waiting on the actual condition. Worth stating what
this flake was NOT: at no point did a child's name appear in a payload. The test
guards with `#expect(!transport.pushed.isEmpty)` before searching the bytes,
precisely so "nothing was pushed" can never be mistaken for "nothing identifying
was pushed". An invariant-10 test that passed vacuously would be far worse than
one that failed loudly.

15 consecutive full runs green after all three.

## D40 — `ALSyncURL` / `ALTelemetryURL` ship empty, exactly as the web ships them

Every phase since D33 recorded that sync and telemetry are inert because nothing
sets these Info.plist keys. Both are now declared in both build configurations,
with empty values.

Empty is the right value, not a placeholder: `.env.example` has
`VITE_TELEMETRY_URL=` and `VITE_SYNC_URL=` with no values and there is no `.env`,
so **the PWA also ships with both disabled**. The native port was never regressing
anything — it was matching. `PlatformConfiguration.normalised` already treats `""`
as absent, reproducing TS truthiness, so an empty key means "no endpoint" rather
than "an endpoint that is the empty string".

What the change buys is that the mechanism is now wired and verified end to end:
filling either value is a one-line build-setting edit, not a code change.

## D41 — Phase 6 verified on the simulator, not just on the host

```
ALCore       63 files   7729 lines
ALArt        16 files   5673 lines
ALUI         45 files  15189 lines
ALPlatform   17 files   2622 lines
tests        99 files  24397 lines    1423 tests, 246 suites, 0.85 s
```

The app was installed on an iPhone 17 Pro simulator and driven through the gate
chain by seeding `CapacitorStorage.attrape-lettres:onboarded:v1` and a v4 roster —
which incidentally exercised D7's migration contract for real, since the app read
keys written from outside by `defaults write`.

Onboarding renders with its consent control OFF and the trial terms above the
button; the first-run picker follows, with its primary action disabled until a
name is typed; the species picker draws all five mascots from ported SVG paths.
Their closed eyes were checked against `parts.tsx:41` rather than assumed —
`sleepy && mood === "idle"` is the authored stade-0 baby look, and every card is
`Tout neuf`.

**One layout defect found that no host test could see:** with the keyboard up on
the first-run picker, keyboard avoidance slides the whole stage upward and the 👋
is clipped by the status bar and the notch. On the web there is no notch and no
keyboard inset, so nothing in the TSX corresponds to it. It needs a safe-area
fix in the picker, and it is exactly the class of thing the D3 pixel diff and a
simulator pass exist to catch.

## D42 — The notch defect is fixed by scrolling, and the fix is gated

D41's clipped 👋 is fixed. `KeyboardScroll` (`ALUI/Components/KeyboardScroll.swift`)
wraps a subtree in a `ScrollView`, which turns SwiftUI's keyboard avoidance from
a **translation** into a bottom **content inset** — so the stage top stays
anchored and the focused field is still reachable.

That is also the faithful answer rather than merely a working one. The web has
no keyboard inset, but mobile Safari shrinks the visual viewport and scrolls the
focused field into view; the page top is never lost, you can always scroll back
to it. `.ignoresSafeArea(.keyboard)` would have matched the web's *static*
layout more literally and been wrong in landscape, where the 402 pt height minus
a keyboard leaves the name field underneath it with no way to see what you type.

**Verified by reproduction, not by inspection.** Same seeded state, same device,
two builds:

| build | result |
|---|---|
| `enabled: isCreating` | 👋 fully clear of the status bar, field and button visible |
| `enabled: false` (control) | 👋 sliced by the Dynamic Island, stage top off-screen |

**The `enabled:` gate is load-bearing and must not be "simplified" away.** A
`UIScrollView` sets `delaysContentTouches`, which holds a touch to see whether it
becomes a pan — that is invariant 1 ("feedback fires on `pointerdown`") with
extra latency bolted on. So the wrapper goes only over subtrees that own a
keyboard AND carry no `LayerHost`. On `WhoIsPlayingView` the split is exact: the
form branch is plain SwiftUI buttons, and the screen's only `LayerHost` is in
`ChildCard`, in the mutually-exclusive grid branch. `KeyboardScrollTests` asserts
`enabled: false` is the pixel-exact identity, so dropping the gate fails the
suite. **Do not promote this to `RootView`.**

### Two host-tier limits, both found the hard way

**`ImageRenderer` cannot see inside a `ScrollView`.** Measured, not assumed: the
same red block sampled bare gives `(255, 56, 60)`; inside a `ScrollView` it gives
`(0, 0, 0)` — a valid `CGImage` containing nothing. So the enabled path is not
host-testable at all, and the obvious "wrapped renders like bare" test cannot be
written. **The D19 render harness inherits this**, and it matters more there than
here: `ShopView` scrolls too, and a pixel differ that does not know this will
happily report two blank images as a perfect match. The harness must assert its
reference renders are non-empty before comparing them.

**Never hand a pixel buffer to `#expect`.** `#expect(bare == wrapped)` on a
1.12 MB `[UInt8]` does not fail — it *hangs*, because the macro captures its
operands for failure diagnostics and reflecting a million-element array never
finishes. The full suite went from 0.85 s to a hard timeout, which reads exactly
like a deadlock in the code under test and is not. Compute the comparison first
and pass the `Bool`; that is why `GameFrameZOrderTests` only ever hands the macro
three `Int`s. (A third trap for the harness: a bare greedy `Color` inside a
`ScrollView` resolves to infinite height and hangs the renderer outright — bound
every stub on the scroll axis.)

Related operational note: `timeout` kills `swift test` but NOT its
`swiftpm-testing-helper` child. Eight orphans accumulated during this
investigation and held the test bundle, producing hangs that had nothing to do
with the code. `pkill -9 -f swiftpm-testing-helper` before trusting a repeat run.

1424 tests, 247 suites, 0.995 s.

## D43 — A local StoreKit configuration, so the purchase path is testable at all

`App/Configuration.storekit` declares both products — `…unlock` at 9,99 € and the
price-0 `…trial14` non-consumable that App Review 3.1.1 prescribes — and the
scheme references it from its `LaunchAction`. Storefront `FRA`, so the price
label comes back formatted as the screens expect.

Without it there is no way to exercise `purchase()`, `restore()` or `beginTrial()`
anywhere: the products do not exist in App Store Connect, so `Product.products(for:)`
returns empty and every path collapses to `.unavailable`. A local configuration
needs no paid account and no App Store Connect record.

Note what this does NOT change: with no configuration the app is still correct,
because invariant 11 is fail-open and `beginTrial()` stamps `trialStartedAt`
locally with no network. The trial and the whole game work with a dead store;
only the purchase button is inert.

**Unverified from the CLI, deliberately flagged.** A scheme's StoreKit
configuration is injected by Xcode's run action; `simctl launch` does not apply
it, so nothing here proves it loads. The JSON parses and the scheme's relative
path resolves to the file — that is all that has been checked. It takes effect on
⌘R from Xcode.

## D44 — What was NOT changed, and why

**`ParentalGate` is a plausible sibling of D41 and was left alone.** It owns the
only other keyboard (`.numberPad`) and its card is centred rather than pinned, so
avoidance shifts it half the inset instead of the full amount — arithmetic says
it clears the status bar on a 17 Pro, and it has no `LayerHost`, so the same
wrapper would be safe. But no bug was reproduced there, and the gate cannot be
reached from the CLI (it needs taps, and `simctl` has no input). An unverified
change to a screen with a working layout is not a fix. Flagged, not touched.

**The D36 quirks stay quirks.** The uncancelled 350 ms announce timer and the
truthiness-not-equality vowel gap are faithful ports of PWA behaviour, and
behaviour is frozen. They are reported for a decision, not fixed by reflex.

**D31's audio session is still `.playback`.** It is a deliberate deviation
(audible on silent, unlike the PWA), already flagged, and reverting is one
constant — but that is a product call, not a defect.

## D45 — The four flagged calls, decided

The user ruled on every open product question. Recorded here with what each one
cost, because two of the four turned out not to be what D31/D36 said they were.

| # | Question | Ruling | Change |
|---|---|---|---|
| 1 | Audio session `.playback` (audible on silent) | keep | none |
| 2 | The 350 ms announce timer stalls a fast child | **fix** | 3 engines |
| 3 | The vowel gap tests truthiness | **fix** | 1 line, no behaviour change |
| 4 | « OK » / « Annuler » / « Supprimer » | keep | none |

### 2 — the fast-child stall, fixed in all three engines

`SinglePickModel`, `TwinsModel` and `AssemblyModel` now cancel the pending
announce on the pick that completes a round. The stall was real and nasty
precisely because it was silent: the prompt spoke over the success line, the real
`say` returns `false` when cut short, every advance is gated on that `false`, and
the round stranded with `locked == true`. No error, no feedback, and only for
children quick enough to answer inside 350 ms — the ones doing best.

Deliberately NOT cancelled anywhere else. A miss leaves the prompt pending, which
is right: the round is still unanswered, so its instruction is still current. The
cancel goes on the branch that has been answered correctly, which is exactly the
branch that gates on `ok`.

**Verified by mutation, and the first attempt failed that check.** Each of the
three tests was re-run against the un-fixed model; each must fail. `holdSays()`
is what makes them mean anything — it pins `idx` while the timer fires, and
without it the announce's own `idx == expected` guard masks the bug. The
`SinglePickModel` test initially PASSED against the unfixed model: two bare
`Task.yield()`s after releasing the timer were not enough for the resumed
announce task to reach its `say`, so it asserted a negative that had not had time
to become true. A negative assertion is only as strong as the wait in front of
it. All three now wait on a positive signal (`pendingSayCount`) and then yield 50
times, and all three fail under mutation.

**This is a deviation from the PWA, which still has the bug.** It is authorised
and marked `[DEVIATION, authorised]` at each site. Fixing the TSX is a two-line
change in each engine and belongs on a web branch, not this one — flagged.

### 3 — the vowel gap: no defect, a guard anyway

**D36's description of this was wrong and is corrected above.** Re-reading both
`SyllableGridExercise.tsx` and `SinglePickModel.pick`: `setFlash`/`flash = key`
run only after the wrong-answer early return, so `flash` is non-nil if and only
if the pick was correct, and then it equals the target. Truthiness and equality
agree on every input the app can produce. No child was ever shown the answer on a
miss.

The comparison went in regardless, because *why* it was harmless matters: it was
harmless only by virtue of a guard three files away, in a different type. If
`flash` ever starts carrying a wrong pick — to highlight what the child tapped,
say — the truthy form silently begins revealing the answer on a miss, in a game
whose invariant 3 is that a wrong tap costs nothing and reveals nothing. The test
pins the unreachable case for that reason and says so, so nobody deletes it as
dead weight.

Cost: one line, zero behaviour change, verified by mutation (reverting to
truthiness fails the test).

1427 tests, 247 suites, ~1.0 s.

### Housekeeping, learned this session

`$SCRATCH/bak` from an earlier session still held five ALArt files. A wildcard
restore (`cp $SP/bak/*.swift Sources/ALUI/Engines/`) dumped all of them into
`Engines/`, and the build failed with `cannot find type 'SVGCanvas' in scope` —
an error that points at ALArt and has nothing to do with ALArt. Back up to a
freshly created directory, restore by explicit filename, and treat a sudden
unrelated-looking compile error after a restore as a misplaced-file symptom.

## D46 — The species picker scrolls (bug 1 of three reported from the device)

`SpeciesPickerView`'s stage is pinned to `PickerMetrics.minHeight` and the card
list grows with the species catalog. On a phone the FIFTH companion sat below the
screen edge with no way to reach it: the dragon could not be chosen at all. On
the web the document scrolls, so the question never arose — this is a port
artefact, not a ported behaviour.

`KeyboardScroll` became `PageScroll` (`alPageScroll`), because the two callers
want the same thing for what only look like different reasons: on the web the
page always scrolls, so neither a keyboard inset (D42) nor a long list can put
content permanently out of reach.

**What is verified and what is not.** The fix builds, the suite is green at 1430
tests, and the picker was installed and photographed on the simulator: it renders
correctly and does not crash. The DRAG itself is unverified — `simctl` has no tap
or swipe input and `osascript` has no assistive access on this machine, so no
scroll gesture can be issued. The reachability claim rests on the code, not on a
photograph of the dragon.

**An unmeasured cost, flagged rather than waived.** The picker cards are
`LayerHost`s, and a `UIScrollView` sets `delaysContentTouches`, which holds a
touch to see whether it becomes a pan — invariant 1's exact enemy. The shop has
always scrolled `LayerHost` tiles, so this is not a new class of risk, but it is
not measured either: no host test and no `simctl` session can time a touch-down.
Exercise screens remain off limits to `PageScroll` regardless.

### A correction to D42

D42 presented "`ImageRenderer` cannot draw `ScrollView` content" as a new
finding. It was a RE-discovery: `HubView.swift:275` already documents it and
already works around it, by keeping the scrolled content in a separate
`HubStage` view that the raster tier can see. The measurement in D42 stands;
the novelty does not, and the hub's pattern is the one to copy. `PickerView`
should get the same split when it next needs raster coverage — today neither
`PickerTests` nor `WhoIsPlayingTests` rasterise, so wrapping those two screens
hollowed nothing out. That was checked, not assumed: a `ScrollView` silently
turns any existing raster assertion into a comparison of two blank images.

### Bugs 2 and 3 are NOT fixed

The shop-scroll crash and the correct-answer crash are still open. What has been
ruled out by measurement rather than reading:

- **not the shop's drawing.** A new sweep (`ShopTileRenderTests`) rasterises
  every catalog item for every species at the locking stage, the freeing stage
  and stage 9, plus every factory look, plus the owned/worn branch. All pass. A
  `LazyVGrid` only builds what scrolls into view, so the failing tile would have
  been the one nothing ever evaluated — it now is evaluated, and it draws.
- **not the confetti**, which is the one thing unique to a correct answer:
  `colorIndex` cannot exceed the palette (`int(below: 2^53)/2^53 < 1`), and the
  cull loop walks DESCENDING, so it cannot index past the end.
- **not the SFX synth**: the sample rate is `outputFormat.sampleRate > 0 ? … :
  48_000`, so the `precondition` cannot fire, and `sfxNodes` is `max(1, voices)`,
  so the round-robin cannot divide by zero.
- **not the wrap arithmetic** in `GameFrame` or `EndButtons`: both keep indices
  and sizes in the same array.

What that leaves is the iOS-only tier the host suite cannot reach at all —
`LayerHost`'s `UIHostingController`, `TouchDown`'s recogniser, Core Animation
completions, and the audio session. Both crashes involve a `LayerHost` whose
content changes (a lazily-built shop tile; a tile that flashes on a correct
pick), which is a hypothesis and not a finding.

**The missing tier is UI testing.** There is no XCUITest target, so nothing in
this repo can tap. That is why three device bugs arrived by hand and why two of
them cannot be reproduced here.

## D47 — The first-tap crash: a mono buffer scheduled into a stereo connection

A device crash log named it exactly, and it is the worst possible location for a
bug in this app:

```
-[AVAudioPlayerNode scheduleBuffer:atTime:options:completionHandler:]
  → +[NSException raise:format:] → objc_exception_throw → abort()
← GameAudioGraph.play(_:) ← LiveAudioEngine.pop() ← SinglePickModel.pick(_:)
← TilePress.pointerDown ← TouchDownCore.began()
```

`build()` connected the SFX player nodes with `format: nil`. That does **not**
mean "adapt to whatever arrives" — it means "use the source node's current output
format", and for an `AVAudioPlayerNode` that has never held a buffer that is the
engine's standard format: **stereo, at the hardware rate** (2 ch, 44 100 Hz as
measured). `renderBuffers` renders **mono** (1 ch, 48 000 Hz). Scheduling a
1-channel buffer into a 2-channel connection raises an ObjC exception, and an
ObjC exception in Swift is an uncatchable `abort()`.

So the app died on the first tap of any tile — on `pop()`, which fires on EVERY
pick, correct or wrong. (The bug was reported as "when I give the correct
answer"; the stack shows it was simply the first tap.) Invariant 1 puts that call
before everything else on the feedback path, which is exactly why it took the
whole app down.

**The file already contained its own fix, for the other node.**
`ensureVoiceFormat` disconnects and reconnects the voice node with the clip's
real format before scheduling, and `ClipPlayer` calls it immediately before every
`scheduleBuffer`. The SFX half never got the same treatment. `ensureSfxFormat` is
now its sibling, and the SFX nodes are attached in `build()` but connected only
once the buffer format is known.

The mixer converts, so this format need not match the hardware at all — only the
buffers. That is also why a route change cannot resurrect it: the connection
stays consistent with what is scheduled into it, whatever the hardware does.

**`play` now asks the NODE, not our bookkeeping.** `outputFormat(forBus:)` is the
value `scheduleBuffer` validates against, so a future graph that records one
format and wires another goes quiet instead of aborting. One property read on the
tap path is a fair price for never again turning a six-year-old's tap into a
crash — audio may fail, it may never take the game down (invariant 3).

### Why 1434 tests missed it, and what now catches it

Nothing ever constructed the real graph. `AVAudio*` sat behind the `SfxPlaying`
protocol and every test used a double; `AudioSfxTests` checks the SAMPLES, which
were always right; and `isReady` is false on a Mac with no audio device, so even
a test that called `play` would have returned at the first guard.

The assertion that catches it needs no device and no running engine — it compares
the format the ENGINE reports for the node's output bus against the format the
buffers carry. Verified by mutation: restoring `connect(…, format: nil)` fails it
with the true failure mode rather than a proxy —

```
buffer.format → 1 ch, 48000 Hz  ==  actual → 2 ch, 44100 Hz, deinterleaved
```

Three assertions, because the first draft only checked the bookkeeping variable
and would have passed a graph that recorded mono and wired stereo:
buffers-match-engine, node-is-mono, and bookkeeping-matches-engine.

> **CORRECTION (D48).** This section closed by asserting that bug 2 (the shop
> scroll crash) was "NOT this", on the grounds that "shop tiles play no sound on
> press, so nothing on that path reaches `play`". That was **wrong**, and it was
> guessed rather than checked: `ShopModel.tapItem` calls `audio.pop()` on the
> owned branch and `tryOn` calls it on the unowned one — every shop tile plays a
> pop. The second crash log has the same abort, from `tryOn`. See D48.

---

## D48 — Bug 2 was bug 3 all along, and the claim that it wasn't was a guess

The second device crash log is byte-for-byte the same abort as D47, from a
different caller:

```
-[AVAudioPlayerNode scheduleBuffer:atTime:options:completionHandler:]
  → +[NSException raise:format:] → objc_exception_throw → abort()
← GameAudioGraph.play(_:) ← LiveAudioEngine.pop() ← ShopModel.tryOn(_:)
← ShopModel.tapItem(_:) ← ShopView.tile ← TouchDownCore.ended(at:in:)
```

Same exception, same frame, same binary — the report is timestamped 01:52, and
the fix landed at 02:26. Both reported bugs were one bug. Nothing else needs
fixing for it; the build that carries `ensureSfxFormat` carries the fix for both.

**The claim in D47 that they were different was wrong, and wrongly arrived at.**
I wrote that shop tiles "play no sound on press" without opening `ShopModel`.
`tapItem` calls `audio.pop()` on the owned branch, and `tryOn` calls it on the
unowned one. Every shop tile pops. Had I read the file instead of reasoning from
a recollection of the shop's feel, the second log would not have been needed —
the first stack plus one `grep` for `pop()` was already enough to close both.

The lesson is the one this port keeps re-teaching: **a claim about the code is
worth nothing until it is read.** It cost the user a second crash-report round
trip.

### The open question the log raises but cannot answer

The crashing frame is `TouchDownCore.ended(at:in:)` — a lift, and `onUp(inside:)`
reported `true`, so the tile's action ran. The bug was reported as *"when I
scroll down the shop"*. Two readings fit:

1. it was an ordinary tap and the "scroll" in the report is incidental; or
2. **a scroll drag that lifts on a tile fires that tile's action** — the child
   flicks the shop and lands in a try-on dialog.

Reading 2 would be a genuine deviation. On the web a scroll fires `pointercancel`
and no `click` follows; `TouchDown.swift`'s header asserts the same happens here,
"when the scroll view takes the touch (cancelling content touches) the recogniser
transitions to `.cancelled`". **That assertion is unverified**, and there is a
concrete reason to doubt it: `Coordinator` returns `true` from
`shouldRecognizeSimultaneouslyWith`, which is exactly what stops the pan from
forcing our recogniser to fail. If the pan cannot fail it, `.ended` arrives
normally at lift and the tap runs.

It is not fixed here, because it cannot be *observed* here: the host suite has no
scroll view and no finger, `simctl` has no tap or swipe input, and the crash log
says only that `.ended` fired — not whether a pan was in flight. Guessing at UIKit
interop is what produced the sentence being corrected at the top of this entry.
Settling it is the XCUITest tier's first job (see the open item in D46): a swipe
across a shop tile either opens a dialog or does not, and that is a one-assertion
test on a real simulator.

---

## D49 — A scroll is not a tap: the flick that bought things

Reported from the device, and unambiguous:

> "if you don't start to swipe immediately after touching the screen, it counts
> as a tap and triggers whatever action"

This is the question D48 raised and could not answer from a crash log. It is
reading 2: a flick that lifts on a shop tile fires that tile.

**`TouchDown.swift` claimed the opposite, and the claim was wrong.** Its header
said that "when the scroll view takes the touch (cancelling content touches) the
recogniser transitions to `.cancelled`". It does not, and the reason is three
lines further down in the same file: `shouldRecognizeSimultaneouslyWith` returns
`true`. That permission exists so a zero-duration press cannot block scrolling —
and it is exactly what stops the pan from failing our recogniser. The press
survives the drag, reaches `.ended` at the lift, and `ended(at:in:)` reports
`inside: true` because the finger is over the tile.

The web never behaved that way: once the page starts scrolling the browser fires
`pointercancel` and no `click` follows.

### The fix, and why it asks the scroll view instead of measuring the finger

`Coordinator` now asks the enclosing `UIScrollView` directly: while it
`isDragging` (the pan won) or `isDecelerating` (momentum, where iOS-wide the
first touch stops the scroll and activates nothing), the touch is a scroll and
the core is `cancelled()`. `cancelled()` clears `isTracking`, so the `.ended`
UIKit still delivers is dropped.

The obvious alternative — cancel once the finger has travelled more than N points
— was rejected. It would also swallow a six-year-old's wobbly press on a
*non-scrolling* button, which the web delivers as a click. Asking the scroll view
is the same question the browser asks, and it changes nothing anywhere else:
`grep` says only two surfaces in the app act on touch-UP at all (`ShopItem`'s
tile and `Picker`'s species card) and both live inside a `ScrollView`. Every
exercise tile acts at touch-DOWN (invariant 1) and is unaffected by construction
— asserted, not assumed, by `noScrollViewMeansNoCancel`.

`handle(_:)` is now a two-line adapter over `apply(state:view:location:)`,
because a `UILongPressGestureRecognizer`'s `state` cannot be set outside a live
touch sequence — so a test of `handle` would have had to be a re-typed copy of
it. Same lesson as `canSchedule` in D47: test the rule, not a paraphrase.

### The test that caught the fix not working

The whole fix rests on one assumption — that SwiftUI's `ScrollView` is backed by
a `UIScrollView` reachable through `superview`. Nothing documents that. So the
suite hosts a real `ScrollView` containing a real `.touchDown`, finds the catcher
UIKit actually built, and walks up from it.

It failed. `enclosingScrollView` returned `nil`.

The cause was the test, not the fix: its catcher-finder matched on
`as? UILongPressGestureRecognizer` with `minimumPressDuration == 0`, and
`ScrollView`'s own indicator knob carries a
`UIScrollViewKnobLongPressGestureRecognizer` — a subclass, also zero-duration.
It matched the scroll view itself, which of course has no scroll view above it.
With an exact class match the real chain appears:

```
UIView (ours, UILongPressGestureRecognizer)
  → UIKitPlatformViewHost<PlatformViewRepresentableAdaptor<TouchDownSurface>>
    → PlatformGroupContainer
      → HostingScrollView            ← isUIScrollView = true
```

Worth stating plainly: a false-failing test is what proved the fix works, and for
half an hour the evidence pointed at a no-op fix. The assumption was worth
asserting either way — if a future SwiftUI stops using a `UIScrollView` there,
this fix silently dies and a flick starts buying things again. That test now
fails the day it happens.

### Mutation

Removing the cancel makes the regression test fail with the reported symptom
exactly: `(insides → [true]) == [false]` — the flick taps.

### Two things this exposed

- **UIKit-level tests already run.** `xcodebuild test -scheme
  AttrapeLettres-Package -destination 'platform=iOS Simulator,…'` runs the whole
  package on a simulator, `UIKit` and `UIHostingController` included. The
  `#if canImport(UIKit)` suites in `TouchDownTests` were written as "compile on
  the host, run on a device" and had, as far as this port knew, never run. They
  do now, in 0.6 s. XCUITest is still the missing tier for real fingers, but a
  large slice of what was assumed device-only is reachable today.
- **`delaysContentTouches` is real** (D46 flagged it as unverified):
  `HostingScrollView` carries a `UIScrollViewDelayedTouchesBeganGestureRecognizer`.
  It delays `touchesBegan` to content VIEWS, not to gesture recognisers attached
  to them, so the touch-down path should be unaffected — but "should" is what
  this entry is about. Still unmeasured.

---

## D50 — Two runtime issues at launch, traced by deletion

Xcode printed this twice on every launch, on device and in the simulator:

```
Potential Structural Swift Concurrency Issue: unsafeForcedSync called from
Swift Concurrent context.
```

**Whose?** The log line carries a subsystem the Xcode Issue navigator drops:

```
[com.apple.Accessibility:AXCommon] Potential Structural Swift Concurrency Issue: …
```

Accessibility, not SwiftUI and not our audio. The obvious suspect was therefore
`UIAccessibility.isReduceMotionEnabled` (`SystemReduceMotion`,
`SystemDefaultReduceMotion`) — and that was wrong. The line immediately after
the second fault named the real one:

```
[com.apple.Accessibility:VoiceDBClient] Error fetching voices: DecodingError…
```

`TextToSpeech` logs under the Accessibility subsystem. The call is
`AVSpeechSynthesisVoice.speechVoices()`, from `SpeechFallback.prewarm()`.

**Confirmed by deletion, not by reading.** One probe build with
`(speech as? SpeechFallback)?.prewarm()` commented out: both faults went to
**zero**. Restored, they came back. That is the whole diagnosis — no stack
trace needed, and no guess of the kind D48 is an apology for.

### What was actually wrong, and it was ours

Apple's forced sync is Apple's business. Where it ran was ours:
`speechVoices()` is a synchronous IPC to the system voice database, invoked on
the **main thread** at launch, and the two faults straddle 54 ms of it. The
lookup now runs on `DispatchQueue.global(qos: .userInitiated)` and hands the
resolved voice back — the same shape `ClipPlayer.preload` already uses.

`voice()` keeps its synchronous path: a child who taps the score in the first
moments of a launch must hear it, not wait for a background resolve. Whoever
lands first wins, and both roads lead to the same database, so the race has no
wrong outcome.

**Verified by measurement, not by a unit test**: 2 faults → 0, with the voice
database still consulted 134 times in the same launch (so the work still
happens, off the main thread). A host test could only have asserted which queue
a call was made on, which is the thing measured here directly. The picker logic
it feeds was already covered by `FrenchVoicePicker`'s tests, and is untouched.

Worth keeping the reason for caring: a runtime issue that fires on every launch
is noise, and noise is what hides the next real one.

### The two audio lines in the same console dump

```
IPCAUClient.cpp:139   IPCAUClient: can't connect to server (-66748)
AVAudioBuffer.mm:281  mBuffers[0].mDataByteSize (0) should be non-zero
```

**Not reproducible here** — zero occurrences in the simulator across every run
of this investigation, so what follows is reasoning, not a finding, and is
labelled as such.

Both are AVFAudio's own diagnostics, and neither can come from a buffer this app
built: `renderBuffers` refuses an empty sample array before it makes a buffer,
and `ClipPlayer.decode` refuses a file with `frames == 0`. The pairing points at
the engine starting while the IO server is unreachable, which on iOS means the
audio session was not active — and `start()` runs from the root view's `.task`,
which is not a guarantee of a foreground-active app.

`unlock()` already heals that on the first tap, so it was never a functional
bug. But invariant 1 says the tap must not pay for the engine start, so
`prewarm()` now also runs on scene `.active`. That closes a second, unrelated
gap that WAS reachable: an interruption ending with `shouldResume == false`
leaves the graph suspended (`LiveAudioEngine.handle`) until something taps.

**This is not verified to silence those two lines**, and it is not offered as
their fix. It is right on its own terms; the diagnosis stays a hypothesis until
a device says otherwise.

---

## D51 — The wash fills the screen (bug 1 of three more, reported from the device)

> « The background gradient should fill all the screen, shouldn't it? I mean
> throughout the entire app »

Yes. The port was faithful and the result was wrong, which is the interesting
part.

`index.css` frames the app card on a cream mat:

```css
body { background: #efe6da; }
#root {
  padding: max(16px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right))
           max(16px, env(safe-area-inset-bottom)) max(16px, env(safe-area-inset-left));
}
#root > * { width: 100%; max-width: 480px; }
```

That is a WEB PAGE's device: centre a phone-shaped card on a desktop, and use
the safe-area env vars so a notched phone does not clip it. `RootView` ported it
literally — `Palette.page.color` behind, `.frame(maxWidth: 480)`, `.padding(16)`
— and on a 402 pt iPhone the cream stopped being a mat and became a grey band
under the Dynamic Island and a second one over the home indicator. Native apps
do not look like that, and the mat had nothing left to do: iOS gives the safe
area for free, so painting it a different colour is pure loss.

### The fix is two lines and one of them is subtle

The gutter became SAFE-AREA padding instead of layout padding:

```swift
gatedScreen
    .frame(maxWidth: Shell.cardMaxWidth)
    .safeAreaPadding(Shell.minimumInset)   // was .padding(Shell.minimumInset)
```

Content keeps exactly the same insets either way. The difference is what a
background can escape: `.ignoresSafeArea()` consumes safe-area insets and can do
nothing about a `.padding`. With the gutter expressed as safe area, one modifier
does the rest, at nine screen roots:

```swift
func stageWash(_ wash: HexGradient) -> some View {
    background { wash.gradient.ignoresSafeArea() }
}
```

`ignoresSafeArea()` on the BACKGROUND, never on the composed view — the latter
would drag the content under the status bar with it. Spelling that nine times is
nine chances to write the wrong one, hence the modifier.

`Palette.page` still exists and still shows where it has a job: beside the 480 pt
card on an iPad, which is what `max-width: 480px` was for.

### The half of it that took three builds: a wash inside a scroll view

`HubStage` and the species picker paint their stage and are then wrapped in a
`ScrollView` (D46 for the picker, always for the hub). A background applied
INSIDE the scroll is part of the scroll CONTENT: pinned to the content rather
than the window, so the top of the screen stayed cream and the wash slid away
under the finger. The wash has to be applied to the scroll view, not to what it
scrolls:

```swift
ScrollView { HubStage(…) }
    .stageWash(Palette.stage)
```

A consequence worth naming, because it is a deviation: the wash no longer
scrolls. On the web the gradient belongs to the stage `<div>` and moves with it;
here it is a fixed backdrop the content slides over. That is what "fills the
screen" means on a phone, and it is what was asked for.

The shop already had it right (its `ScrollView` is inside the stage). `HubStage`
therefore no longer paints itself, and `HubRasterTests` applies the same
modifier so the raster tier sees the same pixels.

### The test that had to be thrown away

The first assertion — find the hub's `UIScrollView`, require it spans the window
— passed against a build with the bug in it. **A `UIWindow` created in a test has
no safe area at all**: there is no scene to take one from, so every inset is
zero and a safe-area claim is true by construction. It was the second vacuous
test of this session (`playForTesting` was the first), and it passed for the
same reason: it asserted something that could not have failed.

What replaced it measures pixels, with the insets injected:

```swift
controller.additionalSafeAreaInsets = UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0)
…
let top = pixel(root, at: CGPoint(x: phone.width / 2, y: 3))
#expect(top.r >= 250 && top.b <= 210)   // #FFE7C9, not #efe6da
```

`#FFE7C9` and `#efe6da` are 17 apart in blue, which is plenty. Mutation-checked
by dropping `ignoresSafeArea()` from `stageWash`: the top pixel comes back
`(239, 230, 218)` — `#efe6da` exactly, the reported defect, to the byte.

---

## D52 — `aspect-square`, and three bugs that were one line

> « the rewards badge is too small and the exercise level box is also too small
> and gets completely hidden by the badge »

Three complaints, one cause, and the cause was a modifier that compiles, reads
like the CSS it ports, and means something else.

```swift
Text(verbatim: "\(cell.level)")
    .font(…)
    .frame(maxWidth: .infinity)
    .aspectRatio(1, contentMode: .fit)   // aspect-square
```

`.fit` means *shrink me until I fit inside the proposal I was given*, and the
proposal a `LazyVGrid` cell hands down carries the CONTENT's ideal height. A
24 pt digit is 28.67 pt tall. So a 60 pt-wide column rendered a **28.67 pt**
square, centred, with 31 pt of dead stage around it — and from that one number:

- every level button was **under Apple's 44 pt floor**, on a game for a
  six-year-old aiming with a whole hand (invariant 6);
- the reward pill is an `.overlay`, so it is proposed the BUTTON's width — 28.67
  pt — and truncated « +10 ⭐ » to « +1 » plus a clipped star. The screenshot
  read as a speaker icon. **The app was misreporting the reward.**
- the pill, pinned to a box a third the size it was drawn for, covered the digit.

CSS derives the height FROM THE WIDTH. `Color.clear` has no ideal size and
accepts whatever it is proposed, so the aspect ratio resolves against the width
alone:

```swift
struct AspectSquare<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay { content }
    }
}
```

Plus `.fixedSize()` on the pill: the TSX badge is `position: absolute`, which is
out of flow and sizes to its content, and a SwiftUI overlay is not. Without it a
wider reward silently truncates again.

Level buttons went 28.67 pt → 60 pt on the reported device. Nothing else moved.

### How it was found, and why it took so long

Every earlier attempt was indirect and every one of them lied a little:

- **The device screenshot.** Measurable, and I measured the wrong thing: the
  badge pitch (68 pt) is the COLUMN pitch, not the button, so the arithmetic
  said the buttons were 60 pt while the eye said they were half that. Both were
  true. The column was 60; the button inside it was 28.67.
- **The `UIView` tree.** Empty. A SwiftUI `Button`, `Text` or `Capsule` is not a
  view, it is a drawing command in one hosting layer.
- **The accessibility tree.** Also empty — SwiftUI does not publish nodes unless
  an assistive technology is actually running, so a walk finds nothing in a test.
- **`GeometryReader` + `onAppear`.** Printed a plausible number ONCE, from a
  provisional layout pass. Printing from the `GeometryReader`'s body instead
  gave the real one, in the app, on the second try: `ZZCELL 28.666×28.666`.

The lesson is the same one D48 cost a round trip over: an inference about the
code is worth nothing next to a measurement OF the code.

### What now guards it

`AspectSquareTests`, on the host, in `swift test`. `ImageRenderer`'s raster is
the size the view RESOLVED to, so it reads a laid-out dimension back without a
window, a simulator or a `GeometryReader`:

```swift
let size = try resolvedSize(AspectSquare { Text("1").font(.system(size: 24, weight: .black)) }
    .frame(width: 60))
#expect(size == CGSize(width: 60, height: 60))
```

Mutation-checked against the old spelling: 60×28, 72×10, 72×33, and the tap
target drops to 28 pt at iPhone SE width. Three failures, all of them the bug.

### The harness this finally produced (D19 / W18, part one)

`ScreenSnapshotTests` hosts a screen in a real `UIWindow` on a simulator
destination and can do three things the host tier cannot: give it real safe-area
insets, lay out a real `UIScrollView`, and read pixels back. It also writes a PNG
per screen to the simulator's temp dir — unconditionally, because an env var does
not survive the trip to a simulator test process and a tool you have to remember
how to switch on is a tool nobody uses. `simctl` has no touch input, so hosting
is the ONLY way to look at the dashboard, the paywall or an exercise; all four
were checked for D51 that way.

Still not built: the golden-master pixel diff of D3. What exists is a way to
look, plus assertions on the two pixels that carry a claim.

### Known, unfixed: five raster tests fail on the simulator destination

`GrowthBarRasterTests` (3) and `Shop — paint order` (2) pass on the host and
fail under `xcodebuild test -destination 'platform=iOS Simulator'`. **Verified
pre-existing** — a baseline worktree at `f46efdd` fails the same five — so they
are not a regression from D51/D52.

**Root cause, found later (D54):** not a compositing difference. `ImageRenderer`
renders any tree containing a platform-view representable as a solid **red
placeholder**, `(255, 56, 60)` — which is exactly the "green" those tests read
back. `LayerHost` is a no-op on macOS and a `UIViewRepresentable` on iOS, so a
view that hosts one rasterises fine on the host tier and returns a red rectangle
on the simulator. The tests are not mis-calibrated; on that destination they are
looking at nothing.

The fix is the one D54 applied to `Tile`: split the paint into a view that hosts
no layer and no gesture (`TileFace`), and point the raster test at that. Not done
for these five — they are green where CI runs them and the reported work came
first — but the diagnosis is no longer open.

---

## D53 — « Jamais le prénom de v… »

Found in a gallery render (D52), not reported: the paywall's parent layer was
drawing its consent card as

> Nous aider à améliorer le jeu — exercice, niveau, réussi ou non. **Jamais le
> prénom de v…**

An ellipsis three words into the sentence that says what the app does *not*
collect. The rest — « …otre enfant. » — was not on the screen.

Not a copy bug and not a `lineLimit`: there is none in `ConsentCheckboxStyle` or
at either call site. Hosting the same card on its own, at the same width and in
the same wrapper (`.frame(maxWidth: 448, alignment: .leading)` + `.padding(24)`),
wraps it to three lines correctly. Raising the window to 1400 pt changed
nothing, so it was not the stack running out of room either. The height came
from the `VStack` proposing one and the label accepting it.

```swift
Text(verbatim: Copy.Paywall.Parent.consentBody)
    …
    .fixedSize(horizontal: false, vertical: true)
```

That is the answer whatever the proposal was: take the height this width needs.
Applied to the onboarding card too, which renders in full today — the paywall's
did as well until the stack around it changed shape, and this is the one class
of copy where "renders fine at the moment" is not good enough. Invariant 10's
promise is only worth what is legible of it.

### Why the guard is a source scan

`ConsentCopyTests` greps both screens for the modifier between `consentBody` and
`.toggleStyle`. A raster assertion would be the obvious choice and is the wrong
one: what went wrong was a MISSING MODIFIER, and a failing screenshot does not
tell a reviewer which line to add. Same mechanism, and the same reasoning, as
`MoneySourceScanTests` — a review-time rule turned into a build-time one.
Mutation-checked by deleting the modifier.

### The wider point about the render gallery

This defect was on a shipping screen, behind a parental gate, in the copy that
carries the app's privacy promise. Nobody reported it because reaching that
screen takes two deliberate taps and an adult. It was found the first time
anything rendered the screen and looked at it — which is the argument for the
gallery, and the reason it writes its PNGs unconditionally.

---

## D54 — The shadow under the letters

Reported from the device: « the shadow under the letters (the letters
themselves, not the tile) should not be there ».

It is a SwiftUI semantic that CSS has no equivalent of. `.shadow` is a
**per-layer** effect, like `.opacity` and the blend modes: applied to a composed
view it runs on every drawing primitive inside it, separately. So a tile's glyph
cast its own drop shadow — and, being painted above the tile's fill, that shadow
landed **on the tile face**, a dark smear trailing every letter.

CSS never does this. `box-shadow` is cast by the border box and by nothing else;
`filter: drop-shadow` by the element's flattened alpha. `.compositingGroup()`
restores either one, by flattening the subtree so there is a single alpha to cast
from.

```swift
content
    .background(bg.color, in: RoundedRectangle(cornerRadius: 28))
    .overlay { highlightRing }
    .compositingGroup()          // ← the whole fix
    .shadow(color: …, radius: …, y: 8)
    .shadow(color: …, radius: 10, y: 12)
```

Measured before writing any of it, on a 100 pt tile with white ink over
`#4FC3F7`: **460 face pixels darker than the fill without the line, 0 with it**,
the darkest `(62, 154, 195)`. White ink is what makes that a clean test — a
glyph's antialiasing can only blend the face *towards* white, so anything darker
than the fill is a shadow and nothing else.

### It was twenty call sites, not one

The same spelling — `.background(_, in: shape)` then `.shadow` — was live at
twenty places: every hub chip and the level buttons, the reward pill, the
`GameFrame` and dashboard back buttons, the roster's edit button, the shop's zone
cards, sticker chips and price badges, the picker and growth cards, the assembly
and spelling slots, and both tile shadows. It is not a per-site slip; it is what
a careful port of a `box-shadow` looks like.

Two spellings are now in use, deliberately:

* `.compositingGroup()` then `.shadow` — where content sits inside the box;
* `.background { Shape().fill(…).shadow(…) }` — where the shadow is cast by a
  bare shape, which is already one layer. `liftedCapsule`, `ListenPill` and the
  twins/grid slots were already written this way and needed nothing.

The two shop mascots are the third case and the reason the rule is worth stating
in terms of CSS rather than of SwiftUI: the web writes `drop-shadow-lg` there, a
FILTER, cast from the whole silhouette. Per-layer, every ear and limb was
dropping a shadow onto the friend's own body.

### Two tests, because the defect has two halves

`BoxShadowRasterTests` renders a tile face and counts pixels darker than the
fill — the reported symptom, in pixels. `BoxShadowScanTests` walks every
`.shadow(` in ALUI and requires one of `.compositingGroup()`, `.fill(`,
`.stroke(`, `.strokeBorder(` or a preceding `.shadow(` within six lines above it.
The scan exists for the same reason `ConsentCopyTests` does: what went wrong is a
**missing modifier**, and no screenshot tells a reviewer which line to add. Both
mutation-checked — dropping the tile's `.compositingGroup()` fails the raster
test with 471 smudged pixels and the scan with `Tile.swift:285`.

### The vacuous test the guard caught

The raster test was first written against `Tile` and passed — against a raster
containing **nothing**. `ImageRenderer` renders any tree holding a
`UIViewRepresentable` / `NSViewRepresentable` as a red placeholder, and `Tile`
carries one: its `touchDown` surface. `theProbeIsNotLookingAtNothing` — assert
the probe can see fill pixels and glyph pixels before trusting what it says about
shadow pixels — is what caught it, and is why `TileFace` exists: the paint, with
no layer host and no gesture, so it can be looked at.

That is the third vacuous test this port has produced (after `playForTesting` and
D51's safe-area assertion), and the first one a deliberately-written guard caught
rather than a re-read. It also explains the five long-standing simulator raster
failures — see the note in D52.

---

## D55 — The 🔊 button had no answer for a finger

Reported from the device: « when I tap on the exercise sound button, it should
have a tap feedback, like the tiles do ».

Faithful, and wrong on a phone. `Tile.tsx` is the only file in the PWA that calls
`el.animate`, so the big « Écouter » pill is visually inert on the web and the
only acknowledgement of a tap is the voice that follows. In a browser on a laptop
that voice is immediate. On a phone the clip may still be decoding, and a child
who gets nothing back taps again — which cuts the line they just asked for.

**[DEVIATION, reported]** It now runs the same 130 ms squish a tile does, through
the same path: `TilePress.previewDown` — press first, then speak, and never a
shake, because asking to hear something has no verdict (invariant 3).

### And it was four buttons

`LettersListenPill`, `SpellListenPill`, `SoundEngineChrome.listenButton` and
`AssembleView`'s private one: four copies of the same eight lines of JSX
(`rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E] shadow`),
all identical down to the shadow. Exactly the divergence CLAUDE.md warns about —
a fix lands in one and nobody sees the other three. There is one `ListenPill`
now; the four names survive as wrappers that differ only in the margin they
carry, and each engine's metric namespace re-exports `ListenPillMetrics` so the
per-engine audit still reads engine by engine.

`ListenPillTests` guards both halves: the pill must reach `TilePress.previewDown`,
and no file in `Engines/` may paint its own `bg-white/70` again.

---

## D56 — A wrong tap made no sound

Reported from the device: « when I press the wrong answer, it wiggles but doesn't
play the failure sound ».

The port is exact. `SinglePickModel.pick` calls `deps.audio.nudge()` on a miss,
`nudge` is `blip(196, 0.14, "sine", 0.10)`, and that is `useAudio.ts` to the
digit. The sound was being scheduled. It was not being **heard**, for two reasons
that compound and neither of which exists on the desktop it was authored against:

* **A phone's loudspeaker has no low end.** It rolls off hard below roughly half
  a kilohertz, so a 196 Hz fundamental arrives tens of dB down — and the ear is
  least sensitive in that band too, so the two losses stack.
* **The pop masks whatever survives.** `pick()` fires `pop()` (660 Hz, gain 0.16)
  in the same instant, right in the band the speaker likes, half again as loud.

So the cue is redesigned rather than transposed — an octave up is still in the
rolloff, two is a chirp:

```swift
case .nudge:
    return [
        SfxBlip(freq: 523.25, dur: 0.12, wave: .sine, gain: 0.09, at: 0.06),  // C5
        SfxBlip(freq: 392.00, dur: 0.22, wave: .sine, gain: 0.09, at: 0.16),  // G4
    ]
```

A soft falling fourth, both notes safely in band, starting 60 ms in — by which
point the pop's exponential decay has it at 0.0015 against the nudge's 0.09,
about 35 dB down. And still the quietest sound in the game: invariant 3 says a
wrong tap is not a failure, and « doucement, non » is the whole message.

Four assertions, each naming its reason rather than its number: softest of the
four sounds, falling, no note below 350 Hz, and the pop measurably out of the way
(computed through `SfxSynth.envelope`, not hard-coded) at the moment the nudge
starts.

### `oops` is deliberately left alone

The assembly engines' wrong-row sound is lower still (392 → 311 Hz) and the same
argument would apply — except that it plays alone, unmasked, and « Oh non ! On
recommence. » speaks over it a beat later. Those engines never relied on the tone
to carry the meaning. This one had nothing else.

### What is not claimed

That the fix was heard. The reasoning is about the signal and the speaker, and
both halves are argued rather than measured on hardware — no phone-speaker
response curve was taken. The device listen is the verification, and it is the
one step this tier cannot do.

## D57 — Training exercises pay for finishing

Reported from the device: « I earn no point at the end of an exercise ».

The economy was intact. The report was against « La première lettre » or
« Complète le mot », the two `difficulty: 0` rows, and `sessionReward` opened
with `if difficulty === 0 return 0` — authored, ported faithfully, and doing
exactly what it said.

The rule it encoded is the one that changed. « Difficulty 0 pays nothing, ever »
conflates two things a six-year-old experiences differently: **finishing**, and
**finishing well**. Only the second is farmable. A child who works through five
rounds of « La première lettre » has finished something, and getting nothing for
it teaches that the first row of the hub is not really part of the game.

So `difficulty` now weights the bonus alone, and the curve is paid by every row:

```swift
// `.d0` needs no branch: it multiplies the bonus to nothing.
let bonus = totalRounds > 0 ? (perfectRounds * difficulty.weight) / totalRounds : 0
return rewardFor(priorClears: priorClears) + bonus
```

### Why this does not reopen farming

Invariant 8 was never « training pays nothing » — that was one implementation of
it. The invariant is that grinding must not out-earn climbing, and it now rests
on the gradient, which is stated as its own test:

> the BEST a training row can pay is the WORST a paying row can pay.

Both equal `rewardFor(priorClears)`. On a training row careful play and spam pay
the identical number, so there is nothing there for accuracy to farm; the only
way to beat the curve remains a harder row. The curve itself is the other half:
10 → 3 → 2 → 2 → 1, so replaying « Complète le mot » forever converges on one
point a run, against 14 for a first-try « écritures mêlées ».

What it does cost, stated plainly: the two training rows have 9 levels between
them, so 90 stars of first-clear jackpot now exist that did not before, and a
child who spam-taps can reach them. That is the price of the rule, and it was
weighed rather than discovered.

### `previewReward` lost its `difficulty` argument

It now returns the curve for every row, so the parameter had nothing to decide.
Dropping it (rather than leaving it unread) is what keeps the hub honest: the
level pill is drawn from `preview`, and a pill that promised nothing while the
exercise paid 10 would be a lie with a compiler-shaped excuse. Training levels
now wear the same « +10 ⭐ » and announce « Niveau 1, gagne 10 étoiles ».

`Copy.Hub.levelTraining` (« pour s'entraîner ») and the `reward == nil` branch
of `hubLevelCells` survive with no caller. `hubLevelCells` is a pure function of
a preview closure, and « promises nothing ⇒ shows nothing » stays the right
answer for an input it can still be handed; the alternative is a « +0 » pill.
`Finished`'s `earned > 0` guard is dead for the same reason and kept for the
same one.

### Both codebases, one commit

The port's contract is that behaviour matches the PWA, so `rewards.ts` and
`Rewards.swift` changed together, along with both suites and CLAUDE.md's
invariant 8. Changing only the Swift would have made every future audit report a
divergence — and shipped two different economies to one family's two devices,
which the per-device counters would then merge into a number neither side could
explain.

### What paid for the diagnosis

`EconomyE2ETests` — written before the change, to answer the report. It plays
all seventeen catalog rows to a full-perfect finish through the real
`engine(for:)` dispatch, the real content and a real `ProfileStore`, and asserts
the balance. It is the only test that would catch an engine that stopped calling
`award`, a row dispatched to the wrong engine, or an empty session (which sets
`done` in the model's init and skips the finish transition entirely). It found
nothing wrong, which is what made the answer « this is your design » defensible
rather than a shrug — and after the change its expectation collapsed from a
conditional to `curve[0] + difficulty.weight` for every row.
