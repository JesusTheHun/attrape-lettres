package fr.dappit.attrapelettres.core.persistence

import fr.dappit.attrapelettres.core.domain.CustomizationCategory
import fr.dappit.attrapelettres.core.domain.CustomizationOption
import fr.dappit.attrapelettres.core.domain.Difficulty
import fr.dappit.attrapelettres.core.domain.ExerciseId
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.platform.InMemoryKVStore
import fr.dappit.attrapelettres.core.platform.KVStore
import fr.dappit.attrapelettres.core.platform.MutableTimeSource
import fr.dappit.attrapelettres.core.rewards.REWARD_CURVE
import fr.dappit.attrapelettres.core.rewards.rewardFor
import fr.dappit.attrapelettres.core.sync.StubSyncTransport
import fr.dappit.attrapelettres.core.sync.SyncClient
import fr.dappit.attrapelettres.core.sync.SyncTransport
import fr.dappit.attrapelettres.core.sync.WireRoster
import fr.dappit.attrapelettres.core.sync.balanceOf
import fr.dappit.attrapelettres.core.sync.mergeProfile
import fr.dappit.attrapelettres.core.sync.toWire
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/* -------------------------------------------------------------------------- */
/* Port of `src/hooks/useProfile.test.tsx`, via ProfileStoreTests.swift, against */
/* `ProfileStore(InMemoryKVStore(), difficultyOf, MutableTimeSource, { device })`.*/
/*                                                                             */
/* Every gameplay call below is a plain synchronous method call whose returned  */
/* value and resulting state are asserted in the same test body — that is       */
/* invariant 1's test-level guard for this scope: nothing on the award/spend    */
/* path can await, so nothing here needs `runTest` except the two sync specs.   */
/*                                                                             */
/* The suites that matter most are the counter ones. They are the executable    */
/* form of invariants 8 and 9: points come only from `sessionReward`, a device  */
/* writes only its own counter slot, and no total is ever stored.               */
/* -------------------------------------------------------------------------- */

private const val DEVICE = "this-phone"
private const val NOW: Millis = 1_700_000_000_000

/**
 * The reward weights the web test leans on, from `EXERCISES` in levels.ts:
 * `first-letter` is 0 (training — the curve, never a bonus) and `read-image` is
 * 2. The store takes this lookup injected (its build package does not depend on
 * the ladders), so the fixture states only what these specs assert; the full
 * table is `levels.ts`'s to own and `LevelsTest`'s to prove.
 */
private fun difficultyOf(id: ExerciseId): Difficulty = when (id) {
    ExerciseId.FIRST_LETTER -> Difficulty.TRAINING
    ExerciseId.READ_IMAGE -> Difficulty(2)
    else -> Difficulty(1)
}

private val HORN = CustomizationOption(
    id = "unicorn.horn.rainbow",
    species = Species.UNICORN,
    category = CustomizationCategory.COLOR,
    slot = "hornColor",
    value = "#F0A",
    name = "Corne arc-en-ciel",
    cost = 4,
)

private fun makeStore(
    kv: KVStore = InMemoryKVStore(),
    clock: MutableTimeSource = MutableTimeSource(NOW),
    sync: SyncClient? = null,
) = ProfileStore(kv = kv, difficultyOf = ::difficultyOf, time = clock, device = { DEVICE }, sync = sync)

/** The exact v3 fixture from `useProfile.test.tsx`, byte for byte. */
private val v3RosterJson =
    """{"children":[{"id":"lea-v3","name":"Léa","profile":{"chosen":true,"current":"fox","species":{"fox":{"config":{"species":"fox","stage":3,"colors":{"furColor":"#F80"},"styles":{},"accessories":[]},"owned":["fox.fur.orange"]}},"balance":17,"ledger":{"read-image:1":2}}}],"activeId":"lea-v3"}"""

/** The exact v1 fixture from `useProfile.test.tsx`, byte for byte. */
private val v1ProfileJson =
    """{"chosen":true,"config":{"species":"cat","stage":1,"colors":{},"styles":{},"accessories":[]},"balance":5,"ledger":{"first-letter:1":1},"owned":["cat.whiskers.long"]}"""

class ProfileStoreRosterGateTest {

    @Test
    fun `starts with no children and no active player`() {
        val store = makeStore()
        assertTrue(store.children.isEmpty())
        assertNull(store.activeId)
        // The exposed profile is a safe default until someone is playing.
        assertFalse(store.profile.chosen)
        assertEquals(0, store.profile.balance)
    }

