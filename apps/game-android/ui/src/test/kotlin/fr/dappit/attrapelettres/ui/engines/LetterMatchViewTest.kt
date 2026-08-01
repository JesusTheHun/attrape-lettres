package fr.dappit.attrapelettres.ui.engines

import androidx.compose.ui.text.font.FontFamily
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.LetterMatchRound
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.LetterMatchPrompts
import fr.dappit.attrapelettres.core.rewards.MISS_COOLDOWN_MS
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// `LetterMatchView` — ONE view, two catalog rows (`match-case`, `match-script`).
// Ported from the LetterMatch half of
// `apps/game-ios/Tests/ALUITests/Engines/LetterViewTests.swift`, itself written
// against `apps/game-web/src/exercises/LetterMatchExercise.tsx`.
//
// A host run has no renderer (A11), so these drive the MODEL and the pure
// [LetterMatchStage] rules: the palette, the three different strings the same
// face produces, the authored `clamp()`, and the one that would silently break
// the game — that the row is keyed on the letter's BASE and not on the glyph it
// draws.

class LetterMatchStageTest {

    /**
     * LetterMatch's own `TILE_COLORS`: four paints, and the fourth is the
     * STANDARD green `#AED581/#213606` — not the lighter `#A5D6A7/#123B18` the
     * syllable grid substitutes at that index.
     */
    @Test
    fun `the palette is the four-paint prefix with the standard green`() {
        assertEquals(4, LetterMatchStage.palette.size)
        assertEquals(
            listOf("#FF8A65", "#FFD54F", "#4FC3F7", "#AED581"),
            LetterMatchStage.palette.map { it.bg.hex },
        )
        assertEquals("#213606", LetterMatchStage.paint(3).ink.hex)
        assertNotEquals(Palette.gridTileColors[3], LetterMatchStage.paint(3))
        assertEquals(LetterMatchStage.palette[0], LetterMatchStage.paint(4))
    }

    /**
     * A tile is labelled by its FORM (`faceLabel`), auditioned by its NAME
     * (`Écouter ${face.base}`) and picked by its IDENTITY (`face.base`) — three
     * different strings off the same face, and the glyph is none of them.
     */
    @Test
    fun `tiles are labelled by form and picked by identity`() {
        val round = LetterMatchRound(
            prompt = LetterFace(base = "A", glyph = "A", script = LetterScript.PRINT),
            choices = listOf(
                LetterFace(base = "B", glyph = "b", script = LetterScript.CURSIVE),
                LetterFace(base = "A", glyph = "a", script = LetterScript.CURSIVE),
            ),
        )
        val tiles = LetterMatchStage.tiles(round)

        assertEquals(
            listOf("Lettre B minuscule attachée", "Lettre A minuscule attachée"),
            tiles.map { it.label },
        )
        assertEquals(listOf("Écouter B", "Écouter A"), tiles.map { it.previewLabel })
        assertEquals(listOf("B", "A"), tiles.map { it.pickKey })
        // The glyph is what gets DRAWN, in its own script — and it is not the key.
        assertEquals(listOf("b", "a"), tiles.map { it.face.glyph })
        assertEquals(
            listOf(LetterScript.CURSIVE, LetterScript.CURSIVE),
            tiles.map { it.face.script },
        )
    }

    /**
     * `fontSize: "clamp(80px,28vw,150px)"` on the prompt glyph. Its
     * `margin: "6px 0"`, the pill's `mb-6` and the column's `px-4 pt-2 pb-8` +
     * `gap-4` are the letter family's shared chrome and are pinned with it.
     */
    @Test
    fun `the prompt glyph uses the authored clamp`() {
        assertEquals(FluidSpec(80f, 28f, 150f), LetterMatchStage.promptSize)
    }

