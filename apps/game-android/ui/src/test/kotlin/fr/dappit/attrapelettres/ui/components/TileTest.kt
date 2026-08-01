package fr.dappit.attrapelettres.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.core.domain.LetterFace
import fr.dappit.attrapelettres.core.domain.LetterScript
import fr.dappit.attrapelettres.core.domain.Verdict
import fr.dappit.attrapelettres.core.domain.faceLabel
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.core.rewards.MISS_COOLDOWN_MS
import fr.dappit.attrapelettres.ui.design.Copy
import fr.dappit.attrapelettres.ui.design.FluidSpec
import fr.dappit.attrapelettres.ui.design.Palette
import fr.dappit.attrapelettres.ui.design.Typography
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Tile — the pick primitive. Every expected value here is derived from the
// TypeScript, never read back from the Kotlin:
//
//   pipeline   src/components/Tile.tsx        `handle` / `handlePreview`
//   cooldown   src/exercises/FindSoundExercise.tsx `coolUntil` +
//              src/rewards.ts MISS_COOLDOWN_MS = 800 (strict `<`)
//   metrics    src/components/Tile.tsx        the inline styles
//   labels     src/components/Tile.tsx        `previewLabel ?? "Écouter"`,
//              plus the nine engines' `ariaLabel` expressions
//   TSX tests  src/components/Tile.test.tsx   (three assertions, carried over)
//
// No test here builds a composition: a @Composable body cannot be invoked from
// plain JUnit, and everything worth pinning was deliberately kept out of one.

// --- Harness -----------------------------------------------------------------

/**
 * [TileFeedback] that writes down what it was asked to play and when, so the
 * ORDER of the pointer-down pipeline is assertable without a frame clock.
 */
private class RecordingFeedback : TileFeedback {
    val calls = mutableListOf<String>()
    var pressFinish: (() -> Unit)? = null
    var shakeFinish: (() -> Unit)? = null

    override fun press(onFinished: () -> Unit) {
        calls.add("press")
        pressFinish = onFinished
    }

    override fun shake(onFinished: () -> Unit) {
        calls.add("shake")
        shakeFinish = onFinished
    }

    /** The animation ran out (or, for a press, was replaced by a shake). */
    fun finishPress() = pressFinish?.invoke()

    fun finishShake() = shakeFinish?.invoke()
}

/**
 * The single-pick handler as `FindSoundExercise.tsx` writes it — locked gate,
 * cooldown gate, then wrong (nudge + cooldown + grey the star) or right (lock +
 * flash + success). The engines package owns the real one; this exists so the
 * component-level mechanisms are asserted against the shape they must serve.
 */
private class SinglePickHarness(rounds: Int, val target: String, time: MutableTimeSource) {
    var stars = MutableList(rounds) { true }
    var locked = false
    var flash: String? = null
    var mood = "idle"
    var index = 0
    val audio = mutableListOf<String>()
    val cooldown = MissCooldown(time)

    /** TSX `disabled={flash != null}` — the celebration lock, never a verdict. */
    val tileDisabled: Boolean get() = flash != null

    fun pick(key: String): Verdict {
        if (locked) return Verdict.REJECT
        if (cooldown.isSwallowing) return Verdict.REJECT
        audio.add("unlock")
        audio.add("pop")
        if (key != target) {
            audio.add("nudge")
            cooldown.registerMiss()
            stars[index] = false
            return Verdict.REJECT
        }
        locked = true
        flash = key
        mood = "happy"
        audio.add("success")
        return Verdict.ACCEPT
    }
}

// --- The visual state machine (invariant 3) ----------------------------------

class TileStateMachineTest {

    @Test
    fun `a touch on an idle tile presses it`() {
        assertEquals(
            TileVisual.PRESSED,
            TileStateMachine.onPointerDown(TileVisual.IDLE, disabled = false),
        )
    }

    @Test
    fun `a disabled tile does not move at all`() {
        // TSX: `if (disabled) return` — BEFORE the animation, so a disabled tile
        // neither squishes nor picks.
        for (state in TileVisual.entries) {
            assertEquals(state, TileStateMachine.onPointerDown(state, disabled = true))
        }
    }

    @Test
    fun `accept goes to ACCEPTED and reject goes to REJECTED`() {
        assertEquals(
            TileVisual.ACCEPTED,
            TileStateMachine.onVerdict(TileVisual.PRESSED, Verdict.ACCEPT),
        )
        assertEquals(
            TileVisual.REJECTED,
            TileStateMachine.onVerdict(TileVisual.PRESSED, Verdict.REJECT),
        )
    }

