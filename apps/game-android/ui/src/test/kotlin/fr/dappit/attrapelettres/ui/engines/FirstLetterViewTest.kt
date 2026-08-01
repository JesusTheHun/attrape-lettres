package fr.dappit.attrapelettres.ui.engines

import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.FirstLetterRound
import fr.dappit.attrapelettres.core.domain.LetterWord
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.FIRST_LETTER_LEVELS
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// `FirstLetterView` and the letter family's shared chrome — everything those
// hold that is NOT a rule of `SinglePickModel`: the three-paint palette and its
// cycle, the strings a tile carries, the authored `clamp()` triples and the
// column's Tailwind numbers, and — the one that would silently break the game —
// that the key a tile hands `pick` is the key the model judges.
//
// Every asserted value comes from `src/exercises/FirstLetterExercise.tsx` (its
// `TILE_COLORS` array, the `ariaLabel` / `previewLabel` template strings, the
// inline `clamp()` style and the Tailwind classes on the exercise column), never
// read back out of the Kotlin.

class FirstLetterViewTest {

    private val round = FirstLetterRound(
        target = LetterWord(letter = "M", word = "Maison", emoji = "🏠"),
        choices = listOf("P", "M", "A"),
    )

    /**
     * ```tsx
     * const TILE_COLORS = [
     *   { bg: "#FF8A65", ink: "#4A2317" },
     *   { bg: "#FFD54F", ink: "#4A3B00" },
     *   { bg: "#4FC3F7", ink: "#062E3D" },
     * ];
     * ```
     * THREE paints — so the cycle is `i % 3`. A row of four would wear `#FF8A65`
     * again, never the green `#AED581` that only ReadImage reaches.
     */
    @Test
    fun `the palette is the three-paint prefix and cycles on three`() {
        assertEquals(3, FirstLetterStage.palette.size)
        assertEquals(
            listOf("#FF8A65", "#FFD54F", "#4FC3F7"),
            FirstLetterStage.palette.map { it.bg.hex },
        )
        assertEquals(
            listOf("#4A2317", "#4A3B00", "#062E3D"),
            FirstLetterStage.palette.map { it.ink.hex },
        )
        assertEquals(Palette.tileColors.take(3), FirstLetterStage.palette)
        assertEquals(FirstLetterStage.palette[0], FirstLetterStage.paint(3))
        assertEquals("#FFD54F", FirstLetterStage.paint(4).bg.hex)
    }

    /**
     * `ariaLabel={`Lettre ${letter}`}`, `previewLabel={`Écouter ${letter}`}`,
     * content `{letter}`, colour by POSITION in the row.
     */
    @Test
    fun `tiles carry the letter, its label and its preview label`() {
        val tiles = FirstLetterStage.tiles(round)

        assertEquals(listOf("P", "M", "A"), tiles.map { it.letter })
        assertEquals(listOf("Lettre P", "Lettre M", "Lettre A"), tiles.map { it.label })
        assertEquals(listOf("Écouter P", "Écouter M", "Écouter A"), tiles.map { it.previewLabel })
        assertEquals(
            listOf("#FF8A65", "#FFD54F", "#4FC3F7"),
            tiles.map { it.paint.bg.hex },
        )
    }

    /**
     * `<WordIcon … size="clamp(80px,28vw,150px)" />`, plus the column's
     * `px-4 pt-2 pb-8`, the row's `gap-4`, the pill's `mb-6` and the picture's
     * `margin: "6px 0"`.
     */
    @Test
    fun `the authored metrics are the Tailwind classes resolved`() {
        assertEquals(FluidSpec(80f, 28f, 150f), FirstLetterStage.pictureSize)
        assertEquals(16.dp, LetterStageMetrics.paddingX)
        assertEquals(8.dp, LetterStageMetrics.paddingTop)
        assertEquals(32.dp, LetterStageMetrics.paddingBottom)
        assertEquals(16.dp, LetterStageMetrics.tileGap)
        assertEquals(24.dp, LetterStageMetrics.listenBottomMargin)
        assertEquals(6.dp, LetterStageMetrics.promptMargin)
        assertEquals(20.dp, LetterStageMetrics.listenPaddingX)
        assertEquals(8.dp, LetterStageMetrics.listenPaddingY)
    }

