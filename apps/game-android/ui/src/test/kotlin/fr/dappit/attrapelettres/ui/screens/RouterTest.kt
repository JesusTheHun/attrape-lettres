package fr.dappit.attrapelettres.ui.screens

import fr.dappit.attrapelettres.core.domain.AppRoute
import fr.dappit.attrapelettres.core.domain.Difficulty
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.ExerciseMeta
import fr.dappit.attrapelettres.core.domain.LetterMatchKind
import fr.dappit.attrapelettres.core.domain.SpellSyllableMode
import fr.dappit.attrapelettres.core.domain.SyllableGridMode
import fr.dappit.attrapelettres.core.domain.SyllableMode
import fr.dappit.attrapelettres.core.levels.EXERCISES
import fr.dappit.attrapelettres.core.licensing.Entitlement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The route table of `src/App.tsx`, walked value by value.
//
// Every expected value below is transcribed from the TypeScript — App.tsx (the
// gates, the dispatch, `next()`, `open()`), levels.ts (the EXERCISES array order
// and each row's capability fields) and licensing/entitlement.ts (`canPlay`) —
// never read back out of the Kotlin under test.

// --- The TSX-derived tables --------------------------------------------------

/**
 * `EXERCISES` in `levels.ts` — the array order, hand-transcribed. `next()` walks
 * this by index and the hub renders it top to bottom, so the order is behaviour.
 */
private val tsxCatalogOrder: List<String> = listOf(
    "first-letter",
    "find-sound",
    "hear-syllable",
    "pick-vowel",
    "fill-blank",
    "order-syllables",
    "find-intruder",
    "spell-sound",
    "spell-syllable",
    "spell-syllable-plus",
    "spell-two-syllables",
    "read-image",
    "match-case",
    "match-script",
    "sound-twins",
    "spell-syllable-plus-mixed",
    "spell-two-syllables-mixed",
)

/**
 * The dispatch of `App.tsx:89-121`, resolved by hand for every row: four id
 * checks (`read-image`, `spell-sound`, `find-sound`, `sound-twins`), then
 * `meta.grid`, `meta.spell` (+ `meta.mixed`), `meta.match`, and finally
 * `meta.mode` ?: first-letter.
 */
private val tsxDispatch: Map<String, ExerciseEngine> = mapOf(
    "first-letter" to ExerciseEngine.FirstLetter,
    "find-sound" to ExerciseEngine.FindSound,
    "hear-syllable" to ExerciseEngine.SyllableGrid(SyllableGridMode.HEAR),
    "pick-vowel" to ExerciseEngine.SyllableGrid(SyllableGridMode.VOWEL),
    "fill-blank" to ExerciseEngine.Assemble(SyllableMode.FILL_BLANK),
    "order-syllables" to ExerciseEngine.Assemble(SyllableMode.ORDER),
    "find-intruder" to ExerciseEngine.Assemble(SyllableMode.ORDER_DISTRACTOR),
    "spell-sound" to ExerciseEngine.SpellSound,
    "spell-syllable" to ExerciseEngine.SpellSyllable(SpellSyllableMode.LETTERS_EXACT, false),
    "spell-syllable-plus" to ExerciseEngine.SpellSyllable(SpellSyllableMode.LETTERS_EXTRA, false),
    "spell-two-syllables" to ExerciseEngine.SpellSyllable(SpellSyllableMode.LETTERS_TWO, false),
    "read-image" to ExerciseEngine.ReadImage,
    "match-case" to ExerciseEngine.LetterMatch(LetterMatchKind.CASE),
    "match-script" to ExerciseEngine.LetterMatch(LetterMatchKind.SCRIPT),
    "sound-twins" to ExerciseEngine.SoundTwins,
    "spell-syllable-plus-mixed" to
        ExerciseEngine.SpellSyllable(SpellSyllableMode.LETTERS_EXTRA, true),
    "spell-two-syllables-mixed" to
        ExerciseEngine.SpellSyllable(SpellSyllableMode.LETTERS_TWO, true),
)