    /**
     * `style={{ fontFamily: SCRIPT_FONT[face.script] }}` — WHICH TOKEN THIS VIEW
     * ASKS FOR, and no more than that.
     *
     * OPEN RISK, pinned here so it stays visible. `match-script` is the
     * « attachée » exercise: its whole content is that a cursive letterform is
     * the same letter as its printed twin. This view asks for the generic
     * `FontFamily.Cursive`; on AOSP that aliases to a joined handwriting face,
     * but a device whose `fonts.xml` lacks the alias silently falls back to the
     * default sans — and the exercise then teaches the WRONG letterform while
     * every test on this machine still passes. Font resolution happens on the
     * platform at draw time; no host test, and no assertion in this file, can
     * observe it. This test guarantees only that the token does not drift.
     */
    @Test
    fun `the prompt and its tiles ask for the script's own family`() {
        assertEquals(Typography.appFamily, LetterMatchStage.family(LetterScript.PRINT))
        assertEquals(Typography.cursiveFamily, LetterMatchStage.family(LetterScript.CURSIVE))
        assertEquals(FontFamily.SansSerif, LetterMatchStage.family(LetterScript.PRINT))
        assertEquals(FontFamily.Cursive, LetterMatchStage.family(LetterScript.CURSIVE))
        assertNotEquals(
            LetterMatchStage.family(LetterScript.PRINT),
            LetterMatchStage.family(LetterScript.CURSIVE),
            "a cursive letter must never be drawn in the printed face",
        )
    }
}

class LetterMatchRowTest {

    /**
     * The row hands `pick` the letter's BASE. On a majuscule→minuscule round the
     * winning tile's glyph is « a » and its base is « A »: keying the row on the
     * glyph would make that round unwinnable, so this asserts both directions on
     * the same seeded session.
     */
    @Test
    fun `the row is keyed on the base, not the drawn glyph`() {
        // Find a seed whose first round draws its tiles in lowercase, i.e. where
        // glyph and base actually differ (the direction is drawn per round).
        var found: Pair<Long, LetterMatchRound>? = null
        for (seed in 1L until 60L) {
            val probe = SinglePickModel.letterMatch(
                exercise = ExerciseId.MATCH_CASE,
                kind = LetterMatchKind.CASE,
                level = 1,
                deps = EngineHarness().deps,
                rng = SeededGenerator(seed),
            )
            if (probe.current.choices.all { it.glyph != it.base }) {
                found = seed to probe.current
                break
            }
        }
        val (seed, round) = assertNotNull(found, "no seed produced a lowercase-tile round")
        val base = round.prompt.base
        val winning = assertNotNull(
            LetterMatchStage.tiles(round).firstOrNull { it.pickKey == base },
        )
        assertEquals(base.lowercase(), winning.face.glyph)

        val accepting = EngineHarness()
        val winner = SinglePickModel.letterMatch(
            exercise = ExerciseId.MATCH_CASE,
            kind = LetterMatchKind.CASE,
            level = 1,
            deps = accepting.deps,
            rng = SeededGenerator(seed),
        )
        assertEquals(Verdict.ACCEPT, LetterMatchStage.pick(winning, winner))
        assertEquals(base, winner.flash)
        assertTrue(LetterMatchStage.isHighlighted(winning, winner))
        assertEquals(
            1,
            LetterMatchStage.tiles(winner.current)
                .count { LetterMatchStage.isHighlighted(it, winner) },
        )
        assertEquals(1, accepting.confetti.count)
        accepting.settle()

        // The same tile, keyed on what it DRAWS: a miss, and the star greys.
        val missing = EngineHarness()
        val loser = SinglePickModel.letterMatch(
            exercise = ExerciseId.MATCH_CASE,
            kind = LetterMatchKind.CASE,
            level = 1,
            deps = missing.deps,
            rng = SeededGenerator(seed),
        )
        assertEquals(Verdict.REJECT, loser.pick(winning.face.glyph))
        assertEquals(false, loser.stars[0])
        assertEquals(0, missing.confetti.count)
    }

