# Mascot subsystem — Swift/SwiftUI port specification

Scope: `src/mascot/**` (~4,200 lines), `src/img/*.svg` (4 files), `public/icon.svg`.
Source of truth: the ORIGINAL working tree at `/Users/jonathan/IdeaProjects/attrape-lettres/src`
(not the worktree — see D0). Behaviour is frozen; every number, colour, draw order and
French string below is normative. Decisions D0–D3 are binding, in particular D2:
**every `d` string is copied verbatim into Swift string interpolation and parsed at
runtime**; geometry is never retyped as `Path` builder calls.

---

## 1. Inventory

| File | Lines | Role |
|---|---:|---|
| `mascot/Mascot.tsx` | 131 | Top component: species dispatch, per-stage feet-pivot scale, ground shadow, "Arc-en-ciel magique" silhouette-masked rainbow overlay, WAAPI mood animation (bob/pop/cheer), `aria-label`, `viewBox`/`focus` crop, `preview` mode. |
| `mascot/growth.ts` | 184 | Pure math: `INK`, `lerp`, `clamp`, `pick`, `mix` (hex blend), `ramp` (piecewise-linear over `[stage,value]` stops), `STAGE_SCALE`/`stageScale`, `Pose`, `LegSpec`, `Layout`, `RigProps`, `poseFor`, `quadLegs`, `layoutFor`. The continuous growth model every rig consumes. |
| `mascot/anchors.ts` | 57 | Pure: `AccessoryAnchors` + `accessoryAnchors(species, layout)` — where worn items sit (throat / hat seat / side clip / head ring / feet). Per-species `NECK_K` = `{unicorn:0.9, cat:0.94, fox:0.86, rabbit:0.94, dragon:0.86}`. |
| `mascot/ids.ts` | 66 | `COLOR_SLOT`, `STYLE_SLOT`, `ACCESSORY` — the string keys/ids the rigs read and the catalog writes. Single source; must never drift. |
| `mascot/catalog.ts` | 224 | Shop inventory `CATALOG: CustomizationOption[]` (colour/style/accessory rows, French names, emoji, costs, `minStage` gates) + `DEFAULT_LOOKS: Record<Species, DefaultLook[]>` (factory looks per slot). Content only. |
| `mascot/parts.tsx` | 535 | Generic part library: `fourStar`, `Eyes`, `Cheeks`, `Mouth`, `Plume`, `Leg`, `FoldedLegs`, `Aura`, `Sparkles`, `RainbowSheen`, `Bow`, `Swimsuit`, `SwimRing`, `Flower`, `Halo`, `GroundGlow`, `Crown`, `Burst`. |
| `mascot/dragonParts.tsx` | 1153 | Dragon part library: egg (`EggCup`, `ShellCap`, `ShellShard`), wings (`BatWings` + unused candidates), `SpadeTail` (+`TailClub`), `Horns`, `Crest`, `BellyPlates`, fire (`FlamePuff`, `Cracks`, `SmokePuffs`, `Claws`, `Fangs`), knight accessories (`CapeBack`, `CapeClasp`), the treasure hoard (~450 lines: `Treasure`, `T_STAGES`, coin/gem/chest sub-parts), `FangPendant`, `Goggles`, `Snout`, plus design-run leftovers (§8.6). |
| `mascot/Unicorn.tsx` | 279 | « Céleste » rig: `USpec` STAGES timeline, `Horn`, `Wings`, `UnicornTail`, full layered draw. |
| `mascot/Cat.tsx` | 265 | « Lynx Royal » rig: `CSpec` STAGES, `Mane`, bezier fur-clump tail, collar variants. |
| `mascot/Fox.tsx` | 291 | « Kitsune » rig: `FSpec` STAGES, `Flame`, `FanTail`, bushy tail, fur patterns. |
| `mascot/Rabbit.tsx` | 408 | « Lune » rig: `RSpec` STAGES, `Ear`, `KinkEar`, `Pompon`, `BunnyNose`, `WhiskerDots`, `Crescent`, `Comet`, `Nightcap`, stardust premium. |
| `mascot/Dragon.tsx` | 259 | « Braise » rig: `DSpec` STAGES, egg stage 0 / shell stage 1 / standing 2–9, blue-flame premium, treasure clamping. |
| `mascot/Mascot.stories.tsx` | 205 | Storybook harness (growth boards, accessory×stage boards, interactive). NOT ported as code; it defines the D3 rendering matrix (§14). |
| `mascot/anchors.test.ts` | 54 | Locks: collar below the muzzle at every (species, stage); hat above dome; side clip upper-left; `feet === layout.legs`. Ported 1:1. |
| `mascot/catalog.test.ts` | 101 | Locks: exact `minStage` gate map; one default look per slot; default gated no later than its variants. Ported 1:1. |
| `img/jupe.svg` | 63 | Word image « Jupe » (viewBox 128), 5 linearGradients, stroke-opacity, one rotated ellipse. |
| `img/pyjama.svg` | 44 | Word image « Pyjama » (viewBox 128), 2 linearGradients, rotated ellipse highlight. |
| `img/macaron.svg` | 23 | Word image « Macaron » (viewBox 128), 3 linearGradients, elliptical arcs (`A36 7`, `A35 10`). |
| `img/igloo.svg` | 31 | Word image « Igloo » (viewBox 128), 2 linearGradients, circular arcs. |
| `public/icon.svg` | 14 | App icon: gradient background + orange "A" strokes (48-wide, round caps/joins), viewBox 512. |

Shared contract types read from `src/types.ts` (owned by the core-types agent, listed here for
completeness): `Species`, `Mood`, `GROWTH_STAGES = 10`, `MascotConfig`, `MascotProps`,
`CustomizationOption`, `CustomizationCategory`.

---

## 2. Swift module plan

Dependency direction: `ALArt → ALCore`. Nothing in ALCore imports SwiftUI.

### ALCore (pure, host-testable)

| File | Owns |
|---|---|
| `Sources/ALCore/Mascot/Growth.swift` | `INK`, `lerp`, `clamp`, `pick`, `mix`, `RampStop`, `ramp`, `stageScale`, `Pose`, `LegSpec`, `Layout`, `poseFor`, `quadLegs` (private), `layoutFor`. Straight function-for-function port of `growth.ts`. |
| `Sources/ALCore/Mascot/AccessoryAnchors.swift` | `AccessoryAnchors`, `neckK` table, `accessoryAnchors(species:layout:)`. |
| `Sources/ALCore/Mascot/MascotIDs.swift` | `ColorSlot`, `StyleSlot`, `AccessoryID` — nested enums/namespaces of `static let` strings, values byte-identical to `ids.ts` (they are persisted in profiles and synced; they can NEVER change). |
| `Sources/ALCore/Mascot/MascotCatalog.swift` | `CATALOG: [CustomizationOption]`, `DefaultLook`, `DEFAULT_LOOKS`. Content only, no logic. |

`Species`, `Mood`, `MascotConfig`, `CustomizationOption` live in the shared
`ALCore/Types` module file owned by the core agent (see §16 — must be decided once,
app-wide). This spec assumes they exist with the mapping in §3.

### ALArt (SwiftUI)