private fun meta(
    id: ExerciseId,
    levelCount: Int = 3,
    mode: SyllableMode? = null,
    grid: SyllableGridMode? = null,
    spell: SpellSyllableMode? = null,
    mixed: Boolean = false,
    match: LetterMatchKind? = null,
): ExerciseMeta = ExerciseMeta(
    id = id,
    name = "x",
    emoji = "x",
    levelCount = levelCount,
    difficulty = Difficulty(1),
    mode = mode,
    grid = grid,
    spell = spell,
    mixed = mixed,
    match = match,
)

// --- Dispatch ----------------------------------------------------------------

class RouterDispatchTest {

    @Test
    fun `the catalog is the TSX catalog — 17 rows, same ids, same order`() {
        assertEquals(tsxCatalogOrder, EXERCISES.map { it.id.wire })
        assertEquals(ExerciseId.entries.toSet(), EXERCISES.map { it.id }.toSet())
    }

    @Test
    fun `every catalog row resolves to the engine the TSX if-chain picks`() {
        // This is the assertion `EconomyE2ETest.playCatalogRow` used to make
        // against its own private copy of the dispatch. It now makes it against
        // this function, and so does the shipping router.
        assertEquals(17, tsxDispatch.size)
        for (row in EXERCISES) {
            assertEquals(
                tsxDispatch[row.id.wire],
                engineFor(row),
                "${row.id.wire} dispatched to the wrong engine",
            )
        }
    }

    @Test
    fun `the id checks come FIRST — read-image with a mode field is still read-image`() {
        // No real row has this shape; the ORDER of the checks is the behaviour,
        // and it is unobservable on the shipped catalog precisely because the
        // four id rows carry none of the capability flags. Pinned here so a
        // "simplification" into capability-first is a red test, not a silent
        // change of engine on some future row.
        assertEquals(
            ExerciseEngine.ReadImage,
            engineFor(meta(ExerciseId.READ_IMAGE, mode = SyllableMode.ORDER)),
        )
        assertEquals(
            ExerciseEngine.SpellSound,
            engineFor(meta(ExerciseId.SPELL_SOUND, grid = SyllableGridMode.HEAR)),
        )
        assertEquals(
            ExerciseEngine.FindSound,
            engineFor(meta(ExerciseId.FIND_SOUND, spell = SpellSyllableMode.LETTERS_TWO)),
        )
        assertEquals(
            ExerciseEngine.SoundTwins,
            engineFor(meta(ExerciseId.SOUND_TWINS, match = LetterMatchKind.CASE)),
        )
    }

    @Test
    fun `grid outranks spell outranks match outranks mode`() {
        assertEquals(
            ExerciseEngine.SyllableGrid(SyllableGridMode.VOWEL),
            engineFor(
                meta(
                    ExerciseId.FILL_BLANK,
                    mode = SyllableMode.ORDER,
                    grid = SyllableGridMode.VOWEL,
                    spell = SpellSyllableMode.LETTERS_TWO,
                    match = LetterMatchKind.CASE,
                ),
            ),
        )
        assertEquals(
            ExerciseEngine.SpellSyllable(SpellSyllableMode.LETTERS_TWO, false),
            engineFor(
                meta(
                    ExerciseId.FILL_BLANK,
                    mode = SyllableMode.ORDER,
                    spell = SpellSyllableMode.LETTERS_TWO,
                    match = LetterMatchKind.CASE,
                ),
            ),
        )
        assertEquals(
            ExerciseEngine.LetterMatch(LetterMatchKind.CASE),
            engineFor(
                meta(ExerciseId.FILL_BLANK, mode = SyllableMode.ORDER, match = LetterMatchKind.CASE),
            ),
        )
        assertEquals(
            ExerciseEngine.Assemble(SyllableMode.ORDER),
            engineFor(meta(ExerciseId.FILL_BLANK, mode = SyllableMode.ORDER)),
        )
    }

