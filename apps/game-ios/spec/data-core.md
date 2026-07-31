# data-core — Swift port specification

Scope: `src/types.ts`, `src/content.ts`, `src/levels.ts`, `src/levels.test.ts`,
`src/rewards.ts`, `src/letterForms.ts`, `src/vo/utterances.ts`.

Target: **`Sources/ALCore`** (pure Swift, no SwiftUI, no UIKit; Foundation only where
noted). Everything in this scope is host-testable with `swift test`.

Binding context: `DECISIONS.md` D0 (behaviour frozen, read TS from the original tree),
D1 (three targets, Swift language mode 5), D2 (SVG `d` strings copied verbatim — only
relevant here for the four `img` assets, see §3.3).

**Behaviour is frozen.** Nothing below "fixes" the TypeScript. Where the TS looks odd
it is ported as-is and listed in §7 Risks.

---

## 1. Inventory

| TS file | lines | what it actually does |
|---|---|---|
| `src/types.ts` | 473 | The whole app's domain vocabulary. ~55% of it is mine (exercise/content/hub types); the rest (profile, counters, roster, mascot) belongs to the storage/sync agent — see §1.1. |
| `src/content.ts` | 591 | The authored datasets. 8 exported tables + 2 derived. Zero logic beyond two `flatMap`/`Set` derivations. Imports 4 SVG asset URLs from `src/img/`. |
| `src/levels.ts` | 1030 | Nine difficulty ladders, nine pool functions, eleven round/session builders, the `EXERCISES` hub catalog, the four hint dictionaries, and the three shared helpers (`shuffle`, `repeatSession`, `pickDistractorSyllable`). Four module-level mutable tile-id counters live here. |
| `src/levels.test.ts` | 965 | 30 `describe` blocks. Property-style: it builds thousands of rounds per level and asserts structural invariants. This is the acceptance suite for the port. |
| `src/rewards.ts` | 60 | `REWARD_CURVE`, `REWARD_FLOOR`, `ledgerKey`, `rewardFor`, `MISS_COOLDOWN_MS`, `sessionReward`, `previewReward`. Invariant 8's arithmetic, in full. |
| `src/letterForms.ts` | 21 | `SCRIPT_FONT` (a CSS font stack — **not** ALCore) and `faceLabel` (pure, ALCore). |
| `src/vo/utterances.ts` | 144 | `enumerateUtterances()` — the closed set of everything the app speaks — plus `voKey()` (FNV-1a/base36) and three shop lines. Depends on `mascot/catalog.ts` (out of scope). |

Dataset sizes (counted, not estimated):

- `LETTER_WORDS` 42 entries (21 letters × 2 words; no Q/U/W/X/Y).
- `SYLLABLE_WORDS` 45 entries — 20 two-syllable, 17 three-syllable, 8 four-syllable.
- `SOUND_TARGETS` 5 pools, 133 entries total (32/28/24/24/25).
- `BASIC_SOUNDS` 4 pools, 30 entries total (7/8/8/7).
- `TWIN_FAMILIES` 4 pools, 19 families, 48 graphies.
- `SYLLABLE_GRID_ROWS` 8 rows-lists (last is `null`), 14 consonants, `GRID_VOWELS` 6 → 84 grid cells.
- `SPELL_SYLLABLE_WORD_NAMES` 4 lists (5/6/8/8 names).
- `LETTER_MATCH_ALPHABET` 26, `SOUND_LETTER_BANK` 22 (A–V, no W/X/Y/Z).
- `EXERCISES` 17 rows — exactly the 17 `ExerciseId` cases.

### 1.1 Ownership split of `types.ts`

I own and port these (they are content/exercise/hub vocabulary):

`ExerciseId`, `Mood`, `Verdict`, `LetterWord`, `ReadImageRound`, `FirstLetterLevel`,
`FirstLetterRound`, `LetterMatchKind`, `LetterScript`, `LetterFace`, `LetterMatchRound`,
`SyllableWord`, `SyllableMode`, `SpellSyllableMode`, `SyllableTier`, `BasicSound`,
`SyllableGridMode`, `GridSyllable`, `SyllableGridLevel`, `TwinGraphy`, `TwinFamily`,
`SoundTarget`, `SoundLevel`, `Difficulty`, `ExerciseMeta`, `View`.

I do **not** port these — the storage/sync/mascot agents do, and they must land in
different files so we do not collide:

`Species`, `GROWTH_STAGES`, `CustomizationCategory`, `CustomizationOption`,
`MascotConfig`, `CompletionLedger`, `Counter`, `StarCounters`, `ClearCounters`, `Rev`,
`SpeciesProgress`, `PersistedProfile`, `Profile`, `ChildProfile`, `Roster`, `MascotProps`.

Two of mine are consumed across the seam and are contracts, not private types:
`ExerciseId.rawValue` and `Difficulty.rawValue` (see §5, invariant 9/8 notes).

---

## 2. Swift module plan

All of it under `Sources/ALCore/`. Dependency direction is strictly downward;
`Content` never imports `Levels`, `Levels` never imports `VO`.

```
Domain/      (no dependencies)
  ExerciseID.swift            ExerciseId, Difficulty, Mood, Verdict
  ExerciseMeta.swift          ExerciseMeta, AppView
  LetterTypes.swift           LetterWord, ReadImageRound, FirstLetterLevel,
                              FirstLetterRound, LetterMatchKind, LetterScript,
                              LetterFace, LetterMatchRound, faceLabel(_:)
  SyllableTypes.swift         SyllableWord, SyllableMode, SpellSyllableMode,
                              SyllableTier, SyllableTile, SyllableRound,
                              SpellCell, SpellLetterTile, SpellSyllableRound
  SoundTypes.swift            SoundTarget, SoundLevel, SoundTile, SoundRound,
                              BasicSound, FindSoundLevel, FindSoundRound
  GridTypes.swift             SyllableGridMode, GridSyllable, SyllableGridLevel,
                              GridRound
  TwinTypes.swift             TwinGraphy, TwinFamily, TwinLevel, TwinTile, TwinRound
  ReadImageTypes.swift        ReadImageLevel
  SpellSyllableTypes.swift    SpellSyllableLevel

Support/     (depends on Domain)
  RandomSource.swift          RandomSource, SeededGenerator, orderedUnique(_:)
  RepeatSession.swift         repeatSessionIndices(...), repeatSession(...)
  TileID.swift                TileIDAllocator (+ .shared)

Content/     (depends on Domain)
  Content.swift               enum Content namespace + the two derived tables
  Content+LetterWords.swift   letterWords, letterMatchAlphabet
  Content+SyllableWords.swift syllableWords, spellSyllableWordNames
  Content+SoundTargets.swift  soundTargets            (133 rows, 5 pools)
  Content+BasicSounds.swift   basicSounds             (30 rows, 4 pools)
  Content+TwinFamilies.swift  twinFamilies            (19 families)
  Content+Grid.swift          gridVowels, syllableGridRows
  Content+Banks.swift         soundLetterBank

Levels/      (depends on Domain + Content + Support)
  Levels.swift                enum Levels namespace, exercises catalog,
                              exerciseDifficulty(_:), modeHint/matchHint/
                              spellHint/mixedHint
  Levels+FirstLetter.swift    firstLetterLevels, firstLetterPool, buildFirstLetterSession
  Levels+ReadImage.swift      readImageLevels, readImageLevel, readImagePool,
                              buildReadImageRound, buildReadImageSession, readImagePrompt
  Levels+LetterMatch.swift    letterMatchLevels, letterMatchPool, letterMatchPrompts,
                              letterMatchPrompt, letterMatchSuccess,
                              buildLetterMatchSession, caseRound, scriptRound
  Levels+Syllable.swift       syllableTiers, syllableTier, syllablePool,
                              pickDistractorSyllable, buildSyllableRound
  Levels+SpellSound.swift     soundLevels, soundLevel, soundPool, buildSoundSession,
                              buildSoundRound, soundPrompt, soundSuccess, soundPick…
  Levels+FindSound.swift      findSoundLevels, findSoundLevel, findSoundPool,
                              buildFindSoundRound, buildFindSoundSession,
                              findSoundPrompt, findSoundSuccess
  Levels+SyllableGrid.swift   syllableGridLevels, syllableGridLevel, gridSyllable,
                              syllableGridPool, buildGridRound,
                              buildSyllableGridSession, gridPrompt, gridSuccess,
                              gridConsigne
  Levels+Twins.swift          twinLevels, twinLevel, twinPool, buildTwinRound,
                              buildTwinSession, twinPrompt, twinSuccess
  Levels+SpellSyllable.swift  spellSyllableLevels, spellSyllableLevel,
                              spellSyllablePool, buildSpellSyllableSession,
                              buildSpellSyllableRound, spellIntruders, SpellForm

Rewards/     (depends on Domain)
  Rewards.swift               curve, floor, missCooldown, ledgerKey, rewardFor,
                              sessionReward, previewReward

VO/          (depends on Domain + Content + Levels + the mascot catalog)
  VOKey.swift                 voKey(_:)
  Utterances.swift            shopBought/shopGrew/shopNeedMore, shopCostLine,
                              enumerateUtterances()
```

