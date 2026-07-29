# Engines — Swift port specification

Scope: `src/exercises/` (all nine engines) plus the exact contracts they consume from
`Tile.tsx`, `GameFrame.tsx`, `Finished.tsx`, `FitLine.tsx`, `useAudio.ts`, `useConfetti.ts`,
`rewards.ts` and the round builders in `levels.ts`. Behaviour is frozen; the PWA is the
specification. Where this document says "verbatim" the French string is byte-for-byte.

Source of truth read for this spec: `/Users/jonathan/IdeaProjects/attrape-lettres/src`
(original working tree — NOT the worktree checkout).

---

## 1. Inventory

### In scope (src/exercises/)

| File | Lines | What it actually does |
|---|---|---|
| `AssembleExercise.tsx` | 305 | ONE engine for the three `SyllableMode`s (`fill-blank`, `order`, `order-distractor`). Word spoken; syllable tiles fill slots left-to-right; whole row judged only when complete; wrong row → "Oh non ! On recommence." + wipe; filled non-locked slots tappable to undo. |
| `FindSoundExercise.tsx` | 188 | Single-pick: hear « sound, comme dans mot », tap the graphy tile. Miss → nudge + 800 ms cooldown + star greys. |
| `FirstLetterExercise.tsx` | 196 | Single-pick: hear « Trouve la première lettre de X. », tap the letter. Builds its own session (target + 2 distractor letters from the level catalog). Levels 4–5 hide the written word on the listen button. |
| `LetterMatchExercise.tsx` | 205 | Single-pick: big prompt glyph (case or script form), tap the counterpart-form tile. Prompt line never names the letter; success line does (`Oui ! A.`). |
| `ReadImageExercise.tsx` | 199 | Single-pick mirror of FirstLetter: printed word shown (never spoken), tap the matching picture. Prompt is the constant « Trouve la bonne image. » |
| `SoundTwinsExercise.tsx` | 232 | Multi-select: hear « Trouve tous les ko ! », tap EVERY tile writing that sound. Correct partial taps lock into a collection strip and speak their anchor word; intruder tap = miss. Round completes when all family tiles found. |
| `SpellSoundExercise.tsx` | 305 | Assembly with letter tiles: hear a sound (+ optional « comme dans mot »), spell it into per-letter slots. Same loop as Assemble (row-judged, wipe on wrong, undo). |
| `SpellSyllableExercise.tsx` | 349 | Assembly: word printed with 1–2 syllables blanked into per-letter slots. `mixed` variant judges the letter's FACE (case + script), not only the letter. Owns `HEADLINE` / `MIXED_HEADLINE` copy. |
| `SyllableGridExercise.tsx` | 221 | Single-pick over the « tableau des syllabes », two modes: `hear` (tiles show whole syllables) / `vowel` (consonant pre-written, tiles are vowels; the picked vowel drops into the gap on success). |

### Contracts consumed (adjacent scope — the engines cannot be specified without them)

