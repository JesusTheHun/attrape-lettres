package fr.dappit.attrapelettres.core.sync

import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.persistence.ChildProfile
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.Rev
import fr.dappit.attrapelettres.core.persistence.Roster
import fr.dappit.attrapelettres.core.persistence.SpeciesProgress
import fr.dappit.attrapelettres.core.persistence.StarCounters
import fr.dappit.attrapelettres.core.rewards.rewardFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* Port of apps/game-web/src/sync/merge.test.ts, case for case.                 */
/*                                                                             */
/* The scenario every test here is really about: Léa plays on Dad's phone in    */
/* the car and on Mum's phone at home. Neither device has signal. Whatever we   */
/* do when they finally meet, she must not lose a single star.                  */
/* -------------------------------------------------------------------------- */

private const val DAD = "dad-phone"
private const val MUM = "mum-phone"
private const val PAD = "family-ipad"

private val ZERO = Rev(0L, "")

private fun look(
    species: Species = Species.UNICORN,
    stage: Int = 0,
    colors: Map<String, String> = emptyMap(),
) = MascotConfig(
    species = species,
    stage = stage,
    colors = colors,
    styles = emptyMap(),
    accessories = emptyList(),
)

private fun progress(
    species: Species = Species.UNICORN,
    owned: List<String> = emptyList(),
    rev: Rev = ZERO,
    config: MascotConfig = look(species),
) = SpeciesProgress(config = config, owned = owned, rev = rev)

/** All five species blank, then whatever this test cares about on top. */
private fun speciesMap(vararg overrides: Pair<Species, SpeciesProgress>): Map<Species, SpeciesProgress> =
    Species.entries.associateWith { progress(it) } + overrides

private fun persisted(
    chosen: Boolean = false,
    current: Species = Species.UNICORN,
    currentRev: Rev = ZERO,
    species: Map<Species, SpeciesProgress> = speciesMap(),
    stars: StarCounters = StarCounters(earned = emptyMap(), spent = emptyMap()),
    clears: Map<String, Map<String, Int>> = emptyMap(),
) = PersistedProfile(
    chosen = chosen,
    current = current,
    currentRev = currentRev,
    species = species,
    stars = stars,
    clears = clears,
)

private fun kid(
    id: String,
    touchedAt: Long = 0L,
    profile: PersistedProfile = persisted(),
) = ChildProfile(id = id, name = id, nameRev = ZERO, touchedAt = touchedAt, profile = profile)

private fun roster(
    children: List<ChildProfile> = emptyList(),
    activeId: String? = null,
    removed: Map<String, Long> = emptyMap(),
) = Roster(children = children, activeId = activeId, removed = removed)

/** Léa earned `n` stars on `device` and spent `spent`. */
private fun earned(device: String, n: Int, spent: Int = 0): PersistedProfile =
    persisted(
        stars = StarCounters(
            earned = mapOf(device to n),
            spent = if (spent > 0) mapOf(device to spent) else emptyMap(),
        ),
    )

class MergeCounterTest {

    @Test
    fun `takes the fresher count per device, never the sum of the same device`() {
        // Dad's phone at 10 stars syncs, earns 3 more, syncs again. Not 23.
        assertEquals(mapOf(DAD to 13), mergeCounter(mapOf(DAD to 10), mapOf(DAD to 13)))
    }

    @Test
    fun `keeps both devices' earnings`() {
        // The whole point.
        val dad = earned(DAD, 10)
        val mum = earned(MUM, 3)
        assertEquals(13, balanceOf(mergeProfile(dad, mum).stars))
    }

    @Test
    fun `is commutative`() {
        // Sync order cannot change the answer.
        val a = earned(DAD, 10)
        val b = earned(MUM, 3)
        assertEquals(mergeProfile(a, b), mergeProfile(b, a))
    }

    @Test
    fun `is idempotent`() {
        // Syncing twice is not earning twice.
        val a = earned(DAD, 10)
        val b = earned(MUM, 3)
        val once = mergeProfile(a, b)
        assertEquals(once, mergeProfile(once, b))
        assertEquals(13, balanceOf(mergeProfile(mergeProfile(once, b), b).stars))
    }

    @Test
    fun `is order-independent across three devices`() {
        val a = earned(DAD, 10)
        val b = earned(MUM, 3)
        val c = earned(PAD, 7)
        val left = mergeProfile(mergeProfile(a, b), c)
        val right = mergeProfile(a, mergeProfile(b, c))
        assertEquals(20, balanceOf(left.stars))
        assertEquals(left, right)
    }