`Tests/ALCoreTests/` mirrors it one-for-one:
`LevelsFirstLetterTests.swift`, `LevelsReadImageTests.swift`,
`LevelsLetterMatchTests.swift`, `LevelsSyllableTests.swift`,
`LevelsSpellSoundTests.swift`, `LevelsFindSoundTests.swift`,
`LevelsSyllableGridTests.swift`, `LevelsTwinsTests.swift`,
`LevelsSpellSyllableTests.swift`, `RepeatSessionTests.swift`,
`RandomSourceTests.swift`, `RewardsTests.swift`, `ContentIntegrityTests.swift`,
`HubCatalogTests.swift`, `VOKeyTests.swift`, `UtterancesTests.swift`.

### 2.1 Naming: the mechanical TS → Swift mapping

Swift API design guidelines and `SCREAMING_SNAKE` do not mix, and the port must stay
greppable in both directions. One rule, applied without exception:

> A TS module-level `SCREAMING_SNAKE` constant becomes a `lowerCamel` `static let` on
> the file's namespace `enum` (`Content` / `Levels` / `Rewards`). A TS module-level
> `camelCase` function keeps its name as a `static func` on the same namespace.

| TypeScript | Swift |
|---|---|
| `LETTER_WORDS` | `Content.letterWords` |
| `LETTER_MATCH_ALPHABET` | `Content.letterMatchAlphabet` |
| `SYLLABLE_WORDS` | `Content.syllableWords` |
| `SYLLABLE_BANK` | `Content.syllableBank` |
| `SPELL_SYLLABLE_WORD_NAMES` | `Content.spellSyllableWordNames` |
| `SOUND_TARGETS` | `Content.soundTargets` |
| `BASIC_SOUNDS` | `Content.basicSounds` |
| `GRID_VOWELS` | `Content.gridVowels` |
| `SYLLABLE_GRID_ROWS` | `Content.syllableGridRows` |
| `GRID_CONSONANTS` | `Content.gridConsonants` |
| `TWIN_FAMILIES` | `Content.twinFamilies` |
| `SOUND_LETTER_BANK` | `Content.soundLetterBank` |
| `FIRST_LETTER_LEVELS` | `Levels.firstLetterLevels` |
| `READ_IMAGE_LEVELS` / `_COUNT` | `Levels.readImageLevels` / `.count` |
| `LETTER_MATCH_LEVELS` / `_COUNT` | `Levels.letterMatchLevels` / `.count` |
| `SYLLABLE_TIERS` / `SYLLABLE_LEVEL_COUNT` | `Levels.syllableTiers` / `.count` |
| `SOUND_LEVELS` / `SOUND_LEVEL_COUNT` | `Levels.soundLevels` / `.count` |
| `SOUND_PICK` / `SOUND_REPEATS` / `SOUND_SESSION_LENGTH` | `Levels.soundPick` / `.soundRepeats` / `.soundSessionLength` |
| `FIND_SOUND_LEVELS` | `Levels.findSoundLevels` |
| `SYLLABLE_GRID_LEVELS` | `Levels.syllableGridLevels` |
| `TWIN_LEVELS` | `Levels.twinLevels` |
| `SPELL_SYLLABLE_LEVELS` | `Levels.spellSyllableLevels` |
| `EXERCISES` | `Levels.exercises` |
| `MODE_HINT` / `MATCH_HINT` / `SPELL_HINT` / `MIXED_HINT` | `Levels.modeHint` / `.matchHint` / `.spellHint` / `.mixedHint` |
| `LETTER_MATCH_PROMPTS` | `Levels.letterMatchPrompts` |
| `READ_IMAGE_PROMPT` | `Levels.readImagePrompt` |
| `GRID_PROMPT` | `Levels.gridConsigne` (`gridPrompt(_:)` already exists as a function) |
| `REWARD_CURVE` / `REWARD_FLOOR` / `MISS_COOLDOWN_MS` | `Rewards.curve` / `.floor` / `.missCooldown` |

`GRID_PROMPT` is the only forced rename (TS has both `GRID_PROMPT` the dictionary and
`gridPrompt()` the function; Swift cannot hold both on one namespace). Recorded here so
nobody wonders.

`MISS_COOLDOWN_MS` becomes `Rewards.missCooldown: Duration = .milliseconds(800)` **plus**
`Rewards.missCooldownMs: Int = 800` kept as the source of truth, so the number in the
spec, the test and the UI is one number.

---

## 3. Type mapping

### 3.1 Unions and enums

| TypeScript | Swift | notes |
|---|---|---|
| `type ExerciseId = "first-letter" \| …` (17) | `public enum ExerciseId: String, CaseIterable, Hashable, Sendable` | **raw values byte-identical to the TS strings.** They are persisted: `ledgerKey` builds `"first-letter:3"` and that string is a key in `ClearCounters`, which is on disk and on the sync wire. Changing a raw value silently orphans a child's clears. |
| `type Mood = "idle" \| "happy" \| "cheer"` | `public enum Mood: String, Sendable` | |
| `type Verdict = "accept" \| "reject"` | `public enum Verdict: String, Sendable` | |
| `type LetterMatchKind = "case" \| "script"` | `public enum LetterMatchKind: String, CaseIterable, Sendable` | |
| `type LetterScript = "print" \| "cursive"` | `public enum LetterScript: String, CaseIterable, Sendable` | |
| `type SyllableMode = "fill-blank" \| "order" \| "order-distractor"` | `public enum SyllableMode: String, CaseIterable, Sendable` | raw values keep the hyphens. |
| `type SpellSyllableMode = "letters-exact" \| "letters-extra" \| "letters-two"` | `public enum SpellSyllableMode: String, CaseIterable, Sendable` | |
| `type SyllableGridMode = "hear" \| "vowel"` | `public enum SyllableGridMode: String, CaseIterable, Sendable` | |
| `type Difficulty = 0\|1\|2\|3\|4` | `public enum Difficulty: Int, CaseIterable, Sendable { case d0 = 0, d1, d2, d3, d4 }` with `public var weight: Int { rawValue }` | The names carry no invented semantics (`d0` is not "training", even though the comment says so — the comment is documentation, not a rename). Keeping it an enum preserves the TS compile error for `difficulty: 5`. `sessionReward` uses `.weight`. |

`type View` is a discriminated union and lands as:

```swift
public enum AppView: Hashable, Sendable {
    case hub
    case play(exercise: ExerciseId, level: Int)
    case dashboard
    case shop
    case pick
    case paywall
}
```

Consumed by the UI agent (`App.tsx` router). Declared here because it lives in
`types.ts`; if the UI agent would rather own it, that is a one-line move — flagged in §8.

### 3.2 Interfaces → structs

All content and round types are `public struct`, `Sendable`, `Hashable`, with a
memberwise `public init`. They are **value types with no identity**; §4.1 explains why
that matters for `repeatSession`.

Optional TS fields map straight to Swift optionals, with two deliberate exceptions:

- `ExerciseMeta.mixed?: boolean` → `public var mixed: Bool = false`. TS only ever writes
  `mixed: true` and reads it as a truthy test; `Bool` with a `false` default is exactly
  faithful and removes a pointless `Bool?`.
- `LetterWord.img?: string` / `SyllableWord.img?: string` → `public var img: ImageKey?`
  where `public enum ImageKey: String, Sendable { case igloo, jupe, macaron, pyjama }`.
  In TS these are Vite-imported SVG **URLs**; ALCore must not know about bundles or
  SwiftUI, so it holds a key and ALArt resolves it (§3.3).

Full list of struct fields (types only; every field keeps its TS name):