| File | Lines | Contract the engines depend on |
|---|---|---|
| `components/Tile.tsx` | 131 | The pick primitive. `onPick: () => Verdict` runs **synchronously on pointerdown**; press animation always plays; verdict `"reject"` adds a shake. Optional `onPreview` renders a separate full-width Écouter button below the tile (own target, gap-separated). `disabled` blocks both. This file carries invariants 1 and 2. |
| `components/GameFrame.tsx` | 74 | Stage gradient, ← Menu button, star strip (progress + per-round verdict), full-bleed confetti canvas overlay. |
| `components/Finished.tsx` | 44 | End screen: 🤩, EarnBadge when `earned > 0`, star row, title, EndButtons. |
| `components/FitLine.tsx` | 64 | Shrink-to-fit one-line row; measures natural width, scales with a transform, never re-renders the exercise. |
| `hooks/useAudio.ts` | 295 | `unlock/pop/success/nudge/oops/say/stop`. `say(text) → Promise<boolean>` — single-flight, latest-wins, watchdogged, never hangs. Engines gate advancement on the boolean. |
| `hooks/useConfetti.ts` | 94 | `fire()` bursts 90 particles on a canvas rAF loop off the render path; no-op under `prefers-reduced-motion`. |
| `rewards.ts` | 60 | `MISS_COOLDOWN_MS = 800`; `sessionReward(difficulty, priorClears, perfectRounds, totalRounds)`. |
| `levels.ts` | 1030 | All session/round builders (`repeatSession`, `buildSyllableRound`, `buildSoundRound`, `buildSpellSyllableRound`, `buildSyllableGridSession`, `buildFindSoundSession`, `buildTwinSession`, `buildLetterMatchSession`, `buildReadImageSession`, FirstLetter's inline `buildSession`), the prompt/success line functions, and the copy maps (`MODE_HINT`, `GRID_PROMPT`, `READ_IMAGE_PROMPT`, …). Owned by the levels/content agent; the exact function-level surface the engines call is listed in §4.9. |
| `hooks/useProfile.tsx` (award) | — | `award(exercise, level, perfectRounds, totalRounds) → Int` — the ONLY way an engine produces points. Bumps the star counter and the clear ledger. Owned by the profile agent. |
| `letterForms.ts` | 21 | `SCRIPT_FONT` (print = rounded sans, cursive = OS handwriting font) and `faceLabel(face)` a11y string. |

---

## 2. Swift module plan

Three engine *families* cover the nine TSX files. The TS files are copy-pasted loops that
differ only in data types and lines; the Swift port factors each family into ONE
host-testable model. This is a structural refactor, not a behavioural one — every
divergence between siblings (there are a few, called out in §4) is carried as
per-exercise configuration, never dropped.

### Sources/ALCore (pure Swift, no SwiftUI/UIKit, `swift test` on host)

| File | Owns |
|---|---|
| `Engines/EnginePorts.swift` | `AudioPort` protocol (mirror of `AudioApi`: `unlock()`, `pop()`, `success()`, `nudge()`, `oops()`, `say(_:rate:) async -> Bool`, `stop()`); `EngineEffects` protocol (`fireConfetti()`); `EngineClock` protocol (`now() -> TimeInterval`, monotonic — maps `performance.now()`); `AwardPort` protocol (`award(exercise:level:perfectRounds:totalRounds:) -> Int`). |
| `Engines/EngineShared.swift` | `Verdict` (enum `accept`/`reject`), `Mood` (enum `idle`/`happy`/`cheer`) if not already in the types module; `missCooldownMS` re-export; the shared star array + `missRound(i)` helper; the run-completion constants (finished titles / bravo lines per exercise, see §4.8). |
| `Engines/SinglePickEngine.swift` | `@Observable final class SinglePickEngine<Round>` — the loop shared by FirstLetter, FindSound, SyllableGrid, LetterMatch, ReadImage. Generic over the round type; configured with a `SinglePickDescriptor<Round>` (see below). |
| `Engines/AssemblyEngine.swift` | `@Observable final class AssemblyEngine<Round, SlotValue>` — the loop shared by Assemble, SpellSound, SpellSyllable. Slots, tray, `used` set, whole-row judgement, wipe-and-retry, `removeAt` undo. |
| `Engines/TwinsEngine.swift` | `@Observable final class TwinsEngine` — the SoundTwins multi-select loop. |
| `Engines/EngineDescriptors.swift` | The nine concrete descriptor values wiring each `ExerciseId` to its session builder, prompt/success/preview line closures, judge, headline, finished title and bravo line. This file is the 1:1 audit surface against the nine TSX files. |
| `Confetti/ConfettiSimulation.swift` | Pure particle physics: `Particle` struct, `fire(width:height:scale:)` spawning 90 particles, `step()` integration (gravity, rotation, `life -= 0.008`, cull at `life <= 0 || y > height + 40`). No drawing. Host-testable. |

Dependency direction: `EngineDescriptors` → (`Levels`, `Content`, `Rewards` — other agents' ALCore files) and → the three engine classes. Engine classes depend only on `EnginePorts` + the round types. Nothing in ALCore imports SwiftUI.

### Sources/ALUI (SwiftUI + the one UIKit representable)

| File | Owns |
|---|---|
| `Components/TilePressControl.swift` | **The invariant-1 primitive.** `UIControl` subclass + `UIViewRepresentable` wrapper hosting arbitrary SwiftUI content. Touch-DOWN semantics, synchronous feedback, CAAnimation press/shake. See §5.1. `#if canImport(UIKit)`. |
| `Components/TileView.swift` | Port of `Tile.tsx`: colors, disabled (opacity 0.4), highlight ring, size/fontSize, a11y label, optional preview (Écouter) button stacked below with 8 pt gap. |
| `Components/GameFrameView.swift` | Port of `GameFrame.tsx`: stage gradient `#FFE7C9 → #FFEFD6 (38%) → #DCEFFB`, rounded-rect clip, ← Menu, star strip, confetti overlay (z-order: confetti above content is *not* the case — canvas is z 40, content z 41; confetti draws UNDER the interactive layer). |
| `Components/ConfettiView.swift` | `TimelineView(.animation)` + `Canvas` drawing `ConfettiSimulation`. See §5.2. |
| `Components/FinishedView.swift` | Port of `Finished.tsx`. |
| `Components/FitLine.swift` | Shrink-to-fit row container. See §5.4. |
| `Exercises/FirstLetterExerciseView.swift` … nine files, one per TSX | Thin views: instantiate the family engine with the exercise's descriptor, lay out the exercise-specific middle content (WordIcon / big glyph / printed word / consonant-gap / collection strip), bind tiles. 1:1 with the TSX files for auditability. |

`App/` injects the platform `AudioPort` (AVFoundation), the real clock, and the profile store.

Mascot rendering (`Mascot config mood`) is ALArt scope; engine views pass `profile.config` + the engine's `mood` through.

---

## 3. Type mapping

| TS | Swift | Notes |
|---|---|---|
| `type Verdict = "accept" \| "reject"` | `enum Verdict { case accept, reject }` | Returned synchronously by `pick`. |
| `type Mood = "idle" \| "happy" \| "cheer"` | `enum Mood` | Engine model property. |
| `ExerciseId` (17-case string union) | `enum ExerciseId: String, CaseIterable` | Raw values = the exact TS strings (they key the completion ledger; changing one orphans persisted clears). |
| `SyllableMode` / `SyllableGridMode` / `SpellSyllableMode` / `LetterMatchKind` / `LetterScript` | `enum … : String` | Raw values preserved. |
| `LetterWord { letter, word, emoji, img? }` | `struct LetterWord` with `img: String?` (asset name) | |
| `SyllableWord { word, syllables, emoji, img? }` | `struct SyllableWord` | |
| `LetterFace { base, glyph, script }` | `struct LetterFace: Equatable, Hashable` | `sameFace(a,b)` ≡ `a.glyph == b.glyph && a.script == b.script` — implement as a dedicated `renderKey` equality, NOT `==` on the whole struct (`base` must not participate: two faces are the same tile iff they *render* identically). |
| `SyllableRound { word, slots: (string\|null)[], locked: boolean[], tray: SyllableTile[] }` | `struct SyllableRound { word; slots: [String?]; locked: [Bool]; tray: [SyllableTile] }` | `null` → `Optional`. |
| `SyllableTile { id: number, syllable }` / `SoundTile` / `SpellLetterTile` / `TwinTile` | structs with `id: Int` | Ids come from module-level counters in TS (`_tileId++` etc.). Swift: a per-builder `static var` counter or an injected `IdGenerator`. Uniqueness only matters within a session; never persisted. |
| `SoundRound { target, slots, tray }` | struct | |
| `SpellSyllableRound { word, cells, answer, answerFaces, tray }` / `SpellCell` | structs | `slotIndex: -1` for shown cells → keep `Int` `-1` (or `Int?`; keep `-1` to match the builder 1:1). |
| `GridSyllable`, `GridRound`, `FindSoundRound`, `TwinFamily`, `TwinGraphy`, `TwinRound`, `LetterMatchRound`, `ReadImageRound`, `FirstLetterRound` | structs | Direct field-for-field ports. |
| `BasicSound { …, traps?: string[] }` | `traps: [String]?` (or `= []`) | |
| `Set<number>` (`used`) | `Set<Int>` | |
| `(string \| null)[]` slots | `[String?]` / `[LetterFace?]` | |
| React `useState` per field | properties on ONE `@Observable` model per running exercise | |
| `useRef` mirrors (`starsRef`, `coolUntil`, `locked`, `mountedRef`) | plain stored properties on the model | The ref/state duplication exists only because React closures go stale. A Swift class has one source of truth; the *semantics* (star count read at award time is never stale; `locked` flips synchronously) are what must be preserved. |
| `AudioApi` | `protocol AudioPort: AnyObject` | `say` is `async -> Bool`, non-throwing. See §4.7 for the exact semantics engines rely on. |
| `Difficulty = 0\|1\|2\|3\|4` | `enum Difficulty: Int` or validated `Int` | Owned by rewards scope; engines never read it directly (only `award` does). |

Index signatures (`Record<string, number>` counters, etc.) are profile scope — engines only see `award`'s return `Int`.

---

## 4. Behaviour — the round lifecycle, precisely

### 4.0 Common shape

Every engine:

1. **Seeds once** at init: `session = <builder>(level …)` (array of rounds/targets),
   `idx = 0`, `stars = [Bool](repeating: true, count: session.count)`, `mood = .idle`,
   `done = false`, `earned = 0`, `locked = false`.
2. **Announces** the current round's prompt line.
3. Accepts **picks** (synchronous verdict) and optional **previews**.
4. On round completion: celebration → *await the success line* → advance or finish.
5. On finish: `mood = .cheer`, `earned = award(exercise, level, stars.count(true), session.count)`,
   `done = true`, speak the bravo line (not awaited), show `Finished`.

`missRound(i)`: greys star `i` exactly once —
```ts
if (!starsRef.current[i]) return;
starsRef.current = starsRef.current.map((s, j) => (j === i ? false : s));
```
Swift: `func missRound(_ i: Int) { guard stars[i] else { return }; stars[i] = false }`.
It is called **synchronously inside the pick handler**, before any await — the star greys
in the same beat as the shake/oops (invariant 8).

**Mount/unmount:**
- On appear: `audio.unlock()` once.
- On disappear: `audio.stop()` (fades the current clip 200 ms then cuts) AND set
  `isActive = false` (TS `mountedRef`). Every async continuation checks `isActive`
  before mutating state; a dead engine never advances, never awards.
- Announce timers are cancelled on disappear/advance (TS `clearTimeout` cleanup).

**Announce timing — two different schemes, port both:**
- *Single-pick + twins engines*: an effect keyed on `idx` speaks the prompt **350 ms**
  after every round becomes current (first round AND every advance).
  `window.setTimeout(..., 350)` → `Task { try await clock.sleep(350ms); guard stillCurrentRound else return; await audio.say(prompt) }` (cancel on advance/disappear).
- *Assembly engines*: only round 0 uses the 350 ms delayed announce. Subsequent rounds
  announce **immediately** inside `loadRound(word)` (`void audio.say(word.word)` — no delay).

**GameFrame progress inputs:** `done: done ? session.count : idx`, `total: session.count`,
`stars`. Strip rendering (§5.3) is GameFrame's job; engines just expose these.

### 4.1 State machine — single-pick family
(FirstLetter, FindSound, SyllableGrid, LetterMatch, ReadImage)

Model state: `idx`, `mood`, `flash: String?` (the highlighted correct answer key),
`done`, `earned`, `stars`, `coolUntil: TimeInterval` (monotonic), `locked: Bool`.

```
            ┌──────────────────────────────────────────────────────────┐
            ▼                                                          │
 [Announce(idx)] --350ms--> say(prompt)          (fire and forget)     │
            │                                                          │
   AWAITING_PICK  (locked=false, flash=nil)                            │
      │ pick(key)                                                      │
      ├─ locked            → return .reject      (shake, NO audio)     │
      ├─ now < coolUntil   → return .reject      (shake, NO audio)  ←──┼── invariant 8:
      │                                            "silently swallowed" = press+shake
      │                                            animations still play, zero sound,
      │                                            zero state change
      ├─ wrong key:
      │     audio.unlock(); audio.pop(); audio.nudge()
      │     coolUntil = now + 0.800
      │     missRound(idx)                       (star greys NOW)
      │     → return .reject                     (shake)
      └─ right key:
            audio.unlock(); audio.pop()
            locked = true; flash = key; mood = .happy
            audio.success(); effects.fireConfetti()
            → return .accept
            Task:  ok = await audio.say(successLine, rate: 0.98)
                   guard ok && isActive else return        ← no advance if cut short
                   flash = nil; locked = false
                   if idx+1 >= session.count:
                       mood = .cheer
                       earned = award(…, stars.count(true), session.count)
                       done = true
                       audio.say(bravoLine)                (not awaited)
                   else:
                       mood = .idle; idx += 1              → [Announce(idx)]
```

Notes that are easy to get wrong:
- The **exact audio order on a wrong tap is `pop` then `nudge`** — the tap always pops
  first (it is feedback for the touch), then the nudge marks the miss.
- The cooldown check comes **before** `unlock/pop`: a cooldown tap makes *no sound at all*
  but the Tile still runs its press animation and, because the verdict is `.reject`, the
  shake. Feedback fired on the tap that *missed*; the swallowed ones stay visual-only.
- While `flash != nil` every tile is `disabled` (`disabled={flash != null}`) — so during
  the celebration line no tile press or preview is possible at all in this family.
- `flash` is compared against a per-engine key: FirstLetter → `letter`,
  FindSound → `graphy`, Grid → `text`, LetterMatch → `face.base`, ReadImage → `word`.
- LetterMatch judges `face.base !== round.prompt.base` (identity, not form).
- ReadImage judges `choice.word !== round.target.word`.
- Wrong-tap state change is ONLY `coolUntil` + `missRound` — `mood` does **not** change
  on a miss (mascot stays idle). No fail state (invariant 3).

Per-engine specifics:

| | prompt line (spoken) | success line (`rate: 0.98`) | listen-button label / text | preview speaks |
|---|---|---|---|---|
| FirstLetter | `Trouve la première lettre de ${word}.` | `Oui ! ${letter}. ${word}.` | aria `Répéter le mot`; text `🔊 ${word}` if `level < levelCount-1` else `🔊` | the letter (`audio.say(letter)`) — **no `locked` guard** |
| FindSound | `findSoundPrompt`: `${sound}, comme dans ${word}.` | `findSoundSuccess`: `Oui ! ${word}.` | aria `Réécouter le son`; text `🔊 Écouter` | `choice.sound` — **no `locked` guard** |
| SyllableGrid | `gridPrompt`: the bare `sound` | `gridSuccess`: `Oui ! ${sound}.` | aria `Réécouter la syllabe`; text `🔊 Écouter` | `choice.sound` — **no `locked` guard** |
| LetterMatch | `letterMatchPrompt(prompt, choices[0])` → one of the four fixed lines (`Trouve la petite lettre.` / `… grande …` / `… la lettre attachée.` / `… en script.`) | `Oui ! ${base}.` | aria `Répéter la consigne`; text `🔊 ${line}` | `face.base` — **no `locked` guard** |
| ReadImage | `READ_IMAGE_PROMPT` = `Trouve la bonne image.` | `Oui ! ${word}.` | aria `Répéter la consigne`; text `🔊 Trouve la bonne image.` | `choice.word` — **HAS `locked` guard** |

(The missing `locked` guards on four previews are harmless in the web app because
`disabled={flash != null}` already blocks them during celebration; port the guards
exactly as they are — presence and absence — so behaviour under future edits stays aligned.)

The top listen button in ALL engines is guarded: `if locked { return }` — it must not cut
the success line mid-celebration. It speaks on **pointerdown** (same primitive as tiles).

FirstLetter also owns its session builder (the only engine that does):
```ts
const catalog = cfg.letters ?? [...new Set(pool.map((w) => w.letter))];
return repeatSession(pool, cfg.pick, cfg.repeats).map((target) => {
  const distractors = shuffle(catalog.filter((l) => l !== target.letter)).slice(0, 2);
  return { target, choices: shuffle([target.letter, ...distractors]) };
});
```
Always exactly 2 distractors (3 tiles). Port into `EngineDescriptors` (or the levels
module — coordinate with that agent; the logic must live in ALCore either way).

FirstLetter `showWord = level < FIRST_LETTER_LEVELS.count - 1` — with 5 levels, levels
1–3 show the word text on the listen button; levels 4–5 sound-only.

SyllableGrid `vowel` mode extra UI: above the listen button, the half-written syllable —
consonant + a gap box. The gap is dashed `#E4A15E` (4 px, radius 16) while empty; when
`flash != nil` it becomes a white filled card showing `round.target.vowel`. The whole
group has a11y label `Syllabe à compléter : ${consonant}`; the gap span is a11y-hidden.
In `vowel` mode tiles render `choice.vowel`; in `hear` mode `choice.text`.

### 4.2 State machine — assembly family
(Assemble, SpellSound, SpellSyllable)

Model state: `idx`, `round`, `slots: [SlotValue?]`, `slotTile: [Int?]`, `used: Set<Int>`,
`mood`, `done`, `earned`, `stars`, `locked`. **No cooldown** — pacing on a wrong row comes
from the "Oh non" line instead (invariant 8's "sequence engines pace retries with the
'Oh non' line").

```
 loadRound(item):
     round = build(item); slots = all nil; slotTile = all nil; used = ∅
     locked = false; audio.say(promptForItem)        (immediate, not awaited)

 AWAITING_PICK
   pick(tileId, value):
     ├─ locked        → .reject                       (shake, no audio)
     │                                                (tray tiles stay ENABLED during
     │                                                 the "Oh non"/success lines except
     │                                                 `used` ones — this is why the
     │                                                 locked guard matters here)
     ├─ audio.unlock(); audio.pop()
     ├─ nextEmpty = first nil slot; none → .reject    (shake)
     ├─ slots[nextEmpty] = value; slotTile[nextEmpty] = tileId; used.insert(tileId)
     ├─ row not full → .accept                        (no judgement per tap)
     └─ row full — judge whole row:
          ├─ ALL slots match target:
          │     locked = true; mood = .happy
          │     audio.success(); effects.fireConfetti()
          │     Task: ok = await audio.say(successLine, rate: 0.98)
          │           guard ok && isActive else return
          │           if idx+1 >= session.count: …finish (same as 4.1)…
          │           else: mood = .idle; idx += 1; loadRound(session[idx])
          │     → .accept
          └─ mismatch:
                locked = true
                audio.oops()                          (two-note wah-wah, NOT nudge)
                missRound(idx)                        (star greys NOW)
                Task: _ = await audio.say("Oh non ! On recommence.")
                      guard isActive else return      ← NOTE: ok is IGNORED here;
                      reset slots/slotTile/used         the reset happens even if the
                      locked = false                    line was cut short. Port as-is.
                → .accept                             (the tile that completed the row
                                                       does NOT shake — the row-level
                                                       oops is the feedback)

 removeAt(slotIndex):        ← tap a FILLED slot to send its tile back to the tray
     guard !locked, slot not pre-revealed-locked, slotTile[i] != nil
     audio.pop()
     slots[i] = nil; slotTile[i] = nil; used.remove(tileId)
```

Judgement per engine:
- **Assemble**: `filled[i] == round.word.syllables[i]` for all i. `fill-blank` rounds have
  pre-revealed slots (`round.locked[i] == true`) that are non-interactive and survive the
  wrong-row reset (`setSlots(round.slots.slice())` restores the *seeded* slots, which
  include the revealed syllables).
- **SpellSound**: `filled[i] == round.target.spelling[i]`.
- **SpellSyllable**: `sameFace(filled[i], round.answerFaces[i])` — glyph + script equality
  (`base` excluded). In plain (non-mixed) rounds every face is uppercase print so this
  degrades to letter equality; in mixed rounds a right letter in the wrong case/script
  fails the row. Reset restores empty slots (`round.answerFaces.map(() => null)`).

Prompts/lines per engine:

| | round announce (in `loadRound` / first-round effect) | success line | wrong-row line | listen button aria / text | slot a11y | tray tile a11y | preview |
|---|---|---|---|---|---|---|---|
| Assemble | `word.word` | `Oui ! ${word.word}.` | `Oh non ! On recommence.` | `Répéter le mot` / `🔊 Écouter` | filled removable: `Retirer ${syllable}` | `Syllabe ${syllable}`; preview label `Écouter ${syllable}` | says `t.syllable`, guarded by `locked` |
| SpellSound | `soundPrompt(target)` = `${sound}, comme dans ${word}.` or bare `${sound}` | `soundSuccess` = `Oui ! ${word ?? sound}.` | same | `Réécouter le son` / `🔊 Écouter` | `Retirer ${letter}` | `Lettre ${letter}`; preview `Écouter ${letter}` | says `t.letter`, guarded |
| SpellSyllable | `word.word` | `Oui ! ${word.word}.` | same | `Réécouter le mot` / `🔊 Écouter` | `Retirer ${face.base}`; row container label `Mot à compléter` | `faceLabel(face)` (e.g. `Lettre A majuscule`, `Lettre a minuscule attachée`); preview `Écouter ${letter}` | says `t.letter`, guarded |

Assemble headline: `MODE_HINT[mode]` — verbatim:
`Trouve la syllabe manquante` / `Remets les syllabes dans l’ordre` / `Range le mot… et évite l’intrus !`
SpellSound headline: `Écoute le son et écris-le avec les lettres`.
SpellSyllable headlines (owned by the engine file — these live in `EngineDescriptors`):
```
HEADLINE:        letters-exact → "Complète le mot avec les lettres"
                 letters-extra → "Complète le mot — attention aux intrus"
                 letters-two   → "Complète les deux syllabes"
MIXED_HEADLINE:  letters-exact → "Trouve la bonne écriture"
                 letters-extra → "La bonne lettre… et la bonne écriture"
                 letters-two   → "Deux syllabes — la bonne écriture"
```

SpellSyllable cell rendering: `round.cells` in order; `fill == false` cells are solid
letters on `#FFF3E0` in the round's script font, a11y-hidden; `fill == true` cells map to
`slots[c.slotIndex]`. `syllableStart && i > 0` adds a left gap (`clamp(8px,2.5vw,16px)`).
Filled slots render the dropped tile's `glyph` in the tile's script font.

Tray tile disabled ⟺ `used.contains(t.id)` (dropped tiles grey out; they come back via
`removeAt`).

### 4.3 State machine — twins (multi-select)

Model state: `idx`, `found: [Int]` (tile ids in tap order), `mood`, `done`, `earned`,
`stars`, `coolUntil`, `locked`.

```
 AWAITING (announce: 350ms → say(twinPrompt) = "Trouve tous les ${sound} !")
   pick(tile):
     ├─ locked          → .reject
     ├─ now < coolUntil → .reject                    (silent)
     ├─ audio.unlock(); audio.pop()
     ├─ !tile.correct:  audio.nudge(); coolUntil = now+0.8; missRound(idx) → .reject
     └─ tile.correct:
          found.append(tile.id); mood = .happy
          complete = every target tile id ∈ found
          ├─ !complete:
          │     audio.say(twinSuccess(tile), rate: 0.98)   ← fire-and-forget; the NEXT
          │     → .accept                                    tap can land while this
          │                                                  line plays (say is
          │                                                  latest-wins)
          └─ complete:
                locked = true
                audio.success(); effects.fireConfetti()
                Task: ok = await audio.say(twinSuccess(tile), rate: 0.98)
                      guard ok && isActive else return
                      locked = false
                      finish or (mood=.idle; found=[]; idx+=1) → announce
                → .accept
```

`twinSuccess(g)` = `Oui ! ${g.word}.` — the anchor word IS the feedback, per graphy.

Tile disabled ⟺ `found.contains(tile.id) || complete` (found tiles lock; a complete round
locks the rest so nothing shakes during the celebration line). Found tiles also render
`highlight` (green ring).

Collection strip: one slot per target tile (`targets = round.tiles.filter(\.correct)`),
slot `i` shows the tile with id `found[i]` (text + emoji at 0.8 em, emoji a11y-hidden)
as a white card once found; dashed `#E4A15E` box before. Note: a miss does NOT change
`mood` and partial finds do NOT grey the star — only intruder taps do.

Headline: `Un son peut s'écrire de plusieurs façons — trouve-les toutes !`
Listen button: aria `Réécouter le son`, text `🔊 Écouter`, `locked`-guarded.
Tile a11y: `Syllabe ${text}`; preview label `Écouter ${text}`; preview speaks `tile.sound`
(the tile's own family's sound), `locked`-guarded.

### 4.4 The Tile primitive (exact behaviour)

From `Tile.tsx` — this is the contract `TileView`/`TilePressControl` must reproduce:

- `onPointerDown` (touch-down, not up):
  1. `if (disabled) return;` — a disabled tile does nothing (no animation, no sound).
  2. Play the **press** animation on the tile: scale keyframes `1 → 0.9 → 1`,
     130 ms, ease-out.
  3. Call `onPick()` **synchronously**; if it returns `reject`, additionally play the
     **shake**: translateX keyframes `0, -8, +8, -5, 0` (px), 300 ms, ease-in-out.
- Press and shake are WAAPI (`el.animate`) — never React state. Swift: `CAKeyframeAnimation`
  added directly to the control's layer in the touch-down handler (§5.1).
- Preview button (rendered only when `onPreview` provided): a separate full-width button
  below the tile, 8 pt gap, own press animation, speaks on pointerdown, never shakes,
  shares the tile's `disabled`. Label: `previewLabel ?? "Écouter"`. Shows `🔊`.
- Styling: min-width = height = `size` (default `clamp(92px,27vw,150px)`), horizontal
  padding `clamp(10px,3vw,20px)`, radius 28, font-black at
  `fontSize ?? clamp(30px,9vw,64px)`, shadow `0 8px 0 rgba(0,0,0,0.12), 0 12px 20px rgba(0,0,0,0.14)`;
  `highlight` swaps the shadow for a 6 px `#66BB6A` ring + `0 10px 22px rgba(0,0,0,0.18)`
  (animate the swap ~0.15 s); disabled = opacity 0.40; preview button height
  `clamp(40px,11vw,52px)`, white bg, radius full, shadow `0 3px 0 rgba(0,0,0,0.10), 0 5px 12px rgba(0,0,0,0.12)`.
- `[touch-action:none]` on the web buttons prevents scroll-stealing; the exercises never
  scroll. In Swift, tiles must NOT sit inside a ScrollView (a scroll view's touch delay
  would break invariant 1); the exercise layout is fixed-height.

Tile colour cycles (`i % palette.count`, index = position in the row) — verbatim hex:

| Engine | palette (bg / ink) |
|---|---|
| FirstLetter, FindSound, ReadImage(first 3) | `#FF8A65/#4A2317`, `#FFD54F/#4A3B00`, `#4FC3F7/#062E3D` |
| ReadImage (5) | + `#AED581/#213606`, `#BA9EE8/#2C1846` |
| SyllableGrid (4) | `#FF8A65/#4A2317`, `#FFD54F/#4A3B00`, `#4FC3F7/#062E3D`, `#A5D6A7/#123B18` |
| LetterMatch (4) | `#FF8A65/#4A2317`, `#FFD54F/#4A3B00`, `#4FC3F7/#062E3D`, `#AED581/#213606` |
| SoundTwins, Assemble tray, SpellSound tray, SpellSyllable tray (5) | `#4FC3F7/#062E3D`, `#AED581/#213606`, `#FFD54F/#4A3B00`, `#BA9EE8/#2C1846`, `#FF8A65/#4A2317` |

Per-engine tile sizes (CSS clamp → fluid util, §5.5):
Assemble tray `size clamp(64px,18vw,100px)` `font clamp(20px,5.5vw,36px)`;
Grid `62/17vw/96`, `26/7vw/46`; Twins `60/17vw/92`, `24/6.5vw/44`;
SpellSound tray `60/17vw/92`, `26/7vw/48`; SpellSyllable tray same;
FirstLetter/FindSound/LetterMatch/ReadImage default size.

Slot boxes (assembly): dashed `3px #E4A15E` (Assemble pre-revealed locked slots use
`#C9A87A`), radius 20 (SpellSyllable cells 16, Twins strip 18), filled = white card with
shadow `0 6px 14px rgba(0,0,0,0.12)`, ink `#5A3A1E`.

### 4.5 GameFrame star strip (exact rules)

`done` = rounds completed (engine passes `idx`, or `total` when finished); star font size
20 pt, or 16 pt when `total > 9`.

For index `i`:
- `i < done` **or** (`i == done` && `!stars[i]`): render ⭐ — greyed
  (`grayscale + opacity 0.45`) when `stars[i] == false`. This is what makes the LIVE
  round's star grey **the instant** the first wrong tap lands (the model mutates `stars`
  synchronously in the pick handler).
- `i == done` (star still winnable): ⭐ at opacity 0.8 with a pulse animation —
  **gated on reduce-motion** (`motion-safe:animate-pulse`).
- `i > done`: `•` at opacity 0.28.

← Menu button fires on **click/touch-up** (deliberate — navigation, not gameplay).

### 4.6 Finished screen

🤩 (fluid ~64–110 pt), `EarnBadge(earned)` only when `earned > 0` (difficulty-0
exercises show no points pill — a 0 never reads as punishment), the same star row
(28 pt, greyed rule identical), title, `EndButtons(onMenu, onNext)`.
Titles per exercise (verbatim): Assemble/SpellSound/SpellSyllable → `Tu as tout réussi !`;
SyllableGrid → `Tu as tout lu !`; all others → `Tu as tout trouvé !`.
Bravo lines (spoken at finish, not awaited): Assemble/SpellSound/SpellSyllable →
`Bravo ! Tu as tout réussi !`; all others → `Bravo ! Tu as tout trouvé !`.

### 4.7 The audio contract engines rely on (AudioPort)

The implementation is the audio agent's scope; the engines REQUIRE these semantics:

- `say(text, rate?) async -> Bool` — resolves when the line is DONE. `true` = played to
  natural completion; `false` = superseded by a later `say()`/`stop()`, errored, or a
  watchdog tripped. **Never throws, never hangs.** Single-flight: a new `say` interrupts
  the current one (latest intent wins; voice never overlaps voice).
- Engines gate **advancement** on the boolean (celebrate paths) — but the assembly
  wrong-row reset ignores it (see 4.2). Getting this asymmetry right matters: if `say`
  could hang, a round would soft-lock (`locked` stays true forever).
- Default rate 0.94; success lines pass 0.98. `text` is matched byte-for-byte against the
  baked VO clip table — engines must emit the exact strings in this spec or clips silently
  fall back to TTS.
- `pop/success/nudge/oops` are synchronous, low-latency chimes (WebAudio oscillator blips
  in the PWA): pop = 660 Hz triangle 0.09 s; success = C5-E5-G5-C6 arpeggio (523.25,
  659.25, 783.99, 1046.5 Hz, 0.16 s notes, 75 ms apart); nudge = 196 Hz sine 0.14 s at
  low gain (soft, non-punishing); oops = falling two-note 392 → 311.13 Hz. They must be
  callable synchronously from the touch-down handler with no audible latency
  (pre-warmed AVAudioEngine / preloaded buffers — audio scope).
- `unlock()` — idempotent warm-up; engines call it on appear and on every pick/preview
  (port the calls as-is; on iOS it can map to audio-session activation + speech warm).
- `stop()` — 200 ms fade-out then cut; called on disappear.

### 4.8 Reward wiring (invariant 8)

Engines NEVER compute points. On finish they call
`award(exercise, level, perfectRounds: stars.count(where: {$0}), totalRounds: session.count)`
exactly once and display the returned `earned`. `award` internally applies
`sessionReward(difficulty, priorClears, perfect, total)` and bumps counters — profile
scope. `MISS_COOLDOWN_MS = 800` lives in the rewards module; engines import it.

### 4.9 Level-builder surface consumed (must exist in ALCore per the levels spec)

`repeatSession(pool:pick:repeats:)` (no item twice in a row; counts clamp to pool),
`shuffle`, `firstLetterPool`, `FIRST_LETTER_LEVELS`, `syllableTier`, `syllablePool`,
`buildSyllableRound(word:mode:)`, `MODE_HINT`, `soundLevel`, `buildSoundSession`,
`buildSoundRound(target:distractors:)`, `soundPrompt`, `soundSuccess`,
`spellSyllableLevel`, `buildSpellSyllableSession`,
`buildSpellSyllableRound(word:mode:distractors:mixed:)`, `buildSyllableGridSession`,
`syllableGridLevel`, `gridPrompt`, `gridSuccess`, `GRID_PROMPT`,
`buildFindSoundSession`, `findSoundPrompt`, `findSoundSuccess`, `buildTwinSession`,
`twinPrompt`, `twinSuccess`, `buildLetterMatchSession`, `letterMatchPrompt`,
`letterMatchSuccess`, `buildReadImageSession`, `READ_IMAGE_PROMPT`,
`SCRIPT_FONT` equivalent + `faceLabel`.

---

## 5. Invariant ownership in the Swift design

### 5.1 Invariant 1 — feedback on touch-DOWN, before any state commit

**The chosen primitive: a `UIControl` subclass inside a `UIViewRepresentable`
(`TilePressControl`), not `Button`, not `.onTapGesture`.**

Why nothing else qualifies:
- SwiftUI `Button` performs its action on touch-**up** — wrong semantics outright.
- `.onTapGesture` recognises on touch-up as well, and participates in gesture
  arbitration.
- `DragGesture(minimumDistance: 0).onChanged` does fire on touch-down and is the pure-
  SwiftUI fallback, but (a) it still routes through SwiftUI's gesture graph (a parent
  scroll/gesture can delay it), and (b) the press/shake animation would then have to be
  driven through `@State`, which is exactly what invariant 1 forbids.
- `UIControl.addTarget(_:action:for: .touchDown)` fires from `touchesBegan` on the main
  thread, synchronously, with **no recogniser and no arbitration delay**. This is the
  `pointerdown` of UIKit.

Design:

```
TilePressControl (UIControl)
  ├─ hosts the SwiftUI tile content via an embedded UIHostingController
  │  (child VC plumbed through the representable's context/coordinator)
  ├─ .touchDown → handler():
  │     1. layer.add(pressAnimation)          // CAKeyframeAnimation scale 1→0.9→1, 130ms, ease-out
  │     2. verdict = onPick()                 // synchronous closure into the ALCore model
  │     3. if verdict == .reject { layer.add(shakeAnimation) }   // translateX 0,-8,8,-5,0, 300ms, ease-in-out
  ├─ isEnabled mirrors `disabled` — a disabled control never reaches the handler
  ├─ isAccessibilityElement = true, accessibilityLabel = the French label,
  │  accessibilityTraits = .button
  └─ animations target the control's OWN layer, so hosted SwiftUI content moves with it
```

The SFX (`audio.pop()` etc.) are called from inside `onPick()` — i.e. inside the
touch-down handler, before the function returns, before SwiftUI observes any
`@Observable` mutation and schedules a commit. The model mutations that follow in the
same call are fine: the *feedback* (sound + layer animation) has already been issued to
CoreAudio/CoreAnimation; the SwiftUI commit that re-renders slots/stars happens on the
next runloop turn, exactly like React committing after the WAAPI call.

**What would break it:** routing the press through `Button`/`onTapGesture`; triggering
the sound from `.onChange(of:)`/`didSet` observation of model state; expressing the
press/shake as a `withAnimation` state change; putting tiles inside a `ScrollView`
(delaysContentTouches) or under a competing gesture.

The top "🔊 Écouter" buttons and the assembly slot-remove buttons use the same primitive
(they speak/act on pointerdown in the PWA). Only GameFrame's ← Menu and the Finished
screen's buttons are ordinary SwiftUI `Button`s (they act on click/touch-up in the PWA
— `onClick`).