    @Test
    fun `no fields at all falls through to first-letter`() {
        assertEquals(ExerciseEngine.FirstLetter, engineFor(meta(ExerciseId.FIRST_LETTER)))
    }

    @Test
    fun `mixed rides the spell dispatch — same mode, different flag`() {
        assertEquals(
            ExerciseEngine.SpellSyllable(SpellSyllableMode.LETTERS_EXTRA, true),
            engineFor(
                meta(
                    ExerciseId.SPELL_SYLLABLE_PLUS_MIXED,
                    spell = SpellSyllableMode.LETTERS_EXTRA,
                    mixed = true,
                ),
            ),
        )
        assertEquals(
            ExerciseEngine.SpellSyllable(SpellSyllableMode.LETTERS_EXTRA, false),
            engineFor(
                meta(ExerciseId.SPELL_SYLLABLE_PLUS, spell = SpellSyllableMode.LETTERS_EXTRA),
            ),
        )
    }
}

// --- « Suivant » -------------------------------------------------------------

class RouterNextTest {

    @Test
    fun `mid-exercise — level plus one, same exercise`() {
        assertEquals(
            AppRoute.Play(ExerciseId.FILL_BLANK, 2),
            nextRoute(AppRoute.Play(ExerciseId.FILL_BLANK, 1)),
        )
    }

    @Test
    fun `past the last level — level 1 of the NEXT catalog row`() {
        val last = EXERCISES[0].levelCount
        assertEquals(
            AppRoute.Play(ExerciseId.FIND_SOUND, 1),
            nextRoute(AppRoute.Play(ExerciseId.FIRST_LETTER, last)),
        )
    }

    @Test
    fun `every non-final row rolls into its successor — the whole ladder, in TSX order`() {
        for ((index, row) in EXERCISES.withIndex()) {
            if (index + 1 >= EXERCISES.size) continue
            val next = EXERCISES[index + 1]
            assertEquals(
                AppRoute.Play(next.id, 1),
                nextRoute(AppRoute.Play(row.id, row.levelCount)),
                "${row.id.wire} should roll into ${next.id.wire}",
            )
        }
    }

    @Test
    fun `past the final exercise's last level — back to the hub`() {
        val last = EXERCISES.last()
        assertEquals("spell-two-syllables-mixed", last.id.wire)
        assertEquals(AppRoute.Hub, nextRoute(AppRoute.Play(last.id, last.levelCount)))
    }

    @Test
    fun `a level past the end behaves like the last one`() {
        // The TSX's only check is `level < levelCount`.
        assertEquals(
            AppRoute.Play(ExerciseId.FIND_SOUND, 1),
            nextRoute(AppRoute.Play(ExerciseId.FIRST_LETTER, 99)),
        )
    }

    @Test
    fun `non-play routes have no next — hub`() {
        for (route in listOf(
            AppRoute.Hub,
            AppRoute.Dashboard,
            AppRoute.Shop,
            AppRoute.Pick,
            AppRoute.Paywall,
        )) {
            assertEquals(AppRoute.Hub, nextRoute(route))
        }
    }

    @Test
    fun `an exercise missing from the catalog bounces to the hub instead of trapping`() {
        // TSX: findIndex −1 ⇒ `meta.levelCount` crashes. The port bounces.
        val without = EXERCISES.filter { it.id != ExerciseId.FILL_BLANK }
        assertEquals(AppRoute.Hub, nextRoute(AppRoute.Play(ExerciseId.FILL_BLANK, 1), without))
        assertEquals(AppRoute.Hub, nextRoute(AppRoute.Play(ExerciseId.FILL_BLANK, 1), emptyList()))
    }