```
LetterWord         letter: String, word: String, emoji: String, img: ImageKey?
ReadImageRound     target: LetterWord, choices: [LetterWord]
FirstLetterLevel   letters: [String]?, pick: Int, repeats: Int
FirstLetterRound   target: LetterWord, choices: [String]
ReadImageLevel     pick: Int, repeats: Int, distractors: Int
LetterMatchLevel   letters: [String]?, pick: Int, repeats: Int, distractors: Int
LetterFace         base: String, glyph: String, script: LetterScript
LetterMatchRound   prompt: LetterFace, choices: [LetterFace]
SyllableWord       word: String, syllables: [String], emoji: String, img: ImageKey?
SyllableTier       minSyllables: Int, maxSyllables: Int, pick: Int, repeats: Int
SyllableTile       id: Int, syllable: String
SyllableRound      word: SyllableWord, slots: [String?], locked: [Bool], tray: [SyllableTile]
SoundTarget        sound: String, spelling: [String], word: String?, emoji: String?
SoundLevel         distractors: Int
SoundTile          id: Int, letter: String
SoundRound         target: SoundTarget, slots: [String?], tray: [SoundTile]
BasicSound         sound: String, graphy: String, word: String, emoji: String, traps: [String]?
FindSoundLevel     pick: Int, repeats: Int, distractors: Int
FindSoundRound     target: BasicSound, choices: [BasicSound]
GridSyllable       text: String, sound: String, consonant: String, vowel: String
SyllableGridLevel  pick: Int, repeats: Int, choices: Int, column: Int
GridRound          target: GridSyllable, choices: [GridSyllable]
TwinGraphy         text: String, word: String, emoji: String
TwinFamily         sound: String, graphies: [TwinGraphy]
TwinLevel          pick: Int, repeats: Int, distractors: Int
TwinTile           id: Int, text: String, sound: String, word: String, emoji: String, correct: Bool
TwinRound          family: TwinFamily, tiles: [TwinTile]
SpellSyllableLevel pick: Int, repeats: Int, distractors: Int
SpellCell          letter: String, glyph: String, script: LetterScript, fill: Bool,
                   slotIndex: Int, syllableStart: Bool
SpellLetterTile    id: Int, letter: String, glyph: String, script: LetterScript
SpellSyllableRound word: SyllableWord, cells: [SpellCell], answer: [String],
                   answerFaces: [LetterFace], tray: [SpellLetterTile]
ExerciseMeta       id: ExerciseId, name: String, emoji: String, levelCount: Int,
                   difficulty: Difficulty, hint: String?, mode: SyllableMode?,
                   grid: SyllableGridMode?, spell: SpellSyllableMode?,
                   mixed: Bool = false, match: LetterMatchKind?
```

`slots: (string | null)[]` → `[String?]`. `[T?]` is exactly `(T|null)[]`; do not
"improve" it into an enum, the exercises index it and write `nil` back.

Every `…Tile` type has an `id: Int` and must be `Identifiable` (`public var id: Int`
already satisfies it) — SwiftUI `ForEach` needs it, and the TS uses these ids as React
keys for exactly the same reason.

### 3.3 Index signatures / `Record<K, V>`

| TypeScript | Swift |
|---|---|
| `Record<SyllableMode, string>` (`MODE_HINT`) | `[SyllableMode: String]` |
| `Record<LetterMatchKind, string>` (`MATCH_HINT`) | `[LetterMatchKind: String]` |
| `Record<SpellSyllableMode, string>` (`SPELL_HINT`) | `[SpellSyllableMode: String]` |
| `Record<SyllableGridMode, string>` (`GRID_PROMPT`) | `[SyllableGridMode: String]` |
| `LETTER_MATCH_PROMPTS` (`as const` object, 4 named keys) | `public struct LetterMatchPrompts { let toLower, toUpper, toCursive, toPrint: String }` — a struct, not a dictionary, because `Object.values()` order is iterated in `enumerateUtterances` (§4.9). |
| `Record<LetterScript, string>` (`SCRIPT_FONT`) | **not ALCore** — see §3.4. |

Swift `Dictionary` is unordered; TS `Record` literals are insertion-ordered. Only
`LETTER_MATCH_PROMPTS` is ever iterated, hence the struct. The three `…_HINT` maps are
only ever subscripted, so a `Dictionary` is safe. A `[K: String]` keyed by an enum loses
TS's exhaustiveness check — add a test that each has `Enum.allCases.count` entries.

### 3.4 `letterForms.ts`

- `faceLabel(face)` → `ALCore/Domain/LetterTypes.swift`,
  `public func faceLabel(_ face: LetterFace) -> String`. Pure, French copy byte-identical:
  `"Lettre \(face.base) \(caseWord)\(scriptWord)"`, `caseWord` = `"majuscule"` when
  `face.glyph == face.glyph.uppercased()` else `"minuscule"`, `scriptWord` =
  `" attachée"` (leading space) for cursive else `""`.
  **Use non-locale `uppercased()`**, never `uppercased(with: Locale.current)` — a Turkish
  locale would turn `i` into `İ` and flip the label.
- `SCRIPT_FONT` is a CSS font stack and does **not** belong in ALCore. It becomes
  `ScriptFont.swift` in ALUI/ALArt: `print` → `.system(.body, design: .rounded)`,
  `cursive` → `Font.custom("SnellRoundhand-Black"/"SnellRoundhand", …)` with
  `"Bradley Hand"` as the fallback (both ship on iOS; `Segoe Script` does not exist there
  and drops out of the stack naturally). Flagged to the UI agent in §8.

---

## 4. Behaviour notes

### 4.1 Randomness — the single highest-value decision

Nine call sites in `levels.ts` use `Math.random()`, and every session and every round is
built from them. The port must make all of it seedable, or none of `levels.test.ts`'s
property tests can be reproduced on failure.

**Design: one `RandomSource` reference type, threaded explicitly, never a global.**

```swift
public protocol ALRandomGenerator {                 // trivial; lets us box any source
    mutating func nextBits() -> UInt64
}

/// Deterministic, portable, no Foundation. SplitMix64 — 1 mul-free line per draw,
/// passes BigCrush's smallcrush, and is stable across OS versions (arc4random is not).
public struct SeededGenerator: ALRandomGenerator {
    public init(seed: UInt64)
    public mutating func nextBits() -> UInt64
}

public struct SystemGenerator: ALRandomGenerator { … SystemRandomNumberGenerator … }

public final class RandomSource {
    public init(_ base: some ALRandomGenerator)
    public static func system() -> RandomSource
    public static func seeded(_ seed: UInt64) -> RandomSource

    /// 0 ..< n, unbiased (Lemire rejection). Returns 0 when n <= 0.
    /// The TS analogue is `(Math.random() * n) | 0`.
    public func int(below n: Int) -> Int
    /// The TS analogue is `Math.random() < 0.5`.
    public func bool() -> Bool
    /// Exact port of `shuffle<T>` — see below.
    public func shuffled<T>(_ a: [T]) -> [T]
    /// nil on an empty array. Analogue of `a[(Math.random()*a.length)|0]`.
    public func element<T>(of a: [T]) -> T?
}
```

Why a **final class** and not `inout some RandomNumberGenerator`:

- `spellIntruders`, `buildGridRound` and `buildTwinRound` call helpers and closures while
  holding `Set`s and partial arrays; threading `inout` through that is noisy and the
  stdlib's `shuffled(using:)` would have to be re-plumbed at every level.
- The exercise views hold one source per session (`RandomSource.system()` at session
  seed) and pass it down. A reference is the natural shape for "the session's RNG".
- Not `Sendable`. Language mode is v5 (D1), so this compiles clean; when a target flips to
  v6 the source becomes `@MainActor`-isolated with the exercise model that owns it.

Why `shuffled` is our own and not `Array.shuffled(using:)`: the TS is

```ts
export function shuffle<T>(arr: readonly T[]): T[] {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = (Math.random() * (i + 1)) | 0;
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}
```

Port it literally — descending Fisher–Yates, `j = rng.int(below: i + 1)`, swap. It is
three lines, and writing it out means the *number of draws per shuffle* matches the TS,
which keeps a seeded Swift run comparable to a seeded JS run if we ever want to
cross-check. `Array.shuffled(using:)` uses the same algorithm but draws differently.

**Every function that consumes randomness takes `_ rng: RandomSource` as its last
parameter**, with a convenience overload defaulting to a fresh `RandomSource.system()`
so the UI call sites stay short. The convenience overload must **not** default to a
shared global source — a shared global is what makes a seeded test irreproducible when
some unrelated view also draws (see §7, the SwiftUI `@State` trap).

Affected signatures:

```
Levels.buildFirstLetterSession(level:_ rng:)
Levels.buildReadImageRound(target:distractors:_ rng:)      Levels.buildReadImageSession(level:_ rng:)
Levels.buildLetterMatchSession(kind:level:_ rng:)
Levels.pickDistractorSyllable(exclude:_ rng:)              Levels.buildSyllableRound(word:mode:_ rng:)
Levels.buildSoundRound(target:distractors:_ rng:)          Levels.buildSoundSession(level:_ rng:)
Levels.buildFindSoundRound(target:pool:distractors:_ rng:) Levels.buildFindSoundSession(level:_ rng:)
Levels.buildGridRound(target:pool:cfg:mode:_ rng:)         Levels.buildSyllableGridSession(level:mode:_ rng:)
Levels.buildTwinRound(family:pool:distractors:_ rng:)      Levels.buildTwinSession(level:_ rng:)
Levels.buildSpellSyllableRound(word:mode:distractors:mixed:_ rng:)
Levels.buildSpellSyllableSession(level:_ rng:)
repeatSession(_:pick:repeats:_ rng:)
```

Everything else in scope (pools, ladders, prompts, rewards, `voKey`) is already pure and
takes no source.

### 4.2 `repeatSession` — port it on **indices**, not elements

```ts
export function repeatSession<T>(pool: readonly T[], pick: number, repeats: number): T[] {
  const p = Math.min(pick, pool.length);
  const r = Math.min(repeats, p);
  const picks = shuffle(pool).slice(0, p);
  if (r === 0) return picks;
  const doubles = shuffle(picks).slice(0, r);
  let out = shuffle([...picks, ...doubles]);
  for (let tries = 0; tries < 64 && out.some((x, i) => i > 0 && x === out[i - 1]); tries++) {
    out = shuffle([...picks, ...doubles]);
  }
  return out;
}
```

Two details that decide the port:

1. `x === out[i - 1]` is **reference identity**, not value equality. The pools hold object
   references, so two structurally identical entries would still be "different". Swift
   structs have no identity, so a naive `==` port would change behaviour on any dataset
   that ever grows a duplicate row. (Today no shipped pool has value-duplicate rows —
   `SOUND_TARGETS[3]` has four `{sound:"oi", spelling:["O","I"]}` entries but each has a
   different `word` — so this is latent, not live.)

   **Therefore: implement the algorithm over `[Int]` indices.**

   ```swift
   public func repeatSessionIndices(count: Int, pick: Int, repeats: Int,
                                    _ rng: RandomSource) -> [Int]
   public func repeatSession<T>(_ pool: [T], pick: Int, repeats: Int,
                                _ rng: RandomSource) -> [T]   // maps the indices
   ```

   Index identity ≡ TS reference identity, exactly, forever. It also makes the tests
   ("`counts.size === pick`", "no two consecutive") expressible without requiring the
   element type to be `Hashable`.

2. The reroll loop is bounded at **64 tries** and may give up with a back-to-back pair
   still present. Keep the bound and the give-up. Do not "fix" it into a guaranteed
   construction — the comment block above it describes a pairing algorithm that the code
   no longer implements, and the tests pass because 64 rerolls is overwhelming on runs of
   ≥3. Port the code, not the comment. (The stale comment is noted in §7.)

Clamping: `p = min(pick, pool.count)`, `r = min(repeats, p)`. A one-element pool yields
`["a","a"]` — back-to-back and unavoidable; the TS accepts it and so do we.

### 4.3 Ordered dedup — Swift `Set` iteration order will break this

TS `Set` iterates in insertion order and `Array.from(new Set(xs))` is a stable
order-preserving dedupe. **Swift `Set` iteration order is unspecified and randomised per
process.** Every one of these must use an explicit ordered dedupe, or the port becomes
nondeterministic even under a seed:

- `SYLLABLE_BANK = Array.from(new Set(SYLLABLE_WORDS.flatMap(w => w.syllables)))`
  — the order is load-bearing twice: `pickDistractorSyllable` picks by index (so order
  is the distribution), and its fallback is `SYLLABLE_BANK[0]` (today `"CHA"`).
- `spellIntruders`: `for (const base of new Set(answer))` iterates the answer's distinct
  letters **in first-appearance order**.
- `FirstLetterExercise.buildSession`: `[...new Set(pool.map(w => w.letter))]`.

Provide once, in `Support/RandomSource.swift` (or its own file):

```swift
public func orderedUnique<T: Hashable>(_ xs: [T]) -> [T]
```

`Set` is fine for pure membership tests (`allowed.has(...)`, `seen.has(...)`,
`need.has(...)`) — those never iterate.

### 4.4 Tile ids

Four module-level counters in `levels.ts`: `_tileId`, `_letterTileId`, `_twinTileId`,
`_spellTileId`. They are globally monotonic for the process lifetime and are used as
React keys. SwiftUI needs the same property: if ids restarted at 0 each round, SwiftUI
would structurally identify round *n*'s tile 0 with round *n+1*'s tile 0 and reuse the
view — which would carry the WAAPI-equivalent press/shake state across rounds
(invariant 1/2 territory).

```swift
public final class TileIDAllocator {
    public init(next: Int = 0)
    public func next() -> Int
    public static let shared = TileIDAllocator()      // the default
}
```

One allocator per *kind* is not needed — the TS has four only because they are four
`let` declarations; ids are only ever compared within one tray. Use a single shared
allocator by default, and let builders take `ids: TileIDAllocator = .shared` so a test
can assert exact ids. Not `Sendable`; same v5 reasoning as `RandomSource`.

### 4.5 Level indexing and clamping — three different behaviours, all preserved

| function | behaviour in TS |
|---|---|
| `firstLetterPool(level)` | `FIRST_LETTER_LEVELS[level - 1]` — **no clamp**. `level = 0` or `6` throws `TypeError`. |
| `syllableTier`, `soundLevel`/`soundPool`, `readImageLevel`, `letterMatchLevel`, `findSoundLevel`, `twinLevel`, `syllableGridLevel`, `spellSyllableLevel` | `min(max(level,1), COUNT) - 1` — clamped both ends. Tests assert `f(0) === levels[0]` and `f(999) === last`. |
| `spellSyllablePool(name lookup)` | throws `Error("spellSyllablePool: unknown word …")` on a name not in `SYLLABLE_WORDS`. |

Port:

- Clamped ones: `let i = min(max(level, 1), Levels.xxx.count) - 1`. Straightforward.
- `firstLetterPool`: keep it unclamped, i.e. `Levels.firstLetterLevels[level - 1]`, which
  traps in Swift where TS threw. The router never produces an out-of-range level. Add a
  doc comment saying so and do **not** silently add a clamp — that would be a behaviour
  change (§7 lists it as a risk; §8 asks for an app-level call on trap-vs-clamp).
- `spellSyllablePool`: `guard let w = Content.wordByName[name] else { preconditionFailure("spellSyllablePool: unknown word \"\(name)\"") }`. A content typo must fail loudly at first use, exactly as today; it is caught by a test at build time anyway.

`Content.wordByName` is `[String: SyllableWord]` built once from `Content.syllableWords`
(the TS `WORD_BY_NAME` map). Lazy `static let`.

### 4.6 Round builders — the non-obvious bits

**`firstLetterPool(level)`** — `letters == nil` returns `LETTER_WORDS` *by reference/whole*;
the test asserts `toEqual(LETTER_WORDS)`. Otherwise filter by an allow-`Set`.

**`buildFirstLetterSession`** currently lives in `FirstLetterExercise.tsx`, not `levels.ts`.
It is pure and belongs in ALCore (§8 asks for the call). Verbatim:

```ts
const cfg = FIRST_LETTER_LEVELS[level - 1];
const pool = firstLetterPool(level);
const catalog = cfg.letters ?? [...new Set(pool.map((w) => w.letter))];
return repeatSession(pool, cfg.pick, cfg.repeats).map((target) => {
  const distractors = shuffle(catalog.filter((l) => l !== target.letter)).slice(0, 2);
  return { target, choices: shuffle([target.letter, ...distractors]) };
});
```

Note the **hard-coded 2 distractors** — it is not in `FirstLetterLevel`. Keep it hard-coded.
The `new Set` needs `orderedUnique` (§4.3).

**`buildReadImageRound(target, distractors)`** — distractors are chosen by *distinct emoji*,
because several nouns share a glyph:

```ts
const seen = new Set([target.emoji]);
for (const w of shuffle(LETTER_WORDS)) { if (seen.has(w.emoji)) continue; seen.add(w.emoji); pool.push(w); if (pool.length >= distractors) break; }
return { target, choices: shuffle([target, ...pool]) };
```

It shuffles the **whole `LETTER_WORDS`**, not the level pool (there is only one pool).
Seeding `seen` with the target's emoji is also what stops the target being picked twice.

**`caseRound` / `scriptRound`** — the two/three coin flips per round:

- `caseRound`: one flip, `promptUpper`. Prompt is `base` cased by it; every choice is
  cased by `!promptUpper`. Both `script: .print`.