    @Test
    fun `INVARIANT 3 — no state is terminal, every one of them is pressable again`() {
        // This is the whole of "no fail state" in one assertion: whatever a tile
        // has just been through, including a rejection, the very next touch
        // presses it. There is no state a wrong answer can strand a child in.
        for (state in TileVisual.entries) {
            assertEquals(
                TileVisual.PRESSED,
                TileStateMachine.onPointerDown(state, disabled = false),
                "$state must accept the next pick",
            )
        }
    }

    @Test
    fun `INVARIANT 3 — the enum carries no failure member`() {
        // A locked/disabled/wrong/failed member is how the fail state gets in:
        // once the type can express it, some future round will set it. `disabled`
        // stays a parameter the exercise passes, and REJECTED is nothing more
        // than "a wobble is in flight".
        val banned = listOf("LOCK", "DISABL", "WRONG", "FAIL", "ERROR", "DEAD", "GAMEOVER")
        for (state in TileVisual.entries) {
            for (word in banned) {
                assertFalse(
                    state.name.contains(word),
                    "TileVisual.${state.name} looks like a fail state",
                )
            }
        }
        assertEquals(4, TileVisual.entries.size)
    }

    @Test
    fun `a running shake outlives the press completion that WAAPI fires on replace`() {
        // The press is replaced by the shake on a reject, and a replaced WAAPI
        // animation still settles its `finished` promise. So the press's
        // completion must not clear a wobble that has only just started.
        assertEquals(
            TileVisual.REJECTED,
            TileStateMachine.onSettled(TileVisual.REJECTED, finished = TileVisual.PRESSED),
        )
        assertEquals(
            TileVisual.IDLE,
            TileStateMachine.onSettled(TileVisual.REJECTED, finished = TileVisual.REJECTED),
        )
        assertEquals(
            TileVisual.IDLE,
            TileStateMachine.onSettled(TileVisual.ACCEPTED, finished = TileVisual.PRESSED),
        )
    }
}

// --- The pointer-down pipeline (invariant 1) ---------------------------------

class TileInteractionTest {

    @Test
    fun `INVARIANT 1 — the press is already playing when onPick runs`() {
        // Tile.tsx animates BEFORE calling onPick, so the child sees and hears
        // the tile answer in the same beat the finger lands, whatever the
        // verdict turns out to be.
        val feedback = RecordingFeedback()
        val interaction = TileInteraction(feedback)
        var callsAtPick: List<String> = emptyList()
        var picked = 0

        interaction.pointerDown(disabled = false) {
            callsAtPick = feedback.calls.toList()
            picked++
            Verdict.ACCEPT
        }

        assertEquals(1, picked)
        assertEquals(listOf("press"), callsAtPick)
        assertEquals(listOf("press"), feedback.calls, "an accepted pick never shakes")
        assertEquals(TileVisual.ACCEPTED, interaction.visual)
    }

    @Test
    fun `INVARIANT 3 — a reject shakes, in the same synchronous beat, and does nothing else`() {
        val feedback = RecordingFeedback()
        val interaction = TileInteraction(feedback)

        interaction.pointerDown(disabled = false) { Verdict.REJECT }

        assertEquals(listOf("press", "shake"), feedback.calls)
        assertEquals(TileVisual.REJECTED, interaction.visual)
        // …and the very next touch presses it again. Nothing locked.
        interaction.pointerDown(disabled = false) { Verdict.ACCEPT }
        assertEquals(TileVisual.ACCEPTED, interaction.visual)
    }

    @Test
    fun `a rejected tile comes back to rest when the wobble ends — not to a locked state`() {
        val feedback = RecordingFeedback()
        val interaction = TileInteraction(feedback)

        interaction.pointerDown(disabled = false) { Verdict.REJECT }
        feedback.finishPress() // WAAPI settles the replaced press first
        assertEquals(TileVisual.REJECTED, interaction.visual)
        feedback.finishShake()
        assertEquals(TileVisual.IDLE, interaction.visual)
    }

    @Test
    fun `a disabled tile does nothing — no animation, no pick`() {
        val feedback = RecordingFeedback()
        val interaction = TileInteraction(feedback)
        var picked = 0

        interaction.pointerDown(disabled = true) {
            picked++
            Verdict.ACCEPT
        }

        assertEquals(0, picked, "TSX: if (disabled) return — before the animation")
        assertTrue(feedback.calls.isEmpty())
        assertEquals(TileVisual.IDLE, interaction.visual)
    }

