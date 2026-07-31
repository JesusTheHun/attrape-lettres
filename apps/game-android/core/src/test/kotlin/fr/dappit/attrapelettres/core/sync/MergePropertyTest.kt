package fr.dappit.attrapelettres.core.sync

import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.persistence.ChildProfile
import fr.dappit.attrapelettres.core.persistence.PersistedProfile
import fr.dappit.attrapelettres.core.persistence.Rev
import fr.dappit.attrapelettres.core.persistence.Roster
import fr.dappit.attrapelettres.core.persistence.SpeciesProgress
import fr.dappit.attrapelettres.core.persistence.StarCounters
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* Property tests — the reason Merge.kt is pure (no clock, no storage, no       */
/* network: values in, values out) is precisely so these can run as ordinary    */
/* JUnit, in milliseconds, with no emulator. The example-based suite            */
/* (MergeTest) pins the scenarios; this suite pins the ALGEBRA the sync layer   */
/* relies on: merge in any order, twice, or after a rollback, same answer — and */
/* two devices that both play offline lose nothing.                            */
/*                                                                             */
/* One deliberate weakening: `mergeOwned` keeps the LOCAL side's order first,   */
/* and `mergeRoster` keeps local children's positions and the local `activeId`. */
/* Those are observable, wanted asymmetries — so commutativity and              */
/* associativity for the document merges are asserted up to a canonical form    */
/* (owned sorted, children sorted by id, activeId dropped), while idempotence   */
/* — the TS shape `merge(merge(a,b), b) == merge(a,b)` — is asserted STRICTLY.  */
/* -------------------------------------------------------------------------- */

private val devices = listOf("dad-phone", "mum-phone", "family-ipad")
private val childIds = listOf("lea", "tom", "zoe")
private val itemPool = listOf("horn", "tail", "hat", "scarf")
private val ledgerKeys = listOf("read-image:1", "syllable-grid:2", "first-letter:1")

private const val ITERATIONS = 500

/* -- seeded generators ------------------------------------------------------*/

/**
 * `kotlin.random.Random(seed)` rather than the injected `RandomSource`: these
 * tests need reproducibility and nothing else, and borrowing the game's own
 * source would couple the merge suite to a module it has no business knowing.
 */
private class Gen(seed: Int) {
    private val rng = Random(seed)
    fun bool(): Boolean = rng.nextBoolean()
    fun below(bound: Int): Int = rng.nextInt(bound)
}

private fun genCounter(g: Gen): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    for (d in devices) if (g.bool()) out[d] = g.below(6)
    return out
}

private fun genStars(g: Gen): StarCounters =
    StarCounters(earned = genCounter(g), spent = genCounter(g))

private fun genClears(g: Gen): Map<String, Map<String, Int>> {
    val out = LinkedHashMap<String, Map<String, Int>>()
    for (key in ledgerKeys) if (g.bool()) out[key] = genCounter(g)
    return out
}

/**
 * Small `at` range on purpose: exact-timestamp ties must actually occur so the
 * deterministic `by` tie-break is exercised on every run.
 */
private fun genRev(g: Gen): Rev = Rev(g.below(4).toLong(), devices[g.below(devices.size)])

private fun genOwned(g: Gen): List<String> = itemPool.filter { g.bool() }

/**
 * A `Rev` uniquely identifies the write it stamps — a device mints a fresh
 * stamp for every LWW write — so two replicas can only ever hold the SAME stamp
 * for the SAME payload. The generators keep that real-world invariant by
 * deriving every LWW payload from its stamp. Without it, two fully-equal stamps
 * guarding different payloads make the `>=` tie-break side-dependent
 * (`merge(a,b)` keeps a's payload, `merge(b,a)` keeps b's) — a state no
 * sequence of real writes can produce. The TS `laterRev` behaves identically;
 * this is a generator constraint, not a merge fix.
 */
private fun stamped(rev: Rev): Int {
    val i = devices.indexOf(rev.by)
    return rev.at.toInt() * 7 + if (i >= 0) i else 5
}