### 5.2 Invariant 2 — animation off the render path

| PWA | Swift | Notes |
|---|---|---|
| WAAPI press/shake (`el.animate`) | `CAKeyframeAnimation` added directly to `TilePressControl.layer` | Never touches SwiftUI state. Enforced-by-API: the layer animation cannot trigger a SwiftUI view update at all. |
| Canvas + rAF confetti | `TimelineView(.animation)` + `Canvas` in `ConfettiView`; particle state in a plain (non-`@Observable`) `ConfettiSimulation` reference | The TimelineView closure re-draws ONLY the canvas each frame; the simulation object is deliberately not observable so mutation never invalidates the exercise view tree. `fire()` is a plain method call reachable from the engine via `EngineEffects`. When the particle array empties the view flips the schedule to paused (one state flip per burst, mirroring the PWA's rAF loop stopping). If profiling ever shows Canvas redraw jank, the documented escalation is a `CALayer`-drawing representable driven by `CADisplayLink` — same simulation struct, different presenter. |
| `transition: box-shadow .15s` highlight | implicit SwiftUI animation on the highlight ring only | Cosmetic, isolated to the tile. |
| star pulse (`animate-pulse`) | `.opacity` repeatForever on the one Text, gated on reduce-motion | |
| FitLine transform | measured scale, § 5.4 — recomputed on size change only, never per frame | |

### 5.3 Invariant 3 — no fail state

Structural: the engine state machines in §4 have exactly one terminal state,
`finished`. A miss mutates `coolUntil` and `stars[idx]` and nothing else; an assembly
wrong row wipes the slots back to the seeded state and unlocks. No lives, no
score-subtraction, no retry limit, no "game over" path exists in the model — a reviewer
can verify the enum/transitions exhaustively. `nudge()` is specified soft
(low-gain 196 Hz) — do not "improve" it into an error buzzer.

### 5.4 Invariant 6 — accessibility floor

- Every tile: `.accessibilityLabel` with the exact French strings from §4 tables
  (`Lettre A`, `Syllabe VA`, `Son OU`, `Image : chat`, `faceLabel` forms, `Retirer X`,
  `Écouter X`, `Répéter le mot`, `Réécouter le son`, `Réécouter la syllabe`,
  `Réécouter le mot`, `Répéter la consigne`, `Mot à compléter`,
  `Syllabe à compléter : X`). These labels are **load-bearing for XCUITest**: the UI
  test suite drives taps by accessibility identifier/label, and `TilePressControl` must
  set `isAccessibilityElement = true` or the whole simulator test tier goes blind.
  Decorative spans marked `aria-hidden` in TSX map to `.accessibilityHidden(true)`
  (emoji in the twins strip, the SpellSound target emoji, shown letters in SpellSyllable
  cells, the grid vowel gap).
- Tap targets: tile minimum is 56 pt (assembly slots) and 60–92+ pt (tiles) — the fluid
  size util must clamp above 44 pt on any device; preview buttons ≥ 40 pt.
- Reduce motion (`@Environment(\.accessibilityReduceMotion)` in views;
  `UIAccessibility.isReduceMotionEnabled` where a plain class needs it): gates confetti
  `fire()` (whole burst no-ops, as in `useConfetti`) and the star pulse. Press/shake are
  NOT gated in the PWA — port that as-is (they are sub-300 ms, small-amplitude).

### 5.5 Invariant 8 — farming never pays

Enforced in ALCore, host-testable:
- `coolUntil` gate at the top of `SinglePickEngine.pick` / `TwinsEngine.pick`, using the
  injected monotonic `EngineClock` (`MISS_COOLDOWN_MS = 800` from the rewards module).
  Silent-swallow semantics per §4.1 (shake yes, audio no, state no).
- Assembly engines have **no cooldown by design** — their pacing is `locked = true` for
  the full duration of the awaited "Oh non ! On recommence." line; taps during it are
  rejected. Do not add a cooldown "for symmetry".
- `missRound(idx)` runs synchronously in the pick handler → the strip star greys at
  pointerdown (§5.3 strip rule makes it visible immediately).
- Points flow exclusively through `AwardPort.award` → `sessionReward`. Engines hold no
  point math; `earned` is whatever `award` returns. Difficulty-0 exercises pay 0 and
  hide the badge.
- What would break it: awarding anywhere but the single finish transition; computing
  points in a view; resetting `coolUntil` on success; letting a completed-row reject
  path skip `missRound`.

(Invariants 4, 5, 7, 9, 10, 11 are out of engine scope: content authorship, hub
navigation, icons, persistence, privacy, licensing.)

---

## 6. Test plan

### Host (`swift test`, no simulator) — the bulk

Fakes: `FakeAudio` (records call order; `say` returns a settable/awaitable result per
call), `FakeClock` (manual advance), `FakeEffects` (counts `fireConfetti`),
`FakeAward` (records args, returns a canned value). Seedable RNG injected into the
builders (whole-app decision, §7) makes sessions deterministic.

SinglePickEngine:
1. Wrong pick: verdict `.reject`; audio order exactly `[unlock, pop, nudge]`; star `idx`
   false; `coolUntil = now + 0.8`; mood unchanged.
2. Pick during cooldown: `.reject`, **zero** audio calls, no state change; after
   `clock.advance(0.81)` picks work again.
3. `missRound` idempotent: second miss in the same round leaves `stars` unchanged
   (and still sets a fresh cooldown).
4. Right pick: `.accept`; order `[unlock, pop, success]`; `fireConfetti == 1`; `locked`;
   `flash == key`; success line text and `rate == 0.98` exactly as per §4.1 table.
5. Advance gated on `say` result: resolve `false` → no advance, still locked (mirrors
   the PWA's "don't advance on a cut line"); resolve `true` → `flash = nil`,
   `locked = false`, `idx += 1`, mood idle.
6. Last round: `award` called once with `(exercise, level, perfect, total)`;
   `done == true`; mood cheer; bravo line spoken with the exercise's exact string;
   `earned` == FakeAward's return.
7. Deactivated engine (`isActive = false`) never advances/awards after `say` resolves.
8. Locked pick → `.reject`, no audio.
9. FirstLetter session builder: 3 choices, target always present, distractors from the
   level catalog, `showWord` flips at level 4.

AssemblyEngine:
10. Fill order: values land in first-nil slot; `used` grows; partial row → `.accept`
    with no judgement, no confetti.
11. `removeAt`: returns tile to tray (`used` shrinks), clears slot; refuses when locked;
    refuses on Assemble's pre-revealed slots; `pop` plays.
12. Full correct row: judged once; success path as tests 4–6; next round loaded with
    fresh empty slots and an **immediate** (un-delayed) announce of the new word.
13. Full wrong row: `oops` (not `nudge`), star greys, `locked`; after "Oh non" line
    resolves (`true` **or** `false` — both), slots reset to seeded state (fill-blank
    keeps revealed syllables), `used` empty, unlocked. No cooldown field touched.
14. Tap while locked (during the line): `.reject`, silent.
15. SpellSyllable mixed: right letter / wrong script fails the row; right face passes;
    plain rounds pass on letter alone.
16. Slots-full pick (`nextEmpty == nil`) → `.reject`.

TwinsEngine:
17. Partial correct: found grows, `.accept`, `twinSuccess` line spoken (not awaited),
    NOT locked, star intact, no confetti.
18. Intruder: nudge + cooldown + star greys.
19. Completing tap: locked, `success` chime + confetti, awaited line, then
    `found == []`, `idx += 1` (or finish).
20. Tile-disabled predicate: found ids and post-completion.

Cross-cutting:
21. `stars.count(where:)` passed to `award` equals rounds with zero misses.
22. Announce timing: single-pick announces 350 ms after every advance (assert via
    FakeClock-driven scheduler); assembly announces immediately on `loadRound` and
    350 ms-delayed only for round 0.
23. Every French string emitted by the descriptors (prompts, success lines, headlines,
    a11y labels, finished titles, bravo lines) snapshot-asserted against this spec's
    verbatim table — the VO clip lookup keys on exact text, so a one-character drift
    silently downgrades to TTS.
24. ConfettiSimulation: 90 particles per fire, decay, cull rules, deterministic under
    seeded RNG.

### Simulator / XCUITest (small, targeted)

- Touch-down semantics: assert the pick side-effect (e.g. star greys / audio spy flag)
  happens on press-and-hold *before* finger lift.
- Accessibility audit: every tile/button in each of the nine exercises exposes the
  expected label (drives the tap targets for the rest of the suite).
- Reduce-motion on: no confetti layer content after a correct pick; no star pulse.
- Visual: D3 pixel-diff hooks for GameFrame/Finished per exercise.

---

## 7. Risks & whole-app decisions needed

1. **`TilePressControl` (UIControl + embedded UIHostingController) is the only design
   found that satisfies both invariant 1 (touch-down, no recogniser delay) and
   invariant 2 (CAAnimation, not state-driven animation) to the letter.** It needs child
   view-controller plumbing in a representable — mildly fiddly (sizing the hosting view,
   forwarding `isEnabled`, accessibility passthrough). Fallback if it misbehaves:
   `DragGesture(minimumDistance: 0)` for touch-down + a `CALayer`-only overlay for the
   animations. **Decide once, app-wide** — every interactive gameplay element (tiles,
   listen buttons, slot-remove buttons) must use the same primitive.
2. **Fluid sizing.** The PWA sizes everything with CSS `clamp(min, vw, max)`. Swift needs
   one shared `fluid(min:vw:max:)` utility and a single decision on what "viewport
   width" means (screen width vs container width via GeometryReader) — app-level, used
   by every UI agent, must match the PWA breakpoints on iPhone/iPad.
3. **Seedable randomness.** `Math.random()` is everywhere in the builders (shuffles,
   fill-blank gap choice, mixed form choice, letter-match direction). Inject a
   `RandomNumberGenerator` (default `SystemRandomNumberGenerator`) through the builders
   — required for host determinism in tests 9–24. Decision belongs to the levels agent
   but the engines' test plan depends on it; flagging app-level.
4. **`say()` semantics are load-bearing.** If the AVSpeechSynthesizer/AVAudioPlayer port
   ever hangs or double-resolves, engines soft-lock (`locked` never clears) or
   double-advance. The audio spec must carry the ticket/single-flight/watchdog semantics
   of `useAudio.ts` intact, including "resolve the superseded promise `false` BEFORE
   cancelling" ordering. Engines additionally rely on synchronous, sub-frame chime
   latency from the touch-down handler (pre-warmed engine — cold AVAudioEngine start is
   audible).
5. **The assembly wrong-row reset ignores the `say` result** (resets even on a cut
   line) while every advance path requires `ok == true`. Easy to "fix" by symmetry
   during implementation; it must not be.
6. **Cooldown taps still shake.** "Silently swallowed" means no audio and no state —
   the press + shake animations DO play (verdict `.reject`). An implementer who
   suppresses all feedback breaks the child-facing behaviour.
7. **Announce-timer vs advance races.** In React, the `setTimeout` cleanup cancels the
   pending announce when `idx` changes. The Swift port must cancel the announce Task on
   advance/disappear or a stale prompt can play over the next round's line. (The `say`
   single-flight would mask it, but the wrong line would still play.)
8. **Emoji rendering.** Stars/`•`/🤩/🔊 render as `Text` emoji; `.grayscale(1).opacity(0.45)`
   matches the CSS filter closely but not pixel-identically — accept, note for D3 diffs
   (mask the strip or diff with tolerance).
9. **Cursive font.** `SCRIPT_FONT.cursive` is `Snell Roundhand / Apple Chancery` on the
   web too, so iOS is actually the home platform — but the exact fallback chain must be
   resolved once in ALUI (`Font.custom("SnellRoundhand", …)`) and shared with
   LetterMatch/SpellSyllable.
10. **Global tile-id counters** (`_tileId`, `_letterTileId`, `_twinTileId`,
    `_spellTileId` — module-level, monotonically increasing, never reset). Port as
    static counters; ids are ephemeral (identity within a session only). Do not use
    array indices — the undo logic (`slotTile`, `used`) depends on stable per-tile ids
    across reshuffles of the same round.
11. **`@Observable` (iOS 17) vs `ObservableObject`.** D1 sets iOS 17 minimum, so
    `@Observable` is assumed throughout this spec. Confirm at app level; a downgrade to
    `ObservableObject` changes nothing behavioural but touches every model.
12. **VO text keys.** Engines emit prompt/success strings that double as clip-lookup
    keys. The clip table port (audio/VO scope) must key on the identical strings —
    including `’` vs `'` (the copy uses BOTH: typographic `’` in `MODE_HINT`/hub names,
    ASCII `'` in `"Écoute le son et trouve comment il s'écrit"` and
    `"Un son peut s'écrire de plusieurs façons — trouve-les toutes !"`). Copy them
    byte-for-byte; do not normalise apostrophes.
