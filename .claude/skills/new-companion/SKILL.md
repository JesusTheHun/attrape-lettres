---
name: new-companion
description: Design-proposal pipeline for a new mascot companion (species). Use when the user asks for a new companion/mascotte ("/new-companion dragon", "propose a panda companion") or to integrate a picked proposal ("/new-companion ship dragon"). Produces 3 real-rig design candidates + ~15 style/accessory suggestions in a screenshot-QA'd HTML page; ship mode wires the picked design into the game.
argument-hint: <animal type + optional notes> | ship <species> <picked candidate + kept items>
---

# New companion playbook

Two modes, decided by the arguments:

- **Propose** (default): input is a companion type (e.g. "dragon", "panda —
  plutôt vert"). Output is `src/dev/proposals/<species>/proposal.html` — 3 design
  candidates built as REAL rigs + ~15 style/accessory suggestions, every visual
  screenshot-QA'd — sent to the user with SendUserFile. **No game code is touched.**
- **Ship** (`ship <species> …`): the user has picked a candidate and (optionally) a
  subset of items. Promote the proposal into the game (checklist in Phase 5).

Never merge the two modes into one run: the user reviews the proposal page and
picks before anything lands in the game.

## Phase 0 — Study (mandatory, in this order)

Read: `src/mascot/growth.ts`, `ids.ts`, `anchors.ts`, `parts.tsx`, `catalog.ts`,
`Fox.tsx` (best exemplar of the STAGE_SPEC pattern), `Mascot.tsx`,
`Mascot.stories.tsx`, `catalog.test.ts`, `anchors.test.ts`.

Hard constraints the rigs live by — violating any of these fails the proposal:

1. **Shared geometry only.** A rig receives `layout` from `layoutFor(stage)` and
   draws inside `viewBox 0 0 100 100`. Never invent your own head/body/leg
   geometry — head:body ratio, eye size, pose (lying 0–1 / wobbly 2–3 /
   standing 4–6 / proud 7–9) and the `feetY` ground line all come from `growth.ts`.
2. **Kawaii house style.** Pastel fills, `INK` (#5A3A1E) for dark details, shared
   face parts (`Eyes`, `Cheeks`, `Mouth` — they handle mood + the stade-0 sleepy
   eyes). Reuse `parts.tsx` primitives (`Leg`, `FoldedLegs`, `Plume`, `Sparkles`,
   `Aura`, `Halo`, `GroundGlow`, `SwimRing`, `Swimsuit`…) before drawing new ones.
3. **10-stade feature timeline.** A 10-entry `STAGES` spec array. Stades 0–2 stay
   plain (the untouched early game). Each stade 3→9 adds ONE clearly visible beat;
   stade 9 is a spectacle (aura / halo / ground glow / burst). The arc grows the
   animal into a mythical dream version of itself (fox→kitsune). A child clicking
   stade N→N+1 must SEE the change.
4. **Worn accessories anchor via `accessoryAnchors(species, layout)`** —
   neck / headTop / headSide / head / feet. Never a fixed body-relative point:
   that is the historical "bow in the middle of the baby's face" bug.
5. **Recolour via `pick(config.colors, slot, fallback)`** — the fallbacks are the
   factory look and MUST later equal the `DEFAULT_LOOKS` values (a test enforces it).
6. **`preview` prop** strips per-stage magic so a shop thumbnail shows only the
   sold part on a plain pet.
7. **Cross-species traditions:** every species carries the shared swimsuit + swim
   ring accessories, and exactly one 200-pt premium spectacle item gated `minStage 4`.
8. French copy is user-facing and kid-simple ("Couronne de fleurs", never jargon).

## Phase 1 — Concept: 3 design directions

For the requested animal, write 3 directions that differ in **silhouette and
growth arc**, not just palette. Each direction defines:

- Concept name + one-line pitch, and the kid-facing FR label ("Mon dragon").
- Silhouette hook — what makes it readable at 88 px and distinct from the
  existing unicorn / cat / fox.
- Palette: body / belly / 1–2 accents, as hex.
- Growth arc: a 10-row table, stade → visible beat (respecting constraint 3).
- Its colour slots, style slots and accessory list (feeding Phase 2's ~15 items).

Pick ONE recommended direction and say why in one line.

## Phase 2 — Build candidate rigs (scratch, wired to nothing)

Workspace: `src/dev/proposals/<species>/` — inside `src` so `tsc`/vitest compile
it (strict + noUnused must stay green), imported by no app code.

- `CandidateA.tsx`, `CandidateB.tsx`, `CandidateC.tsx` — one rig each, following
  the `RigProps` shape. The new species isn't in the `Species` union yet; rigs
  never read `config.species`, so the render spec simply passes an existing value
  (`species: "fox"`) in the config it builds. Note which existing `NECK_K` muzzle
  profile fits best (tuned for real at ship time).
- `ids.ts` — proposal-local slot names + accessory ids mirroring the shape of
  `src/mascot/ids.ts`, so ship mode is a move, not a rewrite.
- Suggest ~15 items total; the exact colour/style/accessory mix is yours to fit
  the species (default bands: ~7 colours, ~3 styles, ~5 accessories incl. the
  swim pair + one premium). **Every suggested item is implemented and rendered on
  the recommended candidate** — colours/styles via `pick()` slots, accessories
  drawn via anchors. Premium items should have at least one growth beat of their
  own (like the swim ring's duck at stade 7+).

## Phase 3 — Render + visual QA loop (the gate)

Copy `templates/render.spec.tsx.template` from this skill's folder into the
proposal folder as `render.spec.tsx` and adapt it. It is a vitest spec gated by
`PROPOSAL=<species>` (so plain `pnpm test` skips it) that `renderToStaticMarkup`s
the rigs into standalone HTML pages:

```bash
PROPOSAL=<species> pnpm vitest run src/dev/proposals/<species>/render.spec.tsx
bash .claude/skills/new-companion/templates/screenshot.sh <page.html> <out.png> [1400x1000]
```

QA pages to generate and screenshot (small, focused pages beat one giant page):

- one growth strip per candidate: stades 0→9, mood idle;
- one page per accessory: worn at EVERY stade 0→9 (this is the placement spec);
- one colours/styles board: each slot's variants next to the factory look.

Read every PNG and check, per cell:

- Species recognizable and silhouette clean at both stade 0 and stade 9.
- Stades 3→9: each cell shows a NEW visible beat vs its left neighbour.
- Hats sit ON the dome (touching, not floating or sunken); neck items sit below
  the muzzle, never on the face — including the lying stades 0–1 where the head
  rests low and forward; foot items track the per-stage legs; nothing covers the
  eyes; items scale with the shrinking head.
- An applied colour/style is obvious at a glance (a child must see what they bought).
- High stades overflow the 100-box upward and sideways by design — give QA cells
  large top padding (≥ 70 px) so overflow isn't read as clipping, and check
  nothing important is cut by a REAL edge.
- "Would a 6-year-old say this looks like a <hat/scarf/…> on a <animal>?" If it
  needs explaining, it fails.

Iterate — fix rig, re-render, re-screenshot, re-read — until every check passes.
The deliverable ships only after a fully green pass; record the iteration count
and any remaining doubts honestly in the QA appendix.

## Phase 4 — Deliverable HTML

Write `src/dev/proposals/<species>/proposal.html` — fully self-contained (inline
SVG only, no external assets), soft warm background matching the game
(`#FFF7EC`). Structure:

1. **Header** — brief as received, date, and the recommendation banner.
2. **3 design directions** — per direction: pitch, palette chips, growth-arc
   table, and the full stade 0→9 rendered strip. Recommended one visibly marked.
3. **~15 item suggestions** — grouped colours / styles / accessories. Each card:
   FR kid name, emoji, proposed cost (bands: colours 15–30, styles 30–60,
   accessories 40–150, premium 200), proposed `minStage` + one-line reason, and a
   rendered strip **at every stade where the item's look changes** — pose changes
   (lying→standing) always count, plus each beat stade; caption each cell with
   the stade range it covers ("stades 4–6"). Colour cards may show one
   before/after pair instead.
4. **QA appendix** — checks run, iteration count, open doubts.

Item names and all copy destined for the game: French, kid-simple.

Then: `SendUserFile` the page (display: render), and commit the proposal folder
on `main` (`feat(mascot): proposition <species>` — repo rule: no co-author
trailer). Final message: recommendation + the 2–3 decisions the user must make
(candidate pick, items to keep/drop, gates to adjust).

## Phase 5 — Ship mode

Input: chosen candidate + kept items (default: recommended candidate, all items).
Integration touchpoints — the `Record<Species, …>` types make most omissions
fail `pnpm typecheck`, which is the safety net; go in this order:

1. `src/types.ts` — extend the `Species` union.
2. `src/mascot/ids.ts` — move the proposal's COLOR_SLOT / STYLE_SLOT / ACCESSORY
   entries in.
3. `src/mascot/<Name>.tsx` — promote the chosen candidate: read slots from the
   real `ids.ts`, implement `preview` stripping (constraint 6).
4. `src/mascot/anchors.ts` — add the species' `NECK_K` (tune from QA screenshots).
5. `src/mascot/Mascot.tsx` — `LABELS` entry + dispatch branch (+ `RAINBOW_IDS`
   if the premium is a whole-image sheen).
6. `src/mascot/catalog.ts` — CATALOG entries (cost bands above) + `DEFAULT_LOOKS`
   whose values equal the rig's `pick()` fallbacks exactly.
7. `src/mascot/catalog.test.ts` — `EXPECTED_GATES` entries + `SPECIES` array.
8. `src/mascot/anchors.test.ts` — `SPECIES` array.
9. `src/mascot/Mascot.stories.tsx` — `Croissance<X>` + `Accessoires<X>` stories.
10. `src/hooks/useProfile.tsx` — `ALL_SPECIES` + `blankSpeciesMap` entry.
11. `src/shop/Picker.tsx` — species tile.
12. `src/shop/ItemPreview.tsx` — per-species preview focus/crop branches.
13. `src/dev/MascotGallery.tsx` — gallery row.

Then, in order: re-run the Phase 3 QA loop once against the SHIPPED rig (growth
strip + full accessory matrix — placement must survive the ids/anchors move);
`pnpm typecheck` && `pnpm test`; delete `src/dev/proposals/<species>/`; commit
(`feat(mascot): nouveau compagnon <species>`).