- `scriptRound`: two flips — `upper` (shared by prompt and all tiles) and `promptCursive`.
  Prompt script = cursive iff `promptCursive`; every tile takes the other script; case is
  `upper` throughout.

Draw order matters for seeded reproducibility: in `scriptRound` `upper` is drawn **before**
`promptCursive`. Keep it.

**`letterMatchPrompt(prompt, answer)`** — script change wins over case change:

```ts
if (prompt.script !== answer.script) return answer.script === "cursive" ? toCursive : toPrint;
return answer.glyph === answer.glyph.toUpperCase() ? toUpper : toLower;
```

**`buildSyllableRound(word, mode)`**:
- `fill-blank`: `missing = rng.int(below: syl.count)`; `slots[i] = (i == missing) ? nil : syl[i]`;
  `locked[i] = (i != missing)`; one distractor from `pickDistractorSyllable(exclude: Set(syl))`;
  `tray = shuffle([syl[missing], distractor])` — exactly two tiles.
- `order`: all slots `nil`, all `locked` false, tray = shuffled syllables.
- `order-distractor`: same + one distractor appended before the shuffle.

**`pickDistractorSyllable(exclude)`**:
`SYLLABLE_BANK.filter(!exclude.contains)` then `options[rng.int(below: options.count)]`,
falling back to `SYLLABLE_BANK[0]` when `options` is empty. In Swift, `rng.element(of:) ?? Content.syllableBank[0]`.

**`buildSoundRound(target, distractors)`**:
`need = Set(target.spelling)`; `intruders = shuffle(SOUND_LETTER_BANK.filter(!need))` prefix
`distractors` (a `prefix`, so asking for more than the bank holds just yields fewer);
`slots = target.spelling.map { _ in nil }`; tray = shuffled `spelling + intruders`.

**`buildFindSoundRound(target, pool, distractors)`** — the trap-preference rule:

```ts
const candidates = pool.filter(e => e.sound !== target.sound && e.graphy !== target.graphy);
const byGraphy = new Map(candidates.map(e => [e.graphy, e]));
const traps = shuffle(target.traps ?? []).map(g => byGraphy.get(g)).filter(defined);
const rest  = shuffle(candidates.filter(e => !traps.includes(e)));
// then take from [...traps, ...rest] until `distractors`, deduped by graphy
```

`traps.includes(e)` is reference identity again → in Swift filter by **graphy**
(`Set(traps.map(\.graphy))`), which is equivalent because `byGraphy` is keyed by graphy
and a level's graphies are unique (asserted by a test). Note the trap list itself is
shuffled before resolution.

**`buildGridRound(target, pool, cfg, mode)`**:
- `need = cfg.choices - 1`.
- `sameRow` = shuffled cells with the same consonant and a different vowel.
- `mode == .vowel`: return `shuffle([target] + sameRow.prefix(need))` — `cfg.column` is
  ignored, and no dedupe is needed (row cells are unique).
- `mode == .hear`: `sameColumn` = shuffled same-vowel/other-consonant cells, prefix
  `min(cfg.column, need)`; then drain `sameColumn + sameRow` into `picked`, deduping on
  `.text` with `seen` seeded by `target.text`, until `need`.

**`buildTwinRound(family, pool, distractors)`** — intruders are graphies of the level's
*other* families, carrying their own family's sound:

```ts
const others = shuffle(pool.filter(f => f.sound !== family.sound)
                           .flatMap(f => f.graphies.map(g => ({...g, sound: f.sound}))));
```

In Swift that anonymous shape is a local `struct SoundedGraphy { text, word, emoji, sound }`.
Dedupe against `Set(family.graphies.map(\.text))` plus each other. Tiles: family graphies
(`correct: true`, sound = family sound) + picked (`correct: false`), shuffled **then**
assigned ids — the ids follow the shuffled order, which is what the TS does.

**`buildSpellSyllableRound(word, mode, distractors, mixed = false)`** — the densest one:

```ts
const hideCount = Math.min(mode === "letters-two" ? 2 : 1, syl.length - 1);
const hidden = new Set(shuffle(syl.map((_, i) => i)).slice(0, hideCount));
const form = mixed ? MIXED_FORMS[(Math.random() * MIXED_FORMS.length) | 0] : PLAIN_FORM;
```

- `hideCount` clamps to `syl.count - 1`, guaranteeing a written anchor (invariant of the
  design; every `spellSyllablePool` word has ≥3 syllables so `letters-two` always hides 2).
- `hidden` is a `Set<Int>` of syllable indices — membership only, never iterated. Fine.
- Cells are emitted in reading order; for each syllable, `[...s].forEach((ch, ci) => …)`.
  `[...s]` spreads **code points**. Every syllable string is NFC Latin (`GÂ`, `TÉ`, `HÔ`),
  so Swift's `Array(s)` over `Character` is equivalent — **provided the content file is
  NFC**. §6 requires a test for it.
- `slotIndex` is the running count of gap letters (`answer.length` at emit time), `-1` for
  shown cells. `syllableStart` = `ci == 0`.
- `extra = mode == .lettersExact ? 0 : distractors` — `letters-exact` ignores the level's
  distractor count entirely.
- tray = shuffle(`answerFaces + spellIntruders(...)`) mapped to tiles (ids after the shuffle).

**`spellIntruders(answer, form, extra, mixed)`**:

- `extra <= 0` → `[]`.
- **plain**: `shuffle(SOUND_LETTER_BANK.filter(!answer.contains))` prefix `extra`, each in
  the round's `form`. So plain intruders are always *wrong letters*.
- **mixed**: `seen` starts as the answer faces' keys (`"\(glyph)|\(script)"`). Then
  1. `wrongForm`: for each distinct answer letter (**first-appearance order**, `orderedUnique`)
     × each of `MIXED_FORMS` **in declared order** (`GRANDE`, `petite`, `attachée`), `take`
     it if unseen. These are the same letters in the two other writings — the point of the
     game.
  2. `others`: `shuffle(SOUND_LETTER_BANK).filter(not an answer letter)`, each in a
     **freshly drawn random** `MIXED_FORMS` entry, `take` if unseen.
  3. return `(shuffle(wrongForm) + others).prefix(extra)`.
  `others` is *not* shuffled again — it is already in shuffled bank order. And the random
  form for `others` is drawn per element, inside the loop, over the whole filtered bank
  (22 draws), even though at most `extra` are used. Keep the draw count: it is what a
  seeded reproduction depends on.

`faceKey(f) = "\(f.glyph)|\(f.script.rawValue)"`. `MIXED_FORMS` order is
`[(upper,print), (lower,print), (lower,cursive)]` — the three legal "writings"; the tests
hard-code exactly that set.

### 4.7 The hub catalog

`Levels.exercises: [ExerciseMeta]` — 17 rows, **in this order** (the hub renders the array
order and `levels.test.ts` asserts positions):

```
first-letter(d0) find-sound(d1) hear-syllable(d1) pick-vowel(d1) fill-blank(d0)
order-syllables(d1) find-intruder(d1) spell-sound(d2) spell-syllable(d2)
spell-syllable-plus(d2) spell-two-syllables(d3) read-image(d2) match-case(d1)
match-script(d2) sound-twins(d3) spell-syllable-plus-mixed(d4) spell-two-syllables-mixed(d4)
```

`levelCount` is *derived from the ladders* (`Levels.firstLetterLevels.count`, …) — keep
the derivation, do not inline the numbers, so adding a level updates the hub for free.

`exerciseDifficulty(id)` is a linear `first(where:)` returning `.d0` when absent. Keep the
`.d0` fallback (unreachable once the exhaustiveness test below exists, but it is the
documented "not in the catalog earns no bonus" behaviour).

> **Superseded, D57 (2026-07-31).** This section was written when `difficulty: 0`
> meant "pays nothing, ever". It now means "no accuracy bonus": every row,
> training included, pays the completion curve. Read the passages below with
> that substitution; `Rewards.swift` and `rewards.ts` are the authority.

French copy in `name` / `hint` / `MODE_HINT` / `MATCH_HINT` / `SPELL_HINT` / `MIXED_HINT` /
`GRID_PROMPT` / `LETTER_MATCH_PROMPTS` / `READ_IMAGE_PROMPT` is copied **byte for byte**,
including the typographic apostrophes (`Trouve l’intrus`, `Remets les syllabes dans
l’ordre`, `Range le mot… et évite l’intrus !`, `…évite les intrus`) and the ellipsis
character `…`. Do not normalise `’` to `'` or `…` to `...`: `voKey` hashes these strings
and a baked VO clip would be lost.

### 4.8 Prompt / success lines (all VO-load-bearing)