| File | Owns |
|---|---|
| `Sources/ALArt/SVG/SVGPath.swift` | `Path(svg:)` runtime parser per D2 (shared with exercise icons; owner: whichever agent lands first, both consume). Requirements in §9. |
| `Sources/ALArt/Mascot/MascotDraw.swift` | The drawing substrate (§4): `GraphicsContext` group/opacity/transform/clip/mask helpers, unit-scale handling, colour parsing of `#rrggbb` literals. |
| `Sources/ALArt/Mascot/Parts.swift` | Port of `parts.tsx` — one draw function per TSX component, same names (`drawEyes`, `drawPlume`, `drawSwimRing`, …), same parameter lists. |
| `Sources/ALArt/Mascot/DragonParts.swift` | Port of `dragonParts.tsx` minus the treasure section. |
| `Sources/ALArt/Mascot/DragonTreasure.swift` | The treasure hoard section (`Treasure`, `T_STAGES`, `TCoin`, `TUprightCoin`, `TGem`, `TRing`, `TCrown`, chest parts, slot tables). Split only along the existing comment boundary; contents 1:1. |
| `Sources/ALArt/Mascot/Rigs/UnicornRig.swift` | Port of `Unicorn.tsx` (STAGES table + draw). |
| `Sources/ALArt/Mascot/Rigs/CatRig.swift` | Port of `Cat.tsx`. |
| `Sources/ALArt/Mascot/Rigs/FoxRig.swift` | Port of `Fox.tsx`. |
| `Sources/ALArt/Mascot/Rigs/RabbitRig.swift` | Port of `Rabbit.tsx`. |
| `Sources/ALArt/Mascot/Rigs/DragonRig.swift` | Port of `Dragon.tsx`. |
| `Sources/ALArt/Mascot/MascotView.swift` | Public `MascotView(config:mood:size:preview:focus:)` — dispatch, growth scale group, shadow, rainbow mask overlay, accessibility label, viewBox/crop. |
| `Sources/ALArt/Mascot/MascotMotion.swift` | bob/pop/cheer keyframes + reduced-motion gate (§10). |
| `Sources/ALArt/StaticArt/WordImages.swift` | `JupeImage`, `PyjamaImage`, `MacaronImage`, `IglooImage` — the four `img/*.svg` converted once through the same parser (§11). |
| `Sources/ALArt/StaticArt/AppIconArt.swift` | `public/icon.svg` as a Swift view for reference/rasterisation; the shipping app icon itself is an asset-catalog PNG exported from it (§11). |

No mascot file lives in ALUI. ALUI consumes `MascotView` only.

---

## 3. Type mapping

| TS | Swift | Notes |
|---|---|---|
| `type Species = "unicorn" \| "cat" \| "fox" \| "rabbit" \| "dragon"` | `enum Species: String, CaseIterable, Codable` | Raw values are persisted/synced; byte-identical. |
| `type Mood = "idle" \| "happy" \| "cheer"` | `enum Mood: String` | |
| `GROWTH_STAGES = 10` | `public let growthStages = 10` (ALCore) | |
| `interface MascotConfig { species; stage; colors: Record<string,string>; styles: Record<string,string>; accessories: string[] }` | `struct MascotConfig { var species: Species; var stage: Int; var colors: [String: String]; var styles: [String: String]; var accessories: [String] }` | Index signatures → `[String: String]`. Values stay raw hex/variant strings — the rigs `pick()` with fallbacks; do NOT model slots as enums (unknown keys from an old profile must round-trip). `accessories` stays `[String]` (order irrelevant, membership tested). |
| `interface MascotProps { config; mood; size?=88; preview?=false; focus? }` | `MascotView` initializer: `init(config:mood:size: CGFloat = 88, preview: Bool = false, focus: CGRect? = nil)` | `focus` `{x,y,w,h}` → `CGRect` in the 0–100 mascot space. |
| `CustomizationCategory` | `enum CustomizationCategory: String { case accessory, color, style }` | |
| `CustomizationOption` | `struct CustomizationOption { let id, species, category, slot, value, name: String…; let emoji: String?; let cost: Int; let minStage: Int? }` | `minStage?` → `Int?` (absent ≠ 0 in the catalog test: keep optional, treat `?? 0`). |
| `type Stop = [number, number]` (growth) | `typealias RampStop = (stage: Double, value: Double)` | `ramp(stage:stops:)` takes `[RampStop]`. `stage` is `Double` in `ramp` (rigs pass Int stage; keep a `Double` overload path — Dragon calls `ramp(stage, …)` with the Int stage). |
| `type Pose = "lying" \| "wobbly" \| "standing" \| "proud"` | `enum Pose: String` | |
| `interface LegSpec` | `struct LegSpec { var hipX, hipY, footX, footY, bend: Double; var side: Double; var back: Bool }` | `side` is `Math.sign(dx) \|\| 1` — keep as Double (−1/1). |
| `interface Layout` | `struct Layout` — same 12 fields, `legs: [LegSpec]` | `standing: Bool` (false only stages 0–1). |
| `interface RigProps` | Not a struct: each rig is `func draw<Species>Rig(into ctx: inout GraphicsContext, config: MascotConfig, layout: Layout, stage: Int, mood: Mood, preview: Bool)` | `uid` DISAPPEARS — SwiftUI has no global id namespace; gradients/clips/masks are values, not referenced defs. Nothing may re-introduce a uid. |
| `interface AccessoryAnchors` | `struct AccessoryAnchors { var neck: (x: Double, y: Double, w: Double); var headTop: (x: Double, y: Double); var headSide: (x: Double, y: Double); var head: (x: Double, y: Double, r: Double); var feet: [LegSpec] }` | Prefer small nested structs over tuples if Codable is ever wanted; either is fine, the shape is what matters. |
| Per-species spec interfaces (`USpec`, `CSpec`, `FSpec`, `RSpec`, `DSpec`, `TSpec`) | `struct` with optional fields exactly as TS (`var maneFlow: Double?` etc.) | The preview-stripping code mutates a copy (`var spec = STAGES[…]; if preview { spec.x = … }`) — structs make this natural. |
| `type Tuft = "none" \| "small" \| "big"` (Cat) | `enum Tuft: String` + `TUFT_LEN: [Tuft: Double]` | |
| Colour strings (`"#7DB874"` etc.) | Stay `String` in specs/config; converted at the draw boundary by one `Color(hex:)`/`GraphicsContext.Shading` helper. `mix()` operates on strings in ALCore exactly like TS. | `mix` uses `Math.round` per channel: JS rounds half toward +∞; channels are non-negative so Swift `(x).rounded()` (half away from zero) is identical. Assert this in tests. |

Discriminated unions: none in this scope beyond the string literal unions above. All optional
spec fields land as Swift optionals with the same `?? 0` / `if let` semantics as the TSX
(`spec.halo && …` in TSX means "non-nil AND non-zero opacity is implied by content"; port as
`if let halo = spec.halo` — TS values are always > 0 when present; note `(spec.aura ?? 0) > 0`
guards exist in Rabbit and must be kept as written).

---

## 4. Rendering substrate — how JSX SVG becomes SwiftUI

**Recommendation (needs app-level ratification, §16): one `Canvas` + `GraphicsContext` per
mascot, with the part library as plain `func draw…(into:…)` functions.** Rationale:

- `GraphicsContext` transforms concatenate exactly like SVG transform lists.
  `transform="translate(x y) rotate(a) scale(s)"` is literally
  `ctx.translateBy(…); ctx.rotate(by: .degrees(a)); ctx.scaleBy(…)` inside a saved layer —
  **no UnitPoint anchor arithmetic, so no silent anchor bugs** (the failure mode called out
  in the task). `rotate(a cx cy)` = `translate(cx,cy) rotate(a) translate(-cx,-cy)`, done
  with the same three calls.
- Strokes scale with the CTM, same as SVG (§7).
- `ctx.clip(to:)` = `<clipPath>`, `ctx.clipToLayer` = `<mask>`, `ctx.opacity` +
  `drawLayer` = `<g opacity>`, `GraphicsContext.Shading.linearGradient/radialGradient` =
  gradient fills.
- The 127 prop-computed paths become `Path(svg: "M \(x) \(y) …")` built inside the draw
  functions — a mechanical, reviewable transliteration of each TSX template.

If the app-level decision instead lands on view composition, every rule below still holds;
the anchor rule becomes "every part occupies the full canvas frame and
`rotationEffect(anchor: UnitPoint(x: cx/100, y: cy/100))`", which is strictly more
error-prone — this spec recommends against it.

### Coordinate space and scaling

- All geometry is authored in **viewBox units** (0–100 for mascots, 0–128 for word images,
  0–512 for the icon). The canvas applies **one uniform CTM up front**:
  `s = size / vb.w`, translate `(-vb.x, -vb.y)` then scale `s` (viewBoxes are square;
  no preserveAspectRatio handling needed).
- Default viewBox `0 0 100 100`; when `focus` is set: viewBox = focus rect (this is how
  the shop zooms a tile onto one accessory). `focus` is only ever used with `preview`.
- **Overflow**: the non-preview mascot deliberately draws OUTSIDE the viewBox (wings, halo,
  sheen at top stages; `overflow: visible` + `display block` in TSX). The Canvas must not
  clip — SwiftUI `Canvas` clips to its bounds by default, so either size the Canvas larger
  than `size` with an inner offset, or use an unclipped drawing container; whichever is
  chosen, the pixel-diff harness (§14) must include stage-9 unicorn/dragon whose wings
  overflow. `preview` mode DOES clip (`overflow: hidden`) → `.clipped()` equivalent.