    @Test
    fun `Ecouter previews without committing the pick — Tile test tsx`() {
        val previewFeedback = RecordingFeedback()
        val preview = TileInteraction(previewFeedback)
        val pickFeedback = RecordingFeedback()
        val pick = TileInteraction(pickFeedback)
        var previewed = 0
        var picked = 0

        preview.previewDown(disabled = false) { previewed++ }
        assertEquals(1, previewed)
        assertEquals(0, picked)
        assertEquals(listOf("press"), previewFeedback.calls)
        assertTrue(
            "shake" !in previewFeedback.calls,
            "a preview has no verdict, so it can never shake",
        )
        assertTrue(preview.visual != TileVisual.REJECTED)

        pick.pointerDown(disabled = false) {
            picked++
            Verdict.ACCEPT
        }
        assertEquals(1, picked)
        assertEquals(1, previewed, "picking did not re-fire the preview")
    }

    @Test
    fun `a disabled tile disables Ecouter too — Tile test tsx`() {
        val feedback = RecordingFeedback()
        val interaction = TileInteraction(feedback)
        var previewed = 0

        interaction.previewDown(disabled = true) { previewed++ }

        assertEquals(0, previewed)
        assertTrue(feedback.calls.isEmpty())
    }

    @Test
    fun `reduced motion changes NOTHING about a tile press — the web does not gate it`() {
        // `Tile.tsx` contains no matchMedia call: lines 59 and 61 are bare
        // `el.animate(...)`. The media query is read in usePopFlourish.ts,
        // useConfetti.ts, Mascot.tsx and shop/anim.ts — never for a pick tile.
        // So neither TileInteraction nor TileFeedback takes a ReduceMotionSource
        // AT ALL; this test can only assert the outcome, because the gate cannot
        // be expressed. (iOS D29, ARCHITECTURE section 3 row 6.)
        val members = TileFeedback::class.java.methods.map { it.name }.toSet()
        assertTrue("press" in members && "shake" in members)
        val constructor = TileInteraction::class.java.constructors.single()
        assertEquals(1, constructor.parameterCount)
        assertEquals(TileFeedback::class.java, constructor.parameterTypes[0])
    }
}

// --- The pick pipeline under fire (invariants 3 + 8) -------------------------

class TilePickPipelineTest {

    @Test
    fun `a wrong tap greys the star and arms the window — and that is ALL that changes`() {
        val time = MutableTimeSource(1_000)
        val harness = SinglePickHarness(rounds = 3, target = "ou", time = time)
        val feedback = RecordingFeedback()
        val interaction = TileInteraction(feedback)

        interaction.pointerDown(disabled = harness.tileDisabled) { harness.pick("an") }

        assertEquals(listOf("unlock", "pop", "nudge"), harness.audio)
        assertEquals(listOf(false, true, true), harness.stars)
        assertEquals(listOf("press", "shake"), feedback.calls)

        // Invariant 3 — no lock, no lives, no route, no mood change, and the
        // tile is NOT disabled by a wrong answer.
        assertFalse(harness.locked)
        assertEquals(0, harness.index)
        assertEquals("idle", harness.mood)
        assertFalse(harness.tileDisabled)
    }

    @Test
    fun `picks inside the window are silently swallowed — feedback still plays`() {
        val time = MutableTimeSource(1_000)
        val harness = SinglePickHarness(rounds = 3, target = "ou", time = time)

        harness.pick("an") // miss at t = 1000
        val audioAfterMiss = harness.audio.toList()
        val starsAfterMiss = harness.stars.toList()

        val feedback = RecordingFeedback()
        val interaction = TileInteraction(feedback)
        interaction.pointerDown(disabled = harness.tileDisabled) { harness.pick("ou") }

        assertEquals(audioAfterMiss, harness.audio, "a swallowed pick makes no sound at all")
        assertEquals(starsAfterMiss, harness.stars)
        assertFalse(harness.locked)
        assertEquals(
            listOf("press", "shake"),
            feedback.calls,
            "the swallow is silent, not invisible",
        )

        time.advance(MISS_COOLDOWN_MS)
        assertEquals(Verdict.ACCEPT, harness.pick("ou"))
        assertEquals(audioAfterMiss + listOf("unlock", "pop", "success"), harness.audio)
    }