private fun genProgress(species: Species, g: Gen): SpeciesProgress {
    val rev = genRev(g)
    val n = stamped(rev)
    return SpeciesProgress(
        config = MascotConfig(
            species = species,
            stage = n % 10,
            colors = if (n % 2 == 0) mapOf("hornColor" to "#F0A") else emptyMap(),
            styles = emptyMap(),
            accessories = emptyList(),
        ),
        owned = genOwned(g),
        rev = rev,
    )
}

private fun blankProgress(species: Species): SpeciesProgress =
    SpeciesProgress(
        config = MascotConfig(
            species = species,
            stage = 0,
            colors = emptyMap(),
            styles = emptyMap(),
            accessories = emptyList(),
        ),
        owned = emptyList(),
        rev = Rev(0L, ""),
    )

private fun genProfile(g: Gen): PersistedProfile {
    // All five slots present, like normalised stored data; some of them touched.
    val species = Species.entries.associateWith { s ->
        if (g.bool()) genProgress(s, g) else blankProgress(s)
    }
    val currentRev = genRev(g)
    return PersistedProfile(
        chosen = g.bool(),
        current = Species.entries[stamped(currentRev) % Species.entries.size],
        currentRev = currentRev,
        species = species,
        stars = genStars(g),
        clears = genClears(g),
    )
}

private fun genChild(id: String, g: Gen): ChildProfile =
    ChildProfile(
        id = id,
        name = id,
        nameRev = genRev(g),
        touchedAt = g.below(8).toLong(),
        profile = genProfile(g),
    )

private fun genRoster(g: Gen): Roster {
    val children = childIds.filter { g.bool() }.map { genChild(it, g) }
    val activeId = if (children.isEmpty() || g.bool()) null else children[g.below(children.size)].id
    val removed = LinkedHashMap<String, Long>()
    for (id in childIds) if (g.below(4) == 0) removed[id] = g.below(8).toLong()
    return Roster(children = children, activeId = activeId, removed = removed)
}

/* -- canonical form for order-insensitive comparison ------------------------*/

private fun canonProgress(p: SpeciesProgress): SpeciesProgress = p.copy(owned = p.owned.sorted())

private fun canonProfile(p: PersistedProfile): PersistedProfile =
    p.copy(species = p.species.mapValues { (_, v) -> canonProgress(v) })

private fun canonChild(c: ChildProfile): ChildProfile = c.copy(profile = canonProfile(c.profile))

private fun canonRoster(r: Roster): Roster =
    Roster(
        children = r.children.map { canonChild(it) }.sortedBy { it.id },
        activeId = null, // deliberately local; excluded from every symmetry claim
        removed = r.removed,
    )

class MergeCounterPropertyTest {

    @Test
    fun `commutative, associative, idempotent and never decreasing`() {
        val g = Gen(0xC0FFEE01.toInt())
        repeat(ITERATIONS) {
            val a = genCounter(g)
            val b = genCounter(g)
            val c = genCounter(g)
            val ab = mergeCounter(a, b)

            assertEquals(ab, mergeCounter(b, a))
            assertEquals(mergeCounter(ab, c), mergeCounter(a, mergeCounter(b, c)))
            assertEquals(ab, mergeCounter(ab, b))
            assertEquals(a, mergeCounter(a, a))
            // A device's slot is only ever advanced by a merge, never rolled back.
            for ((d, n) in a) assertTrue(ab.getValue(d) >= n)
            for ((d, n) in b) assertTrue(ab.getValue(d) >= n)
        }
    }
}

class MergeProfilePropertyTest {

    @Test
    fun `balance never goes negative`() {
        val g = Gen(0xC0FFEE02.toInt())
        repeat(ITERATIONS) {
            assertTrue(balanceOf(mergeStars(genStars(g), genStars(g))) >= 0)
        }
    }

    @Test
    fun `commutative and associative up to owned order`() {
        val g = Gen(0xC0FFEE03.toInt())
        repeat(ITERATIONS) {
            val a = genProfile(g)
            val b = genProfile(g)
            val c = genProfile(g)

            assertEquals(canonProfile(mergeProfile(a, b)), canonProfile(mergeProfile(b, a)))
            assertEquals(
                canonProfile(mergeProfile(mergeProfile(a, b), c)),
                canonProfile(mergeProfile(a, mergeProfile(b, c))),
            )
        }
    }

