package fr.dappit.attrapelettres.ui.screens

import fr.dappit.attrapelettres.art.icons.exerciseIconSpec
import fr.dappit.attrapelettres.core.domain.Difficulty
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.ExerciseMeta
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.levels.exerciseDifficulty
import fr.dappit.attrapelettres.core.licensing.Entitlement
import fr.dappit.attrapelettres.core.licensing.trialNotice
import fr.dappit.attrapelettres.core.persistence.ChildProfile
import fr.dappit.attrapelettres.core.persistence.ProfileStore
import fr.dappit.attrapelettres.core.platform.AudioEngine
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.core.rewards.REWARD_CURVE
import fr.dappit.attrapelettres.ui.components.AspectSquareMetrics
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

// The hub of `src/App.tsx` (lines 156-263), value by value.
//
// Every expectation below is transcribed from the TypeScript — App.tsx (the
// level grid, the aria-labels, the jackpot rule, the listen tap), levels.ts (the
// hint strings, byte for byte, with U+2019 / U+2026 / U+2014 written as escapes)
// and rewards.ts (REWARD_CURVE = [10, 3, 2, 2]) — and never read back out of the
// Kotlin under test.
//
// Nothing here composes: a host JUnit run cannot invoke a @Composable (A11), so
// every decision the hub makes lives in a plain function and these tests drive
// those functions directly. What a composition would have proved instead is
// pinned by the source scan at the bottom.

private const val T0: Long = 1_700_000_000_000

/** A store with one chosen child — the state every hub render sits on. */
private fun makeStore(): ProfileStore {
    val store = ProfileStore(
        kv = InMemoryKVStore(),
        difficultyOf = ::exerciseDifficulty,
        time = MutableTimeSource(T0),
        device = { "hub-test-device" },
    )
    store.createChild("Léa")
    store.chooseSpecies(Species.UNICORN)
    return store
}

// --- Invariant 5 -------------------------------------------------------------

class HubUnlockedTest {

    @Test
    fun `the level row is 1_levelCount for every catalog row, whatever preview says`() {
        // Driven from the catalog itself, so a new exercise is covered the day
        // it is added. The preview value must not be able to hide a level: walk
        // three economies, including a hostile constant zero.
        val previews: List<(ExerciseId, Int) -> Int> = listOf(
            { _, _ -> 0 },
            { _, _ -> 10 },
            { _, level -> level },
        )
        for (preview in previews) {
            for (row in EXERCISES) {
                assertEquals(
                    (1..row.levelCount).toList(),
                    hubLevelCells(row, preview).map { it.level },
                    "${row.id.wire} must expose all ${row.levelCount} levels",
                )
            }
        }
    }

    @Test
    fun `the catalog spans every ExerciseId, so every exercise means all 17`() {
        assertEquals(ExerciseId.entries.toSet(), EXERCISES.map { it.id }.toSet())
        assertEquals(17, EXERCISES.size)
    }

    @Test
    fun `a fresh device exposes every level end to end through the real store`() {
        val store = makeStore()
        for (row in EXERCISES) {
            val cells = hubLevelCells(row) { id, level -> store.preview(id, level) }
            assertEquals((1..row.levelCount).toList(), cells.map { it.level })
        }
    }

    @Test
    fun `a level count of zero is empty, not a crash`() {
        // Unreachable from the shipped catalog; the range would be 1..0 and the
        // guard is what keeps that an empty row rather than an exception in a
        // six-year-old's app.
        val empty = ExerciseMeta(
            id = ExerciseId.FIRST_LETTER,
            name = "x",
            emoji = "x",
            levelCount = 0,
            difficulty = Difficulty.TRAINING,
        )
        assertEquals(emptyList<HubLevelCell>(), hubLevelCells(empty) { _, _ -> 10 })
    }
}

// --- The reward badges --------------------------------------------------------

class HubRewardBadgeTest {

