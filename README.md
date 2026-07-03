# Attrape-Lettres

A small French early-reading game for young children (~6yo). Two skills, four
games, every level unlocked at all times.

- **La première lettre** — hear a word, tap its first letter. 5 levels; each level
  widens the letter catalog (Level 1 is tiny on purpose so words recur and stick).
- **Complète le mot** / **Range les syllabes** / **Trouve l'intrus** — build a word
  from syllable tiles. Same engine, three seedings; 4 difficulty levels each.

## Stack

Vite · React 18 · TypeScript (strict) · TailwindCSS. No runtime dependencies beyond
React — audio is generated (Web Audio), speech is `speechSynthesis`, confetti is a
canvas `requestAnimationFrame` loop.

## Run

```bash
npm install
npm run dev
```

`npm run build` type-checks (`tsc -b`) then bundles. `npm run typecheck` runs the
type-checker alone.

## Project shape

```
src/
  types.ts                 domain types (exercises, tiers, rounds, view)
  content.ts               datasets: letter words, pre-split syllable words, syllable bank
  levels.ts                letter-level table, syllable tier ladder, round builders, hub catalog
  hooks/
    useAudio.ts            gesture-unlocked Web Audio SFX + quality-picked French TTS
    useConfetti.ts         canvas rAF burst, decoupled from React renders
  components/
    Ollie.tsx              mascot
    Tile.tsx               one tappable tile: WAAPI press + forgiving shake
    GameFrame.tsx          gradient stage, confetti mount, back button, progress
  exercises/
    FirstLetterExercise.tsx
    AssembleExercise.tsx   shared engine for all three syllable modes
  App.tsx                  hub (all levels unlocked) + view router
  main.tsx, index.css
```

## Two design decisions worth knowing

1. **Syllabification is authored, not computed.** French segmentation is a rabbit
   hole; syllables live in `content.ts` as data (`{ word, syllables, emoji }`).
2. **Difficulty is the only axis for syllable games.** The three mechanics share
   one 4-tier ladder (`SYLLABLE_TIERS`) and one engine; a level *is* a tier. Adding a
   mechanic that fits "fill slots from a tray" is one seeder branch + one catalog row.

See `CLAUDE.md` for the invariants that keep the app feeling responsive to a child.

## Not built yet (clean extension points)

- `useProgress` mastery hook, keyed by `(exerciseId, level)`.
- Adaptive distractors (letter confusability `b/d/p/q`, `m/n`).
- Recorded voice-over sprite to replace `speechSynthesis` (consistent, lower latency).
