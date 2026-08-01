package fr.dappit.attrapelettres.ui.engines

import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.BasicSound
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.FindSoundRound
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.TwinGraphy
import fr.dappit.attrapelettres.core.domain.TwinTile
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.buildFindSoundSession
import fr.dappit.attrapelettres.core.levels.buildTwinSession
import fr.dappit.attrapelettres.core.levels.findSoundPrompt
import fr.dappit.attrapelettres.core.levels.findSoundSuccess
import fr.dappit.attrapelettres.core.levels.twinPrompt
import fr.dappit.attrapelettres.core.levels.twinSuccess
import fr.dappit.attrapelettres.core.platform.FixedReduceMotion
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.ui.components.ConfettiSystem
import fr.dappit.attrapelettres.ui.components.TileMetrics
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.Palette
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The two sound-ladder VIEWS: `FindSoundView` and `SoundTwinsView`, plus the
// chrome they share with `SyllableGridView`.
//
// What is asserted here is what the VIEWS own — the palettes, the authored
// clamps, the shown/judged/spoken split per tile, the accessibility labels, the
// strip geometry — and, through the real models, the round rules those views
// project. The loop's internals (the cooldown arithmetic, the award call, the
// announce race) belong to `SinglePickModelTest` / `TwinsModelTest` and are not
// re-stated; what IS re-stated is the wiring a screen could get wrong: the key
// a tile picks with, the text a tile speaks, and that a miss stays harmless.
//
// Every expected number and string was read out of
// `src/exercises/FindSoundExercise.tsx` and `src/exercises/SoundTwinsExercise.tsx`
// — never back out of the Kotlin, and never out of the Swift.
//
// No composition is built anywhere in this file (A11): the metrics are plain
// values and the models are plain classes.

// One predicate for the whole package — `EngineTestSupport.near`. The argument
// order here is (expected, actual), the reverse of `near`'s, which is why this
// stays a named wrapper instead of being inlined at 12 call sites.
private fun assertNear(expected: Float, actual: Float, message: String? = null) {
    near(actual = actual, expected = expected, what = message ?: "value")
}

private fun sound(sound: String, graphy: String, word: String, emoji: String) =
    BasicSound(sound = sound, graphy = graphy, word = word, emoji = emoji)

private fun twin(text: String, sound: String, word: String, correct: Boolean) =
    TwinTile(id = 1, text = text, sound = sound, word = word, emoji = "🐓", correct = correct)

// ---------------------------------------------------------------------------
// Find the sound
// ---------------------------------------------------------------------------

private fun findSound(
    seed: Long = 11,
    level: Int = 2,
): Pair<EngineHarness, SinglePickModel<FindSoundRound>> {
    val harness = EngineHarness()
    return harness to SinglePickModel.findSound(
        level = level,
        deps = harness.deps,
        rng = SeededGenerator(seed),
    )
}

class FindSoundViewTest {

    /**
     * ```tsx
     * const TILE_COLORS = [
     *   { bg: "#FF8A65", ink: "#4A2317" },
     *   { bg: "#FFD54F", ink: "#4A3B00" },
     *   { bg: "#4FC3F7", ink: "#062E3D" },
     * ];
     * ```
     * Three paints, indexed `i % 3`.
     */
    @Test
    fun `the palette is the authored three paints, cycled`() {
        assertEquals(3, FindSoundMetrics.TILE_COLOR_COUNT)
        assertEquals("#FF8A65", FindSoundMetrics.paint(0).bg.hex)
        assertEquals("#4A2317", FindSoundMetrics.paint(0).ink.hex)
        assertEquals("#FFD54F", FindSoundMetrics.paint(1).bg.hex)
        assertEquals("#4A3B00", FindSoundMetrics.paint(1).ink.hex)
        assertEquals("#4FC3F7", FindSoundMetrics.paint(2).bg.hex)
        assertEquals("#062E3D", FindSoundMetrics.paint(2).ink.hex)
        // The cycle is 3, not the shared ramp's 5: a fourth tile is coral again,
        // never the ramp's green.
        assertEquals("#FF8A65", FindSoundMetrics.paint(3).bg.hex)
        assertEquals("#FFD54F", FindSoundMetrics.paint(4).bg.hex)
        assertNotEquals(Palette.tileColors[3].bg.hex, FindSoundMetrics.paint(3).bg.hex)
    }

