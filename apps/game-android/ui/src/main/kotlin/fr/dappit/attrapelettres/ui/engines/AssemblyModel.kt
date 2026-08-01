package fr.dappit.attrapelettres.ui.engines

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.SoundRound
import fr.dappit.attrapelettres.core.domain.SoundTarget
import fr.dappit.attrapelettres.core.domain.SpellLetterTile
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.SpellSyllableRound
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableRound
import fr.dappit.attrapelettres.core.domain.SyllableWord
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.MODE_HINT
import fr.dappit.attrapelettres.core.levels.buildSoundRound
import fr.dappit.attrapelettres.core.levels.buildSoundSession
import fr.dappit.attrapelettres.core.levels.buildSpellSyllableRound
import fr.dappit.attrapelettres.core.levels.buildSpellSyllableSession
import fr.dappit.attrapelettres.core.levels.buildSyllableRound
import fr.dappit.attrapelettres.core.levels.soundLevel
import fr.dappit.attrapelettres.core.levels.soundPrompt
import fr.dappit.attrapelettres.core.levels.soundSuccess
import fr.dappit.attrapelettres.core.levels.spellSyllableLevel
import fr.dappit.attrapelettres.core.levels.syllablePool
import fr.dappit.attrapelettres.core.levels.syllableTier
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.core.support.repeatSession
import fr.dappit.attrapelettres.ui.components.StarStrip
import fr.dappit.attrapelettres.ui.design.Copy
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// The assembly family — ONE loop for Assemble (fill-blank / order /
// order-distractor), SpellSound and SpellSyllable.
//
// The loop: tiles drop into the first empty slot with NO per-tap judgement;
// only a COMPLETE row is judged. A right row celebrates and advances; a wrong
// row plays `oops` + « Oh non ! On recommence. » and wipes back to the seeded
// slots. A filled, non-pre-revealed slot can be tapped to send its tile home.
//
// Deliberate asymmetries, ported as-is:
//   - NO MISS COOLDOWN. Pacing on a wrong row is `locked = true` for the full
//     duration of the awaited « Oh non » line; taps during it are silent
//     rejects. Do not add a cooldown "for symmetry" — this family already
//     spends more real time on a wrong row than the 800 ms window would buy,
//     and a cooldown on top would make a corrected row feel broken.
//   - The wrong-row reset IGNORES the say() result (it resets even on a cut
//     line); every ADVANCE path requires `ok == true`.
//   - Round 0 announces 350 ms after mount; every later round announces
//     IMMEDIATELY inside loadRound.
//   - The tile that completes a WRONG row still gets ACCEPT, so it does not
//     shake: the row-level oops is the feedback, and there is no per-tile
//     "wrong" in this family at all (invariant 3).

/**
 * `sameFace` from `SpellSyllableExercise.tsx`, verbatim: two faces are the same
 * tile iff they RENDER identically — glyph + script, with `base` excluded.
 *
 * Deliberately not `==` on [LetterFace] (a data class, which compares `base`
 * too). :core exposes no equivalent, so it lives with its only consumer.
 */
fun sameFace(a: LetterFace, b: LetterFace): Boolean =
    a.glyph == b.glyph && a.script == b.script

/**
 * The value a SpellSyllable tray tile picks with. The screen builds it, the
 * judge compares it with [sameFace]; it is here so all three call sites — the
 * screen, the tests and the economy suite — cannot drift.
 */
fun spellTileFace(tile: SpellLetterTile): LetterFace =
    LetterFace(base = tile.letter, glyph = tile.glyph, script = tile.script)

/**
 * Everything that distinguishes one assembly exercise from its siblings.
 *
 * `Item` = the session element, `Round` = the built round, `Slot` = what a slot
 * holds (String for syllables and letters, [LetterFace] for SpellSyllable).
 */
data class AssemblyDescriptor<Item, Round, Slot : Any>(
    val exercise: ExerciseId,
    val level: Int,
    val headline: String,
    val finishedTitle: String,
    val listenAccessibilityLabel: String,
    val buildSession: (RandomSource) -> List<Item>,
    val buildRound: (Item, RandomSource) -> Round,
    /**
     * Spoken 350 ms after mount (round 0) and immediately on every advance;
     * also what `replayPrompt()` speaks.
     */
    val promptLine: (Item) -> String,
    /** Spoken at rate 0.98 when the row judges right. */
    val successLine: (Round) -> String,
    /**
     * The slots as the round seeds them — fill-blank's pre-revealed syllables
     * stay filled, everything else is all-null. Also the wrong-row reset state.
     */
    val seededSlots: (Round) -> List<Slot?>,
    /** Pre-revealed (non-interactive) slots — Assemble fill-blank only. */
    val lockedSlots: (Round) -> List<Boolean>,
    /** Whole-row judgement over the filled values, in slot order. */
    val judge: (Round, List<Slot>) -> Boolean,
)