    @Test
    fun `the remount key is unique per exercise and level, and stable elsewhere`() {
        val seen = mutableSetOf<String>()
        for (row in EXERCISES) {
            for (level in 1..row.levelCount) {
                val key = AppRoute.Play(row.id, level).remountKey
                assertEquals("${row.id.wire}-$level", key)
                assertTrue(seen.add(key), "duplicate key $key")
            }
        }
        assertEquals("route", AppRoute.Hub.remountKey)
        assertEquals(AppRoute.Shop.remountKey, AppRoute.Dashboard.remountKey)
    }
}

// --- Gates -------------------------------------------------------------------

class RouterGateTest {

    @Test
    fun `the dev benches win over EVERYTHING, onboarding and roster included`() {
        assertEquals(
            ShellGate.DevStages,
            shellGate(DevScreen.STAGES, onboarded = false, activeId = null, chosen = false, route = AppRoute.Hub),
        )
        assertEquals(
            ShellGate.DevVo,
            shellGate(
                DevScreen.VO,
                onboarded = false,
                activeId = null,
                chosen = false,
                route = AppRoute.Play(ExerciseId.FILL_BLANK, 2),
            ),
        )
    }

    @Test
    fun `a fresh device meets the parent screen before anything else`() {
        assertEquals(
            ShellGate.Onboarding,
            shellGate(null, onboarded = false, activeId = null, chosen = false, route = AppRoute.Hub),
        )
        // …even if a route was somehow already stored.
        assertEquals(
            ShellGate.Onboarding,
            shellGate(null, onboarded = false, activeId = "c1", chosen = true, route = AppRoute.Shop),
        )
    }

    @Test
    fun `no active player — « Qui joue ? », whatever the stored route says`() {
        // The silent override: `Play` is stored, the child disappeared, the
        // roster screen wins. Do not reorder for tidiness.
        assertEquals(
            ShellGate.WhoIsPlaying,
            shellGate(
                null,
                onboarded = true,
                activeId = null,
                chosen = true,
                route = AppRoute.Play(ExerciseId.FIND_SOUND, 3),
            ),
        )
    }

    @Test
    fun `no species chosen — the first-run picker IS the app`() {
        assertEquals(
            ShellGate.FirstRunPicker,
            shellGate(
                null,
                onboarded = true,
                activeId = "c1",
                chosen = false,
                route = AppRoute.Play(ExerciseId.FIND_SOUND, 3),
            ),
        )
    }

    @Test
    fun `all gates passed — the stored route renders, whichever kind it is`() {
        for (route in listOf(
            AppRoute.Hub,
            AppRoute.Play(ExerciseId.READ_IMAGE, 2),
            AppRoute.Dashboard,
            AppRoute.Shop,
            AppRoute.Pick,
            AppRoute.Paywall,
        )) {
            assertEquals(
                ShellGate.Screen(route),
                shellGate(null, onboarded = true, activeId = "c1", chosen = true, route = route),
            )
        }
    }
}

// --- Invariant 5: all levels unlocked, always --------------------------------

class RouterUnlockedTest {

    /**
     * INVARIANT 5, stated as the thing that would break it: for EVERY
     * (exercise, level) pair the hub can render, with no preconditions
     * whatsoever — nothing cleared, no stars, a brand-new profile — the shell
     * lets the route through, the tap resolves to `Play`, and an engine is
     * chosen. There is no argument anywhere in this file that could carry
     * progress, which is the structural half of the same claim.
     */
    @Test
    fun `every exercise and level in the catalog is one tap away, with no precondition`() {
        var pairs = 0
        for (row in EXERCISES) {
            for (level in 1..row.levelCount) {
                pairs += 1

                // 1. The tap. A fresh trial, nothing cleared.
                val outcome = openOutcome(row.id, level, Entitlement.Trial(14, 0L))
                assertEquals(
                    OpenOutcome.Play(row.id, level),
                    outcome,
                    "${row.id.wire} level $level was not openable",
                )

                // 2. The shell. A brand-new child who has just chosen a species.
                assertEquals(
                    ShellGate.Screen(AppRoute.Play(row.id, level)),
                    shellGate(
                        dev = null,
                        onboarded = true,
                        activeId = "c1",
                        chosen = true,
                        route = AppRoute.Play(row.id, level),
                    ),
                    "${row.id.wire} level $level was gated by the shell",
                )

                // 3. An engine exists for it, and it is the one the TSX picks.
                assertEquals(
                    tsxDispatch[row.id.wire],
                    engineFor(row),
                    "${row.id.wire} level $level reaches the wrong engine",
                )
            }
        }
        assertTrue(pairs >= 17, "the catalog collapsed to $pairs (exercise, level) pairs")
    }

