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
- `exercises/SyllableGridExercise.tsx` — ONE engine for both combinatoire drills
  (`SyllableGridMode`: `hear` / `vowel`) over the « tableau des syllabes »
  (`SYLLABLE_GRID_ROWS` × `GRID_VOWELS`, content). The consonant×vowel rung that
  comes BEFORE any word exercise. Mode only changes the distractor rule and what
  a tile shows.
- `App.tsx` — hub + a 3-line view router (`meta.mode ? Assemble : FirstLetter`).
- `components/ExerciseIcon.tsx` — the hub's original per-exercise icons (tinted
  badge + in-house white pictogram, NO emoji). Keyed by `ExerciseId`, so a new
  exercise fails to compile until it has an icon.

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
7. **Every exercise has an original drawn icon, never an emoji.** The hub renders
   `<ExerciseIcon id>`, not `ex.emoji`. Adding an exercise ⇒ add its icon in the
   SAME change (the `Record<ExerciseId, …>` in `ExerciseIcon.tsx` enforces it).
8. **Farming never pays.** Kids will spam-tap every tile to grind points; the
   design absorbs it without a fail state (see 3). Three mechanisms, keep all:
   single-tap picks are silently swallowed for `MISS_COOLDOWN_MS` after a miss
   (sequence engines pace retries with the "Oh non" line instead); each round's
   star greys on its FIRST wrong tap, at pointerdown, in the GameFrame strip;
   and all points flow through `sessionReward()` — completion curve + accuracy
   bonus weighted by the exercise's authored `difficulty`. Finishing pays the
   curve on EVERY row, training rows included; `difficulty: 0` means the bonus
   is zero, so on those rows careful play earns exactly what spam earns and
   there is nothing to grind for. The gradient is what carries the invariant:
   the best a training row can pay is the worst a paying row can pay. Never
   award points outside `sessionReward`, and never let accuracy on a
   spam-completable path pay.

## Recipes

**Add a word:** append to `LETTER_WORDS` or `SYLLABLE_WORDS` in `content.ts`. For
syllable words, author the split. That's it — pools derive automatically.

Two rules on the split, both learned the hard way:
- **A shared syllable must sound the same in every word that uses it.** One tile
  string = one baked clip, so MAI-SON (/zɔ̃/) and POIS-SON (/sɔ̃/) cannot coexist
  — no TTS can voice both. Check `SYLLABLE_BANK` for the tile before adding.
- **A fragment may take its in-word sound only when that's a real French rule.**
  Intervocalic S → /z/ (dino-SAURE) and silent finals (choco-LAT) teach something
  true. PAPI-LLON needed « ll » to say /j/, which is false — it's « ill » that
  does, and it straddles the split. That word had to go.

**Fix a pronunciation:** add a row to `IPA` in `scripts/generate-vo.mjs` — the
phonetic target, keyed by lowercase token, matched in the same four utterance
shapes as `SOUND_SAY`. It rides the *instruction*, so the model still receives
real French and the prosody survives; a row here suppresses the older homophone
substitution for that token. `IPA_EXACT` keys whole utterances (the ANNIVERSAIRE
`AN` tile is /an/, not the /ɑ̃/ of the sound). `IPA_BOTH` keeps both levers for a
straggler that ignores the instruction alone. Anchor words for « … comme dans … »
must contain the target sound exactly ONCE (« au, comme dans auto » was wrong:
/oto/ has two, and the second is spelled O). The backend is non-deterministic —
delete the clip, re-bake, and LISTEN. A row is a hypothesis until you do.

**A long clip is not a pronunciation bug.** The model sometimes reads its own
instruction aloud instead of the text — « u » came back as a 20-second story, and
a 1-syllable « Nid » once ran 30. This looks like a phonetic defect and is not:
it's a random roll. A five-arm probe (concat / two parts / systemInstruction /
hammered delimiter / terse) proved it — the SAME payload gave a clean « u » and
the 30-second « Nid ». Do not rewrite the instruction to fix it. `generate-vo.mjs`
has a **length gate**: a take running ≥ `VO_GATE_RATIO` (2.2) × the expected
duration for its shape is thrown out and re-rolled, `VO_GATE_RETRIES` (2) times.
Calibrated at 0 false positives over 831 clips, worst legitimate ratio 1.77×.
`--no-gate` disables it. Rejected takes are KEPT in `src/vo/clips/.rejects/`
(gitignored) — listen to them, because duration alone cannot tell "recited the
instruction" from "read the text four times", and those need opposite fixes.

Corollary: chase a pronunciation only when French says you must. The three that
survived scrutiny were reasoned, not heard — the `SON` collision (MAI-SON /zɔ̃/ vs
POIS-SON /sɔ̃/), « ll » never saying /j/ (it's « ill »), and the once-only anchor
rule. Everything else was clip roulette and stale files.

**Keep the instruction short.** Styles are HEADS (`STYLE`, `STYLE_SYLLABLE`,
`STYLE_LETTER`); `styleFor()` appends the IPA clause and the closing « Lis : ».
An earlier, far wordier wording steered no better by ear and cost ~3× the
characters. Two parts are load-bearing and must stay: the trailing colon (batch
has no prompt field, so it PREPENDS — drop the colon and the model reads the
instruction aloud) and the « enfant de six ans, en français » framing (safety
classifier context; without it short syllables like « nu », « tu » get rejected as
English).

**Add a syllable-style exercise:** add a `SyllableMode`, branch it in
`buildSyllableRound`, add an `EXERCISES` row with that `mode` and a `difficulty`
(required — 0 = training/no accuracy bonus, 1–4 = accuracy-bonus weight rising
with the hub progression). No new component. Then add its icon (next recipe) —
that step is not optional.

**Add an exercise icon (ALWAYS when adding an exercise):** add a `GLYPHS[<id>]`
entry in `components/ExerciseIcon.tsx` — a distinct `tint` + an in-house white
SVG pictogram that says what the game does (letter games get real letterforms;
the "mêlées" twins wear the `ShuffleChip`). No emoji. The keyed `Record` won't
compile without it, so this happens in the same change, automatically.

**Add a consonant row to the syllable grid:** append it to `SYLLABLE_GRID_ROWS`
in `content.ts` (and a matching `SYLLABLE_GRID_LEVELS` row in `levels.ts` if it's
a new level). Both drills, their pools and their VO derive from it. Keep out any
consonant whose sound flips with the vowel (C, G, K, QU) — that's Trouve le son /
Les syllabes jumelles' job, and `levels.test.ts` guards it.

**Tune progression:** edit `SYLLABLE_TIERS` (syllable count + `pick`/`repeats`),
`FIRST_LETTER_LEVELS` (letter catalog + `pick`/`repeats`), or `SOUND_LEVELS`
(distractor count). Pure data; no component changes.

**Tune the economy:** `difficulty` per `EXERCISES` row (levels.ts) and the knobs
in `rewards.ts` (`REWARD_CURVE`, `sessionReward`, `MISS_COOLDOWN_MS`). A careful
full-perfect run earns curve + `difficulty`; spam earns the bare curve. Keep the
gradient monotone with the hub order so climbing always out-pays grinding.

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