    /**
     * The row's key is the one the engine judges (`target.letter`): the tile
     * whose letter IS the target is accepted, any other is a miss that greys the
     * round's star at pointer-down (invariant 8) and does nothing else at all
     * (invariant 3).
     */
    @Test
    fun `the tile key is the key the model judges`() {
        val seed = 11L

        val winning = EngineHarness()
        val winner = SinglePickModel.firstLetter(1, winning.deps, SeededGenerator(seed))
        val target = winner.current.target.letter
        val tiles = FirstLetterStage.tiles(winner.current)
        val hit = assertNotNull(tiles.firstOrNull { it.letter == target })

        assertEquals(Verdict.ACCEPT, FirstLetterStage.pick(hit, winner))
        assertEquals(target, winner.flash)
        assertEquals(1, winning.confetti.count)
        // `highlight={flash === letter}` — the winner only.
        assertTrue(FirstLetterStage.isHighlighted(hit, winner))
        assertEquals(1, tiles.count { FirstLetterStage.isHighlighted(it, winner) })

        val missing = EngineHarness()
        val loser = SinglePickModel.firstLetter(1, missing.deps, SeededGenerator(seed))
        val miss = assertNotNull(
            FirstLetterStage.tiles(loser.current).firstOrNull { it.letter != target },
        )

        assertEquals(Verdict.REJECT, FirstLetterStage.pick(miss, loser))
        assertFalse(loser.stars[0])
        assertEquals(0, missing.confetti.count)
        // Nothing is locked, nothing is lost, no route changes.
        assertEquals(0, loser.idx)
        assertFalse(loser.done)
        assertNull(loser.flash)
        assertFalse(loser.tilesDisabled)
    }

    /**
     * `onPreview={() => { audio.unlock(); void audio.say(letter); }}` — the
     * audition speaks the LETTER and commits nothing: no verdict, no star, no
     * advance.
     */
    @Test
    fun `a preview speaks the letter and commits nothing`() {
        val h = EngineHarness()
        val model = SinglePickModel.firstLetter(1, h.deps, SeededGenerator(5))
        val tile = assertNotNull(FirstLetterStage.tiles(model.current).firstOrNull())

        FirstLetterStage.preview(tile, model)
        h.pump()

        assertTrue(h.audio.events.contains(AudioEvent.Unlock))
        assertTrue(h.audio.sayTexts.contains(tile.letter))
        assertTrue(model.stars.all { it })
        assertEquals(0, model.idx)
        assertNull(model.flash)
    }

    /**
     * The spoken lines, verbatim from the TSX:
     * `audio.say(`Trouve la première lettre de ${word}.`)` and
     * `audio.say(`Oui ! ${letter}. ${word}.`, { rate: 0.98 })`.
     */
    @Test
    fun `the spoken lines are the web's strings`() {
        val h = EngineHarness()
        val model = SinglePickModel.firstLetter(1, h.deps, SeededGenerator(3))
        val target = model.current.target

        assertEquals("Trouve la première lettre de ${target.word}.", model.promptText)
        assertEquals(
            "Oui ! ${target.letter}. ${target.word}.",
            model.descriptor.successLine(model.current),
        )
        assertEquals("Répéter le mot", model.listenAccessibilityLabel)
        assertEquals("Tu as tout trouvé !", model.finishedTitle)
        // No consigne above the mascot in this engine — the picture is the prompt.
        assertNull(model.headline)
    }

    /**
     * The 🔊 pill prints the word on levels 1–3 and a bare speaker on the last
     * two, where the child works from the sound alone
     * (`level < FIRST_LETTER_LEVELS.length - 1`). The rule is the model's; this
     * pins what the view therefore renders.
     */
    @Test
    fun `the listen pill drops the written word on the last two levels`() {
        val last = FIRST_LETTER_LEVELS.size
        val h = EngineHarness()

        val early = SinglePickModel.firstLetter(1, h.deps, SeededGenerator(2))
        assertEquals("🔊 ${early.current.target.word}", early.listenText)

        val late = SinglePickModel.firstLetter(last, h.deps, SeededGenerator(2))
        assertEquals("🔊", late.listenText)

        val alsoLate = SinglePickModel.firstLetter(last - 1, h.deps, SeededGenerator(2))
        assertEquals("🔊", alsoLate.listenText)
    }

    /**
     * Invariant 5 seen from the view: every level in the ladder builds a
     * playable round with three tiles, one of which is the answer. No gate, no
     * lock, no order.
     */
    @Test
    fun `every level builds a playable round`() {
        for (level in 1..FIRST_LETTER_LEVELS.size) {
            val h = EngineHarness()
            val model = SinglePickModel.firstLetter(level, h.deps, SeededGenerator(level.toLong()))
            assertTrue(model.totalRounds > 0, "level $level has no rounds")
            val tiles = FirstLetterStage.tiles(model.current)
            assertEquals(3, tiles.size, "level $level does not show three tiles")
            assertTrue(
                tiles.any { it.letter == model.current.target.letter },
                "level $level has no winning tile",
            )
            assertEquals(tiles.size, tiles.map { it.letter }.toSet().size)
        }
    }
}
