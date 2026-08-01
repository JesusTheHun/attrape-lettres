package fr.dappit.attrapelettres.ui.engines

import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.ImageKey
import fr.dappit.attrapelettres.core.domain.LetterWord
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.ReadImageRound
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.READ_IMAGE_PROMPT
import fr.dappit.attrapelettres.core.rewards.MISS_COOLDOWN_MS
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// `ReadImageView` — the read-the-word row. A host JUnit run has no renderer
// (A11), so what is tested here is everything the view holds that is NOT a rule
// of the shared model: the tile palette and its cycle length, the three strings
// a tile carries, the printed word's `uppercase`, the authored `clamp()`
// triples and the Tailwind classes — plus the one that would silently break the
// game, that the key a tile hands `pick` is the key the model judges.
//
// Every asserted value comes from `apps/game-web/src/exercises/ReadImageExercise.tsx`
// (its `TILE_COLORS` array, the `ariaLabel`/`previewLabel` template strings, the
// inline `clamp()` styles and the classes on the exercise column), with
// `Tests/ALUITests/Engines/LetterViewTests.swift` as the worked example.

class ReadImageStageTest {

    /**
     * ReadImage's `TILE_COLORS` is the FULL five-paint ramp — level 4 shows five
     * pictures — cycled `i % 5`. It is the only single-pick engine that reaches
     * the violet.
     */
    @Test
    fun `the palette is all five paints and cycles on five`() {
        assertEquals(5, ReadImageStage.palette.size)
        assertEquals(
            listOf("#FF8A65", "#FFD54F", "#4FC3F7", "#AED581", "#BA9EE8"),
            ReadImageStage.palette.map { it.bg.hex },
        )
        assertEquals("#2C1846", ReadImageStage.palette.last().ink.hex)
        assertEquals(ReadImageStage.palette[0], ReadImageStage.paint(5))
        assertEquals("#FFD54F", ReadImageStage.paint(6).bg.hex)
    }

    /**
     * `className="… uppercase …"` on the printed word — while the tile's
     * `aria-label` keeps the AUTHORED casing (`Image : ${choice.word}`), because
     * that string is data, not typography.
     */
    @Test
    fun `the word is printed uppercase but labelled as authored`() {
        val gateau = LetterWord(letter = "G", word = "Gâteau", emoji = "🍰")
        val round = ReadImageRound(target = gateau, choices = listOf(gateau))

        assertEquals("GÂTEAU", ReadImageStage.displayWord(round))
        assertEquals(listOf("Image : Gâteau"), ReadImageStage.tiles(round).map { it.label })
        assertEquals(listOf("Écouter Gâteau"), ReadImageStage.tiles(round).map { it.previewLabel })
    }

    /**
     * A tile carries the picture the way `WordIcon` wants it: the dedicated
     * :art drawing when there is one, the emoji as the fallback that is never
     * dropped.
     */
    @Test
    fun `tiles carry both the drawing and the emoji fallback`() {
        val jupe = LetterWord(letter = "J", word = "Jupe", emoji = "👗", img = ImageKey.JUPE)
        val chat = LetterWord(letter = "C", word = "Chat", emoji = "🐱")
        val tiles = ReadImageStage.tiles(ReadImageRound(target = jupe, choices = listOf(jupe, chat)))

        assertEquals(ImageKey.JUPE, tiles[0].img)
        assertEquals("👗", tiles[0].emoji)
        assertNull(tiles[1].img)
        assertEquals("🐱", tiles[1].emoji)
        assertEquals(listOf("Jupe", "Chat"), tiles.map { it.word })
    }

    /**
     * `fontSize: "clamp(38px,11vw,68px)"` on the word,
     * `size="clamp(60px,19vw,104px)"` on a tile's picture, `my-1.5` around the
     * FitLine and `mb-1` under the consigne. The column's `px-4 pt-2 pb-8`,
     * `gap-4` and `mb-6` are the letter family's shared chrome and are pinned
     * with it, not here.
     */
    @Test
    fun `the authored sizes and margins are the TSX numbers`() {
        assertEquals(FluidSpec(38f, 11f, 68f), ReadImageStage.wordSize)
        assertEquals(FluidSpec(60f, 19f, 104f), ReadImageStage.pictureSize)
        assertEquals(6.dp, ReadImageStage.wordMargin)
        assertEquals(4.dp, ReadImageStage.headlineBottomMargin)
    }

    /** The French the screen prints and announces, verbatim from the TSX. */
    @Test
    fun `the screen's French is the TSX's French`() {
        val h = EngineHarness()
        val model = SinglePickModel.readImage(level = 1, deps = h.deps, rng = SeededGenerator(2))

        assertEquals("Lis le mot et touche la bonne image", model.headline)
        assertEquals("Répéter la consigne", model.listenAccessibilityLabel)
        assertEquals("🔊 Trouve la bonne image.", model.listenText)
        assertEquals("Tu as tout trouvé !", model.finishedTitle)
        assertEquals(Copy.Exercise.READ_IMAGE_HEADLINE, model.headline)
    }
}