    @Test
    fun `bump only ever touches this device's own slot`() {
        val after = bump(mapOf(DAD to 10, MUM to 3), DAD, by = 5)
        assertEquals(mapOf(DAD to 15, MUM to 3), after)
    }
}

class MergeStarsTest {

    @Test
    fun `subtracts spending from earnings wherever each happened`() {
        val dad = earned(DAD, 10, spent = 4)
        val mum = earned(MUM, 6)
        assertEquals(12, balanceOf(mergeProfile(dad, mum).stars))
    }

    @Test
    fun `floors at zero when two offline devices both spend the same stars`() {
        // Both see 10, both buy an 8-star item. 10 earned, 16 spent.
        val dad = StarCounters(earned = mapOf(PAD to 10), spent = mapOf(DAD to 8))
        val mum = StarCounters(earned = mapOf(PAD to 10), spent = mapOf(MUM to 8))
        val merged = mergeStars(dad, mum)

        assertEquals(mapOf(DAD to 8, MUM to 8), merged.spent)
        // The arithmetic says -6. The child sees 0 and keeps both items: we
        // never claw a purchase back from a six-year-old to balance a ledger.
        assertEquals(0, balanceOf(merged))
    }

    @Test
    fun `keeps both items bought during that overdraw`() {
        val dad = persisted(species = speciesMap(Species.UNICORN to progress(owned = listOf("horn"))))
        val mum = persisted(species = speciesMap(Species.UNICORN to progress(owned = listOf("tail"))))
        assertEquals(
            listOf("horn", "tail"),
            mergeProfile(dad, mum).species.getValue(Species.UNICORN).owned,
        )
    }
}

class MergeClearsTest {

    @Test
    fun `sums clears across devices so the reward curve keeps decaying`() {
        val dad = persisted(clears = mapOf("read-image:1" to mapOf(DAD to 1)))
        val mum = persisted(clears = mapOf("read-image:1" to mapOf(MUM to 1)))
        val ledger = ledgerOf(mergeProfile(dad, mum).clears)

        // Two clears really happened, so the next one pays the third rung —
        // playing the same level on the other phone must not re-open the
        // 10-star jackpot. This is the one counter that is summed, not maxed.
        assertEquals(2, ledger["read-image:1"])
        assertEquals(rewardFor(2), rewardFor(ledger.getValue("read-image:1")))
        assertTrue(rewardFor(ledger.getValue("read-image:1")) < rewardFor(0))
    }

    @Test
    fun `merges independent levels without touching each other`() {
        val dad = persisted(clears = mapOf("read-image:1" to mapOf(DAD to 3)))
        val mum = persisted(clears = mapOf("syllable-grid:2" to mapOf(MUM to 1)))
        assertEquals(
            mapOf("read-image:1" to 3, "syllable-grid:2" to 1),
            ledgerOf(mergeProfile(dad, mum).clears),
        )
    }
}

class MergeCosmeticsTest {

    @Test
    fun `keeps the later mascot look`() {
        val older = progress(
            rev = Rev(100L, DAD),
            config = look(stage = 2, colors = mapOf("hornColor" to "#F0A")),
        )
        val newer = progress(
            rev = Rev(200L, MUM),
            config = look(stage = 5, colors = mapOf("hornColor" to "#0FA")),
        )
        val a = persisted(species = speciesMap(Species.UNICORN to older))
        val b = persisted(species = speciesMap(Species.UNICORN to newer))

        assertEquals(5, mergeProfile(a, b).species.getValue(Species.UNICORN).config.stage)
        assertEquals(5, mergeProfile(b, a).species.getValue(Species.UNICORN).config.stage)
    }

    @Test
    fun `unions owned items even when the other side's look wins`() {
        val loser = progress(owned = listOf("horn"), rev = Rev(100L, DAD))
        val winner = progress(owned = listOf("tail"), rev = Rev(200L, MUM))
        val merged = mergeProfile(
            persisted(species = speciesMap(Species.UNICORN to loser)),
            persisted(species = speciesMap(Species.UNICORN to winner)),
        )
        // Look came from Mum's phone; nothing bought on Dad's was dropped.
        assertEquals(listOf("horn", "tail"), merged.species.getValue(Species.UNICORN).owned)
    }

