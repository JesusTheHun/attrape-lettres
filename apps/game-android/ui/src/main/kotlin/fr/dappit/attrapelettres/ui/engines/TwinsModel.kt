package fr.dappit.attrapelettres.ui.engines

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.TwinGraphy
import fr.dappit.attrapelettres.core.domain.TwinRound
import fr.dappit.attrapelettres.core.domain.TwinTile
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.buildTwinSession
import fr.dappit.attrapelettres.core.levels.twinPrompt
import fr.dappit.attrapelettres.core.levels.twinSuccess
import fr.dappit.attrapelettres.core.support.RandomSource
import fr.dappit.attrapelettres.core.support.SystemRandomSource
import fr.dappit.attrapelettres.ui.components.MissCooldown
import fr.dappit.attrapelettres.ui.components.StarStrip
import fr.dappit.attrapelettres.ui.design.Copy
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// The multi-select twins engine — `SoundTwinsExercise.tsx`.
//
// Hear one sound (« Trouve tous les ko ! ») and tap EVERY tile that writes it.
// A correct PARTIAL tap locks its tile into the collection strip and speaks its
// anchor word FIRE-AND-FORGET — the next tap can land while that line plays,
// because a child who has spotted two twins should not have to wait to say so.
// The COMPLETING tap locks the round and awaits its line before advancing.
//
// An intruder tap follows the single-pick miss rules exactly: nudge + cooldown
// + the round's star greys at pointer-down. Partial finds never grey the star
// and never touch the strip — only intruders do (invariants 3 and 8).

/**
 * The loop. See [SinglePickModel] for why `@Stable` is honest here.
 *
 * The exercise id is fixed rather than injected: `SOUND_TWINS` is the only
 * catalog row this engine serves, and hard-coding it keeps the award call —
 * the one place a wrong id would silently move a child's stars to another
 * ledger key — impossible to get wrong from a screen.
 */
@Stable
class TwinsModel(
    val level: Int,
    private val deps: EngineDeps,
    rng: RandomSource = SystemRandomSource(),
) : RoundRunner {

    /**
     * Seeded ONCE, here — build the model inside a `remember`, never in a
     * composable body (see RoundRunner.kt's header).
     */
    val session: List<TwinRound> = buildTwinSession(level, rng)

    private var idxState by mutableStateOf(0)
    private var foundState: List<Int> by mutableStateOf(emptyList())
    private var moodState by mutableStateOf(Mood.IDLE)
    private var doneState by mutableStateOf(session.isEmpty())
    private var earnedState by mutableStateOf(0)
    private val starList = mutableStateListOf<Boolean>().apply {
        repeat(session.size) { add(true) }
    }

    private var locked = false
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

    /** U+0027 apostrophe + U+2014 em dash — never normalised. */
    override val headline: String get() = Copy.Exercise.SOUND_TWINS_HEADLINE
    override val finishedTitle: String get() = Copy.Finished.ALL_FOUND
    override val listenAccessibilityLabel: String get() = Copy.Exercise.REPLAY_SOUND

    val round: TwinRound get() = session[idxState]

    /** This round's found tile ids, in tap order — fills the collection strip. */
    val found: List<Int> get() = foundState

    /** The twins to find — one collection-strip slot each. */
    val targets: List<TwinTile> get() = round.tiles.filter { it.correct }

    /** Every target found: the round is celebrating (or about to advance). */
    val complete: Boolean
        get() {
            val ids = foundState
            return targets.all { ids.contains(it.id) }
        }

    /**
     * A found tile locks; a COMPLETE round locks the rest too, so nothing
     * shakes while the celebration line plays out.
     */
    fun tileDisabled(tile: TwinTile): Boolean = foundState.contains(tile.id) || complete

    /** Found tiles wear the green ring. */
    fun tileHighlighted(tile: TwinTile): Boolean = foundState.contains(tile.id)

    /** Collection strip slot `i`: the i-th found tile, or null (a dashed box). */
    fun foundTile(i: Int): TwinTile? {
        val ids = foundState
        if (i !in ids.indices) return null
        return round.tiles.firstOrNull { it.id == ids[i] }
    }

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

    fun pick(tile: TwinTile): Verdict {
        if (doneState) return Verdict.REJECT // unreachable from a correct screen
        if (locked) return Verdict.REJECT
        if (cooldown.isSwallowing) return Verdict.REJECT // silent while the shake plays
        deps.audio.unlock()
        deps.audio.pop()
        if (!tile.correct) {
            deps.audio.nudge()
            cooldown.registerMiss()
            StarStrip.miss(idxState, starList) // the star greys NOW, same beat as the shake
            return Verdict.REJECT
        }
        foundState = foundState + tile.id
        moodState = Mood.HAPPY
        // The anchor word IS the feedback, per graphy.
        val line = twinSuccess(TwinGraphy(text = tile.text, word = tile.word, emoji = tile.emoji))
        if (!complete) {
            // A twin found, more to go — fire-and-forget; the next tap can land
            // while this line plays (`say` is latest-wins).
            deps.scope.launch {
                deps.audio.say(line, EngineLines.SUCCESS_RATE, EngineLines.DEFAULT_PITCH)
            }
            return Verdict.ACCEPT
        }
        locked = true
        // [DEVIATION, authorised — iOS D45] Stale prompt; the identical line is
        // in `SinglePickModel.pick`. The set is complete; announcing the task
        // now would cut the success line short and strand the round.
        announceJob?.cancel()
        announceJob = null
        deps.audio.success()
        deps.fireConfetti()
        // Advance only after the LAST success line has played in full (`ok`).
        val next = idxState + 1
        deps.scope.launch {
            val ok = deps.audio.say(line, EngineLines.SUCCESS_RATE, EngineLines.DEFAULT_PITCH)
            if (!ok || !mounted) return@launch
            locked = false
            if (next >= session.size) {
                announceJob?.cancel()
                announceJob = null
                moodState = Mood.CHEER
                earnedState = deps.award(
                    ExerciseId.SOUND_TWINS,
                    level,
                    StarStrip.kept(starList),
                    session.size,
                )
                doneState = true
                deps.scope.launch { deps.audio.say(EngineLines.BRAVO_FOUND) }
            } else {
                moodState = Mood.IDLE
                foundState = emptyList()
                idxState = next
                scheduleAnnounce()
            }
        }
        return Verdict.ACCEPT
    }

    // --- Audio affordances ---------------------------------------------------

    /** The big 🔊 button — locked-guarded, no unlock (the TSX shape). */
    override fun replayPrompt() {
        if (locked || doneState || session.isEmpty()) return
        val line = twinPrompt(session[idxState].family)
        deps.scope.launch { deps.audio.say(line) }
    }

    /**
     * A tile's « Écouter » — speaks the tile's own family's sound
     * (`tile.sound`); locked-guarded in the TSX.
     */
    fun preview(text: String) {
        if (locked) return
        deps.audio.unlock()
        deps.scope.launch { deps.audio.say(text) }
    }

    // --- Announce (350 ms after every round becomes current) -----------------

    private fun scheduleAnnounce() {
        announceJob?.cancel()
        announceJob = null
        if (doneState || session.isEmpty()) return
        val expected = idxState
        val line = twinPrompt(session[expected].family)
        announceJob = deps.scope.launch {
            deps.delay(EngineLines.ANNOUNCE_DELAY_MS)
            if (!isActive || !mounted || doneState || idxState != expected) return@launch
            deps.audio.say(line)
        }
    }
}
