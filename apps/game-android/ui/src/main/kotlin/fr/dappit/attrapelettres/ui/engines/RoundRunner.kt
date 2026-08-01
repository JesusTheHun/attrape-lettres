package fr.dappit.attrapelettres.ui.engines

import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.platform.AudioEngine
import fr.dappit.attrapelettres.core.platform.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

// ===========================================================================
// THE ENGINE API — read this before writing an exercise screen.
//
// Three models cover the nine exercises. Each is a PLAIN KOTLIN CLASS holding
// Compose snapshot state; the @Composable is a thin projection over it and
// holds no rule. That split is not stylistic — a host JUnit run cannot invoke a
// composable (A11), so every decision an exercise makes (what is on screen,
// what a tap does, when the round advances, what is spoken, what is awarded)
// has to live somewhere a test can call directly. This is that somewhere.
//
//   SinglePickModel<Round>    FirstLetter, FindSound, SyllableGrid (hear/vowel),
//                             LetterMatch (case/script), ReadImage
//   AssemblyModel<I, R, S>    Assemble (fill-blank/order/order-distractor),
//                             SpellSound, SpellSyllable (all modes, mixed twins)
//   TwinsModel                SoundTwins
//
// Build one with the factories on the companion objects — they wire the :core
// builders, prompts and copy, and they are the 1:1 audit surface against the
// nine TSX files:
//
//   SinglePickModel.firstLetter(level, deps, rng)
//   SinglePickModel.findSound(level, deps, rng)
//   SinglePickModel.syllableGrid(exercise, mode, level, deps, rng)
//   SinglePickModel.letterMatch(exercise, kind, level, deps, rng)
//   SinglePickModel.readImage(level, deps, rng)
//   AssemblyModel.assemble(exercise, mode, level, deps, rng)
//   AssemblyModel.spellSound(level, deps, rng)
//   AssemblyModel.spellSyllable(exercise, mode, level, mixed, deps, rng)
//                              (`mixed` has NO default — it is catalog data)
//   TwinsModel(level, deps, rng)
//
// CREATION (iOS D9 — load-bearing): the constructor seeds the WHOLE session
// through the :core builders, so it must run EXACTLY ONCE per entry into an
// exercise. In Compose that means `remember`, keyed on nothing that changes
// mid-run — never a plain call in the composable body, which re-seeds on every
// recomposition and silently replays different words:
//
//     val model = remember { SinglePickModel.firstLetter(level, deps) }
//     DisposableEffect(model) {
//         model.activate()
//         onDispose { model.deactivate() }
//     }
//
// The route's `key(exercise, level)` (or a `remember(exercise, level)`) is what
// re-seeds on a level change — the port of React's `key={`${ex}-${level}`}`.
//
// activate()   = the TSX mount effects: audio.unlock() + the 350 ms delayed
//                announce.
// deactivate() = unmount: cancels the pending announce, fades the voice
//                (audio.stop()), and FREEZES the model — no advance, no award
//                after it.
//
// THE SCREEN IS A PROJECTION. It reads state and calls the handlers. Shared
// surface (the [RoundRunner] interface):
//
//   totalRounds, idx, stars   → GameFrame(done = progressDone, total =
//   progressDone                          totalRounds, stars = stars)
//   mood                      → the mascot's mood
//   done, earned, finishedTitle → Finished(stars, earned, title)
//   headline                  → the consigne above the mascot; null = no line
//                               (FirstLetter and LetterMatch)
//   listenAccessibilityLabel  → the big 🔊 button's contentDescription
//   replayPrompt()            → the big 🔊 button's touchDown action
//   activate() / deactivate()
//
// Per family:
//
//   SinglePickModel:  current (Round), flash, tilesDisabled, listenText,
//                     promptText
//                     pick(key) -> Verdict        key = the engine's flash key
//                       FirstLetter → letter, FindSound → graphy,
//                       Grid → choice.text (BOTH modes — a vowel tile still
//                       picks by the full syllable text), LetterMatch →
//                       face.base, ReadImage → choice.word
//                     preview(text)               pass what the TSX speaks:
//                       letter / choice.sound / choice.sound / face.base /
//                       choice.word
//                     highlight a tile iff flash == its key; disable ALL tiles
//                     iff tilesDisabled (flash != null).
//
//   AssemblyModel:    current (Item), round (Round), slots, slotTile, used,
//                     lockedMask
//                     pick(tileId, value) -> Verdict   value: the syllable
//                       String / letter String / LetterFace built from the tray
//                       tile ([spellTileFace])
//                     removeAt(slotIndex)         filled-slot touchDown
//                     isSlotRemovable(i)          filled AND not pre-revealed
//                     isTrayTileUsed(id)          disables a tray tile
//                     preview(text)               t.syllable / t.letter
//
//   TwinsModel:       round, targets, found, complete
//                     pick(tile) -> Verdict
//                     tileDisabled(tile), tileHighlighted(tile)
//                     foundTile(i)                the collection strip
//                     preview(text)               tile.sound
//
// INVARIANT 1. Every pick handler is NON-suspend and must be callable straight
// from `Modifier.touchDown`'s `onDown`: SFX, verdict, flash and the star grey
// all happen inside that synchronous call, before Compose commits. Where a
// design wants to await the voice line before advancing, that await is on a
// separate coroutine launched from the handler — never in front of the return.
//
// WHAT THE SCREEN STILL OWNS (data plumbing, not rules):
//   - Tile contentDescriptions via `Copy.Exercise.letterTile` / `syllableTile` /
//     `soundTile` / `imageTile`, or `core.domain.faceLabel` for letter forms;
//     preview labels via `Copy.Exercise.listenTile`.
//   - Tile palettes and sizes (Palette + the per-engine FluidSpecs).
//   - The confetti system: the screen creates it, renders the overlay, and
//     hands its `fire` into [EngineHost.deps] (reduce-motion gating lives
//     inside the confetti system, not here).
//   - The award callback, which must be `ProfileStore::award` and nothing else
//     (invariant 8).
//
// PLACES AN ENGINE LEGITIMATELY DOES NOT FIT THE SHARED SHAPE:
//   - FirstLetter's listen button shows the word only on levels 1–3
//     (`level < count - 1`); the rule is baked into the factory's listenText,
//     the screen just renders `model.listenText`.
//   - SyllableGrid `vowel` mode's half-written syllable: the screen renders
//     `current.target.consonant` plus a gap that shows `current.target.vowel`
//     iff `flash != null`. Tiles show `choice.vowel` in vowel mode and
//     `choice.text` in hear mode — but pick by `choice.text` in both.
//   - ReadImage's printed word is never spoken, and its preview IS
//     locked-guarded where the other four single-pick previews are not
//     (`previewGuardedByLock`, a ported asymmetry).
//   - THE ASSEMBLY ENGINES HAVE NO MISS COOLDOWN, BY DESIGN. Their pacing is
//     the awaited « Oh non ! On recommence. » line. Do not add one for
//     symmetry; see [EngineLines.OH_NON].
//   - Assembly announces round 0 after 350 ms; every later round announces
//     IMMEDIATELY inside the advance. Single-pick and twins announce 350 ms
//     after EVERY round becomes current.
//   - The 350 ms announce timer is NOT cancelled by a miss. It IS cancelled by
//     a correct pick — an authorised deviation from the TSX (iOS D45): the web
//     let a prompt fired inside those 350 ms speak over the success line, `say`
//     returned false, the advance is gated on that, and the round stranded with
//     `locked` still true. Silently, and only for children fast enough to
//     answer in a third of a second.
// ===========================================================================