    @Test
    fun `createChild adds a player and makes them active, species unchosen`() {
        val store = makeStore()
        store.createChild("Léa")
        assertEquals(1, store.children.size)
        assertNotNull(store.activeId)
        assertFalse(store.profile.chosen)
    }

    @Test
    fun `switchChild returns to the welcome screen, selectChild resumes`() {
        val store = makeStore()
        store.createChild("Léa")
        val id = assertNotNull(store.activeId)
        store.switchChild()
        assertNull(store.activeId)
        store.selectChild(id)
        assertEquals(id, store.activeId)
    }

    @Test
    fun `renameChild changes the name trimmed and ignores empty input`() {
        val store = makeStore()
        store.createChild("Léa")
        val id = assertNotNull(store.activeId)
        store.renameChild(id, "  Léo  ")
        assertEquals("Léo", store.children[0].name)
        store.renameChild(id, "   ")
        assertEquals("Léo", store.children[0].name) // unchanged
    }

    @Test
    fun `renameChild clamps to 14 characters but createChild does not`() {
        // TS asymmetry, ported as-is: only renameChild clamps.
        val long = "Anne-Charlotte-Éléonore"
        val store = makeStore()
        store.createChild(long)
        assertEquals(long, store.children[0].name)
        val id = assertNotNull(store.activeId)
        store.renameChild(id, long)
        assertEquals(long.take(14), store.children[0].name)
    }

    @Test
    fun `a blank name falls back to Joueur`() {
        val store = makeStore()
        store.createChild("   ")
        assertEquals("Joueur", store.children[0].name)
    }
}

/* -------------------------------------------------------------------------- */
/* Points. Invariant 8 lives here: every point in these assertions came out of  */
/* `sessionReward`, and there is no other way to put one in a profile.          */
/* -------------------------------------------------------------------------- */

class ProfileStorePointsTest {

    @Test
    fun `award grants the curve value and decays on repeat`() {
        val store = makeStore()
        store.createChild("Léa")
        val first = store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        val second = store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        assertEquals(REWARD_CURVE[0], first)
        assertEquals(REWARD_CURVE[1], second)
        assertEquals(REWARD_CURVE[0] + REWARD_CURVE[1], store.profile.balance)
    }