    @Test
    fun `the celebration lock rejects silently and is not a fail state`() {
        val time = MutableTimeSource(1_000)
        val harness = SinglePickHarness(rounds = 3, target = "ou", time = time)

        harness.pick("ou") // right — locks while the success line plays
        val audioAfterSuccess = harness.audio.toList()

        assertEquals(Verdict.REJECT, harness.pick("ou"))
        assertEquals(audioAfterSuccess, harness.audio)
        assertEquals(listOf(true, true, true), harness.stars, "a locked tap never greys a star")
    }
}

// --- The swallow window (invariant 8) ----------------------------------------

class MissCooldownTest {

    @Test
    fun `MISS_COOLDOWN_MS is 800 — rewards ts`() {
        assertEquals(800L, MISS_COOLDOWN_MS)
    }

    @Test
    fun `swallows strictly inside the window and accepts again at exactly plus 800 ms`() {
        val time = MutableTimeSource(1_000)
        val cooldown = MissCooldown(time)

        assertFalse(cooldown.isSwallowing, "nothing is swallowed before the first miss")

        cooldown.registerMiss()
        assertTrue(cooldown.isSwallowing)

        time.advance(799L)
        assertTrue(cooldown.isSwallowing)

        time.advance(1L)
        assertFalse(cooldown.isSwallowing, "performance.now() < coolUntil is false at equality")
    }

    @Test
    fun `a second miss re-arms the window from the new now`() {
        val time = MutableTimeSource(1_000)
        val cooldown = MissCooldown(time)

        cooldown.registerMiss()
        time.advance(800L)
        assertFalse(cooldown.isSwallowing)

        cooldown.registerMiss()
        assertTrue(cooldown.isSwallowing)
        time.advance(799L)
        assertTrue(cooldown.isSwallowing)
    }
}

// --- Metrics and the accessibility floor (invariant 6) -----------------------

class TileMetricsTest {

    @Test
    fun `dim is clamp(92px, 27vw, 150px) — floor, ramp, cap`() {
        assertEquals(FluidSpec(92f, 27f, 150f), TileMetrics.DEFAULT_SIZE)
        assertEquals(92f, TileMetrics.DEFAULT_SIZE.resolve(320f), 1e-4f) // 86.4 -> floor
        assertEquals(105.3f, TileMetrics.DEFAULT_SIZE.resolve(390f), 1e-3f)
        assertEquals(150f, TileMetrics.DEFAULT_SIZE.resolve(600f), 1e-4f) // 162 -> cap
    }

    @Test
    fun `INVARIANT 6 — the tap target never drops below 92 dp, at any width`() {
        assertEquals(92f, TileMetrics.DEFAULT_SIZE.min)
        var width = 200
        while (width <= 1400) {
            val side = TileMetrics.DEFAULT_SIZE.resolve(width.dp)
            assertTrue(side >= 92.dp, "at ${width}dp the tile is $side")
            assertTrue(side >= TileMetrics.PLATFORM_MINIMUM_TAP_TARGET)
            width += 1
        }
        // Copy.kt records the same floor for the screens that quote it; the two
        // numbers must not drift.
        assertEquals(TileMetrics.DEFAULT_SIZE.min, Copy.Tile.MINIMUM_SIDE.value)
    }

    @Test
    fun `glyph, padding, radius, disabled opacity, column gap`() {
        assertEquals(FluidSpec(30f, 9f, 64f), TileMetrics.DEFAULT_FONT_SIZE)
        assertEquals(35.1f, TileMetrics.DEFAULT_FONT_SIZE.resolve(390f), 1e-3f)
        assertEquals(FluidSpec(10f, 3f, 20f), TileMetrics.HORIZONTAL_PADDING)
        assertEquals(28.dp, TileMetrics.CORNER_RADIUS)
        assertEquals(0.4f, TileMetrics.DISABLED_OPACITY)
        assertEquals(8.dp, TileMetrics.COLUMN_GAP) // gap-2
    }

    @Test
    fun `the highlight ring is a 6 dp spread outside a 28 dp box, so radius 34`() {
        assertEquals(6.dp, TileMetrics.HIGHLIGHT_RING_WIDTH)
        assertEquals(
            TileMetrics.CORNER_RADIUS + TileMetrics.HIGHLIGHT_RING_WIDTH,
            TileMetrics.HIGHLIGHT_RING_RADIUS,
        )
        assertEquals(34.dp, TileMetrics.HIGHLIGHT_RING_RADIUS)
        assertEquals(150, TileMetrics.HIGHLIGHT_TRANSITION_MS) // transition 0.15s
        assertEquals("#66BB6A", Palette.green.hex)
    }