- The Mascot layout box remains `size × size` regardless of overflow (hit-testing and
  layout must match the web).

### The three top-level layers (Mascot.tsx, in order)

1. **Ground shadow**: `ellipse cx=50 cy=layout.feetY+3.5 rx=layout.bodyRX*0.92*k ry=3.6
   fill #000 opacity 0.1` — drawn OUTSIDE the scaled rig group; note `rx` is multiplied by
   `k` manually while `cy` is not.
2. **Scaled rig**: `translate(50, pivot) scale(k) translate(-50, -pivot)` where
   `k = preview ? 1 : stageScale(stage)` and `pivot = layout.feetY` — growth pivots at the
   feet so the creature grows upward. Preview pins `k = 1` so `focus` rects line up with
   raw layout coordinates.
3. **Rainbow overlay** (only when any equipped accessory id ∈ `RAINBOW_IDS = ["unicorn.accessory.star-clip"]`):
   draw the rig, then draw `RainbowSheen` **masked to the rig's silhouette**.
   - Web mechanism: `<mask>` containing `<use>` of the rig with CSS
     `filter: brightness(0) invert(1)` (SVG masks are luminance-based; the filter turns
     every rig pixel white so mask value = alpha).
   - Swift mechanism: **plain alpha mask** — `ctx.clipToLayer { draw rig }` then draw the
     sheen, or `.mask { rigView }` in view composition. Because after
     brightness(0)+invert(1) luminance ≡ 1, the web mask value equals the rig's alpha —
     the alpha mask is *exactly* equivalent, including semi-transparent parts (sparkles at
     opacity 0.8 pass 0.8 of the sheen). Do not port the filter.
   - Mask coverage in web is `x=-120 y=-120 w=340 h=340` userSpaceOnUse — i.e. the
     silhouette mask must cover the overflowing wings, not just 0–100.
4. **Accessibility**: `role="img"` + `aria-label` → `.accessibilityLabel(LABELS[species])`,
   byte-for-byte French: `unicorn:"Ma licorne"`, `cat:"Mon chat"`, `fox:"Mon renard"`,
   `rabbit:"Mon lapin"`, `dragon:"Mon dragon"`. Hard-coded, not localised.

### `uid` / defs ids

Every `id={uid}-…` (gradients, clip paths, masks, the rig `<use>`) exists only because SVG
defs share one document namespace. In Swift these become values (a `Shading`, a clip `Path`,
a closure) — the concept vanishes. `useId()` has no port.

---

## 5. SVG feature catalogue → SwiftUI equivalents

Census of what is actually used in this scope (nothing else is):

| SVG feature | Where | SwiftUI equivalent |
|---|---|---|
| `<path d>` (template + literal) | everywhere | `Path(svg:)` via the D2 parser; fill via `ctx.fill(path, with:)`, stroke via `ctx.stroke(path, with:, style:)`. |
| `<circle>` / `<ellipse>` | everywhere (fur clumps, eyes, coins…) | `Path(ellipseIn: CGRect(x: cx−rx, y: cy−ry, width: 2rx, height: 2ry))`. Rotated ellipses (`transform="rotate(a cx cy)"` on Plume locks, rabbit ears, img highlights) → transform the CTM, not the rect. |
| `<rect>` (some with `rx`) | swimsuit band, sheen band, chest, beanie brim | `Path(roundedRect:cornerSize:)` / `Path(_ rect:)`. |
| `<line>` | unicorn horn spiral rings, cat whiskers, pyjama placket | 2-point `Path` + stroke. |
| `<g>` + `opacity` / `fill` / `stroke` inheritance | everywhere | `ctx.drawLayer` with `ctx.opacity`; attribute inheritance becomes explicit parameters — the TSX already passes explicit values in almost all cases; the few inherited group attrs (`<g fill=…>`, `<g stroke=… strokeWidth=…>` e.g. `Eyes`, `FoldedLegs`, `Cracks`, `Claws`, `Fangs`, whisker groups) must be distributed onto each child during transliteration. |
| `<defs>` + `id` + `url(#…)` | gradients, clips, mask | Values; no ids (§4). |
| `<linearGradient x1 y1 x2 y2>` + `<stop offset stop-color stop-opacity>` | Unicorn horn shine (`x1=0 y1=1 x2=1 y2=0`), RainbowSheen (horizontal, 9 stops with opacities 0/0/0.75/0.8/0.8/0.8/0.75/0/0), 12 in standalone SVGs (diagonal `(0,0)→(1,1)` or vertical) | `Gradient(stops: [Gradient.Stop(color: Color(hex:).opacity(stopOpacity), location: offset)])`; `startPoint`/`endPoint` as `UnitPoint(x1,y1)/(x2,y2)`. Default `gradientUnits="objectBoundingBox"` ⇒ UnitPoints are relative to the **filled shape's bounding rect** — in GraphicsContext compute the shape's bounding rect and pass absolute start/end points derived from it (`Shading.linearGradient(_:startPoint:endPoint:)` takes user-space points). |
| `<radialGradient>` (default cx/cy/r = 50%) | `Aura` (stops 0.6/0.22/0 at 0/55%/100%), `GroundGlow` (0.8/0.2/0) | `Shading.radialGradient(center:startRadius:0 endRadius:r)`. **Trap**: `GroundGlow` fills an *ellipse* (`ry = rx*0.3`) with an objectBoundingBox gradient — SVG stretches the gradient anisotropically to the bbox. Equivalent: draw a circle with the radial gradient inside a CTM scaled `(1, 0.3)` about the centre. `Aura` fills a circle — no trap. |
| `<clipPath>` | `Swimsuit` (ellipse clip, 2 layouts), `SwimRing` back/front halves (rect clip), `EggCup` suit stripe (CUP_PATH clip) | `ctx.clip(to: path)` inside a saved layer / `.clipShape`. |
| `<mask maskUnits="userSpaceOnUse">` | 1 — the rainbow silhouette | Alpha mask (`clipToLayer` / `.mask`); §4 explains luminance≡alpha equivalence. |
| `<use href>` | 2 — rig reuse for mask + display | Draw the rig closure twice. |
| `transform="translate(…)"` `rotate(a [cx cy])` `scale(sx [sy])` | ~30 sites incl. compound `translate rotate scale` (ShellCap, FlamePuff, SpadeTail tips, Gem, Bolt, RainCloud, EggShield, TChestLid, TCrown, TUprightCoin, Shield, growth group, sheen group) | CTM concatenation in listed order. `rotate(a cx cy)` ⇒ `translate(cx,cy) rotate(a) translate(−cx,−cy)`; if view-composition is used instead, the anchor is `UnitPoint(cx/W, cy/H)` **of the part's own frame** — full-canvas frames make that `cx/100, cy/100`; anything else is a silent visual bug. **Negative scale exists**: Unicorn `Wings` uses `scale(${dir * s} ${s})` to mirror the left wing (dir = −1) — the CTM approach handles it; `scaleEffect(x: -s)` also works but mind stroke direction is irrelevant (nonzero fill, symmetric strokes). |
| `stroke-dasharray` | 1 — `SwimRing` segments: `strokeDasharray={\`${dash} ${dash}\`}` with `dash = perimeter/8`, Ramanujan perimeter `π(3(rx+ry) − √((3rx+ry)(rx+3ry)))` | `StrokeStyle(lineWidth: w, dash: [dash, dash])` (phase 0 = SVG default). |
| `stroke-linecap="round"` (36×), `stroke-linejoin="round"` (27×) | legs, necks, tails, outlines | `StrokeStyle(lineCap: .round, lineJoin: .round)`. Default when unspecified is `.butt`/`.miter` in BOTH SVG and SwiftUI — do not "helpfully" round anything unrounded. |
| `fill-opacity` / `stroke-opacity` | standalone SVGs only (jupe skirt pleats `stroke-opacity 0.4`, shadows `fill-opacity 0.10`, pyjama dots 0.8…) | `Color.opacity` on the fill/stroke colour. |
| `element opacity` | dozens (shadows 0.1, cheeks 0.6, whiskers 0.3, smoke 0.85…) | `ctx.opacity` in a layer, or bake into the colour when the element is a single fill. |
| `<title>`/`aria` | word images (`Jupe`, `Pyjama`, `Macaron`, `Igloo`) | `.accessibilityLabel` with the same French word. |
| CSS animation (`.al-sheen`) + CSS `filter` on `<use>` | RainbowSheen only | §10 / §4. |
| `<filter>`, `<pattern>`, SMIL, `<text>`, `foreignObject` | **zero** | n/a — no fallback machinery. |