    @Test
    fun `first-try rounds add the accuracy bonus on top of the curve`() {
        val store = makeStore()
        store.createChild("Léa")
        // read-image has difficulty 2: a full-perfect 8-round run = curve + 2.
        val pts = store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 8, totalRounds = 8)
        assertEquals(REWARD_CURVE[0] + 2, pts)
    }

    @Test
    fun `training exercises pay the curve with no accuracy bonus`() {
        val store = makeStore()
        store.createChild("Léa")
        // first-letter is difficulty 0: the curve, and not one point more.
        val perfect = store.award(ExerciseId.FIRST_LETTER, 1, perfectRounds = 8, totalRounds = 8)
        val sloppy = store.award(ExerciseId.FIRST_LETTER, 2, perfectRounds = 0, totalRounds = 8)
        assertEquals(REWARD_CURVE[0], perfect)
        // A second LEVEL, so the curve resets — and careful play earned exactly
        // what every-round-missed play earned. That is what difficulty 0 buys.
        assertEquals(REWARD_CURVE[0], sloppy)
        assertEquals(REWARD_CURVE[0] * 2, store.profile.balance)
        assertEquals(1, store.profile.ledger["first-letter:1"])
        // …and the hub promises the next one in advance, like any other row.
        assertEquals(REWARD_CURVE[1], store.preview(ExerciseId.FIRST_LETTER, 1))
    }

    @Test
    fun `a clear recorded twice moves the reward curve exactly one step`() {
        val store = makeStore()
        store.createChild("Léa")
        assertEquals(REWARD_CURVE[0], store.preview(ExerciseId.READ_IMAGE, 1))

        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        assertEquals(1, store.profile.ledger["read-image:1"])
        assertEquals(REWARD_CURVE[1], store.preview(ExerciseId.READ_IMAGE, 1))

        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        assertEquals(2, store.profile.ledger["read-image:1"])
        assertEquals(REWARD_CURVE[2], store.preview(ExerciseId.READ_IMAGE, 1))

        // One clear = one step, never two: the jackpot decays at exactly the
        // rate the curve says, so replaying a level cannot re-open it.
        assertEquals(mapOf(DEVICE to 2), store.profile.clears["read-image:1"])
        // A different level is a different key and keeps its own jackpot.
        assertEquals(REWARD_CURVE[0], store.preview(ExerciseId.READ_IMAGE, 2))
    }

    @Test
    fun `an award creates this device's earned key`() {
        // `bump(earned, device, n)` writes this device's slot — the counter's
        // PRESENCE is what records "this device played", which is what keeps the
        // per-device merge lossless (invariant 9).
        val store = makeStore()
        store.createChild("Léa")
        store.award(ExerciseId.FIRST_LETTER, 1, perfectRounds = 8, totalRounds = 8)
        assertEquals(REWARD_CURVE[0], store.profile.stars.earned[DEVICE])
    }

    @Test
    fun `two awards on the same device sum into one counter slot`() {
        val store = makeStore()
        store.createChild("Léa")
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        // One key, summed — not two keys, and not a stored running total.
        assertEquals(mapOf(DEVICE to REWARD_CURVE[0] + REWARD_CURVE[1]), store.profile.stars.earned)
        assertEquals(REWARD_CURVE[0] + REWARD_CURVE[1], store.profile.balance)
    }

    @Test
    fun `a device only ever increments its own counter slot`() {
        // The heart of invariant 9: nothing in this API can write another
        // device's key, so a merge is a per-key max and can never lose a star.
        val kv = InMemoryKVStore()
        val seeded = ChildProfile(
            id = "lea",
            name = "Léa",
            nameRev = Rev(NOW, "mum-phone"),
            touchedAt = NOW,
            profile = DEFAULT_PROFILE.copy(
                stars = StarCounters(earned = mapOf("mum-phone" to 6), spent = emptyMap()),
                clears = mapOf("read-image:1" to mapOf("mum-phone" to 1)),
            ),
        )
        ProfileStorage.saveRoster(Roster(listOf(seeded), "lea", emptyMap()), kv)

        val store = makeStore(kv)
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)

        val stars = store.profile.stars
        assertEquals(6, stars.earned["mum-phone"], "Mum's phone's slot is untouched")
        // Prior clears are read from the FOLD, so the other phone's clear counts
        // against the curve: this is the second clear, not another jackpot.
        assertEquals(REWARD_CURVE[1], stars.earned[DEVICE])
        assertEquals(mapOf("mum-phone" to 1, DEVICE to 1), store.profile.clears["read-image:1"])
        assertEquals(2, store.profile.ledger["read-image:1"])
    }

    @Test
    fun `award with no active child returns the points but writes nothing`() {
        // TS oddity, ported as-is: `updateActive` guards, `award` does not.
        val store = makeStore()
        val pts = store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        assertEquals(REWARD_CURVE[0], pts)
        assertTrue(store.children.isEmpty())
        assertEquals(0, store.profile.balance)
    }

    @Test
    fun `spend fails when unaffordable and succeeds otherwise`() {
        val store = makeStore()
        store.createChild("Léa")
        assertFalse(store.spend(5))
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8) // +10
        assertTrue(store.spend(5))
        assertEquals(REWARD_CURVE[0] - 5, store.profile.balance)
    }

    @Test
    fun `spending more than the balance writes nothing and does not throw`() {
        val store = makeStore()
        store.createChild("Léa")
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8) // +10
        assertFalse(store.spend(999))
        assertEquals(REWARD_CURVE[0], store.profile.balance)
        assertTrue(store.profile.stars.spent.isEmpty(), "a refused spend bumps nothing")
    }

    @Test
    fun `an overdrawn balance floors at zero rather than going negative`() {
        // Two phones offline, both see 10 stars, both buy an 8-star item: after
        // the merge Σearned = 10 and Σspent = 16. The child keeps BOTH items and
        // the balance floors at 0 — we never claw a purchase back from a
        // six-year-old to satisfy arithmetic, and nothing throws.
        val kv = InMemoryKVStore()
        val overdrawn = ChildProfile(
            id = "lea",
            name = "Léa",
            nameRev = Rev(NOW, DEVICE),
            touchedAt = NOW,
            profile = DEFAULT_PROFILE.copy(
                stars = StarCounters(
                    earned = mapOf(DEVICE to 10),
                    spent = mapOf(DEVICE to 8, "mum-phone" to 8),
                ),
            ),
        )
        ProfileStorage.saveRoster(Roster(listOf(overdrawn), "lea", emptyMap()), kv)

        val store = makeStore(kv)
        assertEquals(0, store.profile.balance)
        assertFalse(store.spend(1))
        assertFalse(store.buy(HORN))
        // The raw counters are untouched by the floor — only the fold clamps.
        assertEquals(16, store.profile.stars.spent.values.sum())
    }

    @Test
    fun `buy debits once then re-equips free when already owned`() {
        val store = makeStore()
        store.createChild("Léa")
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8) // +10
        assertTrue(store.buy(HORN))
        assertContains(store.profile.owned, HORN.id)
        assertEquals("#F0A", store.profile.config.colors["hornColor"])
        val afterFirst = store.profile.balance
        assertEquals(REWARD_CURVE[0] - HORN.cost, afterFirst)

        store.buy(HORN) // owned -> free re-equip
        assertEquals(afterFirst, store.profile.balance)
        assertEquals(listOf(HORN.id), store.profile.owned, "owning it twice is not a thing")
    }

    @Test
    fun `buy fails when unaffordable and writes nothing`() {
        val store = makeStore()
        store.createChild("Léa")
        assertFalse(store.buy(HORN))
        assertFalse(HORN.id in store.profile.owned)
        assertNull(store.profile.config.colors["hornColor"])
    }
}