    @Test
    fun `the box shadows are the authored CSS layers`() {
        // 0 8px 0 rgba(0,0,0,0.12), 0 12px 20px rgba(0,0,0,0.14)
        assertEquals(BoxShadow(8.dp, 0.dp, 0.12f), TileMetrics.RESTING_LIP)
        assertEquals(BoxShadow(12.dp, 20.dp, 0.14f), TileMetrics.RESTING_CAST)
        // 0 0 0 6px #66BB6A, 0 10px 22px rgba(0,0,0,0.18) — note: NO lip.
        assertEquals(BoxShadow(10.dp, 22.dp, 0.18f), TileMetrics.HIGHLIGHT_CAST)
        // 0 3px 0 rgba(0,0,0,0.10), 0 5px 12px rgba(0,0,0,0.12)
        assertEquals(BoxShadow(3.dp, 0.dp, 0.10f), TileMetrics.PREVIEW_LIP)
        assertEquals(BoxShadow(5.dp, 12.dp, 0.12f), TileMetrics.PREVIEW_CAST)

        // The lip is hard-edged, which is what a blur of 0 means, and is why it
        // is painted rather than handed to Modifier.shadow.
        assertEquals(0.dp, TileMetrics.RESTING_LIP.blur)
        assertEquals(0.dp, TileMetrics.PREVIEW_LIP.blur)
        // …and `color` carries that authored alpha to the painter. The tolerance
        // is one 8-bit step because that is the resolution of the channel: an
        // sRGB Compose `Color` packs alpha into 8 bits, so 0.12 is stored as the
        // nearest representable value, 31/255 = 0.121568…. No Color of any
        // colour space can hold 0.12f exactly (the 64-bit non-sRGB form gives
        // alpha 10 bits, still not exact), so a 1e-4 tolerance here was
        // unsatisfiable by construction rather than a statement about the port.
        // The authored 0.12 itself is pinned exactly by the BoxShadow equality
        // above; this line only proves the alpha reaches `color` unchanged.
        assertEquals(0.12f, TileMetrics.RESTING_LIP.color.alpha, 1f / 255f)
    }

    @Test
    fun `Ecouter is height clamp(40,11vw,52) and font clamp(16,4dot5vw,22)`() {
        assertEquals(FluidSpec(40f, 11f, 52f), TileMetrics.PREVIEW_HEIGHT)
        assertEquals(40f, TileMetrics.PREVIEW_HEIGHT.resolve(320f), 1e-4f) // 35.2 -> floor
        assertEquals(FluidSpec(16f, 4.5f, 22f), TileMetrics.PREVIEW_FONT_SIZE)
        assertEquals(17.55f, TileMetrics.PREVIEW_FONT_SIZE.resolve(390f), 1e-3f)
        // 40 is BELOW Material's 48 dp and below the 44 dp used for tiles. It is
        // the web's authored value and iOS ships it too; the button is a
        // full-width bar with 8 dp of clear space above it, so the miss risk the
        // guideline guards against is not there. Pinned so a change is a
        // decision, not a drift.
        assertEquals(40f, TileMetrics.PREVIEW_HEIGHT.min)
    }

    @Test
    fun `the glyph style is the ink, the resolved clamp and font-black`() {
        val ink = Color(0xFF4A2317)
        val style = tileGlyphStyle(
            fontSize = TileMetrics.DEFAULT_FONT_SIZE,
            ink = ink,
            viewport = 390.dp,
            fontScale = 1f,
        )
        assertEquals(ink, style.color)
        assertEquals(35.1f, style.fontSize.value, 1e-3f)
        assertEquals(Typography.Weight.black, style.fontWeight)

        // A 2x system font scale must not blow the glyph out of a tile whose
        // side is fixed dp — the same decision Typography.fixedSp records.
        val scaled = tileGlyphStyle(
            fontSize = TileMetrics.DEFAULT_FONT_SIZE,
            ink = ink,
            viewport = 390.dp,
            fontScale = 2f,
        )
        assertEquals(35.1f / 2f, scaled.fontSize.value, 1e-3f)
    }
}

// --- Labels (invariant 6) ----------------------------------------------------