    @Test
    fun `level 1 and the last level of a row are equally reachable`() {
        // The shape a lock would take — "finish level 2 first" — would show up
        // as an asymmetry between the first and last level of a row. There is
        // none, because nothing here reads a ledger.
        for (row in EXERCISES) {
            assertEquals(
                ShellGate.Screen(AppRoute.Play(row.id, 1)),
                shellGate(null, true, "c1", true, AppRoute.Play(row.id, 1)),
            )
            assertEquals(
                ShellGate.Screen(AppRoute.Play(row.id, row.levelCount)),
                shellGate(null, true, "c1", true, AppRoute.Play(row.id, row.levelCount)),
            )
        }
    }

    @Test
    fun `even an out-of-range level routes rather than being clamped or refused`() {
        // Not a feature — a statement that the router owns no validation. The
        // catalog is the only authority on how many levels a row has, and the
        // hub is what renders `1..levelCount`.
        assertEquals(
            OpenOutcome.Play(ExerciseId.FIRST_LETTER, 99),
            openOutcome(ExerciseId.FIRST_LETTER, 99, Entitlement.Paid),
        )
    }
}

// --- Opening a level ---------------------------------------------------------

class RouterOpenTest {

    @Test
    fun `expired is the ONLY entitlement that diverts to the paywall`() {
        // entitlement.ts: `canPlay = e.status !== "expired"` — a blacklist of one.
        assertEquals(
            OpenOutcome.Paywall,
            openOutcome(ExerciseId.FIND_SOUND, 2, Entitlement.Expired),
        )
        assertEquals(
            OpenOutcome.Play(ExerciseId.FIND_SOUND, 2),
            openOutcome(ExerciseId.FIND_SOUND, 2, Entitlement.Paid),
        )
        assertEquals(
            OpenOutcome.Play(ExerciseId.FIND_SOUND, 2),
            openOutcome(ExerciseId.FIND_SOUND, 2, Entitlement.Trial(3, 0L)),
        )
    }

    @Test
    fun `a store that has not answered PLAYS — invariant 11`() {
        assertEquals(
            OpenOutcome.Play(ExerciseId.SPELL_SOUND, 1),
            openOutcome(ExerciseId.SPELL_SOUND, 1, Entitlement.Unknown),
        )
    }
}

// --- The dev-bench stand-in --------------------------------------------------

class RouterDevScreenTest {

    @Test
    fun `the intent extra resolves both benches and nothing else`() {
        assertEquals(DevScreen.STAGES, DevScreen.parse("stages"))
        assertEquals(DevScreen.VO, DevScreen.parse("vo"))
        assertEquals(null, DevScreen.parse(null))
        assertEquals(null, DevScreen.parse(""))
        // A typo must not strand the app on a blank screen.
        assertEquals(null, DevScreen.parse("bogus"))
        assertEquals(null, DevScreen.parse("STAGES"))
    }

    @Test
    fun `a non-debuggable build has no dev screens at all`() {
        assertEquals(null, DevScreen.parse("stages", debuggable = false))
        assertEquals(null, DevScreen.parse("vo", debuggable = false))
        assertEquals(DevScreen.STAGES, DevScreen.parse("stages", debuggable = true))
    }
}
