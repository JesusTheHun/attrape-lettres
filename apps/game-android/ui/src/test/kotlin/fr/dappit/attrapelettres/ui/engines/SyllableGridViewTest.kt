package fr.dappit.attrapelettres.ui.engines

import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.GridSyllable
import fr.dappit.attrapelettres.core.domain.SyllableGridMode
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.levels.GRID_PROMPT
import fr.dappit.attrapelettres.core.levels.buildSyllableGridSession
import fr.dappit.attrapelettres.core.support.SeededGenerator
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.Palette
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// `SyllableGridView` — everything the view holds that is NOT a rule of the
// shared model: the four-paint palette and its cycle, what a tile SHOWS versus
// what it PICKS, the half-written syllable's gap, the labels, and the authored
// clamps.
//
// Every asserted value comes from the TypeScript
// (`src/exercises/SyllableGridExercise.tsx` — its `TILE_COLORS` array, the
// `ariaLabel` / `previewLabel` template strings, the inline `clamp()` styles and
// the Tailwind classes on the exercise column) and from `src/levels.ts` for the
// consigne. Never read back out of the Kotlin.
//
// No composition runs (A11): the view's decisions live in `SyllableGridMetrics`
// and in `SinglePickModel`, which is exactly why they can be asserted here.

class SyllableGridViewTest {

    private val va = GridSyllable(text = "VA", sound = "va", consonant = "V", vowel = "A")
    private val vi = GridSyllable(text = "VI", sound = "vi", consonant = "V", vowel = "I")

    /**
     * ```tsx
     * const TILE_COLORS = [
     *   { bg: "#FF8A65", ink: "#4A2317" },
     *   { bg: "#FFD54F", ink: "#4A3B00" },
     *   { bg: "#4FC3F7", ink: "#062E3D" },
     *   { bg: "#A5D6A7", ink: "#123B18" },
     * ];
     * ```
     * Four paints — and the fourth green is this file's own, NOT the shared
     * ramp's `#AED581`/`#213606`.
     */
    @Test
    fun `the palette is four paints with the authored green exception`() {
        assertEquals(4, SyllableGridMetrics.TILE_COLOR_COUNT)
        assertEquals("#FF8A65", SyllableGridMetrics.paint(0).bg.hex)
        assertEquals("#4A2317", SyllableGridMetrics.paint(0).ink.hex)
        assertEquals("#FFD54F", SyllableGridMetrics.paint(1).bg.hex)
        assertEquals("#4FC3F7", SyllableGridMetrics.paint(2).bg.hex)
        assertEquals("#A5D6A7", SyllableGridMetrics.paint(3).bg.hex)
        assertEquals("#123B18", SyllableGridMetrics.paint(3).ink.hex)
        assertNotEquals(Palette.tileColors[3].bg.hex, SyllableGridMetrics.paint(3).bg.hex)
        // Level 4+ shows five and six tiles; the fifth wraps back to coral.
        assertEquals("#FF8A65", SyllableGridMetrics.paint(4).bg.hex)
        assertEquals("#FFD54F", SyllableGridMetrics.paint(5).bg.hex)
    }

    /**
     * `hear`: the tile IS the syllable. `vowel`: the tile is the vowel — but the
     * pick key, the flash key, the label and the audition stay the WHOLE
     * syllable.
     */
    @Test
    fun `the mode changes the face and nothing else`() {
        assertEquals("VA", SyllableGridMetrics.tileFace(va, SyllableGridMode.HEAR))
        assertEquals("A", SyllableGridMetrics.tileFace(va, SyllableGridMode.VOWEL))
        assertEquals("VA", SyllableGridMetrics.pickKey(va))
        assertEquals("Syllabe VA", SyllableGridMetrics.tileLabel(va))
        assertEquals("Écouter VA", SyllableGridMetrics.previewLabel(va))
        // The audition is the whole syllable in both modes — hearing VA next to
        // VI is the drill; a bare « a » would teach nothing.
        assertEquals("va", SyllableGridMetrics.previewText(va))
        assertEquals("vi", SyllableGridMetrics.previewText(vi))
    }

    /**
     * The gap fills for the TARGET only. The TSX tests the flash key's
     * truthiness; this port compares, and the two agree on every reachable
     * input because `pick` assigns `flash` only after the wrong-key early
     * return. The third case is unreachable today and is pinned so it stays a
     * decision: if `flash` ever starts carrying a wrong pick, the gap must NOT
     * answer the question for the child (invariant 3 — a miss reveals nothing).
     */
    @Test
    fun `the vowel gap fills only for the target`() {
        assertEquals(
            SyllableGridMetrics.Gap(text = "", filled = false),
            SyllableGridMetrics.gap(va, flash = null),
        )
        assertEquals(
            SyllableGridMetrics.Gap(text = "A", filled = true),
            SyllableGridMetrics.gap(va, flash = "VA"),
        )
        assertEquals(
            SyllableGridMetrics.Gap(text = "", filled = false),
            SyllableGridMetrics.gap(va, flash = "VI"),
        )
        // The gap shows the TARGET's vowel, never the flash key's own letters.
        assertEquals("I", SyllableGridMetrics.gap(vi, flash = "VI").text)
    }