    /**
     * The tile SHOWS « OU », is JUDGED by « OU », and SPEAKS « ou ». The graphy
     * and the sound are different strings with different baked clips.
     */
    @Test
    fun `a tile shows the graphy and speaks the sound`() {
        val ou = sound("ou", "OU", "hibou", "🦉")
        assertEquals("OU", FindSoundMetrics.tileFace(ou))
        assertEquals("OU", FindSoundMetrics.pickKey(ou))
        assertEquals("ou", FindSoundMetrics.previewText(ou))
        assertNotEquals(FindSoundMetrics.tileFace(ou), FindSoundMetrics.previewText(ou))
    }

    /**
     * ``ariaLabel={`Son ${choice.graphy}`}`` / ``previewLabel={`Écouter …`}`` —
     * « Son », where the two syllable drills say « Syllabe » (invariant 6).
     */
    @Test
    fun `the accessibility labels use the sound wording`() {
        val ch = sound("che", "CH", "chat", "🐱")
        assertEquals("Son CH", FindSoundMetrics.tileLabel(ch))
        assertEquals("Écouter CH", FindSoundMetrics.previewLabel(ch))
    }

    /**
     * `<WordIcon size="clamp(80px,28vw,150px)" />`, `margin: "6px 0"`, `mb-6`
     * on the button, `gap-4` between tiles.
     */
    @Test
    fun `the authored metrics are the TSX's`() {
        assertEquals(FluidSpec(80f, 28f, 150f), FindSoundMetrics.WORD_ICON_SIZE)
        assertNear(109.2f, FindSoundMetrics.WORD_ICON_SIZE.resolve(390f))
        assertNear(80f, FindSoundMetrics.WORD_ICON_SIZE.resolve(200f))
        assertNear(150f, FindSoundMetrics.WORD_ICON_SIZE.resolve(1024f))
        assertEquals(6.dp, FindSoundMetrics.WORD_ICON_MARGIN_Y)
        assertEquals(24.dp, FindSoundMetrics.LISTEN_BOTTOM)
        assertEquals(16.dp, FindSoundMetrics.TILE_GAP)
    }

    /**
     * The TSX passes no `size`, so find-sound tiles keep `Tile`'s default —
     * the `clamp(92px,27vw,150px)` that IS invariant 6's floor.
     */
    @Test
    fun `find-sound tiles keep Tile's own 92dp floor`() {
        assertEquals(92f, TileMetrics.DEFAULT_SIZE.min)
        assertNear(92f, TileMetrics.DEFAULT_SIZE.resolve(320f))
    }

    /** A real round wired the way the view wires it: one paint per choice, in
     * row order, cycling at three. */
    @Test
    fun `a real round gets its paints in row order`() {
        val session = buildFindSoundSession(2, SeededGenerator(11))
        val round = session.first()
        val slots = SoundEngineChrome.slots(round.choices) { it.graphy }
        assertEquals(round.choices.indices.toList(), slots.map { it.index })
        assertEquals(round.choices.map { it.graphy }, slots.map { it.id })
        for (slot in slots) {
            assertEquals(
                Palette.tileColors[slot.index % 3].bg.hex,
                FindSoundMetrics.paint(slot.index).bg.hex,
            )
        }
    }

    /** The consigne above the mascot, verbatim from the TSX `<p>`. */
    @Test
    fun `the consigne is the authored French line`() {
        val (_, model) = findSound()
        assertEquals("Écoute le son et trouve comment il s'écrit", model.headline)
        assertEquals("Réécouter le son", model.listenAccessibilityLabel)
        assertEquals("🔊 Écouter", model.listenText)
        assertEquals("Tu as tout trouvé !", model.finishedTitle)
    }