/**
 * The loop. See [SinglePickModel] for why `@Stable` is honest here.
 */
@Stable
class AssemblyModel<Item, Round, Slot : Any>(
    val descriptor: AssemblyDescriptor<Item, Round, Slot>,
    private val deps: EngineDeps,
    private val rng: RandomSource = SystemRandomSource(),
) : RoundRunner {

    /**
     * Seeded ONCE, here — build the model inside a `remember`, never in a
     * composable body (see RoundRunner.kt's header).
     */
    val session: List<Item> = descriptor.buildSession(rng)

    /**
     * Round 0, exactly like the TSX `useState` initialiser — BUILT here, not
     * announced (the announce is the 350 ms mount effect, in [activate]).
     *
     * An empty session is unreachable with the shipped pools: every assembly
     * ladder yields at least one round, and :core's level tests pin that.
     * Failing loudly beats a nullable `round` that nine screens would have to
     * unwrap forever.
     */
    private val roundHolder: MutableState<Round> = mutableStateOf(
        descriptor.buildRound(
            session.firstOrNull() ?: error("AssemblyModel requires a non-empty session"),
            rng,
        ),
    )

    private var idxState by mutableStateOf(0)
    private var slotsState: List<Slot?> by mutableStateOf(
        descriptor.seededSlots(roundHolder.value),
    )
    private var slotTileState: List<Int?> by mutableStateOf(List(slotsState.size) { null })
    private var usedState: Set<Int> by mutableStateOf(emptySet())
    private var lockedMaskState: List<Boolean> by mutableStateOf(
        descriptor.lockedSlots(roundHolder.value),
    )
    private var moodState by mutableStateOf(Mood.IDLE)
    private var doneState by mutableStateOf(false)
    private var earnedState by mutableStateOf(0)
    private val starList = mutableStateListOf<Boolean>().apply {
        repeat(session.size) { add(true) }
    }

    /**
     * TSX `locked` ref — true through the success line AND the « Oh non » line.
     * Tray tiles stay ENABLED during those lines (only `used` ones grey out),
     * which is why this guard is what carries the pacing (invariant 8).
     */
    private var locked = false
    private var mounted = true
    private var announceJob: Job? = null
    private var didScheduleFirstAnnounce = false

    // --- Projection surface --------------------------------------------------

    override val totalRounds: Int get() = session.size
    override val idx: Int get() = idxState
    override val stars: List<Boolean> get() = starList
    override val mood: Mood get() = moodState
    override val done: Boolean get() = doneState
    override val earned: Int get() = earnedState
    override val headline: String get() = descriptor.headline
    override val finishedTitle: String get() = descriptor.finishedTitle
    override val listenAccessibilityLabel: String get() = descriptor.listenAccessibilityLabel

    /** The current session item — the word or sound the round is built from. */
    val current: Item get() = session[idxState]

    /** The built round: tray, cells, target. */
    val round: Round get() = roundHolder.value

    /** One entry per slot; null = still empty. Fill-blank seeds some entries. */
    val slots: List<Slot?> get() = slotsState

    /**
     * Which tray tile fills each slot (null = empty or pre-revealed) — the undo
     * path's memory.
     */
    val slotTile: List<Int?> get() = slotTileState

    /** Dropped tray tile ids — those tiles grey out until removed. */
    val used: Set<Int> get() = usedState

    /** Pre-revealed slot mask for the current round. */
    val lockedMask: List<Boolean> get() = lockedMaskState

    /** Tray tile greyed ⟺ dropped in a slot (`disabled={used.has(t.id)}`). */
    fun isTrayTileUsed(id: Int): Boolean = usedState.contains(id)

    /** A filled slot the child may tap back out — filled AND not pre-revealed. */
    fun isSlotRemovable(i: Int): Boolean {
        if (i !in slotsState.indices) return false
        return slotsState[i] != null && !lockedMaskState[i]
    }

    // --- Lifecycle -----------------------------------------------------------

    override fun activate() {
        mounted = true
        deps.audio.unlock()
        // The TSX announce effect has `[]` deps: round 0 only, once per mount.
        if (didScheduleFirstAnnounce || doneState) return
        didScheduleFirstAnnounce = true
        val line = descriptor.promptLine(session[0])
        announceJob = deps.scope.launch {
            deps.delay(EngineLines.ANNOUNCE_DELAY_MS)
            if (!isActive || !mounted) return@launch
            deps.audio.say(line)
        }
    }

    override fun deactivate() {
        announceJob?.cancel()
        announceJob = null
        mounted = false
        deps.audio.stop()
    }

    // --- The pick handler (synchronous — invariant 1) ------------------------

    /**
     * Drop a tray tile into the next open slot. No per-tap judgement — only a
     * complete row is judged.
     */
    fun pick(tileId: Int, value: Slot): Verdict {
        if (doneState) return Verdict.REJECT // unreachable from a correct screen
        if (locked) return Verdict.REJECT
        deps.audio.unlock()
        deps.audio.pop()

        val nextEmpty = slotsState.indexOfFirst { it == null }
        // Faithful ordering: the unlock and the pop fire BEFORE this check, so a
        // tap on a full row still sounds like a tap.
        if (nextEmpty < 0) return Verdict.REJECT
        slotsState = slotsState.toMutableList().also { it[nextEmpty] = value }
        slotTileState = slotTileState.toMutableList().also { it[nextEmpty] = tileId }
        usedState = usedState + tileId

        if (slotsState.any { it == null }) return Verdict.ACCEPT

        // Row complete — judge the whole order at once.
        val filled = slotsState.filterNotNull()
        if (descriptor.judge(roundHolder.value, filled)) {
            locked = true
            // [DEVIATION, authorised — iOS D45] Stale prompt; the identical line
            // is in `SinglePickModel.pick`. The row is complete and correct;
            // announcing the task now would cut the success line short and
            // strand the round with `locked` still true.
            announceJob?.cancel()
            announceJob = null
            moodState = Mood.HAPPY
            deps.audio.success()
            deps.fireConfetti()
            // Advance only after the success line played in full (`ok`).
            val nextIdx = idxState + 1
            val line = descriptor.successLine(roundHolder.value)
            deps.scope.launch {
                val ok = deps.audio.say(line, EngineLines.SUCCESS_RATE, EngineLines.DEFAULT_PITCH)
                if (!ok || !mounted) return@launch
                if (nextIdx >= session.size) {
                    moodState = Mood.CHEER
                    earnedState = deps.award(
                        descriptor.exercise,
                        descriptor.level,
                        StarStrip.kept(starList),
                        session.size,
                    )
                    doneState = true
                    deps.scope.launch { deps.audio.say(EngineLines.BRAVO_SUCCEEDED) }
                } else {
                    moodState = Mood.IDLE
                    idxState = nextIdx
                    loadRound(session[nextIdx])
                }
            }
        } else {
            // Wrong row: « Oh non », then — once it has finished speaking — wipe
            // the tray tiles back out. The reset is NOT gated on the line's
            // result (ported as-is); pre-revealed slots stay put.
            locked = true
            deps.audio.oops()
            StarStrip.miss(idxState, starList) // the star greys NOW, same beat as the "Oh non"
            deps.scope.launch {
                deps.audio.say(EngineLines.OH_NON)
                if (!mounted) return@launch
                val seeded = descriptor.seededSlots(roundHolder.value)
                slotsState = seeded
                slotTileState = List(seeded.size) { null }
                usedState = emptySet()
                locked = false
            }
        }
        return Verdict.ACCEPT
    }

    /**
     * Tap a filled slot to send its tile back to the tray. Pre-revealed
     * (fill-blank) slots ignore it, and so does everything while a line plays.
     */
    fun removeAt(i: Int) {
        if (doneState || locked) return
        if (i !in lockedMaskState.indices || lockedMaskState[i]) return
        val tileId = slotTileState[i] ?: return
        deps.audio.pop()
        slotsState = slotsState.toMutableList().also { it[i] = null }
        slotTileState = slotTileState.toMutableList().also { it[i] = null }
        usedState = usedState - tileId
    }

    // --- Audio affordances ---------------------------------------------------

    /** The big 🔊 button — locked-guarded, no unlock (the TSX shape). */
    override fun replayPrompt() {
        if (locked || doneState) return
        val line = descriptor.promptLine(session[idxState])
        deps.scope.launch { deps.audio.say(line) }
    }

    /**
     * A tray tile's « Écouter ». All three assembly previews are locked-guarded
     * in the TSX — unlike four of the five single-pick ones.
     */
    fun preview(text: String) {
        if (locked) return
        deps.audio.unlock()
        deps.scope.launch { deps.audio.say(text) }
    }

    // --- Advance -------------------------------------------------------------

    /**
     * Fresh round + IMMEDIATE announce (`void audio.say(...)` in the TSX
     * `loadRound` — no 350 ms delay after round 0).
     */
    private fun loadRound(item: Item) {
        val next = descriptor.buildRound(item, rng)
        roundHolder.value = next
        val seeded = descriptor.seededSlots(next)
        slotsState = seeded
        slotTileState = List(seeded.size) { null }
        lockedMaskState = descriptor.lockedSlots(next)
        usedState = emptySet()
        locked = false
        val line = descriptor.promptLine(item)
        deps.scope.launch { deps.audio.say(line) }
    }

    // --- The three concrete engines (1:1 with the TSX files) -----------------

    companion object {

        /**
         * `AssembleExercise.tsx` — ONE engine for the three [SyllableMode]s. The
         * mode reaches only `buildSyllableRound` and the headline; the assembly
         * loop is mode-agnostic (CLAUDE.md).
         */
        fun assemble(
            exercise: ExerciseId,
            mode: SyllableMode,
            level: Int,
            deps: EngineDeps,
            rng: RandomSource = SystemRandomSource(),
        ): AssemblyModel<SyllableWord, SyllableRound, String> {
            val tier = syllableTier(level)
            return AssemblyModel(
                descriptor = AssemblyDescriptor(
                    exercise = exercise,
                    level = level,
                    headline = MODE_HINT[mode] ?: "",
                    finishedTitle = Copy.Finished.ALL_SUCCEEDED,
                    listenAccessibilityLabel = Copy.Exercise.REPEAT_WORD,
                    buildSession = {
                        repeatSession(syllablePool(tier), tier.pick, tier.repeats, it)
                    },
                    buildRound = { word, r -> buildSyllableRound(word, mode, r) },
                    promptLine = { it.word },
                    successLine = { "Oui ! ${it.word.word}." },
                    seededSlots = { it.slots },
                    lockedSlots = { it.locked },
                    judge = { round, filled -> filled == round.word.syllables },
                ),
                deps = deps,
                rng = rng,
            )
        }

        /** `SpellSoundExercise.tsx` — hear a sound, spell it letter by letter. */
        fun spellSound(
            level: Int,
            deps: EngineDeps,
            rng: RandomSource = SystemRandomSource(),
        ): AssemblyModel<SoundTarget, SoundRound, String> {
            val cfg = soundLevel(level)
            return AssemblyModel(
                descriptor = AssemblyDescriptor(
                    exercise = ExerciseId.SPELL_SOUND,
                    level = level,
                    headline = Copy.Exercise.SPELL_SOUND_HEADLINE,
                    finishedTitle = Copy.Finished.ALL_SUCCEEDED,
                    listenAccessibilityLabel = Copy.Exercise.REPLAY_SOUND,
                    buildSession = { buildSoundSession(level, it) },
                    buildRound = { target, r -> buildSoundRound(target, cfg.distractors, r) },
                    promptLine = { soundPrompt(it) },
                    successLine = { soundSuccess(it.target) },
                    seededSlots = { it.slots },
                    lockedSlots = { round -> round.slots.map { false } },
                    judge = { round, filled -> filled == round.target.spelling },
                ),
                deps = deps,
                rng = rng,
            )
        }

        /**
         * `SpellSyllableExercise.tsx` — part of the word is written; the gap is
         * spelled into per-letter slots.
         *
         * The « écritures mêlées » twins judge the FACE (glyph + script, via
         * [sameFace]), so a right letter in the wrong writing fails the row.
         * Plain rounds are all-uppercase print, where face equality degrades to
         * letter equality on its own — one judge, two games.
         */
        // NB `mixed` carries no default, unlike the Swift factory: it is real
        // data off the catalog row (`meta.mixed`), not an option, and a default
        // would let a mixed row be built plain by omission — which compiles,
        // runs, and quietly plays a different game.
        fun spellSyllable(
            exercise: ExerciseId,
            mode: SpellSyllableMode,
            level: Int,
            mixed: Boolean,
            deps: EngineDeps,
            rng: RandomSource = SystemRandomSource(),
        ): AssemblyModel<SyllableWord, SpellSyllableRound, LetterFace> {
            val cfg = spellSyllableLevel(level)
            return AssemblyModel(
                descriptor = AssemblyDescriptor(
                    exercise = exercise,
                    level = level,
                    headline = if (mixed) {
                        Copy.Exercise.spellHeadlineMixed(mode)
                    } else {
                        Copy.Exercise.spellHeadline(mode)
                    },
                    finishedTitle = Copy.Finished.ALL_SUCCEEDED,
                    listenAccessibilityLabel = Copy.Exercise.REPLAY_WORD,
                    buildSession = { buildSpellSyllableSession(level, it) },
                    buildRound = { word, r ->
                        buildSpellSyllableRound(word, mode, cfg.distractors, mixed, r)
                    },
                    promptLine = { it.word },
                    successLine = { "Oui ! ${it.word.word}." },
                    seededSlots = { round -> List(round.answerFaces.size) { null } },
                    lockedSlots = { round -> round.answerFaces.map { false } },
                    judge = { round, filled ->
                        filled.size == round.answerFaces.size &&
                            filled.zip(round.answerFaces).all { sameFace(it.first, it.second) }
                    },
                ),
                deps = deps,
                rng = rng,
            )
        }
    }
}
