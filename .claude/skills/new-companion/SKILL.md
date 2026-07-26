---
name: new-companion
description: Design-proposal pipeline for a new mascot companion (species). Use when the user asks for a new companion/mascotte ("/new-companion dragon", "propose a panda companion"), for its wardrobe after picking a design ("/new-companion items dragon B"), or to integrate the result ("/new-companion ship dragon"). Each run produces a screenshot-QA'd HTML page; nothing lands in the game before ship.
argument-hint: <animal type + optional notes> | items <species> <picked candidate> | ship <species> <kept items>
---

# New companion playbook

Three modes = three runs, with a user pick between each. Decided by the arguments:

- **Propose** (default): input is a companion type (e.g. "dragon", "panda —
  plutôt vert"). Output: 3 design candidates built as REAL rigs (no accessories
  yet) in `src/dev/proposals/<species>/proposal.html`. **No game code is touched.**
- **Items** (`items <species> <candidate>`): the user picked a design. Implement
  ~15 style/accessory suggestions ON THAT RIG, screenshot-QA placement, deliver
  `items.html`. Accessories are designed for the picked silhouette — never
  recycled blind from another candidate: a wing charm only makes sense on the
  winged design, and every anchor position depends on the body that wears it.
- **Ship** (`ship <species> …`): promote design + kept items into the game
  (checklist in the Ship section).

Never merge modes into one run: the user reviews each page and picks before the
next step spends work on it.

## Phase 0 — Study (mandatory in every mode, in this order)

Read: `src/mascot/growth.ts`, `ids.ts`, `anchors.ts`, `parts.tsx`, `catalog.ts`,
`Fox.tsx` (best exemplar of the STAGE_SPEC pattern), `Mascot.tsx`,
`Mascot.stories.tsx`, `catalog.test.ts`, `anchors.test.ts`.

Hard constraints the rigs live by — violating any of these fails the run:

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

## Render + screenshot QA loop (shared by every mode — the gate)

Copy `templates/render.spec.tsx.template` from this skill's folder into the
proposal folder as `render.spec.tsx` and adapt. It is a vitest spec gated by
`PROPOSAL=<species>` (so plain `pnpm test` skips it) that `renderToStaticMarkup`s
the rigs into standalone HTML pages:

```bash
PROPOSAL=<species> pnpm vitest run src/dev/proposals/<species>/render.spec.tsx
bash .claude/skills/new-companion/templates/screenshot.sh <page.html> <out.png> [1400x1000]
```

Generate small focused QA pages, screenshot each, Read every PNG, check against
the mode's checklist below, then fix → re-render → re-screenshot → re-read until
every check passes. A deliverable ships only after a fully green pass; record the
iteration count and any remaining doubts honestly in its QA appendix. Layout
note: high stades overflow the 100-box upward and sideways by design — give QA
cells large top padding (≥ 70 px) so overflow isn't misread as clipping, and
check nothing important is cut by a REAL edge.

---

## Mode: propose — 3 design candidates

### P1 — Concept

Write 3 directions that differ in **silhouette and growth arc**, not just
palette. Each direction defines:

- Concept name + one-line pitch, and the kid-facing FR label ("Mon dragon").
- Silhouette hook — what makes it readable at 88 px and distinct from the
  existing unicorn / cat / fox.
- Palette: body / belly / 1–2 accents, as hex.
- Growth arc: a 10-row table, stade → visible beat (respecting constraint 3).
- Its colour/style slots, and a TEXT-ONLY shortlist of item ideas that fit this
  silhouette (helps the user pick; not rendered, not final — the items run
  redesigns the list around the winner).

Pick ONE recommended direction and say why in one line.

### P2 — Build candidate rigs (scratch, wired to nothing)

Workspace: `src/dev/proposals/<species>/` — inside `src` so `tsc`/vitest compile
it (strict + noUnused must stay green), imported by no app code.

- `CandidateA.tsx`, `CandidateB.tsx`, `CandidateC.tsx` — one rig each, factory
  look only (colour/style slots wired via `pick()` fallbacks; **no accessories
  in this mode**). Rigs follow the `RigProps` shape; the new species isn't in the
  `Species` union yet and rigs never read `config.species`, so the render spec
  passes an existing value (`species: "fox"`) in the configs it builds.