---

## 6. Fill rule

`grep -rn "evenodd\|fillRule" src/mascot src/img public/icon.svg` → **zero matches**.
Every fill in scope uses SVG's default `nonzero`. SwiftUI `Path.fill` defaults to
`.nonZero` (`FillStyle(eoFill: false)`). **Rule: never pass `eoFill: true` anywhere in this
port.** Shapes that *look* like they'd need even-odd (Crescent, TUprightCoin's shading
crescent, TRing) are authored as single nonzero outlines or stroked arcs and must stay so.

---

## 7. Stroke mapping

- SVG strokes are centred on the path; SwiftUI strokes are centred (`stroke`, not
  `strokeBorder`). Straight mapping — **never use `strokeBorder`**.
- `strokeWidth` values are in viewBox units. Under the GraphicsContext approach the
  viewBox CTM scales them exactly as the browser does. If any implementation path builds
  device-space geometry instead, stroke widths must be multiplied by the same `s = size/100`
  — a stroke that doesn't scale with the mascot is a subtle, pixel-diff-visible bug
  (e.g. `Leg` stroke width 7 IS the leg).
- The growth group `scale(k)` also scales stroke widths on the web (CTM). Keep the rig
  inside the scaled layer so this happens for free.
- Web renders these vectors at device resolution after the CTM. Do NOT implement scaling
  as `scaleEffect` on a rasterised view: at `size` 220+ (Storybook/terrain, shop hero) the
  upscale blurs and D3 diffs fail. Scale the CTM / the geometry, not the raster.
- Zero-width strokes: `SpadeTail` passes `strokeWidth={edge ? 1 : 0}` — SVG treats
  stroke-width 0 as "no stroke"; in Swift, skip the stroke call when width is 0 (SwiftUI
  may still draw hairlines).

---

## 8. Component tree, growth timelines, wardrobe

### 8.1 Top-level flow

```
MascotView(config, mood, size=88, preview=false, focus=nil)
 ├─ layout = layoutFor(config.stage)          (ALCore)
 ├─ shadow ellipse (§4)
 ├─ growth-scaled group (k = preview ? 1 : stageScale(stage), pivot feetY)
 │   └─ rig: dispatch on species → draw{Unicorn|Cat|Fox|Rabbit|Dragon}Rig
 └─ if accessories ∩ RAINBOW_IDS: redraw rig as alpha mask over RainbowSheen
```