/**
 * `(exercise, level, perfectRounds, totalRounds) -> points earned`.
 *
 * Wire it to `ProfileStore::award`. An engine NEVER computes a point value and
 * never adds to a balance — it counts perfect rounds honestly and hands the
 * counts over; `core.rewards.sessionReward` is the only earner (invariant 8).
 * Whatever comes back is displayed verbatim as [RoundRunner.earned].
 */
typealias AwardFunction = (
    exercise: ExerciseId,
    level: Int,
    perfectRounds: Int,
    totalRounds: Int,
) -> Int

/** The production announce sleep, and the seam a test replaces. */
object EngineDelays {

    /**
     * `window.setTimeout(…, ms)`. Cancellation-aware for free: `delay` is a
     * cancellable suspension, so a cancelled announce never reaches its `say`.
     */
    val system: suspend (Long) -> Unit = { milliseconds ->
        delay(if (milliseconds < 0L) 0L else milliseconds)
    }
}

/**
 * What a CALLER (the hub / router) can supply to an exercise screen: the audio
 * channel, the clock, `award`, and the announce sleep. All nine screens take
 * exactly this, so the hub dispatches over them uniformly.
 *
 * It exists because [EngineDeps] has two members the caller cannot usefully
 * provide. `fireConfetti` must be THIS run's confetti system (the screen owns
 * it, because it also has to place the canvas), and `scope` must be the
 * screen's own `rememberCoroutineScope()` so every pending line dies with the
 * composition. A parameter that is accepted and then thrown away is worse than
 * one that does not exist — on iOS six screens took a whole `EngineDeps` and
 * silently discarded its `fireConfetti`.
 */