    /** The group carries the label; the box is `aria-hidden`. */
    @Test
    fun `the half-syllable label names the consonant`() {
        assertEquals("Syllabe à compléter : V", SyllableGridMetrics.halfSyllableLabel(va))
        val che = GridSyllable(text = "CHÉ", sound = "ché", consonant = "CH", vowel = "É")
        assertEquals("Syllabe à compléter : CH", SyllableGridMetrics.halfSyllableLabel(che))
    }

    /**
     * `fontSize: "clamp(38px,11vw,64px)"`, the gap's em geometry, `mt-2`/`gap-2`,
     * `mt-3 mb-6` on the button, `gap-3` between tiles,
     * `size="clamp(62px,17vw,96px)"`, `fontSize="clamp(26px,7vw,46px)"`, and the
     * column's `px-4 pt-2 pb-8`.
     */
    @Test
    fun `the authored metrics are the Tailwind classes resolved`() {
        assertEquals(FluidSpec(38f, 11f, 64f), SyllableGridMetrics.syllableFontSize)
        assertEquals(8.dp, SyllableGridMetrics.halfSyllableTop)
        assertEquals(8.dp, SyllableGridMetrics.halfSyllableGap)
        near(SyllableGridMetrics.GAP_MIN_WIDTH_EM, 0.9f, "minWidth: 0.9em")
        near(SyllableGridMetrics.GAP_HEIGHT_EM, 1.1f, "height: 1.1em")
        near(SyllableGridMetrics.GAP_PADDING_X_EM, 0.1f, "padding: 0 0.1em")
        assertEquals(16.dp, SyllableGridMetrics.gapCornerRadius)
        assertEquals(4.dp, SyllableGridMetrics.gapBorderWidth)
        assertEquals(6.dp, SyllableGridMetrics.gapFilledShadow.y)
        assertEquals(14.dp, SyllableGridMetrics.gapFilledShadow.blur)
        near(SyllableGridMetrics.gapFilledShadow.opacity, 0.12f, "rgba(0,0,0,0.12)")
        assertEquals(12.dp, SyllableGridMetrics.listenTop)
        assertEquals(24.dp, SyllableGridMetrics.listenBottom)
        assertEquals(12.dp, SyllableGridMetrics.tileGap)
        assertEquals(FluidSpec(62f, 17f, 96f), SyllableGridMetrics.tileSize)
        assertEquals(FluidSpec(26f, 7f, 46f), SyllableGridMetrics.tileFontSize)
        assertEquals(16.dp, SyllableGridMetrics.columnPaddingX)
        assertEquals(8.dp, SyllableGridMetrics.columnPaddingTop)
        assertEquals(32.dp, SyllableGridMetrics.columnPaddingBottom)
        assertEquals(4.dp, SyllableGridMetrics.consigneBottom)
        // `clamp(62px,17vw,96px)` on a 390 dp phone.
        near(SyllableGridMetrics.tileSize.resolve(390f), 66.3f, "clamp(62px,17vw,96px) at 390")
    }

    /**
     * Invariant 6's tap-target floor: this engine shrinks its tiles below
     * `Tile`'s default, so the authored clamp has to clear 44 dp on the
     * narrowest phone we ship to as well as the widest tablet.
     */
    @Test
    fun `every authored tile clamp clears the tap-target floor`() {
        for (viewport in listOf(320f, 375f, 390f, 430f, 1024f)) {
            assertTrue(
                SyllableGridMetrics.tileSize.resolve(viewport) >= 44f,
                "a $viewport dp viewport must still give a 44 dp tile",
            )
        }
        // The authored minimum itself, so a future edit that lowers it fails
        // here and not in a pixel diff.
        near(SyllableGridMetrics.tileSize.min, 62f, "the authored tile minimum")
    }

    /**
     * A `vowel`-mode round from the real builder: every tile shares the target's
     * consonant, so the faces ARE the vowel column — while the KEYS stay whole
     * syllables. This is the trap the view exists to make impossible.
     */
    @Test
    fun `a real vowel round shows the vowel column and picks whole syllables`() {
        val session = buildSyllableGridSession(3, SyllableGridMode.VOWEL, SeededGenerator(7))
        val round = assertNotNull(session.firstOrNull())

        val faces = round.choices.map { SyllableGridMetrics.tileFace(it, SyllableGridMode.VOWEL) }
        assertEquals(round.choices.map { it.vowel }, faces)
        assertEquals(faces.size, faces.toSet().size, "no two tiles read the same")
        assertTrue(round.choices.all { it.consonant == round.target.consonant })

        val keys = round.choices.map { SyllableGridMetrics.pickKey(it) }
        assertEquals(round.choices.map { it.text }, keys)
        assertTrue(keys.contains(round.target.text))
        val consonant = round.target.consonant
        assertTrue(keys.all { it.startsWith(consonant) && it.length > consonant.length })
        assertFalse(keys.contains(round.target.vowel), "a key is never the bare face")
    }