```
soundPrompt(t)      = t.word != nil ? "\(t.sound), comme dans \(t.word!)." : t.sound
soundSuccess(t)     = "Oui ! \(t.word ?? t.sound)."
findSoundPrompt(s)  = "\(s.sound), comme dans \(s.word)."
findSoundSuccess(s) = "Oui ! \(s.word)."
gridPrompt(s)       = s.sound
gridSuccess(s)      = "Oui ! \(s.sound)."
twinPrompt(f)       = "Trouve tous les \(f.sound) !"
twinSuccess(g)      = "Oui ! \(g.word)."
letterMatchSuccess(base) = "Oui ! \(base)."
gridSyllable(c, v)  = GridSyllable(text: c+v, sound: (c+v).lowercased(), consonant: c, vowel: v)
```

`lowercased()` again non-locale. `"CHÉ".lowercased() == "ché"` — asserted by a test.

### 4.9 `enumerateUtterances()` and `voKey()`

`enumerateUtterances` returns `[...new Set(...)]` — **insertion-ordered unique**. Port with
an `orderedUnique` accumulator (an array + a `Set<String>` guard), not a `Set`, so the
generated manifest diffs stably. Emission order, verbatim from the TS:

1. `"Bravo ! Tu as tout réussi !"`, `"Bravo ! Tu as tout trouvé !"`, `"Oh non ! On recommence."`
2. per `LETTER_WORDS`: `"Trouve la première lettre de \(w.word)."`, `"Oui ! \(w.letter). \(w.word)."`
3. `READ_IMAGE_PROMPT`; then per `LETTER_WORDS`: `w.word`, `"Oui ! \(w.word)."`
4. the four `LETTER_MATCH_PROMPTS` **in declaration order** (`toLower, toUpper, toCursive, toPrint` — that is `Object.values` order); then `letterMatchSuccess(base)` per `LETTER_MATCH_ALPHABET`
5. per `SYLLABLE_WORDS`: `w.word`, `"Oui ! \(w.word)."`
6. per flattened `SOUND_TARGETS`: `soundPrompt`, `soundSuccess`
7. per flattened `BASIC_SOUNDS`: `s.sound`, `findSoundPrompt`, `findSoundSuccess`
8. per `GRID_CONSONANTS` × `GRID_VOWELS`: `gridPrompt`, `gridSuccess`
9. per flattened `TWIN_FAMILIES`: `f.sound`, `twinPrompt(f)`, then `twinSuccess(g)` per graphy
10. `SHOP_BOUGHT`, `SHOP_GREW`; then per **distinct** `CATALOG` cost (insertion order of a `Set` over `CATALOG.map(o => o.cost)`): `shopCostLine(cost)` and `"\(shopCostLine(cost)) \(SHOP_NEED_MORE)"`

Step 10 imports `mascot/catalog.ts`, which the mascot agent owns. Contract:
`enumerateUtterances()` in Swift takes `catalogCosts: [Int]` (already in catalog order,
duplicates allowed) as a parameter with a default of `MascotCatalog.costs`. That keeps
`VO/Utterances.swift` compiling before the mascot port lands and keeps ALCore acyclic.

`shopCostLine(cost)` = `cost == 1 ? "Ça coûte 1 étoile." : "Ça coûte \(cost) étoiles."`.

**`voKey` is the one function where a lazy port silently breaks the app** (every baked clip
is found by this hash; a mismatch is a silent fall-through to TTS). The TS:

```ts
const norm = text.normalize("NFC").replace(/\s+/g, " ").trim();
let h = 0x811c9dc5;
for (let i = 0; i < norm.length; i++) { h ^= norm.charCodeAt(i); h = Math.imul(h, 0x01000193); }
return (h >>> 0).toString(36);
```

Port requirements, all mandatory:

- `normalize("NFC")` → `precomposedStringWithCanonicalMapping` (Foundation).
- `charCodeAt(i)` iterates **UTF-16 code units**, so iterate `norm.utf16`. Not
  `unicodeScalars`, not `Characters`. Every emoji in the corpus is above the BMP and
  contributes **two** surrogate halves — using scalars gives a different hash.
- `Math.imul` is a 32-bit wrapping signed multiply. Use `UInt32` and `&*`; `h ^= UInt32(u)`
  where `u` is the UTF-16 unit. FNV offset basis `0x811c9dc5`, prime `0x01000193`.
- `(h >>> 0).toString(36)` → `String(h, radix: 36)` (lowercase digits, which is what JS
  emits).
- `\s+` in JS is `[\t\n\v\f\r    -     　﻿]`.
  Implement it as an explicit `Set<Unicode.Scalar>`/range check, **not** as
  `CharacterSet.whitespacesAndNewlines` (which includes different members and excludes
  `﻿`). `trim()` uses the same class.
- Golden test with vectors dumped from the TS (§6).

---

## 5. Invariant ownership

| CLAUDE.md invariant | in scope? | where it is enforced in the Swift design | what breaks it |
|---|---|---|---|
| **1** feedback on `pointerdown` | no (UI) | — | — |
| **2** animation off the render path | no (UI) | — | — |
| **3** no fail state | partly | `Rewards.missCooldownMs` is a *swallow* window, not a lock. Nothing in ALCore returns "wrong, game over"; `Verdict` has exactly two cases and neither ends a run. | Adding a terminal state to a round builder, or a `lives`/`attempts` field to any level struct. |
| **4** content authored, not computed | **yes, mine** | `Content` is 100 % literal data. `SyllableWord.syllables` is stored, never derived. **ALCore exposes no function `(String) -> [String]` that splits a word**, and none that derives a word from a letter. `spellSyllablePool` resolves *names* through `Content.wordByName` — a lookup, not a generator. | Adding a syllabifier "just for the new words"; deriving `LETTER_WORDS` from an alphabet; computing `syllables` from `word` at launch to shrink the tables. A content test asserts `syllables.joined() == word` for all 45 words — that is a *shape* check, and must never be turned into a *generator*. |
| **5** all levels unlocked | partly | The ladders are plain arrays; no level struct has an `unlocked`/`requires` field, and no pool/builder consults the profile. `Levels` does not import the profile module at all — that is structural, not conventional. | Any `Levels` API taking a `Profile`. |
| **6** accessibility floor | partly | `faceLabel(_:)` (the `aria-label` for a letter tile) lives in ALCore and is unit-tested. Tap targets/reduced motion are UI. | Dropping `faceLabel` into the view layer where nothing tests it. |
| **7** every exercise has a drawn icon | partly | `ExerciseId` is a `CaseIterable` enum and ALArt keys its glyph table by it. The `Record<ExerciseId, …>` compile error becomes a Swift `switch` over `ExerciseId` with **no `default:` clause** in ALArt. §8 asks ALArt to commit to that. | A `default:` in the icon switch, or a `[ExerciseId: Glyph]` dictionary (a dictionary compiles with a missing key). |
| **8** farming never pays | **yes, mine — three of the mechanisms live here** | (a) `Rewards.missCooldownMs = 800` is the swallow window the exercises honour. (b) The star-greying is UI, but the *round count* it is computed over comes from `repeatSession`. (c) **`Rewards.sessionReward` is the only function in ALCore that returns points earned by play.** `Rewards` exposes `rewardFor` (curve lookup) and `previewReward` (display only, guaranteed part only) and nothing else that yields a number a caller could add to a balance. The `difficulty` column on `Levels.exercises` is the single authority, read through `exerciseDifficulty(_:)`. | Adding an `award(...)` helper anywhere else; letting a builder return points; making a `difficulty` mutable or defaulting it (it is non-optional in `ExerciseMeta`, so a new exercise cannot compile without placing itself in the economy); introducing an exercise with `difficulty > 0` that is spam-completable. |
| **9** never persist a bare total | at the seam | `Rewards.ledgerKey(exercise:level:)` produces the string that keys `ClearCounters`. Its format `"\(exercise.rawValue):\(level)"` and every `ExerciseId.rawValue` are a **persistence contract** with the storage agent. | Renaming an `ExerciseId` raw value; changing the separator; "tidying" `ledgerKey` into a struct key. |
| **10** nothing identifying leaves the device | no | Nothing in scope holds a name or a device id. `voKey` hashes only authored copy. | Passing a child's name into `enumerateUtterances` to bake a personalised clip. |
| **11** money never fails closed | no | — | — |

---

## 6. Test plan

**Everything in this scope runs on the host with `swift test`. No simulator. No UI.**
That is the whole payoff of putting the dataset and the ladders in ALCore.

### 6.1 Straight port of `levels.test.ts` (30 blocks, 965 lines)