    @Test
    fun `a training row - difficulty 0 - promises the same curve as any other`() {
        val store = makeStore()
        // levels.ts: first-letter and fill-blank are difficulty 0 — no accuracy
        // bonus, but the completion curve like every other row, so the badge
        // shows and the level announces « gagne 10 étoiles ».
        for (id in listOf(ExerciseId.FIRST_LETTER, ExerciseId.FILL_BLANK)) {
            val row = EXERCISES.first { it.id == id }
            assertEquals(Difficulty.TRAINING, row.difficulty, "${id.wire} is not a training row")
            for (cell in hubLevelCells(row) { e, level -> store.preview(e, level) }) {
                val reward = assertNotNull(cell.reward, "${id.wire} level ${cell.level} has no badge")
                assertEquals(REWARD_CURVE[0], reward.points)
                assertTrue(reward.jackpot)
            }
        }
    }

    @Test
    fun `first clear is the jackpot - a fresh paying level previews plus 10 stars`() {
        val store = makeStore()
        val row = EXERCISES.first { it.id == ExerciseId.FIND_SOUND }
        val cells = hubLevelCells(row) { e, level -> store.preview(e, level) }
        val reward = assertNotNull(cells[0].reward)
        assertEquals(10, reward.points) // REWARD_CURVE[0]
        assertTrue(reward.jackpot)
    }

    @Test
    fun `after one clear the badge decays to the plus 3 coin`() {
        val store = makeStore()
        store.award(ExerciseId.FIND_SOUND, level = 1, perfectRounds = 5, totalRounds = 5)
        val row = EXERCISES.first { it.id == ExerciseId.FIND_SOUND }
        val cells = hubLevelCells(row) { e, level -> store.preview(e, level) }
        val cleared = assertNotNull(cells[0].reward)
        assertEquals(REWARD_CURVE[1], cleared.points)
        assertFalse(cleared.jackpot)
        // …and the NEXT level still shows its jackpot, untouched.
        val untouched = assertNotNull(cells[1].reward)
        assertEquals(REWARD_CURVE[0], untouched.points)
        assertTrue(untouched.jackpot)
    }

    @Test
    fun `jackpot means exactly 10 points - 9 and 11 are coins`() {
        // The TSX is `const jackpot = pts === 10`. Nothing smarter.
        for ((points, jackpot) in listOf(9 to false, 10 to true, 11 to false, 1 to false)) {
            val cells = hubLevelCells(EXERCISES[1]) { _, _ -> points }
            assertEquals(HubLevelReward(points, jackpot), cells[0].reward)
        }
    }

    @Test
    fun `a level that promises nothing shows no badge at all`() {
        // No shipped row reaches this since training rows started paying the
        // curve, but a « +0 » badge would be a promise of nothing.
        val cells = hubLevelCells(EXERCISES[1]) { _, _ -> 0 }
        assertTrue(cells.all { it.reward == null })
    }

    @Test
    fun `the level labels, byte for byte`() {
        // `Niveau ${lvl}, gagne ${pts} ${pts > 1 ? "étoiles" : "étoile"}` /
        // `Niveau ${lvl}, pour s'entraîner` — ASCII apostrophe in the TSX.
        val row = EXERCISES[1]
        assertEquals(
            "Niveau 3, gagne 10 étoiles",
            hubLevelCells(row) { _, _ -> 10 }[2].label,
        )
        assertEquals(
            "Niveau 3, gagne 1 étoile",
            hubLevelCells(row) { _, _ -> 1 }[2].label,
        )
        assertEquals(
            "Niveau 3, pour s'entraîner",
            hubLevelCells(row) { _, _ -> 0 }[2].label,
        )
    }
}

// --- The hint chips -----------------------------------------------------------

class HubHintChipTest {