class TileAccessibilityTest {

    /**
     * Every shape of tile the nine engines render, with the label expression
     * each one passes as `ariaLabel`. A new engine adds a row here.
     */
    private val everyVariant: List<Pair<String, String>> = listOf(
        "FirstLetter" to Copy.Exercise.letterTile("A"),
        "LetterMatch" to faceLabel(LetterFace("A", "a", LetterScript.CURSIVE)),
        "LetterMatch print" to faceLabel(LetterFace("A", "A", LetterScript.PRINT)),
        "Assemble" to Copy.Exercise.syllableTile("MA"),
        "SpellSyllable" to Copy.Exercise.syllableTile("CHA"),
        "SyllableGrid" to Copy.Exercise.syllableTile("BI"),
        "SoundTwins" to Copy.Exercise.syllableTile("OU"),
        "FindSound" to Copy.Exercise.soundTile("ou"),
        "SpellSound" to Copy.Exercise.letterTile("O"),
        "ReadImage" to Copy.Exercise.imageTile("chat"),
        "Ecouter (per tile)" to Copy.Exercise.listenTile("MA"),
        "Ecouter (fallback)" to Copy.Tile.LISTEN_FALLBACK,
    )

    @Test
    fun `every tile variant produces a non-empty contentDescription`() {
        for ((variant, label) in everyVariant) {
            assertTrue(label.isNotBlank(), "$variant has no label")
            // A label made only of a glyph or of punctuation would pass
            // isNotBlank and still tell a screen-reader user nothing.
            assertTrue(label.length >= 5, "$variant label is too thin: $label")
            assertTrue(label.any { it.isLetter() }, "$variant label has no words: $label")
        }
    }

    @Test
    fun `a tile label names the thing on the tile`() {
        assertEquals("Lettre A", Copy.Exercise.letterTile("A"))
        assertEquals("Syllabe MA", Copy.Exercise.syllableTile("MA"))
        assertEquals("Son ou", Copy.Exercise.soundTile("ou"))
        assertEquals("Image : chat", Copy.Exercise.imageTile("chat"))
        assertEquals("Écouter MA", Copy.Exercise.listenTile("MA"))
        assertEquals("Écouter", Copy.Tile.LISTEN_FALLBACK)
    }

    @Test
    fun `INVARIANT 6 — contentDescription is a required parameter, never defaulted`() {
        // The TSX prop is optional and every engine passes one anyway; making it
        // structural is free and cannot be forgotten. A source scan is the only
        // way to see it from a host test: a @Composable signature is rewritten
        // by the compiler plugin, so reflection would be reading the wrong
        // thing.
        val source = assertNotNull(
            tileSourceOrNull,
            "Tile.kt not found from ${System.getProperty("user.dir")}",
        )
        for ((index, line) in source.readLines().withIndex()) {
            val at = line.indexOf("contentDescription: String")
            if (at < 0) continue
            val rest = line.substring(at + "contentDescription: String".length)
            assertFalse(
                rest.trimStart().startsWith("="),
                "Tile.kt:${index + 1} gives contentDescription a default",
            )
            assertFalse(rest.trimStart().startsWith("?"), "Tile.kt:${index + 1} made it nullable")
        }
    }

    @Test
    fun `INVARIANT 1 — the tile never reaches for Modifier clickable`() {
        // clickable fires on pointer-UP, after gesture arbitration, behind
        // Material's ripple. It is the single most likely regression in this
        // file and is one grep away from being impossible.
        val source = assertNotNull(tileSourceOrNull)
        for ((index, line) in source.readLines().withIndex()) {
            assertFalse(
                line.contains("clickable("),
                "Tile.kt:${index + 1} uses clickable — invariant 1 forbids it",
            )
        }
    }
}

/**
 * Gradle runs tests with the module directory as the working directory, so
 * `src/main/kotlin/...` resolves from there. The walk upwards is belt and braces
 * for an IDE runner rooted at the repo or at `apps/game-android`.
 */
private fun findTileSource(): File? {
    val suffix = "src/main/kotlin/fr/dappit/attrapelettres/ui/components/Tile.kt"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        for (candidate in listOf(suffix, "ui/$suffix", "apps/game-android/ui/$suffix")) {
            val here = File(dir, candidate)
            if (here.isFile) return here
        }
        dir = dir.parentFile
    }
    return null
}

private val tileSourceOrNull: File? = findTileSource()