    @Test
    fun `breaks an exact timestamp tie the same way from both sides`() {
        val a = persisted(current = Species.FOX, currentRev = Rev(500L, DAD))
        val b = persisted(current = Species.CAT, currentRev = Rev(500L, MUM))
        assertEquals(mergeProfile(a, b).current, mergeProfile(b, a).current)
    }

    @Test
    fun `never un-picks a species once chosen`() {
        val picked = persisted(chosen = true)
        val fresh = persisted(chosen = false)
        assertTrue(mergeProfile(picked, fresh).chosen)
        assertTrue(mergeProfile(fresh, picked).chosen)
    }

    @Test
    fun `mergeOwned is a set union that keeps the local order first`() {
        assertEquals(listOf("a", "b", "c"), mergeOwned(listOf("a", "b"), listOf("b", "c")))
    }
}

class MergeRosterTest {

    @Test
    fun `brings in a sibling created on the other device`() {
        val here = roster(children = listOf(kid("lea")))
        val there = roster(children = listOf(kid("tom")))
        assertEquals(listOf("lea", "tom"), mergeRoster(here, there).children.map { it.id }.sorted())
    }

    @Test
    fun `merges the same child's progress from both devices`() {
        val here = roster(children = listOf(kid("lea", profile = earned(DAD, 10))))
        val there = roster(children = listOf(kid("lea", profile = earned(MUM, 3))))
        val merged = mergeRoster(here, there)
        assertEquals(1, merged.children.size)
        assertEquals(13, balanceOf(merged.children[0].profile.stars))
    }

    @Test
    fun `never adopts the other device's active player`() {
        val here = roster(children = listOf(kid("lea")), activeId = "lea")
        val there = roster(children = listOf(kid("lea"), kid("tom")), activeId = "tom")
        // Tom being at the wheel on Mum's phone says nothing about this tablet.
        assertEquals("lea", mergeRoster(here, there).activeId)
    }

    @Test
    fun `clears activeId if that child was deleted elsewhere`() {
        val here = roster(children = listOf(kid("lea", touchedAt = 10L)), activeId = "lea")
        val there = roster(removed = mapOf("lea" to 50L))
        val merged = mergeRoster(here, there)
        assertTrue(merged.children.isEmpty())
        assertEquals(null, merged.activeId)
    }

    @Test
    fun `propagates a delete to the other device`() {
        val here = roster(children = listOf(kid("lea", touchedAt = 10L), kid("tom", touchedAt = 10L)))
        val there = roster(children = listOf(kid("tom", touchedAt = 10L)), removed = mapOf("lea" to 99L))
        assertEquals(listOf("tom"), mergeRoster(here, there).children.map { it.id })
    }

    @Test
    fun `refuses a delete that would erase play done afterwards`() {
        // Parent tidies the roster on Mum's phone at t=50. Léa keeps playing on
        // the iPad until t=200. A vanished child is unrecoverable; a resurrected
        // one is two taps. The tie goes to keeping the data.
        val here = roster(children = listOf(kid("lea", touchedAt = 200L, profile = earned(PAD, 40))))
        val there = roster(removed = mapOf("lea" to 50L))
        val merged = mergeRoster(here, there)
        assertEquals(1, merged.children.size)
        assertEquals(40, balanceOf(merged.children[0].profile.stars))
    }

    @Test
    fun `keeps tombstones so a third device also honours the delete`() {
        val here = roster(children = listOf(kid("lea", touchedAt = 10L)))
        val there = roster(removed = mapOf("lea" to 99L))
        val merged = mergeRoster(here, there)
        // The iPad still has Léa and no tombstone; merging must not resurrect her.
        val ipad = roster(children = listOf(kid("lea", touchedAt = 10L)))
        assertTrue(mergeRoster(merged, ipad).children.isEmpty())
    }

    @Test
    fun `is idempotent and agrees on membership in either direction`() {
        val here = roster(children = listOf(kid("lea", profile = earned(DAD, 10))))
        val there = roster(children = listOf(kid("lea", profile = earned(MUM, 3)), kid("tom")))
        val once = mergeRoster(here, there)
        assertEquals(once, mergeRoster(once, there))
        assertEquals(
            once.children.map { it.id }.sorted(),
            mergeRoster(there, here).children.map { it.id }.sorted(),
        )
    }
}