- Note which existing `NECK_K` muzzle profile fits each candidate best (the items
  run will need it; tuned for real at ship time).

### P3 — QA checklist (design)

One growth-strip QA page per candidate (stades 0→9, mood idle). Per strip:

- Species recognizable and silhouette clean at both stade 0 and stade 9; the
  three candidates clearly distinct from each other AND from unicorn/cat/fox.
- Stades 3→9: each cell shows a NEW visible beat vs its left neighbour.
- Lying stades 0–1 read as a curled/resting baby; face parts sit right on the
  forward-resting head.
- Palette reads pastel-kawaii next to the existing pets (render one existing pet
  alongside as reference).

### P4 — Deliverable `proposal.html`

Self-contained (inline SVG only, no external assets), warm background `#FFF7EC`:

1. **Header** — brief as received, date, recommendation banner.
2. **3 design directions** — per direction: pitch, palette chips, growth-arc
   table, full stade 0→9 rendered strip, and the text-only item-idea shortlist.
   Recommended one visibly marked.
3. **QA appendix** — checks run, iteration count, open doubts.

Then: `SendUserFile` the page (display: render), commit the proposal folder on
`main` (`feat(mascot): proposition <species>` — repo rule: no co-author
trailer). Final message: recommendation + the single decision asked of the user
(pick A / B / C, or ask for a variation).

---

## Mode: items — wardrobe for the picked design

Input: the picked candidate (re-read the proposal folder + `proposal.html`).
All work targets THAT rig; losing candidates stay untouched in the folder until
ship deletes them.

### I1 — Item design

Suggest ~15 items designed for the picked silhouette. The colour/style/accessory
mix is yours to fit the species (default bands: ~7 colours, ~3 styles,
~5 accessories incl. the shared swim pair + one premium spectacle). Every item is
implemented and rendered: colours/styles via `pick()` slots, accessories drawn
via `accessoryAnchors` (constraint 4). Premium items should have at least one
growth beat of their own (like the swim ring's duck at stade 7+). Collect ids in
a proposal-local `ids.ts` mirroring the shape of `src/mascot/ids.ts`, so ship
mode is a move, not a rewrite.

### I2 — QA checklist (placement)

One QA page per accessory (worn at EVERY stade 0→9 — the placement spec) plus a
colours/styles board (each slot's variants next to the factory look). Per cell:

- Hats sit ON the dome (touching, not floating or sunken); neck items sit below
  the muzzle, never on the face — including the lying stades 0–1 where the head
  rests low and forward; foot items track the per-stage legs; nothing covers the
  eyes; items scale with the shrinking head.
- An applied colour/style is obvious at a glance (a child must see what they bought).
- Gated items would make sense at their proposed `minStage` (the part they dress
  is visible from that stade on).
- "Would a 6-year-old say this looks like a <hat/scarf/…> on a <animal>?" If it
  needs explaining, it fails.

### I3 — Deliverable `items.html`

Same shell as `proposal.html`. Structure:

1. **Header** — picked design (small stade-4 render as reminder), date.
2. **~15 item cards** — grouped colours / styles / accessories. Each card:
   FR kid name, emoji, proposed cost (bands: colours 15–30, styles 30–60,
   accessories 40–150, premium 200), proposed `minStage` + one-line reason, and a
   rendered strip **at every stade where the item's look changes** — pose changes
   (lying→standing) always count, plus each beat stade; caption each cell with
   the stade range it covers ("stades 4–6"). Colour cards may show one
   before/after pair instead.
3. **QA appendix** — checks run, iteration count, open doubts.

Then: `SendUserFile`, commit (`feat(mascot): garde-robe <species>`). Final
message: which items you'd keep if forced to cut, + the decision asked of the
user (keep/drop list, gate adjustments).

---

## Mode: ship

Input: picked candidate + kept items (default: all items from `items.html`).
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

Then, in order: re-run the QA loop once against the SHIPPED rig (growth strip +
full accessory matrix — placement must survive the ids/anchors move);
`pnpm typecheck` && `pnpm test`; delete `src/dev/proposals/<species>/`; commit
(`feat(mascot): nouveau compagnon <species>`).
