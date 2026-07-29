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
