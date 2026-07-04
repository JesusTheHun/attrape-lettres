# CLAUDE.md

Guidance for Claude Code (and any agent) working in this repo. Read before editing.

## What this is

A French early-reading game for ~6yo children. Vite + React 18 + TypeScript (strict) + TailwindCSS.

## Commands

```bash
pnpm dev            # local dev server
pnpm build          # tsc -b && vite build
pnpm typecheck      # types only
```

Do **not** add `pnpm add <pkg>` commands to answers unless explicitly asked.
Prefer solving with what's here; this app deliberately avoids animation/audio
libraries.

## Conventions

- TypeScript, React function components, Tailwind classes. `strict`,
  `noUnusedLocals`, `noUnusedParameters` are on — keep it clean.
- Prose components stay small; colours that come from data are applied via
  `style`, everything else via Tailwind.
- French copy is user-facing; keep it kid-simple and in `fr`.

## Architecture map

- `types.ts` — domain types. `ExerciseId` is the nav/routing key; `SyllableMode`
  selects seeding; `SyllableTier` is difficulty.
- `content.ts` — the datasets. **Content only, no logic.**
- `levels.ts` — `FIRST_LETTER_LEVELS` (explicit, 5), `SYLLABLE_TIERS` (4) +
  `syllableTier`, the round builders (`firstLetterPool`, `buildSyllableRound`), and
  `EXERCISES` (the hub catalog). This is where difficulty/content wiring lives.
- `exercises/AssembleExercise.tsx` — ONE engine for all three syllable modes. Mode
  only reaches `buildSyllableRound`; the assembly loop is mode-agnostic.
- `App.tsx` — hub + a 3-line view router (`meta.mode ? Assemble : FirstLetter`).

## Invariants — do not break these

These are why the game feels alive to a child. Changing them silently will regress UX.

1. **Feedback fires on `pointerdown`, before React commits.** SFX + the WAAPI press
   animation happen synchronously in the pick handler. Never move feedback behind a
   state update / `useEffect`.
2. **Animation stays off the React render path.** Tile press/shake = WAAPI;
   confetti = canvas `requestAnimationFrame`. Do not re-render to animate.
3. **No fail state.** A wrong tap = soft `nudge()` + shake, nothing locked, nothing
   lost. There is no "wrong answer" terminal. Keep it that way.
4. **Content is authored, not computed.** Do not add a runtime French syllabifier or
   letter->word generator. New words go in `content.ts`, pre-split.
5. **All levels unlocked, always.** No gating/lock logic in navigation.
6. **Accessibility floor:** big tap targets, `aria-label`s on tiles, and
   `prefers-reduced-motion` respected (mascot + confetti). Maintain it.

## Recipes

**Add a word:** append to `LETTER_WORDS` or `SYLLABLE_WORDS` in `content.ts`. For
syllable words, author the split. That's it — pools derive automatically.

**Add a syllable-style exercise:** add a `SyllableMode`, branch it in
`buildSyllableRound`, add an `EXERCISES` row with that `mode`. No new component.

**Tune progression:** edit `SYLLABLE_TIERS` (syllable count + `pick`/`repeats`),
`FIRST_LETTER_LEVELS` (letter catalog + `pick`/`repeats`), or `SOUND_LEVELS`
(distractor count). Pure data; no component changes.

**Run shape:** every exercise seeds a run through `repeatSession(pool, pick,
repeats)` — pick N distinct items, replay `repeats` of them, never two rounds in
a row. Run length = `pick + repeats`; both clamp to the pool.

## Known follow-ups

- `useProgress` mastery hook keyed by `(exerciseId, level)` for spaced repetition.
- Adaptive distractors by confusability (`b/d/p/q`, `m/n`).
- Recorded VO sprite to replace `speechSynthesis` (device-consistent, lower latency).

## Git

Commit on `main` unless asked otherwise.
Never co-author commits