    @Test
    fun `strictly idempotent`() {
        val g = Gen(0xC0FFEE04.toInt())
        repeat(ITERATIONS) {
            val a = genProfile(g)
            val b = genProfile(g)
            val ab = mergeProfile(a, b)
            assertEquals(ab, mergeProfile(ab, b))
            assertEquals(ab, mergeProfile(ab, a))
        }
    }

    @Test
    fun `owned never loses an element and chosen never un-picks`() {
        val g = Gen(0xC0FFEE05.toInt())
        repeat(ITERATIONS) {
            val a = genProfile(g)
            val b = genProfile(g)
            val ab = mergeProfile(a, b)
            for (s in Species.entries) {
                val merged = ab.species.getValue(s).owned
                for (item in a.species.getValue(s).owned) assertTrue(item in merged)
                for (item in b.species.getValue(s).owned) assertTrue(item in merged)
            }
            assertEquals(a.chosen || b.chosen, ab.chosen)
        }
    }

    @Test
    fun `per-device star and clear slots never decrease`() {
        val g = Gen(0xC0FFEE06.toInt())
        repeat(ITERATIONS) {
            val a = genProfile(g)
            val b = genProfile(g)
            val ab = mergeProfile(a, b)
            for ((d, n) in a.stars.earned) assertTrue(ab.stars.earned.getValue(d) >= n)
            for ((d, n) in b.stars.earned) assertTrue(ab.stars.earned.getValue(d) >= n)
            for ((key, counter) in a.clears) {
                for ((d, n) in counter) assertTrue(ab.clears.getValue(key).getValue(d) >= n)
            }
            for ((key, counter) in b.clears) {
                for ((d, n) in counter) assertTrue(ab.clears.getValue(key).getValue(d) >= n)
            }
        }
    }
}

class MergeRosterPropertyTest {

    @Test
    fun `commutative up to order and activeId`() {
        val g = Gen(0xC0FFEE07.toInt())
        repeat(ITERATIONS) {
            val a = genRoster(g)
            val b = genRoster(g)
            assertEquals(canonRoster(mergeRoster(a, b)), canonRoster(mergeRoster(b, a)))
        }
    }

    /**
     * NB: `mergeRoster` is deliberately NOT associative in the corner where a
     * tombstone-drop interleaves with a later touch: in `merge(merge(a,b), c)` a
     * child tombstoned against a and b is dropped BEFORE c resurrects them
     * (losing a's and b's contributions to the resurrected child), while in
     * `merge(a, merge(b,c))` the resurrection happens first and b's contribution
     * survives. The TS behaves identically — the delete rule trades algebraic
     * purity for "never erase play". What sync actually relies on is EVENTUAL
     * CONVERGENCE under repetition ("safe to fire as often as we like"): each
     * resume re-runs pull → merge → push, and after every replica has been
     * folded in twice, the household document is a fixed point that no replica —
     * from either side, in any fold order — can perturb. A single pass is not
     * enough for exactly the resurrection corner above; the second pass
     * re-absorbs what the drop discarded.
     */
    @Test
    fun `repeated gossip converges to a fixed point, no fold order matters`() {
        val g = Gen(0xC0FFEE0A.toInt())
        repeat(ITERATIONS) {
            val a = genRoster(g)
            val b = genRoster(g)
            val c = genRoster(g)

            // Two full rounds of pull -> merge -> push, like two resumes each.
            val doc = listOf(b, c, a, b, c).fold(a) { acc, r -> mergeRoster(acc, r) }

            for (replica in listOf(a, b, c)) {
                assertEquals(canonRoster(doc), canonRoster(mergeRoster(doc, replica)))
                assertEquals(canonRoster(doc), canonRoster(mergeRoster(replica, doc)))
            }

            // Fold order cannot change the converged document.
            val reversed = listOf(b, a, c, b, a).fold(c) { acc, r -> mergeRoster(acc, r) }
            assertEquals(canonRoster(doc), canonRoster(reversed))
        }
    }

