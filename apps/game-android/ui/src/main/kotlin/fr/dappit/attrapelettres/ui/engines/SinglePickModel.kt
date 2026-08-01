package fr.dappit.attrapelettres.ui.engines

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.FindSoundRound
import fr.dappit.attrapelettres.core.domain.FirstLetterRound
import fr.dappit.attrapelettres.core.domain.GridRound
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.LetterMatchRound
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.ReadImageRound
import fr.dappit.attrapelettres.core.domain.SyllableGridMode
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.FIRST_LETTER_LEVELS
import fr.dappit.attrapelettres.core.levels.GRID_PROMPT
import fr.dappit.attrapelettres.core.levels.READ_IMAGE_PROMPT
import fr.dappit.attrapelettres.core.levels.buildFindSoundSession
import fr.dappit.attrapelettres.core.levels.buildFirstLetterSession
import fr.dappit.attrapelettres.core.levels.buildLetterMatchSession
import fr.dappit.attrapelettres.core.levels.buildReadImageSession
import fr.dappit.attrapelettres.core.levels.buildSyllableGridSession
import fr.dappit.attrapelettres.core.levels.findSoundPrompt
import fr.dappit.attrapelettres.core.levels.findSoundSuccess
import fr.dappit.attrapelettres.core.levels.gridPrompt
import fr.dappit.attrapelettres.core.levels.gridSuccess
import fr.dappit.attrapelettres.core.levels.letterMatchPrompt
import fr.dappit.attrapelettres.core.levels.letterMatchSuccess
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.MissCooldown
import fr.dappit.attrapelettres.ui.components.StarStrip
import fr.dappit.attrapelettres.ui.design.Copy
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// The single-pick family — ONE loop for FirstLetter, FindSound, SyllableGrid,
// LetterMatch and ReadImage. The five TSX files are the same component with
// different data; every difference between them travels in the descriptor
// below, never in a fork of the loop.
//
// Invariants owned here:
//   1  pick() is synchronous: SFX, verdict, flash and the star grey all happen
//      inside the touchDown call, before any suspension.
//   3  a wrong tap mutates ONLY the cooldown and the round's star. No lock, no
//      route change, NO MOOD CHANGE, no terminal state but `done`.
//   8  the cooldown swallow (MISS_COOLDOWN_MS, strict `<`, 800 ms) with the
//      press and shake still playing; the star greys on the FIRST wrong tap at
//      pointer-down; points enter exclusively through `deps.award`, once, at
//      the single finish transition.

/**
 * Everything that distinguishes one single-pick exercise from its siblings. The
 * five factories on [SinglePickModel]'s companion are the audit surface against
 * the TSX.
 */
data class SinglePickDescriptor<Round>(
    val exercise: ExerciseId,
    val level: Int,
    /** The consigne above the mascot; null = this engine shows none. */
    val headline: String?,
    /** `Finished`'s title, verbatim. */
    val finishedTitle: String,
    /** The big 🔊 button's contentDescription. */
    val listenAccessibilityLabel: String,
    /**
     * ReadImage's preview checks `locked`; the other four do not. A TSX
     * asymmetry, ported deliberately rather than tidied away.
     */
    val previewGuardedByLock: Boolean,
    val buildSession: (RandomSource) -> List<Round>,
    /** The judge AND the flash key: letter / graphy / text / face.base / word. */
    val targetKey: (Round) -> String,
    /** Spoken 350 ms after the round becomes current, and by `replayPrompt()`. */
    val promptLine: (Round) -> String,
    /**
     * Spoken at rate 0.98 on the correct pick; the round advances only after it
     * has played to completion.
     */
    val successLine: (Round) -> String,
    /** The big 🔊 button's visible text (FirstLetter varies it per level). */
    val listenText: (Round) -> String,
)

/**
 * The loop.
 *
 * `@Stable` is honest here and not decoration: every public property below is
 * either an immutable `val` or snapshot state, so Compose is always notified
 * when one changes. The two mutable fields that are NOT snapshot state
 * (`locked`, `mounted`) are private and no composition reads them — the TSX
 * kept them out of React state for the same reason.
 */