data class EngineHost(
    val audio: AudioEngine,
    val time: TimeSource,
    val award: AwardFunction,
    val delay: suspend (Long) -> Unit = EngineDelays.system,
) {

    /** Complete the set with the run's own confetti and composition scope. */
    fun deps(scope: CoroutineScope, fireConfetti: () -> Unit): EngineDeps = EngineDeps(
        audio = audio,
        time = time,
        award = award,
        fireConfetti = fireConfetti,
        scope = scope,
        delay = delay,
    )
}

/**
 * Everything a round model needs from the outside world, injected so the whole
 * lifecycle runs under `./gradlew :ui:testDebugUnitTest` on the host.
 *
 * A `data class` on purpose: `copy(award = …)` is how a test swaps ONE seam
 * without rebuilding the bundle, which is exactly what the economy suite does.
 *
 * @param audio the SFX + single-flight voice channel (:core's port; a real
 *   engine in the app, a fake in tests). `say` is the only suspend member.
 * @param time drives the miss-cooldown swallow window. INJECTED, never
 *   `System.currentTimeMillis()` — a window a test cannot advance is a window
 *   nobody has verified (iOS D6).
 * @param award the ONLY way points enter a model (invariant 8).
 * @param fireConfetti the run's confetti `fire`; reduce-motion gating lives
 *   inside the confetti system, not here.
 * @param scope the screen's coroutine scope. Every awaited voice line and every
 *   announce timer is launched on it, so leaving the screen cancels them.
 * @param delay the announce timer's sleep, injected so tests drive it without
 *   sleeping.
 */
data class EngineDeps(
    val audio: AudioEngine,
    val time: TimeSource,
    val award: AwardFunction,
    val fireConfetti: () -> Unit,
    val scope: CoroutineScope,
    val delay: suspend (Long) -> Unit = EngineDelays.system,
)

/**
 * The engine-owned constants and spoken lines.
 *
 * The spoken strings are VO CLIP KEYS: the clip bank hashes the utterance's
 * bytes, so they are byte-exact and must never be normalised. They are
 * deliberately NOT in `Copy.kt` — that file owns DISPLAYED text, and a
 * displayed title that happens to read like a spoken line is still a different
 * string with a different owner.
 */
object EngineLines {

    /** `window.setTimeout(() => announce, 350)` — every TSX engine. */
    const val ANNOUNCE_DELAY_MS = 350L

    /** Success lines pass `{ rate: 0.98 }`; pitch stays the 1.1 default. */
    const val SUCCESS_RATE = 0.98
    const val DEFAULT_PITCH = 1.1

    /**
     * The assembly wrong-row line. Spoken at the DEFAULT rate, and the row
     * reset is NOT gated on the returned flag — the wipe happens even if the
     * line was cut short. This awaited line IS the assembly family's pacing,
     * which is why those engines carry no miss cooldown.
     */
    const val OH_NON = "Oh non ! On recommence."

    /** End-of-run bravo (fire-and-forget). Single-pick + twins: */
    const val BRAVO_FOUND = "Bravo ! Tu as tout trouvé !"

    /** Assembly engines: */
    const val BRAVO_SUCCEEDED = "Bravo ! Tu as tout réussi !"
}

/**
 * The uniform surface every exercise screen wires into GameFrame, the mascot
 * and Finished. Conformance is what keeps the nine screens identical in shape —
 * and what lets the hub hold « whatever is playing » as one type.
 */
interface RoundRunner {

    /** Session length. GameFrame's `total`. */
    val totalRounds: Int

    /** The current round index. Frozen at the last round once [done]. */
    val idx: Int

    /**
     * Per-round first-try flags. Greyed (false) on the round's FIRST wrong tap,
     * synchronously at pointer-down (invariant 8).
     */
    val stars: List<Boolean>

    /** Mascot mood — idle / happy / cheer. A MISS NEVER CHANGES IT. */
    val mood: Mood

    /** The run is finished; render `Finished` instead of the round. */
    val done: Boolean

    /**
     * What [AwardFunction] returned at finish. 0 before finish, and 0 for a
     * difficulty-0 exercise only if the curve paid nothing (EarnBadge renders
     * only when > 0).
     */
    val earned: Int

    /** The consigne above the mascot; null = no line (FirstLetter, LetterMatch). */
    val headline: String?

    /** `Finished`'s title, verbatim. */
    val finishedTitle: String

    /** The big 🔊 button's contentDescription. */
    val listenAccessibilityLabel: String

    /** GameFrame's `done` input: `if (done) total else idx`. */
    val progressDone: Int get() = if (done) totalRounds else idx

    /** Mount: unlock audio + schedule the announce. */
    fun activate()

    /** Unmount: cancel the announce, fade the voice, freeze the model. */
    fun deactivate()

    /**
     * The big 🔊 button (touchDown). Locked-guarded: it never cuts the success
     * line mid-celebration.
     */
    fun replayPrompt()
}