`stageScale = [1,1,1,1.0,1.05,1.09,1.13,1.18,1.23,1.3]`, clamped 0…9.
`poseFor`: ≤1 lying, ≤3 wobbly, ≤6 standing, else proud.
`layoutFor` — port verbatim, including: head/body/eye ramps; the lying branch
(`lyRX = bodyRX+8`, `lyRY = bodyRY*0.82`, head at `ramp` positions, `feetY 91`, `legs: []`);
the standing branch (`feetY 94`, `legLen`, `bodyCY = feetY − legLen − bodyRY`, `neckExtra`
for stages 7–9, `headCY` formula, `sf`/`sb`/`bend` ramps, `quadLegs` with hips at
`bodyCY + bodyRY*0.4`, foot `x = hipX + side*bend*0.6`, back pair at ±sb before front pair
at ±sf — **leg array order matters**: `[back-left, back-right, front-left, front-right]`;
Dragon's treasure clamp indexes `layout.legs[0]`).

### 8.2 Shared parts (`parts.tsx`) — all species

`fourStar(cx,cy,r)` (d-string helper, inner radius `r*0.36`) · `Eyes` (4 variants: sleepy
lids [idle+sleepy only], cheer = fourStar eyes ×1.35r, happy = arc eyes stroke `r*0.78`,
default = pupils with two white highlights at fixed offsets, small one opacity 0.8) ·
`Cheeks` (BLUSH `#FF9AA2` opacity 0.6, `ry = r*0.68`) · `Mouth` (idle = Q-curve stroke 1.5;
else open mouth `DARK #4A3222` + tongue `#FF7C93`) · `Plume` (n rotated lock-ellipses;
`wave` toggles `sin(i*1.9)*22` zig-zag vs `(t−0.5)*26` fan; each lock `rotate(r cx cy)`)
· `Leg` (quadratic stroke width w + foot ellipse `w*0.62 × w*0.4`) · `FoldedLegs` ·
`Aura` (radial glow) · `Sparkles` (empty→nil) · `RainbowSheen` (§10) · `Bow` ·
`Swimsuit` (standing: clipped waist band + 2 wavy stripes `[0.34,0.68]` + neckline +
optional white star badge; lying: rump culotte covering left of `edge = cx − rx*0.16`,
vertical stripes at `k ∈ {0.72,0.44}`) · `SwimRing` (torus: cream ellipse stroke + colour
dashed stroke + two faint rim ellipses; `part ∈ full|back|front` via rect clip at the tube
midline; `duck` head drawn with the front half at stage 7+) · `Flower` (5 petals) ·
`Halo` (Aura + ring stroke 1.4 at `r*0.62`) · `GroundGlow` (§5 trap) · `Crown` (band arc
drawn twice edge-then-band; 5 spikes at `(−90 + f*78)°`, f ∈ {−0.62,−0.31,0,0.31,0.62};
`open` drops the centre spike i==2, centre spike h=8.5 vs 5, gem r 2.4/1.7) · `Burst`
(ring of `n` fourStars, sizes `1.6 + i%3`).

### 8.3 Species rigs — stage timelines and z-order

Each rig: clamp stage to 0…9 into its `STAGES` table; if `preview`, strip the listed
fields (exact per-species lists below — these define what a shop "ghost" shows); read
colours/styles via `pick(config…, slot, fallback)`; membership test
`has(id) = config.accessories.contains(id)`.

**Draw order is normative.** The TSX render order = painter's order; re-ordering anything
is a visible bug (e.g. Dragon's crest and horns draw BEFORE the head ellipse so the head
covers their bases; the unicorn's horn draws OVER the star crown's open band; swim-ring
front half draws over the body; the lying culotte draws UNDER the neck/head). The port
must preserve statement order 1:1 per rig.

**Unicorn « Céleste »** — defaults: body `#F5ECFF`, horn `#FFD54F`, mane `#BA9EE8`, tail
`#F49AC2`, styles `tailStyle straight|curly`, `hornStyle smooth|spiral`; hoof `#EADAC6`,
inner ear `#FBE4F1`. `USpec` fields: horn (0,0,3,9,12,15,17,19,21,26), shine (8+),
wing (0,0,0,0.8,1.1,1.3,1.5,1.7,1.9,2.2), aura, sparkle, maneFlow (5–7), rainbow (8+,
5-colour RAINBOW palette), halo (6+), crown (7+), beam+ground (9). Preview strips: shine,
wing, aura, sparkle, maneFlow, rainbow, halo, crown, beam, ground (keeps horn). Parts:
`Horn` (spiral rings = `round(h/4)` lines, shine = linear gradient fill), `Wings`
(mirrored via negative scale; starry when `wing ≥ 2`), `UnicornTail` (wedge + Plume +
curly coil / straight white sheen line). Notables: ribbon worn as HAIR bow when lying,
throat bow when standing; star crown suppressed when flowerCrown equipped; flower crown =
4 Flowers at ±72°/±38° around the head, open at top so the horn pokes through; hoof auras
when `aura > 0.4`; lying baby rests ON full swim ring drawn behind the body.

**Cat « Lynx Royal »** — defaults: body `#F6A96B`, belly `#FFF3E4`, tail = body, styles
`hair short|fluffy`, `tailSize long|short`. `CSpec`: tail (0.5→2.0), tuft none/small/big
(TUFT_LEN 0/4/7), whiskers (3+), mane (5+: 5,6.5,8,9.5,11), aura, sparkle, halo (8+),
crown+ground (9). Preview strips: tuft, aura, sparkle, mane, whiskers, halo, crown, ground
(keeps tail). Notables: `raise`/`earScale`/`earPoint` ramps; tail = 7 fur clumps along a
quadratic bezier evaluated in code (bez formula ported exactly), bushiness
`rB = 3.2 + 4.9*tailF`, `tailF = spec.tail * (longTail ? 1 : 0.62)`; `furEdge =
mix(body, "#FFFFFF", 0.34)`; fluffy adds 16 body-halo circles r 5.4 + 12 head circles
r 4.6 + cheek/head Plumes; Mane = two rings of 12 circles skipping the chest wedge
(`sin(a) > 0.62`), colours `#FFC98A`/`#E08A3C`; bell collar has two forms — kitten bell
(< 5) vs studded leather + gold medal (≥ 5) with 4 studs at bezier t ∈
{0.18,0.38,0.62,0.82}; party hat = cone + stripe + pompom.

**Fox « Kitsune »** — defaults: body `#FF8A65`, belly `#FFFFFF`, tailTip `#FFFFFF`,
styles `furPattern plain|spots|stripes`, `tailSize long|short`. `FSpec`: tail
(0.55→1.8), ruff (0.4→1.3), tuft (3+), extraTails (4:1, 5:2, 7:4, 9:6), flames (6+),
mark (8+), halo (8+), aura, sparkle, ground (9). Preview strips: tuft, aura, sparkle,
extraTails→0, flames, mark, halo, ground (keeps base tail + ruff). Notables: base tail =
6 body-colour clumps over `t ∈ [0, 0.72]` + 4 tip clumps `t ∈ [0.76, 1]` carrying the
`tailTip` recolour; `FanTail` = outline circles (`r+1.1`, `#B8431C`) under deep-orange
clumps (`#F26B3C`) with cream tips (`t > 0.72` → `#FFF6EE`), fanned at
`270 + spread*132`°; spots = 5 fixed ellipses `#A24A2C`; stripes = 3 Q-curves `#8A4326`
opacity 0.4; scarf `#66BB6A` + tassel; beanie `#4FC3F7` with brim + pompom; boots on all 4
`anchor.feet`.

**Rabbit « Lune »** — defaults: body `#F6EFE3`, inner `#D9CCEE`, belly `#FFFFFF`, styles
`earStyle hautes|pliees`, `tailStyle pompon|etoile`, `furPattern uni|flocons`. `RSpec`:
earH (0.78→1.66), pompon (0.75→1.62), chest+bloom (3+), dip gold tips (4+), pomStar (5+),
mark (6+), sparkle, starTips (7+), halo (8+ = floating crescent moon beside the head, NOT
a ring), aura+ground+burst (9). Preview: reduced to `{earH, pompon, bloom}` only.
Notables: `inner = mix(body, innerBase, bloom ? 1 : 0.2)` — pre-bloom the tint is 20%;
ears upright ±12° when standing vs swept back (−66°, −30°, k 0.95/1) when lying;
`KinkEar` kink = `(standing ? (i==0 ? −1 : 1) : −1) * 100`; star-tail draws OVER the hip
(rotate 12°, gold + cream inner star), pompon variant behind the body with `tailEdge =
mix(body, INK, 0.18)`; flecks = 8 fixed fourStars alternating `#CFC2EC`/`#B8A6E0`;
swimsuit standing adds two shoulder straps (the "maillot une-pièce" read); stardust
premium requires `stage ≥ 4`: 5 gold sparkles (+2 at 7+) at polar offsets, 3 small pale
circles, `Comet` at 9 (rot 200 from `(bodyCX−bodyRX−18, bodyCY−bodyRY−20)`); Nightcap
(cap `#8E9AD6`) droops below its brim by design; hoof colour `mix(body, INK, 0.3)`.

**Dragon « Braise »** — defaults: body `#7DB874`, belly `#E9DFB2`, wing `#E2694F`, horn
`#EDE3CE` (`hornEdge = #B8A98C` for the default, else `mix(hornCol, INK, 0.35)`), styles
`hornStyle straight|double`, `crestStyle charbon|lava`, `tailStyle spade|club|flame`.
`DSpec` per stage: egg full (0) / bits (1); horn 0,0,3.5,5,6,7,8,10,11,13; wing
0,0,0,0.9,1.15,1.3,1.45,1.7,1.9,2.2; crest 0…6 (4+); plates (5+); flame 0.5/0.62/0.72/1.1
(6+); fierce → Fangs+Claws (7+); cracks+smoke (8+); aura 0.12…1; ember 2,3,4,8;
goldTips (7+); ground (9). Preview strips: egg, flame, aura, ember, fierce, cracks,
smoke, goldTips, ground (keeps horns/wings/crest/plates + sold styles/accessories).
Three render branches:
1. *Stage 0 (egg full)*: treasure (if owned) at fixed `x=14`; body+neck+head in the shell;
   sleepy eyes (`stage === 0`), eye r `eyeR*0.85`, dx `headR*0.36`; `EggCup` drawn OVER
   the chin (the dragon is IN the egg); `ShellCap` on the head (tilt 10); fixed-coordinate
   `SpadeTail` `p0=(81,87) p1=(89,83) p2=(87.5,75)` w 4.5 tipS 0.7.
2. *Stage 1 (lying)*: tail at body-relative points; folded legs; shell souvenirs
   (`ShellCap` tilt −12 s 0.82 + `ShellShard`); treasure at `bodyCX − bodyRX − 9`.
3. *Stages 2–9 (standing)*: `tf = ramp(stage, [[2,0],[5,0.5],[9,1]])` drives tail
   size/position; order = ground glow → aura → **BatWings (behind)** → CapeBack →
   SpadeTail → back legs → body ellipse → Cracks → belly → BellyPlates → neck → front
   legs → Claws → **Crest → Horns → head ellipse (covers their bases)** → Snout →
   SmokePuffs → Eyes → Cheeks → Mouth → Fangs → FlamePuff(s) → goggles (stage ≥ 3 gate
   in the rig, independent of catalog) → FangPendant (drops 3 units if cape also worn) →
   CapeClasp → Treasure → ember ring.
   Blue-flame premium: `blue = has(blueFlame)`; flame size
   `flameS = blue && stage ≥ 4 ? max(spec.flame, ramp(stage,[[4,0.5],[6,0.62],[7,0.85],[9,1.25]])) : spec.flame`
   — **the `stage >= 4` guard is load-bearing**: `ramp` clamps below its first stop, so
   without the guard the blue breath would leak to stages < 4 (the TSX comment says
   exactly this); embers `max(spec.ember, 4)` when blue && stage ≥ 7; second breath puff
   at stage ≥ 7; blue palette `#5BC8FF`/`#E8F7FF`/`#7FD1FF`, aura `#B8E2FF`, ground
   `#9FD4FF`. Flame tail: `tailTip = tailPick === "flame" && stage < 2 && !preview ?
   "spade" : tailPick`; tip colours: club → `mix(body, INK, 0.12)`, spade → belly,
   flame → nil. Treasure x-clamp:
   `tX = min(bodyCX − bodyRX − 5 − 4*tf, backFootL − tRight − 1)` with `backFootL =
   layout.legs[0].footX − 4.6` and `tRight = stage≥7 ? 17.0 : stage≥6 ? 8.6 : stage≥3 ?
   5.7 : 2.8` (DA redline B1: gold never under the splayed back hoof).

### 8.4 The treasure hoard (`Treasure`) — its own growth timeline

`T_STAGES` (0…9): coins 1,1,1,3,4,5,6,7,9,12; ring 3+; crown 5+; gems 0,0,0,0,0,0,0,2,3,5;
chest+spill 7+ (spill 1,2,3); beads 8+. `set = stage ≥ 7` (jewels get stones). Element
size is CONSTANT (`COIN_R 2.8`, `COIN_RY 1.15`); only quantity grows. Slot tables
(`COIN_SLOTS`, `CHEST_SLOTS`, `SPILL_SLOTS`, `GEM_SLOTS`, `CHEST_GEM_SLOTS`) are fixed
arrays — port verbatim; filled slots are **sorted by dy ascending before drawing** (back
rows first: `[...slots.slice(0,n)].sort((a,b) => a[1]-b[1])`). Three renders: single coin
(≤2), open mound (3–6), chest (7–9: lid behind mound, crown on highest filled row, body
wall AFTER the mound so the bottom row sinks behind the rim, beads at 8+, spill +
upright hero coin + ring on the DRAGON's side, gold sparkles; white glint only on gold).
`heapTopY(coins)` steps −1.1/−2.9/−4.7/−6.5 at 1/3/8/13 coins. Gold palette
`#FFE08A/#FFC94D/#E09B3A/#C9822E/#FFDB6E`; wood `#C9985F/#AF7A45/#8A5C33/#9A6A3E`; gems
RUBY `#E0533B`, SAPPHIRE `#5E7EB5`, EMERALD `#4CAF7D`, AMETHYST `#9575CD` with
`GEM_DARK` rims.

### 8.5 Wardrobe / catalog (data, ALCore)

Port `CATALOG` and `DEFAULT_LOOKS` verbatim — 5 species × (colours + styles + accessories),
French names byte-for-byte (`"Écailles rouge braise"`, `"Poussière d'étoiles"`,
`"Arc-en-ciel magique"`, …), costs, and the exact `minStage` gates pinned by
`catalog.test.ts` (`EXPECTED_GATES` — 28 gated ids). Accessory ids per species (from
`ids.ts`): unicorn ribbon/flower-crown/star-clip/swimsuit/swim-ring; cat
bow/bell-collar/party-hat/swimsuit/swim-ring; fox scarf/beanie/boots/swimsuit/swim-ring;
rabbit bow/nightcap/stardust/swimsuit/swim-ring; dragon
cape/goggles/fang-necklace/treasure/blue-flame (**no swim pair on the dragon — deliberate;
do not "fix"**). One premium (cost 200, minStage 4) per species: star-clip, party-hat,
boots, stardust, blue-flame.

### 8.6 Exported-but-unreachable parts (port decision)

`dragonParts.tsx` exports design-run candidates never referenced by `Dragon.tsx`:
`CloudWings`, `DomeFin`, `Bolt`, `RainCloud`, `AngularWings`, `FrillBand`, `DustPuffs`,
`KnightHelmet`, `Shield`, `ChestArmor`, `EggShield`; `Gem` and the Horns `curly` variant
and SpadeTail `bolt`/`gem` options are reachable only from these. `TailClub` IS reachable
(tailStyle `club`). Recommendation: **port only what the shipped rigs reach** and list the
rest in a `// not ported: design candidates (see spec §8.6)` comment — they have no
runtime path, and the `/new-companion` pipeline that used them is a web-side tool.
Flagged as a decision in §16; if "port everything 1:1" is preferred, they are ~350 extra
lines of the same mechanical translation.

---

## 9. SVG path parser — requirements + golden-test catalogue

Command vocabulary **actually present** in this scope: absolute `M L C Q A Z`, relative
`q l` (Swimsuit stripes, EggCup stripe, unicorn curly tail, FangPendant cord, Cracks,
Claws, Fangs, scarf tassel). Standalone SVGs add nothing new (all absolute `M L Q C A Z`).
The parser still implements the full D2 set (`M m L l H h V v C c S s Q q T t A a Z z`,
implicit repeat, comma-or-space separation, negative eliding `10-5`, arc endpoint→centre
per F.6.5 with F.6.6 radius scale-up).

**Addition to D2 (found during this pass): the number grammar MUST accept exponent
notation (`1e-16`, `2.5E3`).** JS template interpolation prints tiny doubles in exponent
form, and Swift string interpolation of `Double` does the same (`"2.220446049250313e-16"`).
The growth math (ramps, trig) can produce such values at interpolation sites. A parser
without exponent support fails rarely and non-deterministically — worst kind of bug.

Arc inventory (the fiddly 25): mascot runtime — Rabbit `Crescent`
(`A r r 0 1 1 …` then `A r*1.15 r*1.15 0 0 0 …`), dragon `FrillBand` (`A rr rr 0 0 1`),
`TUprightCoin` shading crescent (`A2.8 2.8 0 0 1` + `A2.18 2.18 0 0 0`), `TRing`
(half `0 0 0`, quarter `0 0 1`); standalone — macaron (`A36 7 0 0 0`, `A35 10 0 0 0` —
**unequal radii**), igloo (`A44 40 0 0 1`, `A17 17 0 0 1`, `A11 11 0 0 1`).

### Distinct `d` shapes (golden test list)

One golden per row; templates tested at ≥2 parameter sets (a baby-stage and a stage-9
value pull different numeric ranges through the parser). T = template, L = literal.

| # | Site | Kind | Commands |
|---|---|---|---|
| P01 | parts.fourStar | T | M L×7 Z |
| P02 | parts.Eyes sleepy lid | T | M Q |
| P03 | parts.Eyes happy arc | T | M Q |
| P04 | parts.Mouth idle | T | M Q |
| P05 | parts.Mouth open + tongue | T | M Q Z ×2 |
| P06 | parts.Leg stroke | T | M Q |
| P07 | parts.FoldedLegs ×2 | T | M Q |
| P08 | parts.Swimsuit lying stripe | T | M q q |
| P09 | parts.Swimsuit lying edge / neckline | T | M Q |
| P10 | parts.Swimsuit standing stripe | T | M q q |
| P11 | parts.Bow lobes | T | M L Q Z |
| P12 | parts.SwimRing duck beak | T | M L L Z |
| P13 | parts.Crown band | T | M Q |
| P14 | parts.Crown spike | T | M L L Z |
| U01 | Unicorn.Horn cone | T | M L Q Z |
| U02 | Unicorn.Wings membrane | L | M Q×5 Z |
| U03 | Unicorn.Wings feather lines ×2 | L | M Q |
| U04 | Unicorn.UnicornTail wedge | T | M Q L Z |
| U05 | Unicorn.UnicornTail curly coil | T | M q q q |
| U06 | Unicorn.UnicornTail sheen | T | M q |
| U07 | shared neck stroke (all rigs) | T | M L |
| U08 | Unicorn beam triangle | T | M L L Z |
| C01 | Cat ear outer | T | M Q Q Z |
| C02 | Cat ear inner | T | M L L Z |
| C03 | Cat nose | T | M L L Z |
| C04 | Cat collar arc (both variants) | T | M Q |
| C05 | Cat party hat cone / stripe | T | M L L Z; M L L L Z |
| C06 | Cat tail core | T | M Q |
| F01 | Fox stripes | T | M Q |
| F02 | Fox ear outer / inner tip | T | M Q Q Z ×2 |
| F03 | Fox chest ruff | T | M Q Z |
| F04 | Fox scarf band / tassel | T | M Q; M l l Z |
| F05 | Fox beanie dome | T | M Q Z |
| R01 | Rabbit BunnyNose | T | M Q Q Z |
| R02 | Rabbit Crescent | T | M A A Z |
| R03 | Rabbit Nightcap cone / fold / brim | T | M Q×5 Z; M Q; M Q |
| R04 | Rabbit swimsuit strap | T | M Q |
| D01 | dragonParts.CUP_PATH | L | M L×15 C×3 Z |
| D02 | EggCup suit stripe | T | M q |
| D03 | EggCup cracks ×2 | L | M L L; M L |
| D04 | ShellCap | L | M C C L×5 Z |
| D05 | ShellShard | L | M L L L L C Z |
| D06 | BatWings membrane / ridges | T | M C Q Q Q Z; M L |
| D07 | SpadeTail curve | T | M Q |
| D08 | Spade tip | L | M L Q Z |
| D09 | Bolt tip / Bolt | L | M L×6 Z |
| D10 | Horns cone (straight & double) | T | M Q Q Z |
| D11 | Horns curly | T | M C C |
| D12 | Crest spike | T | M L L Z |
| D13 | DomeFin | T | M C C Z |
| D14 | BellyPlates line | T | M Q |
| D15 | FlamePuff outer / inner | L | M C C Z ×2 |
| D16 | CloudWings — circles only (no d) | — | — |
| D17 | AngularWings poly / ridges | T | M L×6 Z; M L |
| D18 | TailClub spike | T | M L L Z |
| D19 | FrillBand arc | T | M A |
| D20 | Cracks zig | T | M l l l |
| D21 | Claws nick | T | M l l Z |
| D22 | Fangs | T | M l l Z |
| D23 | CapeBack | T | M Q C L L L L C Z |
| D24 | CapeClasp cord | T | M Q |
| D25 | KnightHelmet plume / dome / ridge | T | M C C Z; M C L Q Z; M Q |
| D26 | Shield body / emblem | T | M Q C C Z; M C C Z |
| D27 | Gem / TGem body / facet | T | M L L L Z; M L L Z |
| D28 | TUprightCoin crescent | T | M A L A Z |
| D29 | TUprightCoin star / EggShield star | T/L | M L×7 Z; M L×9 Z |
| D30 | TRing shade / light arcs | T | M A ×2 |
| D31 | TCrown peaks / band line | T | M L×6 Z; M L |
| D32 | TChestLid outer / inner | T | M L Q L Q L Z |
| D33 | TBeads thread | T | M L×7 |
| D34 | FangPendant cord / fang / collar | T | M Q; M Q Q; M q |
| D35 | Goggles strap / bridge | T | M Q |
| D36 | ChestArmor plate / ridge | T | M Q L Q Z; M Q |
| D37 | EggShield body | L | M C×4 Z |
| D38 | Snout — ellipses only | — | — |
| S01–S05 | jupe/pyjama/macaron/igloo/icon paths | L | M L Q C A Z (all absolute; unequal-radii arcs in macaron) |

Golden test format: parse each `d` (with representative interpolated values for templates),
compare the flattened `Path` element list / `path.description` against a checked-in
fixture, and for the arcs additionally compare sampled points against values computed by
the browser (one-time capture via `new Path2D` + `getPointAtLength` dump, committed as
JSON).

---

## 10. Animation

All motion is transform-only on already-built content. **Nothing re-parses paths or
recomputes geometry per frame** (invariant 2's Swift reading: never drive per-frame
animation through state that re-evaluates the rig).

| Web | Spec | Swift mapping |
|---|---|---|
| idle "bob" — WAAPI on the `<svg>` element: `translateY(0) rotate(−1.5°)` → `(−5px, +1.5°)` at 50% → back; 2600 ms, ease-in-out, infinite; `transform-origin: 50% 82%` | element-level, so **px are screen points, not viewBox units** | repeat-forever animation of `offset(y:)` + `rotationEffect(anchor: UnitPoint(0.5, 0.82))` on the whole MascotView content; e.g. `keyframeAnimator` or autoreversing `withAnimation(.easeInOut(duration: 1.3))` — must reproduce the keyframe SHAPE (down-rotate at rest, up-rotate at mid), not merely "some bob". |
| happy "pop" — scale 1 → 1.16 + rotate 4° @0.4 → 0.98 @0.72 → 1; 480 ms ease-out, one-shot | same origin 50%/82% | one-shot keyframe animation on scale+rotation. |
| cheer — scale/rotate keyframes `1/0°, 1.2/−6°@0.25, 1.1/6°@0.5, 1.22/−4°@0.74, 1/0°`; 680 ms ease-out, one-shot | same origin | one-shot keyframes. |
| Mood switching | effect re-runs on `[mood, preview]`; previous animation cancelled | restart on mood change; idle resumes the loop. Mood ALSO changes eyes/mouth — that is a one-time redraw (state → new canvas), same as one React commit; allowed. |
| `preview` | never animates ("a grid of jittering mascots is noise") | gate before starting any animation. |
| `prefers-reduced-motion` | checked at effect time; if reduced, NO animation runs at all | `@Environment(\.accessibilityReduceMotion)`; when true start nothing (not "shorter" — nothing). |
| RainbowSheen sweep — CSS `@keyframes alSheen` on an inner SVG rect: `translateX(var(--al-from, −150px)) → var(--al-to, 150px)`, 3.6 s linear infinite; **inner-element CSS px = viewBox user units** | band: rect `x = 50−26, y = −120, w = 52, h = 340` inside `rotate(−20° about (50,50))`, filled with the 9-stop spectrum gradient, masked to the pet silhouette | animate the band's x-offset −150 → +150 in *viewBox units* (× the canvas scale) with `.linear(duration: 3.6).repeatForever(autoreverses: false)` applied to an offset on the gradient-rect layer INSIDE the mask; the rig-silhouette mask itself is static. Under reduced motion the animation is `none` and the band **parks at its base transform — a static rainbow band across the centre remains visible** (invariant 6's explicit behaviour; do not hide the band). |

Implementation note for the hybrid: rig = one Canvas (static per state); sheen = a small
separate view (`Rectangle().fill(LinearGradient…)` rotated −20°) with a Core-Animation
backed offset, masked by `.mask { rigCanvas }`. This keeps the per-frame work on the
render server, off the SwiftUI update path.

---

## 11. Standalone SVGs (converted once, same parser)

`img/jupe.svg`, `img/pyjama.svg`, `img/macaron.svg`, `img/igloo.svg` — word-images
referenced from `content.ts` (`LETTER_WORDS` Igloo/Jupe, `SYLLABLE_WORDS` PYJAMA/MACARON).
Port each as a static SwiftUI view in `WordImages.swift`: viewBox 128, `d` strings copied
verbatim as string constants through `Path(svg:)` at init (cache the parsed paths in
statics), gradients per §5, rotated highlight ellipses via CTM, `fill-opacity`/
`stroke-opacity` via `Color.opacity`, accessibility label = the `<title>` text (`"Jupe"`,
`"Pyjama"`, `"Macaron"`, `"Igloo"`). The exercise-content agent consumes these views where
the web reads `word.img` — coordinate with them on the lookup API (probably
`WordImage(name:)` keyed by the same file stem).

`public/icon.svg` — the PWA icon (gradient sky + orange "A", stroke 48 round/round). For
iOS the app icon must be an asset-catalog raster; convert this SVG once (any exporter) to
the icon set at build/port time. A Swift view replica is optional; keep the SVG as the
design source. No runtime code needs it.

---

## 12. Invariant ownership (CLAUDE.md)

| Invariant | Where enforced in this design | What would break it |
|---|---|---|
| **2 — animation off the render path** | §10: all mood motion is transform-only on a static rig; sheen animates as a masked overlay layer; no per-frame Canvas redraw, no `TimelineView` driving the rig | Driving bob/sheen from a timer that mutates state re-evaluating the rig; re-parsing `d` strings per frame; implementing the sheen with `TimelineView(.animation)` redrawing the whole canvas. |
| **6 — accessibility floor** | §4 aria labels (5 French strings, byte-for-byte) on the mascot; §11 labels on word images; §10 reduced-motion: no bob/pop/cheer, sheen frozen to the static centred band | Dropping the labels, localising them, or making reduced-motion hide the rainbow band instead of freezing it. |
| **4 — content authored, not computed** (touches) | §8.5: CATALOG/DEFAULT_LOOKS ported as literal data in ALCore, no generation | Deriving catalog entries or gates programmatically. |
| **9/10 (adjacent, not owned)** | Mascot ids and config key strings (`ids.ts` §2) are persisted & synced — kept byte-identical | Renaming a slot/accessory id "for Swift style" would orphan every existing profile at migration time. |
| **D2 (binding decision)** | §9: verbatim `d` strings, runtime parser, golden tests; §16 records the exponent-grammar addition | Re-authoring any geometry as Path builder calls; introducing an asset pipeline. |

Invariant 7 (exercise icons) is out of scope — owned by the icons agent.

---

## 13. Test plan (host, `swift test`, no simulator)

**ALCoreTests** (pure math/data — all runnable on macOS host):
1. `GrowthTests` — `ramp` clamping below-first/above-last stop + interior lerp; `mix`
   golden values incl. rounding parity with JS `Math.round` (e.g.
   `mix("#7DB874", "#5A3A1E", 0.35)` fixture captured from the web build);
   `stageScale` table; `poseFor` boundaries (1/3/6); `layoutFor` golden `Layout` for all
   10 stages (full struct fixture captured once from TS — same IEEE-754 double ops in the
   same order ⇒ exact equality is expected; assert with 1e-9 tolerance to be safe);
   leg-array ORDER `[backL, backR, frontL, frontR]` and `legs.isEmpty` for stages 0–1.
2. `AnchorsTests` — 1:1 port of `anchors.test.ts`: for all 5 species × 10 stages, neck
   below muzzle floor (`headCY + headR × (standing ? 0.75 : 0.5)`), `neck.y ≤ feetY`,
   `|neck.x − headCX| ≤ headR`, `neck.w > 0`; headTop above dome; headSide upper-left;
   `feet` equals `layout.legs`.
3. `CatalogTests` — 1:1 port of `catalog.test.ts`: exact `EXPECTED_GATES` map (28 ids,
   everything else ungated); gates in 0..<10; one default look per colour/style slot,
   sets equal; default gate ≤ min variant gate and gated-iff-variants-gated.
4. `TreasureSpecTests` (new, cheap) — `T_STAGES` monotone coins; chest ⇔ stage ≥ 7;
   `heapTopY` steps.

**ALArtTests** (SwiftUI targets build on macOS 14 per D1):
5. `SVGPathParserTests` — the golden catalogue of §9 (every distinct shape, templates at
   ≥2 parameter sets), exponent-notation numbers, negative eliding, implicit command
   repeat, all 6 arc styles present in scope including unequal radii + F.6.6 radius
   scale-up, comma/space mixing.
6. `RigSmokeTests` — for every (species × stage): building the rig draw commands must not
   trap (array indexing like `layout.legs[0]` in Dragon's treasure is guarded by
   `standing`; the test proves it); preview variant likewise; each accessory equipped at
   each stage (the story matrix) renders without trap.
7. `RigSnapshotTests` (host) — `ImageRenderer` runs on macOS: render a subset of the D3
   matrix to PNG on the host and hash-compare against committed fixtures. This is the
   fast regression net; the authoritative cross-platform diff stays D3's harness (§14).
8. `MaskEquivalenceTest` — rainbow overlay: assert the sheen never draws outside the rig
   silhouette (render sheen-masked frame, assert zero non-transparent pixels where the
   rig alpha is 0).

What CANNOT be host-tested: real reduced-motion environment plumbing, Core-Animation
timing of bob/pop/cheer, and true device rasterisation — covered by the D3 simulator
harness and a manual QA pass.

---

## 14. D3 pixel-diff rendering matrix

Screenshots on both sides (PWA via the existing Storybook boards; Swift via a debug
harness screen that takes `(species, stage, wardrobe, mood, preview, size)` as launch
arguments). All shots with reduced-motion ON (freezes bob and parks the sheen band —
deterministic pixels). Mascot size 220, idle unless stated.

| Block | Cross product | Shots |
|---|---|---:|
| A growth | 5 species × 10 stages, no wardrobe | 50 |
| B accessories | 25 accessory ids × 10 stages (worn even below gate, like the story board — rig-internal gates like goggles ≥ 3 are part of the picture) | 250 |
| C styles | 14 style variants (unicorn 2, cat 2, fox 3, rabbit 3, dragon 4) × 10 stages | 140 |
| D colours | 33 colour options × 2 stages (its `minStage` and stage 9) | 66 |
| E moods | 5 species × {happy, cheer} at stage 4 (eyes/mouth variants) + sleepy check: each species stage 0 idle | 15 |
| F preview | 5 species × preview at stage 4 wearing one sold accessory (ghost stripping) + 1 `focus` crop (unicorn ribbon tile rect from ItemPreview) | 6 |
| G premium beats | star-clip (static band) 4–9; blue-flame 4,6,7,9; stardust 4,7,9; treasure 0,3,5,7,8,9 | 19 |
| H standalone | 4 word images + icon reference | 5 |
| **Total** | | **≈ 551** |

Overflow check rides block A (stage 8–9 unicorn/dragon wings must not be clipped).

---

## 15. Risks — where React/SVG semantics have no clean SwiftUI twin

1. **Rotation anchors** — the classic silent bug. Mitigated by mandating CTM
   concatenation (§4/§5); if view composition is chosen anyway, every
   `rotate(a cx cy)` needs a hand-computed `UnitPoint` and the pixel-diff matrix is the
   only net.
2. **Stroke/raster scaling** — `scaleEffect`-based scaling blurs and is wrong for strokes
   at large sizes; must scale CTM/geometry (§7). Also the growth `scale(k)` group must
   scale stroke widths (CTM does; per-part point-width strokes would not).
3. **objectBoundingBox gradients on ellipses** — `GroundGlow`'s radial gradient is
   anisotropically stretched by SVG; naïve `RadialGradient` in Swift draws a circle.
   Needs the scaled-CTM trick (§5). Same class of issue for the unicorn horn-shine
   linear gradient (bbox of the horn path, diagonal).
4. **Luminance vs alpha masks** — equivalent here ONLY because of the
   `brightness(0) invert(1)` trick (§4). If anyone ports the mask "faithfully" as
   luminance they will re-introduce the web workaround for nothing; if anyone drops the
   semi-transparent-alpha behaviour, sparkle pixels change.
5. **Sheen animation units** — the CSS `±150px` on an inner SVG element are *user units*
   (viewBox), while the mood WAAPI `−5px` on the `<svg>` element is *screen px*. Mixing
   these up scales one of them by `size/100` wrongly. §10 fixes both.
6. **Canvas clipping / overflow: visible** — SwiftUI `Canvas` clips to bounds; wings and
   halo at top stages overflow the 100-box by design. The chosen container must not clip
   (and MUST clip in preview). Parent layouts (ALUI) must tolerate the overflow exactly
   as the DOM does.
7. **Number formatting through interpolation** — Swift prints doubles as shortest
   round-trip incl. exponent form; parser must accept it (§9 addition to D2). Textual
   `d` strings will differ between JS and Swift (`"1"` vs `"1.0"`); irrelevant after
   parsing, but any test comparing raw strings across platforms is wrong by design.
8. **DECISIONS.md census is stale** — it predates the dragon land: transforms are now
   ~30 (translate/rotate/scale compounds, incl. a negative-x mirror scale), not "5, all
   rotate". Doesn't change the D2 conclusion (still no filters/patterns/SMIL), but the
   parser/transform surface is bigger than the log implies; DECISIONS should get an
   amendment entry.
9. **Draw order** — SVG painter's order is encoded as JSX statement order incl.
   mid-list conditionals (crest/horns under head, chest wall after mound, culotte under
   neck). SwiftUI ZStack/canvas order must be transcribed statement-by-statement; any
   "cleanup" reorders pixels.
10. **`Mood`-driven eye/mouth swap vs animation cancel** — React cancels the old WAAPI
    animation on mood change and starts the new one from identity. A naïve SwiftUI port
    can leave a mid-flight transform (e.g. cheer interrupted by idle) composing with the
    bob. The restart must reset the transform to identity first.
11. **Unreachable design candidates** (§8.6) — porting them is dead code; skipping them
    is a deliberate deviation from "port everything". Either way, record it in
    DECISIONS.md.
12. **Storybook** — `Mascot.stories.tsx` has no Swift equivalent; the D3 harness screen
    + Xcode Previews replace it. The stories' French captions (`"bébé"`, `"majestueux"`,
    `"Niveau N"`, `"débloqué au stade N"`) are dev-tool copy, not app copy — no port
    needed, noted so nobody goes looking for them.

---

## 16. Decisions needed at whole-app level (not decidable inside this scope)

1. **Rendering substrate for ALArt** — GraphicsContext-canvas (recommended, §4) vs view
   composition. Must be ONE answer shared with the exercise-icons agent; it also fixes
   the parser's output API (`Path` + draw helpers works for both).
2. **Home of the shared contract types** — `Species`, `Mood`, `MascotConfig`,
   `CustomizationOption`, `GROWTH_STAGES` are shared with profile/shop/storage agents;
   one owner file in ALCore, everyone imports.
3. **Reduced-motion source of truth** — a single injected flag (protocol in ALCore per
   D1) consumed by mascot motion, confetti, and the sheen, so tests and the D3 harness
   can force it.
4. **D3 harness plumbing** — a debug entry point that renders arbitrary
   `(species, stage, wardrobe, mood, preview, focus, size)` from launch arguments; shared
   across art scopes.
5. **Port or drop the unreachable dragon candidates** (§8.6) — needs a D-entry either way.
6. **Amend D2** with the exponent-number grammar requirement (§9) and refresh the
   transform census (risk 8).