@Stable
class SinglePickModel<Round>(
    val descriptor: SinglePickDescriptor<Round>,
    private val deps: EngineDeps,
    rng: RandomSource = SystemRandomSource(),
) : RoundRunner {

    /**
     * Seeded ONCE, here — which is why the model must be built inside a
     * `remember`, never in a composable body (see RoundRunner.kt's header).
     */
    val session: List<Round> = descriptor.buildSession(rng)

    private var idxState by mutableStateOf(0)
    private var moodState by mutableStateOf(Mood.IDLE)
    private var flashState by mutableStateOf<String?>(null)
    private var doneState by mutableStateOf(session.isEmpty())
    private var earnedState by mutableStateOf(0)
    private val starList = mutableStateListOf<Boolean>().apply {
        repeat(session.size) { add(true) }
    }

    /**
     * TSX `locked` ref — true from the correct pick until its success line has
     * finished. Deliberately not observable: no screen reads it.
     */
    private var locked = false

    /**
     * TSX `mountedRef`. Named `mounted` and not `isActive` on purpose: inside a
     * `launch { }` the CoroutineScope receiver already carries an `isActive`,
     * and the two mean different things.
     */
    private var mounted = true

    private val cooldown = MissCooldown(deps.time)
    private var announceJob: Job? = null

    // --- Projection surface --------------------------------------------------

    override val totalRounds: Int get() = session.size
    override val idx: Int get() = idxState
    override val stars: List<Boolean> get() = starList
    override val mood: Mood get() = moodState
    override val done: Boolean get() = doneState
    override val earned: Int get() = earnedState
    override val headline: String? get() = descriptor.headline
    override val finishedTitle: String get() = descriptor.finishedTitle
    override val listenAccessibilityLabel: String get() = descriptor.listenAccessibilityLabel

    /**
     * The current round. `idx` stays on the last round after finish, so this is
     * valid for as long as the session is non-empty.
     */
    val current: Round get() = session[idxState]

    /**
     * The highlighted correct answer's key during the celebration; also the
     * family's all-tiles-disabled flag (`disabled={flash != null}`).
     */
    val flash: String? get() = flashState

    /** `disabled={flash != null}` — during the celebration every tile is disabled. */
    val tilesDisabled: Boolean get() = flashState != null

    /** The big 🔊 button's visible text for the current round. */
    val listenText: String get() = descriptor.listenText(current)

    /**
     * The prompt line for the current round. LetterMatch shows it as the listen
     * button's text; it is also what `replayPrompt()` speaks.
     */
    val promptText: String get() = descriptor.promptLine(current)

    // --- Lifecycle -----------------------------------------------------------

    override fun activate() {
        mounted = true
        deps.audio.unlock()
        scheduleAnnounce()
    }

    override fun deactivate() {
        announceJob?.cancel()
        announceJob = null
        mounted = false
        deps.audio.stop()
    }

    // --- The pick handler (synchronous — invariant 1) ------------------------

    /**
     * A tile was touched. Returns the verdict `Tile` needs SYNCHRONOUSLY, in
     * the same pointer-down beat: everything with a visible or audible effect
     * happens before this returns, and only the celebration's own voice line is
     * awaited afterwards.
     */
    fun pick(key: String): Verdict {
        if (doneState) return Verdict.REJECT // unreachable from a correct screen
        if (locked) return Verdict.REJECT
        if (cooldown.isSwallowing) return Verdict.REJECT // silent while the shake plays
        deps.audio.unlock()
        deps.audio.pop()
        val round = session[idxState]
        if (key != descriptor.targetKey(round)) {
            deps.audio.nudge()
            cooldown.registerMiss()
            StarStrip.miss(idxState, starList) // the star greys NOW, same beat as the shake
            return Verdict.REJECT
        }
        locked = true
        // [DEVIATION, authorised — iOS D45] The round has been answered, so the
        // pending "here is the task" prompt is stale. The TSX does NOT cancel
        // it, and a correct tap inside the 350 ms window therefore let the
        // prompt speak over the success line: `say` returned false, the guard
        // below dropped the advance, and the game stalled with `locked` still
        // true — silently, on the fastest children.
        announceJob?.cancel()
        announceJob = null
        flashState = key
        moodState = Mood.HAPPY
        deps.audio.success()
        deps.fireConfetti()
        // Advance only after the success line has played IN FULL. `ok` is false
        // if it was cut short (child left, superseded) — then we do not advance.
        val next = idxState + 1
        val line = descriptor.successLine(round)
        deps.scope.launch {
            val ok = deps.audio.say(line, EngineLines.SUCCESS_RATE, EngineLines.DEFAULT_PITCH)
            if (!ok || !mounted) return@launch
            flashState = null
            locked = false
            if (next >= session.size) {
                announceJob?.cancel()
                announceJob = null
                moodState = Mood.CHEER
                earnedState = deps.award(
                    descriptor.exercise,
                    descriptor.level,
                    StarStrip.kept(starList),
                    session.size,
                )
                doneState = true
                deps.scope.launch { deps.audio.say(EngineLines.BRAVO_FOUND) }
            } else {
                moodState = Mood.IDLE
                idxState = next
                scheduleAnnounce()
            }
        }
        return Verdict.ACCEPT
    }

    // --- Audio affordances ---------------------------------------------------

    /**
     * The big 🔊 button. Guarded, so it can never cut the success line
     * mid-celebration. (No `unlock()` — the TSX buttons call `say` alone.)
     */
    override fun replayPrompt() {
        if (locked || doneState || session.isEmpty()) return
        val line = descriptor.promptLine(session[idxState])
        deps.scope.launch { deps.audio.say(line) }
    }

    /**
     * A tile's « Écouter ». The screen passes what the TSX speaks (letter /
     * choice.sound / face.base / choice.word). Only ReadImage's is
     * locked-guarded — a ported asymmetry, see [SinglePickDescriptor].
     */
    fun preview(text: String) {
        if (descriptor.previewGuardedByLock && locked) return
        deps.audio.unlock()
        deps.scope.launch { deps.audio.say(text) }
    }

    // --- Announce (350 ms after every round becomes current) -----------------

    /**
     * The TSX `useEffect` keyed on `idx`: a 350 ms timer, cancelled when the
     * round advances, the run finishes, or the screen goes away. It is NOT
     * cancelled by a miss — a child who taps wrong still gets told the task.
     */
    private fun scheduleAnnounce() {
        announceJob?.cancel()
        announceJob = null
        if (doneState || session.isEmpty()) return
        val expected = idxState
        val line = descriptor.promptLine(session[expected])
        announceJob = deps.scope.launch {
            deps.delay(EngineLines.ANNOUNCE_DELAY_MS)
            if (!isActive || !mounted || doneState || idxState != expected) return@launch
            deps.audio.say(line)
        }
    }

    // --- The five concrete engines (1:1 with the TSX files) ------------------

    companion object {

        /**
         * `FirstLetterExercise.tsx`. Levels 4–5 drop the written word from the
         * listen button (`level < FIRST_LETTER_LEVELS.length - 1`), so the child
         * works from the sound alone.
         */
        fun firstLetter(
            level: Int,
            deps: EngineDeps,
            rng: RandomSource = SystemRandomSource(),
        ): SinglePickModel<FirstLetterRound> {
            val showWord = level < FIRST_LETTER_LEVELS.size - 1
            return SinglePickModel(
                descriptor = SinglePickDescriptor(
                    exercise = ExerciseId.FIRST_LETTER,
                    level = level,
                    headline = null,
                    finishedTitle = Copy.Finished.ALL_FOUND,
                    listenAccessibilityLabel = Copy.Exercise.REPEAT_WORD,
                    previewGuardedByLock = false,
                    buildSession = { buildFirstLetterSession(level, it) },
                    targetKey = { it.target.letter },
                    promptLine = { "Trouve la première lettre de ${it.target.word}." },
                    successLine = { "Oui ! ${it.target.letter}. ${it.target.word}." },
                    listenText = {
                        if (showWord) {
                            Copy.Exercise.listenWord(it.target.word)
                        } else {
                            Copy.Exercise.LISTEN_GLYPH
                        }
                    },
                ),
                deps = deps,
                rng = rng,
            )
        }

        /**
         * `FindSoundExercise.tsx`. The preview speaks `choice.sound`, the tiles
         * show the graphy.
         */
        fun findSound(
            level: Int,
            deps: EngineDeps,
            rng: RandomSource = SystemRandomSource(),
        ): SinglePickModel<FindSoundRound> = SinglePickModel(
            descriptor = SinglePickDescriptor(
                exercise = ExerciseId.FIND_SOUND,
                level = level,
                headline = Copy.Exercise.FIND_SOUND_HEADLINE,
                finishedTitle = Copy.Finished.ALL_FOUND,
                listenAccessibilityLabel = Copy.Exercise.REPLAY_SOUND,
                previewGuardedByLock = false,
                buildSession = { buildFindSoundSession(level, it) },
                targetKey = { it.target.graphy },
                promptLine = { findSoundPrompt(it.target) },
                successLine = { findSoundSuccess(it.target) },
                listenText = { Copy.Exercise.LISTEN },
            ),
            deps = deps,
            rng = rng,
        )

        /**
         * `SyllableGridExercise.tsx` — ONE engine, two modes. The mode reaches
         * only the session builder, the headline, and what a tile SHOWS (`vowel`
         * mode renders `choice.vowel`); the pick key is `choice.text` in both.
         */
        fun syllableGrid(
            exercise: ExerciseId,
            mode: SyllableGridMode,
            level: Int,
            deps: EngineDeps,
            rng: RandomSource = SystemRandomSource(),
        ): SinglePickModel<GridRound> = SinglePickModel(
            descriptor = SinglePickDescriptor(
                exercise = exercise,
                level = level,
                headline = GRID_PROMPT[mode],
                finishedTitle = Copy.Finished.ALL_READ,
                listenAccessibilityLabel = Copy.Exercise.REPLAY_SYLLABLE,
                previewGuardedByLock = false,
                buildSession = { buildSyllableGridSession(level, mode, it) },
                targetKey = { it.target.text },
                promptLine = { gridPrompt(it.target) },
                successLine = { gridSuccess(it.target) },
                listenText = { Copy.Exercise.LISTEN },
            ),
            deps = deps,
            rng = rng,
        )

        /**
         * `LetterMatchExercise.tsx`. The prompt line never names the letter (the
         * child reads the shape); the success line does. The listen button shows
         * the line itself.
         */
        fun letterMatch(
            exercise: ExerciseId,
            kind: LetterMatchKind,
            level: Int,
            deps: EngineDeps,
            rng: RandomSource = SystemRandomSource(),
        ): SinglePickModel<LetterMatchRound> = SinglePickModel(
            descriptor = SinglePickDescriptor(
                exercise = exercise,
                level = level,
                headline = null,
                finishedTitle = Copy.Finished.ALL_FOUND,
                listenAccessibilityLabel = Copy.Exercise.REPEAT_INSTRUCTION,
                previewGuardedByLock = false,
                buildSession = { buildLetterMatchSession(kind, level, it) },
                targetKey = { it.prompt.base },
                promptLine = { letterMatchPrompt(it.prompt, it.choices[0]) },
                successLine = { letterMatchSuccess(it.prompt.base) },
                listenText = {
                    Copy.Exercise.listenWord(letterMatchPrompt(it.prompt, it.choices[0]))
                },
            ),
            deps = deps,
            rng = rng,
        )

        /**
         * `ReadImageExercise.tsx`. The word is PRINTED, never spoken — reading it
         * is the task — and the prompt is the constant consigne. The only
         * single-pick engine whose preview is locked-guarded.
         */
        fun readImage(
            level: Int,
            deps: EngineDeps,
            rng: RandomSource = SystemRandomSource(),
        ): SinglePickModel<ReadImageRound> = SinglePickModel(
            descriptor = SinglePickDescriptor(
                exercise = ExerciseId.READ_IMAGE,
                level = level,
                headline = Copy.Exercise.READ_IMAGE_HEADLINE,
                finishedTitle = Copy.Finished.ALL_FOUND,
                listenAccessibilityLabel = Copy.Exercise.REPEAT_INSTRUCTION,
                previewGuardedByLock = true,
                buildSession = { buildReadImageSession(level, it) },
                targetKey = { it.target.word },
                promptLine = { READ_IMAGE_PROMPT },
                successLine = { "Oui ! ${it.target.word}." },
                listenText = { Copy.Exercise.listenWord(READ_IMAGE_PROMPT) },
            ),
            deps = deps,
            rng = rng,
        )
    }
}