Port all of it. Structure it as one test file per ladder (§2). The TS runs each property
over every level × every pool item × N repetitions (N = 4…60); keep the same shape — on
the host these are microseconds. Use a **fresh `RandomSource.seeded(k)` per repetition
with `k` = the loop index**, so a failure is reproducible by seed and the suite is not
flaky. Assert:

- `firstLetterPool`: level 1 restricted to its catalog; the `null` level equals
  `Content.letterWords`.
- `readImageLevel` clamping; pool ≥ `pick`; distinct-emoji count ≥ `distractors + 1`.
- `buildReadImageRound`: `choices.count == distractors + 1`; exactly one choice equals the
  target word; all emoji distinct.
- `syllableTier` clamping and level-n→tier-n; `syllablePool` window.
- `buildSyllableRound` per mode: exactly one `nil` slot and `locked[i] == (slots[i] != nil)`
  for fill-blank; tray of 2 containing the missing syllable; `order` tray is a permutation;
  `order-distractor` tray is `syllables.count + 1` and contains all syllables; unique ids.
- `soundLevel`/`soundPool` clamping; every pool ≥ `SOUND_PICK`.
- `repeatSession` over the exact shipped configs (all `FIRST_LETTER_LEVELS` pools + all
  `SYLLABLE_TIERS` pools): length `pick + repeats`; `pick` distinct items; exactly
  `repeats` items appearing twice; none appearing more than twice; no two consecutive.
  Plus the two edge cases: `(["a","b","c"], 8, 4) → 6` collision-free, and `repeats == 0`
  → a plain 3-element shuffle.
- `buildSoundSession` at all 5 levels × 60 runs: `SOUND_SESSION_LENGTH`; key is
  `"\(sound)|\(word ?? "")|\(spelling.joined())"`; `SOUND_PICK` distinct keys,
  `SOUND_REPEATS` of them doubled; no consecutive repeat; every entry from the level pool.
- `buildSoundRound`: empty slots per letter; exact tray at 0 distractors; N intruders none
  of which is in the target and all in the bank; over-asking the bank yields no duplicate
  letters; unique ids.
- `spellSyllablePool`: clamping; every word ≥ 3 syllables; level 1 ≤ 5 words; each level
  ≥ its `pick`.
- `buildSpellSyllableRound` (3 modes × 4 levels × every pool word × 12): cells spell the
  word; syllables are hidden whole (each syllable's cell group is all-fill or none-fill);
  hidden count is 2 for `letters-two` else 1; ≥1 shown cell; `slotIndex` is `0..<n` over
  fill cells and `-1` elsewhere; `answer` equals the fill letters; `letters-exact` tray is
  exactly the answer; the other two add exactly `distractors` intruders from the bank,
  none in the gap; unique tray ids.
- `buildSpellSyllableRound` **mixed** (2 modes × 4 levels × every word × 12): the whole
  word is in one of the three legal writings; every glyph is correctly cased; every answer
  face has a matching tray tile by `(glyph, script)` **counting multiplicity** (the
  solvability test — port the "decrement a multiset" logic exactly); exactly `distractors`
  extras, all distinct faces, none a valid answer; at least one same-letter/wrong-writing
  trap.
- `buildSpellSyllableSession`, `buildReadImageSession`, `buildFindSoundSession`,
  `buildTwinSession`, `buildSyllableGridSession`, `buildLetterMatchSession`: length and
  no-back-to-back, 20–40 runs per level.
- `letterMatchPool` clamping/coverage; `buildLetterMatchSession` per kind: exactly one
  correct counterpart among distinct letters; `case` → both print, tiles flip the prompt's
  case (compare **glyphs**, not names); `script` → tiles flip the script and share the
  prompt's case.
- `letterMatchPrompt` truth table (4 cases).
- `findSoundPool`: clamping; pool ≥ `pick` and ≥ `distractors + 1`; **sounds and graphies
  both distinct within a level**; every authored `trap` resolves to a different-sound entry
  of the same pool.
- `buildFindSoundRound`: `distractors + 1` choices, distinct graphies, exactly one target;
  no distractor shares the target's `sound`; when `traps.count >= distractors`, *all*
  distractors come from `traps`.
- `twinPool`: clamping; pool ≥ `pick`; every family ≥ 2 graphies; family sounds distinct and
  graphy texts unique within a level; enough other-family graphies to fill the quota.
- `buildTwinRound`: every family graphy present exactly once and `correct`, with the
  family's sound; exactly `distractors` intruders; no intruder spells the family sound; no
  duplicate texts; unique ids.
- `syllableGridPool`: exhaustive rows × vowels; `text == consonant + vowel` and
  `sound == text.lowercased()`; unique cells; clamping; pool ≥ `pick`; and the content
  guard — `GRID_CONSONANTS` contains none of `C`, `G`, `K`, `QU`.
- `buildGridRound`: exactly `cfg.choices` tiles, target once, no duplicate text; `vowel`
  mode → every tile shares the consonant and vowels are distinct; `hear` mode → at most
  `cfg.column` tiles swap the consonant and a swapped tile always shares the target's
  vowel; **never a tile that sounds like the answer**.
- prompt-line string tests: `findSoundPrompt/Success`, `twinPrompt/Success`,
  `soundPrompt/Success` (with and without a context word), `gridPrompt/Success` including
  `gridSuccess(gridSyllable("CH","É")) == "Oui ! ché."`.
- hub placement: both grid drills sit after `find-sound` and before `spell-syllable` and
  `spell-sound`.

### 6.2 Straight port of `rewards.test.ts`

All 7 blocks, unchanged: `ledgerKey` format; the curve and its floor; `previewReward`
(untouched → jackpot, prior clears → curve index; D57 dropped its `difficulty`
argument); `sessionReward`
(D57 — training pays the curve and no bonus; full-perfect earns exactly `difficulty`; zero perfect earns the
bare curve; partial scales and floors; hard-careful beats easy-farming by >4×; empty
session does not divide by zero).

### 6.3 New tests the port needs (no TS counterpart)

1. **Seeded determinism.** For each of the 12 randomised builders: two
   `RandomSource.seeded(1234)` produce byte-identical output (compare a canonical string
   encoding). This is the test that makes every other test debuggable.
2. **Seed independence.** `RandomSource.seeded(1)` and `.seeded(2)` produce different
   sessions for at least one builder — catches an RNG accidentally wired to a constant.
3. **`orderedUnique` order.** `Content.syllableBank.first == "CHA"` and the whole array
   equals the first-appearance order of `syllableWords.flatMap(\.syllables)`. This is the
   regression guard for the "Swift `Set` is unordered" trap (§4.3).
4. **Content is NFC.** For every string in every table:
   `s == s.precomposedStringWithCanonicalMapping`. An NFD `É` would change `[...s]`
   semantics, `voKey`, and the baked-clip lookup all at once.
5. **`voKey` golden vectors.** A table of ~40 `(utterance, key)` pairs dumped from the TS
   (`node -e` over `voKey`), covering: plain ASCII, `é/È/Ô`, the typographic `’` and `…`,
   an emoji-bearing string, a multi-space string, and a leading/trailing-space string.
6. **`enumerateUtterances` count + order.** Assert the total count matches the TS's
   (dump it once) and that the first 10 and last 10 entries match. Order is the diff
   stability guarantee for the VO manifest.
7. **`ExerciseId` raw values.** A golden array of the 17 strings, in `allCases` order,
   compared literally. This is the persistence contract (invariant 9).
8. **Hub catalog exhaustiveness.** `Set(Levels.exercises.map(\.id)) == Set(ExerciseId.allCases)`
   and `Levels.exercises.count == ExerciseId.allCases.count` (no duplicate rows).
9. **`levelCount` is derived.** Each row's `levelCount` equals the count of the ladder it
   claims (`mode`/`grid`/`spell`/`match` tells you which).
10. **Hint dictionaries are total.** `Levels.modeHint.count == SyllableMode.allCases.count`,
    same for `matchHint`, `spellHint`, `gridConsigne` — recovering the exhaustiveness that
    `Record<K,V>` gave us for free.
11. **Content shape.** `syllables.joined() == word` for all 45 `syllableWords`; every
    `spellSyllableWordNames` entry resolves; every `SoundTarget.spelling` is non-empty and
    all-uppercase; every `BasicSound.graphy` is uppercase; every `LetterWord.letter` is a
    single uppercase character.
12. **No syllabifier.** Not mechanically testable; it is a review rule recorded in §5 and
    in the file header of `Content.swift`.

### 6.4 Explicitly *not* tested

- **Do not** assert that `difficulty` is monotone with hub order. `CLAUDE.md` says "keep
  the gradient monotone" but the shipped data is not (`fill-blank` d0 sits after
  `find-sound` d1; `read-image` d2 sits before `match-case` d1). Behaviour is frozen: port
  the data, do not write a test the data fails. §7 records it.