/* -------------------------------------------------------------------------- */
/* Stamps. Every mutation touches the child, and every cosmetic LWW field takes */
/* a fresh Rev off the INJECTED clock — which is why the clock is injected.     */
/* -------------------------------------------------------------------------- */

class ProfileStoreStampTest {

    @Test
    fun `every mutation stamps touchedAt from the injected clock`() {
        val clock = MutableTimeSource(NOW)
        val store = makeStore(clock = clock)
        store.createChild("Léa")
        assertEquals(NOW, store.children[0].touchedAt)

        clock.advance(5_000L)
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        assertEquals(NOW + 5_000L, store.children[0].touchedAt)

        clock.advance(1_000L)
        store.spend(1)
        assertEquals(NOW + 6_000L, store.children[0].touchedAt)

        clock.advance(1_000L)
        val id = assertNotNull(store.activeId)
        store.renameChild(id, "Léo")
        assertEquals(NOW + 7_000L, store.children[0].touchedAt)
    }

    @Test
    fun `cosmetic last-write-wins fields take a fresh Rev off the same clock`() {
        val clock = MutableTimeSource(NOW)
        val store = makeStore(clock = clock)
        store.createChild("Léa")
        assertEquals(Rev(NOW, DEVICE), store.children[0].nameRev)

        clock.advance(1_000L)
        store.chooseSpecies(Species.FOX)
        assertEquals(Rev(NOW + 1_000L, DEVICE), store.profile.currentRev)

        clock.advance(1_000L)
        store.setConfig { it.copy(stage = 2) }
        assertEquals(Rev(NOW + 2_000L, DEVICE), store.profile.species.getValue(Species.FOX).rev)

        clock.advance(1_000L)
        val id = assertNotNull(store.activeId)
        store.renameChild(id, "Léo")
        assertEquals(Rev(NOW + 3_000L, DEVICE), store.children[0].nameRev)
    }

    @Test
    fun `a deleted child is tombstoned at the clock's now`() {
        val clock = MutableTimeSource(NOW)
        val kv = InMemoryKVStore()
        val store = makeStore(kv, clock)
        store.createChild("Léa")
        val id = assertNotNull(store.activeId)
        clock.advance(1_000L)
        store.deleteChild(id)

        assertTrue(store.children.isEmpty())
        assertNull(store.activeId)
        assertEquals(NOW + 1_000L, store.roster.removed[id])
        // Tombstone, not just a removal: it has to survive the round trip, or
        // the family's other device hands the child straight back on next merge.
        val raw = assertNotNull(kv.string(ProfileStorage.ROSTER_KEY))
        assertContains(raw, id)
        assertTrue((makeStore(kv).roster.removed[id] ?: 0L) > 0L)
    }
}

/* -------------------------------------------------------------------------- */
/* The mascot switch has to be non-destructive, and the stars have to ignore it.*/
/* -------------------------------------------------------------------------- */

class ProfileStoreMascotTest {

    @Test
    fun `keeps each species growth and look, stars stay global`() {
        val store = makeStore()
        store.createChild("Léa")
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8) // +10, global
        store.chooseSpecies(Species.UNICORN)
        store.buy(HORN) // the unicorn owns a horn colour
        store.setConfig { it.copy(stage = 3) }
        val starsAfterBuy = store.profile.balance