    /**
     * The spoken lines the view never builds: the prompt is « ou, comme dans
     * hibou. » and the success line is « Oui ! hibou. ». `replayPrompt()` and
     * the 350 ms announce speak the same string.
     */
    @Test
    fun `the prompt and the success line come from the level builders`() {
        val (harness, model) = findSound()
        val target = model.current.target

        model.activate()
        harness.settle()
        assertEquals(listOf(350L), harness.delays.requests)
        assertEquals(listOf(findSoundPrompt(target)), harness.audio.sayTexts)
        assertEquals("${target.sound}, comme dans ${target.word}.", findSoundPrompt(target))

        harness.audio.clearEvents()
        model.replayPrompt()
        harness.pump()
        assertEquals(listOf(findSoundPrompt(target)), harness.audio.sayTexts)

        harness.audio.clearEvents()
        // Hold the NEXT round's announce, so what follows is only the pick's own
        // line and not round 1's prompt riding in behind it.
        harness.delays.holdDelays()
        assertEquals(Verdict.ACCEPT, model.pick(FindSoundMetrics.pickKey(target)))
        harness.pump()
        assertEquals(listOf(findSoundSuccess(target)), harness.audio.sayTexts)
        assertEquals("Oui ! ${target.word}.", findSoundSuccess(target))
        harness.settle()
    }

    /**
     * A correct tap flashes ITS OWN key and disables every tile for the
     * celebration; the round only advances once the line has played.
     */
    @Test
    fun `a correct tap flashes the picked key and freezes the row`() {
        val (harness, model) = findSound()
        val target = model.current.target
        harness.audio.holdSays()

        assertEquals(Verdict.ACCEPT, model.pick(FindSoundMetrics.pickKey(target)))
        // Everything visible happened before pick() returned — invariant 1.
        assertEquals(target.graphy, model.flash)
        assertTrue(model.tilesDisabled)
        assertEquals(Mood.HAPPY, model.mood)
        assertEquals(1, harness.confetti.count)
        assertEquals(0, model.idx, "the advance waits for the success line")

        harness.settle()
        assertEquals(1, model.idx)
        assertNull(model.flash)
        assertFalse(model.tilesDisabled)
        assertTrue(model.stars[0], "a clean round keeps its star")
    }

    /**
     * Invariant 3: a wrong tap shakes, nudges and greys the round's star — and
     * that is ALL. No lock, no lost round, no mood change, no route change, and
     * the same tile is still tappable once the swallow window passes.
     */
    @Test
    fun `a wrong tap has no terminal effect`() {
        val (harness, model) = findSound()
        val round = model.current
        val intruder = round.choices.first { it.graphy != round.target.graphy }

        assertEquals(Verdict.REJECT, model.pick(FindSoundMetrics.pickKey(intruder)))
        assertFalse(model.stars[0], "the star greys at pointer-down")
        assertEquals(Mood.IDLE, model.mood, "a miss never changes the mascot")
        assertEquals(0, model.idx)
        assertFalse(model.done)
        assertNull(model.flash)
        assertFalse(model.tilesDisabled, "nothing is locked by a miss")
        assertEquals(0, harness.confetti.count)

        // Spam inside the cooldown is swallowed silently, then the round is
        // still winnable — the star is simply already spent.
        assertEquals(Verdict.REJECT, model.pick(FindSoundMetrics.pickKey(round.target)))
        harness.time.advance(800L)
        assertEquals(Verdict.ACCEPT, model.pick(FindSoundMetrics.pickKey(round.target)))
        harness.settle()
        assertEquals(1, model.idx)
        assertFalse(model.stars[0])
    }

    /** Every tile's « Écouter » speaks that tile's own sound, not its graphy. */
    @Test
    fun `each tile auditions its own sound`() {
        val (harness, model) = findSound()
        val choices = model.current.choices
        for (choice in choices) {
            model.preview(FindSoundMetrics.previewText(choice))
        }
        harness.pump()
        assertEquals(choices.map { it.sound }, harness.audio.sayTexts)
        assertTrue(harness.audio.sayTexts.none { it in choices.map { c -> c.graphy } })
    }