    /**
     * levels.ts, hand-transcribed. The typographic apostrophe (U+2019), the
     * ellipsis (U+2026) and the em dash (U+2014) are the source's own bytes and
     * are written as escapes so a reviewer can see which is which.
     */
    private val tsxChips: Map<ExerciseId, List<String>> = mapOf(
        ExerciseId.FIRST_LETTER to emptyList(),
        ExerciseId.FIND_SOUND to listOf("Écoute le son, tape son écriture"),
        ExerciseId.HEAR_SYLLABLE to listOf("VA, VE, VI… trouve celle que tu entends"),
        ExerciseId.PICK_VOWEL to listOf("La consonne est écrite — pose la voyelle"),
        ExerciseId.FILL_BLANK to listOf("Trouve la syllabe manquante"),
        ExerciseId.ORDER_SYLLABLES to listOf("Remets les syllabes dans l’ordre"),
        ExerciseId.FIND_INTRUDER to listOf("Range le mot… et évite l’intrus !"),
        ExerciseId.SPELL_SOUND to emptyList(),
        ExerciseId.SPELL_SYLLABLE to listOf("Range les lettres de la syllabe"),
        ExerciseId.SPELL_SYLLABLE_PLUS to listOf("Range les lettres… évite les intrus"),
        ExerciseId.SPELL_TWO_SYLLABLES to listOf("Complète les deux syllabes"),
        ExerciseId.READ_IMAGE to emptyList(),
        ExerciseId.MATCH_CASE to listOf("Associe majuscule et minuscule"),
        ExerciseId.MATCH_SCRIPT to listOf("Associe le script et l’attaché"),
        ExerciseId.SOUND_TWINS to listOf("Trouve toutes les écritures du son"),
        ExerciseId.SPELL_SYLLABLE_PLUS_MIXED to
            listOf("GRANDE, petite ou attachée — trouve la bonne"),
        ExerciseId.SPELL_TWO_SYLLABLES_MIXED to
            listOf("GRANDE, petite ou attachée — trouve la bonne"),
    )

    @Test
    fun `every catalog row shows exactly the TSX's chips, byte for byte`() {
        for (row in EXERCISES) {
            assertEquals(tsxChips[row.id], hubHintChips(row), "wrong chips for ${row.id.wire}")
        }
    }

    @Test
    fun `all four conditionals render, in the TSX's order`() {
        // No shipped row uses more than one, but the markup allows all four and
        // the port keeps all four branches.
        val all = ExerciseMeta(
            id = ExerciseId.FILL_BLANK,
            name = "x",
            emoji = "x",
            levelCount = 1,
            difficulty = Difficulty.TRAINING,
            hint = "libre",
            mode = SyllableMode.ORDER,
            spell = SpellSyllableMode.LETTERS_EXACT,
            match = LetterMatchKind.CASE,
        )
        assertEquals(
            listOf(
                "Remets les syllabes dans l’ordre", // MODE_HINT
                "Range les lettres de la syllabe", // SPELL_HINT
                "Associe majuscule et minuscule", // MATCH_HINT
                "libre", // the free hint
            ),
            hubHintChips(all),
        )
    }

    @Test
    fun `mixed swaps the spell chip for MIXED_HINT and changes nothing else`() {
        val mixed = ExerciseMeta(
            id = ExerciseId.SPELL_SYLLABLE_PLUS_MIXED,
            name = "x",
            emoji = "x",
            levelCount = 1,
            difficulty = Difficulty(4),
            spell = SpellSyllableMode.LETTERS_EXTRA,
            mixed = true,
        )
        assertEquals(listOf("GRANDE, petite ou attachée — trouve la bonne"), hubHintChips(mixed))
        val plain = ExerciseMeta(
            id = ExerciseId.SPELL_SYLLABLE_PLUS,
            name = "x",
            emoji = "x",
            levelCount = 1,
            difficulty = Difficulty(2),
            spell = SpellSyllableMode.LETTERS_EXTRA,
            mixed = false,
        )
        assertEquals(listOf("Range les lettres… évite les intrus"), hubHintChips(plain))
    }
}

// --- Invariant 7 ---------------------------------------------------------------

class HubIconTest {

    @Test
    fun `every ExerciseId has a non-empty drawn icon`() {
        for (id in ExerciseId.entries) {
            assertTrue(exerciseIconSpec(id).nodes.isNotEmpty(), "${id.wire} has an empty icon")
        }
    }

    @Test
    fun `all 17 tints are distinct - a copy-pasted branch cannot hide`() {
        val tints = ExerciseId.entries.map { exerciseIconSpec(it).tint }
        assertEquals(ExerciseId.entries.size, tints.toSet().size)
    }
}

// --- The header -----------------------------------------------------------------

/** Records the exact calls the TSX makes on the audio api, in order. */
private class RecordingAudio : AudioEngine {

    sealed interface Event {
        data object Unlock : Event

