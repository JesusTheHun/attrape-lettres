# CLAUDE.md

Guidance for Claude Code (and any agent) working in this repo. Read before editing.

## What this is

A French early-reading game for ~6yo children, as a monorepo.

```
apps/game-web/       the PWA. Vite + React 18 + TypeScript (strict) + Tailwind.
                     Also the home of the VO clip bank and its generator.
apps/game-ios/       the native app. SwiftPM package + a thin Xcode wrapper.
                     Has its OWN ARCHITECTURE.md and DECISIONS.md.
apps/game-android/   the native app. Kotlin + Compose, a 5-module Gradle build.
                     Has its OWN ARCHITECTURE.md and DECISIONS.md. Feature
                     complete, 1608 host tests, played on an emulator, never on
                     hardware.
apps/backoffice/     not built.
services/api/        Hono + Zod. Household sync (ETag/412) on DynamoDB and
                     telemetry on S3. Ships as one Lambda behind an HTTP API;
                     `infra/` is the whole deployment, and most of it is alarms,
                     because both clients swallow every failure this service can
                     produce. Built; see its README.
packages/            shared TS. Empty on purpose — see its README.
```

The three game apps are **independent implementations of the same game**, not a
shared core with three shells. They agree because each port was written against
the web app line by line and its tests say so, not because they share code. The
one thing they do share is the baked voice-over: 845 clips that live once, in
`apps/game-web/src/vo/clips/`, staged into each native bundle by that app's own
`scripts/stage-vo.sh` and never committed twice.

## Commands

```bash
pnpm dev            # web dev server
pnpm build          # every JS/TS package
pnpm typecheck      # every JS/TS package
pnpm test           # every JS/TS package
pnpm vo:build       # bake the VO clip bank (needs GEMINI_API_KEY)

cd apps/game-ios && swift test    # 1502 host tests, no simulator needed
cd apps/game-ios && swift run IconForge                 # re-bake the app icon, both apps
cd apps/game-ios && swift run IconForge --sheet <dir>   # every candidate, at 300/60/29

# Android needs JDK 21 on JAVA_HOME; see apps/game-android/README.md.
cd apps/game-android && ./gradlew :core:test   # game logic, no emulator needed
cd apps/game-android && ./gradlew assembleDebug
```

Anything scoped to one package also works from inside it (`cd apps/game-web &&
pnpm test`), and `pnpm --filter @attrape/game-web <script>` from the root.

Do **not** add `pnpm add <pkg>` commands to answers unless explicitly asked.
Prefer solving with what's here; this app deliberately avoids animation/audio
libraries.

## Conventions

- TypeScript, React function components, Tailwind classes. `strict`,
  `noUnusedLocals`, `noUnusedParameters` are on — keep it clean.
- Prose components stay small; colours that come from data are applied via
  `style`, everything else via Tailwind.
- French copy is user-facing; keep it kid-simple and in `fr`.

## Architecture map — `apps/game-web/src/`

Paths below are relative to `apps/game-web/src/`. The iOS app has its own map,
in `apps/game-ios/CLAUDE.md`.

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
- `kv.ts` — the key/value primitive: localStorage, wrapped so every access is
  inside a try/catch. Synchronous by contract (invariant 1 has nowhere to
  await), so there is no boot gate to hydrate.
- `storage.ts` — the ONLY module that reads or writes profiles. Schema history +
  the forward-migration rule live in its header. Currently `roster:v4`.
- `hooks/useProfile.tsx` — the roster, the economy writes, and the v1/v2/v3 → v4
  migrations. The single choke point for every profile mutation.
- `device.ts` — this device's id, the key every counter is indexed by. Not an
  identifier for a person; never send it anywhere.
- `sync/merge.ts` — pure cross-device merge (counters, LWW stamps, tombstones).
  No storage, no network, no clock. This is what lets one child's progress live
  on Dad's phone, Mum's phone and the iPad at once.
- `sync/client.ts` — the transport around it. Household = a uuid + a join code,
  no accounts. `toWire` strips names and their stamps, so the server holds only
  opaque ids and integers. Pull → merge → push on mount and on resume, never on
  a write (gameplay stays offline-first).
- `licensing/` — `entitlement.ts` (pure: trial clock, offline grace, fail-open),
  `store.ts` (the vendor-free IAP seam — StoreKit 2 / Play Billing and nobody
  else; this build has no store behind it, so the seam always answers "web"),
  `useEntitlement.tsx` (provider), `persist.ts`.
- `telemetry.ts` — first-party analytics + JS error reporting. Closed event list,
  closed property allowlist, no identifier of any kind.
- `components/Onboarding.tsx`, `Paywall.tsx`, `ParentalGate.tsx` — the only
  screens written for the adult in the room. Deliberately not kid-styled.

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
9. **Never persist a bare running total.** Anything a child accumulates —
   stars, clears — is stored per device (`Counter`) and folded back into the
   plain number the UI reads. A `number` cannot be merged: two phones offline,
   last-write-wins, and a week of stars is gone. Counters are the reason
   `profile.balance` still *looks* like a number everywhere outside
   `useProfile` / `storage.ts` / `sync/merge.ts`. Keep it that way; if a new
   field can change on two devices at once, it is a counter, not a total.
   Cosmetics (which mascot, its colours, a name) are the only legitimate
   last-write-wins fields — losing one costs nothing, losing a star costs trust.
10. **Nothing identifying leaves the device.** `ChildProfile.name` holds a
    six-year-old's first name. `sync/client.ts` strips names before upload;
    `telemetry.ts` has a closed property allowlist with no string escape hatch
    and never sends `deviceId()`. Both have tests that assert a name cannot
    appear in a payload. Adding a field to either path means extending those
    tests, not the allowlist by reflex.