class ReadImageRowTest {

    /**
     * The row's key is `choice.word` — what `buildReadImageSession`'s target is
     * judged on. The winning tile is accepted and highlighted alone; any other
     * is a miss that greys the round's star AT POINTER-DOWN (invariant 8) and
     * does nothing else at all (invariant 3).
     */
    @Test
    fun `the tile key is the word the model judges`() {
        val seed = 3L

        val accepting = EngineHarness()
        val winner = SinglePickModel.readImage(1, accepting.deps, SeededGenerator(seed))
        val target = winner.current.target.word
        val tiles = ReadImageStage.tiles(winner.current)
        val win = assertNotNull(tiles.firstOrNull { it.word == target })

        assertEquals(Verdict.ACCEPT, ReadImageStage.pick(win, winner))
        assertEquals(target, winner.flash)
        assertEquals(1, accepting.confetti.count)
        assertTrue(ReadImageStage.isHighlighted(win, winner))
        assertEquals(1, tiles.count { ReadImageStage.isHighlighted(it, winner) })
        assertTrue(winner.tilesDisabled, "every tile is disabled while the line plays")
        accepting.settle()

        val missing = EngineHarness()
        val loser = SinglePickModel.readImage(1, missing.deps, SeededGenerator(seed))
        val miss = assertNotNull(
            ReadImageStage.tiles(loser.current).firstOrNull { it.word != target },
        )

        assertEquals(Verdict.REJECT, ReadImageStage.pick(miss, loser))
        assertEquals(false, loser.stars[0])
        assertEquals(0, missing.confetti.count)
    }

    /** Invariant 3: a wrong picture locks nothing, loses nothing, routes nowhere. */
    @Test
    fun `a miss has no terminal effect`() {
        val h = EngineHarness()
        val model = SinglePickModel.readImage(1, h.deps, SeededGenerator(17))
        val target = model.current.target.word
        val miss = assertNotNull(
            ReadImageStage.tiles(model.current).firstOrNull { it.word != target },
        )

        ReadImageStage.pick(miss, model)

        assertFalse(model.done)
        assertEquals(0, model.idx)
        assertEquals(Mood.IDLE, model.mood, "a miss never changes the mascot")
        assertNull(model.flash)
        assertFalse(model.tilesDisabled, "nothing is disabled by a wrong answer")

        // …and the very next round is still winnable once the 800 ms swallow
        // window has passed. The window is the anti-farming pacer, not a lock.
        h.time.advance(MISS_COOLDOWN_MS)
        val win = assertNotNull(
            ReadImageStage.tiles(model.current).firstOrNull { it.word == target },
        )
        assertEquals(Verdict.ACCEPT, ReadImageStage.pick(win, model))
        h.settle()
    }

    /**
     * The audition speaks the picture's WORD — the only place the word is voiced
     * before the success line, and only when the child asks for it. The consigne
     * itself never names the target: that would give the answer away.
     */
    @Test
    fun `the preview speaks the tile's word and the consigne never does`() {
        val h = EngineHarness()
        val model = SinglePickModel.readImage(1, h.deps, SeededGenerator(9))
        val tile = ReadImageStage.tiles(model.current).first()

        ReadImageStage.preview(tile, model)
        h.pump()

        assertTrue(h.audio.sayTexts.contains(tile.word))
        // The consigne is the CONSTANT — it never names the target, which is
        // what makes reading the printed word the task.
        assertEquals(READ_IMAGE_PROMPT, model.promptText)
        assertEquals("Trouve la bonne image.", READ_IMAGE_PROMPT)
        // Auditioning commits nothing.
        assertTrue(model.stars.all { it })
        assertEquals(0, model.idx)
        assertNull(model.flash)
    }

    /**
     * ReadImage's ported asymmetry: its TILE preview IS `locked`-guarded, so an
     * impatient tap during the celebration cannot cut the success line short —
     * and cutting it short is what would strand the round (`say` returns false,
     * the advance is dropped).
     */
    @Test
    fun `the preview is locked-guarded while the success line plays`() {
        val h = EngineHarness()
        h.audio.holdSays()
        val model = SinglePickModel.readImage(1, h.deps, SeededGenerator(3))
        val target = model.current.target.word
        val win = assertNotNull(
            ReadImageStage.tiles(model.current).firstOrNull { it.word == target },
        )

        ReadImageStage.pick(win, model)
        h.pump()
        h.audio.clearEvents()

        ReadImageStage.preview(win, model)
        h.pump()

        assertTrue(h.audio.events.isEmpty(), "no unlock, no say — the guard held")
        h.settle()
    }
}