- **Do not** golden-test a shuffled sequence against the JS output. The two RNGs differ;
  only the structural properties are the contract.

---

## 7. Risks

Ordered by how likely they are to silently produce a wrong app.

1. **Swift `Set` iteration order is unordered and per-process randomised; TS `Set` is
   insertion-ordered.** Three live sites (§4.3). Missing one makes `SYLLABLE_BANK`'s
   fallback element, the distractor distribution, and the mixed-intruder trap order
   nondeterministic *even under a fixed seed* — which would look like a flaky test rather
   than a port bug. Mitigated by `orderedUnique` + test 6.3.3.

2. **Reference identity vs value equality.** `repeatSession`'s adjacency check,
   `traps.includes(e)` in `buildFindSoundRound`, and the `counts` map in the tests are all
   identity-based in TS. Structs have no identity. Mitigated by porting `repeatSession` on
   indices and by filtering traps on `graphy`. Anywhere else `===` appears on an object,
   check before reaching for `==`.

3. **`voKey` must be UTF-16-exact.** Iterating `unicodeScalars` or `Characters` instead of
   `utf16` produces a plausible-looking hash that misses every emoji-bearing clip, and the
   app degrades to TTS silently (no crash, no log). Mitigated by golden vectors.

4. **SwiftUI `@State` re-initialisation vs React's `useState(() => build())`.** The TS
   builds a session once, in a lazy `useState` initialiser. In SwiftUI, an expression in a
   `@State` property's default is evaluated **every time the view struct is initialised**
   (its value is used only the first time). If the session builder is called there, every
   parent re-render silently builds and throws away a session — behaviourally harmless with
   a system RNG, but it advances a shared RNG and would make seeded reproduction
   impossible. **Rule for the UI agent: never call a `build…Session` from a view's
   `init`/`@State` default.** Build it in `.task {}`/`onAppear`, or in an `@Observable`
   model created once. This is why `RandomSource` has no shared global instance.

5. **`firstLetterPool` has no clamp.** TS throws a catchable `TypeError`; Swift traps
   (process death). Nothing in the router can produce an out-of-range level today. Ported
   as-is; §8 asks whether the app wants a `precondition` with a message instead of a bare
   index trap.

6. **NFC.** `content.ts` is NFC today (`É` = U+00C9). If any string is ever pasted as NFD,
   TS `[...s]` yields 2 items and Swift `Array(s)` yields 1 — the spell exercise would
   generate a different number of cells on the two platforms. Test 6.3.4 locks it.

7. **`Math.floor` vs Swift `/`.** `Math.floor((perfectRounds * difficulty) / totalRounds)`
   — all three operands are non-negative here, so Swift's truncating `Int` division is
   identical. It would diverge for negative inputs; the types make that unreachable
   (`Difficulty` is 0…4, counts are `Int` ≥ 0) but do not add a signed-input overload.

8. **Compile time of the literal tables.** ~600 lines of struct literals across 7 files.
   Swift's type-checker blows up on large *heterogeneous* expressions, not on arrays of
   explicitly-annotated struct literals — but the risk is real enough to mitigate up front:
   annotate every array (`public static let soundTargets: [[SoundTarget]] = [...]`), give
   every struct an explicit memberwise `init`, and keep each table in its own file. If a
   file ever exceeds a couple of seconds, split the array into
   `part1 + part2` `static let`s. Measure with `-Xfrontend -warn-long-expression-type-checking=500`.

9. **The `difficulty` gradient is not actually monotone** (§6.4). Ported as-is. Whoever
   later "fixes" the data changes the economy.

10. **The `repeatSession` doc comment describes an algorithm the code does not implement**
    ("group each repeated item as an adjacent pair… deal across even indices, then odd").
    The code does shuffle-and-reroll-64-times. Port the code. Copy the comment verbatim
    anyway (behaviour frozen includes not silently editorialising), or copy it with a
    one-line `// NB: the paragraph above describes an earlier construction; the code
    rerolls.` — my recommendation is the latter, since a future reader of the Swift will
    otherwise assume a guarantee that does not exist.

11. **`Sendable` / concurrency.** `RandomSource` and `TileIDAllocator` are mutable reference
    types and are deliberately not `Sendable`. Fine in language mode v5 (D1). When ALCore
    flips to v6 they must become `@MainActor` (they are only ever touched from the UI
    thread) — that is a two-line change, not a redesign, provided nobody makes them global
    singletons in the meantime.

12. **`LetterWord.img` indirection.** ALCore holds an `ImageKey`; ALArt must own the
    key→drawing table (the four files under `src/img/` go through the D2 path parser). If
    ALArt uses a dictionary instead of an exhaustive `switch`, a new key compiles and
    renders nothing.

13. **`Levels.exercises` is a `[ExerciseMeta]` and hub order is behaviour.** Nothing in the
    type system stops a reorder. Test 6.3.7/6.3.8 pin membership; the ordering assertion
    from `levels.test.ts` ("hub placement of the grid drills") pins the only ordering the
    TS itself pins. A full order golden test would be stricter than the TS — do not add
    one.

---

## 8. Decisions needed above this scope

1. **Static Swift literals vs a bundled JSON resource for the content tables.**
   **Recommendation: static `let` arrays in Swift source, in ALCore.** Justification:
   - *Recipe parity.* `CLAUDE.md`'s recipe is "append to `LETTER_WORDS` in `content.ts` —
     that's it; pools derive automatically." With static literals that becomes "append to
     `Content.letterWords` in `Content+LetterWords.swift`" — the same single edit, and the
     compiler still checks the shape, the `ImageKey`, and the enum cases. With JSON the
     recipe grows a second step (edit the JSON *and* nothing type-checks it), and a typo in
     an `img` key becomes a runtime `nil` instead of a build error. Invariant 4 says content
     is authored; making it decodable makes it *loadable*, and loadable things fail at
     runtime.
   - *Host testability (D1).* ALCore currently declares no resources. Adding
     `resources: [.process("Content")]` makes every `Content` access depend on
     `Bundle.module`, which works under SwiftPM but introduces a decode step and a failure
     path into a target whose whole selling point is that it is pure and instant.
   - *Diffability.* `git diff` on a Swift struct literal is exactly as readable as on the
     TS. JSON would be marginally noisier (quoted keys) and would lose the inline French
     comments that carry the *reasons* — the `MAI-SON`/`POIS-SON` note, the `PAPILLON`
     note, the `auto` anchor note. Those comments are the most valuable part of
     `content.ts` and JSON cannot hold them. **Copy every one of them across.**
   - *Compile time* is the only argument for JSON, and it is mitigated (§7.8). The escape
     hatch, if a table ever becomes genuinely slow, is splitting the array across
     `static let` parts — not moving to a resource.

2. **Move `buildSession` out of `FirstLetterExercise.tsx` into ALCore** as
   `Levels.buildFirstLetterSession(level:_:)`. It is pure, it is the only session builder
   not in `levels.ts`, and leaving it in the view means the one exercise a four-year-old
   meets first is the one exercise whose session shape has no host test. Behaviour
   unchanged, hard-coded 2 distractors kept. Needs a nod because it touches a file the UI
   agent owns.

3. **`AppView` (the `View` union) — ALCore or ALUI?** I put it in ALCore because it lives in
   `types.ts`, but it is pure navigation. If the UI agent wants it, it is a file move.

4. **`SCRIPT_FONT` → iOS fonts.** ALCore keeps `LetterScript`; the UI agent must pick the
   concrete faces. The CSS stack is
   `'Snell Roundhand','Apple Chancery','Segoe Script','Bradley Hand',cursive` — on iOS,
   Snell Roundhand and Bradley Hand exist, Apple Chancery is macOS-only, Segoe Script does
   not exist. A cursive letterform a French 6-year-old can read is a **design** call, not a
   mechanical one, and D3's pixel diff will show any drift.

5. **`ExerciseId` raw values are a persistence contract.** The storage agent must agree that
   `ledgerKey` stays `"\(rawValue):\(level)"` and that raw values are frozen. Worth an
   explicit line in `DECISIONS.md`.

6. **Trap vs clamp on out-of-range levels** (§7.5). App-level policy: does an impossible
   route crash loudly (matching TS's throw) or clamp silently (diverging from TS)? My
   recommendation is a `preconditionFailure` with a message — loud like TS, but debuggable.

7. **ALArt must key its exercise-icon table with an exhaustive `switch ExerciseId`, not a
   dictionary** — that is what preserves invariant 7's "a new exercise fails to compile
   until it has an icon".