        // Switch to the fox: it is a fresh baby, but the stars are unchanged.
        store.chooseSpecies(Species.FOX)
        assertEquals(Species.FOX, store.profile.config.species)
        assertEquals(0, store.profile.config.stage)
        assertFalse(HORN.id in store.profile.owned)
        assertEquals(starsAfterBuy, store.profile.balance)

        // Switch back to the unicorn: everything is exactly as we left it.
        store.chooseSpecies(Species.UNICORN)
        assertEquals(3, store.profile.config.stage)
        assertEquals("#F0A", store.profile.config.colors["hornColor"])
        assertContains(store.profile.owned, HORN.id)
    }

    @Test
    fun `setConfig value overload replaces the current species config`() {
        val store = makeStore()
        store.createChild("Léa")
        store.chooseSpecies(Species.UNICORN)
        store.setConfig(store.profile.config.copy(stage = 7))
        assertEquals(7, store.profile.config.stage)
    }

    @Test
    fun `chooseSpecies flips chosen once and never back`() {
        val store = makeStore()
        store.createChild("Léa")
        assertFalse(store.profile.chosen)
        store.chooseSpecies(Species.DRAGON)
        assertTrue(store.profile.chosen)
        store.chooseSpecies(Species.CAT)
        assertTrue(store.profile.chosen)
    }
}

class ProfileStoreSiblingTest {

    @Test
    fun `each child keeps their own stars and progress`() {
        val store = makeStore()
        store.createChild("Léa")
        val lea = assertNotNull(store.activeId)
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8) // Léa: +10

        store.createChild("Tom") // Tom becomes active, fresh
        assertEquals(0, store.profile.balance)

        store.selectChild(lea)
        assertEquals(REWARD_CURVE[0], store.profile.balance)
    }
}

/* -------------------------------------------------------------------------- */
/* Upgrade path, at the STORE level. MigrationsTest already pins the pure       */
/* reshape; what these add is the part only the store can break — that a        */
/* migrated child's history still drives the economy, and that a migration      */
/* writes nothing until the first real mutation.                               */
/* -------------------------------------------------------------------------- */

class ProfileStoreMigrationTest {

    private fun seededV3(): Pair<InMemoryKVStore, ProfileStore> {
        val kv = InMemoryKVStore(mapOf(ProfileStorage.V3_KEY to v3RosterJson))
        return kv to makeStore(kv)
    }

    @Test
    fun `carries stars, clears, look and items across untouched`() {
        val (_, store) = seededV3()
        assertEquals(1, store.children.size)
        assertEquals("Léa", store.children[0].name)
        assertEquals("lea-v3", store.activeId)

        assertEquals(17, store.profile.balance)
        assertEquals(2, store.profile.ledger["read-image:1"])
        assertEquals(Species.FOX, store.profile.config.species)
        assertEquals(3, store.profile.config.stage)
        assertEquals("#F80", store.profile.config.colors["furColor"])
        assertContains(store.profile.owned, "fox.fur.orange")
    }

    @Test
    fun `keeps the reward curve where it was, no reopening the jackpot`() {
        val (_, store) = seededV3()
        assertEquals(rewardFor(2), store.preview(ExerciseId.READ_IMAGE, 1))
        val pts = store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        assertEquals(rewardFor(2), pts)
        assertEquals(17 + rewardFor(2), store.profile.balance)
    }

    @Test
    fun `leaves the v3 blob in place so a rollback still reads data`() {
        val (kv, _) = seededV3()
        assertNotNull(kv.string(ProfileStorage.V3_KEY))
    }

    @Test
    fun `initialRoster computes but does not save`() {
        // TS: `useState(initialRoster)` saves nothing until a write, so a v3
        // migration must not eagerly mint a v4 blob.
        val (kv, _) = seededV3()
        assertNull(kv.string(ProfileStorage.ROSTER_KEY))
    }

    @Test
    fun `migrated stars merge with another device instead of overwriting`() {
        val (_, store) = seededV3()
        val migrated = store.children[0].profile
        // Mum's phone migrated its own v3 blob: two genuinely separate
        // progressions that only meet now, so they sum rather than one replacing
        // the other.
        val mum = migrated.copy(stars = StarCounters(earned = mapOf("mum-phone" to 6), spent = emptyMap()))
        assertEquals(23, balanceOf(mergeProfile(migrated, mum).stars))
    }