        data class Say(val text: String, val rate: Double, val pitch: Double) : Event
    }

    val events = mutableListOf<Event>()

    override fun unlock() {
        events.add(Event.Unlock)
    }

    override fun pop() = Unit

    override fun success() = Unit

    override fun nudge() = Unit

    override fun oops() = Unit

    override suspend fun say(text: String, rate: Double, pitch: Double): Boolean {
        events.add(Event.Say(text, rate, pitch))
        return true
    }

    override fun stop() = Unit
}

class HubHeaderTest {

    @Test
    fun `the player chip shows the active child's name, and empty when nobody matches`() {
        val store = makeStore()
        val children: List<ChildProfile> = store.children
        assertEquals("Léa", hubPlayerName(children, store.activeId))
        // `children.find(...)?.name ?? ""` — all three miss shapes.
        assertEquals("", hubPlayerName(children, "nobody"))
        assertEquals("", hubPlayerName(children, null))
        assertEquals("", hubPlayerName(emptyList(), "x"))
    }

    @Test
    fun `listening unlocks FIRST, then says the bare digits at rate 0_85`() {
        val audio = RecordingAudio()
        // Unconfined: the launched block runs eagerly on this thread, so the
        // recording below is the whole story and nothing has to be awaited.
        hubListenBalance(CoroutineScope(Dispatchers.Unconfined), audio, 42)
        assertEquals(
            listOf(
                RecordingAudio.Event.Unlock,
                // The bare digit string — fr-FR TTS reads "42" as
                // « quarante-deux ». Rate 0.85 is the app's ONE override of the
                // 0.94 default; the pitch stays at its 1.1 default.
                RecordingAudio.Event.Say("42", 0.85, 1.1),
            ),
            audio.events,
        )
    }

    @Test
    fun `the trial pill wording is entitlement_ts's, byte for byte`() {
        assertEquals(
            "Essai gratuit — 11 jours restants",
            trialNotice(Entitlement.Trial(daysLeft = 11, endsAt = 0)),
        )
        assertEquals("Dernier jour d'essai", trialNotice(Entitlement.Trial(daysLeft = 1, endsAt = 0)))
        assertNull(trialNotice(Entitlement.Paid))
        assertNull(trialNotice(Entitlement.Expired))
        assertNull(trialNotice(Entitlement.Unknown))
    }

    @Test
    fun `max-w-55 percent resolves against the header row, not the window`() {
        // index.css: card = min(480, vw − 32); the stage's px-5 takes 40 more;
        // max-w-md caps at 448.
        // 390 dp phone: min(448, 358 − 40) = 318 → 55 % = 174.9.
        assertEquals(318f, HubMetrics.headerWidth(390.dp).value, 0.001f)
        assertEquals(174.9f, HubMetrics.playerChipMaxWidth(390.dp).value, 0.001f)
        // 1024 dp tablet: the card caps at 480 → min(448, 440) = 440 → 242.
        assertEquals(440f, HubMetrics.headerWidth(1024.dp).value, 0.001f)
        assertEquals(242f, HubMetrics.playerChipMaxWidth(1024.dp).value, 0.001f)
    }
}

// --- Invariant 6 -------------------------------------------------------------------

class HubTapTargetTest {

    @Test
    fun `a level cell clears the platform touch-target floor on every shipping width`() {
        // The cells are square and their side is the column width, so this is
        // pure arithmetic — and it is invisible until somebody measures it on a
        // small phone, which is exactly how a 5-column grid breaks invariant 6.
        // 360 dp is the narrowest width current Android phones report.
        for (viewport in listOf(360.dp, 390.dp, 411.dp, 480.dp, 600.dp, 1024.dp)) {
            val side = HubMetrics.levelCellSide(viewport)
            assertTrue(
                side >= AspectSquareMetrics.tapTargetFloor,
                "a level cell is $side at $viewport, under the floor",
            )
        }
    }

