# Shell — architecture spec (hub, router, GameFrame, shared components, adult screens, hooks)

Scope: `src/App.tsx`, `src/App.test.tsx`, all of `src/components/`, all of `src/hooks/`
**except** `useProfile.tsx` (owned by another agent).

Everything below is a port target. **Behaviour is frozen** — the PWA is the specification.
Where the TypeScript looks odd it is ported as-is and the oddity is recorded under
[§7 Risks](#7-risks). All French copy is byte-for-byte, including non-breaking spaces and
typographic apostrophes (`’` U+2019, not `'` — several strings in `levels.ts` and
`ExerciseIcon` comments use it; check each literal).

Binding prior decisions: `DECISIONS.md` D0 (frozen behaviour, read from the original
working tree), D1 (ALCore / ALArt / ALUI / App, host-testable), D2 (SVG `d` strings copied
verbatim, parsed at runtime by the ALArt parser — geometry is never retyped), D3 (pixel-diff
acceptance).

---

## 0. Executive summary of what an implementer must not get wrong

1. `App.tsx` is a **flat ordered if-chain**, not a tree. The order of the seven gates and
   the order of the eight exercise dispatches are both load-bearing. Port them in order.
2. React's `key={`${exercise}-${level}`}` on the exercise component is a **remount**. Its
   exact SwiftUI equivalent is `.id(...)`, and nothing else. Do not use `NavigationStack`.
3. Invariant 1 (feedback on pointerdown) is **not** satisfiable with `.onTapGesture` or a
   plain `Button`. Use the `TouchDownGesture` in §2.3.
4. Invariant 2 (animation off the render path) is **not** satisfiable with `withAnimation`
   for the press/shake/confetti/flourish paths. Use `LayerHost` + Core Animation (§2.3).
5. Invariant 7 (an original icon per exercise, enforced at compile time) becomes a `switch`
   over `ExerciseId` with **no `default:` clause**, in `ALArt/Icons/ExerciseIconCatalog.swift`.
6. `src/App.test.tsx` is **currently red — 9 of its 12 tests fail against the working tree**
   (verified by running it). It specifies licensing gates that `App.tsx` does not implement.
   See [§7.1](#71-the-red-test-file--the-single-biggest-decision-in-this-scope). This needs a
   whole-app decision before `RootView` is written.

---

## 1. Inventory

Line counts are `wc -l` against `/Users/jonathan/IdeaProjects/attrape-lettres/src`.

### 1.1 Router / hub

| File | Lines | What it actually does |
|---|---|---|
| `App.tsx` | 227 | Single component. Holds `view: View` state; reads `useProfile()` and `useAudio()`. Listens to `hashchange` for two dev screens. Seven ordered gates, then an eight-branch exercise dispatch, then the hub markup (header row, mascot button, title, and one `<section>` per `EXERCISES` row with a 5-column level grid). No licensing, no paywall. |
| `App.test.tsx` | 222 | **Untracked, and red.** 12 tests over `<EntitlementProvider><ProfileProvider><App/>`. Asserts an `Onboarding` first-launch gate, a hub trial countdown chip, and a `Paywall` on level tap. None of that exists in `App.tsx`. 9 fail, 3 pass. |

### 1.2 `src/components/`

| File | Lines | What it does |
|---|---|---|
| `ExerciseIcon.tsx` | 278 | `GLYPHS: Record<ExerciseId, {tint, glyph}>` — 17 entries, one per exercise. Renderer is a 32×32 `<svg>`: a `rect` badge (x2 y2 w28 h28 rx8.5, `fill=tint`) plus the glyph. Census inside this file: **30 `<path>` (30 distinct `d` strings), 11 `<circle>`, 12 `<rect>`, 1 `<text>` element (the `Glyph` helper, used 4×), 2 `<g>`**. Zero emoji. `aria-hidden`. All 17 tints are distinct. |
| `GameFrame.tsx` | 74 | The in-exercise chrome: stage background, the full-bleed confetti `<canvas>` at `zIndex 40`, and a header row at `z-[41]` — "← Menu" button, the **star strip**, and a `sm:`-only 84px spacer. Renders `children` below. |
| `Dashboard.tsx` | 154 | "Mon copain". `ResizeObserver` → `mascotSize = round(min(230, max(140, width*0.46)))`. Big balance pill with `usePopFlourish`. Growth meter with a WAAPI width sweep (900ms `cubic-bezier(.2,.9,.3,1)`, static under reduced motion). Shop and "Changer de copain" doors. |
| `WhoIsPlaying.tsx` | 205 | "Qui joue ?". `NewProfile` form (name, `maxLength 14`, autofocus), `ChildCard` grid (2 columns) with an edit mode exposing ✏️ / ✕ corner buttons, and a dashed "Nouveau" tile. Rename uses `window.prompt`, delete uses `window.confirm`. Avatar = the child's current `Mascot`, or 🦉 when `!chosen`. |
| `Onboarding.tsx` | 117 | The first-launch parent screen. Trial terms **before** the trial starts (App Review 3.1.1). Consent checkbox, **unticked** (`useState(false)`). Platform-conditional family-sharing wording. `start()` → `setConsent`, `track("trial_started")`, `beginTrial()`. |
| `Paywall.tsx` | 189 | Three ordered steps `child → gate → parent`. Child step shows no price and no buy button. `ParentalGate` is the door. Parent step: buy, restore, a `note` line, the consent toggle (initialised from `hasConsent`), and "Retour au jeu". |
| `ParentalGate.tsx` | 116 | Modal. Two digits 3..9 rolled once per open (`useState(roll)`), product 9..81. Numeric input filtered to `\D` → digits, max 3 chars. Wrong → clear + refocus + `role="alert"` error. Deliberately not kid-styled. |
| `Tile.tsx` | 131 | The pick tile. `onPointerDown` → WAAPI `press`, then `onPick(): Verdict`; `"reject"` → WAAPI `shake`. Optional stacked "Écouter" sibling button that previews without committing the pick. |
| `Finished.tsx` | 44 | End-of-run screen: 🤩, optional `EarnBadge`, the star row (same gold/grey rule as the strip), title, `EndButtons`. |
| `EarnBadge.tsx` | 26 | "+N ⭐" gold pill with `usePopFlourish`. |
| `EndButtons.tsx` | 28 | 🏠 Menu (white) + 🎉 Suivant (green primary). Both fire on `onPointerDown`. |
| `FitLine.tsx` | 64 | Keeps the presented word on ONE line. Measures the natural row width, applies `scale(available/natural)` straight to the DOM, and pins the wrapper height to `naturalHeight * k`. Carries the fix for the "shrunk word row collapsing onto the tray" bug. |
| `WordIcon.tsx` | 38 | A word's picture: `img` when authored, else the emoji glyph at `size`. Decorative by default. |
| `Ollie.tsx` | 18 | Legacy owl mascot, emoji + CSS keyframes (`ollieBob` / `olliePop` in `index.css`). Superseded by `<Mascot>` everywhere in the shell; still exported. |
| `usePopFlourish.ts` | 37 | One-shot WAAPI pop on mount. `scale .4→1.18@0.68→1`, opacity 0→1, 480ms `cubic-bezier(.2,1.35,.4,1)`. No-op under reduced motion. |
| `Tile.test.tsx` | 66 | 3 tests: preview does not commit the pick; no Écouter button without `onPreview`; disabled tile disables Écouter. **Green.** |
| `WordIcon.stories.tsx` | 225 | Storybook harness for the dedicated illustrations. **Not ported** — the Swift equivalent is SwiftUI `#Preview` blocks; recorded here for completeness. |

### 1.3 `src/hooks/` (minus `useProfile.tsx`)

| File | Lines | What it does |
|---|---|---|
| `useAudio.ts` | 295 | The whole voice + SFX channel. Four WebAudio blips (`pop`, `success`, `nudge`, `oops`), an `unlock()` that creates/resumes the `AudioContext` and warms speech once, and `say()` — a **single-flight** channel with ticket bookkeeping, three watchdog regimes, baked-clip-first with TTS fallback, and a 200 ms fade in `stop()`. |
| `useAudio.test.ts` | 162 | 6 tests: clip end → `true`; supersede → old resolves `false`; media error → `false`; `stop()` settles + is idle-safe; watchdog on a stalled clip; TTS fallback. **Green.** |
| `useConfetti.ts` | 94 | Canvas + rAF particle burst. 90 particles, upward hemisphere, gravity, life decay. Never touches React state. No-op under reduced motion. |

---

## 2. Swift module plan

Dependency direction is strictly `ALUI → ALArt → ALCore`. Nothing points back.

### 2.1 `Sources/ALCore` — pure, no SwiftUI, no UIKit, builds and tests on the host

| File | Owns |
|---|---|
| `Shell/AppRoute.swift` | `enum AppRoute` — the port of `View`. |
| `Shell/ExerciseEngine.swift` | `enum ExerciseEngine` + `func engine(for: ExerciseMeta) -> ExerciseEngine`. The 8-branch dispatch, pure and host-testable. |
| `Shell/RunProgression.swift` | `func nextRoute(after: AppRoute, in catalog: [ExerciseMeta]) -> AppRoute` — the "Suivant" roll-over. |
| `Shell/StarStrip.swift` | `enum StarCell { case earned, lost, live, pending }` + `func starStripCells(done:total:stars:) -> [StarCell]` + `func starFontSize(total:) -> CGFloat`. The GameFrame strip rule, extracted so it is asserted without a simulator. |
| `Shell/DevScreen.swift` | `enum DevScreen { case stages, vo }` + resolution from a launch flag (the `#stages` / `#vo` replacement, §4.2). |
| `Shell/Copy.swift` | Every French string in this scope as a `static let`, plus the label builders (`levelLabel(level:points:)`, `balanceLabel(_:)`, `renamePrompt(_:)`, `deleteConfirm(_:)`). Copy in ALCore is what lets `swift test` assert wording without rendering. |
| `Audio/AudioEngine.swift` | `protocol AudioEngine` (the port of `AudioApi`) + `struct SayOptions` + `struct SilentAudioEngine` (tests/previews). |
| `Audio/VoiceChannel.swift` | The single-flight ticket machine and the three watchdog durations, over a `protocol SpeechBackend`. Pure; this is the part `useAudio.test.ts` covers. |
| `Audio/VoiceScoring.swift` | `struct VoiceDescriptor { name, lang, isLocal }` + `voiceScore` + `pickBestFr`. Pure regex scoring, ported literally. |
| `Audio/ToneSpec.swift` | The blip specs as data: `pop`, `success` (4 notes, 75 ms apart), `nudge`, `oops` (2 notes). Frequencies/durations/gains ported verbatim. |
| `Effects/ConfettiSystem.swift` | The particle model + a **fixed-step** integrator + a seedable RNG. Pure. |
| `Layout/FitScale.swift` | `func fitScale(natural: CGFloat, available: CGFloat) -> CGFloat`. |
| `Layout/Clamp.swift` | `func clampVW(_ min: CGFloat, _ vw: CGFloat, _ max: CGFloat, viewport: CGFloat) -> CGFloat` — the CSS `clamp(a, N vw, b)` port. |
| `Adult/GateChallenge.swift` | `struct GateChallenge { let a, b: Int; static func roll(using:) }`, `sanitizeGateInput(_:) -> String`, `verify(_:)`. |

`CGFloat`/`CGRect` come from CoreGraphics, which is available on the host — ALCore may
import `CoreGraphics` and `Foundation`, nothing else.

### 2.2 `Sources/ALArt` — the icons

| File | Owns |
|---|---|
| `Icons/IconNode.swift` | `enum IconNode` — the SVG primitive model: `.path(d: String, style: IconStyle)`, `.circle(cx:cy:r:style:)`, `.rect(x:y:w:h:rx:style:)`, `.text(IconText)`, `.group([IconNode], style: IconStyle)`. `IconStyle` carries `fill`, `stroke`, `strokeWidth`, `lineCap`, `lineJoin`, `dash`, `opacity` — exactly the SVG attributes the file uses and no more. |
| `Icons/IconNodeRenderer.swift` | `IconNode → some View`. Walks the tree in document order into a `ZStack`; `.path/.circle/.rect` become `Path`s (the `d` string goes through the D2 parser untouched), `.text` becomes a `Text` overlay. Scales the 32-unit space by `size/32`. |
| `Icons/ExerciseIconCatalog.swift` | `func exerciseIconSpec(_ id: ExerciseId) -> ExerciseIconSpec` — **the exhaustive `switch`, no `default:`**. The 17 tints and 17 node trees. This file is the compile-time enforcement of invariant 7. |
| `Icons/ExerciseIcon.swift` | `struct ExerciseIcon: View { let id: ExerciseId; var size: CGFloat = 30 }` — the badge `rect` + the spec's nodes. `.accessibilityHidden(true)`. |
| `Icons/ShuffleChip.swift` | The `ShuffleChip(tint:)` sub-tree, returned as `[IconNode]` so it composes into the two mixed entries exactly as the TSX `<g>` does. |
| `Icons/SVGText.swift` | `struct IconText { x, y, size: CGFloat; string: String; weight; fill }` and the anchoring math for `textAnchor="middle"` + `dominantBaseline="central"` (§3.5). |

### 2.3 `Sources/ALUI` — screens and shared components

**ALUI may import UIKit, but only under `#if canImport(UIKit)` with a macOS fallback.**
ALUI must still compile for macOS or `swift test` loses the ALUI target (D1). Every UIKit
use in this scope (`LayerHost`, `TouchDownGesture`) gets a SwiftUI-only fallback path used
on macOS and in host tests.

| File | Owns |
|---|---|
| `DesignSystem/Tokens.swift` | `enum AL` — colours, radii, spacing scale, fonts (§5). |
| `DesignSystem/Stage.swift` | The `STAGE` gradient (three stops, two variants — see §5.2) and the `.stageBackground()` modifier. |
| `DesignSystem/Shadows.swift` | `.cssShadow(...)` — the CSS-blur→SwiftUI-radius conversion and the hard-offset "0 8px 0" style, plus `.spreadRing(...)` for `0 0 0 6px`. |
| `DesignSystem/Buttons.swift` | `ALPressStyle(scale:)` for the `active:scale-95` chrome buttons; `PillButton`, `PrimaryGreenButton`, `WhiteChipButton`. |
| `DesignSystem/Viewport.swift` | `@Environment(\.alViewportWidth)` + `.clampVW(...)` view helper (§5.3). |
| `Animation/LayerHost.swift` | A `UIViewRepresentable` exposing its backing `CALayer` through a `LayerHandle` reference the parent holds. On macOS, a no-op host whose handle drops animations. This is what keeps every animation off the render path. |
| `Animation/Anim.swift` | `press`, `shake`, `popIn`, `pop`, `widthSweep`, all as `CAAnimation` builders ported 1:1 from the WAAPI keyframes. Every function early-returns when reduce-motion is on. |
| `Animation/TouchDown.swift` | `TouchDownGesture` — `UILongPressGestureRecognizer(minimumPressDuration: 0)`, fires on `.began`. The pointerdown equivalent. |
| `Animation/PopFlourish.swift` | The `usePopFlourish` port as a view modifier firing `Anim.popIn` on appear. |
| `Shell/RootView.swift` | The port of `App.tsx`: route state, the seven ordered gates, the exercise dispatch, `.id()` remounting. |
| `Shell/HubView.swift` | The hub markup: header row, mascot button, title, subtitle, the `EXERCISES` sections. |
| `Shell/HubLevelButton.swift` | One level square + its `+N ⭐/🪙` pill (jackpot vs trickle). |
| `Shell/GameFrame.swift` | The chrome + the star strip. |
| `Shell/ConfettiOverlay.swift` | `ConfettiOverlay(system:)` — a `TimelineView(.animation)` + `Canvas` leaf, `allowsHitTesting(false)`, and the `ConfettiController` handle exercises call `fire()` on. |
| `Components/Tile.swift` | The pick tile + the optional stacked Écouter button. |
| `Components/FitLine.swift` | The one-line word row (§4.6). |
| `Components/WordIcon.swift` | Picture-or-emoji. |
| `Components/EarnBadge.swift`, `EndButtons.swift`, `Finished.swift`, `Ollie.swift` | Direct ports. |
| `Screens/DashboardView.swift` | "Mon copain". |
| `Screens/WhoIsPlayingView.swift` | "Qui joue ?" + `NewProfileForm` + `ChildCard`. |
| `Adult/OnboardingView.swift`, `Adult/PaywallView.swift`, `Adult/ParentalGateView.swift` | The three grown-up screens. |

### 2.4 `App/` — the injection point

| File | Owns |
|---|---|
| `App/AttrapeLettresApp.swift` | `@main`. Builds the concrete `AudioEngine`, reads the window size into `alViewportWidth`, resolves `DevScreen` from launch arguments, and mounts `RootView`. |
| `App/SystemAudioEngine.swift` | `AVAudioEngine` tone generation + `AVAudioPlayer` for baked clips + `AVSpeechSynthesizer` fallback + `AVAudioSession` category. Implements `AudioEngine` by driving `ALCore.VoiceChannel`. iOS-only; never imported by ALCore/ALArt/ALUI. |

---

## 3. Type mapping

### 3.1 `View` (the discriminated union) → `enum AppRoute`

```ts
export type View =
  | { kind: "hub" }
  | { kind: "play"; exercise: ExerciseId; level: number }
  | { kind: "dashboard" }
  | { kind: "shop" }
  | { kind: "pick" }
  | { kind: "paywall" };
```

```swift
public enum AppRoute: Hashable {
    case hub
    case play(exercise: ExerciseId, level: Int)
    case dashboard
    case shop
    case pick
    case paywall          // present in the union; UNREACHABLE in App.tsx today — see §7.1
}
```

A TS discriminated union on a string tag is a Swift `enum` with associated values, one to
one. `AppRoute` gets `Hashable` so it can drive `.id()`. It also gets:

```swift
public var remountKey: String {          // the port of key={`${exercise}-${level}`}
    if case let .play(exercise, level) = self { return "\(exercise.rawValue)-\(level)" }
    return "route"
}
```

### 3.2 Types consumed from other agents (contracts I depend on)

| TS | Swift I require | Why |
|---|---|---|
| `ExerciseId` (17-member string union) | `public enum ExerciseId: String, CaseIterable, Hashable` — **frozen**, cases in the union's declaration order | The `switch` with no `default:` in `ExerciseIconCatalog` is invariant 7's enforcement; `CaseIterable` is what the exhaustiveness test iterates. |
| `ExerciseMeta` | `struct ExerciseMeta` with `let mode: SyllableMode?`, `grid: SyllableGridMode?`, `spell: SpellSyllableMode?`, `match: LetterMatchKind?`, `mixed: Bool` (TS `mixed?: boolean` — absent and `false` are the same to `App.tsx`, so a non-optional `Bool` defaulting to `false` is faithful), `hint: String?` | Optional-field presence is the dispatch key. |
| `EXERCISES` | `let EXERCISES: [ExerciseMeta]` — **array, order preserved** | `next()` walks it by index; the hub renders it in order. |
| `MODE_HINT`, `SPELL_HINT`, `MATCH_HINT` (`Record<Union, string>`) | `func hint(for: SyllableMode) -> String` etc., implemented as exhaustive `switch` | A TS `Record` keyed by a string union is a total function; a Swift `switch` is the faithful port and keeps it total. A `Dictionary` would not. |
| `MIXED_HINT` | `let MIXED_HINT: String` | |
| `Profile` / `ProfileAPI` | `@MainActor final class ProfileStore: ObservableObject` exposing `profile`, `children`, `activeId`, `preview(_:_:)`, `createChild`, `selectChild`, `renameChild`, `deleteChild`, `switchChild` | React context → `@EnvironmentObject`. |
| `EntitlementAPI` | `@MainActor final class EntitlementStore: ObservableObject` | Same. |
| `Mood`, `Verdict` | `enum Mood { case idle, happy, cheer }`, `enum Verdict { case accept, reject }` | |
| `MascotConfig`, `ChildProfile` | Owned by the profile/mascot agents. | |

### 3.3 `AudioApi` → `protocol AudioEngine`

```ts
say: (text: string, opts?: { rate?: number; pitch?: number }) => Promise<boolean>;
```

```swift
public struct SayOptions: Sendable, Equatable {
    public var rate: Double  = 0.94   // useAudio's default
    public var pitch: Double = 1.1
}

@MainActor public protocol AudioEngine: AnyObject {
    func unlock()
    func pop()
    func success()
    func nudge()
    func oops()
    /// `true` = played to natural completion. `false` = superseded, errored, or watchdogged.
    /// Never throws, never hangs. Callers gate their next step on the Bool.
    @discardableResult func say(_ text: String, options: SayOptions) async -> Bool
    func stop()
}
```

`Promise<boolean>` that "never rejects" → `async -> Bool`, **not** `async throws`. The TS
contract is explicit that it never rejects; making it `throws` would invite a `try?` that
silently swallows the distinction between `false` and "no answer".

`opts?` with two optional members → one `SayOptions` struct with defaults, plus a
`func say(_ text: String) async -> Bool` convenience extension with `options: SayOptions()`.
TS optional-with-default destructuring (`{ rate = 0.94, pitch = 1.1 } = {}`) does not have a
Swift equivalent at the call site, so the defaults live in the struct.

### 3.4 `Record<ExerciseId, {tint, glyph}>` → an exhaustive function

This is the index-signature case that matters, so it gets its own subsection.

```ts
const GLYPHS: Record<ExerciseId, { tint: string; glyph: ReactNode }> = { ... };
export function ExerciseIcon({ id, size = 30 }) {
  const { tint, glyph } = GLYPHS[id];
  ...
}
```

A `Record<K, V>` over a **closed** key union is a total function, and TypeScript enforces
totality at the object literal. The Swift port must preserve totality *at compile time*:

```swift
public struct ExerciseIconSpec {
    public let tint: Color
    public let nodes: [IconNode]
}

public func exerciseIconSpec(_ id: ExerciseId) -> ExerciseIconSpec {
    switch id {                                 // NO `default:` — that is the whole point
    case .firstLetter:              return .init(tint: .hex("#FF8A5B"), nodes: [...])
    case .findSound:                return .init(tint: .hex("#7CB342"), nodes: [...])
    ...
    case .spellTwoSyllablesMixed:   return .init(tint: .hex("#2E7D5B"), nodes: [...])
    }
}
```

Adding an `ExerciseId` case without a branch here is a **compile error**
(`switch must be exhaustive`), exactly as adding one without a `GLYPHS` entry is a
type error today. Three rules to keep it that way, all of which must be stated in the file
header:

1. `ExerciseId` must not gain `default`-shaped escape hatches (`@unknown default` is only
   for library-evolution enums; this one is in-module and frozen, so it is not allowed).
2. `ExerciseId` and `exerciseIconSpec` must live in the **same package** — a `switch` over an
   enum from a *resilient* (library-evolution-enabled) module requires `@unknown default`,
   which would silently defeat the check. The package builds without library evolution, so
   this holds; do not enable `-enable-library-evolution`.
3. No `Dictionary<ExerciseId, ExerciseIconSpec>` shortcut. A dictionary lookup returns an
   optional and pushes the failure to runtime — that is the exact regression invariant 7
   exists to prevent.

A `Sources/ALArt` unit test additionally iterates `ExerciseId.allCases` and asserts every
spec has a non-empty `nodes` array and a tint distinct from all others — that catches a
copy-paste branch that compiles but reuses another icon.

### 3.5 The `<text>` element — explicit answer

`Glyph` is the only `<text>` in the file:

```tsx
<text x={x} y={y} textAnchor="middle" dominantBaseline="central"
      fontFamily={ROUNDED} fontWeight={900} fontSize={size} fill="#fff">
```

used four times: `A` at (15, 17.5) size 17 (`first-letter`), `V` at (11, 16) size 17
(`pick-vowel`), `A` at (12, 18) size 16 and `a` at (22, 19) size 11 (`match-case`).

**Decision: render it as a SwiftUI `Text` overlay, not as a converted path.** Reasons:

- `ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif"`. On iOS the PWA already
  resolves that to **SF Pro Rounded** — the same face `Font.system(size:weight:design:.rounded)`
  gives. A `Text` overlay is therefore *closer* to the PWA than a traced path would be, and
  it stays correct across OS font revisions and Dynamic Type-independent sizing.
- D2 forbids retyping geometry. Tracing a glyph into a `d` string is exactly that: retyping
  geometry, with no source `d` string to copy from. Converting via CoreText at build time
  would produce geometry no human reviewed, frozen at one OS version.
- SwiftUI has no `<text>` inside `Path`, so a `Text` in the same `ZStack`, positioned in the
  scaled 32-unit space, is the only structural option that does not involve tracing.

Anchoring must be reproduced deliberately — `Text` centres on its **line box** (which
includes leading), SVG anchors on the **font's central baseline**. `SVGText.swift` owns this:

```
x with textAnchor="middle"        → horizontal centre of the drawn glyph run
y with dominantBaseline="central" → the "central" baseline: the alphabetic baseline
                                    raised by (ascent - descent) / 2
```

so the concrete placement is: lay the `Text` out with `.fixedSize()`, take `ctFont`'s
`ascent`/`descent` for the resolved rounded face at `fontSize`, and place the view's centre
at `(x, y - lineBoxCentreOffset + (ascent - descent)/2)` in 32-space, where
`lineBoxCentreOffset` is the offset from the line-box centre to the alphabetic baseline
(`(ascent + descent)/2 - descent`). Both terms are computable on the host, so the arithmetic
is unit-tested in `ALArtTests` against known CoreText metrics; the final visual match is a
D3 pixel-diff item.

`fontWeight={900}` → `.black`. SVG `fontSize` is in 32-unit space; multiply by `size/32`
along with everything else — apply the scale with a single `.scaleEffect(size/32, anchor: .topLeading)`
on the whole 32×32 `ZStack` so text and paths scale identically and cannot drift apart.

### 3.6 The other SVG attributes actually used in `ExerciseIcon`

Enumerated so `IconStyle` covers them exactly and nothing more:

| Attribute | Where | SwiftUI |
|---|---|---|
| `fill="#fff"` / `fill={tint}` / `fill="none"` | everywhere | `Path().fill(...)` or skip the fill |
| `stroke="#fff"` / `stroke={tint}` | the `line` spread, `ShuffleChip` | `Path().stroke(...)` |
| `strokeWidth` 1.7 / 1.8 / 1.9 / 2 / 2.2 / 2.4 / 2.6 / 2.8 | per-node overrides of `line`'s 2.4 | `StrokeStyle(lineWidth:)` |
| `strokeLinecap="round"`, `strokeLinejoin="round"` | the `line` spread | `StrokeStyle(lineCap: .round, lineJoin: .round)` |
| `strokeDasharray="2.5 2.3"` / `"2.4 2.2"` | `pick-vowel`, `fill-blank` | `StrokeStyle(dash: [2.5, 2.3])` |
| `opacity` 0.65 / 0.7 / 0.72 / 0.8 | six nodes | `.opacity(_)` on the individual shape |
| `rx` on `<rect>` | 1.7 / 2.2 / 2.4 / 2.5 / 2.8 / 3 / 8.5 | `RoundedRectangle(cornerRadius:)` — SVG `rx` with no `ry` means `ry = rx`, a uniform corner |

The `line` object is spread into 8 paths and 2 circles; port it as a `static let line: IconStyle`
and use `IconStyle.line.with(strokeWidth: 2.8)` at the override sites, so the base values are
written once, exactly as in the TSX.

---

## 4. Behaviour notes

### 4.1 The router — the seven gates, in order

`App.tsx` returns early seven times before it reaches the hub. Order is behaviour:

```
1.  hash === "#stages"   → <MascotGallery onClose={hash=""} />
2.  hash === "#vo"       → <VoGallery onClose={hash=""} />
3.  !activeId            → <WhoIsPlaying />
4.  !profile.chosen      → <Picker variant="first-run" onDone={setView(hub)} />
5.  view.kind === "play" → the exercise dispatch (§4.3)
6.  view.kind === "dashboard" | "shop" | "pick"
7.  otherwise            → the hub
```

Consequences that must survive the port:

- The dev screens win over **everything**, including "no active player".
- Gates 3 and 4 sit **above** the play branch: a route of `.play(...)` is silently overridden
  if the active child disappears or has not chosen a species. Do not reorder for tidiness.
- `Picker(variant: .firstRun)`'s `onDone` sets the route to `.hub` — the child is not
  returned to whatever they tapped.
- `.shop`'s back goes to `.dashboard`, not `.hub`. `.pick`'s `onDone` **and** `onCancel`
  both go to `.dashboard`.

Swift shape:

```swift
struct RootView: View {
    @EnvironmentObject var profiles: ProfileStore
    @Environment(\.alDevScreen) var devScreen
    @State private var route: AppRoute = .hub

    var body: some View {
        if let dev = devScreen { DevScreenView(dev) }
        else if profiles.activeId == nil { WhoIsPlayingView() }
        else if !profiles.profile.chosen { PickerView(variant: .firstRun) { route = .hub } }
        else if case let .play(exercise, level) = route { playScreen(exercise, level) }
        else if route == .dashboard { DashboardView(...) }
        else if route == .shop { ShopView { route = .dashboard } }
        else if route == .pick { PickerView(variant: .switchMascot) { route = .dashboard } onCancel: { route = .dashboard } }
        else { HubView(open: { route = .play(exercise: $0, level: $1) }, ...) }
    }
}
```

Use `if/else if` in `body`, not a `switch` on `route` — the gates are not all route
conditions, and a `switch` would force the profile gates into the wrong layer. `@ViewBuilder`
handles the branch-type differences.

**No `NavigationStack`.** The PWA replaces the whole screen: no push animation, no back
chrome, no stack. Each screen provides its own "← Menu" affordance. Introducing a
`NavigationStack` would add a system back button and an interactive-pop gesture that do not
exist in the PWA, and would break the "Suivant rolls into the next exercise" model (which
*replaces* rather than pushes).

### 4.2 Dev screens (`#stages`, `#vo`)

iOS has no URL hash. Replace with a launch-argument/`UserDefaults` resolution behind
`#if DEBUG`, surfaced through `@Environment(\.alDevScreen)`:

```
-ALDevScreen stages   → .stages
-ALDevScreen vo       → .vo
```

In a Release build `alDevScreen` is always `nil` and both screens are unreachable, which
matches "out of kid flow". `onClose` clears the environment value (a `@State` in the app
root), mirroring `window.location.hash = ""`. **This needs an app-level decision** because
`MascotGallery` / `VoGallery` belong to other agents (§8).

### 4.3 The exercise dispatch — eight ordered branches

```tsx
if (view.exercise === "read-image")  return <ReadImageExercise .../>;
if (view.exercise === "spell-sound") return <SpellSoundExercise .../>;
if (view.exercise === "find-sound")  return <FindSoundExercise .../>;
if (view.exercise === "sound-twins") return <SoundTwinsExercise .../>;
if (meta.grid)   return <SyllableGridExercise  mode={meta.grid}  .../>;
if (meta.spell)  return <SpellSyllableExercise mode={meta.spell} mixed={meta.mixed} .../>;
if (meta.match)  return <LetterMatchExercise   kind={meta.match} .../>;
return meta.mode ? <AssembleExercise mode={meta.mode} .../> : <FirstLetterExercise .../>;
```

Four id checks first, then four capability checks. Ported as a pure function so it is
host-testable:

```swift
public enum ExerciseEngine: Equatable {
    case readImage
    case spellSound
    case findSound
    case soundTwins
    case syllableGrid(SyllableGridMode)
    case spellSyllable(SpellSyllableMode, mixed: Bool)
    case letterMatch(LetterMatchKind)
    case assemble(SyllableMode)
    case firstLetter
}

public func engine(for meta: ExerciseMeta) -> ExerciseEngine {
    switch meta.id {
    case .readImage:  return .readImage
    case .spellSound: return .spellSound
    case .findSound:  return .findSound
    case .soundTwins: return .soundTwins
    default: break
    }
    if let grid  = meta.grid  { return .syllableGrid(grid) }
    if let spell = meta.spell { return .spellSyllable(spell, mixed: meta.mixed) }
    if let match = meta.match { return .letterMatch(match) }
    if let mode  = meta.mode  { return .assemble(mode) }
    return .firstLetter
}
```

(The `default: break` here is on the *id* pre-check, not on an exhaustive-icon switch — it is
the faithful port of four `if`s and carries no invariant.)

`RootView` then maps `ExerciseEngine` → the concrete SwiftUI screen and applies
`.id(route.remountKey)`.

### 4.4 `next()` — the "Suivant" roll-over

```tsx
const next = () => {
  if (view.level < meta.levelCount)
    return setView({ kind: "play", exercise: view.exercise, level: view.level + 1 });
  const nextEx = EXERCISES[exIdx + 1];
  setView(nextEx ? { kind: "play", exercise: nextEx.id, level: 1 } : { kind: "hub" });
};
```

Past an exercise's last level it rolls into **level 1 of the next exercise**, and past the
final exercise back to the hub. Pure port:

```swift
public func nextRoute(after route: AppRoute, in catalog: [ExerciseMeta]) -> AppRoute {
    guard case let .play(exercise, level) = route,
          let idx = catalog.firstIndex(where: { $0.id == exercise }) else { return .hub }
    let meta = catalog[idx]
    if level < meta.levelCount { return .play(exercise: exercise, level: level + 1) }
    if idx + 1 < catalog.count { return .play(exercise: catalog[idx + 1].id, level: 1) }
    return .hub
}
```

`EXERCISES.findIndex` returning `-1` would make `meta` `undefined` and crash on
`meta.levelCount` in the PWA; unreachable in practice. The Swift `guard` returns `.hub` for
that branch — a deliberate divergence in an unreachable path, recorded in §7.

Because `key` changes on every `next()`, the exercise **remounts**: fresh seeding, fresh
audio, fresh star array. `.id(route.remountKey)` gives exactly that (SwiftUI tears down and
rebuilds the subtree, discarding all `@State`). Getting this wrong is silent — the run
continues with the previous level's seed — so it needs a test (§6).

### 4.5 The GameFrame star strip (invariant 8's visible half)

```tsx
const fontSize = total > 9 ? 16 : 20;
if (i < done || (i === done && !stars[i]))  → <span style={{fontSize, ...(stars[i] ? null : LOST)}}>⭐</span>
if (i === done)                              → <span className="motion-safe:animate-pulse" style={{fontSize, opacity: 0.8}}>⭐</span>
otherwise                                    → <span style={{fontSize, opacity: 0.28}}>•</span>
const LOST = { filter: "grayscale(1)", opacity: 0.45 };
```

Four states, ported to `StarCell`:

| Condition | Cell | Rendering |
|---|---|---|
| `i < done` and `stars[i]` | `.earned` | ⭐ full |
| `i < done` and `!stars[i]` | `.lost` | ⭐ `.grayscale(1).opacity(0.45)` |
| `i == done` and `!stars[i]` | `.lost` | same — **this is the instant-grey**: the round is still live, but the star already reads as gone |
| `i == done` and `stars[i]` | `.live` | ⭐ pulsing |
| `i > done` | `.pending` | • at `opacity 0.28` |

Two details that are easy to lose:

- **The pulse overrides the inline opacity.** `motion-safe:animate-pulse` is Tailwind's
  `animation: pulse 2s cubic-bezier(0.4,0,0.6,1) infinite` with `50% { opacity: .5 }`. A CSS
  animation outranks an inline style for the animated property, so the live star actually
  breathes between **1.0 and 0.5**, and the inline `opacity: 0.8` is only visible under
  reduced motion (where `motion-safe:` drops the class). Port: reduce-motion → static 0.8;
  otherwise a repeating 2 s `CAKeyframeAnimation` on `opacity` `[1, 0.5, 1]` with the
  `(0.4,0,0.6,1)` timing function, run on a `LayerHost` layer so it stays off the render path.
- The strip must re-render **synchronously with the pointerdown** that greys a star. This is
  the one place in this scope where a state-driven re-render is required and correct: the
  exercise's `stars: [Bool]` is `@Published` on its view model, the pick handler flips
  `stars[i] = false` on the main actor in the same turn as the SFX, and SwiftUI commits
  before the next frame. Do not debounce, animate, or batch this.

Layout: the strip lives in a wrapping centre-aligned row (`flex-wrap`, `gap-x-1 gap-y-0.5` =
4 pt / 2 pt) between the Menu button and a **`hidden … sm:block` 84 pt spacer** that only
exists at viewport ≥ 640 px. On a phone the strip is therefore *not* optically centred — it
is pushed right by the Menu button's width. Odd, frozen; see §7.

Confetti `<canvas>` sits at `zIndex 40` full-bleed and `pointer-events: none`; the header row
is at `z-[41]`, and `Finished` is also `z-[41]`. In SwiftUI: the frame content is a `ZStack`
with the confetti overlay inserted between the background and the header,
`.allowsHitTesting(false)` on the overlay.

### 4.6 `FitLine` and the "shrunk word row collapsing onto the tray" bug

The comment in `FitLine.tsx` is the bug report:

```
`items-start` on the wrapper keeps them natural even once the wrapper's own height is
pinned below — otherwise the row would stretch to the pinned height, and each re-fit
would shrink it again (a ResizeObserver loop that collapses the row onto the tray beneath it).
```

The mechanism: the outer wrapper's height is pinned to `naturalHeight * k`; if the inner row
were allowed to *stretch* to that pinned height (flex default `align-items: stretch`), the
next measurement would read a smaller natural height, compute a smaller `k`, pin a smaller
height, and so on — a measurement feedback loop that visually collapses the word row down
onto the tray. `items-start` makes the inner row's natural size independent of the wrapper's
imposed height, which breaks the cycle.

Current logic:

```ts
const natural = inner.offsetWidth;              // untransformed layout width
const naturalHeight = inner.offsetHeight;
const available = outer.clientWidth;
const k = natural > 0 && natural > available ? available / natural : 1;
inner.style.transform = k < 1 ? `scale(${k})` : "";
outer.style.height = k < 1 ? `${naturalHeight * k}px` : "";
// transformOrigin: "center top"
```

**SwiftUI expression, with the same failure mode structurally impossible:**

```swift
struct FitLine<Content: View>: View {
    @ViewBuilder var content: Content
    @State private var natural: CGSize = .zero

    var body: some View {
        GeometryReader { proxy in                       // available width, one-way
            let k = fitScale(natural: natural.width, available: proxy.size.width)
            HStack(spacing: rowSpacing) { content }
                .fixedSize()                            // <- the `items-start` equivalent
                .measured(into: $natural)               // PreferenceKey, reports the natural size
                .scaleEffect(k, anchor: .top)           // transformOrigin: center top
                .frame(width: proxy.size.width, alignment: .center)
        }
        .frame(height: natural.height * fitScale(natural: natural.width, available: lastAvailable))
    }
}
```

The critical piece is `.fixedSize()`: it makes the row lay out at its **ideal** size,
independent of the proposal it receives. The measured `natural` therefore cannot change when
the outer width or the pinned height changes, so the write of `natural` cannot be re-triggered
by its own consequences. That is the SwiftUI analogue of `items-start`, and it is why the
loop cannot recur. The three rules for anyone touching this file:

1. `.fixedSize()` stays. Removing it re-opens the exact bug in SwiftUI form (a
   `PreferenceKey` write → layout → new preference → write cycle, which SwiftUI resolves by
   either oscillating or emitting "Bound preference … tried to update multiple times").
2. Never feed `proxy.size` back into the *measured* value — only into `k`.
3. `fitScale` is pure and lives in ALCore:
   `natural > 0 && natural > available ? available / natural : 1`. Note it clamps at 1 — the
   row is never enlarged, only shrunk.

`scaleEffect` (like CSS `transform: scale`) does not change layout, matching the PWA where the
wrapper height is pinned separately. `anchor: .top` == `transformOrigin: center top`.

Callers pass margins on the outer (`className="mb-6"` etc.) and the gap on the inner
(`rowClassName="gap-2"` = 8 pt, `"gap-1.5"` = 6 pt) — keep the two-parameter shape so the four
call sites (`AssembleExercise`, `SpellSyllableExercise`, `SpellSoundExercise`,
`ReadImageExercise`) port unchanged.

### 4.7 `Tile` — invariants 1, 3 and 6 all land here

```tsx
onPointerDown={(e) => {
  if (disabled) return;
  el?.animate(press, { duration: 130, easing: "ease-out" });
  if (onPick() === "reject") el?.animate(shake, { duration: 300, easing: "ease-in-out" });
}}
```

- `press`: `scale(1) → scale(0.9) → scale(1)`, 130 ms, `ease-out`.
- `shake`: `translateX 0 → -8 → 8 → -5 → 0`, 300 ms, `ease-in-out`.
- Both fire **before** `onPick` returns / before any state update. In Swift:

```swift
.gesture(TouchDownGesture {                 // UILongPressGestureRecognizer(min: 0), .began
    guard !disabled else { return }
    Anim.press(layer)                       // CABasicAnimation on the hosted CALayer — synchronous
    if onPick() == .reject { Anim.shake(layer) }
})
```

`onPick` is a **synchronous, non-async closure returning `Verdict`**. Do not make it `async`:
the SFX and the verdict must land in the same turn as the touch.

A reject shakes and nothing else — no lock, no disable, no error state (invariant 3).
`disabled` is set by the exercise (already-used tray tile), never by a wrong answer.

Sizing: `dim = size ?? clamp(92px, 27vw, 150px)`, `minWidth = dim`, `width: auto`,
`height = dim`, horizontal padding `clamp(10px, 3vw, 20px)`, `fontSize = fontSize ?? clamp(30px, 9vw, 64px)`,
`borderRadius: 28`. The 92 pt floor is the accessibility floor (invariant 6) — never
parameterise it below that.

Shadows:
- normal: `0 8px 0 rgba(0,0,0,0.12), 0 12px 20px rgba(0,0,0,0.14)`
- highlight: `0 0 0 6px #66BB6A, 0 10px 22px rgba(0,0,0,0.18)`

The `0 0 0 6px` is a **spread**, i.e. a 6 pt ring *outside* the border box whose corner radius
is `28 + 6 = 34`. Port as `.overlay(RoundedRectangle(cornerRadius: 34).stroke(green, lineWidth: 6).padding(-3))`,
not as a `.shadow`. `transition: box-shadow 0.15s` → `.animation(.easeInOut(duration: 0.15), value: highlight)`
(a property transition on a leaf, not an interaction animation — permitted).

The Écouter variant is a **sibling**, in a `VStack(spacing: 8)` with `minWidth: dim` on the
column. It has its own `press` animation and its own `onPointerDown`. `Tile.test.tsx`'s three
assertions carry over verbatim (§6). `aria-label` → `.accessibilityLabel`, default
`previewLabel ?? "Écouter"`.

### 4.8 The hub

Header row (`max-w-md`, space-between):
- Left: player chip, `aria-label="Changer de joueur"`, content `👤 {activeChild.name}`,
  `max-w-[55%]` + `truncate`, calls `switchChild()` (which sets `activeId = nil` → gate 3
  → "Qui joue ?").
- Right: 🔊 chip, `aria-label="Écouter mes points"` → `audio.unlock(); audio.say(String(balance), rate: 0.85)`.
  Note the **rate override 0.85** and that the text is the bare digit string — fr-FR TTS reads
  "42" as « quarante-deux ». There is no baked clip; this always falls through to
  speech synthesis.
- Right: ⭐ chip, `aria-label="Mon copain et mes points"`, content `⭐ {balance}` → `.dashboard`.

Then the mascot button (`aria-label="Voir mon copain"`, `<Mascot size={112} mood="idle">`) →
`.dashboard`; `<h1>` « Attrape-Lettres » at `clamp(28px,8vw,44px)`; `<p>` « Choisis un jeu et un niveau. ».

Per exercise, one section (`max-w-md`, `mb-6`):
- A wrapping chip row: `<ExerciseIcon id size={30}>`, the name at `text-xl font-extrabold`,
  then **up to four** hint chips at `text-sm` in `#9A7A5A`, each prefixed `"· "`, in this order
  and each only when its field is set:
  `MODE_HINT[ex.mode]`, `ex.mixed ? MIXED_HINT : SPELL_HINT[ex.spell]`, `MATCH_HINT[ex.match]`, `ex.hint`.
  (No exercise sets more than one today, but the markup allows it — port all four
  conditionals.)
- A `grid-cols-5 gap-2` of `aspect-square` buttons, `1...levelCount`.

Each level button:

```tsx
const pts = preview(ex.id, lvl);
const jackpot = pts === 10;
aria-label = pts > 0
  ? `Niveau ${lvl}, gagne ${pts} ${pts > 1 ? "étoiles" : "étoile"}`
  : `Niveau ${lvl}, pour s'entraîner`;
```

`pts > 0` adds a corner pill, `aria-hidden`:
- jackpot: `-right-2 -top-2`, `bg #FFC107`, ink `#4A3B00`, `text-sm`, `ring-2 ring-white`, `+N ⭐`
- otherwise: `-right-1 -top-1`, `bg white`, ink `#B07A00`, `text-[11px]`, `ring-1 ring-[#FFE08A]`, `+N 🪙`

In SwiftUI, `.overlay(alignment: .topTrailing) { pill.offset(x: 8, y: -8) }` (or 4/-4);
SwiftUI does not clip overlays, so no `overflow: visible` equivalent is needed.
`ring-N` → `.overlay(Capsule().strokeBorder(color, lineWidth: N))`.

`preview(ex.id, lvl)` is called during render, once per level button (~75 calls per hub
paint). It is a pure read of the folded ledger; it must never write state. Keep it a plain
function call in `body`.

**Emoji in the hub chrome (👤 🔊 ⭐ 🪙) stays.** Invariant 7 is about *exercise icons*; the
hub already renders `<ExerciseIcon id>` and never `ex.emoji`. `ExerciseMeta.emoji` still
exists in the data and must remain unused by the hub — an implementer "simplifying" by
rendering `meta.emoji` would break invariant 7 without touching `ExerciseIcon.swift`.

### 4.9 `Dashboard`

- `pct = ((stage + 1) / GROWTH_STAGES) * 100`.
- `ResizeObserver` on the root → `mascotSize = round(min(230, max(140, width * 0.46)))`.
  Port with a `GeometryReader` at the root reporting width into `@State`; the value feeds a
  size, never a measurement loop (the mascot does not influence the root width — it is
  inside a pedestal with `width: clamp(190px, 62%, 300px)`).
- The balance pill uses `usePopFlourish` (fires on every dashboard open, because the view is
  freshly created each time gate 6 is taken).
- The growth bar: the final width is written directly (`el.style.width = pct%`) and then a
  900 ms `cubic-bezier(.2,.9,.3,1)` WAAPI sweep replays it from `0%`. Under reduced motion the
  sweep is skipped and the bar is simply at `pct`. Port with `Anim.widthSweep(layer, to: pct)`
  on a `LayerHost`-backed fill, **not** `withAnimation`.
- `role="progressbar"` + `aria-valuemin/max/now` → `.accessibilityElement()` with
  `.accessibilityValue("\(stage + 1)")` and `.accessibilityLabel("Croissance de ton copain")`.
- Copy: « Mon copain », « ← Menu », « étoiles à dépenser », « 🌱 Croissance », « Boutique 🛍️ »,
  « Changer de copain 🔄 », and `aria-label` `Tu as {balance} {balance > 1 ? "étoiles" : "étoile"}`.

### 4.10 `WhoIsPlaying` — the roster UI

- `creating` initialises to `children.length === 0`, so an empty device opens straight on the
  name form with **no cancel button** (`onCancel = null` when the roster is empty).
- `NewProfile`: `maxLength 14`, `autoFocus`, `aria-label="Ton prénom"`,
  `placeholder="Ton prénom"`, submit disabled while `name.trim()` is empty, button
  « C'est parti ! 🎉 ». Submitting calls `createChild(name)` — the **untrimmed** value; the
  profile hook does the trimming. Port that faithfully.
  - `maxLength` in the DOM counts UTF-16 code units. `String.count` counts grapheme clusters.
    For a first name they agree; for an emoji-containing name they do not. Use
    `String(name.prefix(14))` on grapheme clusters and record the divergence (§7).
- `ChildCard`: whole card is a button. `onPointerDown` fires `press(el)` (shop `anim.ts`);
  `onClick` dispatches to rename (edit mode) or pick. `aria-label` is
  `Renommer {name}` in edit mode, `Jouer avec {name}` otherwise. Edit mode also overlays a
  ✏️ at `-left-2 -top-2` and a ✕ at `-right-2 -top-2`.
- `Avatar`: `!chosen` → 🦉 at `size * 0.72`; otherwise `<Mascot config={p.species[p.current].config} size={84}>`.
- Rename: `window.prompt(`Nouveau prénom pour ${c.name} ?`, c.name)`; `null` (cancel) → no
  call; anything else → `renameChild(c.id, next)` (the hook ignores empty).
- Delete: `window.confirm(`Supprimer le profil de ${c.name} ? Tout sera perdu.`)`.
- Both become SwiftUI `.alert`s. `prompt` → `.alert(_, isPresented:) { TextField(...) ; Button("OK"); Button("Annuler", role: .cancel) }` seeded with the current name.
  `confirm` → a two-button alert with a `.destructive` role. The browser dialogs are
  *synchronous and blocking*; the SwiftUI ones are not. No observable behaviour differs
  (nothing runs between the call and the answer in the PWA either), but the code shape does —
  do not try to reproduce the blocking call.
  **The alert button titles are not in the PWA** (they are browser chrome). Use « OK » /
  « Annuler » and flag the choice for review (§7).
- The "Nouveau" tile: dashed `3px #E4A15E` border, `minHeight 150`, glyph `＋` (U+FF0B
  FULLWIDTH PLUS SIGN, **not** `+`) at 46 px, label « Nouveau », `aria-label="Nouveau profil"`.
- The header toggle reads « Modifier » / « Terminé ».

### 4.11 `Onboarding` — the consent and disclosure screen

Three things this screen must keep, in order of how badly it hurts to lose them:

1. **The checkbox starts unticked.** `useState(false)`, never `useState(hasConsent)`.
   Pre-ticked consent has been invalid since CJEU *Planet49*. In Swift:
   `@State private var analytics = false` — and it must not be seeded from anything.
2. **Terms are disclosed before the trial starts.** The copy renders *above* the
   « Commencer » button, and `beginTrial()` is only called from `start()`.
3. `start()` order is `setConsent(analytics)` → `track("trial_started", { daysLeft: TRIAL_DAYS })`
   → `beginTrial()`. `setConsent` first, so the very first tracked event already respects the
   answer.

Copy, exactly (`TRIAL_DAYS = 14`, `PRICE = "9,99"`, `priceLabel` from the store when known):

- Title: `Nous aussi, on est parents.`
- `storeAvailable` branch, two paragraphs:
  - `Attrape-Lettres est gratuit pendant <strong>14 jours</strong>. Ensuite, un achat unique de <strong>{priceLabel ?? "9,99 €"}</strong> débloque tout {scope}, pour toujours. Pas d'abonnement, pas de publicité, rien à acheter dans le jeu.`
  - `Après 14 jours, les exercices se mettent en pause. Les progrès, les étoiles et les mascottes sont gardés.`
- `!storeAvailable` branch, one paragraph:
  - `Attrape-Lettres apprend à lire aux enfants de six ans. Pas de publicité, pas de compte, rien à acheter — et tout fonctionne sans connexion.`
- Button: `Commencer les 14 jours` / `Commencer`
- Consent label: bold `Nous aider à améliorer le jeu`, then
  `On reçoit seulement : quel exercice, quel niveau, réussi ou non. Jamais le prénom de votre enfant, jamais rien qui l'identifie. Vous pouvez changer d'avis à tout moment.`

**The family-sharing copy in a pure-iOS port.** Today:

```tsx
const ios = Capacitor.getPlatform() === "ios";
const scope = ios ? "pour toute la famille" : "sur vos appareils";
```

Apple Family Sharing genuinely covers the €9.99 non-consumable for six people; Google Play
Family Library explicitly never shares in-app purchases, which is why the Android string
promises less. In a Swift/iOS-only port `ios` is a compile-time constant `true`, so:

- `scope` is **always** `"pour toute la famille"`.
- Do **not** delete the Android string from the design. Keep it in `Copy.swift` as
  `static let scopeOtherPlatforms = "sur vos appareils"` with the comment explaining why it
  exists, so the day this becomes a Catalyst/Android target the wrong promise is not the
  default. Ship the iOS branch, keep the fork visible.
- This promise is only true if **Family Sharing is actually enabled on the non-consumable in
  App Store Connect** — a store-configuration dependency, not a code one. Record it in the
  release checklist.

The bold runs (`<strong>`) inside the paragraphs: build with SwiftUI `Text` concatenation
(`Text("…") + Text("14 jours").bold() + Text("…")`), not Markdown/`AttributedString`
inference, so the exact substring stays under the implementer's control.

**All French copy uses `Text(verbatim:)`.** A bare `Text("…")` is a `LocalizedStringKey`
lookup; with no strings table it falls back to the literal, but any string containing `%`
or `**` would be reinterpreted. `verbatim:` removes the whole class of bug.

### 4.12 `ParentalGate`

```ts
function roll(): [number, number] {
  const d = () => 3 + Math.floor(Math.random() * 7);   // 3..9
  return [d(), d()];
}
const [[a, b]] = useState(roll);      // lazy initialiser: once per mount
```

Product range 9..81, never a trivial ×1 or ×2. Re-rolled on every open so a child who watches
once learns nothing.

Swift trap to avoid: `@State private var challenge = GateChallenge.roll()` re-evaluates the
initialiser expression whenever the `View` struct is recreated (the value is discarded, but
the RNG is still consumed and it is confusing). Prefer an explicit `init` that assigns
`_challenge = State(initialValue: GateChallenge.roll())`, and make sure the gate view has a
**fresh identity** each time `step` becomes `.gate` (it does: the Paywall's `if step == .gate`
branch creates it). Add `.id(challengeGeneration)` if the branch is ever hoisted.

Input: `e.target.value.replace(/\D/g, "").slice(0, 3)` and clear `wrong` on every keystroke.
`inputMode="numeric"` → `.keyboardType(.numberPad)`. `autoFocus` → `@FocusState` set in
`onAppear`. Submit is disabled at length 0; `Number(value) === answer` compares the parsed
integer.

Wrong → `wrong = true`, `value = ""`, refocus. Error text `Ce n'est pas le bon résultat.`
with `role="alert"` → `.accessibilityAddTraits(.isStaticText)` plus an
`AccessibilityNotification.Announcement` post so VoiceOver speaks it, matching the live region.

Presentation: `fixed inset-0 z-50` scrim `rgba(30,20,10,0.55)`, `role="dialog"`,
`aria-modal`, `aria-label="Espace parents"`. In SwiftUI a `.fullScreenCover` or a
`ZStack` overlay with `.accessibilityAddTraits(.isModal)`; prefer the ZStack overlay so the
scrim colour and the card styling are exactly ported (a system sheet brings its own chrome).

Copy: heading `Espace parents`, the caller's `reason` line, label `Combien font {a} × {b} ?`
(note `×` U+00D7, not `x`), buttons `Annuler` / `Continuer`.

### 4.13 `Paywall`

`Step = "child" | "gate" | "parent"` → `enum Step { case child, gate, parent }`.
Branch order in the render is `gate`, then `child`, then (fall-through) `parent`.

- **child**: 🌙 at `clamp(56px,17vw,90px)`, `Les jeux font une pause`,
  `Demande à un grand\u{00A0}!` — the `&nbsp;` before `!` is French typography and must be a
  real U+00A0 in the Swift literal — `Tes étoiles et ta mascotte t'attendent.`, a green
  `Voir ma mascotte` button calling `onBack`, and an underlined `Je suis un adulte` link
  → `.gate`. **No price, no buy button anywhere on this step** (Kids Category 1.3). A test
  asserts it.
- **gate**: `ParentalGate(reason: "Cette page contient un achat. Elle est réservée aux adultes.")`;
  `onPass` → `.parent` **and** `track("paywall_shown")` (the event fires on passing the gate,
  not on showing the child screen); `onCancel` → `.child`.
- **parent**: title `Débloquer Attrape-Lettres`; paragraph
  `Un achat unique de <strong>{priceLabel ?? "9,99 €"}</strong>. Pas d'abonnement, pas de publicité, rien d'autre à acheter. Les progrès de vos enfants sont déjà enregistrés.`
  - `storeAvailable`: `Débloquer — {priceLabel ?? "9,99 €"}` (shows `…` while `busy`) and
    `Restaurer un achat` (Apple requires a restore control for non-consumables).
  - `!storeAvailable`: `L'achat se fait depuis l'application installée sur le téléphone ou la tablette.`
  - `note` (`role="status"`): `L'achat n'a pas abouti. Rien n'a été débité.` on a failed buy;
    `Achat restauré.` / `Aucun achat trouvé sur ce compte.` after a restore.
  - The consent toggle again, this time **seeded from `hasConsent()`** (withdrawal must be as
    easy as consent, GDPR Art. 7(3)) and writing on every change.
  - `Retour au jeu` → `onBack`.
- `buy()`: `busy = true`, clear note, `await purchase()`, `busy = false`,
  `track(ok ? "purchase_completed" : "purchase_failed")`, then set the note only on failure.
- `redo()`: `busy = true`, clear note, `await restore()`, `busy = false`,
  `track("purchase_restored")` **unconditionally**, then the note either way.

`async` handlers become `Task { }` bodies on `@MainActor`; `busy` gates both buttons via
`.disabled(busy)` + `disabled:opacity-50`.

Invariant 11 note: nothing on this screen ever hard-locks. The Paywall is only reachable by
tapping an exercise (per the `View` union comment: "Reached only by tapping an exercise —
never a startup wall"), and `canPlay` returns `true` for `unknown`, so a store that has not
answered never shows it.

### 4.14 `useAudio` → `AudioEngine` + `VoiceChannel`

This is the largest single behavioural port in the scope. Split it:

**Pure, in `ALCore` (host-testable):**

- The ticket machine. `say()` bumps a ticket, stores the continuation, and an engine callback
  only settles the promise whose ticket is still current. A superseding `say()`/`stop()`
  force-settles the previous one `false`. This is what guarantees no caller ever hangs.
  Swift shape: an `@MainActor` class holding `var ticket: Int` and
  `var pending: CheckedContinuation<Bool, Never>?`; `settle(_:for:)` compares tickets.
  **A `CheckedContinuation` must be resumed exactly once** — `interruptCurrent` already
  guarantees that by nil-ing `pending` before resuming; keep that order.
- Watchdog durations, ported verbatim:
  - `WATCHDOG_MARGIN_MS = 800` — grace past a clip's known duration
  - `PROVISIONAL_WATCHDOG_MS = 8000` — held until a clip reports its duration
  - `TTS_MIN_MS = 1200`, `TTS_MS_PER_CHAR = 90` →
    `arm(max(1200, text.count * 90) / rate + 800)`
  - `TTS_HEARTBEAT_MS = 5000` — the Chrome pause/resume kick. **Not needed on
    `AVSpeechSynthesizer`**; port the constant and the code path as a no-op behind the
    backend protocol, and record it (§7). Do not delete it from the model — the TTS watchdog
    arithmetic is the part that matters and it must stay identical.
- `voiceScore` / `pickBestFr` over a `VoiceDescriptor`. The regexes port directly to
  `NSRegularExpression` or `range(of:options:.regularExpression)` with
  `.caseInsensitive`. The `AVSpeechSynthesisVoice` mapping is: `name` → `voice.name`,
  `lang` → `voice.language`, `localService` → `voice.quality != .default` is **not**
  equivalent; use `false` only when the voice is a network/personal voice. Nearest faithful
  mapping: `.enhanced`/`.premium` quality scores +5 (matching the `enhanced|premium|neural|siri`
  arm), and the `localService === false` +2 arm has no AVFoundation analogue — score it 0 and
  record the divergence (§7). The rest (`fr` prefix filter, `^fr-FR` +1, `compact|espeak` −5)
  port exactly.
- The blip specs (`ToneSpec`): `pop` 660 Hz / 0.09 s / triangle / 0.16;
  `success` = `[523.25, 659.25, 783.99, 1046.5]` each 0.16 s sine 0.16 at `i * 0.075` s;
  `nudge` 196 Hz / 0.14 s / sine / 0.10; `oops` 392 Hz / 0.18 s at t=0 and 311.13 Hz / 0.28 s
  at t=0.16, both sine 0.13. The gain envelope is
  `setValueAtTime(0.0001, t0)` → `exponentialRampToValueAtTime(gain, t0+0.008)` →
  `exponentialRampToValueAtTime(0.0001, t0+dur)`, oscillator stopped at `t0+dur+0.02`.

**Platform, in `App/SystemAudioEngine.swift`:**

- `unlock()` → activate the `AVAudioSession` (category `.ambient`, so the app never stops the
  parent's music — matching a browser tab's behaviour) and warm the speech synthesiser once
  (`unlockedRef` semantics: resume the context every time, warm speech only once).
- Clip playback: `clipUrl(text)` (VO agent) → `AVAudioPlayer`; `onended` → `settle(true)`,
  decode error → `settle(false)`, `onloadedmetadata` → re-arm from the real duration. A
  single reused player instance (the PWA reuses one `<audio>`).
- TTS fallback: `AVSpeechUtterance`, `rate`/`pitchMultiplier` mapped from the same
  `SayOptions` (note `AVSpeechUtteranceDefaultSpeechRate` is not 1.0 — the mapping must be
  written down and pixel/ear-checked; a naive `utterance.rate = 0.94` is far too fast).
- `stop()` → settle `false`, then a **200 ms fade** implemented as 10 volume steps 20 ms apart
  (or `AVAudioPlayer.setVolume(0, fadeDuration: 0.2)`, which is the same envelope in one
  call), then pause + rewind + restore volume to 1.
- The `useEffect` teardown closes the `AudioContext` per exercise mount. In Swift the engine
  is a single app-level object; the equivalent is releasing the player and deactivating the
  session when no exercise is on screen. Record as a deliberate lifecycle difference (§7) —
  the PWA's reason (browser context cap) does not exist on iOS.

`say()`'s "stable identity" note in the TSX (the memoised object) matters because
`FirstLetterExercise`'s announce effect depends on it. In SwiftUI the engine is an injected
reference type, so identity is stable by construction — but it must be injected via
`@Environment`/`@EnvironmentObject` as a **reference**, never recreated per view.

### 4.15 `useConfetti` → `ConfettiSystem` + `ConfettiOverlay`

Particle model, ported verbatim:

```
COLORS = ["#FF8A65","#FFD54F","#4FC3F7","#AED581","#BA9EE8","#F06292"]
fire(): 90 particles
  a  = random()*π − π            // upward hemisphere
  sp = 4 + random()*7
  x  = width/2 + (random()−0.5)*120
  y  = height*0.42
  vx = cos(a)*sp
  vy = sin(a)*sp − 3
  g  = 0.22
  r  = 5 + random()*6
  rot= random()*6.28
  vr = (random()−0.5)*0.4
  life = 1
step (per frame): vy += g; x += vx; y += vy; rot += vr; life -= 0.008
  drop when life <= 0 or y > height + 40
draw: globalAlpha = max(0, life); translate(x,y); rotate(rot);
      fillRect(-r/2, -r/2, r, r*0.6)
```

Two porting notes:

- **Drop `dpr`.** In the PWA the canvas is sized in device pixels and every velocity/size is
  multiplied by `devicePixelRatio`; `cx = canvas.width/2` is likewise in device pixels. The
  whole system is therefore *point-space identical* with `dpr = 1`. SwiftUI `Canvas` works in
  points, so set `dpr = 1` and the constants above are already correct. Multiplying by
  `displayScale` would make the burst 2–3× too fast.
- **Fixed timestep.** The rAF loop assumes ~60 fps (`life -= 0.008` ⇒ ~2 s lifetime, `g` per
  frame). `TimelineView(.animation)` fires at the display's rate, which is 120 Hz on ProMotion.
  `ConfettiSystem.advance(to date:)` must accumulate elapsed time and run **whole 1/60 s
  steps**, so the burst looks the same on every device. This is a required correction to keep
  behaviour frozen, not an improvement.

`ConfettiOverlay` is a `TimelineView(.animation) { ctx in Canvas { … system.draw(…) } }` leaf
inside `GameFrame`'s `ZStack`, `.allowsHitTesting(false)`. The redraw is confined to that leaf
(no ancestor state changes), which is the SwiftUI reading of invariant 2. `fire()` is a plain
method on the `ConfettiSystem` reference — callable synchronously from a pick handler, no
state publish. Reduced motion → `fire()` is a no-op, exactly as today.

### 4.16 `usePopFlourish`, `Ollie`, `WordIcon`

- `usePopFlourish`: `scale .4 → 1.18 (offset .68) → 1`, `opacity 0 → 1 → 1`, 480 ms,
  `cubic-bezier(.2,1.35,.4,1)` (an overshoot curve — `CAMediaTimingFunction(controlPoints:)`
  takes it directly). Fires **once on appear**, no-op under reduced motion. The React
  `StrictMode` double-mount makes it fire twice in dev only; do not reproduce that.
- `Ollie`: emoji faces `{idle: 🦉, happy: 🥳, cheer: 🤩}` at `clamp(52px,15vw,88px)`;
  `ollieBob` = 2.6 s `ease-in-out` infinite `translateY 0→−10→0` with `rotate −2°→2°→−2°`;
  `olliePop` = 0.5 s `ease` `scale 1 → 1.25 + rotate 6° @40% → 1`. Both frozen under
  reduced motion (the `@media` rule in `index.css` sets `animation: none !important`).
  Superseded by `Mascot` in the shell but still exported — port it, mark it legacy.
- `WordIcon`: `img` → the asset at `size × size`, `objectFit: contain`, `draggable=false`,
  `alt` (default `""`); else the emoji at `fontSize: size`, `lineHeight 1.1`, `aria-hidden`.
  In SwiftUI: `Image(...).resizable().scaledToFit().frame(width:height:)` vs
  `Text(verbatim: emoji).font(.system(size: size))` + `.accessibilityHidden(true)`.
  The `img` values are Vite asset URLs today; the port needs them as bundle resources — a
  content-agent dependency (§8).

---

## 5. Tailwind → SwiftUI

### 5.1 General strategy

**Port the computed value, never the class name.** A Tailwind class is shorthand for a fixed
number; the Swift code carries the number. There is no "Tailwind layer" in the port and no
attempt to build a utility DSL — that would add a translation step where bugs hide.

Three rules:

1. Anything that appears in more than one file becomes a token in `DesignSystem/Tokens.swift`
   (colours, the stage gradient, the rounded font, the two shadow recipes, the radii).
2. Anything that appears once is written inline as a literal, with the original class in a
   trailing comment: `.padding(.horizontal, 20)   // px-5`.
3. **Colours that come from data stay dynamic.** `tint` in `ExerciseIcon`, `bg`/`ink` on
   `Tile`, `MascotConfig.colors[slot]`, `CustomizationOption.value` — these are `Color` values
   computed from strings at runtime (`Color(hex:)`), never token constants, exactly as the
   PWA applies them via `style` rather than a class.

### 5.2 The scale actually used in this scope

| Tailwind | Value | SwiftUI |
|---|---|---|
| `px-3/4/5/6/7/8/9` | 12 / 16 / 20 / 24 / 28 / 32 / 36 | `.padding(.horizontal, _)` |
| `py-0.5/2/3/4` | 2 / 8 / 12 / 16 | `.padding(.vertical, _)` |
| `p-4/6` | 16 / 24 | `.padding(_)` |
| `gap-0.5/1/1.5/2/3/4/5/6` | 2 / 4 / 6 / 8 / 12 / 16 / 20 / 24 | `HStack/VStack(spacing:)` |
| `mb-1/2/4/5/6`, `mt-1`, `-mt-3` | 4 / 8 / 16 / 20 / 24, 4, −12 | `.padding(.bottom, _)` etc. |
| `rounded-2xl` / `3xl` / `full` | 16 / 24 / capsule | `RoundedRectangle(cornerRadius:)` / `Capsule()` |
| `text-sm/base/lg/xl/2xl` | 14 / 16 / 18 / 20 / 24 | `.font(AL.rounded(_, ...))` |
| `text-[11px]` | 11 | |
| `font-semibold/bold/extrabold/black` | 600 / 700 / 800 / 900 | `.semibold` / `.bold` / `.heavy` / `.black` |
| `max-w-xs/sm/md` | 320 / 384 / 448 | `.frame(maxWidth: _)` |
| `bg-white/55,70,80,92,95` | white at 0.55…0.95 | `.background(Color.white.opacity(_))` |
| `shadow` | `0 1px 3px rgb(0 0 0/.1), 0 1px 2px -1px rgb(0 0 0/.1)` | `.cssShadow(...)` ×2 |
| `shadow-2xl` | `0 25px 50px -12px rgb(0 0 0/.25)` | `.cssShadow(...)` |
| `ring-1`/`ring-2` | 1 pt / 2 pt outside stroke | `.overlay(Shape().strokeBorder(_, lineWidth:))` |
| `truncate` | ellipsis, one line | `.lineLimit(1).truncationMode(.tail)` |
| `leading-none` / `leading-snug` | 1.0 / 1.375 | `.lineSpacing(_)` derived from the font size |
| `active:scale-95` / `[0.97]` | 0.95 / 0.97 on press | `ALPressStyle(scale:)` |
| `aspect-square` | 1:1 | `.aspectRatio(1, contentMode: .fit)` |
| `grid-cols-5` / `grid-cols-2` | fixed columns | `LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 5))` |
| `disabled:opacity-40/50` | 0.4 / 0.5 | `.opacity(disabled ? 0.4 : 1)` |
| `[touch-action:none]` / `manipulation` | no scroll/zoom on the target, no 300 ms delay | no equivalent needed — a UIKit gesture recogniser already has neither |

**Shadow conversion rule, written once:** a CSS `box-shadow: Xpx Ypx Bpx rgba(...)` becomes
`.shadow(color: _, radius: B/2, x: X, y: Y)`. A CSS *spread* (`0 0 0 Npx`) is **not** a
shadow — it is a stroke of width `N` outside the border box at radius `r + N` (see §4.7).
`0 8px 0` (blur 0) is `radius: 0` — the "hard lip" under every green/gold button.

### 5.3 `clamp()` and viewport units — the one that will bite

The design uses `clamp(min, N vw, max)` in ~20 places (title, tile size, tile font, badge
padding, mascot pedestal, emoji sizes). `vw` is **1 % of the viewport (window) width**, and
critically it is *not* 1 % of the card: `index.css` caps `#root > *` at **480 px** while `vw`
keeps growing. On an iPad the card is 480 pt wide but `8vw` of a 1024 pt window is 82 pt.
Reproducing this with a `GeometryReader` around the card would give 38 pt — visibly wrong,
and it would silently pass a naive port.

Therefore:

```swift
private struct ALViewportWidthKey: EnvironmentKey { static let defaultValue: CGFloat = 390 }
extension EnvironmentValues { var alViewportWidth: CGFloat { ... } }
```

injected **once, at the app root, from the window size** (`WindowGroup` +
`GeometryReader` at the very top, or `UIScreen`/scene bounds), and consumed as:

```swift
func clampVW(_ lo: CGFloat, _ vw: CGFloat, _ hi: CGFloat, viewport: CGFloat) -> CGFloat {
    min(max(lo, viewport * vw / 100), hi)
}
```

The card itself is `min(480, viewport − 32)` wide with `max(16, safeAreaInset)` padding on all
four sides (`#root`'s `env(safe-area-inset-*)` rule), centred, top-aligned
(`place-items: start center`), and `min-h-[620px]` on the screen roots.

---

## 6. Invariant ownership

| Invariant | Where it lives in this design | What breaks it |
|---|---|---|
| **1 — feedback on pointerdown, before React commits** | `ALUI/Animation/TouchDown.swift` (`UILongPressGestureRecognizer(minimumPressDuration: 0)`, `.began`) used by `Tile`, `EndButtons`, `ChildCard`; `Anim.press` called synchronously in the same closure, before `onPick()` returns. | Using `Button` / `.onTapGesture` (fires on touch-**up**, and after a scroll-cancel delay); making `onPick` `async`; moving the SFX into `.onChange`/`.task`. |
| **2 — animation off the render path** | `ALUI/Animation/LayerHost.swift` + `Anim.swift` (Core Animation on a hosted `CALayer`) for press, shake, pop-in, the star pulse and the growth sweep; `ConfettiOverlay` as a `TimelineView`+`Canvas` **leaf** whose particle buffer lives in a reference type. | `withAnimation` / `@State`-driven keyframes for any of those; publishing particle state; putting the confetti `Canvas` where an ancestor's `@State` changes per frame. |
| **3 — no fail state** | `Tile` treats `.reject` as *shake only*; nothing sets `disabled` from a wrong answer; `GameFrame` has no lock path; `Paywall`'s child step is calm and offers a way out (`Voir ma mascotte`). | Disabling a tile after a miss; adding an error colour/state; a modal the child cannot dismiss. |
| **5 — all levels unlocked, always** | `HubView` renders `1...levelCount` unconditionally; `open(exercise:level:)` has no guard; `AppRoute.play` carries no gate. | Any `if unlocked` in the hub or the router. |
| **6 — accessibility floor** | `Tile`'s 92 pt floor and the 40 pt Écouter button; `.accessibilityLabel` on every interactive element with the exact French strings from `Copy.swift`; `@Environment(\.accessibilityReduceMotion)` gating every `Anim.*` entry point and `ConfettiSystem.fire()`. | Shrinking `dim`'s floor; dropping a label when refactoring to a `ButtonStyle`; adding an animation that does not route through `Anim`. |
| **7 — every exercise has an original drawn icon, never an emoji** | `ALArt/Icons/ExerciseIconCatalog.swift`: `func exerciseIconSpec(_ id: ExerciseId) -> ExerciseIconSpec` is a `switch` with **no `default:`** over a frozen, in-module, non-resilient enum. `HubView` renders `ExerciseIcon(id:)` and never touches `ExerciseMeta.emoji`. A test iterates `ExerciseId.allCases` asserting non-empty nodes and 17 distinct tints. | Replacing the `switch` with a `Dictionary` (runtime optional); adding `default:`/`@unknown default:`; enabling library evolution on ALCore; rendering `meta.emoji` in the hub. |
| **8 — farming never pays (visible half)** | `GameFrame`'s strip renders `starStripCells(done:total:stars:)` verbatim, including the `i == done && !stars[i]` case that greys the *live* round's star. The exercise's `stars` array flips on the pointerdown itself, on the main actor, in the same turn. | Animating the transition (it must be instant); recomputing `stars` at the recap; batching the flip behind a `Task`/`DispatchQueue.main.async`. |
| **10 — nothing identifying leaves the device** | This scope *displays* `child.name` (hub chip, `ChildCard`, rename/delete prompts) and never transmits it. The only telemetry writes here are `setConsent(analytics)` and the closed-list `track()` calls in `Onboarding`/`Paywall` (`trial_started`, `paywall_shown`, `purchase_completed`, `purchase_failed`, `purchase_restored`). | Adding a name/level string to a `track()` property; logging a profile for debugging; putting the name in an accessibility identifier that ends up in analytics. |
| **11 — money never fails closed** | `Onboarding` and `Paywall` are pure consumers of `EntitlementStore`. Neither may gate on `.unknown`. The Paywall is reachable only from a level tap, never at startup. | Rendering the Paywall while the entitlement is `.unknown`; blocking the hub, the mascot, or the shop; hard-locking on a store error instead of showing `note`. |

Invariants 4 and 9 are not touched by this scope.

---

## 7. Risks

### 7.1 The red test file — the single biggest decision in this scope

`src/App.test.tsx` is untracked and **fails 9 of 12** against the working tree
(`npx vitest run src/App.test.tsx`: `9 failed | 3 passed`). It asserts:

- first launch shows `Onboarding` before anything else;
- the hub shows `Essai gratuit — 11 jours restants`;
- tapping a level with an expired trial shows `Les jeux font une pause`, then the
  `ParentalGate`, then the price.

`App.tsx` imports none of `useEntitlement`, `Onboarding`, `Paywall`, `ParentalGate`,
`trialNotice` or `canPlay`. The `AppRoute.paywall` case exists in the `View` union and is
never constructed. The three components and the entitlement provider are complete and tested
in isolation; only the *wiring in `App.tsx`* is missing.

So "the PWA is the specification" has two readings, and they differ:

- **Shipping behaviour** (what a child sees today): no onboarding gate, no countdown, no
  paywall. Port `App.tsx` literally; `Onboarding`/`Paywall` become unreachable screens.
- **Authored intent** (what the untracked test and every comment in `licensing/`,
  `telemetry.ts` and `CLAUDE.md` describe): the gates exist and are mandatory for App Review
  3.1.1 and Kids Category 1.3 — an iOS build that ships without them cannot pass review.

**Recommendation:** port `RootView` to the *shipping* shape (frozen behaviour), keep the three
screens and `AppRoute.paywall` fully implemented and reachable behind a single
`FeatureFlag.licensingGates` constant, and carry `App.test.tsx` across as
`ALUITests/LicensingGateTests.swift` marked `XCTSkip`/`.disabled` with a comment pointing at
this section. That way the Swift port neither invents behaviour nor loses the specification,
and flipping one constant is the whole change. **This is an app-level call, not mine.**

### 7.2 React semantics with no clean SwiftUI equivalent

| React | Problem | Resolution |
|---|---|---|
| `key={...}` forcing a remount | SwiftUI reuses views aggressively; a changed `level` would otherwise keep the old `@State` (and the old seeded run). | `.id(route.remountKey)`. This is exact, but it is invisible — omit it and the bug is "level 2 replays level 1's words". Needs a test. |
| `onPointerDown` | SwiftUI's `Button` and `.onTapGesture` fire on touch-up. `DragGesture(minimumDistance: 0).onChanged` fires on touch-down but also on every movement, and is cancelled by an enclosing `ScrollView`. | A UIKit `UILongPressGestureRecognizer(minimumPressDuration: 0)` reading `.began`, wrapped in `TouchDownGesture`, with `cancelsTouchesInView = false`. Under `#if !canImport(UIKit)` (host tests) fall back to `.onTapGesture` so ALUI still compiles on macOS. |
| WAAPI `element.animate()` | The whole point is that it runs outside the render tree. SwiftUI's `withAnimation` is *inside* it. | `LayerHost` + `CAAnimation`. Costs a `UIViewRepresentable` per animated element; acceptable, and the only faithful option. |
| `ResizeObserver` | SwiftUI's `GeometryReader` + `PreferenceKey` is the analogue, and it has the *same* feedback-loop failure mode that produced the FitLine bug. | `.fixedSize()` on the measured subtree (§4.6). Any new measurement must document why it cannot cycle. |
| `window.prompt` / `window.confirm` | Synchronous and blocking; SwiftUI alerts are declarative and async. | `.alert` with `@State` presentation flags. Also needs button titles that the PWA never had (browser chrome) — proposed « OK » / « Annuler », **needs sign-off**. |
| React context | | `@EnvironmentObject`. Note `useProfile()` is called by `App`, `Dashboard` and `WhoIsPlaying` independently; each becomes an `@EnvironmentObject` read of the same store. |
| `StrictMode` double-mount | Makes `usePopFlourish` fire twice in dev. | Not reproduced (dev-only artefact). |

### 7.3 Frozen oddities — port as-is, do not "fix"

- **The asymmetric star strip.** `<div className="hidden w-[84px] shrink-0 sm:block" aria-hidden />`
  only exists at viewport ≥ 640 px, so on a phone the strip is pushed right by the "← Menu"
  button and is not optically centred. Ported literally: the spacer is present only when
  `alViewportWidth >= 640`.
- **The pulse beats the inline opacity.** The live star's `opacity: 0.8` is dead code under
  normal motion (§4.5). Ported as described; do not "simplify" to a static 0.8.
- **`EXERCISES.findIndex` can return −1** and would crash on `meta.levelCount`. Unreachable
  today. The Swift `guard` returns `.hub` instead of trapping — a divergence in an
  unreachable branch, chosen because a `fatalError` in a six-year-old's app is worse than a
  hub bounce.
- **`AppRoute.paywall` is declared and never constructed** (§7.1).
- **`Ollie` is still exported and unused** in the shell. Ported, marked legacy.
- **`createChild(name)` passes the untrimmed string** even though the submit button is gated
  on `name.trim()`. The trimming lives in `useProfile`. Ported as-is.
- **`maxLength={14}`** counts UTF-16 code units in the DOM, grapheme clusters in Swift. They
  differ only for emoji/combining names. Recorded; `String.prefix(14)` chosen.
- **`track("purchase_restored")` fires whether or not a purchase was found.** Ported as-is.
- **The hub calls `preview()` ~75 times per paint.** Fine, but it must stay a pure read.

### 7.4 Fidelity risks for the D3 pixel diff

- **The `<text>` glyphs.** SF Pro Rounded via `Font.system(design: .rounded)` should be the
  same face WebKit resolves for `ui-rounded`, but the baseline anchoring is reimplemented
  (§3.5). Expect this to be the first pixel-diff failure and the first thing to iterate.
- **`grayscale(1)` on an emoji.** CSS `filter: grayscale(1)` and SwiftUI `.grayscale(1)` use
  different luma coefficients in principle. Visible only on the greyed ⭐; check it.
- **CSS blur → SwiftUI shadow radius** is an approximation (`B/2`). The `0 8px 0` hard lips
  are exact; the soft ambient shadows are close, not identical.
- **Gradient interpolation.** The `STAGE` gradient has three stops at 0 % / 38 % / 100 % (and
  a 40 % variant on the three adult screens — `Onboarding`, `Paywall` and `WhoIsPlaying` use
  `38%`→`40%`; `App`, `GameFrame`, `Dashboard` use `38%`. **Two constants, not one.** Do not
  unify them.) `LinearGradient(stops:)` with `.init(color:location:)` reproduces both.
- **`AVSpeechSynthesizer` rate mapping** is not the Web Speech `rate`. Needs an ear check.

### 7.5 Lifecycle / platform differences recorded

- The `AudioContext`-per-exercise-mount teardown has no iOS motivation; the port keeps one
  engine and deactivates the session when idle.
- The Chrome `speechSynthesis` pause/resume heartbeat has no `AVSpeechSynthesizer` analogue;
  the constant is kept, the code path is a no-op.
- `voiceScore`'s `localService === false` (+2 for network voices) has no AVFoundation
  equivalent; scored 0. This can change which French voice is chosen on a device with several.

---

## 8. Test plan

### 8.1 Host — `swift test`, no simulator (the bulk of it)

**`Tests/ALCoreTests/ShellRouterTests.swift`**
- `engine(for:)` returns the expected `ExerciseEngine` for **all 17 `EXERCISES` rows**. This
  is the 3-line router's real content and it is fully host-testable. Table-driven, one
  expectation per id, derived by hand from `App.tsx`'s if-chain — not from `engine()` itself.
- Ordering guards: an exercise with **both** `grid` and `spell` set resolves to `.syllableGrid`
  (grid is checked first); `read-image` resolves to `.readImage` even though it has no
  capability fields; `first-letter` (no fields at all) resolves to `.firstLetter`.
- `nextRoute`: mid-exercise bump; last level → next exercise level 1; last level of the last
  exercise → `.hub`; a `.hub` input → `.hub`; an unknown exercise id → `.hub`.
- `AppRoute.remountKey` changes for every `(exercise, level)` pair and is stable otherwise.

**`Tests/ALCoreTests/StarStripTests.swift`**
- All four cell states, including the load-bearing one: `done = 2`, `stars = [true, true, false]`
  ⇒ index 2 is `.lost`, not `.live`. That single assertion is invariant 8's visible half.
- `starFontSize(total:)`: 16 for `total > 9`, 20 otherwise (boundary at 9/10).
- `total = 0` produces an empty strip; `stars.count < total` does not crash (the TS reads
  `stars[i]` as `undefined` → falsy → `.lost`; assert the Swift port matches by treating a
  missing entry as `false`).

**`Tests/ALCoreTests/GateChallengeTests.swift`**
- 10 000 rolls: both operands in `3...9`, product in `9...81`, never a `1` or `2` factor.
- `sanitizeGateInput`: strips non-digits, truncates to 3, `"12a3456"` → `"123"`.
- `verify`: correct product passes; `"012"` for 12 passes (JS `Number` parity); empty fails.

**`Tests/ALCoreTests/CopyTests.swift`** — byte-equality on the French strings, so a
refactor cannot quietly reword a legal disclosure:
- `Copy.onboardingTitle == "Nous aussi, on est parents."`
- the trial paragraph contains `"gratuit pendant"` and `"se mettent en pause"` (the two
  App-Review-3.1.1 facts `App.test.tsx` checks)
- `Copy.paywallChildTitle == "Les jeux font une pause"` and the child copy contains **no**
  `"9,99"` and no `"Débloquer"` — the Kids-Category assertion, host-testable as a string test
- `Copy.paywallAskAGrownUp` contains U+00A0 before `!`
- `Copy.familyScopeIOS == "pour toute la famille"`
- `levelLabel(3, points: 10) == "Niveau 3, gagne 10 étoiles"`,
  `levelLabel(3, points: 1) == "Niveau 3, gagne 1 étoile"`,
  `levelLabel(3, points: 0) == "Niveau 3, pour s'entraîner"` (singular/plural + the training
  wording)
- `balanceLabel(1) == "Tu as 1 étoile"` / `balanceLabel(20) == "Tu as 20 étoiles"`

**`Tests/ALCoreTests/VoiceChannelTests.swift`** — the six `useAudio.test.ts` cases, ported
against a fake `SpeechBackend`:
1. clip plays to its end → `say` returns `true`;
2. a second `say()` supersedes the first → the first returns `false`, and the two never
   overlap;
3. a media error → `false` (a decode failure never hangs a round);
4. `stop()` settles the in-flight line `false` and is safe when idle;
5. the watchdog settles `false` when the duration is known but "ended" never fires;
6. no baked clip → the TTS path, still settling on end.
Plus: the watchdog arithmetic (`max(1200, n*90)/rate + 800`) and the provisional 8 000 ms
window; and that a `CheckedContinuation` is never resumed twice under a supersede-during-
settle race.

**`Tests/ALCoreTests/VoiceScoringTests.swift`** — `voiceScore` on constructed descriptors
(enhanced +5, the named-voice regex +3, `fr-FR` +1, compact −5) and `pickBestFr` returning
`nil` when no French voice exists.

**`Tests/ALCoreTests/ConfettiSystemTests.swift`** — seeded RNG:
- `fire()` produces exactly 90 particles, all with `vy < 0` at birth is **not** true (the
  angle range is `[−π, 0]`, so `sin(a) ≤ 0` and `vy = sin(a)*sp − 3 < 0` — assert it *is*
  strictly upward, which pins the `− π` in the angle expression);
- a particle is culled when `life <= 0` (≈125 steps) or when `y > height + 40`;
- `advance(to:)` at 120 Hz runs the same number of 1/60 s steps as at 60 Hz for the same
  wall-clock delta (the fixed-timestep guarantee);
- reduced motion → `fire()` adds nothing.

**`Tests/ALCoreTests/LayoutTests.swift`**
- `fitScale`: `natural <= available` → 1; `natural = 0` → 1; `natural = 300, available = 150`
  → 0.5. Never > 1.
- `clampVW`: `clamp(28, 8vw, 44)` at viewport 390 → 31.2; at 1024 → 44 (clamped); at 200 → 28.

**`Tests/ALArtTests/ExerciseIconTests.swift`**
- **Exhaustiveness**: `for id in ExerciseId.allCases { let s = exerciseIconSpec(id); XCTAssertFalse(s.nodes.isEmpty) }`
  and `XCTAssertEqual(Set(allTints).count, ExerciseId.allCases.count)` — 17 distinct tints.
  (The compiler already guarantees totality; this catches a branch that compiles by reusing
  another icon's body.)
- **Golden geometry**: one test per **distinct `d` string in `ExerciseIcon.tsx` (30 of them)**,
  parsing through the D2 parser and asserting the element count and the bounding box against
  values computed once and reviewed. This is what makes "the `d` strings were copied
  verbatim" checkable.
- Node census per icon: assert the exact `(paths, circles, rects, texts)` counts per id
  against the table in §1.2, so a dropped element is caught without rendering.
- `SVGText` anchoring maths: the central-baseline offset for a known ascent/descent pair.
- `ShuffleChip` appears in exactly the two `*-mixed` specs and nowhere else, with the tint
  matching the badge (`#B23A2A`, `#2E7D5B`).

**`Tests/ALUITests/`** (SwiftUI compiles on macOS, so these run on the host)
- `HubViewModelTests`: the four hint chips resolve in the documented order for a synthetic
  `ExerciseMeta` with all four fields set; `jackpot == (pts == 10)`; the pill is absent at
  `pts == 0`.
- `FitLineTests`: the pure scale/height derivation only (the view itself needs a renderer).
- Compile-guard test: ALUI builds for macOS, proving the `#if canImport(UIKit)` fallbacks are
  in place.

### 8.2 Simulator / device only

- Invariant 1 timing: SFX + press animation begin on touch-down, before the state commit.
  Assert with a UI test that records the gesture phase, or with an instrumented `Anim` hook.
- Reduced-motion: every `Anim.*` and `ConfettiSystem.fire()` no-ops; the star strip's live
  star is static at 0.8; the growth bar is static at `pct`.
- VoiceOver: every label from `Copy.swift` is announced; `ExerciseIcon` is hidden; the pills
  are hidden; the parental-gate error is announced.
- `.id()` remount: play level 1, tap Suivant, assert the level-2 run is freshly seeded.
- Keyboard: the name field and the gate field focus on appear; the gate uses the number pad.
- **D3 pixel diff** vs the PWA for: the hub at three widths (390 / 480 / 1024 — the last one
  is where the `vw`/480 px interaction and the `sm:` spacer both change), all 17
  `ExerciseIcon`s at 30 pt, the GameFrame strip at `total` 5 / 9 / 10 / 12, and the three
  adult screens.

---

## 9. Decisions this scope needs from the app level

1. **Do the licensing gates ship?** (§7.1) The router shape depends on it. Recommendation:
   port the shipping shape, keep the screens behind one flag, carry `App.test.tsx` as a
   skipped suite.
2. **`ExerciseId` must be a frozen, `CaseIterable`, in-module enum**, and the package must not
   enable library evolution. Invariant 7's compile-time check depends on both.
3. **Dev screens**: how `#stages` / `#vo` are reached (launch argument behind `#if DEBUG` is
   proposed), and who owns `MascotGallery` / `VoGallery`.
4. **`alViewportWidth`** is a cross-cutting environment value every screen needs; it must be
   injected once at the app root from the *window*, not from any card. (§5.3)
5. **`AudioEngine` protocol ownership**: declared here, consumed by every exercise agent.
   Its exact signature (`async -> Bool`, `SayOptions`) needs to be agreed before the exercise
   agents write against it.
6. **`ALUI` may import UIKit under `#if canImport(UIKit)` with a macOS fallback.** Without
   this, invariants 1 and 2 cannot both be honoured. With it, host testing of ALUI survives.
   Needs an explicit ruling and a note in `DECISIONS.md`.
7. **Design-token ownership**: `ALUI/DesignSystem` is proposed as the single home for the
   stage gradients (both 38 % and 40 % variants), the ink colours, the rounded font, the two
   shadow recipes and `ALPressStyle`. Every other agent consumes it rather than redeclaring.
8. **Alert button titles** for the rename/delete flows, which the PWA never had (browser
   chrome). « OK » / « Annuler » proposed.
9. **`Text(verbatim:)` everywhere for French copy** — proposed as a package-wide rule.
10. **`WordIcon`'s `img` assets** are Vite URLs today and must become bundle resources;
    a content-agent dependency.