    /**
     * The key a tile hands `pick` is the key the model judges — in VOWEL mode,
     * where the tile shows something else entirely. A correct tap flashes and
     * fires the confetti; any other tap is a miss that greys the round's star at
     * pointer-down (invariant 8) and locks nothing (invariant 3).
     */
    @Test
    fun `the tile key is the key the model judges, vowel mode included`() {
        val seed = 5L

        val winning = EngineHarness()
        val winner = SinglePickModel.syllableGrid(
            exercise = ExerciseId.PICK_VOWEL,
            mode = SyllableGridMode.VOWEL,
            level = 1,
            deps = winning.deps,
            rng = SeededGenerator(seed),
        )
        val target = winner.current.target
        val hit = assertNotNull(winner.current.choices.firstOrNull { it.text == target.text })
        assertEquals(Verdict.ACCEPT, winner.pick(SyllableGridMetrics.pickKey(hit)))
        assertEquals(target.text, winner.flash)
        assertEquals(1, winning.confetti.count)
        // `highlight={flash === choice.text}` — the winner only.
        assertEquals(
            1,
            winner.current.choices.count { winner.flash == SyllableGridMetrics.pickKey(it) },
        )
        // The gap now shows the target's vowel, filled.
        assertEquals(
            SyllableGridMetrics.Gap(text = target.vowel, filled = true),
            SyllableGridMetrics.gap(target, winner.flash),
        )

        val missing = EngineHarness()
        val loser = SinglePickModel.syllableGrid(
            exercise = ExerciseId.PICK_VOWEL,
            mode = SyllableGridMode.VOWEL,
            level = 1,
            deps = missing.deps,
            rng = SeededGenerator(seed),
        )
        val miss = loser.current.choices.firstOrNull { it.text != target.text }
        if (miss != null) {
            assertEquals(Verdict.REJECT, loser.pick(SyllableGridMetrics.pickKey(miss)))
            assertFalse(loser.stars[0])
            assertEquals(0, missing.confetti.count)
            // A miss costs the star and nothing else: no advance, no lock, and
            // the gap still hides the answer.
            assertEquals(0, loser.idx)
            assertFalse(loser.done)
            assertEquals(
                SyllableGridMetrics.Gap(text = "", filled = false),
                SyllableGridMetrics.gap(loser.current.target, loser.flash),
            )
        }
    }

    /**
     * A tile's « Écouter » speaks THAT tile's syllable and commits nothing — no
     * verdict, no star, no advance. Hearing VA next to VI is the whole drill.
     */
    @Test
    fun `a tile audition speaks its own syllable and commits nothing`() {
        val h = EngineHarness()
        val model = SinglePickModel.syllableGrid(
            exercise = ExerciseId.HEAR_SYLLABLE,
            mode = SyllableGridMode.HEAR,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(3),
        )
        val choice = assertNotNull(model.current.choices.firstOrNull())

        model.preview(SyllableGridMetrics.previewText(choice))
        h.pump()

        assertTrue(h.audio.sayTexts.contains(choice.sound))
        assertTrue(model.stars.all { it })
        assertEquals(0, model.idx)
        assertNull(model.flash)
    }

    /**
     * The consigne above the mascot is `GRID_PROMPT[mode]`, and the big button's
     * label is « Réécouter la syllabe » — both verbatim from the web
     * (`levels.ts`, `SyllableGridExercise.tsx`).
     */
    @Test
    fun `the consigne and the listen label are the web's strings`() {
        val h = EngineHarness()
        val hear = SinglePickModel.syllableGrid(
            exercise = ExerciseId.HEAR_SYLLABLE,
            mode = SyllableGridMode.HEAR,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(1),
        )
        val vowel = SinglePickModel.syllableGrid(
            exercise = ExerciseId.PICK_VOWEL,
            mode = SyllableGridMode.VOWEL,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(1),
        )

        assertEquals("Écoute la syllabe et trouve son écriture", hear.headline)
        assertEquals("Écoute la syllabe et trouve la voyelle qui manque", vowel.headline)
        assertEquals(GRID_PROMPT[SyllableGridMode.HEAR], hear.headline)
        assertEquals(GRID_PROMPT[SyllableGridMode.VOWEL], vowel.headline)
        assertEquals("Réécouter la syllabe", hear.listenAccessibilityLabel)
        assertEquals("🔊 Écouter", hear.listenText)
        assertEquals("Tu as tout lu !", hear.finishedTitle)
    }

    /**
     * The prompt and success lines are the syllable's SPOKEN form — the same
     * strings the VO bank baked (`gridPrompt` / `gridSuccess` in `levels.ts`).
     */
    @Test
    fun `the spoken lines are the syllable's sound`() {
        val h = EngineHarness()
        val model = SinglePickModel.syllableGrid(
            exercise = ExerciseId.HEAR_SYLLABLE,
            mode = SyllableGridMode.HEAR,
            level = 1,
            deps = h.deps,
            rng = SeededGenerator(9),
        )
        val target = model.current.target

        assertEquals(target.sound, model.promptText)
        assertEquals(Verdict.ACCEPT, model.pick(target.text))
        h.pump()
        assertTrue(h.audio.sayTexts.contains("Oui ! ${target.sound}."))
    }
}