    /** Invariant 3: a wrong form locks nothing, loses nothing, routes nowhere. */
    @Test
    fun `a miss has no terminal effect`() {
        val h = EngineHarness()
        val model = SinglePickModel.letterMatch(
            exercise = ExerciseId.MATCH_SCRIPT,
            kind = LetterMatchKind.SCRIPT,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(21),
        )
        val base = model.current.prompt.base
        val miss = assertNotNull(
            LetterMatchStage.tiles(model.current).firstOrNull { it.pickKey != base },
        )

        assertEquals(Verdict.REJECT, LetterMatchStage.pick(miss, model))
        assertFalse(model.done)
        assertEquals(0, model.idx)
        assertEquals(Mood.IDLE, model.mood, "a miss never changes the mascot")
        assertNull(model.flash)
        assertFalse(model.tilesDisabled, "nothing is disabled by a wrong answer")
        assertEquals(false, model.stars[0])

        // Still winnable once the 800 ms swallow window has passed: a pacer, not
        // a lock (invariant 8's swallow, invariant 3's no-fail).
        h.time.advance(MISS_COOLDOWN_MS)
        val win = assertNotNull(
            LetterMatchStage.tiles(model.current).firstOrNull { it.pickKey == base },
        )
        assertEquals(Verdict.ACCEPT, LetterMatchStage.pick(win, model))
        h.settle()
    }

    /**
     * The audition speaks `face.base` — « A », never the drawn « a », and never
     * the cursive form, which has no separate name. It commits nothing.
     */
    @Test
    fun `the preview speaks the letter's name, not its glyph`() {
        val h = EngineHarness()
        val model = SinglePickModel.letterMatch(
            exercise = ExerciseId.MATCH_SCRIPT,
            kind = LetterMatchKind.SCRIPT,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(4),
        )
        val tile = LetterMatchStage.tiles(model.current).first()

        LetterMatchStage.preview(tile, model)
        h.pump()

        assertEquals(listOf(tile.face.base), h.audio.sayTexts)
        assertEquals(tile.face.base.uppercase(), tile.face.base)
        assertTrue(model.stars.all { it })
        assertEquals(0, model.idx)
        assertNull(model.flash)
    }

    /**
     * The screen's French, verbatim from the TSX. The consigne NEVER names the
     * target — it names the direction — and it is what the 🔊 pill prints.
     */
    @Test
    fun `the screen's French is the TSX's French`() {
        val h = EngineHarness()
        val model = SinglePickModel.letterMatch(
            exercise = ExerciseId.MATCH_SCRIPT,
            kind = LetterMatchKind.SCRIPT,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(6),
        )

        assertNull(model.headline, "LetterMatch prints no consigne above the mascot")
        assertEquals("Répéter la consigne", model.listenAccessibilityLabel)
        assertEquals("Tu as tout trouvé !", model.finishedTitle)
        // `script` rounds speak one of the two script lines, both directions.
        assertTrue(
            model.promptText in listOf(
                LetterMatchPrompts.TO_CURSIVE,
                LetterMatchPrompts.TO_PRINT,
            ),
            "a script round speaks a script line: ${model.promptText}",
        )
        assertEquals("🔊 ${model.promptText}", model.listenText)

        val cased = SinglePickModel.letterMatch(
            exercise = ExerciseId.MATCH_CASE,
            kind = LetterMatchKind.CASE,
            level = 1,
            deps = EngineHarness().deps,
            rng = SeededGenerator(6),
        )
        assertTrue(
            cased.promptText in listOf(
                LetterMatchPrompts.TO_UPPER,
                LetterMatchPrompts.TO_LOWER,
            ),
            "a case round speaks a case line: ${cased.promptText}",
        )
        assertEquals("Trouve la petite lettre.", LetterMatchPrompts.TO_LOWER)
        assertEquals("Trouve la grande lettre.", LetterMatchPrompts.TO_UPPER)
        assertEquals("Trouve la lettre attachée.", LetterMatchPrompts.TO_CURSIVE)
        assertEquals("Trouve la lettre en script.", LetterMatchPrompts.TO_PRINT)
    }
}