    @Test
    fun `strictly idempotent`() {
        val g = Gen(0xC0FFEE08.toInt())
        repeat(ITERATIONS) {
            val a = genRoster(g)
            val b = genRoster(g)
            val ab = mergeRoster(a, b)
            assertEquals(ab, mergeRoster(ab, b))
        }
    }

    @Test
    fun `tombstones only drop untouched children and never regress`() {
        val g = Gen(0xC0FFEE09.toInt())
        repeat(ITERATIONS) {
            val a = genRoster(g)
            val b = genRoster(g)
            val ab = mergeRoster(a, b)

            // Tombstones merge by max and are kept forever.
            for ((id, at) in a.removed) assertTrue(ab.removed.getValue(id) >= at)
            for ((id, at) in b.removed) assertTrue(ab.removed.getValue(id) >= at)

            // Every survivor beats (or ties) its tombstone; every dropped child
            // was strictly older than one.
            for (c in ab.children) assertTrue((ab.removed[c.id] ?: 0L) <= c.touchedAt)
            val survivors = ab.children.map { it.id }.toSet()
            for (c in a.children + b.children) {
                if (c.id in survivors) continue
                val tombstone = ab.removed[c.id]
                assertNotNull(tombstone)
                assertTrue(tombstone > c.touchedAt)
            }
        }
    }
}

class OfflineConvergenceTest {

    /**
     * The invariant-9 story end to end: one child, two devices, both offline,
     * both playing — reconciliation loses NOTHING, in either merge order.
     */
    @Test
    fun `two devices that both play offline lose nothing`() {
        val base = PersistedProfile(
            chosen = true,
            current = Species.UNICORN,
            currentRev = Rev(1L, "dad-phone"),
            species = Species.entries.associateWith { blankProgress(it) },
            stars = StarCounters(earned = mapOf("family-ipad" to 10), spent = emptyMap()),
            clears = mapOf("read-image:1" to mapOf("family-ipad" to 1)),
        )

        // Dad's phone, offline: two sessions and a 3-star purchase.
        val dadUnicorn = base.species.getValue(Species.UNICORN)
        val dad = base.copy(
            stars = StarCounters(
                earned = bump(base.stars.earned, "dad-phone", by = 5),
                spent = bump(base.stars.spent, "dad-phone", by = 3),
            ),
            clears = base.clears + mapOf(
                "read-image:1" to bump(base.clears.getValue("read-image:1"), "dad-phone", by = 1),
            ),
            species = base.species + mapOf(
                Species.UNICORN to dadUnicorn.copy(owned = dadUnicorn.owned + "horn"),
            ),
        )

        // Mum's phone, offline: one session on another level.
        val mumUnicorn = base.species.getValue(Species.UNICORN)
        val mum = base.copy(
            stars = StarCounters(
                earned = bump(base.stars.earned, "mum-phone", by = 2),
                spent = base.stars.spent,
            ),
            clears = base.clears + mapOf(
                "syllable-grid:2" to bump(emptyMap(), "mum-phone", by = 1),
            ),
            species = base.species + mapOf(
                Species.UNICORN to mumUnicorn.copy(owned = mumUnicorn.owned + "tail"),
            ),
        )

        for (merged in listOf(mergeProfile(dad, mum), mergeProfile(mum, dad))) {
            // 10 + 5 + 2 earned, 3 spent — every star from every device.
            assertEquals(14, balanceOf(merged.stars))
            // Clears SUM: the iPad's and Dad's sessions on the same level are two
            // clears, so the curve keeps decaying.
            val ledger = ledgerOf(merged.clears)
            assertEquals(2, ledger["read-image:1"])
            assertEquals(1, ledger["syllable-grid:2"])
            // Both purchases survive.
            assertEquals(
                setOf("horn", "tail"),
                merged.species.getValue(Species.UNICORN).owned.toSet(),
            )
        }
    }
}