    @Test
    fun `at 320 dp the web's own arithmetic lands under the floor - recorded, not fixed`() {
        // min(480, 320 − 32) = 288, less the stage's px-5 → 248, less four 8 dp
        // gutters over five columns = 43.2 dp. The PWA has exactly the same cell
        // at that width; forcing 48 here would either overflow the row or stop
        // the cell being square, and the fix that is actually right is fewer
        // columns on a narrow window — a layout change the web has not made and
        // this port must not make unilaterally. Pinned so the number is a
        // decision and not a surprise.
        assertEquals(43.2f, HubMetrics.levelCellSide(320.dp).value, 0.001f)
        assertTrue(HubMetrics.levelCellSide(320.dp) < AspectSquareMetrics.tapTargetFloor)
    }

    @Test
    fun `every level button carries a spoken label, whatever it pays`() {
        // contentDescription comes from the cell, so "has a label" is a property
        // of the data the button is built from, at every catalog row.
        for (row in EXERCISES) {
            for (points in listOf(0, 1, 3, 10)) {
                for (cell in hubLevelCells(row) { _, _ -> points }) {
                    assertTrue(cell.label.isNotBlank(), "${row.id.wire} level ${cell.level}")
                    assertTrue(cell.label.startsWith("Niveau ${cell.level},"))
                }
            }
        }
    }
}

// --- The source scan ------------------------------------------------------------------

private fun findHubSource(): File? {
    val suffix = "src/main/kotlin/fr/dappit/attrapelettres/ui/screens/HubView.kt"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val here = File(dir, suffix)
        if (here.isFile) return here
        val nested = File(dir, "ui/$suffix")
        if (nested.isFile) return nested
        val underApp = File(dir, "apps/game-android/ui/$suffix")
        if (underApp.isFile) return underApp
        dir = dir.parentFile
    }
    return null
}

/**
 * What the hub may not do.
 *
 * A host run cannot compose, so the properties a rendered tree would have shown
 * are asserted over the source instead — the same trick the iOS suite uses, and
 * the only reach these three invariants have into a @Composable body.
 */
class HubSourceScanTest {

    private fun code(): String {
        val file = assertNotNull(
            findHubSource(),
            "the scan could not locate HubView.kt from ${System.getProperty("user.dir")}",
        )
        return file.readLines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    @Test
    fun `the scan can find the file it is meant to scan`() {
        assertNotNull(findHubSource())
    }

    @Test
    fun `the hub renders the drawn icon and never the catalog row's own glyph`() {
        // Invariant 7. `ExerciseMeta` carries a decorative glyph that must stay
        // unused here: an implementer "simplifying" to it breaks the invariant
        // without touching :art's icon catalog, where the exhaustive `when`
        // lives.
        val code = code()
        assertTrue(code.contains("ExerciseIcon("))
        assertFalse(code.contains(".emoji"))
    }

    @Test
    fun `no lock, no gate, no money check in the hub`() {
        // Invariant 5's structural half, and invariant 11's. `canPlay` is
        // `RootView.open`'s business — a hub-side check would be exactly the
        // "if this level is available" regression this screen exists without.
        val code = code()
        for (forbidden in listOf("locked", "unlocked", "canPlay(", "requires")) {
            assertFalse(code.contains(forbidden), "HubView.kt contains $forbidden")
        }
        // …and the level row really is the unconditional range.
        assertTrue(code.contains("1..meta.levelCount"))
    }

    @Test
    fun `the hub reads the preview and never mints or spends a point`() {
        // Invariant 8's display half.
        val code = code()
        assertTrue(code.contains("profiles.preview("))
        for (forbidden in listOf("award(", "spend(", ".buy(", "sessionReward")) {
            assertFalse(code.contains(forbidden), "HubView.kt contains $forbidden")
        }
    }

    @Test
    fun `the catalog is rendered as authored, never sorted`() {
        // Invariant 8 again, from the other end: the authored order IS the
        // difficulty progression, and the reward gradient is monotone with it.
        val code = code()
        assertTrue(code.contains("EXERCISES.forEach"))
        for (forbidden in listOf("sortedBy", "sortedWith", ".sorted(", "reversed()", ".filter {")) {
            assertFalse(code.contains(forbidden), "HubView.kt contains $forbidden")
        }
    }

    @Test
    fun `the hub never reaches for Modifier clickable`() {
        // A12, module-wide: clickable fires on pointer-UP, after gesture
        // arbitration, behind Material's ripple.
        assertFalse(code().contains("clickable"))
    }
}