    @Test
    fun `promotes the single legacy v1 mascot into its species slot with its stars`() {
        val kv = InMemoryKVStore(mapOf(ProfileStorage.V1_KEY to v1ProfileJson))
        val store = makeStore(kv)
        assertEquals(1, store.children.size)
        assertEquals(5, store.profile.balance)
        assertEquals(1, store.profile.ledger["first-letter:1"])
        assertEquals(Species.CAT, store.profile.config.species)
        assertEquals(1, store.profile.config.stage)
        assertContains(store.profile.owned, "cat.whiskers.long")
    }
}

class ProfileStorePersistenceTest {

    @Test
    fun `persists the roster and rehydrates a fresh store`() {
        val kv = InMemoryKVStore()
        val store = makeStore(kv)
        store.createChild("Léa")
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        store.chooseSpecies(Species.FOX)

        val reloaded = makeStore(kv)
        assertEquals(1, reloaded.children.size)
        assertNotNull(reloaded.activeId)
        assertTrue(reloaded.profile.chosen)
        assertEquals(Species.FOX, reloaded.profile.config.species)
        assertEquals(REWARD_CURVE[0], reloaded.profile.balance)
        // And what round-tripped is the COUNTER, not a total.
        assertEquals(mapOf(DEVICE to REWARD_CURVE[0]), reloaded.profile.stars.earned)
    }

    @Test
    fun `the persisted blob holds no balance and no ledger field`() {
        // Invariant 9, read off the bytes: the stored shape has counters only.
        // `ProfileView` — the thing that carries `balance` — is not serialisable
        // at all, so this can only regress by someone adding a field on purpose.
        val kv = InMemoryKVStore()
        val store = makeStore(kv)
        store.createChild("Léa")
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)

        val raw = assertNotNull(kv.string(ProfileStorage.ROSTER_KEY))
        assertFalse(raw.contains("\"balance\""), raw)
        assertFalse(raw.contains("\"ledger\""), raw)
        assertContains(raw, "\"earned\"")
        assertContains(raw, "\"clears\"")
    }

    @Test
    fun `the roster flow carries every commit`() {
        val store = makeStore()
        assertEquals(store.roster, store.rosterFlow.value)
        store.createChild("Léa")
        assertEquals(store.roster, store.rosterFlow.value)
        assertEquals(1, store.rosterFlow.value.children.size)
    }
}

/* -------------------------------------------------------------------------- */
/* The sync trigger — pull → merge → push on demand, NEVER on a write. The full */
/* transport semantics live in SyncClientTest; here: the store folds a merged   */
/* roster back in, persists it, and a missing or failing client no-ops.         */
/* -------------------------------------------------------------------------- */

/** A phone with no signal: every exchange throws, and nothing may break. */
private class FailingTransport : SyncTransport {
    override suspend fun exchange(payload: WireRoster): WireRoster = throw IOException("no signal")
}

class ProfileStoreSyncTest {

    @Test
    fun `syncNow folds the household into the roster and persists`() = runTest {
        val kv = InMemoryKVStore()
        // The other phone already uploaded Tom.
        val tom = ChildProfile(
            id = "tom",
            name = "Tom",
            nameRev = Rev(10L, "mum-phone"),
            touchedAt = 10L,
            profile = DEFAULT_PROFILE,
        )
        val server = StubSyncTransport(toWire(Roster(listOf(tom), null, emptyMap())))
        val store = makeStore(kv, sync = SyncClient(server))
        store.createChild("Léa")
        store.syncNow()

        assertContains(store.children.map { it.id }, "tom")
        // The never-seen sibling arrives as the placeholder, not a name.
        assertEquals("Enfant", store.children.first { it.id == "tom" }.name)
        // And the merged roster was committed to disk.
        assertContains(assertNotNull(kv.string(ProfileStorage.ROSTER_KEY)), "tom")
    }

    @Test
    fun `syncNow without a client is a no-op`() = runTest {
        val store = makeStore()
        store.createChild("Léa")
        val before = store.roster
        store.syncNow()
        assertEquals(before, store.roster)
    }

    @Test
    fun `a transport failure is swallowed and the device keeps playing alone`() = runTest {
        val kv = InMemoryKVStore()
        val store = makeStore(kv, sync = SyncClient(FailingTransport()))
        store.createChild("Léa")
        store.award(ExerciseId.READ_IMAGE, 1, perfectRounds = 0, totalRounds = 8)
        val before = store.roster

        store.syncNow()

        assertEquals(before, store.roster)
        assertEquals(REWARD_CURVE[0], store.profile.balance)
        // Still fully playable after the failure — money and stars never fail
        // closed on a dead network.
        assertTrue(store.spend(1))
    }
}