    /** Invariant 5: a level is built from its number and nothing else. */
    @Test
    fun `every level builds a runnable session`() {
        for (level in 1..4) {
            val harness = EngineHarness()
            val model = SinglePickModel.findSound(level, harness.deps, SeededGenerator(3))
            assertTrue(model.totalRounds > 0, "level $level has rounds")
            assertTrue(
                model.current.choices.any { it.graphy == model.current.target.graphy },
                "level $level shows the answer",
            )
            assertTrue(
                model.current.choices.size <= FindSoundMetrics.TILE_COLOR_COUNT,
                "level $level never asks for a fourth paint",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sound twins
// ---------------------------------------------------------------------------

private fun soundTwins(seed: Long = 42, level: Int = 1): Pair<EngineHarness, TwinsModel> {
    val harness = EngineHarness()
    return harness to TwinsModel(
        level = level,
        deps = harness.deps,
        rng = SeededGenerator(seed),
    )
}

class SoundTwinsViewTest {

    /**
     * ```tsx
     * const TILE_COLORS = [
     *   { bg: "#4FC3F7", ink: "#062E3D" },
     *   { bg: "#AED581", ink: "#213606" },
     *   { bg: "#FFD54F", ink: "#4A3B00" },
     *   { bg: "#BA9EE8", ink: "#2C1846" },
     *   { bg: "#FF8A65", ink: "#4A2317" },
     * ];
     * ```
     * The TRAY rotation — blue first. A twins tile at index 0 is therefore a
     * different colour from a find-sound tile at index 0.
     */
    @Test
    fun `the palette is the tray ramp, blue first`() {
        assertEquals(5, SoundTwinsMetrics.TILE_COLOR_COUNT)
        assertEquals("#4FC3F7", SoundTwinsMetrics.paint(0).bg.hex)
        assertEquals("#062E3D", SoundTwinsMetrics.paint(0).ink.hex)
        assertEquals("#AED581", SoundTwinsMetrics.paint(1).bg.hex)
        assertEquals("#FFD54F", SoundTwinsMetrics.paint(2).bg.hex)
        assertEquals("#BA9EE8", SoundTwinsMetrics.paint(3).bg.hex)
        assertEquals("#FF8A65", SoundTwinsMetrics.paint(4).bg.hex)
        assertEquals("#4FC3F7", SoundTwinsMetrics.paint(5).bg.hex)
        assertNotEquals(FindSoundMetrics.paint(0).bg.hex, SoundTwinsMetrics.paint(0).bg.hex)
    }

    /**
     * Each tile auditions ITS OWN family's sound — the intruder included. If a
     * tile spoke the round's target instead, every tile would sound right and
     * the exercise would be unplayable by ear.
     */
    @Test
    fun `every tile auditions its own sound`() {
        val co = twin("CO", "ko", "coq", correct = true)
        val so = twin("SO", "so", "sol", correct = false)
        assertEquals("ko", SoundTwinsMetrics.previewText(co))
        assertEquals("so", SoundTwinsMetrics.previewText(so))
        assertEquals("SO", SoundTwinsMetrics.tileFace(so))
        assertEquals("Syllabe SO", SoundTwinsMetrics.tileLabel(so))
        assertEquals("Écouter SO", SoundTwinsMetrics.previewLabel(so))
    }

    /**
     * `mt-2 mb-4` on the button and a hard-coded « 🔊 Écouter » — [TwinsModel]
     * exposes no `listenText` because only the single-pick family varies it.
     */
    @Test
    fun `the listen button's text and spacing are authored here`() {
        assertEquals("🔊 Écouter", SoundTwinsMetrics.LISTEN_TEXT)
        assertEquals(8.dp, SoundTwinsMetrics.LISTEN_TOP)
        assertEquals(16.dp, SoundTwinsMetrics.LISTEN_BOTTOM)
    }

    /**
     * The strip slot: `minWidth: clamp(56px,16vw,84px)`,
     * `height: clamp(48px,13vw,64px)`, `fontSize: clamp(20px,5.5vw,32px)`,
     * `padding: "0 10px"`, `borderRadius: 18`, `gap-1`, `3px dashed`, and the
     * anchor emoji at `0.8em`.
     */
    @Test
    fun `the authored strip metrics are the TSX's`() {
        assertEquals(FluidSpec(56f, 16f, 84f), SoundTwinsMetrics.SLOT_MIN_WIDTH)
        assertEquals(FluidSpec(48f, 13f, 64f), SoundTwinsMetrics.SLOT_HEIGHT)
        assertEquals(FluidSpec(20f, 5.5f, 32f), SoundTwinsMetrics.SLOT_FONT_SIZE)
        assertNear(62.4f, SoundTwinsMetrics.SLOT_MIN_WIDTH.resolve(390f))
        assertNear(50.7f, SoundTwinsMetrics.SLOT_HEIGHT.resolve(390f))
        assertNear(21.45f, SoundTwinsMetrics.SLOT_FONT_SIZE.resolve(390f))
        assertEquals(10.dp, SoundTwinsMetrics.SLOT_PADDING_X)
        assertEquals(18.dp, SoundTwinsMetrics.SLOT_CORNER_RADIUS)
        assertEquals(4.dp, SoundTwinsMetrics.SLOT_INNER_GAP)
        assertEquals(3.dp, SoundTwinsMetrics.SLOT_BORDER_WIDTH)
        assertEquals(24.dp, SoundTwinsMetrics.STRIP_BOTTOM)
        assertEquals(8.dp, SoundTwinsMetrics.STRIP_GAP)
        assertEquals(0.8f, SoundTwinsMetrics.SLOT_EMOJI_EM)
        assertNear(25.6f, SoundTwinsMetrics.slotEmojiFontSize(32f))
        assertNear(16f, SoundTwinsMetrics.slotEmojiFontSize(20f))
        // `boxShadow: "0 6px 14px rgba(0,0,0,0.12)"` on a filled slot; a dashed
        // `#E4A15E` outline and no shadow while it is empty.
        assertEquals(6.dp, SoundTwinsMetrics.SLOT_SHADOW.y)
        assertEquals(14.dp, SoundTwinsMetrics.SLOT_SHADOW.blur)
        assertEquals(0.12f, SoundTwinsMetrics.SLOT_SHADOW.opacity)
        assertEquals("#E4A15E", Palette.slotDashed.hex)
    }

    @Test
    fun `the authored tile metrics are the TSX's`() {
        assertEquals(12.dp, SoundTwinsMetrics.TILE_GAP)
        assertEquals(FluidSpec(60f, 17f, 92f), SoundTwinsMetrics.TILE_SIZE)
        assertEquals(FluidSpec(24f, 6.5f, 44f), SoundTwinsMetrics.TILE_FONT_SIZE)
        assertNear(66.3f, SoundTwinsMetrics.TILE_SIZE.resolve(390f))
        assertNear(25.35f, SoundTwinsMetrics.TILE_FONT_SIZE.resolve(390f))
    }

    /**
     * Invariant 6: twins shrink their tiles below `Tile`'s default, so the
     * authored minimum is what has to clear the platform's 44 dp tap target —
     * on every phone this app runs on.
     */
    @Test
    fun `a twins tile clears the tap-target floor at every viewport`() {
        for (viewport in listOf(320f, 375f, 390f, 430f, 1024f)) {
            assertTrue(
                SoundTwinsMetrics.TILE_SIZE.resolve(viewport) >=
                    TileMetrics.PLATFORM_MINIMUM_TAP_TARGET.value,
                "a $viewport dp phone still gets a finger-sized tile",
            )
            assertTrue(TileMetrics.PREVIEW_HEIGHT.resolve(viewport) >= 40f)
        }
        // The authored minimum itself, so a future edit that lowers it fails
        // here and not in a pixel diff.
        assertEquals(60f, SoundTwinsMetrics.TILE_SIZE.min)
    }

    /**
     * A real round: the strip has one slot per CORRECT tile, and the intruders
     * get no slot however many there are.
     */
    @Test
    fun `the strip has one slot per twin to find`() {
        val session = buildTwinSession(4, SeededGenerator(3))
        val round = session.first()
        val targets = round.tiles.filter { it.correct }
        assertEquals(round.family.graphies.size, targets.size)
        assertTrue(round.tiles.size > targets.size, "level 4 adds intruders")
    }

    /** The consigne, the replay label and the finished title, verbatim. */
    @Test
    fun `the authored French strings are the TSX's`() {
        val (_, model) = soundTwins()
        assertEquals(
            "Un son peut s'écrire de plusieurs façons — trouve-les toutes !",
            model.headline,
        )
        assertEquals("Réécouter le son", model.listenAccessibilityLabel)
        assertEquals("Tu as tout trouvé !", model.finishedTitle)
        // U+2014 EM DASH and U+0027 APOSTROPHE, never normalised.
        assertTrue(model.headline.contains("—"))
        assertTrue(model.headline.contains("s'écrire"))
    }

    /**
     * The strip fills in TAP order, and a found tile locks — the projection the
     * view reads for every slot and every tile.
     */
    @Test
    fun `a partial find fills the next slot and locks only that tile`() {
        val (harness, model) = soundTwins()
        val targets = model.targets
        assertTrue(targets.size >= 2, "this seed must give a multi-twin round")
        val first = targets[0]

        assertNull(model.foundTile(0), "an unfilled slot is a dashed box")
        harness.audio.holdSays()
        assertEquals(Verdict.ACCEPT, model.pick(first))

        assertEquals(first.id, model.foundTile(0)?.id)
        assertNull(model.foundTile(1), "the remaining twin is still hiding")
        assertTrue(model.tileDisabled(first))
        assertTrue(model.tileHighlighted(first))
        assertFalse(model.tileDisabled(targets[1]), "the round keeps running")
        assertFalse(model.complete)
        assertTrue(model.stars[0], "a partial find never costs the star")
        // The anchor word IS the feedback, per graphy.
        harness.pump()
        val anchor = TwinGraphy(text = first.text, word = first.word, emoji = first.emoji)
        assertEquals(listOf(twinSuccess(anchor)), harness.audio.sayTexts)
        assertEquals("Oui ! ${first.word}.", harness.audio.sayTexts.first())
        harness.settle()
    }

    /**
     * Invariant 3 again, in the multi-select machine: an intruder greys the
     * round's star and nothing else. The twins already found stay found.
     */
    @Test
    fun `an intruder costs the star and nothing else`() {
        val (harness, model) = soundTwins()
        val intruder = model.round.tiles.first { !it.correct }

        assertEquals(Verdict.REJECT, model.pick(intruder))
        assertFalse(model.stars[0])
        assertEquals(Mood.IDLE, model.mood)
        assertEquals(0, model.idx)
        assertFalse(model.done)
        assertFalse(model.tileHighlighted(intruder))
        assertFalse(model.tileDisabled(intruder), "an intruder is never locked away")
        assertTrue(model.found.isEmpty())
        assertEquals(0, harness.confetti.count)

        // The round is still winnable, once the swallow window passes.
        harness.time.advance(800L)
        for (target in model.targets) {
            assertEquals(Verdict.ACCEPT, model.pick(target))
        }
        harness.settle()
        assertTrue(model.complete || model.idx == 1)
    }

    /**
     * The COMPLETING find is the only one that celebrates: chime, confetti, the
     * whole row locked while the last anchor word plays, then the next round.
     */
    @Test
    fun `completing the family celebrates and advances`() {
        val (harness, model) = soundTwins()
        val targets = model.targets
        // The prompt the round announces — « Trouve tous les ko ! », built by
        // :core and never by the view.
        assertEquals("Trouve tous les ${model.round.family.sound} !", twinPrompt(model.round.family))

        harness.audio.holdSays()
        for (target in targets) model.pick(target)

        assertTrue(model.complete)
        assertEquals(1, harness.confetti.count, "one burst, on the completing tap")
        for (tile in model.round.tiles) {
            assertTrue(model.tileDisabled(tile), "the whole row locks for the line")
        }
        assertEquals(0, model.idx, "the advance waits for the last line")

        harness.settle()
        assertEquals(1, model.idx, "the run has more rounds, so it moved on")
        assertTrue(model.found.isEmpty(), "the strip resets for the new round")
        assertTrue(model.stars[0], "a clean round keeps its star")
    }

    /** Invariant 5: every level is built from its number alone. */
    @Test
    fun `every level builds a runnable session`() {
        for (level in 1..4) {
            val harness = EngineHarness()
            val model = TwinsModel(level, harness.deps, SeededGenerator(5))
            assertTrue(model.totalRounds > 0, "level $level has rounds")
            assertTrue(model.targets.size >= 2, "level $level has twins to collect")
            assertTrue(
                model.round.tiles.size > model.targets.size,
                "level $level mixes in at least one intruder",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The chrome the three sound engines share
// ---------------------------------------------------------------------------

class SoundEngineChromeTest {

    /**
     * `relative z-[41] … px-4 pb-8 pt-2` + `mb-1` on the consigne + `px-5 py-2`
     * on the listen pill — identical in all three TSX files.
     */
    @Test
    fun `the authored column and pill metrics are the TSX's`() {
        assertEquals(16.dp, SoundEngineChrome.COLUMN_PADDING_X)
        assertEquals(8.dp, SoundEngineChrome.COLUMN_PADDING_TOP)
        assertEquals(32.dp, SoundEngineChrome.COLUMN_PADDING_BOTTOM)
        assertEquals(4.dp, SoundEngineChrome.CONSIGNE_BOTTOM)
        assertEquals(20.dp, SoundEngineChrome.LISTEN_PADDING_X)
        assertEquals(8.dp, SoundEngineChrome.LISTEN_PADDING_Y)
        // z-[41] — one above GameFrame's confetti canvas (zIndex 40), which is
        // the value GameFrame's own FLOW layer already carries.
        assertEquals(41f, SoundEngineChrome.CONTENT_Z_INDEX)
    }

    /** The index feeds `TILE_COLORS[i % n]`; the id is the TSX `key`. */
    @Test
    fun `slots carry row order and the authored key`() {
        val values = listOf("OU", "ON", "OI")
        val slots = SoundEngineChrome.slots(values) { it }
        assertEquals(listOf(0, 1, 2), slots.map { it.index })
        assertEquals(values, slots.map { it.id })
        assertEquals(values, slots.map { it.value })
        assertTrue(SoundEngineChrome.slots(emptyList<String>()) { it }.isEmpty())
    }

    @Test
    fun `CSS dashed is proportional to the border width`() {
        assertEquals(listOf(8.dp, 8.dp), SoundEngineChrome.dash(4.dp))
        assertEquals(listOf(6.dp, 6.dp), SoundEngineChrome.dash(3.dp))
    }

    /**
     * The run's OWN confetti system gets the burst, and everything the CALLER
     * supplied — the audio channel, the clock, `award`, the announce delay —
     * reaches the model untouched, because points may only ever come from the
     * injected `award` (invariant 8).
     *
     * [EngineHost] has no `fireConfetti` member at all, so "the caller's
     * fireConfetti must not win" is not something a test has to check — there is
     * nothing for a caller to pass. What still needs checking is the other
     * direction: that wiring the confetti in does not quietly drop or substitute
     * one of the four things the caller DID pass.
     */
    @Test
    fun `wiring adds this run's confetti and preserves everything else`() {
        val harness = EngineHarness()
        val system = ConfettiSystem(
            random = SeededGenerator(5),
            reduceMotion = FixedReduceMotion(false),
        )
        system.report(width = 320f * 2f, height = 620f * 2f, pixelScale = 2f)

        val wired = SoundEngineChrome.wire(harness.host, harness.scope, system)
        wired.fireConfetti()

        assertEquals(ConfettiSystem.BURST_COUNT, system.particles.size)
        assertEquals(0, harness.confetti.count, "the burst went to this run's system")

        assertTrue(wired.audio === harness.audio)
        harness.time.advance(1_234L)
        assertEquals(harness.time.nowMillis, wired.time.nowMillis)
        assertEquals(harness.award.result, wired.award(ExerciseId.FIND_SOUND, 2, 3, 4))
        assertEquals(1, harness.award.calls.size)
        assertEquals(ExerciseId.FIND_SOUND, harness.award.calls[0].exercise)
        assertEquals(2, harness.award.calls[0].level)
        assertEquals(3, harness.award.calls[0].perfect)
        assertEquals(4, harness.award.calls[0].total)
        assertTrue(wired.scope === harness.scope)
    }
}