11. **Money never fails closed.** An unreachable store, a timed-out receipt
    check, a flat network — none of them may lock a child out. `entitlementOf`
    keeps a paid family paid through a 14-day offline grace, and `canPlay`
    returns true for `unknown`. We would rather give play away than show one
    paying six-year-old a paywall because StoreKit blinked.

## Native, store and money

- **The phones are native apps; nothing ships over the air.** This was once a
  Capacitor shell around this same web bundle, which bought same-day JS updates
  under the DPLA §3.3.1(B) interpreted-code carve-out. That whole mechanism is
  gone with it — a native binary changes only through store review, so every
  fix, including a one-character content fix, waits for a release. Budget for
  it. (The carve-out never covered the paywall, the price or anything under
  `licensing/` anyway; guidelines 2.3.1 and 2.5.2 are what get accounts pulled.)
- **Kids Category (guideline 1.3) shapes the UI, not just the paperwork.** No
  purchase may sit in front of a child: the expired-trial screen shows a
  kid-legible "ask a grown-up", and the price only exists behind
  `ParentalGate`. No third-party analytics, no PII or device information to
  third parties — which is the whole reason `telemetry.ts` posts to our own
  endpoint and `store.ts` has no vendor in it. Once in the category we are bound
  to it, even if it is later deselected.
- **The trial is Apple's own mechanism.** Guideline 3.1.1 allows a
  time-based trial before a full unlock via a price-0 non-consumable named
  `"14-day Trial"`; its StoreKit `purchaseDate` is the clock, because it is
  signed and survives a reinstall. Play has no price-0 IAP, so Android keeps a
  local stamp through Auto Backup. Terms must be disclosed BEFORE the trial
  starts — that is `Onboarding.tsx`, which is why it also carries the consent
  checkbox (unticked; pre-ticked consent has been invalid since CJEU Planet49).
- **The two platforms are NOT symmetric on family.** iOS: switch Family Sharing
  on for the €9.99 non-consumable in App Store Connect — six people, free, no
  code. Google Play Family Library explicitly does not share in-app purchases,
  ever; Android restores per Google account only. Any copy promising "toute la
  famille" belongs to the app that can keep the promise — it lives in the iOS
  app's `Copy.swift`, and the web build, which has no store, says "sur vos
  appareils". The fix, when it is wanted, is entitlement on the sync backend
  keyed by the household — **not by a `familyId`, which does not exist.** No
  API exposes Apple family membership to a third-party app, deliberately: a
  family identifier would be a cross-user identifier, exactly what the Kids
  Category exists to keep out. `Transaction.ownershipType == .familyShared`
  says an entitlement arrived *through* a family and carries no key to join
  on. The candidate anchor is `Transaction.appAccountToken`, which we set
  ourselves — but it exists only after a purchase, so it is nothing during the
  trial, and its propagation to family-shared transactions wants verifying on
  real hardware before anything is built on it.
- **A household is joined, never inferred.** Two devices agree either through
  iCloud key-value store (same Apple ID only — one parent's phone and the
  family iPad, automatically, with no screen) or by scanning a QR, which is
  the only path that crosses two Apple IDs and the only one Android or the web
  could ever have. A scan is stamped and therefore wins; joining merges the
  local roster INTO the joined household, so losing the id costs nothing —
  which is the only reason invariant 9 tolerates a last-write-wins value here.
  `apps/game-ios/DECISIONS.md` D53 has the whole argument.

## Recipes

**Change the app icon:** edit `apps/game-ios/Sources/ALUI/AppIcon/AppIcon.swift`
— it is a SwiftUI view built from the product's own tokens, not a raster — then
`swift run IconForge`, which writes the iOS `AppIcon.appiconset` AND the PWA's
`icon-192`/`icon-512`/`apple-touch-icon`/`icon-maskable-512`. Forget the re-bake
and `AppIconTests` fails: it compares the committed PNG against a fresh render.
`--sheet <dir>` renders every candidate masked as iOS masks it, at 300, 60 and
29 pt — judge there, not at 1024. D58 has the rest, including why the confetti
sits exactly where it does.

Two icons are NOT baked and can drift, because no renderer here emits their
format: `public/icon.svg` and Android's three `ic_launcher_*.xml`. Both are hand
transcriptions in the same 1024-unit space, so the numbers copy across. Android's
also drops the blurred shadow, draws its own A (a VectorDrawable cannot set type,
and this app ships no fonts) and scales the whole composition by 0.628, which is
what puts the outermost fleck exactly on the 66 dp an adaptive icon guarantees.
Its A20 explains each, and `LauncherIconContractTest` parses the geometry back
out of the files so a palette change cannot leave the icon behind.

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

**Fix a pronunciation:** add a row to `IPA` in `apps/game-web/scripts/generate-vo.mjs` — the
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
`--no-gate` disables it. Rejected takes are KEPT in `apps/game-web/src/vo/clips/.rejects/`
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

**Add a field to the saved profile:** decide its merge class FIRST (invariant
9) — accumulates on two devices ⇒ `Counter`; cosmetic ⇒ a value plus a `Rev`
stamp; a set ⇒ grow-only array. Then bump `KEY` to `:vN` in `storage.ts`, add a
`loadV(N-1)` reader, migrate forward in `useProfile`, and KEEP the old key and
reader (a rolled-back launch must still read something it understands). Add the
field to `mergeProfile`/`mergeChild` in the same change, with a test that two
offline devices both writing it lose nothing.

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